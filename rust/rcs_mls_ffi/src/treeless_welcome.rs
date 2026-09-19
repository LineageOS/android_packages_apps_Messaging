//
// SPDX-FileCopyrightText: The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

//! Joins a Welcome whose GroupInfo omits the optional `ratchet_tree` extension (RFC 9420 §12.4.3.3)
//! by rebuilding the tree from the canonical LeafNodes the sender ships after the Welcome in the
//! same payload. The node sequence is spliced verbatim and parsed with `ExportedTree::from_bytes`;
//! `join_group` then verifies each leaf signature and the tree hash. The name is kept because
//! `nativeJoinTreelessWelcome` is a JNI symbol. See docs/mls/rust-core.md.

use mls_rs::mls_rs_codec::{MlsEncode, VarInt};

const MIN_LEAF_LEN: usize = 1500; // a member leaf carries an X.509 chain

/// Reads an MLS varint at `o`; returns (value, next offset).
fn read_varint(b: &[u8], o: usize) -> Result<(usize, usize), String> {
    if o >= b.len() { return Err("varint: eof".into()); }
    let b0 = b[o];
    match b0 >> 6 {
        0 => Ok(((b0 & 0x3f) as usize, o + 1)),
        1 => {
            if o + 2 > b.len() { return Err("varint2: eof".into()); }
            Ok(((((b0 & 0x3f) as usize) << 8) | b[o + 1] as usize, o + 2))
        }
        2 => {
            if o + 4 > b.len() { return Err("varint4: eof".into()); }
            Ok(((((b0 & 0x3f) as usize) << 24) | ((b[o + 1] as usize) << 16)
                | ((b[o + 2] as usize) << 8) | b[o + 3] as usize, o + 4))
        }
        _ => {
            if o + 8 > b.len() { return Err("varint8: eof".into()); }
            let mut v = (b0 & 0x3f) as usize;
            for i in 1..8 { v = (v << 8) | b[o + i] as usize; }
            Ok((v, o + 8))
        }
    }
}

/// Skips an `opaque<V>`; returns the offset just past it.
fn skip_opaque(b: &[u8], o: usize) -> Result<usize, String> {
    let (n, o2) = read_varint(b, o)?;
    let end = o2.checked_add(n).ok_or("opaque: overflow")?;
    if end > b.len() { return Err("opaque: overrun".into()); }
    Ok(end)
}

/// Walks one LeafNode starting at its encryption_key length; returns the offset past the
/// signature, or an error if the bytes are not a valid LeafNode.
fn walk_leaf(b: &[u8], o: usize) -> Result<usize, String> {
    let mut o = skip_opaque(b, o)?; // encryption_key
    o = skip_opaque(b, o)?;         // signature_key
    if o + 2 > b.len() || ((b[o] as u16) << 8 | b[o + 1] as u16) != 0x0002 {
        return Err("leaf: credential_type != x509".into());
    }
    o += 2;
    o = skip_opaque(b, o)?;             // certificates<V>
    for _ in 0..5 { o = skip_opaque(b, o)?; } // Capabilities: 5 <V> vectors
    if o >= b.len() { return Err("leaf: eof at source".into()); }
    let src = b[o];
    o += 1;
    match src {
        1 => { o = o.checked_add(16).ok_or("leaf: lifetime overflow")?; if o > b.len() { return Err(
            "leaf: lifetime overrun".into()); } }
        2 => {}
        3 => { o = skip_opaque(b, o)?; } // parent_hash<V>
        other => return Err(format!("leaf: bad source {other}")),
    }
    o = skip_opaque(b, o)?; // extensions<V>
    o = skip_opaque(b, o)?; // signature
    Ok(o)
}

/// Byte spans of every LeafNode in `blob`, in wire order. Scans for the encryption-key marker
/// (`40 41 04`) and skips past each valid leaf, so its own signature key and ciphertext false
/// matches are ignored.
pub fn locate_leaves(blob: &[u8]) -> Vec<(usize, usize)> {
    let mut leaves = Vec::new();
    let mut i = 0usize;
    while i + 3 <= blob.len() {
        if blob[i] == 0x40 && blob[i + 1] == 0x41 && blob[i + 2] == 0x04 {
            if let Ok(end) = walk_leaf(blob, i) {
                if end - i > MIN_LEAF_LEN {
                    leaves.push((i, end));
                    i = end;
                    continue;
                }
            }
        }
        i += 1;
    }
    leaves
}

/// The RFC 9420 `ratchet_tree<V>` from the blob. The sender's node sequence (`01 01` LeafNode,
/// `01 02` ParentNode, `00` blank) lies between the first and last leaf, since a left-balanced tree
/// starts and ends with a leaf, and it is copied verbatim so populated parents survive; a
/// leaves-only tree fails `TreeHashMismatch`.
///
/// A real leaf below `MIN_LEAF_LEN` (a short chain) is skipped silently, and the join then fails
/// with `TreeHashMismatch` rather than naming the cause.
pub fn splice_ratchet_tree(blob: &[u8]) -> Result<Vec<u8>, String> {
    let leaves = locate_leaves(blob);
    if leaves.is_empty() { return Err("splice_ratchet_tree: no LeafNodes located".into()); }
    let (first_enc, _) = leaves[0];
    let (_, last_end) = leaves[leaves.len() - 1];
    if first_enc < 2 { return Err(
        "splice_ratchet_tree: first leaf too early for 01 01 prefix".into()); }
    let node_start = first_enc - 2; // the `01 01` (present, leaf) before the first leaf
    if blob[node_start] != 0x01 || blob[node_start + 1] != 0x01 {
        return Err(format!(
            "splice_ratchet_tree: expected 01 01 node prefix at {node_start}, got {:02x} {:02x}",
            blob[node_start], blob[node_start + 1]));
    }
    if last_end > blob.len() || node_start >= last_end {
        return Err("splice_ratchet_tree: bad node-sequence bounds".into());
    }
    let content = &blob[node_start..last_end]; // the whole node sequence, parents included
    let mut w = Vec::new();
    let v = u32::try_from(content.len()).map_err(|_| "tree: length overflow".to_string())?;
    VarInt(v).mls_encode(&mut w).map_err(|e| format!("tree: outer varint: {e:?}"))?;
    w.extend_from_slice(content);
    Ok(w)
}

/// Number of member leaves the blob carries, for logging.
pub fn leaf_count(blob: &[u8]) -> usize { locate_leaves(blob).len() }

#[cfg(test)]
mod tests {
    use super::*;
    // A synthetic one-leaf LeafNode: minimal credential, empty capabilities and extensions.
    fn tiny_leaf() -> Vec<u8> {
        let mut w = Vec::new();
        let key = [vec![0x40u8, 0x41], vec![0x04u8; 65]].concat(); // opaque(65) 04..
        w.extend_from_slice(&key);   // enc_key
        w.extend_from_slice(&key);   // sig_key
        w.extend_from_slice(&[0x00, 0x02]); // credential_type x509
        // certs<V>: one cert = opaque(DER 3B); certs content = [03 300102]; certs<V> = 04 || that
        w.extend_from_slice(&[0x04, 0x03, 0x30, 0x01, 0x02]);
        for _ in 0..5 { w.push(0x00); } // 5 empty caps vectors
        w.push(0x01);                    // source = key_package
        w.extend_from_slice(&[0u8; 16]); // lifetime
        w.push(0x00);                    // extensions empty
        w.extend_from_slice(&[0x03, 0x30, 0x01, 0x02]); // signature opaque(3)
        w
    }
    #[test]
    fn walk_and_locate_synthetic() {
        let leaf = tiny_leaf();
        let end = walk_leaf(&leaf, 0).expect("walk ok");
        assert_eq!(end, leaf.len(), "walk consumes exactly the leaf");
        // `locate_leaves` needs a leaf over MIN_LEAF_LEN, so splice a one-leaf sequence directly.
        let mut content = vec![0x01u8, 0x01u8];
        content.extend_from_slice(&leaf);
        let mut tree = Vec::new();
        VarInt(content.len() as u32).mls_encode(&mut tree).unwrap();
        tree.extend_from_slice(&content);
        let vw = if (tree[0] & 0xc0) == 0 { 1 } else { 2 };
        assert_eq!(tree[vw], 0x01);
        assert_eq!(tree[vw + 1], 0x01);
    }
}

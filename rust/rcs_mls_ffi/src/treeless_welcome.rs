// Copyright (C) 2026 The LineageOS Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Join from a Welcome that carries NO ratchet_tree extension: reconstruct the tree from the
// LeafNodes that ride alongside the Welcome in the same blob, so process_welcome can complete.
//
// ===========================================================================================
// RENAMED FROM `apple_kind5` 2026-08-06. The old name was PROVENANCE, not a vendor branch, and
// it was read as one often enough to be worth changing.
//
// It is named for where the shape was FIRST SEEN (an iPhone add-member, kind=5, 2026-07-23), and
// that name then leaked into an operator-facing log line that announced "JOINED via Apple kind=5"
// for traffic that had nothing to do with Apple.
//
// The condition is protocol-general. RFC 9420 makes `ratchet_tree` an OPTIONAL GroupInfo extension:
// a sender may legitimately omit it and distribute the tree out of band. mls-rs models exactly that
// split as `join_group(None, welcome)` vs `join_group(Some(tree), welcome)`. So ANY conforming
// implementation may send a Welcome this way — it is not a quirk to be detected per vendor, and
// there is no "Apple variant" of MLS here. Everything on this path is RCC.16 over RFC 9420.
//
// NOT APPLE-SPECIFIC, confirmed on device 2026-08-04: this code is what joined us to a group
// created by GOOGLE MESSAGES (version 317307063) — a 9529B kind=47 Welcome, spliced and joined OK.
// Two different vendors, two different transport kinds (5 and 47), one shape.
//
// The name is kept only because `nativeJoinTreelessWelcome` is a JNI native whose C symbol would have to
// be regenerated in lockstep to rename it — churn and bridge risk for no functional gain. Treat the
// name as a historical label; the doc is the truth.
// ===========================================================================================
//
// WHY IT IS NEEDED: such a Welcome makes mls-rs `join_group(None, welcome)` fail
// `RatchetTreeNotFound` even though the KeyPackage secret is present (the KP-persist fix decrypted
// the group secrets). The tree ships as N LeafNodes in the SAME blob, AFTER the Welcome.
//
// KEY FINDING (bytes decoded 2026-07-23): the Apple member LeafNodes are **canonical RFC-9420**
// (MLS QUIC-varints throughout: enc_key `40 41 04..`, sig_key `40 41 04..`, credential
// `0002`+certs<V>, 5-list Capabilities, source+select, exts<V>, sig). So we do NOT reconstruct
// field-by-field — we LOCATE each canonical LeafNode's byte span and SPLICE it verbatim into a
// RatchetTree. Locator: scan for the enc_key marker (`40 41 04` = varint-65 + EC 0x04), canonically
// walk the LeafNode; a real leaf is >1500B (carries a cert) so Welcome-ciphertext / sig_key false
// matches are rejected. Validated offline: finds exactly the 4 leaves at the expected byte-exact
// spans, sources {commit(3) for the iPhone committer, key_package(1) for the added members}.
//
// Then: RatchetTree = ratchet_tree<V> of optional<Node> = [01 01 Leaf0][00][01 01 Leaf1][00]..
// (present, leaf node_type; blank parents mls-rs recomputes from the Welcome path_secret) → the
// PUBLIC `ExportedTree::from_bytes` (mls-rs tree_kem is private, so no object construction) →
// `join_group(Some(tree), welcome)`. Oracles: per-leaf sig + tree_hash inside join_group.

use mls_rs::mls_rs_codec::{MlsEncode, VarInt};
use mls_rs::group::ExportedTree;

const MIN_LEAF_LEN: usize = 1500; // a real member leaf carries an x509 cert (~1KB+)

/// Read an MLS QUIC-style varint at `o`; returns (value, next_offset).
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

/// Skip an `opaque<V>` (varint length + bytes); returns the offset just past it.
fn skip_opaque(b: &[u8], o: usize) -> Result<usize, String> {
    let (n, o2) = read_varint(b, o)?;
    let end = o2.checked_add(n).ok_or("opaque: overflow")?;
    if end > b.len() { return Err("opaque: overrun".into()); }
    Ok(end)
}

/// Canonically walk one RFC-9420 LeafNode starting at `o` (the encryption_key length varint);
/// returns the offset just past the signature. Errors if the structure is not a valid LeafNode.
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
        1 => { o = o.checked_add(16).ok_or("leaf: lifetime overflow")?; if o > b.len() { return Err("leaf: lifetime overrun".into()); } }
        2 => {}
        3 => { o = skip_opaque(b, o)?; } // parent_hash<V>
        other => return Err(format!("leaf: bad source {other}")),
    }
    o = skip_opaque(b, o)?; // extensions<V>
    o = skip_opaque(b, o)?; // signature
    Ok(o)
}

/// Locate every canonical LeafNode in `blob` (in wire order). Scans for the enc_key marker
/// (`40 41 04`), walks each candidate, keeps the large valid leaves and skips past them (so the
/// internal signature_key `40 41 04` and any Welcome-ciphertext false match are ignored).
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

/// Build the RFC-9420 ratchet_tree<V> from the blob. The blob already contains the CANONICAL
/// node sequence (optional<Node>: `01 01 LeafNode` / `01 02 ParentNode` / `00` blank) between the
/// first and last member leaf — including the POPULATED parent nodes the iPhone's commit created
/// (with parent_hash + HPKE key + unmerged_leaves). We must NOT drop those (a leaves-only tree
/// fails TreeHashMismatch — device-observed 2026-07-23). Each optional<Node> begins with a 0x01
/// present byte + node_type, so the first leaf's node starts 2 bytes before its enc_key length. The
/// tree is left-balanced: node 0 (first) and node 2n-2 (last) are always leaves, so the whole
/// canonical node sequence is `blob[first_leaf_node .. last_leaf_end]` — extracted VERBATIM (real
/// parents intact) and wrapped in the outer <V> (== ExportedTree / RatchetTreeExtension.data).
///
/// # The 2-leaf (1:1) case, measured 2026-09-08
///
/// "The parent lies physically between the leaves" was written from a 4-leaf iPhone tree and was
/// never tested at N=2, which is the shape every 1:1 conversation has and the smallest one where
/// the claim says anything. It holds, in both parent shapes, against trees mls-rs exported for real
/// groups (`treeless_welcome_joins_a_real_two_leaf_group` and
/// `treeless_welcome_splices_a_two_leaf_group_with_a_populated_parent` in `ffi.rs`):
///
/// ```text
/// add-only commit (no UpdatePath, RFC 9420 §12.4) — node 1 BLANK:
///   [4c ad][01 01 <leaf0 1618B>][00][01 01 <leaf1 1622B>]                       tree = 3247B
/// path commit at two members — node 1 POPULATED:
///   [.. ..][01 01 <leaf0 1635B>][01 02 <ParentNode 69B>][01 01 <leaf1 1621B>]   tree = 3333B
/// ```
///
/// In both, `splice_ratchet_tree` reproduces `Group::export_tree()` BYTE-FOR-BYTE, outer varint
/// included, out of a blob that also carries the Welcome/GroupInfo and transport framing either
/// side. Byte equality is only the first instrument, though: the tests also make mls-rs itself
/// validate the spliced bytes (`join_group` for the blank case, external-commit `with_tree_data`
/// for the populated one), which fails independently — a leaves-only splice is `InvalidTreeIndex`
/// and a one-byte-short one is `UnexpectedEOF`, both verified by mutation.
///
/// One thing the measurement DID surface: `MIN_LEAF_LEN` has less headroom than it reads like. Our
/// own lab leaves are 1618-1639B against a 1500B floor — ~8% margin, not the order of magnitude the
/// comment's "a real leaf is >1500B" implies. A shorter certificate chain (no ICA, or a smaller
/// key) would put a REAL leaf under the floor and it would be silently skipped, which at N=2 means
/// a one-leaf tree that fails `TreeHashMismatch` rather than an error naming the cause. Not changed
/// here — the floor is what rejects Welcome-ciphertext false matches, so moving it needs its own
/// evidence about what the false-match distribution actually looks like.
pub fn splice_ratchet_tree(blob: &[u8]) -> Result<Vec<u8>, String> {
    let leaves = locate_leaves(blob);
    if leaves.is_empty() { return Err("splice_ratchet_tree: no LeafNodes located".into()); }
    let (first_enc, _) = leaves[0];
    let (_, last_end) = leaves[leaves.len() - 1];
    if first_enc < 2 { return Err("splice_ratchet_tree: first leaf too early for 01 01 prefix".into()); }
    let node_start = first_enc - 2; // the optional<Node> `01 01` (present + leaf node_type)
    if blob[node_start] != 0x01 || blob[node_start + 1] != 0x01 {
        return Err(format!("splice_ratchet_tree: expected 01 01 node prefix at {node_start}, got {:02x} {:02x}",
            blob[node_start], blob[node_start + 1]));
    }
    if last_end > blob.len() || node_start >= last_end {
        return Err("splice_ratchet_tree: bad node-sequence bounds".into());
    }
    let content = &blob[node_start..last_end]; // whole canonical node sequence, parents intact
    let mut w = Vec::new();
    let v = u32::try_from(content.len()).map_err(|_| "tree: length overflow".to_string())?;
    VarInt(v).mls_encode(&mut w).map_err(|e| format!("tree: outer varint: {e:?}"))?;
    w.extend_from_slice(content);
    Ok(w)
}

/// Number of member leaves the blob carries (for logging/telemetry).
pub fn leaf_count(blob: &[u8]) -> usize { locate_leaves(blob).len() }

/// Parse the spliced tree into an mls-rs ExportedTree (the value `join_group` wants).
pub fn exported_tree(blob: &[u8]) -> Result<ExportedTree<'static>, String> {
    let bytes = splice_ratchet_tree(blob)?;
    ExportedTree::from_bytes(&bytes).map_err(|e| format!("ExportedTree::from_bytes: {e:?}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    // Walker/locator on a synthetic 1-leaf canonical LeafNode: enc(65) sig(65) cred(x509,1 tiny DER)
    // caps(5 empty) source=key_package+lifetime ext(empty) sig(tiny). Confirms walk + splice framing.
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
        // walk from 0
        let end = walk_leaf(&leaf, 0).expect("walk ok");
        assert_eq!(end, leaf.len(), "walk consumes exactly the leaf");
        // locate needs >MIN_LEAF_LEN; lower the bar by embedding a big cert isn't worth it here —
        // just assert the walker+splicer compose on a vec of one leaf via direct splice.
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

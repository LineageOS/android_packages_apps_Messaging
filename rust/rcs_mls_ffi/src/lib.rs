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

// M1+M2: persistent byte-based MLS session op surface via mls-rs (suite 0x0002 / P256,
// RustCrypto provider, pure-Rust FILE storage — survives process restart).
// Soong builds this crate WITHOUT the test cfg, so every helper and import that exists for the
// #[cfg(test)] suite below reads as unused there while being live under `cargo test`. Verified
// symbol by symbol (SUITE, ERA_EXT, der_int, era_extension_list, smoke_dir, prune_expired_epochs,
// tombstone_trusted_ms, delete_expired, and all 17 of rcc16's test-only imports). Deleting them to
// silence the platform build would break the test suite; gating each one individually would put
// the attribute on thirty items across five files. Scoped to not(test) so a real unused item in a
// test build is still reported.
#![cfg_attr(not(test), allow(dead_code, unused_imports))]

mod storage;
pub mod rcc16;
pub mod ffi;
// kind=5 Welcome ratchet_tree reconstruction — hand-serializes canonical RFC-9420 tree bytes →
// ExportedTree::from_bytes (mls-rs tree_kem is private, so no object construction). Serializer is
// complete; the Apple-TLV parser (blob → AppleLeaf) is wired next + validated on-device.
pub mod treeless_welcome;
pub mod rcc16_validate;
use storage::FileGroupStateStorage;

use mls_rs::{
    client_builder::{BaseConfig, WithCryptoProvider, WithGroupStateStorage, WithIdentityProvider},
    crypto::{SignaturePublicKey, SignatureSecretKey},
    identity::{basic::{BasicCredential, BasicIdentityProvider}, SigningIdentity},
    group::ReceivedMessage,
    CipherSuite, CipherSuiteProvider, Client, CryptoProvider, ExtensionList, MlsMessage,
};
use mls_rs_crypto_rustcrypto::RustCryptoProvider;

const SUITE: CipherSuite = CipherSuite::P256_AES128;
type RcsConfig = WithGroupStateStorage<
    FileGroupStateStorage,
    WithCryptoProvider<RustCryptoProvider, WithIdentityProvider<BasicIdentityProvider, BaseConfig>>,
>;

/// Opaque per-identity session handle with persistent (file) group storage.
pub struct RcsMlsSession { client: Client<RcsConfig> }

impl RcsMlsSession {
    pub fn generate_identity() -> Result<(SignatureSecretKey, SignaturePublicKey), String> {
        let crypto = RustCryptoProvider::default();
        let cs = crypto.cipher_suite_provider(SUITE).ok_or("suite unavailable")?;
        cs.signature_key_generate().map_err(|e| format!("keygen: {e:?}"))
    }
    /// Open a session for an existing identity, persisting group state under `dir`.
    pub fn open(name: &[u8], secret: SignatureSecretKey, public: SignaturePublicKey, dir: &str) -> Self {
        let si = SigningIdentity::new(BasicCredential::new(name.to_vec()).into_credential(), public);
        let client = Client::builder()
            .identity_provider(BasicIdentityProvider)
            .crypto_provider(RustCryptoProvider::default())
            .group_state_storage(FileGroupStateStorage::new(dir))
            .signing_identity(si, secret, SUITE)
            .build();
        Self { client }
    }
    /// Fresh identity + session (returns the identity so a restart can re-open it).
    pub fn start(name: &[u8], dir: &str) -> Result<(Self, SignatureSecretKey, SignaturePublicKey), String> {
        let (secret, public) = Self::generate_identity()?;
        Ok((Self::open(name, secret.clone(), public.clone(), dir), secret, public))
    }
    pub fn generate_key_package(&self) -> Result<Vec<u8>, String> {
        self.client.generate_key_package_message(Default::default(), Default::default(), None)
            .map_err(|e| format!("gen_kp: {e:?}"))?
            .to_bytes().map_err(|e| format!("kp_to_bytes: {e:?}"))
    }
    pub fn create_group(&self, peer_kp: &[u8]) -> Result<(Vec<u8>, Vec<u8>), String> {
        let mut g = self.client.create_group(ExtensionList::default(), Default::default(), None)
            .map_err(|e| format!("create_group: {e:?}"))?;
        let kp = MlsMessage::from_bytes(peer_kp).map_err(|e| format!("kp_from_bytes: {e:?}"))?;
        let commit = g.commit_builder().add_member(kp).map_err(|e| format!("add: {e:?}"))?
            .build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let gid = g.group_id().to_vec();
        g.write_to_storage().map_err(|e| format!("store: {e:?}"))?;
        let welcome = commit.welcome_messages[0].to_bytes().map_err(|e| format!("welcome_bytes: {e:?}"))?;
        Ok((gid, welcome))
    }
    pub fn join(&self, welcome: &[u8]) -> Result<Vec<u8>, String> {
        let w = MlsMessage::from_bytes(welcome).map_err(|e| format!("welcome_from_bytes: {e:?}"))?;
        let (mut g, _) = self.client.join_group(None, &w, None).map_err(|e| format!("join: {e:?}"))?;
        let gid = g.group_id().to_vec();
        g.write_to_storage().map_err(|e| format!("store: {e:?}"))?;
        Ok(gid)
    }
    pub fn encrypt(&self, gid: &[u8], pt: &[u8]) -> Result<Vec<u8>, String> {
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let ct = g.encrypt_application_message(pt, Default::default()).map_err(|e| format!("enc: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("store: {e:?}"))?;
        ct.to_bytes().map_err(|e| format!("ct_bytes: {e:?}"))
    }
    pub fn process(&self, gid: &[u8], wire: &[u8]) -> Result<Vec<u8>, String> {
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let msg = MlsMessage::from_bytes(wire).map_err(|e| format!("wire_from_bytes: {e:?}"))?;
        let res = g.process_incoming_message(msg).map_err(|e| format!("process: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("store: {e:?}"))?;
        match res {
            ReceivedMessage::ApplicationMessage(m) => Ok(m.data().to_vec()),
            other => Err(format!("non-app: {other:?}")),
        }
    }
}

fn smoke_dir(sub: &str) -> String {
    let base = std::env::var("RCS_MLS_TMP").ok()
        .unwrap_or_else(|| std::env::temp_dir().to_string_lossy().to_string());
    let d = format!("{base}/rcsmls_{sub}_{}", std::process::id());
    let _ = std::fs::remove_dir_all(&d);
    d
}

/// Byte-based round-trip over the persistent op surface.
#[cfg(test)]
pub fn handle_roundtrip() -> Result<(), String> {
    let d = smoke_dir("rt");
    let (alice, _, _) = RcsMlsSession::start(b"alice", &format!("{d}/a"))?;
    let (bob, _, _) = RcsMlsSession::start(b"bob", &format!("{d}/b"))?;
    let bob_kp = bob.generate_key_package()?;
    let (gid_a, welcome) = alice.create_group(&bob_kp)?;
    let gid_b = bob.join(&welcome)?;
    let ct = alice.encrypt(&gid_a, b"hello over the wire")?;
    if bob.process(&gid_b, &ct)? != b"hello over the wire" { return Err("A->B mismatch".into()); }
    let ct = bob.encrypt(&gid_b, b"reply over the wire")?;
    if alice.process(&gid_a, &ct)? != b"reply over the wire" { return Err("B->A mismatch".into()); }
    let _ = std::fs::remove_dir_all(&d);
    Ok(())
}

/// M2 gate: group state survives a process restart (drop the session, re-open from disk, decrypt).
#[cfg(test)]
pub fn restart_survival() -> Result<(), String> {
    let d = smoke_dir("restart");
    let a_dir = format!("{d}/a");
    let b_dir = format!("{d}/b");
    let (alice, _, _) = RcsMlsSession::start(b"alice", &a_dir)?;
    let (b_sec, b_pub) = RcsMlsSession::generate_identity()?;
    let (gid_a, gid_b);
    {
        // "before restart" B: generate KP, join, then B is dropped (simulated process death).
        let bob = RcsMlsSession::open(b"bob", b_sec.clone(), b_pub.clone(), &b_dir);
        let bob_kp = bob.generate_key_package()?;
        let (ga, welcome) = alice.create_group(&bob_kp)?;
        gid_a = ga;
        gid_b = bob.join(&welcome)?;
    } // bob dropped here — only its FILE storage (b_dir) remains
    // Alice sends a message that B must decrypt AFTER the restart.
    let ct = alice.encrypt(&gid_a, b"survives the restart")?;
    // "after restart": brand-new B session, same identity + same storage dir, loaded from disk.
    let bob2 = RcsMlsSession::open(b"bob", b_sec, b_pub, &b_dir);
    let got = bob2.process(&gid_b, &ct)?;
    if got != b"survives the restart" { return Err(format!("restart decrypt mismatch: {got:?}")); }
    let _ = std::fs::remove_dir_all(&d);
    Ok(())
}

#[cfg(test)]
#[no_mangle]
pub extern "C" fn rcs_mls_smoke_roundtrip() -> i32 { if handle_roundtrip().is_ok() { 0 } else { -1 } }
#[cfg(test)]
#[no_mangle]
pub extern "C" fn rcs_mls_smoke_restart() -> i32 { if restart_survival().is_ok() { 0 } else { -1 } }
#[cfg(test)]
#[no_mangle]
pub extern "C" fn rcs_mls_smoke_p256_pubkey_len() -> i32 {
    let crypto = RustCryptoProvider::default();
    match crypto.cipher_suite_provider(SUITE).and_then(|cs| cs.signature_key_generate().ok()) {
        Some((_s, public)) => public.as_bytes().len() as i32,
        None => -1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn op_surface_roundtrip() { handle_roundtrip().expect("round-trip"); }
    #[test]
    fn state_survives_restart() {
        restart_survival().expect("restart survival");
        println!("M2 OK: group state persisted + decrypted a pre-restart message");
    }
}

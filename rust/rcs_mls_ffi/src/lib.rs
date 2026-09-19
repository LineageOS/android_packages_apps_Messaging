//
// SPDX-FileCopyrightText: The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

//! Persistent MLS sessions over mls-rs (cipher suite 0x0002, RustCrypto, file storage) and the
//! C ABI the JNI bridge calls. See docs/mls/rust-core.md.
// Soong builds without cfg(test), where the test-only helpers and imports read as unused.
#![cfg_attr(not(test), allow(dead_code, unused_imports))]

mod storage;
pub mod rcc16;
pub mod ffi;
pub mod treeless_welcome;
pub mod rcc16_validate;

// The rest of this file is a minimal session the crate's own tests drive; none of it ships.
#[cfg(test)]
use storage::FileGroupStateStorage;

#[cfg(test)]
use mls_rs::{
    client_builder::{BaseConfig, WithCryptoProvider, WithGroupStateStorage, WithIdentityProvider},
    crypto::{SignaturePublicKey, SignatureSecretKey},
    identity::{basic::{BasicCredential, BasicIdentityProvider}, SigningIdentity},
    group::ReceivedMessage,
    CipherSuite, CipherSuiteProvider, Client, CryptoProvider, ExtensionList, MlsMessage,
};
#[cfg(test)]
use mls_rs_crypto_rustcrypto::RustCryptoProvider;

#[cfg(test)]
const SUITE: CipherSuite = CipherSuite::P256_AES128;
#[cfg(test)]
type RcsConfig = WithGroupStateStorage<
    FileGroupStateStorage,
    WithCryptoProvider<RustCryptoProvider, WithIdentityProvider<BasicIdentityProvider, BaseConfig>>,
>;

/// Minimal basic-credential session with file storage, used by the crate's own tests.
#[cfg(test)]
pub struct RcsMlsSession { client: Client<RcsConfig> }

#[cfg(test)]
impl RcsMlsSession {
    pub fn generate_identity() -> Result<(SignatureSecretKey, SignaturePublicKey), String> {
        let crypto = RustCryptoProvider::default();
        let cs = crypto.cipher_suite_provider(SUITE).ok_or("suite unavailable")?;
        cs.signature_key_generate().map_err(|e| format!("keygen: {e:?}"))
    }
    /// Open a session for an existing identity, persisting group state under `dir`.
    pub fn open(name: &[u8], secret: SignatureSecretKey, public: SignaturePublicKey,
            dir: &str) -> Self {
        let si =
            SigningIdentity::new(BasicCredential::new(name.to_vec()).into_credential(), public);
        let client = Client::builder()
            .identity_provider(BasicIdentityProvider)
            .crypto_provider(RustCryptoProvider::default())
            .group_state_storage(FileGroupStateStorage::new(dir))
            .signing_identity(si, secret, SUITE)
            .build();
        Self { client }
    }
    /// Creates a fresh identity and session; returns the keys so a restart can re-open it.
    pub fn start(name: &[u8],
            dir: &str) -> Result<(Self, SignatureSecretKey, SignaturePublicKey), String> {
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
        let welcome =
            commit.welcome_messages[0].to_bytes().map_err(|e| format!("welcome_bytes: {e:?}"))?;
        Ok((gid, welcome))
    }
    pub fn join(&self, welcome: &[u8]) -> Result<Vec<u8>, String> {
        let w = MlsMessage::from_bytes(welcome).map_err(|e| format!("welcome_from_bytes: {e:?}"))?;
        let (mut g, _) =
            self.client.join_group(None, &w, None).map_err(|e| format!("join: {e:?}"))?;
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

#[cfg(test)]
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

/// Group state survives a process restart: drop the session, re-open from disk, decrypt.
#[cfg(test)]
pub fn restart_survival() -> Result<(), String> {
    let d = smoke_dir("restart");
    let a_dir = format!("{d}/a");
    let b_dir = format!("{d}/b");
    let (alice, _, _) = RcsMlsSession::start(b"alice", &a_dir)?;
    let (b_sec, b_pub) = RcsMlsSession::generate_identity()?;
    let (gid_a, gid_b);
    {
        // Generate a KeyPackage and join, then drop the session to simulate process death.
        let bob = RcsMlsSession::open(b"bob", b_sec.clone(), b_pub.clone(), &b_dir);
        let bob_kp = bob.generate_key_package()?;
        let (ga, welcome) = alice.create_group(&bob_kp)?;
        gid_a = ga;
        gid_b = bob.join(&welcome)?;
    }    let ct = alice.encrypt(&gid_a, b"survives the restart")?;
    // A new session with the same identity and storage directory, loaded from disk.
    let bob2 = RcsMlsSession::open(b"bob", b_sec, b_pub, &b_dir);
    let got = bob2.process(&gid_b, &ct)?;
    if got != b"survives the restart" { return Err(format!("restart decrypt mismatch: {got:?}")); }
    let _ = std::fs::remove_dir_all(&d);
    Ok(())
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

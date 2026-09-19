//
// SPDX-FileCopyrightText: The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

//! The production session (`ProdSession`: X.509 credentials, file storage) and every `rcs_mls_*`
//! export the JNI bridge calls. See docs/mls/rust-core.md.
use core::time::Duration;
use std::os::raw::c_char;
use std::ffi::CStr;
use std::sync::atomic::{AtomicBool, Ordering};

/// Engine setting bit: KeyPackages live a fixed 365 days from the certificate's notBefore (Google's
/// KDS). Clear: within the certificate, RCC.16 §5.1. See `kp_lifetime_window`.
pub const SETTING_KP_FIXED_365_DAYS: u8 = 0x01;
/// Engine setting bit: peer certificates are judged deployment-tolerant (`Rcc16Validator.strict`
/// off). Clear: RCC.16 strict.
pub const SETTING_PEER_CERT_TOLERANT: u8 = 0x02;

/// The two settings, set by `rcs_mls_set_engine_settings` before `session_start` and read only
/// while a session is built. Both default to the RCC.16 setting. Process-global, and one process
/// runs sessions of both kinds, so the host serialises session starts
/// (`OpenMlsEngine.startSession`).
static KP_FIXED_365_DAYS: AtomicBool = AtomicBool::new(false);
static PEER_CERT_TOLERANT: AtomicBool = AtomicBool::new(false);

/// `(kp_fixed_365_days, peer_cert_tolerant)` from a flags byte; unknown bits are ignored.
fn decode_settings(flags: u8) -> (bool, bool) {
    (flags & SETTING_KP_FIXED_365_DAYS != 0, flags & SETTING_PEER_CERT_TOLERANT != 0)
}

/// Sets both engine settings from a flags byte (`SETTING_*`); unknown bits are ignored.
#[no_mangle]
pub extern "C" fn rcs_mls_set_engine_settings(flags: u8) {
    let (kp, peer) = decode_settings(flags);
    KP_FIXED_365_DAYS.store(kp, Ordering::Relaxed);
    PEER_CERT_TOLERANT.store(peer, Ordering::Relaxed);
}

#[inline]
fn kp_fixed_365_days() -> bool { KP_FIXED_365_DAYS.load(Ordering::Relaxed) }
#[inline]
fn peer_cert_tolerant() -> bool { PEER_CERT_TOLERANT.load(Ordering::Relaxed) }

/// Escape hatch: true makes a failed `.4` proof-of-possession a warning instead of rejecting the
/// peer's certificate. Off by default; a runtime lever so an unknown peer's certificates can be
/// accepted without a rebuild.
#[no_mangle]
pub extern "C" fn rcs_mls_set_pop_lenient(on: u8) {
    POP_LENIENT.store(on != 0, Ordering::Relaxed);
}
static POP_LENIENT: AtomicBool = AtomicBool::new(false);

#[inline]
fn pop_lenient() -> bool { POP_LENIENT.load(Ordering::Relaxed) }

/// Announces the RCC.16 revision the transport speaks (30 = v3.0, the default; 40 = v4.0) and
/// returns the revision in effect, so a refused value is visible. Independent of the KeyPackage
/// lifetime and peer-certificate settings; see `rcc16::set_rcc16_version`.
#[no_mangle]
pub extern "C" fn rcs_mls_set_rcc16_version(v: u8) -> u8 {
    crate::rcc16::set_rcc16_version(v)
}
use mls_rs::{
    client_builder::{BaseConfig, WithCryptoProvider, WithGroupStateStorage, WithIdentityProvider,
        WithKeyPackageRepo, WithMlsRules},
    group::{proposal::ProposalType, ReceivedMessage},
    mls_rules::{CommitOptions, DefaultMlsRules, EncryptionOptions},
    client_builder::PaddingMode,
    CipherSuite, Client, Group, MlsMessage,
    group::proposal::CustomProposal, group::Sender,
};
use mls_rs::group::ExportedTree;
use mls_rs_core::{crypto::{SignaturePublicKey, SignatureSecretKey},
    identity::{CredentialType, IdentityProvider, MemberValidationContext, SigningIdentity},
    extension::{Extension, ExtensionType, ExtensionList as CoreExtensionList},
    time::MlsTime};

// The key directory stores the raw RFC 9420 KeyPackage, not the MLSMessage wrapping mls-rs
// serialises; a claimed package is re-wrapped with these 4 bytes, version mls10 and wire format
// mls_key_package, before add_member (RFC 9420 §6).
const MLS_KEYPACKAGE_MSG_PREFIX: [u8; 4] = [0x00, 0x01, 0x00, 0x05];
fn wrap_key_package(raw_kp: &[u8]) -> Vec<u8> {
    let mut w = Vec::with_capacity(4 + raw_kp.len());
    w.extend_from_slice(&MLS_KEYPACKAGE_MSG_PREFIX);
    w.extend_from_slice(raw_kp);
    w
}

// ---- Peer KeyPackage: advertised cipher suites ----
// A hand parse, because mls-rs keeps `KeyPackage.leaf_node` crate-private. It mirrors
// `treeless_welcome::walk_leaf`, which is the reference if the two disagree. RFC 9420 §10, §7.2:
//   KeyPackage   = version(u16) cipher_suite(u16) init_key<V> LeafNode extensions<V> signature<V>
//   LeafNode     = encryption_key<V> signature_key<V> Credential Capabilities ...
//   Credential   = credential_type(u16) then one <V> field for basic(1) / x509(2)
//   Capabilities = versions<V> cipher_suites<V> extensions<V> proposals<V> credentials<V>

/// Reads an MLS varint at `o`; returns (value, next offset).
fn kp_varint(b: &[u8], o: usize) -> Result<(usize, usize), String> {
    if o >= b.len() { return Err("kp varint: eof".into()); }
    let b0 = b[o];
    match b0 >> 6 {
        0 => Ok(((b0 & 0x3f) as usize, o + 1)),
        1 => {
            if o + 2 > b.len() { return Err("kp varint2: eof".into()); }
            Ok(((((b0 & 0x3f) as usize) << 8) | b[o + 1] as usize, o + 2))
        }
        2 => {
            if o + 4 > b.len() { return Err("kp varint4: eof".into()); }
            Ok(((((b0 & 0x3f) as usize) << 24) | ((b[o + 1] as usize) << 16)
                | ((b[o + 2] as usize) << 8) | b[o + 3] as usize, o + 4))
        }
        _ => Err("kp varint8: a KeyPackage field is never 2^62 bytes".into()),
    }
}

/// Skips one `opaque<V>`; returns the offset just past it.
fn kp_skip_v(b: &[u8], o: usize) -> Result<usize, String> {
    let (n, o2) = kp_varint(b, o)?;
    let end = o2.checked_add(n).ok_or("kp <V>: overflow")?;
    if end > b.len() { return Err("kp <V>: overrun".into()); }
    Ok(end)
}

/// The cipher suites a KeyPackage's leaf advertises (RFC 9420 §17.1 code points), in wire order,
/// duplicates and unknown or grease values kept, so an absent suite is never confused with a
/// filtered one. Accepts the raw or MLSMessage-wrapped form.
fn leaf_cipher_suites(kp: &[u8]) -> Result<Vec<u16>, String> {
    let b = if kp.starts_with(&MLS_KEYPACKAGE_MSG_PREFIX) { &kp[4..] } else { kp };
    // KeyPackage: version(u16) || cipher_suite(u16) || init_key<V>
    if b.len() < 4 { return Err("kp: too short for version+cipher_suite".into()); }
    let mut o = kp_skip_v(b, 4)?;
    // LeafNode: encryption_key<V> || signature_key<V>
    o = kp_skip_v(b, o)?;
    o = kp_skip_v(b, o)?;
    // Credential: credential_type(u16) || one <V>. An unknown type has an unknown layout; refuse
    // rather than read arbitrary bytes as suites.
    if o + 2 > b.len() { return Err("kp: eof at credential_type".into()); }
    let cred = ((b[o] as u16) << 8) | b[o + 1] as u16;
    if cred != 1 && cred != 2 {
        return Err(format!("kp: credential_type {cred} is neither basic(1) nor x509(2)"));
    }
    o = kp_skip_v(b, o + 2)?;
    // Capabilities: versions<V>, then cipher_suites<V>
    o = kp_skip_v(b, o)?;
    let (n, mut p) = kp_varint(b, o)?;
    if n % 2 != 0 { return Err(format!("kp: cipher_suites is {n}B, not a whole number of u16")); }
    let end = p.checked_add(n).ok_or("kp: cipher_suites overflow")?;
    if end > b.len() { return Err("kp: cipher_suites overrun".into()); }
    let mut out = Vec::with_capacity(n / 2);
    while p < end {
        out.push(((b[p] as u16) << 8) | b[p + 1] as u16);
        p += 2;
    }
    Ok(out)
}
use mls_rs_identity_x509::{CertificateChain, DerCertificate, SubjectIdentityExtractor,
    X509IdentityProvider};
use mls_rs_crypto_rustcrypto::{x509::{X509Reader, X509Validator}, RustCryptoProvider};
use x509_cert::{Certificate, der::Decode};
use crate::rcc16_validate::Rcc16Validator;
use crate::storage::{FileGroupStateStorage, FileKeyPackageStorage};

/// The KeyPackage leaf `Lifetime` to mint, as `(not_before, duration_secs)`.
///
/// `not_before` is the certificate's `notBefore`, since the lifetime is the one leaf field a peer
/// checks against its own clock (RFC 9420 §7.3) and "now" fails for any peer whose clock trails.
/// The duration is the KeyPackage lifetime setting: Google's key directory requires the package to
/// outlive the certificate (`fixed_365_days`); an RCC.16 §5.1 directory requires it not to (the
/// certificate window less 30 minutes). Falls back to `(None, 365 days)`, i.e. anchored at now,
/// if the certificate does not parse.
fn kp_lifetime_window(leaf: &[u8], fixed_365_days: bool) -> (Option<u64>, u64) {
    const DAY: u64 = 24 * 3600;
    const MARGIN: u64 = 1800;
    let c = match Certificate::from_der(leaf) {
        Ok(c) => c,
        Err(_) => return (None, 365 * DAY),
    };
    let nb = c.tbs_certificate.validity.not_before.to_unix_duration().as_secs();
    let na = c.tbs_certificate.validity.not_after.to_unix_duration().as_secs();
    let (anchor, span) = if fixed_365_days {
        (Some(nb), 365 * DAY)
    } else {
        // Degenerate certificate window: fall back to now and 365 days rather than a zero lifetime.
        let w = na.saturating_sub(nb);
        if w > MARGIN { (Some(nb), (w - MARGIN).min(365 * DAY)) } else { (None, 365 * DAY) }
    };
    // Below the RCC.16 A.4.1.2 floor a package uploads fine but peers cannot use it; log it.
    if let Some(a) = anchor {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs()).unwrap_or(0);
        let remaining = (a + span).saturating_sub(now);
        if remaining < 30 * DAY {
            alog!("kp_lifetime_window WARN: minting KeyPackages with only {}d remaining \
                (< the RCC.16 A.4.1.2 30d floor) — the client certificate needs refreshing",
                remaining / DAY);
        }
    }
    (anchor, span)
}

// Logs the errors the C ABI returns as null. liblog is linked by the C bridge; off Android the
// sink is a no-op.
#[cfg(target_os = "android")]
extern "C" {
    fn __android_log_write(prio: i32, tag: *const c_char, text: *const c_char) -> i32;
}
/// The tag of every MLS log line. Keep in step with `MlsLog.TAG` and `TAG` in
/// `jni/mls_openmls_bridge.c`.
#[cfg(target_os = "android")]
pub(crate) const LOG_TAG: &[u8] = b"RcsMls\0";

/// Writes one line prefixed with the Rust module and line. Use the `alog!` macro, which captures
/// them.
pub(crate) fn alog_at(module: &str, line: u32, msg: &str) {
    #[cfg(target_os = "android")]
    {
        // drop the crate prefix every line would carry
        let m = module.strip_prefix("rcs_mls_ffi::").unwrap_or(module);
        if let Ok(c) = std::ffi::CString::new(format!("[{m}:{line}] {msg}")) {
            // ANDROID_LOG_ERROR = 6
            unsafe { __android_log_write(6, LOG_TAG.as_ptr() as *const c_char, c.as_ptr()); }
        }
    }
    #[cfg(not(target_os = "android"))]
    let _ = (module, line, msg);
}

/// Test-only capture of the last `WELCOME-GI-EXT` line, so a test can drive a real Welcome join and
/// assert what was logged. Thread-local: `join_group` returns on the calling thread.
#[cfg(test)]
thread_local! {
    static LAST_WELCOME_GI_EXT_LINE: core::cell::RefCell<Option<String>> =
        const { core::cell::RefCell::new(None) };
}

/// Test-only record of which arm of `external_commit_resync`'s stale-archive recovery ran (not
/// applicable, purged and retried, or purged, retried and restored); the outcomes are otherwise
/// indistinguishable from outside. Thread-local is correct because the decision runs on the
/// caller's thread.
#[cfg(test)]
thread_local! {
    static LAST_RESYNC_STALE_RECOVERY: core::cell::RefCell<Option<String>> =
        const { core::cell::RefCell::new(None) };
}

/// Test-only fault injection for the one arm of `external_commit_resync` a black-box test cannot
/// reach: the retry after purging the archive also fails, so the snapshot must be restored. Every
/// other build failure is decided before the archive matters and would fail the first build.
#[cfg(test)]
thread_local! {
    static FAIL_POST_PURGE_RESYNC_BUILD: core::cell::Cell<bool> =
        const { core::cell::Cell::new(false) };
}

#[cfg(test)]
fn fail_the_post_purge_resync_build() -> bool {
    FAIL_POST_PURGE_RESYNC_BUILD.with(|c| c.get())
}

#[cfg(not(test))]
#[inline]
fn fail_the_post_purge_resync_build() -> bool { false }

/// `alog!("...{}", x)`: module and line are captured at the call site.
macro_rules! alog {
    ($($arg:tt)*) => {
        $crate::ffi::alog_at(module_path!(), line!(), &format!($($arg)*))
    };
}
pub(crate) use alog;
// ---- Tri-state op result ----
// Every export returns bytes or null; what a null meant (no-op, not found, error) travels in a
// thread-local read right after the call on the same thread, which is exact for a JNI caller.
// `From<String> for MlsError` makes every unannotated error site ERR.

/// What an op result means beyond bytes or null.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum MlsStatus {
    Ok = 0,
    /// The op ran and had nothing to do. A drive loop stops on this.
    NoOp = 1,
    /// The group is not in local storage: a join or recovery case, not a retry.
    NotFound = 2,
    /// A real failure, retryable at the caller's discretion.
    Err = 3,
}

#[derive(Debug)]
pub struct MlsError {
    pub status: MlsStatus,
    pub msg: String,
}

impl From<String> for MlsError {
    /// Every unannotated error site is a real failure.
    fn from(msg: String) -> Self { MlsError { status: MlsStatus::Err, msg } }
}
impl From<&str> for MlsError {
    fn from(msg: &str) -> Self { MlsError { status: MlsStatus::Err, msg: msg.to_string() } }
}

/// The group is not in local storage.
#[allow(dead_code)]
pub fn not_found(msg: impl Into<String>) -> MlsError {
    MlsError { status: MlsStatus::NotFound, msg: msg.into() }
}

thread_local! {
    /// Status of the last op on this thread, read by `rcs_mls_last_status`.
    static LAST_STATUS: std::cell::Cell<u8> = const { std::cell::Cell::new(MlsStatus::Ok as u8) };
    /// `authenticated_data` of the last application message processed on this thread. A side
    /// channel so the plaintext return keeps its framing.
    static LAST_AAD: std::cell::RefCell<Vec<u8>> = const { std::cell::RefCell::new(Vec::new()) };
    /// RCC.16 §7.5.3.1: the message_id the host says this request is for. `None` skips the check;
    /// an empty id is a request that carried none, and an AAD naming one is then a mismatch.
    static EXPECTED_MESSAGE_ID: std::cell::RefCell<Option<Vec<u8>>> =
        const { std::cell::RefCell::new(None) };
    /// An RCC.16 §10.3 resent-message component for the next AAD's trailing slot instead of the
    /// absent `0x00`. One-shot: consumed by the next `aad_for`. The host chooses the component; the
    /// AAD around it is always the engine's.
    static NEXT_RESENT_COMPONENT: std::cell::RefCell<Vec<u8>> =
        const { std::cell::RefCell::new(Vec::new()) };
    /// Set when the last application message's AAD message_id differed from the expected one.
    static LAST_ID_MISMATCH: std::cell::RefCell<Option<(Vec<u8>, Vec<u8>)>> =
        const { std::cell::RefCell::new(None) };
    /// Certified MSISDN of the leaf that signed the last application message on this thread. mls-rs
    /// authenticates only that some current member sent it, and the transport envelope's sender is
    /// unauthenticated; the decrypt authenticates `sender_index`, the roster maps it to a leaf, and
    /// the leaf's SAN carries the number. Empty (no X.509 credential or no `tel:` SAN) means
    /// unknown, never a match.
    static LAST_SENDER_MSISDN: std::cell::RefCell<Vec<u8>> =
        const { std::cell::RefCell::new(Vec::new()) };
    /// Largest group-state record written on this thread since the last read, in bytes: one
    /// operation can write several times and the largest is the useful size. Reading clears it.
    static LAST_STATE_BYTES: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

/// Records a group-state write; called by the storage layer.
pub(crate) fn note_state_write(bytes: usize) {
    LAST_STATE_BYTES.with(|c| c.set(c.get().max(bytes as u64)));
}

// ---- Read-only scope ----
// Read verbs run inside `read_only`, and the storage layer refuses writes while one is open, so
// write-freedom of a read path is enforced where every write passes rather than by convention.

thread_local! {
    /// A depth, so nested read verbs unwind correctly.
    static READ_ONLY_DEPTH: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
}

/// True while a read-only scope is open on this thread.
pub(crate) fn writes_forbidden() -> bool {
    READ_ONLY_DEPTH.with(|c| c.get() > 0)
}

struct ReadOnlyGuard;
impl Drop for ReadOnlyGuard {
    fn drop(&mut self) {
        READ_ONLY_DEPTH.with(|c| c.set(c.get().saturating_sub(1)));
    }
}

/// Runs `f` with writes to the engine's storage refused. A `Drop` guard closes the scope even if
/// `f` panics, since a scope left open would refuse every later write on the thread.
pub(crate) fn read_only<T>(f: impl FnOnce() -> T) -> T {
    READ_ONLY_DEPTH.with(|c| c.set(c.get() + 1));
    let _guard = ReadOnlyGuard;
    f()
}

/// Largest group-state record written on the calling thread since the last read, in bytes; 0 if
/// none. Reading clears it.
#[no_mangle]
pub extern "C" fn rcs_mls_take_state_bytes() -> u64 {
    LAST_STATE_BYTES.with(|c| { let v = c.get(); c.set(0); v })
}

/// `authenticated_data` of the last application message processed on the calling thread; empty
/// when there was none. Valid immediately after the call, on the same thread.
#[no_mangle]
pub extern "C" fn rcs_mls_last_aad() -> RcsBytes {
    LAST_AAD.with(|c| to_bytes(c.borrow().clone()))
}

/// RCC.16 §7.5.3.1: the message_id this request is for, set before `process`/`process_ex`. The
/// only AAD input the host supplies. Null clears it, which skips the check; a non-null pointer
/// with length 0 arms an empty id, for a request whose envelope carried none.
#[no_mangle]
// Null- and length-checked before any dereference.
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn rcs_mls_set_request_message_id(ptr: *const u8, len: usize) -> i32 {
    let v = if ptr.is_null() {
        None
    } else if len == 0 {
        Some(Vec::new())
    } else {
        Some(unsafe { std::slice::from_raw_parts(ptr, len) }.to_vec())
    };
    EXPECTED_MESSAGE_ID.with(|c| *c.borrow_mut() = v);
    0
}

/// Places an RCC.16 §10.3 resent-message component in the next AAD built on this thread instead
/// of the absent `0x00`. Null or empty clears it; one-shot.
#[no_mangle]
// Null- and length-checked before any dereference.
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn rcs_mls_set_next_resent_component(ptr: *const u8, len: usize) -> i32 {
    let v = if ptr.is_null() || len == 0 {
        Vec::new()
    } else {
        unsafe { std::slice::from_raw_parts(ptr, len) }.to_vec()
    };
    NEXT_RESENT_COMPONENT.with(|c| *c.borrow_mut() = v);
    0
}

/// Non-zero when the last application message on this thread carried an AAD message_id other
/// than the one set by `rcs_mls_set_request_message_id` (RCC.16 `MessageIdMismatch`).
#[no_mangle]
pub extern "C" fn rcs_mls_last_message_id_mismatch() -> i32 {
    LAST_ID_MISMATCH.with(|c| if c.borrow().is_some() { 1 } else { 0 })
}

/// The `(expected, actual)` pair behind a mismatch as `expected\\0actual`, for logging; empty
/// when there was none.
#[no_mangle]
pub extern "C" fn rcs_mls_last_message_id_mismatch_detail() -> RcsBytes {
    LAST_ID_MISMATCH.with(|c| {
        let out = match &*c.borrow() {
            Some((exp, act)) => {
                let mut v = exp.clone();
                v.push(0);
                v.extend_from_slice(act);
                v
            }
            None => Vec::new(),
        };
        to_bytes(out)
    })
}

/// Compares a decrypted application message's AAD message_id with the host's and records the
/// verdict, which the host refuses on. Skipped when the host armed nothing or the AAD does not
/// parse; an AAD with an empty id matches an empty expected id.
fn note_message_id_check(aad: &[u8]) {
    let Some(expected) = EXPECTED_MESSAGE_ID.with(|c| c.borrow().clone()) else {
        return;
    };
    match crate::rcc16::parse_authenticated_data(aad) {
        Ok(p) => {
            if p.message_id != expected {
                alog!(
                    "§7.5.3.1 MessageIdMismatch: request_context.message_id={:?} but AAD carries {:?}",
                    String::from_utf8_lossy(&expected),
                    String::from_utf8_lossy(&p.message_id)
                );
                LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = Some((expected, p.message_id)));
            }
        }
        Err(e) => {
            alog!("§7.5.3.1 check skipped — AAD did not parse ({e}); leaving it to the FTD path");
        }
    }
}

/// Certified MSISDN of the leaf that signed the last application message on the calling thread.
/// Empty means unknown, never a match.
#[no_mangle]
pub extern "C" fn rcs_mls_last_sender_msisdn() -> RcsBytes {
    LAST_SENDER_MSISDN.with(|c| to_bytes(c.borrow().clone()))
}

fn set_status(s: MlsStatus) {
    LAST_STATUS.with(|c| c.set(s as u8));
}

/// Status of the last op on the calling thread; `Ok` (0) if none has run. Read immediately after
/// the op.
#[no_mangle]
pub extern "C" fn rcs_mls_last_status() -> u8 {
    LAST_STATUS.with(|c| c.get())
}

// ---- Transaction frame ----
// One engine call is one atomic storage unit. Mutating ops also clear the send cache when they
// start (a landed commit makes it stale) and again on failure (a half-applied op may leave a cached
// group whose epoch was never committed). The two cover different states.

/// `logged`, plus clearing the send cache on failure, for an op that may mutate group state.
///
/// # Safety
/// `s` must be a session pointer from `rcs_mls_session_start`, or null.
unsafe fn logged_tx<E: Into<MlsError>>(op: &str, s: *mut ProdSession, r: Result<Vec<u8>, E>)
        -> RcsBytes {
    if r.is_err() && !s.is_null() {
        alog!("{op}: op FAILED — invalidating engine caches (D1(c) rollback compensator)");
        (*s).invalidate_send_cache();
    }
    logged(op, r)
}

/// Records the op's status for the caller and logs the reason on failure.
fn logged<E: Into<MlsError>>(op: &str, r: Result<Vec<u8>, E>) -> RcsBytes {
    match r {
        Ok(v) => { set_status(MlsStatus::Ok); to_bytes(v) }
        Err(e) => {
            let e: MlsError = e.into();
            set_status(e.status);
            // NO_OP is logged as such, not as a failure.
            match e.status {
                MlsStatus::NoOp => alog!("{op} NO_OP: {}", e.msg),
                MlsStatus::NotFound => alog!("{op} NOT_FOUND: {}", e.msg),
                _ => alog!("{op} ERR: {}", e.msg),
            }
            NULL_BYTES
        }
    }
}

const SUITE: CipherSuite = CipherSuite::P256_AES128;
type Extractor = SubjectIdentityExtractor<X509Reader>;

/// Identity provider advertising both `basic(1)` and `x509(2)` in the leaf's credential
/// capabilities (RFC 9420 §7.2), as other clients do, so a peer with a basic credential is not
/// seen as unsupported. Validation delegates unchanged to the inner X.509 provider.
#[derive(Clone, Debug)]
struct X509WithBasicCreds<P>(P);

/// Test-only: the validation instant for engine-driven tests. The pki2 fixtures must be evaluated
/// at `PKI2_NOW`, and ffi tests reach the validator deep inside mls-rs with no timestamp to pass.
/// This wrapper is the outermost identity provider and no `rcc16_validate` test builds one, so the
/// pin applies to exactly the tests that need it. Every ffi test uses the pki2 chain or the v1
/// chain, whose windows both contain `PKI2_NOW`; a test with another chain needs this revisited.
/// `commit_builder` and `wall_clock_secs` pin mls-rs's own lifetime checks to the same instant.
#[cfg(test)]
#[inline]
fn pinned_validation_time(_ts: Option<MlsTime>) -> Option<MlsTime> {
    // A constant, never a thread-local: mls-rs validates on a rayon pool, where a per-thread
    // override would not be seen.
    Some(MlsTime::from(crate::rcc16_validate::PKI2_NOW))
}
#[cfg(not(test))]
#[inline]
fn pinned_validation_time(ts: Option<MlsTime>) -> Option<MlsTime> { ts }

/// Every engine commit starts here. mls-rs checks each added KeyPackage's lifetime at the commit
/// time, the wall clock unless one is set, and the pki2 packages' lifetimes end with their
/// certificates, so tests pin the commit time to `PKI2_NOW`. Production sets none, as before.
#[cfg(test)]
fn commit_builder(g: &mut Group<ProdConfig>) -> mls_rs::group::CommitBuilder<'_, ProdConfig> {
    g.commit_builder().commit_time(MlsTime::from(crate::rcc16_validate::PKI2_NOW))
}
#[cfg(not(test))]
#[inline]
fn commit_builder(g: &mut Group<ProdConfig>) -> mls_rs::group::CommitBuilder<'_, ProdConfig> {
    g.commit_builder()
}

/// The engine's wall clock in seconds since the UNIX epoch, 0 if unreadable. Tests read
/// `PKI2_NOW`, so a leaf anchored at it stays inside the fixtures' windows.
#[cfg(test)]
fn wall_clock_secs() -> u64 { crate::rcc16_validate::PKI2_NOW }
#[cfg(not(test))]
fn wall_clock_secs() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs()).unwrap_or(0)
}
impl<P: IdentityProvider> IdentityProvider for X509WithBasicCreds<P> {
    type Error = P::Error;


    // Sync signatures: this crate builds mls-rs in sync mode, where `must_be_sync` strips `async`.
    fn validate_member(&self, signing_identity: &SigningIdentity, timestamp: Option<MlsTime>,
                       context: MemberValidationContext<'_>) -> Result<(), Self::Error> {
        self.0.validate_member(signing_identity, pinned_validation_time(timestamp), context)
    }
    fn validate_external_sender(&self, signing_identity: &SigningIdentity,
                               timestamp: Option<MlsTime>,
                               extensions: Option<&CoreExtensionList>) -> Result<(), Self::Error> {
        self.0.validate_external_sender(signing_identity, pinned_validation_time(timestamp),
            extensions)
    }
    fn identity(&self, signing_identity: &SigningIdentity, extensions: &CoreExtensionList)
        -> Result<Vec<u8>, Self::Error> {
        self.0.identity(signing_identity, extensions)
    }
    fn valid_successor(&self, predecessor: &SigningIdentity, successor: &SigningIdentity,
                       extensions: &CoreExtensionList) -> Result<bool, Self::Error> {
        self.0.valid_successor(predecessor, successor, extensions)
    }
    /// RFC 9420 §17.5 credential types `basic=1`, `x509=2`, in wire order.
    fn supported_types(&self) -> Vec<CredentialType> {
        vec![CredentialType::BASIC, CredentialType::X509]
    }
}

// allow_external_commit makes commit.external_commit_group_info non-empty. The last builder method
// wins the type, so WithMlsRules is the outermost layer.
type ProdConfig = WithKeyPackageRepo<
    FileKeyPackageStorage,
    WithMlsRules<
        crate::rcc16::Rcc16MlsRules,
        WithGroupStateStorage<
            FileGroupStateStorage,
            WithIdentityProvider<
                X509WithBasicCreds<X509IdentityProvider<Extractor, Rcc16Validator>>,
                WithCryptoProvider<RustCryptoProvider, BaseConfig>>>>>;

/// The era-advance mode; mirrors Java's `MlsAdvanceEraKind`. Mode 1 removes `end_mls`, 2 installs
/// it, 0 or anything else touches neither, so clearing is its own operation. `create_group_carry`'s
/// revival arm and [`EndMlsOp::RemoveForRevival`] are the only two sites that may drop 0xF002, and
/// both take the intent from the caller. See docs/mls/downgrade.md.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AdvanceEraKind {
    /// Mode 0: `end_mls` carried forward unchanged.
    Normal,
    /// Mode 1, revival: era+1 and drop `end_mls`.
    Revival,
    /// Mode 2, phoenix: era+1 and install `end_mls`; the new era is born downgraded.
    PhoenixDowngrade,
}

impl AdvanceEraKind {
    /// An unknown mode is `Normal`: carrying the extension forward is the safe reading.
    pub fn from_mode(m: u8) -> Self {
        match m {
            1 => AdvanceEraKind::Revival,
            2 => AdvanceEraKind::PhoenixDowngrade,
            _ => AdvanceEraKind::Normal,
        }
    }
    pub fn may_remove_end_mls(self) -> bool { self == AdvanceEraKind::Revival }
    pub fn installs_end_mls(self) -> bool { self == AdvanceEraKind::PhoenixDowngrade }
}

/// What `commit_end_mls` is asked to do. An enum rather than a boolean, so a recovery path cannot
/// revive a downgraded group by a transposed argument.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EndMlsOp {
    /// Add `end_mls` to the GroupContext.
    Install,
    /// Remove `end_mls`: revival only. One of the two removal sites.
    RemoveForRevival,
}

impl EndMlsOp {
    pub fn from_flag(remove: bool) -> Self {
        if remove { EndMlsOp::RemoveForRevival } else { EndMlsOp::Install }
    }
    pub fn removes(self) -> bool { self == EndMlsOp::RemoveForRevival }
}

pub struct ProdSession {
    client: Client<ProdConfig>,
    storage_dir: std::path::PathBuf,
    /// The group of the last encrypt, kept across consecutive encrypts in one epoch: reloading it
    /// re-derives the secret tree from generation 0 while the generation counter persists, and
    /// peers reject the mismatched key. Cleared by every group-modifying op. `(group_id, Group)`.
    send_cache: std::sync::Mutex<Option<(Vec<u8>, Group<ProdConfig>)>>,
    /// `not_before` for every KeyPackage leaf we mint: the certificate's `notBefore` (see
    /// `kp_lifetime_window`); `None`, anchoring at now, only if the certificate does not parse.
    kp_not_before: Option<MlsTime>,
    /// Our current credential and signing key. A group's leaf keeps the identity it was created or
    /// joined with, so `self_update` compares against these and carries them in when they differ
    /// (RCC.16 v4.0 §9.5.3).
    signing_identity: SigningIdentity,
    signer: SignatureSecretKey,
    /// RCC.16 §7.11.12.1 continuity tokens captured from a Welcome's decrypted GroupInfo at join,
    /// as `(group_id, token)`, until the host takes them (`take_welcome_continuity_token`) to
    /// persist in `MlsConversationRecord`. The token is readable only inside `join_group`. Bounded
    /// at [`WELCOME_CONTINUITY_SLOTS`]; taking removes it, so the secret stays only until
    /// persisted.
    welcome_continuity: std::sync::Mutex<Vec<(Vec<u8>, Vec<u8>)>>,
}

/// Uncollected Welcome tokens to hold, oldest evicted first.
const WELCOME_CONTINUITY_SLOTS: usize = 8;

/// A lowercase UUID-v4 string (36 ASCII bytes) for an MLS group id, since the server rejects a raw
/// random id. Zeros on the rare RNG error.
fn mint_uuid_string() -> String {
    let mut b = [0u8; 16];
    let _ = getrandom::getrandom(&mut b);
    b[6] = (b[6] & 0x0f) | 0x40;   // version 4
    b[8] = (b[8] & 0x3f) | 0x80;   // variant RFC 4122
    format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
        b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15])
}

/// Splits `[u32-be len][DER]` records into DER blobs.
fn split_len_prefixed(mut b: &[u8]) -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    while b.len() >= 4 {
        let n = u32::from_be_bytes([b[0], b[1], b[2], b[3]]) as usize;
        if 4 + n > b.len() { break; }
        out.push(b[4..4 + n].to_vec());
        b = &b[4 + n..];
    }
    out
}
fn join_len_prefixed(parts: &[Vec<u8>]) -> Vec<u8> {
    let mut out = Vec::new();
    for p in parts {
        out.extend_from_slice(&(p.len() as u32).to_be_bytes());
        out.extend_from_slice(p);
    }
    out
}

impl ProdSession {
    #[allow(clippy::too_many_arguments)]
    /// `revoked_serials` is the host's revoked-serial list, the only revocation input; usually
    /// present and empty. The engine fetches no CRL or OCSP and links no network stack.
    /// `extra_ext_types` / `extra_prop_types` are advertised in addition to
    /// `rcc16::advertised_extensions`/`advertised_proposals`, for code points the transport needs;
    /// duplicates are ignored.
    pub fn start_with_advertisement(leaf: &[u8], chain: &[Vec<u8>], priv32: &[u8], pub65: &[u8],
                 roots: &[Vec<u8>], revoked_serials: &[Vec<u8>],
                 extra_ext_types: &[u16], extra_prop_types: &[u16],
                 storage_dir: &str) -> Result<Self, String> {
        let mut certs = vec![DerCertificate::from(leaf.to_vec())];
        for c in chain { certs.push(DerCertificate::from(c.clone())); }
        let credential = CertificateChain::from(certs).into_credential();
        let signing_identity =
            SigningIdentity::new(credential, SignaturePublicKey::from(pub65.to_vec()));
        let secret = SignatureSecretKey::from(priv32.to_vec());
        let extractor = SubjectIdentityExtractor::new(0, X509Reader::new());
        let root_list: Vec<DerCertificate> =
            roots.iter().map(|r| DerCertificate::from(r.clone())).collect();
        let x509 = X509Validator::new(root_list).map_err(|e| format!("validator: {e:?}"))?;
        // Two independent axes. `strict` is the A.4.1 lifetime floors and subject/policy rules,
        // off under the deployment-tolerant peer policy because deployed leaves live about 75
        // days. `pop_strict` rejects a failed .4 proof-of-possession under both policies; the
        // escape hatch `rcs_mls_set_pop_lenient(1)` restores tolerance without a rebuild.
        let mut rcc16 = Rcc16Validator::new(x509);
        rcc16.strict = !peer_cert_tolerant();
        rcc16.pop_strict = !pop_lenient();
        // A.4.1 SAN equality needs the number queried for one particular peer, which exists only at
        // the claim site: this validator sees every credential, ours and every peer's, so a
        // session- wide expectation would refuse every peer. `kp_inspect` returns the certified SAN
        // and the host compares it (`MlsProviderTransport.keyPackageUsable`).
        rcc16.expected_msisdn = None;
        // The host's revocation list, normally empty; serials are normalised by the builder.
        let rcc16 = rcc16.with_revoked_serials(revoked_serials.to_vec());
        // Advertise [basic(1), x509(2)] as other clients do; see X509WithBasicCreds.
        let idp = X509WithBasicCreds(X509IdentityProvider::new(extractor, rcc16));
        // KeyPackage capabilities as other clients advertise them: cipher suites [1,3,2,7] (the
        // group runs suite 2), the extension and proposal lists from rcc16, credentials [1,2]. The
        // extra types are advertised only; no group requires them.
        let crypto = RustCryptoProvider::with_enabled_cipher_suites(vec![
            CipherSuite::CURVE25519_AES128,  // 1
            CipherSuite::CURVE25519_CHACHA,  // 3
            CipherSuite::P256_AES128,        // 2 (the group suite)
            CipherSuite::P384_AES256,        // 7
        ]);
        // The canonical lists from rcc16.rs, plus the transport's extras.
        let ext_types: Vec<ExtensionType> = crate::rcc16::advertised_extensions(extra_ext_types)
            .into_iter().map(ExtensionType::from).collect();
        let prop_types: Vec<ProposalType> = crate::rcc16::advertised_proposals(extra_prop_types)
            .into_iter().map(ProposalType::from).collect();
        // KeyPackage leaf lifetime, anchor and duration; see kp_lifetime_window.
        let (kp_not_before, kp_lifetime_secs) = kp_lifetime_window(leaf, kp_fixed_365_days());
        let client = Client::builder()
            .crypto_provider(crypto)
            .identity_provider(idp)
            .group_state_storage(FileGroupStateStorage::new(storage_dir))
            // No ratchet_tree extension in the GroupInfo: the server rejects it. The GroupInfo
            // carries external_pub (allow_external_commit) and the tree travels separately.
            // Rcc16MlsRules adds the era guard on both commit directions.
            .mls_rules(crate::rcc16::Rcc16MlsRules::new(DefaultMlsRules::default()
                .with_commit_options(CommitOptions::new()
                    .with_allow_external_commit(true)
                    .with_ratchet_tree_extension(false))
                // Padme padding (RFC 9420 §17.6, as RCC.16 Annex C.2 uses for files), so ciphertext
                // lengths match other clients'. Control messages stay unencrypted.
                .with_encryption_options(EncryptionOptions::new(false, PaddingMode::Padme))))
            // KeyPackage secrets persist next to the group state, so a Welcome arriving in a later
            // session can be opened.
            .key_package_repo(FileKeyPackageStorage::new(storage_dir.to_string()))
            .extension_types(ext_types)
            .custom_proposal_types(prop_types)
            .key_package_lifetime(Duration::from_secs(kp_lifetime_secs))
            .signing_identity(signing_identity.clone(), secret.clone(), SUITE)
            .build();
        // Kept for `self_update`: the builder consumes both and `Client` has no getter.
        let kept_identity = signing_identity;
        let kept_signer = secret;
        Ok(Self { client, storage_dir: storage_dir.into(), send_cache: std::sync::Mutex::new(None),
                  kp_not_before: kp_not_before.map(MlsTime::from),
                  signing_identity: kept_identity, signer: kept_signer,
                  welcome_continuity: std::sync::Mutex::new(Vec::new()) })
    }
    /// Engine-decided advertisement, no transport extras.
    #[allow(clippy::too_many_arguments)]
    pub fn start(leaf: &[u8], chain: &[Vec<u8>], priv32: &[u8], pub65: &[u8],
                 roots: &[Vec<u8>], revoked_serials: &[Vec<u8>],
                 storage_dir: &str) -> Result<Self, String> {
        Self::start_with_advertisement(leaf, chain, priv32, pub65, roots, revoked_serials,
                                       &[], &[], storage_dir)
    }

    /// `start` with an empty revocation list, named so the choice stays visible in tests.
    #[cfg(test)]
    pub fn start_no_revocation(leaf: &[u8], chain: &[Vec<u8>], priv32: &[u8], pub65: &[u8],
                               roots: &[Vec<u8>], storage_dir: &str) -> Result<Self, String> {
        Self::start(leaf, chain, priv32, pub65, roots, &[], storage_dir)
    }

    pub fn generate_key_packages(&self, count: u32) -> Result<Vec<u8>, String> {
        let mut kps = Vec::new();
        for _ in 0..count.max(1) {
            // MLSMessage-wrapped (mls10 || mls_key_package || KeyPackage), which is what peers and
            // the key directory parse. The lifetime is anchored at the certificate's notBefore.
            let kp = self.client.generate_key_package_message(Default::default(),
                    Default::default(), self.kp_not_before)
                .map_err(|e| format!("gen_kp: {e:?}"))?
                .to_bytes().map_err(|e| format!("kp_to_bytes: {e:?}"))?;
            kps.push(kp);
        }
        Ok(join_len_prefixed(&kps))  // [u32 len][MLSMessage-wrapped kp]...
    }
    /// The RFC 9420 KeyPackageRef of one MLSMessage-wrapped KeyPackage, the id a Welcome addresses
    /// it by. Derived from the bytes, so stable across processes.
    pub fn key_package_ref(&self, kp: &[u8]) -> Result<Vec<u8>, String> {
        use mls_rs_core::crypto::CryptoProvider;
        let m = MlsMessage::from_bytes(kp).map_err(|e| format!("kp_from_bytes: {e:?}"))?;
        let csp = RustCryptoProvider::default().cipher_suite_provider(SUITE)
            .ok_or_else(|| "key_package_ref: no cipher suite provider".to_string())?;
        match m.key_package_reference(&csp).map_err(|e| format!("kp_ref: {e:?}"))? {
            Some(r) => Ok(r.to_vec()),
            // name a caller bug rather than hash it
            None => Err("key_package_ref: not a KeyPackage MLSMessage".to_string()),
        }
    }

    /// The KeyPackageRefs a Welcome is sealed to, one per EncryptedGroupSecrets entry, as a
    /// length-prefixed list. In a multi-add only one is ours; match on the intersection.
    pub fn welcome_key_package_refs(&self, welcome: &[u8]) -> Result<Vec<u8>, String> {
        let m = MlsMessage::from_bytes(welcome)
            .map_err(|e| format!("welcome_from_bytes: {e:?}"))?;
        let refs: Vec<Vec<u8>> = m.welcome_key_package_references()
            .into_iter().map(|r| r.to_vec()).collect();
        Ok(join_len_prefixed(&refs))
    }

    /// One MLSMessage-wrapped last-resort KeyPackage carrying the RFC 9420 `last_resort` extension
    /// (0x000A), which the key directory requires in that slot.
    pub fn generate_last_resort_key_package(&self) -> Result<Vec<u8>, String> {
        use mls_rs::extension::recommended::LastResortKeyPackageExt;
        use mls_rs_core::extension::MlsExtension;
        let ext = LastResortKeyPackageExt.into_extension().map_err(|e| format!("lr_ext: {e:?}"))?;
        let kp_exts = CoreExtensionList::from(vec![ext]);
        let kp = self.client.generate_key_package_message(kp_exts, Default::default(),
                self.kp_not_before)
            .map_err(|e| format!("gen_lr_kp: {e:?}"))?
            .to_bytes().map_err(|e| format!("lr_to_bytes: {e:?}"))?;
        Ok(kp)
    }
    /// Creates a group carrying era 0xF001 and adds one peer KeyPackage. Returns the artifact
    /// bundle (Welcome, commit, GroupInfo, 32-byte epoch authenticator, group id, ...); see
    /// docs/mls/rust-core.md.
    pub fn create_group(&self, era: u32, peer_kp: &[u8], gid_override: &[u8])
            -> Result<(Vec<u8>, Vec<u8>), String> {
        self.create_group_multi(era, std::slice::from_ref(&peer_kp.to_vec()), gid_override)
    }

    /// Creates a group whose initial commit adds every member, with one Welcome for all of them.
    /// The server validates the GroupContext against the full RCS roster, so create-then-add fails.
    pub fn create_group_multi(&self, era: u32, peer_kps: &[Vec<u8>], gid_override: &[u8])
            -> Result<(Vec<u8>, Vec<u8>), String> {
        self.create_group_carry(era, peer_kps, gid_override, &[], AdvanceEraKind::Normal)
    }

    /// The certified MSISDN of a claimed KeyPackage, empty when it has none. Shares
    /// `leaf_san_msisdn` with [`Self::certified_msisdn_of`], so [`Self::plan_group`] compares
    /// packages and roster leaves by one definition. Empty is unknown, never a mismatch.
    fn kp_certified_msisdn(kp: &[u8]) -> Vec<u8> {
        let wrapped = if kp.starts_with(&MLS_KEYPACKAGE_MSG_PREFIX) {
            kp.to_vec()
        } else {
            wrap_key_package(kp)
        };
        MlsMessage::from_bytes(&wrapped).ok()
            .and_then(|m| m.into_key_package())
            .and_then(|k| match k.signing_identity().credential {
                mls_rs_core::identity::Credential::X509(ref chain) => chain.leaf()
                    .and_then(|l| crate::rcc16_validate::leaf_san_msisdn(l.as_ref())),
                _ => None,
            })
            .unwrap_or_default()
    }

    /// Decides what a group operation is and at which era; the host is told, not asked. See
    /// docs/mls/group-lifecycle.md.
    ///
    /// The era comes from bytes, not a number: the 0xF001 of the group held at `gid_override` and
    /// of `carry_group_info`, the server's GroupInfo, which is the authority for a member that is
    /// behind. The answers:
    ///
    /// * [`WELCOME_ACTION_NEW_GROUP`]: nothing local, nothing carried; born at
    ///   [`ERA_INITIAL`](crate::rcc16::ERA_INITIAL).
    /// * [`WELCOME_ACTION_NEW_MEMBERSHIP_EXISTING_GROUP`]: we hold the group, nothing was carried,
    ///   and the request adds to the held roster. Not a create: same era, an add commit.
    /// * [`WELCOME_ACTION_NEW_ERA_EXISTING_GROUP`]: any other prior state; the era moves past the
    ///   higher of the two sources.
    ///
    /// Never returns `REFRESH_MEMBERSHIP_EXISTING_GROUP` (4; accepted inbound, its outbound trigger
    /// is unknown) or `UNKNOWN` (0). Returns `(era, welcome_action, kps_to_add)`; the last only for
    /// arm 3, holding the packages whose MSISDN is not yet in the roster.
    pub fn plan_group(&self, peer_kps: &[Vec<u8>], gid_override: &[u8], carry_group_info: &[u8])
            -> Result<(u32, u32, Vec<Vec<u8>>), String> {
        // Read-only: runs before create_group_carry deletes the state it is about to replace.
        let local = if gid_override.is_empty() {
            None
        } else {
            self.client.load_group(gid_override).ok()
        };
        let local_era = local.as_ref()
            .and_then(|g| crate::rcc16::era_of(g.context().extensions()));
        let carried_era = if carry_group_info.is_empty() {
            None
        } else {
            MlsMessage::from_bytes(carry_group_info).ok()
                .and_then(|m| m.into_group_info())
                .and_then(|gi| crate::rcc16::era_of(gi.group_context().extensions()))
        };

        // Arm 1: nothing anywhere, born at era 1. A conversation the server already holds arrives
        // with a carried GroupInfo, so the server's era is not consulted here.
        if local_era.is_none() && carried_era.is_none() && local.is_none() {
            alog!("plan_group: no local group at this id and no carried GroupInfo → NEW_GROUP at \
                   era {}", crate::rcc16::ERA_INITIAL);
            return Ok((crate::rcc16::ERA_INITIAL, crate::rcc16::WELCOME_ACTION_NEW_GROUP,
                       Vec::new()));
        }

        // Arm 3: we hold the group, nothing was carried, and some requested participants are
        // members and some are not. All-new and all-known go to the create path, which rebuilds
        // the roster. A carried GroupInfo means an advance was intended and rules this arm out.
        if let Some(ref g) = local {
            if carried_era.is_none() {
                let held: Vec<Vec<u8>> = g.roster().members().iter()
                    .map(|m| Self::certified_msisdn_of(g, m.index))
                    .filter(|s| !s.is_empty())
                    .collect();
                let requested: Vec<Vec<u8>> =
                        peer_kps.iter().map(|kp| Self::kp_certified_msisdn(kp)).collect();
                let fresh_idx = crate::rcc16::additions_to(&held, &requested);
                if !fresh_idx.is_empty() && fresh_idx.len() < peer_kps.len() {
                    let era = local_era.unwrap_or(crate::rcc16::ERA_INITIAL);
                    alog!("plan_group: we already hold this group at era {era} and {} of {} \
                           requested package(s) are members already → NEW_MEMBERSHIP_EXISTING_GROUP, \
                           an addMembers commit at the SAME era (no create, no era move)",
                          peer_kps.len() - fresh_idx.len(), peer_kps.len());
                    let fresh: Vec<Vec<u8>> =
                            fresh_idx.iter().map(|i| peer_kps[*i].clone()).collect();
                    return Ok((era, crate::rcc16::WELCOME_ACTION_NEW_MEMBERSHIP_EXISTING_GROUP,
                               fresh));
                }
            }
        }

        // Arm 2: the era moves past the higher of the two sources.
        let base = core::cmp::max(local_era.unwrap_or(0), carried_era.unwrap_or(0));
        let era = crate::rcc16::next_era(base)?;
        alog!("plan_group: local era={local_era:?} carried era={carried_era:?} → \
               NEW_ERA_EXISTING_GROUP at era {era} (past the higher of the two)");
        Ok((era, crate::rcc16::WELCOME_ACTION_NEW_ERA_EXISTING_GROUP, Vec::new()))
    }

    /// Builds the group operation [`Self::plan_group`] decides, without being told an era, and
    /// reports the era and `welcomeAction` in bundle slots 6 and 7 (appended, so slots 0-5 are
    /// unchanged). Arm 3 goes on the add RPC, arms 1 and 2 on `CreateMlsConversation`.
    pub fn create_group_planned(&self, peer_kps: &[Vec<u8>], gid_override: &[u8],
            carry_group_info: &[u8], kind: AdvanceEraKind)
            -> Result<(Vec<u8>, Vec<u8>), String> {
        if peer_kps.is_empty() {
            return Err("create_group_planned: no member KeyPackages".to_string());
        }
        let (era, action, to_add) = self.plan_group(peer_kps, gid_override, carry_group_info)?;
        let (gid, artifacts) =
                if action == crate::rcc16::WELCOME_ACTION_NEW_MEMBERSHIP_EXISTING_GROUP {
            // Not a create: the group and era stay, and the bundle carries an add commit.
            (gid_override.to_vec(), self.add_members(gid_override, &to_add, &[])?)
        } else {
            self.create_group_carry(era, peer_kps, gid_override, carry_group_info, kind)?
        };
        // Slot 8: the MSISDNs the commit admits, so the RCS add names the same participants as the
        // MLS commit.
        let admitted: Vec<Vec<u8>> =
            to_add.iter().map(|kp| Self::kp_certified_msisdn(kp)).collect();
        let mut parts = split_len_prefixed(&artifacts);
        parts.push(era.to_be_bytes().to_vec());
        parts.push(action.to_be_bytes().to_vec());
        parts.push(join_len_prefixed(&admitted));
        Ok((gid, join_len_prefixed(&parts)))
    }

    /// As above, carrying the carried GroupInfo's extensions into the new era. The server refuses
    /// an era advance that drops a committed subject or icon, and the source is the server's
    /// GroupInfo because a member needing an advance holds stale metadata.
    pub fn create_group_carry(&self, era: u32, peer_kps: &[Vec<u8>], gid_override: &[u8],
            carry_group_info: &[u8], kind: AdvanceEraKind)
            -> Result<(Vec<u8>, Vec<u8>), String> {
        if peer_kps.is_empty() {
            return Err("create_group_multi: no member KeyPackages".to_string());
        }
        self.invalidate_send_cache();
        // RCC.16 §7.11.1.1: the era is a uint32 0xF001 GroupContext extension. `era == 0` emits
        // none; every caller passes a real era.
        let mut group_ctx = if era != 0 {
            CoreExtensionList::from(vec![
                Extension::new(ExtensionType::from(0xF001u16), era.to_be_bytes().to_vec())])
        } else {
            CoreExtensionList::new()
        };
        // Inherit extensions from the carried GroupInfo (see the deny-list below).
        if !carry_group_info.is_empty() {
            match MlsMessage::from_bytes(carry_group_info)
                    .ok()
                    .and_then(|m| m.into_group_info()) {
                Some(gi) => {
                    // The new era must be strictly above the carried one: a group at an era peers
                    // have passed is unrecoverable. Era 0 is included, since it emits no era
                    // extension and peers refuse that.
                    crate::rcc16::check_era_advances(
                        crate::rcc16::era_of(gi.group_context().extensions()), era)?;
                    // A deny-list: every carried extension is copied byte-for-byte except 0xF001,
                    // which takes the new era, so an unknown code point is preserved rather than
                    // dropped. 0xF002 is carried under AdvanceEraKind::Normal, so an advance never
                    // erases a peer's downgrade; the mode is the only way to remove or install it.
                    let mut carried: Vec<u16> = Vec::new();
                    for e in gi.group_context().extensions().iter() {
                        let ty: u16 = (*e.extension_type()).into();
                        if ty == crate::rcc16::ERA_EXT { continue; }
                        // One of the two sites that may drop 0xF002 (the other is
                        // EndMlsOp::RemoveForRevival); both take a revival intent from the caller.
                        if ty == crate::rcc16::END_MLS_EXT && kind.may_remove_end_mls() {
                            alog!("create_group_carry: REVIVAL (mode 1) — dropping end_mls (0xF002) \
                                   from the new era's GroupContext. This is a deliberate removal.");
                            continue;
                        }
                        // Copied verbatim: the source already framed it, and re-encoding would
                        // frame it twice.
                        group_ctx.set(Extension::new(ExtensionType::from(ty),
                                                     e.extension_data.clone()));
                        carried.push(ty);
                    }
                    // log the types: which extension went missing is the first question asked
                    alog!("create_group_carry: inherited {} GroupContext extension(s) from \
                                   the supplied GroupInfo: {:04X?} (era set to {era})",
                                  carried.len(), carried);
                }
                None => {
                    // Refuse rather than create without them; the server would refuse the advance.
                    return Err("create_group_carry: could not parse the carry-over GroupInfo"
                        .to_string());
                }
            }
        }
        // Mode 2, phoenix: the new era is born downgraded. Outside the carry block because it must
        // work without a carried GroupInfo, and after the era and the carry so it wins.
        if kind.installs_end_mls() {
            // The payload depends on the revision; `end_mls_payload` chooses. v4.0 reason 10 is
            // "phoenix after a failed end_mls commit", exactly this branch.
            group_ctx.set(Extension::new(ExtensionType::from(crate::rcc16::END_MLS_EXT),
                crate::rcc16::end_mls_payload(crate::rcc16::EndMlsReason::OutgoingCommitFailed)));
            alog!("create_group_carry: PHOENIX (mode 2) — INSTALLING end_mls (0xF002) in the new \
                   era's GroupContext. The new era is born downgraded; no key packages are needed \
                   to advance into an era whose group is not encrypted (INV-KP).");
        }
        // The server rejects a raw random group id, so a fresh group gets a UUID-v4 string (36
        // ASCII bytes). An era advance reuses the existing RCS group id (`gid_override`), since a
        // new id reads as a different conversation.
        let gid_bytes = if gid_override.is_empty() {
            mint_uuid_string().into_bytes()
        } else {
            // A reused id may hold stale state from a failed attempt, which would fail the apply
            // with InvalidEpoch; delete it first (idempotent).
            FileGroupStateStorage::new(self.storage_dir.clone()).delete_group(gid_override);
            gid_override.to_vec()
        };
        // The leaf lifetime anchor; see `leaf_lifetime_anchor`.
        let anchor = self.leaf_lifetime_anchor();
        alog!(
            "create_group: leaf Lifetime anchor={:?} (cert notBefore={:?}) — anchoring in the past \
            so a peer whose clock trails ours can still join; None would anchor at NOW and draw \
            MlsError_InvalidLifetime on the joiner", anchor, self.kp_not_before);
        let mut g = self.client
            .create_group_with_id(gid_bytes, group_ctx, Default::default(), anchor)
            .map_err(|e| format!("create_group: {e:?}"))?;
        // Each peer KeyPackage arrives raw from the key directory and is re-wrapped (an already
        // wrapped one is accepted). Every member is added in this one commit, so the MLS
        // membership equals the RCS roster.
        let mut builder = commit_builder(&mut g);
        for peer_kp in peer_kps {
            let wrapped = if peer_kp.starts_with(&MLS_KEYPACKAGE_MSG_PREFIX) {
                peer_kp.clone()
            } else {
                wrap_key_package(peer_kp)
            };
            let kp = MlsMessage::from_bytes(&wrapped)
                .map_err(|e| format!("kp_from_bytes: {e:?}"))?;
            builder = builder.add_member(kp).map_err(|e| format!("add: {e:?}"))?;
        }
        let commit = builder.build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let gid = g.group_id().to_vec();
        // epoch_authenticator of the post-add epoch
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        g.write_to_storage().map_err(|e| format!("store: {e:?}"))?;
        let welcome = commit.welcome_messages.first()
            .ok_or_else(|| "create: commit produced no Welcome".to_string())?
            .to_bytes().map_err(|e| format!("{e:?}"))?;
        let commit_b = commit.commit_message.to_bytes().map_err(|e| format!("{e:?}"))?;
        let ginfo = commit.external_commit_group_info
            .ok_or_else(
                || "no external_commit_group_info (allow_external_commit off?)".to_string())?
            .to_bytes().map_err(|e| format!("ginfo: {e:?}"))?;
        // The ratchet tree of the post-add epoch, exported on its own because the create request
        // carries GroupInfo and tree as separate fields. Slot 5.
        let tree = g.export_tree().to_bytes().map_err(|e| format!("export_tree: {e:?}"))?;
        let artifacts = join_len_prefixed(&[welcome, commit_b, ginfo, tag, gid.clone(), tree]);
        Ok((gid, artifacts))
    }
    /// Loads `gid` for a commit or an inbound message, first making its prior-epoch archive one
    /// mls-rs will extend (see [`FileGroupStateStorage::heal_contiguity`]). A record left with a
    /// gap by an older trim otherwise fails every commit with `InvalidEpoch` until the member
    /// re-joins. `every_apply_site_loads_through_the_heal` pins the sites.
    fn load_for_apply(&self, gid: &[u8], verb: &str) -> Result<Group<ProdConfig>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let epoch = g.context().epoch;
        let n = FileGroupStateStorage::new(self.storage_dir.clone()).heal_contiguity(gid, epoch);
        if n > 0 {
            alog!("{verb}: dropped {n} discontinuous archived epoch(s) for {} at epoch {epoch} — \
                mls-rs refuses to archive an epoch that is not the stored max + 1, so every commit \
                on this group was failing InvalidEpoch", gid_str(gid));
        }
        Ok(g)
    }

    /// Drops the prior-epoch archive of a group just joined, before its state is written, on every
    /// join path (Welcome and external commit). See [`FileGroupStateStorage::purge_epochs`].
    fn purge_prior_epochs_on_join(&self, gid: &[u8], how: &str) {
        let n = FileGroupStateStorage::new(self.storage_dir.clone()).purge_epochs(gid);
        if n > 0 {
            alog!("{how}: dropped {n} archived prior epoch(s) left over from a PREVIOUS membership \
                of this group. Keeping them makes the NEXT commit fail InvalidEpoch at its own \
                epoch, which strands this member permanently.");
        }
    }

    /// Formats an extension list as ` 0xTTTT=NB` pairs, the shape of the host's `GROUP-EXT` lines;
    /// an empty list gives `" (none)"`.
    fn ext_list_summary(exts: &CoreExtensionList) -> String {
        let mut s = String::new();
        for e in exts.iter() {
            let ty: u16 = (*e.extension_type()).into();
            s.push_str(&format!(" 0x{:04X}={}B", ty, e.extension_data.len()));
        }
        if s.is_empty() { " (none)".to_string() } else { s }
    }

    /// Logs the extensions of a Welcome's decrypted GroupInfo (`GroupInfo.extensions`, not the
    /// GroupContext's). Log only: never refuses a join, changes state or reads a value. Those bytes
    /// are in the clear only inside `join_group`, and they are where RCC.16 v4.0 §7.11.12.1 puts
    /// the continuity token; [`ProdSession::capture_welcome_continuity_token`] is the separate
    /// consumer.
    fn log_welcome_group_info_exts(how: &str, gid: &[u8], info: &mls_rs::group::NewMemberInfo) {
        let line = Self::welcome_gi_ext_line(how, gid, info.sender, info.group_info_extensions());
        alog!("{line}");
        #[cfg(test)]
        LAST_WELCOME_GI_EXT_LINE.with(|c| *c.borrow_mut() = Some(line));
    }

    /// The log line, split out so a host test can assert it (`alog!` is a no-op off Android).
    fn welcome_gi_ext_line(how: &str, gid: &[u8], sender: u32, exts: &CoreExtensionList) -> String {
        let token = exts.get(ExtensionType::from(crate::rcc16::CONTINUITY_TOKEN_EXT)).is_some();
        format!("WELCOME-GI-EXT {how} g:{} committer_leaf={sender} — DECRYPTED GroupInfo's OWN \
extensions (not the GroupContext's):{}  [{}]",
            gid_str(gid), Self::ext_list_summary(exts),
            if token {
                "0xF010 IS PRESENT — the peer that built this Welcome IS minting a continuity \
token, which OVERTURNS the do-not-mint prohibition in rcc16.rs"
            } else {
                "no 0xF010 — this Welcome's builder minted no continuity token. Read it as a fact \
about THIS committer only: a Welcome we built shows our own behaviour, and only a \
GOOGLE-MESSAGES-originated Welcome answers the question"
            })
    }

    /// Captures the RCC.16 §7.11.12.1 continuity token from a Welcome's decrypted GroupInfo, the
    /// only moment it is readable, for the host to persist. Stores the decoded value, matching the
    /// token delivered by RCC.16 §10.5.4. Never refuses a join: an undecodable token is logged and
    /// dropped.
    fn capture_welcome_continuity_token(&self, gid: &[u8], info: &mls_rs::group::NewMemberInfo) {
        let exts = info.group_info_extensions();
        let ty = crate::rcc16::CONTINUITY_TOKEN_EXT;
        let Some(e) = exts.get(ExtensionType::from(ty)) else {
            // Not an error: our own Welcomes and peers without continuity carry none.
            alog!("WELCOME-CONTINUITY g:{} — no 0x{ty:04X} in the decrypted GroupInfo; nothing to \
persist. Says only that THIS Welcome's builder minted no token.", gid_str(gid));
            return;
        };
        let raw = e.extension_data();
        match crate::rcc16::ext_decode(ty, raw) {
            Ok(v) => {
                let framed = raw.len() != v.len();
                let mut q = match self.welcome_continuity.lock() {
                    Ok(q) => q,
                    // poisoned: keep going rather than fail a join that has succeeded
                    Err(poisoned) => poisoned.into_inner(),
                };
                q.retain(|(g, _)| g != gid);
                q.push((gid.to_vec(), v.clone()));
                while q.len() > WELCOME_CONTINUITY_SLOTS { q.remove(0); }
                // length and framing only; the value is a group secret
                alog!(
                    "WELCOME-CONTINUITY g:{} CAPTURED {}B token from 0x{ty:04X} ({}B on the wire, \
{}) — held for the host to persist; {} slot(s) pending",
                    gid_str(gid), v.len(), raw.len(),
                    if framed { "opaque<V>-framed, the shape peers send" }
                    else { "BARE — no inner length; tolerated, and worth reporting" },
                    q.len());
            }
            Err(err) => alog!("WELCOME-CONTINUITY g:{} UNDECODABLE 0x{ty:04X} ({}B): {err} — NOT \
persisted. The join stands; this only means we hold no token for this group.",
                gid_str(gid), raw.len()),
        }
    }

    /// Takes the token captured at `gid`'s join and forgets it here; empty when none was captured
    /// (our own groups, external-commit joins, peers without continuity). A second call returns
    /// empty.
    pub fn take_welcome_continuity_token(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let mut q = match self.welcome_continuity.lock() {
            Ok(q) => q,
            Err(poisoned) => poisoned.into_inner(),
        };
        match q.iter().position(|(g, _)| g == gid) {
            Some(i) => Ok(q.remove(i).1),
            None => Ok(Vec::new()),
        }
    }

    pub fn join(&self, welcome: &[u8]) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let w = MlsMessage::from_bytes(welcome).map_err(|e| format!("{e:?}"))?;
        let (mut g, info) =
            self.client.join_group(None, &w, None).map_err(|e| format!("join: {e:?}"))?;
        let gid = g.group_id().to_vec();
        Self::log_welcome_group_info_exts("join", &gid, &info);
        self.capture_welcome_continuity_token(&gid, &info);
        self.purge_prior_epochs_on_join(&gid, "join");
        g.write_to_storage().map_err(|e| format!("{e:?}"))?;
        Ok(gid)
    }
    /// Welcome join with the ratchet tree supplied out of band (an exported RFC 9420 ratchet tree),
    /// for a Welcome whose GroupInfo carries none. Empty uses the tree in the Welcome.
    pub fn join_with_tree(&self, welcome: &[u8], ratchet_tree: &[u8]) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let w = MlsMessage::from_bytes(welcome).map_err(|e| format!("{e:?}"))?;
        let tree = if ratchet_tree.is_empty() {
            None
        } else {
            Some(ExportedTree::from_bytes(ratchet_tree)
                .map_err(|e| format!("tree_from_bytes: {e:?}"))?)
        };
        let (mut g, info) = self.client.join_group(tree, &w, None)
            .map_err(|e| format!("join_with_tree: {e:?}"))?;
        let gid = g.group_id().to_vec();
        Self::log_welcome_group_info_exts("join_with_tree", &gid, &info);
        self.capture_welcome_continuity_token(&gid, &info);
        self.purge_prior_epochs_on_join(&gid, "join_with_tree");
        g.write_to_storage().map_err(|e| format!("{e:?}"))?;
        Ok(gid)
    }
    /// Join for a Welcome without a ratchet tree: `welcome` is the bare Welcome and `blob` the
    /// whole payload, whose trailing member LeafNodes are spliced into the tree (see
    /// treeless_welcome).
    pub fn join_treeless_welcome(&self, welcome: &[u8], blob: &[u8]) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let w = MlsMessage::from_bytes(welcome).map_err(|e| format!("welcome_from_bytes: {e:?}"))?;
        let tree_bytes = crate::treeless_welcome::splice_ratchet_tree(blob)?;
        let n = crate::treeless_welcome::leaf_count(blob);
        let tree = ExportedTree::from_bytes(&tree_bytes)
            .map_err(|e| format!("apple_tree_from_bytes({n} leaves): {e:?}"))?;
        let (mut g, info) = self.client.join_group(Some(tree), &w, None)
            .map_err(|e| format!("join_treeless_welcome({n} leaves): {e:?}"))?;
        let gid = g.group_id().to_vec();
        Self::log_welcome_group_info_exts("join_treeless_welcome", &gid, &info);
        self.capture_welcome_continuity_token(&gid, &info);
        self.purge_prior_epochs_on_join(&gid, "join_treeless_welcome");
        g.write_to_storage().map_err(|e| format!("{e:?}"))?;
        Ok(gid)
    }
    /// External-commit join (RFC 9420 §12.4.3.2) from a peer group's published GroupInfo. Returns
    /// length-prefixed {group_id, external commit}; the caller sends the commit. `ratchet_tree` is
    /// the tree when delivered out of band; empty uses the GroupInfo's extension.
    pub fn external_join(&self, group_info: &[u8], ratchet_tree: &[u8]) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let gi = MlsMessage::from_bytes(group_info)
            .map_err(|e| format!("group_info_from_bytes: {e:?}"))?;
        let mut builder = self.client.external_commit_builder()
            .map_err(|e| format!("external_commit_builder: {e:?}"))?;
        // An external commit mints our leaf, which needs the lifetime anchor too.
        if let Some(t) = self.leaf_lifetime_anchor() { builder = builder.commit_time(t); }
        if !ratchet_tree.is_empty() {
            let tree = ExportedTree::from_bytes(ratchet_tree)
                .map_err(|e| format!("tree_from_bytes: {e:?}"))?;
            builder = builder.with_tree_data(tree);
        }
        let (mut g, commit) = builder.build(gi)
            .map_err(|e| format!("external_commit build: {e:?}"))?;
        let gid = g.group_id().to_vec();
        self.purge_prior_epochs_on_join(&gid, "external_commit");
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let commit_b = commit.to_bytes().map_err(|e| format!("commit_to_bytes: {e:?}"))?;
        Ok(join_len_prefixed(&[gid, commit_b]))
    }
    /// The 32-byte epoch_authenticator of the group's current epoch (RCC.16 §7.11), stamped on the
    /// CPIM `mls.Epoch-Authenticator` header.
    pub fn epoch_auth(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        Ok(g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec())
    }
    /// The RCC.16 AuthenticatedData for an operation on `gid`. The host supplies only the
    /// message_id; the era is read from the group's own 0xF001, never from the caller. `trailing`
    /// empty means the RCC.16 §10.3 resent-message component is absent.
    fn aad_for(&self, gid: &[u8], message_id: &[u8], trailing: &[u8]) -> Vec<u8> {
        let era = match self.client.load_group(gid) {
            Ok(g) => g
                .context()
                .extensions()
                .get(ExtensionType::from(crate::rcc16::ERA_EXT))
                .and_then(|e| {
                    e.extension_data
                        .get(0..4)
                        .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
                })
                .unwrap_or(1),
            // A group that cannot be loaded has no era; use 1.
            Err(_) => 1,
        };
        // A one-shot component set by the host wins over `trailing` and is consumed.
        let oneshot = NEXT_RESENT_COMPONENT.with(|c| {
            let v = c.borrow().clone();
            c.borrow_mut().clear();
            v
        });
        let tail: &[u8] = if !oneshot.is_empty() { &oneshot } else { trailing };
        if !oneshot.is_empty() {
            alog!("AAD carries a §10.3 RESENT COMPONENT ({}B) — probe path", oneshot.len());
        }
        crate::rcc16::build_authenticated_data(message_id, era, tail)
    }

    /// The group's current `[era u32 BE][epoch u64 BE]` (12 bytes); era 1 if 0xF001 is absent.
    pub fn era_epoch(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let era: u32 = g.context().extensions()
            .get(ExtensionType::from(crate::rcc16::ERA_EXT))
            .and_then(|e| e.extension_data.get(0..4)
                .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]])))
            .unwrap_or(1);
        let epoch: u64 = g.current_epoch();
        let mut out = Vec::with_capacity(12);
        out.extend_from_slice(&era.to_be_bytes());
        out.extend_from_slice(&epoch.to_be_bytes());
        Ok(out)
    }
    /// Drops the cached sending group; every group-modifying op must call this.
    fn invalidate_send_cache(&self) {
        if let Ok(mut c) = self.send_cache.lock() { *c = None; }
    }

    /// The `Lifetime.not_before` anchor for every leaf we mint. Never pass `None` to mls-rs here:
    /// `None` means now, and a peer whose clock trails refuses the leaf (RFC 9420 §7.3). mls-rs
    /// mints a lifetime at three sites (KeyPackage, group creation, external commit), all fed from
    /// here. The anchor is `now - SKEW`, clamped to at most now and at least the certificate's
    /// notBefore, since mls-rs also validates the chain at this instant. See docs/mls/rust-core.md.
    fn leaf_lifetime_anchor(&self) -> Option<MlsTime> {
        const SKEW: u64 = 24 * 3600;
        let now = wall_clock_secs();
        let floor = self.kp_not_before.map(|t| t.seconds_since_epoch()).unwrap_or(0);
        let want = now.saturating_sub(SKEW).max(floor).min(now);
        // An unreadable clock gives no sane anchor; leave it to mls-rs.
        if now == 0 { None } else { Some(MlsTime::from(want)) }
    }

    /// The application generation the next `encrypt` will use for our leaf (a peek). The RCC.16
    /// body header's uint32 carries the same number, so the caller frames with it before
    /// encrypting.
    pub fn next_app_gen(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let gen = g.peek_next_key_generation().unwrap_or(0);
        Ok(gen.to_be_bytes().to_vec())
    }

    pub fn encrypt(&self, gid: &[u8], pt: &[u8], message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        // Reuse the cached group for consecutive sends in one epoch; see `send_cache`.
        let mut cache = self.send_cache.lock().map_err(|_| "send_cache poisoned".to_string())?;
        // Reload for a different group, or when the stored epoch has moved past the cached one (a
        // commit applied elsewhere). Resets that can lower the epoch clear the cache explicitly.
        let need_load = match cache.as_ref() {
            Some((cg, g)) => cg.as_slice() != gid
                || FileGroupStateStorage::new(self.storage_dir.clone()).current_max_epoch(gid)
                    > g.context().epoch,
            None => true,
        };
        if need_load {
            let loaded = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
            *cache = Some((gid.to_vec(), loaded));
        }
        let g = &mut cache.as_mut().unwrap().1;
        // The AEAD covers `authenticated_data`; peers set the RCC.16 AuthenticatedData there, and
        // an empty AAD fails their decrypt as a key generation mismatch. `aad` comes from
        // `aad_for`.
        {
            let idx = g.current_member_index();
            let epoch = g.context().epoch;
            let gen = g.peek_next_key_generation().unwrap_or(u32::MAX);
            // No epoch authenticator: it is a group secret.
            alog!("encrypt-diag: self_leaf_index={idx} epoch={epoch} next_app_gen={gen} aad={}B",
                aad.len());
        }
        let ct =
            g.encrypt_application_message(pt, aad.to_vec()).map_err(|e| format!("enc: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("{e:?}"))?;
        ct.to_bytes().map_err(|e| format!("{e:?}"))
    }
    /// The repeated-result form of `encrypt`. The ciphertext is produced first, at the current
    /// epoch, then an optional key-update commit (`want_key_update`, decided by the host, which
    /// keeps the usage counter). Both come back from one call, so a caller cannot keep the
    /// ciphertext and lose the commit; the whole list must be dispatched before branching on
    /// status.
    pub fn encrypt_results(&self, gid: &[u8], pt: &[u8], message_id: &[u8], context_id: &[u8],
            want_key_update: bool) -> Result<Vec<u8>, String> {
        const FORMAT_VERSION: u8 = 1;
        // encrypt() builds the AAD from the id
        let ct = self.encrypt(gid, pt, message_id)?;
        let mut records: Vec<Vec<u8>> = vec![vec![FORMAT_VERSION]];
        // status 0 = application: the ciphertext to send
        records.push(join_len_prefixed(&[
            vec![0u8], context_id.to_vec(), gid.to_vec(), Vec::new(), ct,
        ]));
        if want_key_update {
            // The self-update applies the commit locally before the server accepts it, and a
            // refused commit would leave us an epoch ahead. The snapshot has to be taken here,
            // between the encrypt and the commit, and goes back as a third record for the host to
            // restore on refusal. It is taken after the ciphertext, so the consumed generation
            // stays consumed.
            let rollback = self.export_group_snapshot(gid).unwrap_or_default();
            match self.self_update(gid, message_id) {
                Ok(bundle) => {
                    let parts = split_len_prefixed(&bundle);
                    // the bundle's commit is slot 1, the only part a send needs
                    if let Some(commit) = parts.get(1) {
                        if !commit.is_empty() {
                            // status 1 = commit
                            records.push(join_len_prefixed(&[
                                vec![1u8], context_id.to_vec(), gid.to_vec(), Vec::new(),
                                commit.clone(),
                            ]));
                            // status 3 = other: the pre-commit snapshot, only alongside a commit
                            if !rollback.is_empty() {
                                records.push(join_len_prefixed(&[
                                    vec![3u8], context_id.to_vec(), gid.to_vec(), Vec::new(),
                                    rollback,
                                ]));
                            } else {
                                alog!("encrypt_results: no rollback snapshot available — a REFUSED \
                                    key update will leave this group epoch-AHEAD");
                            }
                        }
                    }
                }
                Err(e) => {
                    alog!("encrypt_results: key update FAILED ({e}) — returning the ciphertext \
                        alone; the host will see no commit and rotate on a later send");
                }
            }
        }
        Ok(join_len_prefixed(&records))
    }

    pub fn process(&self, gid: &[u8], wire: &[u8]) -> Result<Vec<u8>, String> {
        // Clear first, as process_ex does, so a stale AAD is never attributed to this message.
        LAST_AAD.with(|c| c.borrow_mut().clear());
        LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = None);
        LAST_SENDER_MSISDN.with(|c| c.borrow_mut().clear());
        let mut g = self.load_for_apply(gid, "process")?;
        let pre_epoch = g.context().epoch;
        let msg = MlsMessage::from_bytes(wire).map_err(|e| format!("{e:?}"))?;
        let res = g.process_incoming_message(msg).map_err(|e| format!("process: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("{e:?}"))?;
        // An inbound commit advances the epoch; a cached pre-commit group would be written back
        // over it by the next encrypt.
        if g.context().epoch != pre_epoch {
            self.invalidate_send_cache();
        }
        // Expose the AAD, and record the RCC.16 §7.5.3.1 message-id verdict the host refuses on.
        // This is the production inbound path.
        if let ReceivedMessage::ApplicationMessage(ref m) = res {
            LAST_AAD.with(|c| *c.borrow_mut() = m.authenticated_data.clone());
                    note_message_id_check(&m.authenticated_data);
            // the sender, authenticated by the decrypt rather than taken from the envelope
            let who = Self::certified_msisdn_of(&g, m.sender_index);
            LAST_SENDER_MSISDN.with(|c| *c.borrow_mut() = who);
        }
        Ok(match res { ReceivedMessage::ApplicationMessage(m) => m.data().to_vec(),
            _ => Vec::new() })
    }

    // ---- group mutation, resync, delete, status-tagged process ----

    /// Adds a member to an existing group. Returns the bundle `[welcome, commit, groupInfo, tag,
    /// gid, tree]`. Applied optimistically.
    pub fn add_member(&self, gid: &[u8], peer_kp: &[u8],
            message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        // A commit advances the epoch; drop the cached pre-commit group.
        self.invalidate_send_cache();
        let mut g = self.load_for_apply(gid, "add_member")?;
        let wrapped = if peer_kp.starts_with(&MLS_KEYPACKAGE_MSG_PREFIX) {
            peer_kp.to_vec()
        } else {
            wrap_key_package(peer_kp)
        };
        let kp = MlsMessage::from_bytes(&wrapped).map_err(|e| format!("kp_from_bytes: {e:?}"))?;
        // The server rejects a membership commit with an empty AAD.
        let commit = commit_builder(&mut g).add_member(kp).map_err(|e| format!("add: {e:?}"))?
            .authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        commit_bundle(&mut g, commit)
    }

    /// Adds every KeyPackage in one commit (RCC.16 §14.1, §15.2), so a participant's devices join
    /// together, with one Welcome. N commits would leave intermediate epochs with the participant
    /// half-added. Mirrors `remove_member_by_msisdn`. An empty list is refused.
    pub fn add_members(&self, gid: &[u8], peer_kps: &[Vec<u8>], message_id: &[u8])
            -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        if peer_kps.is_empty() {
            return Err("add_members: no KeyPackages — an empty add would advance the epoch and \
                        add nobody".to_string());
        }
        self.invalidate_send_cache();
        let mut g = self.load_for_apply(gid, "add_members")?;
        let mut builder = commit_builder(&mut g);
        for (i, peer_kp) in peer_kps.iter().enumerate() {
            let wrapped = if peer_kp.starts_with(&MLS_KEYPACKAGE_MSG_PREFIX) {
                peer_kp.clone()
            } else {
                wrap_key_package(peer_kp)
            };
            let kp = MlsMessage::from_bytes(&wrapped)
                .map_err(|e| format!("kp_from_bytes[{i}]: {e:?}"))?;
            // one builder, so one commit with N Add proposals and one Welcome
            builder = builder.add_member(kp).map_err(|e| format!("add[{i}]: {e:?}"))?;
        }
        alog!("add_members: {} KeyPackage(s) in ONE commit for {}", peer_kps.len(), gid_str(gid));
        let commit = builder
            .authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        commit_bundle(&mut g, commit)
    }

    /// The certified MSISDN of a roster leaf, empty when it has none. Same field as the A.4.1
    /// claim-time check (`leaf_san_msisdn`). Empty is unknown, not a mismatch: refusing on it would
    /// drop traffic from any credential shape we cannot read.
    fn certified_msisdn_of(g: &Group<ProdConfig>, leaf_index: u32) -> Vec<u8> {
        g.roster().members().iter()
            .find(|m| m.index == leaf_index)
            .and_then(|m| match m.signing_identity.credential {
                mls_rs_core::identity::Credential::X509(ref chain) => chain.leaf()
                    .and_then(|l| crate::rcc16_validate::leaf_san_msisdn(l.as_ref())),
                _ => None,
            })
            .unwrap_or_default()
    }

    /// Removes every leaf certified to an MSISDN in one commit (RCC.16 §15.2): a participant is a
    /// person with possibly several devices, and removing one leaf would leave another receiving
    /// while the UI reports the person removed.
    pub fn remove_member_by_msisdn(&self, gid: &[u8], msisdn: &[u8], message_id: &[u8])
            -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        self.invalidate_send_cache();
        let mut g = self.load_for_apply(gid, "remove_member_by_msisdn")?;
        let self_idx = g.current_member_index();
        let targets: Vec<u32> = g.roster().members().iter()
            .filter(|m| m.index != self_idx)
            .filter(|m| {
                let leaf = Self::certified_msisdn_of(&g, m.index);
                !leaf.is_empty() && crate::rcc16_validate::msisdn_equals(&leaf, msisdn)
            })
            .map(|m| m.index)
            .collect();
        if targets.is_empty() {
            // no leaf for this MSISDN: already absent
            return Err(format!("remove_by_msisdn: no leaf certified to {} in this group",
                masked_msisdn(msisdn)));
        }
        alog!("remove_member_by_msisdn: removing {} leaf/leaves for {} in ONE commit",
            targets.len(), masked_msisdn(msisdn));
        let mut builder = commit_builder(&mut g);
        for idx in &targets {
            builder = builder.remove_member(*idx).map_err(|e| format!("rm {idx}: {e:?}"))?;
        }
        let commit = builder.authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        commit_bundle(&mut g, commit)
    }

    /// DER of the leaf certificate in a `SigningIdentity`; `None` for a non-X.509 credential.
    fn leaf_der_of(id: &SigningIdentity) -> Option<Vec<u8>> {
        match id.credential {
            mls_rs_core::identity::Credential::X509(ref chain) =>
                chain.leaf().map(|l| l.as_ref().to_vec()),
            _ => None,
        }
    }

    /// Whether our leaf in this group certifies a different certificate from the one the client
    /// holds. Compared by certificate DER, since the subject key is stable across renewals.
    /// `false` when either side is not X.509 or our leaf is not found.
    fn credential_differs_from_group(&self, g: &Group<ProdConfig>) -> bool {
        let ours = match Self::leaf_der_of(&self.signing_identity) { Some(d) => d,
            None => return false };
        let idx = g.current_member_index();
        let roster = g.roster();
        let mine = match roster.members().iter().find(|m| m.index == idx) {
            Some(m) => m.clone(), None => return false,
        };
        match Self::leaf_der_of(&mine.signing_identity) {
            Some(theirs) => theirs != ours,
            None => false,
        }
    }

    /// Our own leaf in this group against the certificate the client holds, for the
    /// certificate-driven self-update.
    ///
    /// Wire (37 bytes): `[u32 leaf_index BE][u64 group_nb][u64 group_na][u64 client_nb]
    /// [u64 client_na][u8 stale]`. An unparseable window is reported as `0/0`, never skipped.
    /// `stale` is 1 when the certificate DERs differ.
    pub fn self_leaf_status(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        use x509_cert::Certificate;
        use x509_cert::der::Decode;
        fn window(id: &SigningIdentity) -> (u64, u64) {
            ProdSession::leaf_der_of(id)
                .and_then(|l| Certificate::from_der(&l).ok())
                .map(|c| (c.tbs_certificate.validity.not_before.to_unix_duration().as_secs(),
                          c.tbs_certificate.validity.not_after.to_unix_duration().as_secs()))
                .unwrap_or((0, 0))
        }
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let idx = g.current_member_index();
        let roster = g.roster();
        let mine = roster.members().iter().find(|m| m.index == idx).cloned()
            .ok_or_else(|| "self_leaf_status: our own leaf is not in the roster".to_string())?;
        let (gnb, gna) = window(&mine.signing_identity);
        let (cnb, cna) = window(&self.signing_identity);
        let stale = self.credential_differs_from_group(&g);
        let mut out = Vec::with_capacity(37);
        out.extend_from_slice(&idx.to_be_bytes());
        out.extend_from_slice(&gnb.to_be_bytes());
        out.extend_from_slice(&gna.to_be_bytes());
        out.extend_from_slice(&cnb.to_be_bytes());
        out.extend_from_slice(&cna.to_be_bytes());
        out.push(if stale { 1 } else { 0 });
        Ok(out)
    }

    /// Every member's certificate validity window, for the expiry refresh (`MlsMaintenancePolicy`).
    ///
    /// Wire: `[u32 leaf_index BE][u64 not_before BE][u64 not_after BE]` per member, 20 bytes each.
    /// A member that is not X.509 or does not parse is reported as `0/0` rather than skipped, so
    /// "could not read" never reads as "not expired".
    pub fn member_validity(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        use x509_cert::Certificate;
        use x509_cert::der::Decode;
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let mut out = Vec::new();
        for m in g.roster().members().iter() {
            let (nb, na) = match m.signing_identity.credential {
                mls_rs_core::identity::Credential::X509(ref chain) => chain.leaf()
                    .and_then(|l| Certificate::from_der(l.as_ref()).ok())
                    .map(|c| (c.tbs_certificate.validity.not_before.to_unix_duration().as_secs(),
                              c.tbs_certificate.validity.not_after.to_unix_duration().as_secs()))
                    .unwrap_or((0, 0)),
                _ => (0, 0),
            };
            out.extend_from_slice(&m.index.to_be_bytes());
            out.extend_from_slice(&nb.to_be_bytes());
            out.extend_from_slice(&na.to_be_bytes());
        }
        Ok(out)
    }

    /// The committer of the epoch a GroupInfo describes, from the bytes alone: RFC 9420
    /// `GroupInfo.signer` via `GroupInfo::sender()`. On the server's GroupInfo it names the member
    /// whose commit produced the epoch; on one we generated, us. The GroupInfo's own epoch comes
    /// with it so the caller can tell which epoch was signed.
    ///
    /// Wire: `[u32 signer_leaf_index BE][u64 group_info_epoch BE]`, 12 bytes.
    pub fn group_info_signer(&self, group_info: &[u8]) -> Result<Vec<u8>, String> {
        if group_info.is_empty() {
            return Err("empty GroupInfo — nothing to read (NOT 'no signer')".to_string());
        }
        let msg = MlsMessage::from_bytes(group_info)
            .map_err(|e| format!("group_info_from_bytes: {e:?}"))?;
        let gi = msg.into_group_info()
            .ok_or_else(|| "those bytes are not a GroupInfo".to_string())?;
        let mut out = gi.sender().to_be_bytes().to_vec();
        out.extend_from_slice(&gi.group_context().epoch.to_be_bytes());
        Ok(out)
    }

    /// Every leaf's certificate window in a serialized ratchet tree, such as the server's copy.
    /// Read-only: parses without loading, joining or mutating anything. `member_validity` reports
    /// the local copy instead, which can differ from the server's.
    ///
    /// Wire, per leaf: `[u32 index BE][u64 not_before BE][u64 not_after BE][u32 msisdn_len BE]
    /// [msisdn]`. A leaf that is not X.509 or does not parse is reported with zeros and an empty
    /// MSISDN rather than skipped.
    pub fn tree_member_validity(&self, ratchet_tree: &[u8]) -> Result<Vec<u8>, String> {
        use x509_cert::Certificate;
        use x509_cert::der::Decode;
        if ratchet_tree.is_empty() {
            return Err("empty ratchet tree — nothing to read (NOT an empty roster)".to_string());
        }
        let tree = ExportedTree::from_bytes(ratchet_tree)
            .map_err(|e| format!("tree_from_bytes: {e:?}"))?;
        let mut out = Vec::new();
        for m in tree.roster().members().iter() {
            let leaf: Option<Vec<u8>> = match m.signing_identity.credential {
                mls_rs_core::identity::Credential::X509(ref chain) =>
                    chain.leaf().map(|l| l.as_ref().to_vec()),
                _ => None,
            };
            let (nb, na) = leaf.as_deref()
                .and_then(|l| Certificate::from_der(l).ok())
                .map(|c| (c.tbs_certificate.validity.not_before.to_unix_duration().as_secs(),
                          c.tbs_certificate.validity.not_after.to_unix_duration().as_secs()))
                .unwrap_or((0, 0));
            let msisdn = leaf.as_deref()
                .and_then(crate::rcc16_validate::leaf_san_msisdn)
                .unwrap_or_default();
            out.extend_from_slice(&m.index.to_be_bytes());
            out.extend_from_slice(&nb.to_be_bytes());
            out.extend_from_slice(&na.to_be_bytes());
            out.extend_from_slice(&(msisdn.len() as u32).to_be_bytes());
            out.extend_from_slice(&msisdn);
        }
        Ok(out)
    }

    /// Per-leaf `(index, MSISDN, participant-key SPKI)`, for `MlsParticipantKeyResync.plan()` to
    /// tell a member on the current participant key from one on a superseded key. An unreadable
    /// leaf gets an empty key, which plan() treats as not examined, never as stale.
    ///
    /// Wire: `[u32 index][u32 msisdn_len][msisdn][u32 key_len][key]` per member.
    pub fn member_participant_keys(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let mut out = Vec::new();
        for m in g.roster().members().iter() {
            let leaf: Option<Vec<u8>> = match m.signing_identity.credential {
                mls_rs_core::identity::Credential::X509(ref chain) =>
                    chain.leaf().map(|l| l.as_ref().to_vec()),
                _ => None,
            };
            let msisdn = leaf.as_deref()
                .and_then(crate::rcc16_validate::leaf_san_msisdn)
                .unwrap_or_default();
            let key = leaf.as_deref()
                .and_then(crate::rcc16_validate::leaf_participant_key_spki)
                .unwrap_or_default();
            out.extend_from_slice(&m.index.to_be_bytes());
            out.extend_from_slice(&(msisdn.len() as u32).to_be_bytes());
            out.extend_from_slice(&msisdn);
            out.extend_from_slice(&(key.len() as u32).to_be_bytes());
            out.extend_from_slice(&key);
        }
        Ok(out)
    }

    /// Removes another member by signature public key (empty: the sole other member of a 1:1).
    /// Returns the bundle with an empty Welcome. Never removes ourselves.
    pub fn remove_member(&self, gid: &[u8], member_sig_pub: &[u8],
            message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        // A commit advances the epoch; drop the cached pre-commit group.
        self.invalidate_send_cache();
        let mut g = self.load_for_apply(gid, "remove_member")?;
        let self_idx = g.current_member_index();
        let members = g.roster().members();
        let target = members.iter()
            .find(|m| m.index != self_idx
                && (member_sig_pub.is_empty()
                    || m.signing_identity.signature_key.as_ref() == member_sig_pub))
            .map(|m| m.index)
            .ok_or_else(|| "remove: target member not found".to_string())?;
        let commit = commit_builder(&mut g).remove_member(target).map_err(|e| format!("rm: {e:?}"))?
            .authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        commit_bundle(&mut g, commit)
    }

    /// Self-update: an empty commit with an UpdatePath (same era and group id, epoch+1), giving a
    /// fresh encryption secret so both sides' application ratchets restart at generation 0. Used
    /// for the usage-limit rekey. The GroupInfo is the plain tree-less `group_info_message(false)`:
    /// one carrying external_pub makes the server demand the tree. Returns the bundle with an empty
    /// Welcome and tree.
    pub fn self_update(&self, gid: &[u8], message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        // A commit advances the epoch; drop the cached pre-commit group.
        self.invalidate_send_cache();
        let mut g = self.load_for_apply(gid, "self_update")?;
        // RCC.16 v4.0 §9.5.3: the UpdatePath's new leaf must carry a newly minted credential.
        // mls-rs keeps the old signing identity unless given a new one, so compare certificates
        // (the key is stable across renewals) and carry the new one in. The existing leaf's expiry
        // is not checked on a self-update (A.4.3.2), so this can rescue a stale member.
        let rotate = self.credential_differs_from_group(&g);
        let mut builder = commit_builder(&mut g).authenticated_data(aad.to_vec());
        if rotate {
            builder = builder.set_new_signing_identity(
                self.signer.clone(), self.signing_identity.clone());
            alog!("self_update: CARRYING A NEW CREDENTIAL into the UpdatePath (§9.5.3) — our leaf \
                   in this group certifies an older certificate than the one we now hold");
        }
        // The commit's authenticated_data is the RCC.16 AuthenticatedData from `aad_for`; the
        // server rejects an empty one.
        let commit = builder.build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let gid_v = g.group_id().to_vec();
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        // Plain tree-less GroupInfo; the server rebuilds the tree from the UpdatePath.
        let ginfo = g.group_info_message(false).map_err(|e| format!("ginfo: {e:?}"))?
            .to_bytes().map_err(|e| format!("ginfo_bytes: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let commit_b =
            commit.commit_message.to_bytes().map_err(|e| format!("commit_bytes: {e:?}"))?;
        Ok(join_len_prefixed(&[Vec::new(), commit_b, ginfo, tag, gid_v, Vec::new()]))
    }

    /// `self_update`, publishing a GroupInfo that carries `external_pub`. Only the committer can
    /// produce it, so without this the server's GroupInfo for our groups cannot support a resync
    /// external commit. The GroupInfo stays tree-less and the tree goes in the bundle's tree slot.
    pub fn self_update_extpub(&self, gid: &[u8], message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        self.invalidate_send_cache();
        let mut g = self.load_for_apply(gid, "self_update_extpub")?;
        // The same RCC.16 §9.5.3 credential carry as `self_update`.
        let rotate = self.credential_differs_from_group(&g);
        let mut builder = commit_builder(&mut g).authenticated_data(aad.to_vec());
        if rotate {
            builder = builder.set_new_signing_identity(
                self.signer.clone(), self.signing_identity.clone());
        }
        let commit = builder.build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let gid_v = g.group_id().to_vec();
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        // with external_pub, without an embedded tree
        let ginfo = g.group_info_message_allowing_ext_commit(false)
            .map_err(|e| format!("ginfo: {e:?}"))?
            .to_bytes().map_err(|e| format!("ginfo_bytes: {e:?}"))?;
        let post_tree = g.export_tree().to_bytes().map_err(|e| format!("export_tree: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let commit_b =
            commit.commit_message.to_bytes().map_err(|e| format!("commit_bytes: {e:?}"))?;
        alog!("self_update_extpub: gi={}B (external_pub) tree={}B", ginfo.len(), post_tree.len());
        Ok(join_len_prefixed(&[Vec::new(), commit_b, ginfo, tag, gid_v, post_tree]))
    }

    /// Commits the RCC.16 §7.11.2.2 `end_mls` extension, moving the conversation to unencrypted
    /// (RCC.16 §9.1.1), or with [`EndMlsOp::RemoveForRevival`] removes it. The current extensions
    /// are merged, since `set_group_context_ext` replaces the list.
    ///
    /// One of the two sites that may remove 0xF002 (the other is `create_group_carry` under
    /// [`AdvanceEraKind::Revival`]); removal is only for revival. The caller must have completed
    /// the transition to OngoingReviveMls first; that ordering is enforced on the Java side. See
    /// docs/mls/downgrade.md.
    pub fn commit_end_mls(&self, gid: &[u8], message_id: &[u8],
            op: EndMlsOp) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        self.invalidate_send_cache();
        let mut g = self.load_for_apply(gid, "commit_end_mls")?;
        let mut exts = g.context().extensions().clone();
        if op.removes() {
            alog!("commit_end_mls: REVIVAL — removing end_mls (0xF002). This is one of the two \
                   deliberate removal sites permitted by INVARIANT ED-1.");
            exts.remove(ExtensionType::from(crate::rcc16::END_MLS_EXT));
        } else {
            // Reason unset: this entry point takes an intent, not a cause. Under v4.0 that is an
            // empty extension_data; under v3.0 the literal.
            exts.set(Extension::new(ExtensionType::from(crate::rcc16::END_MLS_EXT),
                crate::rcc16::end_mls_payload(crate::rcc16::EndMlsReason::Unset)));
        }
        let commit = commit_builder(&mut g)
            .set_group_context_ext(exts).map_err(|e| format!("set_ext: {e:?}"))?
            .authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let gid_v = g.group_id().to_vec();
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        let ginfo = g.group_info_message(false).map_err(|e| format!("ginfo: {e:?}"))?
            .to_bytes().map_err(|e| format!("ginfo_bytes: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let commit_b = commit.commit_message.to_bytes().map_err(|e| format!("cb: {e:?}"))?;
        Ok(join_len_prefixed(&[Vec::new(), commit_b, ginfo, tag, gid_v, Vec::new()]))
    }

    /// Era advance on the existing group by a GroupContextExtensions commit. Always refused:
    /// the era cannot change inside an MLS group (see `Rcc16MlsRules`), so an era advance is a new
    /// group (`create_group_carry`). Kept for the host's `debug.rcs.mls_era_advance_mode` modes,
    /// whose `BUILD_FAILED` path falls back to the create.
    pub fn commit_era_advance(&self, _gid: &[u8], _aad: &[u8], _new_era: u32)
            -> Result<Vec<u8>, String> {
        Err("commit_era_advance: an era advance CANNOT be a GroupContextExtensions commit — the Era \
             (0xF001) is immutable within an MLS group instance (RCC.16 design §9.2; the peer's \
             GroupContextExtensionProposalChangesEraError). Advance by CREATING a new group at era+1 \
             reusing the RCS group id — create_group_carry, i.e. ERA_MODE_CREATE.".to_string())
    }

    /// Test-only: commits an arbitrary GroupContext extension, to show the carry deny-list
    /// preserves a code point it does not know.
    #[cfg(test)]
    fn commit_arbitrary_ext_for_test(&self, gid: &[u8], ty: u16, value: &[u8])
            -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let mut g = self.load_for_apply(gid, "commit_arbitrary_ext_for_test")?;
        let mut exts = g.context().extensions().clone();
        exts.set(Extension::new(ExtensionType::from(ty), value.to_vec()));
        let commit = commit_builder(&mut g)
            .set_group_context_ext(exts).map_err(|e| format!("set_ext: {e:?}"))?
            .build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let ginfo = g.group_info_message(false).map_err(|e| format!("ginfo: {e:?}"))?
            .to_bytes().map_err(|e| format!("ginfo_bytes: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let _ = commit;
        Ok(ginfo)
    }

    /// The pre-refusal body, test-only, so the era guard can be shown to fire on a real group.
    #[cfg(test)]
    fn commit_era_advance_illegal_for_test(&self, gid: &[u8], aad: &[u8], new_era: u32)
            -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let mut g = self.load_for_apply(gid, "commit_era_advance_illegal_for_test")?;
        // merge: every other extension must survive
        let mut exts = g.context().extensions().clone();
        exts.set(Extension::new(ExtensionType::from(crate::rcc16::ERA_EXT),
                                new_era.to_be_bytes().to_vec()));
        let commit = commit_builder(&mut g)
            .set_group_context_ext(exts).map_err(|e| format!("set_ext: {e:?}"))?
            .authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let gid_v = g.group_id().to_vec();
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        let ginfo = g.group_info_message(false).map_err(|e| format!("ginfo: {e:?}"))?
            .to_bytes().map_err(|e| format!("ginfo_bytes: {e:?}"))?;
        // the same tree, one epoch on
        let tree = g.export_tree().to_bytes().map_err(|e| format!("tree: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let commit_b = commit.commit_message.to_bytes().map_err(|e| format!("cb: {e:?}"))?;
        // slot 0, the Welcome, is empty: nobody joins
        Ok(join_len_prefixed(&[Vec::new(), commit_b, ginfo, tag, gid_v, tree]))
    }

    /// Commits the icon/subject commitment extensions (RCC.16 §7.11.4, §7.11.6); an empty slice
    /// leaves one untouched. The current extensions are merged. The key extensions (0xF003/0xF005)
    /// are not settable here, since they would reach the server's GroupInfo; see
    /// `commit_group_metadata`.
    pub fn commit_icon_subject(&self, gid: &[u8], message_id: &[u8],
                               icon_commitment: &[u8],
                               subject_commitment: &[u8]) -> Result<Vec<u8>, String> {
        self.commit_group_metadata(gid, message_id, &[], icon_commitment, &[], subject_commitment)
    }

    /// The RCC.16 §9.7.1.4/§9.7.1.5 metadata commit: keys and their commitments together, since the
    /// commitment binds the key to the ciphertext. An empty slice leaves one unchanged; the current
    /// extensions are merged. The GroupInfo this publishes carries the keys: RCC.16 §9.7.1.4's
    /// sanitised GroupInfo cannot be built with mls-rs (it embeds the whole GroupContext), and
    /// other clients put the key in the GroupContext too. See docs/mls/metadata.md.
    pub fn commit_group_metadata(&self, gid: &[u8], message_id: &[u8],
                                 icon_key: &[u8], icon_commitment: &[u8],
                                 subject_key: &[u8], subject_commitment: &[u8])
            -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        self.invalidate_send_cache();
        let mut g = self.load_for_apply(gid, "commit_group_metadata")?;
        let mut exts = g.context().extensions().clone();
        for (ty, val) in [
            (crate::rcc16::ICON_KEY_EXT, icon_key),
            (crate::rcc16::ICON_COMMITMENT_EXT, icon_commitment),
            (crate::rcc16::SUBJECT_KEY_EXT, subject_key),
            (crate::rcc16::SUBJECT_COMMITMENT_EXT, subject_commitment),
        ] {
            if !val.is_empty() {
                // Framed per type (rcc16::ext_encode): a 32-byte commitment is 33 bytes.
                exts.set(Extension::new(ExtensionType::from(ty),
                                        crate::rcc16::ext_encode(ty, val)?));
            }
        }
        let commit = commit_builder(&mut g)
            .set_group_context_ext(exts).map_err(|e| format!("set_ext: {e:?}"))?
            .authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let gid_v = g.group_id().to_vec();
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        let carries_key = crate::rcc16::WELCOME_ONLY_EXTS.iter().any(|ty|
            g.context().extensions().get(ExtensionType::from(*ty)).is_some());
        if carries_key {
            alog!("commit_group_metadata: GroupInfo carries a Welcome-only KEY extension \
                  (0xF003/0xF005) — required to bind the ciphertext");
        }
        let ginfo = g.group_info_message(false).map_err(|e| format!("ginfo: {e:?}"))?
            .to_bytes().map_err(|e| format!("ginfo_bytes: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let commit_b = commit.commit_message.to_bytes().map_err(|e| format!("cb: {e:?}"))?;
        Ok(join_len_prefixed(&[Vec::new(), commit_b, ginfo, tag, gid_v, Vec::new()]))
    }

    /// The GroupContext extension types and lengths of a serialized GroupInfo, as big-endian u16
    /// pairs. The continuity token (0xF010) is Welcome-only and never in a server GroupInfo; its
    /// commitment 0xF011 would be.
    pub fn group_info_ext_types(&self, group_info: &[u8]) -> Result<Vec<u8>, String> {
        let gi = MlsMessage::from_bytes(group_info)
            .map_err(|e| format!("parse: {e:?}"))?
            .into_group_info()
            .ok_or_else(|| "not a GroupInfo".to_string())?;
        let mut out = Vec::new();
        for ext in gi.group_context().extensions().iter() {
            let ty: u16 = (*ext.extension_type()).into();
            out.extend_from_slice(&ty.to_be_bytes());
            // length too, so an empty extension is visible
            out.extend_from_slice(&(ext.extension_data.len() as u16).to_be_bytes());
        }
        Ok(out)
    }

    /// One extension's value from a serialized GroupInfo, such as the server's copy. Decoded as
    /// [`Self::group_ext`] decodes, so the two compare directly. Read-only; works for a group this
    /// client never joined.
    pub fn group_info_ext(&self, group_info: &[u8], ext_type: u16) -> Result<Vec<u8>, String> {
        let gi = MlsMessage::from_bytes(group_info)
            .map_err(|e| format!("parse: {e:?}"))?
            .into_group_info()
            .ok_or_else(|| "not a GroupInfo".to_string())?;
        match gi.group_context().extensions().get(ExtensionType::from(ext_type)) {
            Some(e) => crate::rcc16::ext_decode(ext_type, &e.extension_data),
            None => Ok(Vec::new()),
        }
    }

    /// An RCC.16 group extension's value (icon/subject key or commitment), empty if absent. Returns
    /// the decoded value, inner varint stripped, so framing stays inside the engine. A payload that
    /// does not decode is an error rather than raw bytes.
    pub fn group_ext(&self, gid: &[u8], ext_type: u16) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        match g.context().extensions().get(ExtensionType::from(ext_type)) {
            Some(e) => crate::rcc16::ext_decode(ext_type, &e.extension_data),
            None => Ok(Vec::new()),
        }
    }

    /// Whether the group carries `end_mls`, i.e. encrypted sending is forbidden (RCC.16 §9.1.1).
    pub fn end_mls_present(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let present = g.context().extensions()
            .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_some();
        Ok(vec![if present { 1u8 } else { 0u8 }])
    }

    /// RCC.16 §7.9.2 / §7.11.12: a GroupInfo carrying the continuity-token commitment. The token
    /// itself is Welcome-only and never goes in this server-bound GroupInfo. The host computes
    /// `commitment` (`MlsContinuityToken.commitment`), since the token is conversation state that
    /// outlives the MLS group. Under v3.0 the ordinary GroupInfo, so callers may call it always.
    pub fn group_info_with_continuity(&self, gid: &[u8], commitment: &[u8], with_tree: bool)
            -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let mut exts = CoreExtensionList::new();
        if crate::rcc16::emit_continuity() && !commitment.is_empty() {
            let data = crate::rcc16::ext_encode(
                crate::rcc16::CONTINUITY_TOKEN_COMMITMENT_EXT, commitment)?;
            exts.set(Extension::new(
                ExtensionType::from(crate::rcc16::CONTINUITY_TOKEN_COMMITMENT_EXT), data));
        }
        let gi = g.group_info_message_internal(exts, with_tree)
            .map_err(|e| format!("group_info: {e:?}"))?;
        gi.to_bytes().map_err(|e| format!("group_info to_bytes: {e:?}"))
    }

    /// A continuity extension (0xF010 token, 0xF011 commitment) from a serialized GroupInfo,
    /// decoded, or empty when absent. Not version-gated. Searches both the GroupInfo's own list and
    /// its GroupContext's, and logs which one answered.
    pub fn group_info_continuity(&self, group_info: &[u8], ty: u16) -> Result<Vec<u8>, String> {
        let (v, _where) = self.group_info_ext_either_list(group_info, ty)?;
        Ok(v)
    }

    /// [`group_info_continuity`]'s worker: the value and which list held it (`"GroupInfo"`,
    /// `"GroupContext"` or `"absent"`), assertable in a host test.
    pub fn group_info_ext_either_list(&self, group_info: &[u8], ty: u16)
            -> Result<(Vec<u8>, &'static str), String> {
        let msg = MlsMessage::from_bytes(group_info)
            .map_err(|e| format!("group_info parse: {e:?}"))?;
        let gi = msg.into_group_info().ok_or("not a GroupInfo")?;
        // the GroupInfo's own list first, where 0xF010 lives
        let (val, from) = if let Some(e) = gi.extensions().get(ExtensionType::from(ty)) {
            (Self::decode_ext_for_read(ty, e.extension_data())?, "GroupInfo")
        } else if let Some(e) = gi.group_context().extensions().get(ExtensionType::from(ty)) {
            (Self::decode_ext_for_read(ty, e.extension_data())?, "GroupContext")
        } else {
            (Vec::new(), "absent")
        };
        // Say which list answered; the JNI result cannot.
        alog!(
              "GI-EXT-READ 0x{ty:04X} -> {} ({} bytes) [which list answered; 'absent' means neither]",
              from, val.len());
        Ok((val, from))
    }

    /// Decodes for a read: continuity code points lose their inner varint; anything else (this also
    /// reads 0x0004 external_pub and 0x0005 external_senders) is returned raw, since `ext_decode`
    /// refuses types it does not own.
    fn decode_ext_for_read(ty: u16, data: &[u8]) -> Result<Vec<u8>, String> {
        if ty == crate::rcc16::CONTINUITY_TOKEN_EXT
            || ty == crate::rcc16::CONTINUITY_TOKEN_COMMITMENT_EXT {
            crate::rcc16::ext_decode(ty, data)
        } else {
            Ok(data.to_vec())
        }
    }

    /// RCC.16 §7.6.2 sign: the `rcs_signature` PublicMessage over `derived_content` (the RCC.16
    /// §7.6.3 VerifiableDerivedContent, carried as `authenticated_data`). Uses the fork's
    /// `rcs_signature_message`, since `propose_custom` would cache the proposal and make a commit
    /// required.
    pub fn rcs_sign(&self, gid: &[u8], derived_content: &[u8]) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        // The rcs_signature body must be empty: the mls-rs codec writes it bare but decodes it as
        // empty without consuming bytes, so a non-empty body would corrupt what follows. The
        // signature rides in `derived_content`.
        let body: Vec<u8> = Vec::new();
        if !body.is_empty() {
            return Err(format!(
                "rcs_signature (0xF002) body must be EMPTY, got {}B — upstream encodes it bare and \
                 decodes it as zero-length without consuming, so a non-empty body would silently \
                 corrupt everything after it on the wire",
                body.len()));
        }
        let prop = CustomProposal::new(
            ProposalType::from(crate::rcc16::RCS_SIGNATURE_PROP), body);
        let msg = g.rcs_signature_message(prop, derived_content.to_vec())
            .map_err(|e| format!("sign: {e:?}"))?;
        msg.to_bytes().map_err(|e| format!("to_bytes: {e:?}"))
    }

    /// RCC.16 §7.6.2 validate: verifies an inbound `rcs_signature` PublicMessage and returns
    /// `[u32 BE leaf_index][derived_content]`, so the caller can check both the signer and the
    /// content. Empty when the signature or type check fails.
    pub fn rcs_verify(&self, gid: &[u8], msg_bytes: &[u8]) -> Result<Vec<u8>, String> {
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let msg = MlsMessage::from_bytes(msg_bytes).map_err(|e| format!("from_bytes: {e:?}"))?;
        let (data, sender) = g.validate_custom_proposal(
            &msg, Some(ProposalType::from(crate::rcc16::RCS_SIGNATURE_PROP)))
            .map_err(|e| format!("validate: {e:?}"))?;
        let leaf: u32 = match sender {
            Sender::Member(i) => i,
            _ => return Err("rcs_signature from a non-member sender".to_string()),
        };
        let mut out = Vec::with_capacity(4 + data.len());
        out.extend_from_slice(&leaf.to_be_bytes());
        out.extend_from_slice(&data);
        Ok(out)
    }

    /// Whether a cached by-reference proposal must be committed before another application message
    /// (mls-rs `commit_required`). A self_remove cannot be committed by the leaver, so another
    /// member sweeps it into their next commit.
    pub fn commit_required(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        Ok(vec![if g.commit_required() { 1u8 } else { 0u8 }])
    }

    /// Snapshot of the group's persisted record for rolling back an optimistic commit the server
    /// refuses; see `restore_group_snapshot`.
    pub fn export_group_snapshot(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let store = FileGroupStateStorage::new(self.storage_dir.clone());
        let max = store.current_max_epoch(gid);
        let state = store.export_state(gid).ok_or_else(|| "no persisted state".to_string())?;
        let mut out = Vec::with_capacity(8 + state.len());
        out.extend_from_slice(&max.to_be_bytes());
        out.extend_from_slice(&state);
        Ok(out)
    }

    /// Restores a snapshot from `export_group_snapshot`, dropping epoch records above its max
    /// epoch. Returns `[1]`.
    pub fn restore_group_snapshot(&self, gid: &[u8], snap: &[u8]) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        if snap.len() < 8 { return Err("snapshot too short".to_string()); }
        let mut e = [0u8; 8];
        e.copy_from_slice(&snap[..8]);
        let max = u64::from_be_bytes(e);
        let store = FileGroupStateStorage::new(self.storage_dir.clone());
        store.restore_state(gid, &snap[8..], max);
        Ok(vec![1u8])
    }

    /// Proposes our own removal: a by-reference SelfRemove proposal, since a member cannot commit
    /// its own Remove. Returns the bundle with the proposal in the commit slot and an empty Welcome
    /// and tree; a proposal does not advance the epoch, so the authenticator and GroupInfo are the
    /// current ones.
    pub fn self_leave(&self, gid: &[u8], message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        // A cached proposal changes commit_required(); drop the cached sender, which predates it.
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let proposal = g.propose_self_remove(aad.to_vec())
            .map_err(|e| format!("self_remove: {e:?}"))?;
        let gid_v = g.group_id().to_vec();
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        let ginfo = g.group_info_message(false).map_err(|e| format!("ginfo: {e:?}"))?
            .to_bytes().map_err(|e| format!("ginfo_bytes: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let prop_b = proposal.to_bytes().map_err(|e| format!("prop_bytes: {e:?}"))?;
        Ok(join_len_prefixed(&[Vec::new(), prop_b, ginfo, tag, gid_v, Vec::new()]))
    }

    /// Drops every by-reference proposal cached on this group. A cached proposal blocks application
    /// messages until committed, so one we cannot honour would wedge the conversation, and
    /// committing an opaque custom proposal changes nothing. mls-rs has no per-proposal eviction,
    /// so this clears the whole cache; call it only when the cache holds something unhonourable.
    pub fn clear_pending_proposals(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        g.clear_proposal_cache();
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        Ok(vec![1u8])
    }

    /// Whether a claimed KeyPackage carries the RFC 9420 `last_resort` extension (0x000A). A member
    /// joined on a last-resort leaf self-updates on every commit until it holds a one-time leaf, so
    /// callers prefer one-time packages. Accepts the raw or MLSMessage-wrapped form.
    pub fn kp_is_last_resort(&self, kp: &[u8]) -> Result<bool, String> {
        let wrapped = if kp.starts_with(
            &MLS_KEYPACKAGE_MSG_PREFIX) { kp.to_vec() } else { wrap_key_package(kp) };
        let msg = MlsMessage::from_bytes(&wrapped).map_err(|e| format!("kp_from_bytes: {e:?}"))?;
        let key_package = msg.into_key_package().ok_or_else(|| "not a KeyPackage".to_string())?;
        Ok(key_package.extensions.get(ExtensionType::from(0x000Au16)).is_some())
    }

    /// Everything the consume-side gate needs about a claimed KeyPackage, in one parse:
    ///
    /// ```text
    ///   [0]              last_resort (u8, 0/1)
    ///   [1..9]           leaf Lifetime not_after (u64 BE, seconds)
    ///   [9..11]          the KeyPackage's own cipher_suite (u16 BE)
    ///   [11]             n, the number of suites the leaf advertises (u8)
    ///   [12..12+2n]      those suites (u16 BE each, wire order)
    ///   [12+2n..+8]      the leaf certificate's notBefore (u64 BE, 0 = not readable)
    ///   [12+2n+8..+8]    the leaf certificate's notAfter  (u64 BE, 0 = not readable)
    ///   [12+2n+16..]     the certificate's SAN tel: identity, bare E.164 ASCII (may be empty)
    /// ```
    ///
    /// The MSISDN stays last, having no length of its own. mls-rs checks the package's own suite
    /// against the group but not the advertised list. The 30-day floor applies to the certificate
    /// window, the clock the server measures, not to the leaf Lifetime. A 0/0 window means not
    /// readable. Errors when the package has no `key_package` leaf source, since it cannot be
    /// dated. See docs/mls/credentials.md.
    pub fn kp_inspect(&self, kp: &[u8]) -> Result<Vec<u8>, String> {
        let wrapped = if kp.starts_with(
            &MLS_KEYPACKAGE_MSG_PREFIX) { kp.to_vec() } else { wrap_key_package(kp) };
        let msg = MlsMessage::from_bytes(&wrapped).map_err(|e| format!("kp_from_bytes: {e:?}"))?;
        let key_package = msg.into_key_package().ok_or_else(|| "not a KeyPackage".to_string())?;
        let last_resort = key_package.extensions.get(ExtensionType::from(0x000Au16)).is_some();
        let not_after = key_package.expiration()
            .map_err(|e| format!("expiration: {e:?}"))?.seconds_since_epoch();
        // The SAN tel: identity, for the caller's A.4.1 comparison with the number it queried.
        let msisdn = match key_package.signing_identity().credential {
            mls_rs_core::identity::Credential::X509(ref chain) => chain.leaf()
                .and_then(|l| crate::rcc16_validate::leaf_san_msisdn(l.as_ref()))
                .unwrap_or_default(),
            _ => Vec::new(),
        };
        // The certificate's validity window; 0/0 when not readable.
        let (cert_nb, cert_na) = match key_package.signing_identity().credential {
            mls_rs_core::identity::Credential::X509(ref chain) => chain.leaf()
                .and_then(|l| Certificate::from_der(l.as_ref()).ok())
                .map(|c| (c.tbs_certificate.validity.not_before.to_unix_duration().as_secs(),
                          c.tbs_certificate.validity.not_after.to_unix_duration().as_secs()))
                .unwrap_or((0, 0)),
            _ => (0, 0),
        };
        // An unreadable advertisement is logged and reported empty ("unknown") without failing the
        // inspection.
        let suites = match leaf_cipher_suites(&wrapped) {
            Ok(s) => s,
            Err(e) => {
                alog!("kp_inspect: leaf capabilities.cipher_suites unreadable ({e}) — reporting \
                    NO advertised suites, which the host must treat as UNKNOWN rather than as \
                    'the peer does not support our suite'");
                Vec::new()
            }
        };
        // u8 count; a longer list is truncated with a log line.
        let n = if suites.len() > u8::MAX as usize {
            alog!("kp_inspect: peer advertises {} cipher suites; reporting the first 255",
                suites.len());
            u8::MAX as usize
        } else { suites.len() };
        let mut out = Vec::with_capacity(12 + 2 * n + 16 + msisdn.len());
        out.push(u8::from(last_resort));
        out.extend_from_slice(&not_after.to_be_bytes());
        out.extend_from_slice(&u16::from(key_package.cipher_suite()).to_be_bytes());
        out.push(n as u8);
        for s in &suites[..n] { out.extend_from_slice(&s.to_be_bytes()); }
        // Before the MSISDN. The top bytes of these u64s are 0x00, which never starts an E.164
        // string, so the host can tell this layout from the older one.
        out.extend_from_slice(&cert_nb.to_be_bytes());
        out.extend_from_slice(&cert_na.to_be_bytes());
        out.extend_from_slice(&msisdn);
        Ok(out)
    }

    /// External-commit resync: joins from the server's current GroupInfo with a fresh leaf, landing
    /// at the server's epoch. `ratchet_tree` empty uses the GroupInfo's tree. `remove_leaf_index >=
    /// 0` also removes that stale leaf in the same commit; `-1` is a plain external join. Returns
    /// `[group_id, external_commit]`; the caller sends the commit.
    pub fn external_commit_resync(&self, group_info: &[u8], ratchet_tree: &[u8],
            remove_leaf_index: i64) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let gi = MlsMessage::from_bytes(group_info).map_err(|e| format!("gi_from_bytes: {e:?}"))?;
        // Build before removing anything, so a failed build leaves the group intact. The build
        // reads storage only through the prior-epoch archive's contiguity check, so: attempt; on
        // InvalidEpoch only, snapshot, purge the archive and attempt again; restore the snapshot
        // if that also fails. The host's own snapshot covers a server refusal.
        let stale_gid: Option<Vec<u8>> = gi.clone().into_group_info()
            .map(|info| info.group_context().group_id().to_vec());
        let tree = if ratchet_tree.is_empty() { None } else {
            Some(ExportedTree::from_bytes(ratchet_tree)
                .map_err(|e| format!("tree_from_bytes: {e:?}"))?)
        };
        let do_build = |self_remove: bool| -> Result<(Group<ProdConfig>, MlsMessage), String> {
            let mut b = self.client.external_commit_builder()
                .map_err(|e| format!("external_commit_builder: {e:?}"))?;
            // the leaf lifetime anchor, as for every leaf we mint
            if let Some(t) = self.leaf_lifetime_anchor() { b = b.commit_time(t); }
            if let Some(t) = tree.clone() { b = b.with_tree_data(t); }
            if self_remove { b = b.with_self_remove(); }
            b.build(gi.clone()).map_err(|e| format!("{e:?}"))
        };
        // Try a plain external join first; on DuplicateLeafData retry with the fork's SelfRemove
        // proposal, which frees our stale leaf and yields the tree peers compute.
        let force_self_remove = remove_leaf_index >= 0;
        // One attempt including the fallback, so the archive recovery can rerun it whole.
        let attempt = |used: &mut bool| -> Result<(Group<ProdConfig>, MlsMessage), String> {
            *used = force_self_remove;
            match do_build(force_self_remove) {
                Ok(x) => Ok(x),
                Err(e) if !force_self_remove && e.contains("DuplicateLeafData") => {
                    *used = true;
                    do_build(true)
                        .map_err(|e2| format!("external_commit build (self_remove): {e2}"))
                }
                Err(e) => Err(format!("external_commit build: {e}")),
            }
        };
        // Log which RFC 9420 §12.4.3.2 flavour was built: a plain join, or the resync carrying the
        // SelfRemove that replaces our earlier leaf. The server's rejection does not tell them
        // apart.
        let mut used_self_remove = force_self_remove;
        let (mut g, commit) = match attempt(&mut used_self_remove) {
            Ok(x) => x,
            // The one failure a stale local archive causes; nothing is touched for any other.
            Err(e) if e.contains("InvalidEpoch") => {
                let Some(sg) = stale_gid.clone() else { return Err(e) };
                let store = FileGroupStateStorage::new(self.storage_dir.clone());
                // The undo is the whole record, so restoring re-derives what was removed.
                // u64::MAX keeps every epoch the snapshot carried.
                let undo = store.export_state(&sg);
                let dropped = store.purge_epochs(&sg);
                if dropped == 0 {
                    // No archive, so it was not the cause and a retry would fail the same way.
                    alog!("external_commit_resync: build failed InvalidEpoch but g:{} has no \
                        archived prior epoch to drop, so the stale-archive recovery does not apply \
                        and nothing was touched: {e}", gid_str(&sg));
                    return Err(e);
                }
                alog!("external_commit_resync: build failed InvalidEpoch; dropped {dropped} \
                    archived prior epoch(s) for g:{} — the mls-rs contiguity check refuses an \
                    archive id that is not max+1, and a re-join lands at the SERVER's epoch rather \
                    than the one after ours. Retrying the build; the record is snapshotted and is \
                    put back if the retry also fails.", gid_str(&sg));
                #[cfg(test)]
                LAST_RESYNC_STALE_RECOVERY.with(|c| *c.borrow_mut() =
                    Some(format!("purged {dropped}")));
                let retry = if fail_the_post_purge_resync_build() {
                    Err("external_commit build: INJECTED post-purge failure (test hook)"
                        .to_string())
                } else {
                    attempt(&mut used_self_remove)
                };
                match retry {
                    Ok(x) => x,
                    Err(e2) => {
                        // `restore_state` is best-effort and silent, so read the record back and
                        // compare it with the snapshot rather than assume.
                        let restored: Option<bool> = undo.as_ref().map(|snap| {
                            store.restore_state(&sg, snap, u64::MAX);
                            store.export_state(&sg).as_deref() == Some(&snap[..])
                        });
                        let (verdict, tag) = match restored {
                            None => ("NOT restorable: there was no persisted record to snapshot, \
                                so nothing was lost either", "nothing to restore"),
                            Some(true) => ("RESTORED — read back and compared byte-for-byte \
                                against the pre-attempt snapshot, so this group is exactly as it \
                                was", "restored"),
                            Some(false) => ("WRITE-BACK DID NOT VERIFY — the record reads back \
                                different from the snapshot we took. Nothing here can fix that; \
                                this group needs a look", "write-back did not verify"),
                        };
                        alog!("external_commit_resync: the retry after dropping the stale archive \
                            for g:{} ALSO failed ({e2}); the pre-attempt record was {verdict}",
                            gid_str(&sg));
                        #[cfg(test)]
                        LAST_RESYNC_STALE_RECOVERY.with(|c| *c.borrow_mut() = Some(format!(
                            "purged {dropped}, retry failed, {tag}")));
                        #[cfg(not(test))]
                        let _ = tag;
                        return Err(e2);
                    }
                }
            }
            Err(e) => return Err(e),
        };
        alog!("external_commit_resync: flavor={} (forced={}) tree={}B",
            if used_self_remove { "RESYNC(self_remove)" } else { "JOIN(plain)" },
            force_self_remove, ratchet_tree.len());
        let gid = g.group_id().to_vec();
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        // The post-commit GroupInfo must carry external_pub (the server's recomputed GroupInfo
        // does, and a mismatch fails its signature check) and no embedded tree (rejected). The
        // post-commit tree travels as its own field, as on the create path.
        let new_gi = g.group_info_message_allowing_ext_commit(false)
            .map_err(|e| format!("gi_msg: {e:?}"))?
            .to_bytes().map_err(|e| format!("gi_bytes: {e:?}"))?;
        // the post-commit tree, for the separate tree field
        let post_tree = g.export_tree().to_bytes().map_err(|e| format!("export_tree: {e:?}"))?;
        // Diagnostic: our post-commit GroupInfo must verify against our own post-commit tree; a
        // failure here is a client inconsistency.
        match g.rcs_debug_validate_group_info(&new_gi) {
            Ok((idx, key)) => {
                let hex: String = key.iter().map(|b| format!("{b:02x}")).collect();
                alog!("revive-gi-selfcheck OK: GroupInfo VERIFIES vs our own post-commit \
                    tree; signer_index={idx} signer_key={hex}");
            }
            Err(e) => alog!("revive-gi-selfcheck FAIL: {e:?} (GI does NOT verify vs our own \
                tree — client GI/Commit key inconsistency)"),
        }
        self.purge_prior_epochs_on_join(&gid, "external_commit_resync");
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let commit_b = commit.to_bytes().map_err(|e| format!("commit_to_bytes: {e:?}"))?;
        // [group_id, external commit, tree-less GroupInfo, epoch_auth, ratchet tree]
        Ok(join_len_prefixed(&[gid, commit_b, new_gi, tag, post_tree]))
    }

    /// The repeated-result form of `process_ex` (RCC.16 §10.5): one record today, but the shape can
    /// carry a piggybacked commit or a result for another context. Wraps `process_ex` rather than
    /// reimplementing its classification. The encoding is described by `MlsEngineResult`.
    pub fn process_results(&self, gid: &[u8], wire: &[u8], context_id: &[u8])
            -> Result<Vec<u8>, String> {
        const FORMAT_VERSION: u8 = 1;
        let raw = self.process_ex(gid, wire)?;
        // process_ex returns [status, payload...]; for status 2 bytes 1..3 are the proposal type.
        let status = *raw.first().unwrap_or(&3u8);
        let (aux, payload): (Vec<u8>, Vec<u8>) = match status {
            2 if raw.len() >= 3 => (raw[1..3].to_vec(), Vec::new()),
            0 => (Vec::new(), raw[1..].to_vec()),
            _ => (Vec::new(), Vec::new()),
        };
        // The group id is echoed from the request: a result that failed to apply has no group to
        // read, and §10.5's agreement check needs every result to name one.
        let record = join_len_prefixed(&[
            vec![status],
            context_id.to_vec(),
            gid.to_vec(),
            aux,
            payload,
        ]);
        Ok(join_len_prefixed(&[vec![FORMAT_VERSION], record]))
    }

    /// Deletes a group's persisted state, for a discard before resync or a phoenix reset. Returns
    /// `[1]`.
    pub fn delete_group(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        FileGroupStateStorage::new(self.storage_dir.clone()).delete_group(gid);
        Ok(vec![1u8])
    }

    /// Status-tagged process: `[status, payload...]`. 0 application (payload = plaintext), 1
    /// commit, 2 proposal (bytes 1..3 = proposal type), 3 other, 7 malformed, 8 could not apply
    /// yet, 9 a commit from a past epoch. Every success writes storage before returning.
    pub fn process_ex(&self, gid: &[u8], wire: &[u8]) -> Result<Vec<u8>, String> {
        // Clear first, so a stale AAD is never attributed to this message.
        LAST_AAD.with(|c| c.borrow_mut().clear());
        LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = None);
        LAST_SENDER_MSISDN.with(|c| c.borrow_mut().clear());
        let mut g = self.load_for_apply(gid, "process_ex")?;
        let pre_epoch = g.context().epoch;
        // Malformed wire (7) and a valid message we could not apply (8) are reported apart.
        let msg = match MlsMessage::from_bytes(wire) {
            Ok(m) => m,
            Err(e) => { alog!("process_ex from_bytes FAIL ({}B): {e:?}", wire.len()); return Ok(
                vec![7u8]); }
        };
        // The message's own epoch, captured before processing consumes it, separates a past-epoch
        // commit (9, drop) from a current or future one (8, buffer).
        let msg_epoch = msg.epoch();
        // A log pair in the peer's wording, bracketing one apply.
        let pre_era = era_of(&g).unwrap_or(0);
        alog!(
              "Processing a message on group GroupId: \"{}\", at moment GroupMoment {{ Epoch: {}, and Era: {} }}",
              gid_str(gid), pre_epoch, pre_era);
        match g.process_incoming_message(msg) {
            Ok(res) => {
                g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
                let post_epoch = g.context().epoch;
                // the after half
                alog!(
                      "Finished processing a message on group: GroupId: \"{}\", now at moment GroupMoment {{ Epoch: {}, and Era: {} }}",
                      gid_str(gid), post_epoch, era_of(&g).unwrap_or(pre_era));
                // An inbound commit advances the epoch; a cached pre-commit group would be written
                // back over it by the next encrypt.
                if post_epoch != pre_epoch {
                    self.invalidate_send_cache();
                }
                let mut out = match &res {
                    ReceivedMessage::ApplicationMessage(_) => vec![0u8],
                    ReceivedMessage::Commit(_) => {
                        alog!("process_ex COMMIT applied: epoch {pre_epoch} → {post_epoch}");
                        vec![1u8]
                    }
                    // The proposal type goes in bytes 1..3 (u16 BE) so the caller can tell a native
                    // SelfRemove (commit it) from an advertised-only custom type, whose commit
                    // would consume it and change nothing. Byte 0 is unchanged.
                    ReceivedMessage::Proposal(p) => {
                        let t: u16 = p.proposal.proposal_type().raw_value();
                        alog!("process_ex PROPOSAL type=0x{t:04x} cached by-reference");
                        vec![2u8, (t >> 8) as u8, t as u8]
                    }
                    _ => vec![3u8],
                };
                if let ReceivedMessage::ApplicationMessage(ref m) = res {
                    // Expose the AAD and record the RCC.16 §7.5.3.1 verdict, before the plaintext
                    // is returned.
                    LAST_AAD.with(|c| *c.borrow_mut() = m.authenticated_data.clone());
                    note_message_id_check(&m.authenticated_data);
                    // and the sender, from the same authenticated decrypt
                    let who = Self::certified_msisdn_of(&g, m.sender_index);
                    LAST_SENDER_MSISDN.with(|c| *c.borrow_mut() = who);
                }
                if let ReceivedMessage::ApplicationMessage(m) = res {
                    // Diagnostic: log the generation the peer used (visible only after decrypt,
                    // published by the forked mls-rs) and the plaintext length, never its bytes.
                    use core::sync::atomic::Ordering;
                    let pt = m.data();
                    if mls_rs::group::LAST_RECV_VALID.load(Ordering::SeqCst) {
                        alog!(
                            "recv-diag: sender_leaf={} msg_epoch={} sender_data_generation={} plaintext={}B",
                            mls_rs::group::LAST_RECV_SENDER.load(Ordering::SeqCst),
                            mls_rs::group::LAST_RECV_EPOCH.load(Ordering::SeqCst),
                            mls_rs::group::LAST_RECV_GENERATION.load(Ordering::SeqCst),
                            pt.len());
                    }
                    out.extend_from_slice(&pt);
                }
                Ok(out)
            }
            Err(e) => {
                let past = matches!(msg_epoch, Some(me) if me < pre_epoch);
                alog!("process_ex apply FAIL ({}B) msg_epoch={:?} pre={} → status {}: {e:?}",
                    wire.len(), msg_epoch, pre_epoch, if past { 9 } else { 8 });
                Ok(vec![if past { 9u8 } else { 8u8 }])   // 9 past: drop; 8: buffer
            }
        }
    }
}

/// The `[welcome, commit, groupInfo, tag, gid, tree]` bundle from a just-built commit, applied and
/// persisted. Welcome and GroupInfo are empty when the op has none.
fn commit_bundle(g: &mut Group<ProdConfig>,
        commit: mls_rs::group::CommitOutput) -> Result<Vec<u8>, String> {
    g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
    let gid = g.group_id().to_vec();
    let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
    g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
    let welcome = match commit.welcome_messages.first() {
        Some(w) => w.to_bytes().map_err(|e| format!("welcome: {e:?}"))?,
        None => Vec::new(),
    };
    let commit_b = commit.commit_message.to_bytes().map_err(|e| format!("commit: {e:?}"))?;
    let ginfo = match commit.external_commit_group_info {
        Some(gi) => gi.to_bytes().map_err(|e| format!("ginfo: {e:?}"))?,
        None => Vec::new(),
    };
    let tree = g.export_tree().to_bytes().map_err(|e| format!("tree: {e:?}"))?;
    Ok(join_len_prefixed(&[welcome, commit_b, ginfo, tag, gid, tree]))
}

// ---- C ABI ----
#[repr(C)]
pub struct RcsBytes { pub data: *mut u8, pub len: usize }
fn to_bytes(v: Vec<u8>) -> RcsBytes {
    let mut b = v.into_boxed_slice();
    let r = RcsBytes { data: b.as_mut_ptr(), len: b.len() };
    core::mem::forget(b); r
}
const NULL_BYTES: RcsBytes = RcsBytes { data: core::ptr::null_mut(), len: 0 };
unsafe fn slice<'a>(p: *const u8, n: usize) -> &'a [u8] {
    if p.is_null() || n == 0 { &[] } else { core::slice::from_raw_parts(p, n) }
}
unsafe fn cstr<'a>(p: *const c_char) -> &'a str {
    if p.is_null() { return ""; }
    CStr::from_ptr(p).to_str().unwrap_or("")
}

#[no_mangle]
pub unsafe extern "C" fn rcs_mls_session_start(
    leaf: *const u8, leaf_len: usize, chain: *const u8, chain_len: usize,
    priv_: *const u8, priv_len: usize, pub_: *const u8, pub_len: usize,
    roots: *const u8, roots_len: usize, revoked: *const u8, revoked_len: usize,
    storage_dir: *const c_char,
) -> *mut ProdSession {
    let chain_v = split_len_prefixed(slice(chain, chain_len));
    let roots_v = split_len_prefixed(slice(roots, roots_len));
    // same length-prefixed framing as chain and roots
    let revoked_v = split_len_prefixed(slice(revoked, revoked_len));
    match ProdSession::start(slice(leaf, leaf_len), &chain_v, slice(priv_, priv_len),
                             slice(pub_, pub_len), &roots_v, &revoked_v, cstr(storage_dir)) {
        Ok(s) => Box::into_raw(Box::new(s)),
        Err(_) => core::ptr::null_mut(),
    }
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_generate_key_packages(s: *mut ProdSession,
        count: u32) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("generate_key_packages", (*s).generate_key_packages(count))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_generate_last_resort_kp(s: *mut ProdSession) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("generate_last_resort_kp", (*s).generate_last_resort_key_package())
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_member_participant_keys(s: *mut ProdSession, gid: *const u8,
        gid_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("member_participant_keys", (*s).member_participant_keys(slice(gid, gid_len)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_key_package_ref(s: *mut ProdSession, kp: *const u8, kp_len: usize)
        -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("key_package_ref", (*s).key_package_ref(slice(kp, kp_len)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_welcome_key_package_refs(s: *mut ProdSession, w: *const u8,
        w_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("welcome_key_package_refs", (*s).welcome_key_package_refs(slice(w, w_len)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_create_group(s: *mut ProdSession, era: u32, kp: *const u8,
        kp_len: usize, gid: *const u8, gid_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    let gid_slice: &[u8] = if gid.is_null() || gid_len == 0 { &[] } else { slice(gid, gid_len) };
    logged_tx("create_group", s,
        (*s).create_group(era, slice(kp, kp_len), gid_slice).map(|(_g, a)| a))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_join(s: *mut ProdSession, w: *const u8, w_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("join", s, (*s).join(slice(w, w_len)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_join_with_tree(s: *mut ProdSession, w: *const u8, w_len: usize,
        rt: *const u8, rt_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("join_with_tree", s, (*s).join_with_tree(slice(w, w_len), slice(rt, rt_len)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_join_treeless_welcome(s: *mut ProdSession, w: *const u8,
        w_len: usize, blob: *const u8, blob_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("join_treeless_welcome", s,
        (*s).join_treeless_welcome(slice(w, w_len), slice(blob, blob_len)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_member_validity(s: *mut ProdSession, gid: *const u8,
        gid_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("member_validity", (*s).member_validity(slice(gid, gid_len)))
}

/// Read-only: the leaf index that signed a GroupInfo. See `ProdSession::group_info_signer`.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_group_info_signer(s: *mut ProdSession, gi: *const u8,
        gi_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("group_info_signer", (*s).group_info_signer(slice(gi, gi_len)))
}
/// Read-only: every leaf's certificate window in a serialized ratchet tree.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_tree_member_validity(s: *mut ProdSession, tree: *const u8,
        tree_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("tree_member_validity", (*s).tree_member_validity(slice(tree, tree_len)))
}
/// Read-only: our leaf's certificate window in this group against the client's.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_self_leaf_status(s: *mut ProdSession, gid: *const u8,
        gid_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("self_leaf_status", (*s).self_leaf_status(slice(gid, gid_len)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_external_join(s: *mut ProdSession, gi: *const u8, gi_len: usize,
        rt: *const u8, rt_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("external_join", s, (*s).external_join(slice(gi, gi_len), slice(rt, rt_len)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_epoch_auth(s: *mut ProdSession, gid: *const u8,
        gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("epoch_auth", (*s).epoch_auth(slice(gid, gl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_era_epoch(s: *mut ProdSession, gid: *const u8,
        gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("era_epoch", (*s).era_epoch(slice(gid, gl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_add_member(s: *mut ProdSession, gid: *const u8, gl: usize,
        kp: *const u8, kl: usize, aad: *const u8, al: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("add_member", s, (*s).add_member(slice(gid, gl), slice(kp, kl), slice(aad, al)))
}
/// Adds N members in one commit; `kps` is length-prefixed. See `add_members`.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_add_members(s: *mut ProdSession, gid: *const u8, gl: usize,
        kps: *const u8, kl: usize, aad: *const u8, al: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    let list = split_len_prefixed(slice(kps, kl));
    logged_tx("add_members", s, (*s).add_members(slice(gid, gl), &list, slice(aad, al)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_remove_member(s: *mut ProdSession, gid: *const u8, gl: usize,
        sig: *const u8, sigl: usize, aad: *const u8, al: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("remove_member", s,
        (*s).remove_member(slice(gid, gl), slice(sig, sigl), slice(aad, al)))
}
/// Removes every leaf certified to one MSISDN, in one commit. The only correct selector for a
/// multi-device participant; `remove_member` with an empty key means "the sole other member" and
/// must not be used for groups.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_remove_member_by_msisdn(s: *mut ProdSession, gid: *const u8,
        gl: usize, msisdn: *const u8, ml: usize, aad: *const u8, al: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("remove_member_by_msisdn", s,
        (*s).remove_member_by_msisdn(slice(gid, gl), slice(msisdn, ml), slice(aad, al)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_commit_end_mls(s: *mut ProdSession, gid: *const u8, gl: usize,
        aad: *const u8, al: usize, remove: u8) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
        // The u8 flag becomes EndMlsOp at the first statement, so no Rust code handles a bare bool.
    logged_tx("commit_end_mls", s,
        (*s).commit_end_mls(slice(gid, gl), slice(aad, al), EndMlsOp::from_flag(remove != 0)))
}
/// Which GroupContext extension types a serialized GroupInfo carries (u16 type, u16 len each).
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_group_info_ext_types(s: *mut ProdSession, gi: *const u8, gl: usize)
        -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("group_info_ext_types", (*s).group_info_ext_types(slice(gi, gl)))
    })
}
/// One extension's decoded value from a serialized GroupInfo. See `ProdSession::group_info_ext`.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_group_info_ext(s: *mut ProdSession, gi: *const u8, gl: usize,
        ext_type: u16) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("group_info_ext", (*s).group_info_ext(slice(gi, gl), ext_type))
    })
}
/// Era advance on the existing group; always refused. See `ProdSession::commit_era_advance`.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_commit_era_advance(s: *mut ProdSession, gid: *const u8, gl: usize,
        aad: *const u8, al: usize, new_era: u32) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("commit_era_advance", s,
        (*s).commit_era_advance(slice(gid, gl), slice(aad, al), new_era))
}
/// RCC.16 metadata commit carrying the keys and their commitments.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_commit_group_metadata(s: *mut ProdSession, gid: *const u8,
        gl: usize, aad: *const u8, al: usize, ik: *const u8, ikl: usize, ic: *const u8, icl: usize,
        sk: *const u8, skl: usize, sc: *const u8, scl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("commit_group_metadata", s, (*s).commit_group_metadata(slice(gid, gl), slice(aad, al),
        slice(ik, ikl), slice(ic, icl), slice(sk, skl), slice(sc, scl)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_commit_icon_subject(s: *mut ProdSession, gid: *const u8, gl: usize,
        aad: *const u8, al: usize, ic: *const u8, icl: usize, sc: *const u8,
        scl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("commit_icon_subject", s, (*s).commit_icon_subject(slice(gid, gl), slice(aad, al),
        slice(ic, icl), slice(sc, scl)))
}
/// Creates a group adding all members in the initial commit; `kps` is length-prefixed. `mode` is
/// the era-advance mode: 0 Normal (carry `end_mls`), 1 Revival (drop it), 2 Phoenix (install it);
/// anything else is Normal.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_create_group_multi(s: *mut ProdSession, era: u32,
        kps: *const u8, kl: usize, gid: *const u8, gl: usize,
        carry_gi: *const u8, cgl: usize, mode: u8) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    let list = split_len_prefixed(slice(kps, kl));
    logged("create_group_multi",
        (*s).create_group_carry(era, &list, slice(gid, gl), slice(carry_gi, cgl),
            AdvanceEraKind::from_mode(mode)).map(|(_, a)| a))
}
/// Create, advance or add without an era argument: the engine decides and reports the era and
/// `welcomeAction` in bundle slots 6 and 7. See `ProdSession::create_group_planned`.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_create_group_planned(s: *mut ProdSession,
        kps: *const u8, kl: usize, gid: *const u8, gl: usize,
        carry_gi: *const u8, cgl: usize, mode: u8) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    let list = split_len_prefixed(slice(kps, kl));
    logged("create_group_planned",
        (*s).create_group_planned(&list, slice(gid, gl), slice(carry_gi, cgl),
            AdvanceEraKind::from_mode(mode)).map(|(_, a)| a))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_group_ext(s: *mut ProdSession, gid: *const u8, gl: usize,
        ext_type: u16) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("group_ext", (*s).group_ext(slice(gid, gl), ext_type))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_group_info_with_continuity(s: *mut ProdSession, gid: *const u8,
        gl: usize, commitment: *const u8, cl: usize, with_tree: u8) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("group_info_with_continuity",
           (*s).group_info_with_continuity(slice(gid, gl), slice(commitment, cl), with_tree != 0))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_group_info_continuity(s: *mut ProdSession, gi: *const u8,
        gil: usize, ty: u16) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("group_info_continuity", (*s).group_info_continuity(slice(gi, gil), ty))
}
/// Takes the RCC.16 §7.11.12.1 token captured when `gid` was joined from a Welcome; empty is a
/// normal answer. Not in `read_only`: it changes the session's hand-off queue, not storage.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_take_welcome_continuity_token(s: *mut ProdSession,
        gid: *const u8, gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("take_welcome_continuity_token", (*s).take_welcome_continuity_token(slice(gid, gl)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_end_mls_present(s: *mut ProdSession, gid: *const u8, gl: usize)
        -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("end_mls_present", (*s).end_mls_present(slice(gid, gl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_rcs_sign(s: *mut ProdSession, gid: *const u8, gl: usize,
        dc: *const u8, dcl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("rcs_sign", (*s).rcs_sign(slice(gid, gl), slice(dc, dcl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_rcs_verify(s: *mut ProdSession, gid: *const u8, gl: usize,
        m: *const u8, ml: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("rcs_verify", (*s).rcs_verify(slice(gid, gl), slice(m, ml)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_commit_required(s: *mut ProdSession, gid: *const u8, gl: usize)
        -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("commit_required", (*s).commit_required(slice(gid, gl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_self_update(s: *mut ProdSession, gid: *const u8, gl: usize,
        aad: *const u8, al: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("self_update", s, (*s).self_update(slice(gid, gl), slice(aad, al)))
}
/// As `rcs_mls_self_update`, but publishing a GroupInfo with `external_pub` and returning the
/// post-commit ratchet tree.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_self_update_extpub(s: *mut ProdSession, gid: *const u8, gl: usize,
        aad: *const u8, al: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("self_update_extpub", s, (*s).self_update_extpub(slice(gid, gl), slice(aad, al)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_self_leave(s: *mut ProdSession, gid: *const u8, gl: usize,
        aad: *const u8, al: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("self_leave", s, (*s).self_leave(slice(gid, gl), slice(aad, al)))
}
/// Snapshot and restore of group state around an optimistic self-update the server may refuse.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_export_group_snapshot(s: *mut ProdSession, gid: *const u8,
        gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("export_group_snapshot", (*s).export_group_snapshot(slice(gid, gl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_restore_group_snapshot(s: *mut ProdSession, gid: *const u8,
        gl: usize, snap: *const u8, sl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("restore_group_snapshot", (*s).restore_group_snapshot(slice(gid, gl), slice(snap, sl)))
}
/// `[1]` for a last-resort KeyPackage, `[0]` for a one-time one; null on a parse error.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_kp_is_last_resort(s: *mut ProdSession, kp: *const u8,
        kl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("kp_is_last_resort",
            (*s).kp_is_last_resort(slice(kp, kl)).map(|b| vec![if b { 1u8 } else { 0u8 }]))
    })
}
/// Drops all cached by-reference proposals; see `clear_pending_proposals`.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_clear_pending_proposals(s: *mut ProdSession, gid: *const u8,
        gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("clear_pending_proposals", (*s).clear_pending_proposals(slice(gid, gl)))
}
/// The consume-side KeyPackage facts; see `ProdSession::kp_inspect` for the layout. Null on a
/// parse error or an undatable package.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_kp_inspect(s: *mut ProdSession, kp: *const u8,
        kl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("kp_inspect", (*s).kp_inspect(slice(kp, kl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_external_commit_resync(s: *mut ProdSession, gi: *const u8,
        gil: usize, tree: *const u8, tl: usize, remove_leaf_index: i64) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("external_commit_resync",
        (*s).external_commit_resync(slice(gi, gil), slice(tree, tl), remove_leaf_index))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_delete_group(s: *mut ProdSession, gid: *const u8,
        gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("delete_group", (*s).delete_group(slice(gid, gl)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_process_ex(s: *mut ProdSession, gid: *const u8, gl: usize,
        wire: *const u8, wl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("process_ex", (*s).process_ex(slice(gid, gl), slice(wire, wl)))
}
/// The repeated-result process. See `ProdSession::process_results`.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_process_results(s: *mut ProdSession, gid: *const u8, gl: usize,
        wire: *const u8, wl: usize, ctx: *const u8, cl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("process_results",
        (*s).process_results(slice(gid, gl), slice(wire, wl), slice(ctx, cl)))
}
/// The repeated-result encrypt. See `ProdSession::encrypt_results`.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_encrypt_results(s: *mut ProdSession, gid: *const u8, gl: usize,
        pt: *const u8, pl: usize, aad: *const u8, al: usize, ctx: *const u8, cl: usize,
        want_key_update: bool) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    let aad_slice: &[u8] = if aad.is_null() || al == 0 { &[] } else { slice(aad, al) };
    logged("encrypt_results",
        (*s).encrypt_results(slice(gid, gl), slice(pt, pl), aad_slice, slice(ctx, cl),
                             want_key_update))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_encrypt(s: *mut ProdSession, gid: *const u8, gl: usize,
        pt: *const u8, pl: usize, aad: *const u8, al: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    let aad_slice: &[u8] = if aad.is_null() || al == 0 { &[] } else { slice(aad, al) };
    logged_tx("encrypt", s, (*s).encrypt(slice(gid, gl), slice(pt, pl), aad_slice))
}
/// The generation the next `encrypt` will use (4 bytes big-endian), for RCC.16 body framing.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_next_app_gen(s: *mut ProdSession, gid: *const u8,
        gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    read_only(|| {
        logged("next_app_gen", (*s).next_app_gen(slice(gid, gl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_process(s: *mut ProdSession, gid: *const u8, gl: usize,
        w: *const u8, wl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("process", (*s).process(slice(gid, gl), slice(w, wl)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_bytes_free(b: RcsBytes) {
    if !b.data.is_null()
        && b.len > 0 { drop(Box::from_raw(core::slice::from_raw_parts_mut(b.data, b.len))); }
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_session_close(s: *mut ProdSession) {
    if !s.is_null() { drop(Box::from_raw(s)); }
}

#[cfg(test)]
mod aad_id_check_tests {
    use super::*;

    fn set(id: &[u8]) {
        rcs_mls_set_request_message_id(id.as_ptr(), id.len());
    }
    /// Resets every thread-local this module touches. Call at the start of each test: the harness
    /// reuses threads and a panicking test skips its own cleanup.
    fn clear_state() {
        rcs_mls_set_request_message_id(std::ptr::null(), 0);
        rcs_mls_set_next_resent_component(std::ptr::null(), 0);
        LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = None);
    }

    // An unset message id skips the check rather than failing it.
    // The resent component is one-shot: it must not leak into the next message.
    #[test]
    fn a_one_shot_resent_component_is_used_then_cleared() {
        clear_state();
        let comp = [0x02u8, 0x40, 0xAA, 0xBB];
        rcs_mls_set_next_resent_component(comp.as_ptr(), comp.len());
        let first = NEXT_RESENT_COMPONENT.with(|c| c.borrow().clone());
        assert_eq!(first, comp, "component must be staged");
        // the consume aad_for performs
        let taken = NEXT_RESENT_COMPONENT.with(|c| {
            let v = c.borrow().clone();
            c.borrow_mut().clear();
            v
        });
        assert_eq!(taken, comp);
        let after = NEXT_RESENT_COMPONENT.with(|c| c.borrow().clone());
        assert!(after.is_empty(), "must be cleared so it cannot leak into the next message");
        // an AAD built with it carries it in the trailing slot and parses back
        let aad = crate::rcc16::build_authenticated_data(b"MxID", 7, &comp);
        let p = crate::rcc16::parse_authenticated_data(&aad).unwrap();
        assert_eq!(p.trailing, comp);
        assert!(!p.resent_absent());
        assert_eq!(p.era, 7);
        rcs_mls_set_next_resent_component(std::ptr::null(), 0);
    }

    #[test]
    fn no_expected_id_means_the_check_is_skipped() {
        clear_state();
        let aad = crate::rcc16::build_authenticated_data(b"MxAAA", 1, &[]);
        note_message_id_check(&aad);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 0);
    }

    #[test]
    fn matching_id_is_not_a_mismatch() {
        clear_state();
        set(b"MxCACYGxqfQa-CnQZ=5XctuQ");
        let aad = crate::rcc16::build_authenticated_data(b"MxCACYGxqfQa-CnQZ=5XctuQ", 8, &[]);
        note_message_id_check(&aad);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 0);
        clear_state();
    }

    #[test]
    fn differing_id_is_flagged_with_both_sides() {
        clear_state();
        set(b"MxEXPECTED");
        let aad = crate::rcc16::build_authenticated_data(b"MxACTUAL", 3, &[]);
        note_message_id_check(&aad);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 1);
        let d = rcs_mls_last_message_id_mismatch_detail();
        let raw = unsafe { std::slice::from_raw_parts(d.data, d.len) };
        let mut it = raw.split(|b| *b == 0);
        assert_eq!(it.next().unwrap(), b"MxEXPECTED");
        assert_eq!(it.next().unwrap(), b"MxACTUAL");
        clear_state();
    }

    // A prefix is not a match: real message ids share long prefixes.
    #[test]
    fn a_prefix_does_not_count_as_a_match() {
        clear_state();
        set(b"MxCACYGxqfQa");
        let aad = crate::rcc16::build_authenticated_data(b"MxCACYGxqfQa-CnQZ=5XctuQ", 8, &[]);
        note_message_id_check(&aad);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 1);
        clear_state();
    }

    // A malformed AAD is not an id mismatch; it belongs to the decrypt-failure path.
    #[test]
    fn a_malformed_aad_is_skipped_not_flagged() {
        clear_state();
        set(b"MxEXPECTED");
        note_message_id_check(&[0x00]);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 0);
        note_message_id_check(&[]);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 0);
        clear_state();
    }

    // An envelope with no id arms an empty one: an AAD naming an id is then a mismatch, and an
    // AAD with an empty id is not.
    #[test]
    fn an_empty_armed_id_refuses_an_aad_that_names_one() {
        clear_state();
        set(b"");
        let aad = crate::rcc16::build_authenticated_data(b"MxACTUAL", 3, &[]);
        note_message_id_check(&aad);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 1);
        LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = None);
        let empty = crate::rcc16::build_authenticated_data(b"", 3, &[]);
        note_message_id_check(&empty);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 0);
        clear_state();
    }

    // Null disarms, even after an empty id was armed.
    #[test]
    fn null_disarms_an_empty_id() {
        clear_state();
        set(b"");
        rcs_mls_set_request_message_id(std::ptr::null(), 0);
        let aad = crate::rcc16::build_authenticated_data(b"MxACTUAL", 3, &[]);
        note_message_id_check(&aad);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 0);
        clear_state();
    }

    #[test]
    fn setting_a_new_id_replaces_the_old_one() {
        clear_state();
        set(b"first");
        set(b"second");
        let aad = crate::rcc16::build_authenticated_data(b"second", 1, &[]);
        note_message_id_check(&aad);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 0);
        clear_state();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::treeless_welcome::{leaf_count, locate_leaves, splice_ratchet_tree};
    fn td(f: &str) -> Vec<u8> { std::fs::read(format!("testdata/{f}")).unwrap() }
    /// With the host passing only a message id, the AAD that travels (captured on decrypt) is
    /// byte-identical to `build_authenticated_data` with the group's own era; together with
    /// MlsAuthenticatedDataWireVectorTest this shows peers see the same bytes.
    #[test]
    fn engine_built_aad_matches_the_wire_format_after_the_seam_removal() {
        // inherit nothing from a reused test thread
        rcs_mls_set_request_message_id(std::ptr::null(), 0);
        rcs_mls_set_next_resent_component(std::ptr::null(), 0);
        LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = None);
        let dir = format!("{}/aadseam_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap();

        // the era the engine reads from 0xF001
        let ee = a.era_epoch(&gid_a).unwrap();
        let era = u32::from_be_bytes([ee[0], ee[1], ee[2], ee[3]]);

        const MID: &[u8] = b"MxCACYGxqfQa-CnQZ=5XctuQ";
        let ct = a.encrypt(&gid_a, b"seam", MID).unwrap();
        // process(), not process_ex(), which prefixes a status byte; both set LAST_AAD
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"seam");

        // what travelled, captured on decrypt
        let seen = LAST_AAD.with(|c| c.borrow().clone());
        let want = crate::rcc16::build_authenticated_data(MID, era, &[]);
        assert_eq!(seen, want, "the AAD on the wire must be exactly what the format specifies");

        // it decomposes to the id we gave and the group's era
        let parsed = crate::rcc16::parse_authenticated_data(&seen).expect("engine AAD must parse");
        assert_eq!(parsed.message_id, MID, "the host's message id must survive to the wire");
        assert_eq!(parsed.era, era, "the era must come from the group, not from a caller guess");
        assert_eq!(parsed.version, 1);
        assert!(parsed.resent_absent(), "an ordinary message carries the component ABSENT");

        // the RCC.16 §7.5.3.1 check agrees on the same id and objects to another
        rcs_mls_set_request_message_id(MID.as_ptr(), MID.len());
        note_message_id_check(&seen);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 0, "matching id must not be flagged");
        LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = None);
        let other = b"MxSOMETHINGELSE";
        rcs_mls_set_request_message_id(other.as_ptr(), other.len());
        note_message_id_check(&seen);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 1, "a different id must be flagged");
        rcs_mls_set_request_message_id(std::ptr::null(), 0);
        LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = None);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn prod_op_surface_roundtrip() {
        let dir = format!("{}/prod_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        // The Welcome is tree-less (the GroupInfo carries no ratchet_tree), so join with the
        // separately exported tree, as production does.
        let parts = split_len_prefixed(&artifacts);
        let welcome = parts[0].clone();
        let tree = parts[5].clone();
        let gid_b = b.join_with_tree(&welcome, &tree).unwrap();
        let ct = a.encrypt(&gid_a, b"prod op surface", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"prod op surface");
        let ct = b.encrypt(&gid_b, b"prod reply", &[]).unwrap();
        assert_eq!(a.process(&gid_a, &ct).unwrap(), b"prod reply");
        let _ = std::fs::remove_dir_all(&dir);
        println!("M4 prod op surface: full handle-based x509+storage round-trip OK");
    }

    /// A failed `external_commit_resync` leaves the group intact. The input is a GroupInfo without
    /// `external_pub`, which is what our own commits publish and the first thing the build checks
    /// (RFC 9420 §12.4.3.2), so the build fails. The snapshots must match before and after, and the
    /// group must still work.
    #[test]
    fn a_failed_external_commit_resync_leaves_the_local_group_intact() {
        let dir = format!("{}/dx3d_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap();
        assert_eq!(gid_a, gid_b, "both members must be on the same MLS group id — the defect is \
            that the resync's delete targets the id it reads out of the GroupInfo");

        // A commit b processes gives b a prior-epoch archive to lose.
        let upd = a.self_update(&gid_a, &[]).unwrap();
        let upd_commit = split_len_prefixed(&upd)[1].clone();
        b.process(&gid_b, &upd_commit).unwrap();

        // A GroupInfo without external_pub, as our commits publish. Read-only.
        let gi_no_extpub = a.group_info_with_continuity(&gid_a, &[], false).unwrap();
        assert!(a.group_info_ext_either_list(&gi_no_extpub, 0x0004).unwrap().0.is_empty(),
            "the fixture must really lack external_pub, or this test proves nothing");

        let before = b.export_group_snapshot(&gid_b)
            .expect("b must hold a persisted group before the resync is attempted");

        let err = b.external_commit_resync(&gi_no_extpub, &[], -1)
            .expect_err("a GroupInfo with no external_pub cannot yield an external commit");
        assert!(err.contains("MissingExternalPubExtension"),
            "expected the RFC 9420 §12.4.3.2 failure, got: {err}");

        let after = b.export_group_snapshot(&gid_b)
            .expect("THE DEFECT: the failed resync deleted b's persisted group — the device \
                now holds nothing for a conversation the server still counts it a member of");
        assert_eq!(before, after,
            "a resync that built nothing must leave the persisted record byte-identical; a state \
             blob that survives while the prior-epoch archive is gone is the same bug, smaller");

        // present and usable: traffic in both directions
        let ct = a.encrypt(&gid_a, b"still here", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"still here");
        let ct = b.encrypt(&gid_b, b"and still able to answer", &[]).unwrap();
        assert_eq!(a.process(&gid_a, &ct).unwrap(), b"and still able to answer");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A group where `b` is several epochs behind `a`, so `b`'s archive is not contiguous with the
    /// epoch an external commit lands at. Returns `(a, b, gid, gi_with_extpub, tree)`.
    #[cfg(test)]
    fn dx3d_stranded_pair(dir: &str) -> (ProdSession, ProdSession, Vec<u8>, Vec<u8>, Vec<u8>) {
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        assert_eq!(b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap(), gid);

        // one commit b follows, giving b an archive
        let upd = a.self_update(&gid, &[]).unwrap();
        b.process(&gid, &split_len_prefixed(&upd)[1].clone()).unwrap();

        // three commits b never sees
        for _ in 0..3 { a.self_update(&gid, &[]).unwrap(); }

        // A GroupInfo with external_pub and the tree separate (slot 5), as the transport publishes.
        let pub_upd = a.self_update_extpub(&gid, &[]).unwrap();
        let p = split_len_prefixed(&pub_upd);
        let gi = p[2].clone();
        let tree = p[5].clone();
        assert!(!a.group_info_ext_either_list(&gi, 0x0004).unwrap().0.is_empty(),
            "self_update_extpub must publish external_pub, or this fixture tests nothing");
        (a, b, gid, gi, tree)
    }

    /// A stale prior-epoch archive is still cleared, after the build demands it: the resync
    /// succeeds for a member whose archive is stale, and the recovery record shows it took that
    /// route.
    #[test]
    fn a_stale_prior_epoch_archive_still_does_not_block_the_resync() {
        LAST_RESYNC_STALE_RECOVERY.with(|c| *c.borrow_mut() = None);
        FAIL_POST_PURGE_RESYNC_BUILD.with(|c| c.set(false));
        let dir = format!("{}/dx3d_stale_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let (_a, b, gid, gi, tree) = dx3d_stranded_pair(&dir);

        let out = b.external_commit_resync(&gi, &tree, 0)
            .expect("a member with a stale archive must still be able to re-join");
        let recs = split_len_prefixed(&out);
        assert_eq!(recs.len(), 5, "[gid, commit, groupInfo, epoch_auth, tree]");
        assert_eq!(recs[0], gid, "the re-join must land on the SAME group id");
        assert!(!recs[1].is_empty(), "an external commit must have been produced");
        assert!(b.export_group_snapshot(&gid).is_ok(), "and it must be persisted");

        // assert the arm, not just the outcome
        let arm = LAST_RESYNC_STALE_RECOVERY.with(|c| c.borrow().clone());
        assert_eq!(arm.as_deref(), Some("purged 1"),
            "the InvalidEpoch recovery must be what unblocked this; got {arm:?}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// If the retry after dropping the archive also fails (injected; no real input reaches this
    /// arm), the record is restored exactly. Without the restore, `after` differs from `before`.
    #[test]
    fn a_retry_that_also_fails_puts_the_stale_archive_back() {
        LAST_RESYNC_STALE_RECOVERY.with(|c| *c.borrow_mut() = None);
        FAIL_POST_PURGE_RESYNC_BUILD.with(|c| c.set(false));
        let dir = format!("{}/dx3d_undo_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let (a, b, gid, gi, tree) = dx3d_stranded_pair(&dir);

        let before = b.export_group_snapshot(&gid).expect("b holds a group before the attempt");

        FAIL_POST_PURGE_RESYNC_BUILD.with(|c| c.set(true));
        let err = b.external_commit_resync(&gi, &tree, 0).expect_err("the injected retry failure");
        FAIL_POST_PURGE_RESYNC_BUILD.with(|c| c.set(false));
        assert!(err.contains("INJECTED post-purge failure"), "got: {err}");

        let arm = LAST_RESYNC_STALE_RECOVERY.with(|c| c.borrow().clone());
        assert_eq!(arm.as_deref(), Some("purged 1, retry failed, restored"),
            "the recovery must report that it put the record back AND verified the read-back; \
             \"write-back did not verify\" here would mean restore_state ran and did not take; \
             got {arm:?}");

        let after = b.export_group_snapshot(&gid)
            .expect("the record must exist again after the restore");
        assert_eq!(before, after,
            "the restore must be EXACT — the purged prior-epoch archive comes back too, not just \
             the state blob");

        // The restored record must load into a working group. b's own send, since b is behind by
        // construction and a decrypt would fail for unrelated reasons.
        assert!(!b.encrypt(&gid, b"after the undo", &[]).unwrap().is_empty(),
            "the restored record must load into a group that can still encrypt");
        drop(a);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A serial passed at `start` reaches the validator: revoking the ICA that issues every
    /// certificate in play refuses the peer's chain. RCC.16 leaves are not revocable, so the ICA is
    /// the meaningful target.
    #[test]
    fn a_pushed_revoked_serial_reaches_the_validator() {
        let dir = format!("{}/revoked_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);

        // control: an empty list works
        let ok_a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/ok_a")).unwrap();
        let ok_b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/ok_b")).unwrap();
        let bkp = split_len_prefixed(&ok_b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        assert!(ok_a.create_group(1, &bkp, &[]).is_ok(),
            "control: an empty revocation list must change nothing");

        // revoke the ICA that issued both leaves
        let ica_serial = crate::rcc16_validate::cert_serial(&td("pki2_ica.der"))
            .expect("the test ICA must have a readable serial");
        let a = ProdSession::start(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &[ica_serial],
            &format!("{dir}/rev_a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/rev_b")).unwrap();
        let bkp2 = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();

        // A now refuses B's credential
        assert!(a.create_group(1, &bkp2, &[]).is_err(),
            "a serial pushed through ProdSession::start MUST reach the validator — if this \
             passes, the revocation list is being dropped somewhere in the plumbing and the \
             whole mechanism is dead code");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// self_leave caches and persists a by-reference proposal that blocks every later application
    /// message, and the sender cannot commit its own removal; only `clear_pending_proposals`
    /// restores sending when the proposal cannot be shipped.
    #[test]
    fn self_leave_wedges_the_sender_until_the_proposal_is_dropped() {
        let dir = format!("{}/leave_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // sending works before the leave
        let ct = a.encrypt(&gid_a, b"before", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"before");

        // the proposal is cached and persisted
        a.self_leave(&gid_a, &[]).unwrap();
        assert_eq!(a.commit_required(&gid_a).unwrap(), vec![1u8],
            "self_leave must leave a cached proposal — that is the wedge");

        // it survives a reload
        let a2 = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        assert_eq!(a2.commit_required(&gid_a).unwrap(), vec![1u8], "the wedge is persisted");

        // only dropping it restores the conversation
        a2.clear_pending_proposals(&gid_a).unwrap();
        assert_eq!(a2.commit_required(&gid_a).unwrap(), vec![0u8]);
        let ct = a2.encrypt(&gid_a, b"after", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"after");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A peer's self_remove (0xF003, RCC.16 §7.11.8.1) is swept by our ordinary next commit and the
    /// leaver is gone: the proposal is reported as 0xF003, a plain `self_update` removes the
    /// proposer, and every remaining member must hold the proposal, since the commit references it.
    #[test]
    fn a_peer_self_remove_is_swept_by_our_next_commit_and_the_leaver_leaves_the_roster() {
        let dir = format!("{}/selfrm_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let c = ProdSession::start_no_revocation(&td("pki2_leaf_pc.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pc_priv.bin"), &td("pki2_leaf_pc_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/c")).unwrap();

        // Three members, so a group remains after the departure.
        let mut kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());
        kps.extend(split_len_prefixed(&c.generate_key_packages(1).unwrap()));
        let (gid_a, artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();
        let gid_c = c.join_with_tree(&parts[0], &parts[5]).unwrap();
        assert_eq!(a.client.load_group(&gid_a).unwrap().roster().members().len(), 3);

        // B's certified MSISDN as the roster sees it, independent of leaf order
        let bmsisdn = {
            let gb = b.client.load_group(&gid_b).unwrap();
            let me = gb.current_member_index();
            ProdSession::certified_msisdn_of(&gb, me)
        };
        assert!(!bmsisdn.is_empty(), "the fixture leaf must carry a tel: SAN");

        // B leaves: a by-reference proposal and nothing else
        let leave = split_len_prefixed(&b.self_leave(&gid_b, b"mls-self-leave").unwrap());
        let proposal = leave[1].clone();
        assert!(!proposal.is_empty(), "self_leave must produce a proposal in the commit slot");

        // (1) A sees it and its type
        let seen = a.process_ex(&gid_a, &proposal).unwrap();
        assert_eq!(seen[0], 2u8, "a by-reference proposal must report status 2, not a commit");
        assert_eq!(u16::from_be_bytes([seen[1], seen[2]]), 0xF003,
            "mls-rs must report the RCC.16 §7.11.8.1 code point NATIVELY — if this ever reads \
             0x000A the engine has moved to draft-ietf-mls-extensions' self_remove, which is a \
             DIFFERENT proposal with different metadata, and the host's PROP_SELF_REMOVE arm \
             would silently stop matching");
        assert_eq!(a.commit_required(&gid_a).unwrap(), vec![1u8],
            "the cached proposal must block A's sends until it is committed — that is the signal \
             the host turns into a COMMIT_PENDING_PROPOSALS pending operation");

        // (3) C holds it too, or cannot apply the commit
        let seen_c = c.process_ex(&gid_c, &proposal).unwrap();
        assert_eq!(seen_c[0], 2u8, "every remaining member caches the same by-reference proposal");

        // (2) an ordinary commit with no removal in it
        let art = split_len_prefixed(&a.self_update(&gid_a, b"commit-proposal").unwrap());
        let commit = art[1].clone();

        let roster: Vec<Vec<u8>> = {
            let ga = a.client.load_group(&gid_a).unwrap();
            ga.roster().members().iter()
                .map(|m| ProdSession::certified_msisdn_of(&ga, m.index)).collect()
        };
        assert_eq!(roster.len(), 2, "the plain commit must have REMOVED the proposer");
        assert!(!roster.contains(&bmsisdn),
            "the leaver must be gone from the roster by MSISDN, not merely by leaf count — a \
             count alone would pass if the wrong member had been removed");
        assert_eq!(a.commit_required(&gid_a).unwrap(), vec![0u8],
            "committing the proposal must clear the block, or the conversation stays wedged");

        // C follows and the group works for those who stayed
        assert_eq!(c.process_ex(&gid_c, &commit).unwrap()[0], 1u8, "C applies the commit");
        let ct = a.encrypt(&gid_a, b"after the leave", &[]).unwrap();
        assert_eq!(c.process(&gid_c, &ct).unwrap(), b"after the leave");

        // B cannot read the epoch it was removed in
        assert!(b.process(&gid_b, &ct).is_err(),
            "a removed member must not be able to decrypt traffic from the epoch that removed it");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// `create_group_multi` with one KeyPackage yields the same artifacts; more members are covered
    /// by `create_group_multi_adds_three_members_in_one_commit`.
    #[test]
    fn create_group_multi_is_the_create_path() {
        let dir = format!("{}/multi_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());

        let (gid_a, artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        assert_eq!(parts.len(), 6, "artifact layout must stay [welcome,commit,ginfo,tag,gid,tree]");
        assert_eq!(a.client.load_group(&gid_a).unwrap().roster().members().len(), 2);

        // the Welcome admits the member and the group carries traffic
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();
        let ct = a.encrypt(&gid_a, b"multi member", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"multi member");

        // an empty member list is refused
        assert!(a.create_group_multi(1, &[], &[]).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Three leaves in one commit, one of them a second device of another's participant (same
    /// MSISDN, different key and subject). The server compares the GroupContext with the full RCS
    /// roster, so all adds must land in the initial commit; and an identity derived from the SAN
    /// alone would make mls-rs refuse the second device as `DuplicateLeafData`.
    #[test]
    fn create_group_multi_adds_three_members_in_one_commit() {
        let dir = format!("{}/multi3_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        // the second device of +15551110001, `a`'s participant
        let c = ProdSession::start_no_revocation(&td("pki2_leaf_pc.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pc_priv.bin"), &td("pki2_leaf_pc_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/c")).unwrap();

        let mut kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());
        kps.extend(split_len_prefixed(&c.generate_key_packages(1).unwrap()));
        assert_eq!(kps.len(), 2, "two KeyPackages go into the initial commit");

        let (gid_a, artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let group = a.client.load_group(&gid_a).unwrap();
        assert_eq!(group.roster().members().len(), 3,
            "creator + two added devices, all in the FIRST commit");
        // one commit: epoch 1, not 3
        assert_eq!(group.current_epoch(), 1, "all adds ride a single commit");

        // both admitted members can read traffic
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();
        let gid_c = c.join_with_tree(&parts[0], &parts[5]).unwrap();
        let ct = a.encrypt(&gid_a, b"three members", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"three members");
        let ct2 = a.encrypt(&gid_a, b"and the second device", &[]).unwrap();
        assert_eq!(c.process(&gid_c, &ct2).unwrap(), b"and the second device");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// An era advance cannot be a GroupContextExtensions commit (0xF001 is immutable): refused at
    /// both layers, and a refused advance leaves the group unchanged.
    #[test]
    fn an_era_advance_cannot_be_a_commit() {
        let dir = format!("{}/era_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // a subject commitment, to show nothing strips it
        let meta = a.commit_group_metadata(&gid_a, &[], &[], &[], &[], &[9u8; 32]).unwrap();
        let meta_parts = split_len_prefixed(&meta);
        b.process(&gid_b, &meta_parts[1]).unwrap();

        // 0xF006 is 33 bytes on the wire (`0x20 || hash`) while group_ext returns the 32-byte
        // value.
        let ga = a.client.load_group(&gid_a).unwrap();
        let sc = ga.context().extensions()
            .get(ExtensionType::from(crate::rcc16::SUBJECT_COMMITMENT_EXT)).unwrap();
        assert_eq!(sc.extension_data.len(), 33,
            "0xF006 extension_data = varint(32) || 32-byte hash");
        assert_eq!(sc.extension_data[0], 0x20);
        assert_eq!(a.group_ext(&gid_a, crate::rcc16::SUBJECT_COMMITMENT_EXT).unwrap(),
                   vec![9u8; 32], "group_ext returns the DECODED value");
        // the era stays bare through the same path
        assert_eq!(ga.context().extensions()
                       .get(ExtensionType::from(crate::rcc16::ERA_EXT)).unwrap()
                       .extension_data.len(), 4);
        drop(ga);

        // the early refusal
        let refused = a.commit_era_advance(&gid_a, &[], 2).unwrap_err();
        assert!(refused.contains("immutable"), "must name the reason, got: {refused}");

        // The original body is refused by the MlsRules era guard while building, which also covers
        // inbound commits.
        let by_the_guard = a.commit_era_advance_illegal_for_test(&gid_a, &[], 2).unwrap_err();
        assert!(by_the_guard.contains("ChangesEra"),
                "the MlsRules guard must refuse the commit build, got: {by_the_guard}");

        // untouched: era 1, two members, still sending
        let ga = a.client.load_group(&gid_a).unwrap();
        assert_eq!(crate::rcc16::era_of(ga.context().extensions()), Some(1),
                   "a refused advance must leave the era where it was");
        assert_eq!(ga.roster().members().len(), 2);
        drop(ga);
        let ct = a.encrypt(&gid_a, b"still encrypting at era 1", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"still encrypting at era 1");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The legal era advance: a new group at era+1 reusing the RCS group id and carrying the
    /// metadata, with a carried commitment keeping its framing.
    #[test]
    fn the_legal_era_advance_is_a_create_that_carries_the_metadata() {
        let dir = format!("{}/eracreate_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let kp = |s: &ProdSession| split_len_prefixed(&s.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();

        let (gid, artifacts) = a.create_group(1, &kp(&b), &[]).unwrap();
        let meta = a.commit_group_metadata(&gid, &[], &[], &[], &[], &[9u8; 32]).unwrap();
        let ginfo = split_len_prefixed(&meta)[2].clone();
        let _ = artifacts;

        // era+1, same group id, carrying the previous GroupInfo
        let (gid2, _) = a.create_group_carry(2, &[kp(&b)], &gid, &ginfo,
            AdvanceEraKind::Normal).unwrap();
        assert_eq!(gid2, gid, "an advance keeps the conversation's group id");
        let g2 = a.client.load_group(&gid2).unwrap();
        assert_eq!(crate::rcc16::era_of(g2.context().extensions()), Some(2));
        let sc = g2.context().extensions()
            .get(ExtensionType::from(crate::rcc16::SUBJECT_COMMITMENT_EXT))
            .expect(
                "the advance must inherit the commitment — the server refuses Some([..]) -> None");
        assert_eq!(sc.extension_data.len(), 33,
            "carried verbatim, so still framed — not re-encoded");
        assert_eq!(a.group_ext(&gid2, crate::rcc16::SUBJECT_COMMITMENT_EXT).unwrap(),
            vec![9u8; 32]);

        // The deny-list: a code point no production path sets (0xF007) survives the advance.
        let gi2 = a.commit_arbitrary_ext_for_test(
            &gid2, crate::rcc16::METADATA_KEYS_REQUESTED_EXT,
            crate::rcc16::METADATA_KEYS_REQUESTED_DATA).unwrap();
        let (gid3, _) = a.create_group_carry(3, &[kp(&b)], &gid, &gi2,
            AdvanceEraKind::Normal).unwrap();
        let g3 = a.client.load_group(&gid3).unwrap();
        assert_eq!(crate::rcc16::era_of(g3.context().extensions()), Some(3));
        assert_eq!(g3.context().extensions()
                       .get(ExtensionType::from(crate::rcc16::METADATA_KEYS_REQUESTED_EXT))
                       .expect("the carry must preserve a type it was never told about")
                       .extension_data,
                   crate::rcc16::METADATA_KEYS_REQUESTED_DATA.to_vec(),
                   "0xF007 is BARE — 29 bytes, no inner varint, carried verbatim");
        assert!(g3.context().extensions()
                    .get(ExtensionType::from(crate::rcc16::SUBJECT_COMMITMENT_EXT)).is_some(),
                "and still carries everything it did before");
        drop(g3);

        // Equal, backwards and zero are refused before any group is built; 0 would emit no era.
        for bad in [1u32, 0] {
            let e = a.create_group_carry(bad, &[kp(&b)], &gid, &ginfo,
                AdvanceEraKind::Normal).unwrap_err();
            assert!(e.contains("backwards in eras"),
                    "era {bad} must not be accepted as an advance from 1, got: {e}");
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Prior-epoch retention stops at an era boundary on both sides, by design. The advancer clears
    /// via `create_group_carry` and every other member via `purge_prior_epochs_on_join`, before
    /// either retention bound runs. A new era restarts epoch ids at the same group id, so an old
    /// entry would shadow the live secret at the same key; the test reads that key across the
    /// advance and requires different answers.
    #[test]
    fn an_era_advance_reuses_the_epoch_id_space_so_prior_era_secrets_cannot_stay_at_the_same_key() {
        use mls_rs_core::group::GroupStateStorage;
        let dir = format!("{}/erakeys_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let kp = |s: &ProdSession| split_len_prefixed(&s.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let store_a = FileGroupStateStorage::new(format!("{dir}/a"));
        let store_b = FileGroupStateStorage::new(format!("{dir}/b"));

        // era 1, walked far enough that both sides hold an archive
        let (gid, artifacts) = a.create_group(1, &kp(&b), &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();
        assert_eq!(gid_b, gid, "one conversation, one group id");
        let mut ginfo = Vec::new();
        for _ in 0..3 {
            let meta = a.commit_group_metadata(&gid, &[], &[], &[], &[], &[9u8; 32]).unwrap();
            let m = split_len_prefixed(&meta);
            assert_eq!(b.process_ex(&gid_b, &m[1]).unwrap()[0], 1u8, "peer must apply the commit");
            ginfo = m[2].clone();
        }
        let era1_epoch2_on_a = store_a.epoch(&gid, 2).unwrap()
            .expect("era 1 must have archived epoch 2 before the advance").to_vec();
        let era1_epoch2_on_b = store_b.epoch(&gid, 2).unwrap()
            .expect("the PEER must hold it too, not only the advancer").to_vec();

        // the advance: same group id, era 2
        let (gid2, artifacts2) = a.create_group_carry(2, &[kp(&b)], &gid, &ginfo,
            AdvanceEraKind::Normal).unwrap();
        assert_eq!(gid2, gid, "the advance reuses the id — that is what makes the keys collide");

        // the advancer kept nothing from era 1
        assert_eq!(store_a.max_epoch_id(&gid).unwrap(), Some(0),
                   "the advancer's archive restarts at the new era's epoch 0");
        for stale in [1u64, 2, 3] {
            assert!(store_a.epoch(&gid, stale).unwrap().is_none(),
                    "era 1's epoch {stale} must be gone on the advancer");
        }

        // nor did the peer, which reaches the new era by Welcome
        let parts2 = split_len_prefixed(&artifacts2);
        assert_eq!(b.join_with_tree(&parts2[0], &parts2[5]).unwrap(), gid,
                   "the peer joins the new era at the same id");
        assert_eq!(store_b.max_epoch_id(&gid).unwrap(), None,
                   "a joiner starts with an EMPTY archive (purge_prior_epochs_on_join)");
        for stale in [1u64, 2, 3] {
            assert!(store_b.epoch(&gid, stale).unwrap().is_none(),
                    "era 1's epoch {stale} must be gone on the peer too");
        }

        // The collision: walk the new era over the same epoch ids and read the key that held era
        // 1's secret; the answer must differ.
        for _ in 0..3 {
            let meta = a.commit_group_metadata(&gid, &[], &[], &[], &[], &[9u8; 32]).unwrap();
            assert_eq!(b.process_ex(&gid, &split_len_prefixed(&meta)[1]).unwrap()[0], 1u8);
        }
        let era2_epoch2_on_a = store_a.epoch(&gid, 2).unwrap()
            .expect("era 2 reaches epoch 2 in its turn").to_vec();
        assert_ne!(era2_epoch2_on_a, era1_epoch2_on_a,
                   "(group_id, 2) means something different in era 2 — retaining era 1's entry at \
                    that key would shadow the live secret, not merely take up room");
        assert_ne!(store_b.epoch(&gid, 2).unwrap().expect("peer too").to_vec(), era1_epoch2_on_b,
                   "same on the peer");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// An era advance must not erase a downgrade: 0xF002 is carried, since removing `end_mls` has
    /// its own explicit path.
    #[test]
    fn era_advance_carry_preserves_end_mls() {
        let dir = format!("{}/endmls_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid, artifacts) = a.create_group(1, &bkp, &[]).unwrap();

        // end MLS, then read the GroupInfo an advance would carry from
        let ended = a.commit_end_mls(&gid, &[], EndMlsOp::Install).unwrap();
        let ended_parts = split_len_prefixed(&ended);
        let ginfo_with_end_mls = &ended_parts[2];
        assert!(a.client.load_group(&gid).unwrap().context().extensions()
                    .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_some(),
                "precondition: end_mls is set");

        // advance by re-creating, carrying from that GroupInfo, as recovery does
        let bkp2 = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid2, _) = a.create_group_carry(2, &[bkp2], &gid, ginfo_with_end_mls,
            AdvanceEraKind::Normal).unwrap();
        assert!(a.client.load_group(&gid2).unwrap().context().extensions()
                    .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_some(),
                "an era advance must NOT drop end_mls — that silently re-encrypts a conversation                  someone deliberately downgraded");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// All three era-advance modes on one group.
    #[test]
    fn era_advance_mode_byte_drives_end_mls_three_ways() {
        let dir = format!("{}/eramode_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let fresh_kp = |n: usize| split_len_prefixed(&b.generate_key_packages(n as u32).unwrap())
            .into_iter().next().unwrap();
        let has_end_mls = |g: &[u8]| a.client.load_group(g).unwrap().context().extensions()
            .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_some();

        let (gid, _) = a.create_group(1, &fresh_kp(1), &[]).unwrap();
        let ended = a.commit_end_mls(&gid, &[], EndMlsOp::Install).unwrap();
        let gi_downgraded = split_len_prefixed(&ended)[2].clone();
        assert!(has_end_mls(&gid), "precondition: the source group is downgraded");

        // mode 0 carries it (compared against the same source as the other two)
        let (g_normal, _) = a.create_group_carry(2, &[fresh_kp(1)], b"era2-normal", &gi_downgraded,
            AdvanceEraKind::Normal).unwrap();
        assert!(has_end_mls(&g_normal), "mode 0 must carry end_mls forward");

        // mode 1, revival, drops it; the only era-advance arm allowed to
        let (g_revive, _) = a.create_group_carry(3, &[fresh_kp(1)], b"era3-revive", &gi_downgraded,
            AdvanceEraKind::Revival).unwrap();
        assert!(!has_end_mls(&g_revive),
                "mode 1 is REVIVAL — the new era must NOT carry end_mls, or the revive did nothing");

        // mode 2, phoenix, installs it from a source without it
        let (gid_healthy, _) = a.create_group(1, &fresh_kp(1), b"healthy-src").unwrap();
        let gi_healthy = {
            // a source with no end_mls, so phoenix originates the tag
            let g = a.client.load_group(&gid_healthy).unwrap();
            g.group_info_message(false).unwrap().to_bytes().unwrap()
        };
        assert!(!has_end_mls(&gid_healthy), "precondition: the source group is NOT downgraded");
        let (g_phoenix, _) = a.create_group_carry(2, &[fresh_kp(1)], b"era2-phoenix", &gi_healthy,
            AdvanceEraKind::PhoenixDowngrade).unwrap();
        assert!(has_end_mls(&g_phoenix),
                "mode 2 is PHOENIX — the new era must be BORN downgraded even though the source \
                 GroupInfo carried no end_mls");

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Phoenix installs `end_mls` with no carried GroupInfo at all, the case it exists for.
    #[test]
    fn phoenix_installs_end_mls_with_no_carry_group_info() {
        let dir = format!("{}/phoenixnc_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid, _) = a.create_group_carry(7, &[bkp], b"phoenix-nocarry", &[],
            AdvanceEraKind::PhoenixDowngrade).unwrap();
        assert!(a.client.load_group(&gid).unwrap().context().extensions()
                    .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_some(),
                "Phoenix must install end_mls with no carry GroupInfo");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The mode decode, including the fallthrough to Normal.
    #[test]
    fn advance_era_kind_decodes_like_the_reference_clients_branch() {
        assert_eq!(AdvanceEraKind::from_mode(0), AdvanceEraKind::Normal);
        assert_eq!(AdvanceEraKind::from_mode(1), AdvanceEraKind::Revival);
        assert_eq!(AdvanceEraKind::from_mode(2), AdvanceEraKind::PhoenixDowngrade);
        // `> 2` touches neither
        assert_eq!(AdvanceEraKind::from_mode(3), AdvanceEraKind::Normal);
        assert_eq!(AdvanceEraKind::from_mode(255), AdvanceEraKind::Normal);
        // exactly one arm removes and one installs
        let all = [AdvanceEraKind::Normal, AdvanceEraKind::Revival,
                   AdvanceEraKind::PhoenixDowngrade];
        assert_eq!(all.iter().filter(|k| k.may_remove_end_mls()).count(), 1);
        assert_eq!(all.iter().filter(|k| k.installs_end_mls()).count(), 1);
    }

    /// `EndMlsOp` and the C ABI's round trip.
    #[test]
    fn end_mls_op_widens_the_c_abi_flag() {
        assert_eq!(EndMlsOp::from_flag(false), EndMlsOp::Install);
        assert_eq!(EndMlsOp::from_flag(true), EndMlsOp::RemoveForRevival);
        assert!(!EndMlsOp::Install.removes());
        assert!(EndMlsOp::RemoveForRevival.removes());
    }

    /// The in-place revival (the other removal site) removes `end_mls`.
    #[test]
    fn commit_end_mls_remove_for_revival_drops_the_extension() {
        let dir = format!("{}/revive_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid, _) = a.create_group(1, &bkp, &[]).unwrap();
        a.commit_end_mls(&gid, &[], EndMlsOp::Install).unwrap();
        assert!(a.client.load_group(&gid).unwrap().context().extensions()
                    .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_some());
        a.commit_end_mls(&gid, &[], EndMlsOp::RemoveForRevival).unwrap();
        assert!(a.client.load_group(&gid).unwrap().context().extensions()
                    .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_none(),
                "RemoveForRevival must actually remove 0xF002 — this is the in-place revive");
        // the era is untouched: an in-place revive is not an era advance
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// An inbound proposal surfaces its type, and one we cannot honour can be dropped: an RCC.16
    /// custom proposal such as `server_remove` (0xF004) is opaque to mls-rs and would otherwise
    /// block sends forever.
    #[test]
    /// RCC.16 §15.2: removal selects by certified MSISDN, and an MSISDN with no leaf is refused
    /// rather than removing someone else. The multi-device sweep needs two certificates for one
    /// MSISDN with distinct keys, which this test does not use.
    fn removal_selects_by_msisdn_not_by_leaf() {
        let dir = format!("{}/rmall_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, _) = a.create_group(1, &bkp, &[]).unwrap();
        assert_eq!(a.client.load_group(&gid_a).unwrap().roster().members().len(), 2);

        let bmsisdn = {
            let ga = a.client.load_group(&gid_a).unwrap();
            let me = ga.current_member_index();
            let other = ga.roster().members().iter().find(|m| m.index != me).unwrap().index;
            ProdSession::certified_msisdn_of(&ga, other)
        };
        assert!(!bmsisdn.is_empty(), "the fixture leaf must carry a tel: SAN");

        // An MSISDN nobody holds is refused.
        assert!(a.remove_member_by_msisdn(&gid_a, b"+15550009999", b"aad").is_err(),
            "an absent MSISDN must not remove an arbitrary member");
        assert_eq!(a.client.load_group(&gid_a).unwrap().roster().members().len(), 2,
            "the refused removal must not have changed the roster");

        // and the real one goes
        a.remove_member_by_msisdn(&gid_a, &bmsisdn, b"aad").unwrap();
        assert_eq!(a.client.load_group(&gid_a).unwrap().roster().members().len(), 1);
    }

    /// The engine validates the credential of a member being added: a KeyPackage chaining to a
    /// different root is refused with an `IdentityProviderError` while the commit is built, a layer
    /// behind the host's `keyPackageUsable` gate. No clocks or thread-local counters, since mls-rs
    /// validates on a rayon pool.
    #[test]
    fn the_engine_validates_the_credential_of_a_member_being_added() {
        let dir = format!("{}/cczpadd_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        // the stranger: retired test chain, a different root
        let x = ProdSession::start_no_revocation(&td("leaf_a.der"), &[td("ica.der")],
            &td("leaf_a_priv.bin"),
            &td("leaf_a_pub.bin"), &[td("root.der")], &format!("{dir}/x")).unwrap();

        let bkps = split_len_prefixed(&b.generate_key_packages(1).unwrap());
        let xkps = split_len_prefixed(&x.generate_key_packages(1).unwrap());
        let (gid, _) = a.create_group_multi(1, &bkps, &[]).unwrap();
        let before = a.client.load_group(&gid).unwrap().roster().members().len();

        let err = a.add_members(&gid, &xkps[0..1], b"aad")
            .expect_err("adding a member whose certificate chains to a DIFFERENT root must be \
                 refused by the engine's in-builder validation");
        assert!(err.contains("IdentityProvider") || err.contains("X509") || err.contains("Rcc16"),
            "the refusal must come from the identity provider — that is what proves the ENGINE \
             validated, since the Java caller gate is not on this path. Got: {err}");
        assert_eq!(a.client.load_group(&gid).unwrap().roster().members().len(), before,
            "a refused add must not change the roster");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// `add_members` advances the epoch once, however many packages it adds.
    #[test]
    fn add_members_advances_the_epoch_exactly_once() {
        let dir = format!("{}/addmulti_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkps = split_len_prefixed(&b.generate_key_packages(2).unwrap());

        let (gid, _) = a.create_group(1, &bkps[0], &[]).unwrap();
        // Remove B so its second package can be added back as a fresh leaf.
        let bmsisdn = {
            let ga = a.client.load_group(&gid).unwrap();
            let me = ga.current_member_index();
            let other = ga.roster().members().iter().find(|m| m.index != me).unwrap().index;
            ProdSession::certified_msisdn_of(&ga, other)
        };
        a.remove_member_by_msisdn(&gid, &bmsisdn, b"aad").unwrap();
        let epoch_before = a.client.load_group(&gid).unwrap().context().epoch;
        let members_before = a.client.load_group(&gid).unwrap().roster().members().len();

        a.add_members(&gid, &bkps[1..2], b"aad").unwrap();
        let g = a.client.load_group(&gid).unwrap();
        assert_eq!(g.roster().members().len(), members_before + 1);
        assert_eq!(g.context().epoch, epoch_before + 1,
            "ONE commit means ONE epoch — N adds in N commits would be +N, and each intermediate \
             epoch is a state in which a participant is half-added");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// N KeyPackages from one device cannot become N leaves: they share a signature key, which RFC
    /// 9420 forbids in one group (`DuplicateLeafData`). Multi-add is per distinct device.
    #[test]
    fn add_members_refuses_duplicate_leaves_from_one_device() {
        let dir = format!("{}/adddup_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkps = split_len_prefixed(&b.generate_key_packages(3).unwrap());
        let (gid, _) = a.create_group(1, &bkps[0], &[]).unwrap();
        let epoch_before = a.client.load_group(&gid).unwrap().context().epoch;

        // two more packages from the same client
        let e = a.add_members(&gid, &bkps[1..3], b"aad").unwrap_err();
        assert!(e.contains("DuplicateLeafData"), "expected a duplicate-leaf refusal, got: {e}");
        assert_eq!(a.client.load_group(&gid).unwrap().context().epoch, epoch_before,
            "the refused add must not have advanced the epoch");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// An empty add is refused rather than committed.
    #[test]
    fn add_members_refuses_an_empty_list() {
        let dir = format!("{}/addempty_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid, _) = a.create_group(1, &bkp, &[]).unwrap();
        let epoch_before = a.client.load_group(&gid).unwrap().context().epoch;
        assert!(a.add_members(&gid, &[], b"aad").is_err());
        assert_eq!(a.client.load_group(&gid).unwrap().context().epoch, epoch_before,
            "the refused add must not have advanced the epoch");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    /// One encrypt returns the ciphertext and then a piggybacked key update.
    fn encrypt_results_carries_the_piggybacked_key_update() {
        let dir = format!("{}/encres_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // without a key update: one result, the ciphertext
        let plain = a.encrypt_results(&gid_a, b"hello", b"aad", b"ctx-1", false).unwrap();
        let recs = split_len_prefixed(&plain);
        assert_eq!(recs.len(), 2, "version record + one result");
        let f = split_len_prefixed(&recs[1]);
        assert_eq!(f[0], vec![0u8], "status 0 = APPLICATION");
        assert_eq!(f[1], b"ctx-1".to_vec());
        assert_eq!(b.process(&gid_b, &f[4]).unwrap(), b"hello".to_vec(),
            "the ciphertext in the list must be a real, openable message");

        // with one: ciphertext, commit, rollback snapshot
        let epoch_before = a.client.load_group(&gid_a).unwrap().context().epoch;
        let both = a.encrypt_results(&gid_a, b"again", b"aad", b"ctx-2", true).unwrap();
        let recs2 = split_len_prefixed(&both);
        assert_eq!(recs2.len(), 4, "version + ciphertext + commit + rollback snapshot");
        let app = split_len_prefixed(&recs2[1]);
        let commit = split_len_prefixed(&recs2[2]);
        let rollback = split_len_prefixed(&recs2[3]);
        assert_eq!(app[0], vec![0u8], "the APPLICATION message must come FIRST");
        assert_eq!(commit[0], vec![1u8], "status 1 = COMMIT");
        assert!(!commit[4].is_empty(), "the commit must carry bytes");
        assert_eq!(rollback[0], vec![3u8], "status 3 = OTHER carries the pre-commit snapshot");
        assert!(!rollback[4].is_empty(), "the snapshot must carry bytes");

        // encrypted at the epoch B holds, so B opens it before applying the commit
        assert_eq!(b.process(&gid_b, &app[4]).unwrap(), b"again".to_vec(),
            "the ciphertext must be readable at the PRE-commit epoch");

        // the snapshot winds our local advance back, as for a refused commit
        let epoch_after = a.client.load_group(&gid_a).unwrap().context().epoch;
        assert!(epoch_after > epoch_before, "the key update advanced us locally, as commits do");
        assert!(a.restore_group_snapshot(&gid_a, &rollback[4]).is_ok(),
            "the snapshot must restore");
        assert_eq!(a.client.load_group(&gid_a).unwrap().context().epoch, epoch_before,
            "a refused key update must leave us where the ciphertext was made, not ahead of it");

        // after the rollback A still reaches B, which never saw the commit
        let after = a.encrypt_results(&gid_a, b"post-rollback", b"aad", b"ctx-3", false).unwrap();
        let f3 = split_len_prefixed(&split_len_prefixed(&after)[1]);
        assert_eq!(b.process(&gid_b, &f3[4]).unwrap(), b"post-rollback".to_vec(),
            "after a rolled-back rotation the conversation must still work");
    }

    /// A re-minted certificate reaches the group (RCC.16 v4.0 §9.5.3): after a renewal with the
    /// same key, subject and SAN, the self-update's new leaf certifies the new certificate.
    /// Asserted on B's roster, the copy the server validates.
    #[test]
    fn a_self_update_carries_a_re_minted_credential_into_the_group() {
        let dir = format!("{}/renewal_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        // notAfter of each fixture
        const PA_NOT_AFTER: u64 = 1791387137;
        const RENEWED_NOT_AFTER: u64 = 1791892800;
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let (gid_a, gid_b, a_index) = {
            let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
                &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
                &format!("{dir}/a")).unwrap();
            let kps = b.generate_key_packages(2).unwrap();
            let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
            let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
            let parts = split_len_prefixed(&artifacts);
            let gid_b = b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap();
            // baseline: both sides see the original leaf
            let st = a.self_leaf_status(&gid_a).unwrap();
            assert_eq!(st[36], 0, "before any re-mint our leaf is NOT stale");
            // Our index comes from the engine; the fixtures' windows are identical, so matching
            // on notAfter would pick the wrong row.
            let idx = u32::from_be_bytes([st[0], st[1], st[2], st[3]]);
            (gid_a, gid_b, idx)
        };
        {
            let v = parse_validity(&b.member_validity(&gid_b).unwrap());
            let (_, _, na) = *v.iter().find(|(i, _, _)| *i == a_index)
                .expect("B must hold a leaf at A's index");
            assert_eq!(na, PA_NOT_AFTER,
                "B must see A under the ORIGINAL certificate before the renewal");
        }

        // The re-mint: same storage and key, a new certificate, as after onIdentityChanged.
        let a2 = ProdSession::start_no_revocation(&td("pki2_leaf_pa_renewed.der"),
            &[td("pki2_ica.der")], &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"),
            &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();

        // the detector sees the drift and reports both windows
        let st = a2.self_leaf_status(&gid_a).unwrap();
        assert_eq!(st.len(), 37, "the status record is fixed-width");
        assert_eq!(u32::from_be_bytes([st[0], st[1], st[2], st[3]]), a_index,
            "self_leaf_status must name OUR leaf index");
        assert_eq!(u64::from_be_bytes(st[12..20].try_into().unwrap()), PA_NOT_AFTER,
            "the GROUP still holds the certificate we joined with");
        assert_eq!(u64::from_be_bytes(st[28..36].try_into().unwrap()), RENEWED_NOT_AFTER,
            "the CLIENT holds the re-minted certificate");
        assert_eq!(st[36], 1, "and the two differ, which is the whole condition");

        // the RCC.16 §9.5.3 empty commit with the new leaf
        let bundle = split_len_prefixed(&a2.self_update(&gid_a, b"mid-renewal").unwrap());
        assert!(b.process(&gid_b, &bundle[1]).is_ok(),
            "the peer must ACCEPT a Commit that changes our credential — valid_successor compares \
             the subject CN, and a renewal keeps it");

        // on the peer's copy of the roster
        let v = parse_validity(&b.member_validity(&gid_b).unwrap());
        let (_, _, na_now) = *v.iter().find(|(i, _, _)| *i == a_index)
            .expect("A must still be at the same leaf index");
        assert_eq!(na_now, RENEWED_NOT_AFTER,
            "THE GROUP'S COPY OF OUR CREDENTIAL MUST BE THE RE-MINTED ONE. If this is \
             PA_NOT_AFTER the Commit rotated the HPKE key and left the certificate behind, which \
             is the defect exactly.");
        // and nobody else moved, so it is a rotation, not a rebuild
        assert_eq!(v.len(), 2, "the roster must still be the two of us");
        let (_, _, b_na) = *v.iter().find(|(i, _, _)| *i != a_index).unwrap();
        assert_eq!(b_na, PA_NOT_AFTER,
            "B's own leaf must be untouched (the test fixtures share a validity window, which is \
             why A's index is taken from the engine rather than matched on notAfter)");

        // the detector now agrees
        assert_eq!(a2.self_leaf_status(&gid_a).unwrap()[36], 0,
            "after the Commit the group holds the certificate we hold");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Negative control: with an unchanged certificate the rotation does not fire, the window does
    /// not move, and the peer still accepts the commit.
    #[test]
    fn a_self_update_with_an_unchanged_certificate_does_not_rotate_the_credential() {
        let dir = format!("{}/renewalctl_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        const PA_NOT_AFTER: u64 = 1791387137;
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap();

        assert_eq!(a.self_leaf_status(&gid_a).unwrap()[36], 0, "nothing was re-minted");
        let bundle = split_len_prefixed(&a.self_update(&gid_a, b"mid-renewal-ctl").unwrap());
        assert!(b.process(&gid_b, &bundle[1]).is_ok(), "an ordinary rekey must still work");
        let v = parse_validity(&b.member_validity(&gid_b).unwrap());
        assert!(v.iter().any(|(_, _, na)| *na == PA_NOT_AFTER),
            "an ordinary rekey must leave the credential exactly where it was");
        let _ = std::fs::remove_dir_all(&dir);
    }

    // ---- a prior-epoch archive mls-rs will not extend ----

    /// A real two-party group where B has committed once, so A (the creator, leaf 0) is at epoch 2
    /// with the archive {0, 1}. Returns the directory, both sessions and both group ids.
    fn gap_pair(tag: &str) -> (String, ProdSession, ProdSession, Vec<u8>, Vec<u8>) {
        let dir = format!("{}/archgap_{}_{}", std::env::temp_dir().display(), tag,
            std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap();
        let c = split_len_prefixed(&b.self_update(&gid_b, b"b-rekey").unwrap());
        assert_eq!(a.process_ex(&gid_a, &c[1]).unwrap()[0], 1, "A applies B's commit");
        let store = FileGroupStateStorage::new(format!("{dir}/a"));
        assert_eq!(mls_rs::GroupStateStorage::max_epoch_id(&store, &gid_a).unwrap(), Some(1));
        (dir, a, b, gid_a, gid_b)
    }

    /// Rewrites the stamp of every archived epoch in A's v2 record: `f(id)` is the new stamp, 0
    /// for unknown (how a v1 record decodes). Writes the file directly, so no trim runs.
    fn gap_restamp(dir: &str, gid: &[u8], f: &dyn Fn(u64) -> u64) -> Vec<u64> {
        let path = std::path::PathBuf::from(format!("{dir}/a")).join(format!("g_{}.bin",
            gid.iter().map(|x| format!("{x:02x}")).collect::<String>()));
        let mut raw = std::fs::read(&path).unwrap();
        assert_eq!(&raw[0..5], b"RCSG\x02");
        let mut p = 5;
        let slen = u32::from_be_bytes(raw[p..p + 4].try_into().unwrap()) as usize;
        p += 4 + slen;
        let n = u32::from_be_bytes(raw[p..p + 4].try_into().unwrap()) as usize;
        p += 4;
        let mut ids = vec![];
        for _ in 0..n {
            let id = u64::from_be_bytes(raw[p..p + 8].try_into().unwrap());
            raw[p + 8..p + 16].copy_from_slice(&f(id).to_be_bytes());
            p += 16;
            let len = u32::from_be_bytes(raw[p..p + 4].try_into().unwrap()) as usize;
            p += 4 + len;
            ids.push(id);
        }
        std::fs::write(&path, &raw).unwrap();
        ids
    }

    fn gap_now_ms() -> u64 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis()
            as u64
    }

    /// The device case: epoch 0 archived before record stamps existed, epoch 1 after, then more
    /// than three quiet days. The trim used to drop 1 and keep 0, a gap that
    /// failed every commit with `apply: InvalidEpoch`; now it drops both, and our Self-Update and
    /// the peer's next commit both apply.
    #[test]
    fn an_undated_epoch_below_an_aged_one_no_longer_breaks_the_commits() {
        let (dir, a, b, gid_a, gid_b) = gap_pair("legacy");
        let four_days_ago = gap_now_ms() - 4 * 86_400_000;
        let ids = gap_restamp(&dir, &gid_a, &|id| if id == 0 { 0 } else { four_days_ago });
        assert_eq!(ids, vec![0, 1]);
        // any ordinary write runs the trim
        a.encrypt(&gid_a, b"hello", b"mid-1").unwrap();
        let store = FileGroupStateStorage::new(format!("{dir}/a"));
        assert_eq!(mls_rs::GroupStateStorage::max_epoch_id(&store, &gid_a).unwrap(), None,
            "the trim left the archive empty, not {{0}} under a group at epoch 2");
        let bundle = split_len_prefixed(&a.self_update(&gid_a, b"cred-update").unwrap());
        assert!(b.process(&gid_b, &bundle[1]).is_ok(), "the peer accepts our Self-Update");
        let c = split_len_prefixed(&b.self_update(&gid_b, b"b-rekey-2").unwrap());
        assert_eq!(a.process_ex(&gid_a, &c[1]).unwrap()[0], 1, "and we apply the peer's commit");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Control: an archive stamped throughout ages out whole.
    #[test]
    fn a_fully_stamped_archive_ages_out_whole() {
        let (dir, a, _b, gid_a, _gid_b) = gap_pair("ctl");
        let old = gap_now_ms() - 5 * 86_400_000;
        gap_restamp(&dir, &gid_a, &|id| old + id);
        a.encrypt(&gid_a, b"hello", b"mid-1").unwrap();
        let store = FileGroupStateStorage::new(format!("{dir}/a"));
        assert_eq!(mls_rs::GroupStateStorage::max_epoch_id(&store, &gid_a).unwrap(), None);
        assert!(a.self_update(&gid_a, b"x").is_ok());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A record already broken on disk, {0} under a group at epoch 2 as the older trim left it,
    /// is repaired where a commit is built: the Self-Update applies and the peer takes it.
    #[test]
    fn a_record_already_broken_is_healed_before_our_commit() {
        let (dir, a, b, gid_a, gid_b) = gap_pair("heal-commit");
        let store = FileGroupStateStorage::new(format!("{dir}/a"));
        let snap = store.export_state(&gid_a).unwrap();
        store.restore_state(&gid_a, &snap, 0);
        assert_eq!(mls_rs::GroupStateStorage::max_epoch_id(&store, &gid_a).unwrap(), Some(0));
        let bundle = split_len_prefixed(&a.self_update(&gid_a, b"cred-update").unwrap());
        assert!(b.process(&gid_b, &bundle[1]).is_ok(), "the peer accepts the healed commit");
        assert_eq!(mls_rs::GroupStateStorage::max_epoch_id(&store, &gid_a).unwrap(), Some(2),
            "the archive restarts at the epoch just left");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The same broken record is repaired before an inbound commit: without the heal A reports 8
    /// (buffer) for every commit the peer sends.
    #[test]
    fn a_record_already_broken_is_healed_before_an_inbound_commit() {
        let (dir, a, b, gid_a, gid_b) = gap_pair("heal-inbound");
        let store = FileGroupStateStorage::new(format!("{dir}/a"));
        let snap = store.export_state(&gid_a).unwrap();
        store.restore_state(&gid_a, &snap, 0);
        let c = split_len_prefixed(&b.self_update(&gid_b, b"b-rekey-2").unwrap());
        assert_eq!(a.process_ex(&gid_a, &c[1]).unwrap()[0], 1);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Every site that applies a commit or processes an inbound message loads the group through
    /// `load_for_apply`. Count-based, so a new site fails here until it is routed through the heal
    /// or added to the exempt list with a reason.
    #[test]
    fn every_apply_site_loads_through_the_heal() {
        let src = include_str!("ffi.rs");
        let prod = &src[..src.find("mod aad_id_check_tests").expect("the first test module")];
        // The enclosing function of byte offset `at`: the last `fn name(` before it.
        let enclosing = |at: usize| -> (String, usize) {
            let head = &prod[..at];
            let start = ["\n    pub fn ", "\n    fn ", "\nfn "].iter()
                .filter_map(|k| head.rfind(k).map(|i| i + k.len()))
                .max().expect("a function");
            let name: String = prod[start..].chars()
                .take_while(|c| c.is_alphanumeric() || *c == '_').collect();
            (name, start)
        };
        // A new group, or a reused id deleted just before: there is no archive to heal.
        const EXEMPT: &[&str] = &["create_group_carry", "commit_bundle"];
        let mut seen = Vec::new();
        for needle in [".apply_pending_commit()", ".process_incoming_message(",
                       "commit_bundle(&mut g, commit)"] {
            for (at, _) in prod.match_indices(needle) {
                let (name, start) = enclosing(at);
                seen.push(name.clone());
                if EXEMPT.contains(&name.as_str()) { continue; }
                assert!(prod[start..at].contains("self.load_for_apply(gid, "),
                    "{name} reaches {needle} without loading through load_for_apply");
            }
        }
        seen.sort();
        assert_eq!(seen, [
            "add_member", "add_members", "commit_arbitrary_ext_for_test", "commit_bundle",
            "commit_end_mls", "commit_era_advance_illegal_for_test", "commit_group_metadata",
            "create_group_carry", "process", "process_ex", "remove_member",
            "remove_member_by_msisdn", "self_update", "self_update_extpub",
        ], "the set of apply sites changed: route the new one through load_for_apply");
    }

    /// The GroupInfo signer names a member and tells two members apart.
    #[test]
    fn the_group_info_signer_names_which_member_signed_it() {
        let dir = format!("{}/htos_signer_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap();

        // indices from the engine; the fixture windows are identical
        let idx_of = |st: &[u8]| u32::from_be_bytes([st[0], st[1], st[2], st[3]]);
        let a_index = idx_of(&a.self_leaf_status(&gid_a).unwrap());
        let b_index = idx_of(&b.self_leaf_status(&gid_b).unwrap());
        assert_ne!(a_index, b_index, "the fixture must put the two members at different leaves");

        let gi_a = a.group_info_with_continuity(&gid_a, &[], true).unwrap();
        let gi_b = b.group_info_with_continuity(&gid_b, &[], true).unwrap();
        let signer_epoch = |gi: &[u8]| {
            let v = a.group_info_signer(gi).unwrap();
            assert_eq!(v.len(), 12, "the record is a fixed-width u32 signer + u64 epoch");
            (u32::from_be_bytes(v[0..4].try_into().unwrap()),
             u64::from_be_bytes(v[4..12].try_into().unwrap()))
        };
        let signer = |gi: &[u8]| signer_epoch(gi).0;
        // The signed epoch travels with the signer.
        assert_eq!(signer_epoch(&gi_a).1, a.client.load_group(&gid_a).unwrap().context().epoch,
            "the GroupInfo must report the epoch of the group it describes");
        assert_eq!(signer(&gi_a), a_index, "A's GroupInfo must name A's leaf");
        assert_eq!(signer(&gi_b), b_index, "B's GroupInfo must name B's leaf");
        assert_ne!(signer(&gi_a), signer(&gi_b),
            "the whole use is telling two members apart; equal answers would be worthless");

        // A resolves B's signer holding only its own group; empty bytes are refused.
        assert!(a.group_info_signer(&[]).is_err(), "empty bytes must be refused, not answered 0");
        assert!(a.group_info_signer(b"not a GroupInfo").is_err(),
            "unparseable bytes must be refused rather than reported as leaf 0");
        assert!(a.group_info_signer(&parts[5]).is_err(),
            "a RATCHET TREE is not a GroupInfo and must be refused rather than mis-parsed");
    }

    /// Parses `tree_member_validity`'s records.
    fn parse_tree_validity(packed: &[u8]) -> Vec<(u32, u64, u64, String)> {
        let mut out = Vec::new();
        let mut o = 0usize;
        while o + 24 <= packed.len() {
            let idx = u32::from_be_bytes(packed[o..o + 4].try_into().unwrap());
            let nb = u64::from_be_bytes(packed[o + 4..o + 12].try_into().unwrap());
            let na = u64::from_be_bytes(packed[o + 12..o + 20].try_into().unwrap());
            let ml = u32::from_be_bytes(packed[o + 20..o + 24].try_into().unwrap()) as usize;
            assert!(o + 24 + ml <= packed.len(), "record overruns the buffer");
            out.push(
                (idx, nb, na, String::from_utf8_lossy(&packed[o + 24..o + 24 + ml]).into_owned()));
            o += 24 + ml;
        }
        assert_eq!(o, packed.len(), "trailing bytes: the framing is wrong");
        out
    }

    /// Reading certificate windows from a serialized tree agrees exactly with the loaded group's
    /// `member_validity`, and mutates nothing.
    #[test]
    fn the_server_tree_reports_the_same_windows_as_the_loaded_group() {
        let dir = format!("{}/htos_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let tree = parts[5].clone();                      // the out-of-band ratchet tree
        let gid_b = b.join_with_tree(&parts[0].clone(), &tree).unwrap();

        // captured before the read, for the non-mutation check
        let epoch_before = a.client.load_group(&gid_a).unwrap().context().epoch;

        let from_tree = parse_tree_validity(&a.tree_member_validity(&tree).unwrap());
        let from_group = parse_validity(&b.member_validity(&gid_b).unwrap());
        assert_eq!(from_tree.len(), 2, "a two-member group has two leaves");
        assert_eq!(from_tree.len(), from_group.len(),
            "the parse-only path must see every member the loaded path sees");
        for (idx, nb, na, msisdn) in &from_tree {
            let (_, gnb, gna) = *from_group.iter().find(|(i, _, _)| i == idx)
                .expect("every leaf in the tree must exist in the loaded roster");
            assert_eq!((*nb, *na), (gnb, gna),
                "leaf {idx}: the tree and the loaded group must report the SAME window — a \
                 disagreement means one of them is reading a different certificate");
            assert!(*na > *nb, "leaf {idx}: a window must be ordered");
            // the SAN MSISDN is what makes a leaf identifiable
            assert!(!msisdn.is_empty(), "leaf {idx}: the SAN MSISDN must be carried");
        }
        let mut msisdns: Vec<&str> = from_tree.iter().map(|(_, _, _, m)| m.as_str()).collect();
        msisdns.sort();
        msisdns.dedup();
        assert_eq!(msisdns.len(), 2, "the two leaves must be distinguishable by MSISDN");

        // An empty tree is an error, not an empty roster.
        assert!(a.tree_member_validity(&[]).is_err(),
            "an empty tree must be refused, not answered");
        assert!(a.tree_member_validity(b"not a ratchet tree").is_err(),
            "unparseable bytes must be refused rather than reported as an empty roster");

        // nothing was mutated
        assert_eq!(a.client.load_group(&gid_a).unwrap().context().epoch, epoch_before,
            "reading the tree must not advance or disturb the local group");
    }

    /// Decodes `member_validity`'s 20-byte rows into `(leaf_index, not_before, not_after)`.
    fn parse_validity(packed: &[u8]) -> Vec<(u32, u64, u64)> {
        packed.chunks_exact(20).map(|c| (
            u32::from_be_bytes(c[0..4].try_into().unwrap()),
            u64::from_be_bytes(c[4..12].try_into().unwrap()),
            u64::from_be_bytes(c[12..20].try_into().unwrap()),
        )).collect()
    }

    #[test]
    /// The sender identity comes from the authenticated leaf: an envelope can name anyone, while
    /// `sender_index` is authenticated by the decrypt.
    fn the_sender_msisdn_comes_from_the_signing_leaf() {
        let dir = format!("{}/sender_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // A's certified identity, read as the claim-time check reads it
        let a_msisdn = {
            let ga = a.client.load_group(&gid_a).unwrap();
            let me = ga.current_member_index();
            ProdSession::certified_msisdn_of(&ga, me)
        };
        assert!(!a_msisdn.is_empty(),
            "the test leaf must carry a tel: SAN, else this proves nothing");

        // B decrypts and arrives at A's MSISDN
        let ct = a.encrypt(&gid_a, b"hi", b"aad").unwrap();
        b.process(&gid_b, &ct).unwrap();
        let seen = LAST_SENDER_MSISDN.with(|c| c.borrow().clone());
        assert_eq!(seen, a_msisdn, "the receiver must name the SIGNER, whatever the envelope said");

        // process_ex agrees
        let ct2 = a.encrypt(&gid_a, b"hi again", b"aad").unwrap();
        b.process_ex(&gid_b, &ct2).unwrap();
        assert_eq!(LAST_SENDER_MSISDN.with(|c| c.borrow().clone()), a_msisdn,
            "process_ex must bind the sender too — a check on only one path is not a check");
    }

    #[test]
    /// A non-application message clears the sender identity, so it is never attributed to the
    /// next message.
    fn a_commit_clears_the_sender_identity() {
        let dir = format!("{}/sender2_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        let ct = a.encrypt(&gid_a, b"hi", b"aad").unwrap();
        b.process(&gid_b, &ct).unwrap();
        assert!(!LAST_SENDER_MSISDN.with(|c| c.borrow().clone()).is_empty());

        // B applies A's commit, not an application message
        let commit = split_len_prefixed(&a.self_update(&gid_a, b"aad").unwrap())[1].clone();
        b.process_ex(&gid_b, &commit).unwrap();
        assert!(LAST_SENDER_MSISDN.with(|c| c.borrow().clone()).is_empty(),
            "a commit must leave NO sender identity behind — stale is worse than absent here");
    }

    #[test]
    /// `process_results` emits the record layout `MlsEngineResult` decodes; the Java side pins the
    /// same layout from hand-built fixtures.
    fn process_results_emits_the_versioned_record_list() {
        let dir = format!("{}/presults_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        let ct = a.encrypt(&gid_a, b"hello", b"aad").unwrap();
        let out = b.process_results(&gid_b, &ct, b"ctx-1").unwrap();

        let records = split_len_prefixed(&out);
        assert_eq!(records.len(), 2, "a version record plus one result");
        assert_eq!(records[0], vec![1u8], "format version 1");

        let fields = split_len_prefixed(&records[1]);
        assert_eq!(fields.len(), 5, "hdr, ctx, gid, aux, payload");
        assert_eq!(fields[0], vec![0u8], "status 0 = APPLICATION");
        assert_eq!(fields[1], b"ctx-1".to_vec(), "the context must be echoed VERBATIM");
        assert_eq!(fields[2], gid_b, "the group id is echoed from the request");
        assert!(fields[3].is_empty(), "aux is proposal-type only, and this is not a proposal");
        assert_eq!(fields[4], b"hello".to_vec(), "the plaintext rides the payload field");
    }

    #[test]
    fn inbound_proposal_surfaces_its_type_and_can_be_dropped() {
        let dir = format!("{}/props_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // B sends a server_remove (0xF004), built through mls-rs directly since we have no builder.
        // Its body is a 4-byte big-endian LeafIndex, the shape mls-rs decodes with
        // gsma_rcs_e2ee_feature and the shape MlsParticipantKeyResync writes.
        let mut gb = b.client.load_group(&gid_b).unwrap();
        let prop = gb.propose_custom(
            CustomProposal::new(ProposalType::from(0xF004u16), vec![0, 0, 0, 1]), Vec::new())
            .unwrap();
        gb.write_to_storage().unwrap();
        let wire = prop.to_bytes().unwrap();

        // A learns which proposal it is
        let r = a.process_ex(&gid_a, &wire).unwrap();
        assert_eq!(r[0], 2, "status must be PROPOSAL");
        assert_eq!(((r[1] as u16) << 8) | r[2] as u16, 0xF004, "the type must reach the caller");

        // and A is blocked until it is committed
        assert_eq!(a.commit_required(&gid_a).unwrap(), vec![1u8],
            "a cached proposal must block sends — this is the wedge");

        // Dropping it keeps the conversation alive; committing would remove nobody.
        a.clear_pending_proposals(&gid_a).unwrap();
        assert_eq!(a.commit_required(&gid_a).unwrap(), vec![0u8],
            "dropping the proposal must clear the block");
        let ct = a.encrypt(&gid_a, b"unwedged", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"unwedged");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The KeyPackage leaf lifetime is anchored at the certificate's notBefore, asserted on the
    /// helper and on the bytes of a minted KeyPackage.
    #[test]
    fn key_package_lifetime_is_anchored_at_the_certificate() {
        const DAY: u64 = 24 * 3600;
        let leaf = td("pki2_leaf_pb.der");
        let c = Certificate::from_der(&leaf).unwrap();
        let nb = c.tbs_certificate.validity.not_before.to_unix_duration().as_secs();
        let na = c.tbs_certificate.validity.not_after.to_unix_duration().as_secs();

        // FIXED_365_DAYS: not_before = cert notBefore, not_after = +365 days
        assert_eq!(kp_lifetime_window(&leaf, true), (Some(nb), 365 * DAY));
        // WITHIN_CERTIFICATE: same anchor, and the package must not outlive the certificate
        let (rcc16_anchor, rcc16_span) = kp_lifetime_window(&leaf, false);
        assert_eq!(rcc16_anchor, Some(nb));
        assert!(nb + rcc16_span < na, "an RCC.16 KeyPackage must expire before its certificate");

        // The default is WITHIN_CERTIFICATE; kp_inspect reads not_after from the minted bytes.
        let dir = format!("{}/kplife_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let s = ProdSession::start_no_revocation(&leaf, &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &dir).unwrap();
        let kp = split_len_prefixed(&s.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let info = s.kp_inspect(&kp).unwrap();
        assert_eq!(info[0], 0, "a pool KeyPackage is one-time, not last-resort");
        assert_eq!(u64::from_be_bytes(info[1..9].try_into().unwrap()), nb + rcc16_span);

        // the last-resort package carries the same window and its flag
        let lr = s.generate_last_resort_key_package().unwrap();
        let lri = s.kp_inspect(&lr).unwrap();
        assert_eq!(lri[0], 1, "the last-resort KeyPackage must carry the last_resort extension");
        assert_eq!(u64::from_be_bytes(lri[1..9].try_into().unwrap()), nb + rcc16_span);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Each bit of the settings byte moves one setting, and unknown bits move neither.
    #[test]
    fn the_settings_byte_carries_two_independent_bits() {
        assert_eq!(decode_settings(0), (false, false));
        assert_eq!(decode_settings(SETTING_KP_FIXED_365_DAYS), (true, false));
        assert_eq!(decode_settings(SETTING_PEER_CERT_TOLERANT), (false, true));
        assert_eq!(decode_settings(SETTING_KP_FIXED_365_DAYS | SETTING_PEER_CERT_TOLERANT),
                   (true, true));
        assert_eq!(decode_settings(0xfc), (false, false));
    }

    /// The Java side builds the byte from its own constants; they must be these values.
    #[test]
    fn the_java_settings_constants_match() {
        let p = concat!(env!("CARGO_MANIFEST_DIR"),
            "/../../engine/src/com/android/messaging/rcs/engine/mls/OpenMlsNative.java");
        let src = std::fs::read_to_string(p).unwrap_or_else(|e| panic!("{p}: {e}"));
        for (name, v) in [("SETTING_KP_FIXED_365_DAYS", SETTING_KP_FIXED_365_DAYS),
                          ("SETTING_PEER_CERT_TOLERANT", SETTING_PEER_CERT_TOLERANT)] {
            let decl = format!("static final int {name} = 0x{v:02x};");
            assert!(src.contains(&decl), "OpenMlsNative.java must declare `{decl}`");
        }
    }

    /// `kp_inspect` reports the certificate's window, and the leaf Lifetime is not it: under
    /// FIXED_365_DAYS a certificate one day from expiry still has a Lifetime about 334 days out,
    /// so a 30-day floor on the Lifetime could never fire.
    #[test]
    fn kp_inspect_reports_the_certificate_window_not_only_the_leaf_lifetime() {
        const DAY: u64 = 24 * 3600;
        let leaf = td("pki2_leaf_pb.der");
        let c = Certificate::from_der(&leaf).unwrap();
        let nb = c.tbs_certificate.validity.not_before.to_unix_duration().as_secs();
        let na = c.tbs_certificate.validity.not_after.to_unix_duration().as_secs();

        // pure arithmetic, no session or clock
        let (anchor, span) = kp_lifetime_window(&leaf, true);
        assert_eq!((anchor, span), (Some(nb), 365 * DAY));
        let lifetime_na = nb + span;
        // at the certificate's last full day
        let one_day_before_cert_expiry = na - DAY;
        assert!(lifetime_na - one_day_before_cert_expiry > 30 * DAY,
            "the LeafNode Lifetime still reports {}d left when the CERTIFICATE has 1 day — a \
             30-day floor on the Lifetime is unreachable under FIXED_365_DAYS",
            (lifetime_na - one_day_before_cert_expiry) / DAY);

        // the window is reported, whichever setting produced the Lifetime
        let dir = format!("{}/kpcert_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let s = ProdSession::start_no_revocation(&leaf, &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &dir).unwrap();
        let kp = split_len_prefixed(&s.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let info = s.kp_inspect(&kp).unwrap();
        let n = info[11] as usize;
        let w = 12 + 2 * n;
        assert!(info.len() >= w + 16, "kp_inspect must carry the 16-byte certificate window");
        assert_eq!(u64::from_be_bytes(info[w..w + 8].try_into().unwrap()), nb,
            "cert notBefore");
        assert_eq!(u64::from_be_bytes(info[w + 8..w + 16].try_into().unwrap()), na,
            "cert notAfter");
        // the two clocks differ here, so a build reporting the Lifetime twice fails
        assert_ne!(u64::from_be_bytes(info[1..9].try_into().unwrap()), na,
            "the Lifetime not_after and the certificate notAfter are different artefacts");
        // the MSISDN still decodes behind the window
        let msisdn = String::from_utf8_lossy(&info[w + 16..]).to_string();
        assert!(msisdn.chars().all(|ch| ch.is_ascii_digit()),
            "the SAN identity must still be bare ASCII digits after the window, got {msisdn:?}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The leaf anchor is in the past and inside the certificate window, never now, and the
    /// fallback is a real value rather than `None`.
    #[test]
    fn the_leaf_lifetime_anchor_is_in_the_past_and_inside_the_cert_window() {
        let dir = format!("{}/anchor_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let s = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &dir)
            .unwrap();
        let now = wall_clock_secs();
        let a = s.leaf_lifetime_anchor().expect("a readable clock must yield an anchor, not None");
        let secs = a.seconds_since_epoch();

        assert!(secs <= now,
            "the anchor must never be in the future — that IS the bug (got {secs}, now {now})");
        if let Some(nb) = s.kp_not_before {
            assert!(secs >= nb.seconds_since_epoch(),
                "the anchor must not predate our own certificate, or chain validation fails at it");
        }
        // Either the full skew margin, or the certificate floor stopped it short.
        let floor = s.kp_not_before.map(|t| t.seconds_since_epoch()).unwrap_or(0);
        assert!(secs <= now - 24 * 3600 || secs == floor,
            "the anchor must clear the 24h skew margin unless the cert floor raised it (got {secs}, \
             now {now}, floor {floor})");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// No call site may mint a leaf without the shared anchor; only a foreign peer would notice, so
    /// this is a source guard.
    #[test]
    fn every_leaf_minting_call_site_carries_the_anchor() {
        let src = include_str!("ffi.rs");
        // strip this test's own body so its literals do not count
        let prod = src.split("fn every_leaf_minting_call_site_carries_the_anchor").next().unwrap();

        assert!(
            !prod.contains(
                "generate_key_package_message(Default::default(), Default::default(), None)"),
            "a KeyPackage is being minted with None — it must pass self.kp_not_before");
        assert!(
            !prod.contains("create_group_with_id(gid_bytes, group_ctx, Default::default(), None)"),
            "a group is being created with None — it must pass self.leaf_lifetime_anchor()");
        // every external commit mints a leaf, so every builder gets a commit time
        let builders = prod.matches("external_commit_builder()").count();
        let stamped = prod.matches(".commit_time(t)").count();
        assert_eq!(builders, stamped,
            "{builders} external_commit_builder() call(s) but {stamped} commit_time() — an external \
             commit without one anchors its new leaf at NOW and is rejected by any peer whose clock \
             trails ours");
    }

    /// mls-rs still mints a leaf Lifetime at exactly the three places we anchor. If this fails
    /// after an mls-rs upgrade, read the new site and, if it mints a leaf sent to peers, route it
    /// through `leaf_lifetime_anchor()` before updating the expected set.
    #[test]
    fn mls_rs_mints_a_leaf_lifetime_in_exactly_the_three_places_we_anchor() {
        fn walk(dir: &std::path::Path, out: &mut Vec<(String, usize)>) {
            let Ok(rd) = std::fs::read_dir(dir) else { return };
            for e in rd.flatten() {
                let p = e.path();
                if p.is_dir() { walk(&p, out); continue; }
                if p.extension().and_then(|s| s.to_str()) != Some("rs") { continue; }
                let Ok(text) = std::fs::read_to_string(&p) else { continue };
                // test_utils and cfg(test) bodies mint lifetimes for assertions only
                if p.to_string_lossy().contains("test_utils") { continue; }
                let n = text.matches("config.lifetime(").count()
                    // client.rs asserts on a minted KP inside a test module
                    - text.matches("client.config.lifetime(None)").count();
                if n > 0 { out.push((p.to_string_lossy().into_owned(), n)); }
            }
        }
        // The sources the mls-rs path dependency in Cargo.toml reaches through
        // external/mls-rs/android/cargo/mls-rs; a missing directory fails the assert below
        // rather than passing with nothing checked.
        let vendor = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../../external/mls-rs/mls-rs/src");
        assert!(vendor.is_dir(),
            "vendored mls-rs not found at {vendor:?} — did the vendor path move?");
        let mut found = Vec::new();
        walk(&vendor, &mut found);
        found.sort();

        let mut names: Vec<String> = found.iter()
            .map(|(f, n)| format!("{}x{}", f.rsplit("/src/").next().unwrap_or(f), n))
            .collect();
        names.sort();
        assert_eq!(names, vec!["client.rsx1".to_string(),
                               "group/external_commit.rsx1".to_string(),
                               "group/mod.rsx1".to_string()],
            "the set of leaf-Lifetime mint sites in mls-rs changed — every one of them must be fed \
             by leaf_lifetime_anchor(), never by None. Found: {names:?}");
    }

    /// Two sessions in a scratch dir and one of B's KeyPackages, for the `plan_group` tests.
    fn plan_fixture(tag: &str) -> (String, ProdSession, ProdSession, Vec<u8>) {
        let dir = format!("{}/plan_{}_{}", std::env::temp_dir().display(),
                          std::process::id(), tag);
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(4).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        (dir, a, b, bkp)
    }

    /// A group nobody has held is born at era 1, whatever the server holds: no group and no carried
    /// state means NEW_GROUP.
    #[test]
    fn plan_group_with_nothing_known_is_a_new_group_at_era_one() {
        let (dir, a, _b, bkp) = plan_fixture("new");
        let (era, action, add) = a.plan_group(&[bkp], b"grp-never-seen", &[]).unwrap();
        assert_eq!(era, crate::rcc16::ERA_INITIAL);
        assert_eq!(action, crate::rcc16::WELCOME_ACTION_NEW_GROUP);
        assert!(add.is_empty(), "a create admits members through its Welcome, not through slot 8");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A group we hold advances past our own era.
    #[test]
    fn plan_group_over_a_group_we_hold_advances_past_our_own_era() {
        let (dir, a, b, bkp) = plan_fixture("adv");
        let gid = b"grp-held".to_vec();
        a.create_group_multi(3, std::slice::from_ref(&bkp), &gid).unwrap();
        // a different member; the same one would be arm 3's refresh
        let other = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (era, action, _) = a.plan_group(&[other], &gid, &[]).unwrap();
        assert_eq!(era, 4, "we hold era 3, so the next one is 4 — derived here, not passed in");
        assert_eq!(action, crate::rcc16::WELCOME_ACTION_NEW_ERA_EXISTING_GROUP);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The server is ahead of us: the engine advances past the server's era, read from the
    /// GroupInfo bytes it was handed.
    #[test]
    fn plan_group_advances_past_the_servers_era_when_the_server_is_ahead() {
        let (dir, a, b, bkp) = plan_fixture("srv");
        let gid = b"grp-behind".to_vec();
        // we hold era 1
        a.create_group_multi(1, std::slice::from_ref(&bkp), &gid).unwrap();
        // the "server" holds era 5, built by B so its GroupInfo is foreign to A
        let bkp2 = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (_, srv_art) = a.create_group_multi(5, &[bkp2], b"grp-server-copy").unwrap();
        let server_gi = split_len_prefixed(&srv_art)[2].clone();
        let other = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (era, action, _) = a.plan_group(&[other], &gid, &server_gi).unwrap();
        assert_eq!(era, 6,
            "the carried GroupInfo says era 5, so the next era is 6 — not our own 2");
        assert_eq!(action, crate::rcc16::WELCOME_ACTION_NEW_ERA_EXISTING_GROUP);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// No local group but a carried server GroupInfo: the era is `server_era + 1`, not
    /// [`ERA_INITIAL`](crate::rcc16::ERA_INITIAL). A rebuild drops its local state, so the carry
    /// alone must be enough; without it the rebuild lands at era 1 as a different group at the same
    /// era.
    #[test]
    fn plan_group_with_no_local_state_but_a_carry_rebuilds_past_the_servers_era() {
        let (dir, a, b, bkp) = plan_fixture("rebuild");
        // The "server" holds era 7 at an id we do not plan against, so the planning id has no local
        // group.
        let (_, srv_art) = a.create_group_multi(7, std::slice::from_ref(&bkp),
            b"grp-server-side").unwrap();
        let server_gi = split_len_prefixed(&srv_art)[2].clone();
        let other = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        assert!(a.client.load_group(b"grp-forgotten").is_err(),
                "precondition: nothing local at the id we are rebuilding");
        let (era, action, add) = a.plan_group(&[other], b"grp-forgotten", &server_gi).unwrap();
        assert_eq!(era, 8, "the carried GroupInfo says era 7, so a rebuild lands at 8 — an absent \
                            local group must not drag this back to ERA_INITIAL");
        assert_eq!(action, crate::rcc16::WELCOME_ACTION_NEW_ERA_EXISTING_GROUP,
                   "a conversation the server still holds is an ADVANCE, not a new group");
        assert!(add.is_empty(), "a rebuild admits members through its Welcome, not through slot 8");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The additive rule over identities directly; the fixtures hold only two MSISDNs, too few for
    /// a three-party roster through `plan_group`.
    #[test]
    fn additions_are_selected_by_participant_and_not_by_device() {
        let a = b"+15551110001".to_vec();
        let b = b"+15551110002".to_vec();
        let c = b"+15551110003".to_vec();
        let held = vec![a.clone(), b.clone()];
        assert_eq!(crate::rcc16::additions_to(&held, &[a.clone(), b.clone(), c.clone()]), vec![2],
            "only the participant who is not already a member counts as an addition");
        // A second device of a present participant is not an addition.
        assert!(crate::rcc16::additions_to(&held, &[b.clone(), b.clone()]).is_empty());
        // An unreadable identity counts as new.
        assert_eq!(crate::rcc16::additions_to(&held, &[Vec::new()]), vec![0]);
        // nothing held: every request is an addition
        assert_eq!(crate::rcc16::additions_to(&[], &[a, b]), vec![0, 1]);
    }

    /// A held group asked for members all already in it is a refresh, not an add: arm 3 needs some
    /// participant to be new.
    #[test]
    fn an_all_known_roster_is_not_an_add() {
        let (dir, a, b, bkp) = plan_fixture("add");
        let gid = b"grp-additive".to_vec();
        a.create_group_multi(2, std::slice::from_ref(&bkp), &gid).unwrap();
        // a second device of the same participant
        let second_device = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (era, action, add) = a.plan_group(&[bkp.clone(), second_device], &gid, &[]).unwrap();
        assert_eq!(action, crate::rcc16::WELCOME_ACTION_NEW_ERA_EXISTING_GROUP);
        assert_eq!(era, 3);
        assert!(add.is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A carried GroupInfo means an advance was intended: never an add, even when the roster looks
    /// additive.
    #[test]
    fn a_carried_group_info_is_never_downgraded_to_an_add() {
        let (dir, a, b, bkp) = plan_fixture("carry");
        let gid = b"grp-carry".to_vec();
        let (_, art) = a.create_group_multi(2, std::slice::from_ref(&bkp), &gid).unwrap();
        let our_gi = split_len_prefixed(&art)[2].clone();
        let newcomer = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (era, action, _) = a.plan_group(&[bkp.clone(), newcomer], &gid, &our_gi).unwrap();
        assert_eq!(action, crate::rcc16::WELCOME_ACTION_NEW_ERA_EXISTING_GROUP);
        assert_eq!(era, 3);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A planned create's bundle carries the engine's answer in slots 6 and 7.
    #[test]
    fn create_group_planned_appends_the_era_and_the_action() {
        let (dir, a, _b, bkp) = plan_fixture("bundle");
        let (_, art) = a.create_group_planned(&[bkp], b"grp-planned", &[],
                                              AdvanceEraKind::Normal).unwrap();
        let parts = split_len_prefixed(&art);
        assert!(parts.len() >= 9, "slots 0..5 plus era, action and admitted — got {}", parts.len());
        assert_eq!(u32::from_be_bytes(parts[6].clone().try_into().unwrap()),
                   crate::rcc16::ERA_INITIAL);
        assert_eq!(u32::from_be_bytes(parts[7].clone().try_into().unwrap()),
                   crate::rcc16::WELCOME_ACTION_NEW_GROUP);
        assert!(!parts[0].is_empty(), "a create must still produce a Welcome");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Every advertised code point is implemented or listed as deliberately advertised-only, and
    /// production and the conformance fixture advertise the same set.
    #[test]
    fn the_advertised_set_is_implemented_or_explicitly_advertised_only() {
        use crate::rcc16::*;
        let ext = advertised_extensions(&[]);
        for e in IMPLEMENTED_EXTENSIONS {
            assert!(ext.contains(e), "implemented ext {e:#06x} is NOT advertised — an \
                under-advertisement is as wrong as an over-advertisement");
        }
        // accounted for: implemented, fixed advertised-only, the range, or the metadata-keys code
        // point (from `metadata_keys_ext()`, overridable)
        for e in &ext {
            let known = IMPLEMENTED_EXTENSIONS.contains(e)
                || ADVERTISED_UNIMPLEMENTED_EXTENSIONS_FIXED.contains(e)
                || ADVERTISED_VENDOR_EXT_RANGE.contains(e)
                || metadata_keys_ext() == Some(*e);
            assert!(known, "ext {e:#06x} is advertised and appears in NO list — an advertised code \
                point with no account of why is the thing these lists exist to prevent");
        }
        let props = advertised_proposals(&[]);
        for p in IMPLEMENTED_PROPOSALS {
            assert!(props.contains(p), "implemented proposal {p:#06x} is NOT advertised");
        }
        // rcs_signature is implemented and advertised
        assert!(props.contains(&RCS_SIGNATURE_PROP));
        // the icon/subject keys are implemented (commit_group_metadata)
        assert!(ext.contains(&ICON_KEY_EXT) && ext.contains(&SUBJECT_KEY_EXT));
    }

    /// Arming the 0xF007 number does not arm a framing: `ext_payload_is_bare` does not answer for
    /// it, and the encoding follows the announced revision.
    #[test]
    fn arming_the_metadata_keys_code_point_does_not_arm_its_framing() {
        use crate::rcc16::*;
        let _g = crate::rcc16::METADATA_KEYS_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        set_metadata_keys_ext(METADATA_KEYS_REQUESTED_EXT);
        assert!(advertised_extensions(&[]).contains(&METADATA_KEYS_REQUESTED_EXT),
            "the number is published — advertising it is now the right position");
        assert!(!ext_payload_is_bare(METADATA_KEYS_REQUESTED_EXT),
            "the framing is VERSION-DEPENDENT and must not ride along with the number; \
             `ext_payload_is_bare` is the fixed-rule table and this type is not in it");
        // encoding succeeds; the framing follows the revision
        assert!(ext_encode(METADATA_KEYS_REQUESTED_EXT, METADATA_KEYS_REQUESTED_DATA).is_ok(),
            "§10.5.2 needs a producer: a client asks for metadata keys via this extension");

        // still clearable
        set_metadata_keys_ext(0);
        assert_eq!(metadata_keys_ext(), None);
        assert!(!advertised_extensions(&[]).contains(&METADATA_KEYS_REQUESTED_EXT));
        set_metadata_keys_ext(METADATA_KEYS_REQUESTED_EXT);
    }

    /// The continuity commitment reaches a GroupInfo only under v4.0, through mls-rs's own signing
    /// and serialisation.
    #[test]
    fn the_continuity_commitment_lands_in_the_group_info_only_under_v4() {
        use crate::rcc16::*;
        let _v = crate::rcc16::VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = format!("{}/cont_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());
        let (gid, _artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let commitment = vec![0xAB; 32];

        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        let gi3 = a.group_info_with_continuity(&gid, &commitment, false).unwrap();
        assert!(a.group_info_continuity(&gi3, CONTINUITY_TOKEN_COMMITMENT_EXT).unwrap().is_empty(),
            "v3.0 must not put a code point on the wire that v3.0 servers do not carry");

        assert_eq!(set_rcc16_version(RCC16_V4_0), RCC16_V4_0);
        let gi4 = a.group_info_with_continuity(&gid, &commitment, false).unwrap();
        assert_eq!(a.group_info_continuity(&gi4, CONTINUITY_TOKEN_COMMITMENT_EXT).unwrap(),
                   commitment, "the DECODED value must round-trip through the varint framing");
        // the token never appears in a server-bound GroupInfo
        assert!(a.group_info_continuity(&gi4, CONTINUITY_TOKEN_EXT).unwrap().is_empty(),
            "0xF010 is Welcome-only — putting it here hands the server the group secret");

        // reading is not gated
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        assert_eq!(a.group_info_continuity(&gi4, CONTINUITY_TOKEN_COMMITMENT_EXT).unwrap(),
                   commitment, "decode tolerantly — a server may upgrade before we notice");

        // The reader also serves arbitrary types (0x0004 external_pub, 0x0005 external_senders),
        // and must not refuse them, since a refusal reaches the host as "absent". 0xF001 lives in
        // the GroupContext, so one assertion covers both the arbitrary type and that list.
        let (era_val, era_where) = a.group_info_ext_either_list(&gi4, ERA_EXT).unwrap();
        assert_eq!(era_where, "GroupContext",
            "0xF001 is a GroupContext extension — reading only gi.extensions() misses it, which is \
             exactly how an earlier probe got the same answer wrong twice");
        assert!(!era_val.is_empty(), "the era extension must read back non-empty");

        // in neither list: reported as a location, "absent"
        let (none_val, none_where) = a.group_info_ext_either_list(&gi4, 0x0BAD).unwrap();
        assert!(none_val.is_empty());
        assert_eq!(none_where, "absent");
    }

    /// The transport override adds without duplicating and never drops the engine's own set.
    #[test]
    fn the_transport_override_extends_rather_than_replaces() {
        use crate::rcc16::*;
        let base = advertised_extensions(&[]);
        let with = advertised_extensions(&[0xABCD, ERA_EXT]);
        assert!(with.contains(&0xABCD), "a transport extra must be advertised");
        assert_eq!(with.iter().filter(|e| **e == ERA_EXT).count(), 1,
            "re-asserting something already advertised must not duplicate it");
        for e in &base { assert!(with.contains(e), "the override must not drop the engine's set"); }
    }

    /// The u32 era ceiling is refused, not wrapped.
    #[test]
    fn the_era_ceiling_is_refused_rather_than_wrapped() {
        assert_eq!(crate::rcc16::next_era(41).unwrap(), 42);
        assert!(crate::rcc16::next_era(u32::MAX).is_err());
    }

    /// The treeless-Welcome splice on a real two-leaf (1:1) group with a blank parent: the spliced
    /// tree equals mls-rs's export byte for byte, and `join_treeless_welcome` then verifies the
    /// tree hash and every leaf signature. Layout: `[varint][01 01 <leaf0>][00][01 01 <leaf1>]`,
    /// the parent blank because an add-only commit has no UpdatePath (RFC 9420 §12.4).
    #[test]
    fn treeless_welcome_joins_a_real_two_leaf_group() {
        let dir = format!("{}/tw2leaf_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        let kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());
        let (gid_a, artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let (welcome, tree) = (&parts[0], &parts[5]);
        assert_eq!(a.client.load_group(&gid_a).unwrap().roster().members().len(), 2,
            "the shape under test is a 1:1 group — two leaves, one parent");

        // the Welcome and the node sequence in one blob, with framing the locator must reject
        let mut blob = vec![0x0au8, 0x8f, 0x01, 0x12, 0x04, 0xde, 0xad, 0xbe];
        blob.extend_from_slice(welcome);
        let tree_at = blob.len();
        blob.extend_from_slice(tree);
        blob.extend_from_slice(&[0x18, 0x01, 0x22, 0x00, 0x2a, 0x08, 0xca, 0xfe]);

        // 1. both leaves found, and only those
        let leaves = locate_leaves(&blob);
        assert_eq!(leaves.len(), 2,
            "a 2-leaf group must yield exactly two leaf spans, got {leaves:?}");
        assert_eq!(leaf_count(&blob), 2);

        // 2. the parent lies between the leaves: a blank parent is exactly one byte
        let gap = &blob[leaves[0].1..leaves[1].0 - 2];
        assert_eq!(gap, &[0x00],
            "node 1 must sit between the leaves as a single blank optional<Node>, got {gap:02x?}");

        // 3. byte-identical to the export, outer varint included
        let spliced = splice_ratchet_tree(&blob).expect("splice");
        assert_eq!(&spliced, tree,
            "the splice must reproduce the exported ratchet_tree exactly — same length framing, \
             same node sequence, nothing of the surrounding blob dragged in");
        assert_eq!(leaves[0].0 - 2, tree_at + 2, "leaf0's node prefix follows the outer varint");

        // 4. join through it: tree hash and leaf signatures verified, then traffic
        let gid_b = b.join_treeless_welcome(welcome, &blob)
            .expect("a 2-leaf treeless Welcome must join");
        let ct = a.encrypt(&gid_a, b"one to one", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"one to one",
            "a joined member that cannot read traffic joined the wrong tree");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The same two-leaf splice with a populated parent (a path commit fills node 1 with
    /// `01 02 <ParentNode>`). Validated by `external_join` with the spliced tree, which checks it
    /// against the GroupInfo's tree hash.
    #[test]
    fn treeless_welcome_splices_a_two_leaf_group_with_a_populated_parent() {
        let dir = format!("{}/tw2par_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"),
            &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"),
            &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/b")).unwrap();
        // a third identity, outside the group, to validate the spliced tree
        let c = ProdSession::start_no_revocation(&td("pki2_leaf_pc.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pc_priv.bin"),
            &td("pki2_leaf_pc_pub.bin"), &[td("pki2_root.der")], &format!("{dir}/c")).unwrap();
        let kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());
        let (gid_a, artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // a path commit at two members fills node 1
        let up = split_len_prefixed(&a.self_update_extpub(&gid_a, b"mid-pathcommit").unwrap());
        b.process(&gid_b, &up[1]).unwrap();
        let (ginfo, tree) = (&up[2], &up[5]);

        let mut blob = Vec::new();
        blob.extend_from_slice(ginfo);
        blob.extend_from_slice(tree);
        let leaves = locate_leaves(&blob);
        assert_eq!(leaves.len(), 2, "still two leaves after a path commit, got {leaves:?}");

        // a real ParentNode where the blank one was
        let gap = &blob[leaves[0].1..leaves[1].0 - 2];
        assert_eq!(&gap[..2], &[0x01, 0x02],
            "node 1 must be a PRESENT parent (01 02), got {:02x?}", &gap[..2.min(gap.len())]);
        assert!(gap.len() > 2,
            "a populated parent carries an HPKE key — a 2-byte one would mean we read a header and \
             then walked off the node the splice is supposed to carry");

        let spliced = splice_ratchet_tree(&blob).expect("splice");
        assert_eq!(&spliced, tree, "the splice must carry the populated parent through verbatim");

        // mls-rs validates the tree against the GroupInfo's tree hash
        c.external_join(ginfo, &spliced)
            .expect("an external commit against the SPLICED tree must validate — if the parent were \
                     dropped or mangled the tree hash would not match the GroupInfo");
        let _ = std::fs::remove_dir_all(&dir);
    }

    // ---- the peer's advertised cipher suites ----

    /// `b` prefixed with its MLS varint length (all lengths here are below 64).
    fn v(b: &[u8]) -> Vec<u8> {
        assert!(b.len() < 64, "test helper only encodes 1-byte varints");
        let mut o = vec![b.len() as u8];
        o.extend_from_slice(b);
        o
    }

    /// A synthetic KeyPackage prefix up to and past `cipher_suites`.
    fn synthetic_kp(cred_type: u16, suites: &[u8]) -> Vec<u8> {
        let mut kp = vec![0x00, 0x01, 0x00, 0x02];  // version=mls10, cipher_suite=P256_AES128
        kp.extend(v(&[0xAA; 3]));                   // init_key<V>
        kp.extend(v(&[0xBB; 3]));                   // encryption_key<V>
        kp.extend(v(&[0xCC; 3]));                   // signature_key<V>
        kp.extend_from_slice(&cred_type.to_be_bytes());
        kp.extend(v(b"tel:+15551234567"));          // basic identity or x509 chain: one <V>
        kp.extend(v(&[0x00, 0x01]));                // capabilities.versions<V> = [mls10]
        kp.extend(v(suites));                       // capabilities.cipher_suites<V>
        kp.extend(v(&[]));                          // capabilities.extensions<V>, never reached
        kp
    }

    /// The walk reads the field and follows it when it changes: two advertisements must read back
    /// differently. The grease value 0x0A0A is kept, not filtered.
    #[test]
    fn leaf_cipher_suites_reads_the_second_capabilities_vector() {
        let kp = synthetic_kp(1, &[0x00, 0x01, 0x00, 0x02, 0x0A, 0x0A]);
        assert_eq!(leaf_cipher_suites(&kp).unwrap(), vec![0x0001u16, 0x0002, 0x0A0A]);

        // a different list reads back differently
        let kp2 = synthetic_kp(1, &[0x00, 0x07]);
        assert_eq!(leaf_cipher_suites(&kp2).unwrap(), vec![0x0007u16]);

        // empty is legal and means unknown, not an error
        assert_eq!(leaf_cipher_suites(&synthetic_kp(1, &[])).unwrap(), Vec::<u16>::new());

        // x509(2) has basic(1)'s one-<V> shape; the wrapped form is accepted
        let x509 = synthetic_kp(2, &[0x00, 0x02]);
        assert_eq!(leaf_cipher_suites(&x509).unwrap(), vec![0x0002u16]);
        assert_eq!(leaf_cipher_suites(&wrap_key_package(&x509)).unwrap(), vec![0x0002u16]);
    }

    /// An unreadable advertisement is refused rather than guessed; `kp_inspect` turns the error
    /// into "unknown".
    #[test]
    fn an_unreadable_advertisement_is_an_error_not_a_guess() {
        // an unknown credential type has an unknown layout
        assert!(leaf_cipher_suites(&synthetic_kp(3, &[0x00, 0x02])).is_err(),
            "an unknown credential_type must not be walked past");
        // an odd byte count is not whole u16 code points
        assert!(leaf_cipher_suites(&synthetic_kp(1, &[0x00, 0x02, 0x00])).is_err(),
            "an odd-length cipher_suites vector is malformed, not a truncated list");
        // Every prefix shorter than the shortest that parses must fail, and that prefix must give
        // the right answer, so the walk never reads past `cipher_suites`.
        let kp = synthetic_kp(1, &[0x00, 0x02]);
        let shortest = (0..=kp.len()).find(|&n| leaf_cipher_suites(&kp[..n]).is_ok())
            .expect("the whole KeyPackage must parse");
        for cut in 0..shortest {
            assert!(leaf_cipher_suites(&kp[..cut]).is_err(), "a KeyPackage cut at {cut}B parsed");
        }
        assert_eq!(leaf_cipher_suites(&kp[..shortest]).unwrap(), vec![0x0002u16]);
        assert!(shortest < kp.len(),
            "the walk must not need the whole KeyPackage — it stops at cipher_suites");
    }

    /// The walk agrees with a KeyPackage minted by mls-rs, whose suites are those configured at
    /// `start`. Also pins the `kp_inspect` layout, with the MSISDN still last.
    #[test]
    fn kp_inspect_reports_the_peers_suites_alongside_its_own() {
        let dir = format!("{}/kpsuites_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let s = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &dir).unwrap();
        let kp = split_len_prefixed(&s.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();

        let mut advertised = leaf_cipher_suites(&kp).unwrap();
        advertised.sort_unstable();
        assert_eq!(advertised, vec![1u16, 2, 3, 7],
            "the leaf must advertise exactly the suites the crypto provider enables");
        assert!(advertised.contains(&u16::from(SUITE)),
            "RFC 9420 §7.2: our own leaf must advertise the suite our groups run");

        let info = s.kp_inspect(&kp).unwrap();
        assert_eq!(info[0], 0, "a pool KeyPackage is one-time, not last-resort");
        assert!(u64::from_be_bytes(info[1..9].try_into().unwrap()) > 0, "not_after must be set");
        assert_eq!(u16::from_be_bytes([info[9], info[10]]), u16::from(SUITE),
            "the KeyPackage's own cipher_suite field is the group suite");
        let n = info[11] as usize;
        let suites: Vec<u16> = (0..n)
            .map(|i| u16::from_be_bytes([info[12 + 2 * i], info[13 + 2 * i]])).collect();
        assert_eq!(suites, leaf_cipher_suites(&kp).unwrap(),
            "kp_inspect must carry the same list the walk produces, in wire order");
        // Bare E.164 digits, as `keyPackageUsable` compares; the 16 bytes skipped are the
        // certificate window.
        let msisdn = String::from_utf8(info[12 + 2 * n + 16..].to_vec()).unwrap();
        assert!(msisdn.len() > 5 && msisdn.bytes().all(|b| b.is_ascii_digit()),
            "the certified SAN identity must still decode from behind the new fields, got {msisdn:?}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    // ---- a Welcome's own GroupInfo extensions ----

    /// `group_ext` and `group_info_ext_types` read the GroupContext list (RFC 9420 §11), so an
    /// extension in the GroupInfo's own list, where RCC.16 v4.0 §7.11.12.1 puts the continuity
    /// token, is invisible to them. `ratchet_tree` (0x0002) stands in for the token.
    #[test]
    fn a_groupinfos_own_extensions_are_invisible_to_the_group_context_probes() {
        let dir = format!("{}/giown_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid, _) = a.create_group(1, &bkp, &[]).unwrap();

        // a GroupInfo with the tree in its own list
        let gi_bytes = a.group_info_with_continuity(&gid, &[], true).unwrap();
        let gi = MlsMessage::from_bytes(&gi_bytes).unwrap().into_group_info().unwrap();
        const RATCHET_TREE: u16 = 0x0002;
        assert!(gi.extensions().get(ExtensionType::from(RATCHET_TREE)).is_some(),
            "the GroupInfo's OWN list must carry the tree — otherwise this test proves nothing");
        assert!(gi.group_context().extensions().get(ExtensionType::from(RATCHET_TREE)).is_none(),
            "the GroupContext must NOT carry it: the two lists are different lists");

        // both read the GroupContext, so neither sees it
        let types = a.group_info_ext_types(&gi_bytes).unwrap();
        let listed: Vec<u16> = types.chunks_exact(4)
            .map(|c| u16::from_be_bytes([c[0], c[1]])).collect();
        assert!(!listed.contains(&RATCHET_TREE),
            "group_info_ext_types reads group_context — an entry in the GroupInfo's own list must \
             not appear there; if it now does, this instrument is no longer needed, but say so \
             deliberately rather than by deleting this assertion");
        assert!(a.group_ext(&gid, RATCHET_TREE).unwrap().is_empty(),
            "group_ext reads the joined group's GroupContext — same blindness");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The verdict line both ways: one branch when 0xF010 is present, the other when absent.
    #[test]
    fn the_welcome_verdict_names_the_branch_it_took() {
        let mut exts = CoreExtensionList::new();
        let bare = ProdSession::welcome_gi_ext_line("join", b"g1", 0, &exts);
        assert!(bare.contains(" (none)"), "empty list must print (none): {bare}");
        assert!(bare.contains("no 0xF010"), "{bare}");
        assert!(!bare.contains("IS PRESENT"), "{bare}");

        exts.set(Extension::new(ExtensionType::from(crate::rcc16::CONTINUITY_TOKEN_EXT),
            vec![0u8; 33]));
        exts.set(Extension::new(ExtensionType::from(0x0002u16), vec![0u8; 7]));
        let found = ProdSession::welcome_gi_ext_line("join_with_tree", b"g1", 3, &exts);
        assert!(found.contains(" 0xF010=33B"), "type and length, GROUP-EXT shape: {found}");
        assert!(found.contains(" 0x0002=7B"),
            "every extension is listed, not only the token: {found}");
        assert!(found.contains("0xF010 IS PRESENT"), "{found}");
        assert!(found.contains("committer_leaf=3"), "the committer is named: {found}");
        assert!(found.contains("join_with_tree"), "the join path is named: {found}");
    }

    /// The instrument runs on a real join: create, Welcome, join, then the emitted line. Our own
    /// Welcomes carry no GroupInfo extensions, so the line reads `(none)`.
    #[test]
    fn the_welcome_instrument_fires_on_a_real_join() {
        let dir = format!("{}/wgi_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        LAST_WELCOME_GI_EXT_LINE.with(|c| *c.borrow_mut() = None);

        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (_, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        let line = LAST_WELCOME_GI_EXT_LINE.with(|c| c.borrow().clone())
            .expect("join_with_tree must have emitted a WELCOME-GI-EXT line");
        assert!(line.starts_with("WELCOME-GI-EXT join_with_tree "), "{line}");
        assert!(line.contains(&gid_str(&gid_b)), "the line must name the group joined: {line}");
        assert!(line.contains("committer_leaf=0"), "leaf 0 built this Welcome: {line}");
        // our Welcomes carry no GroupInfo extensions, tree included
        assert!(line.contains(" (none)"), "our Welcome's GroupInfo has no own extensions: {line}");
        assert!(line.contains("no 0xF010"), "{line}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    // ---- the Welcome-borne continuity token reaches the host ----

    /// An add commit whose Welcome carries `token_ext_data` at 0xF010, the test playing a peer via
    /// `CommitBuilder::set_group_info_ext` (no production path emits one). Returns the bundle.
    fn add_member_with_welcome_ext(a: &ProdSession, gid: &[u8], peer_kp: &[u8],
            ty: u16, ext_data: Vec<u8>) -> Vec<u8> {
        let mut g = a.client.load_group(gid).unwrap();
        let wrapped = if peer_kp.starts_with(&MLS_KEYPACKAGE_MSG_PREFIX) {
            peer_kp.to_vec()
        } else {
            wrap_key_package(peer_kp)
        };
        let kp = MlsMessage::from_bytes(&wrapped).unwrap();
        let mut exts = CoreExtensionList::new();
        exts.set(Extension::new(ExtensionType::from(ty), ext_data));
        let commit = commit_builder(&mut g)
            .add_member(kp).unwrap()
            .set_group_info_ext(exts)
            .build().unwrap();
        commit_bundle(&mut g, commit).unwrap()
    }

    /// A token a peer puts in a Welcome, framed as peers send it (`0x20 || 32`), reaches the host
    /// decoded to the 32-byte value (RCC.16 §8.3.1.1).
    #[test]
    fn a_welcome_borne_continuity_token_survives_the_join_and_is_handed_to_the_host() {
        let dir = format!("{}/wct_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("pki2_leaf_pb.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pb_priv.bin"), &td("pki2_leaf_pb_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/b")).unwrap();
        let c = ProdSession::start_no_revocation(&td("pki2_leaf_pc.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pc_priv.bin"), &td("pki2_leaf_pc_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/c")).unwrap();

        // Control first: our own Welcome carries no token, so B's join yields nothing.
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();
        assert!(b.take_welcome_continuity_token(&gid_b).unwrap().is_empty(),
            "we mint no token, so a Welcome WE built must hand the joiner nothing");

        // A plays a peer that does continuity
        let token: Vec<u8> = (0u8..32).collect();
        let framed = crate::rcc16::ext_encode(crate::rcc16::CONTINUITY_TOKEN_EXT, &token).unwrap();
        assert_eq!(framed.len(), 33, "the wire shape is 0x20 ‖ 32");
        let ckp = split_len_prefixed(&c.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        LAST_WELCOME_GI_EXT_LINE.with(|x| *x.borrow_mut() = None);
        let bundle = split_len_prefixed(&add_member_with_welcome_ext(
            &a, &gid, &ckp, crate::rcc16::CONTINUITY_TOKEN_EXT, framed));
        let gid_c = c.join_with_tree(&bundle[0], &bundle[5]).unwrap();
        assert_eq!(gid_c, gid, "same group");

        // the instrument still reports it
        let line = LAST_WELCOME_GI_EXT_LINE.with(|x| x.borrow().clone()).unwrap();
        assert!(line.contains(" 0xF010=33B"), "the exact reading: {line}");

        // the value is reachable, decoded, once
        assert_eq!(c.take_welcome_continuity_token(&gid_c).unwrap(), token,
            "the joiner must be handed the DECODED 32-byte token, not the 33-byte framing");
        assert!(c.take_welcome_continuity_token(&gid_c).unwrap().is_empty(),
            "taking is a hand-off: a second call must not re-deliver, or a caller would read the \
             repeat as a re-mint");
        // the adder never joined, so captured nothing
        assert!(a.take_welcome_continuity_token(&gid).unwrap().is_empty(),
            "capture is on the join, not on the commit that built the Welcome");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A bare token decodes; an undecodable one is dropped and the Welcome still joins.
    #[test]
    fn a_bare_token_is_tolerated_and_an_unreadable_one_never_fails_the_join() {
        let dir = format!("{}/wctb_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let mk = |who: &str, n: &str| ProdSession::start_no_revocation(
            &td(&format!("pki2_leaf_p{n}.der")), &[td("pki2_ica.der")],
            &td(&format!("pki2_leaf_p{n}_priv.bin")), &td(&format!("pki2_leaf_p{n}_pub.bin")),
            &[td("pki2_root.der")], &format!("{dir}/{who}")).unwrap();
        let token: Vec<u8> = (0u8..32).map(|i| i ^ 0x5A).collect();

        // bare: the same 32 bytes come back
        {
            let (a, b, c) = (mk("a", "a"), mk("b", "b"), mk("c", "c"));
            let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
                .into_iter().next().unwrap();
            let (gid, art) = a.create_group(1, &bkp, &[]).unwrap();
            b.join_with_tree(&split_len_prefixed(&art)[0], &split_len_prefixed(&art)[5]).unwrap();
            let ckp = split_len_prefixed(&c.generate_key_packages(1).unwrap())
                .into_iter().next().unwrap();
            let bundle = split_len_prefixed(&add_member_with_welcome_ext(
                &a, &gid, &ckp, crate::rcc16::CONTINUITY_TOKEN_EXT, token.clone()));
            let gid_c = c.join_with_tree(&bundle[0], &bundle[5]).unwrap();
            assert_eq!(c.take_welcome_continuity_token(&gid_c).unwrap(), token,
                "a bare 32-byte 0xF010 decodes verbatim rather than being dropped");
        }
        let _ = std::fs::remove_dir_all(&dir);

        // unreadable: the join still works
        {
            let (a, b, c) = (mk("a2", "a"), mk("b2", "b"), mk("c2", "c"));
            let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
                .into_iter().next().unwrap();
            let (gid, art) = a.create_group(1, &bkp, &[]).unwrap();
            b.join_with_tree(&split_len_prefixed(&art)[0], &split_len_prefixed(&art)[5]).unwrap();
            let ckp = split_len_prefixed(&c.generate_key_packages(1).unwrap())
                .into_iter().next().unwrap();
            let mut junk = vec![0x0Au8];              // declares 10, carries 4
            junk.extend_from_slice(&[0xDE, 0xAD, 0xBE, 0xEF]);
            let bundle = split_len_prefixed(&add_member_with_welcome_ext(
                &a, &gid, &ckp, crate::rcc16::CONTINUITY_TOKEN_EXT, junk));
            let gid_c = c.join_with_tree(&bundle[0], &bundle[5])
                .expect("an undecodable 0xF010 must NOT fail the join");
            assert!(c.take_welcome_continuity_token(&gid_c).unwrap().is_empty(),
                "and it must leave us holding no token rather than a guess");
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The hand-off queue is bounded, so a host that never collects cannot grow it.
    #[test]
    fn the_welcome_token_handoff_queue_is_bounded() {
        let dir = format!("{}/wctq_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("pki2_leaf_pa.der"), &[td("pki2_ica.der")],
            &td("pki2_leaf_pa_priv.bin"), &td("pki2_leaf_pa_pub.bin"), &[td("pki2_root.der")],
            &format!("{dir}/a")).unwrap();
        {
            let mut q = a.welcome_continuity.lock().unwrap();
            for i in 0..(WELCOME_CONTINUITY_SLOTS + 4) {
                q.retain(|(g, _)| g != &vec![i as u8]);
                q.push((vec![i as u8], vec![0xAA; 32]));
                while q.len() > WELCOME_CONTINUITY_SLOTS { q.remove(0); }
            }
            assert_eq!(q.len(), WELCOME_CONTINUITY_SLOTS, "the queue is capped");
        }
        // FIFO: the oldest are gone, the newest collectable
        assert!(a.take_welcome_continuity_token(&[0u8]).unwrap().is_empty(), "oldest evicted");
        assert_eq!(a.take_welcome_continuity_token(
            &[(WELCOME_CONTINUITY_SLOTS + 3) as u8]).unwrap().len(), 32, "newest retained");
        let _ = std::fs::remove_dir_all(&dir);
    }

}


/// A phone number for a log line, or for an error that ends up in one: `***` and its last four
/// digits, as the app's `LogMask.number`. This library cannot tell a debug build, so it always
/// masks.
fn masked_msisdn(msisdn: &[u8]) -> String {
    if msisdn.is_empty() {
        return String::new();
    }
    let digits: Vec<u8> = msisdn.iter().copied().filter(u8::is_ascii_digit).collect();
    if digits.len() <= 4 {
        return "***".to_string();
    }
    format!("***{}", String::from_utf8_lossy(&digits[digits.len() - 4..]))
}

#[cfg(test)]
mod masked_msisdn_tests {
    use super::masked_msisdn;

    #[test]
    fn keeps_the_last_four_digits_only() {
        assert_eq!(masked_msisdn(b"+15551234567"), "***4567");
        assert_eq!(masked_msisdn(b"tel:+1-555-123-4567"), "***4567");
        assert_eq!(masked_msisdn(b"3538"), "***");
        assert_eq!(masked_msisdn(b""), "");
    }
}

/// A group id as other clients print it in `GroupId: "..."`: the stored bytes are ASCII (an RCS
/// group id or a UUID), so ASCII when printable, else hex.
fn gid_str(gid: &[u8]) -> String {
    if !gid.is_empty() && gid.iter().all(|b| (0x20..=0x7e).contains(b)) {
        return String::from_utf8_lossy(gid).into_owned();
    }
    gid.iter().map(|b| format!("{b:02x}")).collect()
}

/// The group's RCC.16 era (0xF001), read from the group already held so the two log lines around
/// one apply see the same state.
fn era_of(g: &Group<ProdConfig>) -> Option<u32> {
    Some(g.context().extensions()
        .get(ExtensionType::from(crate::rcc16::ERA_EXT))
        .and_then(|e| e.extension_data.get(0..4)
            .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]])))
        .unwrap_or(1))
}

// ---- RCC.16 certificate encoding (rcc16_build.rs) ----
// One call per encoder, so neither side needs framing or a DER reader. Private-key operations stay
// in Java: `rcs_mls_rcc16_tbs_der` returns the bytes to sign and `rcs_mls_rcc16_ext4_der` takes the
// signature. A null return means the encoder refused: a hard failure, never an empty value.

/// `Name` with one `CN=<cn>` RDN as UTF8String. Null on failure.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn rcs_mls_rcc16_subject_der(cn: *const u8, cn_len: usize) -> RcsBytes {
    let s = match core::str::from_utf8(unsafe { slice(cn, cn_len) }) {
        Ok(s) => s,
        Err(_) => return NULL_BYTES,
    };
    match crate::rcc16_build::name_cn_utf8(s) {
        Ok(v) => to_bytes(v),
        Err(e) => { alog!("rcc16_build: subject: {e}"); NULL_BYTES }
    }
}

/// `GeneralNames` with one `uniformResourceIdentifier`. Null on failure.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn rcs_mls_rcc16_san_der(uri: *const u8, uri_len: usize) -> RcsBytes {
    let s = match core::str::from_utf8(unsafe { slice(uri, uri_len) }) {
        Ok(s) => s,
        Err(_) => return NULL_BYTES,
    };
    match crate::rcc16_build::san_uri(s) {
        Ok(v) => to_bytes(v),
        Err(e) => { alog!("rcc16_build: san: {e}"); NULL_BYTES }
    }
}

/// The validity sequence `{ notBefore, notAfter }`. Null when the window is empty or inverted.
#[no_mangle]
pub extern "C" fn rcs_mls_rcc16_validity_der(not_before: u64, not_after: u64) -> RcsBytes {
    match crate::rcc16_build::validity(not_before, not_after) {
        Ok(v) => to_bytes(v),
        Err(e) => { alog!("rcc16_build: validity: {e}"); NULL_BYTES }
    }
}

/// `tbsParticipantInfo`, the bytes the caller signs with the participant key.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn rcs_mls_rcc16_tbs_der(
    subject: *const u8, subject_len: usize,
    vendor_id: u64,
    validity: *const u8, validity_len: usize,
    leaf_spki: *const u8, leaf_spki_len: usize,
    san: *const u8, san_len: usize,
) -> RcsBytes {
    let (s, v, k, a) = unsafe {
        (slice(subject, subject_len), slice(validity, validity_len),
         slice(leaf_spki, leaf_spki_len), slice(san, san_len))
    };
    if s.is_empty() || v.is_empty() || k.is_empty() || a.is_empty() {
        alog!("rcc16_build: tbs: an element is empty (subject={} validity={} spki={} san={})",
              s.len(), v.len(), k.len(), a.len());
        return NULL_BYTES;
    }
    to_bytes(crate::rcc16_build::tbs_participant_info(s, vendor_id, v, k, a))
}

/// The `.4 ParticipantInformation` value around a signature over `rcs_mls_rcc16_tbs_der`.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn rcs_mls_rcc16_ext4_der(
    vendor_id: u64,
    validity: *const u8, validity_len: usize,
    pop_sig: *const u8, pop_sig_len: usize,
    participant_spki: *const u8, participant_spki_len: usize,
) -> RcsBytes {
    let (v, g, k) = unsafe {
        (slice(validity, validity_len), slice(pop_sig, pop_sig_len),
         slice(participant_spki, participant_spki_len))
    };
    if v.is_empty() || g.is_empty() || k.is_empty() {
        alog!("rcc16_build: ext4: an element is empty (validity={} sig={} spki={})",
              v.len(), g.len(), k.len());
        return NULL_BYTES;
    }
    match crate::rcc16_build::ext4(vendor_id, v, g, k) {
        Ok(out) => to_bytes(out),
        Err(e) => { alog!("rcc16_build: ext4: {e}"); NULL_BYTES }
    }
}

// ---- RCC.16 self-test PKI (rcc16_mint.rs) ----
// Test scaffolding, not a CA: these return a TBS for the caller to sign with JCE, and
// `rcs_mls_rcc16_certificate` assembles the result.

/// A CA certificate's TBS; for a self-signed root pass `issuer == subject` and `aki == ski`.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref, clippy::too_many_arguments)]
pub extern "C" fn rcs_mls_rcc16_tbs_ca(
    issuer: *const u8, issuer_len: usize,
    subject: *const u8, subject_len: usize,
    spki: *const u8, spki_len: usize,
    serial: *const u8, serial_len: usize,
    not_before: u64, not_after: u64,
    ski: *const u8, ski_len: usize,
    aki: *const u8, aki_len: usize,
    vendor_id: u64,
) -> RcsBytes {
    let (i, s, k, n, sk, ak) = unsafe {
        (slice(issuer, issuer_len), slice(subject, subject_len), slice(spki, spki_len),
         slice(serial, serial_len), slice(ski, ski_len), slice(aki, aki_len))
    };
    match crate::rcc16_mint::tbs_ca(i, s, k, n, not_before, not_after, sk, ak, vendor_id) {
        Ok(v) => to_bytes(v),
        Err(e) => { alog!("rcc16_mint: tbs_ca: {e}"); NULL_BYTES }
    }
}

/// A client leaf's TBS. `san` and `ext4` are embedded verbatim, since the `.4` signature covers
/// them as encoded.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref, clippy::too_many_arguments)]
pub extern "C" fn rcs_mls_rcc16_tbs_leaf(
    issuer: *const u8, issuer_len: usize,
    subject: *const u8, subject_len: usize,
    spki: *const u8, spki_len: usize,
    serial: *const u8, serial_len: usize,
    not_before: u64, not_after: u64,
    ski: *const u8, ski_len: usize,
    aki: *const u8, aki_len: usize,
    san: *const u8, san_len: usize,
    ext4: *const u8, ext4_len: usize,
    vendor_id: u64,
) -> RcsBytes {
    let (i, s, k, n, sk, ak, sa, e4) = unsafe {
        (slice(issuer, issuer_len), slice(subject, subject_len), slice(spki, spki_len),
         slice(serial, serial_len), slice(ski, ski_len), slice(aki, aki_len),
         slice(san, san_len), slice(ext4, ext4_len))
    };
    match crate::rcc16_mint::tbs_leaf(i, s, k, n, not_before, not_after, sk, ak, sa, e4,
            vendor_id) {
        Ok(v) => to_bytes(v),
        Err(e) => { alog!("rcc16_mint: tbs_leaf: {e}"); NULL_BYTES }
    }
}

/// A certificate: `{ tbsCertificate, signatureAlgorithm, signature }`.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn rcs_mls_rcc16_certificate(
    tbs: *const u8, tbs_len: usize,
    sig: *const u8, sig_len: usize,
) -> RcsBytes {
    let (t, g) = unsafe { (slice(tbs, tbs_len), slice(sig, sig_len)) };
    match crate::rcc16_mint::certificate(t, g) {
        Ok(v) => to_bytes(v),
        Err(e) => { alog!("rcc16_mint: certificate: {e}"); NULL_BYTES }
    }
}

/// `Name` with `O=<org>, CN=<cn>` as PrintableString, the CA subject form; the client subject
/// (`rcs_mls_rcc16_subject_der`) is UTF8String.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn rcs_mls_rcc16_ca_name_der(
    org: *const u8, org_len: usize,
    cn: *const u8, cn_len: usize,
) -> RcsBytes {
    let (o, c) = unsafe { (slice(org, org_len), slice(cn, cn_len)) };
    let (o, c) = match (core::str::from_utf8(o), core::str::from_utf8(c)) {
        (Ok(a), Ok(b)) => (a, b),
        _ => return NULL_BYTES,
    };
    match crate::rcc16_build::name_o_cn_printable(o, c) {
        Ok(v) => to_bytes(v),
        Err(e) => { alog!("rcc16_build: ca name: {e}"); NULL_BYTES }
    }
}

/// Flips one bit of a `.4` PoP signature for a negative fixture. Test scaffolding.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn rcs_mls_rcc16_corrupt_pop(ext4: *const u8, ext4_len: usize) -> RcsBytes {
    match crate::rcc16_mint::corrupt_pop_signature(unsafe { slice(ext4, ext4_len) }) {
        Ok(v) => to_bytes(v),
        Err(e) => { alog!("rcc16_mint: corrupt_pop: {e}"); NULL_BYTES }
    }
}

// Decrypted message content must not reach logcat. A source scan: the property is what a log
// line may reference, which no runtime test can enumerate. The scan stops at this module, whose
// own literals name the forbidden forms.
#[cfg(test)]
mod no_plaintext_log_tests {
    const SRC: &str = include_str!("ffi.rs");

    /// Every `alog!(…)` before this module: (line, raw arguments, [`code_view`] of them), both
    /// with whitespace removed.
    fn alog_args() -> Vec<(usize, String, String)> {
        let prod = &SRC[..SRC.find("mod no_plaintext_log_tests").expect("this module")];
        let b = prod.as_bytes();
        let mut out = Vec::new();
        let mut from = 0;
        while let Some(off) = prod[from..].find("alog!(") {
            let start = from + off + "alog!(".len();
            let (mut i, mut depth, mut in_str) = (start, 1i32, false);
            while depth > 0 {
                let c = b[i];
                if in_str {
                    if c == b'\\' { i += 1; } else if c == b'"' { in_str = false; }
                } else if c == b'"' {
                    in_str = true;
                } else if c == b'(' {
                    depth += 1;
                } else if c == b')' {
                    depth -= 1;
                }
                i += 1;
            }
            let line = prod[..start].matches('\n').count() + 1;
            let raw = &prod[start..i - 1];
            let squash = |t: &str| -> String { t.chars().filter(|c| !c.is_whitespace()).collect() };
            out.push((line, squash(raw), squash(&code_view(raw))));
            from = i;
        }
        out
    }

    /// The macro arguments with each string literal replaced by the names it interpolates
    /// (`"a {pt} b {}"` becomes `pt`), so literal prose such as `plaintext={}B` is not a
    /// reference while an inline `{pt}` still is.
    fn code_view(args: &str) -> String {
        let mut out = String::new();
        let mut chars = args.chars().peekable();
        while let Some(c) = chars.next() {
            if c != '"' {
                out.push(c);
                continue;
            }
            let mut lit = String::new();
            while let Some(d) = chars.next() {
                if d == '\\' { if let Some(e) = chars.next() { lit.push(e); } continue; }
                if d == '"' { break; }
                lit.push(d);
            }
            let mut names = Vec::new();
            let mut rest = lit.as_str();
            while let Some(o) = rest.find('{') {
                let tail = &rest[o + 1..];
                let close = tail.find('}').unwrap_or(tail.len());
                let name: String = tail[..close].split(':').next().unwrap_or("").to_string();
                if !name.is_empty() && !name.starts_with('{') { names.push(name); }
                rest = &tail[close.min(tail.len())..];
            }
            out.push_str("\"\"");
            for n in names { out.push(','); out.push_str(&n); }
        }
        out
    }

    /// True when `name` appears as a whole identifier not followed by `.len()`.
    fn references_content(args: &str, name: &str) -> bool {
        let b = args.as_bytes();
        let ident = |c: u8| c.is_ascii_alphanumeric() || c == b'_';
        let mut from = 0;
        while let Some(off) = args[from..].find(name) {
            let at = from + off;
            let end = at + name.len();
            let whole = (at == 0 || (!ident(b[at - 1]) && b[at - 1] != b'.'))
                && (end == b.len() || !ident(b[end]));
            if whole && !args[end..].starts_with(".len()") {
                return true;
            }
            from = end;
        }
        false
    }

    #[test]
    fn no_plaintext_reaches_the_log() {
        let all = alog_args();
        // Zero hits must fail: the receive-side diagnostic has to be found, or the scan is
        // looking at nothing.
        assert!(all.iter().any(|(_, raw, _)| raw.contains("recv-diag:sender_leaf=")),
            "the recv-diag line was not found — update this guard rather than let it scan nothing");
        let mut leaks = Vec::new();
        for (line, raw, code) in &all {
            if raw.contains("plaintext_hex")
                || code.contains("pt.iter(")
                || code.contains(".data()")
                || ["pt", "plaintext", "plain"].iter().any(|n| references_content(code, n))
            {
                leaks.push(format!("ffi.rs:{line}: alog!({raw})"));
            }
        }
        assert!(leaks.is_empty(),
            "decrypted message content reaches logcat; log its length:\n{}",
            leaks.join("\n"));
    }

    /// The scan must be able to fail: a hex dump of the plaintext, through the same predicate.
    #[test]
    fn the_scan_catches_the_line_it_replaced() {
        let old: String = code_view("\"recv-diag: plaintext_hex={hex}\"")
            .chars().filter(|c| !c.is_whitespace()).collect();
        assert!(!old.contains("plaintext_hex"), "prose is not a reference");
        // Hence `plaintext_hex` is matched on the raw arguments, and identifiers on this view.
        assert!(references_content(&code_view("\"x={}\", pt"), "pt"));
        assert!(references_content(&code_view("\"x={pt:?}\""), "pt"));
        assert!(!references_content(&code_view("\"plaintext={}B\", pt.len()"), "pt"));
        assert!(!references_content(&code_view("\"plaintext={}B\", pt.len()"), "plaintext"));
    }
}

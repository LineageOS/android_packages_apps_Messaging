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

// M4: real handle-based C-ABI op surface (rcs_mls.h). Production session = X.509 credential (M3)
// + file storage (M2). The C JNI bridge (libmlsopenmlsbridge) + OpenMlsNative call these.
use core::time::Duration;
use std::os::raw::c_char;
use std::ffi::CStr;
use std::sync::atomic::{AtomicBool, Ordering};

/// Per-path behavior selector (default OFF = the lab/RCC.16 profile, so messaging2 is unchanged).
/// The PROVIDER (Google Tachyon path) flips this ON via `rcs_mls_set_tachyon_profile` before
/// `session_start` because Google's KDS and certs need the OPPOSITE of the lab's rules:
///   - **`.4` PoP strict**: NO LONGER ON THIS SWITCH. Both paths now reject a failed PoP; see
///     `rcs_mls_set_pop_lenient` and the comment at the validator construction.
///   - **KP LeafNode lifetime**: lab/openrcs-kds = clamp inside the cert window less 30min (R11, KP
///     must NOT outlive the cert); Google KDS = the KP must expire AFTER the cert (rejects a clamped
///     KP as "expires too early"), satisfied by a fixed 365d. Both anchor `not_before` at the cert's
///     `notBefore` like Google Messages — see `kp_lifetime_window`.
/// One shared `.a`, but each app runs in its own process, so this static is per-app.
static TACHYON_PROFILE: AtomicBool = AtomicBool::new(false);

#[no_mangle]
pub extern "C" fn rcs_mls_set_tachyon_profile(on: u8) {
    TACHYON_PROFILE.store(on != 0, Ordering::Relaxed);
}

#[inline]
fn tachyon_profile() -> bool { TACHYON_PROFILE.load(Ordering::Relaxed) }

/// Escape hatch for the .4 PoP enforcement flipped on 2026-08-07. OFF by
/// default: a failed PoP REJECTS the peer's certificate.
///
/// This exists because the flip changes a warning into a refused peer, and every certificate that
/// justified it was one we could measure. A vendor we have never seen is exactly the case the
/// evidence cannot cover, so the revert must not require a rebuild — by the time it is needed,
/// someone is looking at a conversation that will not establish.
#[no_mangle]
pub extern "C" fn rcs_mls_set_pop_lenient(on: u8) {
    POP_LENIENT.store(on != 0, Ordering::Relaxed);
}
static POP_LENIENT: AtomicBool = AtomicBool::new(false);

#[inline]
fn pop_lenient() -> bool { POP_LENIENT.load(Ordering::Relaxed) }

/// Announce the RCC.16 SPEC VERSION the transport speaks — see `rcc16::set_rcc16_version` for the
/// full reasoning. `30` = v3.0 (the default), `40` = v4.0. Returns the version now in effect, which
/// is how the caller learns an unrecognised value was REFUSED rather than silently applied.
///
/// Deliberately separate from `rcs_mls_set_tachyon_profile`: the profile says WHOSE KDS and cert
/// rules apply, the version says WHICH SPEC REVISION's wire shapes to emit. Today Tachyon is
/// (TACHYON, v3.0) and the lab is (LAB_RCC16, v3.0 → v4.0 on request), but those are two
/// independent axes and collapsing them into one would make a lab-on-v4.0 impossible to express.
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

// The KDS UploadKeyPackages key-package field carries a RAW RFC-9420 KeyPackage struct, NOT an
// MlsMessage-wrapped one (mls-rs's `to_bytes()` emits the wrapped form → KDS INVALID_ARGUMENT).
// So we upload the raw KeyPackage and re-wrap a claimed raw KeyPackage back into the MlsMessage
// wire form before add_member. The MlsMessage(KeyPackage) framing is version(mls10=1) ‖
// wire_format(KeyPackage=5) ‖ KeyPackage — 4 fixed bytes, no length prefix (RFC 9420 §6).
const MLS_KEYPACKAGE_MSG_PREFIX: [u8; 4] = [0x00, 0x01, 0x00, 0x05];
fn wrap_key_package(raw_kp: &[u8]) -> Vec<u8> {
    let mut w = Vec::with_capacity(4 + raw_kp.len());
    w.extend_from_slice(&MLS_KEYPACKAGE_MSG_PREFIX);
    w.extend_from_slice(raw_kp);
    w
}

// -------------------------------------------------------------------------------------------
// PEER KeyPackage — the advertised cipher suites
// -------------------------------------------------------------------------------------------
//
// WHY THIS IS A HAND PARSE. mls-rs makes `KeyPackage.leaf_node` `pub(crate)` and keeps `tree_kem`
// a private module, so a `KeyPackage` we have already decoded still cannot hand us its
// `LeafNode.capabilities`. `KeyPackage::cipher_suite()` (the package's OWN suite) is public;
// the leaf's ADVERTISED set is not reachable through any accessor. The alternative was a sixth
// patch to the vendored fork; a ~30-line read-only walk of bytes we have already validated is the
// smaller surface, and it cannot regress anything mls-rs does.
//
// (`treeless_welcome::walk_leaf` walks the same LeafNode prefix and SKIPS the five Capabilities
// vectors. It is a private fn in a module this change may not edit, so the varint reader is
// duplicated here rather than shared. If the two ever disagree, that one is the reference: it is
// exercised against real Apple and Google leaves on every treeless join.)
//
// RFC 9420 §10 / §7.2 layout, from the start of the KeyPackage struct:
//   KeyPackage  = version(u16) cipher_suite(u16) init_key<V> LeafNode extensions<V> signature<V>
//   LeafNode    = encryption_key<V> signature_key<V> Credential Capabilities …
//   Credential  = credential_type(u16) then ONE <V> field for basic(1) / x509(2)
//   Capabilities= versions<V> cipher_suites<V> extensions<V> proposals<V> credentials<V>
// so the walk stops at the SECOND Capabilities vector and never has to understand the rest.

/// Read an MLS QUIC-style varint at `o`; returns (value, next_offset).
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

/// Skip one `opaque<V>` / `vector<V>`; returns the offset just past it.
fn kp_skip_v(b: &[u8], o: usize) -> Result<usize, String> {
    let (n, o2) = kp_varint(b, o)?;
    let end = o2.checked_add(n).ok_or("kp <V>: overflow")?;
    if end > b.len() { return Err("kp <V>: overrun".into()); }
    Ok(end)
}

/// The peer's `LeafNode.capabilities.cipher_suites` — the RFC-9420 §17.1 code points a claimed
/// KeyPackage's leaf says it can run — in wire order, duplicates and unknown values preserved.
///
/// Accepts the raw KeyPackage or the `MlsMessage`-wrapped form, exactly like `kp_inspect`.
///
/// THE VALUES ARE REPORTED, NOT FILTERED. mls-rs strips GREASE code points before it validates a
/// leaf (`ungreased_capabilities`), and the `grease` feature is off in our build so nothing here is
/// stripped either — but the host is comparing an advertisement against ONE suite it can run, and a
/// list that silently dropped entries would make an absent suite indistinguishable from a filtered
/// one. GREASE values (0x?A?A) are legal to advertise and simply will not match suite 2.
fn leaf_cipher_suites(kp: &[u8]) -> Result<Vec<u16>, String> {
    let b = if kp.starts_with(&MLS_KEYPACKAGE_MSG_PREFIX) { &kp[4..] } else { kp };
    // KeyPackage: version(u16) ‖ cipher_suite(u16) ‖ init_key<V>
    if b.len() < 4 { return Err("kp: too short for version+cipher_suite".into()); }
    let mut o = kp_skip_v(b, 4)?;
    // LeafNode: encryption_key<V> ‖ signature_key<V>
    o = kp_skip_v(b, o)?;
    o = kp_skip_v(b, o)?;
    // Credential: credential_type(u16) ‖ one <V> (basic identity / x509 chain).
    // Anything else has a structure we do not know, and guessing would make the offsets below
    // read arbitrary bytes as suite code points — refuse instead.
    if o + 2 > b.len() { return Err("kp: eof at credential_type".into()); }
    let cred = ((b[o] as u16) << 8) | b[o + 1] as u16;
    if cred != 1 && cred != 2 {
        return Err(format!("kp: credential_type {cred} is neither basic(1) nor x509(2)"));
    }
    o = kp_skip_v(b, o + 2)?;
    // Capabilities: versions<V> first, then the one we want.
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
use mls_rs_identity_x509::{CertificateChain, DerCertificate, SubjectIdentityExtractor, X509IdentityProvider};
use mls_rs_crypto_rustcrypto::{x509::{X509Reader, X509Validator}, RustCryptoProvider};
use x509_cert::{Certificate, der::Decode};
use crate::rcc16_validate::Rcc16Validator;
use crate::storage::{FileGroupStateStorage, FileKeyPackageStorage};

/// The KeyPackage LeafNode `Lifetime{not_before, not_after}` window to mint, as
/// `(not_before_unix, duration_secs)` — `not_after = not_before + duration`.
///
/// <b>Anchor.</b> mls-rs anchors `not_before` at NOW when the caller passes no timestamp. BOTH
/// VENDORS anchor it at the CERTIFICATE's `notBefore`, exactly — Google then sets
/// `not_after = not_before + 365d` and Apple the RFC-9420 "no expiration" sentinel
/// (both observed on the wire). Anchoring at NOW is not merely a byte-level
/// difference: it makes every KP we mint invalid on any peer whose clock trails ours, and it is the
/// one field of the leaf a peer validates against its OWN clock (RFC 9420 §7.3 `within_lifetime`).
/// So we anchor at `cert.notBefore` like they do, which is always in the past and therefore immune
/// to skew in the direction that matters.
///
/// <b>Duration.</b> The two KDSes have OPPOSITE rules, so it stays per-path:
///   - Google/Tachyon: the KP must expire AFTER the cert ("Key package expires at .. expected after
///     ..") → a fixed 365d, which also reproduces their `not_after` byte-for-byte.
///   - lab/`openrcs-kds` (RCC.16 v3.0 §5.1 / R11): the KP must NOT outlive the cert → the cert's own
///     window less a 30-minute margin. Computed from the cert window (not from `now`) because the
///     anchor is now the cert's `notBefore`, so a `now`-relative span would overshoot `notAfter`.
///
/// Falls back to `(None, 365d)` — i.e. mls-rs's anchor-at-now — if the cert will not parse.
fn kp_lifetime_window(leaf: &[u8], tachyon: bool) -> (Option<u64>, u64) {
    const DAY: u64 = 24 * 3600;
    const MARGIN: u64 = 1800;
    let c = match Certificate::from_der(leaf) {
        Ok(c) => c,
        Err(_) => return (None, 365 * DAY),
    };
    let nb = c.tbs_certificate.validity.not_before.to_unix_duration().as_secs();
    let na = c.tbs_certificate.validity.not_after.to_unix_duration().as_secs();
    let (anchor, span) = if tachyon {
        (Some(nb), 365 * DAY)
    } else {
        // Lab: keep not_after inside the cert window. If the cert is degenerate, fall back to
        // mls-rs's anchor-at-now with 365d rather than mint a zero-length lifetime.
        let w = na.saturating_sub(nb);
        if w > MARGIN { (Some(nb), (w - MARGIN).min(365 * DAY)) } else { (None, 365 * DAY) }
    };
    // A KeyPackage minted below the RCC.16 A.4.1.2 floor is the silent failure this whole area is
    // about: it uploads fine, and peers that claim it simply cannot reach us. Say so out loud —
    // the cause is always an aged certificate, and nothing downstream can diagnose it.
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

// Surface the errors the C ABI otherwise swallows into NULL_BYTES to logcat. liblog is already
// linked by the C bridge (mls_openmls_bridge.c uses __android_log_print), so the symbol resolves
// on-device; on non-android targets (host `cargo test`) it is a no-op so nothing needs liblog.
#[cfg(target_os = "android")]
extern "C" {
    fn __android_log_write(prio: i32, tag: *const c_char, text: *const c_char) -> i32;
}
/// The ONE tag every MLS line carries — host, JNI bridge and engine alike.
///
/// It used to be five: `MessagingApp` (host), `MlsOpenMlsBridge` (JNI), `RcsMlsFfi` (here),
/// `OpenMlsEngine` and `OpenMlsNative`. A single MLS operation therefore scattered its lines across
/// five `logcat -s` filters, so reading one operation end to end meant knowing all five and
/// interleaving them by timestamp. Keep this in lockstep with `MlsLog.TAG` (Java) and `TAG` in
/// `jni/mls_openmls_bridge.c`.
#[cfg(target_os = "android")]
pub(crate) const LOG_TAG: &[u8] = b"RcsMls\0";

/// Emit one line, tagged with the Rust module and line it came from.
///
/// Prefer the `alog!` macro, which captures `module_path!()`/`line!()` for you; this is the sink it
/// calls. The module/line is the half of 14.3 that keeps a one-tag stream navigable: with five tags
/// the tag itself told you roughly where a line came from, so collapsing them without adding the
/// origin would trade one problem for another.
pub(crate) fn alog_at(module: &str, line: u32, msg: &str) {
    #[cfg(target_os = "android")]
    {
        // Strip the crate prefix — every line carries it, so it is pure width.
        let m = module.strip_prefix("rcs_mls_ffi::").unwrap_or(module);
        if let Ok(c) = std::ffi::CString::new(format!("[{m}:{line}] {msg}")) {
            // ANDROID_LOG_ERROR = 6
            unsafe { __android_log_write(6, LOG_TAG.as_ptr() as *const c_char, c.as_ptr()); }
        }
    }
    #[cfg(not(target_os = "android"))]
    let _ = (module, line, msg);
}

/// TEST-ONLY capture of the last `WELCOME-GI-EXT` line, so a host test can drive a REAL
/// create-group → Welcome → join and assert what the instrument actually emitted, rather than
/// asserting a formatter in isolation and hoping it is wired to the join. Compiled out entirely in
/// production, where the line goes only to logcat. Thread-local because `join_group` returns on the
/// calling thread — the rayon workers mls-rs validates on never touch this.
#[cfg(test)]
thread_local! {
    static LAST_WELCOME_GI_EXT_LINE: core::cell::RefCell<Option<String>> =
        const { core::cell::RefCell::new(None) };
}

/// TEST-ONLY record of what `external_commit_resync`'s stale-archive recovery did.
///
/// The recovery has three outcomes (not applicable / purged-and-retried / purged-retried-restored)
/// and two of them are indistinguishable from outside: a resync that succeeds looks the same
/// whether or not the retry fired, and a resync that fails looks the same whether or not the record
/// was put back. Asserting on this makes the arm that ran a fact rather than an inference.
///
/// Thread-local is correct HERE and would not be everywhere in this file: the recovery decision is
/// taken on the caller's thread. See `pinned_validation_time` for the case where a thread-local
/// override silently did nothing because mls-rs validates on a rayon pool.
#[cfg(test)]
thread_local! {
    static LAST_RESYNC_STALE_RECOVERY: core::cell::RefCell<Option<String>> =
        const { core::cell::RefCell::new(None) };
}

/// TEST-ONLY fault injection for the ONE arm of `external_commit_resync` a black-box test cannot
/// reach.
///
/// The arm is: the first build fails `InvalidEpoch`, we drop the stale prior-epoch archive, and the
/// SECOND build fails too, so the snapshot must be put back. Reaching it honestly needs two builds
/// over identical inputs to fail for two different reasons, and they cannot: the only difference
/// between them is the archive, and every other failure `ExternalCommitBuilder::build` can produce
/// (`MissingExternalPubExtension`, tree validation, credential lifetime, cipher suite) is decided
/// BEFORE `apply_pending_commit` and so would have sunk the first build instead.
///
/// Without the injection this arm would be defensive code asserted by nothing — which is the shape
/// this project has been bitten by often enough to have a rule about it. The injection is a real
/// fault: with the restore removed the test fails, because the archive stays dropped.
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

/// `alog!("…{}", x)` — module and line are captured at the call site.
macro_rules! alog {
    ($($arg:tt)*) => {
        $crate::ffi::alog_at(module_path!(), line!(), &format!($($arg)*))
    };
}
pub(crate) use alog;
// ---------------------------------------------------------------------------------------------
// TRI-STATE OP RESULT — "nothing to do" is not "it failed"
// ---------------------------------------------------------------------------------------------
//
// `logged` mapped every Err to NULL_BYTES, so the host could not tell a real failure from a group
// that does not exist from an op that correctly had nothing to do. The host's bounded drive loop
// cannot terminate on that: it must stop when the engine reports NO_OP and retry when it
// reports ERR, and both look identical today.
//
// The status rides a THREAD-LOCAL rather than a length-prefixed status byte on every return, which
// is a deliberate deviation from the plan's letter. A status byte would re-frame all ~40 ops and
// every Java call site that decodes them — a large, mechanical, mis-decodable change to a surface
// that is going to be restructured regardless. A JNI call runs on the calling Java thread, so a
// thread-local read immediately after the call is exact for that caller, and it costs nothing at the
// ~200 existing `format!`-into-String error sites.
//
// `From<String> for MlsError` is what keeps that promise: every existing `?` and `.map_err(|e|
// format!(...))` keeps compiling and defaults to ERR. Only the sites that mean something else get
// annotated.

/// What an op result MEANS, beyond "some bytes or null".
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum MlsStatus {
    Ok = 0,
    /// The op ran and correctly had nothing to do. A drive loop must STOP on this, not retry.
    NoOp = 1,
    /// The group is not in local storage. Distinct from ERR because the remedy differs: a missing
    /// group is a join/recover situation, not something to retry.
    NotFound = 2,
    /// A real failure. Retryable at the caller's discretion.
    Err = 3,
}

#[derive(Debug)]
pub struct MlsError {
    pub status: MlsStatus,
    pub msg: String,
}

impl From<String> for MlsError {
    /// The default for every unannotated error site: a real failure.
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
/// The op ran and there was nothing to do.
#[allow(dead_code)]
pub fn no_op(msg: impl Into<String>) -> MlsError {
    MlsError { status: MlsStatus::NoOp, msg: msg.into() }
}

thread_local! {
    /// Status of the last op on THIS thread. Read via `rcs_mls_last_status` immediately after a call.
    static LAST_STATUS: std::cell::Cell<u8> = const { std::cell::Cell::new(MlsStatus::Ok as u8) };
    /// `authenticated_data` of the last APPLICATION message processed on this thread.
    ///
    /// Surfaced the same way as the status, and for the same reason: the payload return is a status
    /// byte followed by raw plaintext, so appending the AAD would move the plaintext boundary for
    /// every caller. RFC 9420 AUTHENTICATES this data but assigns it no meaning — enforcing RCC.16
    /// §7.5.3.1 (the AAD's message_id must equal the transport's) is therefore the host's job, and
    /// it cannot do it without seeing the bytes. Google Messages enforces it: two comparison sites
    /// against `ProcessMessageRequest.request_context.message_id`, failing `MessageIdMismatch`.
    static LAST_AAD: std::cell::RefCell<Vec<u8>> = const { std::cell::RefCell::new(Vec::new()) };
    /// §7.5.3.1: the message_id the HOST says this request is for, i.e.
    /// `ProcessMessageRequest.request_context.message_id`. Empty = the host did not supply
    /// one, in which case the engine-side check is SKIPPED rather than failed — a caller
    /// that has not been migrated must not start losing messages.
    static EXPECTED_MESSAGE_ID: std::cell::RefCell<Vec<u8>> = const { std::cell::RefCell::new(Vec::new()) };
    /// A §10.3 RESENT-MESSAGE COMPONENT to place in the next AAD's trailing slot instead of
    /// the absent `0x00`. One-shot: consumed and cleared by the next `aad_for`.
    ///
    /// This exists so the host can still run the resent-component PROBE after removing
    /// the host-builds-the-whole-AAD seam. Supplying a COMPONENT is not the
    /// seam coming back: §10.3 makes the component the host's to choose, while the AAD
    /// around it stays the engine's to build.
    static NEXT_RESENT_COMPONENT: std::cell::RefCell<Vec<u8>> = const { std::cell::RefCell::new(Vec::new()) };
    /// Set when the last processed application message's AAD message_id did NOT equal the
    /// expected one. Read by `rcs_mls_last_message_id_mismatch`, valid only immediately
    /// after the call on the same thread, exactly like LAST_AAD.
    static LAST_ID_MISMATCH: std::cell::RefCell<Option<(Vec<u8>, Vec<u8>)>> = const { std::cell::RefCell::new(None) };
    /// The CERTIFIED MSISDN of the leaf that signed the last application message processed on this
    /// thread — the authenticated answer to "who sent this".
    ///
    /// # Why the transport's answer is not good enough
    ///
    /// mls-rs authenticates the sender WITHIN the group: it proves *some current member* produced
    /// the message. It does not tie that member to the identity the UI puts a name against, and the
    /// host was taking that identity from the TRANSPORT ENVELOPE — an unauthenticated field. Any
    /// member of a group could therefore send a message and have it attributed to any other member
    /// simply by addressing the envelope in their name.
    ///
    /// This closes it at the only place the answer exists: `sender_index` is authenticated by the
    /// decrypt, the roster maps it to a leaf, and the leaf's X.509 SAN carries the MSISDN the KDS
    /// certified. Empty when the sender's credential is not X.509 or carries no `tel:` SAN — the
    /// host must treat empty as UNKNOWN, never as a match.
    static LAST_SENDER_MSISDN: std::cell::RefCell<Vec<u8>> = const { std::cell::RefCell::new(Vec::new()) };
    /// Size in bytes of the LARGEST group-state record written on this thread since it was last
    /// read — the input to the `Bugle.Mls.ZinniaStateSize` log2 bucket.
    ///
    /// Largest rather than latest because one host operation can drive several writes (a commit
    /// that also trims epochs), and the metric is a bloat detector: the biggest blob the operation
    /// produced is the number that matters. Reading it clears it, so a host that records after
    /// every op cannot double-count a write from the previous one.
    static LAST_STATE_BYTES: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

/// Note a group-state record write. Called by the storage layer, read by the host.
pub(crate) fn note_state_write(bytes: usize) {
    LAST_STATE_BYTES.with(|c| c.set(c.get().max(bytes as u64)));
}

// ---------------------------------------------------------------------------------------------
// READ-ONLY SCOPE — enforce write-freedom rather than trusting it
// ---------------------------------------------------------------------------------------------
//
// The read-shaped verbs happen to be write-free today. That property was convention only: nothing
// in the build, the Rust types or the Java facade distinguished a read verb from a mutating one, so
// a regression that added a write to a read path would have been silent — which is EXACTLY how the
// known read-path bug arose in Google Messages, and it is not the kind of thing a device test
// surfaces.
//
// So a read verb now runs inside `read_only`, and the storage layer refuses to write while it is
// open. This is a partition of the surface enforced at the one place every write must pass through,
// rather than an annotation nobody re-checks.

thread_local! {
    /// Depth, not a flag, so nested read verbs unwind correctly.
    static READ_ONLY_DEPTH: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
}

/// True while a read-only scope is open on this thread. Consulted by the storage layer.
pub(crate) fn writes_forbidden() -> bool {
    READ_ONLY_DEPTH.with(|c| c.get() > 0)
}

struct ReadOnlyGuard;
impl Drop for ReadOnlyGuard {
    fn drop(&mut self) {
        READ_ONLY_DEPTH.with(|c| c.set(c.get().saturating_sub(1)));
    }
}

/// Run `f` with writes to the engine's own storage refused.
///
/// The guard is a Drop type so the scope closes even if `f` panics across the FFI boundary — a
/// scope left open would turn every subsequent write on this thread into a refusal, which is a far
/// worse failure than the one it is guarding against.
pub(crate) fn read_only<T>(f: impl FnOnce() -> T) -> T {
    READ_ONLY_DEPTH.with(|c| c.set(c.get() + 1));
    let _guard = ReadOnlyGuard;
    f()
}

/// The largest group-state record written on the CALLING thread since the last read, in bytes.
///
/// Zero if none. **Reading clears it** — see `LAST_STATE_BYTES`. The host buckets this with
/// `MlsMetrics.log2Bucket` and records it against `Bugle.Mls.ZinniaStateSize`.
#[no_mangle]
pub extern "C" fn rcs_mls_take_state_bytes() -> u64 {
    LAST_STATE_BYTES.with(|c| { let v = c.get(); c.set(0); v })
}

/// The `authenticated_data` of the last application message processed on the CALLING thread.
///
/// Empty when the last message carried none, was not an application message, or failed. Like the
/// status, it is valid only immediately after the call, on the same thread.
#[no_mangle]
pub extern "C" fn rcs_mls_last_aad() -> RcsBytes {
    LAST_AAD.with(|c| to_bytes(c.borrow().clone()))
}

/// §7.5.3.1 — tell the engine which message_id this request is for, before calling
/// `process`/`process_ex`.
///
/// This is the ONLY thing the host should be supplying about the AAD. RCC.16 puts AAD
/// construction and validation in the engine; the request context carries a message_id and
/// nothing else. Passing a null/empty id CLEARS it, which SKIPS the check — deliberately,
/// so a call site that has not been migrated keeps working rather than silently dropping
/// every message.
///
/// Thread-local and consumed by the next process call, matching LAST_AAD's contract.
#[no_mangle]
// Null- and length-checked below before any deref; the C ABI is unchanged by the lint.
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn rcs_mls_set_request_message_id(ptr: *const u8, len: usize) -> i32 {
    let v = if ptr.is_null() || len == 0 {
        Vec::new()
    } else {
        unsafe { std::slice::from_raw_parts(ptr, len) }.to_vec()
    };
    EXPECTED_MESSAGE_ID.with(|c| *c.borrow_mut() = v);
    0
}

/// Non-zero when the last application message processed on this thread carried an AAD
/// message_id that did NOT match the one given to `rcs_mls_set_request_message_id`.
///
/// This is RCC.16's `MessageIdMismatch` (status 9) computed WHERE THE SPEC PUTS IT. The
/// host has enforced the same rule since before the engine could
/// (`MlsProviderTransport.aadMessageIdMatches`); the two are deliberately kept in
/// agreement rather than a third check being added, and this one is authoritative.
/// Place a §10.3 resent-message component in the NEXT AAD built on this thread, instead of
/// the absent `0x00`. Null/empty clears it. One-shot — consumed by the next build.
///
/// For the resent-component probe: the peer's rejection distinguishes the failure
/// modes for us — "does not contain a resent message" means the TAG is wrong, a PARSE error
/// means the tag was right and the PREFIX WIDTH is wrong.
#[no_mangle]
// Null- and length-checked below before any deref; the C ABI is unchanged by the lint.
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

#[no_mangle]
pub extern "C" fn rcs_mls_last_message_id_mismatch() -> i32 {
    LAST_ID_MISMATCH.with(|c| if c.borrow().is_some() { 1 } else { 0 })
}

/// The `(expected, actual)` pair behind a mismatch, as `expected\\0actual`, for logging.
/// Empty when there was no mismatch.
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

/// Compare a freshly-decrypted application message's AAD against the host-supplied
/// request-context message_id and record the verdict. Skips silently when the host
/// supplied nothing, or when the AAD does not parse (a malformed AAD is the FTD path's
/// problem, not an id-mismatch).
fn note_message_id_check(aad: &[u8]) {
    let expected = EXPECTED_MESSAGE_ID.with(|c| c.borrow().clone());
    if expected.is_empty() {
        return;
    }
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

/// The certified MSISDN of the leaf that signed the last application message processed on the
/// CALLING thread. Empty means UNKNOWN — never treat it as a match.
#[no_mangle]
pub extern "C" fn rcs_mls_last_sender_msisdn() -> RcsBytes {
    LAST_SENDER_MSISDN.with(|c| to_bytes(c.borrow().clone()))
}

fn set_status(s: MlsStatus) {
    LAST_STATUS.with(|c| c.set(s as u8));
}

/// The status of the most recent op on the CALLING thread.
///
/// Must be read immediately after the op, on the same thread — which is exactly what a JNI caller
/// does. Returns `Ok(0)` if no op has run on this thread.
#[no_mangle]
pub extern "C" fn rcs_mls_last_status() -> u8 {
    LAST_STATUS.with(|c| c.get())
}

// ---------------------------------------------------------------------------------------------
// THE TRANSACTION FRAME — and the D1(c) rollback compensator
// ---------------------------------------------------------------------------------------------
//
// One engine entry point is ONE atomic storage unit. That is not aspiration: the storage rewrite
// made a group's state and its epoch records one file committed with one rename, so a mutating op
// either lands whole or not at all. What was missing was the OTHER half — what happens to the
// engine's IN-MEMORY caches when an op fails after touching them.
//
// D1, decided: the HYBRID. Google Messages registers `invalidate_all_caches` as an ON-ROLLBACK
// hook, and a reimplementation that invalidates unconditionally throws away the caches it
// deliberately keeps. We invalidate the send cache UNCONDITIONALLY at the top of 15 mutating
// ops — the named anti-pattern — and that eager invalidation IS the fix that made gen-2 decrypt
// on Google Messages. It stays, exactly as it is. What is added is the missing
// compensator: on FAILURE, invalidate again, because a half-applied op can leave a cached group
// whose in-memory epoch no longer matches what is on disk, and the next encrypt would then ratchet
// from a state that was never committed.
//
// Both together is not belt-and-braces. The eager invalidation protects the SUCCESS path (the
// cached group is stale the moment a commit lands); the compensator protects the FAILURE path (the
// cached group is stale because the write did not land). They cover different states.

/// `logged`, plus the rollback compensator, for an op that may mutate group state.
///
/// The compensator runs ONLY on Err — that is what makes it a compensator rather than a blanket
/// invalidation on every call, and it is why the eager invalidation at the top
/// of each op is left exactly as it is.
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

/// Run an FFI op; record its status for the caller, and on Err log the reason (tag RcsMls).
fn logged<E: Into<MlsError>>(op: &str, r: Result<Vec<u8>, E>) -> RcsBytes {
    match r {
        Ok(v) => { set_status(MlsStatus::Ok); to_bytes(v) }
        Err(e) => {
            let e: MlsError = e.into();
            set_status(e.status);
            // NO_OP is not a failure and must not read as one in logcat — a log full of spurious
            // ERR lines is how a real one gets missed.
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
const ERA_EXT: u16 = 0xF001;
type Extractor = SubjectIdentityExtractor<X509Reader>;

/// Delegating wrapper that advertises BOTH `basic(1)` and `x509(2)` in
/// `LeafNode.capabilities.credentials`, matching GOOGLE MESSAGES (RFC 9420 §7.2). mls-rs derives that
/// list from `IdentityProvider::supported_types()`; a bare [`X509IdentityProvider`] returns only
/// `[x509]=[2]`, but Google Messages advertises `[basic, x509]=[1,2]` as observed on the wire,
/// so a peer presenting a Basic credential is not seen as unsupported during capability negotiation.
/// ADVERTISE-ONLY: actual credential validation delegates wholesale to the inner X.509 provider (our
/// certs are always X.509, so the validation path is byte-identical) — this only realigns our
/// capabilities bytes with theirs.
#[derive(Clone, Debug)]
struct X509WithBasicCreds<P>(P);

/// TEST-ONLY CLOCK PIN — and the reason it lives *here*.
///
/// `testdata/README.md` requires the lab2 fixtures to be evaluated at the pinned `LAB2_NOW`,
/// "never the wall clock: a 76-day-capped profile means every fixture expires, and a suite that
/// reads the clock goes red on a date nobody chose". The `rcc16_validate` tests honour that by
/// passing an explicit `MlsTime`. The **ffi** tests could not: they drive whole engine operations
/// (`create_group` / `add_members` / `commit`) that reach the validator many frames down inside
/// mls-rs, with nowhere to thread a timestamp through. So on 2026-09-08 they went red — 103/27 —
/// with no code change, exactly as the README predicted, on `A.4.1: only 29 d remaining < 30 d`.
///
/// AN EARLIER ATTEMPT PUT THE PIN IN `Rcc16Validator` AND HAD TO BE REVERTED. Two reasons, both
/// still true, which is why it is not there now: mls-rs's `X509IdentityProvider` passes an
/// EXPLICIT wall-clock timestamp, so a guard of the form "only substitute when the caller passed
/// `None`" never fires; and making it unconditional would corrupt the `rcc16_validate` tests that
/// deliberately assert at chosen instants (expired / not-yet-valid / inside window), since both
/// families share that type.
///
/// THIS SEAM HAS NEITHER PROBLEM. `X509WithBasicCreds` is our own wrapper, it is the outermost
/// identity provider on `ProdConfig` and therefore on the path of every engine-driven validation,
/// and **no `rcc16_validate` test constructs one** — those call the validator directly. So the
/// substitution is unconditional and total for exactly the tests that need it, and invisible to
/// the tests that must keep choosing their own instant. It is also a fixed `const`, not a mutable
/// static, so it carries none of the cross-test pollution hazard that reverted attempt did.
///
/// Verified safe by inspection of the fixtures in use: every `ProdSession` in the ffi tests is
/// built from the `lab2_*` chain and nothing else, so `LAB2_NOW` is the correct instant for all
/// of them. A future ffi test using a different chain (a live root, or a leaf from a different chain) MUST NOT
/// be added without revisiting this — it would silently be evaluated in August 2026.
///
/// `#[cfg(test)]` means the Android/JNI build never compiles it; production always reads the real
/// clock. Re-minting the fixtures means re-pinning `LAB2_NOW`, which is now one constant.
#[cfg(test)]
#[inline]
fn pinned_validation_time(_ts: Option<MlsTime>) -> Option<MlsTime> {
    // A PLAIN CONST, AND IT MUST STAY ONE. An earlier version added a thread-local override so a
    // test could move the instant between engine calls; it silently did nothing, because mls-rs
    // validates on a RAYON WORKER POOL (`wrap_iter` = `into_par_iter()`, and rayon is in our
    // Cargo.lock transitively). The override lived on the test thread and the validation ran
    // elsewhere, so those calls fell back to this const and the "expired" experiment quietly
    // measured a perfectly valid certificate. That produced a confidently wrong finding — see
    // `the_engine_validates_the_credential_of_a_member_being_added`.
    //
    // This const is safe precisely BECAUSE it is unconditional: every thread reads the same value.
    // Any future per-test variation of the instant must be process-global, not thread-local.
    Some(MlsTime::from(crate::rcc16_validate::LAB2_NOW))
}
#[cfg(not(test))]
#[inline]
fn pinned_validation_time(ts: Option<MlsTime>) -> Option<MlsTime> { ts }
impl<P: IdentityProvider> IdentityProvider for X509WithBasicCreds<P> {
    type Error = P::Error;


    // Sync signatures — this crate is only ever built in mls-rs's default (sync) mode for the C-ABI
    // FFI (`not(mls_build_async)`), where `must_be_sync` strips the trait's `async fn` to `fn`.
    fn validate_member(&self, signing_identity: &SigningIdentity, timestamp: Option<MlsTime>,
                       context: MemberValidationContext<'_>) -> Result<(), Self::Error> {
        self.0.validate_member(signing_identity, pinned_validation_time(timestamp), context)
    }
    fn validate_external_sender(&self, signing_identity: &SigningIdentity, timestamp: Option<MlsTime>,
                               extensions: Option<&CoreExtensionList>) -> Result<(), Self::Error> {
        self.0.validate_external_sender(signing_identity, pinned_validation_time(timestamp), extensions)
    }
    fn identity(&self, signing_identity: &SigningIdentity, extensions: &CoreExtensionList)
        -> Result<Vec<u8>, Self::Error> {
        self.0.identity(signing_identity, extensions)
    }
    fn valid_successor(&self, predecessor: &SigningIdentity, successor: &SigningIdentity,
                       extensions: &CoreExtensionList) -> Result<bool, Self::Error> {
        self.0.valid_successor(predecessor, successor, extensions)
    }
    /// RFC 9420 §17.5 credential-type codepoints: `basic=1`, `x509=2`. Order matches the wire bytes.
    fn supported_types(&self) -> Vec<CredentialType> {
        vec![CredentialType::BASIC, CredentialType::X509]
    }
}

// allow_external_commit=true is what makes commit.external_commit_group_info non-empty.
// It adds a WithMlsRules wrapper as the OUTERMOST config layer (last builder method wins the type).
type ProdConfig = WithKeyPackageRepo<
    FileKeyPackageStorage,
    WithMlsRules<
        crate::rcc16::Rcc16MlsRules,
        WithGroupStateStorage<
            FileGroupStateStorage,
            WithIdentityProvider<X509WithBasicCreds<X509IdentityProvider<Extractor, Rcc16Validator>>,
                WithCryptoProvider<RustCryptoProvider, BaseConfig>>>>>;

/// The three-valued era-advance MODE — §9.7g. Mirrors Java's `MlsAdvanceEraKind`.
///
/// Google Messages' `advance_era` reads a mode byte and branches three ways: `1` removes `end_mls`,
/// `2` installs it, `0` (or anything else) touches neither. That makes clearing a distinct OPERATION
/// rather than a guarded branch of the ordinary advance, which is strictly stronger than a guard:
/// there is no code path in which a plain advance *could* clear `end_mls` and be prevented.
///
/// This is one half of the end-MLS removal rule's enforcement. The other half is that the whole crate has
/// exactly TWO sites that may drop `0xF002` — `create_group_carry`'s revival arm and
/// `commit_end_mls`'s [`EndMlsOp::RemoveForRevival`] — and both take the intent from the caller
/// rather than deriving it from group state.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AdvanceEraKind {
    /// Mode 0 — a plain advance. `end_mls` is carried forward exactly as it is.
    Normal,
    /// Mode 1 — REVIVAL: era+1 and DROP `end_mls`. A deliberate removal site.
    Revival,
    /// Mode 2 — PHOENIX: era+1 and INSTALL `end_mls`. The new era is born downgraded.
    PhoenixDowngrade,
}

impl AdvanceEraKind {
    /// Google Messages' decode: `>2` falls through to "touch neither", so an unknown mode is `Normal`.
    /// Carrying the extension forward is always the safe answer to "I do not know what you meant".
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

/// What `commit_end_mls` is being asked to do — §9.7m.
///
/// This replaced a bare `remove: bool`. The difference is not stylistic: the removal rule requires that every
/// removal site "assert a revival intent", and a boolean parameter named `remove` asserts nothing —
/// it is one transposed argument away from a recovery path silently reviving a group that a peer
/// deliberately downgraded. A two-variant enum makes the intent unspellable by accident.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EndMlsOp {
    /// Add `end_mls` to the GroupContext — leave MLS.
    Install,
    /// Remove `end_mls` — <b>revival, and nothing else</b>. One of exactly two removal sites.
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
    /// SENDER-RATCHET CACHE (the KEY_GEN root fix): reloading the group for every encrypt
    /// re-derives the SecretTree to gen-0 while the generation COUNTER persists separately, so gen-N
    /// messages get gen-0's key → the peer's "Key generation mismatch". Keep the group in memory across
    /// consecutive encrypts (same epoch) so the application ratchet advances correctly. Invalidated
    /// (set None) by every group-MODIFYING op (process/commit/rekey/join/delete/restore) so the next
    /// encrypt reloads the fresh epoch. `(group_id, Group)`.
    send_cache: std::sync::Mutex<Option<(Vec<u8>, Group<ProdConfig>)>>,
    /// The `not_before` to stamp on every KeyPackage LeafNode we mint — the client cert's own
    /// `notBefore`, matching Google Messages (see `kp_lifetime_window`). `None` = let mls-rs
    /// anchor at NOW, used only when the cert will not parse.
    kp_not_before: Option<MlsTime>,
    /// OUR CURRENT credential and its signing key — the pair the client was started with.
    ///
    /// <b>Kept because a group's leaf does not track it.</b> `Client::builder().signing_identity()`
    /// is consulted when a group is CREATED or JOINED and never again: every later commit reuses
    /// whatever `SigningIdentity` is already sitting in our leaf. So a device that re-mints its KDS
    /// certificate and reopens the session goes on committing under the OLD certificate for the
    /// life of the group. `self_update` compares these against the group's leaf and
    /// carries them in when they differ, which is RCC.16 v4.0 §9.5.3's "empty Commit with
    /// UpdatePath containing the new leaf".
    signing_identity: SigningIdentity,
    signer: SignatureSecretKey,
    /// §7.11.12.1 continuity tokens read out of a Welcome's DECRYPTED GroupInfo at join, as
    /// `(group_id, token)`, waiting for the host to make them durable.
    ///
    /// <b>A hand-off, not a store.</b> The token must survive a restart and an Era advance, and
    /// nothing here does either — the durable home is the host's `MlsConversationRecord` (§4.8
    /// row 13), keyed `(identity, group_id)`, which is the only key that outlives the MLS group
    /// itself. This exists because a Welcome's extensions are in the clear for exactly one instant,
    /// inside `join_group`, and the host cannot reach into that instant: `group_ext` reads the
    /// GroupContext and `group_info_ext` needs a serialized GroupInfo, so both are structurally
    /// blind to 0xF010 no matter what they are pointed at (the WELCOME-EXT headstone in
    /// `MlsProviderTransport` is what that mistake cost).
    ///
    /// FIFO-bounded at [`WELCOME_CONTINUITY_SLOTS`] and drained by
    /// [`ProdSession::take_welcome_continuity_token`], so a host that never collects cannot make it
    /// grow. Taking rather than peeking is deliberate: it is a group secret, and it should live in
    /// this process for as long as it takes the caller to persist it and no longer.
    welcome_continuity: std::sync::Mutex<Vec<(Vec<u8>, Vec<u8>)>>,
}

/// How many un-collected Welcome tokens to hold. Joins are rare and the host collects on the same
/// thread that joined, so one would nearly always do; the slack is there so a second join racing
/// ahead of a slow collector evicts nothing that is still wanted.
const WELCOME_CONTINUITY_SLOTS: usize = 8;

/// A lowercase UUID-v4 STRING (36 ASCII bytes, e.g. "c1e9bb86-04fb-48f7-a869-6ce76b953212") for the
/// MLS group_id. The server rejects a raw-bytes group_id ("Invalid group ID"); Google Messages uses
/// a UUID string. Uses getrandom (OS CSPRNG); zeros on the vanishingly-rare RNG error.
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

/// Decode a concatenation of [u32-be len][DER] records into a Vec of DER blobs.
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
    /// `revoked_serials` is the host-pushed `RevokedCertificates { repeated string serial_numbers }`
    /// list — `CreateClientRequest` field 7, and **the only revocation input in the whole ABI**
    /// Google Messages always sends the message PRESENT and EMPTY.
    ///
    /// Present-and-empty is not the same as absent, which is why this is a parameter rather than a
    /// default: RCC.16 leaf certificates are deliberately non-revocable, so the intermediate-CA
    /// serial list is the only revocation lever that exists. With no way to supply one, a
    /// compromised ICA keeps every certificate it ever minted — for every MSISDN — valid until
    /// expiry, and there is nothing anyone can do about it from the client.
    ///
    /// **Do NOT add a CRL or OCSP fetcher.** The engine links no network stack at all, so it could
    /// not use one; Google Messages parses CRL-DP and AIA as *shape* checks and never acts on them.
    /// The negative is part of the specification.
    /// `extra_ext_types` / `extra_prop_types` are the TRANSPORT-LAYER ADVERTISEMENT OVERRIDE.
    ///
    /// The engine advertises what it implements plus the documented advertised-only set (see
    /// `rcc16::advertised_extensions`). A transport may need MORE — Tachyon registration can require
    /// a code point this engine knows nothing about — and that is a transport concern rather than a
    /// spec one, so it arrives here as data instead of being hard-coded into the spec layer.
    /// Duplicates are ignored, so a transport may safely re-assert something already advertised.
    pub fn start_with_advertisement(leaf: &[u8], chain: &[Vec<u8>], priv32: &[u8], pub65: &[u8],
                 roots: &[Vec<u8>], revoked_serials: &[Vec<u8>],
                 extra_ext_types: &[u16], extra_prop_types: &[u16],
                 storage_dir: &str) -> Result<Self, String> {
        let mut certs = vec![DerCertificate::from(leaf.to_vec())];
        for c in chain { certs.push(DerCertificate::from(c.clone())); }
        let credential = CertificateChain::from(certs).into_credential();
        let signing_identity = SigningIdentity::new(credential, SignaturePublicKey::from(pub65.to_vec()));
        let secret = SignatureSecretKey::from(priv32.to_vec());
        let extractor = SubjectIdentityExtractor::new(0, X509Reader::new());
        let root_list: Vec<DerCertificate> = roots.iter().map(|r| DerCertificate::from(r.clone())).collect();
        let x509 = X509Validator::new(root_list).map_err(|e| format!("validator: {e:?}"))?;
        // TWO INDEPENDENT AXES, deliberately not one flag (2026-08-07).
        //
        // `strict` = the A.4.1 DEPLOYMENT rules: the lifetime floors (<=76 d, >=30 d remaining) and
        // the subject/policy profile. Tachyon must stay RELAXED here — the KDS mints 75.5-day leaves
        // and the >=30-day floor would refuse every Google Messages peer from day ~46 of its own cert.
        // That is an interop dial and it has not changed.
        //
        // `pop_strict` = whether a FAILED .4 ParticipantInformation PoP REJECTS. Now ON for BOTH
        // paths. It used to ride `strict`, so Tachyon got leniency it never needed: the stated
        // reason was that the tbsParticipantInfo layout was unpinned and a strict verify might
        // reject a good certificate. The layout is now pinned and the defect was OURS — element 4 is
        // the CERTIFIED SUBJECT key, not the participant key.
        //
        // Measured before flipping, because this turns a warning into a rejected peer:
        //   Google KDS, live-claimed on-device, 3 lines x 2 Google Messages versions
        //     -> 6/6 "PoP VERIFIED", zero tolerations
        //   Apple, from a captured real inbound (vendorId=1, two distinct keys) -> VERIFIED
        //   ours + lab -> VERIFIED
        // There is no longer a known certificate the lenient branch would tolerate.
        //
        // ESCAPE HATCH: rcs_mls_set_pop_lenient(1) restores the old behaviour without a rebuild.
        // Keep it. Enforcement here rejects a PEER, and the failure mode of being wrong is a
        // conversation that cannot be established — recoverable in one setprop, but only if the
        // lever exists before it is needed.
        let mut rcc16 = Rcc16Validator::new(x509);
        rcc16.strict = !tachyon_profile();
        rcc16.pop_strict = !pop_lenient();
        // RCC.16 A.4.1 SAN identity equality: a leaf's SAN tel: URI must EQUAL the QUERIED MSISDN —
        // a certificate for the WRONG number must fail, not merely be a well-formed tel: URI.
        //
        // ⚠ This stays None DELIBERATELY, and threading a per-session MSISDN in here would be a bug.
        // An earlier TODO asked for exactly that ("add an expected_msisdn arg to
        // rcs_mls_session_start"), but this validator is the IDENTITY PROVIDER's — `validate_chain`
        // runs on EVERY credential the client sees, ours and every peer's alike. Pinning it to one
        // number would require every peer's certificate to be issued for OUR msisdn, so the first
        // member we added would be refused and no group could ever form.
        //
        // "The queried MSISDN" is inherently per-lookup: it is the number we asked the KDS for when
        // we claimed THAT peer's KeyPackage, and it exists only at the claim site. So the equality
        // check lives there — `kp_inspect` returns the leaf's certified SAN identity and the host
        // compares it against the number it queried, in MlsProviderTransport.keyPackageUsable.
        rcc16.expected_msisdn = None;
        // 0.9: the host-pushed revocation list. Empty is the normal, expected state — the slot
        // existing is what matters. Routed through the builder so serials are normalised (leading
        // zeroes stripped) and a serial is matched by its NUMBER rather than by one encoding of it.
        let rcc16 = rcc16.with_revoked_serials(revoked_serials.to_vec());
        // Wrap the X.509 provider so LeafNode.capabilities.credentials advertises [basic(1), x509(2)]
        // like Google Messages (RFC 9420 §7.2), not the bare provider's [x509] — see X509WithBasicCreds.
        let idp = X509WithBasicCreds(X509IdentityProvider::new(extractor, rcc16));
        // Match GOOGLE MESSAGES' KeyPackage capabilities exactly. A byte-diff of our device KP
        // against a passing one: byte-IDENTICAL except our capabilities are minimal while theirs
        // are RICH — the only structural diff, and the suspected cause of the KDS "Unsupported enum
        // discriminant" (a capabilities deserialization misalignment that the rich lists realign).
        // Observed on the wire:
        //   cipher_suites = [1,3,2,7]  extensions = [0xF001..0xF007, 0xE000, 0xF010..0xF030]
        //   proposals = [0xF001,0xF003,0xF004, 0xF010..0xF018]   credentials = [1,2]
        // credentials = [basic(1), x509(2)] now MATCHES theirs — X509WithBasicCreds overrides
        // supported_types() to add basic (advertise-only; validation stays X.509). RustCrypto supports
        // exactly {1,2,3,7}; the group still runs suite 2. These custom extension/proposal/credential
        // types are advertised only — no group uses them, so add_member imposes no capability requirement.
        let crypto = RustCryptoProvider::with_enabled_cipher_suites(vec![
            CipherSuite::CURVE25519_AES128,  // 1
            CipherSuite::CURVE25519_CHACHA,  // 3
            CipherSuite::P256_AES128,        // 2 (the group suite)
            CipherSuite::P384_AES256,        // 7
        ]);
        // ONE canonical list, shared with the conformance fixture — see rcc16.rs. This used to be
        // two hand-written ranges here and a DIFFERENT hand-written range in the fixture, which is
        // how the smoke test came to advertise a set we do not ship. `extra` is the transport
        // override: Tachyon registration may need a code point the engine knows nothing about, and
        // that is a transport concern rather than a spec one.
        //
        // One real fix rides along: proposal 0xF002 (rcs_signature) is IMPLEMENTED and device-proven
        // and was never advertised. An under-advertisement is as wrong as an over-advertisement —
        // we could do it and did not say so.
        let ext_types: Vec<ExtensionType> = crate::rcc16::advertised_extensions(extra_ext_types)
            .into_iter().map(ExtensionType::from).collect();
        let prop_types: Vec<ProposalType> = crate::rcc16::advertised_proposals(extra_prop_types)
            .into_iter().map(ProposalType::from).collect();
        // KP LeafNode lifetime window — anchor AND duration, both per-path. See kp_lifetime_window:
        // Google Messages anchors not_before at cert.notBefore (not at NOW, which is what mls-rs does
        // by default and what a peer with a trailing clock rejects).
        let (kp_not_before, kp_lifetime_secs) = kp_lifetime_window(leaf, tachyon_profile());
        let client = Client::builder()
            .crypto_provider(crypto)
            .identity_provider(idp)
            .group_state_storage(FileGroupStateStorage::new(storage_dir))
            // ratchet_tree_extension(false): do NOT embed the ratchet_tree as a GroupInfo extension
            // (0x0002). Google's CreateMlsConversation REJECTS it — device-proven 2026-07-24:
            // grpcStatus=3 "Unsupported ratchet_tree extension" on our external_commit_group_info,
            // which mls-rs bundles the tree into by default (mls_rules CommitOptions default = true).
            // With false, the GroupInfo carries only external_pub; the tree stays available standalone
            // via export_tree()/commit.ratchet_tree() for the separate request field / Welcome. Keeps
            // allow_external_commit(true) for the external_pub ext (interop external-commit joins).
            // Rcc16MlsRules wraps DefaultMlsRules with the RCC.16 era guard: a GroupContextExtensions
            // proposal may neither change nor remove 0xF001 (§9.2). It sits here rather than at the
            // call sites because filter_proposals runs on BOTH directions, so an inbound peer commit
            // that tries to move our era is refused on apply as well as on build.
            .mls_rules(crate::rcc16::Rcc16MlsRules::new(DefaultMlsRules::default()
                .with_commit_options(CommitOptions::new()
                    .with_allow_external_commit(true)
                    .with_ratchet_tree_extension(false))
                // PADME padding (RFC 9420 §17.6 / the Annex C.2 length-hiding function RCC.16 also
                // uses for files): pads to within 11.11% while leaking only O(log log M) bits of the
                // plaintext length. mls-rs defaults to StepFunction, which pads differently and so
                // makes our ciphertext lengths distinguishable from a real client's on the wire.
                // encrypt_control_messages stays false — unchanged, and a separate question.
                .with_encryption_options(EncryptionOptions::new(false, PaddingMode::Padme))))
            // Persist KeyPackage SECRETS to disk (same per-identity dir as group state). Without this
            // mls-rs keeps KP secrets in-memory only, so a Welcome that arrives in a LATER session
            // (the iPhone-adds-us case) fails `WelcomeKeyPackageNotFound`.
            .key_package_repo(FileKeyPackageStorage::new(storage_dir.to_string()))
            .extension_types(ext_types)
            .custom_proposal_types(prop_types)
            .key_package_lifetime(Duration::from_secs(kp_lifetime_secs))
            .signing_identity(signing_identity.clone(), secret.clone(), SUITE)
            .build();
        // Held for `self_update` — see the `signing_identity` field. The builder CONSUMES both, and
        // `Client` exposes no getter for either, so the only way to still have them at commit time
        // is to keep a copy here.
        let kept_identity = signing_identity;
        let kept_signer = secret;
        Ok(Self { client, storage_dir: storage_dir.into(), send_cache: std::sync::Mutex::new(None),
                  kp_not_before: kp_not_before.map(MlsTime::from),
                  signing_identity: kept_identity, signer: kept_signer,
                  welcome_continuity: std::sync::Mutex::new(Vec::new()) })
    }
    /// The ordinary entry point: engine-decided advertisement, no transport extras.
    #[allow(clippy::too_many_arguments)]
    pub fn start(leaf: &[u8], chain: &[Vec<u8>], priv32: &[u8], pub65: &[u8],
                 roots: &[Vec<u8>], revoked_serials: &[Vec<u8>],
                 storage_dir: &str) -> Result<Self, String> {
        Self::start_with_advertisement(leaf, chain, priv32, pub65, roots, revoked_serials,
                                       &[], &[], storage_dir)
    }

    /// `start` with an EMPTY revocation list — the normal state in the field, and the only thing
    /// tests want unless they are specifically exercising revocation.
    ///
    /// Named rather than defaulted so that "no revocation list" stays a visible, deliberate choice
    /// at each call site instead of something a caller can omit without noticing.
    #[cfg(test)]
    pub fn start_no_revocation(leaf: &[u8], chain: &[Vec<u8>], priv32: &[u8], pub65: &[u8],
                               roots: &[Vec<u8>], storage_dir: &str) -> Result<Self, String> {
        Self::start(leaf, chain, priv32, pub65, roots, &[], storage_dir)
    }

    pub fn generate_key_packages(&self, count: u32) -> Result<Vec<u8>, String> {
        let mut kps = Vec::new();
        for _ in 0..count.max(1) {
            // Emit the MLSMessage-WRAPPED KeyPackage (version=mls10 || wire_format=mls_key_package(5)
            // || KeyPackage), i.e. MlsMessage::to_bytes(). Both Google Messages'
            // create_group_with_members and Google's KDS UploadKeyPackages parse this as an MLSMessage,
            // NOT a bare KeyPackage — a RAW KP's cipher_suite(0x0002) gets misread as wire_format=2
            // (private_message) → cursor drift → the peer's parser reports "Invalid varint prefix 3",
            // and its identity check then reads a wire_format of 0 where it requires 5
            // (mls_key_package). Measured 2026-07-15:
            // our KP is RFC-9420-clean; the only bug was the missing MLSMessage envelope.
            // The third arg is the Lifetime ANCHOR (not_before). Passing None anchors at NOW; we pass
            // the cert's notBefore so the leaf matches Google Messages and survives peer clock skew.
            let kp = self.client.generate_key_package_message(Default::default(), Default::default(),
                    self.kp_not_before)
                .map_err(|e| format!("gen_kp: {e:?}"))?
                .to_bytes().map_err(|e| format!("kp_to_bytes: {e:?}"))?;
            kps.push(kp);
        }
        Ok(join_len_prefixed(&kps))  // [u32 len][MLSMessage-wrapped kp]...
    }
    /// The RFC-9420 **KeyPackageRef** of one MLSMessage-wrapped KeyPackage — the identity a Welcome
    /// addresses a package by, so publish-time and consume-time can be matched exactly.
    ///
    /// The ref is `RefHash("MLS 1.0 KeyPackage Reference", KeyPackage)`, so it is derived from the
    /// package bytes and is stable across processes — the reason the published set can be persisted
    /// and still mean something after a restart.
    pub fn key_package_ref(&self, kp: &[u8]) -> Result<Vec<u8>, String> {
        use mls_rs_core::crypto::CryptoProvider;
        let m = MlsMessage::from_bytes(kp).map_err(|e| format!("kp_from_bytes: {e:?}"))?;
        let csp = RustCryptoProvider::default().cipher_suite_provider(SUITE)
            .ok_or_else(|| "key_package_ref: no cipher suite provider".to_string())?;
        match m.key_package_reference(&csp).map_err(|e| format!("kp_ref: {e:?}"))? {
            Some(r) => Ok(r.to_vec()),
            // Not an error worth a panic upstream: the caller hands us whatever it published, and a
            // non-KeyPackage there is a caller bug we want named rather than silently hashed.
            None => Err("key_package_ref: not a KeyPackage MLSMessage".to_string()),
        }
    }

    /// The KeyPackageRefs a Welcome is SEALED TO — one per EncryptedGroupSecrets entry.
    ///
    /// Returns the len-prefixed list. Matching these against the published set says exactly WHICH
    /// package a join consumed, instead of the blind "decrement by one on any Welcome-open" that
    /// miscounted a duplicate or replayed Welcome.
    ///
    /// A Welcome carries a secrets entry per ADDED member, so in a multi-add only ONE of these is
    /// ours. Cross off the intersection, never the whole list.
    pub fn welcome_key_package_refs(&self, welcome: &[u8]) -> Result<Vec<u8>, String> {
        let m = MlsMessage::from_bytes(welcome)
            .map_err(|e| format!("welcome_from_bytes: {e:?}"))?;
        let refs: Vec<Vec<u8>> = m.welcome_key_package_references()
            .into_iter().map(|r| r.to_vec()).collect();
        Ok(join_len_prefixed(&refs))
    }

    /// Generate ONE last-resort KeyPackage: a KP carrying the RFC-9420 `last_resort` extension
    /// (0x000A) in KeyPackage.extensions, MLSMessage-wrapped. The KDS UploadKeyPackages last-resort
    /// slot REQUIRES this extension — a plain KP as last-resort → generic INVALID_ARGUMENT.
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
    /// Create a 1:1 group carrying Era 0xF001, add the peer KP. Returns the group id + the packed
    /// artifacts `[welcome | commit | groupInfo | tag(32B) | gid]` (5 len-prefixed records) — the
    /// five inputs `MlsCreateConversationClient` + the transport need:
    ///   - `welcome`   → create-request f2 (the added member's Welcome)
    ///   - `commit`    → create-request f3 (the epoch-advancing Commit)
    ///   - `groupInfo` → create-request f5 (GroupInfo + ratchet tree; needs allow_external_commit)
    ///   - `tag`       → create-request f4 (the 32-byte epoch_authenticator of the POST-commit epoch)
    ///   - `gid`       → the engine-chosen group id (mls-rs mints it; the caller keys storage by it)
    pub fn create_group(&self, era: u32, peer_kp: &[u8], gid_override: &[u8])
            -> Result<(Vec<u8>, Vec<u8>), String> {
        self.create_group_multi(era, std::slice::from_ref(&peer_kp.to_vec()), gid_override)
    }

    /// Create a group whose INITIAL commit adds EVERY member.
    ///
    /// <b>Why this exists.</b> `create_group` adds exactly one peer, so an N-member RCS group got a
    /// 2-member MLS group and Tachyon refused the mismatch with `mlsError 5`
    /// (mismatched-rcs-group-state) — it validates the MLS GroupContext against the full RCS roster.
    /// Google Messages builds the whole membership in one commit (`create_group_with_members`, which
    /// logs `initial_member_keypackages` as a LIST), not create-then-Add.
    ///
    /// One commit means ONE Welcome covering all added members (RFC 9420), so joiners are unchanged.
    pub fn create_group_multi(&self, era: u32, peer_kps: &[Vec<u8>], gid_override: &[u8])
            -> Result<(Vec<u8>, Vec<u8>), String> {
        self.create_group_carry(era, peer_kps, gid_override, &[], AdvanceEraKind::Normal)
    }

    /// The certified MSISDN of a CLAIMED KeyPackage, or empty when it has none.
    ///
    /// The KeyPackage-side twin of [`Self::certified_msisdn_of`], which answers the same question for
    /// a roster leaf. Both read `leaf_san_msisdn`, so "who is this leaf" has ONE definition whether
    /// the leaf is already in the group or is about to be added — which is the whole basis on which
    /// [`Self::plan_group`] may compare a requested roster against the one it holds via
    /// [`crate::rcc16::additions_to`]. Empty is UNKNOWN, never a mismatch.
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

    /// DECIDE what this group operation actually is, and at which era (design §9.5).
    ///
    /// # The inversion this exists to fix
    ///
    /// The host used to compute the era: read `getMlsServerEraEpoch`, add one, pass the number down.
    /// The engine then stamped whatever it was handed into 0xF001. That put the DECISION above the
    /// layer that owns the field, and the two could disagree without anything noticing — "we are
    /// creating at era N" and "the GroupInfo says era N" are separate claims and only the second one
    /// ever reaches the server.
    ///
    /// Google Messages' engine is told no era at all (its create takes a context, a config and a
    /// member list), derives one from its own state, and reports what it did as a `welcomeAction`. This
    /// is that derivation. **ERA IS AN INPUT TO READS AND AN OUTPUT OF WRITES.**
    ///
    /// # What counts as "its own state"
    ///
    /// Two sources, both of them BYTES rather than numbers:
    ///
    /// * the group we hold at `gid_override`, if any — its 0xF001;
    /// * `carry_group_info`, the GroupInfo the host fetched from the server — its 0xF001.
    ///
    /// The second is not a host decision sneaking back in. A member that needs an advance is behind
    /// by definition, so its own era is stale; the server's GroupInfo is the authority and the host's
    /// only job was to fetch it. Reading the era out of those bytes here is exactly the difference
    /// between the engine being told a number and the engine being handed a state. It also
    /// subsumes, correctly, the host arithmetic it replaces: `max(local, server) + 1` was
    /// device-derived on 2026-07-30 (local 1 / server 2, where local+1 would have targeted an era the
    /// server already held), and it is the same maximum — taken one layer down, where 0xF001 is
    /// written.
    ///
    /// # The three answers
    ///
    /// * **[`WELCOME_ACTION_NEW_GROUP`]** — nothing local, nothing carried. Born at
    ///   [`ERA_INITIAL`](crate::rcc16::ERA_INITIAL).
    /// * **[`WELCOME_ACTION_NEW_MEMBERSHIP_EXISTING_GROUP`]** — we hold the group, no server state
    ///   was carried, and the requested roster is the one we hold PLUS someone. That is not a create:
    ///   the era does not move and the artifacts are an `addMembers` commit. Returning it as a create
    ///   at era+1 is the "accepted and discarded" shape we reproduced.
    /// * **[`WELCOME_ACTION_NEW_ERA_EXISTING_GROUP`]** — anything else with prior state: the era
    ///   moves forward, past the higher of the two sources.
    ///
    /// # What this deliberately never returns
    ///
    /// `REFRESH_MEMBERSHIP_EXISTING_GROUP` (4) is a real value peers send us and our INBOUND path
    /// accepts, but we have never observed the condition that produces it outbound and are not going
    /// to guess one — a rule invented here would be indistinguishable from the arm-3 rule until it
    /// silently misrouted something. `UNKNOWN` (0) is never returned either: it means "the engine
    /// named no action", and this function always names one or fails.
    ///
    /// Returns `(era, welcome_action, kps_to_add)`. The third is populated only for arm 3, and holds
    /// just the packages whose MSISDN is not already in the roster.
    pub fn plan_group(&self, peer_kps: &[Vec<u8>], gid_override: &[u8], carry_group_info: &[u8])
            -> Result<(u32, u32, Vec<Vec<u8>>), String> {
        // READ-ONLY. Nothing below writes, and nothing may: this runs BEFORE create_group_carry's
        // delete_group, which is the only chance to see the state we are about to replace.
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

        // ARM 1 — nothing anywhere. A group that did not exist is born at era 1.
        //
        // Note what is NOT consulted: the server's era for a conversation we hold no state for. The
        // host used to ask, and create at server+1 so as not to draw "Era changed from 1 to 1". That
        // read is exactly the host-chosen era this is about, and a conversation the server already
        // holds is not a create — it is an advance, and it arrives here WITH a carry GroupInfo.
        if local_era.is_none() && carried_era.is_none() && local.is_none() {
            alog!("plan_group: no local group at this id and no carried GroupInfo → NEW_GROUP at \
                   era {}", crate::rcc16::ERA_INITIAL);
            return Ok((crate::rcc16::ERA_INITIAL, crate::rcc16::WELCOME_ACTION_NEW_GROUP,
                       Vec::new()));
        }

        // ARM 3 — we hold the group, nobody handed us server state, and the ask is additive.
        //
        // "Additive" means SOME requested participant is already a member and some are not. All-new
        // is a group we do not really hold a matching roster for, and all-known is a membership
        // refresh; both belong on the create path below, where the whole roster is rebuilt.
        //
        // A CARRIED GroupInfo disqualifies this arm outright, and that is not a detail: somebody
        // fetched server state, which is an advance BY INTENT. Letting a self-heal be silently
        // downgraded into an addMembers would leave the era exactly where it was — the stuck state
        // the recovery path exists to escape.
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

        // ARM 2 — prior state exists and the era moves, past the HIGHER of the two sources.
        let base = core::cmp::max(local_era.unwrap_or(0), carried_era.unwrap_or(0));
        let era = crate::rcc16::next_era(base)?;
        alog!("plan_group: local era={local_era:?} carried era={carried_era:?} → \
               NEW_ERA_EXISTING_GROUP at era {era} (past the higher of the two)");
        Ok((era, crate::rcc16::WELCOME_ACTION_NEW_ERA_EXISTING_GROUP, Vec::new()))
    }

    /// Build a group operation WITHOUT being told an era — the entry point [`Self::plan_group`]
    /// exists for, and the one the host is expected to call.
    ///
    /// Decides via `plan_group`, executes the operation that decision names, and reports both the era
    /// and the `welcomeAction` back in the artifact bundle as slots 6 and 7. The host's job shrinks to
    /// picking an RPC from the action it is handed: arm 3 ships on the add RPC, arms 1 and 2 on
    /// `CreateMlsConversation`.
    ///
    /// The two extra slots are APPENDED so indices 0..5 stay byte-stable for every existing consumer,
    /// the same convention the ratchet tree arrived under.
    pub fn create_group_planned(&self, peer_kps: &[Vec<u8>], gid_override: &[u8],
            carry_group_info: &[u8], kind: AdvanceEraKind)
            -> Result<(Vec<u8>, Vec<u8>), String> {
        if peer_kps.is_empty() {
            return Err("create_group_planned: no member KeyPackages".to_string());
        }
        let (era, action, to_add) = self.plan_group(peer_kps, gid_override, carry_group_info)?;
        let (gid, artifacts) =
                if action == crate::rcc16::WELCOME_ACTION_NEW_MEMBERSHIP_EXISTING_GROUP {
            // NOT a create. The group survives, the era does not move, and the bundle carries an
            // addMembers commit — the artifacts the add RPC wants, produced by the layer that knows
            // this is what the operation actually was.
            (gid_override.to_vec(), self.add_members(gid_override, &to_add, &[])?)
        } else {
            self.create_group_carry(era, peer_kps, gid_override, carry_group_info, kind)?
        };
        // WHO the commit actually admits, so the host does not have to work it out.
        //
        // The RCS half of an add names participants, and it must name the SAME ones the MLS commit
        // added or the server sees a roster change and a commit that disagree — the shape that draws
        // mismatched-rcs-group-state. Only this layer knows which of the requested packages were new,
        // so recomputing it upstairs would put half the decision back where it came from.
        let admitted: Vec<Vec<u8>> = to_add.iter().map(|kp| Self::kp_certified_msisdn(kp)).collect();
        let mut parts = split_len_prefixed(&artifacts);
        parts.push(era.to_be_bytes().to_vec());
        parts.push(action.to_be_bytes().to_vec());
        parts.push(join_len_prefixed(&admitted));
        Ok((gid, join_len_prefixed(&parts)))
    }

    /// As above, CARRYING OVER the RCC.16 group metadata from an existing GroupInfo.
    ///
    /// <b>Why an era advance needs this.</b> An era advance re-creates the group, and a fresh
    /// GroupContext has none of the metadata the old one accumulated. Tachyon refuses that outright:
    /// `"Subject commitment changed from Some([..]) to None"` — it will not let an era advance
    /// silently discard a committed subject/icon, which is exactly the check that stops someone
    /// erasing a commitment by advancing. So the new era must inherit 0xF003-0xF006.
    ///
    /// The source is the SERVER's GroupInfo, not ours: a member that needs an era advance is behind
    /// by definition, so its own copy of these extensions is stale or missing. Parsing lives here
    /// because it is MLS wire format, not something the transport should be decoding.
    pub fn create_group_carry(&self, era: u32, peer_kps: &[Vec<u8>], gid_override: &[u8],
            carry_group_info: &[u8], kind: AdvanceEraKind)
            -> Result<(Vec<u8>, Vec<u8>), String> {
        if peer_kps.is_empty() {
            return Err("create_group_multi: no member KeyPackages".to_string());
        }
        self.invalidate_send_cache();
        // RCC.16 §7.11.1.1: Era is a real 0xF001 GroupContext extension (uint32) carried on
        // GroupInfo + Welcome. Our KPs advertise 0xF001 support (start() extension_types).
        //
        // `era != 0` is a "caller supplied no era" guard, NOT a Tachyon opt-out. An older version of
        // this comment claimed the Tachyon path passed era=0 and therefore emitted no extension; that
        // is FALSE and was disproven on the wire 2026-07-26 — the host transport passes
        // the real computed era, and the GroupInfo Tachyon holds for our group decodes as
        // `f001 04 00000013` (era 19). A bug was once filed off that stale comment and closed invalid.
        // Do not "fix" this branch on the strength of a comment; dump the GroupInfo instead.
        let mut group_ctx = if era != 0 {
            CoreExtensionList::from(vec![
                Extension::new(ExtensionType::from(0xF001u16), era.to_be_bytes().to_vec())])
        } else {
            CoreExtensionList::new()
        };
        // Inherit the RCC.16 metadata extensions from the supplied GroupInfo. Only the four
        // icon/subject ones — the Era is set above from the NEW era, and anything else in the old
        // context belongs to the old group's shape, not its metadata.
        if !carry_group_info.is_empty() {
            match MlsMessage::from_bytes(carry_group_info)
                    .ok()
                    .and_then(|m| m.into_group_info()) {
                Some(gi) => {
                    // An advance must move STRICTLY FORWARD. The +1 happens on the host (the number
                    // comes from the server's era), so this is the engine's only chance to catch a
                    // wrapped, truncated or stale value before it becomes a group at an era the peers
                    // have already passed — which, unlike a failed advance, is unrecoverable.
                    // era 0 is included deliberately: it means "emit no Era extension", so carrying
                    // from a source that HAS one would produce a group peers refuse outright with
                    // `Missing Era group context extension`. The era-0 lab path passes no carry
                    // GroupInfo at all, so it never reaches here.
                    crate::rcc16::check_era_advances(
                        crate::rcc16::era_of(gi.group_context().extensions()), era)?;
                    // A DENY-LIST, NOT AN ALLOW-LIST.
                    //
                    // This used to name the five types it would inherit, which inverts the rule:
                    // Google Messages never rebuilds a GroupContext extension list, it starts from the
                    // existing one and applies targeted edits, and its complete deliberate-drop set
                    // is five sites — 0xF001, 0xF003, 0xF005 and 0xF010 are never removed by any code
                    // path. An allow-list drops everything it was not told about BY CONSTRUCTION, so
                    // 0xF007, 0xF010, `required_capabilities` and every future code point vanished
                    // silently across an advance, and the loss shows up later as an unexplained peer
                    // rejection rather than as an error here.
                    //
                    // 0xF001 is the only entry: the caller is advancing the era, so the NEW value set
                    // above must win over the source's. Everything else rides along byte-for-byte.
                    //
                    // 0xF002 rides along under AdvanceEraKind::Normal, deliberately. An era advance
                    // re-creates the group, so dropping end_mls would silently erase a downgrade: a
                    // peer ends MLS, we advance for an unrelated reason, and the conversation comes
                    // back encrypted against that peer's decision.
                    //
                    // THE MODE BYTE (§9.7g) is what makes the two exceptions
                    // expressible without weakening that. Their advance_era carries a 3-valued
                    // mode: 1 REMOVES end_mls (revival), 2 INSTALLS it (phoenix), 0 touches neither.
                    // Making clearing a distinct MODE rather than a guarded branch is strictly
                    // stronger than a guard — there is no code path in which a plain advance COULD
                    // clear it and be prevented (§9.7m mechanism 3).
                    //
                    // Before this, the carry could only ever PRESERVE end_mls: revival was
                    // unimplemented and "the new era is born downgraded" was inexpressible.
                    let mut carried: Vec<u16> = Vec::new();
                    for e in gi.group_context().extensions().iter() {
                        let ty: u16 = (*e.extension_type()).into();
                        if ty == crate::rcc16::ERA_EXT { continue; }
                        // THE END-MLS REMOVAL RULE, at the removal site. This is one of exactly TWO places in
                        // this crate that may drop 0xF002, and the other is commit_end_mls's
                        // EndMlsOp::RemoveForRevival arm. Both assert a revival intent supplied by
                        // the caller; neither can be reached by a recovery path that merely forgot
                        // to carry something.
                        if ty == crate::rcc16::END_MLS_EXT && kind.may_remove_end_mls() {
                            alog!("create_group_carry: REVIVAL (mode 1) — dropping end_mls (0xF002) \
                                   from the new era's GroupContext. This is a deliberate removal.");
                            continue;
                        }
                        // extension_data is copied VERBATIM and that is correct: it is already
                        // framed (inner varint and all) by whoever produced the source GroupInfo,
                        // so re-encoding it here would double-frame it. Do not "fix" this to
                        // ext_decode/ext_encode — carry is byte-preserving by design.
                        group_ctx.set(Extension::new(ExtensionType::from(ty),
                                                     e.extension_data.clone()));
                        carried.push(ty);
                    }
                    // Log the TYPES, not just a count: which extension went missing is the first
                    // question asked when an advance is refused, and a count cannot answer it.
                    alog!("create_group_carry: inherited {} GroupContext extension(s) from \
                                   the supplied GroupInfo: {:04X?} (era set to {era})",
                                  carried.len(), carried);
                }
                None => {
                    // Do NOT silently create without them: the server would refuse the advance and
                    // the reason would surface as an opaque wire error far from here.
                    return Err("create_group_carry: could not parse the carry-over GroupInfo"
                        .to_string());
                }
            }
        }
        // MODE 2 — PHOENIX: the new era is BORN DOWNGRADED (§9.7g).
        //
        // Outside the carry block on purpose. The install must happen whether or not a source
        // GroupInfo was supplied, because Phoenix exists precisely for the case where the current
        // group is wedged badly enough that the ordinary end-mls commit cannot land — and "we could
        // not fetch the old GroupInfo either" is squarely inside that case. Putting it inside the
        // `if !carry_group_info.is_empty()` arm would make the one mode that must never fail
        // silently depend on the one input most likely to be missing.
        //
        // AFTER the carry loop, so it wins over an inherited absence; and after the era is set, per
        // the ordering rule: the era is set FIRST in an era advance, before the end-mls edit.
        if kind.installs_end_mls() {
            // The payload is version-dependent (v3.0 ASCII literal vs v4.0 EndMlsMetadata) and
            // `end_mls_payload` is the only thing allowed to choose. Phoenix has a reason the spec
            // names exactly: v4.0 EndMlsReason 10 is "Client ends MLS with Phoenix mode because the
            // outgoing end_mls Commit failed", which is this branch verbatim. Under v3.0 (the
            // default, and what Tachyon speaks) the reason is dropped and the bytes are unchanged.
            group_ctx.set(Extension::new(ExtensionType::from(crate::rcc16::END_MLS_EXT),
                crate::rcc16::end_mls_payload(crate::rcc16::EndMlsReason::OutgoingCommitFailed)));
            alog!("create_group_carry: PHOENIX (mode 2) — INSTALLING end_mls (0xF002) in the new \
                   era's GroupContext. The new era is born downgraded; no key packages are needed \
                   to advance into an era whose group is not encrypted (INV-KP).");
        }
        // The server VALIDATES the MLS group_id and REJECTS raw random bytes ("Invalid group ID",
        // device-proven 2026-07-24). Google Messages' group_id is a lowercase UUID STRING (e.g.
        // "c1e9bb86-04fb-48f7-a869-6ce76b953212") — 36 ASCII bytes. So mint the
        // group_id as a UUID-v4 string instead of letting mls-rs mint random bytes (create_group's
        // None). mls-rs treats group_id as opaque bytes, so an ASCII UUID is fine.
        // REVIVE (era-advancement CREATE): reuse the server's EXISTING rcs group_id instead of
        // minting a new UUID — a revive keeps the same conversation, only the era
        // advances. Minting a fresh id draws "Group ID changed" / IncorrectEra. `gid_override` empty ⟹
        // normal create (mint a fresh UUID-v4 string; their group_id is a 36-ASCII lowercase UUID).
        let gid_bytes = if gid_override.is_empty() {
            mint_uuid_string().into_bytes()
        } else {
            // Revive reuses an EXISTING group_id → a prior (failed) attempt can leave stale group state
            // at this id in local storage, so create+add+apply hits `apply: InvalidEpoch` against the
            // leftover epoch data. Delete any stale state first (idempotent), same as external_commit_resync.
            FileGroupStateStorage::new(self.storage_dir.clone()).delete_group(gid_override);
            gid_override.to_vec()
        };
        // The fourth arg is the leaf Lifetime anchor; passing None anchored it at NOW, which was the
        // root cause of a long-running join failure. See `leaf_lifetime_anchor` for the whole story —
        // it is the ONE place that decides this, for every site that mints a leaf.
        let anchor = self.leaf_lifetime_anchor();
        alog!("create_group: leaf Lifetime anchor={:?} (cert notBefore={:?}) — anchoring in the past \
            so a peer whose clock trails ours can still join; None would anchor at NOW and draw \
            MlsError_InvalidLifetime on the joiner", anchor, self.kp_not_before);
        let mut g = self.client
            .create_group_with_id(gid_bytes, group_ctx, Default::default(), anchor)
            .map_err(|e| format!("create_group: {e:?}"))?;
        // Each peer_kp is a RAW KeyPackage (as claimed from KDS) — re-wrap into the MlsMessage wire
        // form add_member expects. (Tolerate an already-wrapped input for the local self-test.)
        // EVERY member is added in this ONE commit, so the initial MLS membership equals the RCS
        // roster and the server has nothing to mismatch.
        let mut builder = g.commit_builder();
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
        // epoch_authenticator of the now-current (post-Add) epoch — the create-request f4 32-byte tag.
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        g.write_to_storage().map_err(|e| format!("store: {e:?}"))?;
        let welcome = commit.welcome_messages.first()
            .ok_or_else(|| "create: commit produced no Welcome".to_string())?
            .to_bytes().map_err(|e| format!("{e:?}"))?;
        let commit_b = commit.commit_message.to_bytes().map_err(|e| format!("{e:?}"))?;
        let ginfo = commit.external_commit_group_info
            .ok_or_else(|| "no external_commit_group_info (allow_external_commit off?)".to_string())?
            .to_bytes().map_err(|e| format!("ginfo: {e:?}"))?;
        // The STANDALONE serialized ratchet tree of the current (post-Add) epoch. The server's
        // create request carries GroupInfo and ratchet_tree as SEPARATE fields — our
        // external_commit_group_info may bundle the tree as a GroupInfo extension, so we export it
        // independently here. (RFC 9420 ratchet_tree.)
        // APPENDED as element 5 so indices 0..4 stay byte-stable for every existing consumer.
        let tree = g.export_tree().to_bytes().map_err(|e| format!("export_tree: {e:?}"))?;
        let artifacts = join_len_prefixed(&[welcome, commit_b, ginfo, tag, gid.clone(), tree]);
        Ok((gid, artifacts))
    }
    /// Drop the prior-epoch archive of a group we have just (RE)JOINED, before its fresh state is
    /// written. See [`FileGroupStateStorage::purge_epochs`] for the full mechanism — in short, the
    /// MLS group id survives a remove+re-add within an era, so a rejoiner inherits an archive that
    /// stops at its OLD membership, and mls-rs then refuses the very next commit as `InvalidEpoch`
    /// because the epoch it wants to archive is not `max + 1`. That refusal is what stranded a
    /// member on every membership change.
    ///
    /// Every path that puts us on a branch at an epoch we did not walk to needs this, which is both
    /// Welcome joins and external-commit joins — not just the Welcome ones.
    fn purge_prior_epochs_on_join(&self, gid: &[u8], how: &str) {
        let n = FileGroupStateStorage::new(self.storage_dir.clone()).purge_epochs(gid);
        if n > 0 {
            alog!("{how}: dropped {n} archived prior epoch(s) left over from a PREVIOUS membership \
                of this group. Keeping them makes the NEXT commit fail InvalidEpoch at its own \
                epoch, which strands this member permanently.");
        }
    }

    /// Format an extension list as ` 0xTTTT=NB` pairs — the SAME shape the host's `GROUP-EXT` lines
    /// use, so a Welcome's list and a GroupInfo's can be read side by side without re-encoding one.
    /// Empty list ⇒ `" (none)"`, never an empty string, so the log never ends in a dangling dash.
    fn ext_list_summary(exts: &CoreExtensionList) -> String {
        let mut s = String::new();
        for e in exts.iter() {
            let ty: u16 = (*e.extension_type()).into();
            s.push_str(&format!(" 0x{:04X}={}B", ty, e.extension_data.len()));
        }
        if s.is_empty() { " (none)".to_string() } else { s }
    }

    /// WELCOME-ONLY EXTENSION INSTRUMENT. LOG ONLY: it never refuses a join, never
    /// changes state, and never looks at a value.
    ///
    /// <b>That is still true, and it is no longer the whole story.</b> Every join site now also
    /// calls [`ProdSession::capture_welcome_continuity_token`], which reads the same list and keeps
    /// the 0xF010 VALUE for the host to persist. The two are deliberately separate: this
    /// one is the measurement that answered the question and must stay unable to affect a join, and the
    /// other one is the consumer. Do not fold them together — a log line that can fail a join is a
    /// different thing from an instrument.
    ///
    /// <b>What it reads, and why nothing else could.</b> RFC 9420 makes a Welcome
    /// `{cipher_suite, secrets<V>, encrypted_group_info<V>}` — the GroupInfo is encrypted under a
    /// joiner secret only the addressee can derive, so a Welcome on disk has no cleartext
    /// extensions at all. `join_group` decrypts it and hands the GroupInfo's OWN extension list
    /// back as `NewMemberInfo.group_info_extensions`. This function is standing at the one
    /// instant, in the one process, where those bytes are in the clear.
    ///
    /// <b>It is the GroupInfo's own list, NOT the GroupContext's.</b> RFC 9420 §11 keeps two
    /// separate lists — `GroupInfo.group_context.extensions` (era, icon_key, subject_key … which
    /// every member holds) and `GroupInfo.extensions` (ratchet_tree, external_pub … which travel
    /// with THAT GroupInfo). RCC.16 v4.0 §7.11.12.1 puts the continuity TOKEN (0xF010) "only in the
    /// encrypted groupinfo in Welcome messages", i.e. in the second list. That distinction is the
    /// whole reason this exists: `group_ext(gid, ty)` reads the joined group's GroupContext and
    /// `group_info_ext_types` reads a GroupInfo's group_context, so BOTH are structurally blind to
    /// 0xF010 no matter what artefact they are pointed at. Only `gi.extensions()` — and, before a
    /// group exists, only this — can see one. (`group_info_continuity` reads the right list, which
    /// is why the earlier 0xF011 commitment negative still stands.)
    ///
    /// <b>Nothing filters this list.</b> mls-rs calls `ungrease()` on `NewMemberInfo`, but the
    /// `grease` cargo feature is OFF in our build, so `ungrease_extensions` is the no-op stub —
    /// and 0xF010 is not a GREASE code point (those are 0x?A?A) even when it is on. An absence
    /// logged here is an absence on the wire.
    fn log_welcome_group_info_exts(how: &str, gid: &[u8], info: &mls_rs::group::NewMemberInfo) {
        let line = Self::welcome_gi_ext_line(how, gid, info.sender, info.group_info_extensions());
        alog!("{line}");
        #[cfg(test)]
        LAST_WELCOME_GI_EXT_LINE.with(|c| *c.borrow_mut() = Some(line));
    }

    /// The line itself, split out so the whole thing — the list, the committer, and WHICH branch of
    /// the 0xF010 verdict it took — is asserted by a host test rather than only read on a device.
    /// `alog!` compiles to nothing off Android, so a formatter left inside the logging call would
    /// have no oracle at all.
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

    /// Keep the §7.11.12.1 continuity token out of a Welcome's DECRYPTED GroupInfo.
    ///
    /// <b>Why it has to happen here.</b> RFC 9420 ships the GroupInfo inside a Welcome encrypted
    /// under a joiner secret, so the token is in the clear for exactly the length of `join_group`,
    /// in this process, once. `group_ext` reads the joined group's GroupContext and
    /// `group_info_ext` needs a serialized GroupInfo; both are structurally blind to 0xF010 wherever
    /// they are pointed, which is how this project produced the same confident false negative twice
    /// (the WELCOME-EXT headstone in `MlsProviderTransport`). There is no later read.
    ///
    /// <b>It stores the DECODED value</b>, so the two routes a token can arrive by agree at the
    /// host's store: §10.5.4's `GroupMetadataKeys` yields a bare token from a proto field, and this
    /// yields a bare token from an `opaque<V>`. A store fed one framed and one bare value would
    /// compare unequal for a reason that has nothing to do with continuity.
    ///
    /// <b>Never refuses a join.</b> A token we cannot decode is logged and dropped; the join stands.
    /// Continuity is a validation mechanism with a §11.2 downgrade at the end of it, and refusing to
    /// enter a group over an extension nothing yet consumes would be a far larger harm than the one
    /// this fixes.
    fn capture_welcome_continuity_token(&self, gid: &[u8], info: &mls_rs::group::NewMemberInfo) {
        let exts = info.group_info_extensions();
        let ty = crate::rcc16::CONTINUITY_TOKEN_EXT;
        let Some(e) = exts.get(ExtensionType::from(ty)) else {
            // NOT an error, and the log says which fact it is: a Welcome WE built carries no token
            // (we do not mint one), and so does a Welcome from a peer that does not do continuity.
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
                    // A poisoned mutex means another thread panicked holding it. Losing the token is
                    // the lesser outcome; panicking here would fail a join that has already succeeded.
                    Err(poisoned) => poisoned.into_inner(),
                };
                q.retain(|(g, _)| g != gid);
                q.push((gid.to_vec(), v.clone()));
                while q.len() > WELCOME_CONTINUITY_SLOTS { q.remove(0); }
                // LENGTH AND FRAMING ONLY — the value is a 256-bit group secret and this is logcat.
                alog!("WELCOME-CONTINUITY g:{} CAPTURED {}B token from 0x{ty:04X} ({}B on the wire, \
{}) — held for the host to persist; {} slot(s) pending",
                    gid_str(gid), v.len(), raw.len(),
                    if framed { "opaque<V>-framed, the shape measured off Google Messages" }
                    else { "BARE — no inner length; tolerated, and worth reporting" },
                    q.len());
            }
            Err(err) => alog!("WELCOME-CONTINUITY g:{} UNDECODABLE 0x{ty:04X} ({}B): {err} — NOT \
persisted. The join stands; this only means we hold no token for this group.",
                gid_str(gid), raw.len()),
        }
    }

    /// Hand the host the token captured at `gid`'s join, and forget it here — see
    /// [`ProdSession::welcome_continuity`]. Empty means "none captured", which is the ordinary
    /// answer for a group we created ourselves, for an external-commit join (§7.11.12.1 is
    /// Welcome-only, and an external commit has no Welcome), and for a peer that does not do
    /// continuity.
    ///
    /// Idempotent in the direction that matters: a second call returns empty, so a caller cannot
    /// persist the same token twice and read the repeat as a re-mint.
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
        let (mut g, info) = self.client.join_group(None, &w, None).map_err(|e| format!("join: {e:?}"))?;
        let gid = g.group_id().to_vec();
        Self::log_welcome_group_info_exts("join", &gid, &info);
        self.capture_welcome_continuity_token(&gid, &info);
        self.purge_prior_epochs_on_join(&gid, "join");
        g.write_to_storage().map_err(|e| format!("{e:?}"))?;
        Ok(gid)
    }
    /// Welcome join WITH an out-of-band ratchet_tree. Apple RCS delivers the add-member Welcome
    /// (kind=5 event) whose group_info carries NO ratchet_tree extension — so `join()` fails
    /// `RatchetTreeNotFound` even though the KeyPackage secret is present (device-proven 2026-07-23,
    /// 0286 add). The tree ships alongside the Welcome in the same kind=5 blob; the caller extracts
    /// it and passes it here. `ratchet_tree` = the exported RFC-9420 RatchetTree (mls-rs
    /// ExportedTree wire form); empty falls back to the in-Welcome tree (== plain `join`).
    pub fn join_with_tree(&self, welcome: &[u8], ratchet_tree: &[u8]) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let w = MlsMessage::from_bytes(welcome).map_err(|e| format!("{e:?}"))?;
        let tree = if ratchet_tree.is_empty() {
            None
        } else {
            Some(ExportedTree::from_bytes(ratchet_tree).map_err(|e| format!("tree_from_bytes: {e:?}"))?)
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
    /// Apple kind=5 add-member join: `welcome` is the bare Welcome (already unwrapped by the Java
    /// findMlsMessage); `blob` is the WHOLE kind=5 rawInner, which also carries the member LeafNodes
    /// (canonical RFC-9420) that make up the ratchet_tree. Locate + splice the tree from `blob`, then
    /// `join_group(Some(tree), welcome)`. Since KP-persist already lets process_welcome decrypt the
    /// group secrets, a successful join yields THIS epoch's secrets → the buffered message decrypts.
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
    /// GSMA external-commit join: join a peer-created group from its published GroupInfo (RFC-9420
    /// External Commit) rather than a Welcome — the inbound path a real iPhone uses. Google
    /// Messages' native engine does external-commit, not process_welcome (the kind=47
    /// inbound blob carries a GroupInfo + plaintext ratchet_tree, not an HPKE Welcome). Returns
    /// len-prefixed {group_id, external_commit_message}; the caller SENDS the external_commit back
    /// over Tachyon so the group applies our join. `ratchet_tree` = the plaintext tree if delivered
    /// out-of-band; empty → use the GroupInfo's own ratchet_tree extension.
    pub fn external_join(&self, group_info: &[u8], ratchet_tree: &[u8]) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let gi = MlsMessage::from_bytes(group_info)
            .map_err(|e| format!("group_info_from_bytes: {e:?}"))?;
        let mut builder = self.client.external_commit_builder()
            .map_err(|e| format!("external_commit_builder: {e:?}"))?;
        // An external commit mints a NEW leaf for us, with a Lifetime — so it needs the same anchor
        // as create_group. The builder defaults to None (= NOW), which the existing members would
        // reject with MlsError_InvalidLifetime the moment their clocks trail ours.
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
    /// The 32-byte epoch_authenticator of the group's CURRENT epoch (RCC.16 §7.11 Epoch-Authenticator).
    /// Used to stamp the CPIM `mls.Epoch-Authenticator` header — esp. on the joiner's outbound path
    /// (join() itself doesn't return it).
    pub fn epoch_auth(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        Ok(g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec())
    }
    /// Build the RCC.16 AuthenticatedData for an operation on `gid` — THE ENGINE'S JOB.
    ///
    /// RCC.16 has no authenticated-data field on the request: the host supplies a
    /// message_id via the request context and the engine builds the AAD, reading the era
    /// from the group's own 0xF001 ERA_EXT. The host used to build the whole
    /// thing and passed it down ten FFI entry points, which is the seam this removes.
    ///
    /// ERA SOURCE IS THE GROUP, NOT THE CALLER. Several host call sites used a
    /// `era > 0 ? era : 1` fallback, so a caller that had lost track of the era stamped a
    /// 1 into the AAD while the group was demonstrably at era 8. Reading 0xF001 here makes
    /// that impossible. This is safe with respect to decryption on the far side: the AAD
    /// TRAVELS IN THE MESSAGE and the receiver reads it rather than recomputing it, so a
    /// changed era value cannot break a peer's decrypt.
    ///
    /// `trailing` empty = the §10.3 resent-message component ABSENT, the ordinary case.
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
            // A group we cannot load has no era to read. 1 matches the host's old
            // fallback, so this is not a behaviour change — it is the same guess, made in
            // the one place that can no longer be wrong when the group IS loadable.
            Err(_) => 1,
        };
        // A host-set one-shot component wins over the caller's `trailing`, and is consumed
        // so it can never leak into a second message.
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

    /// Group's CURRENT era + epoch as a 12-byte blob `[era u32 BE][epoch u64 BE]`. era = the RCC.16
    /// Era GroupContext extension (0xF001, uint32), defaulting to 1 if absent; epoch = the RFC-9420
    /// group epoch counter (advances on every processed Commit). Used to stamp the SendMessage
    /// `Era-ID` MIME part with the ACTUAL era (not a hardcoded 1) and to log our epoch for the
    /// incorrect-epoch-authenticator diagnosis (local-vs-server epoch lag).
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
    /// Drop the cached sending group so the next encrypt reloads the group's CURRENT persisted epoch.
    /// MUST be called by every op that modifies the group (apply commit, rekey, add/remove, join,
    /// delete, restore) — else a stale cached group would encrypt at the wrong epoch.
    fn invalidate_send_cache(&self) {
        if let Ok(mut c) = self.send_cache.lock() { *c = None; }
    }

    /// The instant every leaf we mint anchors its `Lifetime.not_before` at. **Never pass `None` to an
    /// mls-rs call that takes this** — `None` means NOW, and NOW is a bug.
    ///
    /// THE DEFECT THIS EXISTS TO PREVENT (the same bug, three times over).
    ///
    /// mls-rs stamps a minted LeafNode with `Lifetime{not_before, not_after}`, anchored at this
    /// value. RFC 9420 §7.3 `within_lifetime` has the **RECEIVER** validate that leaf against **its
    /// own** clock — so a peer whose clock trails ours by even a moment sees `now < not_before` and
    /// refuses. A peer's clock trailing ours is the normal case, not an exotic one. It fails against
    /// real peers and passes wherever both halves share a clock, so no host test and no loopback
    /// capture can catch it — only a foreign implementation can.
    ///
    /// CAUGHT BY INSTRUMENTING THE PEER, 2026-08-04, after days spent auditing the sender. Google
    /// Messages' own engine log, on receiving our era-advance Welcome:
    ///     Joining existing group: GroupId: ""
    ///     Failed to join group: MlsError, MlsError_InvalidLifetime
    ///     ZINNIA_FAILURE_FAILED_TO_PROCESS_WELCOME (reason 25)
    /// and the peer then DELETED its group state. That is the whole of it: the server was never
    /// declining to advance the era — the PEER could not join the era we advanced to, so it never
    /// became real.
    ///
    /// WHY THIS IS ONE FUNCTION. In mls-rs 0.55.2 a Lifetime is minted at exactly three places, all
    /// via `config.lifetime(timestamp)`, and we reach all three:
    ///     client.rs:487           generate_key_package_message   (KeyPackage leaf)
    ///     group/mod.rs:311        Group::new / create_group_with_id   (creator leaf)
    ///     external_commit.rs:213  ExternalCommitBuilder::build   (external joiner leaf)
    /// The fix was applied to the first, then months later to the second, then to the third — each
    /// time as if it were a new bug. The right read is:
    /// this is one root that presents once per call site, so the decision belongs in one place.
    ///
    /// WHY NOT SIMPLY `self.kp_not_before`, the certificate's own notBefore: mls-rs uses this ONE
    /// argument for two jobs — the leaf Lifetime anchor AND the instant identity/chain validation
    /// runs at. A certificate chain is only valid from the LATEST notBefore among its members, so
    /// validating at the leaf's own notBefore fails whenever any CA in the chain starts later. 19
    /// Rust tests caught exactly that when it was tried:
    ///     chain: ValidityError { timestamp: 1783814400, not_before: 1784063130, … }
    ///
    /// So: anchor in the PAST BY A SKEW MARGIN, clamped into the certificate's own window —
    ///   * at most `now`             — never anchor in the future, which is the bug being fixed;
    ///   * at least `cert.notBefore` — never validate before our own certificate exists;
    ///   * otherwise `now - SKEW`    — the tolerance a trailing peer clock actually needs.
    /// A fresh certificate (notBefore newer than `now - SKEW`) lands exactly on cert.notBefore,
    /// which is Google Messages' own anchor — so this agrees with them wherever their choice is
    /// expressible, and is strictly safer where it is not.
    fn leaf_lifetime_anchor(&self) -> Option<MlsTime> {
        const SKEW: u64 = 24 * 3600;
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs()).unwrap_or(0);
        let floor = self.kp_not_before.map(|t| t.seconds_since_epoch()).unwrap_or(0);
        let want = now.saturating_sub(SKEW).max(floor).min(now);
        // now == 0 only if the clock is before the epoch or unreadable; there is no sane anchor to
        // pick, so fall back to mls-rs's own default rather than asserting a wrong one.
        if now == 0 { None } else { Some(MlsTime::from(want)) }
    }

    /// Load a group, reporting a missing one as NOT_FOUND rather than a generic failure.
    ///
    /// The distinction is the caller's remedy: a group that is not in storage needs a join or a
    /// recovery, not a retry of the same call. Collapsing both into ERR is what made the drive loop
    /// undecidable.
    #[allow(dead_code)]
    fn load(&self, gid: &[u8]) -> Result<Group<ProdConfig>, MlsError> {
        self.client.load_group(gid).map_err(|e| not_found(format!("load: {e:?}")))
    }

    /// The application generation the NEXT `encrypt` will stamp for our own leaf (peek, no advance).
    /// The RCC.16 body header carries this same number as its uint32 (Google Messages increments the
    /// framing counter and `sender_data.generation` in lockstep — captured 2026-07-25: 0,1,2 across
    /// three messages in one epoch), so the caller must frame with it BEFORE encrypting.
    pub fn next_app_gen(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let gen = g.peek_next_key_generation().unwrap_or(0);
        Ok(gen.to_be_bytes().to_vec())
    }

    pub fn encrypt(&self, gid: &[u8], pt: &[u8], message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        // SENDER-RATCHET CACHE: reuse the in-memory group for consecutive sends in the same epoch so the
        // SecretTree ratchet advances correctly (gen 0,1,2,… carry key[0],key[1],…). Reloading per encrypt
        // reset the ratchet secret to gen-0 while the counter persisted → gen-N got key[0] → peer KEY_GEN.
        let mut cache = self.send_cache.lock().map_err(|_| "send_cache poisoned".to_string())?;
        // Reload if: different gid, OR the on-disk epoch advanced PAST our cached group (a commit was
        // applied out-of-band by process_ex / self_update → new epoch record). This auto-catches forward
        // epoch advances; group RESETS (revive/join/delete/restore, epoch may go DOWN) explicitly
        // invalidate the cache instead.
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
        // ROOT CAUSE of KEY_GENERATION_MISMATCH (wire-confirmed): Google Messages sets the
        // PrivateMessage.authenticated_data = AuthenticatedData{version, message_id, ...} which RFC-9420
        // feeds into the content AEAD as AAD. Stock mls-rs seals with EMPTY AAD → the peer's AEAD auth-fails
        // and skip-aheads the ratchet → surfaces as "key generation mismatch". We now pass the same
        // AuthenticatedData bytes (built in Java from the outgoing message_id, byte-exact from captured
        // peer messages: 00 01 <len><message_id> 00 00 00 03 00) so the AAD matches and gen-0 decrypts.
        {
            let idx = g.current_member_index();
            let epoch = g.context().epoch;
            let gen = g.peek_next_key_generation().unwrap_or(u32::MAX);
            let ea = g.epoch_authenticator().map(|s| {
                s.to_vec().iter().map(|b| format!("{b:02x}")).collect::<String>()
            }).unwrap_or_default();
            alog!("encrypt-diag: self_leaf_index={idx} epoch={epoch} next_app_gen={gen} aad={}B epoch_auth={ea}", aad.len());
        }
        let ct = g.encrypt_application_message(pt, aad.to_vec()).map_err(|e| format!("enc: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("{e:?}"))?;
        ct.to_bytes().map_err(|e| format!("{e:?}"))
    }
    /// The REPEATED-RESULT form of `encrypt` (§11.1a).
    ///
    /// # The rule this exists to make expressible
    ///
    /// §11.1a: **the whole result list must be dispatched before the caller branches on status.**
    /// Interpreting the first element and dropping the rest loses the self-key-update commit that
    /// rides along with the ciphertext — the send succeeds, the key rotation silently does not
    /// happen, and nothing says so.
    ///
    /// Our encrypt returned exactly one ciphertext, so that rule was not merely unimplemented, it
    /// was **unrepresentable**: there was no second element to lose. In its place the host rotated
    /// out of band AFTER the send (`noteSendAndMaybeRekey`, 256 sends per epoch), which has the
    /// failure mode the rule prevents — a rotation that fails is logged and deferred to the next
    /// send, so a group can keep sending indefinitely on a key it meant to retire.
    ///
    /// # Who decides to rotate, and why it is still the host
    ///
    /// Google Messages' engine tracks `encryption_key_usage_level` itself. Ours does not, and inventing
    /// a counter here would put the same decision in two places — the exact drift this project has a
    /// documented history with. So the HOST keeps the counter and passes `want_key_update`, while the
    /// ENGINE guarantees the property that actually matters: both artifacts are produced by one call
    /// and returned together, so a caller cannot take the ciphertext and lose the commit.
    ///
    /// # Ordering
    ///
    /// The ciphertext is produced FIRST, at the current epoch, and only then the commit. Reversing
    /// them would encrypt the message at an epoch the recipient has not yet been told about.
    pub fn encrypt_results(&self, gid: &[u8], pt: &[u8], message_id: &[u8], context_id: &[u8],
            want_key_update: bool) -> Result<Vec<u8>, String> {
        const FORMAT_VERSION: u8 = 1;
        // encrypt() builds the AAD itself now; pass the id straight through rather
        // than building it twice and risking the two disagreeing.
        let ct = self.encrypt(gid, pt, message_id)?;
        let mut records: Vec<Vec<u8>> = vec![vec![FORMAT_VERSION]];
        // status 0 = APPLICATION — the ciphertext to send.
        records.push(join_len_prefixed(&[
            vec![0u8], context_id.to_vec(), gid.to_vec(), Vec::new(), ct,
        ]));
        if want_key_update {
            // DEFER-UNTIL-ACK, and it has to be built HERE.
            //
            // self_update applies the commit LOCALLY before anyone knows the server accepts it —
            // commit_bundle calls apply_pending_commit. A refused commit therefore leaves us
            // epoch-AHEAD forever, after which every send draws INVALID_ARGUMENT. That was
            // device-reproduced on 2026-07-27 and every other commit path in this codebase guards
            // it with a snapshot/rollback pair.
            //
            // The host cannot take that snapshot: the moment it has to be taken is BETWEEN the
            // encrypt and the self-update, and both happen inside this one call. So it is taken
            // here and handed back as a third record; the host restores it if the commit is refused.
            // Restoring rewinds to the state immediately AFTER the ciphertext was produced, so the
            // generation that ciphertext consumed stays consumed — rolling back further would
            // reissue it and put two messages on one generation.
            let rollback = self.export_group_snapshot(gid).unwrap_or_default();
            match self.self_update(gid, message_id) {
                Ok(bundle) => {
                    let parts = split_len_prefixed(&bundle);
                    // commit_bundle packs [welcome, commit, groupInfo, tag, gid, tree]; the commit
                    // is slot 1 and is the only part a send path needs.
                    if let Some(commit) = parts.get(1) {
                        if !commit.is_empty() {
                            // status 1 = COMMIT, carrying the commit bytes as its payload.
                            records.push(join_len_prefixed(&[
                                vec![1u8], context_id.to_vec(), gid.to_vec(), Vec::new(),
                                commit.clone(),
                            ]));
                            // status 3 = OTHER, carrying the PRE-commit snapshot. Emitted only
                            // alongside a commit, because it is meaningless without one.
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
        // Clear FIRST, exactly as process_ex does. LAST_AAD is a thread-local that only process_ex
        // used to touch, so this path could return a STALE AAD left by an earlier process_ex call on
        // the same thread — attributing one message's authenticated_data to another, which is worse
        // than having none.
        LAST_AAD.with(|c| c.borrow_mut().clear());
        LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = None);
        LAST_SENDER_MSISDN.with(|c| c.borrow_mut().clear());
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let pre_epoch = g.context().epoch;
        let msg = MlsMessage::from_bytes(wire).map_err(|e| format!("{e:?}"))?;
        let res = g.process_incoming_message(msg).map_err(|e| format!("process: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("{e:?}"))?;
        // An INBOUND commit advances the epoch exactly as one of ours does, and the send cache
        // holds a SEPARATE pre-commit Group. Leaving it stale means the next encrypt uses it and
        // write_to_storage()s it, REGRESSING the persisted epoch back over the commit we just
        // applied — after which every send draws KEY_GEN and the peer's messages fail
        // EpochNotFound, i.e. the conversation dies in both directions. process_ex has carried this
        // guard since it was device-observed; this path never got it despite being live on
        // MlsSession.process.
        if g.context().epoch != pre_epoch {
            self.invalidate_send_cache();
        }
        // Surface the AAD for the host's RCC.16 §7.5.3.1 message-id equality check.
        //
        // THIS PATH IS THE PRODUCTION INBOUND ONE — MlsProviderTransport.decryptInbound calls
        // process(), not process_ex — and it did not populate LAST_AAD. So lastInboundAad() was
        // ALWAYS empty there, and aadMessageIdMatches() fail-opens on an empty AAD ("nothing
        // asserted"). The check that exists to stop a ciphertext being replayed under a different
        // transport message-id therefore never compared anything on the path that matters.
        //
        // Found 2026-07-31 by dumping a real peer's inbound AAD and getting len=0 for a message
        // that decrypted perfectly well.
        if let ReceivedMessage::ApplicationMessage(ref m) = res {
            LAST_AAD.with(|c| *c.borrow_mut() = m.authenticated_data.clone());
                    note_message_id_check(&m.authenticated_data);
            // WHO SIGNED IT. sender_index is authenticated by the decrypt; the envelope
            // the host would otherwise believe is not.
            let who = Self::certified_msisdn_of(&g, m.sender_index);
            LAST_SENDER_MSISDN.with(|c| *c.borrow_mut() = who);
        }
        Ok(match res { ReceivedMessage::ApplicationMessage(m) => m.data().to_vec(), _ => Vec::new() })
    }

    // ---- S2 group-mutation ops (us-as-actor) + resync + delete + status-tagged process ----

    /// Add a member to an existing group. Returns the 6-record bundle
    /// `[welcome, commit, groupInfo, tag, gid, tree]` (same order as create_group). Optimistic apply.
    pub fn add_member(&self, gid: &[u8], peer_kp: &[u8], message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        // A commit advances the epoch out-of-band of the send cache; a stale cached group
        // would keep encrypting (and write_to_storage-ing) at the PRE-commit epoch, so drop it.
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let wrapped = if peer_kp.starts_with(&MLS_KEYPACKAGE_MSG_PREFIX) {
            peer_kp.to_vec()
        } else {
            wrap_key_package(peer_kp)
        };
        let kp = MlsMessage::from_bytes(&wrapped).map_err(|e| format!("kp_from_bytes: {e:?}"))?;
        // authenticated_data as in self_update: Tachyon rejects a membership commit with an EMPTY AAD
        // ("Could not parse AAD: []") exactly as it does a rekey — device-proven 2026-07-27, when
        // add_member had no aad parameter at all.
        let commit = g.commit_builder().add_member(kp).map_err(|e| format!("add: {e:?}"))?
            .authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        commit_bundle(&mut g, commit)
    }

    /// Add EVERY key package in ONE commit — §14.1/§15.2.
    ///
    /// # Why one commit and not N
    ///
    /// A participant is a PERSON and a person can have several devices: N devices means N key
    /// packages and **N leaves**. Adding them one at a time is not merely slower — each commit
    /// advances an epoch that every existing member must apply, so adding a two-device participant
    /// in two commits creates an intermediate epoch in which that participant is **half-added**.
    /// Their second device is not yet in the group, cannot decrypt what is sent at that epoch, and
    /// the sender has no way to know. It also burns an era per device on the paths where an add
    /// triggers an era advance.
    ///
    /// This is the exact mirror of `remove_member_by_msisdn`, which already removes all of a
    /// participant's leaves in one commit for the same reason — and the two must stay symmetric or
    /// a roster can be assembled by one rule and taken apart by another.
    ///
    /// # Refuses an empty list rather than committing nothing
    ///
    /// An empty add would build a commit that changes no membership, advance the epoch, and report
    /// success — a "successful" add that added nobody. The caller has a bug; say so.
    pub fn add_members(&self, gid: &[u8], peer_kps: &[Vec<u8>], message_id: &[u8])
            -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        if peer_kps.is_empty() {
            return Err("add_members: no KeyPackages — an empty add would advance the epoch and \
                        add nobody".to_string());
        }
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let mut builder = g.commit_builder();
        for (i, peer_kp) in peer_kps.iter().enumerate() {
            let wrapped = if peer_kp.starts_with(&MLS_KEYPACKAGE_MSG_PREFIX) {
                peer_kp.clone()
            } else {
                wrap_key_package(peer_kp)
            };
            let kp = MlsMessage::from_bytes(&wrapped)
                .map_err(|e| format!("kp_from_bytes[{i}]: {e:?}"))?;
            // ONE add_member call per package on the SAME builder — that is what produces one
            // commit with N Add proposals, and therefore ONE Welcome covering every new leaf
            // (RFC 9420). Rebuilding the builder per package would produce N commits.
            builder = builder.add_member(kp).map_err(|e| format!("add[{i}]: {e:?}"))?;
        }
        alog!("add_members: {} KeyPackage(s) in ONE commit for {}", peer_kps.len(), gid_str(gid));
        let commit = builder
            .authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        commit_bundle(&mut g, commit)
    }

    /// Remove another member (by their signature public key; empty = the sole non-self member, 1:1).
    /// Returns the 6-record bundle with an EMPTY welcome. NEVER self-remove (MLS forbids it).
    /// The certified MSISDN of a roster leaf, or empty when it has none.
    ///
    /// Reads the SAME field the A.4.1 claim-time check reads (`leaf_san_msisdn`), so "who the KDS
    /// certified this leaf as" has one definition on both the outbound and inbound sides. An empty
    /// answer is UNKNOWN, not a mismatch — a non-X.509 credential or a SAN without a `tel:` entry
    /// lands here, and refusing on it would drop traffic from anyone whose credential shape we
    /// simply cannot read.
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

    /// Remove EVERY leaf belonging to an MSISDN, in ONE commit (§15.2).
    ///
    /// # Why the selector is an MSISDN and not a leaf
    ///
    /// A participant is a PERSON, and a person can have several devices — N devices means N key
    /// packages and N leaves in the group. `remove_member` selects ONE leaf by its signature key, so
    /// removing a two-device participant removed one of their devices: the person is reported
    /// removed, disappears from the roster the UI renders, and **their other device keeps receiving
    /// every message**. There is no worse shape for a removal to fail in — it fails silently, in the
    /// direction of continued access, and the UI actively says otherwise.
    ///
    /// §15.2 is explicit that the selector has no client-id arm: removal means "all leaves of
    /// this MSISDN". So this REPLACES the per-leaf selector rather than extending it.
    ///
    /// ONE commit, not N: each commit advances an epoch and every member must apply every one, so
    /// removing three devices in three commits is three chances for a peer to fall behind — and the
    /// intermediate epochs are states in which the participant is partially removed.
    pub fn remove_member_by_msisdn(&self, gid: &[u8], msisdn: &[u8], message_id: &[u8])
            -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
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
            // NOT an error that should read as "removal failed to apply". An MSISDN with no leaves
            // is already absent, and the caller's intent is satisfied.
            return Err(format!("remove_by_msisdn: no leaf certified to {} in this group",
                String::from_utf8_lossy(msisdn)));
        }
        alog!("remove_member_by_msisdn: removing {} leaf/leaves for {} in ONE commit",
            targets.len(), String::from_utf8_lossy(msisdn));
        let mut builder = g.commit_builder();
        for idx in &targets {
            builder = builder.remove_member(*idx).map_err(|e| format!("rm {idx}: {e:?}"))?;
        }
        let commit = builder.authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        commit_bundle(&mut g, commit)
    }

    /// The DER of the leaf certificate inside a `SigningIdentity`, or `None` for a non-X.509
    /// credential (our own lab basic credentials, and anything a future profile adds).
    fn leaf_der_of(id: &SigningIdentity) -> Option<Vec<u8>> {
        match id.credential {
            mls_rs_core::identity::Credential::X509(ref chain) =>
                chain.leaf().map(|l| l.as_ref().to_vec()),
            _ => None,
        }
    }

    /// Does OUR leaf in this group certify a DIFFERENT certificate from the one the client holds?
    ///
    /// The comparison is over the leaf certificate's DER, which is the only thing that actually
    /// moves on a re-mint: the subject key is derived deterministically from a stable secret, so a
    /// renewal produces the same signature key and a new certificate. Comparing keys would report
    /// "unchanged" for every renewal there has ever been.
    ///
    /// `false` when either side is not X.509, or when we cannot find our own leaf: rotating a
    /// credential we cannot compare would be a change made on no evidence, and the operation this
    /// gates (a Commit) is not free.
    fn credential_differs_from_group(&self, g: &Group<ProdConfig>) -> bool {
        let ours = match Self::leaf_der_of(&self.signing_identity) { Some(d) => d, None => return false };
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

    /// OUR OWN leaf in this group, compared against the certificate the client currently holds —
    /// the fact the certificate-driven Self-Update is decided from.
    ///
    /// `member_validity` above reports the WHOLE roster keyed by leaf index and says nothing about
    /// which index is ours, so the host could not ask "is the group's copy of MY credential the one
    /// I hold?" without matching MSISDNs across two separate calls and hoping they agree. This
    /// answers it from the one place where both certificates are in hand at once.
    ///
    /// Wire (37 bytes, fixed): `[u32 leaf_index BE][u64 group_nb][u64 group_na][u64 client_nb]
    /// [u64 client_na][u8 stale]`. A window that will not parse is reported as `0/0` exactly as
    /// `member_validity` does — never skipped, because "we could not read it" and "it is fine" must
    /// not be the same answer. `stale` is 1 when the group's leaf certificate DER differs from the
    /// client's, i.e. when a Self-Update would carry a new credential in.
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

    /// Every member's certificate validity window — the input the §9.7 expiry refresh needs.
    ///
    /// <b>Why this exists.</b> `MlsConversationRecord` has carried row 16 (`memberValidity`, leaf
    /// index → window) since the record was defined, and NOTHING has ever written it: on a live
    /// device it reads `memberValidity=0`. Google Messages' maintenance refresh is driven by exactly
    /// this ("Expired members count: {:?}, for group: {:?}" / "Member with client ID: {:?} needs key
    /// rotation"), so the policy that decides whether to refresh (`MlsMaintenancePolicy`) had a
    /// parameter no caller could supply. This is that caller's data source.
    ///
    /// Returns a packed, fixed-width table so the JNI layer stays a byte copy:
    /// `[u32 leaf_index BE][u64 not_before BE][u64 not_after BE]` per member, 20 bytes each.
    ///
    /// A member whose credential is not X.509, or whose leaf will not parse, is reported with
    /// `not_before = not_after = 0` rather than skipped. Skipping would make "no expired members"
    /// and "we could not read this member" indistinguishable, and the second must never be silently
    /// counted as the first — that is the shape of an error that hides a stale roster.
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

    /// WHO COMMITTED the epoch this GroupInfo describes — fork attribution.
    ///
    /// RFC 9420 §12.4.3.3: `GroupInfo.signer` is the LeafIndex of the member whose Commit produced
    /// this epoch. Read from the GroupInfo BYTES ALONE — no group loaded, nothing mutated. Resolve
    /// the index through `tree_member_validity`'s per-leaf MSISDN table and the committer is named.
    ///
    /// <b>Why this beats matching certificate windows.</b> A (notBefore, notAfter) pair identifies a
    /// CERTIFICATE, not a member: all three lab lines re-minted within 36 minutes on 2026-08-09 and
    /// therefore share triples, which is the aliasing trap that forced retracting an identification
    /// over. The signer index is an identity the server itself asserts.
    ///
    /// <b>Read it for exactly what it says, and the distinction is not pedantic.</b> It names the
    /// member that SIGNED THIS GroupInfo. For the anchor the SERVER stores that is the member whose
    /// Commit produced the epoch, which is the fork-attribution question; for a GroupInfo this
    /// device just generated it is simply us. So it is evidence about which side of a fork the
    /// server descends from ONLY when the bytes came from the server, and it speaks to the CURRENT
    /// epoch rather than to any member's whole lineage.
    ///
    /// `mls-rs` exposes this without a fork patch: `GroupInfo.signer` is `pub(crate)`, but
    /// `GroupInfo::sender()` is public and returns exactly `*self.signer`. The in-crate helper
    /// `rcs_debug_validate_group_info` resolves the leaf too and therefore needs a LOADED group,
    /// which is the wrong shape for a measurement.
    ///
    /// <b>THE EPOCH TRAVELS WITH THE SIGNER, and it is what makes the signer readable at all.</b>
    /// A signer index is only evidence about a fork if you know WHICH EPOCH's GroupInfo it signed.
    /// The provider fetches this pack ANCHORED AT OUR OWN era and epoch authenticator, and its own
    /// source notes the returned bundle's authenticator equals the anchor we asked with — so a reader
    /// cannot assume the bundle describes the server's CURRENT epoch rather than the one we asked
    /// from. Returning the GroupInfo's own `GroupContext.epoch` lets the caller check that against
    /// the era/epoch look instead of assuming: equal to the server's epoch, the signer is the
    /// current committer; equal to OURS, it is our own anchor handed back and says nothing about
    /// anyone else.
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

    /// Every leaf's certificate window in a SERIALIZED RATCHET TREE — the SERVER's copy, read
    /// without loading, joining or mutating anything.
    ///
    /// <b>Why this exists and `member_validity` cannot answer it.</b> That one calls
    /// `load_group(gid)`, so it reports the window in the copy THIS DEVICE holds. The rule is that
    /// success is measured on the group rather than on the device, and we have measured a device at
    /// epoch 1 against a server at epoch 3 and epoch 24 — so the local answer can describe a copy
    /// the server does not hold, in either direction. `GetMlsGroupInfo` already returns the server's
    /// ratchet tree; nothing parsed it. The alternative, claiming a peer's KeyPackage, spends one
    /// from their pool and measures their PUBLISHED POOL rather than their IN-GROUP LEAF — the
    /// conflation to avoid.
    ///
    /// READ-ONLY BY CONSTRUCTION. `ExportedTree::from_bytes` parses; `join_with_tree` and
    /// `external_commit_resync` take the same bytes but mutate, and must not be reached for a
    /// measurement.
    ///
    /// Wire, per leaf: `[u32 index BE][u64 not_before BE][u64 not_after BE][u32 msisdn_len BE]
    /// [msisdn]`. The MSISDN is carried because an index alone cannot answer either question worth
    /// asking — "is MY leaf in the server's tree at all" and "what is THIS member's window" — and
    /// making the caller match indices across two calls is how the two drift apart.
    ///
    /// A leaf whose credential is not X.509, or which will not parse, is reported with
    /// `not_before = not_after = 0` and an empty MSISDN rather than skipped, exactly as
    /// `member_validity` does — "we could not read it" and "it is fine" must never look alike.
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

    /// Per-leaf `(index, MSISDN, participant-key SPKI)` for every member — the input
    /// `MlsParticipantKeyResync.plan()` has been waiting for.
    ///
    /// plan() must tell a client on the participant's CURRENT key from one on a SUPERSEDED key, and
    /// nothing supplied that: the conversation record stores a TIMESTAMP of the last participant-key
    /// update, and selecting members to REMOVE from a proxy signal is exactly what plan()'s own
    /// guard refuses to do.
    ///
    /// A leaf we cannot read contributes an EMPTY key rather than being skipped. plan() treats an
    /// empty signing key as "we did not look", not as stale, and drops it from the removal set —
    /// so an unparseable leaf costs a missed cleanup, never an unrecoverable removal. Emitting the
    /// leaf with an empty key keeps that decision where it is documented instead of silently
    /// shortening the roster here.
    ///
    /// Wire: `[u32 index][u32 msisdn_len][msisdn][u32 key_len][key]` per member, concatenated.
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

    pub fn remove_member(&self, gid: &[u8], member_sig_pub: &[u8], message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        // A commit advances the epoch out-of-band of the send cache; a stale cached group
        // would keep encrypting (and write_to_storage-ing) at the PRE-commit epoch, so drop it.
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let self_idx = g.current_member_index();
        let members = g.roster().members();
        let target = members.iter()
            .find(|m| m.index != self_idx
                && (member_sig_pub.is_empty()
                    || m.signing_identity.signature_key.as_ref() == member_sig_pub))
            .map(|m| m.index)
            .ok_or_else(|| "remove: target member not found".to_string())?;
        let commit = g.commit_builder().remove_member(target).map_err(|e| format!("rm: {e:?}"))?
            .authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        commit_bundle(&mut g, commit)
    }

    /// Self-update = an IN-PLACE EPOCH advancement (same era, same group_id, epoch++): an empty-proposal
    /// Commit that rotates our leaf and derives a FRESH encryption_secret → a fresh SecretTree → BOTH
    /// sides' application ratchets reset to gen 0. This is Google Messages' usage-limit rekey
    /// (SelfKeyUpdate) AND the fix for a peer's receiver-ratchet CARRYOVER when the group_id is reused
    /// across eras (an era-advancement CREATE reuses the group — "Group already exists, not creating
    /// new group" — and does NOT reset the ratchet; only an epoch advance does).
    ///
    /// It is submitted via ApplyMlsControlMessage, so the GroupInfo MUST be a PLAIN TREE-LESS
    /// `group_info_message(false)` — NOT `external_commit_group_info` (which carries external_pub → the
    /// server demands the ratchet_tree → `field1003{7}` tree-not-found, the earlier failure). The
    /// server reconstructs the post-commit tree from our UpdatePath, so no tree is shipped. Returns the
    /// 6-record bundle [welcome(empty), commit, PLAIN group_info, epoch_auth, group_id, tree(empty)].
    pub fn self_update(&self, gid: &[u8], message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        // A commit advances the epoch out-of-band of the send cache; a stale cached group
        // would keep encrypting (and write_to_storage-ing) at the PRE-commit epoch, so drop it.
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        // §9.5.3, AND THE HALF THAT WAS MISSING. The paragraph above describes an
        // in-place epoch advance; the specification's certificate-update flow additionally requires
        // the UpdatePath's new leaf to carry the NEWLY MINTED credential. mls-rs does NOT do that on
        // its own: with `new_signing_identity` unset, `LeafNode::commit` rotates the HPKE key and
        // KEEPS `self.signing_identity`, so a rekey after a re-mint publishes a brand-new leaf still
        // certifying the OLD certificate. The group then ages on the ORIGINAL mint's clock however
        // often the device re-mints, and once every leaf is inside RCC.16's 30-day remaining-lifetime
        // floor the RCS SPN refuses every Commit into it (A.4.3.1 §1(a)).
        //
        // Comparing the CERTIFICATE rather than the signature key is what makes this fire at all:
        // our subject key is derived deterministically and is STABLE across re-mints, so the key is
        // identical either side of a renewal and only the credential moves.
        //
        // A.4.3.2 §3 is why this is safe on an already-stale leaf: expiry of the EXISTING leaf
        // certificate is deliberately not checked on a Self-Update, so this is the one operation
        // that can rescue a member the group would otherwise refuse.
        let rotate = self.credential_differs_from_group(&g);
        let mut builder = g.commit_builder().authenticated_data(aad.to_vec());
        if rotate {
            builder = builder.set_new_signing_identity(
                self.signer.clone(), self.signing_identity.clone());
            alog!("self_update: CARRYING A NEW CREDENTIAL into the UpdatePath (§9.5.3) — our leaf \
                   in this group certifies an older certificate than the one we now hold");
        }
        // FramedContent.authenticated_data on the Commit = the SAME AuthenticatedData struct Google
        // Messages sets on app messages ({version=1, message_id, era, 00}), built in Java and passed
        // in. mls-rs leaves it empty by default → the server rejects with
        // "Could not parse AAD: []". commit_builder().authenticated_data(aad) fills it.
        let commit = builder.build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let gid_v = g.group_id().to_vec();
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        // PLAIN tree-less GroupInfo for the control message (with_tree_in_extension=false → no tree ext, and
        // group_info_message adds no external_pub) — the server rebuilds the tree from our UpdatePath.
        let ginfo = g.group_info_message(false).map_err(|e| format!("ginfo: {e:?}"))?
            .to_bytes().map_err(|e| format!("ginfo_bytes: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let commit_b = commit.commit_message.to_bytes().map_err(|e| format!("commit_bytes: {e:?}"))?;
        Ok(join_len_prefixed(&[Vec::new(), commit_b, ginfo, tag, gid_v, Vec::new()]))
    }

    /// `self_update`, but publishing a GroupInfo that CARRIES `external_pub`.
    ///
    /// Why this exists: `external_pub` is COMMITTER-produced (RFC 9420 — the committer builds the
    /// GroupInfo, the delivery service just stores and serves it). Every commit we make publishes
    /// `group_info_message(false)`, which omits it, so the GroupInfo the server holds for OUR groups
    /// has no `external_pub` — and a resync EXTERNAL COMMIT against it is not merely refused, it is
    /// unconstructible (`MissingExternalPubExtension`, measured 2026-08-16). The absent extension may
    /// therefore be OUR publication gap rather than a property of the transport.
    ///
    /// The earlier attempt at this failed and the reason is recorded on `self_update`: an
    /// external_pub-carrying GroupInfo made the server demand the ratchet_tree
    /// (`field1003{7}` tree-not-found). The resolution is the one the CREATE path and
    /// `external_commit_resync` already use — GroupInfo stays TREE-LESS (the server rejects an
    /// embedded ratchet_tree extension outright) and the post-commit tree rides as a SEPARATE field.
    /// So this returns the tree in record 6 where `self_update` returns empty.
    pub fn self_update_extpub(&self, gid: &[u8], message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        // The SAME §9.5.3 credential carry as `self_update` — see the long note there. This variant
        // differs only in the GroupInfo it publishes, so a leaf that skipped the rotation here would
        // be a different operation depending on an unrelated experiment's flag.
        let rotate = self.credential_differs_from_group(&g);
        let mut builder = g.commit_builder().authenticated_data(aad.to_vec());
        if rotate {
            builder = builder.set_new_signing_identity(
                self.signer.clone(), self.signing_identity.clone());
        }
        let commit = builder.build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let gid_v = g.group_id().to_vec();
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        // WITH external_pub, WITHOUT an embedded tree — the same pairing external_commit_resync uses.
        let ginfo = g.group_info_message_allowing_ext_commit(false)
            .map_err(|e| format!("ginfo: {e:?}"))?
            .to_bytes().map_err(|e| format!("ginfo_bytes: {e:?}"))?;
        let post_tree = g.export_tree().to_bytes().map_err(|e| format!("export_tree: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let commit_b = commit.commit_message.to_bytes().map_err(|e| format!("commit_bytes: {e:?}"))?;
        alog!("self_update_extpub: gi={}B (external_pub) tree={}B", ginfo.len(), post_tree.len());
        Ok(join_len_prefixed(&[Vec::new(), commit_b, ginfo, tag, gid_v, post_tree]))
    }

    /// Commit the RCC.16 `end_mls` GroupContext extension (§7.11.2.2) — move the conversation to
    /// UNENCRYPTED. After this the client must not send encrypted messages (§9.1.1).
    ///
    /// `set_group_context_ext` REPLACES the whole extension list, so the current extensions are read
    /// and merged: dropping `era` (0xF001) here would silently strip the one extension every send
    /// stamps and every peer validates.
    ///
    /// [`EndMlsOp::RemoveForRevival`] inverts it — the server explicitly accepts a Commit that
    /// removes the tag, which is how a conversation returns to encrypted.
    ///
    /// # THE END-MLS REMOVAL RULE (§9.7m)
    ///
    /// This is one of exactly **two** sites in this crate that may remove `0xF002`; the other is
    /// `create_group_carry` under [`AdvanceEraKind::Revival`]. Both take the intent from the caller.
    /// `end_mls` may be removed **only** by a path whose *purpose* is revival — never as an emergent
    /// property of recovery, group re-creation, a non-revival era advance, a rebuild-from-scratch or
    /// a template copy.
    ///
    /// # Ordering
    ///
    /// The caller must have completed `transition(→ OngoingReviveMls)` **successfully** before
    /// reaching here, and a failed transition must leave `0xF002` in place. That ordering is
    /// enforced on the Java side because that is where the state machine lives — this function
    /// cannot check it, which is exactly why the parameter is an intent rather than a flag.
    pub fn commit_end_mls(&self, gid: &[u8], message_id: &[u8], op: EndMlsOp) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let mut exts = g.context().extensions().clone();
        if op.removes() {
            alog!("commit_end_mls: REVIVAL — removing end_mls (0xF002). This is one of the two \
                   deliberate removal sites permitted by INVARIANT ED-1.");
            exts.remove(ExtensionType::from(crate::rcc16::END_MLS_EXT));
        } else {
            // UNSET, deliberately: this entry point takes an INTENT (`EndMlsOp`), not a cause, so
            // the reason genuinely is not known here — and v4.0 makes UNSET(0) serialise to an
            // EMPTY extension_data, which is a legal EndMlsMetadata saying exactly that. Threading a
            // real reason down from the state machine is still outstanding. Under
            // v3.0 this is byte-identical to what we have always sent.
            exts.set(Extension::new(ExtensionType::from(crate::rcc16::END_MLS_EXT),
                crate::rcc16::end_mls_payload(crate::rcc16::EndMlsReason::Unset)));
        }
        let commit = g.commit_builder()
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

    /// ERA ADVANCE that PRESERVES MEMBERSHIP — RCC.16 §8.3, the peer's `generate_revive_mls_commit`.
    ///
    /// # Why this exists alongside `create_group_multi`
    ///
    /// Our era advance has always been a create: build a FRESH group at era+1 reusing the group id and
    /// re-add every member from a newly claimed KeyPackage. That is Google Messages' OTHER op
    /// (`create_group_with_members` / `CREATE_GROUP_AND_ADD_MEMBERS`), and Tachyon rejects it for an
    /// existing conversation with `mlsError 5` — `MismatchedRcsGroupState`.
    ///
    /// Tracing both ops, the difference is exactly one thing: the create appends
    /// an explicit KeyPackage LIST and so REBUILDS the roster, while the revive takes NO KeyPackage
    /// list and REVIVES the existing membership. Both carry the same conversation context, so context
    /// was never the missing piece. A rebuilt roster produces new leaves — different keys, possibly a
    /// different order, possibly one stale KP — and no longer matches the RCS group state the server
    /// holds. A preserved roster matches by construction.
    ///
    /// That also explains the behaviour that puzzled us for weeks: our era advances were GRANTED when
    /// the rebuilt roster happened to line up and refused when it did not.
    ///
    /// # What this does instead
    ///
    /// Advances the era on the EXISTING group: a GroupContextExtensions commit that replaces 0xF001
    /// and touches nothing else. The ratchet tree, every leaf and every member survive, so there is no
    /// Welcome to send — nobody is being admitted. That is the structural difference from the create
    /// path, and the reason the returned artifact bundle carries an empty Welcome slot.
    ///
    /// `set_group_context_ext` REPLACES the whole extension list, so the current extensions are read
    /// and merged — dropping 0xF003-0xF006 here would draw Tachyon's *other* refusal ("Subject
    /// commitment changed from Some([..]) to None"), trading one rejection for another.
    ///
    /// # ⛔ This shape is ILLEGAL and the function now refuses before building anything
    ///
    /// Everything above is why we BUILT it; §9.2 of the design doc is why it can never work. The peer
    /// carries two dedicated error variants — `GroupContextExtensionProposalChangesEraError` and
    /// `GroupContextExtensionProposalRemovesEraError` — so **a GroupContextExtensions proposal may
    /// neither change nor remove the Era, and the era is immutable for the lifetime of an MLS group
    /// instance.** RFC 9420 makes that proposal the only in-group way to alter a group-context
    /// extension, so an era advance simply *cannot* be a commit; the only way to move the era is to
    /// build a NEW group, which is what their `Creating new group for new era` does and what our
    /// default `ERA_MODE_CREATE` path (`create_group_carry`) has always done.
    ///
    /// That is the explanation for the refusals we measured on 2026-07-29 and recorded as
    /// `ERA_MODE_PRESERVE` / `_CTRL` — they were not a Tachyon quirk to be tuned around.
    ///
    /// It is kept rather than deleted because the two host modes behind
    /// `debug.rcs.mls_era_advance_mode` are the most specific record anyone has of what the
    /// era-advance RPCs reject, and because deleting the export means changing Rust, the C bridge and
    /// four Java layers in lockstep for no behavioural gain. Refusing HERE is strictly better than
    /// the guard catching it later: it costs no server round-trip, the host's `BUILD_FAILED` path
    /// falls back to the legal create, and the reason is in the log instead of an opaque codec error.
    pub fn commit_era_advance(&self, _gid: &[u8], _aad: &[u8], _new_era: u32)
            -> Result<Vec<u8>, String> {
        Err("commit_era_advance: an era advance CANNOT be a GroupContextExtensions commit — the Era \
             (0xF001) is immutable within an MLS group instance (RCC.16 design §9.2; the peer's \
             GroupContextExtensionProposalChangesEraError). Advance by CREATING a new group at era+1 \
             reusing the RCS group id — create_group_carry, i.e. ERA_MODE_CREATE.".to_string())
    }

    /// Commit an ARBITRARY GroupContext extension. Test-only: the point is to put a type on the
    /// group that no production path sets, so the carry deny-list can be shown to preserve a code
    /// point it was never told about — which is the whole difference from the old allow-list.
    #[cfg(test)]
    fn commit_arbitrary_ext_for_test(&self, gid: &[u8], ty: u16, value: &[u8])
            -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let mut exts = g.context().extensions().clone();
        exts.set(Extension::new(ExtensionType::from(ty), value.to_vec()));
        let commit = g.commit_builder()
            .set_group_context_ext(exts).map_err(|e| format!("set_ext: {e:?}"))?
            .build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let ginfo = g.group_info_message(false).map_err(|e| format!("ginfo: {e:?}"))?
            .to_bytes().map_err(|e| format!("ginfo_bytes: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let _ = commit;
        Ok(ginfo)
    }

    /// The body this had before §9.2 explained the refusals, kept compiling behind `cfg(test)` so the
    /// era-immutability guard can be proven to fire against a real group rather than a mock.
    #[cfg(test)]
    fn commit_era_advance_illegal_for_test(&self, gid: &[u8], aad: &[u8], new_era: u32)
            -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        // Merge, never replace: every other extension on this group — the icon/subject keys and
        // commitments especially — has to survive an era advance.
        let mut exts = g.context().extensions().clone();
        exts.set(Extension::new(ExtensionType::from(crate::rcc16::ERA_EXT),
                                new_era.to_be_bytes().to_vec()));
        let commit = g.commit_builder()
            .set_group_context_ext(exts).map_err(|e| format!("set_ext: {e:?}"))?
            .authenticated_data(aad.to_vec())
            .build().map_err(|e| format!("commit: {e:?}"))?;
        g.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
        let gid_v = g.group_id().to_vec();
        let tag = g.epoch_authenticator().map_err(|e| format!("epoch_auth: {e:?}"))?.to_vec();
        let ginfo = g.group_info_message(false).map_err(|e| format!("ginfo: {e:?}"))?
            .to_bytes().map_err(|e| format!("ginfo_bytes: {e:?}"))?;
        // The tree goes out because the server stores it for the conversation; unlike the create path
        // it is the SAME tree as before, one epoch on.
        let tree = g.export_tree().to_bytes().map_err(|e| format!("tree: {e:?}"))?;
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        let commit_b = commit.commit_message.to_bytes().map_err(|e| format!("cb: {e:?}"))?;
        // Slot 0 is the Welcome and is deliberately EMPTY: preserving membership means nobody joins.
        Ok(join_len_prefixed(&[Vec::new(), commit_b, ginfo, tag, gid_v, tree]))
    }

    /// Commit the RCC.16 group icon/subject COMMITMENT extensions (§7.11.4 / §7.11.6, flows §9.7.1.x).
    ///
    /// Pass an empty slice to leave one untouched. The current extension list is read and merged for
    /// the same reason as `commit_end_mls`: `set_group_context_ext` replaces wholesale, and dropping
    /// `era` would strip the extension every send stamps.
    ///
    /// # Why the KEY extensions are not settable here
    ///
    /// `icon_key` (0xF003) and `subject_key` (0xF005) carry the SYMMETRIC KEYS that decrypt the icon
    /// and subject. They are "Welcome Message only", and §9.7.1.4 requires the sender to build a
    /// SEPARATE, SANITISED GroupInfo *without* them for the messaging server. A GroupInfo is signed
    /// over its contents, and mls-rs offers no way to build one from a context subset — so putting a
    /// key into the committed GroupContext here would put it into the GroupInfo we hand the server,
    /// which defeats the encryption the key exists for.
    ///
    /// The COMMITMENTS carry no secret (they are Annex C.1 hashes) and are exactly the extensions the
    /// spec puts on GroupInfo *and* Welcome, so they are implementable today and implemented here.
    /// Key delivery is deliberately left out rather than done unsafely.
    pub fn commit_icon_subject(&self, gid: &[u8], message_id: &[u8],
                               icon_commitment: &[u8],
                               subject_commitment: &[u8]) -> Result<Vec<u8>, String> {
        self.commit_group_metadata(gid, message_id, &[], icon_commitment, &[], subject_commitment)
    }

    /// The RCC.16 §9.7.1.4/§9.7.1.5 metadata commit: KEYS **and** their commitments, together.
    ///
    /// <b>Why the keys must be in here.</b> The commitment (0xF004/0xF006) is a RefHash over the KEY
    /// MATERIAL, and the encrypted icon/subject is encrypted with that key — so the extension is what
    /// BINDS key to ciphertext. Google Messages' own engine refuses a commit without it ("Missing
    /// SubjectCommitment group context extension"), and Tachyon refuses the paired
    /// ChangeGroupProfile as INVALID_ARGUMENT because the ciphertext it carries is unbound. A commit
    /// with the commitment but no key, or a commitment computed over the ciphertext, is the same
    /// failure by a different route — both were ours.
    ///
    /// Pass an empty slice for anything not changing; the current extension list is read and merged,
    /// because `set_group_context_ext` replaces wholesale and dropping `era` would strip the
    /// extension every send stamps.
    ///
    /// <b>On emitting a GroupInfo that carries a key.</b> This deliberately does NOT apply the
    /// Welcome-only refusal that guards the commitment-only path. RCC.16 §9.7.1.4 asks for a
    /// SEPARATE sanitised GroupInfo for the server, which mls-rs cannot build (a GroupInfo embeds the
    /// whole GroupContext, so filtering an extension changes the context every member computed).
    /// Google Messages demonstrably puts the key in the GroupContext, so refusing here means never
    /// implementing the flow at all. The honest position: we emit what the protocol requires and
    /// record that the GroupInfo reaching the server carries the key — which is a real confidentiality
    /// question, not a solved one.
    pub fn commit_group_metadata(&self, gid: &[u8], message_id: &[u8],
                                 icon_key: &[u8], icon_commitment: &[u8],
                                 subject_key: &[u8], subject_commitment: &[u8])
            -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let mut exts = g.context().extensions().clone();
        for (ty, val) in [
            (crate::rcc16::ICON_KEY_EXT, icon_key),
            (crate::rcc16::ICON_COMMITMENT_EXT, icon_commitment),
            (crate::rcc16::SUBJECT_KEY_EXT, subject_key),
            (crate::rcc16::SUBJECT_COMMITMENT_EXT, subject_commitment),
        ] {
            if !val.is_empty() {
                // Frame per type — 0xF003-0xF006 all take an INNER varint, so a 32-byte commitment
                // must land as 33 bytes (`0x20 ‖ hash`). Writing the raw value here gave a
                // byte-different GroupContext, a wrong context hash, and a group rejected by any
                // real peer. See rcc16::ext_encode for the per-type table.
                exts.set(Extension::new(ExtensionType::from(ty),
                                        crate::rcc16::ext_encode(ty, val)?));
            }
        }
        let commit = g.commit_builder()
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

    /// Which GroupContext extension types a serialized GroupInfo carries, as big-endian u16s.
    ///
    /// A probe, not a protocol step: it answers "does the SERVER's GroupInfo actually carry X" from
    /// the bytes rather than from a guess. Written to check for the continuity token before building
    /// anything that depends on receiving one — carrying a token we are never sent would not be a
    /// fix, and a byte-scan for the type number would find false positives inside key material.
    ///
    /// **The type numbers this was written against were mislabelled**; v4.0 §7.11.12 assigns
    /// 0xF010 = the token and 0xF011 = its commitment (we had the pair reversed). It changes what a
    /// negative result MEANS here: the token is Welcome-only, carried solely in the ENCRYPTED
    /// GroupInfo inside a Welcome, so a SERVER GroupInfo is never expected to contain it. The
    /// commitment is the one that "shall be included in all GroupInfo and Welcome messages", so
    /// 0xF011 is what a server GroupInfo would carry — and its absence is the meaningful negative.
    pub fn group_info_ext_types(&self, group_info: &[u8]) -> Result<Vec<u8>, String> {
        let gi = MlsMessage::from_bytes(group_info)
            .map_err(|e| format!("parse: {e:?}"))?
            .into_group_info()
            .ok_or_else(|| "not a GroupInfo".to_string())?;
        let mut out = Vec::new();
        for ext in gi.group_context().extensions().iter() {
            let ty: u16 = (*ext.extension_type()).into();
            out.extend_from_slice(&ty.to_be_bytes());
            // Length too, so an empty extension is distinguishable from a populated one.
            out.extend_from_slice(&(ext.extension_data.len() as u16).to_be_bytes());
        }
        Ok(out)
    }

    /// One extension's VALUE out of a SERIALIZED GroupInfo — the server's copy, not ours.
    ///
    /// `group_info_ext_types` reports only type and length, which is enough to see that an extension
    /// is PRESENT and no help at all in seeing what it SAYS. That gap is why the era defect could not be
    /// narrowed on device: the server accepts an era-advance create (`verdict=0`, and a fresh
    /// `serverGroupId`) and then reports the OLD era, and the one question that separates "the server
    /// ignored the era we asked for" from "the server recorded it but the conversation still points
    /// at the previous group" is what `0xF001` reads on the server's own GroupInfo. We could see the
    /// era extension was there, 4 bytes, and not what was in it.
    ///
    /// Decoded exactly as [`Self::group_ext`] does — the varint-framed types are unframed here, the
    /// bare ones (`0xF001`, `0xF002`, `0xF007`) pass through — so the two are directly comparable.
    /// Comparing a decoded local value against a raw server one is the exact confusion the
    /// `dump_group_extensions` comment already warns about.
    ///
    /// Read-only and takes no group id: it parses the bytes handed to it, so it works for a GroupInfo
    /// belonging to a group this client has never joined.
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

    /// Read an RCC.16 group extension's VALUE (icon/subject key or commitment), empty if absent.
    ///
    /// Returns the DECODED value, not the raw `extension_data`: for the varint-framed types the
    /// inner length prefix is stripped here, so a 32-byte commitment reads back as 32 bytes. Every
    /// caller wants the value — `verifyIconSubject` compares it against a locally computed Annex C.1
    /// hash, and `currentCommitment` feeds it the same way — so decoding at the boundary keeps the
    /// framing entirely inside the engine.
    ///
    /// A malformed payload is reported, not silently returned raw: reading a commitment that does not
    /// decode means the group context is not what we think it is, and quietly handing back the framed
    /// bytes would surface as a commitment mismatch far from the cause.
    pub fn group_ext(&self, gid: &[u8], ext_type: u16) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        match g.context().extensions().get(ExtensionType::from(ext_type)) {
            Some(e) => crate::rcc16::ext_decode(ext_type, &e.extension_data),
            None => Ok(Vec::new()),
        }
    }

    /// True iff the group carries the `end_mls` tag — i.e. encrypted sending is forbidden (§9.1.1).
    pub fn end_mls_present(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let present = g.context().extensions()
            .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_some();
        Ok(vec![if present { 1u8 } else { 0u8 }])
    }

    /// RCC.16 §7.9.2 / §7.11.12 — a GroupInfo carrying the continuity-token COMMITMENT.
    ///
    /// v4.0 requires the commitment in EVERY GroupInfo the client produces. The TOKEN itself is
    /// Welcome-only and does NOT go here — the GroupInfo this returns is the one handed to the
    /// SERVER, and putting a 256-bit group secret in it is the exact confidentiality defect the
    /// v4.0 icon_key/subject_key reclassification exists to fix. See `WELCOME_ONLY_EXTS`.
    ///
    /// `commitment` is computed host-side (`MlsContinuityToken.commitment`) because it needs the
    /// token, which is per-conversation host state that outlives any single MLS group — the whole
    /// point of continuity is that it survives an Era advance, and an Era advance destroys the
    /// group. Passing the finished commitment down keeps the secret out of the engine's storage.
    ///
    /// Gated: under v3.0 this returns the ordinary GroupInfo unchanged, so a caller may invoke it
    /// unconditionally and get the right bytes for whichever revision the transport announced.
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

    /// Read a continuity extension out of a serialized GroupInfo.
    ///
    /// `ty` is 0xF010 (the token, present only in a Welcome's encrypted GroupInfo) or 0xF011 (the
    /// commitment, which v4.0 requires everywhere). Returns the DECODED value — `ext_decode` strips
    /// the inner varint — or an empty vector when the extension is absent.
    ///
    /// Reading is NOT version-gated. A peer that sends us continuity is understood whatever we
    /// announce, which is also how the "does this server do continuity at all" measurement gets
    /// made: the commitment appearing on a server GroupInfo is the decisive positive.
    ///
    /// # It searches BOTH extension lists, and says which one answered
    ///
    /// A serialized GroupInfo carries TWO extension lists — its own (`gi.extensions()`) and the
    /// GroupContext's (`gi.group_context().extensions()`) — and reading the wrong one returns a
    /// clean, confident, wrong `absent`. That is not hypothetical here: a probe recorded the
    /// SAME WRONG ANSWER TWICE for 0xF010 because a probe enumerated the GroupContext list while
    /// the token lives in the GroupInfo's own, and on 2026-09-08 a peer-built Welcome proved the
    /// token present after both earlier reads had called it absent.
    ///
    /// The mirror risk is live for **0xF011**. Our two records disagree about where the pair live
    /// and are exactly swapped (one says token→GroupContext, commitment→GroupInfo; the other says
    /// the reverse). The 0xF010 measurement settled that row in favour of the second — which, if
    /// the pair is swapped as a pair, puts the COMMITMENT in the **GroupContext**, i.e. NOT where
    /// this function used to look. The earlier negative ("0xF011 absent from four server GroupInfos")
    /// was read from `gi.extensions()` alone and is therefore suspect on exactly the grounds that
    /// made the earlier reads wrong twice.
    ///
    /// So rather than move the read and risk being wrong in the other direction, this searches both
    /// and reports which list held it. An absence from here is an absence from the whole GroupInfo,
    /// which is the only kind of absence worth recording.
    pub fn group_info_continuity(&self, group_info: &[u8], ty: u16) -> Result<Vec<u8>, String> {
        let (v, _where) = self.group_info_ext_either_list(group_info, ty)?;
        Ok(v)
    }

    /// [`group_info_continuity`]'s worker: returns the value and WHICH list held it —
    /// `"GroupInfo"`, `"GroupContext"`, or `"absent"`. Split out so the location is assertable in a
    /// host test rather than only inferable from a log line.
    pub fn group_info_ext_either_list(&self, group_info: &[u8], ty: u16)
            -> Result<(Vec<u8>, &'static str), String> {
        let msg = MlsMessage::from_bytes(group_info)
            .map_err(|e| format!("group_info parse: {e:?}"))?;
        let gi = msg.into_group_info().ok_or("not a GroupInfo")?;
        // GroupInfo's OWN list first: that is where 0xF010 is measured to live, so the code point
        // with evidence behind it decides the common case without consulting inference.
        let (val, from) = if let Some(e) = gi.extensions().get(ExtensionType::from(ty)) {
            (Self::decode_ext_for_read(ty, e.extension_data())?, "GroupInfo")
        } else if let Some(e) = gi.group_context().extensions().get(ExtensionType::from(ty)) {
            (Self::decode_ext_for_read(ty, e.extension_data())?, "GroupContext")
        } else {
            (Vec::new(), "absent")
        };
        // SAY WHICH LIST ANSWERED. The host cannot see it — the JNI returns bytes — and without it
        // a positive is ambiguous in exactly the way that has cost this project four wrong answers:
        // "0xF011 = 32B" does not say whether the old GroupInfo-only read would have found it, so it
        // cannot distinguish "the commitment lives in the GroupContext" from "this group has one and
        // the earlier four did not". One line here settles it at the moment of the read instead of
        // by argument afterwards.
        alog!("GI-EXT-READ 0x{ty:04X} -> {} ({} bytes) [which list answered; 'absent' means neither]",
              from, val.len());
        Ok((val, from))
    }

    /// Decode for a READ. Continuity code points carry an inner varint that `ext_decode` strips;
    /// everything else (this function is also used as an arbitrary-type GroupInfo extension reader
    /// for 0x0004 external_pub and 0x0005 external_senders) is returned raw, because `ext_decode`
    /// refuses a type it does not own and a refusal here would read as "absent".
    fn decode_ext_for_read(ty: u16, data: &[u8]) -> Result<Vec<u8>, String> {
        if ty == crate::rcc16::CONTINUITY_TOKEN_EXT
            || ty == crate::rcc16::CONTINUITY_TOKEN_COMMITMENT_EXT {
            crate::rcc16::ext_decode(ty, data)
        } else {
            Ok(data.to_vec())
        }
    }

    /// RCC.16 §7.6.2 — SIGN: produce the `rcs_signature` PublicMessage over `derived_content`.
    ///
    /// `derived_content` is the §7.6.3 VerifiableDerivedContent, which becomes the FramedContent's
    /// `authenticated_data`. The caller Base64s the returned MLSMessage into the CPIM
    /// `MLS-Derived-Content-Signature` header.
    ///
    /// Uses the fork's `rcs_signature_message`, NOT `propose_custom`: the latter caches the proposal,
    /// which would make `commit_required()` true and get a signature committed as a group operation.
    pub fn rcs_sign(&self, gid: &[u8], derived_content: &[u8]) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        // AN rcs_signature BODY MUST BE EMPTY, AND THE LIBRARY WILL NOT TELL YOU OTHERWISE.
        //
        // Upstream's codec is ASYMMETRIC for this type (proposal.rs:335-348, gsma_rcs_e2ee_feature):
        //   encode  writes `data` BARE, whatever its length
        //   decode  returns Ok(Vec::new()) for RCS_SIGNATURE WITHOUT CONSUMING ANY BYTES
        // So a non-empty body would encode happily, decode to nothing, and leave its bytes in the
        // reader to be misread as the NEXT field — corrupting the rest of the message rather than
        // failing. That is the same class as the stray length prefix this feature just fixed,
        // and it fails in the same silent direction.
        //
        // The body is empty by construction below, so this can only ever fire on a future edit —
        // which is exactly who it is for. Upstream does not guard the invariant, so we do. The
        // SIGNATURE rides in `derived_content` (the AuthenticatedData), never in the proposal.
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

    /// RCC.16 §7.6.2 — VALIDATE: verify an inbound `rcs_signature` PublicMessage and return the
    /// `authenticated_data` it was signed over, so the caller can compare it against the
    /// VerifiableDerivedContent recomputed from the received CPIM.
    ///
    /// Returns `[u32 BE leaf_index][derived_content]` — the signer's leaf matters: a valid signature
    /// from the WRONG member is not a valid receipt for that member's message.
    ///
    /// Empty result = signature or type check failed. Never returns the data unverified.
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

    /// True iff the group holds a cached BY-REFERENCE proposal that must be committed before we may
    /// encrypt another application message (mls-rs `commit_required`).
    ///
    /// RCC.16 self_remove is by-reference and the LEAVER cannot commit it (mls-rs enforces
    /// `OnlyMembersCanCommitProposalsByRef` + "Committer can not remove themselves"), so a remaining
    /// member has to sweep it into their next commit. Ignoring a
    /// pending proposal also means encrypting at a state the group has already been asked to leave.
    pub fn commit_required(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        Ok(vec![if g.commit_required() { 1u8 } else { 0u8 }])
    }

    /// Snapshot the group's persisted STATE for a defer-until-ACK rollback: returns
    /// `[max_epoch_id u64 BE ++ s_blob]`. Capture this BEFORE an optimistic `self_update`; if the
    /// self-update is REJECTED, `restore_group_snapshot` reverts it so our local epoch never
    /// drifts ahead of the server (a rekey that applies + persists locally but that the server
    /// never accepted leaves us stuck epoch-ahead).
    pub fn export_group_snapshot(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        let store = FileGroupStateStorage::new(self.storage_dir.clone());
        let max = store.current_max_epoch(gid);
        let state = store.export_state(gid).ok_or_else(|| "no persisted state".to_string())?;
        let mut out = Vec::with_capacity(8 + state.len());
        out.extend_from_slice(&max.to_be_bytes());
        out.extend_from_slice(&state);
        Ok(out)
    }

    /// Restore a snapshot from `export_group_snapshot` — revert an un-ACKed self-update. Rewrites the
    /// state blob and deletes epoch records newer than the snapshot's max epoch. Returns `[1]`.
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

    /// SELF_LEAVE (fgxc=15): propose our OWN removal — a by-reference `SelfRemoveProposal` MlsMessage.
    /// MLS forbids a member committing its own Remove, so this is a PROPOSAL the OTHER member (or the
    /// server) acts on. Returns the 6-record bundle [welcome(empty), PROPOSAL (in the commit slot),
    /// GroupInfo, epoch_auth, group_id, tree(empty)]. A proposal does NOT advance the epoch, so the
    /// CURRENT epoch_authenticator is both the base and the post value, and the GroupInfo is
    /// the current group's. The caller ships it via the SELF_LEAVE op so the server relays it + the
    /// peer removes us — used to empty a 1:1 group (both members leave) so the server GCs it →
    /// GetMlsGroupInfo NOT_FOUND → a fresh establish mints a clean group_id.
    pub fn self_leave(&self, gid: &[u8], message_id: &[u8]) -> Result<Vec<u8>, String> {
        let aad = &self.aad_for(gid, message_id, &[])[..];
        // Caching a proposal changes commit_required(), and the cached Group predates it — so a
        // cached sender would keep encrypting application messages that mls-rs should be refusing
        // until the proposal is committed.
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

    /// Drop every by-reference proposal cached for commit on this group.
    ///
    /// <b>Why this has to exist.</b> A cached by-reference proposal blocks application messages until
    /// it is committed — mls-rs's own `commit_required()` contract, which we honour on the send path.
    /// So an inbound proposal we CANNOT honour is not merely ignorable: leaving it cached wedges the
    /// conversation permanently, and committing it would consume it while doing nothing (an opaque
    /// CustomProposal changes no group state). Neither is acceptable, which leaves dropping it — the
    /// caller logs loudly, and the removal that a `server_remove` was asking for still happens over
    /// the RCS NOTIFY path that accompanies it (§9.6).
    ///
    /// Deliberately blunt: mls-rs exposes no per-proposal eviction, so this clears the whole cache.
    /// The caller must therefore only reach for it when the cache holds something unhonourable.
    pub fn clear_pending_proposals(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        g.clear_proposal_cache();
        g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
        Ok(vec![1u8])
    }

    /// True iff a claimed peer KeyPackage carries the RFC-9420 `last_resort` extension (0x000A). A
    /// last-resort leaf is a REUSABLE fallback KP the peer publishes when its one-time pool is empty;
    /// per Google Messages' policy a member joined on a last-resort leaf EAGERLY self-updates
    /// (rotates) on every commit it processes until it holds a fresh one-time leaf
    /// (`is_using_last_resort`) — which shows up as perpetual era/epoch churn on the peer. So the caller
    /// should prefer a one-time KP and, ideally, avoid ESTABLISHING with a last-resort one (wait for
    /// the peer to replenish). Accepts the raw KP or the MlsMessage-wrapped form.
    pub fn kp_is_last_resort(&self, kp: &[u8]) -> Result<bool, String> {
        let wrapped = if kp.starts_with(&MLS_KEYPACKAGE_MSG_PREFIX) { kp.to_vec() } else { wrap_key_package(kp) };
        let msg = MlsMessage::from_bytes(&wrapped).map_err(|e| format!("kp_from_bytes: {e:?}"))?;
        let key_package = msg.into_key_package().ok_or_else(|| "not a KeyPackage".to_string())?;
        Ok(key_package.extensions.get(ExtensionType::from(0x000Au16)).is_some())
    }

    /// Everything the CONSUME-side gate needs about a claimed peer KeyPackage, in one parse:
    ///
    /// ```text
    ///   [0]              last_resort (u8, 0/1)
    ///   [1..9]           not_after   (u64 big-endian, seconds since epoch)
    ///   [9..11]          the KeyPackage's OWN cipher_suite (u16 big-endian, RFC 9420 §17.1)
    ///   [11]             n, the count of suites the LeafNode ADVERTISES (u8)
    ///   [12..12+2n]      those suite code points (u16 big-endian each, wire order)
    ///   [12+2n..+8]      the leaf CERTIFICATE's notBefore (u64 big-endian, 0 = not readable)
    ///   [12+2n+8..+8]    the leaf CERTIFICATE's notAfter  (u64 big-endian, 0 = not readable)
    ///   [12+2n+16..]     the leaf certificate's SAN tel: identity, bare E.164 ASCII (may be empty)
    /// ```
    ///
    /// The MSISDN stays LAST because it is the only field with no length of its own; everything
    /// added later must go in front of it.
    ///
    /// <b>Two suite fields, and they fail differently.</b> `[9..11]` is the package's
    /// own `cipher_suite`, which mls-rs itself enforces against the group suite at add time
    /// (`validate_key_package_properties` → `CipherSuiteMismatch`). `[11..]` is the LeafNode's
    /// ADVERTISED set, which RFC 9420 §7.2 requires to contain the group's suite and which mls-rs
    /// 0.55.2 does <em>not</em> check — it validates `required_capabilities`, extensions, proposals
    /// and credentials, but never the suite list. So the advertised set is the half no existing
    /// oracle can see, and the package's own suite is the half that is already guarded; reporting
    /// both means a peer can be refused for the reason that actually applies.
    ///
    /// <b>TWO CLOCKS, and this javadoc used to conflate them.</b> The sentence that
    /// stood here said `not_after` "is what RCC.16 A.4.1.2 / A.4.2.2 impose the ≥30-day floor on".
    /// It is not, and asserting it made an unreachable gate read as an enforced one:
    ///
    ///   * `not_after` (`[1..9]`) is the LeafNode's own RFC 9420 §7.2 `Lifetime.not_after` — what
    ///     `key_package.expiration()` returns, present iff `leaf_node_source == key_package`. On the
    ///     TACHYON profile `kp_lifetime_window` anchors that Lifetime at the CERTIFICATE's
    ///     `notBefore` and runs it a fixed 365 days, so it reads ~363d on a certificate with 73d
    ///     left, and it cannot enter a 30-day floor until the certificate is ~335 days old — which
    ///     a ~75-day certificate never reaches. A floor on this value can never fire here.
    ///   * `cert_not_before` / `cert_not_after` (`[12+2n..+16]`) are the leaf CERTIFICATE's own
    ///     X.509 Validity. This is the clock the SERVER measures: the RCS SPN validates every
    ///     credential in the post-Commit roster at `now + 30d` (A.4.3.1 §1(a)) and
    ///     refuses quoting `Validity { not_before … not_after … }` off the certificate — measured on
    ///     a device on 2026-09-10, three samples.
    ///
    /// Both are returned RAW rather than as a pre-computed "remaining" span, and both for the same
    /// reason: the caller logs the actual expiry, and a floor that silently used a different clock
    /// than the one the log shows would be untraceable — which is exactly what happened.
    ///
    /// `0/0` for the certificate window means NOT READABLE (a non-X.509 credential, or a leaf that
    /// will not parse), never "expired" and never "fine". A caller that refuses on it must treat
    /// `0` as no evidence — see `MlsCredentialFloor.insideFloor`, which does.
    ///
    /// Errors (rather than reporting an infinite lifetime) when the KP has no `key_package` leaf
    /// source — a KP we cannot date must not pass a lifetime floor by default.
    pub fn kp_inspect(&self, kp: &[u8]) -> Result<Vec<u8>, String> {
        let wrapped = if kp.starts_with(&MLS_KEYPACKAGE_MSG_PREFIX) { kp.to_vec() } else { wrap_key_package(kp) };
        let msg = MlsMessage::from_bytes(&wrapped).map_err(|e| format!("kp_from_bytes: {e:?}"))?;
        let key_package = msg.into_key_package().ok_or_else(|| "not a KeyPackage".to_string())?;
        let last_resort = key_package.extensions.get(ExtensionType::from(0x000Au16)).is_some();
        let not_after = key_package.expiration()
            .map_err(|e| format!("expiration: {e:?}"))?.seconds_since_epoch();
        // The leaf's SAN tel: identity, appended as bare E.164 ASCII (possibly empty).
        //
        // A.4.1 requires the SAN to EQUAL the QUERIED MSISDN, and this is the only place in the
        // engine that can serve that check: the number we asked the KDS for exists only at the claim
        // site, one layer up. Returning the certified identity lets the caller compare, rather than
        // the engine guessing which number to expect. See the note at the `expected_msisdn`
        // construction site for why a session-wide expectation is the wrong shape.
        let msisdn = match key_package.signing_identity().credential {
            mls_rs_core::identity::Credential::X509(ref chain) => chain.leaf()
                .and_then(|l| crate::rcc16_validate::leaf_san_msisdn(l.as_ref()))
                .unwrap_or_default(),
            _ => Vec::new(),
        };
        // The leaf CERTIFICATE's own Validity window — the clock the SERVER measures.
        //
        // The bytes are already in hand: the SAN read above parsed this same leaf. What was missing
        // was reporting the window, so the host could only ever compare against the LeafNode
        // Lifetime, which on Tachyon is cert.notBefore + 365d and therefore cannot fall inside a
        // 30-day floor while the certificate lives ~75 days.
        //
        // 0/0 = NOT READABLE, exactly as `member_validity` reports it and for the same reason:
        // "we could not read it" and "it is fine" must not be the same answer.
        let (cert_nb, cert_na) = match key_package.signing_identity().credential {
            mls_rs_core::identity::Credential::X509(ref chain) => chain.leaf()
                .and_then(|l| Certificate::from_der(l.as_ref()).ok())
                .map(|c| (c.tbs_certificate.validity.not_before.to_unix_duration().as_secs(),
                          c.tbs_certificate.validity.not_after.to_unix_duration().as_secs()))
                .unwrap_or((0, 0)),
            _ => (0, 0),
        };
        // The peer's advertised suite set. A KeyPackage that mls-rs decoded can still fail this
        // walk (an unknown credential type has an unknown shape), and that must NOT sink the
        // whole inspection: the lifetime and identity checks above are the gate that already
        // works, and an empty list reads downstream as "peer suites unknown", which is exactly
        // what an unreadable advertisement means. It is logged so the silence is attributable.
        let suites = match leaf_cipher_suites(&wrapped) {
            Ok(s) => s,
            Err(e) => {
                alog!("kp_inspect: leaf capabilities.cipher_suites unreadable ({e}) — reporting \
                    NO advertised suites, which the host must treat as UNKNOWN rather than as \
                    'the peer does not support our suite'");
                Vec::new()
            }
        };
        // u8 count: RFC 9420 permits a longer list, but a real leaf advertises a handful (Google
        // Messages advertises four) and truncating at 255 keeps the field fixed-width. Truncation is
        // announced rather than silent.
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
        // IN FRONT OF THE MSISDN, per the layout note above. It also gives the host a reliable
        // discriminator against an older `.so`: a Unix second is under 2^40, so the top THREE bytes
        // of each of these u64s are 0x00, and 0x00 never appears in the ASCII E.164 that used to
        // start here. The unreadable case (0/0) satisfies the same test.
        out.extend_from_slice(&cert_nb.to_be_bytes());
        out.extend_from_slice(&cert_na.to_be_bytes());
        out.extend_from_slice(&msisdn);
        Ok(out)
    }

    /// External-commit RESYNC (cptg AHEAD/ERA_ADVANCEMENT): join the group from the SERVER's current
    /// GroupInfo with a FRESH leaf (derives new epoch secrets — solves "public GroupInfo has no
    /// secrets"), landing at the server epoch. `ratchet_tree` empty = use the GroupInfo's own tree.
    /// `remove_leaf_index >= 0` = also remove that stale leaf (our old self) in the same commit;
    /// `-1` = plain fresh external join (server/native prunes the old leaf). Returns
    /// `[group_id, external_commit]` — the caller SENDS the external_commit via ApplyMlsControlMessage.
    pub fn external_commit_resync(&self, group_info: &[u8], ratchet_tree: &[u8],
            remove_leaf_index: i64) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        let gi = MlsMessage::from_bytes(group_info).map_err(|e| format!("gi_from_bytes: {e:?}"))?;
        // BUILD BEFORE ANYTHING IS REMOVED.
        //
        // This method used to OPEN by deleting our persisted group at the GroupInfo's group_id:
        //
        //     FileGroupStateStorage::new(..).delete_group(&stale_gid);   // then build
        //
        // The delete was unconditional and the build is not, so every build failure left the device
        // holding NO GROUP for a conversation the server still counts it a member of — strictly
        // worse than the divergence a resync exists to repair, because a diverged member can be
        // re-driven and a member with no group needs a re-Welcome it cannot ask for. Measured as a
        // host test (`a_failed_external_commit_resync_leaves_the_local_group_intact`): against the
        // old ordering the group was simply gone, `export_state` → "no persisted state".
        //
        // WHAT THE DELETE WAS ACTUALLY FOR, and why it can be this much narrower. The recorded
        // reason is InvalidEpoch on a re-join, and the mechanism is one line in mls-rs:
        // `GroupStateRepository::insert` (state_repo.rs) refuses a non-contiguous archive id —
        // `find_max_id() + 1` — and is reached from `apply_pending_commit` INSIDE
        // `ExternalCommitBuilder::build`. That is the ONLY thing `build` reads out of storage:
        // `Group::join_with` constructs the group from the GroupInfo and never loads our state
        // blob, and nothing on the path persists (`insert_past_epoch` pushes into an in-memory
        // `pending_commit`; only our own `write_to_storage` below touches the disk). So a build
        // attempt is storage-PURE, the state blob never needed deleting at all, and the prior-epoch
        // ARCHIVE only needs clearing in the one case where it actually bites.
        //
        // THE ORDER IS THEREFORE: attempt → (only on InvalidEpoch) snapshot, purge the archive,
        // attempt again → restore the snapshot if that second attempt also fails. A failed
        // `external_commit_resync` leaves this group's persisted record byte-identical to what it
        // found. `MlsProviderTransport.resyncViaExternalCommit`'s own snapshot/rollback stays where
        // it is — it also covers the SERVER refusing a commit we did build, which nothing here can
        // see — but it is no longer the only thing standing between a failed repair and an empty
        // device.
        let stale_gid: Option<Vec<u8>> = gi.clone().into_group_info()
            .map(|info| info.group_context().group_id().to_vec());
        let tree = if ratchet_tree.is_empty() { None } else {
            Some(ExportedTree::from_bytes(ratchet_tree).map_err(|e| format!("tree_from_bytes: {e:?}"))?)
        };
        let do_build = |self_remove: bool| -> Result<(Group<ProdConfig>, MlsMessage), String> {
            let mut b = self.client.external_commit_builder()
                .map_err(|e| format!("external_commit_builder: {e:?}"))?;
            // Same leaf-Lifetime anchor as create_group and external_join — a resync mints a new leaf
            // too, and this is the path we RECOVER on, so anchoring it at NOW would make recovery the
            // least reliable step we have.
            if let Some(t) = self.leaf_lifetime_anchor() { b = b.commit_time(t); }
            if let Some(t) = tree.clone() { b = b.with_tree_data(t); }
            if self_remove { b = b.with_self_remove(); }
            b.build(gi.clone()).map_err(|e| format!("{e:?}"))
        };
        // RCS REVIVE (our mls-rs fork): the peer's revive external commit carries a SELF_REMOVE
        // proposal which frees our stale same-identity leaf AND yields the tree their engine
        // reproduces (a plain Remove diverges → "invalid signature"). Our fork adds
        // with_self_remove + resolves the NewMemberCommit SelfRemove target by the committer's identity.
        // Try plain first (fresh join, no stale leaf); on DuplicateLeafData, retry with the SelfRemove.
        let force_self_remove = remove_leaf_index >= 0;
        // ONE ATTEMPT, including the DuplicateLeafData fallback — factored out because the stale-archive
        // recovery has to be able to run the whole thing a second time, and a second hand-written
        // copy of the fallback is how the two flavors drift apart.
        let attempt = |used: &mut bool| -> Result<(Group<ProdConfig>, MlsMessage), String> {
            *used = force_self_remove;
            match do_build(force_self_remove) {
                Ok(x) => Ok(x),
                Err(e) if !force_self_remove && e.contains("DuplicateLeafData") => {
                    *used = true;
                    do_build(true).map_err(|e2| format!("external_commit build (self_remove): {e2}"))
                }
                Err(e) => Err(format!("external_commit build: {e}")),
            }
        };
        // Log WHICH RFC-9420 §12.4.3.2 flavor we actually built: the plain "join" external commit, or the
        // "resync" one carrying the SelfRemove that replaces our prior appearance. Re-entering a group
        // whose tree already holds our leaf REQUIRES the resync flavor (§7.3 leaf uniqueness), and the two
        // are indistinguishable in the server's InvalidProposalTypeForSender rejection — so without this
        // line we cannot tell a vendor policy refusal from our own malformed commit.
        let mut used_self_remove = force_self_remove;
        let (mut g, commit) = match attempt(&mut used_self_remove) {
            Ok(x) => x,
            // THE ONE FAILURE A STALE LOCAL ARCHIVE CAUSES (see the ordering note at the top).
            // Everything else is a property of the GroupInfo, the tree or our own credential, and
            // deleting local state would not change the answer — so nothing is touched for it.
            Err(e) if e.contains("InvalidEpoch") => {
                let Some(sg) = stale_gid.clone() else { return Err(e) };
                let store = FileGroupStateStorage::new(self.storage_dir.clone());
                // The UNDO is the whole record — state and every archived epoch — so restoring it
                // re-derives what was removed rather than trusting a high-water mark. u64::MAX keeps
                // every epoch the snapshot carried; the retain is for the legacy bare-blob form.
                let undo = store.export_state(&sg);
                let dropped = store.purge_epochs(&sg);
                if dropped == 0 {
                    // The archive was not the cause, so there is nothing to recover from and a
                    // second identical attempt would fail identically. Say which of the two this
                    // is: an InvalidEpoch with an EMPTY archive is a different fault and wants a
                    // different investigation.
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
                        // MEASURE THE RESTORE, DO NOT ASSERT IT. `restore_state` returns nothing
                        // and is best-effort: handed a snapshot it cannot decode it leaves the
                        // group exactly as it is and tells no one. Reporting "restored" off the
                        // fact that a void function was called would be a claim that cannot be
                        // false — so the record is read back and compared against the snapshot,
                        // and the three outcomes are named apart.
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
        // Fresh post-commit GroupInfo for the control message's f3. MUST use *_allowing_ext_commit so it
        // carries the ExternalPub extension — a device byte-diff (2026-07-24) showed the server's GroupInfo
        // has a 65-byte P-256 ExternalPub ext and plain group_info_message(false) OMITS it, so the server's
        // recomputed epoch GroupInfo (with ExternalPub) mismatched ours → "invalid signature". Tree
        // stays out (with_tree_in_extension=false; the tree rides separately if needed).
        // GroupInfo tree-less (with_tree_in_extension=FALSE): the server REJECTS an embedded ratchet_tree
        // extension ("Unsupported ratchet_tree extension", code 13 field1003{8}, vc756) — IDENTICAL to the
        // create path. But it ALSO needs the tree to resolve our NEW leaf for the GI-signature check
        // (tree-less → "ratchet tree not found", code 13 field1003{7}, vc754). Resolution mirrors CREATE:
        // GroupInfo carries NO tree; the post-commit ratchet_tree rides as a SEPARATE field (parallel
        // to the create request's f5). We export it below and return it as the 5th artifact.
        let new_gi = g.group_info_message_allowing_ext_commit(false)
            .map_err(|e| format!("gi_msg: {e:?}"))?
            .to_bytes().map_err(|e| format!("gi_bytes: {e:?}"))?;
        // Post-commit ratchet_tree (carries our new leaf0 + the peer at leaf1) for the SEPARATE tree field.
        let post_tree = g.export_tree().to_bytes().map_err(|e| format!("export_tree: {e:?}"))?;
        // RCS revive DEBUG (the decisive test): does our post-commit GroupInfo VERIFY
        // against g's OWN post-commit tree? Ok ⟹ GI.signer key == tree.leaf0 == Commit.UpdatePath.leaf0
        // (identical by construction; the server's InvalidInput is a validation-ORDER issue on its
        // side, not a client GI/Commit key mismatch). Err ⟹ a client inconsistency to fix.
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
        // [group_id, external_commit(=Commit), groupInfo, epoch_auth, ratchet_tree] — the caller sends
        // Commit + GroupInfo(tree-less) + epoch_auth + the SEPARATE post-commit ratchet_tree via
        // ApplyMlsControlMessage, the tree as its own field (mirrors the create request's f5).
        Ok(join_len_prefixed(&[gid, commit_b, new_gi, tag, post_tree]))
    }

    /// The REPEATED-RESULT form of `process_ex` (§10.5).
    ///
    /// # Why a list when there is one result
    ///
    /// `process_ex` returns one status for one message, which makes three things unrepresentable:
    /// a piggybacked commit riding an operation, a result belonging to a DIFFERENT context (which
    /// the host must post-process for effects but must not return), and the carry
    /// function of the §10.5 re-drive, which has nothing to fold a pending-operation id into.
    ///
    /// Today this yields exactly ONE result, because that is what the engine actually produces.
    /// The point is the vocabulary: when the encrypt path starts folding in a self-key-update,
    /// it becomes a second element and nothing above this changes.
    ///
    /// # It WRAPS process_ex rather than reimplementing it
    ///
    /// Deliberate. The classification (APP / COMMIT / PROPOSAL / OTHER / MALFORMED / FUTURE / PAST)
    /// has accumulated hard-won detail — the pre-epoch capture that separates PAST from FUTURE, the
    /// send-cache invalidation that stops an inbound commit regressing the persisted epoch, the AAD
    /// capture ordering. A second copy would drift from it, and "one contract, two implementations"
    /// is the exact failure this codebase has a documented history with.
    ///
    /// The encoding is normatively described in `MlsEngineResult` on the Java side, which is where
    /// it can be host-tested.
    pub fn process_results(&self, gid: &[u8], wire: &[u8], context_id: &[u8])
            -> Result<Vec<u8>, String> {
        const FORMAT_VERSION: u8 = 1;
        let raw = self.process_ex(gid, wire)?;
        // process_ex returns [status, payload...] with the proposal type in bytes 1..3 for status 2.
        let status = *raw.first().unwrap_or(&3u8);
        let (aux, payload): (Vec<u8>, Vec<u8>) = match status {
            2 if raw.len() >= 3 => (raw[1..3].to_vec(), Vec::new()),
            0 => (Vec::new(), raw[1..].to_vec()),
            _ => (Vec::new(), Vec::new()),
        };
        // The group id is echoed from the REQUEST, not read back from the engine. A result that
        // failed to apply has no group to ask, and §10.5's agreement check needs every result in a
        // list to name one — a status-8 result contributing "no group" would make the sole-group
        // rule unanswerable on exactly the lists where the host most needs it.
        let record = join_len_prefixed(&[
            vec![status],
            context_id.to_vec(),
            gid.to_vec(),
            aux,
            payload,
        ]);
        Ok(join_len_prefixed(&[vec![FORMAT_VERSION], record]))
    }

    /// Delete a group's persisted state (AHEAD-discard before resync; PHOENIX reset). Removes
    /// `s_<hexid>.bin` + every `e_<hexid>_*.bin` under the session storage dir. Returns 1 byte: 1=ok.
    pub fn delete_group(&self, gid: &[u8]) -> Result<Vec<u8>, String> {
        self.invalidate_send_cache();
        FileGroupStateStorage::new(self.storage_dir.clone()).delete_group(gid);
        Ok(vec![1u8])
    }

    /// Status-tagged process: `[status, payload...]`. status 0=APP(payload=plaintext), 1=COMMIT,
    /// 2=PROPOSAL, 3=OTHER, 7=FAILED (process error). Every Ok writes storage before returning.
    /// (FUTURE/STALE/REMOVED refinement is S6-follow-up; a failed process → 7 = Java DROPs.)
    pub fn process_ex(&self, gid: &[u8], wire: &[u8]) -> Result<Vec<u8>, String> {
        // Clear first: a stale AAD read after a commit or a failed process would be attributed to
        // the wrong message, which is worse than having none.
        LAST_AAD.with(|c| c.borrow_mut().clear());
        LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = None);
        LAST_SENDER_MSISDN.with(|c| c.borrow_mut().clear());
        let mut g = self.client.load_group(gid).map_err(|e| format!("load: {e:?}"))?;
        let pre_epoch = g.context().epoch;
        // Split the two failure modes so the receive path (and logs) can tell a malformed/wrong-type
        // wire (status 7) from a valid message our engine couldn't APPLY (status 8) — the S6 self-heal
        // commit lands here; we need to know which.
        let msg = match MlsMessage::from_bytes(wire) {
            Ok(m) => m,
            Err(e) => { alog!("process_ex from_bytes FAIL ({}B): {e:?}", wire.len()); return Ok(vec![7u8]); }
        };
        // Capture the message's own epoch BEFORE process_incoming_message consumes it, so an apply
        // failure can be classified PAST (already advanced past → DROP, status 9) vs FUTURE/current
        // (not yet applicable → BUFFER, status 8). Re-buffering a past-epoch commit just re-spams the
        // flush cap.
        let msg_epoch = msg.epoch();
        // §20.4 #21, verbatim — the BEFORE half of the peer's moment pair. Emitted
        // here, at the actual processing site, because the pair's whole value is bracketing one
        // apply: "at moment X" ... "now at moment X+1". Note the rendering is theirs, including
        // the comma-and and epoch-before-era, which read like typos and are measured.
        let pre_era = era_of(&g).unwrap_or(0);
        alog!("Processing a message on group GroupId: \"{}\", at moment GroupMoment {{ Epoch: {}, and Era: {} }}",
              gid_str(gid), pre_epoch, pre_era);
        match g.process_incoming_message(msg) {
            Ok(res) => {
                g.write_to_storage().map_err(|e| format!("write: {e:?}"))?;
                let post_epoch = g.context().epoch;
                // §20.4 #21, the AFTER half.
                alog!("Finished processing a message on group: GroupId: \"{}\", now at moment GroupMoment {{ Epoch: {}, and Era: {} }}",
                      gid_str(gid), post_epoch, era_of(&g).unwrap_or(pre_era));
                // An INBOUND commit advances the epoch just like one of ours does. The send cache holds
                // a separate pre-commit Group; if we leave it, the next encrypt uses it AND
                // write_to_storage()s it, REGRESSING the persisted epoch back over the commit we just
                // applied (device-observed: "COMMIT applied: epoch 1 → 2" then detect reporting
                // local epoch=1 vs server epoch=2, after which every send KEY_GENs and the peer's own
                // messages fail EpochNotFound — i.e. the conversation dies in both directions).
                if post_epoch != pre_epoch {
                    self.invalidate_send_cache();
                }
                let mut out = match &res {
                    ReceivedMessage::ApplicationMessage(_) => vec![0u8],
                    ReceivedMessage::Commit(_) => {
                        alog!("process_ex COMMIT applied: epoch {pre_epoch} → {post_epoch}");
                        vec![1u8]
                    }
                    // Carry the proposal's TYPE out with the status. Every inbound
                    // proposal used to look alike to the caller, which then swept it into a commit.
                    // That is right for SelfRemove (0xF003 — mls-rs implements it natively, so the
                    // commit really does remove the proposer) and WRONG for an RCC.16 custom type we
                    // only advertise: committing an opaque CustomProposal consumes it and tells the
                    // sender it was honoured while nothing happens. The caller cannot tell those apart
                    // without the type, so it goes in bytes 1..3 (big-endian u16); byte 0 is unchanged
                    // for any caller that only reads the status.
                    ReceivedMessage::Proposal(p) => {
                        let t: u16 = p.proposal.proposal_type().raw_value();
                        alog!("process_ex PROPOSAL type=0x{t:04x} cached by-reference");
                        vec![2u8, (t >> 8) as u8, t as u8]
                    }
                    _ => vec![3u8],
                };
                if let ReceivedMessage::ApplicationMessage(ref m) = res {
                    // Surface the AAD for the host's §7.5.3.1 message-id equality check. Captured
                    // before the plaintext is appended, so a caller that reads it sees the AAD of
                    // the very message it is about to be handed.
                    LAST_AAD.with(|c| *c.borrow_mut() = m.authenticated_data.clone());
                    note_message_id_check(&m.authenticated_data);
                    // ...and WHO SIGNED IT, from the same authenticated decrypt.
                    let who = Self::certified_msisdn_of(&g, m.sender_index);
                    LAST_SENDER_MSISDN.with(|c| *c.borrow_mut() = who);
                }
                if let ReceivedMessage::ApplicationMessage(m) = res {
                    // PEER-SEND GROUND TRUTH: log what the PEER stamped for this
                    // application message. sender_data.generation is encrypted on the wire (opened with
                    // the epoch's sender_data_key), so it is only observable post-decrypt — our vendored
                    // mls-rs publishes it via LAST_RECV_*. Dump the decrypted plaintext too: the peer's
                    // MessageContent fields cannot be enumerated from outside, so a real
                    // peer's own bytes are the authoritative answer to whether the payload carries a
                    // per-message generation/moment/sequence field that advances the receiver's expected.
                    use core::sync::atomic::Ordering;
                    let pt = m.data();
                    if mls_rs::group::LAST_RECV_VALID.load(Ordering::SeqCst) {
                        alog!(
                            "recv-diag: sender_leaf={} msg_epoch={} sender_data_generation={} plaintext={}B",
                            mls_rs::group::LAST_RECV_SENDER.load(Ordering::SeqCst),
                            mls_rs::group::LAST_RECV_EPOCH.load(Ordering::SeqCst),
                            mls_rs::group::LAST_RECV_GENERATION.load(Ordering::SeqCst),
                            pt.len());
                        let hex: String = pt.iter().take(512).map(|b| format!("{b:02x}")).collect();
                        alog!("recv-diag: plaintext_hex={hex}");
                    }
                    out.extend_from_slice(&pt);
                }
                Ok(out)
            }
            Err(e) => {
                let past = matches!(msg_epoch, Some(me) if me < pre_epoch);
                alog!("process_ex apply FAIL ({}B) msg_epoch={:?} pre={} → status {}: {e:?}",
                    wire.len(), msg_epoch, pre_epoch, if past { 9 } else { 8 });
                Ok(vec![if past { 9u8 } else { 8u8 }])   // 9 PAST → drop; 8 FUTURE/current → buffer
            }
        }
    }
}

/// Build the 6-record `[welcome, commit, groupInfo, tag, gid, tree]` bundle from a just-built commit
/// (optimistic apply + persist). welcome/groupInfo default-empty when the op has none (remove/update).
fn commit_bundle(g: &mut Group<ProdConfig>, commit: mls_rs::group::CommitOutput) -> Result<Vec<u8>, String> {
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

// ---- C ABI (rcs_mls.h) ----
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
    // Same length-prefixed framing as chain/roots — no new codec for a third repeated-bytes field.
    let revoked_v = split_len_prefixed(slice(revoked, revoked_len));
    match ProdSession::start(slice(leaf, leaf_len), &chain_v, slice(priv_, priv_len),
                             slice(pub_, pub_len), &roots_v, &revoked_v, cstr(storage_dir)) {
        Ok(s) => Box::into_raw(Box::new(s)),
        Err(_) => core::ptr::null_mut(),
    }
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_generate_key_packages(s: *mut ProdSession, count: u32) -> RcsBytes {
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
pub unsafe extern "C" fn rcs_mls_create_group(s: *mut ProdSession, era: u32, kp: *const u8, kp_len: usize,
        gid: *const u8, gid_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    let gid_slice: &[u8] = if gid.is_null() || gid_len == 0 { &[] } else { slice(gid, gid_len) };
    logged_tx("create_group", s, (*s).create_group(era, slice(kp, kp_len), gid_slice).map(|(_g, a)| a))
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
pub unsafe extern "C" fn rcs_mls_join_treeless_welcome(s: *mut ProdSession, w: *const u8, w_len: usize,
        blob: *const u8, blob_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("join_treeless_welcome", s, (*s).join_treeless_welcome(slice(w, w_len), slice(blob, blob_len)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_member_validity(s: *mut ProdSession, gid: *const u8,
        gid_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("member_validity", (*s).member_validity(slice(gid, gid_len)))
}

/// Read-only: the LeafIndex that signed a GroupInfo — who committed this epoch.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_group_info_signer(s: *mut ProdSession, gi: *const u8,
        gi_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("group_info_signer", (*s).group_info_signer(slice(gi, gi_len)))
}
/// Read-only: every leaf's certificate window in a SERIALIZED ratchet tree.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_tree_member_validity(s: *mut ProdSession, tree: *const u8,
        tree_len: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("tree_member_validity", (*s).tree_member_validity(slice(tree, tree_len)))
}
/// OUR leaf's certificate window in this group vs the one the client holds — the resync input.
/// READ-ONLY: it loads the group to look and writes nothing back.
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
pub unsafe extern "C" fn rcs_mls_epoch_auth(s: *mut ProdSession, gid: *const u8, gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    // READ-ONLY: the storage layer refuses any write inside this scope.
    read_only(|| {
        logged("epoch_auth", (*s).epoch_auth(slice(gid, gl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_era_epoch(s: *mut ProdSession, gid: *const u8, gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    // READ-ONLY: the storage layer refuses any write inside this scope.
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
/// Add N members in ONE commit; `kps` is len-prefixed. See `add_members`.
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
    logged_tx("remove_member", s, (*s).remove_member(slice(gid, gl), slice(sig, sigl), slice(aad, al)))
}
/// Remove EVERY leaf certified to one MSISDN, in one commit.
///
/// `remove_member_by_msisdn` has existed here for some time and had **no C export and no Java
/// binding**: it was written, referenced from `MlsSession`'s javadoc as though it were reachable,
/// and never wired. The consequence was not a missing feature but a WRONG one — the group-removal
/// path fell back to `remove_member` with an empty signature key, whose contract is "remove the sole
/// other member", and on a three-member group that removes an arbitrary leaf. Device-observed
/// 2026-08-05: the commit removed someone other than the named member, so the MLS membership no
/// longer matched the RCS roster and Tachyon answered `mismatched-rcs-group-state`.
///
/// Selecting by MSISDN is also the only correct selector for a multi-device participant: it removes
/// ALL of their leaves in ONE commit, where removing one leaf at a time would create an intermediate
/// epoch in which that participant is half-removed.
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
    // The C ABI stays a flag — a u8 across the boundary is the same either way — but it is widened
    // to the intent enum at the first Rust statement, so no Rust code below this line handles a bare
    // "remove" boolean. That is the whole point of EndMlsOp.
    logged_tx("commit_end_mls", s,
        (*s).commit_end_mls(slice(gid, gl), slice(aad, al), EndMlsOp::from_flag(remove != 0)))
}
/// Probe: which GroupContext extension types a serialized GroupInfo carries (u16 type, u16 len each).
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_group_info_ext_types(s: *mut ProdSession, gi: *const u8, gl: usize)
        -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    // READ-ONLY: the storage layer refuses any write inside this scope.
    read_only(|| {
        logged("group_info_ext_types", (*s).group_info_ext_types(slice(gi, gl)))
    })
}
/// One extension's decoded VALUE from a serialized GroupInfo. See `ProdSession::group_info_ext`.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_group_info_ext(s: *mut ProdSession, gi: *const u8, gl: usize,
        ext_type: u16) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    // READ-ONLY: the storage layer refuses any write inside this scope.
    read_only(|| {
        logged("group_info_ext", (*s).group_info_ext(slice(gi, gl), ext_type))
    })
}
/// Membership-PRESERVING era advance (RCC.16 §8.3, the peer's revive). No KeyPackage list, no Welcome.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_commit_era_advance(s: *mut ProdSession, gid: *const u8, gl: usize,
        aad: *const u8, al: usize, new_era: u32) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("commit_era_advance", s, (*s).commit_era_advance(slice(gid, gl), slice(aad, al), new_era))
}
/// RCC.16 metadata commit carrying the KEYS and their commitments.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_commit_group_metadata(s: *mut ProdSession, gid: *const u8, gl: usize,
        aad: *const u8, al: usize, ik: *const u8, ikl: usize, ic: *const u8, icl: usize,
        sk: *const u8, skl: usize, sc: *const u8, scl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("commit_group_metadata", s, (*s).commit_group_metadata(slice(gid, gl), slice(aad, al),
        slice(ik, ikl), slice(ic, icl), slice(sk, skl), slice(sc, scl)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_commit_icon_subject(s: *mut ProdSession, gid: *const u8, gl: usize,
        aad: *const u8, al: usize, ic: *const u8, icl: usize, sc: *const u8, scl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged_tx("commit_icon_subject", s, (*s).commit_icon_subject(slice(gid, gl), slice(aad, al),
        slice(ic, icl), slice(sc, scl)))
}
/// Create a group adding ALL members in the initial commit; `kps` is len-prefixed.
///
/// `mode` is the §9.7g era-advance mode byte: 0 Normal (carry `end_mls` as-is), 1 Revival (drop it),
/// 2 Phoenix (install it). Anything else decodes to Normal, as Google Messages' own decode does.
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
/// Create/advance/add WITHOUT an era argument — the engine decides and reports.
///
/// The signature is the point: there is no `era` parameter, so a host cannot pass one, and the
/// decision has nowhere to live except below this boundary. The returned bundle carries two extra
/// len-prefixed slots after the usual six — `[6] u32 BE era`, `[7] u32 BE welcomeAction`.
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
    // READ-ONLY: the storage layer refuses any write inside this scope.
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
/// Collect the §7.11.12.1 token captured when `gid` was joined from a Welcome.
///
/// Empty is a NORMAL answer (we created the group; the Welcome carried no token; it was already
/// collected), so the host must not read empty as a failure. NOT wrapped in `read_only`: the take
/// mutates this session's hand-off queue, which is process memory and not group storage, and the
/// read-only scope is about refusing STORAGE writes.
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
    // READ-ONLY: the storage layer refuses any write inside this scope.
    read_only(|| {
        logged("end_mls_present", (*s).end_mls_present(slice(gid, gl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_rcs_sign(s: *mut ProdSession, gid: *const u8, gl: usize,
        dc: *const u8, dcl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    // READ-ONLY: the storage layer refuses any write inside this scope.
    read_only(|| {
        logged("rcs_sign", (*s).rcs_sign(slice(gid, gl), slice(dc, dcl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_rcs_verify(s: *mut ProdSession, gid: *const u8, gl: usize,
        m: *const u8, ml: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    // READ-ONLY: the storage layer refuses any write inside this scope.
    read_only(|| {
        logged("rcs_verify", (*s).rcs_verify(slice(gid, gl), slice(m, ml)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_commit_required(s: *mut ProdSession, gid: *const u8, gl: usize)
        -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    // READ-ONLY: the storage layer refuses any write inside this scope.
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
/// As `rcs_mls_self_update`, but PUBLISHES a GroupInfo carrying `external_pub` and ships the
/// post-commit ratchet_tree as the 6th record instead of an empty one.
///
/// Additive on purpose — the plain path is untouched, so this can be enabled and disabled from the
/// host without risking the ordinary commit shape.
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
/// Defer-until-ACK snapshot/restore: capture group state before an optimistic self_update, restore if
/// its control message is rejected (prevents local epoch drift ahead of the server).
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_export_group_snapshot(s: *mut ProdSession, gid: *const u8, gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    // READ-ONLY: the storage layer refuses any write inside this scope.
    read_only(|| {
        logged("export_group_snapshot", (*s).export_group_snapshot(slice(gid, gl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_restore_group_snapshot(s: *mut ProdSession, gid: *const u8, gl: usize,
        snap: *const u8, sl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("restore_group_snapshot", (*s).restore_group_snapshot(slice(gid, gl), slice(snap, sl)))
}
/// 1-byte result: [1]=last-resort KP, [0]=one-time KP. NULL_BYTES on parse error.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_kp_is_last_resort(s: *mut ProdSession, kp: *const u8, kl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    // READ-ONLY: the storage layer refuses any write inside this scope.
    read_only(|| {
        logged("kp_is_last_resort",
            (*s).kp_is_last_resort(slice(kp, kl)).map(|b| vec![if b { 1u8 } else { 0u8 }]))
    })
}
/// Drop all cached by-reference proposals — see `clear_pending_proposals`.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_clear_pending_proposals(s: *mut ProdSession, gid: *const u8,
        gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("clear_pending_proposals", (*s).clear_pending_proposals(slice(gid, gl)))
}
/// 9-byte result: [u8 last_resort][u64 not_after BE]. NULL_BYTES on parse error / undatable KP.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_kp_inspect(s: *mut ProdSession, kp: *const u8, kl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    // READ-ONLY: the storage layer refuses any write inside this scope.
    read_only(|| {
        logged("kp_inspect", (*s).kp_inspect(slice(kp, kl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_external_commit_resync(s: *mut ProdSession, gi: *const u8, gil: usize,
        tree: *const u8, tl: usize, remove_leaf_index: i64) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("external_commit_resync",
        (*s).external_commit_resync(slice(gi, gil), slice(tree, tl), remove_leaf_index))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_delete_group(s: *mut ProdSession, gid: *const u8, gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("delete_group", (*s).delete_group(slice(gid, gl)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_process_ex(s: *mut ProdSession, gid: *const u8, gl: usize,
        wire: *const u8, wl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("process_ex", (*s).process_ex(slice(gid, gl), slice(wire, wl)))
}
/// The repeated-result form. See `ProdSession::process_results`.
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
pub unsafe extern "C" fn rcs_mls_encrypt(s: *mut ProdSession, gid: *const u8, gl: usize, pt: *const u8, pl: usize,
        aad: *const u8, al: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    let aad_slice: &[u8] = if aad.is_null() || al == 0 { &[] } else { slice(aad, al) };
    logged_tx("encrypt", s, (*s).encrypt(slice(gid, gl), slice(pt, pl), aad_slice))
}
/// The generation the next `encrypt` will stamp (4-byte big-endian), for RCC.16 body framing.
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_next_app_gen(s: *mut ProdSession, gid: *const u8, gl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    // READ-ONLY: the storage layer refuses any write inside this scope.
    read_only(|| {
        logged("next_app_gen", (*s).next_app_gen(slice(gid, gl)))
    })
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_process(s: *mut ProdSession, gid: *const u8, gl: usize, w: *const u8, wl: usize) -> RcsBytes {
    if s.is_null() { return NULL_BYTES; }
    logged("process", (*s).process(slice(gid, gl), slice(w, wl)))
}
#[no_mangle]
pub unsafe extern "C" fn rcs_mls_bytes_free(b: RcsBytes) {
    if !b.data.is_null() && b.len > 0 { drop(Box::from_raw(core::slice::from_raw_parts_mut(b.data, b.len))); }
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
    /// Reset every thread-local this module touches.
    ///
    /// MUST be called at the START of each test, not only at the end. cargo's harness reuses
    /// worker threads, so a test can inherit dirty state from an earlier one, and a test that
    /// panics midway never reaches its own cleanup. Clearing only on exit made this module
    /// intermittently fail — observed once before this was fixed.
    fn clear_state() {
        rcs_mls_set_request_message_id(std::ptr::null(), 0);
        rcs_mls_set_next_resent_component(std::ptr::null(), 0);
        LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = None);
    }

    // THE DEFAULT MUST BE SKIP, NOT FAIL. Every call site that has not been migrated
    // supplies no message id, and if that were treated as a mismatch the engine would drop
    // every inbound message the moment this landed. That is the failure mode a
    // half-migrated seam produces, and the reason this check fails open.
    // The §10.3 component probe survives the seam removal: the host supplies a COMPONENT,
    // the engine still owns the AAD around it. And it is ONE-SHOT — a probe component
    // leaking into the next message would silently corrupt ordinary traffic.
    #[test]
    fn a_one_shot_resent_component_is_used_then_cleared() {
        clear_state();
        let comp = [0x02u8, 0x40, 0xAA, 0xBB];
        rcs_mls_set_next_resent_component(comp.as_ptr(), comp.len());
        let first = NEXT_RESENT_COMPONENT.with(|c| c.borrow().clone());
        assert_eq!(first, comp, "component must be staged");
        // Simulate the consume that aad_for performs.
        let taken = NEXT_RESENT_COMPONENT.with(|c| {
            let v = c.borrow().clone();
            c.borrow_mut().clear();
            v
        });
        assert_eq!(taken, comp);
        let after = NEXT_RESENT_COMPONENT.with(|c| c.borrow().clone());
        assert!(after.is_empty(), "must be cleared so it cannot leak into the next message");
        // An AAD built with it carries it in the trailing slot, and parses back out.
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

    // A PREFIX IS NOT A MATCH. Real message ids share long prefixes ("Mx..."), so a
    // startsWith-style comparison would pass mismatched messages.
    #[test]
    fn a_prefix_does_not_count_as_a_match() {
        clear_state();
        set(b"MxCACYGxqfQa");
        let aad = crate::rcc16::build_authenticated_data(b"MxCACYGxqfQa-CnQZ=5XctuQ", 8, &[]);
        note_message_id_check(&aad);
        assert_eq!(rcs_mls_last_message_id_mismatch(), 1);
        clear_state();
    }

    // A MALFORMED AAD IS NOT AN ID MISMATCH. It belongs to the §10/FTD path; reporting it
    // as MessageIdMismatch would attribute a parse failure to the wrong cause.
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
    /// THE SEAM-REMOVAL GUARD.
    ///
    /// The host no longer builds the AuthenticatedData — it passes a MESSAGE ID and the
    /// engine builds the AAD from it plus the group's own 0xF001 era. This test is the
    /// proof that the swap did not move the bytes: it encrypts with a message id, decrypts
    /// on the far side, and asserts the AAD that actually travelled is byte-identical to
    /// what `build_authenticated_data` specifies — which is itself pinned to three real
    /// Google Messages captures.
    ///
    /// If this passes and the Java MlsAuthenticatedDataWireVectorTest passes, then the
    /// engine and the old host builder agree, and nothing a real peer sees has changed.
    #[test]
    fn engine_built_aad_matches_the_wire_format_after_the_seam_removal() {
        // Inherit nothing: see clear_state's note on harness thread reuse.
        rcs_mls_set_request_message_id(std::ptr::null(), 0);
        rcs_mls_set_next_resent_component(std::ptr::null(), 0);
        LAST_ID_MISMATCH.with(|c| *c.borrow_mut() = None);
        let dir = format!("{}/aadseam_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap();

        // The era the ENGINE will read out of 0xF001 — not one the caller supplies.
        let ee = a.era_epoch(&gid_a).unwrap();
        let era = u32::from_be_bytes([ee[0], ee[1], ee[2], ee[3]]);

        const MID: &[u8] = b"MxCACYGxqfQa-CnQZ=5XctuQ";
        let ct = a.encrypt(&gid_a, b"seam", MID).unwrap();
        // process(), not process_ex(): the latter prefixes a status byte, and both populate
        // LAST_AAD identically, so the plain one keeps the assertion about the AAD.
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"seam");

        // What actually travelled, captured by the engine on decrypt.
        let seen = LAST_AAD.with(|c| c.borrow().clone());
        let want = crate::rcc16::build_authenticated_data(MID, era, &[]);
        assert_eq!(seen, want, "the AAD on the wire must be exactly what the format specifies");

        // ...and it must decompose back to the id we handed in, with the group's real era.
        let parsed = crate::rcc16::parse_authenticated_data(&seen).expect("engine AAD must parse");
        assert_eq!(parsed.message_id, MID, "the host's message id must survive to the wire");
        assert_eq!(parsed.era, era, "the era must come from the group, not from a caller guess");
        assert_eq!(parsed.version, 1);
        assert!(parsed.resent_absent(), "an ordinary message carries the component ABSENT");

        // And the §7.5.3.1 check agrees when told the same id, and objects when told another.
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
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        // create_group returns [welcome, commit, groupInfo, tag, gid, tree]. The Welcome is TREE-LESS
        // (our GroupInfo omits the ratchet_tree extension because the server rejects an embedded one),
        // so a plain join() fails with RatchetTreeNotFound — the production path passes the separately
        // exported tree, exactly as routeOpen does for an inbound WelcomeCommitBundle.
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

    /// A FAILED `external_commit_resync` MUST NOT destroy the group it was trying to repair.
    ///
    /// # The defect
    ///
    /// `external_commit_resync` opened by DELETING our persisted group at the GroupInfo's group_id
    /// and only then attempted the build. The delete was unconditional; the build is not. So every
    /// build failure — and there are several, since `ExternalCommitBuilder::build` validates the
    /// tree, the joiner's own credential lifetime and the cipher suite before it produces anything —
    /// left the device holding NO GROUP on a conversation the server still counts it a member of.
    /// That is strictly worse than the divergence the resync exists to repair: a diverged member can
    /// be re-driven, a member with no group needs a re-Welcome it cannot ask for.
    ///
    /// # Why THIS failure, and why it is not a contrived one
    ///
    /// `MissingExternalPubExtension` is the FIRST thing `build` checks (RFC 9420 §12.4.3.2 derives
    /// the ExternalInit from `external_pub`, GroupInfo extension 0x0004), and it is the failure the
    /// groups this lever gets pointed at actually hit — measured 2026-09-11 on `g:b1189d9c`, whose
    /// server GroupInfo is 233 B with `external_pub` ABSENT. A GroupInfo from
    /// `group_info_message_internal` (what `group_info_with_continuity` returns, and what every
    /// commit we publish carries) omits it for the same reason, so the artefact fed in here is the
    /// one the transport really produces rather than a mutilated fixture.
    ///
    /// # What would make this test FAIL
    ///
    /// The pre-fix code deleted `s_<gid>.bin` and every `e_<gid>_*.bin` at the top of
    /// `external_commit_resync`, so `export_group_snapshot` below answers `Err("no persisted
    /// state")` and the `expect` panics. That is not a hypothetical arm: it is the code that shipped
    /// before the fix, and the reason the defect was filed — verified by running this test against it.
    /// The `assert_eq!` on the two snapshots then catches the weaker form, a group that survives
    /// with its prior-epoch archive gone, and the round trip at the end catches a record that is
    /// present but unusable.
    #[test]
    fn a_failed_external_commit_resync_leaves_the_local_group_intact() {
        let dir = format!("{}/dx3d_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap();
        assert_eq!(gid_a, gid_b, "both members must be on the same MLS group id — the defect is \
            that the resync's delete targets the id it reads out of the GroupInfo");

        // Give b a prior-epoch archive to lose: a commit from a that b processes moves b off the
        // epoch it was on, and mls-rs archives the epoch it leaves. Without this the record holds a
        // state blob and no epochs, and the weaker half of the assertion below could not fire.
        let upd = a.self_update(&gid_a, &[]).unwrap();
        let upd_commit = split_len_prefixed(&upd)[1].clone();
        b.process(&gid_b, &upd_commit).unwrap();

        // A GroupInfo WITHOUT external_pub — `group_info_message_internal`, i.e. what every commit
        // we publish carries. Read-only: it loads a's group and serializes, writing nothing.
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

        // Present is not the same as usable: prove the group still works in both directions.
        let ct = a.encrypt(&gid_a, b"still here", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"still here");
        let ct = b.encrypt(&gid_b, b"and still able to answer", &[]).unwrap();
        assert_eq!(a.process(&gid_a, &ct).unwrap(), b"and still able to answer");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Build a group where `b` is BEHIND `a` by several epochs, so `b`'s prior-epoch archive is
    /// non-contiguous with the epoch an external commit would land at — the exact condition the
    /// deleted `delete_group` was protecting against. Returns `(a, b, gid, gi_with_extpub, tree)`.
    ///
    /// The gap is what makes it bite: mls-rs's `GroupStateRepository::insert` refuses an archive id
    /// that is not `max + 1`, a re-join archives the SERVER's epoch, and `b`'s max is whatever it
    /// last processed. One missed commit is enough to make those differ.
    #[cfg(test)]
    fn dx3d_stranded_pair(dir: &str) -> (ProdSession, ProdSession, Vec<u8>, Vec<u8>, Vec<u8>) {
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        assert_eq!(b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap(), gid);

        // ONE commit b follows — this is what gives b a prior-epoch archive at all.
        let upd = a.self_update(&gid, &[]).unwrap();
        b.process(&gid, &split_len_prefixed(&upd)[1].clone()).unwrap();

        // THREE commits b never sees. b's archive now stops several epochs short of a's, which is
        // what makes the re-join's archive id non-contiguous.
        for _ in 0..3 { a.self_update(&gid, &[]).unwrap(); }

        // The GroupInfo an external commit can actually be built from: external_pub present,
        // ratchet_tree carried separately (record 5), exactly as the transport publishes it.
        let pub_upd = a.self_update_extpub(&gid, &[]).unwrap();
        let p = split_len_prefixed(&pub_upd);
        let gi = p[2].clone();
        let tree = p[5].clone();
        assert!(!a.group_info_ext_either_list(&gi, 0x0004).unwrap().0.is_empty(),
            "self_update_extpub must publish external_pub, or this fixture tests nothing");
        (a, b, gid, gi, tree)
    }

    /// The stale prior-epoch archive must still be got out of the way — the other half.
    ///
    /// Removing the up-front `delete_group` is only correct if the one thing it was load-bearing for
    /// still happens. It was load-bearing for exactly one failure (`InvalidEpoch` out of mls-rs's
    /// archive-contiguity check), and the replacement drops the ARCHIVE and only after
    /// the build has actually demanded it. This asserts the resync still succeeds for a member whose
    /// archive is stale — and, through the recovery record, that it succeeded BY that route rather
    /// than by the archive happening to be contiguous, which would make the test pass while
    /// measuring nothing.
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

        // THE ARM, NOT THE OUTCOME. Without this the test would also pass if the archive had been
        // contiguous all along and no recovery had been needed.
        let arm = LAST_RESYNC_STALE_RECOVERY.with(|c| c.borrow().clone());
        assert_eq!(arm.as_deref(), Some("purged 1"),
            "the InvalidEpoch recovery must be what unblocked this; got {arm:?}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// If the retry AFTER dropping the stale archive also fails, the record goes back.
    ///
    /// This is the one arm no black-box input can reach: the two attempts differ only in the
    /// archive, and every other failure `ExternalCommitBuilder::build` can raise is decided before
    /// `apply_pending_commit` and would have sunk the FIRST attempt. So the second failure is
    /// injected. The injection is honest about what it proves and what it does not: it proves the
    /// restore runs and is exact, not that a real second failure exists in the wild.
    ///
    /// WHAT WOULD MAKE IT FAIL: delete the `store.restore_state(..)` line and the archive stays
    /// dropped — `after` then differs from `before` by the purged epoch record. Confirmed by doing
    /// exactly that before quoting this green.
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

        // Exact on disk is not the same as usable: the restored record must still LOAD into a
        // working group. Deliberately b's own send and not a decrypt of a's traffic — b is
        // stranded several epochs behind by construction, so `process` would answer EpochNotFound
        // for reasons that have nothing to do with the restore, and an assertion that fails for
        // the wrong reason is worse than none.
        assert!(!b.encrypt(&gid, b"after the undo", &[]).unwrap().is_empty(),
            "the restored record must load into a group that can still encrypt");
        drop(a);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A serial pushed at `start` REACHES the validator.
    ///
    /// The validator's own revocation logic was already built and unit-tested; what did
    /// not exist was any way for a host to supply the list, which made the whole mechanism dead
    /// code. So the property under test here is the PLUMBING, not the matching: a serial handed to
    /// `ProdSession::start` must end up refusing a chain that carries it.
    ///
    /// Revoking the ICA — not a leaf — because that is the only revocation that means anything
    /// under RCC.16: leaf certificates are deliberately non-revocable, so the intermediate-CA
    /// serial list is the entire lever. Revoking our own issuer is also the sharpest possible test
    /// of reachability: every certificate in play chains through it, so if the list is ignored the
    /// session comes up perfectly and the assertion fails loudly rather than passing vacuously.
    #[test]
    fn a_pushed_revoked_serial_reaches_the_validator() {
        let dir = format!("{}/revoked_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);

        // The control: with an EMPTY list — the normal state in the field — everything works.
        let ok_a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/ok_a")).unwrap();
        let ok_b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/ok_b")).unwrap();
        let bkp = split_len_prefixed(&ok_b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        assert!(ok_a.create_group(1, &bkp, &[]).is_ok(),
            "control: an empty revocation list must change nothing");

        // Now revoke the ICA that issued both leaves, and push it in at start.
        let ica_serial = crate::rcc16_validate::cert_serial(&td("lab2_ica.der"))
            .expect("the test ICA must have a readable serial");
        let a = ProdSession::start(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &[ica_serial],
            &format!("{dir}/rev_a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/rev_b")).unwrap();
        let bkp2 = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();

        // A's validator now refuses B's credential, because B chains through the revoked ICA.
        assert!(a.create_group(1, &bkp2, &[]).is_err(),
            "a serial pushed through ProdSession::start MUST reach the validator — if this \
             passes, the revocation list is being dropped somewhere in the plumbing and the \
             whole mechanism is dead code");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Calling self_leave on a transport that cannot ship a proposal WEDGES the conversation.
    ///
    /// propose_self_remove caches a by-reference proposal and write_to_storage persists it, so
    /// commit_required() goes true and every subsequent application message is blocked. MLS forbids
    /// committing your own removal ("Committer can not remove themselves"), so the sender cannot
    /// clear it either. On Tachyon — where control messages are commit-only and the proposal can never
    /// be sent — that is a permanently silenced conversation from one UI tap.
    #[test]
    fn self_leave_wedges_the_sender_until_the_proposal_is_dropped() {
        let dir = format!("{}/leave_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // Sending works before the leave attempt.
        let ct = a.encrypt(&gid_a, b"before", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"before");

        // A taps "leave". The proposal is cached AND persisted.
        a.self_leave(&gid_a, &[]).unwrap();
        assert_eq!(a.commit_required(&gid_a).unwrap(), vec![1u8],
            "self_leave must leave a cached proposal — that is the wedge");

        // It survives a reload, so this is not in-memory-only.
        let a2 = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        assert_eq!(a2.commit_required(&gid_a).unwrap(), vec![1u8], "the wedge is persisted");

        // Only dropping it restores the conversation; this is what the lever is for.
        a2.clear_pending_proposals(&gid_a).unwrap();
        assert_eq!(a2.commit_required(&gid_a).unwrap(), vec![0u8]);
        let ct = a2.encrypt(&gid_a, b"after", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"after");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A PEER's `self_remove` (0xF003) is swept by our ORDINARY next commit, and the leaver is
    /// actually GONE from the roster.
    ///
    /// <b>This is the engine claim the whole inbound arm rests on and it had no test.</b>
    /// `MlsProviderTransport.onInboundProposal` routes a 0xF003 to a `COMMIT_PENDING_PROPOSALS`
    /// pending operation and `commitPendingProposals` then issues a plain REKEY — a `self_update`,
    /// nothing removal-shaped about it — on the belief that mls-rs sweeps the cached by-reference
    /// proposal into it and the proposer leaves. Everything downstream (the roster we record, the
    /// departure we apply to the conversation) is built on that belief, so it is pinned here.
    ///
    /// Three properties, and the third is the one that would have gone unnoticed:
    ///
    ///  1. the inbound proposal is REPORTED as 0xF003 — the same code point RCC.16 §7.11.8.1 uses,
    ///     which is why no translation layer exists (mls-rs-core names it `SELF_REMOVE`);
    ///  2. an ordinary `self_update` removes the proposer — we never build a Remove;
    ///  3. EVERY REMAINING MEMBER MUST HOLD THE PROPOSAL TOO. It travels by REFERENCE, so the
    ///     commit carries a `ProposalRef` and not the proposal — a member that never saw it cannot
    ///     apply the commit. That is a property of the fan-out, not of the committer, and it is
    ///     asserted here so a future change to the delivery path fails loudly rather than stranding
    ///     whichever member the server skipped.
    #[test]
    fn a_peer_self_remove_is_swept_by_our_next_commit_and_the_leaver_leaves_the_roster() {
        let dir = format!("{}/selfrm_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/b")).unwrap();
        let c = ProdSession::start_no_revocation(&td("lab2_leaf_pc.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pc_priv.bin"), &td("lab2_leaf_pc_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/c")).unwrap();

        // THREE members, so the departure leaves a group behind. A 1:1 cannot exercise this at all:
        // the only member who could commit the leaver's removal is the leaver's sole peer, and once
        // it does the group is a single member — which is why RCC.16's 1:1 "leave" is end_mls.
        let mut kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());
        kps.extend(split_len_prefixed(&c.generate_key_packages(1).unwrap()));
        let (gid_a, artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();
        let gid_c = c.join_with_tree(&parts[0], &parts[5]).unwrap();
        assert_eq!(a.client.load_group(&gid_a).unwrap().roster().members().len(), 3);

        // Who B is, as the ROSTER sees them — the same certified MSISDN the host reads back to
        // decide who left. Read from B's own group so the test does not depend on leaf ordering.
        let bmsisdn = {
            let gb = b.client.load_group(&gid_b).unwrap();
            let me = gb.current_member_index();
            ProdSession::certified_msisdn_of(&gb, me)
        };
        assert!(!bmsisdn.is_empty(), "the fixture leaf must carry a tel: SAN");

        // B leaves. This is the OUTBOUND half — it produces a by-reference proposal and nothing
        // else, which is why B can never complete its own departure.
        let leave = split_len_prefixed(&b.self_leave(&gid_b, b"mls-self-leave").unwrap());
        let proposal = leave[1].clone();
        assert!(!proposal.is_empty(), "self_leave must produce a proposal in the commit slot");

        // (1) A sees it, and sees WHAT it is.
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

        // (3) C must hold it too, or C cannot apply the commit that references it.
        let seen_c = c.process_ex(&gid_c, &proposal).unwrap();
        assert_eq!(seen_c[0], 2u8, "every remaining member caches the same by-reference proposal");

        // (2) An ORDINARY commit — the REKEY arm's self_update, with no removal in it.
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

        // C follows and the group still works for the members who stayed.
        assert_eq!(c.process_ex(&gid_c, &commit).unwrap()[0], 1u8, "C applies the commit");
        let ct = a.encrypt(&gid_a, b"after the leave", &[]).unwrap();
        assert_eq!(c.process(&gid_c, &ct).unwrap(), b"after the leave");

        // And B is genuinely out: the epoch it was removed in is not one it can read.
        assert!(b.process(&gid_b, &ct).is_err(),
            "a removed member must not be able to decrypt traffic from the epoch that removed it");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// `create_group_multi` is the create path, and one KeyPackage still yields the same artifacts.
    ///
    /// <b>N>2 is covered separately</b> by `create_group_multi_adds_three_members_in_one_commit`,
    /// which became possible on 2026-08-08 when the lab chain was regenerated with a third
    /// conformant leaf. This test guards the single-member path.
    #[test]
    fn create_group_multi_is_the_create_path() {
        let dir = format!("{}/multi_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());

        let (gid_a, artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        assert_eq!(parts.len(), 6, "artifact layout must stay [welcome,commit,ginfo,tag,gid,tree]");
        assert_eq!(a.client.load_group(&gid_a).unwrap().roster().members().len(), 2);

        // The Welcome admits the member and the group carries traffic.
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();
        let ct = a.encrypt(&gid_a, b"multi member", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"multi member");

        // An empty member list is a caller bug, not an empty group.
        assert!(a.create_group_multi(1, &[], &[]).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// N>2 IN ONE COMMIT, INCLUDING A SECOND DEVICE OF ONE PARTICIPANT.
    ///
    /// This is the case `create_group_multi` exists for and the one nothing could reach until the
    /// lab chain gained a third conformant leaf. Two things are proven together, and they are worth
    /// separating in your head:
    ///
    /// 1. **Three leaves, ONE epoch.** Tachyon compares the MLS GroupContext against the full RCS
    ///    roster and refuses a smaller MLS group with `mlsError 5`, so the initial commit must carry
    ///    every member. Adding them one at a time would land at epoch 3 and be refused.
    /// 2. **`pc` is a SECOND DEVICE of `pa`'s participant** — same SAN MSISDN, different key,
    ///    different subject. That is the shape a real multi-device peer has, and it is the shape
    ///    most likely to be rejected by an identity provider that treats the MSISDN as the identity:
    ///    mls-rs refuses two KeyPackages from ONE identity with `DuplicateLeafData`. If our identity
    ///    were derived from the SAN alone, multi-device add would be impossible and this test says
    ///    so loudly instead of leaving it to be discovered against a live peer.
    #[test]
    fn create_group_multi_adds_three_members_in_one_commit() {
        let dir = format!("{}/multi3_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/b")).unwrap();
        // The second device of participant +15551110001 — the same MSISDN as `a`.
        let c = ProdSession::start_no_revocation(&td("lab2_leaf_pc.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pc_priv.bin"), &td("lab2_leaf_pc_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/c")).unwrap();

        let mut kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());
        kps.extend(split_len_prefixed(&c.generate_key_packages(1).unwrap()));
        assert_eq!(kps.len(), 2, "two KeyPackages go into the initial commit");

        let (gid_a, artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let group = a.client.load_group(&gid_a).unwrap();
        assert_eq!(group.roster().members().len(), 3,
            "creator + two added devices, all in the FIRST commit");
        // ONE commit: the group is at epoch 1, not 3. This is the property Tachyon's roster check
        // depends on, and adding members serially would satisfy the member count but not this.
        assert_eq!(group.current_epoch(), 1, "all adds ride a single commit");

        // Both admitted members can actually read traffic — a roster entry is not a membership.
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();
        let gid_c = c.join_with_tree(&parts[0], &parts[5]).unwrap();
        let ct = a.encrypt(&gid_a, b"three members", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"three members");
        let ct2 = a.encrypt(&gid_a, b"and the second device", &[]).unwrap();
        assert_eq!(c.process(&gid_c, &ct2).unwrap(), b"and the second device");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// An era advance CANNOT be a GroupContextExtensions commit — 0xF001 is immutable (§9.2).
    ///
    /// This test used to assert the opposite: that a membership-preserving commit advanced the era
    /// and every member followed without a Welcome. It does that correctly, in MLS terms, and the
    /// server refused it every time (`ERA_MODE_PRESERVE` / `_CTRL`, 2026-07-29). §9.2 named the
    /// reason — the peer has a dedicated `GroupContextExtensionProposalChangesEraError` — so the
    /// behaviour under test is now the REFUSAL, at both layers that produce it, plus the proof that a
    /// refused advance leaves the group exactly where it was.
    #[test]
    fn an_era_advance_cannot_be_a_commit() {
        let dir = format!("{}/era_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // Put a subject commitment on the group, so we can prove an advance does not strip it.
        let meta = a.commit_group_metadata(&gid_a, &[], &[], &[], &[], &[9u8; 32]).unwrap();
        let meta_parts = split_len_prefixed(&meta);
        b.process(&gid_b, &meta_parts[1]).unwrap();

        // THE FRAMING, on the live group: 0xF006 takes an inner varint, so the 32-byte commitment
        // committed above sits on the wire as 33 bytes (`0x20 ‖ hash`) while group_ext hands the
        // caller back the 32-byte VALUE. We once shipped 32 on both sides.
        let ga = a.client.load_group(&gid_a).unwrap();
        let sc = ga.context().extensions()
            .get(ExtensionType::from(crate::rcc16::SUBJECT_COMMITMENT_EXT)).unwrap();
        assert_eq!(sc.extension_data.len(), 33, "0xF006 extension_data = varint(32) || 32-byte hash");
        assert_eq!(sc.extension_data[0], 0x20);
        assert_eq!(a.group_ext(&gid_a, crate::rcc16::SUBJECT_COMMITMENT_EXT).unwrap(),
                   vec![9u8; 32], "group_ext returns the DECODED value");
        // The era stays BARE through the same commit path — the rule is per-type, not uniform.
        assert_eq!(ga.context().extensions()
                       .get(ExtensionType::from(crate::rcc16::ERA_EXT)).unwrap()
                       .extension_data.len(), 4);
        drop(ga);

        // THE REFUSAL. An era advance cannot be a GroupContextExtensions commit — 0xF001 is immutable
        // within a group instance (§9.2), which is what our ERA_MODE_PRESERVE/_CTRL refusals were.
        let refused = a.commit_era_advance(&gid_a, &[], 2).unwrap_err();
        assert!(refused.contains("immutable"), "must name the reason, got: {refused}");

        // And the guard behind it fires independently of that early return: run the ORIGINAL body and
        // the MlsRules era check refuses it while BUILDING the commit. This is the half that also
        // covers an inbound peer commit, since filter_proposals runs on both directions.
        let by_the_guard = a.commit_era_advance_illegal_for_test(&gid_a, &[], 2).unwrap_err();
        assert!(by_the_guard.contains("ChangesEra"),
                "the MlsRules guard must refuse the commit build, got: {by_the_guard}");

        // The group is untouched by either refusal — still era 1, still 2 members, still sending.
        let ga = a.client.load_group(&gid_a).unwrap();
        assert_eq!(crate::rcc16::era_of(ga.context().extensions()), Some(1),
                   "a refused advance must leave the era where it was");
        assert_eq!(ga.roster().members().len(), 2);
        drop(ga);
        let ct = a.encrypt(&gid_a, b"still encrypting at era 1", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"still encrypting at era 1");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The LEGAL era advance: a new group at era+1 reusing the RCS group id, carrying the metadata.
    ///
    /// This is the path §9.2 leaves — `Creating new group for new era` — and the one our default
    /// `ERA_MODE_CREATE` takes. It also pins that a carried commitment keeps its varint framing
    /// (the carry copies `extension_data` verbatim, so re-encoding it would double-frame it).
    #[test]
    fn the_legal_era_advance_is_a_create_that_carries_the_metadata() {
        let dir = format!("{}/eracreate_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let kp = |s: &ProdSession| split_len_prefixed(&s.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();

        let (gid, artifacts) = a.create_group(1, &kp(&b), &[]).unwrap();
        let meta = a.commit_group_metadata(&gid, &[], &[], &[], &[], &[9u8; 32]).unwrap();
        let ginfo = split_len_prefixed(&meta)[2].clone();
        let _ = artifacts;

        // era+1, same group id, carrying the previous era's GroupInfo.
        let (gid2, _) = a.create_group_carry(2, &[kp(&b)], &gid, &ginfo,
            AdvanceEraKind::Normal).unwrap();
        assert_eq!(gid2, gid, "an advance keeps the conversation's group id");
        let g2 = a.client.load_group(&gid2).unwrap();
        assert_eq!(crate::rcc16::era_of(g2.context().extensions()), Some(2));
        let sc = g2.context().extensions()
            .get(ExtensionType::from(crate::rcc16::SUBJECT_COMMITMENT_EXT))
            .expect("the advance must inherit the commitment — Tachyon refuses Some([..]) -> None");
        assert_eq!(sc.extension_data.len(), 33, "carried verbatim, so still framed — not re-encoded");
        assert_eq!(a.group_ext(&gid2, crate::rcc16::SUBJECT_COMMITMENT_EXT).unwrap(), vec![9u8; 32]);

        // THE DENY-LIST. Put a code point on the group that the old allow-list never named — 0xF007,
        // which Google Messages uses and no production path of ours sets — and prove the advance keeps it.
        // Under the allow-list this vanished by construction, and the loss surfaced later as an
        // unexplained peer rejection rather than as an error at the point of loss.
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

        // Equal, backwards, and wrapped-to-zero must all be refused before any group is built.
        // 0 is in the list because it means "emit no Era extension" — carrying from a source that
        // has one would build a group peers refuse as `Missing Era group context extension`.
        for bad in [1u32, 0] {
            let e = a.create_group_carry(bad, &[kp(&b)], &gid, &ginfo,
                AdvanceEraKind::Normal).unwrap_err();
            assert!(e.contains("backwards in eras"),
                    "era {bad} must not be accepted as an advance from 1, got: {e}");
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// **RCC.16 §6.1.1 retention stops at an ERA BOUNDARY on BOTH sides, and that is correct rather
    /// than a retention bug**.
    ///
    /// # What this pins, and why the constants are not the control
    ///
    /// `EPOCH_RETENTION` and `epoch_retention_ms` decide how long a *retained* epoch lives. Neither
    /// is consulted here: an era advance removes the record before either runs, so widening them
    /// changes nothing across an advance. That is the trap this test exists to close — the caveat
    /// on §8-N5 reads like a retention caveat and is not one.
    ///
    /// # It is TWO sites, not one, and the second is the load-bearing half
    ///
    /// The advancer clears via `create_group_carry`'s `gid_override` arm (`delete_group`). **Every
    /// other member clears too**, via `purge_prior_epochs_on_join` on all five join paths — so an
    /// "archive instead of delete" applied only to the advancer would retain for exactly ONE member
    /// of N and for none of the peers holding the message that needed decrypting.
    ///
    /// # And the secrets are not merely unused — they are AMBIGUOUS
    ///
    /// A new era is a new MLS group at the SAME group id, so the epoch id restarts. mls-rs addresses
    /// its archive by `(group_id, epoch_id)` and nothing else, so era 1's epoch 2 and era 2's epoch 2
    /// are two different secrets competing for one key. The final assertion reads the same key twice,
    /// across the advance, and requires the answers to DIFFER: keeping the old entry at that key
    /// would shadow the live one. (mls-rs also refuses a non-contiguous archive insert with
    /// `InvalidEpoch` — which is the separate, device-proven reason the clear cannot
    /// simply be dropped.)
    #[test]
    fn an_era_advance_reuses_the_epoch_id_space_so_prior_era_secrets_cannot_stay_at_the_same_key() {
        use mls_rs_core::group::GroupStateStorage;
        let dir = format!("{}/erakeys_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let kp = |s: &ProdSession| split_len_prefixed(&s.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let store_a = FileGroupStateStorage::new(format!("{dir}/a"));
        let store_b = FileGroupStateStorage::new(format!("{dir}/b"));

        // ERA 1, walked far enough that both sides hold a real prior-epoch archive.
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
            .expect("the PEER must hold it too — that is the half the bead under-scoped").to_vec();

        // THE ADVANCE. Same group id, era 2, carrying era 1's GroupInfo.
        let (gid2, artifacts2) = a.create_group_carry(2, &[kp(&b)], &gid, &ginfo,
            AdvanceEraKind::Normal).unwrap();
        assert_eq!(gid2, gid, "the advance reuses the id — that is what makes the keys collide");

        // THE ADVANCER kept nothing from era 1.
        assert_eq!(store_a.max_epoch_id(&gid).unwrap(), Some(0),
                   "the advancer's archive restarts at the new era's epoch 0");
        for stale in [1u64, 2, 3] {
            assert!(store_a.epoch(&gid, stale).unwrap().is_none(),
                    "era 1's epoch {stale} must be gone on the advancer");
        }

        // ...AND NEITHER DID THE PEER, which reaches the new era by Welcome and never by a commit
        // chain. This is the assertion that makes "archive instead of delete" insufficient on its own.
        let parts2 = split_len_prefixed(&artifacts2);
        assert_eq!(b.join_with_tree(&parts2[0], &parts2[5]).unwrap(), gid,
                   "the peer joins the new era at the same id");
        assert_eq!(store_b.max_epoch_id(&gid).unwrap(), None,
                   "a joiner starts with an EMPTY archive (purge_prior_epochs_on_join)");
        for stale in [1u64, 2, 3] {
            assert!(store_b.epoch(&gid, stale).unwrap().is_none(),
                    "era 1's epoch {stale} must be gone on the peer too");
        }

        // THE COLLISION, stated as a measurement: walk the new era back over the same epoch ids and
        // read the very key that used to hold era 1's secret. It answers, and it answers DIFFERENTLY.
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

    /// An era advance must NOT silently erase a downgrade.
    ///
    /// A remote peer can end MLS on a conversation (RCC.16 §9.1.1 `end_mls`, 0xF002). Since an era
    /// advance RE-CREATES the group, leaving 0xF002 out of the carry list means recovery quietly
    /// brings a deliberately-plaintext conversation back to encrypted — against the decision of
    /// whoever ended it. Removing end_mls has its own explicit path; it must never be a side effect.
    #[test]
    fn era_advance_carry_preserves_end_mls() {
        let dir = format!("{}/endmls_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid, artifacts) = a.create_group(1, &bkp, &[]).unwrap();

        // End MLS on the group, then read back the GroupInfo that an era advance would carry from.
        let ended = a.commit_end_mls(&gid, &[], EndMlsOp::Install).unwrap();
        let ended_parts = split_len_prefixed(&ended);
        let ginfo_with_end_mls = &ended_parts[2];
        assert!(a.client.load_group(&gid).unwrap().context().extensions()
                    .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_some(),
                "precondition: end_mls is set");

        // Advance the era by re-creating, carrying over from that GroupInfo — as recovery does.
        let bkp2 = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid2, _) = a.create_group_carry(2, &[bkp2], &gid, ginfo_with_end_mls,
            AdvanceEraKind::Normal).unwrap();
        assert!(a.client.load_group(&gid2).unwrap().context().extensions()
                    .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_some(),
                "an era advance must NOT drop end_mls — that silently re-encrypts a conversation                  someone deliberately downgraded");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The three-valued mode byte, all three arms, on one group (§9.7g).
    ///
    /// The two non-Normal arms had no test because they had no CALLER — which is exactly the shape
    /// of defect `MlsRecoveryLadderTest` exists for: "the pair is legal" was true the whole time
    /// recovery was broken. A mode that is unreachable is not a mode that works.
    #[test]
    fn era_advance_mode_byte_drives_end_mls_three_ways() {
        let dir = format!("{}/eramode_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let fresh_kp = |n: usize| split_len_prefixed(&b.generate_key_packages(n as u32).unwrap())
            .into_iter().next().unwrap();
        let has_end_mls = |g: &[u8]| a.client.load_group(g).unwrap().context().extensions()
            .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_some();

        let (gid, _) = a.create_group(1, &fresh_kp(1), &[]).unwrap();
        let ended = a.commit_end_mls(&gid, &[], EndMlsOp::Install).unwrap();
        let gi_downgraded = split_len_prefixed(&ended)[2].clone();
        assert!(has_end_mls(&gid), "precondition: the source group is downgraded");

        // MODE 0 — carries it forward. (Also covered by the test above; repeated here so all three
        // arms are compared against ONE source GroupInfo rather than three different ones.)
        let (g_normal, _) = a.create_group_carry(2, &[fresh_kp(1)], b"era2-normal", &gi_downgraded,
            AdvanceEraKind::Normal).unwrap();
        assert!(has_end_mls(&g_normal), "mode 0 must carry end_mls forward");

        // MODE 1 — REVIVAL drops it. This is the arm that brings a conversation back, and the only
        // era-advance arm permitted to remove 0xF002 at all.
        let (g_revive, _) = a.create_group_carry(3, &[fresh_kp(1)], b"era3-revive", &gi_downgraded,
            AdvanceEraKind::Revival).unwrap();
        assert!(!has_end_mls(&g_revive),
                "mode 1 is REVIVAL — the new era must NOT carry end_mls, or the revive did nothing");

        // MODE 2 — PHOENIX installs it, from a source that does NOT have it. This is the half a
        // preserve-only carry list cannot express: "the new era is born downgraded".
        let (gid_healthy, _) = a.create_group(1, &fresh_kp(1), b"healthy-src").unwrap();
        let gi_healthy = {
            // A GroupInfo from a group with no end_mls, to prove Phoenix ORIGINATES the tag rather
            // than inheriting it.
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

    /// Phoenix must install `end_mls` even with NO carry GroupInfo at all.
    ///
    /// Phoenix exists for the case where the current group is wedged badly enough that the ordinary
    /// end-mls commit cannot land — and "we could not fetch the old GroupInfo either" is squarely
    /// inside that case. If the install lived inside the carry block, the one mode that must never
    /// fail silently would depend on the one input most likely to be missing.
    #[test]
    fn phoenix_installs_end_mls_with_no_carry_group_info() {
        let dir = format!("{}/phoenixnc_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid, _) = a.create_group_carry(7, &[bkp], b"phoenix-nocarry", &[],
            AdvanceEraKind::PhoenixDowngrade).unwrap();
        assert!(a.client.load_group(&gid).unwrap().context().extensions()
                    .get(ExtensionType::from(crate::rcc16::END_MLS_EXT)).is_some(),
                "Phoenix must install end_mls with no carry GroupInfo");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The mode decode, including the "anything else is Normal" fallthrough.
    #[test]
    fn advance_era_kind_decodes_like_the_reference_clients_branch() {
        assert_eq!(AdvanceEraKind::from_mode(0), AdvanceEraKind::Normal);
        assert_eq!(AdvanceEraKind::from_mode(1), AdvanceEraKind::Revival);
        assert_eq!(AdvanceEraKind::from_mode(2), AdvanceEraKind::PhoenixDowngrade);
        // `> 2` falls through to touching neither — carrying forward is the safe unknown answer.
        assert_eq!(AdvanceEraKind::from_mode(3), AdvanceEraKind::Normal);
        assert_eq!(AdvanceEraKind::from_mode(255), AdvanceEraKind::Normal);
        // Exactly one arm may remove, exactly one installs. If either count changes, THE END-MLS REMOVAL RULE
        // has grown a call site.
        let all = [AdvanceEraKind::Normal, AdvanceEraKind::Revival,
                   AdvanceEraKind::PhoenixDowngrade];
        assert_eq!(all.iter().filter(|k| k.may_remove_end_mls()).count(), 1);
        assert_eq!(all.iter().filter(|k| k.installs_end_mls()).count(), 1);
    }

    /// `EndMlsOp` — the enum that replaced a bare `remove: bool`, and the round trip the C ABI uses.
    #[test]
    fn end_mls_op_widens_the_c_abi_flag() {
        assert_eq!(EndMlsOp::from_flag(false), EndMlsOp::Install);
        assert_eq!(EndMlsOp::from_flag(true), EndMlsOp::RemoveForRevival);
        assert!(!EndMlsOp::Install.removes());
        assert!(EndMlsOp::RemoveForRevival.removes());
    }

    /// Revival by the IN-PLACE path (the other of the two removal sites) actually removes it.
    #[test]
    fn commit_end_mls_remove_for_revival_drops_the_extension() {
        let dir = format!("{}/revive_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
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
        // The era must be untouched: an in-place revive is not an era advance.
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// An inbound proposal must surface its TYPE, and one we cannot honour must be droppable.
    ///
    /// The failure this pins is not theoretical. A cached by-reference proposal blocks application
    /// messages (`commit_required`), and an RCC.16 custom proposal like `server_remove` (0xF004)
    /// reaches mls-rs as an opaque CustomProposal — committing it changes no group state. So without
    /// a way to tell the type apart and drop it, one undecodable proposal from a focus wedges the
    /// conversation permanently: sends blocked forever, and a "successful" commit that removes nobody.
    #[test]
    /// §15.2: removal selects an MSISDN, and takes EVERY matching leaf in ONE commit.
    ///
    /// # What this proves, and what it cannot
    ///
    /// It proves the SELECTOR: the leaf is chosen by its certified SAN rather than by a signature
    /// key, and an MSISDN with no leaf is refused rather than silently removing someone else.
    ///
    /// It does NOT exercise the multi-device case, and that is a fixture limit rather than an
    /// oversight: RFC 9420 requires each leaf in a group to carry a distinct SIGNATURE key, so two
    /// devices of one person means two device keys certified to one SAN — and every test cert here
    /// carries one signature key, so two key packages from one identity are rejected as
    /// DuplicateLeafData before a group can even be built. Proving the N-leaf sweep needs a second
    /// certificate issued to the same MSISDN, which we do not have.
    ///
    /// The failure the selector guards is still worth stating: with a per-leaf selector, removing a
    /// two-device participant drops one device, reports success, and leaves the other reading every
    /// message while the UI says they are gone.
    fn removal_selects_by_msisdn_not_by_leaf() {
        let dir = format!("{}/rmall_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
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

        // An MSISDN nobody in the group is certified to must be REFUSED, not answered by removing
        // whoever happened to be first — the per-leaf selector's empty-key arm did exactly that.
        assert!(a.remove_member_by_msisdn(&gid_a, b"+15550009999", b"aad").is_err(),
            "an absent MSISDN must not remove an arbitrary member");
        assert_eq!(a.client.load_group(&gid_a).unwrap().roster().members().len(), 2,
            "the refused removal must not have changed the roster");

        // ...and the real one goes.
        a.remove_member_by_msisdn(&gid_a, &bmsisdn, b"aad").unwrap();
        assert_eq!(a.client.load_group(&gid_a).unwrap().roster().members().len(), 1);
    }

    /// ONE commit, N leaves — the exact mirror of the removal test above.
    ///
    /// The property that matters is not "N members ended up in the group" but "the epoch advanced
    /// ONCE while they did". Adding them one at a time also reaches N members, and creates an
    /// intermediate epoch in which a participant is half-added: their second device is not yet in
    /// the group, cannot decrypt what is sent at that epoch, and the sender has no way to know.
    /// `add_members` advances the epoch exactly ONCE and is equivalent to `add_member` for N=1.
    ///
    /// # What this does and does NOT cover, stated plainly
    ///
    /// The property `add_members` exists for is *N packages ⇒ N leaves in ONE commit*, and the
    /// N-distinct-devices half of it is **not exercised here**: it needs two RCC.16-conformant leaf
    /// certificates for the same participant, and the fixture set has exactly two conformant leaves
    /// (`leaf_pa`, `leaf_pb`) belonging to two different MSISDNs. `leaf_a`/`leaf_b` carry no RCC.16
    /// EKU, so the identity provider refuses them — verified, not assumed.
    ///
    /// So what is pinned here is the ONE-COMMIT half (the epoch delta), and the duplicate test below
    /// pins the refusal. The N-leaf half is structural — one builder, N `add_member` calls — and is
    /// left honestly untested rather than faked with a loop that would pass either way. A third
    /// conformant fixture would close it; that is a follow-up, not a claim.
    /// **The engine DOES validate the credential of a member being added**, and this
    /// test replaces one that asserted the opposite.
    ///
    /// It was recorded that mls-rs runs our `Rcc16Validator` as its `IdentityProvider` while a commit
    /// is being constructed, giving us a second validation layer behind the caller-side
    /// `keyPackageUsable` gate. On 2026-09-08 I claimed to have refuted that. **The refutation was
    /// wrong and the original claim was right.**
    ///
    /// # How the wrong answer was produced, because the trap is reusable
    ///
    /// Two instruments were used and they agreed, which read as independent confirmation:
    /// a THREAD-LOCAL invocation counter on `X509WithBasicCreds::validate_member`, and a THREAD-LOCAL
    /// clock override that pushed the evaluation instant past the fixture's `notAfter`. The counter
    /// read zero on the add path, and the expired-window add was accepted.
    ///
    /// Both are thread-local, and **mls-rs validates on a rayon worker pool** — `wrap_iter` in
    /// `src/iter.rs` is `into_par_iter()`, and `rayon` is present in our `Cargo.lock` transitively
    /// even though it is not in mls-rs's default feature list. So the calls happened on other
    /// threads: the counter never saw them, and the override never reached them (the validator on
    /// those threads fell back to the ordinary pinned instant, at which the fixture is perfectly
    /// valid — hence the "accepted"). Printing `std::thread::current().id()` at the call site showed
    /// `ThreadId(2)`, `ThreadId(45)`, `ThreadId(47)`, each reporting its own `count=1`.
    ///
    /// **The two instruments were not independent — they shared one hidden assumption**, and agreeing
    /// with each other is exactly what made the wrong answer persuasive. A second instrument only
    /// corroborates if it can fail differently from the first.
    ///
    /// # What this test does instead
    ///
    /// No clocks and no counters, so no thread can hide from it: it adds a member whose certificate
    /// chains to a **different root** (the retired lab chain, `leaf_a.der` under `root.der`, which
    /// also carries no RCC.16 EKU) to a group anchored on `lab2_root`. The add must be REFUSED, and
    /// the refusal must surface as an `IdentityProviderError` — which only the engine's in-builder
    /// validation can produce, since the Java caller gate is not on this path at all.
    ///
    /// If this ever starts passing the add, the second layer really has gone, and the caller gate in
    /// `MlsProviderTransport` becomes the only certificate check on the send path.
    #[test]
    fn the_engine_validates_the_credential_of_a_member_being_added() {
        let dir = format!("{}/cczpadd_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        // The STRANGER: retired lab chain, a different root entirely.
        let x = ProdSession::start_no_revocation(&td("leaf_a.der"), &[td("ica.der")], &td("leaf_a_priv.bin"),
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

    #[test]
    fn add_members_advances_the_epoch_exactly_once() {
        let dir = format!("{}/addmulti_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let bkps = split_len_prefixed(&b.generate_key_packages(2).unwrap());

        let (gid, _) = a.create_group(1, &bkps[0], &[]).unwrap();
        // Remove B so its second package can be added back as a fresh leaf — the only way to
        // exercise a real add with the two identities available.
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

    /// **N key packages from ONE device cannot become N leaves, and that is RFC 9420, not a bug.**
    ///
    /// Discovered while writing the test above, which originally claimed four packages from one
    /// session as "a two-device participant" and was refused with `DuplicateLeafData(1)`. Every
    /// package a single client mints carries that client's signature key, and RFC 9420 forbids two
    /// leaves with duplicate leaf data in one group.
    ///
    /// So §14.1's "N devices ⇒ N key packages ⇒ N leaves" is about **N distinct devices**, each with
    /// its own credential. A claim that returns several packages for one MSISDN is returning one per
    /// DEVICE, and adding several packages minted by the same device is a caller error the protocol
    /// catches. Worth pinning because the refusal is what makes the multi-add safe to expose: there
    /// is no silent path where an over-eager claim produces a group with duplicate leaves.
    #[test]
    fn add_members_refuses_duplicate_leaves_from_one_device() {
        let dir = format!("{}/adddup_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let bkps = split_len_prefixed(&b.generate_key_packages(3).unwrap());
        let (gid, _) = a.create_group(1, &bkps[0], &[]).unwrap();
        let epoch_before = a.client.load_group(&gid).unwrap().context().epoch;

        // Two more packages from the SAME client — same signature key, so duplicate leaf data.
        let e = a.add_members(&gid, &bkps[1..3], b"aad").unwrap_err();
        assert!(e.contains("DuplicateLeafData"), "expected a duplicate-leaf refusal, got: {e}");
        assert_eq!(a.client.load_group(&gid).unwrap().context().epoch, epoch_before,
            "the refused add must not have advanced the epoch");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// An empty add must be REFUSED, not committed. A commit that changes no membership still
    /// advances the epoch and would report success — an "add" that added nobody.
    #[test]
    fn add_members_refuses_an_empty_list() {
        let dir = format!("{}/addempty_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
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
    /// An encrypt must be able to return the ciphertext AND a piggybacked key update,
    /// in that order, from ONE call — so a caller cannot take the first and lose the second.
    fn encrypt_results_carries_the_piggybacked_key_update() {
        let dir = format!("{}/encres_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // WITHOUT a key update: one result, the ciphertext, and B can open it.
        let plain = a.encrypt_results(&gid_a, b"hello", b"aad", b"ctx-1", false).unwrap();
        let recs = split_len_prefixed(&plain);
        assert_eq!(recs.len(), 2, "version record + one result");
        let f = split_len_prefixed(&recs[1]);
        assert_eq!(f[0], vec![0u8], "status 0 = APPLICATION");
        assert_eq!(f[1], b"ctx-1".to_vec());
        assert_eq!(b.process(&gid_b, &f[4]).unwrap(), b"hello".to_vec(),
            "the ciphertext in the list must be a real, openable message");

        // WITH one: ciphertext FIRST, then the commit, then the rollback snapshot.
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

        // ORDERING IS THE POINT: the message was encrypted at the epoch B still holds, so B can
        // open it BEFORE applying the commit. Reversed, this would fail.
        assert_eq!(b.process(&gid_b, &app[4]).unwrap(), b"again".to_vec(),
            "the ciphertext must be readable at the PRE-commit epoch");

        // DEFER-UNTIL-ACK: self_update already advanced US locally. A server that REFUSES the commit
        // would leave us epoch-AHEAD forever, so the snapshot must actually wind that back.
        let epoch_after = a.client.load_group(&gid_a).unwrap().context().epoch;
        assert!(epoch_after > epoch_before, "the key update advanced us locally, as commits do");
        assert!(a.restore_group_snapshot(&gid_a, &rollback[4]).is_ok(), "the snapshot must restore");
        assert_eq!(a.client.load_group(&gid_a).unwrap().context().epoch, epoch_before,
            "a refused key update must leave us where the ciphertext was made, not ahead of it");

        // ...and having rolled back, A can still talk to B, which never saw the commit.
        let after = a.encrypt_results(&gid_a, b"post-rollback", b"aad", b"ctx-3", false).unwrap();
        let f3 = split_len_prefixed(&split_len_prefixed(&after)[1]);
        assert_eq!(b.process(&gid_b, &f3[4]).unwrap(), b"post-rollback".to_vec(),
            "after a rolled-back rotation the conversation must still work");
    }

    /// A RE-MINTED CERTIFICATE MUST REACH THE GROUP, and before this it never did.
    ///
    /// RCC.16 v4.0 §9.5.3 ("Certificate Update") requires the client to "create an empty Commit with
    /// UpdatePath containing the new leaf from the newly created KeyPackage". We issued the empty
    /// Commit and mls-rs built the UpdatePath — but with `new_signing_identity` unset it reuses the
    /// leaf's EXISTING `SigningIdentity`, so the new leaf certified the OLD certificate. The group's
    /// copy therefore aged on the ORIGINAL mint's clock no matter how often the device re-minted,
    /// and once every leaf sat inside the 30-day remaining-lifetime floor the server refused every
    /// membership Commit into the group (A.4.3.1 §1(a)).
    ///
    /// The fixture is a REAL renewal: `lab2_leaf_pa_renewed.der` is `lab2_leaf_pa`'s subject, key,
    /// SAN and `.4` ParticipantInformation re-issued by the same lab ICA with a new serial and a
    /// later `notAfter`. Same CN, which is what `valid_successor` compares, and the same key, which
    /// is what a real KDS renewal does (our subject key is derived deterministically and does not
    /// move on a re-mint) — so this exercises exactly the case that occurs on device and NOT the
    /// easier one where the signature key changes too.
    ///
    /// The assertion is made on B'S ROSTER, not on A's own state: the group's copy is what the
    /// server validates, so proving it locally would prove the wrong thing.
    #[test]
    fn a_self_update_carries_a_re_minted_credential_into_the_group() {
        let dir = format!("{}/renewal_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        // notAfter of each fixture, so the assertions name a value rather than a difference.
        const PA_NOT_AFTER: u64 = 1791387137;          // 2026-10-07 15:32:17Z
        const RENEWED_NOT_AFTER: u64 = 1791892800;     // 2026-10-13 12:00:00Z
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/b")).unwrap();
        let (gid_a, gid_b, a_index) = {
            let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
                &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
                &format!("{dir}/a")).unwrap();
            let kps = b.generate_key_packages(2).unwrap();
            let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
            let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
            let parts = split_len_prefixed(&artifacts);
            let gid_b = b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap();
            // Baseline: both sides see A certified by the ORIGINAL leaf.
            let st = a.self_leaf_status(&gid_a).unwrap();
            assert_eq!(st[36], 0, "before any re-mint our leaf is NOT stale");
            // OUR index comes from the engine, not from matching a validity window: the lab
            // fixtures were minted in one batch, so A's and B's windows are IDENTICAL and a match
            // on `notAfter` would silently pick whichever row came first.
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

        // THE RE-MINT. Same storage, same key, a NEW certificate — this is what the device does
        // when MlsRefreshWorker re-mints and MlsProviderTransport.onIdentityChanged reopens the
        // session against the new identity.
        let a2 = ProdSession::start_no_revocation(&td("lab2_leaf_pa_renewed.der"),
            &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"),
            &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();

        // THE DETECTOR. It must SEE the drift, and report both windows.
        let st = a2.self_leaf_status(&gid_a).unwrap();
        assert_eq!(st.len(), 37, "the status record is fixed-width");
        assert_eq!(u32::from_be_bytes([st[0], st[1], st[2], st[3]]), a_index,
            "self_leaf_status must name OUR leaf index");
        assert_eq!(u64::from_be_bytes(st[12..20].try_into().unwrap()), PA_NOT_AFTER,
            "the GROUP still holds the certificate we joined with");
        assert_eq!(u64::from_be_bytes(st[28..36].try_into().unwrap()), RENEWED_NOT_AFTER,
            "the CLIENT holds the re-minted certificate");
        assert_eq!(st[36], 1, "and the two differ, which is the whole condition");

        // THE COMMIT. §9.5.3's empty Commit with the new leaf in the UpdatePath.
        let bundle = split_len_prefixed(&a2.self_update(&gid_a, b"mid-renewal").unwrap());
        assert!(b.process(&gid_b, &bundle[1]).is_ok(),
            "the peer must ACCEPT a Commit that changes our credential — valid_successor compares \
             the subject CN, and a renewal keeps it");

        // THE PROOF, on the peer's copy of the roster.
        let v = parse_validity(&b.member_validity(&gid_b).unwrap());
        let (_, _, na_now) = *v.iter().find(|(i, _, _)| *i == a_index)
            .expect("A must still be at the same leaf index");
        assert_eq!(na_now, RENEWED_NOT_AFTER,
            "THE GROUP'S COPY OF OUR CREDENTIAL MUST BE THE RE-MINTED ONE. If this is \
             PA_NOT_AFTER the Commit rotated the HPKE key and left the certificate behind, which \
             is the defect exactly.");
        // ...and NOBODY ELSE moved. A commit that rebuilt the roster would also satisfy the
        // assertion above; this is what makes it a credential rotation rather than a rebuild.
        assert_eq!(v.len(), 2, "the roster must still be the two of us");
        let (_, _, b_na) = *v.iter().find(|(i, _, _)| *i != a_index).unwrap();
        assert_eq!(b_na, PA_NOT_AFTER,
            "B's own leaf must be untouched (the lab fixtures share a validity window, which is              why A's index is taken from the engine rather than matched on notAfter)");

        // ...and the detector now says so.
        assert_eq!(a2.self_leaf_status(&gid_a).unwrap()[36], 0,
            "after the Commit the group holds the certificate we hold");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// NEGATIVE CONTROL for the test above (a check that has never been seen decide the
    /// other way is not a check). Same code path, an UNCHANGED certificate: the rotation must not
    /// fire, the leaf's window must not move, and the peer must still accept the Commit.
    #[test]
    fn a_self_update_with_an_unchanged_certificate_does_not_rotate_the_credential() {
        let dir = format!("{}/renewalctl_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        const PA_NOT_AFTER: u64 = 1791387137;
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
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

    /// Decode `member_validity`'s 20-byte rows into `(leaf_index, not_before, not_after)`.
    /// THE SIGNER NAMES A MEMBER, AND DISTINGUISHES TWO OF THEM.
    ///
    /// Fork attribution previously had to match (notBefore, notAfter) pairs, which identify a
    /// CERTIFICATE rather than a member — all three lab lines re-minted within 36 minutes on
    /// 2026-08-09 and share triples, which is the aliasing that forced retracting an identification.
    ///
    /// THE ASSERTION IS THAT IT TELLS TWO MEMBERS APART, not merely that it returns a number. A
    /// function returning a constant 0 would satisfy "it returns an index" for the creator and be
    /// useless for the one question it exists to answer, so both members are checked and the two
    /// answers are required to DIFFER.
    #[test]
    fn the_group_info_signer_names_which_member_signed_it() {
        let dir = format!("{}/htos_signer_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0].clone(), &parts[5].clone()).unwrap();

        // Indices come from the engine, never from matching a certificate window — the lab fixtures
        // were minted in one batch and A's and B's windows are IDENTICAL, which is the whole point.
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
        // THE EPOCH MUST TRAVEL WITH THE SIGNER. A signer index is only evidence about a fork if the
        // reader knows WHICH epoch's GroupInfo it signed — the provider fetches this pack anchored
        // at our OWN epoch, so a bundle describing our anchor rather than the server's current state
        // would carry a signer that says nothing about anyone else.
        assert_eq!(signer_epoch(&gi_a).1, a.client.load_group(&gid_a).unwrap().context().epoch,
            "the GroupInfo must report the epoch of the group it describes");
        assert_eq!(signer(&gi_a), a_index, "A's GroupInfo must name A's leaf");
        assert_eq!(signer(&gi_b), b_index, "B's GroupInfo must name B's leaf");
        assert_ne!(signer(&gi_a), signer(&gi_b),
            "the whole use is telling two members apart; equal answers would be worthless");

        // READ-ONLY, and parsing a PEER's GroupInfo must not need that peer's state: A resolves B's
        // signer above while holding only its own group. That is what makes this usable on the
        // server's anchor, which no device holds the state for.
        assert!(a.group_info_signer(&[]).is_err(), "empty bytes must be refused, not answered 0");
        assert!(a.group_info_signer(b"not a GroupInfo").is_err(),
            "unparseable bytes must be refused rather than reported as leaf 0");
        assert!(a.group_info_signer(&parts[5]).is_err(),
            "a RATCHET TREE is not a GroupInfo and must be refused rather than mis-parsed");
    }

    /// Parse the variable-width records `tree_member_validity` emits.
    fn parse_tree_validity(packed: &[u8]) -> Vec<(u32, u64, u64, String)> {
        let mut out = Vec::new();
        let mut o = 0usize;
        while o + 24 <= packed.len() {
            let idx = u32::from_be_bytes(packed[o..o + 4].try_into().unwrap());
            let nb = u64::from_be_bytes(packed[o + 4..o + 12].try_into().unwrap());
            let na = u64::from_be_bytes(packed[o + 12..o + 20].try_into().unwrap());
            let ml = u32::from_be_bytes(packed[o + 20..o + 24].try_into().unwrap()) as usize;
            assert!(o + 24 + ml <= packed.len(), "record overruns the buffer");
            out.push((idx, nb, na, String::from_utf8_lossy(&packed[o + 24..o + 24 + ml]).into_owned()));
            o += 24 + ml;
        }
        assert_eq!(o, packed.len(), "trailing bytes: the framing is wrong");
        out
    }

    /// READING THE SERVER'S COPY OF THE ROSTER'S CERTIFICATES, WITHOUT LOADING IT.
    ///
    /// `member_validity` calls `load_group`, so it can only ever report the copy THIS DEVICE holds.
    /// The rule is that success is measured on the group, and we have measured one device at
    /// epoch 1 against a server at epoch 3 — so the local answer can describe a copy the server does
    /// not hold, in either direction. The instrument this test pins parses the ratchet tree
    /// `GetMlsGroupInfo` already returns.
    ///
    /// THE ASSERTION THAT MATTERS is the AGREEMENT: for a tree exported from a group the peer also
    /// holds, the parse-only path must produce exactly what the loaded path produces. A test that
    /// only checked "it returns some windows" would pass just as well if it read a different
    /// certificate out of the leaf, which is the two-clocks defect one layer down.
    #[test]
    fn the_server_tree_reports_the_same_windows_as_the_loaded_group() {
        let dir = format!("{}/htos_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(2).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let tree = parts[5].clone();                      // the out-of-band ratchet tree
        let gid_b = b.join_with_tree(&parts[0].clone(), &tree).unwrap();

        // Captured BEFORE the read, so the non-mutation assertion below compares invariance rather
        // than a constant somebody has to keep correct.
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
            // The MSISDN is what makes a leaf identifiable; an index alone cannot answer "is MY
            // leaf here". If the SAN stops being read this goes empty and the probe silently
            // degrades to anonymous rows, so it is asserted rather than printed.
            assert!(!msisdn.is_empty(), "leaf {idx}: the SAN MSISDN must be carried");
        }
        let mut msisdns: Vec<&str> = from_tree.iter().map(|(_, _, _, m)| m.as_str()).collect();
        msisdns.sort();
        msisdns.dedup();
        assert_eq!(msisdns.len(), 2, "the two leaves must be distinguishable by MSISDN");

        // AN EMPTY TREE IS AN ERROR, NOT AN EMPTY ROSTER. Returning Ok(vec![]) would let a caller
        // render "the server holds no members", which is the reader-refusal defect this project
        // keeps re-filing — "we could not look" must never arrive as "we looked and found nothing".
        assert!(a.tree_member_validity(&[]).is_err(), "an empty tree must be refused, not answered");
        assert!(a.tree_member_validity(b"not a ratchet tree").is_err(),
            "unparseable bytes must be refused rather than reported as an empty roster");

        // AND IT MUST NOT HAVE MUTATED ANYTHING: the group A holds is still at the epoch it was.
        assert_eq!(a.client.load_group(&gid_a).unwrap().context().epoch, epoch_before,
            "reading the tree must not advance or disturb the local group");
    }

    fn parse_validity(packed: &[u8]) -> Vec<(u32, u64, u64)> {
        packed.chunks_exact(20).map(|c| (
            u32::from_be_bytes(c[0..4].try_into().unwrap()),
            u64::from_be_bytes(c[4..12].try_into().unwrap()),
            u64::from_be_bytes(c[12..20].try_into().unwrap()),
        )).collect()
    }

    #[test]
    /// The SENDER identity must come from the authenticated leaf, not the envelope.
    ///
    /// The security property: a group member can address a transport envelope in anyone's name, so
    /// an identity read off the envelope is attacker-chosen. `sender_index` is authenticated by the
    /// decrypt, and its leaf's X.509 SAN is what the KDS certified.
    fn the_sender_msisdn_comes_from_the_signing_leaf() {
        let dir = format!("{}/sender_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // A's own certified identity, read the same way the claim-time A.4.1 check reads it.
        let a_msisdn = {
            let ga = a.client.load_group(&gid_a).unwrap();
            let me = ga.current_member_index();
            ProdSession::certified_msisdn_of(&ga, me)
        };
        assert!(!a_msisdn.is_empty(), "the test leaf must carry a tel: SAN, else this proves nothing");

        // B decrypts A's message and must independently arrive at A's certified MSISDN.
        let ct = a.encrypt(&gid_a, b"hi", b"aad").unwrap();
        b.process(&gid_b, &ct).unwrap();
        let seen = LAST_SENDER_MSISDN.with(|c| c.borrow().clone());
        assert_eq!(seen, a_msisdn, "the receiver must name the SIGNER, whatever the envelope said");

        // ...and process_ex must agree, since both are production inbound paths.
        let ct2 = a.encrypt(&gid_a, b"hi again", b"aad").unwrap();
        b.process_ex(&gid_b, &ct2).unwrap();
        assert_eq!(LAST_SENDER_MSISDN.with(|c| c.borrow().clone()), a_msisdn,
            "process_ex must bind the sender too — a check on only one path is not a check");
    }

    #[test]
    /// A NON-application message must not leave a stale identity behind.
    ///
    /// The thread-local is read right after a decrypt. If a commit left the previous message's
    /// sender in place, the next application message that failed to populate it would be attributed
    /// to whoever sent the one before — which is the very confusion the binding exists to end.
    fn a_commit_clears_the_sender_identity() {
        let dir = format!("{}/sender2_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        let ct = a.encrypt(&gid_a, b"hi", b"aad").unwrap();
        b.process(&gid_b, &ct).unwrap();
        assert!(!LAST_SENDER_MSISDN.with(|c| c.borrow().clone()).is_empty());

        // A rekeys; B applies the COMMIT, which is not an application message.
        let commit = split_len_prefixed(&a.self_update(&gid_a, b"aad").unwrap())[1].clone();
        b.process_ex(&gid_b, &commit).unwrap();
        assert!(LAST_SENDER_MSISDN.with(|c| c.borrow().clone()).is_empty(),
            "a commit must leave NO sender identity behind — stale is worse than absent here");
    }

    #[test]
    /// `process_results` must emit the record layout `MlsEngineResult` decodes.
    ///
    /// The Java side asserts this layout with HAND-BUILT fixtures rather than its own encoder's
    /// output, so the two tests pin the same contract from opposite ends. Without a test on this
    /// side the Rust could change shape and only a device would notice.
    fn process_results_emits_the_versioned_record_list() {
        let dir = format!("{}/presults_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
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
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid_a, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // B sends a server_remove (0xF004) — the type a focus would send. Emitted through mls-rs
        // directly: we deliberately have no builder for it.
        //
        // FOUR bytes, not three, and the width is load-bearing now. With gsma_rcs_e2ee_feature on,
        // 0xF004 decode round-trips a RemoveProposal (proposal.rs:351) whose body is a LeafIndex(u32)
        // — fixed 4 bytes network order. A 3-byte body no longer decodes, which is upstream
        // validation doing its job. That this test had to widen is itself a check: it confirms our
        // own hand-rolled 4-byte-BE body in MlsParticipantKeyResync agrees with upstream's shape.
        let mut gb = b.client.load_group(&gid_b).unwrap();
        let prop = gb.propose_custom(
            CustomProposal::new(ProposalType::from(0xF004u16), vec![0, 0, 0, 1]), Vec::new()).unwrap();
        gb.write_to_storage().unwrap();
        let wire = prop.to_bytes().unwrap();

        // A must learn WHICH proposal it is — status 2 alone made every type look alike.
        let r = a.process_ex(&gid_a, &wire).unwrap();
        assert_eq!(r[0], 2, "status must be PROPOSAL");
        assert_eq!(((r[1] as u16) << 8) | r[2] as u16, 0xF004, "the type must reach the caller");

        // …and A is now WEDGED: mls-rs refuses further application messages until it is committed.
        assert_eq!(a.commit_required(&gid_a).unwrap(), vec![1u8],
            "a cached proposal must block sends — this is the wedge");

        // Dropping it is what keeps the conversation alive. Committing instead would consume the
        // proposal and report success while removing nobody.
        a.clear_pending_proposals(&gid_a).unwrap();
        assert_eq!(a.commit_required(&gid_a).unwrap(), vec![0u8],
            "dropping the proposal must clear the block");
        let ct = a.encrypt(&gid_a, b"unwedged", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"unwedged");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The KeyPackage LeafNode lifetime must be anchored at the CERTIFICATE's `notBefore`,
    /// not at NOW — Google Messages does, and a now-anchored leaf is invalid on any peer whose clock
    /// trails ours. Asserted BOTH on the helper and on the bytes of a minted KeyPackage: the helper
    /// alone would pass even if the anchor never reached `generate_key_package_message`.
    #[test]
    fn key_package_lifetime_is_anchored_at_the_certificate() {
        const DAY: u64 = 24 * 3600;
        let leaf = td("lab2_leaf_pb.der");
        let c = Certificate::from_der(&leaf).unwrap();
        let nb = c.tbs_certificate.validity.not_before.to_unix_duration().as_secs();
        let na = c.tbs_certificate.validity.not_after.to_unix_duration().as_secs();

        // Google/Tachyon: Google Messages' exact window — not_before = cert.notBefore,
        // not_after = +365d (observed on the wire).
        assert_eq!(kp_lifetime_window(&leaf, true), (Some(nb), 365 * DAY));
        // Lab/openrcs-kds: same anchor, but the KP must not outlive the cert (R11).
        let (lab_anchor, lab_span) = kp_lifetime_window(&leaf, false);
        assert_eq!(lab_anchor, Some(nb));
        assert!(nb + lab_span < na, "a lab KeyPackage must expire before its certificate");

        // The default profile is lab, so a minted KP must carry exactly that window. kp_inspect reads
        // not_after back out of the KeyPackage bytes, so this pins the wire, not our arithmetic.
        let dir = format!("{}/kplife_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let s = ProdSession::start_no_revocation(&leaf, &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &dir).unwrap();
        let kp = split_len_prefixed(&s.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let info = s.kp_inspect(&kp).unwrap();
        assert_eq!(info[0], 0, "a pool KeyPackage is one-time, not last-resort");
        assert_eq!(u64::from_be_bytes(info[1..9].try_into().unwrap()), nb + lab_span);

        // The last-resort package carries the same window and reports its flag — the consume-side
        // gate reads both from one call.
        let lr = s.generate_last_resort_key_package().unwrap();
        let lri = s.kp_inspect(&lr).unwrap();
        assert_eq!(lri[0], 1, "the last-resort KeyPackage must carry the last_resort extension");
        assert_eq!(u64::from_be_bytes(lri[1..9].try_into().unwrap()), nb + lab_span);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// `kp_inspect` must report the leaf CERTIFICATE's window, and the LeafNode Lifetime must not be
    /// mistaken for it, which is the defect this pins rather than the fix.
    ///
    /// The claim-site floor read `[1..9]`, the RFC 9420 §7.2 `Lifetime.not_after`. On the TACHYON
    /// profile `kp_lifetime_window` anchors that at `cert.notBefore` and runs it a fixed 365 days,
    /// so it is a function of the certificate's START and says nothing about its END. The second
    /// assertion below is the unreachability itself, stated as arithmetic: a certificate ONE DAY
    /// from expiry still yields a Lifetime with ~334 days left, so a 30-day floor on that value
    /// cannot fire — it is not a strict floor, it is no floor at all.
    #[test]
    fn kp_inspect_reports_the_certificate_window_not_only_the_leaf_lifetime() {
        const DAY: u64 = 24 * 3600;
        let leaf = td("lab2_leaf_pb.der");
        let c = Certificate::from_der(&leaf).unwrap();
        let nb = c.tbs_certificate.validity.not_before.to_unix_duration().as_secs();
        let na = c.tbs_certificate.validity.not_after.to_unix_duration().as_secs();

        // THE UNREACHABILITY, as arithmetic and not as prose. Pure function, no session, no clock.
        let (anchor, span) = kp_lifetime_window(&leaf, true);
        assert_eq!((anchor, span), (Some(nb), 365 * DAY));
        let lifetime_na = nb + span;
        // Stand at the certificate's last full day and ask what each clock says.
        let one_day_before_cert_expiry = na - DAY;
        assert!(lifetime_na - one_day_before_cert_expiry > 30 * DAY,
            "the LeafNode Lifetime still reports {}d left when the CERTIFICATE has 1 day — a \
             30-day floor on the Lifetime is unreachable on the Tachyon profile",
            (lifetime_na - one_day_before_cert_expiry) / DAY);

        // AND THE WINDOW IS NOW REPORTED. Profile-independent: the certificate is the same object
        // whichever KDS rules produced the Lifetime, so the lab session proves the bytes.
        let dir = format!("{}/kpcert_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let s = ProdSession::start_no_revocation(&leaf, &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
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
        // The two clocks must be DIFFERENT numbers here, or this test would pass on a build that
        // reported the Lifetime twice.
        assert_ne!(u64::from_be_bytes(info[1..9].try_into().unwrap()), na,
            "the Lifetime not_after and the certificate notAfter are different artefacts");
        // The MSISDN still decodes, i.e. the window went IN FRONT of it and nothing ate it.
        let msisdn = String::from_utf8_lossy(&info[w + 16..]).to_string();
        assert!(msisdn.chars().all(|ch| ch.is_ascii_digit()),
            "the SAN identity must still be bare ASCII digits after the window, got {msisdn:?}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The anchor is in the PAST and inside the certificate window — never NOW.
    ///
    /// Anchoring at NOW is the defect itself: RFC 9420 §7.3 makes the RECEIVER validate our leaf
    /// against ITS clock, so `not_before == our now` fails for any peer trailing us. This asserts the
    /// two bounds that make it safe, and that the fallback is a real value rather than `None`.
    #[test]
    fn the_leaf_lifetime_anchor_is_in_the_past_and_inside_the_cert_window() {
        let dir = format!("{}/anchor_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let s = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &dir).unwrap();
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
            .unwrap().as_secs();
        let a = s.leaf_lifetime_anchor().expect("a readable clock must yield an anchor, not None");
        let secs = a.seconds_since_epoch();

        assert!(secs <= now, "the anchor must never be in the future — that IS the bug (got {secs}, now {now})");
        if let Some(nb) = s.kp_not_before {
            assert!(secs >= nb.seconds_since_epoch(),
                "the anchor must not predate our own certificate, or chain validation fails at it");
        }
        // Either it cleared the full skew margin, or the certificate floor stopped it short. Anything
        // else means the clamp collapsed to NOW.
        let floor = s.kp_not_before.map(|t| t.seconds_since_epoch()).unwrap_or(0);
        assert!(secs <= now - 24 * 3600 || secs == floor,
            "the anchor must clear the 24h skew margin unless the cert floor raised it (got {secs}, \
             now {now}, floor {floor})");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// No call site may mint a leaf without the shared anchor.
    ///
    /// This one root produced three separate bugs, each time because a NEW call
    /// site was written passing `None` while the known ones were already fixed. The failure only
    /// shows against a foreign peer, so nothing else in this suite can catch it: a fourth site would
    /// ship green and cost another week. Hence a source guard rather than a behavioural one.
    #[test]
    fn every_leaf_minting_call_site_carries_the_anchor() {
        let src = include_str!("ffi.rs");
        // Strip this test's own body so its literals do not count as call sites.
        let prod = src.split("fn every_leaf_minting_call_site_carries_the_anchor").next().unwrap();

        assert!(!prod.contains("generate_key_package_message(Default::default(), Default::default(), None)"),
            "a KeyPackage is being minted with None — it must pass self.kp_not_before");
        assert!(!prod.contains("create_group_with_id(gid_bytes, group_ctx, Default::default(), None)"),
            "a group is being created with None — it must pass self.leaf_lifetime_anchor()");
        // Every external commit mints a leaf, so every builder must be given a commit_time.
        let builders = prod.matches("external_commit_builder()").count();
        let stamped = prod.matches(".commit_time(t)").count();
        assert_eq!(builders, stamped,
            "{builders} external_commit_builder() call(s) but {stamped} commit_time() — an external \
             commit without one anchors its new leaf at NOW and is rejected by any peer whose clock \
             trails ours");
    }

    /// mls-rs must still mint a leaf Lifetime in exactly the three places we anchor — the guard that
    /// survives a version bump.
    ///
    /// The test above guards our CALLERS; this one guards the MINT itself, one level down. The
    /// invariant that actually matters is "a leaf Lifetime is never minted from an unanchored
    /// timestamp", and in mls-rs every mint is a `config.lifetime(…)` call. A version bump could add
    /// a fourth — and a bump is precisely when a new site would appear and nobody would be looking
    /// for one, because the existing call sites would all still compile and all still pass.
    ///
    /// If this fails after an mls-rs upgrade that is not a defect in the upgrade: go read the new
    /// site, decide whether it mints a leaf we send to a peer, and if it does, route it through
    /// `leaf_lifetime_anchor()` before updating the expected set here.
    #[test]
    fn mls_rs_mints_a_leaf_lifetime_in_exactly_the_three_places_we_anchor() {
        fn walk(dir: &std::path::Path, out: &mut Vec<(String, usize)>) {
            let Ok(rd) = std::fs::read_dir(dir) else { return };
            for e in rd.flatten() {
                let p = e.path();
                if p.is_dir() { walk(&p, out); continue; }
                if p.extension().and_then(|s| s.to_str()) != Some("rs") { continue; }
                let Ok(text) = std::fs::read_to_string(&p) else { continue };
                // `test_utils.rs` and `#[cfg(test)]` bodies mint lifetimes for assertions, not for
                // anything that reaches a peer. Count only what ships.
                if p.to_string_lossy().contains("test_utils") { continue; }
                let n = text.matches("config.lifetime(").count()
                    // client.rs:976 asserts against a minted KP inside a test module.
                    - text.matches("client.config.lifetime(None)").count();
                if n > 0 { out.push((p.to_string_lossy().into_owned(), n)); }
            }
        }
        let vendor = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../vendor/mls-rs-0.55.2/src");
        assert!(vendor.is_dir(), "vendored mls-rs not found at {vendor:?} — did the vendor path move?");
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

    /// Two sessions in a scratch dir, and one of B's KeyPackages. The fixture every `plan_group`
    /// test below needs.
    fn plan_fixture(tag: &str) -> (String, ProdSession, ProdSession, Vec<u8>) {
        let dir = format!("{}/plan_{}_{}", std::env::temp_dir().display(),
                          std::process::id(), tag);
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = b.generate_key_packages(4).unwrap();
        let bkp = split_len_prefixed(&kps).into_iter().next().unwrap();
        (dir, a, b, bkp)
    }

    /// A group nobody has ever held is born at era 1 — and NOT at whatever the server holds.
    ///
    /// This is the behaviour restored here. The host used to read `getMlsServerEraEpoch` and
    /// create at server+1 so as not to draw "Era changed from 1 to 1"; that host-chosen era is the
    /// shape that gets accepted and silently discarded. The engine sees no group and no carried
    /// state, so it says NEW_GROUP at era 1 — the only honest answer available from what it can see.
    #[test]
    fn plan_group_with_nothing_known_is_a_new_group_at_era_one() {
        let (dir, a, _b, bkp) = plan_fixture("new");
        let (era, action, add) = a.plan_group(&[bkp], b"grp-never-seen", &[]).unwrap();
        assert_eq!(era, crate::rcc16::ERA_INITIAL);
        assert_eq!(action, crate::rcc16::WELCOME_ACTION_NEW_GROUP);
        assert!(add.is_empty(), "a create admits members through its Welcome, not through slot 8");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A group we ALREADY hold advances past our own era — no host arithmetic anywhere.
    #[test]
    fn plan_group_over_a_group_we_hold_advances_past_our_own_era() {
        let (dir, a, b, bkp) = plan_fixture("adv");
        let gid = b"grp-held".to_vec();
        a.create_group_multi(3, std::slice::from_ref(&bkp), &gid).unwrap();
        // A DIFFERENT member: the same one would be a membership refresh, which is arm 3's
        // territory and is covered separately.
        let other = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (era, action, _) = a.plan_group(&[other], &gid, &[]).unwrap();
        assert_eq!(era, 4, "we hold era 3, so the next one is 4 — derived here, not passed in");
        assert_eq!(action, crate::rcc16::WELCOME_ACTION_NEW_ERA_EXISTING_GROUP);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// THE CASE THE INVERSION IS ABOUT: the SERVER is ahead of us, and the engine advances past the
    /// SERVER, not past itself — reading the era out of the GroupInfo bytes it was handed.
    ///
    /// Device-observed against a real peer on 2026-07-30 in the host's arithmetic: local era 1,
    /// server era 2, and local+1 would have targeted an era the server already held. Same maximum,
    /// taken one layer down, where 0xF001 is actually written.
    #[test]
    fn plan_group_advances_past_the_servers_era_when_the_server_is_ahead() {
        let (dir, a, b, bkp) = plan_fixture("srv");
        let gid = b"grp-behind".to_vec();
        // We hold era 1.
        a.create_group_multi(1, std::slice::from_ref(&bkp), &gid).unwrap();
        // The "server" holds era 5 — built by B so that its GroupInfo is genuinely foreign to A.
        let bkp2 = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (_, srv_art) = a.create_group_multi(5, &[bkp2], b"grp-server-copy").unwrap();
        let server_gi = split_len_prefixed(&srv_art)[2].clone();
        let other = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (era, action, _) = a.plan_group(&[other], &gid, &server_gi).unwrap();
        assert_eq!(era, 6, "the carried GroupInfo says era 5, so the next era is 6 — not our own 2");
        assert_eq!(action, crate::rcc16::WELCOME_ACTION_NEW_ERA_EXISTING_GROUP);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// THE REBUILD SHAPE: **no local group at all**, but the server's GroupInfo carried — the era
    /// must still be `server_era + 1`, not [`ERA_INITIAL`](crate::rcc16::ERA_INITIAL).
    ///
    /// The two neighbouring tests cover no-local/no-carry (arm 1, born at era 1) and
    /// local+carry (arm 2). The combination BETWEEN them had no test, and that gap is what shipped
    /// a defect: the host's automatic rebuild drops both halves of its state and re-establishes, so
    /// it arrives here with nothing local — and it was passing no carry either, which put it on arm
    /// 1 and built at era 1 against a server that already held era 1. Device-verbatim, 2026-09-08 on
    /// group `32725c4d…`: *"the server reports era 1 and so do we, but our epoch AUTHENTICATOR does
    /// not match its. That is a DIFFERENT GROUP at the same era."*
    ///
    /// What this pins is that the carry ALONE is sufficient — the decision must not require local
    /// state to be present, because the one path that most needs to advance is the one that just
    /// destroyed its own.
    #[test]
    fn plan_group_with_no_local_state_but_a_carry_rebuilds_past_the_servers_era() {
        let (dir, a, b, bkp) = plan_fixture("rebuild");
        // The "server" holds era 7, at an id we do NOT plan against — so the planning id has no
        // local group, exactly as it does not after a forget.
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

    /// The additive rule itself, over identities directly.
    ///
    /// Tested here rather than through `plan_group` because the fixtures carry exactly TWO certified
    /// MSISDNs (+15551110001 / +15551110002) and no ICA private key to mint a third, so a genuinely
    /// three-party roster is not constructible from them. The rule is the part worth pinning; the
    /// integration test below covers the boundary the fixtures CAN express.
    #[test]
    fn additions_are_selected_by_participant_and_not_by_device() {
        let a = b"+15551110001".to_vec();
        let b = b"+15551110002".to_vec();
        let c = b"+15551110003".to_vec();
        let held = vec![a.clone(), b.clone()];
        assert_eq!(crate::rcc16::additions_to(&held, &[a.clone(), b.clone(), c.clone()]), vec![2],
            "only the participant who is not already a member counts as an addition");
        // A SECOND DEVICE of somebody already present is NOT an addition — the RCS participant list
        // does not change, so there is nothing for the add RPC to name.
        assert!(crate::rcc16::additions_to(&held, &[b.clone(), b.clone()]).is_empty());
        // An identity we cannot read counts as NEW. Calling it already-present would silently drop
        // a member from the commit, which is invisible until they cannot decrypt anything.
        assert_eq!(crate::rcc16::additions_to(&held, &[Vec::new()]), vec![0]);
        // Nothing held: every request is an addition.
        assert_eq!(crate::rcc16::additions_to(&[], &[a, b]), vec![0, 1]);
    }

    /// A group we hold, asked for members who are ALL already in it, is a refresh — not an add.
    ///
    /// The boundary the two-identity fixtures can express, and it matters in its own right: arm 3
    /// requires SOME participant to be new. All-known falls through to the create/advance path,
    /// where the roster is rebuilt, rather than producing an addMembers commit that adds nobody.
    #[test]
    fn an_all_known_roster_is_not_an_add() {
        let (dir, a, b, bkp) = plan_fixture("add");
        let gid = b"grp-additive".to_vec();
        a.create_group_multi(2, std::slice::from_ref(&bkp), &gid).unwrap();
        // Another package from the SAME participant — a second device, not a new member.
        let second_device = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (era, action, add) = a.plan_group(&[bkp.clone(), second_device], &gid, &[]).unwrap();
        assert_eq!(action, crate::rcc16::WELCOME_ACTION_NEW_ERA_EXISTING_GROUP);
        assert_eq!(era, 3);
        assert!(add.is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A carried GroupInfo means somebody fetched server state, which is an ADVANCE by intent —
    /// never an add, even when the roster happens to look additive.
    ///
    /// Without this, a self-heal that fetched the server's GroupInfo and claimed fresh packages
    /// could be silently downgraded into an addMembers, and the era would never move — the exact
    /// stuck state the recovery path exists to escape.
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

    /// The bundle a planned create returns carries the engine's answer in slots 6 and 7.
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

    /// THE CANONICAL CAPABILITY LIST — every code point we ADVERTISE is either implemented or
    /// listed as deliberately advertised-only. This is the test that stops the two advertisement
    /// sites drifting apart again, which is exactly what had happened: production advertised
    /// 0xF001-0xF007 plus a vendor range while the conformance fixture advertised 0xF001 plus a
    /// WIDER range and no proposals at all.
    #[test]
    fn the_advertised_set_is_implemented_or_explicitly_advertised_only() {
        use crate::rcc16::*;
        let ext = advertised_extensions(&[]);
        for e in IMPLEMENTED_EXTENSIONS {
            assert!(ext.contains(e), "implemented ext {e:#06x} is NOT advertised — an \
                under-advertisement is as wrong as an over-advertisement");
        }
        // Everything advertised is accounted for: implemented, fixed advertised-only, the range,
        // or the metadata-keys code point (armed by default since v4.0 §7.11.10.1 published it —
        // it enters the advertisement through `metadata_keys_ext()` rather than through a list,
        // because it stays overridable in case a capture contradicts the document).
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
        // The specific regression: rcs_signature is implemented (sign+verify, device-proven) and
        // was silently absent from the advertisement.
        assert!(props.contains(&RCS_SIGNATURE_PROP));
        // The icon/subject KEYS are implemented, despite an earlier note recording them as structurally
        // blocked — commit_group_metadata builds them. Pinned so the stale reading cannot come back.
        assert!(ext.contains(&ICON_KEY_EXT) && ext.contains(&SUBJECT_KEY_EXT));
    }

    /// ARMING THE NUMBER MUST NOT ARM THE FRAMING.
    ///
    /// The inverse of what this test used to assert, and the change is evidential: v4.0 §7.11.10.1
    /// publishes 0xF007, so withholding it now would be refusing a spec fact rather than refusing a
    /// guess. The number really was unrecoverable from the binary — it was in a
    /// document that had not been published.
    ///
    /// What must NOT come with it is the framing. The value used to drive TWO behaviours at once:
    /// the advertisement and the bare-framing rule. Only the first has an answer. v4.0 says
    /// `opaque<V>` (framed) and a v3.0-era client emits bare, we have no capture, and a wrong
    /// `extension_data` is a different GroupContext hash — i.e. a group a peer rejects outright.
    /// So the code point is armed and `ext_payload_is_bare` still declines to answer.
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
        // Encoding now succeeds, because the framing turned out to be a revision difference rather
        // than an open question — v4.0 describes v4.0 and the measurement was of a v3.0-era client.
        assert!(ext_encode(METADATA_KEYS_REQUESTED_EXT, METADATA_KEYS_REQUESTED_DATA).is_ok(),
            "§10.5.2 needs a producer: a client asks for metadata keys via this extension");

        // Still clearable, so a capture that contradicts v4.0 can win without a rebuild.
        set_metadata_keys_ext(0);
        assert_eq!(metadata_keys_ext(), None);
        assert!(!advertised_extensions(&[]).contains(&METADATA_KEYS_REQUESTED_EXT));
        set_metadata_keys_ext(METADATA_KEYS_REQUESTED_EXT);
    }

    /// The continuity commitment reaches a GroupInfo, and ONLY under v4.0.
    ///
    /// Round-tripped through a real group rather than asserted about the encoder, because the thing
    /// that matters is whether a PEER parsing our GroupInfo finds the extension where the spec says
    /// it is — and that goes through mls-rs's GroupInfo signing and serialisation, not just ours.
    #[test]
    fn the_continuity_commitment_lands_in_the_group_info_only_under_v4() {
        use crate::rcc16::*;
        let _v = crate::rcc16::VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = format!("{}/cont_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/b")).unwrap();
        let kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());
        let (gid, _artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let commitment = vec![0xAB; 32];

        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        let gi3 = a.group_info_with_continuity(&gid, &commitment, false).unwrap();
        assert!(a.group_info_continuity(&gi3, CONTINUITY_TOKEN_COMMITMENT_EXT).unwrap().is_empty(),
            "v3.0 must not put a code point on the wire that Tachyon has never been seen to carry");

        assert_eq!(set_rcc16_version(RCC16_V4_0), RCC16_V4_0);
        let gi4 = a.group_info_with_continuity(&gid, &commitment, false).unwrap();
        assert_eq!(a.group_info_continuity(&gi4, CONTINUITY_TOKEN_COMMITMENT_EXT).unwrap(),
                   commitment, "the DECODED value must round-trip through the varint framing");
        // The TOKEN never appears in a server-bound GroupInfo, whatever the revision.
        assert!(a.group_info_continuity(&gi4, CONTINUITY_TOKEN_EXT).unwrap().is_empty(),
            "0xF010 is Welcome-only — putting it here hands the server the group secret");

        // Reading is not gated: a v4.0 GroupInfo is still understood after we announce v3.0.
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        assert_eq!(a.group_info_continuity(&gi4, CONTINUITY_TOKEN_COMMITMENT_EXT).unwrap(),
                   commitment, "decode tolerantly — a server may upgrade before we notice");

        // AND THE READ IS NO LONGER RESTRICTED TO THE TWO CONTINUITY CODE POINTS — because the
        // restriction was silently breaking a DIFFERENT measurement. The host calls this same
        // function as an arbitrary-type GroupInfo extension reader for 0x0004 external_pub and
        // 0x0005 external_senders; the old guard returned Err for those, `logged()` maps an Err to
        // NULL_BYTES, the JNI maps that to a Java null, and the host logs null as "ABSENT". So
        // external_pub and external_senders read ABSENT unconditionally, whatever the server sent —
        // and that null read is what the "external commit cannot rescue a behind member" result was
        // measured on. Third instance in one day of a probe that cannot see what it reports on.
        //
        // ERA_EXT is the right positive to pin it with: 0xF001 lives in the GROUPCONTEXT, so one
        // assertion covers both the arbitrary-type read AND the GroupContext arm this function
        // grew. If either regresses, this fails.
        let (era_val, era_where) = a.group_info_ext_either_list(&gi4, ERA_EXT).unwrap();
        assert_eq!(era_where, "GroupContext",
            "0xF001 is a GroupContext extension — reading only gi.extensions() misses it, which is \
             exactly how an earlier probe got the same answer wrong twice");
        assert!(!era_val.is_empty(), "the era extension must read back non-empty");

        // An extension in neither list reports absent, and says so as a location rather than as an
        // empty value that could equally mean "read the wrong list".
        let (none_val, none_where) = a.group_info_ext_either_list(&gi4, 0x0BAD).unwrap();
        assert!(none_val.is_empty());
        assert_eq!(none_where, "absent");
    }

    /// The transport override adds without duplicating, and cannot silently drop the engine's own set.
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

    /// The u32 ceiling is REFUSED, not wrapped. Google Messages panics here rather than wrapping,
    /// because a wrap reads to every peer as a move backwards and cannot be undone.
    #[test]
    fn the_era_ceiling_is_refused_rather_than_wrapped() {
        assert_eq!(crate::rcc16::next_era(41).unwrap(), 42);
        assert!(crate::rcc16::next_era(u32::MAX).is_err());
    }

    /// **The treeless-Welcome splice on the 2-leaf (1:1) tree, against a REAL mls-rs group.**
    ///
    /// `splice_ratchet_tree` takes the node sequence VERBATIM from the first leaf's `01 01` prefix
    /// to the last leaf's final byte, on the reasoning that a left-balanced tree puts leaves at the
    /// two ends and everything in between is already canonical `optional<Node>`. Until now the only
    /// test in that module built a vec of ONE leaf, so the "everything in between" half of the claim
    /// — the part that carries the parent — was never exercised at all. A 1:1 conversation is a
    /// 2-leaf group with exactly ONE parent, which is both the most common shape we handle and the
    /// smallest one where the claim says anything.
    ///
    /// The oracle here is not a hand-built byte vector: it is the tree mls-rs itself exported for a
    /// real group, and a real Welcome joined through `join_treeless_welcome`. The join is what makes
    /// it a proof rather than a restatement — `join_group` recomputes the tree hash and verifies
    /// every leaf signature over the spliced bytes, so a splice that dropped the parent, kept a byte
    /// too many, or mis-framed the outer length would fail there, not merely compare unequal.
    ///
    /// Byte layout as measured: `[4c ad][01 01 <leaf0 1618B>][00][01 01 <leaf1 1622B>]`
    /// — the parent (node 1) is the single blank `00` between the leaves, because an add-only commit
    /// omits the UpdatePath (RFC 9420 §12.4). The populated-parent shape is covered by
    /// `treeless_welcome_splices_a_two_leaf_group_with_a_populated_parent`.
    #[test]
    fn treeless_welcome_joins_a_real_two_leaf_group() {
        let dir = format!("{}/tw2leaf_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        let kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());
        let (gid_a, artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let (welcome, tree) = (&parts[0], &parts[5]);
        assert_eq!(a.client.load_group(&gid_a).unwrap().roster().members().len(), 2,
            "the shape under test is a 1:1 group — two leaves, one parent");

        // The wire shape: the Welcome and the node sequence in ONE blob, with transport framing
        // around both. The bytes either side are there so the locator has to reject them.
        let mut blob = vec![0x0au8, 0x8f, 0x01, 0x12, 0x04, 0xde, 0xad, 0xbe];
        blob.extend_from_slice(welcome);
        let tree_at = blob.len();
        blob.extend_from_slice(tree);
        blob.extend_from_slice(&[0x18, 0x01, 0x22, 0x00, 0x2a, 0x08, 0xca, 0xfe]);

        // 1. BOTH leaves are found, and only those two — a 412B Welcome and the framing contribute
        //    no false match. One leaf here would still splice, into a truncated tree.
        let leaves = locate_leaves(&blob);
        assert_eq!(leaves.len(), 2, "a 2-leaf group must yield exactly two leaf spans, got {leaves:?}");
        assert_eq!(leaf_count(&blob), 2);

        // 2. THE CLAIM UNDER TEST: the parent's bytes lie physically between the two leaves, so the
        //    span from leaf0's `01 01` to leaf1's end is the whole node sequence. For an add-only
        //    commit that parent is blank, and blank is exactly one byte — so if the splice were
        //    reaching over something rather than through it, this gap would not be 1.
        let gap = &blob[leaves[0].1..leaves[1].0 - 2];
        assert_eq!(gap, &[0x00],
            "node 1 must sit between the leaves as a single blank optional<Node>, got {gap:02x?}");

        // 3. The spliced tree is BYTE-IDENTICAL to what mls-rs exported, outer varint included.
        let spliced = splice_ratchet_tree(&blob).expect("splice");
        assert_eq!(&spliced, tree,
            "the splice must reproduce the exported ratchet_tree exactly — same length framing, \
             same node sequence, nothing of the surrounding blob dragged in");
        assert_eq!(leaves[0].0 - 2, tree_at + 2, "leaf0's node prefix follows the outer varint");

        // 4. THE REAL ORACLE: join through it. mls-rs recomputes the tree hash and verifies every
        //    leaf signature inside join_group, then the group has to carry traffic.
        let gid_b = b.join_treeless_welcome(welcome, &blob)
            .expect("a 2-leaf treeless Welcome must join");
        let ct = a.encrypt(&gid_a, b"one to one", &[]).unwrap();
        assert_eq!(b.process(&gid_b, &ct).unwrap(), b"one to one",
            "a joined member that cannot read traffic joined the wrong tree");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The same 2-leaf splice when the single parent is POPULATED rather than blank.
    ///
    /// The blank case above cannot distinguish "the splice steps through the parent" from "the
    /// splice happens to skip one byte", and the parents this code exists for are populated — the
    /// module header records a leaves-only tree failing `TreeHashMismatch` on device (2026-07-23)
    /// precisely because a real committer's UpdatePath had filled them in. A commit carrying a path
    /// at two members produces that shape: node 1 becomes `01 02 <ParentNode>`, 71 bytes here.
    ///
    /// The oracle is `external_join` fed the SPLICED tree against the same epoch's GroupInfo:
    /// mls-rs checks the supplied tree against the GroupInfo's tree hash and confirmation tag before
    /// it will build the external commit, so a parent lost or corrupted by the splice is refused
    /// there. Byte equality alone would only say we recovered what we handed in.
    #[test]
    fn treeless_welcome_splices_a_two_leaf_group_with_a_populated_parent() {
        let dir = format!("{}/tw2par_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pa_priv.bin"),
            &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pb_priv.bin"),
            &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/b")).unwrap();
        // A third identity, so the spliced tree can be validated by someone not already in the group.
        let c = ProdSession::start_no_revocation(&td("lab2_leaf_pc.der"), &[td("lab2_ica.der")], &td("lab2_leaf_pc_priv.bin"),
            &td("lab2_leaf_pc_pub.bin"), &[td("lab2_root.der")], &format!("{dir}/c")).unwrap();
        let kps = split_len_prefixed(&b.generate_key_packages(1).unwrap());
        let (gid_a, artifacts) = a.create_group_multi(1, &kps, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();

        // A path commit at two members fills in node 1.
        let up = split_len_prefixed(&a.self_update_extpub(&gid_a, b"mid-pathcommit").unwrap());
        b.process(&gid_b, &up[1]).unwrap();
        let (ginfo, tree) = (&up[2], &up[5]);

        let mut blob = Vec::new();
        blob.extend_from_slice(ginfo);
        blob.extend_from_slice(tree);
        let leaves = locate_leaves(&blob);
        assert_eq!(leaves.len(), 2, "still two leaves after a path commit, got {leaves:?}");

        // The parent is a real ParentNode now, and it is where the blank one was.
        let gap = &blob[leaves[0].1..leaves[1].0 - 2];
        assert_eq!(&gap[..2], &[0x01, 0x02],
            "node 1 must be a PRESENT parent (01 02), got {:02x?}", &gap[..2.min(gap.len())]);
        assert!(gap.len() > 2,
            "a populated parent carries an HPKE key — a 2-byte one would mean we read a header and \
             then walked off the node the splice is supposed to carry");

        let spliced = splice_ratchet_tree(&blob).expect("splice");
        assert_eq!(&spliced, tree, "the splice must carry the populated parent through verbatim");

        // The cryptographic oracle: mls-rs validates this tree against the GroupInfo's tree hash.
        c.external_join(ginfo, &spliced)
            .expect("an external commit against the SPLICED tree must validate — if the parent were \
                     dropped or mangled the tree hash would not match the GroupInfo");
        let _ = std::fs::remove_dir_all(&dir);
    }

    // =========================================================================================
    // the peer's advertised cipher suites
    // =========================================================================================

    /// Prefix `b` with its MLS varint length (test helper: lengths here are all < 64, one byte).
    fn v(b: &[u8]) -> Vec<u8> {
        assert!(b.len() < 64, "test helper only encodes 1-byte varints");
        let mut o = vec![b.len() as u8];
        o.extend_from_slice(b);
        o
    }

    /// A synthetic RFC-9420 KeyPackage prefix, built by hand up to and past `cipher_suites`.
    /// `cred_type` and `suites` are the two things the walk has to get right.
    fn synthetic_kp(cred_type: u16, suites: &[u8]) -> Vec<u8> {
        let mut kp = vec![0x00, 0x01, 0x00, 0x02];  // version=mls10, cipher_suite=P256_AES128
        kp.extend(v(&[0xAA; 3]));                   // init_key<V>
        kp.extend(v(&[0xBB; 3]));                   // LeafNode.encryption_key<V>
        kp.extend(v(&[0xCC; 3]));                   // LeafNode.signature_key<V>
        kp.extend_from_slice(&cred_type.to_be_bytes());
        kp.extend(v(b"tel:+15551234567"));          // basic identity / x509 chain — one <V> either way
        kp.extend(v(&[0x00, 0x01]));                // capabilities.versions<V> = [mls10]
        kp.extend(v(suites));                       // capabilities.cipher_suites<V>
        kp.extend(v(&[]));                          // capabilities.extensions<V> — never reached
        kp
    }

    /// THE WALK LANDS ON THE FIELD, and follows it when the field moves.
    ///
    /// A parser that returned a plausible constant would pass a single-vector test, so this asserts
    /// TWO different advertisements through the same code and requires the answers to differ. The
    /// GREASE code point (0x0A0A) is deliberate: mls-rs strips those before it validates a leaf, and
    /// this must NOT — the host is comparing an advertisement against one suite it can run, and a
    /// silently shortened list makes "absent" indistinguishable from "filtered".
    #[test]
    fn leaf_cipher_suites_reads_the_second_capabilities_vector() {
        let kp = synthetic_kp(1, &[0x00, 0x01, 0x00, 0x02, 0x0A, 0x0A]);
        assert_eq!(leaf_cipher_suites(&kp).unwrap(), vec![0x0001u16, 0x0002, 0x0A0A]);

        // A DIFFERENT list must read back differently — the oracle can fail.
        let kp2 = synthetic_kp(1, &[0x00, 0x07]);
        assert_eq!(leaf_cipher_suites(&kp2).unwrap(), vec![0x0007u16]);

        // Empty is legal on the wire and is NOT an error: it means the peer advertises nothing,
        // which the host must read as "unknown", not as "does not support suite 2".
        assert_eq!(leaf_cipher_suites(&synthetic_kp(1, &[])).unwrap(), Vec::<u16>::new());

        // x509(2) has the same one-<V> shape as basic(1), and the MlsMessage-wrapped form is
        // accepted exactly as `kp_inspect` accepts it.
        let x509 = synthetic_kp(2, &[0x00, 0x02]);
        assert_eq!(leaf_cipher_suites(&x509).unwrap(), vec![0x0002u16]);
        assert_eq!(leaf_cipher_suites(&wrap_key_package(&x509)).unwrap(), vec![0x0002u16]);
    }

    /// An advertisement we cannot READ is refused, never guessed.
    ///
    /// Every failure here would otherwise produce a list of arbitrary bytes reinterpreted as suite
    /// code points — which is worse than no answer, because it looks like an answer. The caller
    /// turns an Err into "unknown" (see `kp_inspect`); what must not happen is a confident wrong
    /// list reaching `MlsCipherSuite.negotiate`.
    #[test]
    fn an_unreadable_advertisement_is_an_error_not_a_guess() {
        // A credential type whose body shape we do not know — the offsets after it are meaningless.
        assert!(leaf_cipher_suites(&synthetic_kp(3, &[0x00, 0x02])).is_err(),
            "an unknown credential_type must not be walked past");
        // An odd byte count cannot be a whole number of u16 code points.
        assert!(leaf_cipher_suites(&synthetic_kp(1, &[0x00, 0x02, 0x00])).is_err(),
            "an odd-length cipher_suites vector is malformed, not a truncated list");
        // Truncation ANYWHERE before the field is complete. Stated as a property rather than as a
        // list of offsets: find the shortest prefix that parses, require every shorter one to fail,
        // and require that prefix to give the right answer. That also pins the walk's REACH — it
        // must stop at `cipher_suites` and never depend on the rest of the LeafNode, which is what
        // makes it safe against a peer whose later fields we do not model.
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

    /// The walk agrees with a REAL mls-rs-minted KeyPackage — two independent producers.
    ///
    /// The synthetic test above pins the walk against bytes this file wrote, which cannot catch a
    /// field-order mistake shared by both. This one mints through mls-rs and requires the answer to
    /// equal the suites the crypto provider was configured with at `start` — a set chosen to match
    /// Google Messages' `[1,3,2,7]` advertisement (see the capabilities comment at the builder).
    ///
    /// It also pins the `kp_inspect` layout: the three older fields must not have moved, the
    /// package's OWN suite is the group suite, and the MSISDN — the only field with no length of
    /// its own — must still decode from behind the new ones.
    #[test]
    fn kp_inspect_reports_the_peers_suites_alongside_its_own() {
        let dir = format!("{}/kpsuites_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let s = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
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
        // Bare E.164 digits, no '+' — the shape `keyPackageUsable` compares against the number it
        // queried. What matters here is that it survived the layout change intact.
        // The 16 bytes skipped are the leaf CERTIFICATE's Validity window, which went
        // IN FRONT of the MSISDN for the reason the layout note gives.
        let msisdn = String::from_utf8(info[12 + 2 * n + 16..].to_vec()).unwrap();
        assert!(msisdn.len() > 5 && msisdn.bytes().all(|b| b.is_ascii_digit()),
            "the certified SAN identity must still decode from behind the new fields, got {msisdn:?}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    // =========================================================================================
    // a Welcome's own GroupInfo extensions
    // =========================================================================================

    /// WHY THE EXISTING PROBES COULD NEVER HAVE SEEN 0xF010 — the fact the whole question turns on.
    ///
    /// RFC 9420 §11 gives a GroupInfo two extension lists: `group_context.extensions` (era,
    /// icon_key, subject_key — group-wide consensus state) and `GroupInfo.extensions` (ratchet_tree,
    /// external_pub — carried by THAT GroupInfo). RCC.16 v4.0 §7.11.12.1 puts the continuity token
    /// in the SECOND one, in a Welcome.
    ///
    /// `group_ext` and `group_info_ext_types` both read the FIRST. So this asserts, on a real group,
    /// that an extension living in the second is invisible to them — which is exactly why the
    /// `--ez groupexts` dump and the host's `WELCOME-EXT` probe (`groupExt(gid, 0xF010..0xF018)`)
    /// returned "none" and could not have returned anything else. Not a bug in either; a blindness.
    ///
    /// `ratchet_tree` (0x0002) stands in for the token: it is the extension mls-rs puts in the
    /// GroupInfo's own list, and no code of ours can put 0xF010 there.
    #[test]
    fn a_groupinfos_own_extensions_are_invisible_to_the_group_context_probes() {
        let dir = format!("{}/giown_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/b")).unwrap();
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid, _) = a.create_group(1, &bkp, &[]).unwrap();

        // A GroupInfo that carries the tree in its OWN extension list.
        let gi_bytes = a.group_info_with_continuity(&gid, &[], true).unwrap();
        let gi = MlsMessage::from_bytes(&gi_bytes).unwrap().into_group_info().unwrap();
        const RATCHET_TREE: u16 = 0x0002;
        assert!(gi.extensions().get(ExtensionType::from(RATCHET_TREE)).is_some(),
            "the GroupInfo's OWN list must carry the tree — otherwise this test proves nothing");
        assert!(gi.group_context().extensions().get(ExtensionType::from(RATCHET_TREE)).is_none(),
            "the GroupContext must NOT carry it: the two lists are different lists");

        // THE PROBES. Both read the GroupContext, so neither can see it.
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

    /// The instrument's verdict line, both ways round. Shown able to FAIL before it is trusted:
    /// the same formatter must print the OVERTURNS branch when 0xF010 is present and the
    /// this-committer-only branch when it is not, so a "no token" line on a device is a reading
    /// rather than a default.
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
        assert!(found.contains(" 0x0002=7B"), "every extension is listed, not only the token: {found}");
        assert!(found.contains("0xF010 IS PRESENT"), "{found}");
        assert!(found.contains("committer_leaf=3"), "the committer is named: {found}");
        assert!(found.contains("join_with_tree"), "the join path is named: {found}");
    }

    /// THE INSTRUMENT IS WIRED TO THE JOIN, not merely present.
    ///
    /// The formatter test above would pass on a function nothing calls. This drives a real
    /// create-group → Welcome → join and asserts the line the join actually emitted, so a future
    /// edit that drops the call site — or that reads the GroupContext by mistake — fails here.
    ///
    /// It also records what our OWN Welcomes look like: we build them with
    /// `with_ratchet_tree_extension(false)`, so the GroupInfo's own list is empty and the line reads
    /// `(none)`. On device that is the expected shape for a Welcome WE sent; the question is
    /// only answered by a Welcome a REAL peer built.
    #[test]
    fn the_welcome_instrument_fires_on_a_real_join() {
        let dir = format!("{}/wgi_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
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
        // Our own Welcomes carry no GroupInfo extensions at all, tree included.
        assert!(line.contains(" (none)"), "our Welcome's GroupInfo has no own extensions: {line}");
        assert!(line.contains("no 0xF010"), "{line}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    // =========================================================================================
    // the Welcome-borne continuity token reaches the host instead of being dropped
    // =========================================================================================

    /// Build an add-commit whose WELCOME carries `token_ext_data` at 0xF010 — i.e. a Welcome
    /// shaped like the one Google Messages built for us on 2026-09-08.
    ///
    /// We have no production path that does this, and deliberately so: [`emit_continuity`] is gated
    /// and the decision to mint is unsettled. So the only way to exercise the RECEIVE
    /// half end to end is for the test to play the peer, using mls-rs's own
    /// `CommitBuilder::set_group_info_ext` — which is exactly the API §9.7.1.3's "put the token in
    /// the Welcome" describes. Returns the 6-record bundle `commit_bundle` produces.
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
        let commit = g.commit_builder()
            .add_member(kp).unwrap()
            .set_group_info_ext(exts)
            .build().unwrap();
        commit_bundle(&mut g, commit).unwrap()
    }

    /// THE WHOLE POINT, end to end: a peer hands us a token in a Welcome and the host
    /// can collect it.
    ///
    /// Every assertion here failed before the change — the token reached `log_welcome_group_info_exts`
    /// and nothing else. The shape driven is the measured one: `0x20 ‖ 32` = 33 bytes on the wire,
    /// decoding to the 32-byte value §8.3.1.1 specifies.
    #[test]
    fn a_welcome_borne_continuity_token_survives_the_join_and_is_handed_to_the_host() {
        let dir = format!("{}/wct_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/a")).unwrap();
        let b = ProdSession::start_no_revocation(&td("lab2_leaf_pb.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pb_priv.bin"), &td("lab2_leaf_pb_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/b")).unwrap();
        let c = ProdSession::start_no_revocation(&td("lab2_leaf_pc.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pc_priv.bin"), &td("lab2_leaf_pc_pub.bin"), &[td("lab2_root.der")],
            &format!("{dir}/c")).unwrap();

        // THE CONTROL, and it has to come first: our OWN Welcome carries no token, so B's join
        // must yield nothing. Without this the test cannot tell "captured the token" from
        // "returns something for every join".
        let bkp = split_len_prefixed(&b.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        let (gid, artifacts) = a.create_group(1, &bkp, &[]).unwrap();
        let parts = split_len_prefixed(&artifacts);
        let gid_b = b.join_with_tree(&parts[0], &parts[5]).unwrap();
        assert!(b.take_welcome_continuity_token(&gid_b).unwrap().is_empty(),
            "we mint no token, so a Welcome WE built must hand the joiner nothing");

        // Now A plays a peer that DOES do continuity, in the measured framing.
        let token: Vec<u8> = (0u8..32).collect();
        let framed = crate::rcc16::ext_encode(crate::rcc16::CONTINUITY_TOKEN_EXT, &token).unwrap();
        assert_eq!(framed.len(), 33, "the measured wire shape is 0x20 ‖ 32");
        let ckp = split_len_prefixed(&c.generate_key_packages(1).unwrap())
            .into_iter().next().unwrap();
        LAST_WELCOME_GI_EXT_LINE.with(|x| *x.borrow_mut() = None);
        let bundle = split_len_prefixed(&add_member_with_welcome_ext(
            &a, &gid, &ckp, crate::rcc16::CONTINUITY_TOKEN_EXT, framed));
        let gid_c = c.join_with_tree(&bundle[0], &bundle[5]).unwrap();
        assert_eq!(gid_c, gid, "same group");

        // The instrument still reports it — the capture did not replace the measurement.
        let line = LAST_WELCOME_GI_EXT_LINE.with(|x| x.borrow().clone()).unwrap();
        assert!(line.contains(" 0xF010=33B"), "the exact reading: {line}");

        // AND THE VALUE IS NOW REACHABLE, decoded, exactly once.
        assert_eq!(c.take_welcome_continuity_token(&gid_c).unwrap(), token,
            "the joiner must be handed the DECODED 32-byte token, not the 33-byte framing");
        assert!(c.take_welcome_continuity_token(&gid_c).unwrap().is_empty(),
            "taking is a hand-off: a second call must not re-deliver, or a caller would read the \
             repeat as a re-mint");
        // The ADDER captured nothing: it never joined, and §7.11.12.1 is about new joiners.
        assert!(a.take_welcome_continuity_token(&gid).unwrap().is_empty(),
            "capture is on the join, not on the commit that built the Welcome");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A token in the OTHER framing, and a token we cannot read at all.
    ///
    /// The bare arm is tolerance rather than measurement (see `bare_payload_is_plausible`); the
    /// undecodable arm is the one that matters operationally — <b>a Welcome must still join</b>.
    /// Continuity ends in a §11.2 downgrade, and refusing to enter a group over an extension
    /// nothing yet consumes would be a far larger harm than the drop this change fixes.
    #[test]
    fn a_bare_token_is_tolerated_and_an_unreadable_one_never_fails_the_join() {
        let dir = format!("{}/wctb_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let mk = |who: &str, n: &str| ProdSession::start_no_revocation(
            &td(&format!("lab2_leaf_p{n}.der")), &[td("lab2_ica.der")],
            &td(&format!("lab2_leaf_p{n}_priv.bin")), &td(&format!("lab2_leaf_p{n}_pub.bin")),
            &[td("lab2_root.der")], &format!("{dir}/{who}")).unwrap();
        let token: Vec<u8> = (0u8..32).map(|i| i ^ 0x5A).collect();

        // BARE — no inner length. Same 32 bytes must come back.
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

        // UNREADABLE — a length prefix that does not describe the rest. THE JOIN MUST STILL WORK.
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

    /// The hand-off queue is BOUNDED, so a host that never collects cannot grow it without limit.
    ///
    /// Not hypothetical: the collector lives in another module, in another language, behind a JNI
    /// boundary — exactly the arrangement where "the caller always drains it" stops being true.
    #[test]
    fn the_welcome_token_handoff_queue_is_bounded() {
        let dir = format!("{}/wctq_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        let a = ProdSession::start_no_revocation(&td("lab2_leaf_pa.der"), &[td("lab2_ica.der")],
            &td("lab2_leaf_pa_priv.bin"), &td("lab2_leaf_pa_pub.bin"), &[td("lab2_root.der")],
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
        // FIFO: the oldest four are gone, the newest are still collectable.
        assert!(a.take_welcome_continuity_token(&[0u8]).unwrap().is_empty(), "oldest evicted");
        assert_eq!(a.take_welcome_continuity_token(
            &[(WELCOME_CONTINUITY_SLOTS + 3) as u8]).unwrap().len(), 32, "newest retained");
        let _ = std::fs::remove_dir_all(&dir);
    }

}


/// Render an MLS group id the way Google Messages prints it inside `GroupId: "…"`.
///
/// A GROUP's id is the RCS gid shape and a 1:1's is a dashed UUID; in both cases our stored bytes
/// ARE the ASCII of that string, so print ASCII when printable and fall back to hex. Assuming one
/// encoding would produce an undiffable line for the other.
fn gid_str(gid: &[u8]) -> String {
    if !gid.is_empty() && gid.iter().all(|b| (0x20..=0x7e).contains(b)) {
        return String::from_utf8_lossy(gid).into_owned();
    }
    gid.iter().map(|b| format!("{b:02x}")).collect()
}

/// The group's RCC.16 era (GroupContext extension 0xF001), defaulting to 1 when absent.
///
/// Same read as `era_epoch`, against the live group we already hold rather than a fresh load — the
/// §20.4 moment lines bracket ONE apply, so reloading between them could straddle a write and print
/// a pair that never existed. Missing extension defaults to 1, matching `era_epoch`.
fn era_of(g: &Group<ProdConfig>) -> Option<u32> {
    Some(g.context().extensions()
        .get(ExtensionType::from(crate::rcc16::ERA_EXT))
        .and_then(|e| e.extension_data.get(0..4)
            .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]])))
        .unwrap_or(1))
}

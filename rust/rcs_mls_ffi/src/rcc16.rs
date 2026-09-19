//
// SPDX-FileCopyrightText: The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

//! RCC.16 inside the MLS engine: extension and proposal registries, capability advertisement, the
//! announced spec revision, `end_mls` payloads, extension framing, era rules (`Rcc16MlsRules`) and
//! the application-message `AuthenticatedData`. See docs/mls/rust-core.md.
use mls_rs_core::extension::{ExtensionType, ExtensionList as CoreExtensionList};
use mls_rs::mls_rs_codec::{MlsDecode, MlsEncode, VarInt};
use mls_rs::MlsRules;
use mls_rs::group::{GroupContext, Roster};
use mls_rs::mls_rules::{CommitDirection, CommitOptions, CommitSource, DefaultMlsRules,
    EncryptionOptions, ProposalBundle};

// For the test clients below.
#[cfg(test)]
use mls_rs::{
    client_builder::MlsConfig, group::ReceivedMessage,
    CipherSuite, Client, ExtensionList, MlsMessage,
};
#[cfg(test)]
use mls_rs_core::{crypto::{SignaturePublicKey, SignatureSecretKey}, identity::{SigningIdentity,
    Credential}, extension::Extension};
#[cfg(test)]
use core::time::Duration;
#[cfg(test)]
use mls_rs_identity_x509::{CertificateChain, DerCertificate, SubjectIdentityExtractor,
    X509IdentityProvider};
#[cfg(test)]
use mls_rs_crypto_rustcrypto::{x509::{X509Reader, X509Validator}, RustCryptoProvider};

#[cfg(test)]
const SUITE: CipherSuite = CipherSuite::P256_AES128;

// Smoke entry points, test-only: they build clients from embedded private keys, which must not
// ship in the static library.
#[cfg(test)]
// Test fixtures: P-256 root, ICA and leaf; the leaf has a tel: SAN and a critical .4 extension.
#[cfg(test)]
const ROOT: &[u8] = include_bytes!("../testdata/root.der");
#[cfg(test)]
const ICA:  &[u8] = include_bytes!("../testdata/ica.der");
#[cfg(test)]
const LEAF_A: &[u8] = include_bytes!("../testdata/leaf_a.der");
#[cfg(test)]
const LEAF_A_PRIV: &[u8] = include_bytes!("../testdata/leaf_a_priv.bin");
#[cfg(test)]
const LEAF_A_PUB:  &[u8] = include_bytes!("../testdata/leaf_a_pub.bin");
#[cfg(test)]
const LEAF_B: &[u8] = include_bytes!("../testdata/leaf_b.der");
#[cfg(test)]
const LEAF_B_PRIV: &[u8] = include_bytes!("../testdata/leaf_b_priv.bin");
#[cfg(test)]
const LEAF_B_PUB:  &[u8] = include_bytes!("../testdata/leaf_b_pub.bin");

/// A client whose credential is the given X.509 chain (leaf first), validated against `root`. The
/// subject key (32-byte scalar, 65-byte SEC1 point) is the key the leaf certifies.
#[cfg(test)]
fn build_x509_client(
    leaf: &[u8], chain: &[&[u8]], priv32: &[u8], pub65: &[u8], roots: &[&[u8]],
) -> Result<Client<impl MlsConfig>, String> {
    let mut certs = vec![DerCertificate::from(leaf.to_vec())];
    for c in chain { certs.push(DerCertificate::from(c.to_vec())); }
    let cert_chain = CertificateChain::from(certs);
    let credential = cert_chain.into_credential();
    let signing_identity =
        SigningIdentity::new(credential, SignaturePublicKey::from(pub65.to_vec()));
    let secret = SignatureSecretKey::from(priv32.to_vec());

    let extractor = SubjectIdentityExtractor::new(0, X509Reader::new());
    let root_list: Vec<DerCertificate> =
        roots.iter().map(|r| DerCertificate::from(r.to_vec())).collect();
    let validator = X509Validator::new(root_list).map_err(|e| format!("validator: {e:?}"))?;
    let idp = X509IdentityProvider::new(extractor, validator);

    // The production capability lists; KeyPackage lifetime 365 days.
    let ext_types: Vec<ExtensionType> =
        advertised_extensions(&[]).into_iter().map(ExtensionType::from).collect();
    let prop_types: Vec<mls_rs_core::group::ProposalType> =
        advertised_proposals(&[]).into_iter().map(mls_rs_core::group::ProposalType::from).collect();
    Ok(Client::builder()
        .crypto_provider(RustCryptoProvider::default())
        .identity_provider(idp)
        .extension_types(ext_types)
        .custom_proposal_types(prop_types)
        .key_package_lifetime(Duration::from_secs(365 * 24 * 3600))
        .signing_identity(signing_identity, secret, SUITE)
        .build())
}

/// X.509 round trip: A adds B and B joins, each validating the other's chain with its critical
/// extension, then both directions encrypt and decrypt. Every step runs at `PKI2_NOW`, inside the
/// v1 leaves' window, never at the wall clock.
#[cfg(test)]
pub fn x509_roundtrip() -> Result<(), String> {
    let now = mls_rs_core::time::MlsTime::from(crate::rcc16_validate::PKI2_NOW);
    let a = build_x509_client(LEAF_A, &[ICA], LEAF_A_PRIV, LEAF_A_PUB, &[ROOT])?;
    let b = build_x509_client(LEAF_B, &[ICA], LEAF_B_PRIV, LEAF_B_PUB, &[ROOT])?;
    let b_kp = b.generate_key_package_message(Default::default(), Default::default(), Some(now))
        .map_err(|e| format!("gen_kp: {e:?}"))?;
    let mut ag = a.create_group(ExtensionList::default(), Default::default(), Some(now))
        .map_err(|e| format!("create_group: {e:?}"))?;
    let commit = ag.commit_builder().commit_time(now).add_member(b_kp)
        .map_err(|e| format!("add(validate B cert): {e:?}"))?
        .build().map_err(|e| format!("commit: {e:?}"))?;
    ag.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
    let (mut bg, _) = b.join_group(None, &commit.welcome_messages[0], Some(now))
        .map_err(|e| format!("join(validate A cert): {e:?}"))?;
    let ct = ag.encrypt_application_message(b"x509 hello", Default::default())
        .map_err(|e| format!("enc: {e:?}"))?;
    match bg.process_incoming_message_with_time(ct, now).map_err(|e| format!("dec: {e:?}"))? {
        ReceivedMessage::ApplicationMessage(m) if m.data() == b"x509 hello" => {}
        other => return Err(format!("bad decrypt: {other:?}")),
    }
    let _ = MlsMessage::from_bytes(&[]);
    Ok(())
}


/// RCC.16 §7.11.1.1 era GroupContext extension; `extension_data` is a bare uint32.
pub const ERA_EXT: u16 = 0xF001;
/// The era a new group starts at. Era 0 is reserved: `create_group_carry` emits no era extension
/// for it, which peers refuse.
pub const ERA_INITIAL: u32 = 1;

/// The `welcomeAction` the engine reports with a planned create (bundle slot 7). The engine writes
/// 0xF001, so it decides the era and reports what it did; the host only picks the RPC. Numbered to
/// match other clients. See docs/mls/group-lifecycle.md.
pub const WELCOME_ACTION_UNKNOWN: u32 = 0;
/// A group that did not exist; born at [`ERA_INITIAL`].
pub const WELCOME_ACTION_NEW_GROUP: u32 = 1;
/// A new era of an existing group: same RCS group id, new MLS group.
pub const WELCOME_ACTION_NEW_ERA_EXISTING_GROUP: u32 = 2;
/// Members added to a group we already hold, at the same era: the artifacts are an add commit for
/// the add RPC, not a create.
pub const WELCOME_ACTION_NEW_MEMBERSHIP_EXISTING_GROUP: u32 = 3;

/// Indices of `requested` whose participant `held` does not contain, compared by MSISDN because a
/// participant is a person: a second device of a member is not an addition. An empty (unreadable)
/// identity counts as new, since dropping it would silently leave someone out of the commit.
pub fn additions_to(held: &[Vec<u8>], requested: &[Vec<u8>]) -> Vec<usize> {
    requested.iter().enumerate()
        .filter(|(_, id)| id.is_empty()
            || !held.iter().any(|h| crate::rcc16_validate::msisdn_equals(h, id)))
        .map(|(i, _)| i)
        .collect()
}

/// The RCC.16 §7.11 proposal registry. It shares 0xF0xx numbering with the extension registry but
/// is a separate namespace (0xF002 is `end_mls` as an extension, `rcs_signature` as a proposal).
/// The other three types derive from mls-rs's `ProposalType` constants, so a moved code point or a
/// disabled feature breaks the build rather than the wire. `END_MLS_PROP` has no upstream
/// counterpart: v3.0 defines it and v4.0 §7.11.2.1 voids it.
pub const END_MLS_PROP: u16 = 0xF001;
pub const SELF_REMOVE_PROP: u16 =
    mls_rs_core::group::ProposalType::SELF_REMOVE.raw_value();
pub const SERVER_REMOVE_PROP: u16 =
    mls_rs_core::group::ProposalType::RCS_SERVER_REMOVE.raw_value();

// ---- Capability advertisement ----
// Production and the smoke fixtures build from these lists. The advertisement is not trimmed to the
// implementation: what is implemented and what is advertised beyond it are listed separately.

/// Extension types implemented by this engine.
pub const IMPLEMENTED_EXTENSIONS: &[u16] = &[
    ERA_EXT,                 // set on every create, read by era_of
    END_MLS_EXT,             // commit_end_mls, both directions
    ICON_KEY_EXT,            // commit_group_metadata
    ICON_COMMITMENT_EXT,     // commit_group_metadata, commit_icon_subject
    SUBJECT_KEY_EXT,         // commit_group_metadata
    SUBJECT_COMMITMENT_EXT,  // commit_group_metadata, commit_icon_subject
];

/// Proposal types implemented by this engine. `END_MLS_PROP` is advertised under v3.0 only and
/// filtered out under v4.0, which withdraws it (RCC.16 §7.11.2.1); it is still handled inbound.
/// Other clients still advertise it, which does not make a peer v4.0-conformant.
pub const IMPLEMENTED_PROPOSALS: &[u16] = &[
    END_MLS_PROP,        // inbound, committed with the RCC.16 §7.11.2.2 extension; v3.0 only
    RCS_SIGNATURE_PROP,  // rcs_sign / rcs_verify
    SELF_REMOVE_PROP,    // self_leave
    SERVER_REMOVE_PROP,  // applied as a plain Remove
];

/// Extensions advertised without an implementation, so a group carrying one does not fail our
/// capability check: 0xE000, the testing expiry override. The metadata-keys type (0xF007 by
/// default, settable) is added from [`metadata_keys_ext`], and the vendor range 0xF010..=0xF030
/// (continuity token 0xF010 and its commitment 0xF011, the rest unclaimed) from
/// `ADVERTISED_VENDOR_EXT_RANGE`.
pub const ADVERTISED_UNIMPLEMENTED_EXTENSIONS_FIXED: &[u16] = &[TESTING_OVERRIDE_EXPIRY_EXT];
pub const ADVERTISED_VENDOR_EXT_RANGE: core::ops::RangeInclusive<u16> = 0xF010..=0xF030;
pub const ADVERTISED_VENDOR_PROP_RANGE: core::ops::RangeInclusive<u16> = 0xF010..=0xF018;

/// RCC.16 v4.0 §7.11.11.1 `reserved_for_future_use` proposal band, mandatory to advertise under
/// v4.0 and undefined in v3.0 (hence gated). It starts at 0xF012 because 0xF010/0xF011 are assigned
/// in v4.0. Under v3.0 the band is `ADVERTISED_VENDOR_PROP_RANGE`, which is what other clients
/// advertise; the union of the two is not advertised, since proposal support is an all-leaves
/// intersection and an advertised proposal we do not implement blocks application messages while it
/// sits in the proposal cache. Moving to v4.0 drops 0xF010/0xF011 from the intersection, so peers'
/// bands need re-checking first. See docs/mls/rust-core.md.
pub const RESERVED_FUTURE_PROP_RANGE_V4: core::ops::RangeInclusive<u16> = 0xF012..=0xF030;

/// Implemented + advertised-only + `extra`, the transport's additions.
pub fn advertised_extensions(extra: &[u16]) -> Vec<u16> {
    let mut v: Vec<u16> = IMPLEMENTED_EXTENSIONS.to_vec();
    v.extend_from_slice(ADVERTISED_UNIMPLEMENTED_EXTENSIONS_FIXED);
    if let Some(m) = metadata_keys_ext() { v.push(m); }
    v.extend(ADVERTISED_VENDOR_EXT_RANGE);
    for e in extra { if !v.contains(e) { v.push(*e); } }
    v
}

/// Implemented + advertised-only proposals + `extra`. The one version-dependent advertisement:
/// v4.0 drops `END_MLS_PROP` and swaps the vendor band for [`RESERVED_FUTURE_PROP_RANGE_V4`].
pub fn advertised_proposals(extra: &[u16]) -> Vec<u16> {
    let v4 = is_v4_0_or_later();
    let mut v: Vec<u16> = IMPLEMENTED_PROPOSALS.iter().copied()
        .filter(|p| !(v4 && *p == END_MLS_PROP))
        .collect();
    if v4 {
        v.extend(RESERVED_FUTURE_PROP_RANGE_V4);
    } else {
        v.extend(ADVERTISED_VENDOR_PROP_RANGE);
    }
    for e in extra { if !v.contains(e) { v.push(*e); } }
    v
}

/// Advances an era by one, refusing the u32 ceiling: a wrap reads as a move backwards to every
/// peer. The only place the crate increments an era.
pub fn next_era(current: u32) -> Result<u32, String> {
    current.checked_add(1).ok_or_else(|| format!(
        "era {current} is the u32 ceiling; advancing would wrap to 0, which reads as a move \
         BACKWARDS to every peer and cannot be undone"))
}
/// RCC.16 §7.11.2.2 `end_mls` GroupContext extension. Its presence means the conversation has moved
/// to unencrypted (RCC.16 §9.1.1); a commit that removes it moves it back.
pub const END_MLS_EXT: u16 = 0xF002;
/// RCC.16 §7.11.7.1 `rcs_signature` proposal, the carrier for a CPIM message signature (RCC.16
/// §7.6.2); never committed. Bound to the mls-rs constant.
pub const RCS_SIGNATURE_PROP: u16 =
    mls_rs_core::group::ProposalType::RCS_SIGNATURE.raw_value();
/// The RCC.16 v3.0 `end_mls` payload. Encode through [`end_mls_payload`]; public for the decode
/// side, since v3.0 peers send it.
pub const END_MLS_DATA: &[u8] = b"end_mls";

// ---- The RCC.16 revision ----
// Announced by the transport, never inferred: RCC.16 has no in-band version signal and a detection
// heuristic would fail silently. Decode tolerantly (accept both revisions' shapes), encode strictly
// (emit exactly the announced revision's shape).

/// RCC.16 v3.0, the default.
pub const RCC16_V3_0: u8 = 30;
/// RCC.16 v4.0.
pub const RCC16_V4_0: u8 = 40;

/// Per process, since each app runs in its own process.
static RCC16_VERSION: core::sync::atomic::AtomicU8 =
    core::sync::atomic::AtomicU8::new(RCC16_V3_0);

/// The announced RCC.16 revision; [`RCC16_V3_0`] until a transport says otherwise.
pub fn rcc16_version() -> u8 {
    RCC16_VERSION.load(core::sync::atomic::Ordering::Relaxed)
}

/// Announces the revision the transport speaks and returns the one in effect. An unrecognised value
/// is refused and the current revision kept.
pub fn set_rcc16_version(v: u8) -> u8 {
    if v == RCC16_V3_0 || v == RCC16_V4_0 {
        RCC16_VERSION.store(v, core::sync::atomic::Ordering::Relaxed);
    }
    rcc16_version()
}

/// Every version-dependent site reads this rather than comparing with `RCC16_V4_0`.
pub fn is_v4_0_or_later() -> bool { rcc16_version() >= RCC16_V4_0 }

/// RCC.16 v4.0 §7.11.2.2 `EndMlsReason`: why the downgrading client downgrades.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum EndMlsReason {
    Unset = 0,
    ServerFailureEncryptionNotAvailable = 1,
    ClientFailureEncryptionNotAvailable = 2,
    GroupMemberFailsCapabilitiesCheck = 3,
    UntrustedRootOnOtherGroupMember = 4,
    ContinuityTokenMismatch = 5,
    ContinuityTokenNotReceived = 6,
    PersistentRefreshFailure = 7,
    CannotFetchKeyPackages = 8,
    InternalClientError = 9,
    OutgoingCommitFailed = 10,
    ServerFailureEraAdvancementQuotaReached = 11,
}

/// The `end_mls` `extension_data` for the announced revision: v3.0 the ASCII `end_mls` (`reason`
/// ignored); v4.0 a bare serialised `EndMlsMetadata { downgrade_reason = 1 }`, not `opaque<V>`, so
/// `reason = 5` is `08 05` and `Unset` is zero bytes. 0xF002 is bare in both.
pub fn end_mls_payload(reason: EndMlsReason) -> Vec<u8> {
    if !is_v4_0_or_later() { return END_MLS_DATA.to_vec(); }
    if reason == EndMlsReason::Unset { return Vec::new(); }
    // field 1, varint: tag 0x08; every reason fits one byte
    vec![0x08, reason as u8]
}

/// Whether `data` is an `end_mls` payload of either revision. Presence of the extension carries the
/// meaning (RCC.16 §9.1.1), so a payload that is not understood must still be treated as `end_mls`.
#[cfg(test)]
pub fn end_mls_payload_understood(data: &[u8]) -> bool {
    // v3.0: the literal, possibly followed by more bytes
    if data.starts_with(END_MLS_DATA) { return true; }
    // v4.0: empty (Unset), or one varint field 1 holding a defined reason
    match data {
        [] => true,
        [0x08, r] => *r <= EndMlsReason::ServerFailureEraAdvancementQuotaReached as u8,
        _ => false,
    }
}
/// RCC.16 §7.11.3.1 `icon_key`. Welcome only: never in a GroupInfo sent to the server (RCC.16
/// §9.7.1.4).
pub const ICON_KEY_EXT: u16 = 0xF003;
/// RCC.16 §7.11.4.1 `icon_commitment` (Annex C.1). GroupInfo and Welcome.
pub const ICON_COMMITMENT_EXT: u16 = 0xF004;
/// RCC.16 §7.11.5.1 `subject_key`. Welcome only.
pub const SUBJECT_KEY_EXT: u16 = 0xF005;
/// RCC.16 §7.11.6.1 `subject_commitment` (Annex C.1). GroupInfo and Welcome.
pub const SUBJECT_COMMITMENT_EXT: u16 = 0xF006;

/// Secret-carrying extensions stripped from any GroupInfo leaving the device. The continuity token
/// is Welcome-only (RCC.16 §7.11.12.1); its commitment belongs in every GroupInfo and is not
/// listed.
pub const WELCOME_ONLY_EXTS: [u16; 3] = [ICON_KEY_EXT, SUBJECT_KEY_EXT, CONTINUITY_TOKEN_EXT];

/// The RCC.16 §7.11.12 pair.
pub const CONTINUITY_EXTS: [u16; 2] = [CONTINUITY_TOKEN_EXT, CONTINUITY_TOKEN_COMMITMENT_EXT];

/// Whether to emit continuity extensions; reading them is always on. Gated on v4.0. Emitting the
/// commitment (0xF011) arms every peer's RCC.16 §10.5.1 check, whose persistent mismatch may
/// downgrade the conversation (RCC.16 §11.2), and the engine cannot yet reproduce a token across
/// restarts. If staged: persist the token, emit 0xF010, and keep 0xF011 off. See
/// docs/mls/metadata.md.
pub fn emit_continuity() -> bool { is_v4_0_or_later() }

/// RCC.16 v4.0 §7.11.10.1 `group_metadata_keys_requested`; presence is the flag. Framing is chosen
/// by revision, see [`metadata_keys_is_bare`].
pub const METADATA_KEYS_REQUESTED_EXT: u16 = 0xF007;

/// The metadata-keys code point, 0xF007 by default; `0` means unset. Settable (the Java side's
/// `debug.rcs.mls_metadata_keys_ext`) so a peer using another number can be honoured without a
/// rebuild. The advertisement and the framing rule both follow this value.
static METADATA_KEYS_EXT: core::sync::atomic::AtomicU16 =
    core::sync::atomic::AtomicU16::new(METADATA_KEYS_REQUESTED_EXT);

/// The metadata-keys code point, or `None` if cleared.
pub fn metadata_keys_ext() -> Option<u16> {
    match METADATA_KEYS_EXT.load(core::sync::atomic::Ordering::Relaxed) {
        0 => None,
        v => Some(v),
    }
}

/// Serialises tests that change the process-global code point, across both test modules.
#[cfg(test)]
pub(crate) static METADATA_KEYS_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Serialises tests that change the announced revision, across both test modules.
#[cfg(test)]
pub(crate) static VERSION_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Sets the code point; `0` clears it (0 is never a valid extension type).
pub fn set_metadata_keys_ext(ty: u16) {
    METADATA_KEYS_EXT.store(ty, core::sync::atomic::Ordering::Relaxed);
}
/// The 0xF007 payload: 29 bytes of ASCII.
pub const METADATA_KEYS_REQUESTED_DATA: &[u8] = b"group_metadata_keys_requested";
/// RCC.16 v4.0 §7.11.12.1 continuity token, carried only in the encrypted GroupInfo inside a
/// Welcome: 256 random bits, continuous across epochs and eras (RCC.16 §8.3.1.1). Peers mint it
/// under v3.0 too; we read it and, per [`emit_continuity`], emit none. A client with no local token
/// requests one (RCC.16 §10.5.2) when the RCS participants are a subset of the MLS membership, and
/// mints otherwise (RCC.16 §8.3.1.3).
pub const CONTINUITY_TOKEN_EXT: u16 = 0xF010;
/// RCC.16 v4.0 §7.11.12.2 continuity-token commitment, a GroupInfo extension:
/// `RefHash("Continuity Token GroupInfo Commitment", TokenCommitment { continuity_token<V>,
/// epoch_authenticator<V> })`, 33 bytes framed. It cannot be a GroupContext extension, which would
/// have to commit to an authenticator derived from itself.
pub const CONTINUITY_TOKEN_COMMITMENT_EXT: u16 = 0xF011;
/// Test-only certificate-validity override (varint-framed).
pub const TESTING_OVERRIDE_EXPIRY_EXT: u16 = 0xE000;

// ---- extension_data framing ----
// RFC 9420 frames `extension_data` as `opaque<V>`; most RCC.16 extensions add a second inner varint
// length inside it (`varint(L_inner) || value`). Which types do is fixed per type. A commitment
// written bare is 32 bytes where peers write 33, which changes the GroupContext and makes peers
// reject the group.

/// Whether `ty`'s `extension_data` has no inner varint. The bare set is closed, so an unknown type
/// defaults to framed.
pub fn ext_payload_is_bare(ty: u16) -> bool {
    // 0xF007 is not here: its framing depends on the revision (`framing_is_version_dependent`).
    matches!(ty, ERA_EXT | END_MLS_EXT)
}

/// 0xF007, whose framing depends on the revision: bare under v3.0, `opaque<V>` under v4.0. Decode
/// accepts both.
fn framing_is_version_dependent(ty: u16) -> bool { metadata_keys_ext() == Some(ty) }

/// Whether 0xF007 is written bare under the announced revision (v3.0 yes, v4.0 no). Other clients
/// write it three ways (30, 29 and 0 bytes) with no in-band discriminator; keying the encoding on
/// the revision matches the peers each transport has, and a per-peer rule would need per-peer
/// evidence. Decode accepts all three.
pub fn metadata_keys_is_bare() -> bool { !is_v4_0_or_later() }

/// Frames a raw value (a commitment, a key, the BE32 era) as `extension_data` for `ty`, adding
/// the inner varint when the type takes one. `Extension::new` adds the outer `opaque<V>`.
pub fn ext_encode(ty: u16, value: &[u8]) -> Result<Vec<u8>, String> {
    if framing_is_version_dependent(ty) {
        // Resolved by the announced revision; RCC.16 §10.5.2 needs a producer.
        if metadata_keys_is_bare() { return Ok(value.to_vec()); }
        // framed, as below
        let n = VarInt::try_from(value.len())
            .map_err(|e| format!("ext_encode(0x{ty:04X}): value too long: {e:?}"))?;
        let mut out = Vec::with_capacity(value.len() + 4);
        n.mls_encode(&mut out)
            .map_err(|e| format!("ext_encode(0x{ty:04X}): varint: {e:?}"))?;
        out.extend_from_slice(value);
        return Ok(out);
    }
    if ext_payload_is_bare(ty) {
        return Ok(value.to_vec());
    }
    let n = VarInt::try_from(value.len())
        .map_err(|e| format!("ext_encode(0x{ty:04X}): value too long for an MLS varint: {e:?}"))?;
    let mut out = Vec::with_capacity(value.len() + 4);
    n.mls_encode(&mut out)
        .map_err(|e| format!("ext_encode(0x{ty:04X}): varint: {e:?}"))?;
    out.extend_from_slice(value);
    Ok(out)
}

// ---- Era immutability ----
// A GroupContextExtensions proposal may neither change nor remove 0xF001, and it is the only
// in-group way to alter a GroupContext extension (RFC 9420 §12.1.7), so the era is fixed for the
// lifetime of an MLS group and an era advance is a new group (`create_group_carry`), never a
// commit. Enforced in `filter_proposals`, which runs for sent and received commits.

/// The era (0xF001) from an extension list, if present.
pub fn era_of(exts: &CoreExtensionList) -> Option<u32> {
    exts.get(ExtensionType::from(ERA_EXT))
        .and_then(|e| e.extension_data.get(0..4)
            .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]])))
}

/// A new era must be strictly above the carried one, compared unsigned: a new group at an era peers
/// have passed is unrecoverable, where a refused advance is not.
pub fn check_era_advances(carried: Option<u32>, new_era: u32) -> Result<(), String> {
    match carried {
        Some(old) if new_era <= old => Err(format!(
            "attempted to advance the group from era {old} to {new_era}; backwards in eras \
             (or wrapped) — an advance must move strictly forward")),
        _ => Ok(()),
    }
}

/// An era-touching GroupContextExtensions proposal, named as other clients name the error.
#[derive(Debug)]
pub enum EraViolation {
    /// `GroupContextExtensionProposalChangesEraError`
    ChangesEra { from: Vec<u8>, to: Vec<u8> },
    /// `GroupContextExtensionProposalRemovesEraError`
    RemovesEra,
}

impl core::fmt::Display for EraViolation {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            EraViolation::ChangesEra { from, to } => write!(f,
                "GroupContextExtensionProposalChangesEra: 0xF001 {from:02x?} -> {to:02x?}; the era is \
                 immutable within a group — advance it by CREATING a new group at era+1"),
            EraViolation::RemovesEra => write!(f,
                "GroupContextExtensionProposalRemovesEra: a proposal dropped 0xF001; every send stamps \
                 the era and every peer validates it"),
        }
    }
}

impl mls_rs_core::error::IntoAnyError for EraViolation {}

/// `DefaultMlsRules` plus the RCC.16 era guard; everything else delegates unchanged.
#[derive(Debug, Clone)]
pub struct Rcc16MlsRules {
    inner: DefaultMlsRules,
}

impl Rcc16MlsRules {
    pub fn new(inner: DefaultMlsRules) -> Self { Self { inner } }

    /// The guard, testable without a group. `current` and `proposed` are the 0xF001
    /// `extension_data` before and in the proposal. Adding the first era is allowed.
    pub fn check_era_preserved(current: Option<&[u8]>, proposed: Option<&[u8]>)
            -> Result<(), EraViolation> {
        match (current, proposed) {
            (Some(cur), Some(new)) if cur != new => Err(EraViolation::ChangesEra {
                from: cur.to_vec(), to: new.to_vec() }),
            (Some(_), None) => Err(EraViolation::RemovesEra),
            _ => Ok(()),
        }
    }
}

impl MlsRules for Rcc16MlsRules {
    type Error = EraViolation;

    fn filter_proposals(
        &self,
        _direction: CommitDirection,
        _source: CommitSource,
        _current_roster: &Roster,
        current_context: &GroupContext,
        proposals: ProposalBundle,
    ) -> Result<ProposalBundle, Self::Error> {
        let era_ty = ExtensionType::from(ERA_EXT);
        let current = current_context.extensions().get(era_ty);
        for p in proposals.group_context_ext_proposals() {
            Rcc16MlsRules::check_era_preserved(
                current.as_ref().map(|e| e.extension_data.as_slice()),
                p.proposal.get(era_ty).as_ref().map(|e| e.extension_data.as_slice()))?;
        }
        // the inner rules are Infallible
        match self.inner.filter_proposals(_direction, _source, _current_roster,
                                          current_context, proposals) {
            Ok(b) => Ok(b),
            Err(e) => match e {},
        }
    }

    fn commit_options(&self, new_roster: &Roster, new_context: &GroupContext,
                      proposals: &ProposalBundle) -> Result<CommitOptions, Self::Error> {
        match self.inner.commit_options(new_roster, new_context, proposals) {
            Ok(o) => Ok(o),
            Err(e) => match e {},
        }
    }

    fn encryption_options(&self, current_roster: &Roster, current_context: &GroupContext)
            -> Result<EncryptionOptions, Self::Error> {
        match self.inner.encryption_options(current_roster, current_context) {
            Ok(o) => Ok(o),
            Err(e) => match e {},
        }
    }
}

/// Recovers the value from `extension_data`. Rejects a non-minimal varint and the reserved `11`
/// prefix (`VarInt::mls_decode`), so our view of the GroupContext bytes matches peers'. Bare types
/// come back verbatim; an `end_mls` payload may be longer than the 7-byte literal.
pub fn ext_decode(ty: u16, extension_data: &[u8]) -> Result<Vec<u8>, String> {
    if framing_is_version_dependent(ty) {
        // Accept both framings: a framed payload is one byte longer and starts with its own
        // length; strip it only when it describes the rest.
        if let [n, rest @ ..] = extension_data {
            if *n as usize == rest.len() && *n < 0x40 { return Ok(rest.to_vec()); }
        }
        return Ok(extension_data.to_vec());
    }
    if ext_payload_is_bare(ty) {
        return Ok(extension_data.to_vec());
    }
    let mut r = extension_data;
    let framed = VarInt::mls_decode(&mut r)
        .map_err(|e| format!("ext_decode(0x{ty:04X}): inner varint: {e:?}"))
        .and_then(|n| {
            let n = u32::from(n) as usize;
            if r.len() == n { Ok(r.to_vec()) } else {
                Err(format!(
                    "ext_decode(0x{ty:04X}): inner length {n} != {} remaining byte(s) — the \
                     payload is not a single varint-framed vector", r.len()))
            }
        });
    match framed {
        Ok(v) => Ok(v),
        // Some clients write a commitment as a bare digest. Accepted only at a length the type's
        // value can have (`bare_payload_is_plausible`); anything else stays an error.
        Err(_) if bare_payload_is_plausible(ty, extension_data.len()) => {
            Ok(extension_data.to_vec())
        }
        Err(e2) => Err(e2),
    }
}

/// Whether `n` bytes at `ty` could be the value written bare. Consulted only after the framed read
/// failed. 0xF004/0xF006: a digest length. 0xF010: 32 (RCC.16 §8.3.1.1), since a refused token is
/// a dropped one. 0xF011 is not tolerated.
fn bare_payload_is_plausible(ty: u16, n: usize) -> bool {
    if ext_payload_is_commitment(ty) { return is_digest_len(n); }
    ty == CONTINUITY_TOKEN_EXT && n == CONTINUITY_TOKEN_BYTES
}

/// RCC.16 §8.3.1.1: 256 random bits.
pub const CONTINUITY_TOKEN_BYTES: usize = 32;

fn ext_payload_is_commitment(ty: u16) -> bool {
    ty == ICON_COMMITMENT_EXT || ty == SUBJECT_COMMITMENT_EXT
}

/// SHA-256, SHA-384, SHA-512 digest lengths.
fn is_digest_len(n: usize) -> bool {
    n == 32 || n == 48 || n == 64
}

#[cfg(test)]
fn era_extension_list(era: u32) -> CoreExtensionList {
    CoreExtensionList::from(
        vec![Extension::new(ExtensionType::from(ERA_EXT), era.to_be_bytes().to_vec())])
}

/// A group created with an era carries 0xF001 in its GroupContext as a uint32 extension.
#[cfg(test)]
pub fn rcc16_conformance() -> Result<String, String> {
    let a = build_x509_client(LEAF_A, &[ICA], LEAF_A_PRIV, LEAF_A_PUB, &[ROOT])?;
    let ag = a.create_group(era_extension_list(1), Default::default(), None)
        .map_err(|e| format!("create_group(era): {e:?}"))?;
    let ctx = ag.context();
    let era = ctx.extensions().get(ExtensionType::from(ERA_EXT))
        .ok_or("Era 0xF001 missing from GroupContext")?;
    if era.extension_data != vec![0u8, 0, 0, 1] {
        return Err(format!("Era data mismatch: {:?}", era.extension_data));
    }
    Ok(format!("GroupContext carries Era 0xF001 = uint32 {} (raw {:02x?}); suite {:?}",
        1, era.extension_data, ctx.cipher_suite()))
}


/// A generated KeyPackage decodes as RCC.16-shaped: X.509 credential with chain, suite 0x0002,
/// 65-byte SEC1 signature key.
#[cfg(test)]
pub fn keypackage_conformance() -> Result<String, String> {
    let b = build_x509_client(LEAF_B, &[ICA], LEAF_B_PRIV, LEAF_B_PUB, &[ROOT])?;
    let kp_msg = b.generate_key_package_message(Default::default(), Default::default(), None)
        .map_err(|e| format!("gen_kp: {e:?}"))?;
    let kp = kp_msg.into_key_package().ok_or("message is not a KeyPackage")?;
    if kp.cipher_suite() != SUITE {
        return Err(format!("cipher_suite {:?} != 0x0002", kp.cipher_suite()));
    }
    let si = kp.signing_identity();
    let sk_len = si.signature_key.as_bytes().len();
    if sk_len != 65 { return Err(format!("signature_key {sk_len}B != 65 (SEC1)")); }
    let n_certs = match &si.credential {
        Credential::X509(chain) => chain.len(),
        other => return Err(format!("credential not X509: {:?}", other.credential_type())),
    };
    if n_certs < 2 { return Err(format!("chain has {n_certs} certs, expected leaf+CA")); }
    Ok(
        format!(
            "KeyPackage conformant: suite=0x0002 credential=X509({n_certs} certs, leaf+chain) signature_key={sk_len}B SEC1"))
}


// ---- RCC.16 AuthenticatedData ----
// Built and parsed in the engine; the host supplies only the message id. Byte-identical to
// `MlsAppMessage.buildAuthenticatedData` and pinned by the same wire vectors:
//
//     [00 01] [mls_varint(len)] [message_id] [uint32 era BE] [resent-message component]
//
// The uint32 era is not in RCC.16's text, but other clients emit it and it tracks the era (not the
// epoch); omitting it breaks decryption with KEY_GENERATION_MISMATCH. The trailing component
// (RCC.16 §10.3) is 0x00 when absent; the present form is passed through verbatim.

/// MLS varint, matching `MlsAppMessage.mlsVarint`.
pub fn mls_varint(value: usize) -> Vec<u8> {
    if value < 0x40 {
        vec![value as u8]
    } else if value < 0x4000 {
        vec![0x40 | ((value >> 8) as u8), (value & 0xff) as u8]
    } else if value < 0x4000_0000 {
        vec![
            0x80 | ((value >> 24) as u8),
            ((value >> 16) & 0xff) as u8,
            ((value >> 8) & 0xff) as u8,
            (value & 0xff) as u8,
        ]
    } else {
        // Clamp rather than panic across the FFI boundary; no real message id is this long.
        vec![0xbf, 0xff, 0xff, 0xff]
    }
}

/// Reads an MLS varint at `o`; returns (value, next offset).
fn read_mls_varint(b: &[u8], o: usize) -> Result<(usize, usize), String> {
    if o >= b.len() {
        return Err("aad varint: eof".into());
    }
    match b[o] >> 6 {
        0 => Ok(((b[o] & 0x3f) as usize, o + 1)),
        1 => {
            if o + 2 > b.len() {
                return Err("aad varint2: eof".into());
            }
            Ok(((((b[o] & 0x3f) as usize) << 8) | b[o + 1] as usize, o + 2))
        }
        2 => {
            if o + 4 > b.len() {
                return Err("aad varint4: eof".into());
            }
            Ok((
                (((b[o] & 0x3f) as usize) << 24)
                    | ((b[o + 1] as usize) << 16)
                    | ((b[o + 2] as usize) << 8)
                    | b[o + 3] as usize,
                o + 4,
            ))
        }
        _ => Err("aad varint: reserved 3-prefix".into()),
    }
}

/// The AuthenticatedData of an application message. An empty `trailing` emits `0x00`, the absent
/// resent-message component.
pub fn build_authenticated_data(message_id: &[u8], era: u32, trailing: &[u8]) -> Vec<u8> {
    let len = mls_varint(message_id.len());
    let tail: &[u8] = if trailing.is_empty() { &[0x00] } else { trailing };
    let mut aad = Vec::with_capacity(2 + len.len() + message_id.len() + 4 + tail.len());
    aad.extend_from_slice(&[0x00, 0x01]); // version = 1
    aad.extend_from_slice(&len);
    aad.extend_from_slice(message_id);
    aad.extend_from_slice(&era.to_be_bytes());
    aad.extend_from_slice(tail);
    aad
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedAad {
    pub version: u16,
    pub message_id: Vec<u8>,
    pub era: u32,
    /// The RCC.16 §10.3 resent-message component; `[0x00]` means absent.
    pub trailing: Vec<u8>,
}

impl ParsedAad {
    #[cfg(test)]
    pub fn resent_absent(&self) -> bool {
        self.trailing == [0x00]
    }
}

/// Parses an inbound AuthenticatedData: strict on structure, and anything after the era is
/// returned verbatim, so a resend with a present component is not dropped.
pub fn parse_authenticated_data(aad: &[u8]) -> Result<ParsedAad, String> {
    if aad.len() < 2 {
        return Err("aad: too short for version".into());
    }
    let version = ((aad[0] as u16) << 8) | aad[1] as u16;
    let (mid_len, o) = read_mls_varint(aad, 2)?;
    let end = o
        .checked_add(mid_len)
        .ok_or_else(|| "aad: message-id length overflow".to_string())?;
    if end > aad.len() {
        return Err(format!(
            "aad: message-id len {} exceeds {} remaining",
            mid_len,
            aad.len().saturating_sub(o)
        ));
    }
    let message_id = aad[o..end].to_vec();
    if end + 4 > aad.len() {
        return Err("aad: truncated before the uint32 era".into());
    }
    let era = u32::from_be_bytes([aad[end], aad[end + 1], aad[end + 2], aad[end + 3]]);
    let trailing = aad[end + 4..].to_vec();
    Ok(ParsedAad { version, message_id, era, trailing })
}

#[cfg(test)]
mod aad_tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
    }

    // Wire vectors at three eras, shared with MlsAuthenticatedDataWireVectorTest so the Rust and
    // Java builders stay byte-identical.
    const E6: (&str, u32, &str) =
        ("MxaDOv6I6QTwGK2Xj6V-Ubhw", 6,
            "0001184d7861444f76364936515477474b32586a36562d556268770000000600");
    const E7: (&str, u32, &str) =
        ("MxHjYubZBiQYuA9DqODpyQiw", 7,
            "0001184d78486a5975625a4269515975413944714f4470795169770000000700");
    const E8: (&str, u32, &str) =
        ("MxCACYGxqfQa-CnQZ=5XctuQ", 8,
            "0001184d78434143594778716651612d436e515a3d3558637475510000000800");

    #[test]
    fn builds_the_captured_wire_bytes_at_three_eras() {
        for (mid, era, want) in [E6, E7, E8] {
            let got = build_authenticated_data(mid.as_bytes(), era, &[]);
            assert_eq!(got, hex(want), "era {era} ({mid}) must match the captured wire bytes");
        }
    }

    #[test]
    fn round_trips_every_captured_vector() {
        for (mid, era, want) in [E6, E7, E8] {
            let p = parse_authenticated_data(&hex(want)).expect("a captured AAD must parse");
            assert_eq!(p.version, 1);
            assert_eq!(p.message_id, mid.as_bytes());
            assert_eq!(p.era, era);
            assert!(p.resent_absent(), "all captured samples carry the component ABSENT");
        }
    }

    // the uint32 tracks the era rather than being a constant
    #[test]
    fn era_is_carried_not_constant() {
        let mid = b"MxCACYGxqfQa-CnQZ=5XctuQ";
        let a = build_authenticated_data(mid, 4, &[]);
        let b = build_authenticated_data(mid, 8, &[]);
        assert_ne!(a, b);
        let o = 3 + mid.len();
        assert_eq!(a[o + 3], 4);
        assert_eq!(b[o + 3], 8);
        assert_eq!(&a[..o + 3], &b[..o + 3], "only the era byte may differ");
    }

    // big-endian four bytes; every captured era is below 256, so the vectors alone cannot tell
    #[test]
    fn era_is_four_bytes_big_endian() {
        let mid = b"m";
        let aad = build_authenticated_data(mid, 0x0102_0304, &[]);
        let o = 3 + mid.len();
        assert_eq!(&aad[o..o + 4], &[0x01, 0x02, 0x03, 0x04]);
        assert_eq!(parse_authenticated_data(&aad).unwrap().era, 0x0102_0304);
    }

    #[test]
    fn absent_component_is_exactly_one_zero_byte() {
        let aad = build_authenticated_data(b"m", 1, &[]);
        assert_eq!(*aad.last().unwrap(), 0x00);
        assert_eq!(aad.len(), 2 + 1 + 1 + 4 + 1);
    }

    // A present resent component passes through verbatim both ways.
    #[test]
    fn present_component_survives_a_round_trip_verbatim() {
        let tail = [0x01u8, 0xde, 0xad, 0xbe, 0xef];
        let aad = build_authenticated_data(b"mid", 3, &tail);
        let p = parse_authenticated_data(&aad).unwrap();
        assert_eq!(p.trailing, tail);
        assert!(!p.resent_absent());
        assert_eq!(p.message_id, b"mid");
        assert_eq!(p.era, 3);
    }

    // A second implementation's AAD from an external commit: same fields and order, a 36-character
    // message id. Its commits span MLS epochs 1 to 3 with the uint32 fixed at 1, so the field is
    // the era, not the epoch.
    #[test]
    fn parses_an_apple_rcs_aad_byte_for_byte() {
        const APPLE: &str = "00012435464639373038422d303744382d343844342d4142323\
72d3831363735333143333541410000000100";
        let aad = hex(&APPLE.replace('\\', "").replace('\n', ""));
        assert_eq!(aad.len(), 44, "Apple ids are 36 chars, so its AAD is 44B not 32B");
        let p = parse_authenticated_data(&aad).expect("Apple's AAD must parse with our reader");
        assert_eq!(p.version, 1);
        assert_eq!(p.message_id, b"5FF9708B-07D8-48D4-AB27-8167531C35AA");
        assert_eq!(p.era, 1,
            "the uint32 is the RCC.16 ERA — this capture has epochs 1/2/3 at era 1");
        assert!(p.resent_absent());

        // our builder reproduces its bytes
        let ours = build_authenticated_data(b"5FF9708B-07D8-48D4-AB27-8167531C35AA", 1, &[]);
        assert_eq!(ours, aad, "our builder must reproduce Apple's AAD byte-for-byte");
    }

    #[test]
    fn varint_matches_java_at_each_form_boundary() {
        assert_eq!(mls_varint(0), vec![0x00]);
        assert_eq!(mls_varint(0x3f), vec![0x3f]);
        assert_eq!(mls_varint(0x40), vec![0x40, 0x40]);
        assert_eq!(mls_varint(0x3fff), vec![0x7f, 0xff]);
        assert_eq!(mls_varint(0x4000), vec![0x80, 0x00, 0x40, 0x00]);
    }

    #[test]
    fn a_long_message_id_round_trips_through_the_two_byte_varint() {
        let mid = vec![b'x'; 300];
        let aad = build_authenticated_data(&mid, 9, &[]);
        let p = parse_authenticated_data(&aad).unwrap();
        assert_eq!(p.message_id.len(), 300);
        assert_eq!(p.era, 9);
    }

    // Malformed input errors, never panics (this runs behind the FFI boundary).
    #[test]
    fn malformed_aad_errors_and_never_panics() {
        assert!(parse_authenticated_data(&[]).is_err());
        assert!(parse_authenticated_data(&[0x00]).is_err());
        assert!(parse_authenticated_data(&hex("000118")).is_err()); // length claims 24, none follow
        assert!(parse_authenticated_data(&hex("00011f4142")).is_err()); // truncated before the era
        assert!(parse_authenticated_data(&hex("0001c0")).is_err()); // reserved varint prefix
        // an empty message id with a full era is structurally valid
        assert!(
            parse_authenticated_data(&hex("00010000000005 00".replace(' ', "").as_str())).is_ok());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn x509_critical_ext_tolerated() {
        x509_roundtrip().expect("x509 round-trip with critical vendor ext");
        println!(
            "M3: mls-rs ACCEPTED the KDS-style leaf w/ CRITICAL ext 2.23.146.2.1.4 (the BoringSSL err-34 killer)");
    }
    #[test]
    fn rcc16_era_conformance() {
        let r = rcc16_conformance().expect("rcc16 conformance");
        println!("M3 conformance: {r}");
    }
    #[test]
    fn keypackage_is_conformant() {
        let r = keypackage_conformance().expect("kp conformance");
        println!("M3 KP: {r}");
    }

    // --- extension_data framing -------------------------------------------------------------

    /// A 32-byte commitment serialises as 33 bytes, `0x20 || hash`.
    #[test]
    fn commitment_gains_an_inner_varint() {
        let hash = vec![0xABu8; 32];
        let enc = ext_encode(SUBJECT_COMMITMENT_EXT, &hash).unwrap();
        assert_eq!(enc.len(), 33, "32-byte commitment must frame to 33 bytes");
        assert_eq!(enc[0], 0x20, "inner varint for len 32 is the single byte 0x20");
        assert_eq!(&enc[1..], &hash[..]);
        assert_eq!(ext_decode(SUBJECT_COMMITMENT_EXT, &enc).unwrap(), hash);
    }

    /// Bare types gain no prefix; the era is `f001 04 00000013` on the wire.
    #[test]
    fn the_three_bare_types_stay_bare() {
        let era = 19u32.to_be_bytes().to_vec();
        assert_eq!(ext_encode(ERA_EXT, &era).unwrap(), era, "era is a bare uint32");
        assert_eq!(ext_encode(END_MLS_EXT, END_MLS_DATA).unwrap(), END_MLS_DATA.to_vec());
        // bare, no inner varint
        assert_eq!(METADATA_KEYS_REQUESTED_DATA.len(), 29, "the observed encoder writes 29 bytes");
        for ty in [ERA_EXT, END_MLS_EXT] {
            assert!(ext_payload_is_bare(ty));
        }
        for ty in [ICON_KEY_EXT, ICON_COMMITMENT_EXT, SUBJECT_KEY_EXT, SUBJECT_COMMITMENT_EXT,
                   CONTINUITY_TOKEN_EXT, CONTINUITY_TOKEN_COMMITMENT_EXT,
                   TESTING_OVERRIDE_EXPIRY_EXT] {
            assert!(!ext_payload_is_bare(ty), "0x{ty:04X} is varint-framed");
        }
        // 0xF007 is in neither set; see the tests below.
        assert!(!ext_payload_is_bare(METADATA_KEYS_REQUESTED_EXT));
    }

    /// A bare 32-byte commitment, as some clients write it, decodes, without loosening the checks
    /// that catch corruption. Peer extensions are mirrored verbatim; only our own are framed on
    /// encode, since re-framing a peer's GroupContext would diverge us from the group.
    #[test]
    fn a_bare_hash_commitment_decodes_without_weakening_the_malformed_checks() {
        let hash = [0xABu8; 32];
        // our framing: 0x20 || hash
        assert_eq!(ext_decode(SUBJECT_COMMITMENT_EXT, &ext_encode(SUBJECT_COMMITMENT_EXT, &hash)
            .unwrap()).unwrap(), hash.to_vec(), "our own 33-byte framed form must still decode");
        // the bare digest, whose first byte is not a valid length prefix
        for ty in [SUBJECT_COMMITMENT_EXT, ICON_COMMITMENT_EXT] {
            assert_eq!(ext_decode(ty, &hash).unwrap(), hash.to_vec(),
                "a bare 32-byte digest must decode verbatim, not error");
        }
        // a leading 0x08 0xC1 reads as inner length 2241 with 30 bytes left
        let mut observed = vec![0x08u8, 0xC1];
        observed.extend_from_slice(&[0x5Eu8; 30]);
        assert_eq!(observed.len(), 32);
        assert_eq!(ext_decode(SUBJECT_COMMITMENT_EXT, &observed).unwrap(), observed,
            "the 2241-vs-30 case from the Apple group is the bug this fixes");
        // genuinely malformed input still fails
        assert!(ext_decode(ICON_COMMITMENT_EXT, &[0x02, 0xAA]).is_err(), "declared 2, carries 1");
        assert!(ext_decode(ICON_COMMITMENT_EXT, &[0x01, 0xAA, 0xBB]).is_err(), "trailing byte");
        assert!(ext_decode(SUBJECT_COMMITMENT_EXT, &[0x5Eu8; 31]).is_err(),
            "31 bytes is not a digest length — tolerance must not become blanket verbatim");
        // tolerance is limited to the commitments
        assert!(ext_decode(ICON_KEY_EXT, &[0xAAu8; 32]).is_err(),
            "0xF003 is not a commitment — a bare 32-byte payload there is still an error");
    }

    /// RCC.16 §7.11.12.1 continuity token in both framings: framed (`0x20 || 32`, as peers send it)
    /// and bare at exactly 32 bytes.
    #[test]
    fn a_continuity_token_decodes_framed_and_bare_and_nothing_else_does() {
        let token = [0x5Au8; CONTINUITY_TOKEN_BYTES];
        // framed, built by hand rather than through ext_encode
        let mut framed = vec![0x20u8];
        framed.extend_from_slice(&token);
        assert_eq!(framed.len(), 33, "the measurement was 33 bytes");
        assert_eq!(ext_decode(CONTINUITY_TOKEN_EXT, &framed).unwrap(), token.to_vec(),
            "a peer-built Welcome's 0xF010 must decode to the 32-byte value");
        assert_eq!(ext_encode(CONTINUITY_TOKEN_EXT, &token).unwrap(), framed,
            "and our own encoder must produce exactly those bytes");
        // bare
        assert_eq!(ext_decode(CONTINUITY_TOKEN_EXT, &token).unwrap(), token.to_vec(),
            "a bare 32-byte token decodes verbatim rather than erroring");
        // 31 bytes and a malformed frame stay errors
        assert!(ext_decode(CONTINUITY_TOKEN_EXT, &[0x5Au8; 31]).is_err(),
            "31 bytes is not a 256-bit token — tolerance must not become blanket verbatim");
        assert!(ext_decode(CONTINUITY_TOKEN_EXT, &[0x02, 0xAA]).is_err(), "declared 2, carries 1");
        // the commitment is not tolerated bare
        assert!(ext_decode(CONTINUITY_TOKEN_COMMITMENT_EXT, &[0xABu8; 32]).is_err(),
            "0xF011 gets no bare arm — see bare_payload_is_plausible");
    }

    /// The decoder accepts all three 0xF007 framings seen from other clients: 30 bytes (framed, as
    /// v4.0 §7.11.10.1 specifies), 29 (bare) and 0 (empty). Presence is the signal; nothing gates
    /// on the payload.
    #[test]
    fn all_three_observed_0xf007_framings_decode_without_error() {
        let _g = METADATA_KEYS_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let value = METADATA_KEYS_REQUESTED_DATA;          // 29 bytes
        let mut framed = vec![29u8];
        framed.extend_from_slice(value);                    // 30 bytes, opaque<V>
        let empty: &[u8] = &[];                             // 0 bytes

        assert_eq!(ext_decode(METADATA_KEYS_REQUESTED_EXT, value).unwrap(), value.to_vec(),
            "the 29-byte bare form must decode verbatim — its first byte is 'g'(0x67), which \
             must NOT be mistaken for a 103-byte length prefix");
        assert_eq!(ext_decode(METADATA_KEYS_REQUESTED_EXT, &framed).unwrap(), value.to_vec(),
            "v4.0's 30-byte framed form must yield the SAME value, so callers cannot tell which \
             encoder produced it");
        assert_eq!(ext_decode(METADATA_KEYS_REQUESTED_EXT, empty).unwrap(), Vec::<u8>::new(),
            "the third-party 0-byte form must decode to empty rather than erroring — presence is \
             the flag, so an empty payload is still a valid request");
    }

    /// 0xF007 encoding follows the announced revision: bare under v3.0, framed under v4.0.
    #[test]
    fn the_metadata_keys_framing_follows_the_revision_and_decodes_both() {
        let _g = METADATA_KEYS_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let _v = VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let bare = METADATA_KEYS_REQUESTED_DATA.to_vec();
        let mut framed = vec![29u8];
        framed.extend_from_slice(METADATA_KEYS_REQUESTED_DATA);
        assert_eq!(framed.len(), 30, "the framed form is exactly one byte longer");

        // encode follows the announcement
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        assert!(metadata_keys_is_bare());
        assert_eq!(ext_encode(METADATA_KEYS_REQUESTED_EXT, METADATA_KEYS_REQUESTED_DATA).unwrap(),
                   bare, "v3.0 is the bare v3.0-era form");

        assert_eq!(set_rcc16_version(RCC16_V4_0), RCC16_V4_0);
        assert!(!metadata_keys_is_bare());
        assert_eq!(ext_encode(METADATA_KEYS_REQUESTED_EXT, METADATA_KEYS_REQUESTED_DATA).unwrap(),
                   framed, "v4.0 §7.11.10.1 is opaque<V>");

        // decode accepts both under either announcement
        for v in [RCC16_V3_0, RCC16_V4_0] {
            set_rcc16_version(v);
            assert_eq!(ext_decode(METADATA_KEYS_REQUESTED_EXT, &bare).unwrap(), bare);
            assert_eq!(ext_decode(METADATA_KEYS_REQUESTED_EXT, &framed).unwrap(), bare);
        }
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
    }

    #[test]
    fn continuity_is_emitted_only_under_v4_and_the_token_is_welcome_only() {
        let _v = VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        assert!(!emit_continuity(),
            "v3.0 servers carry neither code point, and the failure             mode of getting continuity wrong is a DOWNGRADED conversation");
        assert_eq!(set_rcc16_version(RCC16_V4_0), RCC16_V4_0);
        assert!(emit_continuity());
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);

        // The token never leaves in a server-bound GroupInfo; its commitment belongs in every one.
        assert!(WELCOME_ONLY_EXTS.contains(&CONTINUITY_TOKEN_EXT));
        assert!(!WELCOME_ONLY_EXTS.contains(&CONTINUITY_TOKEN_COMMITMENT_EXT));
        assert!(WELCOME_ONLY_EXTS.contains(&ICON_KEY_EXT));
        assert!(WELCOME_ONLY_EXTS.contains(&SUBJECT_KEY_EXT));
        // both framed
        assert!(!ext_payload_is_bare(CONTINUITY_TOKEN_EXT));
        assert!(!ext_payload_is_bare(CONTINUITY_TOKEN_COMMITMENT_EXT));
    }

    /// 0xF007 is armed, and so advertised, by default.
    #[test]
    fn the_metadata_keys_code_point_is_armed_and_advertised_by_default() {
        let _g = METADATA_KEYS_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(metadata_keys_ext(), Some(0xF007),
            "v4.0 §7.11.10.1 publishes the value — it is no longer a guess to be withheld");
        assert!(advertised_extensions(&[]).contains(&METADATA_KEYS_REQUESTED_EXT),
            "a code point we can now name and decode must be advertised");
        // still overridable
        set_metadata_keys_ext(0);
        assert_eq!(metadata_keys_ext(), None);
        assert!(!advertised_extensions(&[]).contains(&METADATA_KEYS_REQUESTED_EXT));
        set_metadata_keys_ext(METADATA_KEYS_REQUESTED_EXT);
    }

    /// An unknown code point defaults to framed.
    #[test]
    fn an_unknown_type_defaults_to_framed() {
        assert!(!ext_payload_is_bare(0xF020));
        assert_eq!(ext_encode(0xF020, &[1, 2, 3]).unwrap(), vec![0x03, 1, 2, 3]);
    }

    /// The varint widens at 0x40, between a 32-byte commitment and a longer key.
    #[test]
    fn varint_widens_at_the_boundary_we_operate_in() {
        let short = vec![7u8; 0x3F];
        let long = vec![7u8; 0x40];
        assert_eq!(ext_encode(ICON_KEY_EXT, &short).unwrap()[..1], [0x3F]);
        assert_eq!(ext_encode(ICON_KEY_EXT, &long).unwrap()[..2], [0x40, 0x40]);
        for v in [short, long] {
            let e = ext_encode(ICON_KEY_EXT, &v).unwrap();
            assert_eq!(ext_decode(ICON_KEY_EXT, &e).unwrap(), v);
        }
    }

    /// A non-minimal varint (`0x40 0x20` for 32) is rejected, as peers reject it.
    #[test]
    fn a_non_minimal_varint_is_rejected() {
        let mut bad = vec![0x40u8, 0x20];
        bad.extend_from_slice(&[0xAB; 32]);
        assert!(ext_decode(SUBJECT_COMMITMENT_EXT, &bad).is_err(),
                "non-minimal length encoding must not decode");
        // the reserved `11` prefix is not a length
        assert!(ext_decode(SUBJECT_COMMITMENT_EXT, &[0xC0, 0x00]).is_err());
    }

    /// Trailing or missing bytes are an error, never returned as-is.
    #[test]
    fn a_length_that_disagrees_with_the_payload_is_rejected() {
        assert!(ext_decode(ICON_COMMITMENT_EXT, &[0x02, 0xAA]).is_err(), "declared 2, carries 1");
        assert!(ext_decode(ICON_COMMITMENT_EXT, &[0x01, 0xAA, 0xBB]).is_err(), "trailing byte");
        assert!(ext_decode(ICON_COMMITMENT_EXT, &[]).is_err(), "no varint at all");
        // empty but framed is legal
        assert_eq!(ext_encode(ICON_COMMITMENT_EXT, &[]).unwrap(), vec![0x00]);
        assert_eq!(ext_decode(ICON_COMMITMENT_EXT, &[0x00]).unwrap(), Vec::<u8>::new());
    }

    /// A longer `end_mls` payload is accepted on decode.
    #[test]
    fn a_longer_end_mls_payload_is_tolerated() {
        let odd = b"end_mls\x01\x02\x03".to_vec();
        assert_eq!(ext_decode(END_MLS_EXT, &odd).unwrap(), odd, "bare types decode verbatim");
    }

    /// The announced revision is process-global and tests run as threads in one process, so the
    /// tests that change it hold this lock (poison-tolerant).
    pub(crate) use super::VERSION_LOCK;

    /// The proposal advertisement changes with the revision.
    #[test]
    fn the_proposal_advertisement_follows_the_spec_revision() {
        let _g = VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());

        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        let v3 = advertised_proposals(&[]);
        assert!(v3.contains(&END_MLS_PROP),
            "under v3.0 the end_mls PROPOSAL is a live registry entry a peer may still send, and we              handle it inbound — dropping it would under-advertise against the only production              transport we have");
        assert!(v3.contains(&0xF018) && !v3.contains(&0xF019),
            "v3.0 keeps the observed 0xF010..=0xF018 band and nothing beyond it");

        assert_eq!(set_rcc16_version(RCC16_V4_0), RCC16_V4_0);
        let v4 = advertised_proposals(&[]);
        assert!(!v4.contains(&END_MLS_PROP),
            "v4.0 §7.11.2.1 is the single word `void` — the proposal is WITHDRAWN");
        assert!(v4.contains(&0xF012) && v4.contains(&0xF030) && !v4.contains(&0xF031),
            "v4.0 §7.11.11.1 reserves 0xF012..=0xF030 and REQUIRES clients to advertise support");
        assert!(!v4.contains(&0xF010) && !v4.contains(&0xF011),
            "under v4.0 0xF010/0xF011 are ASSIGNED extensions (continuity token + commitment), not              reserved proposals — advertising them as proposals would be a different claim");
        // the implemented set minus the withdrawn proposal, both ways
        for p in [RCS_SIGNATURE_PROP, SELF_REMOVE_PROP, SERVER_REMOVE_PROP] {
            assert!(v3.contains(&p) && v4.contains(&p), "0x{p:04X} is implemented in both");
        }

        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
    }

    /// The default revision is v3.0.
    #[test]
    fn the_default_version_is_v3_0_and_an_unknown_value_is_refused() {
        let _g = VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(rcc16_version(), RCC16_V3_0, "the shipped default must be v3.0");
        assert_eq!(set_rcc16_version(99), RCC16_V3_0, "an unrecognised version must be REFUSED \
            and the current one kept — a truncated or typo'd value must not select a spec revision");
        assert_eq!(set_rcc16_version(RCC16_V4_0), RCC16_V4_0);
        assert!(is_v4_0_or_later());
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        assert!(!is_v4_0_or_later());
    }

    /// The revision selects the `end_mls` payload.
    #[test]
    fn end_mls_payload_follows_the_announced_version() {
        let _g = VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        // v3.0 ignores the reason
        for r in [EndMlsReason::Unset, EndMlsReason::OutgoingCommitFailed,
                  EndMlsReason::ServerFailureEraAdvancementQuotaReached] {
            assert_eq!(end_mls_payload(r), END_MLS_DATA.to_vec(),
                "under v3.0 the payload is the ASCII literal for EVERY reason — this is the byte \
                 sequence the server accepts and peers decrypt");
        }

        assert_eq!(set_rcc16_version(RCC16_V4_0), RCC16_V4_0);
        // bare protobuf, field 1 varint
        assert_eq!(end_mls_payload(EndMlsReason::ContinuityTokenMismatch), vec![0x08, 0x05]);
        assert_eq!(end_mls_payload(EndMlsReason::OutgoingCommitFailed), vec![0x08, 0x0A]);
        // Unset serialises to nothing, distinct from the v3.0 string
        assert_eq!(end_mls_payload(EndMlsReason::Unset), Vec::<u8>::new());
        assert_ne!(end_mls_payload(EndMlsReason::Unset), END_MLS_DATA.to_vec());

        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
    }

    /// A v4.0 payload is understood while v3.0 is announced.
    #[test]
    fn both_end_mls_payload_forms_are_understood_regardless_of_the_announced_version() {
        let _g = VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(rcc16_version(), RCC16_V3_0, "tolerance is tested from the v3.0 announcement");
        assert!(end_mls_payload_understood(END_MLS_DATA), "the v3.0 literal");
        assert!(end_mls_payload_understood(b"end_mls\x01\x02"), "the tolerated longer v3.0 tag");
        assert!(end_mls_payload_understood(&[]), "v4.0 UNSET is zero bytes");
        assert!(end_mls_payload_understood(&[0x08, 0x0B]), "v4.0 era-quota reason");
        // Not understood, yet presence alone still means end_mls.
        assert!(!end_mls_payload_understood(&[0x08, 0x7F]), "0x7F is not a defined reason");
        assert!(!end_mls_payload_understood(&[0xFF, 0x01]), "field 31 wiretype 7 is not our shape");
    }
}

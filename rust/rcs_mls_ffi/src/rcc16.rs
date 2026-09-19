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

// M3: RCC.16 X.509 credential path. Builds an mls-rs client from a KDS-style leaf+chain
// (SAN URI:tel:, CRITICAL vendor ext 2.23.146.2.1.4) — proving mls-rs tolerates the critical
// extension that fails openssl/BoringSSL with err-34, which is what breaks a BoringSSL-based client.
use mls_rs::{
    client_builder::MlsConfig, group::ReceivedMessage,
    CipherSuite, Client, ExtensionList, MlsMessage,
};
use mls_rs_core::{crypto::{SignaturePublicKey, SignatureSecretKey}, identity::{SigningIdentity, Credential},
    extension::{Extension, ExtensionType, ExtensionList as CoreExtensionList}};
use mls_rs::mls_rs_codec::{MlsDecode, MlsEncode, VarInt};
use mls_rs::MlsRules;
use mls_rs::group::{GroupContext, Roster};
use mls_rs::mls_rules::{CommitDirection, CommitOptions, CommitSource, DefaultMlsRules,
    EncryptionOptions, ProposalBundle};
use core::time::Duration;
use mls_rs_identity_x509::{CertificateChain, DerCertificate, SubjectIdentityExtractor, X509IdentityProvider};
use mls_rs_crypto_rustcrypto::{x509::{X509Reader, X509Validator}, RustCryptoProvider};

const SUITE: CipherSuite = CipherSuite::P256_AES128;

// Smoke entry points. #[cfg(test)]-gated deliberately: these construct MLS clients from
// key material embedded by include_bytes!, and an ungated #[no_mangle] export put those
// private keys in the shipped staticlib with no caller anywhere in the tree.
#[cfg(test)]
// Test fixtures: P256 chain (root->ICA->leaf), leaf has SAN tel: + CRITICAL ext 2.23.146.2.1.4.
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

/// Build a client whose credential is the KDS-issued X.509 chain (leaf-first), with a root-anchored
/// validator. The subject key (priv scalar 32B / pub SEC1 65B) is the key the leaf certifies.
#[cfg(test)]
fn build_x509_client(
    leaf: &[u8], chain: &[&[u8]], priv32: &[u8], pub65: &[u8], roots: &[&[u8]],
) -> Result<Client<impl MlsConfig>, String> {
    let mut certs = vec![DerCertificate::from(leaf.to_vec())];
    for c in chain { certs.push(DerCertificate::from(c.to_vec())); }
    let cert_chain = CertificateChain::from(certs);
    let credential = cert_chain.into_credential();
    let signing_identity = SigningIdentity::new(credential, SignaturePublicKey::from(pub65.to_vec()));
    let secret = SignatureSecretKey::from(priv32.to_vec());

    let extractor = SubjectIdentityExtractor::new(0, X509Reader::new());
    let root_list: Vec<DerCertificate> = roots.iter().map(|r| DerCertificate::from(r.to_vec())).collect();
    let validator = X509Validator::new(root_list).map_err(|e| format!("validator: {e:?}"))?;
    let idp = X509IdentityProvider::new(extractor, validator);

    // THE SAME CANONICAL LISTS PRODUCTION USES. This fixture previously advertised 0xF001 plus a
    // WIDER vendor range (to 0xF040) and no proposals at all, so the conformance smoke test did not
    // exercise the capability set we actually ship — the one thing a conformance test is for.
    // KeyPackage lifetime stays 365 days (M0.5 byte-map: Google not_after = +365d).
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

/// End-to-end x509 round-trip: A adds B (validates B's critical-ext chain), B joins (validates A's),
/// both directions encrypt/decrypt. If this passes, mls-rs accepts the KDS leaf BoringSSL rejects.
#[cfg(test)]
pub fn x509_roundtrip() -> Result<(), String> {
    let a = build_x509_client(LEAF_A, &[ICA], LEAF_A_PRIV, LEAF_A_PUB, &[ROOT])?;
    let b = build_x509_client(LEAF_B, &[ICA], LEAF_B_PRIV, LEAF_B_PUB, &[ROOT])?;
    let b_kp = b.generate_key_package_message(Default::default(), Default::default(), None)
        .map_err(|e| format!("gen_kp: {e:?}"))?;
    let mut ag = a.create_group(ExtensionList::default(), Default::default(), None)
        .map_err(|e| format!("create_group: {e:?}"))?;
    let commit = ag.commit_builder().add_member(b_kp).map_err(|e| format!("add(validate B cert): {e:?}"))?
        .build().map_err(|e| format!("commit: {e:?}"))?;
    ag.apply_pending_commit().map_err(|e| format!("apply: {e:?}"))?;
    let (mut bg, _) = b.join_group(None, &commit.welcome_messages[0], None)
        .map_err(|e| format!("join(validate A cert): {e:?}"))?;
    let ct = ag.encrypt_application_message(b"x509 hello", Default::default()).map_err(|e| format!("enc: {e:?}"))?;
    match bg.process_incoming_message(ct).map_err(|e| format!("dec: {e:?}"))? {
        ReceivedMessage::ApplicationMessage(m) if m.data() == b"x509 hello" => {}
        other => return Err(format!("bad decrypt: {other:?}")),
    }
    let _ = MlsMessage::from_bytes(&[]); // silence unused import if any
    Ok(())
}


/// RCC.16 Era GroupContext extension type (§7.11.1.1): 0xF001, extension_data = uint32.
pub const ERA_EXT: u16 = 0xF001;
/// The era a group that has never existed is born at. Era 0 is reserved: `create_group_carry` reads
/// it as "emit no Era extension at all", which Google Messages refuses outright with `Missing Era group
/// context extension`, so the first real era is 1.
pub const ERA_INITIAL: u32 = 1;

/// The engine→host `welcomeAction` discriminator (design §9.5).
///
/// # Why these live in the ENGINE and not in the host
///
/// The era is not a request field — it exists ONLY as the 0xF001
/// GroupContext extension inside the GroupInfo — so whatever writes 0xF001 is the only layer that
/// can honestly say which era a group was built at. That layer is here. When the host picked the
/// number and handed it down, "which era did we create at" and "which era does the GroupInfo claim"
/// were two separate facts that could disagree, and only the second one ever reached the server.
///
/// So the engine derives the era from state it can see and REPORTS what it did, as one of these.
/// A host that receives the action does not need to know how it was reached — it only has to pick
/// the RPC. An enum computed by the host from a number the host chose would be the host's decision
/// relocated, not removed; these are numbered to match Google Messages so a trace diff reads.
pub const WELCOME_ACTION_UNKNOWN: u32 = 0;
/// A group that did not exist, anywhere we can see. Born at [`ERA_INITIAL`].
pub const WELCOME_ACTION_NEW_GROUP: u32 = 1;
/// A NEW ERA of a group that already exists — same RCS group id, new MLS group, era moved forward.
pub const WELCOME_ACTION_NEW_ERA_EXISTING_GROUP: u32 = 2;
/// Members are being ADDED to a group we already hold, at the SAME era. Not a create at all: the
/// artifacts are an `addMembers` commit and the host must ship them on the add RPC.
pub const WELCOME_ACTION_NEW_MEMBERSHIP_EXISTING_GROUP: u32 = 3;

/// Which of `requested` name a participant that `held` does not already contain.
///
/// # Why the comparison is by MSISDN and not by leaf or by key
///
/// A participant is a PERSON, and the RCS half of an add names people. Removal already selects this
/// way (§15.2 — removing a two-device participant must take both leaves), and the two sides have to
/// agree on what "a member" is or the roster change and the MLS commit end up naming different
/// things, which is the `mismatched-rcs-group-state` shape.
///
/// The consequence worth stating outright: **a second DEVICE of somebody already in the group is not
/// an addition.** The participant list does not change, so there is nothing for the add RPC to name;
/// that is a membership refresh, and it belongs on the create/advance path where the whole roster is
/// rebuilt from freshly claimed packages.
///
/// An identity we cannot READ (empty) counts as new. Treating an unreadable one as already-present
/// would silently drop a member from the commit — a failure that is invisible until the person
/// cannot decrypt anything — whereas counting it as new can at worst produce a redundant add.
pub fn additions_to(held: &[Vec<u8>], requested: &[Vec<u8>]) -> Vec<usize> {
    requested.iter().enumerate()
        .filter(|(_, id)| id.is_empty()
            || !held.iter().any(|h| crate::rcc16_validate::msisdn_equals(h, id)))
        .map(|(i, _)| i)
        .collect()
}

/// The RCC.16 §7.11 PROPOSAL registry. Shares 0xF00x numbering with the extension registry above and
/// is a DIFFERENT namespace — 0xF002 is `end_mls` as an extension and `rcs_signature` as a proposal.
/// Conflating the two is the first mistake available here.
///
/// THREE OF THESE FOUR ARE NOW BOUND TO UPSTREAM RATHER THAN RE-TYPED (2026-08-07).
/// mls-rs-core defines SELF_REMOVE (under `self_remove_proposal`) and RCS_SIGNATURE /
/// RCS_SERVER_REMOVE (under `gsma_rcs_e2ee_feature`), and we enable all three features. Deriving our
/// `u16` from `ProposalType::…​.raw_value()` — a `const fn`, so this is still a compile-time constant
/// — converts "our number happens to equal theirs" into "our number CANNOT differ from theirs".
///
/// That matters more than the tidiness: these numbers select the CODEC path. If a future mls-rs
/// moved a code point, or if someone dropped one of those features from Cargo.toml, a hand-typed
/// literal would keep compiling and start disagreeing with the encoder on the wire — silently, and
/// in the one place where silence is most expensive. Bound this way, either change breaks the BUILD.
///
/// `END_MLS_PROP` (0xF001) stays a literal because upstream has no counterpart: RCC.16 v3.0 defined
/// it and v4.0 §7.11.2.1 voided it, so no library will ever define it for us.
pub const END_MLS_PROP: u16 = 0xF001;
pub const SELF_REMOVE_PROP: u16 =
    mls_rs_core::group::ProposalType::SELF_REMOVE.raw_value();
pub const SERVER_REMOVE_PROP: u16 =
    mls_rs_core::group::ProposalType::RCS_SERVER_REMOVE.raw_value();

// =================================================================================================
// THE CANONICAL CAPABILITY LISTS — one source of truth for what we ADVERTISE
//
// There were two advertisement sites and they disagreed: production advertised 0xF001-0xF007 plus a
// vendor range, while the conformance SMOKE fixture in this file advertised only 0xF001 plus a wider
// range and NO proposals. So the smoke test never exercised the capability set we actually ship.
// Both now build from these lists, which is the only way they cannot drift again.
//
// THE STANDING POSITION (Rashed, 2026-08-06) is that we do NOT trim the advertisement to match the
// implementation. We are building as close a reimplementation of Google Messages as we can, we
// cannot see what the server does, and quietly advertising less would hide a gap rather than close
// it. So the split below is deliberate and permanent in shape: what we implement, and what we
// advertise BEYOND that — named, so the difference is legible instead of being an unexplained range.
// =================================================================================================

/// Code points we ACTUALLY IMPLEMENT, verified against the code 2026-08-06.
///
/// 0xF003/0xF005 (the icon/subject KEYS) are in this list. They were once recorded as
/// "structurally blocked" because §9.7.1.4 asks for a sanitised GroupInfo that mls-rs cannot build —
/// but `commit_group_metadata` implements them anyway and records why: Google Messages demonstrably
/// puts the key in the GroupContext, so refusing would mean never implementing the flow at all. The
/// confidentiality question that raises is documented, not solved.
pub const IMPLEMENTED_EXTENSIONS: &[u16] = &[
    ERA_EXT,                 // 0xF001 — set on every create, read by era_of
    END_MLS_EXT,             // 0xF002 — commit_end_mls, both directions, device-proven
    ICON_KEY_EXT,            // 0xF003 — commit_group_metadata
    ICON_COMMITMENT_EXT,     // 0xF004 — commit_group_metadata + commit_icon_subject
    SUBJECT_KEY_EXT,         // 0xF005 — commit_group_metadata
    SUBJECT_COMMITMENT_EXT,  // 0xF006 — commit_group_metadata + commit_icon_subject
];

/// Proposal types we ACTUALLY IMPLEMENT.
///
/// `RCS_SIGNATURE_PROP` (0xF002) is here and was NOT previously advertised — implemented,
/// device-proven sign+verify, and silently absent from the capability list. That is the inverse
/// failure to an over-advertisement and just as wrong: we could do it and did not say so.
/// **`END_MLS_PROP` (0xF001) is v3.0-ONLY and is filtered out under v4.0** — see
/// [`advertised_proposals`]. v4.0 §7.11.2.1 is the single word `void`: the proposal is WITHDRAWN.
/// It stays in this list because under v3.0 — which is what Tachyon speaks — it is a live registry
/// entry that a peer may still send, and we do still handle it inbound. Dropping it outright would
/// be an under-advertisement against the only production transport we have.
///
/// **GOOGLE MESSAGES STILL ADVERTISES IT.** That upgrades the
/// justification above from "the v3.0 registry has it" to "the client we interoperate with puts it
/// on the wire", which is the evidence that actually matters — and it makes this one of the few
/// divergences running the OTHER way: an implementation RETAINING something the spec deleted,
/// rather than shipping something the spec had not yet documented.
///
/// Two consequences worth stating because they are easy to get backwards:
///  * Do **not** read an advertised 0xF001 proposal as evidence of a v4.0-conformant peer.
///  * Do **not** add the proposal FORM on the strength of Google Messages advertising the code point.
///    Advertising a type and implementing it are different claims; §7.11.2.1 is void either way.
///
/// Google Messages' position, which is the one to build against: *substantially a v4.0
/// implementation, but not cleanly one — a v4.0 feature set carried on a v3.0-derived capability
/// list.* Any capability list derived from its advertisements is therefore a v3.0-era list,
/// whatever else that client implements.
pub const IMPLEMENTED_PROPOSALS: &[u16] = &[
    END_MLS_PROP,        // 0xF001 — inbound, committed with the §7.11.2.2 extension. v3.0 ONLY.
    RCS_SIGNATURE_PROP,  // 0xF002 — rcs_sign / rcs_verify
    SELF_REMOVE_PROP,    // 0xF003 — self_leave; EMIT via SIP BYE belongs to the carrier transport
    SERVER_REMOVE_PROP,  // 0xF004 — applied as a plain Remove; the body is genuinely unknown
];

/// Advertised WITHOUT an implementation behind them, deliberately and by the standing position.
///
/// Registration and interop want the full surface, and a group that carries one of these must not
/// refuse to add us over a capability check. What we get for naming them here rather than inlining a
/// range is that "advertised but unimplemented" becomes a list somebody can read and shorten.
///
///  * 0xE000 — our own testing expiry override.
///  * 0xF007 — group_metadata_keys_requested. **NO LONGER A GUESS, and this note used to say it
///    was.** It came from RCC.16 v4.0 §7.11.10.1 as a published assignment (2026-08-06) and was
///    then VERIFIED ON DEVICE: 0xF007 observed on the wire, the gate evaluates keysRequested=true
///    and declines correctly. The earlier "DO NOT GUESS A VALUE" caution was correct when it was
///    written and is DISCHARGED; leaving it standing kept a settled number reading as
///    unverified. It is inert on the emit side — we never produce it — but it IS advertised, via
///    `metadata_keys_ext()` in `advertised_extensions`, not via the list below.
///    FALSIFIER: a Google Messages GroupInfo or Welcome carrying the metadata-keys gate at a type
///    OTHER than 0xF007. That would also mean the observation above read some other extension as
///    the gate. `set_metadata_keys_ext` / `debug.rcs.mls_metadata_keys_ext` exist to honour it
///    without a rebuild.
///  * 0xF010..=0xF030 — the vendor range. **0xF010 is the continuity TOKEN and 0xF011 is its
///    COMMITMENT** (v4.0 §7.11.12). Google Messages does a find-by-u16 PRESENCE lookup on the
///    token, which is exactly the right operation for a Welcome-only secret. The rest of the
///    range is unclaimed.
/// NOTE the metadata-keys type is absent from THIS list because it is settable at runtime, not
/// because it is unverified — it was both, until v4.0 published the number and a device confirmed
/// it. [`metadata_keys_ext`] holds it (armed to 0xF007 by default) and `advertised_extensions`
/// adds it from there, so the advertisement follows whatever that value is set to.
pub const ADVERTISED_UNIMPLEMENTED_EXTENSIONS_FIXED: &[u16] = &[TESTING_OVERRIDE_EXPIRY_EXT];
pub const ADVERTISED_VENDOR_EXT_RANGE: core::ops::RangeInclusive<u16> = 0xF010..=0xF030;
pub const ADVERTISED_VENDOR_PROP_RANGE: core::ops::RangeInclusive<u16> = 0xF010..=0xF018;

/// v4.0 §7.11.11.1 — the `reserved_for_future_use` PROPOSAL band, and advertising it is MANDATORY:
/// *"Clients shall advertise support of reserved_for_future_use extensions in their KeyPackages."*
///
/// Version-gated rather than simply widening [`ADVERTISED_VENDOR_PROP_RANGE`], because this band
/// **does not exist in v3.0**. Advertising 0xF019..=0xF030 as proposals to a v3.0 peer claims
/// support for numbers that revision does not define — an over-advertisement, which the doctrine
/// above treats as exactly as wrong as an under-advertisement. Note the band starts at 0xF012, not
/// 0xF010: under v4.0 0xF010/0xF011 are ASSIGNED (continuity token / commitment), not reserved.
///
/// ⚠ **NO SHIPPING IMPLEMENTATION ADVERTISES THIS BAND — measured 2026-08-08, and the v4.0 gate
/// should have to answer it.** Both vendors we hold key packages for advertise proposals
/// `0xF010..=0xF018`, which is [`ADVERTISED_VENDOR_PROP_RANGE`] — our v3.0 default — exactly:
///
/// ```text
///   Google  55 key packages, two devices, ten days apart : F001 F003 F004 F010..F018
///   Apple    7 captured control payloads                 : F002 F004 F003 F010..F018
///   v4.0 §7.11.11.1 reserves                             : F012..F030
/// ```
///
/// So switching to v4.0 moves our advertisement AWAY from every implementation we can talk to, toward
/// a band neither uses. That is not automatically wrong — the spec says what it says, and this band
/// is genuinely v4.0-only — but "the spec mandates it" is now a weaker argument than it looks: TWO
/// INDEPENDENT IMPLEMENTATIONS DIVERGE FROM §7.11.11.1 IDENTICALLY, which makes F010..F018 the
/// de-facto standard and the spec text the outlier. We had previously recorded that divergence as a
/// contradiction against GOOGLE specifically; the second vendor reverses that reading.
///
/// The extension side is the opposite and worth contrasting: both vendors comply with §7.11.11.2's
/// `F012..F030` exactly. So the spec is FOLLOWED on extensions and DIVERGED FROM on proposals, by
/// both — which is why this cannot be dismissed as one vendor being sloppy.
///
/// # THE DECISION THIS GATE MUST MAKE — **SETTLED 2026-09-13**
///
/// **RULING: (a) `F010..=F018` on v3.0 · (b) `F012..=F030` AT THIS GATE · (c) the UNION REJECTED.**
/// This const already implements (b), so the VALUE below does not change — what changed is that
/// it is now a CHOICE. Before this ruling, whoever flipped `is_v4_0_or_later()` would have shipped
/// a band matching nobody on the wire **without anyone having decided to**. The flip itself is
/// tracked separately and is not made here.
///
/// ⚠ **PRECONDITION ON THE FLIP, and it is the under-declaration risk made concrete:** moving to
/// (b) DROPS `F010`/`F011` from our advertised proposals, and that genuinely DOES subtract them
/// from the all-leaves intersection. **Re-measure BOTH vendors' advertised band on v4.0-capable
/// builds before flipping.** The measurement below is from v3.0-era clients and does not transfer.
///
/// ```text
///   (a) keep F010..=F018   matches every implementation we can talk to; diverges from §7.11.11.1
///   (b) spec F012..=F030   matches the spec; matches nobody on the wire        <- what this const does
///   (c) union F010..=F030  monotone; cannot shrink the intersection            <- REJECTED
/// ```
///
/// **WHY (c) WAS REJECTED — it is monotone AND operationally inert.** `can_support_proposal` is an
/// ALL-LEAVES intersection (`mls-rs .../tree_kem/mod.rs:247-254`), so our list can only change the
/// outcome for a type EVERY OTHER MEMBER ALREADY ADVERTISES — and both measured vendors stop at
/// `F018`. For every type in `F019..F030` the intersection is empty **because of them, not because
/// of us**. So the union buys nothing against anyone we can talk to, while TRIPLING the surface of
/// mitigation M1 from 9 advertised-unimplemented proposal types to 27. M1's own table says all
/// three of its options are wrong and it picked the least bad; its lever
/// (`clear_pending_proposals` = mls-rs `clear_proposal_cache`) wipes the WHOLE cache, and both of
/// its collateral failures were found by being bitten — a peer's announced departure lost forever,
/// and silently ending up back in a group we had left.
///
/// **The monotonicity case is NOT refuted — it is three claims with three verdicts.** (1) the
/// monotonicity arithmetic is observed, and it is about our own vendored mls-rs, not about the peer.
/// (2) "under-declaring gets you REJECTED FROM THE GROUP" — the MECHANISM is observed, at
/// `leaf_node_validator.rs:143` (**not `:125`**, which is the enclosing
/// `validate_required_capabilities`); the TRIGGER has never been observed, because that function
/// early-returns `Ok(())` without a `RequiredCapabilitiesExt` (`:126-133`, and its commit-path
/// caller is gated by `must_check` at `filtering_common.rs:223`) and no group we have ever parsed
/// publishes one — n=1, the single Google Messages GroupInfo we hold. (3) its stated precondition,
/// "the peer's dispatch tolerates types it does not implement", is inferred rather than observed:
/// nothing in any capture shows those code points being handled at all, so "tolerates" describes
/// an untraced default fall-through.
/// Note also that the precondition names M1 — and **M1 exists because we over-advertise**, so it
/// cannot be the reason it is safe to over-advertise further.
///
/// **The case against (c) — our own no-over-advertisement doctrine**, stated above and applied
/// consistently elsewhere in this file (it is why `metadata_keys_ext` is absent while its code point
/// is unverified). Claiming `F019..F030` under v3.0 asserts support for numbers that revision does
/// not define, which the doctrine treats as *exactly as wrong* as under-advertising.
/// **Do not read the doctrine as absolute, and do not cite the EXTENSION axis against it:**
/// `ADVERTISED_VENDOR_EXT_RANGE` above is the full `F010..=F030` union and that is deliberate. An
/// advertised EXTENSION we do not implement costs nothing at receive time — unknown extensions are
/// data we ignore and carry forward. An advertised PROPOSAL we do not implement blocks EVERY
/// application message until it is committed, because `commit_required()` is
/// `!self.state.proposals.is_empty()` for ANY cached by-reference proposal regardless of type. That
/// asymmetry is the whole reason M1 exists on this axis and there is no M1 for extensions.
///
/// REOPEN CONDITION: any captured GroupInfo, from anyone, carrying a `required_capabilities`
/// extension that names proposal types. That makes the mechanism above reachable and this ruling
/// worth re-running.
///
/// *(Superseded: "DEFERRED TO THIS GATE, 2026-08-20" and "Neither side is
/// settled by evidence — that is why this is a decision and not a bug." The deferral ended on
/// 2026-09-13; what settled it was not new evidence about the spec but the inertness argument
/// above, which nobody had made.)*
///
/// ```text
///   (a) keep F010..=F018   matches every implementation we can talk to; diverges from §7.11.11.1
///   (b) spec F012..=F030   matches the spec; matches nobody on the wire        <- what this const does
///   (c) union F010..=F030  monotone; cannot shrink the intersection
/// ```
///
pub const RESERVED_FUTURE_PROP_RANGE_V4: core::ops::RangeInclusive<u16> = 0xF012..=0xF030;

/// The full advertised extension set = implemented + advertised-only + any TRANSPORT EXTRAS.
///
/// `extra` is the transport-layer override: Tachyon registration may require advertising a code
/// point this engine knows nothing about, and that is a transport concern rather than a spec one.
/// Passing an empty slice yields exactly the set we shipped before this list existed, plus 0xF002's
/// proposal fix.
pub fn advertised_extensions(extra: &[u16]) -> Vec<u16> {
    let mut v: Vec<u16> = IMPLEMENTED_EXTENSIONS.to_vec();
    v.extend_from_slice(ADVERTISED_UNIMPLEMENTED_EXTENSIONS_FIXED);
    if let Some(m) = metadata_keys_ext() { v.push(m); }
    v.extend(ADVERTISED_VENDOR_EXT_RANGE);
    for e in extra { if !v.contains(e) { v.push(*e); } }
    v
}

/// As above for proposals — and the one advertisement that is VERSION-DEPENDENT.
///
/// Two v4.0 changes, neither of which may be applied unconditionally, because Tachyon is v3.0:
///
///  * `end_mls` as a PROPOSAL (0xF001) is **withdrawn** — v4.0 §7.11.2.1 is the single word `void`.
///    Under v3.0 it is a live registry entry a peer may still send and that we still handle inbound,
///    so dropping it there would under-advertise against the only production transport we have.
///  * The `reserved_for_future_use` proposal band 0xF012..=0xF030 is **new and mandatory to
///    advertise** (§7.11.11.1). It does not exist in v3.0, so advertising it to a v3.0 peer claims
///    support for undefined numbers.
///
/// Under v3.0 this returns exactly what it returned before the version enabler existed.
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

/// Advance an era by exactly one, refusing the u32 ceiling rather than wrapping.
///
/// Google Messages **panics** at `u32::MAX` instead of wrapping, because a wrap reads to every peer
/// as a move backwards and cannot be undone. Refusing is the same decision without the crash. This
/// is the only place in the crate that increments an era.
pub fn next_era(current: u32) -> Result<u32, String> {
    current.checked_add(1).ok_or_else(|| format!(
        "era {current} is the u32 ceiling; advancing would wrap to 0, which reads as a move \
         BACKWARDS to every peer and cannot be undone"))
}
/// RCC.16 §7.11.2.2 — `end_mls` GroupContext extension. Its presence means the RCS conversation has
/// moved to UNENCRYPTED: §9.1.1 "the client shall not send encrypted messages if the end_mls
/// GroupContext Extension is present in the GroupInfo". Reversible — the server accepts a Commit that
/// REMOVES it (§ server checks item 3), which is how a conversation goes back to encrypted.
pub const END_MLS_EXT: u16 = 0xF002;
/// RCC.16 §7.11.7.1 — `rcs_signature` PROPOSAL type (0xF002 in the proposal registry, distinct from
/// the end_mls GroupContext extension that shares the number). §7.6.2 uses it as the carrier for a
/// CPIM message signature; it is never committed.
/// Bound to upstream (see the proposal registry above) so it cannot drift from the codec that
/// encodes it.
pub const RCS_SIGNATURE_PROP: u16 =
    mls_rs_core::group::ProposalType::RCS_SIGNATURE.raw_value();
/// The extension_data **RCC.16 v3.0** fixes for it: the literal string "end_mls".
///
/// v4.0 replaces this with a serialised `EndMlsMetadata` protobuf — see [`end_mls_payload`], which
/// is the only thing that should choose between the two. Encode sites must not reach for this
/// constant directly; it is `pub` because the v3.0 form is also what a v3.0 peer sends us, so the
/// DECODE side compares against it.
pub const END_MLS_DATA: &[u8] = b"end_mls";

// ---------------------------------------------------------------------------------------------
// THE RCC.16 SPECIFICATION VERSION — announced by the TRANSPORT, never inferred
// ---------------------------------------------------------------------------------------------
//
// GSMA published RCC.16 v4.0 in 2026-08 and it changes shapes we have already device-proven against
// Google Tachyon. The clearest is `end_mls` above: v3.0 puts a 7-byte ASCII literal in
// extension_data, v4.0 puts a serialised protobuf there. Both cannot be on the wire at once, and
// the two transports we speak to are NOT on the same version:
//
//   * TACHYON is v3.0. No v4.0 shape has ever been observed from it, and our v3.0-form `end_mls`
//     was ACCEPTED by the server and device-proven both directions against Google Messages.
//     Shipping a v4.0 shape at it would break a path that currently works.
//   * The LAB (openrcs) can be moved to v4.0 on request — their CPM AS is already an RCC.16
//     Conversation Focus and they develop against the spec.
//
// So the engine has to serve both AT THE SAME TIME, and it cannot discover which is which: the spec
// version is a property of the DEPLOYMENT, not of MLS. It is announced, exactly like the capability
// advertisement override (`start_with_advertisement`) and the behaviour profile
// (`rcs_mls_set_tachyon_profile`) before it.
//
// **DO NOT infer the version** from a capability advertisement, a probe, or the shape of something
// that arrived. We have no evidence Tachyon signals its RCC.16 version anywhere, and a detection
// heuristic here is the silent-failure class — the same trap as guessing a code point, where a
// guessed 0xF007 was driving two behaviours for months.
//
// **Decode tolerantly, encode strictly.** Accepting both forms inbound is usually cheap and protects
// us against a server that upgrades before we notice; what we PRODUCE must follow the announced
// version exactly. Where tolerance is impossible, the site says so.

/// RCC.16 v3.0 — the only version with wire evidence behind it, and therefore the default.
pub const RCC16_V3_0: u8 = 30;
/// RCC.16 v4.0 — published 2026-08, not yet observed on any transport we talk to.
pub const RCC16_V4_0: u8 = 40;

/// One value, so flipping a transport is ONE assignment and cannot leave half the shapes behind.
/// One shared `.a`, but each app runs in its own process, so this static is per-app — the same
/// reasoning as `TACHYON_PROFILE`.
static RCC16_VERSION: core::sync::atomic::AtomicU8 =
    core::sync::atomic::AtomicU8::new(RCC16_V3_0);

/// The announced RCC.16 version. [`RCC16_V3_0`] until a transport says otherwise.
pub fn rcc16_version() -> u8 {
    RCC16_VERSION.load(core::sync::atomic::Ordering::Relaxed)
}

/// Announce the version the TRANSPORT speaks. Returns the version now in effect.
///
/// An unrecognised value is REFUSED and the current version is kept — a typo'd or truncated value
/// must not silently select a spec revision. The caller sees the refusal in the return value.
pub fn set_rcc16_version(v: u8) -> u8 {
    if v == RCC16_V3_0 || v == RCC16_V4_0 {
        RCC16_VERSION.store(v, core::sync::atomic::Ordering::Relaxed);
    }
    rcc16_version()
}

/// True iff the announced version is v4.0 or later. Every version-divergent site reads THIS, not a
/// direct comparison, so adding a v5.0 does not mean auditing each branch for `== RCC16_V4_0`.
pub fn is_v4_0_or_later() -> bool { rcc16_version() >= RCC16_V4_0 }

/// v4.0 §7.11.2.2 `EndMlsReason` — the reason the downgrading client initiates the downgrade.
///
/// Twelve values, and two of them name things we already had under our own names: `10` is our
/// **Phoenix** mode (v4.0 is the first GSMA document to use that word) and `11` is the era-advance
/// quota we chased for a while as a silent server no-move (note the published quota numbers do NOT
/// fit that observation, so this is a name for the concept, not a diagnosis).
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

/// The `end_mls` `extension_data` for the ANNOUNCED version.
///
/// * v3.0 — the 7-byte ASCII literal `end_mls`. `reason` is ignored: v3.0 has nowhere to put it.
/// * v4.0 — a bare serialised `EndMlsMetadata { downgrade_reason = 1 }`, NOT a length-framed
///   `opaque<V>`. A single varint field, so `reason = 5` is the two bytes `08 05`. **`Unset`
///   serialises to ZERO bytes** — an empty `extension_data` is a legal `EndMlsMetadata`, and it is
///   emphatically not the same thing as the v3.0 7-byte string.
///
/// Both encode sites go through here so the two cannot drift; the bare-vs-framed rule in
/// [`ext_payload_is_bare`] is unchanged (0xF002 is bare in both versions).
pub fn end_mls_payload(reason: EndMlsReason) -> Vec<u8> {
    if !is_v4_0_or_later() { return END_MLS_DATA.to_vec(); }
    if reason == EndMlsReason::Unset { return Vec::new(); }
    // field 1, wire type 0 (varint) => tag 0x08. Every reason is < 128, so one byte of varint.
    vec![0x08, reason as u8]
}

/// True iff `data` is a plausible `end_mls` payload in EITHER version — the tolerant decode side.
///
/// Presence of the extension is what carries the meaning in both versions (§9.1.1 keys on the
/// extension being present, not on its contents), so this exists for the sites that want to know
/// they understood the payload rather than to gate the downgrade on it. A payload we cannot parse
/// must NOT be treated as "not end_mls" — that would keep sending ciphertext into a downgraded
/// conversation, which is the failure this extension exists to prevent.
pub fn end_mls_payload_understood(data: &[u8]) -> bool {
    // v3.0: the literal, possibly with trailing bytes (see the tolerance test below).
    if data.starts_with(END_MLS_DATA) { return true; }
    // v4.0: empty (UNSET), or a single varint field-1 whose value is a defined reason.
    match data {
        [] => true,
        [0x08, r] => *r <= EndMlsReason::ServerFailureEraAdvancementQuotaReached as u8,
        _ => false,
    }
}
/// RCC.16 §7.11.3.1 — `icon_key`: the symmetric key that decrypts the group icon.
/// **Welcome Message ONLY** — it must never appear in a GroupInfo sent to the server (§9.7.1.4 has
/// the sender build a SEPARATE, sanitised GroupInfo without it).
pub const ICON_KEY_EXT: u16 = 0xF003;
/// RCC.16 §7.11.4.1 — `icon_commitment` (Annex C.1). GroupInfo AND Welcome.
pub const ICON_COMMITMENT_EXT: u16 = 0xF004;
/// RCC.16 §7.11.5.1 — `subject_key`. Welcome Message ONLY, same sanitisation rule as icon_key.
pub const SUBJECT_KEY_EXT: u16 = 0xF005;
/// RCC.16 §7.11.6.1 — `subject_commitment` (Annex C.1). GroupInfo AND Welcome.
pub const SUBJECT_COMMITMENT_EXT: u16 = 0xF006;

/// The extensions that carry SECRETS and must be stripped from any GroupInfo leaving the device.
///
/// The CONTINUITY TOKEN (0xF010) joins the icon/subject keys here: v4.0 §7.11.12.1 says
/// *"Applicable message(s): GroupInfo (Welcome only)"* and *"to be included only in the encrypted
/// groupinfo in Welcome messages"*. Its COMMITMENT (0xF011) is the opposite — *"to be included in
/// all GroupInfo and Welcome messages"* — and is deliberately absent from this list. Getting those
/// two the wrong way round would hand the server a 256-bit group secret, which is precisely the
/// confidentiality defect v4.0's icon_key/subject_key reclassification exists to fix.
pub const WELCOME_ONLY_EXTS: [u16; 3] = [ICON_KEY_EXT, SUBJECT_KEY_EXT, CONTINUITY_TOKEN_EXT];

/// The §7.11.12 pair, for callers that need to reason about continuity as one feature.
pub const CONTINUITY_EXTS: [u16; 2] = [CONTINUITY_TOKEN_EXT, CONTINUITY_TOKEN_COMMITMENT_EXT];

/// Should we EMIT continuity extensions?
///
/// Gated on the announced spec revision, and this is the one item in the v4.0 batch that genuinely
/// changes bytes we put on the wire, so v3.0 emits nothing. Reading (rather than validating) is
/// always on — a peer that sends us a token is understood regardless of what we announce. Decode
/// tolerantly, encode strictly.
///
/// # THE ONE GATE TURNS ON TWO HALVES THAT CARRY VERY DIFFERENT RISK
///
/// `CONTINUITY_EXTS` is a pair and this predicate governs both, which hides the asymmetry:
///
///  * **0xF010, the TOKEN** — Welcome-ONLY, a secret handed to new joiners. Emitting it arms
///    nothing on the receiver: §7.11.12.1 gives it no validation duty at all.
///  * **0xF011, the COMMITMENT** — emitting it is what **ARMS §10.5.1 on every peer**. That
///    section reads *"If the continuity token commitment is present, check whether a local
///    continuity token exists and if it does, construct the commitment at the epoch authenticator
///    … and check if the values match"*, on four triggers (self-heal, a new-Era Welcome, a failed
///    subject/icon decrypt, creating an Era). A mismatch routes to §10.5.2, and *"if the continuity
///    token is still mismatched, the RCS client **may move the conversation to unencrypted** as per
///    §11.2"*. So the peer's downgrade path is dormant precisely because we publish no commitment,
///    and publishing one is the act that wakes it.
///
/// **And we could not honour a commitment we published.** We hold NO durable token state: the
/// Welcome-borne token (the only channel Google Messages has ever been measured to use) is read by
/// the `WELCOME-GI-EXT` instrument, which is log-only and never looks at a value; the §10.5.4 path
/// writes `mls_continuity_token_<key>` into prefs and **nothing ever reads it back**. Committing to
/// a token we cannot reproduce is a guaranteed mismatch on the next trigger — i.e. it manufactures
/// exactly the downgrade this gate exists to avoid. **Token persistence is the prerequisite, not
/// the gate.**
///
/// **THE ARMING IS A PROPERTY OF THE PEER, NOT A READING OF THE SPEC.** Every site in Google
/// Messages that names `0xF011` takes `(extension_list, type)` and returns a **bool**: two branch
/// on it directly, one is capability-list registration, and — the one that matters — one sits in
/// the era-advance path. A builder would have to pass a VALUE; none of them does. The label
/// `"Continuity Token GroupInfo Commitment"` is present in shipping builds, so with no builder its
/// only remaining use is **recomputing a commitment to verify someone else's**.
///
/// So Google Messages ships §7.11.12.2's RECEIVER half and not its SENDER half, and the receiver
/// half is live in the era-advance path — §8.3.1.2's exact trigger, the one that ends in the §11.2
/// downgrade. Emitting a commitment would hand a compiled, identified verifier a value we cannot
/// reproduce across a restart.
///
/// **Which turns "commitment second" into "commitment NOT AT ALL, on current evidence."** There is
/// nothing to wait for: no flag gates it (the only continuity flags that have ever shipped are
/// `bugle.enable_zinnia_populate_continuity_token` and `..._telemetry_for_continuity_token`, both
/// since removed from shipping builds, and NEITHER is a commitment flag), so this is not a dormant
/// feature pending a Phenotype value — the construction code is absent. If continuity is ever
/// staged the order is: persist the token, then emit `0xF010` (Welcome-only, arms nothing), and
/// leave `0xF011` off until some implementation is observed BUILDING one.
///
/// # TWO CLAIMS THAT USED TO STAND HERE AND ARE NOW WRONG
///
///  * *"§7.9.2 requires the commitment in EVERY GroupInfo."* §7.9.2 has THREE arms and names the
///    commitment in only the first: *"When creating a new RCS Conversation, or a new Era … The
///    GroupInfo shall include a commitment to the continuity token"*. The add-a-Client arm and the
///    any-other-Commit arm list a GroupInfo with no commitment requirement. §7.11.12.2 IS the broad
///    claim (*"all GroupInfo and Welcome messages"*), so the spec says both; the consequence for us
///    is unchanged, because "every group we create or advance" is §7.9.2's first arm exactly.
///  * *"Tachyon has never been observed carrying either code point."* False for 0xF010 since
///    2026-09-08: a Welcome Google Messages BUILT, delivered over Tachyon, carried `0xF010=33B`.
///    It remains true for 0xF011 — see below, and that asymmetry is a measurement,
///    not an absence of looking.
///
/// # GOOGLE MESSAGES MINTS BUT DOES NOT COMMIT — and it is why "match the peer" is not this flag
///
/// The one peer-built GroupInfo we have ever read in the clear is that Welcome's, and its own
/// extension list was `0xF010=33B` and nothing else. 0xF011 is absent from every GroupInfo we have
/// measured anywhere: four server GroupInfos, the server GroupInfo of a group we created holding
/// two Google Messages members (2026-09-11), and the 1:1 with a Google Messages
/// peer at era 8 — all `0xF001` only, `GI-EXT-READ 0xF011 -> absent` across BOTH extension lists.
/// So flipping this predicate would make us emit a code point NO implementation we can talk to
/// emits, into the structure that arms their downgrade check. That is a strictly worse position
/// than silence, and it is not what "match the peer" means.
///
/// # WHERE THE PAIR LIVES, settled twice over
///
/// Our instrumented reader answered `GI-EXT-READ 0xF011 -> GroupInfo`, and RFC 9420 says the same
/// thing structurally: §7.11.12.2's HEADING calls it a *"Group Context extension"* while its BODY
/// calls it *"A GroupInfo extension"* — and the heading cannot be right. `epoch_authenticator` is
/// `DeriveSecret(epoch_secret, "authentication")` and `epoch_secret` is expanded over
/// `GroupContext_[n]`, whose `extensions<V>` field would then have to contain a hash of the
/// authenticator derived from it. A GroupContext extension committing to its own epoch's
/// authenticator is unsatisfiable. (§7.9.2 pins the epoch — *"for the epoch in the Commit"* — so the
/// previous-epoch escape is closed.) Our two records disagreed because the section does.
pub fn emit_continuity() -> bool { is_v4_0_or_later() }

/// The `group_metadata_keys_requested` flag extension — **RCC.16 v4.0 §7.11.10.1**. Presence
/// IS the flag; the payload is the 29-byte literal below. We never PRODUCE it (see [`ext_encode`],
/// which refuses); it is here so the framing table is complete and a reader is decodable.
///
/// This was `METADATA_KEYS_REQUESTED_EXT` for as long as the number was our guess. It is
/// now one name for one number, because a constant called CANDIDATE sitting beside a constant called
/// V4 with the same value is the shape a wrong number hides in.
pub const METADATA_KEYS_REQUESTED_EXT: u16 = 0xF007;

/// The metadata-keys code point. **ARMED to 0xF007 by default** since v4.0 published it; `0` clears
/// it back to unset.
///
/// # Why this is not a constant
///
/// It used to be `= 0xF007`, and that was OUR GUESS. The limit was structural rather than effort:
/// extension type numbers are integer CONSTANTS in code, never string literals, so no amount of
/// string extraction produces one — the best anyone could do was narrow it to 0xF007..=0xF00F and
/// decline to name a value. A guess here fails SILENTLY in both directions: too low and we never
/// see the gate, too high and we read some other extension AS the gate.
///
/// The guess was reaching two behaviours. It was ADVERTISED — claiming support for whatever 0xF007
/// really is, which is the opposite of matching the peer — and it was in the BARE-FRAMING set, so a
/// varint-framed extension arriving at 0xF007 would have been mis-decoded. Neither is
/// acceptable for a number we invented, and neither is fixed by a comment.
///
/// What IS known and is encoded below without the number: the NAME
/// (`group_metadata_keys_requested`, verbatim), the semantics (presence IS the flag), the 29-byte
/// bare-ASCII payload, and that the bare-framed set is a closed list of three.
/// So the moment a capture supplies the number, setting it here makes the framing rule and the
/// advertisement both correct at once — which is the whole point of routing them through one value.
///
/// Mirrors the Java side's `debug.rcs.mls_metadata_keys_ext`, which is also unset by default.
/// **RESOLVED 2026-08-06 by GSMA RCC.16 v4.0 §7.11.10.1** — the value is `0xF007`, and it is now a
/// SPEC FACT rather than our guess:
///
/// ```text
///   7.11.10.1 group_metadata_keys_requested GroupContext Extension
///     • Extension Value: 0xF007
///     • Extension Name: group_metadata_keys_requested
///     • extension_data: opaque<V> struct containing the contents of the UTF-8 string
///                       group_metadata_keys_requested
///     • Applicable Messages: GroupInfo and Welcome Message
/// ```
///
/// Why it was unknowable before, recorded so the method is reusable: this extension is NOT in
/// RCC.16 v3.0, which is the spec we held. Every other code point we have came from that document.
/// A binary carries the NAME as a string and the NUMBER as an integer literal with nothing linking
/// them, so the range could only be narrowed to 0xF007..=0xF00F. The answer was in a document that
/// had not been published yet — not in any analysis we could have run harder.
///
/// # The framing disagreement, and why arming the NUMBER no longer waits on it
///
/// v4.0 says `opaque<V>` — varint-framed. What Google Messages does says the bare-framed set is a
/// closed list of three that includes this one. Those disagree and we have no capture of this
/// extension, so neither is evidence. What unblocked the arming is that **the two are
/// distinguishable by LENGTH**: the payload is a fixed 29-byte string, so bare is 29 bytes and
/// framed is 30 (`0x1D` ‖ 29). [`ext_decode`] therefore accepts BOTH and needs no answer, and
/// [`ext_encode`] REFUSES rather than picking one — which costs nothing, because we never produce
/// this extension. Decode tolerantly, encode strictly; where strictness has no answer, refuse.
///
/// The number stays settable (`debug.rcs.mls_metadata_keys_ext` on the Java side) so a capture that
/// contradicts v4.0 can be honoured without a rebuild.
///
/// # Falsifier, and one arm of it has already been run
///
/// The claim "the gate's type is 0xF007" is overturned by a peer GroupInfo or Welcome carrying
/// the metadata-keys gate at any other type. That is reachable and it has been exercised once in
/// the confirming direction: device-verified, with 0xF007 observed on the wire and the gate
/// evaluating keysRequested=true and declining correctly. So this is no longer spec-only.
///
/// The FRAMING claim above is separate, and **two sentences of it are now out of date**
/// (2026-09-11). "We have no capture of the extension at all" is FALSE — the same device run
/// that confirmed the number captured the payload too, and it matched NEITHER candidate:
///
/// ```text
///   SERVER GroupInfo (raw extension_data) 347B: 0xF006=32B 0xF001=4B 0xF007=0B
/// ```
///
/// Zero bytes, from an Apple peer, with our config at `rcc16=30`. Not the 29-byte bare string and
/// not the 30-byte framed one. That is fully consistent with the recorded semantics (presence IS
/// the flag: a sender has nothing to put in it) and it is weak evidence for BARE on that sender,
/// since empty is 0 bytes bare and 1 byte (`0x00`) framed — weak because a zero-length `opaque<V>`
/// is an odd thing to emit either way. And `ext_encode` no longer refuses: it resolves by version
/// (`metadata_keys_is_bare()`), because §10.5.2 needs a producer. Do not read the number's
/// confirmation as settling the framing — but do not read this paragraph as "no capture exists".
static METADATA_KEYS_EXT: core::sync::atomic::AtomicU16 =
    core::sync::atomic::AtomicU16::new(METADATA_KEYS_REQUESTED_EXT);

/// The metadata-keys code point, or `None` if it has been explicitly cleared.
pub fn metadata_keys_ext() -> Option<u16> {
    match METADATA_KEYS_EXT.load(core::sync::atomic::Ordering::Relaxed) {
        0 => None,
        v => Some(v),
    }
}

/// Serialises the tests that flip the process-global code point, across BOTH test modules.
///
/// The same hazard as `VERSION_LOCK`: cargo runs tests as parallel threads in one process, and once
/// the default became ARMED, a test that clears the value can make an unrelated test in `ffi` observe
/// an unarmed engine. Crate-visible (rather than one lock per module) because there is one global.
#[cfg(test)]
pub(crate) static METADATA_KEYS_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Serialises the tests that flip the announced revision, across BOTH test modules — same hazard
/// and same reasoning as `METADATA_KEYS_LOCK`.
#[cfg(test)]
pub(crate) static VERSION_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Supply the code point once a capture has established it. `0` clears it back to unverified.
///
/// 0 is not a valid MLS extension type, so "unset" cannot collide with a real answer.
pub fn set_metadata_keys_ext(ty: u16) {
    METADATA_KEYS_EXT.store(ty, core::sync::atomic::Ordering::Relaxed);
}
/// The literal payload of 0xF007 — bare ASCII, 29 bytes, no length prefix.
pub const METADATA_KEYS_REQUESTED_DATA: &[u8] = b"group_metadata_keys_requested";
/// **The continuity token itself** — RCC.16 v4.0 §7.11.12.1. A **GroupInfo** extension, and
/// *"Applicable message(s): GroupInfo (Welcome only)"*: it is carried ONLY in the encrypted GroupInfo
/// inside a Welcome. The value is 256 CSPRNG bits, continuous across Epochs AND Eras (§8.3.1.1).
///
/// # This was our "gate of UNKNOWN purpose", and v4.0 explains it
///
/// Google Messages does a find-by-u16 *presence lookup* on this type, with no builder, no enum arm
/// and no payload anywhere — which read as an unexplained capability gate for as long as we only
/// had v3.0, where **this code point does not exist at all**. A presence check is exactly the right
/// operation for a Welcome-only secret: the question it is asking is "did this Welcome carry a
/// token", not "what is it".
///
/// # ~~Do not mint one.~~ **OVERTURNED ON DEVICE, 2026-09-08 — Google Messages mints one.**
///
/// The prohibition below stood on "no server GroupInfo of ours has ever carried or requested one",
/// with a stated falsifier of a `0xF011` COMMITMENT in a server GroupInfo — deliberately not
/// `0xF010`, on the reasoning that a Welcome-only extension's absence from a *server* GroupInfo
/// proves nothing. That reasoning was correct; it simply aimed at the weaker observable, and the
/// stronger one was available all along inside a Welcome we receive.
///
/// **The measurement.** Google Messages added one of our devices to a group it owns; the
/// join logged, from the decrypted GroupInfo's OWN extension list:
///
/// ```text
/// WELCOME-GI-EXT join_treeless_welcome g:5f14cb18… committer_leaf=0
///   — DECRYPTED GroupInfo's OWN extensions (not the GroupContext's): 0xF010=33B
/// ```
///
/// 33 bytes is the right shape and not merely the right code point: the token is 256 CSPRNG bits,
/// and 32 bytes as `opaque<V>` is `0x20 ‖ value` = 33 — the same framing already derived for the
/// commitment on the peer side. So a peer in our groups **is** doing continuity, which is
/// exactly the condition under which §8.3.1.3 makes minting correct.
///
/// **What §8.3.1.3 still says, and it is the reason this is not a one-line flip:** a client with no
/// local token REQUESTs one (§10.5.2) when the RCS participants are a subset of the fetched MLS
/// membership, and mints only otherwise. So "the peer mints" settles *whether continuity is live in
/// these groups*; it does not by itself say which of request-vs-mint we owe in a given join.
///
/// **And note the version problem, which is the sharp end.** §7.11.12 is a **v4.0** section — it
/// does not exist in v3.0 — yet Google Messages minted a token in a group negotiated with us under
/// v3.0 today. [`emit_continuity`] is gated on [`is_v4_0_or_later`], so we emit nothing and read
/// tolerantly. Whether that is correct conformance or a live divergence is now a real question
/// rather than a hypothetical one, and it is tracked with the v4.0 batch rather than decided here:
/// changing it alters bytes in every group we create or advance, and the failure mode of getting
/// continuity wrong is a DOWNGRADED conversation (§8.3.1.2).
///
/// **Do not "fix" this by flipping [`emit_continuity`].** Minting under v3.0 is a wire change with
/// a downgrade failure mode; it needs the request-vs-mint rule settled first.
///
/// (A prohibition without a stated overturn condition does not merely record a belief — it
/// discourages the check that would revise it. This one HAD a falsifier, which is why it could be
/// overturned at all; the lesson it adds is that the falsifier should name the STRONGEST available
/// observation, not the most convenient one. See the 2026-08-04 control-bundle marker for the
/// version of this that had no falsifier at all.)
pub const CONTINUITY_TOKEN_EXT: u16 = 0xF010;
/// **The commitment to the continuity token** — v4.0 §7.11.12.2. GroupInfo, and unlike the token
/// itself *"to be included in all GroupInfo and Welcome messages"*. `extension_data` is a serialised
/// `token_commitment`:
///
/// ```text
///   struct TokenCommitment { opaque continuity_token<V>; opaque epoch_authenticator<V>; }
///   token_commitment = RefHash("Continuity Token GroupInfo Commitment", TokenCommitment)
/// ```
///
/// # The measurement and the spec agree; only our NAME was wrong
///
/// We measured 33 bytes here (`0x20` ‖ 32 B, varint-framed, verified from bytes 2026-07-29) and
/// called it "the continuity token". A `RefHash` is a 32-byte hash, and a 32-byte hash written as
/// `opaque<V>` is `0x20 ‖ hash` = **33 bytes**. So the bytes we measured were always the
/// COMMITMENT — the length says so on its own.
///
/// This is worth stating plainly because it is NOT a capture-versus-document conflict, and it is
/// easy to file as one. Our earlier note said "neither code point appears anywhere in the
/// specification, so both are vendor and only the bytes on the wire can decide" — true of
/// v3.0, and wrong about the world: v4.0 assigns both. That reading was right about the bytes and
/// wrong about which name went with them, which is the failure mode of naming a field by the first
/// plausible meaning of its size.
pub const CONTINUITY_TOKEN_COMMITMENT_EXT: u16 = 0xF011;
/// Google Messages' test-only certificate-validity override extension (varint-framed).
pub const TESTING_OVERRIDE_EXPIRY_EXT: u16 = 0xE000;

// ---------------------------------------------------------------------------------------------
// GroupContext `extension_data` framing — THE DOUBLE-LENGTH-PREFIX RULE
// ---------------------------------------------------------------------------------------------
//
// RFC 9420 serialises `Extension.extension_data` as `opaque<V>`, so every extension already gets ONE
// varint length from the codec. Seven of Google Messages' ten also put a SECOND varint INSIDE that
// payload, because their `MlsExtension` impl is a struct with one `opaque<V>` field and
// `to_extension()` = `Extension::new(TYPE, self.mls_encode_to_vec())`:
//
//     Extension:  u16 extension_type
//                 varint L_outer            ( = |varint(L_inner)| + L_inner )
//                 bytes  varint(L_inner) || value
//
// **Three write a BARE payload with no inner prefix** — 0xF001 (BE32 era), 0xF002 (`end_mls`) and
// 0xF007 (`group_metadata_keys_requested`). There is NO uniform rule; it is hard-coded per type,
// which is exactly why this lives in one function instead of at each call site.
//
// Getting this wrong is not cosmetic. A 32-byte commitment written bare is 32 bytes where a peer
// writes 33 (`0x20 ‖ hash`); different `extension_data` ⇒ different GroupContext ⇒ different
// group-context hash ⇒ an invalid confirmation tag and tree hash, i.e. the peer rejects the group
// outright. This is the highest-risk byte-level detail in the protocol, and we had it wrong for
// every metadata commit we ever shipped.

/// True iff `ty`'s `extension_data` is written BARE — no inner varint length prefix.
///
/// Everything not named here is varint-framed. Defaulting the *unknown* type to framed is the safe
/// direction: the bare set is a closed, enumerated list of three, whereas the framed
/// set is "every extension whose payload is an opaque byte string", which is where any future code
/// point will land.
pub fn ext_payload_is_bare(ty: u16) -> bool {
    // 0xF007 is DELIBERATELY NOT HERE even though what Google Messages emits puts it in the bare set.
    // v4.0 §7.11.10.1 says `opaque<V>`, i.e. FRAMED, and the two sources disagree with no capture to
    // settle it. Rather than pick, the two sites that care handle it directly: `ext_decode` accepts
    // both framings (they differ in length — 29 bare, 30 framed) and `ext_encode` refuses. So the
    // disagreement costs us nothing and is not silently resolved by a `matches!` arm.
    matches!(ty, ERA_EXT | END_MLS_EXT)
}

/// The one extension whose framing differs BY SPEC REVISION — see [`ext_payload_is_bare`].
///
/// This used to be called `framing_is_undecided`, and calling it undecided was the wrong reading of
/// the evidence. v4.0 §7.11.10.1 says `opaque<V>` (framed); what Google Messages emits is bare.
/// **That client is a v3.0-era client**, so the two sources are not in conflict at all — they
/// describe two different revisions, exactly like the `end_mls` payload and the commitment labels.
/// The version enabler is what lets both be true.
///
/// Encode still refuses when we have no announced answer for the type; decode still accepts both,
/// because the fixed 29-byte payload makes them distinguishable by length and tolerance costs
/// nothing.
fn framing_is_version_dependent(ty: u16) -> bool { metadata_keys_ext() == Some(ty) }

/// Is 0xF007's payload written bare under the ANNOUNCED revision?
///
/// v3.0 = bare (measured off a v3.0-era client). v4.0 = framed (§7.11.10.1's `opaque<V>`).
///
/// # THE AXIS IS THE PEER, NOT THE TRANSPORT — this gate is right by luck, and says so
///
/// RCC.16 versioning does not appear to gate extension payload encoding at all — there is no
/// in-band discriminator anywhere in the 0xF00x family, which is precisely why the three-way
/// framing divergence is possible. Three encoders have been seen on this one code point: 30 bytes
/// (the spec), 29 (Google Messages), 0 (a third-party client).
///
/// So keying the ENCODING off the announced spec revision is not a principled rule. It happens to
/// produce the right bytes for both deployments we have — Tachyon announces v3.0 and its peers are
/// Google Messages, which emits bare; the lab announces v4.0 and its peers are us, where
/// spec-conformant is the sensible choice — but it would be wrong for any deployment where those
/// two come apart.
///
/// Left as-is deliberately rather than restructured: a per-peer framing model needs per-peer state
/// we do not have and evidence we cannot populate it with, and it would trade a rule that is
/// currently correct everywhere we ship for a mechanism with nothing to drive it. **If this ever
/// needs to change, drive it from evidence about the PEER, not about the transport.**
///
/// None of this reaches the decode side, which accepts all three lengths — see
/// `all_three_observed_0xf007_framings_decode_without_error`.
pub fn metadata_keys_is_bare() -> bool { !is_v4_0_or_later() }

/// Frame a raw extension VALUE into the `extension_data` bytes for `ty`.
///
/// Pass the decoded value (a 32-byte commitment, a symmetric key, the BE32 era); this adds the inner
/// varint when the type takes one. The caller still hands the result to `Extension::new`, which adds
/// the outer `opaque<V>`.
pub fn ext_encode(ty: u16, value: &[u8]) -> Result<Vec<u8>, String> {
    if framing_is_version_dependent(ty) {
        // RESOLVED BY THE VERSION, not by picking a side. §10.5.2 needs a producer for this
        // extension (a client asks for metadata keys by putting it in the GroupContext), so
        // refusing outright would have blocked the feature — but the disagreement that made
        // refusing right was never a disagreement: v4.0 describes v4.0 and the measurement was v3.0.
        if metadata_keys_is_bare() { return Ok(value.to_vec()); }
        // Fall through to the ordinary framed path below rather than re-implementing the varint —
        // getting a SECOND copy of the length encoding wrong is the exact failure described above.
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

// ---------------------------------------------------------------------------------------------
// ERA IMMUTABILITY — a GroupContextExtensions proposal may neither CHANGE nor REMOVE 0xF001
// ---------------------------------------------------------------------------------------------
//
// Google Messages carries two dedicated, named error variants for this and nothing else:
// `GroupContextExtensionProposalChangesEraError` and `GroupContextExtensionProposalRemovesEraError`.
// **Era is the only extension with a NAMED proposal-level guard** — everything else, as far as we can
// see, is protected merely by commitment comparison and the inherit-then-edit model.
//
// NOTE THE SCOPE OF THAT PROOF: what was enumerated is the set of DEDICATED ERROR NAMES.
// A proposal-level guard that refuses with a shared or generic error carries no name of its own and
// is invisible to a name enumeration, so "the only extension with a guard" is broader than what was
// established. FALSIFIER: any refusal of a GroupContextExtensions proposal that alters a NON-era
// extension — from Google Messages, or from Tachyon — which we would see as a rejection with no
// matching named variant.
//
// The conclusion below does NOT rest on the "only" claim, which is why the narrowing changes nothing
// we do: era immutability needs just the two named era guards to exist, and the create-not-commit
// consequence is separately device-proven (Tachyon refused our commit_era_advance).
//
// The consequence is structural, and it is the thing that explains our own refusals: RFC 9420 makes
// GroupContextExtensions the only in-group way to alter a group-context extension, so if that
// proposal cannot touch the era, **the era is immutable for the lifetime of an MLS group instance and
// the only way to move it is to build a NEW group.** That is exactly what Google Messages'
// `Creating new group for new era: {:?} for new moment: {:?}` says it does.
//
// So an era advance is a create (`create_group_carry` at era+1, reusing the RCS group id), never a
// commit. Our `commit_era_advance` did it as a commit and Tachyon refused it on 2026-07-29.
// Enforcing it here rather than at the call sites means an inbound peer commit that tries the
// same thing is refused too — `filter_proposals` runs on BOTH `CommitDirection::Send` and `::Receive`.
//
// The error names are observed; "therefore an era advance cannot be a commit" is a strong
// inference resting on RFC 9420 §12.1.3.

/// Read the Era (0xF001) out of an extension list, if present. Bare BE32, no inner varint.
pub fn era_of(exts: &CoreExtensionList) -> Option<u32> {
    exts.get(ExtensionType::from(ERA_EXT))
        .and_then(|e| e.extension_data.get(0..4)
            .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]])))
}

/// An era advance must move STRICTLY FORWARD, compared unsigned.
///
/// Era is a u32 that increases by exactly 1 and never wraps — Google Messages **panics** at
/// `u32::MAX` rather than wrapping, and refuses a backwards move outright:
/// `Attempted to advance the group from {:?} to {:?}; backwards in eras.`
///
/// This is the check the ENGINE can actually make. The `+1` itself happens on the host side (the new
/// era comes from the server's, so the engine never increments), which is where the ceiling guard
/// lives — but a host that wrapped, truncated a u32, or passed a stale number arrives here, and a new
/// group at an era the peers have already passed is unrecoverable in a way a failed advance is not.
pub fn check_era_advances(carried: Option<u32>, new_era: u32) -> Result<(), String> {
    match carried {
        Some(old) if new_era <= old => Err(format!(
            "attempted to advance the group from era {old} to {new_era}; backwards in eras \
             (or wrapped) — an advance must move strictly forward")),
        _ => Ok(()),
    }
}

/// The violation an era-touching GroupContextExtensions proposal produces. Named after the two
/// error variants Google Messages uses, so a log line here is greppable against theirs.
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

/// `DefaultMlsRules` plus the RCC.16 era guard.
///
/// Everything except the guard delegates: commit options and encryption options are the inner rules'
/// verbatim, so the PADME padding mode and the external-commit/ratchet-tree settings configured at
/// the builder keep working unchanged.
#[derive(Debug, Clone)]
pub struct Rcc16MlsRules {
    inner: DefaultMlsRules,
}

impl Rcc16MlsRules {
    pub fn new(inner: DefaultMlsRules) -> Self { Self { inner } }

    /// The guard itself, split out so it is testable without building a group.
    ///
    /// `current` is the group's existing 0xF001 `extension_data`; `proposed` is the same slot in the
    /// proposal's replacement list. A group that has no era yet (our `era == 0` create guard) is
    /// unconstrained — the rule protects an era that EXISTS from being moved, and adding the first one
    /// is not a move.
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
        // The inner rules are Infallible, so there is no error to translate.
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

/// The inverse: recover the VALUE from an extension's `extension_data`.
///
/// Rejects a non-minimal varint (`VarIntMinimumLengthEncoding`) and the reserved `11` prefix, both
/// enforced by `VarInt::mls_decode` itself. That matters more than it looks: a peer that accepts a
/// non-minimal length and we that reject it disagree about the GroupContext bytes precisely at the
/// 0x40 / 0x4000 boundary — which is the size range separating a 32-byte commitment from a longer key
/// blob, so it is the range we actually operate in.
///
/// Bare types are returned verbatim. That is deliberately permissive for `end_mls`: Google Messages
/// has TWO 0xF002 constructors and the non-literal one takes a caller-supplied payload, so an
/// inbound tag may be longer than the 7-byte literal. We must not reject that, and we must not
/// assume it either.
pub fn ext_decode(ty: u16, extension_data: &[u8]) -> Result<Vec<u8>, String> {
    if framing_is_version_dependent(ty) {
        // TOLERANT, because it can be: the payload is a fixed 29-byte string, so the framed form is
        // exactly one byte longer and starts with its own length. Strip an inner varint only when it
        // actually describes the rest; otherwise take the bytes verbatim. Either way the caller gets
        // the 29-byte value, so nothing downstream has to know which side of the disagreement was
        // right — and this keeps working if a capture later settles it the other way.
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
        // A COMMITMENT THAT IS A BARE HASH IS NOT MALFORMED — IT IS ANOTHER VENDOR'S FRAMING.
        // Established 2026-08-07 and re-confirmed 08-22 off an Apple-created group:
        // a third-party client writes 0xF006 as 32 raw bytes where our own groups write 33
        // (`0x20 ‖ hash`). Read as varint-framed, those 32 bytes yield a nonsense inner length
        // (2241 in the observed case) — the fingerprint of a bare hash, not of corruption.
        //
        // Tolerance is deliberately narrow: ONLY a payload whose whole length is the one the spec
        // gives that type's VALUE. Blanket "fall back to verbatim" would also swallow genuinely
        // malformed data, which the strictness tests below exist to keep catching.
        Err(_) if bare_payload_is_plausible(ty, extension_data.len()) => {
            Ok(extension_data.to_vec())
        }
        Err(e2) => Err(e2),
    }
}

/// Could `n` bytes at `ty` be the VALUE written bare, i.e. by an encoder that omitted the inner
/// `opaque<V>` — as opposed to corruption?
///
/// Only ever consulted after the framed read has already failed, so it can never change how a
/// well-formed payload decodes. The bar is that the length be one the spec fixes for that type:
///
///  * 0xF004 / 0xF006 — a commitment is a digest, so a digest length and nothing else. This arm is
///    the measured one (see the call site).
///  * 0xF010 — §8.3.1.1 makes the continuity token "CSPRNG, 256 bits", so 32 and nothing else. This
///    arm is NOT measured: every 0xF010 we have read came framed (`0x20 ‖ 32` = 33 B).
///    It is here because the cost of being wrong is asymmetric — a token we refuse to decode is a
///    token DROPPED, which is precisely the failure we are trying to remove, while a wrong 32
///    bytes accepted here reaches a store nothing consumes and is logged as the bare branch at the
///    call site. Delete this arm the day a bare 0xF010 is ruled out, not before.
///
/// 0xF011 is deliberately NOT here: its payload is a RefHash and every one we have read was framed,
/// so there is nothing to be tolerant of yet and a silent 32-byte acceptance would blur the one
/// measurement the commitment question turns on.
fn bare_payload_is_plausible(ty: u16, n: usize) -> bool {
    if ext_payload_is_commitment(ty) { return is_digest_len(n); }
    ty == CONTINUITY_TOKEN_EXT && n == CONTINUITY_TOKEN_BYTES
}

/// §8.3.1.1 — *"CSPRNG, 256 bits"*. The one length a continuity token may have.
pub const CONTINUITY_TOKEN_BYTES: usize = 32;

/// 0xF004 / 0xF006 — the commitment extensions, whose payload is a hash.
fn ext_payload_is_commitment(ty: u16) -> bool {
    ty == ICON_COMMITMENT_EXT || ty == SUBJECT_COMMITMENT_EXT
}

/// SHA-256 / SHA-384 / SHA-512. A commitment is a digest, so these are the only bare lengths
/// that can be one; anything else of unexpected length stays an error.
fn is_digest_len(n: usize) -> bool {
    n == 32 || n == 48 || n == 64
}

fn era_extension_list(era: u32) -> CoreExtensionList {
    CoreExtensionList::from(vec![Extension::new(ExtensionType::from(ERA_EXT), era.to_be_bytes().to_vec())])
}

/// M3 conformance: create a group carrying Era 0xF001 and verify it lands in the GroupContext as
/// a real RFC 9420 extension (type 0xF001, uint32) — matching the observed wire byte-map.
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


/// M3 self-conformance: generate a KeyPackage and verify (via mls-rs's own decoder) that it is
/// structurally RCC.16-conformant against the observed wire byte-map — x509 credential (leaf+CA
/// chain), suite 0x0002, raw-65 SEC1 signature key.
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
    Ok(format!("KeyPackage conformant: suite=0x0002 credential=X509({n_certs} certs, leaf+chain) signature_key={sk_len}B SEC1"))
}

#[cfg(test)]
#[no_mangle]
pub extern "C" fn rcs_mls_smoke_x509() -> i32 { if x509_roundtrip().is_ok() { 0 } else { -1 } }
#[cfg(test)]
#[no_mangle]
pub extern "C" fn rcs_mls_smoke_rcc16() -> i32 { if rcc16_conformance().is_ok() { 0 } else { -1 } }
#[cfg(test)]
#[no_mangle]
pub extern "C" fn rcs_mls_smoke_kpconform() -> i32 { if keypackage_conformance().is_ok() { 0 } else { -1 } }


// ============================================================================
// RCC.16 AuthenticatedData — ENGINE-SIDE construction and parsing
// ============================================================================
//
// The spec puts AAD construction in the ENGINE: EncryptMessageRequest has no
// authenticated-data field, and the host supplies only a message_id via the request
// context. Ours historically took the fully-built AAD as a caller parameter on ten FFI
// entry points, which is the seam being removed. These functions are that
// removal's engine half.
//
// LAYOUT — byte-identical to Java's MlsAppMessage.buildAuthenticatedData, which is the
// authority because it is byte-matched against real Google Messages traffic at
// four different eras (4/6/7/8) and locked by MlsAuthenticatedDataWireVectorTest:
//
//     [00 01] [mls_varint(len)] [message_id ASCII] [uint32 era BE] [trailing]
//
// THE uint32 ERA IS NOT IN RCC.16's TEXT. The spec says
// {version, message_id, optional resent-message}. Google Messages emits the era anyway, and its
// value tracks the real era rather than being a constant. DO NOT "correct" this to match
// the spec — that breaks interop with every Google Messages peer and surfaces as
// KEY_GENERATION_MISMATCH, which looks nothing like the cause.
//
// The trailing byte is the §10.3 RESENT-MESSAGE COMPONENT. A single 0x00 is that
// component ABSENT, which is every sample we have ever captured. The PRESENT form is
// still uncaptured, which is why `build_authenticated_data` takes an explicit
// trailing slice rather than choosing one: the caller supplies exact bytes so the tag /
// prefix-width question can be settled by experiment against the peer's rejection
// asymmetry rather than by argument.

/// MLS QUIC-style varint, matching `MlsAppMessage.mlsVarint`.
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
        // Java throws here; the engine must not panic across an FFI boundary, so clamp to
        // the 4-byte form. A message id this long is not reachable from any real caller.
        vec![0xbf, 0xff, 0xff, 0xff]
    }
}

/// Read an MLS varint at `o`. Returns (value, next_offset).
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

/// Build the RCC.16 AuthenticatedData for an application message.
///
/// `trailing` empty means the resent-message component is ABSENT and a single `0x00` is
/// emitted — the ordinary case, and the only one ever observed from Google Messages.
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

/// A parsed AuthenticatedData.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedAad {
    pub version: u16,
    pub message_id: Vec<u8>,
    pub era: u32,
    /// The §10.3 resent-message component. `[0x00]` means ABSENT.
    pub trailing: Vec<u8>,
}

impl ParsedAad {
    /// Whether the resent-message component is absent (a lone `0x00`).
    pub fn resent_absent(&self) -> bool {
        self.trailing == [0x00]
    }
}

/// Parse an inbound AuthenticatedData.
///
/// Deliberately strict about structure but tolerant about the trailing component: we do
/// not yet know the PRESENT form's encoding, so anything after the era is returned
/// verbatim rather than rejected. Rejecting it would make us drop real resends the
/// moment they start arriving, which is the opposite of what §11.3a wants.
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

    // Three real Google Messages AADs, read off the wire at three different eras. These
    // are the SAME vectors Java's MlsAuthenticatedDataWireVectorTest asserts, which is
    // the point: if both sides pass, the Rust engine and the Java builder are
    // byte-identical, and moving the seam cannot silently change what goes on the wire.
    const E6: (&str, u32, &str) =
        ("MxaDOv6I6QTwGK2Xj6V-Ubhw", 6, "0001184d7861444f76364936515477474b32586a36562d556268770000000600");
    const E7: (&str, u32, &str) =
        ("MxHjYubZBiQYuA9DqODpyQiw", 7, "0001184d78486a5975625a4269515975413944714f4470795169770000000700");
    const E8: (&str, u32, &str) =
        ("MxCACYGxqfQa-CnQZ=5XctuQ", 8, "0001184d78434143594778716651612d436e515a3d3558637475510000000800");

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

    // The uint32 must TRACK the era rather than be a constant — the assertion a single
    // captured sample could never support, and the reason three more artifacts were
    // recovered. A hard-coded four-byte constant 4 would satisfy the era-4 capture perfectly.
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

    // Four bytes BIG-ENDIAN. A 1-byte or little-endian encoding satisfies every captured
    // vector (all observed eras are < 256) and breaks first at era 256 — months later, as
    // an unexplained KEY_GENERATION_MISMATCH on one long-lived thread.
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

    // A PRESENT resent component is passed through verbatim in both directions. We do not
    // know its encoding yet, so the engine must neither invent nor reject one.
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

    // CROSS-VENDOR: an APPLE RCS AAD, byte-for-byte, from a real iPhone external commit
    // (kind=47 PublicMessage Commit, sender_type=4).
    //
    // This is a SECOND INDEPENDENT IMPLEMENTATION of the same format, and it is worth more than
    // another Google sample: the two vendors differ only in message-id length (Google's Mx-form ids
    // are 24 chars → 32-byte AAD; Apple's UUIDs are 36 → 44-byte AAD), and agree on every field and
    // its order.
    //
    // AND IT SETTLES SOMETHING OUR OWN VECTORS CANNOT. On our side the era and the MLS epoch tend to
    // move together, so a Google-only corpus cannot prove the uint32 is the ERA rather than the
    // EPOCH. In this Apple capture three commits carry MLS epochs 1, 2 and 3 while the AAD uint32
    // stays 1 throughout — the RCC.16 era. The field is the era. Do not "fix" it to the epoch.
    #[test]
    fn parses_an_apple_rcs_aad_byte_for_byte() {
        const APPLE: &str = "00012435464639373038422d303744382d343844342d4142323\
72d3831363735333143333541410000000100";
        let aad = hex(&APPLE.replace('\\', "").replace('\n', ""));
        assert_eq!(aad.len(), 44, "Apple ids are 36 chars, so its AAD is 44B not 32B");
        let p = parse_authenticated_data(&aad).expect("Apple's AAD must parse with our reader");
        assert_eq!(p.version, 1);
        assert_eq!(p.message_id, b"5FF9708B-07D8-48D4-AB27-8167531C35AA");
        assert_eq!(p.era, 1, "the uint32 is the RCC.16 ERA — this capture has epochs 1/2/3 at era 1");
        assert!(p.resent_absent());

        // ...and our BUILDER reproduces Apple's bytes exactly, which is the real claim: one encoder,
        // two vendors.
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

    // Malformed input must ERROR, never panic: this runs behind an FFI boundary where a
    // panic is undefined behaviour for the caller.
    #[test]
    fn malformed_aad_errors_and_never_panics() {
        assert!(parse_authenticated_data(&[]).is_err());
        assert!(parse_authenticated_data(&[0x00]).is_err());
        assert!(parse_authenticated_data(&hex("000118")).is_err()); // len claims 24, none follow
        assert!(parse_authenticated_data(&hex("00011f4142")).is_err()); // truncated before era
        assert!(parse_authenticated_data(&hex("0001c0")).is_err()); // reserved varint prefix
        // an empty message id with a full era is structurally valid
        assert!(parse_authenticated_data(&hex("00010000000005 00".replace(' ', "").as_str())).is_ok());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn x509_critical_ext_tolerated() {
        x509_roundtrip().expect("x509 round-trip with critical vendor ext");
        println!("M3: mls-rs ACCEPTED the KDS-style leaf w/ CRITICAL ext 2.23.146.2.1.4 (the BoringSSL err-34 killer)");
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

    /// THE REGRESSION. A 32-byte commitment serialises as 33 bytes, `0x20 || hash`. We shipped 32.
    #[test]
    fn commitment_gains_an_inner_varint() {
        let hash = vec![0xABu8; 32];
        let enc = ext_encode(SUBJECT_COMMITMENT_EXT, &hash).unwrap();
        assert_eq!(enc.len(), 33, "32-byte commitment must frame to 33 bytes");
        assert_eq!(enc[0], 0x20, "inner varint for len 32 is the single byte 0x20");
        assert_eq!(&enc[1..], &hash[..]);
        assert_eq!(ext_decode(SUBJECT_COMMITMENT_EXT, &enc).unwrap(), hash);
    }

    /// The three bare types must gain NOTHING — the era especially, which is device-proven on the
    /// wire as `f001 04 00000013` (a 4-byte extension_data, no inner prefix).
    #[test]
    fn the_three_bare_types_stay_bare() {
        let era = 19u32.to_be_bytes().to_vec();
        assert_eq!(ext_encode(ERA_EXT, &era).unwrap(), era, "era is a bare uint32");
        assert_eq!(ext_encode(END_MLS_EXT, END_MLS_DATA).unwrap(), END_MLS_DATA.to_vec());
        // The RULE, pinned against the candidate number: bare, no inner varint. If the real code
        // point turns out to be different, this test still pins the framing — set_metadata_keys_ext
        // is what makes it apply to the right number.
        assert_eq!(METADATA_KEYS_REQUESTED_DATA.len(), 29, "the observed encoder writes 29 bytes");
        for ty in [ERA_EXT, END_MLS_EXT] {
            assert!(ext_payload_is_bare(ty));
        }
        for ty in [ICON_KEY_EXT, ICON_COMMITMENT_EXT, SUBJECT_KEY_EXT, SUBJECT_COMMITMENT_EXT,
                   CONTINUITY_TOKEN_EXT, CONTINUITY_TOKEN_COMMITMENT_EXT,
                   TESTING_OVERRIDE_EXPIRY_EXT] {
            assert!(!ext_payload_is_bare(ty), "0x{ty:04X} is varint-framed");
        }
        // 0xF007 is in NEITHER set: its framing is the one open disagreement, and
        // `ext_payload_is_bare` deliberately does not answer for it. See the test below.
        assert!(!ext_payload_is_bare(METADATA_KEYS_REQUESTED_EXT));
    }

    /// A BARE 32-BYTE COMMITMENT IS A REAL PEER'S FRAMING, NOT CORRUPTION — and it must decode
    /// without giving up the strictness that catches actual corruption.
    ///
    /// Observed twice: a third-party client on 2026-08-06, then the Apple-created group
    /// `5B8905CD-…` on 2026-08-22, whose GroupContext we mirror locally from Apple's own commits.
    /// Both write 0xF006 as 32 raw bytes; our own groups write 33 (`0x20 ‖ hash`).
    ///
    /// **The fix belongs in the DECODER and must never be moved to the writer.** The first
    /// instinct is "prepend the 0x20 when you encode/store" — correct-sounding, and
    /// wrong here: these bytes are a PEER's GroupContext, which is consensus state agreed by every
    /// member. Re-framing them on the way into local storage would change the group context we
    /// compute against and diverge us from the group — surfacing as a confirmation-tag or key
    /// schedule failure a long way from its cause, which is what makes it nasty: the "fix" looks
    /// like cleanup.
    ///
    /// **ORIGINATE CANONICALLY, MIRROR VERBATIM** (the standing rule, 2026-08-22). The
    /// two halves coexist and the distinction is whose consensus bytes they are:
    /// - extensions WE originate → write Google Messages' form, 0xF006 = 33 bytes (`0x20 ‖ hash`);
    /// - extensions we MIRROR from a peer → store exactly the bytes they sent, however odd.
    ///
    /// So do NOT read "store verbatim" as licence to drop the prefix in `ext_encode` — that is the
    /// same bug pointing the other way.
    #[test]
    fn a_bare_hash_commitment_decodes_without_weakening_the_malformed_checks() {
        let hash = [0xABu8; 32];
        // Our own framing: 0x20 ‖ hash.
        assert_eq!(ext_decode(SUBJECT_COMMITMENT_EXT, &ext_encode(SUBJECT_COMMITMENT_EXT, &hash)
            .unwrap()).unwrap(), hash.to_vec(), "our own 33-byte framed form must still decode");
        // The peer's framing: the bare digest, whose first byte is NOT a valid length prefix.
        for ty in [SUBJECT_COMMITMENT_EXT, ICON_COMMITMENT_EXT] {
            assert_eq!(ext_decode(ty, &hash).unwrap(), hash.to_vec(),
                "a bare 32-byte digest must decode verbatim, not error");
        }
        // The exact observed bytes: a leading 0x08,0xC1 reads as inner length 2241 with 30 left.
        let mut observed = vec![0x08u8, 0xC1];
        observed.extend_from_slice(&[0x5Eu8; 30]);
        assert_eq!(observed.len(), 32);
        assert_eq!(ext_decode(SUBJECT_COMMITMENT_EXT, &observed).unwrap(), observed,
            "the 2241-vs-30 case from the Apple group is the bug this fixes");
        // ...and none of that may rescue genuinely malformed input.
        assert!(ext_decode(ICON_COMMITMENT_EXT, &[0x02, 0xAA]).is_err(), "declared 2, carries 1");
        assert!(ext_decode(ICON_COMMITMENT_EXT, &[0x01, 0xAA, 0xBB]).is_err(), "trailing byte");
        assert!(ext_decode(SUBJECT_COMMITMENT_EXT, &[0x5Eu8; 31]).is_err(),
            "31 bytes is not a digest length — tolerance must not become blanket verbatim");
        // Tolerance is scoped to the commitments; other varint-framed types stay strict.
        assert!(ext_decode(ICON_KEY_EXT, &[0xAAu8; 32]).is_err(),
            "0xF003 is not a commitment — a bare 32-byte payload there is still an error");
    }

    /// §7.11.12.1's continuity token, both framings.
    ///
    /// The FRAMED case is the measured one and the one that must never regress: we read
    /// `0xF010=33B` off a Welcome Google Messages built for us on 2026-09-08, and 33 is
    /// `0x20 ‖ 32` — §8.3.1.1's 256 CSPRNG bits written as `opaque<V>`. Decoding it to anything but
    /// the 32-byte value would store a token no peer could ever match.
    ///
    /// The BARE case is tolerance, not measurement, and the asymmetry is the argument for it: a
    /// token we refuse to decode is a token dropped, which is the whole bug. It stays as narrow as
    /// the commitments' — exactly the one length §8.3.1.1 permits.
    #[test]
    fn a_continuity_token_decodes_framed_and_bare_and_nothing_else_does() {
        let token = [0x5Au8; CONTINUITY_TOKEN_BYTES];
        // FRAMED — reproduce the measured shape byte for byte rather than trusting ext_encode.
        let mut framed = vec![0x20u8];
        framed.extend_from_slice(&token);
        assert_eq!(framed.len(), 33, "the measurement was 33 bytes");
        assert_eq!(ext_decode(CONTINUITY_TOKEN_EXT, &framed).unwrap(), token.to_vec(),
            "a peer-built Welcome's 0xF010 must decode to the 32-byte value");
        assert_eq!(ext_encode(CONTINUITY_TOKEN_EXT, &token).unwrap(), framed,
            "and our own encoder must produce exactly those bytes");
        // BARE.
        assert_eq!(ext_decode(CONTINUITY_TOKEN_EXT, &token).unwrap(), token.to_vec(),
            "a bare 32-byte token decodes verbatim rather than erroring");
        // Nothing wider. 31 and 33-bare are not token lengths, and a malformed frame stays an error.
        assert!(ext_decode(CONTINUITY_TOKEN_EXT, &[0x5Au8; 31]).is_err(),
            "31 bytes is not a 256-bit token — tolerance must not become blanket verbatim");
        assert!(ext_decode(CONTINUITY_TOKEN_EXT, &[0x02, 0xAA]).is_err(), "declared 2, carries 1");
        // The COMMITMENT is deliberately outside the tolerance: 0xF011 has only ever been read
        // framed, and blurring it would cost the one measurement that question turns on.
        assert!(ext_decode(CONTINUITY_TOKEN_COMMITMENT_EXT, &[0xABu8; 32]).is_err(),
            "0xF011 gets no bare arm — see bare_payload_is_plausible");
    }

    /// THREE framings exist for 0xF007 in the wild. The decoder must survive all of them.
    ///
    /// Established 2026-08-07 — the code point has no in-band
    /// discriminator, so this is not a version question and cannot be resolved by announcing one:
    ///
    /// ```text
    ///   30 bytes  what v4.0 §7.11.10.1 SPECIFIES  (opaque<V>-framed 29-char string)
    ///   29 bytes  what GOOGLE MESSAGES' engine was measured emitting (bare ASCII, no prefix)
    ///    0 bytes  what WE observed on the wire from a THIRD-PARTY client (2026-08-06)
    /// ```
    ///
    /// The same family shows the same hazard elsewhere: that third-party client wrote a 32-byte
    /// `subject_commitment` (0xF006) where our own groups carry 33 (`0x20 ‖ hash`). So at least
    /// three encoders disagree about framing across this block, and a decoder that assumes ONE will
    /// mis-parse the others.
    ///
    /// **The operational rule, reached independently from two directions: PRESENCE is the reliable
    /// signal; the PAYLOAD is not.** Nothing gates behaviour on these bytes.
    #[test]
    fn all_three_observed_0xf007_framings_decode_without_error() {
        let _g = METADATA_KEYS_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let value = METADATA_KEYS_REQUESTED_DATA;          // 29 bytes
        let mut framed = vec![29u8];
        framed.extend_from_slice(value);                    // 30 bytes, v4.0's opaque<V>
        let empty: &[u8] = &[];                             // 0 bytes, the third-party form

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

    /// The 0xF007 framing, resolved BY REVISION rather than by picking a side.
    ///
    /// v4.0 §7.11.10.1 says `opaque<V>` (framed); what Google Messages emits is bare. That was
    /// measured on a v3.0-era client — so the two sources were never in conflict, they
    /// describe two different revisions. §10.5.2 needs a PRODUCER for this extension (a client asks
    /// for metadata keys by putting it in the GroupContext), so "refuse" could not stand.
    #[test]
    fn the_metadata_keys_framing_follows_the_revision_and_decodes_both() {
        let _g = METADATA_KEYS_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let _v = VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let bare = METADATA_KEYS_REQUESTED_DATA.to_vec();
        let mut framed = vec![29u8];
        framed.extend_from_slice(METADATA_KEYS_REQUESTED_DATA);
        assert_eq!(framed.len(), 30, "the framed form is exactly one byte longer");

        // ENCODE follows the announcement, strictly.
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        assert!(metadata_keys_is_bare());
        assert_eq!(ext_encode(METADATA_KEYS_REQUESTED_EXT, METADATA_KEYS_REQUESTED_DATA).unwrap(),
                   bare, "v3.0 is the measured v3.0-era form");

        assert_eq!(set_rcc16_version(RCC16_V4_0), RCC16_V4_0);
        assert!(!metadata_keys_is_bare());
        assert_eq!(ext_encode(METADATA_KEYS_REQUESTED_EXT, METADATA_KEYS_REQUESTED_DATA).unwrap(),
                   framed, "v4.0 §7.11.10.1 is opaque<V>");

        // DECODE stays tolerant of BOTH under EITHER announcement — the fixed 29-byte payload makes
        // them distinguishable by length, so tolerance costs nothing and protects us from a server
        // that upgrades before we notice.
        for v in [RCC16_V3_0, RCC16_V4_0] {
            set_rcc16_version(v);
            assert_eq!(ext_decode(METADATA_KEYS_REQUESTED_EXT, &bare).unwrap(), bare);
            assert_eq!(ext_decode(METADATA_KEYS_REQUESTED_EXT, &framed).unwrap(), bare);
        }
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
    }

    /// Continuity emission is version-gated; the code points are readable either way.
    #[test]
    fn continuity_is_emitted_only_under_v4_and_the_token_is_welcome_only() {
        let _v = VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        assert!(!emit_continuity(), "Tachyon has never carried either code point, and the failure             mode of getting continuity wrong is a DOWNGRADED conversation");
        assert_eq!(set_rcc16_version(RCC16_V4_0), RCC16_V4_0);
        assert!(emit_continuity());
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);

        // The TOKEN is a secret and must never leave in a server-bound GroupInfo; its COMMITMENT
        // must appear in every one. Swapping these hands the server a 256-bit group secret.
        assert!(WELCOME_ONLY_EXTS.contains(&CONTINUITY_TOKEN_EXT));
        assert!(!WELCOME_ONLY_EXTS.contains(&CONTINUITY_TOKEN_COMMITMENT_EXT));
        assert!(WELCOME_ONLY_EXTS.contains(&ICON_KEY_EXT));
        assert!(WELCOME_ONLY_EXTS.contains(&SUBJECT_KEY_EXT));
        // Both are varint-framed; neither is in the bare set.
        assert!(!ext_payload_is_bare(CONTINUITY_TOKEN_EXT));
        assert!(!ext_payload_is_bare(CONTINUITY_TOKEN_COMMITMENT_EXT));
    }

    /// The NUMBER is a spec fact now (v4.0 §7.11.10.1), so it is armed by default and therefore
    /// advertised. This is the assertion that was inverted while the value was our own guess.
    #[test]
    fn the_metadata_keys_code_point_is_armed_and_advertised_by_default() {
        let _g = METADATA_KEYS_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(metadata_keys_ext(), Some(0xF007),
            "v4.0 §7.11.10.1 publishes the value — it is no longer a guess to be withheld");
        assert!(advertised_extensions(&[]).contains(&METADATA_KEYS_REQUESTED_EXT),
            "a code point we can now name and decode must be advertised");
        // Still overridable, so a capture that contradicts v4.0 can win without a rebuild.
        set_metadata_keys_ext(0);
        assert_eq!(metadata_keys_ext(), None);
        assert!(!advertised_extensions(&[]).contains(&METADATA_KEYS_REQUESTED_EXT));
        set_metadata_keys_ext(METADATA_KEYS_REQUESTED_EXT);
    }

    /// An unknown/future code point must default to FRAMED, not bare — the bare set is closed.
    #[test]
    fn an_unknown_type_defaults_to_framed() {
        assert!(!ext_payload_is_bare(0xF020));
        assert_eq!(ext_encode(0xF020, &[1, 2, 3]).unwrap(), vec![0x03, 1, 2, 3]);
    }

    /// The varint widens at 0x40, which is exactly the boundary between a 32-byte commitment and a
    /// longer key blob — i.e. the range we actually operate in, so both sides are pinned.
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

    /// A non-minimal varint must be REJECTED. 32 encoded two-wide (`0x40 0x20`) says "length 32" to a
    /// permissive decoder — but a real peer rejects it, so accepting it would leave us agreeing
    /// with nobody about the GroupContext bytes.
    #[test]
    fn a_non_minimal_varint_is_rejected() {
        let mut bad = vec![0x40u8, 0x20];
        bad.extend_from_slice(&[0xAB; 32]);
        assert!(ext_decode(SUBJECT_COMMITMENT_EXT, &bad).is_err(),
                "non-minimal length encoding must not decode");
        // The reserved `11` prefix is not a length at all.
        assert!(ext_decode(SUBJECT_COMMITMENT_EXT, &[0xC0, 0x00]).is_err());
    }

    /// Trailing or missing bytes mean the payload is not one framed vector — a mis-framed commitment
    /// must be reported, never silently returned as-is.
    #[test]
    fn a_length_that_disagrees_with_the_payload_is_rejected() {
        assert!(ext_decode(ICON_COMMITMENT_EXT, &[0x02, 0xAA]).is_err(), "declared 2, carries 1");
        assert!(ext_decode(ICON_COMMITMENT_EXT, &[0x01, 0xAA, 0xBB]).is_err(), "trailing byte");
        assert!(ext_decode(ICON_COMMITMENT_EXT, &[]).is_err(), "no varint at all");
        // Empty-but-framed is legal and round-trips.
        assert_eq!(ext_encode(ICON_COMMITMENT_EXT, &[]).unwrap(), vec![0x00]);
        assert_eq!(ext_decode(ICON_COMMITMENT_EXT, &[0x00]).unwrap(), Vec::<u8>::new());
    }

    /// Google Messages has TWO 0xF002 constructors and the non-literal one takes a caller-supplied
    /// payload, so a longer end_mls tag must be tolerated on decode rather than rejected.
    #[test]
    fn a_longer_end_mls_payload_is_tolerated() {
        let odd = b"end_mls\x01\x02\x03".to_vec();
        assert_eq!(ext_decode(END_MLS_EXT, &odd).unwrap(), odd, "bare types decode verbatim");
    }

    /// The announced version is a process-global and cargo runs tests as parallel THREADS in ONE
    /// process, so the three tests that flip it must not interleave — otherwise "the default is
    /// v3.0" fails whenever it happens to run while another test holds v4.0. Serialising them is
    /// the fix; making the version thread-local would be a lie, because in production it really is
    /// one per-process value. (`.unwrap_or_else(|e| e.into_inner())` so one failing test poisoning
    /// the lock does not cascade into two spurious failures.)
    pub(crate) use super::VERSION_LOCK;

    /// The proposal advertisement is the ONE list that changes with the spec revision, and both
    /// changes would be wrong if applied unconditionally.
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
        // The implemented set minus the withdrawal survives, both ways.
        for p in [RCS_SIGNATURE_PROP, SELF_REMOVE_PROP, SERVER_REMOVE_PROP] {
            assert!(v3.contains(&p) && v4.contains(&p), "0x{p:04X} is implemented in both");
        }

        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
    }

    /// A guard, not a feature test: the default must be the version we have WIRE EVIDENCE for.
    /// Tachyon accepted our v3.0 `end_mls` and Google Messages decrypted the result; no v4.0
    /// shape has ever been observed from it. A default of v4.0 would silently break that path on
    /// every device that took the update, with no error anywhere.
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

    /// The whole point of routing both encode sites through one function: flipping the announced
    /// version changes the payload, and NOTHING changes it while the announcement says v3.0.
    #[test]
    fn end_mls_payload_follows_the_announced_version() {
        let _g = VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
        // v3.0 ignores the reason entirely — it has nowhere to put one.
        for r in [EndMlsReason::Unset, EndMlsReason::OutgoingCommitFailed,
                  EndMlsReason::ServerFailureEraAdvancementQuotaReached] {
            assert_eq!(end_mls_payload(r), END_MLS_DATA.to_vec(),
                "under v3.0 the payload is the ASCII literal for EVERY reason — this is the byte \
                 sequence Tachyon has accepted and Google Messages has decrypted");
        }

        assert_eq!(set_rcc16_version(RCC16_V4_0), RCC16_V4_0);
        // Bare protobuf, field 1 varint. NOT a length-framed opaque<V>.
        assert_eq!(end_mls_payload(EndMlsReason::ContinuityTokenMismatch), vec![0x08, 0x05]);
        assert_eq!(end_mls_payload(EndMlsReason::OutgoingCommitFailed), vec![0x08, 0x0A]);
        // UNSET(0) is a legal EndMlsMetadata that serialises to NOTHING. An empty extension_data
        // is therefore meaningful under v4.0, and is emphatically not the v3.0 7-byte string.
        assert_eq!(end_mls_payload(EndMlsReason::Unset), Vec::<u8>::new());
        assert_ne!(end_mls_payload(EndMlsReason::Unset), END_MLS_DATA.to_vec());

        assert_eq!(set_rcc16_version(RCC16_V3_0), RCC16_V3_0);
    }

    /// Decode tolerantly, encode strictly. A v4.0 payload arriving while we announce v3.0 must be
    /// UNDERSTOOD, not rejected — that is what protects us from a server upgrading before we notice.
    #[test]
    fn both_end_mls_payload_forms_are_understood_regardless_of_the_announced_version() {
        let _g = VERSION_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(rcc16_version(), RCC16_V3_0, "tolerance is tested from the v3.0 announcement");
        assert!(end_mls_payload_understood(END_MLS_DATA), "the v3.0 literal");
        assert!(end_mls_payload_understood(b"end_mls\x01\x02"), "the tolerated longer v3.0 tag");
        assert!(end_mls_payload_understood(&[]), "v4.0 UNSET is zero bytes");
        assert!(end_mls_payload_understood(&[0x08, 0x0B]), "v4.0 era-quota reason");
        // Not understood, and that must NOT be read as "not end_mls" — presence carries the
        // meaning in both versions, so an unparsed payload still downgrades the conversation.
        assert!(!end_mls_payload_understood(&[0x08, 0x7F]), "0x7F is not a defined reason");
        assert!(!end_mls_payload_understood(&[0xFF, 0x01]), "field 31 wiretype 7 is not our shape");
    }
}

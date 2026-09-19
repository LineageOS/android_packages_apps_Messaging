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

// GSMA RCC.16 credential validation (Annex A.3.8 / A.4.1). Wraps mls-rs's X509Validator (chain
// validation) and enforces the RCS-MLS cert profile on the leaf: the vendor extension family
// 2.23.146.2.1.x, the A.4.1 default-validation checks, and the CRITICAL .4 ParticipantInformation
// proof-of-possession (structure byte-pinned to a real KDS leaf's ext_4.der via MlsSyntheticCa).
use mls_rs_core::{crypto::SignaturePublicKey, error::IntoAnyError, time::MlsTime};
use mls_rs_identity_x509::{CertificateChain, X509CredentialValidator};
use mls_rs_crypto_rustcrypto::x509::X509Validator;
use x509_cert::{Certificate, der::{Decode, Encode}};
use p256::ecdsa::{Signature, VerifyingKey,
                  signature::hazmat::PrehashVerifier};
use sha2::{Digest, Sha224, Sha256, Sha384, Sha512};

// GSMA arc 2.23.146.2.1.x
const OID_EKU_RCSMLS: &str = "2.23.146.2.1.3";   // id-kp-rcsMlsClient
const OID_PARTICIPANT_INFO: &[u8] = &[0x67, 0x81, 0x12, 0x02, 0x01, 0x04];  // 2.23.146.2.1.4 DER
const OID_ACS_PARTICIPANT_INFO: &[u8] = &[0x67, 0x81, 0x12, 0x02, 0x01, 0x05];
const OID_SAN: &str = "2.5.29.17";
const OID_EKU: &str = "2.5.29.37";
const OID_KEY_USAGE: &str = "2.5.29.15";
const MAX_LIFETIME_S: u64 = 76 * 24 * 3600;      // A.4.1: cert lifetime <= 76 days
const MIN_REMAINING_S: u64 = 30 * 24 * 3600;     // A.4.1: >= 30 days remaining at query time

// ---- §14.2.3 profile OIDs --------------------------------------------------------------------
/// `vendorId`, carried in the ROOT certificate. Google == 2, Apple == 1 (§14.4, decoded from the
/// four hardcoded roots that ship in Google Messages).
const OID_VENDOR_ID: &[u8] = &[0x67, 0x81, 0x12, 0x02, 0x01, 0x06];  // 2.23.146.2.1.6 DER
const OID_SKI: &str = "2.5.29.14";
const OID_AKI: &str = "2.5.29.35";
const OID_CERT_POLICIES: &str = "2.5.29.32";
const OID_CRL_DP: &str = "2.5.29.31";
const OID_AIA: &str = "1.3.6.1.5.5.7.1.1";
const OID_ANY_POLICY: &str = "2.5.29.32.0";

/// ECDSA signature algorithms — SHA-256/384/512 only. **There is no RSA arc**, and re-imposing that
/// is not optional: the BoringSSL PKI bundled in the same `.so` DOES implement RSA PKCS#1 v1.5 and
/// RSA-PSS, so a delegated path build accepts RSA happily. §14.2.3 calls this out as the caveat you
/// must not miss, and we delegate path building to mls-rs, so it applies to us exactly.
const SIG_ALGS_ECDSA: &[&str] = &[
    "1.2.840.10045.4.3.2",   // ecdsa-with-SHA256
    "1.2.840.10045.4.3.3",   // ecdsa-with-SHA384
    "1.2.840.10045.4.3.4",   // ecdsa-with-SHA512
];
/// id-ecPublicKey — the only permitted SPKI algorithm.
const OID_EC_PUBLIC_KEY: &str = "1.2.840.10045.2.1";
/// P-256 / P-384 / P-521 only.
const CURVES_PERMITTED: &[&str] = &[
    "1.2.840.10045.3.1.7",   // prime256v1 / P-256
    "1.3.132.0.34",          // secp384r1 / P-384
    "1.3.132.0.35",          // secp521r1 / P-521
];

/// Extensions the §14.2.3 profile inspects, and therefore the ONLY ones permitted.
///
/// Derived from the profile's own per-extension diagnostics ("Invalid <X> extension: {}"), which
/// enumerate exactly: version, signature algorithm, subject components, subject key identifier,
/// key usage, extended key usage, authority information access, CRL distribution points, basic
/// constraints, vendor ID, subject public key info, subject alternative name, certificate policies,
/// and the two participant-information extensions.
const EXTENSIONS_PERMITTED: &[&str] = &[
    OID_SKI, OID_KEY_USAGE, OID_SAN, OID_BASIC_CONSTRAINTS, OID_CRL_DP,
    OID_CERT_POLICIES, OID_AKI, OID_EKU, OID_AIA,
    "2.23.146.2.1.4",        // ParticipantInformation
    "2.23.146.2.1.5",        // ACS SignedEncryptionIdentityProof
    "2.23.146.2.1.6",        // vendorId
];

/// X.520 upper bounds for the subject attributes §14.2.3 bounds explicitly.
const RDN_MAX_C: usize = 2;
const RDN_MAX_O: usize = 64;
const RDN_MAX_CN: usize = 64;
const RDN_MAX_ST: usize = 128;
const RDN_MAX_L: usize = 128;
/// RFC 5280: a serial number is at most 20 octets.
const SERIAL_MAX_OCTETS: usize = 20;

#[derive(Debug)]
pub struct Rcc16Error(pub String);
impl IntoAnyError for Rcc16Error {}

/// RCC.16 validator: X509Validator chain check + the A.4.1 leaf profile (incl. the .4 PoP).
#[derive(Clone, Debug)]
pub struct Rcc16Validator {
    inner: X509Validator,
    /// A.4.1 DEPLOYMENT strictness: the lifetime floors (<=76 d total, >=30 d remaining) and the
    /// subject/policy profile rules. Lab = strict; Tachyon = relaxed, because the KDS mints 75.5-day
    /// leaves and the >=30-day floor would refuse every Google Messages peer from day ~46 of its own
    /// certificate. This is an INTEROP dial, not a security one.
    ///
    /// <b>It no longer governs the .4 PoP.</b> See {@link Self::pop_strict}.
    pub strict: bool,
    /// Whether a FAILED .4 ParticipantInformation PoP REJECTS the certificate. Default true.
    ///
    /// SPLIT FROM `strict` ON 2026-08-07 because the evidence for the two now
    /// points in OPPOSITE directions, which is exactly when one flag for two facts stops being a
    /// convenience and starts being a bug:
    ///   - the lifetime floors must stay RELAXED on Tachyon, or we refuse every Google Messages peer;
    ///   - the PoP can be ENFORCED, because every certificate we can measure now verifies.
    /// Flipping the old shared `strict` would have re-broken the lifetime interop to buy the PoP
    /// enforcement — the two were only ever coupled by an implementation detail.
    ///
    /// EVIDENCE FOR THE DEFAULT (all with the corrected element-4 layout):
    ///   - Google KDS, live-claimed on-device from THREE distinct lines across TWO Google Messages
    ///     versions: 6/6 `PoP VERIFIED`, zero tolerations.
    ///   - Apple, from a real captured inbound (vendorId=1, two distinct keys): VERIFIED.
    ///   - our own issuer and the lab: VERIFIED.
    /// The lenient branch existed to tolerate real deployed certificates; there is no longer a known
    /// certificate it would tolerate.
    ///
    /// FALSIFIER / WHAT REOPENS THIS: any `.4 PoP tolerated` line from a peer we intend to
    /// interoperate with. That is now a REJECTION, so it will show up as a failed add rather than a
    /// warning — which is the point, and also why the escape hatch below exists.
    pub pop_strict: bool,
    /// A.4.1 SAN identity equality: the queried/expected MSISDN (E.164). When `Some`, the leaf SAN
    /// `tel:` URI must EQUAL this number (a cert for the WRONG number FAILs), not merely be a
    /// well-formed `tel:` URI. When `None` the SAN check falls back to presence-only — see the
    /// TODO(msisdn-plumbing) in `ffi.rs`, where the caller must thread the queried MSISDN through.
    pub expected_msisdn: Option<String>,
    /// REVOKED CERTIFICATE SERIAL NUMBERS — the `RevokedCertificates` list Google Messages always
    /// passes to `create_client`, "present and empty".
    ///
    /// Present and empty is the normal state, and the slot existing is the point: RCC.16 LEAF certs
    /// are deliberately NON-REVOCABLE, so the intermediate-CA CRL is the only revocation lever in
    /// the whole design. Without somewhere to put a serial there is nothing to pull when an ICA is
    /// compromised — every certificate it ever minted, for every MSISDN, stays valid until it
    /// expires.
    ///
    /// Serial numbers are compared as raw DER INTEGER *value* bytes, leading zeroes stripped, so a
    /// serial is matched by its number rather than by one encoding of it.
    pub revoked_serials: Vec<Vec<u8>>,
}
impl Rcc16Validator {
    pub fn new(inner: X509Validator) -> Self {
        Self { inner, strict: true, pop_strict: true, expected_msisdn: None,
               revoked_serials: Vec::new() }
    }
    /// Replace the revocation list. Empty (the default) is the normal state in the field.
    pub fn with_revoked_serials(mut self, serials: Vec<Vec<u8>>) -> Self {
        self.revoked_serials = serials.into_iter().map(|s| normalize_serial(&s)).collect();
        self
    }
    /// Set the queried MSISDN so the A.4.1 SAN identity-equality check is enforced (not presence-only).
    pub fn with_expected_msisdn(mut self, msisdn: Option<String>) -> Self {
        self.expected_msisdn = msisdn; self
    }
}

impl X509CredentialValidator for Rcc16Validator {
    type Error = Rcc16Error;
    fn validate_chain(&self, chain: &CertificateChain, ts: Option<MlsTime>)
            -> Result<SignaturePublicKey, Rcc16Error> {
        // 1) standard MLS chain validation (path to a trusted root, signatures, expiry).
        let pk = self.inner.validate_chain(chain, ts).map_err(|e| Rcc16Error(format!("chain: {e:?}")))?;
        // 2) RCC.16 A.4.1 leaf profile.
        let leaf_der = chain.leaf().ok_or_else(|| Rcc16Error("empty chain".into()))?;
        validate_leaf_rcc16(leaf_der.as_ref(), ts, self.strict, self.pop_strict,
                            self.expected_msisdn.as_deref())?;
        // 3) Revocation, on EVERY certificate in the chain including the leaf.
        //
        // Checked before the CA profile so a revoked issuer is reported as revoked rather than as
        // whatever structural complaint it happens to also have — the remedy is entirely different.
        if !self.revoked_serials.is_empty() {
            for (i, c) in chain.iter().enumerate() {
                if let Some(sn) = cert_serial(c.as_ref()) {
                    if self.revoked_serials.iter().any(|r| *r == sn) {
                        return Err(Rcc16Error(format!(
                            "chain[{i}]: certificate serial {} is REVOKED",
                            sn.iter().map(|b| format!("{b:02x}")).collect::<String>())));
                    }
                }
            }
        }
        // 4) RCC.16 A.2 CA profile on EVERY issuer in the chain.
        //
        // Without this, any certificate the trust anchor happens to have signed can mint leaves for
        // arbitrary MSISDNs — an end-entity cert with no CA constraint is accepted as an issuer, and
        // RCC.16 leaves are deliberately non-revocable, so there is no lever to pull afterwards.
        for (i, ca) in chain.iter().enumerate().skip(1) {
            validate_ca_rcc16(ca.as_ref())
                .map_err(|e| Rcc16Error(format!("chain[{i}]: {}", e.0)))?;
        }

        // 5) §14.2.3's X.509 profile on every certificate.
        //
        // These are the rules that sit ON TOP of path building: version, serial, ECDSA-only,
        // permitted curves, subject shape, SKI/AKI, certificatePolicies, and the unexpected-
        // extension rule. mls-rs's verifier enforces none of them, and its BoringSSL-equivalent
        // happily accepts RSA — so anything not re-imposed here is simply not enforced.
        let n = chain.len();
        for (i, c) in chain.iter().enumerate() {
            let role = if i == 0 { CertRole::Leaf }
                       else if i == n - 1 && n > 1 { CertRole::Intermediate }
                       else { CertRole::Intermediate };
            validate_x509_profile(c.as_ref(), role, self.strict)
                .map_err(|e| Rcc16Error(format!("chain[{i}]: {}", e.0)))?;
        }

        // 6) CHAIN LENGTH.
        //
        // §14.2.3 says ">= 3 (leaf + >=1 intermediate + root)". Our `CertificateChain` does NOT
        // carry the trust anchor — roots are supplied separately at create_client — so the same
        // chain that counts as 3 for Google Messages counts as 2 here, and enforcing 3 against our
        // representation would reject conformant peers. We therefore enforce the part that is
        // certainly implied either way: a leaf plus at least one issuer. Whether their count
        // includes the anchor is undetermined and is NOT guessed.
        if n < 2 {
            return Err(Rcc16Error(format!(
                "Certificate chain length must be >= 3, was {n} \
                 (counting leaf + issuers; the trust anchor is supplied separately)")));
        }

        // 7) vendorId(leaf) == vendorId(ROOT) — §14.2.3 / §14.4.
        //
        // The leaf's vendor id lives inside its .4 ParticipantInformation; the root's is its own
        // 2.23.146.2.1.6 extension, with Google == 2 and Apple == 1. This used to be hardcoded to
        // 2, which rejected every Apple peer outright — and Apple roots ship in Google Messages by
        // construction, so the trust store is multi-vendor and that was a real interop break.
        //
        // Binding leaf to ROOT rather than to a constant is what makes it meaningful: it says a
        // leaf may only claim the vendor of the anchor that actually certified it, which stops one
        // vendor's CA from minting leaves that impersonate another's.
        //
        // Skipped silently when either side carries no vendor id: our own lab CA does not mint one,
        // and failing closed here would break every lab and self-test chain for a rule that only
        // has meaning when both ends assert it.
        if let (Some(leaf_v), Some(root_v)) = (
                leaf_vendor_id(leaf_der.as_ref()),
                chain.iter().last().and_then(|c| cert_vendor_id(c.as_ref()))) {
            if leaf_v != root_v {
                return Err(Rcc16Error(format!(
                    "Participant information wrong vendor ID: expected {:?}, got {:?}",
                    root_v, leaf_v)));
            }
        }
        Ok(pk)
    }
}

// ---- minimal DER TLV reader (matches the .4 structure exactly) ----
struct Tlv<'a> { tag: u8, full: &'a [u8], val: &'a [u8] }
fn read_tlv<'a>(b: &'a [u8], pos: &mut usize) -> Option<Tlv<'a>> {
    let s = *pos;
    if s + 2 > b.len() { return None; }
    let tag = b[s]; let mut i = s + 1;
    let l0 = b[i] as usize; i += 1;
    let len = if l0 < 0x80 { l0 } else {
        let n = l0 & 0x7f; if i + n > b.len() { return None; }
        let mut v = 0usize; for _ in 0..n { v = (v << 8) | b[i] as usize; i += 1; } v
    };
    if i + len > b.len() { return None; }
    let full = &b[s..i + len]; let val = &b[i..i + len];
    *pos = i + len;
    Some(Tlv { tag, full, val })
}
fn der_len(l: usize) -> Vec<u8> {
    if l < 0x80 { vec![l as u8] } else {
        let b = (l as u64).to_be_bytes(); let b = &b[b.iter().position(|&x| x != 0).unwrap()..];
        let mut o = vec![0x80 | b.len() as u8]; o.extend_from_slice(b); o
    }
}
fn der_seq(parts: &[&[u8]]) -> Vec<u8> {
    let body: Vec<u8> = parts.concat();
    let mut o = vec![0x30]; o.extend_from_slice(&der_len(body.len())); o.extend_from_slice(&body); o
}
fn der_int(n: u64) -> Vec<u8> {
    let b = n.to_be_bytes(); let mut b = &b[b.iter().position(|&x| x != 0).unwrap_or(7)..];
    let mut body = Vec::new();
    if b[0] & 0x80 != 0 { body.push(0); }
    body.extend_from_slice(b); let _ = &mut b;
    let mut o = vec![0x02]; o.extend_from_slice(&der_len(body.len())); o.extend_from_slice(&body); o
}

fn validate_leaf_rcc16(leaf_der: &[u8], ts: Option<MlsTime>, strict: bool, pop_strict: bool,
        expected_msisdn: Option<&str>) -> Result<(), Rcc16Error> {
    let cert = Certificate::from_der(leaf_der).map_err(|e| Rcc16Error(format!("leaf parse: {e}")))?;
    let tbs = &cert.tbs_certificate;
    let exts = tbs.extensions.as_ref().ok_or_else(|| Rcc16Error("no extensions".into()))?;

    // Locate the extensions we need by OID.
    let mut san_val: Option<&[u8]> = None;
    let mut eku_val: Option<&[u8]> = None;
    let mut ku_val: Option<&[u8]> = None;
    let mut p4_val: Option<&[u8]> = None;
    let mut p4_critical = false;
    // .5 id-acsParticipantInformation — the ACS SignedEncryptionIdentityProof.
    let mut p5_val: Option<&[u8]> = None;
    for e in exts.iter() {
        let oid = e.extn_id.to_string();
        let raw = e.extn_value.as_bytes();
        match oid.as_str() {
            OID_SAN => san_val = Some(raw),
            OID_EKU => eku_val = Some(raw),
            OID_KEY_USAGE => ku_val = Some(raw),
            _ => {
                let oid_der = e.extn_id.as_bytes();
                if oid_der == OID_PARTICIPANT_INFO { p4_val = Some(raw); p4_critical = e.critical; }
                if oid_der == OID_ACS_PARTICIPANT_INFO { p5_val = Some(raw); }
            }
        }
    }

    // A.4.1.1 / A.3.8.7: EKU must be EXACTLY the single id-kp-rcsMlsClient (2.23.146.2.1.3).
    // Exclusivity, not membership: any additional EKU OID (e.g. serverAuth/clientAuth) is a profile
    // violation and MUST reject — an RCS-MLS leaf is single-purpose.
    let eku = eku_val.ok_or_else(|| Rcc16Error("A.4.1: missing EKU".into()))?;
    if !eku_is_exactly(eku, OID_EKU_RCSMLS) {
        return Err(Rcc16Error("A.4.1.1: EKU must be EXACTLY id-kp-rcsMlsClient (2.23.146.2.1.3); no other EKU permitted".into()));
    }
    // A.4.1: KeyUsage must assert digitalSignature (bit 0).
    let ku = ku_val.ok_or_else(|| Rcc16Error("A.4.1: missing KeyUsage".into()))?;
    if !key_usage_has_digital_signature(ku) {
        return Err(Rcc16Error("A.4.1: KeyUsage lacks digitalSignature".into()));
    }
    // A.4.1: SAN must carry a tel: URI identity that EQUALS the queried MSISDN. Presence alone is
    // insufficient — a leaf cert for the WRONG number must FAIL. When the caller has plumbed the
    // expected MSISDN we enforce identity equality (E.164-normalized); otherwise we can only enforce
    // tel:-URI presence (see the TODO(msisdn-plumbing) at the ffi.rs construction site).
    let san = san_val.ok_or_else(|| Rcc16Error("A.4.1: missing SAN".into()))?;
    match expected_msisdn {
        Some(want) => {
            if !san_tel_uri_equals(san, want) {
                return Err(Rcc16Error(format!(
                    "A.4.1: SAN tel: URI does not match queried MSISDN {want}")));
            }
        }
        None => {
            // TODO(msisdn-plumbing): the caller has not passed the queried MSISDN, so identity
            // EQUALITY (A.4.1) cannot be enforced here — presence-only. Thread the MSISDN in via
            // Rcc16Validator::with_expected_msisdn (ffi.rs) to close this fully.
            if !san_has_tel_uri(san) {
                return Err(Rcc16Error("A.4.1: SAN lacks a tel: URI identity".into()));
            }
        }
    }
    // ---- the lifetime floors: RCC.16 REQUIRES them, Google's deployment does NOT --------------
    //
    // §14.2.3's table has no lifetime or remaining-lifetime rule at all. RCC.16 A.4.1 does, and we
    // enforced both unconditionally — which is an ACTIVE interop break, not a theoretical one:
    //
    //   the KDS mints 75.5-day leaves (byte-verified on two captures)
    //   we reject any peer with < 30 days remaining
    //   => every Google Messages peer is refused from day ~46 of its own certificate onward
    //
    // and refused with an error that reads like the PEER is misconfigured. Google accepts that
    // peer, so the disagreement is entirely ours.
    //
    // Gated on `strict` rather than deleted, because these are real RCC.16 rules and our own lab
    // and self-test certificates should keep satisfying them. `strict` is exactly the right lever:
    // it is already "full RCC.16 conformance" (lab) versus "Google-deployment interop" (Tachyon),
    // set from `!tachyon_profile()` at construction.
    let nb = tbs.validity.not_before.to_unix_duration().as_secs();
    let na = tbs.validity.not_after.to_unix_duration().as_secs();
    if strict && na.saturating_sub(nb) > MAX_LIFETIME_S {
        return Err(Rcc16Error(format!("A.4.1: lifetime {} d > 76 d", (na - nb) / 86400)));
    }
    // >= 30 days remaining. RCC.16 mandates this floor UNCONDITIONALLY, but mls-rs passes ts=None on
    // several paths (external-commit / new-group joiner — client.rs:636 — per its "None => skip
    // expiration" contract). Silently skipping would let a near-expiry cert through, so we FAIL
    // CLOSED: fall back to a trusted system clock when ts is absent, and only if NO trusted time
    // source exists at all do we reject (never silently pass). A conformant cert (>= 30 d remaining)
    // passes regardless of which "now" source is used, so this does not reject valid peers.
    let now = ts.map(|t| t.seconds_since_epoch())
        .or_else(trusted_now_secs)
        .ok_or_else(|| Rcc16Error(
            "A.4.1: no trusted time source for the >=30-day remaining-lifetime floor (fail-closed)".into()))?;
    if strict && na.saturating_sub(now) < MIN_REMAINING_S {
        return Err(Rcc16Error(format!("A.4.1: only {} d remaining < 30 d", na.saturating_sub(now) / 86400)));
    }
    // On the deployment profile the certificate must still be VALID — just not 30-days-valid. That
    // is the part both sides agree on, and dropping it with the floor would have been a real
    // weakening rather than an interop fix.
    if !strict && now >= na {
        return Err(Rcc16Error(format!(
            "leaf expired {} d ago", now.saturating_sub(na) / 86400)));
    }
    // .4 ParticipantInformation must be present + CRITICAL.
    let p4 = p4_val.ok_or_else(|| Rcc16Error("A.3.8.9: missing critical ParticipantInformation ext .4".into()))?;
    if !p4_critical {
        return Err(Rcc16Error("A.3.8.9: ParticipantInformation ext .4 must be CRITICAL".into()));
    }
    // The .4 extn_value is an OCTET STRING whose content is our SEQUENCE.
    let p4_inner = unwrap_octet_string(p4).unwrap_or(p4);

    // .4 EXPIRY IS ENFORCED UNCONDITIONALLY — it is NOT part of the lenient PoP gate.
    //
    // The lenient branch below existed for one reason, and that reason is now GONE: we believed
    // the .4 tbsParticipantInfo byte layout was unpinned, so a strict verify could reject a
    // perfectly good deployed certificate. It was our element 4 that was wrong (fixed
    // 2026-08-07); a real KDS leaf now verifies. Leniency is KEPT for now because it still covers
    // other vendors we have never byte-checked — Apple is the concrete case — and flipping the
    // Tachyon profile to strict is a rejection-behaviour change that wants device evidence first.
    // WHAT WOULD JUSTIFY FLIPPING IT: ".4 ParticipantInformation PoP VERIFIED" logged against real
    // Google Messages peers on-device, with no "tolerated (lenient)" line from any peer we intend to
    // interoperate with. Until then this branch is load-bearing for other vendors, not for Google.
    // That argument does not extend to the validity window. Its TLV parses fine on real certs (the PoP
    // gets far enough to fail on the SIGNATURE, not on structure), and an EXPIRED binding is expired
    // regardless of how its tbs bytes are ordered. Folding it into the lenient branch would have made
    // the check unenforced on the only path that matters — device-confirmed 2026-07-30, where a device
    // logged ".4 PoP tolerated (lenient)" against real peers, which is exactly where an expired
    // .4 would have gone too.
    if let Some(v) = participant_validity_tlv(p4_inner) {
        check_participant_validity(v, now)?;
    }

    // A .4 CARRYING A KEY ROLL IS REFUSED UNCONDITIONALLY — AND THAT IS WHY IT IS HERE AND NOT
    // INSIDE THE PoP (hoisted 2026-09-13).
    //
    // The refusal used to live in `verify_participant_pop`, whose every error is SWALLOWED by the
    // lenient arm below. `debug.rcs.mls_pop_lenient=1` is a shipped, documented one-setprop escape
    // hatch (`rcs_mls_set_pop_lenient` -> `pop_strict = false`), so flipping it for an interop
    // outage ALSO silently restored "accept a credential asserting a key-change chain nobody
    // verified" — the exact hole that refusal closed, re-opened by a lever whose documentation says only
    // that a failed PoP stops rejecting. Nothing named the coupling and no test could catch it: the
    // only coverage called `verify_participant_pop` directly, bypassing this gate entirely.
    //
    // This is the same split, for the same reason, as `check_participant_validity` two lines above:
    // a STRUCTURAL refusal is not a signature we failed to reproduce, so it does not belong behind
    // the dial that tolerates signatures. A.4.1.1(1c-ii) requires the roll chain to be VERIFIED; we
    // do not implement that, so the only safe answer is to refuse, on every profile setting.
    if let Some((n, tag)) = participant_trailing_after_spki(p4_inner) {
        return Err(trailing_after_spki_error(n, tag));
    }

    // .5 ACS PROOF EXPIRY — DECODE AND LOG ONLY FOR NOW (step 1 of 3).
    //
    // THE SEAM IS THE DECISION HERE, not the comparison. That hole was not "we forgot to verify"; it
    // was a refusal written into `verify_participant_pop`, every error of which is SWALLOWED by the
    // lenient arm below — so `debug.rcs.mls_pop_lenient=1`, a shipped one-setprop escape hatch
    // documented as only tolerating a failed PoP, silently disarmed it too. There are exactly two
    // global levers over this validator (`strict`, from the Tachyon profile, and `pop_strict`, from
    // that sysprop). This site is before the `pop_strict` match and inside no `if strict`, so
    // NEITHER reaches it — the same placement, for the same reason, as `check_participant_validity`
    // and the key-roll refusal above. `acs_proof_expiry_check_is_outside_the_lenient_gate` pins
    // that, so the seam cannot be quietly moved into the PoP where the bytes would be handier.
    //
    // WHY IT ONLY LOGS TODAY. Flipping a tolerate into a reject on PEER credentials is what
    // `pop_strict` itself demanded device evidence for (6/6 PoP VERIFIED across three
    // lines and two Google Messages versions, plus a captured Apple leaf, before the default). Same
    // bar: count this on real Google-KDS and Apple peers first, then flip. Until then an expired
    // `.5` is announced and tolerated — which is still strictly more than the nothing we did
    // before, because it makes the case OBSERVABLE instead of invisible.
    //
    // AND WHAT IT WILL PROBABLY MEASURE, kept because it bounds the value rather than killing it:
    // on the one real KDS leaf we hold the `.5` expiry (1791149096) OUTLIVES the certificate's own
    // notAfter (1789766788) by 16.0 days, so the unconditional cert-expiry check above is strictly
    // tighter and this would never fire on a leaf of that shape. n = 1 is not a distribution; a
    // leaf whose `.5` expires BEFORE its notAfter is the case this catches, and we have zero
    // samples of one. That is an argument about frequency, not correctness.
    match acs_proof_verdict(p5_val, now) {
        AcsProofVerdict::Absent => ACS_PROOF_ABSENT_ONCE.call_once(|| {
            crate::ffi::alog!(
                "rcc16: .5 ACS proof ABSENT on a validated leaf (said ONCE per process). Expected \
                 for our own lab-minted leaves, which carry no .5 at all. If real peers also \
                 show this, a zero EXPIRED count in step 2 means WE NEVER SAW A PROOF, not \
                 that nothing expired — do not read it as evidence for arming the refusal.");
        }),
        AcsProofVerdict::Fresh { remaining } => crate::ffi::alog!(
            "rcc16: .5 ACS proof expiry OK ({} d left)", remaining / 86400),
        AcsProofVerdict::Undecodable(why) => crate::ffi::alog!(
            "rcc16: .5 ACS proof present but UNDECODABLE: {} — TOLERATED", why),
        AcsProofVerdict::Expired { since } => {
            if ACS_PROOF_EXPIRY_ENFORCED {
                return Err(acs_proof_expired_error(since));
            }
            crate::ffi::alog!(
                "rcc16: .5 ACS proof EXPIRED {} d ago — TOLERATED (step 2: counting on real \
                 peers before the refusal is armed; flip ACS_PROOF_EXPIRY_ENFORCED)", since / 86400);
        }
    }

    // .4 PoP verification.
    match verify_participant_pop(p4_inner, tbs, san) {
        Ok(()) => { crate::ffi::alog!("rcc16: .4 ParticipantInformation PoP VERIFIED"); Ok(()) }
        Err(e) => {
            if pop_strict { Err(e) } else {
                // lenient: a present-but-unexpected .4 structure (vendor variant) is tolerated
                // (the KDS already validated it); the standard A.4.1 checks above still gate.
                crate::ffi::alog!("rcc16: .4 PoP tolerated (lenient): {}", e.0);
                Ok(())
            }
        }
    }
}

/// Element [1] of the `.4` SEQUENCE — the validity window — without reconstructing the PoP.
///
/// Split out so the expiry check can run OUTSIDE the lenient PoP gate: an expired binding must fail
/// even on a path where an unpinned tbs layout means we tolerate a signature we cannot reproduce.
fn participant_validity_tlv(p4: &[u8]) -> Option<&[u8]> {
    let mut pos = 0;
    let seq = read_tlv(p4, &mut pos).filter(|t| t.tag == 0x30)?;
    let mut ip = 0;
    let _serial = read_tlv(seq.val, &mut ip).filter(|t| t.tag == 0x02)?;
    read_tlv(seq.val, &mut ip).filter(|t| t.tag == 0x30).map(|t| t.val)
}

/// Anything present in `.4` AFTER the five members A.3.8.9 defines — `(byte count, first tag)`.
///
/// A.3.8.9 puts `participantKeyRolls [0] IMPLICIT OPTIONAL` there: a signature chain asserting
/// continuity across participant-key changes, which A.4.1.1(1c-ii) requires a verifier to CHECK.
/// We do not implement that check, so its presence must REFUSE rather than be ignored — silently
/// reading five elements and dropping the sixth accepts a key-change chain nobody verified, which
/// is the asymmetry this refusal exists for.
///
/// `None` for a `.4` that does not parse as the five-element shape at all: that is a different
/// defect and `verify_participant_pop` names it precisely, so reporting it as trailing data here
/// would replace a exact error with a vaguer one.
///
/// Byte-decoded from `testdata/lab2_kds_leaf.der`: a real KDS leaf has EXACTLY five members
/// and no context-specific tag, so this costs nothing against real credentials.
fn participant_trailing_after_spki(p4: &[u8]) -> Option<(usize, u8)> {
    let mut pos = 0;
    let seq = read_tlv(p4, &mut pos).filter(|t| t.tag == 0x30)?;
    let inner = seq.val;
    let mut ip = 0;
    read_tlv(inner, &mut ip).filter(|t| t.tag == 0x02)?;   // [0] vendorId
    read_tlv(inner, &mut ip).filter(|t| t.tag == 0x30)?;   // [1] validity
    read_tlv(inner, &mut ip)?;                             // [2] sigalg
    read_tlv(inner, &mut ip).filter(|t| t.tag == 0x03)?;   // [3] signature
    read_tlv(inner, &mut ip).filter(|t| t.tag == 0x30)?;   // [4] participant SPKI
    if ip >= inner.len() { return None; }
    Some((inner.len() - ip, inner[ip]))
}

/// The refusal both callers of {@link participant_trailing_after_spki} emit, so the enforced guard
/// and its second reader cannot report the same fact in two different words.
fn trailing_after_spki_error(n: usize, tag: u8) -> Rcc16Error {
    Rcc16Error(format!(
        ".4 carries {} trailing byte(s) after the SPKI (first tag 0x{:02x}) — most likely \
         participantKeyRolls [0] IMPLICIT (A.3.8.9). We do NOT implement the A.4.1.1(1c-ii) \
         roll-chain verification, so this credential is REFUSED rather than accepted with an \
         unverified key-roll assertion",
        n, tag))
}

/// RCC.16 §7.12 `SignedEncryptionIdentityProof`, as the `.5` extension carries it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AcsProof {
    /// Seconds since the epoch after which the ACS's attestation is no longer good.
    pub expiry_seconds: u64,
    /// Length of the DER `Ecdsa-Sig-Value` that follows.
    pub signature_len: usize,
    /// How many bytes the MLS varint length prefix occupied: 1, 2 or 4.
    pub varint_len_bytes: usize,
}

/// Decode the `.5` extension's inner bytes. **Reads the expiry; verifies NOTHING.**
///
/// The layout, byte-decoded from `testdata/lab2_kds_leaf.der` and cross-confirmed
/// against a blob from a DIFFERENT issuer:
///
/// ```text
///   uint64 BE expiry_seconds  ||  MLS varint len  ||  DER ECDSA-Sig-Value
///   00000000 6ac2c428            4047 (= 71)         3045 0220 …          8 + 2 + 71 = 81
/// ```
///
/// # Why this is separable from the signature
///
/// Google Messages verifies the signature and we cannot: the verification key is reached through
/// an EXTERNAL KEY HANDLE nobody has traced, and we hold no ACS anchor. The EXPIRY needs no
/// key at all — it is the first eight bytes — and they enforce it as their own named failure
/// (`CertificateValidationAcsParticipantInformationSignatureExpired`, one of nine variants in a
/// validator disjoint from the `.4` family). So an expired `.5` is a rejection on their side
/// and, until this landed, a silent no-op on ours.
///
/// # The varint arms, and an honest statement of what is witnessed
///
/// RFC 9420 §2.1.2: the top two bits give the width — `00` 1 byte, `01` 2 bytes, `10` 4 bytes,
/// `11` reserved. Both blobs we hold use the **2-byte** arm. The 1-byte and 4-byte arms are
/// implemented here and exercised by synthetic tests, but **no real peer has produced one** and
/// nothing has been traced for them. That is a gap in the WITNESS, not in the code, and it is
/// recorded because a decoder that silently assumed the 2-byte arm would be wrong on the day it
/// met another — which is exactly the shape of defect this function exists to avoid.
///
/// Structurally strict on purpose: a length prefix that does not account for the remaining bytes
/// is REFUSED rather than read as far as it goes. A partially-read proof whose tail happened to
/// parse would present an expiry nobody signed.
pub fn parse_acs_proof(p5_inner: &[u8]) -> Result<AcsProof, Rcc16Error> {
    if p5_inner.len() < 9 {
        return Err(Rcc16Error(format!(
            "§7.12: .5 proof is {} B, too short for a u64 expiry plus a length prefix",
            p5_inner.len())));
    }
    let mut e = [0u8; 8];
    e.copy_from_slice(&p5_inner[0..8]);
    let expiry_seconds = u64::from_be_bytes(e);

    let rest = &p5_inner[8..];
    let b0 = rest[0];
    let (signature_len, varint_len_bytes) = match b0 >> 6 {
        0 => ((b0 & 0x3F) as usize, 1usize),
        1 => {
            if rest.len() < 2 {
                return Err(Rcc16Error("§7.12: .5 truncated 2-byte varint".into()));
            }
            (((((b0 & 0x3F) as usize) << 8) | rest[1] as usize), 2usize)
        }
        2 => {
            if rest.len() < 4 {
                return Err(Rcc16Error("§7.12: .5 truncated 4-byte varint".into()));
            }
            ((((b0 & 0x3F) as usize) << 24)
                | ((rest[1] as usize) << 16)
                | ((rest[2] as usize) << 8)
                | rest[3] as usize, 4usize)
        }
        _ => return Err(Rcc16Error(
            "§7.12: .5 signature length uses the RESERVED varint prefix 0b11".into())),
    };
    if rest.len() != varint_len_bytes + signature_len {
        return Err(Rcc16Error(format!(
            "§7.12: .5 says {} signature bytes after a {}-byte prefix, but {} remain — refusing \
             rather than reading a proof whose framing does not add up",
            signature_len, varint_len_bytes, rest.len().saturating_sub(varint_len_bytes))));
    }
    Ok(AcsProof { expiry_seconds, signature_len, varint_len_bytes })
}

/// What the `.5` ACS proof says about time.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AcsProofVerdict {
    /// No `.5` extension at all. NOT an error here: A.3.8.10 says it "shall be present", but we
    /// have never minted one and the enforcement of its presence is a separate decision from the
    /// enforcement of its expiry. Conflating them would make this change reject every leaf we
    /// have ever issued.
    Absent,
    /// Present, decodes, and has not expired. Carries seconds remaining.
    Fresh { remaining: u64 },
    /// Present, decodes, and expired. Carries seconds since expiry.
    Expired { since: u64 },
    /// Present and does not decode. Deliberately NOT reported as expired — different facts, and
    /// only one of them is about time. Folding them together would put a parse bug in the
    /// expiry's column the moment anyone counts these on real peers.
    Undecodable(String),
}

/// Whether an EXPIRED `.5` refuses the credential.
///
/// **A compile-time constant, deliberately not a sysprop, and that is the whole of the earlier
/// lesson.** That hole was not a missing check — it was a check that existed and that
/// `debug.rcs.mls_pop_lenient=1` silently disarmed, because it had been written inside
/// `verify_participant_pop` whose errors the lenient arm swallows. A runtime dial here would
/// re-create exactly that: an operator flipping a flag for an unrelated interop outage would turn
/// this off too, and nothing would say so.
///
/// It is `false` today because flipping a tolerate into a reject on PEER credentials is what
/// `pop_strict` itself demanded device evidence for (6/6 PoP VERIFIED across three
/// lines and two Google Messages versions, plus a captured Apple leaf, BEFORE the default moved).
/// The counting is step 2 and needs devices. **Both arms are tested** — see
/// `an_expired_proof_is_refused_when_enforcement_is_on` — so step 3 is this constant and nothing
/// else.
const ACS_PROOF_EXPIRY_ENFORCED: bool = false;

/// Says "we validated a leaf that carried no `.5`" exactly once per process.
///
/// **Without this, step 2's count is uninterpretable.** The plan is to count expired vs
/// fresh proofs on real peers and arm the refusal when nothing we interoperate with trips it. But
/// a fleet reading of ZERO expired proofs is ambiguous between two very different facts: peers
/// send `.5` and none of them is expired (the reading step 3 needs), or no peer sends one at all
/// and the count is zero for a reason that says nothing about expiry. That is the absent-vs-
/// negative confusion, and it is the reason a silent `Absent` arm was the wrong default.
///
/// Once per process, not per leaf: this fires on every validation of every one of OUR OWN
/// lab-minted leaves — which carry no `.5` by construction, since we moved keyroll out of the
/// slot and we mint nothing there — so per-leaf it would be pure noise drowning the lines that
/// matter.
static ACS_PROOF_ABSENT_ONCE: std::sync::Once = std::sync::Once::new();

/// Classify a leaf's `.5` against `now`. Pure: no clock, no globals, no levers.
///
/// Takes the RAW extension value (what `e.extn_value.as_bytes()` yields) and does the
/// OCTET-STRING unwrap itself, so a caller cannot get that half right and this half wrong.
pub fn acs_proof_verdict(p5_raw: Option<&[u8]>, now: u64) -> AcsProofVerdict {
    let raw = match p5_raw {
        None => return AcsProofVerdict::Absent,
        Some(r) => r,
    };
    let inner = unwrap_octet_string(raw).unwrap_or(raw);
    match parse_acs_proof(inner) {
        Err(e) => AcsProofVerdict::Undecodable(e.0),
        Ok(p) if now >= p.expiry_seconds =>
            AcsProofVerdict::Expired { since: now - p.expiry_seconds },
        Ok(p) => AcsProofVerdict::Fresh { remaining: p.expiry_seconds - now },
    }
}

/// The refusal an expired `.5` produces once `ACS_PROOF_EXPIRY_ENFORCED` is on.
///
/// Separate from the decision so the ERROR TEXT is pinned by a test today, rather than being
/// written for the first time on the day somebody flips the constant under pressure.
fn acs_proof_expired_error(since: u64) -> Rcc16Error {
    Rcc16Error(format!(
        "§7.12: the ACS SignedEncryptionIdentityProof (.5) EXPIRED {} d ago. The ACS's attestation \
         of this participant's identity is no longer current; Google Messages refuses this as \
         CertificateValidationAcsParticipantInformationSignatureExpired", since / 86400))
}

/// The `.4` ParticipantInformation validity window: `SEQUENCE { notBefore Time, notAfter Time }`,
/// the same shape as a certificate's, checked against the same trusted clock.
fn check_participant_validity(validity_seq: &[u8], now: u64) -> Result<(), Rcc16Error> {
    let mut p = 0;
    let nb = read_tlv(validity_seq, &mut p)
        .ok_or_else(|| Rcc16Error("A.3.8.9: .4 validity missing notBefore".into()))?;
    let na = read_tlv(validity_seq, &mut p)
        .ok_or_else(|| Rcc16Error("A.3.8.9: .4 validity missing notAfter".into()))?;
    let nb_s = der_time_to_unix(nb.tag, nb.val)
        .ok_or_else(|| Rcc16Error("A.3.8.9: .4 notBefore unparseable".into()))?;
    let na_s = der_time_to_unix(na.tag, na.val)
        .ok_or_else(|| Rcc16Error("A.3.8.9: .4 notAfter unparseable".into()))?;
    if now < nb_s {
        return Err(Rcc16Error(format!(
            "A.3.8.9: .4 ParticipantInformation not yet valid ({} s early)", nb_s - now)));
    }
    if now > na_s {
        return Err(Rcc16Error(format!(
            "A.3.8.9: .4 ParticipantInformation EXPIRED {} d ago", (now - na_s) / 86400)));
    }
    Ok(())
}

/// DER `UTCTime` (tag 0x17, `YYMMDDHHMMSSZ`) or `GeneralizedTime` (tag 0x18, `YYYYMMDDHHMMSSZ`).
/// RFC 5280 pins the two-digit year: 50..99 is 19xx, 00..49 is 20xx.
fn der_time_to_unix(tag: u8, v: &[u8]) -> Option<u64> {
    let s = core::str::from_utf8(v).ok()?;
    let (year, rest) = match tag {
        0x17 if s.len() >= 13 => {
            let yy: i64 = s[0..2].parse().ok()?;
            (if yy >= 50 { 1900 + yy } else { 2000 + yy }, &s[2..])
        }
        0x18 if s.len() >= 15 => (s[0..4].parse().ok()?, &s[4..]),
        _ => return None,
    };
    let mo: i64 = rest[0..2].parse().ok()?;
    let d: i64 = rest[2..4].parse().ok()?;
    let h: i64 = rest[4..6].parse().ok()?;
    let mi: i64 = rest[6..8].parse().ok()?;
    let sec: i64 = rest[8..10].parse().ok()?;
    // Days from the civil epoch (Howard Hinnant's algorithm) — no chrono dependency for this.
    let y = if mo <= 2 { year - 1 } else { year };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let doy = (153 * (if mo > 2 { mo - 3 } else { mo + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    u64::try_from(days * 86400 + h * 3600 + mi * 60 + sec).ok()
}

const OID_BASIC_CONSTRAINTS: &str = "2.5.29.19";
/// A.2.5: an intermediate CA's validity period may not exceed 1827 days (~5 years).
const CA_MAX_LIFETIME_S: u64 = 1827 * 24 * 3600;
/// A.1.5: a ROOT's validity may reach 3652 days (~10 years) — twice the intermediate allowance.
const ROOT_MAX_LIFETIME_S: u64 = 3652 * 24 * 3600;

/// RCC.16 CA profile, applied to every issuer above the leaf — **A.2** for an intermediate,
/// **A.1** for a self-signed root (the two differ on the validity limit; see below).
///
/// <b>Why this exists.</b> mls-rs's `X509Validator` builds and verifies the path, but nothing checked
/// that the certificates in it are permitted to BE certificate authorities under the RCS-MLS profile.
/// A root-signed end-entity could therefore act as an issuer and mint leaves for arbitrary MSISDNs,
/// and because RCC.16 leaf certs are deliberately non-revocable there is no recovery once one exists.
///
/// <b>The EKU rule is evidence-based, not spec-literal.</b> A.2.8 lists the permitted extensions and
/// EKU is not among them ("any other extension not defined herein should not be included"), so a
/// strict reading forbids it. Google's real chain carries it on BOTH CAs — verified 2026-07-30 from a
/// claimed peer KeyPackage off a live device:
///
/// ```text
/// kds-client-ca-us-east1-20250701    CA:TRUE pathlen:0  KU=certSign,cRLSign  EKU=2.23.146.2.1.3   730d
/// kds-ica-prod-us-central1-20250622  CA:TRUE pathlen:1  KU=certSign,cRLSign  EKU=2.23.146.2.1.3  1825d
/// ```
///
/// So requiring EKU-absence would reject every deployed peer. We require instead that an EKU, *if
/// present*, is exactly `id-kp-rcsMlsClient` — which is the meaningful half (a CA scoped to some
/// other purpose has no business issuing RCS-MLS leaves) and matches deployed reality.
///
/// The 1825-day ICA against the 1827-day bound is worth noticing: Google is plainly targeting the
/// same limit, which is good evidence they enforce it in the other direction too.
fn validate_ca_rcc16(ca_der: &[u8]) -> Result<(), Rcc16Error> {
    let cert = Certificate::from_der(ca_der)
        .map_err(|e| Rcc16Error(format!("CA parse: {e}")))?;
    let tbs = &cert.tbs_certificate;
    let exts = tbs.extensions.as_ref()
        .ok_or_else(|| Rcc16Error("A.2.8: CA has no extensions".into()))?;

    let mut bc: Option<(&[u8], bool)> = None;
    let mut ku: Option<(&[u8], bool)> = None;
    let mut eku: Option<&[u8]> = None;
    for e in exts.iter() {
        match e.extn_id.to_string().as_str() {
            OID_BASIC_CONSTRAINTS => bc = Some((e.extn_value.as_bytes(), e.critical)),
            OID_KEY_USAGE => ku = Some((e.extn_value.as_bytes(), e.critical)),
            OID_EKU => eku = Some(e.extn_value.as_bytes()),
            _ => {}
        }
    }

    // A.2.8.5 — BasicConstraints present, CRITICAL, cA = TRUE.
    let (bc_val, bc_crit) = bc.ok_or_else(|| Rcc16Error(
        "A.2.8.5: no BasicConstraints — this certificate is not a CA and must not issue".into()))?;
    if !bc_crit {
        return Err(Rcc16Error("A.2.8.5: BasicConstraints must be CRITICAL".into()));
    }
    if !basic_constraints_is_ca(bc_val) {
        return Err(Rcc16Error("A.2.8.5: cA is not TRUE — not permitted to issue certificates".into()));
    }

    // A.2.8.3 — KeyUsage present, CRITICAL, keyCertSign set and no bits beyond keyCertSign/cRLSign.
    let (ku_val, ku_crit) = ku.ok_or_else(|| Rcc16Error("A.2.8.3: no KeyUsage".into()))?;
    if !ku_crit {
        return Err(Rcc16Error("A.2.8.3: KeyUsage must be CRITICAL".into()));
    }
    let bits = key_usage_bits(ku_val).ok_or_else(|| Rcc16Error("A.2.8.3: malformed KeyUsage".into()))?;
    // RFC 5280 bit order, MSB first in the first octet: 5 = keyCertSign, 6 = cRLSign.
    const KEY_CERT_SIGN: u16 = 1 << (15 - 5);
    const CRL_SIGN: u16 = 1 << (15 - 6);
    if bits & KEY_CERT_SIGN == 0 {
        return Err(Rcc16Error("A.2.8.3: keyCertSign not asserted — cannot sign certificates".into()));
    }
    if bits & !(KEY_CERT_SIGN | CRL_SIGN) != 0 {
        return Err(Rcc16Error(format!(
            "A.2.8.3: KeyUsage {bits:#06x} sets bits beyond keyCertSign/cRLSign")));
    }

    // A.3.8.7 applied to the CA: an EKU is optional here, but a CA scoped to some OTHER purpose has
    // no business issuing RCS-MLS leaves. See the doc comment for why absence is tolerated.
    if let Some(e) = eku {
        if !eku_is_exactly(e, OID_EKU_RCSMLS) {
            return Err(Rcc16Error(
                "A.2: CA EKU present but not exactly id-kp-rcsMlsClient (2.23.146.2.1.3)".into()));
        }
    }

    // VALIDITY — and the limit depends on WHICH profile applies.
    //
    // A ROOT is self-signed (issuer == subject) and gets A.1.5's 3652 days; an INTERMEDIATE gets
    // A.2.5's 1827. Applying the intermediate limit to everything above the leaf rejects every real
    // root: the live trusted-root list carries Google's kds-root-ca at 3651 days, Android MLS CA - G1
    // at 3650 and Apple's RCS Signing ECC Root CA - G1 at 3650. Whether a root reaches here at all
    // depends on the sender — Google's KeyPackage presents leaf + client-CA + ICA and stops — but a
    // peer that includes its root is not doing anything wrong, and rejecting it would break exactly
    // the cross-vendor interop the fetched root list exists to enable.
    //
    // Both roots and intermediates sit just under their respective bounds (3650/3652, 1825/1827),
    // which is decent evidence the issuers implement these limits deliberately.
    let nb = tbs.validity.not_before.to_unix_duration().as_secs();
    let na = tbs.validity.not_after.to_unix_duration().as_secs();
    let self_signed = tbs.issuer == tbs.subject;
    let (limit, days, sec) = if self_signed {
        (ROOT_MAX_LIFETIME_S, 3652, "A.1.5")
    } else {
        (CA_MAX_LIFETIME_S, 1827, "A.2.5")
    };
    let span = na.saturating_sub(nb);
    if span > limit {
        // Report the EXCESS in seconds, not just the day count. A certificate that is over by an
        // hour truncates to the same day number as the limit and reads as "1827 d > 1827 d", which
        // sends the reader looking for an off-by-one in the comparison that is not there — cost us
        // one build cycle on 2026-07-30.
        return Err(Rcc16Error(format!(
            "{sec}: {} lifetime {} d ({} s) exceeds {days} d by {} s",
            if self_signed { "root" } else { "intermediate CA" },
            span / 86400, span, span - limit)));
    }
    Ok(())
}

/// BasicConstraints ::= SEQUENCE { cA BOOLEAN DEFAULT FALSE, pathLenConstraint INTEGER OPTIONAL }
fn basic_constraints_is_ca(ext_der: &[u8]) -> bool {
    let mut p = 0;
    if let Some(seq) = read_tlv(ext_der, &mut p) {
        if seq.tag == 0x30 {
            let mut ip = 0;
            if let Some(b) = read_tlv(seq.val, &mut ip) {
                // cA is the first element when present; absent means DEFAULT FALSE.
                return b.tag == 0x01 && b.val.first().is_some_and(|v| *v != 0);
            }
        }
    }
    false
}

/// KeyUsage ::= BIT STRING. Returns the first two octets as a big-endian u16 (bit 0 = MSB), which
/// covers every bit RFC 5280 defines.
fn key_usage_bits(ext_der: &[u8]) -> Option<u16> {
    let mut p = 0;
    let bs = read_tlv(ext_der, &mut p)?;
    if bs.tag != 0x03 || bs.val.is_empty() { return None; }
    let body = &bs.val[1..];               // skip the unused-bits count
    let hi = *body.first()? as u16;
    let lo = body.get(1).copied().unwrap_or(0) as u16;
    Some((hi << 8) | lo)
}

/// Verify the .4 PoP: reconstruct A.3.8.9 `tbsParticipantInfo` = SEQ{ leafSubject, INT(vendorId),
/// participantSignatureValidity, **leaf subjectPublicKeyInfo**, leafSAN } and check
/// ECDSA-SHA256 over it with the PARTICIPANT key from `.4[4]`.
///
/// The two keys are not interchangeable and each appears exactly once, in a different role:
/// element 4 of the signed bytes is the CERTIFIED SUBJECT key (what the binding is *about*), and
/// the verifying key is the PARTICIPANT key (who is *asserting* it). That is the whole point of the
/// PoP — the participant key attests that the subject key is theirs.
///
/// RESOLVED 2026-08-07, and it was OUR bug, not theirs. This function previously put
/// `.4[4]` — the participant key — into element 4, so it verified a statement nobody ever made,
/// and every deployed certificate failed. With the leaf SPKI in element 4 the signature on a real
/// KDS-issued leaf verifies exactly (`testdata/lab2_kds_leaf.der`, see the test below).
///
/// WHY A STRICT TEST NEVER CAUGHT IT: our synthetic fixtures mint ONE key and use it as both the
/// participant key and the certified subject key, so `.4[4] == leaf SPKI` and the two candidate
/// layouts are byte-identical. The fixture could not discriminate the thing it was testing. The
/// real KDS leaf has two distinct keys, which is what makes it the only fixture with any power here
/// — keep it in the suite for that reason and do not "simplify" it to a synthetic one.
fn verify_participant_pop(p4: &[u8], tbs: &x509_cert::certificate::TbsCertificate, san_ext: &[u8])
        -> Result<(), Rcc16Error> {
    // .4 = SEQUENCE { INT serial, validity SEQ, SEQ{oid}, BITSTRING sig, SPKI }
    let mut pos = 0;
    let seq = read_tlv(p4, &mut pos).filter(|t| t.tag == 0x30).ok_or_else(|| Rcc16Error(".4 not a SEQUENCE".into()))?;
    let mut ip = 0; let inner = seq.val;
    let serial = read_tlv(inner, &mut ip).filter(|t| t.tag == 0x02).ok_or_else(|| Rcc16Error(".4[0] serial".into()))?;
    let validity = read_tlv(inner, &mut ip).filter(|t| t.tag == 0x30).ok_or_else(|| Rcc16Error(".4[1] validity".into()))?;
    let sigalg = read_tlv(inner, &mut ip).ok_or_else(|| Rcc16Error(".4[2] sigalg".into()))?;
    let sigbits = read_tlv(inner, &mut ip).filter(|t| t.tag == 0x03).ok_or_else(|| Rcc16Error(".4[3] sig".into()))?;
    let spki = read_tlv(inner, &mut ip).filter(|t| t.tag == 0x30).ok_or_else(|| Rcc16Error(".4[4] spki".into()))?;

    // FAIL CLOSED ON ANYTHING AFTER ELEMENT 5.
    //
    // A.3.8.9 defines participantKeyRolls as [0] IMPLICIT OPTIONAL *inside* .4, after the SPKI: a
    // signature chain asserting continuity across participant-key changes. A.4.1.1(1c-ii) requires a
    // verifier to CHECK it. We do not implement that check, and Google Messages verifies it — its TBS
    // assembler invokes the verification helper at two distinct call sites where we invoke one.
    //
    // Byte-decoded from testdata/lab2_kds_leaf.der: a real KDS leaf carries NO [0] element, .4
    // has exactly five members, so we have never RECEIVED a roll and cannot test a verifier against a
    // real one. That is why the verification half is fixture-blocked rather than unknown.
    //
    // But silently IGNORING a roll is the one option that is actually unsafe: it accepts a credential
    // asserting a key-change chain we did not verify, which is precisely the asymmetry to avoid —
    // passing a vendor's certificates is not evidence you are checking everything they check.
    // So we refuse instead. A credential that carries a roll is rejected until the check exists;
    // nothing in our own issuance emits one, so this costs nothing today and closes the gap.
    //
    // SECOND READER, NOT THE ENFORCED ONE. The guard that actually holds is in
    // `validate_leaf_rcc16`, OUTSIDE the lenient gate — every error this function returns is
    // swallowed when `pop_strict` is false, so a refusal expressed only here is disarmed by
    // `debug.rcs.mls_pop_lenient=1`. Kept so a direct caller of this function cannot be handed a
    // rolled `.4` either, and sharing one helper + one error string with the enforced copy so the
    // two cannot drift. Whoever finds this one must not conclude they have seen the whole guard.
    if let Some((n, tag)) = participant_trailing_after_spki(p4) {
        return Err(trailing_after_spki_error(n, tag));
    }

    // NOTE: the .4 participant key is a DISTINCT key from the leaf's certified key (Google mints a
    // separate PoP-bound MLS-participant key — a self-signed .4 whose whole purpose is to bind a key
    // OTHER than the cert key; if they were equal the cert's own signature would be the PoP). The old
    // "spki.full == leaf subjectPublicKeyInfo" equality was therefore a wrong invariant and is removed
    // — the PoP is proven below by verifying the .4 self-signature with the participant key itself.
    // VENDOR ID: use the one the certificate actually carries, do not hardcode Google's.
    //
    // This required serial == 2 and reconstructed the signed bytes with a literal der_int(2), so a
    // certificate from any other vendor failed twice over — rejected by the check, and unverifiable
    // even without it because the reconstruction would not match what was signed. Apple is the
    // concrete case: a peer we already interoperate with at the MLS layer could never have passed
    // the .4 PoP.
    //
    // Dropping the equality is not a weakening. The trust boundary is the CHAIN — a leaf reaches
    // here only by verifying to a configured trust anchor and satisfying the A.2/A.4.1 profiles —
    // and the .4's purpose is proving possession of the participant key, which the signature below
    // does with whatever vendor id the issuer used. Binding the vendor id to the anchor that signed
    // it is a further, real check, but it needs a definition of the anchor's vendor id we do not
    // have; filed rather than guessed.
    if serial.val.is_empty() {
        return Err(Rcc16Error(".4 vendorId missing".into()));
    }
    // Reconstruct tbsParticipantInfo. Elements 1(subject), 4(SPKI) and 5(SAN) come from the LEAF;
    // only the vendorId and the signature validity window come from the .4 itself.
    let subject_der = tbs.subject.to_der().map_err(|e| Rcc16Error(format!("subject: {e}")))?;
    // A.3.8.9 element 4 is `subjectPublicKeyInfo` — the CERTIFIED key, taken from the leaf. NOT
    // `.4[4]`, which is the participant key and is the key we VERIFY WITH, three lines below. Our
    // own CSR builder has always signed the leaf SPKI here (MlsCsr.buildTbsDer element 4), so the
    // old `spki.full` made this verifier disagree with our own issuer.
    let leaf_spki_der = tbs.subject_public_key_info.to_der()
        .map_err(|e| Rcc16Error(format!("leaf spki: {e}")))?;
    // san_ext extn_value is the GeneralNames SEQUENCE DER (element 5).
    let recon = der_seq(&[&subject_der, serial.full, validity.full, &leaf_spki_der, san_ext]);

    // ECDSA verify. THE HASH COMES FROM .4[2], IT IS NOT ASSUMED.
    //
    // Google Messages' algorithm gate accepts ecdsa-with-SHA256 AND ecdsa-with-SHA384 — both
    // branches converge and proceed, anything else is rejected (read across two code
    // paths). We used to hardcode SHA-256 and DISCARD this element,
    // which made us STRICTER than they are: a SHA-384-signed .4 would fail a signature they
    // honours. Strictness in a peer validator is not safety, it is an interop break that reads like
    // the peer is broken — the same mistake as the A.4.1 lifetime floors above.
    //
    // WE ACCEPT ALL FOUR SHA2 VARIANTS, WHICH IS A DELIBERATE SUPERSET — NOT A MATCH.
    // State the evidence precisely, because the two facts are easy to collapse and the collapse is
    // what a reader will otherwise inherit:
    //   Measured across two comparison paths: their gate opens for 02 and 03 and rejects
    //     everything else. That is a read of the CODE, and it is the fact about their client.
    //   NOT EVIDENCE: all four OIDs (SHA224/256/384/512) appear as constants in the binary. A
    //     constant is not a branch — grepping them would have over-claimed to all four, and that
    //     was flagged explicitly when the finding was reported.
    // So 01/04 are accepted here by OUR choice (2026-08-07), on the reasoning
    // that a validator refusing a well-formed signature it can check is an interop break, and the
    // profile is lenient today in any case. It widens what we ACCEPT from peers; it changes nothing
    // about what we EMIT, and our own issuer is P-256/SHA-256.
    //
    // THE COST, so it is a decision and not a drift: SHA-224 is a 112-bit-security hash and the
    // weakest thing this validator will now accept. If the Tachyon profile is ever flipped to strict,
    // revisit 01 specifically — accepting a weaker hash than the peer does on a credential check is the
    // one direction where "more permissive" is not obviously free.
    // FALSIFIER for the superset being harmless: any peer or CA observed signing a .4 with SHA-224.
    // We have never seen one; every leaf measured (Google, Apple, ours) is SHA-256.
    let alg_oid = read_tlv(sigalg.val, &mut 0).filter(|t| t.tag == 0x06)
        .ok_or_else(|| Rcc16Error(".4[2] sigalg has no OID".into()))?;
    // 1.2.840.10045.4.3.x = 2a 86 48 ce 3d 04 03 x
    const ECDSA_PREFIX: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03];
    if alg_oid.val.len() != ECDSA_PREFIX.len() + 1 || !alg_oid.val.starts_with(ECDSA_PREFIX) {
        return Err(Rcc16Error(format!(
            ".4 sigalg is not an ecdsa-with-SHA* OID: {:02x?}", alg_oid.val)));
    }
    let recon_digest: Vec<u8> = match alg_oid.val[ECDSA_PREFIX.len()] {
        0x01 => Sha224::digest(&recon).to_vec(),
        0x02 => Sha256::digest(&recon).to_vec(),
        0x03 => Sha384::digest(&recon).to_vec(),
        0x04 => Sha512::digest(&recon).to_vec(),
        other => return Err(Rcc16Error(format!(
            ".4 sigalg ecdsa-with-SHA variant {other:#04x} is not an SHA2 variant"))),
    };
    let point = sec1_point_from_spki(spki.full).ok_or_else(|| Rcc16Error(".4 spki not P-256 SEC1".into()))?;
    // NAME THE CHECK IN EVERY ERROR ON THIS PATH. These two used to read "vk: signature error" and
    // "sig der: ...", which say nothing about WHICH signature or which extension — and a fixture
    // that corrupted the participant key instead of the signature produced the first of them while
    // a test asserted it was a bad-PoP rejection. An error that cannot be told apart from a
    // different failure is how a test ends up passing for the wrong reason.
    let vk = VerifyingKey::from_sec1_bytes(&point)
        .map_err(|e| Rcc16Error(format!(".4 ParticipantInformation PoP: participant key is not a \
                                         valid P-256 point: {e}")))?;
    // sig BIT STRING: skip the unused-bits octet.
    let sig_der = &sigbits.val[1..];
    let sig = Signature::from_der(sig_der)
        .map_err(|e| Rcc16Error(format!(".4 ParticipantInformation PoP: signature is not a valid \
                                         ECDSA-Sig-Value: {e}")))?;
    // verify_prehash takes the digest and truncates to the field size itself (bits2field), which is
    // what makes SHA-384-over-P-256 work: leftmost 32 bytes, per FIPS 186-4.
    let digest = &recon_digest[..];
    vk.verify_prehash(digest, &sig)
        .map_err(|_| Rcc16Error(".4 ParticipantInformation PoP signature INVALID".into()))?;
    Ok(())
}

// --- small ASN.1 helpers ---
fn unwrap_octet_string(b: &[u8]) -> Option<&[u8]> {
    let mut p = 0; let t = read_tlv(b, &mut p)?; if t.tag == 0x04 { Some(t.val) } else { None }
}
fn eku_is_exactly(ext_der: &[u8], oid: &str) -> bool {
    // ext_der = SEQUENCE OF OID (EKU). A.4.1.1 exclusivity: must be EXACTLY one OID == `oid`. Reject
    // if there are zero, more than one, a mismatched OID, or any non-OID element in the sequence.
    let want = oid_to_der(oid);
    let mut p = 0;
    if let Some(seq) = read_tlv(ext_der, &mut p) {
        if seq.tag == 0x30 {
            let mut ip = 0; let mut count = 0usize; let mut matched = false;
            while let Some(o) = read_tlv(seq.val, &mut ip) {
                if o.tag != 0x06 { return false; }  // only OIDs are legal inside an EKU sequence
                count += 1;
                matched = o.val == want.as_slice();
            }
            return count == 1 && matched;
        }
    }
    false
}
fn oid_to_der(oid: &str) -> Vec<u8> {
    let parts: Vec<u64> = oid.split('.').map(|x| x.parse().unwrap()).collect();
    let mut body = vec![(40 * parts[0] + parts[1]) as u8];
    for &p in &parts[2..] {
        if p < 0x80 { body.push(p as u8); } else {
            let mut s = Vec::new(); let mut v = p;
            while v > 0 { s.insert(0, (v & 0x7f) as u8); v >>= 7; }
            for i in 0..s.len() - 1 { s[i] |= 0x80; } body.extend_from_slice(&s);
        }
    }
    body
}
fn key_usage_has_digital_signature(ext_der: &[u8]) -> bool {
    // KeyUsage = BIT STRING; bit 0 (MSB of first content octet) = digitalSignature.
    let mut p = 0;
    if let Some(bs) = read_tlv(ext_der, &mut p) {
        if bs.tag == 0x03 && bs.val.len() >= 2 { return bs.val[1] & 0x80 != 0; }
    }
    false
}
fn san_has_tel_uri(ext_der: &[u8]) -> bool {
    // SAN = SEQUENCE OF GeneralName; [6] IA5String uniformResourceIdentifier.
    let mut p = 0;
    if let Some(seq) = read_tlv(ext_der, &mut p) {
        if seq.tag == 0x30 {
            let mut ip = 0;
            while let Some(gn) = read_tlv(seq.val, &mut ip) {
                if gn.tag == 0x86 && gn.val.starts_with(b"tel:") { return true; }
            }
        }
    }
    false
}
fn san_tel_uri_equals(ext_der: &[u8], expected: &str) -> bool {
    // A.4.1 identity equality: SAN = SEQUENCE OF GeneralName; [6] IA5String URI. The tel: URI must
    // EQUAL the queried MSISDN (E.164-normalized). Any tel: URI carrying a different number fails.
    let want = normalize_e164(expected.as_bytes());
    if want.is_empty() { return false; }
    let mut p = 0;
    if let Some(seq) = read_tlv(ext_der, &mut p) {
        if seq.tag == 0x30 {
            let mut ip = 0;
            while let Some(gn) = read_tlv(seq.val, &mut ip) {
                if gn.tag == 0x86 && gn.val.starts_with(b"tel:") && normalize_e164(gn.val) == want {
                    return true;
                }
            }
        }
    }
    false
}
/// The leaf's SAN `tel:` identity, normalized to bare E.164 digits — the number the certificate
/// asserts. `None` if the leaf will not parse or carries no `tel:` URI.
///
/// This exists so the A.4.1 identity-equality check can be applied where the *queried* MSISDN is
/// actually known. See the note at the `expected_msisdn` construction site in `ffi.rs`: the validator
/// runs on every credential including every peer's, so a single session-wide expectation cannot
/// express "this cert must be for the number I asked the KDS for".
pub fn leaf_san_msisdn(leaf_der: &[u8]) -> Option<Vec<u8>> {
    let cert = Certificate::from_der(leaf_der).ok()?;
    let exts = cert.tbs_certificate.extensions.as_ref()?;
    let san = exts.iter().find(|e| e.extn_id.to_string() == OID_SAN)?;
    let der = san.extn_value.as_bytes();
    let mut p = 0;
    let seq = read_tlv(der, &mut p)?;
    if seq.tag != 0x30 { return None; }
    let mut ip = 0;
    while let Some(gn) = read_tlv(seq.val, &mut ip) {
        if gn.tag == 0x86 && gn.val.starts_with(b"tel:") {
            let n = normalize_e164(gn.val);
            if !n.is_empty() { return Some(n); }
        }
    }
    None
}

/// Do two MSISDN-ish strings denote the same number? E.164-normalized, so `+1-555-111-0001`,
/// `tel:+15551110001` and `15551110001` all compare equal.
/// The leaf's PARTICIPANT KEY, as the raw `.4[4]` SubjectPublicKeyInfo DER.
///
/// RCC.16 A.3.8 puts the participant key in the ParticipantInformation extension (2.23.146.2.1.4)
/// as element 4 of `SEQUENCE { serial, validity, sigalg, sig, SPKI }` — a key DISTINCT from the
/// leaf's own certified key, minted per participant and PoP-bound to the leaf. That makes it
/// exactly the "which participant key signed this leaf" identity `MlsParticipantKeyResync` needs to
/// tell a client on the CURRENT key from one on a superseded key.
///
/// Returns the SPKI bytes, not a hash: the caller decides how to identify it, and hashing here
/// would bake in a choice the comparison has to agree with. `None` if the leaf will not parse, has
/// no `.4`, or `.4` is not the shape above — and `None` MUST NOT be read as "stale", which is why
/// `plan()` refuses to remove a leaf whose signing key it could not read.
pub fn leaf_participant_key_spki(leaf_der: &[u8]) -> Option<Vec<u8>> {
    use x509_cert::Certificate;
    use x509_cert::der::Decode;
    let cert = Certificate::from_der(leaf_der).ok()?;
    let exts = cert.tbs_certificate.extensions.as_ref()?;
    let e = exts.iter().find(|e| e.extn_id.as_bytes() == OID_PARTICIPANT_INFO)?;
    let raw = e.extn_value.as_bytes();
    let p4 = unwrap_octet_string(raw).unwrap_or(raw);
    let mut pos = 0;
    let seq = read_tlv(p4, &mut pos).filter(|t| t.tag == 0x30)?;
    let inner = seq.val;
    let mut ip = 0;
    read_tlv(inner, &mut ip).filter(|t| t.tag == 0x02)?;   // serial
    read_tlv(inner, &mut ip).filter(|t| t.tag == 0x30)?;   // validity
    read_tlv(inner, &mut ip)?;                             // sigalg
    read_tlv(inner, &mut ip).filter(|t| t.tag == 0x03)?;   // sig
    let spki = read_tlv(inner, &mut ip).filter(|t| t.tag == 0x30)?;
    // Re-encode the whole SPKI TLV, not just its value: two keys differing only in algorithm
    // parameters would otherwise collide.
    let mut full = Vec::with_capacity(spki.val.len() + 8);
    full.push(0x30);
    encode_der_len(&mut full, spki.val.len());
    full.extend_from_slice(spki.val);
    Some(full)
}

/// Minimal DER length encoder for re-wrapping a parsed TLV.
fn encode_der_len(out: &mut Vec<u8>, len: usize) {
    if len < 0x80 {
        out.push(len as u8);
    } else {
        let mut b = Vec::new();
        let mut n = len;
        while n > 0 { b.push((n & 0xFF) as u8); n >>= 8; }
        b.reverse();
        out.push(0x80 | (b.len() as u8));
        out.extend_from_slice(&b);
    }
}

pub fn msisdn_equals(a: &[u8], b: &[u8]) -> bool {
    let (a, b) = (normalize_e164(a), normalize_e164(b));
    !a.is_empty() && a == b
}

/// Normalize a `tel:` URI or a raw MSISDN to bare E.164 digits for identity comparison: strip a
/// leading `tel:` scheme, cut any tel-URI parameters (`;phone-context=...`), and drop visual
/// separators (`+`, `-`, `.`, spaces, parens). ASCII digits only.
fn normalize_e164(s: &[u8]) -> Vec<u8> {
    let s = s.strip_prefix(b"tel:").unwrap_or(s);
    let mut out = Vec::new();
    for &b in s {
        match b {
            b';' => break,             // tel-URI parameters follow the number part
            b'0'..=b'9' => out.push(b),
            _ => {}                    // '+', '-', '.', ' ', '(', ')' are non-significant
        }
    }
    out
}
/// Trusted wall-clock fallback (seconds since epoch) for the A.4.1 remaining-lifetime floor when
/// mls-rs hands us `ts=None`. Used to FAIL CLOSED rather than skip the >=30-day check.
fn trusted_now_secs() -> Option<u64> {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .ok()
        .map(|d| d.as_secs())
}
fn sec1_point_from_spki(spki_der: &[u8]) -> Option<Vec<u8>> {
    // SPKI = SEQ{ AlgId, BIT STRING pubkey }. Return the 65-byte SEC1 point.
    let mut p = 0; let seq = read_tlv(spki_der, &mut p)?; if seq.tag != 0x30 { return None; }
    let mut ip = 0; let _alg = read_tlv(seq.val, &mut ip)?; let bs = read_tlv(seq.val, &mut ip)?;
    if bs.tag == 0x03 && bs.val.len() == 66 && bs.val[1] == 0x04 { Some(bs.val[1..].to_vec()) } else { None }
}


/// 2026-08-15 — inside the lab2 window (2026-08-08 → 2026-10-07) with 53 days remaining, so the
/// A.4.1 ">= 30 days remaining" floor is satisfied at a FIXED instant. Pinned rather than
/// wall-clock deliberately: a 76-day-capped profile means any fixture expires, and a suite that
/// reads the clock would go red on a date nobody chose.
///
/// **This is the ONE definition.** It is `pub(crate)` because `ffi.rs` must pin to the SAME instant:
/// the validator tests can pass an explicit `MlsTime`, but the ffi tests drive whole engine
/// operations that reach the validator many frames down, so they pin at the identity-provider seam
/// instead (see `X509WithBasicCreds::validate_member`). Two constants would let the two halves of the
/// suite drift apart silently, which is worse than either being wrong.
///
/// RE-MINTING THE FIXTURES MEANS RE-PINNING THIS — see `testdata/README.md`.
#[cfg(test)]
pub(crate) const LAB2_NOW: u64 = 1786752000;

#[cfg(test)]
mod tests {
    use super::*;
    use mls_rs_identity_x509::DerCertificate;
    fn td(f: &str) -> Vec<u8> { std::fs::read(format!("testdata/{f}")).unwrap() }

    /// The CURRENT lab chain (`lab2_*`), minted 2026-08-08 by `MlsCredential` with the full
    /// §14.2.3 leaf profile — SKI and one `certificatePolicies` included. Its CA private keys are
    /// committed (`lab2_root_priv.p8`, `lab2_ica_priv.p8`), which is the whole point: the previous
    /// chain could not be re-issued by anyone, so two profile rules had to be relaxed around it.
    fn chain(leaf: &str) -> CertificateChain {
        CertificateChain::from(vec![DerCertificate::from(td(leaf)),
                                    DerCertificate::from(td("lab2_ica.der"))])
    }
    fn validator() -> Rcc16Validator {
        Rcc16Validator::new(
            X509Validator::new(vec![DerCertificate::from(td("lab2_root.der"))]).unwrap())
    }
    /// The RETIRED lab chain. Kept for the fixtures that are deliberately non-conformant in ways
    /// unrelated to the profile rules above (`leaf_a`, `leaf_b`) — re-minting those would mean
    /// teaching the minter to emit broken certificates, which is a worse trade than keeping the
    /// chain that already produced them.
    fn chain_v1(leaf: &str) -> CertificateChain {
        CertificateChain::from(vec![DerCertificate::from(td(leaf)),
                                    DerCertificate::from(td("ica.der"))])
    }
    fn validator_v1() -> Rcc16Validator {
        Rcc16Validator::new(X509Validator::new(vec![DerCertificate::from(td("root.der"))]).unwrap())
    }
    use crate::rcc16_validate::LAB2_NOW;

    // ---- .5 ACS proof expiry -----------------------------------------------------------------

    /// Pull the `.5` extension's inner bytes out of a leaf, the way `validate_leaf_rcc16` does.
    fn p5_inner_of(leaf: &[u8]) -> Vec<u8> {
        use x509_cert::Certificate;
        use x509_cert::der::Decode;
        let cert = Certificate::from_der(leaf).unwrap();
        let exts = cert.tbs_certificate.extensions.as_ref().unwrap();
        let e = exts.iter()
            .find(|e| e.extn_id.as_bytes() == OID_ACS_PARTICIPANT_INFO)
            .expect("a real KDS leaf carries a .5");
        let raw = e.extn_value.as_bytes();
        unwrap_octet_string(raw).unwrap_or(raw).to_vec()
    }

    /// THE PIN: a real KDS leaf's own `.5`, decoded to the byte.
    ///
    /// Every number here was read off `testdata/lab2_kds_leaf.der` and is spelled out rather
    /// than recomputed, so this fails if the decoder changes even "consistently".
    #[test]
    fn acs_proof_decodes_to_its_documented_bytes() {
        let inner = p5_inner_of(&td("lab2_kds_leaf.der"));
        assert_eq!(inner.len(), 81, "8 B expiry + 2 B varint + 71 B signature");
        assert_eq!(&inner[0..8], &[0x00, 0x00, 0x00, 0x00, 0x6a, 0xc2, 0xc4, 0x28]);
        assert_eq!(&inner[8..10], &[0x40, 0x47]);
        let p = parse_acs_proof(&inner).expect("a real .5 must decode");
        assert_eq!(p.expiry_seconds, 1791149096, "2026-10-04T21:24:56Z");
        assert_eq!(p.signature_len, 71);
        assert_eq!(p.varint_len_bytes, 2);
        // The signature really is a DER SEQUENCE of two INTEGERs, which is what makes the length
        // a signature length rather than a coincidence.
        assert_eq!(inner[10], 0x30);
        assert_eq!(inner[11] as usize, 71 - 2);
    }

    /// THE MEASUREMENT THAT BOUNDS THE VALUE, pinned so nobody has to re-derive it: on this leaf
    /// the proof OUTLIVES the certificate, so the unconditional cert-expiry check is strictly
    /// tighter and an expiry refusal would never fire here. Recorded as a fact about our one
    /// sample, not as a reason to skip the check.
    #[test]
    fn acs_proof_outlives_its_own_certificate() {
        use x509_cert::Certificate;
        use x509_cert::der::Decode;
        let leaf = td("lab2_kds_leaf.der");
        let p = parse_acs_proof(&p5_inner_of(&leaf)).unwrap();
        let cert = Certificate::from_der(&leaf).unwrap();
        let na = cert.tbs_certificate.validity.not_after.to_unix_duration().as_secs();
        assert_eq!(na, 1789766788);
        assert!(p.expiry_seconds > na);
        // Asserted in SECONDS, not days. The quoted "16.0 days" is the correctly
        // rounded figure — 1382308 s is 15.9989 d — but integer division by 86400 floors it to
        // 15, so a `== 16` day assertion fails against the very number it was derived from. Pinned
        // exactly here so the next reader does not re-discover that the round number is a round
        // number.
        assert_eq!(p.expiry_seconds - na, 1_382_308, "15.9989 d, i.e. the bead's 16.0 d rounded");
        assert_eq!((p.expiry_seconds - na) / 86400, 15, "integer-division days FLOOR to 15");
    }

    /// All three varint arms round-trip. The 1- and 4-byte arms are SYNTHETIC — no real peer has
    /// produced one and nothing has been traced for them — so this exercises the CODE while the
    /// witness gap stays recorded on `parse_acs_proof`.
    #[test]
    fn acs_proof_decodes_all_three_varint_arms() {
        // 1-byte arm: prefix 00, len 5.
        let mut one = vec![0u8; 8];
        one.extend_from_slice(&[0x05, 1, 2, 3, 4, 5]);
        let p = parse_acs_proof(&one).unwrap();
        assert_eq!((p.signature_len, p.varint_len_bytes), (5, 1));

        // 2-byte arm: prefix 01, len 71 — the real leaf's shape.
        let mut two = vec![0u8; 8];
        two.extend_from_slice(&[0x40, 0x47]);
        two.extend_from_slice(&[0u8; 71]);
        let p = parse_acs_proof(&two).unwrap();
        assert_eq!((p.signature_len, p.varint_len_bytes), (71, 2));

        // 4-byte arm: prefix 10, len 100.
        let mut four = vec![0u8; 8];
        four.extend_from_slice(&[0x80, 0x00, 0x00, 0x64]);
        four.extend_from_slice(&[0u8; 100]);
        let p = parse_acs_proof(&four).unwrap();
        assert_eq!((p.signature_len, p.varint_len_bytes), (100, 4));
    }

    /// The expiry is read as a u64 BIG-endian, and a little-endian read would not merely differ —
    /// it would put a real proof about 5.4 billion years out. Pinned because a wrong-endian
    /// read produces a date that always looks valid, so it can never fail a naive "is it in the
    /// future" test.
    #[test]
    fn acs_proof_expiry_is_big_endian() {
        let inner = p5_inner_of(&td("lab2_kds_leaf.der"));
        let le = u64::from_le_bytes(inner[0..8].try_into().unwrap());
        let p = parse_acs_proof(&inner).unwrap();
        assert_eq!(p.expiry_seconds, 1791149096);
        assert_ne!(p.expiry_seconds, le);
        assert!(le > 1_000_000_000_000_000_000, "the LE reading is absurd, and always 'valid'");
    }

    /// Framing that does not add up is REFUSED, not read as far as it goes. A partially-read proof
    /// whose tail happened to parse would present an expiry nobody signed.
    #[test]
    fn acs_proof_refuses_framing_that_does_not_add_up() {
        let mut short = vec![0u8; 8];
        short.extend_from_slice(&[0x40, 0x47]);
        short.extend_from_slice(&[0u8; 70]);               // one byte shy of 71
        assert!(parse_acs_proof(&short).is_err());

        let mut long = vec![0u8; 8];
        long.extend_from_slice(&[0x40, 0x47]);
        long.extend_from_slice(&[0u8; 72]);                // one byte over
        assert!(parse_acs_proof(&long).is_err());

        assert!(parse_acs_proof(&[0u8; 8]).is_err(), "no length prefix at all");
        assert!(parse_acs_proof(&[0u8; 4]).is_err(), "shorter than the expiry itself");

        let mut reserved = vec![0u8; 8];
        reserved.extend_from_slice(&[0xC0, 0x00]);         // prefix 0b11 is reserved
        assert!(parse_acs_proof(&reserved).is_err());
    }

    /// The four verdicts, against a real `.5`.
    ///
    /// `now` is chosen either side of the pinned expiry (1791149096), so both time arms are
    /// exercised on REAL bytes rather than on a synthetic blob.
    #[test]
    fn acs_proof_verdict_classifies_a_real_extension() {
        use x509_cert::Certificate;
        use x509_cert::der::Decode;
        let leaf = td("lab2_kds_leaf.der");
        let cert = Certificate::from_der(&leaf).unwrap();
        let exts = cert.tbs_certificate.extensions.as_ref().unwrap();
        let raw = exts.iter()
            .find(|e| e.extn_id.as_bytes() == OID_ACS_PARTICIPANT_INFO)
            .unwrap().extn_value.as_bytes();

        assert_eq!(acs_proof_verdict(Some(raw), 1791149095),
                   AcsProofVerdict::Fresh { remaining: 1 }, "one second before expiry");
        assert_eq!(acs_proof_verdict(Some(raw), 1791149096),
                   AcsProofVerdict::Expired { since: 0 },
                   "AT the expiry second is EXPIRED — `now >= expiry`, not `>`");
        assert_eq!(acs_proof_verdict(Some(raw), 1791149096 + 86400),
                   AcsProofVerdict::Expired { since: 86400 });
        assert_eq!(acs_proof_verdict(None, 0), AcsProofVerdict::Absent);
        match acs_proof_verdict(Some(&[0x04, 0x02, 0x00, 0x00]), 0) {
            AcsProofVerdict::Undecodable(_) => {}
            other => panic!("a 2-byte proof must be Undecodable, got {other:?}"),
        }
    }

    /// AN ABSENT `.5` IS NOT AN ERROR HERE, and that is a deliberate separation.
    ///
    /// A.3.8.10 says the extension "shall be present", but we have never minted one — every leaf
    /// our own lab PKI issues omits it (our half moved keyroll out of the slot and
    /// mints no `.5` at all). Enforcing PRESENCE and enforcing EXPIRY are different decisions, and
    /// folding them together would make this change reject every credential we have ever issued.
    #[test]
    fn an_absent_proof_is_absent_not_expired() {
        assert_eq!(acs_proof_verdict(None, u64::MAX), AcsProofVerdict::Absent);
    }

    /// BOTH ARMS OF THE ENFORCEMENT SWITCH, so step 3 is the constant and nothing else.
    ///
    /// The refusal cannot be reached through `validate_leaf_rcc16` while the constant is false, so
    /// it is tested at the two pieces it is made of: the verdict (which fires) and the error text
    /// (which is pinned now rather than written for the first time on the day somebody flips the
    /// constant under pressure).
    #[test]
    fn an_expired_proof_is_refused_when_enforcement_is_on() {
        // The decision half: an expired proof really does classify as Expired.
        let mut blob = vec![0u8; 8];
        blob[0..8].copy_from_slice(&1000u64.to_be_bytes());
        blob.extend_from_slice(&[0x40, 0x47]);
        blob.extend_from_slice(&[0u8; 71]);
        let wrapped = {
            let mut w = vec![0x04, blob.len() as u8];
            w.extend_from_slice(&blob);
            w
        };
        assert_eq!(acs_proof_verdict(Some(&wrapped), 1000 + 5 * 86400),
                   AcsProofVerdict::Expired { since: 5 * 86400 });

        // The refusal half: the error names the code Google Messages uses, so a log line from our side
        // and a failure on theirs are recognisably the same event.
        let e = acs_proof_expired_error(5 * 86400);
        assert!(e.0.contains("EXPIRED 5 d ago"), "{}", e.0);
        assert!(e.0.contains("SignatureExpired"), "{}", e.0);
        assert!(e.0.contains("§7.12"), "{}", e.0);

        // And the switch is OFF today — asserted, so flipping it is a deliberate edit that a
        // reviewer sees, not a drift nobody notices.
        assert!(!ACS_PROOF_EXPIRY_ENFORCED,
                "step 2 (counting on real peers) has not happened");
    }

    /// THE SEAM, PINNED — the whole point, and the earlier lesson applied before the
    /// refusal exists.
    ///
    /// That hole was not a missing check; it was a check written into `verify_participant_pop`, whose
    /// every error the lenient arm swallows, so `debug.rcs.mls_pop_lenient=1` disarmed it. The
    /// `.5` bytes are parsed nowhere else, so nothing yet ATTRACTS this call into that function —
    /// but the bytes would be handier there, which is exactly how the first one got in.
    ///
    /// So: the call must sit in `validate_leaf_rcc16` (outside both levers) and must NOT appear
    /// anywhere inside `verify_participant_pop`. A source scan is the only way to assert placement
    /// — the log-only behaviour is identical from either seam, which is precisely why a
    /// behavioural test could not catch the move.
    #[test]
    fn acs_proof_expiry_check_is_outside_the_lenient_gate() {
        let src = include_str!("rcc16_validate.rs");
        // ASSEMBLED, never written out whole: a source scan whose needle appears verbatim in its
        // own source matches ITSELF. Measured — the first version of this test counted 2 and blamed
        // the production code. Same family as a grep that finds its own command line.
        let call = format!("acs_proof_{}(p5_val, now)", "verdict");
        let call = call.as_str();
        assert_eq!(src.matches(call).count(), 1, "exactly one production call site");

        let v_start = src.find("fn validate_leaf_rcc16").expect("validate_leaf_rcc16");
        let pop_start = src.find("fn verify_participant_pop").expect("verify_participant_pop");
        let call_at = src.find(call).unwrap();
        assert!(call_at > v_start && call_at < pop_start,
                "the .5 expiry decode must live in validate_leaf_rcc16, before \
                 verify_participant_pop — not inside the function whose errors pop_strict swallows");

        // And it must precede the pop_strict match, which is what makes it undisarmable.
        let pop_match = src[v_start..pop_start].find("if pop_strict")
            .expect("the lenient arm is in validate_leaf_rcc16") + v_start;
        assert!(call_at < pop_match, "the decode must run before the lenient arm, not within it");

        // Nothing in the .5 path may consult either global lever.
        //
        // The window is ANCHORED on the marker that starts the next block, not a byte count. The
        // first version used `call_at + 1200` and went red the moment the .5 block got shorter —
        // it had read into the PoP match below and found ITS `pop_strict`. A positional range
        // asserts about whatever happens to be nearby; this asserts about the block.
        let block_end = src[call_at..].find("// .4 PoP verification.")
            .expect("the .4 PoP block still follows the .5 seam") + call_at;
        let window = &src[call_at..block_end];
        assert!(!window.contains("pop_strict"), "the .5 seam must not read pop_strict");
        assert!(!window.contains("if strict"), "the .5 seam must not read strict");

        // AND THE ENFORCEMENT SWITCH IS COMPILE-TIME, NOT A SYSPROP. A runtime dial here would
        // re-create that hole exactly: someone flipping a flag for an unrelated interop outage
        // would silently turn this off too. The FFI's two setters are the only runtime levers
        // that exist, and neither may appear in this path.
        assert!(window.contains("ACS_PROOF_EXPIRY_ENFORCED"),
                "the refusal must be gated on the compile-time constant");
        assert!(!window.contains("set_pop_lenient") && !window.contains("set_tachyon_profile"),
                "the .5 seam must not consult a runtime lever");
    }

    #[test]
    fn rcc16_full_validation() {
        let now = Some(MlsTime::from(LAB2_NOW));
        // 1) valid RCC.16 leaf (A.4.1 + verified .4 PoP) -> ACCEPTED
        validator().validate_chain(&chain("lab2_leaf_pa.der"), now)
            .expect("valid RCC.16 cert accepted");
        // 2) .4 PoP corrupted -> REJECTED (this is the ParticipantInformation check).
        //    Same CA as the good leaf, and its own certificate signature is VALID — the one byte
        //    changed is inside the .4 signature. Without that property this assertion could be
        //    satisfied by a chain failure while claiming to be about the PoP.
        let e = validator().validate_chain(&chain("lab2_leaf_badpop.der"), now).unwrap_err();
        assert!(e.0.contains("PoP") || e.0.contains(".4"), "bad-PoP reason: {}", e.0);
        // 3) dummy .4 (not the PoP structure) + missing EKU -> REJECTED (A.4.1), on the old chain
        assert!(validator_v1().validate_chain(&chain_v1("leaf_a.der"), now).is_err());
        println!("RCC.16: valid ACCEPTED; bad .4-PoP REJECTED ({}); non-conformant REJECTED", e.0);
    }

    /// §14.2.3, UNRELAXED — the lab leaf now satisfies the same rules a real peer is held to.
    ///
    /// Before this landed, `validate_x509_profile`
    /// exempted leaves from the SKI rule and skipped `certificatePolicies` on the lab profile, for
    /// one reason: our own fixture carried neither. Pinning both here is what stops the exemption
    /// from being reintroduced the next time a fixture is inconvenient.
    #[test]
    fn the_lab_leaf_satisfies_the_unrelaxed_1423_profile() {
        for leaf in ["lab2_leaf_pa.der", "lab2_leaf_pb.der", "lab2_leaf_pc.der"] {
            let der = td(leaf);
            let cert = Certificate::from_der(&der).unwrap();
            let mut ski = false;
            let mut policies = false;
            for e in cert.tbs_certificate.extensions.as_ref().unwrap().iter() {
                match e.extn_id.to_string().as_str() {
                    OID_SKI => ski = true,
                    OID_CERT_POLICIES => policies = true,
                    _ => {}
                }
            }
            assert!(ski, "{leaf} must carry a subjectKeyIdentifier");
            assert!(policies, "{leaf} must carry certificatePolicies");
            // And it passes the profile with BOTH rules unconditional, on both strictness settings.
            validate_x509_profile(&der, CertRole::Leaf, true).unwrap_or_else(
                |e| panic!("{leaf} strict: {}", e.0));
            validate_x509_profile(&der, CertRole::Leaf, false).unwrap_or_else(
                |e| panic!("{leaf} relaxed: {}", e.0));
        }
    }

    /// TWO DEVICES OF ONE PARTICIPANT — previously untestable.
    ///
    /// `lab2_leaf_pa` and `lab2_leaf_pc` are issued to the SAME MSISDN with DIFFERENT keys and
    /// different subjects, which is exactly what a second device of one participant looks like. The
    /// old fixture set had only two conformant leaves and they belonged to different MSISDNs, so the
    /// N-distinct-devices path could not be exercised at all — a claim about multi-device add that
    /// no test could reach.
    ///
    /// What this pins: both leaves validate independently, both satisfy SAN identity equality
    /// against the shared MSISDN, and they are genuinely distinct credentials (distinct subject and
    /// distinct public key) rather than one certificate under two names — the latter would satisfy a
    /// naive test while proving nothing, and MLS rejects duplicate signature keys in a group anyway.
    #[test]
    fn two_devices_of_one_participant_are_distinct_and_both_valid() {
        let now = Some(MlsTime::from(LAB2_NOW));
        let (a, _, _) = san_and_p4(&td("lab2_leaf_pa.der"));
        let (c, _, _) = san_and_p4(&td("lab2_leaf_pc.der"));

        assert_eq!(leaf_san_msisdn(&td("lab2_leaf_pa.der")).unwrap(), b"15551110001".to_vec());
        assert_eq!(leaf_san_msisdn(&td("lab2_leaf_pc.der")).unwrap(), b"15551110001".to_vec(),
            "the second device must present the SAME participant MSISDN");
        assert_ne!(a.tbs_certificate.subject, c.tbs_certificate.subject,
            "two devices are two credentials, not one certificate reused");
        assert_ne!(a.tbs_certificate.subject_public_key_info,
                   c.tbs_certificate.subject_public_key_info,
            "distinct device keys — MLS refuses duplicate signature keys in one group");

        // Both accepted on their own, and both accepted when the queried MSISDN is enforced.
        for leaf in ["lab2_leaf_pa.der", "lab2_leaf_pc.der"] {
            validator().validate_chain(&chain(leaf), now)
                .unwrap_or_else(|e| panic!("{leaf}: {}", e.0));
            validator().with_expected_msisdn(Some("+15551110001".to_string()))
                .validate_chain(&chain(leaf), now)
                .unwrap_or_else(|e| panic!("{leaf} with queried MSISDN: {}", e.0));
        }
    }

    /// Pull the SAN extn_value and the .4 inner SEQUENCE out of a leaf, for the PoP tests.
    fn san_and_p4(der: &[u8]) -> (Certificate, Vec<u8>, Vec<u8>) {
        let cert = Certificate::from_der(der).unwrap();
        let mut san = None; let mut p4 = None;
        for e in cert.tbs_certificate.extensions.as_ref().unwrap().iter() {
            let raw = e.extn_value.as_bytes();
            if e.extn_id.to_string() == OID_SAN { san = Some(raw.to_vec()); }
            if e.extn_id.as_bytes() == OID_PARTICIPANT_INFO {
                p4 = Some(unwrap_octet_string(raw).unwrap_or(raw).to_vec());
            }
        }
        (cert, san.unwrap(), p4.unwrap())
    }

    /// A REAL KDS-issued leaf's .4 PoP must VERIFY. It is the only fixture
    /// in the suite with any power over the tbsParticipantInfo layout.
    ///
    /// A.3.8.9 element 4 is the certified SUBJECT key; the key that SIGNS is the participant key at
    /// .4[4]. On a real KDS leaf those are two different keys, so getting the roles backwards fails
    /// here — which is what it did until 2026-08-07, and why the Tachyon profile had to tolerate a
    /// signature it could not reproduce.
    #[test]
    fn kds_leaf_participant_pop_verifies() {
        let (cert, san, p4) = san_and_p4(&td("lab2_kds_leaf.der"));
        verify_participant_pop(&p4, &cert.tbs_certificate, &san)
            .expect("a real KDS-issued leaf's .4 PoP must verify");
    }

    /// THE TWO STRICTNESS AXES MOVE INDEPENDENTLY — the point of splitting them.
    ///
    /// Before the split one `strict` flag drove both, so buying PoP enforcement on Tachyon would
    /// have re-imposed the A.4.1 lifetime floors — and the >=30-day floor refuses every deployed peer
    /// from day ~46 of its own 75.5-day certificate. That coupling was an implementation detail, and
    /// the evidence for the two now points in opposite directions.
    ///
    /// This pins the combination we actually ship on Tachyon: **lifetime relaxed, PoP enforced.**
    #[test]
    fn pop_enforcement_is_independent_of_the_deployment_lifetime_relaxation() {
        // Exercised at the LEAF validator rather than through validate_chain, deliberately: the
        // chain path also applies the certificatePolicies rule, which our lab fixtures do not carry
        // and which has nothing to do with either axis here. Testing through it would have made this
        // pass or fail for reasons other than the one it is about.
        let now = Some(MlsTime::from(LAB2_NOW));
        let bad = td("lab2_leaf_badpop.der");
        let good = td("lab2_leaf_pa.der");

        // THE BEHAVIOUR CHANGE: deployment-relaxed (Tachyon) but PoP-enforced -> a bad PoP REJECTS.
        // Before the split, strict=false tolerated this and logged a warning.
        let e = validate_leaf_rcc16(&bad, now, false, true, None).unwrap_err();
        assert!(e.0.contains("PoP") || e.0.contains(".4"),
            "a bad PoP must reject on the relaxed deployment profile too, got: {}", e.0);

        // Enforcement did not simply reject everything: a good leaf still passes, relaxed or not.
        validate_leaf_rcc16(&good, now, false, true, None).expect("valid leaf, relaxed deployment");
        validate_leaf_rcc16(&good, now, true, true, None).expect("valid leaf, strict deployment");

        // THE ESCAPE HATCH: pop_strict=false restores toleration, on either deployment setting.
        // This is the one-setprop revert, and it must keep working for a vendor we have never seen.
        validate_leaf_rcc16(&bad, now, false, false, None)
            .expect("pop_strict=false must still tolerate a bad PoP");
        validate_leaf_rcc16(&bad, now, true, false, None)
            .expect("the axes are independent in BOTH directions, not just the shipped one");
    }

    /// Re-encode a leaf with a `participantKeyRolls [0] IMPLICIT` element appended inside its `.4`.
    ///
    /// The certificate's own signature no longer covers the mutated TBS, which is fine and is the
    /// point: `validate_leaf_rcc16` is the PROFILE check, and the chain signature is `X509Validator`
    /// one layer up. What this fixture must model is a credential whose issuer DID assert a roll.
    fn leaf_with_rolled_p4(der: &[u8]) -> Vec<u8> {
        use x509_cert::der::Encode;
        use x509_cert::der::asn1::OctetString;
        let mut cert = Certificate::from_der(der).expect("leaf parses");
        for e in cert.tbs_certificate.extensions.as_mut().expect("extensions").iter_mut() {
            if e.extn_id.as_bytes() != OID_PARTICIPANT_INFO { continue; }
            let raw = e.extn_value.as_bytes().to_vec();
            let p4: Vec<u8> = match unwrap_octet_string(&raw) {
                Some(v) => v.to_vec(),
                None => raw.clone(),
            };
            let mut pos = 0usize;
            let seq = read_tlv(&p4, &mut pos).expect(".4 SEQUENCE");
            let mut inner = seq.val.to_vec();
            inner.extend_from_slice(&[0xA0, 0x03, 0x02, 0x01, 0x07]); // [0] { INTEGER 7 }
            let mut rolled = vec![0x30u8];
            encode_der_len(&mut rolled, inner.len());
            rolled.extend_from_slice(&inner);
            e.extn_value = OctetString::new(rolled).expect("octet string");
        }
        cert.to_der().expect("re-encode")
    }

    /// The midpoint of a leaf's own validity window — a "now" that is inside it by construction, so
    /// the fixture does not expire out from under the suite the way a hardcoded instant would.
    fn leaf_midpoint(der: &[u8]) -> u64 {
        let c = Certificate::from_der(der).expect("leaf parses");
        let nb = c.tbs_certificate.validity.not_before.to_unix_duration().as_secs();
        let na = c.tbs_certificate.validity.not_after.to_unix_duration().as_secs();
        nb + (na - nb) / 2
    }

    /// A .4 CARRYING A TRAILING ELEMENT IS REFUSED, NOT IGNORED.
    ///
    /// A.3.8.9 puts `participantKeyRolls [0] IMPLICIT OPTIONAL` inside .4 after the SPKI, and
    /// A.4.1.1(1c-ii) requires a verifier to CHECK it. We do not implement that check. The unsafe
    /// option is to read five elements and ignore the sixth, which accepts a credential asserting a
    /// key-change chain nobody verified; the safe one is to refuse.
    ///
    /// **DRIVEN THROUGH `validate_leaf_rcc16`, ON BOTH `pop_strict` SETTINGS, AND THAT IS THE WHOLE
    /// POINT OF THIS TEST.** Its predecessor called `verify_participant_pop` DIRECTLY, which is the
    /// one caller that cannot fail: the refusal lived inside that function and every error it
    /// returns is swallowed by the lenient arm of `validate_leaf_rcc16`, so
    /// `debug.rcs.mls_pop_lenient=1` — a shipped, documented one-setprop escape hatch — silently
    /// restored the exact acceptance that refusal had closed, and no test in the suite could see it.
    ///
    /// **The state that would make this FAIL is `pop_strict=false`, and it is reachable in
    /// production**: `OpenMlsNative`'s static init reads that sysprop into
    /// `rcs_mls_set_pop_lenient`. Delete the hoisted check in `validate_leaf_rcc16` and the
    /// `false` arm below goes red while the `true` arm stays green — which is what it did.
    ///
    /// A real KDS leaf carries no [0] element (byte-decoded from testdata/lab2_kds_leaf.der,
    /// .4 has exactly five members), so this costs nothing against real credentials and closes the
    /// gap until the verification exists.
    #[test]
    fn a_participant_information_with_a_trailing_element_is_refused() {
        let baseline = td("lab2_kds_leaf.der");
        let now = Some(MlsTime::from(leaf_midpoint(&baseline)));

        // The fixture must be a faithful re-encode apart from the element we added, or a refusal
        // below could be about the re-encoding rather than about the roll.
        assert_eq!(leaf_with_rolled_p4(&baseline).len(), baseline.len() + 5,
            "the only change must be the 5-byte [0] element");

        // Sanity, and NOT redundant: the unmodified baseline leaf passes the whole deployment
        // profile at this instant, so every rejection below is attributable to the trailing data.
        validate_leaf_rcc16(&baseline, now, false, true, None)
            .expect("the baseline leaf's .4 has exactly five elements and its PoP verifies");

        let rolled = leaf_with_rolled_p4(&baseline);
        for pop_strict in [true, false] {
            let msg = match validate_leaf_rcc16(&rolled, now, false, pop_strict, None) {
                Ok(()) => panic!("a .4 carrying a key roll must be REFUSED, never silently \
                                  accepted (pop_strict={pop_strict})"),
                Err(e) => e.0,
            };
            assert!(msg.contains("trailing"),
                "the error must name the trailing data (pop_strict={pop_strict}): {msg}");
            assert!(msg.contains("key-roll"),
                "and name the unverified key-roll it refuses (pop_strict={pop_strict}): {msg}");
        }

        // The second reader inside the PoP still refuses too, so a direct caller of that function
        // cannot be handed a rolled .4 either.
        let (cert, san, p4) = san_and_p4(&rolled);
        let e = verify_participant_pop(&p4, &cert.tbs_certificate, &san)
            .expect_err("verify_participant_pop is the second reader, not a hole");
        assert!(e.0.contains("trailing") && e.0.contains("key-roll"), "{}", e.0);
    }

    /// THE ALGORITHM GATE TAKES SHA-256 **AND** SHA-384, because Google Messages does.
    ///
    /// Their gate was read across two code paths: it checks the OID prefix 1.2.840.10045.4.3.x
    /// and accepts trailing `02` (SHA-256) and `03` (SHA-384); both converge, everything else is
    /// rejected. We used to hardcode SHA-256 and discard `.4[2]` entirely, which made us STRICTER
    /// than they are — we would have rejected certificates they honour.
    ///
    /// AND WE GO WIDER ON PURPOSE: 01 (SHA-224) and 04 (SHA-512) are outside their
    /// measured set but accepted here by a deliberate decision (2026-08-07) — a SUPERSET, not a match.
    /// The distinction is load-bearing: their 02/03 gate is a MEASUREMENT of the code, whereas
    /// all four OIDs merely being present as constants is not evidence of anything. A constant is
    /// not a branch.
    ///
    /// Exercised by rewriting the real leaf's `.4[2]` trailing OID byte, which moves the gate
    /// without needing a second issuer: every SHA2 variant must reach the SIGNATURE check (and fail
    /// there, since the bytes were signed with SHA-256 — that failure is the proof the gate opened),
    /// while a non-SHA2 arc byte and a non-ECDSA OID must be refused BY THE GATE. The distinct error
    /// messages are what separate "the gate opened" from "the signature matched".
    #[test]
    fn the_sigalg_gate_takes_every_sha2_variant_and_refuses_non_sha2() {
        let (cert, san, p4) = san_and_p4(&td("lab2_kds_leaf.der"));
        // Locate the .4[2] OID's trailing byte inside the extension bytes.
        let needle: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03];
        let at = p4.windows(needle.len()).position(|w| w == needle)
            .expect("ecdsa-with-SHA* OID present in .4[2]") + needle.len();
        assert_eq!(p4[at], 0x02, "the real-leaf fixture is SHA-256 signed");

        let with = |b: u8| { let mut v = p4.clone(); v[at] = b; v };

        // 03 = SHA-384: the gate must OPEN. The signature then fails, because these bytes were
        // signed with SHA-256 — that failure is the proof the gate let it through to the verify.
        let e = verify_participant_pop(&with(0x03), &cert.tbs_certificate, &san).unwrap_err();
        assert!(e.0.contains("signature INVALID"),
            "SHA-384 must reach the signature check, got: {}", e.0);

        // 01 (SHA-224) and 04 (SHA-512) are OUTSIDE the measured accepted set but we take
        // them anyway, deliberately — a SUPERSET. So they too must reach the
        // signature check rather than being gate-refused.
        for wider in [0x01u8, 0x04] {
            let e = verify_participant_pop(&with(wider), &cert.tbs_certificate, &san).unwrap_err();
            assert!(e.0.contains("signature INVALID"),
                "variant {wider:#04x} must reach the signature check, got: {}", e.0);
        }
        // A non-SHA2 trailing byte is still refused BY THE GATE — the OID arc has no 05.
        let e = verify_participant_pop(&with(0x05), &cert.tbs_certificate, &san).unwrap_err();
        assert!(e.0.contains("not an SHA2 variant"), "05 must be gate-refused, got: {}", e.0);
        // A non-ECDSA OID entirely (RSA) is refused before the hash is even chosen.
        let mut rsa = p4.clone();
        rsa[at - needle.len()..at].copy_from_slice(&[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01]);
        let e = verify_participant_pop(&rsa, &cert.tbs_certificate, &san).unwrap_err();
        assert!(e.0.contains("not an ecdsa-with-SHA"), "RSA OID must be refused, got: {}", e.0);
        // And 02 still verifies, i.e. none of this disturbed the working path.
        verify_participant_pop(&p4, &cert.tbs_certificate, &san).expect("SHA-256 still verifies");
    }

    /// WHY THE BUG SURVIVED A STRICT TEST, locked in so nobody "simplifies" the fixture above.
    ///
    /// Our synthetic fixtures mint ONE key and use it as both the participant key and the certified
    /// subject key. With `.4[4] == leaf SPKI` the two candidate element-4 layouts are byte-identical
    /// and leaf_pa verifies under either — a passing test that discriminates nothing. Only a
    /// two-key certificate can tell them apart.
    #[test]
    fn a_one_key_fixture_cannot_discriminate_the_layout_but_a_two_key_one_can() {
        let (syn, _, _) = san_and_p4(&td("lab2_leaf_pa.der"));
        let (gen, _, gp4) = san_and_p4(&td("lab2_kds_leaf.der"));
        let syn_spki = syn.tbs_certificate.subject_public_key_info.to_der().unwrap();
        let gen_spki = gen.tbs_certificate.subject_public_key_info.to_der().unwrap();
        // .4[4] on each.
        let p4_spki = |p4: &[u8]| -> Vec<u8> {
            let mut pos = 0; let seq = read_tlv(p4, &mut pos).unwrap();
            let mut ip = 0;
            for _ in 0..4 { read_tlv(seq.val, &mut ip).unwrap(); }
            read_tlv(seq.val, &mut ip).unwrap().full.to_vec()
        };
        let (_, _, sp4) = san_and_p4(&td("lab2_leaf_pa.der"));
        assert_eq!(p4_spki(&sp4), syn_spki,
            "synthetic fixture reuses one key, so element 4 is ambiguous — this is the blind spot");
        assert_ne!(p4_spki(&gp4), gen_spki,
            "the real KDS leaf must carry TWO distinct keys, or it proves nothing either");
    }

    #[test]
    fn rcc16_san_identity_equality() {
        let now = Some(MlsTime::from(LAB2_NOW));
        // leaf_pa SAN = tel:+15551110001.
        // Correct queried MSISDN -> ACCEPTED (A.4.1 identity equality holds).
        let ok = validator().with_expected_msisdn(Some("+15551110001".into()));
        ok.validate_chain(&chain("lab2_leaf_pa.der"), now).expect("matching MSISDN accepted");
        // Same digits, no '+' / with separators -> still ACCEPTED (E.164-normalized compare).
        let ok2 = validator().with_expected_msisdn(Some("tel:1-555-111-0001".into()));
        ok2.validate_chain(&chain("lab2_leaf_pa.der"), now).expect("normalized MSISDN accepted");
        // WRONG number -> REJECTED (the core A.4.1 gap this closes).
        let bad = validator().with_expected_msisdn(Some("+15559999999".into()));
        let e = bad.validate_chain(&chain("lab2_leaf_pa.der"), now).unwrap_err();
        assert!(e.0.contains("SAN tel: URI does not match"), "wrong-MSISDN reason: {}", e.0);
    }

    /// The identity the caller compares against. This is the half of A.4.1 that can actually run in
    /// production: the validator above needs an expectation it cannot have (it sees every peer's
    /// credential), whereas the claim site knows exactly which number it queried.
    #[test]
    fn a_leaf_reports_the_msisdn_it_certifies() {
        assert_eq!(leaf_san_msisdn(&td("lab2_leaf_pa.der")).unwrap(), b"15551110001".to_vec());
        // A cert for a different number reports THAT number — the comparison is the caller's.
        assert_eq!(other_digits(&td("lab2_leaf_pb.der")), "15551110002");
        assert!(msisdn_equals(b"tel:+1 (555) 111-0001", b"+15551110001"));
        assert!(!msisdn_equals(b"+15551110001", b"+15559999999"));
        // An absent identity must NOT compare equal to anything — no wildcard by omission.
        assert!(!msisdn_equals(b"", b"+15551110001"));
        assert!(!msisdn_equals(b"", b""));
        assert!(leaf_san_msisdn(b"not a certificate").is_none());
    }

    fn other_digits(der: &[u8]) -> String {
        String::from_utf8(leaf_san_msisdn(der).unwrap()).unwrap()
    }

    /// GOOGLE'S DEPLOYED CHAIN MUST PASS. These are the two real CA certificates every deployed
    /// KeyPackage carries, pulled from a claimed peer KeyPackage on 2026-07-30 (public
    /// certificates — they are transmitted to every peer; no identity material).
    ///
    /// This is the regression guard for the A.2 checks. The spec's A.2.8 lists the permitted CA
    /// extensions and EKU is NOT among them, so a literal reading forbids it — but both of these
    /// carry EKU=2.23.146.2.1.3, and enforcing absence would have rejected every deployed peer.
    /// Note the ICA at 1825 days against A.2.5's 1827-day limit: Google is plainly targeting the
    /// same bound, which is decent evidence they enforce it in the other direction too.
    #[test]
    fn googles_real_ca_chain_satisfies_the_a2_profile() {
        for name in ["google_kds_client_ca.der", "google_kds_ica.der"] {
            validate_ca_rcc16(&td(name))
                .unwrap_or_else(|e| panic!("{name} must satisfy A.2: {}", e.0));
        }
    }

    /// EVERY ROOT IN THE LIVE TRUSTED-ROOT LIST MUST PASS, Apple's included.
    ///
    /// The list is FETCHED (over a gstatic path) precisely so Google can add vendors it
    /// interworks with, so anything hard-coded to Google's shape is a cross-vendor interop bug
    /// waiting to happen. These three are from the live generation 1770679592 pulled off a device.
    ///
    /// This is also the regression guard for a real bug: the first cut applied A.2's 1827-day
    /// intermediate limit to everything above the leaf, and every one of these is ~3650 days under
    /// A.1.5's 3652-day ROOT limit — so a peer that presented its root would have been rejected.
    #[test]
    fn every_live_trusted_root_satisfies_the_a1_profile() {
        for name in ["live_root_google_kds.der", "live_root_android_mls.der",
                     "live_root_apple_rcs.der"] {
            validate_ca_rcc16(&td(name))
                .unwrap_or_else(|e| panic!("{name} must satisfy A.1: {}", e.0));
        }
    }

    /// The two limits must stay distinct: a root-length INTERMEDIATE is still a violation.
    #[test]
    fn an_intermediate_may_not_use_the_root_validity_allowance() {
        // Google's ICA is not self-signed, so it is held to A.2.5's 1827 days.
        let ica = td("google_kds_ica.der");
        let cert = Certificate::from_der(&ica).unwrap();
        assert_ne!(cert.tbs_certificate.issuer, cert.tbs_certificate.subject,
                   "the ICA must be issuer != subject, or this test proves nothing");
        // And a real root IS self-signed, which is what selects the longer allowance.
        let root = td("live_root_apple_rcs.der");
        let rc = Certificate::from_der(&root).unwrap();
        assert_eq!(rc.tbs_certificate.issuer, rc.tbs_certificate.subject);
    }

    /// A CA that is not marked as one must not be able to issue. This is the actual hole: without
    /// it, any end-entity certificate the trust anchor signed could mint leaves for arbitrary
    /// MSISDNs, and RCC.16 leaves are deliberately non-revocable.
    #[test]
    fn an_end_entity_certificate_is_refused_as_an_issuer() {
        let e = validate_ca_rcc16(&td("lab2_leaf_pa.der")).unwrap_err();
        assert!(e.0.contains("A.2.8.5"), "must fail the CA constraint, got: {}", e.0);
    }

    /// The two-digit-year rule and both DER time forms — an off-by-a-century here would either
    /// expire every .4 immediately or never.
    #[test]
    fn der_times_decode_for_both_forms() {
        // 2026-07-30T00:00:00Z
        let want = 1785369600u64;
        assert_eq!(der_time_to_unix(0x17, b"260730000000Z"), Some(want));
        assert_eq!(der_time_to_unix(0x18, b"20260730000000Z"), Some(want));
        // RFC 5280: YY >= 50 is 19xx.
        let y1995 = der_time_to_unix(0x17, b"950101000000Z").unwrap();
        let y2005 = der_time_to_unix(0x17, b"050101000000Z").unwrap();
        assert!(y1995 < y2005, "50..99 must read as 19xx, 00..49 as 20xx");
        assert_eq!(der_time_to_unix(0x17, b"short"), None);
    }

    /// An expired ParticipantInformation must be REFUSED. The window was parsed for the PoP
    /// reconstruction and then never checked, so the key-to-identity binding outlived it.
    #[test]
    fn an_expired_participant_information_is_refused() {
        // SEQUENCE { UTCTime notBefore, UTCTime notAfter } covering 2026-07-01..2026-07-10.
        let mut v = Vec::new();
        for t in [b"260701000000Z", b"260710000000Z"] {
            v.push(0x17); v.push(t.len() as u8); v.extend_from_slice(t);
        }
        let inside = der_time_to_unix(0x17, b"260705000000Z").unwrap();
        let after = der_time_to_unix(0x17, b"260801000000Z").unwrap();
        let before = der_time_to_unix(0x17, b"260601000000Z").unwrap();
        assert!(check_participant_validity(&v, inside).is_ok());
        let e = check_participant_validity(&v, after).unwrap_err();
        assert!(e.0.contains("EXPIRED"), "got: {}", e.0);
        let e2 = check_participant_validity(&v, before).unwrap_err();
        assert!(e2.0.contains("not yet valid"), "got: {}", e2.0);
    }

    #[test]
    fn rcc16_normalize_e164() {
        assert_eq!(normalize_e164(b"tel:+1-555-111-0001"), b"15551110001");
        assert_eq!(normalize_e164(b"+15551110001"), b"15551110001");
        assert_eq!(normalize_e164(b"tel:+15551110001;phone-context=+1"), b"15551110001");
        assert!(normalize_e164(b"").is_empty());
    }

    #[test]
    fn rcc16_lifetime_fail_closed_uses_trusted_now() {
        // ts=None must NOT silently skip the >=30-day floor nor hard-reject: the validator falls back
        // to a trusted system clock. Date-robust assertion: whatever the decision, it must go through
        // the fallback (never the "no trusted time source" hard-fail), i.e. it either ACCEPTS (cert
        // still >=30d remaining at the real clock) or REJECTS with a lifetime reason (once inside the
        // 30-day expiry window) — but is never the fail-closed no-clock error.
        match validator().validate_chain(&chain("lab2_leaf_pa.der"), None) {
            Ok(_) => {}
            Err(e) => assert!(
                (e.0.contains("remaining") || e.0.contains("lifetime")) && !e.0.contains("no trusted time source"),
                "fallback should decide by lifetime, got: {}", e.0),
        }
    }
}

/// A serial number's DER INTEGER value bytes with leading zeroes stripped.
///
/// A DER INTEGER is signed, so a serial whose top bit is set carries a leading 0x00 padding byte.
/// Comparing raw encodings would therefore let the same number miss its own revocation entry
/// depending on how it was written down.
fn normalize_serial(v: &[u8]) -> Vec<u8> {
    let start = v.iter().position(|&b| b != 0).unwrap_or(v.len().saturating_sub(1));
    v[start..].to_vec()
}

// =================================================================================================
// §14.2.3 — the X.509 profile a peer certificate must satisfy
// =================================================================================================
//
// The profile is enforced by three cooperating layers in Google Messages: BoringSSL path building,
// the Google/GSMA RCS profile, and identity extraction. We delegate the FIRST to mls-rs, which is
// exactly the situation §14.2.3's caveat warns about — a stock verifier implements more than the
// profile allows (RSA, in particular), so every rule the profile adds ON TOP of path building has
// to be re-imposed here or it simply is not enforced.
//
// WHAT IS DELIBERATELY NOT ENFORCED, and why it is gated rather than dropped:
//
//   * RESOLVED 2026-08-02. The "exactly one E2EE certificatePolicies" count needed the policy's
//     own OID, which is 2.23.146.2.1.2 — read directly off a KDS-minted leaf
//     (`testdata/lab2_kds_leaf.der`). Counting policies in TOTAL would have been a different
//     rule, wrong in both directions, so the rule stayed inert until the OID was proven rather
//     than being approximated. It is now specific and enforced.
//   * The `clientIdentifier` subject-attribute OID is STILL UNKNOWN. 2.23.146.2.1.2 was proposed
//     for it and proved to be the E2EE POLICY instead — and the real KDS
//     leaf turns out to carry no clientIdentifier attribute at all, putting the client UUID in
//     commonName. So the OR-leg is satisfied by commonName on real certificates and the missing
//     OID costs nothing today.
//
//     Worth keeping as a worked example: that OID was wired ONLY where being wrong failed SAFE,
//     and when it turned out to be wrong the cost was zero. The same value wired into the
//     rejection path would have refused conformant peers with a message blaming their client
//     identifier.
//
// Both are recorded as gates rather than silently approximated, because an approximated rule that
// looks implemented is worse than an absent one nobody is relying on.

/// The `clientIdentifier` subject attribute OID — **STILL UNKNOWN**.
///
/// 2.23.146.2.1.2 was proposed for this and was WRONG: the artefact (below) shows .2 is the E2EE
/// certificatePolicies policy. The evidence had been constant-adjacency to the "Invalid client
/// identifier" diagnostic, which is exactly the weaker-than-it-looks kind it was flagged as.
///
/// The real KDS leaf carries NO clientIdentifier attribute at all — its subject is a bare
/// commonName whose VALUE is the client UUID — so the "commonName OR clientIdentifier" rule is
/// satisfied by commonName on real certificates and this constant is not needed to validate them.
const OID_CLIENT_IDENTIFIER: Option<&str> = None;

/// The E2EE `certificatePolicies` policy OID — **2.23.146.2.1.2, read from the artefact**.
///
/// Named directly in a KDS-minted leaf's own certificatePolicies extension
/// (`testdata/lab2_kds_leaf.der`). Exactly one policy is present and
/// it is this OID, which confirms §14.2.3's "exactly 1 E2EE certificate policy in the leaf" and
/// lets the count rule be SPECIFIC rather than a count of policies in general.
const E2EE_POLICY_OID: Option<&str> = Some("2.23.146.2.1.2");

/// Where in the chain a certificate sits — several profile rules differ by role.
#[derive(Clone, Copy, PartialEq, Debug)]
pub(crate) enum CertRole { Leaf, Intermediate, Root }

/// The §14.2.3 rules that apply to EVERY certificate regardless of role.
///
/// `strict` selects full RCC.16 conformance over Google-deployment interop. It gates only the rules
/// where the two genuinely disagree — never a rule both require — and each such gate says so where
/// it is applied.
fn validate_x509_profile(der: &[u8], role: CertRole, strict: bool) -> Result<(), Rcc16Error> {
    let cert = Certificate::from_der(der)
        .map_err(|e| Rcc16Error(format!("Invalid certificate: {e}")))?;
    let tbs = &cert.tbs_certificate;
    let what = match role {
        CertRole::Leaf => "leaf",
        CertRole::Intermediate => "intermediate",
        CertRole::Root => "root",
    };

    // ---- version: V3 -------------------------------------------------------------------------
    // A V1/V2 certificate cannot carry extensions at all, so every extension rule below would be
    // vacuously satisfied by one. BoringSSL words this as "Unexpected extensions (must be V3)".
    if tbs.version != x509_cert::Version::V3 {
        return Err(Rcc16Error(format!(
            "Invalid version extension: {what} is not a V3 certificate ({:?})", tbs.version)));
    }

    // ---- serial number: non-empty, positive, <= 20 octets -------------------------------------
    let serial = tbs.serial_number.as_bytes();
    if serial.is_empty() {
        return Err(Rcc16Error(format!("Invalid serial number: {what} serial is empty")));
    }
    if serial[0] & 0x80 != 0 {
        // A leading bit set means a NEGATIVE DER INTEGER. RFC 5280 requires a positive serial;
        // accepting one invites two certificates whose serials compare equal after normalisation.
        return Err(Rcc16Error(format!("Invalid serial number: {what} serial is negative")));
    }
    if normalize_serial(serial).len() > SERIAL_MAX_OCTETS {
        return Err(Rcc16Error(format!(
            "Invalid serial number: {what} serial is {} octets, max {SERIAL_MAX_OCTETS}",
            normalize_serial(serial).len())));
    }

    // ---- signature algorithm: ECDSA + SHA-2 ONLY ----------------------------------------------
    // THE caveat of §14.2.3. mls-rs delegates to a verifier that implements RSA, so without this
    // an RSA-signed certificate chaining to a trusted root would be accepted — the profile's
    // ECDSA-only rule lives at this layer and nowhere else.
    let sig_alg = tbs.signature.oid.to_string();
    if !SIG_ALGS_ECDSA.contains(&sig_alg.as_str()) {
        return Err(Rcc16Error(format!(
            "Invalid signature algorithm extension: {what} uses {sig_alg}; \
             only ECDSA with SHA-256/384/512 is permitted (no RSA arc exists in the profile)")));
    }

    // ---- subject public key: P-256 / P-384 / P-521 only ---------------------------------------
    let spki_alg = tbs.subject_public_key_info.algorithm.oid.to_string();
    if spki_alg != OID_EC_PUBLIC_KEY {
        return Err(Rcc16Error(format!(
            "Invalid subject public key info extension: {what} key algorithm {spki_alg} \
             is not id-ecPublicKey")));
    }
    let curve = tbs.subject_public_key_info.algorithm.parameters.as_ref()
        .and_then(|p| p.decode_as::<x509_cert::der::asn1::ObjectIdentifier>().ok())
        .map(|o| o.to_string());
    match curve {
        Some(c) if CURVES_PERMITTED.contains(&c.as_str()) => {}
        Some(c) => return Err(Rcc16Error(format!(
            "Invalid subject public key info extension: {what} curve {c} is not P-256/384/521"))),
        None => return Err(Rcc16Error(format!(
            "Invalid subject public key info extension: {what} has no named curve"))),
    }

    // ---- subject RDNs: single-valued, bounded ------------------------------------------------
    validate_subject(&tbs.subject, role, strict)?;

    let exts = match tbs.extensions.as_ref() {
        Some(e) => e,
        // A root may legitimately be minimal; a leaf without extensions has already failed above
        // in validate_leaf_rcc16 (no EKU/SAN/KU), so this is not a hole.
        None if role == CertRole::Root => return Ok(()),
        None => return Err(Rcc16Error(format!("{what} has no extensions"))),
    };

    let mut saw_ski = false;
    let mut saw_policies = false;
    for e in exts.iter() {
        let oid = e.extn_id.to_string();
        let raw = e.extn_value.as_bytes();
        match oid.as_str() {
            // ---- subjectKeyIdentifier: present and well-formed --------------------------------
            OID_SKI => {
                saw_ski = true;
                let mut p = 0;
                let ok = read_tlv(raw, &mut p)
                    .is_some_and(|t| t.tag == 0x04 && !t.val.is_empty());
                if !ok {
                    return Err(Rcc16Error(format!(
                        "Invalid subject key identifier extension: {what} SKI is malformed")));
                }
            }
            // ---- authorityKeyIdentifier -------------------------------------------------------
            // keyIdentifier [0] present; authorityCertIssuer [1] and authorityCertSerialNumber [2]
            // BOTH absent. The two absences matter: an AKI that names the issuer by name+serial
            // instead of by key identifier lets path building be steered by a mutable field.
            OID_AKI => validate_aki(raw, what)?,
            // ---- certificatePolicies ----------------------------------------------------------
            OID_CERT_POLICIES => {
                saw_policies = true;
                validate_cert_policies(raw, role, e.critical, what)?;
            }
            // ---- the ACS proof must NOT be critical -------------------------------------------
            _ if e.extn_id.as_bytes() == OID_ACS_PARTICIPANT_INFO => {
                if e.critical {
                    return Err(Rcc16Error(
                        "ACS Participant Information signed identity proof was marked critical."
                            .into()));
                }
            }
            _ => {
                // ---- unexpected extensions are REJECTED, not ignored --------------------------
                // Gated on `strict` because it is an ALLOW-LIST, and the list is derived from the
                // profile's own diagnostics rather than from an enumeration we have seen. On the
                // Google-deployment path a single unlisted extension on a real peer leaf would
                // take out every conversation with that peer, so there we log and continue —
                // which also tells us what to add.
                if !EXTENSIONS_PERMITTED.contains(&oid.as_str()) {
                    if strict {
                        return Err(Rcc16Error(format!(
                            "{what}, certificate contains unexpected extension: {oid}")));
                    }
                    crate::ffi::alog!(
                        "rcc16: {} carries unlisted extension {} (tolerated on the deployment \
                         profile; add it to EXTENSIONS_PERMITTED if legitimate)", what, oid);
                }
            }
        }
    }

    // ---- PRESENCE rules: evidence-based, not spec-literal --------------------------------------
    //
    // The same reasoning the CA EKU rule already uses in this file. §14.2.3 lists SKI and
    // certificatePolicies as required, but a rule that rejects certificates we have IN HAND is not
    // conformance, it is an outage. What the fixtures actually carry:
    //
    //   cert                                SKI   certificatePolicies
    //   lab2_kds_leaf.der (REAL LEAF)    YES   YES (one policy: 2.23.146.2.1.2)
    //   our lab leaf (lab2_leaf_pa.der)     YES   YES (one policy: 2.23.146.2.1.2)  <- since 2026-08
    //   the RETIRED lab leaf (leaf_pa.der)  no    no                                <- what forced the exemption
    //   our lab ica.der / root.der          yes   no
    //   google_kds_ica.der (REAL)           yes   NO
    //   live_root_google_kds.der (REAL)     yes   no
    //
    // SKI is universal — every real certificate has one, leaf included. certificatePolicies is on
    // the real LEAF but absent from a REAL GOOGLE ICA, which settles it in both directions:
    // required on leaves, and requiring it on issuers would reject the actual deployed chain.
    //
    // UNCONDITIONAL SINCE 2026-08-08. Both of these used to carry an exemption whose
    // only justification was that OUR OWN lab fixture could not satisfy them:
    //
    //     if !saw_ski && role != CertRole::Leaf                  <- SKI un-required on leaves
    //     if !saw_policies && role == CertRole::Leaf && !strict  <- policies skipped on the lab profile
    //
    // That is the wrong direction of causation for a validator, and it is worth naming because it
    // is easy to re-introduce: a fixture that cannot meet a rule silently becomes the ceiling on how
    // strict the rule is allowed to be, and the exemption then reads like a considered policy. The
    // rules themselves were never in doubt — SKI is on every real certificate, and the real KDS
    // leaf carries exactly one certificatePolicies naming 2.23.146.2.1.2.
    //
    // What changed is the fixture, not the evidence: MlsCredential now mints both, the lab chain
    // was regenerated from it (testdata/lab2_*), and its CA private keys are committed so the next
    // rule never has to be relaxed to accommodate a chain nobody can re-issue.
    if !saw_ski {
        return Err(Rcc16Error(format!(
            "Invalid subject key identifier extension: Missing subject public key identifier \
             on {what}")));
    }
    // Still LEAF-only: certificatePolicies is absent from a REAL GOOGLE ICA, so requiring it on
    // issuers would reject the actual deployed chain. That condition is evidence-driven; the
    // `!strict` one that used to sit beside it was not.
    if !saw_policies && role == CertRole::Leaf {
        return Err(Rcc16Error(
            "Invalid certificate policies extension: leaf has no certificatePolicies".into()));
    }
    Ok(())
}

/// Subject RDNs: single-valued, with bounded C/O/CN/ST/L, and a subject that actually identifies
/// something.
fn validate_subject(subject: &x509_cert::name::Name, role: CertRole, strict: bool)
        -> Result<(), Rcc16Error> {
    let mut common_name: Option<String> = None;
    let mut client_identifier: Option<String> = None;
    for rdn in subject.0.iter() {
        // MULTI-VALUED RDNs are forbidden. They are the classic way to smuggle a second attribute
        // past a validator that only reads the first element of each RDN.
        if rdn.0.len() != 1 {
            return Err(Rcc16Error(format!(
                "Invalid subject extension: multi-valued RDN ({} values)", rdn.0.len())));
        }
        let atv = &rdn.0.as_slice()[0];
        let oid = atv.oid.to_string();
        let value = atv.value.value();
        if OID_CLIENT_IDENTIFIER.is_some_and(|ci| oid == ci) {
            client_identifier = Some(String::from_utf8_lossy(value).to_string());
            continue;
        }
        let (label, max) = match oid.as_str() {
            "2.5.4.6"  => ("C", RDN_MAX_C),
            "2.5.4.10" => ("O", RDN_MAX_O),
            "2.5.4.3"  => ("CN", RDN_MAX_CN),
            "2.5.4.8"  => ("ST", RDN_MAX_ST),
            "2.5.4.7"  => ("L", RDN_MAX_L),
            _ => continue,
        };
        if value.len() > max {
            return Err(Rcc16Error(format!(
                "Invalid subject extension: {label} is {} chars, max {max}", value.len())));
        }
        if label == "CN" {
            common_name = Some(String::from_utf8_lossy(value).to_string());
        }
    }
    // "subject must contain commonName or clientIdentifier" (arm 67). Leaf only — CA subjects are
    // ordinary names.
    //
    // THE INFERRED OID IS USED ONLY WHERE BEING WRONG FAILS SAFE, and this is one of those places:
    // recognising a clientIdentifier can only ever make this check PASS. If 2.23.146.2.1.2 turns
    // out to be something else, we simply do not find one and fall back to requiring a commonName —
    // exactly the behaviour we had before the OID was known. A wrong value costs nothing here.
    if role == CertRole::Leaf && common_name.is_none() && client_identifier.is_none() {
        return Err(Rcc16Error(
            "Invalid subject extension: leaf subject carries neither commonName nor \
             clientIdentifier".into()));
    }
    // The UUID parse (arm 66). This one CAN reject, so the inferred OID is not trusted to drive it
    // on the deployment path: if the OID is wrong, some other attribute's value would be parsed as
    // a UUID and a conformant peer would be refused with a message blaming its client identifier.
    // Under `strict` — our own lab certificates, where we control what the attribute is — it gates
    // properly.
    if let Some(ci) = client_identifier.as_deref() {
        let trimmed = ci.strip_prefix("urn:uuid:").unwrap_or(ci);
        if !is_uuid(trimmed) {
            if strict {
                return Err(Rcc16Error(
                    "Client identifier parse error: Invalid client identifier".into()));
            }
            crate::ffi::alog!(
                "rcc16: subject clientIdentifier {:?} is not a UUID — tolerated on the deployment \
                 profile (the attribute OID is an inference)", trimmed);
        }
    }
    Ok(())
}

/// 8-4-4-4-12 hex with dashes. Shape first, then validity, so an ordinary commonName that merely
/// contains dashes is never mistaken for a malformed UUID.
fn looks_like_uuid_shape(s: &str) -> bool {
    let parts: Vec<&str> = s.split('-').collect();
    parts.len() == 5 && [8usize, 4, 4, 4, 12].iter().zip(&parts).all(|(n, p)| p.len() == *n)
}
fn is_uuid(s: &str) -> bool {
    looks_like_uuid_shape(s) && s.split('-').all(|p| p.bytes().all(|c| c.is_ascii_hexdigit()))
}

/// AuthorityKeyIdentifier ::= SEQUENCE { keyIdentifier [0] OPTIONAL,
///   authorityCertIssuer [1] OPTIONAL, authorityCertSerialNumber [2] OPTIONAL }
fn validate_aki(raw: &[u8], what: &str) -> Result<(), Rcc16Error> {
    let mut p = 0;
    let seq = read_tlv(raw, &mut p).filter(|t| t.tag == 0x30)
        .ok_or_else(|| Rcc16Error(format!(
            "Invalid authority key identifier extension: {what} AKI is not a SEQUENCE")))?;
    let mut q = 0;
    let mut have_kid = false;
    while let Some(t) = read_tlv(seq.val, &mut q) {
        match t.tag {
            0x80 => have_kid = true,
            0xA1 | 0x81 => return Err(Rcc16Error(format!(
                "Invalid authority key identifier extension: {what} AKI carries \
                 authorityCertIssuer, which must be absent"))),
            0xA2 | 0x82 => return Err(Rcc16Error(format!(
                "Invalid authority key identifier extension: {what} AKI carries \
                 authorityCertSerialNumber, which must be absent"))),
            _ => {}
        }
    }
    if !have_kid {
        return Err(Rcc16Error(format!(
            "Invalid authority key identifier extension: {what} AKI has no keyIdentifier")));
    }
    Ok(())
}

/// certificatePolicies ::= SEQUENCE OF PolicyInformation { policyIdentifier, qualifiers OPTIONAL }
fn validate_cert_policies(raw: &[u8], role: CertRole, critical: bool, what: &str)
        -> Result<(), Rcc16Error> {
    if critical {
        return Err(Rcc16Error(format!(
            "Invalid certificate policies extension: {what} certificatePolicies must be \
             non-critical")));
    }
    let mut p = 0;
    let seq = read_tlv(raw, &mut p).filter(|t| t.tag == 0x30)
        .ok_or_else(|| Rcc16Error(format!(
            "Invalid certificate policies extension: {what} is not a SEQUENCE")))?;
    let any_policy_der = oid_to_der(OID_ANY_POLICY);
    let e2ee_der = E2EE_POLICY_OID.map(oid_to_der);
    let mut e2ee_count = 0usize;
    let mut q = 0;
    while let Some(pi) = read_tlv(seq.val, &mut q) {
        if pi.tag != 0x30 { continue; }
        let mut r = 0;
        let Some(oid_tlv) = read_tlv(pi.val, &mut r).filter(|t| t.tag == 0x06) else { continue };
        // anyPolicy is forbidden on a Root or an Intermediate: it would let an issuer assert every
        // policy at once, which defeats the point of naming one.
        if oid_tlv.val == any_policy_der.as_slice() && role != CertRole::Leaf {
            return Err(Rcc16Error(
                "An AnyPolicy certificate policy was found on a Root or Intermediate certificate"
                    .into()));
        }
        // POLICY QUALIFIERS are forbidden — anything after the policy identifier is one.
        if read_tlv(pi.val, &mut r).is_some() {
            return Err(Rcc16Error(format!(
                "Invalid certificate policies extension: {what} carries policy qualifiers")));
        }
        if let Some(want) = e2ee_der.as_deref() {
            if oid_tlv.val == want { e2ee_count += 1; }
        }
    }
    // "Wrong number of E2EE certificate policies: expected 1 but got N" — now SPECIFIC to the E2EE
    // policy rather than a count of policies in general, which is what made it worth waiting for
    // the OID: counting all policies is a different rule and wrong in both directions.
    if E2EE_POLICY_OID.is_some() && role == CertRole::Leaf && e2ee_count != 1 {
        return Err(Rcc16Error(format!(
            "Wrong number of E2EE certificate policies: expected 1 but got {e2ee_count}")));
    }
    Ok(())
}

/// The `vendorId` an INTEGER-valued 2.23.146.2.1.6 extension carries, normalised.
pub(crate) fn cert_vendor_id(der: &[u8]) -> Option<Vec<u8>> {
    let cert = Certificate::from_der(der).ok()?;
    let exts = cert.tbs_certificate.extensions.as_ref()?;
    for e in exts.iter() {
        if e.extn_id.as_bytes() == OID_VENDOR_ID {
            let raw = e.extn_value.as_bytes();
            let mut p = 0;
            let t = read_tlv(raw, &mut p)?;
            if t.tag == 0x02 { return Some(normalize_serial(t.val)); }
            // Some encoders wrap it in an OCTET STRING; unwrap once and retry.
            let inner = unwrap_octet_string(raw)?;
            let mut q = 0;
            let t2 = read_tlv(inner, &mut q)?;
            if t2.tag == 0x02 { return Some(normalize_serial(t2.val)); }
            return None;
        }
    }
    None
}

/// The leaf's `vendorId`, which lives inside the .4 ParticipantInformation rather than in its own
/// extension: the .4's first element is the vendor id INTEGER.
pub(crate) fn leaf_vendor_id(leaf_der: &[u8]) -> Option<Vec<u8>> {
    let cert = Certificate::from_der(leaf_der).ok()?;
    let exts = cert.tbs_certificate.extensions.as_ref()?;
    for e in exts.iter() {
        if e.extn_id.as_bytes() == OID_PARTICIPANT_INFO {
            let raw = e.extn_value.as_bytes();
            let inner = unwrap_octet_string(raw).unwrap_or(raw);
            let mut p = 0;
            let seq = read_tlv(inner, &mut p).filter(|t| t.tag == 0x30)?;
            let mut q = 0;
            let first = read_tlv(seq.val, &mut q).filter(|t| t.tag == 0x02)?;
            return Some(normalize_serial(first.val));
        }
    }
    None
}

/// The serial number of a DER certificate, normalised by [`normalize_serial`].
///
/// `pub(crate)` so a caller can build a revocation list FROM a certificate it holds — which is the
/// only ergonomic way to revoke an intermediate CA, since the alternative is hand-transcribing a
/// serial and getting the leading-zero encoding wrong.
pub(crate) fn cert_serial(der: &[u8]) -> Option<Vec<u8>> {
    // Certificate ::= SEQ { tbsCertificate SEQ { [0] version?, serialNumber INTEGER, ... }, ... }
    let mut p = 0usize;
    let cert = read_tlv(der, &mut p).filter(|t| t.tag == 0x30)?;
    let mut q = 0usize;
    let tbs = read_tlv(cert.val, &mut q).filter(|t| t.tag == 0x30)?;
    let mut r = 0usize;
    let first = read_tlv(tbs.val, &mut r)?;
    let serial = if first.tag == 0xA0 {
        read_tlv(tbs.val, &mut r)?          // explicit [0] version present; serial is next
    } else {
        first                                // v1: serial is first
    };
    if serial.tag != 0x02 { return None; }
    Some(normalize_serial(serial.val))
}

#[cfg(test)]
mod profile_tests {
    use super::*;
    fn td(f: &str) -> Vec<u8> { std::fs::read(format!("testdata/{f}")).unwrap() }

    /// The REAL Google root carries a vendorId, and it is Google's (2) — §14.4's table, checked
    /// against the artefact rather than trusted from the doc.
    #[test]
    fn the_real_google_root_carries_vendor_id_two() {
        let v = cert_vendor_id(&td("live_root_google_kds.der"))
            .expect("the live Google KDS root must carry 2.23.146.2.1.6");
        assert_eq!(v, vec![2u8], "§14.4: Google == 2");
    }

    /// Apple's root carries vendorId 1, and Apple roots ship in Google Messages by construction —
    /// which is precisely why binding the leaf's vendor id to a hardcoded 2 rejected every Apple
    /// peer. The trust store is multi-vendor; the check has to be leaf-vs-ROOT.
    #[test]
    fn the_apple_root_carries_a_different_vendor_id() {
        let google = cert_vendor_id(&td("live_root_google_kds.der")).unwrap();
        match cert_vendor_id(&td("live_root_apple_rcs.der")) {
            Some(apple) => {
                assert_eq!(apple, vec![1u8], "§14.4: Apple == 1");
                assert_ne!(apple, google,
                    "if these ever compare equal the whole vendor-binding check is vacuous");
            }
            // Recorded rather than silently skipped: an Apple root without the extension would
            // mean the vendor check cannot bind for Apple chains at all, which is worth knowing.
            None => panic!("the Apple root carries no vendorId extension — the leaf-vs-root \
                            vendor binding cannot be enforced for Apple chains"),
        }
    }

    /// Real deployed CAs all carry an SKI — the evidence behind requiring it on issuers.
    #[test]
    fn real_issuers_carry_a_subject_key_identifier() {
        for f in ["google_kds_ica.der", "google_kds_client_ca.der", "live_root_google_kds.der",
                  "ica.der", "root.der"] {
            assert!(validate_x509_profile(&td(f), CertRole::Intermediate, false).is_ok(),
                "{f} must satisfy the issuer profile");
        }
    }

    /// A REAL Google ICA has NO certificatePolicies. This is the artefact that settles the
    /// "certificatePolicies is required" reading: enforcing it on issuers would reject the actual
    /// deployed chain. Pinned as a test so the rule cannot be quietly reinstated.
    #[test]
    fn a_real_google_ica_has_no_certificate_policies() {
        let cert = Certificate::from_der(&td("google_kds_ica.der")).unwrap();
        let has = cert.tbs_certificate.extensions.as_ref().unwrap().iter()
            .any(|e| e.extn_id.to_string() == OID_CERT_POLICIES);
        assert!(!has, "a real Google ICA carries no certificatePolicies — do not require it");
    }

    /// ECDSA-only is re-imposed HERE, because the delegated verifier does not do it. §14.2.3 calls
    /// this the caveat you must not miss.
    #[test]
    fn only_ecdsa_signature_algorithms_are_permitted() {
        assert!(SIG_ALGS_ECDSA.iter().all(|o| o.starts_with("1.2.840.10045.4.3.")),
            "the permitted set must be the ECDSA arc");
        // The point of the rule: no RSA arc is reachable. 1.2.840.113549.1.1.x is RSA.
        assert!(!SIG_ALGS_ECDSA.iter().any(|o| o.starts_with("1.2.840.113549.")),
            "an RSA algorithm must never be permitted — the bundled PKI implements RSA happily");
    }

    #[test]
    fn only_p256_p384_p521_curves_are_permitted() {
        assert_eq!(CURVES_PERMITTED.len(), 3);
        assert!(CURVES_PERMITTED.contains(&"1.2.840.10045.3.1.7"));   // P-256
        // secp256k1 is the classic near-miss and must NOT be in the set.
        assert!(!CURVES_PERMITTED.contains(&"1.3.132.0.10"));
    }

    /// The AKI absence rules. An AKI naming its issuer by name+serial instead of by key identifier
    /// lets path building be steered by a mutable field.
    #[test]
    fn aki_must_carry_a_key_identifier_and_nothing_else() {
        // SEQ { [0] keyIdentifier }
        let ok = [0x30u8, 0x06, 0x80, 0x04, 1, 2, 3, 4];
        assert!(validate_aki(&ok, "leaf").is_ok());
        // SEQ { [0] keyIdentifier, [2] authorityCertSerialNumber } -> rejected
        let with_serial = [0x30u8, 0x09, 0x80, 0x04, 1, 2, 3, 4, 0x82, 0x01, 0x07];
        assert!(validate_aki(&with_serial, "leaf").is_err());
        // SEQ { } -> no keyIdentifier at all
        let empty = [0x30u8, 0x00];
        assert!(validate_aki(&empty, "leaf").is_err());
    }

    /// anyPolicy is forbidden on a Root or an Intermediate — the half of the policy rule that is
    /// independently specified and needs no E2EE OID, so it ships now.
    #[test]
    fn any_policy_is_forbidden_on_an_issuer_but_not_on_a_leaf() {
        // certificatePolicies ::= SEQ { PolicyInformation ::= SEQ { OID anyPolicy } }
        let any = oid_to_der(OID_ANY_POLICY);
        let pi = der_seq(&[&[&[0x06u8, any.len() as u8][..], &any[..]].concat()]);
        let ext = der_seq(&[&pi]);
        assert!(validate_cert_policies(&ext, CertRole::Root, false, "root").is_err());
        assert!(validate_cert_policies(&ext, CertRole::Intermediate, false, "ica").is_err());
        // On a LEAF, anyPolicy itself is permitted — but the leaf must still carry its one E2EE
        // policy, so pair them to isolate the rule under test from the count rule.
        let e2ee = oid_to_der(E2EE_POLICY_OID.unwrap());
        let pi_e2ee = der_seq(&[&[&[0x06u8, e2ee.len() as u8][..], &e2ee[..]].concat()]);
        let leaf_ext = der_seq(&[&pi, &pi_e2ee]);
        assert!(validate_cert_policies(&leaf_ext, CertRole::Leaf, false, "leaf").is_ok(),
            "anyPolicy is only forbidden on Root and Intermediate");
    }

    #[test]
    fn certificate_policies_must_be_non_critical() {
        // An empty policy list on an ISSUER — isolates criticality from the leaf-only count rule.
        let ext = der_seq(&[]);
        assert!(validate_cert_policies(&ext, CertRole::Intermediate, true, "ica").is_err(),
            "critical certificatePolicies must be refused");
        assert!(validate_cert_policies(&ext, CertRole::Intermediate, false, "ica").is_ok());
    }

    /// The E2EE policy OID, read from a KDS-minted leaf's own certificatePolicies extension.
    ///
    /// It was very nearly something else: 2.23.146.2.1.2 was first proposed as the
    /// `clientIdentifier` SUBJECT ATTRIBUTE on constant-adjacency evidence, and the artefact showed
    /// it is the POLICY. The count rule stayed disabled through that whole exchange rather than
    /// being switched on against a plausible value — which is the only reason the mistake cost
    /// nothing.
    #[test]
    fn the_e2ee_policy_oid_is_proven_from_a_genuine_leaf() {
        assert_eq!(E2EE_POLICY_OID, Some("2.23.146.2.1.2"));
        let leaf = td("lab2_kds_leaf.der");
        let cert = Certificate::from_der(&leaf).unwrap();
        let pol = cert.tbs_certificate.extensions.as_ref().unwrap().iter()
            .find(|e| e.extn_id.to_string() == OID_CERT_POLICIES)
            .expect("the real KDS leaf carries certificatePolicies");
        // Exactly ONE policy, and it is the E2EE one — §14.2.3's "expected 1".
        assert!(validate_cert_policies(pol.extn_value.as_bytes(), CertRole::Leaf,
                                       pol.critical, "leaf").is_ok());
    }

    /// A leaf carrying TWO E2EE policies is rejected, and one carrying NONE is too. Without this
    /// the count rule could be present and vacuous.
    #[test]
    fn the_e2ee_policy_count_must_be_exactly_one() {
        let e2ee = oid_to_der(E2EE_POLICY_OID.unwrap());
        let pi = der_seq(&[&[&[0x06u8, e2ee.len() as u8][..], &e2ee[..]].concat()]);
        let two = der_seq(&[&pi, &pi]);
        assert!(validate_cert_policies(&two, CertRole::Leaf, false, "leaf").is_err(),
            "two E2EE policies must fail 'expected 1 but got 2'");
        let none = der_seq(&[]);
        assert!(validate_cert_policies(&none, CertRole::Leaf, false, "leaf").is_err(),
            "zero E2EE policies must fail too");
    }

    /// The real KDS leaf satisfies the whole profile on the deployment path.
    ///
    /// This is the artefact the entire item was missing. It confirms, in one file: SKI present on
    /// a leaf, exactly one E2EE certificatePolicies, EKU = .3, SAN as a tel: URI, P-256 with
    /// ecdsa-with-SHA384, and a critical .4.
    #[test]
    fn the_reference_kds_leaf_satisfies_the_profile() {
        let leaf = td("lab2_kds_leaf.der");
        validate_x509_profile(&leaf, CertRole::Leaf, false)
            .expect("a KDS-minted leaf must pass the deployment profile");
    }

    /// The subject is a bare commonName whose VALUE is the client UUID — there is no
    /// clientIdentifier attribute on a real leaf, which is why the unknown OID costs nothing.
    #[test]
    fn the_reference_leaf_puts_its_uuid_in_the_common_name() {
        assert!(OID_CLIENT_IDENTIFIER.is_none(), "the attribute OID is still unknown");
        let cert = Certificate::from_der(&td("lab2_kds_leaf.der")).unwrap();
        let subject = cert.tbs_certificate.subject.to_string();
        assert!(subject.contains("CN="), "subject is a bare commonName: {subject}");
        let cn = subject.split("CN=").nth(1).unwrap().trim();
        assert!(is_uuid(cn), "the commonName value is the client UUID: {cn}");
    }

    /// The .4 ParticipantInformation carries its OWN validity window, distinct from the
    /// certificate's notBefore/notAfter — ~76 days, matching the KDS's observed 75.5-day mint.
    /// Recorded because writing an expiry rule against the wrong one of the two is a live hazard.
    #[test]
    fn the_participant_information_has_its_own_validity_window() {
        let cert = Certificate::from_der(&td("lab2_kds_leaf.der")).unwrap();
        let p4 = cert.tbs_certificate.extensions.as_ref().unwrap().iter()
            .find(|e| e.extn_id.as_bytes() == OID_PARTICIPANT_INFO)
            .expect("the real KDS leaf carries .4");
        assert!(p4.critical, "the .4 ParticipantInformation must be CRITICAL");
        let raw = p4.extn_value.as_bytes();
        let inner = unwrap_octet_string(raw).unwrap_or(raw);
        assert!(participant_validity_tlv(inner).is_some(),
            "the .4 carries its own validity window, separate from the cert's");
    }

    #[test]
    fn a_uuid_client_identifier_is_recognised_with_or_without_the_urn_prefix() {
        assert!(is_uuid("f81d4fae-7dec-11d0-a765-00a0c91e6bf6"));
        assert!(!is_uuid("f81d4fae-7dec-11d0-a765-00a0c91e6bfZ"));   // non-hex
        assert!(!looks_like_uuid_shape("some-ordinary-common-name"));
        assert!(!looks_like_uuid_shape("kds-ica-prod-us-central1-20250622"));
    }

    /// THE LAB LEAF NOW PASSES BOTH PROFILES — the fixture gap is closed.
    ///
    /// This test used to assert the OPPOSITE second half: that our lab leaf FAILED the deployment
    /// profile, "a gap in our synthetic CA rather than in the rule". That was an honest thing to pin
    /// while it was true, and it is worth keeping the history visible: the assertion documented a
    /// weakness, and the two conditional relaxations in `validate_x509_profile` existed to keep it
    /// tolerable. Both are gone. `MlsCredential` mints the SKI and the single E2EE
    /// `certificatePolicies`, the chain was regenerated from it, and the rules are unconditional.
    ///
    /// The retired chain is still in `testdata/` (`leaf_pa.der` et al) and still fails the
    /// deployment profile. It is deliberately NOT asserted here: pinning the old fixture's weakness
    /// would keep alive the idea that a leaf without these extensions is an acceptable lab shape.
    #[test]
    fn the_lab_leaf_passes_the_deployment_profile_too() {
        for leaf in ["lab2_leaf_pa.der", "lab2_leaf_pb.der", "lab2_leaf_pc.der"] {
            validate_x509_profile(&td(leaf), CertRole::Leaf, true)
                .unwrap_or_else(|e| panic!("{leaf} lab profile: {}", e.0));
            validate_x509_profile(&td(leaf), CertRole::Leaf, false)
                .unwrap_or_else(|e| panic!("{leaf} deployment profile: {}", e.0));
        }
    }
}

#[cfg(test)]
mod revocation_tests {
    use super::*;

    #[test]
    fn serials_normalise_past_der_sign_padding() {
        // The same number, written with and without the DER sign-padding byte.
        assert_eq!(normalize_serial(&[0x00, 0x80, 0x01]), vec![0x80, 0x01]);
        assert_eq!(normalize_serial(&[0x80, 0x01]), vec![0x80, 0x01]);
        assert_eq!(normalize_serial(&[0x00, 0x00, 0x2a]), vec![0x2a]);
    }

    /// Zero is a legal serial and must not normalise to nothing — an empty list entry would match
    /// every certificate whose serial also normalised to empty.
    #[test]
    fn zero_normalises_to_a_single_zero() {
        assert_eq!(normalize_serial(&[0x00]), vec![0x00]);
        assert_eq!(normalize_serial(&[0x00, 0x00]), vec![0x00]);
    }

    #[test]
    fn serial_is_read_past_the_version_field() {
        // The live Google KDS client CA fixture; whatever its serial is, reading it must succeed
        // and must not return the version number (which would be a 1-byte 0x02).
        let der = include_bytes!("../testdata/google_kds_client_ca.der");
        let sn = cert_serial(der).expect("serial");
        assert!(!sn.is_empty());
        assert_ne!(sn, vec![0x02], "read the version field instead of the serial");
    }

    /// Present-and-empty is the normal state in the field, and must cost nothing and reject nothing.
    #[test]
    fn an_empty_list_revokes_nothing() {
        let v = Rcc16Validator::new(
            X509Validator::new(vec![]).unwrap()).with_revoked_serials(vec![]);
        assert!(v.revoked_serials.is_empty());
    }
}

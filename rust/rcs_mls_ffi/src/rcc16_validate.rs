//
// SPDX-FileCopyrightText: The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

//! RCC.16 credential validation (Annex A.3.8, A.4.1, §14.2.3): mls-rs's `X509Validator` builds the
//! path, and this adds the leaf and CA profiles, revocation, the X.509 profile the delegated
//! verifier does not enforce, and the `.4` ParticipantInformation proof-of-possession.
//! See docs/mls/rust-core.md.
use mls_rs_core::{crypto::SignaturePublicKey, error::IntoAnyError, time::MlsTime};
use mls_rs_identity_x509::{CertificateChain, X509CredentialValidator};
use mls_rs_crypto_rustcrypto::x509::X509Validator;
use x509_cert::{Certificate, der::{Decode, Encode}};
use p256::ecdsa::{Signature, VerifyingKey,
                  signature::hazmat::PrehashVerifier};
use sha2::{Digest, Sha224, Sha256, Sha384, Sha512};

// RCC.16 arc 2.23.146.2.1.x
const OID_EKU_RCSMLS: &str = "2.23.146.2.1.3";   // id-kp-rcsMlsClient
const OID_PARTICIPANT_INFO: &[u8] = &[0x67, 0x81, 0x12, 0x02, 0x01, 0x04];  // 2.23.146.2.1.4
const OID_ACS_PARTICIPANT_INFO: &[u8] = &[0x67, 0x81, 0x12, 0x02, 0x01, 0x05];
const OID_SAN: &str = "2.5.29.17";
const OID_EKU: &str = "2.5.29.37";
const OID_KEY_USAGE: &str = "2.5.29.15";
const MAX_LIFETIME_S: u64 = 76 * 24 * 3600;      // A.4.1: lifetime at most 76 days
const MIN_REMAINING_S: u64 = 30 * 24 * 3600;     // A.4.1: at least 30 days remaining

// ---- RCC.16 §14.2.3 profile OIDs ----
/// `vendorId`, carried in the root certificate (RCC.16 §14.4).
const OID_VENDOR_ID: &[u8] = &[0x67, 0x81, 0x12, 0x02, 0x01, 0x06];  // 2.23.146.2.1.6
const OID_SKI: &str = "2.5.29.14";
const OID_AKI: &str = "2.5.29.35";
const OID_CERT_POLICIES: &str = "2.5.29.32";
const OID_CRL_DP: &str = "2.5.29.31";
const OID_AIA: &str = "1.3.6.1.5.5.7.1.1";
const OID_ANY_POLICY: &str = "2.5.29.32.0";

/// ECDSA with SHA-256/384/512 only. The delegated path builder accepts RSA, so the restriction
/// must be imposed here (RCC.16 §14.2.3).
const SIG_ALGS_ECDSA: &[&str] = &[
    "1.2.840.10045.4.3.2",   // ecdsa-with-SHA-256
    "1.2.840.10045.4.3.3",   // ecdsa-with-SHA-384
    "1.2.840.10045.4.3.4",   // ecdsa-with-SHA-512
];
/// id-ecPublicKey, the only permitted SPKI algorithm.
const OID_EC_PUBLIC_KEY: &str = "1.2.840.10045.2.1";
/// P-256 / P-384 / P-521 only.
const CURVES_PERMITTED: &[&str] = &[
    "1.2.840.10045.3.1.7",   // P-256
    "1.3.132.0.34",          // P-384
    "1.3.132.0.35",          // P-521
];

/// The only extensions permitted: those the RCC.16 §14.2.3 profile inspects.
const EXTENSIONS_PERMITTED: &[&str] = &[
    OID_SKI, OID_KEY_USAGE, OID_SAN, OID_BASIC_CONSTRAINTS, OID_CRL_DP,
    OID_CERT_POLICIES, OID_AKI, OID_EKU, OID_AIA,
    "2.23.146.2.1.4",        // ParticipantInformation
    "2.23.146.2.1.5",        // ACS SignedEncryptionIdentityProof
    "2.23.146.2.1.6",        // vendorId
];

/// X.520 upper bounds for the subject attributes RCC.16 §14.2.3 bounds.
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

/// RCC.16 validator: `X509Validator` chain check plus the RCC.16 profiles.
#[derive(Clone, Debug)]
pub struct Rcc16Validator {
    inner: X509Validator,
    /// Full A.4.1 conformance: the lifetime floors and the subject/policy rules. Off under the
    /// deployment-tolerant peer policy: deployed leaves live about 75 days, so the 30-day floor
    /// would refuse every such peer for part of each certificate's life. Does not govern the `.4`
    /// PoP.
    pub strict: bool,
    /// Whether a failed `.4` proof-of-possession rejects the certificate (default true). Separate
    /// from `strict` so the PoP can be enforced while the lifetime floors stay relaxed; false only
    /// through the `debug.rcs.mls_pop_lenient` escape hatch.
    pub pop_strict: bool,
    /// The queried MSISDN (E.164). When set, the leaf's SAN `tel:` URI must equal it (A.4.1);
    /// when `None`, only a well-formed `tel:` URI is required.
    pub expected_msisdn: Option<String>,
    /// Revoked certificate serials from the host, usually empty. RCC.16 leaves are not revocable,
    /// so revoking an intermediate is the only lever against a compromised issuer. Compared as
    /// integer values with leading zeroes stripped.
    pub revoked_serials: Vec<Vec<u8>>,
}
impl Rcc16Validator {
    pub fn new(inner: X509Validator) -> Self {
        Self { inner, strict: true, pop_strict: true, expected_msisdn: None,
               revoked_serials: Vec::new() }
    }
    /// Replaces the revocation list.
    pub fn with_revoked_serials(mut self, serials: Vec<Vec<u8>>) -> Self {
        self.revoked_serials = serials.into_iter().map(|s| normalize_serial(&s)).collect();
        self
    }
    /// Sets the queried MSISDN, enforcing SAN identity equality.
    pub fn with_expected_msisdn(mut self, msisdn: Option<String>) -> Self {
        self.expected_msisdn = msisdn; self
    }
}

impl X509CredentialValidator for Rcc16Validator {
    type Error = Rcc16Error;
    fn validate_chain(&self, chain: &CertificateChain, ts: Option<MlsTime>)
            -> Result<SignaturePublicKey, Rcc16Error> {
        // 1) chain validation: path to a trusted root, signatures, expiry
        let pk =
            self.inner.validate_chain(chain, ts).map_err(|e| Rcc16Error(format!("chain: {e:?}")))?;
        // 2) RCC.16 A.4.1 leaf profile
        let leaf_der = chain.leaf().ok_or_else(|| Rcc16Error("empty chain".into()))?;
        validate_leaf_rcc16(leaf_der.as_ref(), ts, self.strict, self.pop_strict,
                            self.expected_msisdn.as_deref())?;
        // 3) Revocation, on every certificate including the leaf; before the CA profile so a
        //    revoked issuer is reported as revoked.
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
        // 4) RCC.16 A.2 CA profile on every issuer, so an end-entity certificate cannot issue.
        for (i, ca) in chain.iter().enumerate().skip(1) {
            validate_ca_rcc16(ca.as_ref())
                .map_err(|e| Rcc16Error(format!("chain[{i}]: {}", e.0)))?;
        }

        // 5) RCC.16 §14.2.3 X.509 profile on every certificate; mls-rs enforces none of it.
        let n = chain.len();
        for (i, c) in chain.iter().enumerate() {
            let role = if i == 0 { CertRole::Leaf }
                       else if i == n - 1 && n > 1 { CertRole::Intermediate }
                       else { CertRole::Intermediate };
            validate_x509_profile(c.as_ref(), role, self.strict)
                .map_err(|e| Rcc16Error(format!("chain[{i}]: {}", e.0)))?;
        }

        // 6) Chain length. RCC.16 §14.2.3 asks for at least 3 including the root, but our chain
        //    excludes the trust anchor, so the check is a leaf plus at least one issuer.
        if n < 2 {
            return Err(Rcc16Error(format!(
                "Certificate chain length must be >= 3, was {n} \
                 (counting leaf + issuers; the trust anchor is supplied separately)")));
        }

        // 7) The leaf's vendorId (inside its .4) must equal the root's (RCC.16 §14.2.3, §14.4),
        //    so one vendor's CA cannot mint leaves claiming another. Skipped when either side has
        //    none; our test CAs mint none.
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

    let mut san_val: Option<&[u8]> = None;
    let mut eku_val: Option<&[u8]> = None;
    let mut ku_val: Option<&[u8]> = None;
    let mut p4_val: Option<&[u8]> = None;
    let mut p4_critical = false;
    // .5 id-acsParticipantInformation, the ACS SignedEncryptionIdentityProof
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

    // A.4.1.1 / A.3.8.7: the EKU must be exactly id-kp-rcsMlsClient; any further purpose rejects.
    let eku = eku_val.ok_or_else(|| Rcc16Error("A.4.1: missing EKU".into()))?;
    if !eku_is_exactly(eku, OID_EKU_RCSMLS) {
        return Err(Rcc16Error("A.4.1.1: EKU must be EXACTLY id-kp-rcsMlsClient (2.23.146.2.1.3); no other EKU permitted".into()));
    }
    // A.4.1: KeyUsage asserts digitalSignature.
    let ku = ku_val.ok_or_else(|| Rcc16Error("A.4.1: missing KeyUsage".into()))?;
    if !key_usage_has_digital_signature(ku) {
        return Err(Rcc16Error("A.4.1: KeyUsage lacks digitalSignature".into()));
    }
    // A.4.1: the SAN carries a tel: URI equal to the queried MSISDN when one is set, otherwise
    // any tel: URI.
    let san = san_val.ok_or_else(|| Rcc16Error("A.4.1: missing SAN".into()))?;
    match expected_msisdn {
        Some(want) => {
            if !san_tel_uri_equals(san, want) {
                return Err(Rcc16Error(format!(
                    "A.4.1: SAN tel: URI does not match queried MSISDN {want}")));
            }
        }
        None => {
            // no expected MSISDN: presence only
            if !san_has_tel_uri(san) {
                return Err(Rcc16Error("A.4.1: SAN lacks a tel: URI identity".into()));
            }
        }
    }
    // ---- A.4.1 lifetime floors ----
    // RCC.16 requires them and §14.2.3 does not; deployed leaves would fail the 30-day floor for
    // part of their life, so they apply only under `strict` (the RCC.16 strict peer policy).
    let nb = tbs.validity.not_before.to_unix_duration().as_secs();
    let na = tbs.validity.not_after.to_unix_duration().as_secs();
    if strict && na.saturating_sub(nb) > MAX_LIFETIME_S {
        return Err(Rcc16Error(format!("A.4.1: lifetime {} d > 76 d", (na - nb) / 86400)));
    }
    // At least 30 days remaining. mls-rs passes no timestamp on some paths; then use the trusted
    // system clock, and reject when there is none rather than skip.
    let now = ts.map(|t| t.seconds_since_epoch())
        .or_else(trusted_now_secs)
        .ok_or_else(|| Rcc16Error(
            "A.4.1: no trusted time source for the >=30-day remaining-lifetime floor (fail-closed)".into()))?;
    if strict && na.saturating_sub(now) < MIN_REMAINING_S {
        return Err(Rcc16Error(format!("A.4.1: only {} d remaining < 30 d", na.saturating_sub(now) / 86400)));
    }
    // Without `strict` the certificate must still be unexpired.
    if !strict && now >= na {
        return Err(Rcc16Error(format!(
            "leaf expired {} d ago", now.saturating_sub(na) / 86400)));
    }
    // .4 ParticipantInformation must be present and critical.
    let p4 = p4_val.ok_or_else(|| Rcc16Error("A.3.8.9: missing critical ParticipantInformation ext .4".into()))?;
    if !p4_critical {
        return Err(Rcc16Error("A.3.8.9: ParticipantInformation ext .4 must be CRITICAL".into()));
    }
    // The .4 value is an octet string wrapping the sequence.
    let p4_inner = unwrap_octet_string(p4).unwrap_or(p4);

    // The .4 validity window is enforced on every setting, outside the lenient PoP arm below: an
    // expired binding is expired whatever the signature check concludes.
    if let Some(v) = participant_validity_tlv(p4_inner) {
        check_participant_validity(v, now)?;
    }

    // A .4 carrying key rolls is refused on every setting, outside the lenient arm:
    // A.4.1.1(1c-ii) requires verifying the roll chain, which is not implemented, and a
    // structural refusal must not be disarmed by the lever that tolerates signatures.
    if let Some((n, tag)) = participant_trailing_after_spki(p4_inner) {
        return Err(trailing_after_spki_error(n, tag));
    }

    // .5 ACS proof expiry: decoded and logged, refused only once ACS_PROOF_EXPIRY_ENFORCED is set.
    // Deliberately outside both levers, like the two checks above; a source-scan test pins it.
    match acs_proof_verdict(p5_val, now) {
        AcsProofVerdict::Absent => ACS_PROOF_ABSENT_ONCE.call_once(|| {
            crate::ffi::alog!(
                "rcc16: .5 ACS proof ABSENT on a validated leaf (said ONCE per process). Expected \
                 for our own test-minted leaves, which carry no .5 at all. If real peers also \
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
                // lenient: logged; the A.4.1 checks above still apply
                crate::ffi::alog!("rcc16: .4 PoP tolerated (lenient): {}", e.0);
                Ok(())
            }
        }
    }
}

/// Element [1] of `.4`, its validity window, read without the PoP reconstruction so the expiry
/// check can run outside the lenient gate.
fn participant_validity_tlv(p4: &[u8]) -> Option<&[u8]> {
    let mut pos = 0;
    let seq = read_tlv(p4, &mut pos).filter(|t| t.tag == 0x30)?;
    let mut ip = 0;
    let _serial = read_tlv(seq.val, &mut ip).filter(|t| t.tag == 0x02)?;
    read_tlv(seq.val, &mut ip).filter(|t| t.tag == 0x30).map(|t| t.val)
}

/// Anything in `.4` after the five members A.3.8.9 defines, as `(byte count, first tag)`: where
/// `participantKeyRolls [0]` would sit. `None` when `.4` is not the five-member shape at all,
/// which `verify_participant_pop` reports more precisely.
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

/// The one refusal text both readers of `participant_trailing_after_spki` emit.
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
    /// Seconds since the epoch after which the attestation expires.
    pub expiry_seconds: u64,
    /// Length of the DER `Ecdsa-Sig-Value` that follows.
    pub signature_len: usize,
    /// Width of the MLS varint length prefix: 1, 2 or 4 bytes.
    pub varint_len_bytes: usize,
}

/// Decodes the `.5` value; reads the expiry and verifies nothing (no ACS anchor is available).
///
/// ```text
///   uint64 BE expiry_seconds  ||  MLS varint len  ||  DER ECDSA-Sig-Value
/// ```
///
/// All three varint widths (RFC 9420 §2.1.2) are decoded; only the 2-byte form has been seen from
/// peers. A length that does not account for exactly the remaining bytes is refused.
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

/// What the `.5` proof says about time.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AcsProofVerdict {
    /// No `.5` at all. Not an error: presence and expiry are separate decisions, and our own
    /// leaves carry none.
    Absent,
    /// Present, decodes, not expired; seconds remaining.
    Fresh { remaining: u64 },
    /// Present, decodes, expired; seconds since expiry.
    Expired { since: u64 },
    /// Present and undecodable; not reported as expired.
    Undecodable(String),
}

/// Whether an expired `.5` refuses the credential. A compile-time constant rather than a sysprop,
/// so no runtime lever can disarm it; off until counts from real peers support refusing.
const ACS_PROOF_EXPIRY_ENFORCED: bool = false;

/// Logs a leaf without `.5` once per process, so a count of zero expired proofs can be told apart
/// from peers sending none.
static ACS_PROOF_ABSENT_ONCE: std::sync::Once = std::sync::Once::new();

/// Classifies a leaf's `.5` against `now`. Pure. Takes the raw extension value and unwraps the
/// octet string itself.
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

/// The refusal for an expired `.5`, kept separate so its text is pinned by a test before it is
/// enabled.
fn acs_proof_expired_error(since: u64) -> Rcc16Error {
    Rcc16Error(format!(
        "§7.12: the ACS SignedEncryptionIdentityProof (.5) EXPIRED {} d ago. The ACS's attestation \
         of this participant's identity is no longer current; some peers refuse this as \
         CertificateValidationAcsParticipantInformationSignatureExpired", since / 86400))
}

/// The `.4` validity window, `SEQUENCE { notBefore, notAfter }`, checked against the trusted clock.
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

/// DER UTCTime (0x17) or GeneralizedTime (0x18) to Unix seconds; a two-digit year 50..99 is 19xx
/// (RFC 5280).
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
    // days from the civil epoch (Howard Hinnant's algorithm)
    let y = if mo <= 2 { year - 1 } else { year };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let doy = (153 * (if mo > 2 { mo - 3 } else { mo + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    u64::try_from(days * 86400 + h * 3600 + mi * 60 + sec).ok()
}

const OID_BASIC_CONSTRAINTS: &str = "2.5.29.19";
/// RCC.16 A.2.5: an intermediate CA's validity is at most 1827 days.
const CA_MAX_LIFETIME_S: u64 = 1827 * 24 * 3600;
/// RCC.16 A.1.5: a root's validity is at most 3652 days.
const ROOT_MAX_LIFETIME_S: u64 = 3652 * 24 * 3600;

/// RCC.16 CA profile for every issuer above the leaf: A.2 for an intermediate, A.1 for a
/// self-signed root. Path verification alone does not check that a certificate may act as a CA.
/// An EKU is tolerated if it is exactly `id-kp-rcsMlsClient`: A.2.8 does not list it, but
/// deployed CAs carry it.
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

    // A.2.8.5: BasicConstraints present, critical, cA true.
    let (bc_val, bc_crit) = bc.ok_or_else(|| Rcc16Error(
        "A.2.8.5: no BasicConstraints — this certificate is not a CA and must not issue".into()))?;
    if !bc_crit {
        return Err(Rcc16Error("A.2.8.5: BasicConstraints must be CRITICAL".into()));
    }
    if !basic_constraints_is_ca(bc_val) {
        return Err(Rcc16Error("A.2.8.5: cA is not TRUE — not permitted to issue certificates".into()));
    }

    // A.2.8.3: KeyUsage present, critical, keyCertSign set, nothing beyond keyCertSign/cRLSign.
    let (ku_val, ku_crit) = ku.ok_or_else(|| Rcc16Error("A.2.8.3: no KeyUsage".into()))?;
    if !ku_crit {
        return Err(Rcc16Error("A.2.8.3: KeyUsage must be CRITICAL".into()));
    }
    let bits =
        key_usage_bits(ku_val).ok_or_else(|| Rcc16Error("A.2.8.3: malformed KeyUsage".into()))?;
    // RFC 5280 bit order, MSB first: 5 = keyCertSign, 6 = cRLSign.
    const KEY_CERT_SIGN: u16 = 1 << (15 - 5);
    const CRL_SIGN: u16 = 1 << (15 - 6);
    if bits & KEY_CERT_SIGN == 0 {
        return Err(Rcc16Error("A.2.8.3: keyCertSign not asserted — cannot sign certificates".into()));
    }
    if bits & !(KEY_CERT_SIGN | CRL_SIGN) != 0 {
        return Err(Rcc16Error(format!(
            "A.2.8.3: KeyUsage {bits:#06x} sets bits beyond keyCertSign/cRLSign")));
    }

    // A.3.8.7 on a CA: an EKU is optional, but if present it must be id-kp-rcsMlsClient.
    if let Some(e) = eku {
        if !eku_is_exactly(e, OID_EKU_RCSMLS) {
            return Err(Rcc16Error(
                "A.2: CA EKU present but not exactly id-kp-rcsMlsClient (2.23.146.2.1.3)".into()));
        }
    }

    // Validity: a self-signed root gets A.1.5's 3652 days, an intermediate A.2.5's 1827.
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
        // Report the excess in seconds; a day count truncates to the limit and hides it.
        return Err(Rcc16Error(format!(
            "{sec}: {} lifetime {} d ({} s) exceeds {days} d by {} s",
            if self_signed { "root" } else { "intermediate CA" },
            span / 86400, span, span - limit)));
    }
    Ok(())
}

/// BasicConstraints: a sequence of `cA` (boolean, default false) and an optional path length.
fn basic_constraints_is_ca(ext_der: &[u8]) -> bool {
    let mut p = 0;
    if let Some(seq) = read_tlv(ext_der, &mut p) {
        if seq.tag == 0x30 {
            let mut ip = 0;
            if let Some(b) = read_tlv(seq.val, &mut ip) {
                // cA comes first when present; absent means false.
                return b.tag == 0x01 && b.val.first().is_some_and(|v| *v != 0);
            }
        }
    }
    false
}

/// KeyUsage bit string as a big-endian u16 of its first two octets (bit 0 = MSB).
fn key_usage_bits(ext_der: &[u8]) -> Option<u16> {
    let mut p = 0;
    let bs = read_tlv(ext_der, &mut p)?;
    if bs.tag != 0x03 || bs.val.is_empty() { return None; }
    let body = &bs.val[1..];               // skip the unused-bits count
    let hi = *body.first()? as u16;
    let lo = body.get(1).copied().unwrap_or(0) as u16;
    Some((hi << 8) | lo)
}

/// Verifies the `.4` PoP: rebuilds A.3.8.9 `tbsParticipantInfo` = SEQ{ leafSubject, vendorId,
/// participantSignatureValidity, leaf subjectPublicKeyInfo, leafSAN } and checks the ECDSA
/// signature with the participant key from `.4[4]`. Element 4 is the certified subject key; the
/// verifying key is the participant key. Only a fixture with two distinct keys tells them apart.
fn verify_participant_pop(p4: &[u8], tbs: &x509_cert::certificate::TbsCertificate, san_ext: &[u8])
        -> Result<(), Rcc16Error> {
    // .4 = SEQ { vendorId, validity, SEQ{oid}, sig bit string, SPKI }
    let mut pos = 0;
    let seq = read_tlv(p4, &mut pos).filter(|t| t.tag == 0x30).ok_or_else(|| Rcc16Error(".4 not a SEQUENCE".into()))?;
    let mut ip = 0; let inner = seq.val;
    let serial = read_tlv(inner, &mut ip).filter(|t| t.tag == 0x02).ok_or_else(|| Rcc16Error(".4[0] serial".into()))?;
    let validity = read_tlv(inner, &mut ip).filter(|t| t.tag == 0x30).ok_or_else(|| Rcc16Error(".4[1] validity".into()))?;
    let sigalg = read_tlv(inner, &mut ip).ok_or_else(|| Rcc16Error(".4[2] sigalg".into()))?;
    let sigbits = read_tlv(inner, &mut ip).filter(|t| t.tag == 0x03).ok_or_else(|| Rcc16Error(".4[3] sig".into()))?;
    let spki = read_tlv(inner, &mut ip).filter(|t| t.tag == 0x30).ok_or_else(|| Rcc16Error(".4[4] spki".into()))?;

    // Anything after element 5 (participantKeyRolls) is refused. The enforced copy of this check
    // is in validate_leaf_rcc16, outside the lenient gate; this one covers direct callers.
    if let Some((n, tag)) = participant_trailing_after_spki(p4) {
        return Err(trailing_after_spki_error(n, tag));
    }

    // The .4 participant key is distinct from the certified key, so no equality is required; the
    // vendor id is the one the .4 carries.
    if serial.val.is_empty() {
        return Err(Rcc16Error(".4 vendorId missing".into()));
    }
    // Elements 1 (subject), 4 (SPKI) and 5 (SAN) come from the leaf; vendorId and the validity
    // window from the .4.
    let subject_der = tbs.subject.to_der().map_err(|e| Rcc16Error(format!("subject: {e}")))?;
    // A.3.8.9 element 4: the leaf's certified key, not .4[4].
    let leaf_spki_der = tbs.subject_public_key_info.to_der()
        .map_err(|e| Rcc16Error(format!("leaf spki: {e}")))?;
    // san_ext is the GeneralNames SEQUENCE (element 5).
    let recon = der_seq(&[&subject_der, serial.full, validity.full, &leaf_spki_der, san_ext]);

    // The hash is taken from .4[2]: SHA-224/256/384/512 are all accepted, a deliberate superset of
    // what other clients accept (SHA-256, SHA-384). See docs/mls/rust-core.md.
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
    let point =
        sec1_point_from_spki(spki.full).ok_or_else(|| Rcc16Error(".4 spki not P-256 SEC1".into()))?;
    // Every error on this path names the check, so a wrong fixture cannot pass as a bad PoP.
    let vk = VerifyingKey::from_sec1_bytes(&point)
        .map_err(|e| Rcc16Error(format!(".4 ParticipantInformation PoP: participant key is not a \
                                         valid P-256 point: {e}")))?;
    // skip the bit string's unused-bits octet
    let sig_der = &sigbits.val[1..];
    let sig = Signature::from_der(sig_der)
        .map_err(|e| Rcc16Error(format!(".4 ParticipantInformation PoP: signature is not a valid \
                                         ECDSA-Sig-Value: {e}")))?;
    // verify_prehash truncates the digest to the field size (FIPS 186-4), so SHA-384 over P-256
    // works.
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
    // An EKU sequence holding exactly one OID, equal to `oid` (A.4.1.1).
    let want = oid_to_der(oid);
    let mut p = 0;
    if let Some(seq) = read_tlv(ext_der, &mut p) {
        if seq.tag == 0x30 {
            let mut ip = 0; let mut count = 0usize; let mut matched = false;
            while let Some(o) = read_tlv(seq.val, &mut ip) {
                if o.tag != 0x06 { return false; }  // only OIDs are legal in an EKU
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
    // bit 0 (MSB of the first content octet) = digitalSignature
    let mut p = 0;
    if let Some(bs) = read_tlv(ext_der, &mut p) {
        if bs.tag == 0x03 && bs.val.len() >= 2 { return bs.val[1] & 0x80 != 0; }
    }
    false
}
fn san_has_tel_uri(ext_der: &[u8]) -> bool {
    // SAN = SEQUENCE OF GeneralName; [6] IA5String URI
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
    // A.4.1 identity equality: some tel: URI in the SAN equals the queried MSISDN (E.164 digits).
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
/// The leaf SAN's `tel:` number as bare E.164 digits, or `None`. For callers that know the queried
/// MSISDN; the validator itself sees every peer's credential and cannot hold one expectation.
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

/// The leaf's participant key, the raw `.4[4]` SubjectPublicKeyInfo DER (RCC.16 A.3.8): distinct
/// from the certified key, it identifies which participant key signed the leaf. Returns the SPKI,
/// not a hash. `None` (unparseable, no `.4`) must not be read as a stale key.
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
    // Re-encode the whole SPKI TLV so keys differing only in parameters do not collide.
    let mut full = Vec::with_capacity(spki.val.len() + 8);
    full.push(0x30);
    encode_der_len(&mut full, spki.val.len());
    full.extend_from_slice(spki.val);
    Some(full)
}

/// Minimal DER length encoder.
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

/// Bare E.164 digits of a `tel:` URI or raw MSISDN: drops the scheme, any URI parameters and
/// visual separators.
fn normalize_e164(s: &[u8]) -> Vec<u8> {
    let s = s.strip_prefix(b"tel:").unwrap_or(s);
    let mut out = Vec::new();
    for &b in s {
        match b {
            b';' => break,             // tel-URI parameters follow
            b'0'..=b'9' => out.push(b),
            _ => {}                    // separators
        }
    }
    out
}
/// Trusted wall clock in seconds, for when mls-rs passes no timestamp.
fn trusted_now_secs() -> Option<u64> {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .ok()
        .map(|d| d.as_secs())
}
fn sec1_point_from_spki(spki_der: &[u8]) -> Option<Vec<u8>> {
    // SPKI = SEQ{ AlgId, key bit string }; returns the 65-byte SEC1 point.
    let mut p = 0; let seq = read_tlv(spki_der, &mut p)?; if seq.tag != 0x30 { return None; }
    let mut ip = 0; let _alg = read_tlv(seq.val, &mut ip)?; let bs = read_tlv(seq.val, &mut ip)?;
    if bs.tag == 0x03 && bs.val.len() == 66 && bs.val[1]
        == 0x04 { Some(bs.val[1..].to_vec()) } else { None }
}


/// The instant test fixtures are validated at: inside every pki2 leaf window with more than 30 days
/// left, and inside the v1 leaves' window. Fixtures have capped lifetimes, so tests never read the
/// wall clock. `ffi.rs` pins the same constant at the identity-provider seam, the commit time and
/// the leaf anchor's clock; `rcc16::x509_roundtrip` passes it to every step. Re-minting the
/// fixtures means re-pinning this.
#[cfg(test)]
pub(crate) const PKI2_NOW: u64 = 1786752000;

#[cfg(test)]
mod tests {
    use super::*;
    use mls_rs_identity_x509::DerCertificate;
    fn td(f: &str) -> Vec<u8> { std::fs::read(format!("testdata/{f}")).unwrap() }

    /// The current test chain (`pki2_*`), full RCC.16 §14.2.3 leaf profile; its CA keys are
    /// committed so it can be re-issued.
    fn chain(leaf: &str) -> CertificateChain {
        CertificateChain::from(vec![DerCertificate::from(td(leaf)),
                                    DerCertificate::from(td("pki2_ica.der"))])
    }
    fn validator() -> Rcc16Validator {
        Rcc16Validator::new(
            X509Validator::new(vec![DerCertificate::from(td("pki2_root.der"))]).unwrap())
    }
    /// The retired chain, kept for the deliberately non-conformant `leaf_a` and `leaf_b`.
    fn chain_v1(leaf: &str) -> CertificateChain {
        CertificateChain::from(vec![DerCertificate::from(td(leaf)),
                                    DerCertificate::from(td("ica.der"))])
    }
    fn validator_v1() -> Rcc16Validator {
        Rcc16Validator::new(X509Validator::new(vec![DerCertificate::from(td("root.der"))]).unwrap())
    }
    use crate::rcc16_validate::PKI2_NOW;

    // ---- .5 ACS proof expiry -----------------------------------------------------------------

    /// The `.5` inner bytes of a leaf, extracted as `validate_leaf_rcc16` does.
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

    /// The reference leaf's `.5`, decoded to the byte; values are spelled out, not recomputed.
    #[test]
    fn acs_proof_decodes_to_its_documented_bytes() {
        let inner = p5_inner_of(&td("pki2_kds_leaf.der"));
        assert_eq!(inner.len(), 81, "8 B expiry + 2 B varint + 71 B signature");
        assert_eq!(&inner[0..8], &[0x00, 0x00, 0x00, 0x00, 0x6a, 0xc2, 0xc4, 0x28]);
        assert_eq!(&inner[8..10], &[0x40, 0x47]);
        let p = parse_acs_proof(&inner).expect("a real .5 must decode");
        assert_eq!(p.expiry_seconds, 1791149096, "2026-10-04T21:24:56Z");
        assert_eq!(p.signature_len, 71);
        assert_eq!(p.varint_len_bytes, 2);
        // the signature is a DER sequence of two integers
        assert_eq!(inner[10], 0x30);
        assert_eq!(inner[11] as usize, 71 - 2);
    }

    /// On this leaf the proof outlives the certificate, so the certificate expiry check is the
    /// tighter one here.
    #[test]
    fn acs_proof_outlives_its_own_certificate() {
        use x509_cert::Certificate;
        use x509_cert::der::Decode;
        let leaf = td("pki2_kds_leaf.der");
        let p = parse_acs_proof(&p5_inner_of(&leaf)).unwrap();
        let cert = Certificate::from_der(&leaf).unwrap();
        let na = cert.tbs_certificate.validity.not_after.to_unix_duration().as_secs();
        assert_eq!(na, 1789766788);
        assert!(p.expiry_seconds > na);
        // In seconds: 1382308 s is 15.9989 days, which floors to 15.
        assert_eq!(p.expiry_seconds - na, 1_382_308, "15.9989 d, i.e. 16.0 d rounded");
        assert_eq!((p.expiry_seconds - na) / 86400, 15, "integer-division days FLOOR to 15");
    }

    /// All three varint widths round-trip; the 1- and 4-byte forms are synthetic.
    #[test]
    fn acs_proof_decodes_all_three_varint_arms() {
        // 1-byte: prefix 00, len 5
        let mut one = vec![0u8; 8];
        one.extend_from_slice(&[0x05, 1, 2, 3, 4, 5]);
        let p = parse_acs_proof(&one).unwrap();
        assert_eq!((p.signature_len, p.varint_len_bytes), (5, 1));

        // 2-byte: prefix 01, len 71, the reference leaf's shape
        let mut two = vec![0u8; 8];
        two.extend_from_slice(&[0x40, 0x47]);
        two.extend_from_slice(&[0u8; 71]);
        let p = parse_acs_proof(&two).unwrap();
        assert_eq!((p.signature_len, p.varint_len_bytes), (71, 2));

        // 4-byte: prefix 10, len 100
        let mut four = vec![0u8; 8];
        four.extend_from_slice(&[0x80, 0x00, 0x00, 0x64]);
        four.extend_from_slice(&[0u8; 100]);
        let p = parse_acs_proof(&four).unwrap();
        assert_eq!((p.signature_len, p.varint_len_bytes), (100, 4));
    }

    /// The expiry is big-endian; a little-endian read gives a far-future date that always looks
    /// valid.
    #[test]
    fn acs_proof_expiry_is_big_endian() {
        let inner = p5_inner_of(&td("pki2_kds_leaf.der"));
        let le = u64::from_le_bytes(inner[0..8].try_into().unwrap());
        let p = parse_acs_proof(&inner).unwrap();
        assert_eq!(p.expiry_seconds, 1791149096);
        assert_ne!(p.expiry_seconds, le);
        assert!(le > 1_000_000_000_000_000_000, "the LE reading is absurd, and always 'valid'");
    }

    /// Framing that does not add up is refused, not read as far as it goes.
    #[test]
    fn acs_proof_refuses_framing_that_does_not_add_up() {
        let mut short = vec![0u8; 8];
        short.extend_from_slice(&[0x40, 0x47]);
        short.extend_from_slice(&[0u8; 70]);               // one byte short of 71
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

    /// The four verdicts against a real `.5`, with `now` either side of its expiry (1791149096).
    #[test]
    fn acs_proof_verdict_classifies_a_real_extension() {
        use x509_cert::Certificate;
        use x509_cert::der::Decode;
        let leaf = td("pki2_kds_leaf.der");
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

    /// An absent `.5` is not an error: presence and expiry are separate decisions.
    #[test]
    fn an_absent_proof_is_absent_not_expired() {
        assert_eq!(acs_proof_verdict(None, u64::MAX), AcsProofVerdict::Absent);
    }

    /// Both arms of the enforcement switch: the verdict and the pinned error text.
    #[test]
    fn an_expired_proof_is_refused_when_enforcement_is_on() {
        // the decision: an expired proof classifies as Expired
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

        // the refusal names the error other clients use
        let e = acs_proof_expired_error(5 * 86400);
        assert!(e.0.contains("EXPIRED 5 d ago"), "{}", e.0);
        assert!(e.0.contains("SignatureExpired"), "{}", e.0);
        assert!(e.0.contains("§7.12"), "{}", e.0);

        // and the switch is off
        assert!(!ACS_PROOF_EXPIRY_ENFORCED,
                "step 2 (counting on real peers) has not happened");
    }

    /// The `.5` expiry decode sits in `validate_leaf_rcc16`, before the lenient arm, and never
    /// inside `verify_participant_pop`; only a source scan can assert placement.
    #[test]
    fn acs_proof_expiry_check_is_outside_the_lenient_gate() {
        let src = include_str!("rcc16_validate.rs");
        // Assembled, so the needle does not match this test's own source.
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

        // Nothing in the .5 path may consult either lever. The window ends at the marker that
        // starts the next block, not at a byte offset.
        let block_end = src[call_at..].find("// .4 PoP verification.")
            .expect("the .4 PoP block still follows the .5 seam") + call_at;
        let window = &src[call_at..block_end];
        assert!(!window.contains("pop_strict"), "the .5 seam must not read pop_strict");
        assert!(!window.contains("if strict"), "the .5 seam must not read strict");

        // The enforcement switch is compile-time; neither runtime setter may appear here.
        assert!(window.contains("ACS_PROOF_EXPIRY_ENFORCED"),
                "the refusal must be gated on the compile-time constant");
        assert!(!window.contains("set_pop_lenient") && !window.contains("set_engine_settings"),
                "the .5 seam must not consult a runtime lever");
    }

    #[test]
    fn rcc16_full_validation() {
        let now = Some(MlsTime::from(PKI2_NOW));
        // 1) valid RCC.16 leaf (A.4.1 and a verified .4 PoP): accepted
        validator().validate_chain(&chain("pki2_leaf_pa.der"), now)
            .expect("valid RCC.16 cert accepted");
        // 2) .4 PoP corrupted: rejected. Same CA, valid certificate signature; only the .4
        //    signature differs, so the rejection is about the PoP.
        let e = validator().validate_chain(&chain("pki2_leaf_badpop.der"), now).unwrap_err();
        assert!(e.0.contains("PoP") || e.0.contains(".4"), "bad-PoP reason: {}", e.0);
        // 3) dummy .4 and no EKU: rejected (A.4.1), on the retired chain
        assert!(validator_v1().validate_chain(&chain_v1("leaf_a.der"), now).is_err());
        println!("RCC.16: valid ACCEPTED; bad .4-PoP REJECTED ({}); non-conformant REJECTED", e.0);
    }

    /// The fixture leaf satisfies RCC.16 §14.2.3 with the SKI and certificatePolicies rules
    /// unconditional.
    #[test]
    fn the_pki2_leaf_satisfies_the_unrelaxed_1423_profile() {
        for leaf in ["pki2_leaf_pa.der", "pki2_leaf_pb.der", "pki2_leaf_pc.der"] {
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
            // passes with both rules on both strictness settings
            validate_x509_profile(&der, CertRole::Leaf, true).unwrap_or_else(
                |e| panic!("{leaf} strict: {}", e.0));
            validate_x509_profile(&der, CertRole::Leaf, false).unwrap_or_else(
                |e| panic!("{leaf} relaxed: {}", e.0));
        }
    }

    /// Two devices of one participant: `pki2_leaf_pa` and `pki2_leaf_pc` share an MSISDN with
    /// different keys and subjects. Both validate, both satisfy SAN equality, and they are distinct
    /// credentials.
    #[test]
    fn two_devices_of_one_participant_are_distinct_and_both_valid() {
        let now = Some(MlsTime::from(PKI2_NOW));
        let (a, _, _) = san_and_p4(&td("pki2_leaf_pa.der"));
        let (c, _, _) = san_and_p4(&td("pki2_leaf_pc.der"));

        assert_eq!(leaf_san_msisdn(&td("pki2_leaf_pa.der")).unwrap(), b"15551110001".to_vec());
        assert_eq!(leaf_san_msisdn(&td("pki2_leaf_pc.der")).unwrap(), b"15551110001".to_vec(),
            "the second device must present the SAME participant MSISDN");
        assert_ne!(a.tbs_certificate.subject, c.tbs_certificate.subject,
            "two devices are two credentials, not one certificate reused");
        assert_ne!(a.tbs_certificate.subject_public_key_info,
                   c.tbs_certificate.subject_public_key_info,
            "distinct device keys — MLS refuses duplicate signature keys in one group");

        // both accepted, with and without the queried MSISDN enforced
        for leaf in ["pki2_leaf_pa.der", "pki2_leaf_pc.der"] {
            validator().validate_chain(&chain(leaf), now)
                .unwrap_or_else(|e| panic!("{leaf}: {}", e.0));
            validator().with_expected_msisdn(Some("+15551110001".to_string()))
                .validate_chain(&chain(leaf), now)
                .unwrap_or_else(|e| panic!("{leaf} with queried MSISDN: {}", e.0));
        }
    }

    /// The SAN value and the .4 inner sequence of a leaf.
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

    /// The reference leaf's .4 PoP verifies; its two distinct keys make it the only fixture that
    /// pins the tbsParticipantInfo layout.
    #[test]
    fn kds_leaf_participant_pop_verifies() {
        let (cert, san, p4) = san_and_p4(&td("pki2_kds_leaf.der"));
        verify_participant_pop(&p4, &cert.tbs_certificate, &san)
            .expect("a real KDS-issued leaf's .4 PoP must verify");
    }

    /// The two strictness axes move independently: the deployment-tolerant policy ships lifetime
    /// relaxed and PoP enforced.
    #[test]
    fn pop_enforcement_is_independent_of_the_deployment_lifetime_relaxation() {
        // At the leaf validator, so the chain's certificatePolicies rule does not interfere.
        let now = Some(MlsTime::from(PKI2_NOW));
        let bad = td("pki2_leaf_badpop.der");
        let good = td("pki2_leaf_pa.der");

        // relaxed deployment, PoP enforced: a bad PoP rejects
        let e = validate_leaf_rcc16(&bad, now, false, true, None).unwrap_err();
        assert!(e.0.contains("PoP") || e.0.contains(".4"),
            "a bad PoP must reject on the relaxed deployment profile too, got: {}", e.0);

        // a good leaf still passes
        validate_leaf_rcc16(&good, now, false, true, None).expect("valid leaf, relaxed deployment");
        validate_leaf_rcc16(&good, now, true, true, None).expect("valid leaf, strict deployment");

        // pop_strict=false tolerates again, on either deployment setting
        validate_leaf_rcc16(&bad, now, false, false, None)
            .expect("pop_strict=false must still tolerate a bad PoP");
        validate_leaf_rcc16(&bad, now, true, false, None)
            .expect("the axes are independent in BOTH directions, not just the shipped one");
    }

    /// A leaf re-encoded with a `participantKeyRolls [0]` element appended inside its `.4`. Its
    /// certificate signature no longer covers the TBS; this is a profile-check fixture.
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
            inner.extend_from_slice(&[0xA0, 0x03, 0x02, 0x01, 0x07]); // [0] { integer 7 }
            let mut rolled = vec![0x30u8];
            encode_der_len(&mut rolled, inner.len());
            rolled.extend_from_slice(&inner);
            e.extn_value = OctetString::new(rolled).expect("octet string");
        }
        cert.to_der().expect("re-encode")
    }

    /// The midpoint of a leaf's validity window, inside it by construction.
    fn leaf_midpoint(der: &[u8]) -> u64 {
        let c = Certificate::from_der(der).expect("leaf parses");
        let nb = c.tbs_certificate.validity.not_before.to_unix_duration().as_secs();
        let na = c.tbs_certificate.validity.not_after.to_unix_duration().as_secs();
        nb + (na - nb) / 2
    }

    /// A `.4` with a trailing element is refused through `validate_leaf_rcc16` on both
    /// `pop_strict` settings; with the hoisted check removed the `false` arm fails.
    #[test]
    fn a_participant_information_with_a_trailing_element_is_refused() {
        let baseline = td("pki2_kds_leaf.der");
        let now = Some(MlsTime::from(leaf_midpoint(&baseline)));

        // the fixture differs from the baseline only by the added element
        assert_eq!(leaf_with_rolled_p4(&baseline).len(), baseline.len() + 5,
            "the only change must be the 5-byte [0] element");

        // the baseline passes, so every rejection below is due to the trailing data
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

        // the check inside the PoP still refuses for direct callers
        let (cert, san, p4) = san_and_p4(&rolled);
        let e = verify_participant_pop(&p4, &cert.tbs_certificate, &san)
            .expect_err("verify_participant_pop is the second reader, not a hole");
        assert!(e.0.contains("trailing") && e.0.contains("key-roll"), "{}", e.0);
    }

    /// The algorithm gate: SHA-224/256/384/512 reach the signature check (and fail there, as the
    /// bytes were signed with SHA-256), while a non-SHA-2 arc byte and a non-ECDSA OID are refused
    /// by the gate itself. Distinct errors tell the two apart.
    #[test]
    fn the_sigalg_gate_takes_every_sha2_variant_and_refuses_non_sha2() {
        let (cert, san, p4) = san_and_p4(&td("pki2_kds_leaf.der"));
        // the .4[2] OID's trailing byte
        let needle: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03];
        let at = p4.windows(needle.len()).position(|w| w == needle)
            .expect("ecdsa-with-SHA* OID present in .4[2]") + needle.len();
        assert_eq!(p4[at], 0x02, "the real-leaf fixture is SHA-256 signed");

        let with = |b: u8| { let mut v = p4.clone(); v[at] = b; v };

        // 03 = SHA-384: the gate opens and the signature check fails
        let e = verify_participant_pop(&with(0x03), &cert.tbs_certificate, &san).unwrap_err();
        assert!(e.0.contains("signature INVALID"),
            "SHA-384 must reach the signature check, got: {}", e.0);

        // 01 and 04 reach the signature check too
        for wider in [0x01u8, 0x04] {
            let e = verify_participant_pop(&with(wider), &cert.tbs_certificate, &san).unwrap_err();
            assert!(e.0.contains("signature INVALID"),
                "variant {wider:#04x} must reach the signature check, got: {}", e.0);
        }
        // no 05 in the arc: refused by the gate
        let e = verify_participant_pop(&with(0x05), &cert.tbs_certificate, &san).unwrap_err();
        assert!(e.0.contains("not an SHA2 variant"), "05 must be gate-refused, got: {}", e.0);
        // a non-ECDSA OID is refused before a hash is chosen
        let mut rsa = p4.clone();
        rsa[at - needle.len()..at].copy_from_slice(&[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01]);
        let e = verify_participant_pop(&rsa, &cert.tbs_certificate, &san).unwrap_err();
        assert!(e.0.contains("not an ecdsa-with-SHA"), "RSA OID must be refused, got: {}", e.0);
        // 02 still verifies
        verify_participant_pop(&p4, &cert.tbs_certificate, &san).expect("SHA-256 still verifies");
    }

    /// A one-key fixture cannot tell the two element-4 layouts apart; a two-key one can.
    #[test]
    fn a_one_key_fixture_cannot_discriminate_the_layout_but_a_two_key_one_can() {
        let (syn, _, _) = san_and_p4(&td("pki2_leaf_pa.der"));
        let (gen, _, gp4) = san_and_p4(&td("pki2_kds_leaf.der"));
        let syn_spki = syn.tbs_certificate.subject_public_key_info.to_der().unwrap();
        let gen_spki = gen.tbs_certificate.subject_public_key_info.to_der().unwrap();
        // .4[4] of each
        let p4_spki = |p4: &[u8]| -> Vec<u8> {
            let mut pos = 0; let seq = read_tlv(p4, &mut pos).unwrap();
            let mut ip = 0;
            for _ in 0..4 { read_tlv(seq.val, &mut ip).unwrap(); }
            read_tlv(seq.val, &mut ip).unwrap().full.to_vec()
        };
        let (_, _, sp4) = san_and_p4(&td("pki2_leaf_pa.der"));
        assert_eq!(p4_spki(&sp4), syn_spki,
            "synthetic fixture reuses one key, so element 4 is ambiguous — this is the blind spot");
        assert_ne!(p4_spki(&gp4), gen_spki,
            "the real KDS leaf must carry TWO distinct keys, or it proves nothing either");
    }

    #[test]
    fn rcc16_san_identity_equality() {
        let now = Some(MlsTime::from(PKI2_NOW));
        // leaf_pa SAN = tel:+15551110001; the right MSISDN is accepted
        let ok = validator().with_expected_msisdn(Some("+15551110001".into()));
        ok.validate_chain(&chain("pki2_leaf_pa.der"), now).expect("matching MSISDN accepted");
        // same digits, other spelling: accepted
        let ok2 = validator().with_expected_msisdn(Some("tel:1-555-111-0001".into()));
        ok2.validate_chain(&chain("pki2_leaf_pa.der"), now).expect("normalized MSISDN accepted");
        // wrong number: rejected
        let bad = validator().with_expected_msisdn(Some("+15559999999".into()));
        let e = bad.validate_chain(&chain("pki2_leaf_pa.der"), now).unwrap_err();
        assert!(e.0.contains("SAN tel: URI does not match"), "wrong-MSISDN reason: {}", e.0);
    }

    /// The SAN identity a caller compares against its queried number.
    #[test]
    fn a_leaf_reports_the_msisdn_it_certifies() {
        assert_eq!(leaf_san_msisdn(&td("pki2_leaf_pa.der")).unwrap(), b"15551110001".to_vec());
        // a certificate for another number reports that number
        assert_eq!(other_digits(&td("pki2_leaf_pb.der")), "15551110002");
        assert!(msisdn_equals(b"tel:+1 (555) 111-0001", b"+15551110001"));
        assert!(!msisdn_equals(b"+15551110001", b"+15559999999"));
        // an absent identity equals nothing
        assert!(!msisdn_equals(b"", b"+15551110001"));
        assert!(!msisdn_equals(b"", b""));
        assert!(leaf_san_msisdn(b"not a certificate").is_none());
    }

    fn other_digits(der: &[u8]) -> String {
        String::from_utf8(leaf_san_msisdn(der).unwrap()).unwrap()
    }

    /// The deployed CA certificates a claimed peer KeyPackage carries must pass, including an EKU
    /// on both CAs and an intermediate at 1825 of 1827 days.
    #[test]
    fn googles_real_ca_chain_satisfies_the_a2_profile() {
        for name in ["google_kds_client_ca.der", "google_kds_ica.der"] {
            validate_ca_rcc16(&td(name))
                .unwrap_or_else(|e| panic!("{name} must satisfy A.2: {}", e.0));
        }
    }

    /// Every root in the fetched trusted-root list passes, at about 3650 days under the A.1.5
    /// root limit rather than the intermediate one.
    #[test]
    fn every_live_trusted_root_satisfies_the_a1_profile() {
        for name in ["live_root_google_kds.der", "live_root_android_mls.der",
                     "live_root_apple_rcs.der"] {
            validate_ca_rcc16(&td(name))
                .unwrap_or_else(|e| panic!("{name} must satisfy A.1: {}", e.0));
        }
    }

    /// A root-length intermediate is still a violation.
    #[test]
    fn an_intermediate_may_not_use_the_root_validity_allowance() {
        // not self-signed: held to A.2.5's 1827 days
        let ica = td("google_kds_ica.der");
        let cert = Certificate::from_der(&ica).unwrap();
        assert_ne!(cert.tbs_certificate.issuer, cert.tbs_certificate.subject,
                   "the ICA must be issuer != subject, or this test proves nothing");
        // self-signed: the longer allowance
        let root = td("live_root_apple_rcs.der");
        let rc = Certificate::from_der(&root).unwrap();
        assert_eq!(rc.tbs_certificate.issuer, rc.tbs_certificate.subject);
    }

    /// A certificate not marked as a CA cannot issue.
    #[test]
    fn an_end_entity_certificate_is_refused_as_an_issuer() {
        let e = validate_ca_rcc16(&td("pki2_leaf_pa.der")).unwrap_err();
        assert!(e.0.contains("A.2.8.5"), "must fail the CA constraint, got: {}", e.0);
    }

    /// The two-digit-year rule and both DER time forms.
    #[test]
    fn der_times_decode_for_both_forms() {
        // 1785369600
        let want = 1785369600u64;
        assert_eq!(der_time_to_unix(0x17, b"260730000000Z"), Some(want));
        assert_eq!(der_time_to_unix(0x18, b"20260730000000Z"), Some(want));
        // RFC 5280: YY >= 50 is 19xx
        let y1995 = der_time_to_unix(0x17, b"950101000000Z").unwrap();
        let y2005 = der_time_to_unix(0x17, b"050101000000Z").unwrap();
        assert!(y1995 < y2005, "50..99 must read as 19xx, 00..49 as 20xx");
        assert_eq!(der_time_to_unix(0x17, b"short"), None);
    }

    /// An expired ParticipantInformation is refused.
    #[test]
    fn an_expired_participant_information_is_refused() {
        // SEQUENCE { UTCTime notBefore, UTCTime notAfter }, a ten-day window in the past
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
        // With no timestamp the validator uses the trusted clock: it accepts or rejects on
        // lifetime, never with the no-clock error.
        match validator().validate_chain(&chain("pki2_leaf_pa.der"), None) {
            Ok(_) => {}
            Err(e) => assert!(
                (e.0.contains("remaining") || e.0.contains("lifetime"))
                && !e.0.contains("no trusted time source"),
                "fallback should decide by lifetime, got: {}", e.0),
        }
    }
}

/// A serial's DER integer value bytes with leading zeroes stripped, so a number matches however
/// it was padded.
fn normalize_serial(v: &[u8]) -> Vec<u8> {
    let start = v.iter().position(|&b| b != 0).unwrap_or(v.len().saturating_sub(1));
    v[start..].to_vec()
}

// ---- RCC.16 §14.2.3 X.509 profile ----
// Path building is delegated to mls-rs, whose verifier accepts more than the profile (RSA), so
// every rule the profile adds on top is imposed here. The clientIdentifier attribute OID is
// unknown; it is used only where a wrong value fails safe.

/// The `clientIdentifier` subject attribute OID, unknown. Real leaves carry the client UUID in
/// commonName instead, which satisfies the "commonName or clientIdentifier" rule.
const OID_CLIENT_IDENTIFIER: Option<&str> = None;

/// The E2EE `certificatePolicies` policy OID; a leaf carries exactly one.
const E2EE_POLICY_OID: Option<&str> = Some("2.23.146.2.1.2");

/// Where in the chain a certificate sits; several rules differ by role.
#[derive(Clone, Copy, PartialEq, Debug)]
pub(crate) enum CertRole { Leaf, Intermediate, Root }

/// The RCC.16 §14.2.3 rules for every certificate. `strict` gates only the rules where full
/// conformance and deployed peers disagree; each gate says so where it applies.
fn validate_x509_profile(der: &[u8], role: CertRole, strict: bool) -> Result<(), Rcc16Error> {
    let cert = Certificate::from_der(der)
        .map_err(|e| Rcc16Error(format!("Invalid certificate: {e}")))?;
    let tbs = &cert.tbs_certificate;
    let what = match role {
        CertRole::Leaf => "leaf",
        CertRole::Intermediate => "intermediate",
        CertRole::Root => "root",
    };

    // ---- version: V3 (a V1/V2 certificate has no extensions to check) ----
    if tbs.version != x509_cert::Version::V3 {
        return Err(Rcc16Error(format!(
            "Invalid version extension: {what} is not a V3 certificate ({:?})", tbs.version)));
    }

    // ---- serial number: non-empty, positive, at most 20 octets ----
    let serial = tbs.serial_number.as_bytes();
    if serial.is_empty() {
        return Err(Rcc16Error(format!("Invalid serial number: {what} serial is empty")));
    }
    if serial[0] & 0x80 != 0 {
        // A leading set bit is a negative integer; RFC 5280 requires a positive serial.
        return Err(Rcc16Error(format!("Invalid serial number: {what} serial is negative")));
    }
    if normalize_serial(serial).len() > SERIAL_MAX_OCTETS {
        return Err(Rcc16Error(format!(
            "Invalid serial number: {what} serial is {} octets, max {SERIAL_MAX_OCTETS}",
            normalize_serial(serial).len())));
    }

    // ---- signature algorithm: ECDSA with SHA-2 only; the delegated verifier accepts RSA ----
    let sig_alg = tbs.signature.oid.to_string();
    if !SIG_ALGS_ECDSA.contains(&sig_alg.as_str()) {
        return Err(Rcc16Error(format!(
            "Invalid signature algorithm extension: {what} uses {sig_alg}; \
             only ECDSA with SHA-256/384/512 is permitted (no RSA arc exists in the profile)")));
    }

    // ---- subject public key: P-256 / P-384 / P-521 only ----
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

    // ---- subject RDNs: single-valued, bounded ----
    validate_subject(&tbs.subject, role, strict)?;

    let exts = match tbs.extensions.as_ref() {
        Some(e) => e,
        // A root may be minimal; a leaf without extensions already failed in validate_leaf_rcc16.
        None if role == CertRole::Root => return Ok(()),
        None => return Err(Rcc16Error(format!("{what} has no extensions"))),
    };

    let mut saw_ski = false;
    let mut saw_policies = false;
    for e in exts.iter() {
        let oid = e.extn_id.to_string();
        let raw = e.extn_value.as_bytes();
        match oid.as_str() {
            // ---- subjectKeyIdentifier: present and well-formed ----
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
            // ---- authorityKeyIdentifier: keyIdentifier [0] present, [1] and [2] absent, so path
            // building is not steered by issuer name and serial ----
            OID_AKI => validate_aki(raw, what)?,
            // ---- certificatePolicies ----
            OID_CERT_POLICIES => {
                saw_policies = true;
                validate_cert_policies(raw, role, e.critical, what)?;
            }
            // ---- the ACS proof must not be critical ----
            _ if e.extn_id.as_bytes() == OID_ACS_PARTICIPANT_INFO => {
                if e.critical {
                    return Err(Rcc16Error(
                        "ACS Participant Information signed identity proof was marked critical."
                            .into()));
                }
            }
            _ => {
                // ---- unexpected extensions: rejected under `strict`, logged otherwise, since
                // the allow-list is derived from the profile's diagnostics and one unlisted
                // extension on a deployed leaf would cut off that peer ----
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

    // ---- presence rules ----
    // SKI is required on every certificate. certificatePolicies only on leaves: deployed
    // intermediates lack it.
    if !saw_ski {
        return Err(Rcc16Error(format!(
            "Invalid subject key identifier extension: Missing subject public key identifier \
             on {what}")));
    }
    if !saw_policies && role == CertRole::Leaf {
        return Err(Rcc16Error(
            "Invalid certificate policies extension: leaf has no certificatePolicies".into()));
    }
    Ok(())
}

/// Subject RDNs: single-valued, bounded C/O/CN/ST/L, and something that identifies the subject.
fn validate_subject(subject: &x509_cert::name::Name, role: CertRole, strict: bool)
        -> Result<(), Rcc16Error> {
    let mut common_name: Option<String> = None;
    let mut client_identifier: Option<String> = None;
    for rdn in subject.0.iter() {
        // Multi-valued RDNs are forbidden: they hide a second attribute from readers of the first.
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
    // Leaf subject must contain commonName or clientIdentifier. Recognising a clientIdentifier can
    // only make this pass, so the unknown OID fails safe here.
    if role == CertRole::Leaf && common_name.is_none() && client_identifier.is_none() {
        return Err(Rcc16Error(
            "Invalid subject extension: leaf subject carries neither commonName nor \
             clientIdentifier".into()));
    }
    // The UUID parse can reject, so it is enforced only under `strict`, where we control the
    // attribute.
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

/// 8-4-4-4-12 hex with dashes. Shape first, so a commonName that merely contains dashes is not
/// taken for a malformed UUID.
fn looks_like_uuid_shape(s: &str) -> bool {
    let parts: Vec<&str> = s.split('-').collect();
    parts.len() == 5 && [8usize, 4, 4, 4, 12].iter().zip(&parts).all(|(n, p)| p.len() == *n)
}
fn is_uuid(s: &str) -> bool {
    looks_like_uuid_shape(s) && s.split('-').all(|p| p.bytes().all(|c| c.is_ascii_hexdigit()))
}

/// `AuthorityKeyIdentifier ::= SEQUENCE { keyIdentifier [0], authorityCertIssuer [1],
/// authorityCertSerialNumber [2] }`, all optional.
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

/// `certificatePolicies ::= SEQUENCE OF PolicyInformation { policyIdentifier, qualifiers }`
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
        // anyPolicy is forbidden on a root or intermediate.
        if oid_tlv.val == any_policy_der.as_slice() && role != CertRole::Leaf {
            return Err(Rcc16Error(
                "An AnyPolicy certificate policy was found on a Root or Intermediate certificate"
                    .into()));
        }
        // policy qualifiers are forbidden
        if read_tlv(pi.val, &mut r).is_some() {
            return Err(Rcc16Error(format!(
                "Invalid certificate policies extension: {what} carries policy qualifiers")));
        }
        if let Some(want) = e2ee_der.as_deref() {
            if oid_tlv.val == want { e2ee_count += 1; }
        }
    }
    // Exactly one E2EE policy on a leaf.
    if E2EE_POLICY_OID.is_some() && role == CertRole::Leaf && e2ee_count != 1 {
        return Err(Rcc16Error(format!(
            "Wrong number of E2EE certificate policies: expected 1 but got {e2ee_count}")));
    }
    Ok(())
}

/// The `vendorId` of a certificate's 2.23.146.2.1.6 extension, normalised.
pub(crate) fn cert_vendor_id(der: &[u8]) -> Option<Vec<u8>> {
    let cert = Certificate::from_der(der).ok()?;
    let exts = cert.tbs_certificate.extensions.as_ref()?;
    for e in exts.iter() {
        if e.extn_id.as_bytes() == OID_VENDOR_ID {
            let raw = e.extn_value.as_bytes();
            let mut p = 0;
            let t = read_tlv(raw, &mut p)?;
            if t.tag == 0x02 { return Some(normalize_serial(t.val)); }
            // some encoders wrap it in an octet string
            let inner = unwrap_octet_string(raw)?;
            let mut q = 0;
            let t2 = read_tlv(inner, &mut q)?;
            if t2.tag == 0x02 { return Some(normalize_serial(t2.val)); }
            return None;
        }
    }
    None
}

/// The leaf's `vendorId`: the first element of its `.4`.
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

/// A DER certificate's serial, normalised by [`normalize_serial`]; lets a caller build a
/// revocation list from a certificate it holds.
pub(crate) fn cert_serial(der: &[u8]) -> Option<Vec<u8>> {
    // Certificate ::= SEQ { tbsCertificate SEQ { [0] version?, serialNumber, ... }, ... }
    let mut p = 0usize;
    let cert = read_tlv(der, &mut p).filter(|t| t.tag == 0x30)?;
    let mut q = 0usize;
    let tbs = read_tlv(cert.val, &mut q).filter(|t| t.tag == 0x30)?;
    let mut r = 0usize;
    let first = read_tlv(tbs.val, &mut r)?;
    let serial = if first.tag == 0xA0 {
        read_tlv(tbs.val, &mut r)?          // explicit [0] version; serial is next
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

    /// The reference root's vendorId is 2 (RCC.16 §14.4).
    #[test]
    fn the_real_google_root_carries_vendor_id_two() {
        let v = cert_vendor_id(&td("live_root_google_kds.der"))
            .expect("the live reference KDS root must carry 2.23.146.2.1.6");
        assert_eq!(v, vec![2u8], "§14.4: the reference vendorId is 2");
    }

    /// Another vendor's root carries vendorId 1; the trust store is multi-vendor, so the check is
    /// leaf against root.
    #[test]
    fn the_apple_root_carries_a_different_vendor_id() {
        let google = cert_vendor_id(&td("live_root_google_kds.der")).unwrap();
        match cert_vendor_id(&td("live_root_apple_rcs.der")) {
            Some(apple) => {
                assert_eq!(apple, vec![1u8], "§14.4: Apple == 1");
                assert_ne!(apple, google,
                    "if these ever compare equal the whole vendor-binding check is vacuous");
            }
            // a root without the extension would leave the vendor check unable to bind
            None => panic!("the Apple root carries no vendorId extension — the leaf-vs-root \
                            vendor binding cannot be enforced for Apple chains"),
        }
    }

    /// Deployed CAs carry an SKI.
    #[test]
    fn real_issuers_carry_a_subject_key_identifier() {
        for f in ["google_kds_ica.der", "google_kds_client_ca.der", "live_root_google_kds.der",
                  "ica.der", "root.der"] {
            assert!(validate_x509_profile(&td(f), CertRole::Intermediate, false).is_ok(),
                "{f} must satisfy the issuer profile");
        }
    }

    /// A deployed intermediate has no certificatePolicies, so the rule is leaf-only.
    #[test]
    fn a_real_google_ica_has_no_certificate_policies() {
        let cert = Certificate::from_der(&td("google_kds_ica.der")).unwrap();
        let has = cert.tbs_certificate.extensions.as_ref().unwrap().iter()
            .any(|e| e.extn_id.to_string() == OID_CERT_POLICIES);
        assert!(!has,
            "a real reference KDS ICA carries no certificatePolicies — do not require it");
    }

    /// ECDSA-only is imposed here, since the delegated verifier does not.
    #[test]
    fn only_ecdsa_signature_algorithms_are_permitted() {
        assert!(SIG_ALGS_ECDSA.iter().all(|o| o.starts_with("1.2.840.10045.4.3.")),
            "the permitted set must be the ECDSA arc");
        // no RSA arc (1.2.840.113549.1.1.x) is reachable
        assert!(!SIG_ALGS_ECDSA.iter().any(|o| o.starts_with("1.2.840.113549.")),
            "an RSA algorithm must never be permitted — the bundled PKI implements RSA happily");
    }

    #[test]
    fn only_p256_p384_p521_curves_are_permitted() {
        assert_eq!(CURVES_PERMITTED.len(), 3);
        assert!(CURVES_PERMITTED.contains(&"1.2.840.10045.3.1.7"));   // P-256
        // secp256k1 is not permitted
        assert!(!CURVES_PERMITTED.contains(&"1.3.132.0.10"));
    }

    /// AKI absence rules.
    #[test]
    fn aki_must_carry_a_key_identifier_and_nothing_else() {
        let ok = [0x30u8, 0x06, 0x80, 0x04, 1, 2, 3, 4];
        assert!(validate_aki(&ok, "leaf").is_ok());
        // with [2] authorityCertSerialNumber: rejected
        let with_serial = [0x30u8, 0x09, 0x80, 0x04, 1, 2, 3, 4, 0x82, 0x01, 0x07];
        assert!(validate_aki(&with_serial, "leaf").is_err());
        // no keyIdentifier at all
        let empty = [0x30u8, 0x00];
        assert!(validate_aki(&empty, "leaf").is_err());
    }

    /// anyPolicy is forbidden on a root or intermediate.
    #[test]
    fn any_policy_is_forbidden_on_an_issuer_but_not_on_a_leaf() {
        let any = oid_to_der(OID_ANY_POLICY);
        let pi = der_seq(&[&[&[0x06u8, any.len() as u8][..], &any[..]].concat()]);
        let ext = der_seq(&[&pi]);
        assert!(validate_cert_policies(&ext, CertRole::Root, false, "root").is_err());
        assert!(validate_cert_policies(&ext, CertRole::Intermediate, false, "ica").is_err());
        // anyPolicy is permitted on a leaf; pair it with the E2EE policy to isolate the rule.
        let e2ee = oid_to_der(E2EE_POLICY_OID.unwrap());
        let pi_e2ee = der_seq(&[&[&[0x06u8, e2ee.len() as u8][..], &e2ee[..]].concat()]);
        let leaf_ext = der_seq(&[&pi, &pi_e2ee]);
        assert!(validate_cert_policies(&leaf_ext, CertRole::Leaf, false, "leaf").is_ok(),
            "anyPolicy is only forbidden on Root and Intermediate");
    }

    #[test]
    fn certificate_policies_must_be_non_critical() {
        // an empty policy list on an issuer isolates criticality from the leaf count rule
        let ext = der_seq(&[]);
        assert!(validate_cert_policies(&ext, CertRole::Intermediate, true, "ica").is_err(),
            "critical certificatePolicies must be refused");
        assert!(validate_cert_policies(&ext, CertRole::Intermediate, false, "ica").is_ok());
    }

    /// The E2EE policy OID as a key-directory leaf carries it.
    #[test]
    fn the_e2ee_policy_oid_is_proven_from_a_genuine_leaf() {
        assert_eq!(E2EE_POLICY_OID, Some("2.23.146.2.1.2"));
        let leaf = td("pki2_kds_leaf.der");
        let cert = Certificate::from_der(&leaf).unwrap();
        let pol = cert.tbs_certificate.extensions.as_ref().unwrap().iter()
            .find(|e| e.extn_id.to_string() == OID_CERT_POLICIES)
            .expect("the real KDS leaf carries certificatePolicies");
        // exactly one policy, the E2EE one
        assert!(validate_cert_policies(pol.extn_value.as_bytes(), CertRole::Leaf,
                                       pol.critical, "leaf").is_ok());
    }

    /// Two E2EE policies are rejected, and so is none.
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

    /// The reference leaf satisfies the whole profile on the deployment path: SKI, one E2EE policy,
    /// EKU .3, tel: SAN, P-256 with ecdsa-with-SHA-384, critical .4.
    #[test]
    fn the_reference_kds_leaf_satisfies_the_profile() {
        let leaf = td("pki2_kds_leaf.der");
        validate_x509_profile(&leaf, CertRole::Leaf, false)
            .expect("a KDS-minted leaf must pass the deployment profile");
    }

    /// A real leaf's subject is a bare commonName holding the client UUID.
    #[test]
    fn the_reference_leaf_puts_its_uuid_in_the_common_name() {
        assert!(OID_CLIENT_IDENTIFIER.is_none(), "the attribute OID is still unknown");
        let cert = Certificate::from_der(&td("pki2_kds_leaf.der")).unwrap();
        let subject = cert.tbs_certificate.subject.to_string();
        assert!(subject.contains("CN="), "subject is a bare commonName: {subject}");
        let cn = subject.split("CN=").nth(1).unwrap().trim();
        assert!(is_uuid(cn), "the commonName value is the client UUID: {cn}");
    }

    /// The `.4` has its own validity window, distinct from the certificate's.
    #[test]
    fn the_participant_information_has_its_own_validity_window() {
        let cert = Certificate::from_der(&td("pki2_kds_leaf.der")).unwrap();
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

    /// The fixture leaf passes both the strict and the deployment profile.
    #[test]
    fn the_pki2_leaf_passes_the_deployment_profile_too() {
        for leaf in ["pki2_leaf_pa.der", "pki2_leaf_pb.der", "pki2_leaf_pc.der"] {
            validate_x509_profile(&td(leaf), CertRole::Leaf, true)
                .unwrap_or_else(|e| panic!("{leaf} strict profile: {}", e.0));
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
        // the same number with and without the sign-padding byte
        assert_eq!(normalize_serial(&[0x00, 0x80, 0x01]), vec![0x80, 0x01]);
        assert_eq!(normalize_serial(&[0x80, 0x01]), vec![0x80, 0x01]);
        assert_eq!(normalize_serial(&[0x00, 0x00, 0x2a]), vec![0x2a]);
    }

    /// Zero normalises to a non-empty value, so an empty entry cannot match every serial.
    #[test]
    fn zero_normalises_to_a_single_zero() {
        assert_eq!(normalize_serial(&[0x00]), vec![0x00]);
        assert_eq!(normalize_serial(&[0x00, 0x00]), vec![0x00]);
    }

    #[test]
    fn serial_is_read_past_the_version_field() {
        // reading the serial succeeds and does not return the version
        let der = include_bytes!("../testdata/google_kds_client_ca.der");
        let sn = cert_serial(der).expect("serial");
        assert!(!sn.is_empty());
        assert_ne!(sn, vec![0x02], "read the version field instead of the serial");
    }

    /// An empty revocation list rejects nothing.
    #[test]
    fn an_empty_list_revokes_nothing() {
        let v = Rcc16Validator::new(
            X509Validator::new(vec![]).unwrap()).with_revoked_serials(vec![]);
        assert!(v.revoked_serials.is_empty());
    }
}

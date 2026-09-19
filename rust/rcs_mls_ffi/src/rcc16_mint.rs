//
// SPDX-FileCopyrightText: The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

//! Throwaway root, intermediate and leaf for the RCC.16 self-test, so the engine can run end to end
//! on one device with no network. Test scaffolding, not a certificate authority; no production
//! enrolment path uses it. Functions return a TBS for Java to sign with JCE, and [`certificate`]
//! assembles the result. Built on `x509-cert` types directly, without its `builder` feature.
//! See docs/mls/rust-core.md.

use crate::rcc16_build::{time_of, Rcc16BuildError, ALGID_ECDSA_SHA256};
use crate::rcc16_validate::{der_int, der_len, der_seq};
use x509_cert::certificate::{TbsCertificate, Version};
use x509_cert::der::asn1::{ObjectIdentifier, OctetString};
#[cfg(test)]
use x509_cert::der::asn1::BitString;
use x509_cert::der::oid::AssociatedOid;
use x509_cert::der::{Decode, Encode};
use x509_cert::ext::pkix::certpolicy::{CertificatePolicies, PolicyInformation};
use x509_cert::ext::pkix::constraints::BasicConstraints;
use x509_cert::ext::pkix::{
    AuthorityKeyIdentifier, ExtendedKeyUsage, KeyUsage, KeyUsages, SubjectKeyIdentifier,
};
use x509_cert::ext::Extension;
use x509_cert::name::Name;
use x509_cert::serial_number::SerialNumber;
use x509_cert::spki::SubjectPublicKeyInfoOwned;
use x509_cert::time::Validity;

/// `id-ce-*` and the RCC.16 arc; `const_oid::db` needs a feature the Soong build does not enable.
const OID_VENDOR_ID: &str = "2.23.146.2.1.6";
const OID_PARTICIPANT_INFO: &str = "2.23.146.2.1.4";
const OID_EKU_RCS_MLS: &str = "2.23.146.2.1.3";

// `2.23.146.2.1.5` (id-acsParticipantInformation, RCC.16 A.3.8.10) is never emitted: it is the
// configuration server's signed encryption-identity proof, which a client cannot sign. Key rolls
// are a field inside `.4` (A.3.8.9), not an extension.
/// The E2EE `certificatePolicies` identifier. RCC.16 §14.2.3 requires exactly one on a leaf,
/// non-critical and without qualifiers.
const OID_E2EE_POLICY: &str = "2.23.146.2.1.2";
const OID_SAN: &str = "2.5.29.17";

fn err<E: core::fmt::Display>(what: &'static str) -> impl Fn(E) -> Rcc16BuildError {
    move |e| Rcc16BuildError(format!("{what}: {e}"))
}

fn oid(s: &str) -> Result<ObjectIdentifier, Rcc16BuildError> {
    ObjectIdentifier::new(s).map_err(err("oid"))
}

/// An `Extension` whose value is already-encoded DER, embedded verbatim.
fn raw_ext(id: &str, critical: bool, value_der: &[u8]) -> Result<Extension, Rcc16BuildError> {
    Ok(Extension {
        extn_id: oid(id)?,
        critical,
        extn_value: OctetString::new(value_der).map_err(err("ext value"))?,
    })
}

fn typed_ext<T: Encode>(
    id: ObjectIdentifier,
    critical: bool,
    v: &T,
) -> Result<Extension, Rcc16BuildError> {
    let der = v.to_der().map_err(err("ext encode"))?;
    Ok(Extension {
        extn_id: id,
        critical,
        extn_value: OctetString::new(der).map_err(err("ext value"))?,
    })
}

#[allow(clippy::too_many_arguments)]
fn tbs(
    issuer_der: &[u8],
    subject_der: &[u8],
    spki_der: &[u8],
    serial: &[u8],
    not_before: u64,
    not_after: u64,
    extensions: Vec<Extension>,
) -> Result<Vec<u8>, Rcc16BuildError> {
    let t = TbsCertificate {
        version: Version::V3,
        serial_number: SerialNumber::new(serial).map_err(err("serial"))?,
        // Must equal the outer signature algorithm; `certificate` writes the same literal.
        signature: Decode::from_der(ALGID_ECDSA_SHA256).map_err(err("algid"))?,
        issuer: Name::from_der(issuer_der).map_err(err("issuer"))?,
        validity: Validity {
            not_before: time_of(not_before)?,
            not_after: time_of(not_after)?,
        },
        subject: Name::from_der(subject_der).map_err(err("subject"))?,
        subject_public_key_info: SubjectPublicKeyInfoOwned::from_der(spki_der)
            .map_err(err("spki"))?,
        issuer_unique_id: None,
        subject_unique_id: None,
        extensions: Some(extensions),
    };
    t.to_der().map_err(err("tbs"))
}

/// A CA certificate's TBS: the intermediate, or the self-signed root (`issuer_der == subject_der`,
/// `aki == ski`).
#[allow(clippy::too_many_arguments)]
pub fn tbs_ca(
    issuer_der: &[u8],
    subject_der: &[u8],
    spki_der: &[u8],
    serial: &[u8],
    not_before: u64,
    not_after: u64,
    ski: &[u8],
    aki: &[u8],
    vendor_id: u64,
) -> Result<Vec<u8>, Rcc16BuildError> {
    let exts = vec![
        typed_ext(
            <BasicConstraints as AssociatedOid>::OID,
            true,
            &BasicConstraints { ca: true, path_len_constraint: None },
        )?,
        typed_ext(
            <KeyUsage as AssociatedOid>::OID,
            true,
            &KeyUsage(KeyUsages::KeyCertSign | KeyUsages::CRLSign),
        )?,
        typed_ext(
            <SubjectKeyIdentifier as AssociatedOid>::OID,
            false,
            &SubjectKeyIdentifier(OctetString::new(ski).map_err(err("ski"))?),
        )?,
        typed_ext(
            <AuthorityKeyIdentifier as AssociatedOid>::OID,
            false,
            &AuthorityKeyIdentifier {
                key_identifier: Some(OctetString::new(aki).map_err(err("aki"))?),
                authority_cert_issuer: None,
                authority_cert_serial_number: None,
            },
        )?,
        raw_ext(OID_VENDOR_ID, false, &der_int(vendor_id))?,
    ];
    tbs(issuer_der, subject_der, spki_der, serial, not_before, not_after, exts)
}

/// A client leaf's TBS (RCC.16 A.3.8, §14.2.3). `san_der` and `ext4_der` are embedded verbatim,
/// because the `.4` proof-of-possession covers the client's own encoding. No BasicConstraints on a
/// leaf.
#[allow(clippy::too_many_arguments)]
pub fn tbs_leaf(
    issuer_der: &[u8],
    subject_der: &[u8],
    spki_der: &[u8],
    serial: &[u8],
    not_before: u64,
    not_after: u64,
    ski: &[u8],
    aki: &[u8],
    san_der: &[u8],
    ext4_der: &[u8],
    vendor_id: u64,
) -> Result<Vec<u8>, Rcc16BuildError> {
    // RCC.16 §14.2.3: one E2EE policy, non-critical, no qualifiers, i.e. a PolicyInformation
    // holding only the OID. The validator rejects anything after the identifier.
    let policy = CertificatePolicies(vec![PolicyInformation {
        policy_identifier: oid(OID_E2EE_POLICY)?,
        policy_qualifiers: None,
    }]);
    let exts = vec![
        typed_ext(
            <KeyUsage as AssociatedOid>::OID,
            true,
            &KeyUsage(KeyUsages::DigitalSignature.into()),
        )?,
        typed_ext(
            <ExtendedKeyUsage as AssociatedOid>::OID,
            false,
            &ExtendedKeyUsage(vec![oid(OID_EKU_RCS_MLS)?]),
        )?,
        typed_ext(
            <SubjectKeyIdentifier as AssociatedOid>::OID,
            false,
            &SubjectKeyIdentifier(OctetString::new(ski).map_err(err("ski"))?),
        )?,
        typed_ext(
            <AuthorityKeyIdentifier as AssociatedOid>::OID,
            false,
            &AuthorityKeyIdentifier {
                key_identifier: Some(OctetString::new(aki).map_err(err("aki"))?),
                authority_cert_issuer: None,
                authority_cert_serial_number: None,
            },
        )?,
        raw_ext(OID_SAN, false, san_der)?,
        raw_ext(OID_VENDOR_ID, false, &der_int(vendor_id))?,
        typed_ext(<CertificatePolicies as AssociatedOid>::OID, false, &policy)?,
        // critical (RCC.16 A.3.8.9)
        raw_ext(OID_PARTICIPANT_INFO, true, ext4_der)?,
    ];
    tbs(issuer_der, subject_der, spki_der, serial, not_before, not_after, exts)
}

/// `Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signature }`, assembled with
/// `der_seq` so the TBS reaches the wire exactly as it was signed.
pub fn certificate(tbs_der: &[u8], signature_der: &[u8]) -> Result<Vec<u8>, Rcc16BuildError> {
    if tbs_der.is_empty() || signature_der.is_empty() {
        return Err(Rcc16BuildError(format!(
            "certificate: empty input (tbs={} sig={})",
            tbs_der.len(),
            signature_der.len()
        )));
    }
    let mut bits = Vec::with_capacity(signature_der.len() + 1);
    bits.push(0x00); // unused bits
    bits.extend_from_slice(signature_der);
    let mut sig = vec![0x03];
    sig.extend_from_slice(&der_len(bits.len()));
    sig.extend_from_slice(&bits);
    Ok(der_seq(&[tbs_der, ALGID_ECDSA_SHA256, &sig]))
}

/// Flips one bit of the `.4` proof-of-possession signature for the self-test's negative fixture.
/// The flip is inside `s` and length-preserving, so the result still parses and the rejection comes
/// from the signature check, not the parser.
pub fn corrupt_pop_signature(ext4: &[u8]) -> Result<Vec<u8>, Rcc16BuildError> {
    use crate::rcc16_validate::read_tlv;
    let mut pos = 0;
    let seq = read_tlv(ext4, &mut pos)
        .filter(|t| t.tag == 0x30)
        .ok_or_else(|| Rcc16BuildError(".4 is not a SEQUENCE".into()))?;
    let header = ext4.len() - seq.val.len();
    let inner = seq.val;
    let mut ip = 0;
    for idx in 0..4 {
        let t = read_tlv(inner, &mut ip)
            .ok_or_else(|| Rcc16BuildError(format!(".4[{idx}] missing")))?;
        if idx == 3 {
            if t.tag != 0x03 {
                return Err(Rcc16BuildError(format!(
                    ".4[3] is tag {:#04x}, expected a BIT STRING", t.tag)));
            }
            let mut out = ext4.to_vec();
            let last = header + ip - 1;
            out[last] ^= 0x01;
            debug_assert_eq!(out.len(), ext4.len());
            return Ok(out);
        }
    }
    Err(Rcc16BuildError(".4 has fewer than four elements".into()))
}

/// Reference encoding for the test that pins the hand-written bit string in [`certificate`].
#[cfg(test)]
fn bitstring_reference(sig: &[u8]) -> Vec<u8> {
    BitString::from_bytes(sig).unwrap().to_der().unwrap()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The hand-written bit string in `certificate` matches the `der` crate's `BitString`.
    #[test]
    fn hand_written_bitstring_matches_the_der_crate() {
        let sig = [0x30u8, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02];
        let cert = certificate(&[0x30, 0x00], &sig).unwrap();
        let reference = bitstring_reference(&sig);
        assert!(
            cert.windows(reference.len()).any(|w| w == reference),
            "hand-assembled BIT STRING is not the der crate's encoding"
        );
    }

    #[test]
    fn certificate_refuses_empty_input() {
        assert!(certificate(&[], &[1]).is_err());
        assert!(certificate(&[1], &[]).is_err());
    }
}

#[cfg(test)]
mod fixture_tests {
    use super::*;
    use x509_cert::Certificate;

    fn td(f: &str) -> Vec<u8> {
        std::fs::read(format!("testdata/{f}")).unwrap()
    }

    fn ext_of(c: &Certificate, id: &str) -> Option<Vec<u8>> {
        c.tbs_certificate.extensions.as_ref()?.iter()
            .find(|e| e.extn_id.to_string() == id)
            .map(|e| e.extn_value.as_bytes().to_vec())
    }

    fn key_id(c: &Certificate, id: &str) -> Vec<u8> {
        let v = ext_of(c, id).unwrap_or_else(|| panic!("{id} absent"));
        // The SKI value is an octet string; the AKI value a sequence holding `[0] keyIdentifier`.
        let mut pos = 0;
        let t = crate::rcc16_validate::read_tlv(&v, &mut pos).unwrap();
        if t.tag == 0x04 { t.val.to_vec() } else {
            let mut ip = 0;
            crate::rcc16_validate::read_tlv(t.val, &mut ip).unwrap().val.to_vec()
        }
    }

    fn parts(c: &Certificate) -> (Vec<u8>, Vec<u8>, Vec<u8>, Vec<u8>, u64, u64) {
        let t = &c.tbs_certificate;
        (t.issuer.to_der().unwrap(),
         t.subject.to_der().unwrap(),
         t.subject_public_key_info.to_der().unwrap(),
         t.serial_number.as_bytes().to_vec(),
         t.validity.not_before.to_unix_duration().as_secs(),
         t.validity.not_after.to_unix_duration().as_secs())
    }

    /// Rebuilds the fixture root's and intermediate's TBS, minted independently by
    /// `testdata/regen-chain.py`, and requires byte identity.
    #[test]
    fn rebuilds_the_fixture_ca_tbs_byte_for_byte() {
        for f in ["pki2_root.der", "pki2_ica.der"] {
            let c = Certificate::from_der(&td(f)).unwrap();
            let (issuer, subject, spki, serial, nb, na) = parts(&c);
            let ski = key_id(&c, "2.5.29.14");
            let aki = key_id(&c, "2.5.29.35");
            let vendor = ext_of(&c, OID_VENDOR_ID)
                .map(|v| { let mut p = 0;
                           let t = crate::rcc16_validate::read_tlv(&v, &mut p).unwrap();
                           t.val.iter().fold(0u64, |a, &b| (a << 8) | b as u64) })
                .unwrap_or_else(|| panic!("{f}: no vendorId extension"));
            let rebuilt = tbs_ca(&issuer, &subject, &spki, &serial, nb, na, &ski, &aki, vendor)
                .unwrap_or_else(|e| panic!("{f}: {e}"));
            assert_eq!(rebuilt, c.tbs_certificate.to_der().unwrap(),
                       "{f}: rebuilt TBS differs from the independently minted certificate");
        }
    }

    /// The same for the leaf profile: EKU, certificatePolicies, SAN, critical `.4`, no
    /// BasicConstraints.
    #[test]
    fn rebuilds_the_fixture_leaf_tbs_byte_for_byte() {
        // The client leaf profile. Not pki2_kds_leaf, which models the key directory's output
        // (BasicConstraints and a `.5` proof, no AKI or `.6`) and so cannot pin this builder.
        let c = Certificate::from_der(&td("pki2_leaf_pa.der")).unwrap();
        let (issuer, subject, spki, serial, nb, na) = parts(&c);
        let ski = key_id(&c, "2.5.29.14");
        let aki = key_id(&c, "2.5.29.35");
        let san = ext_of(&c, OID_SAN).expect("SAN");
        let p4 = ext_of(&c, OID_PARTICIPANT_INFO).expect(".4");
        let vendor = ext_of(&c, OID_VENDOR_ID)
            .map(|v| { let mut p = 0;
                       let t = crate::rcc16_validate::read_tlv(&v, &mut p).unwrap();
                       t.val.iter().fold(0u64, |a, &b| (a << 8) | b as u64) })
            .expect("vendorId");
        let rebuilt = tbs_leaf(&issuer, &subject, &spki, &serial, nb, na, &ski, &aki,
                               &san, &p4, vendor).unwrap();
        assert_eq!(rebuilt, c.tbs_certificate.to_der().unwrap(),
                   "rebuilt leaf TBS differs from the independently minted certificate");
    }

    #[test]
    fn reassembles_a_fixture_certificate_byte_for_byte() {
        for f in ["pki2_root.der", "pki2_ica.der", "pki2_kds_leaf.der"] {
            let der = td(f);
            let c = Certificate::from_der(&der).unwrap();
            let tbs_der = c.tbs_certificate.to_der().unwrap();
            let sig = c.signature.as_bytes().expect("signature bits");
            assert_eq!(certificate(&tbs_der, sig).unwrap(), der,
                       "{f}: reassembled certificate differs from the original");
        }
    }
}

#[cfg(test)]
mod corrupt_tests {
    use super::*;
    use x509_cert::Certificate;

    /// The corruption changes exactly one byte, keeps the length and leaves a parseable
    /// ECDSA-Sig-Value, so the negative fixture fails for the right reason.
    #[test]
    fn corruption_is_one_byte_and_length_preserving() {
        let der = std::fs::read("testdata/pki2_kds_leaf.der").unwrap();
        let c = Certificate::from_der(&der).unwrap();
        let p4 = c.tbs_certificate.extensions.as_ref().unwrap().iter()
            .find(|e| e.extn_id.to_string() == OID_PARTICIPANT_INFO).unwrap()
            .extn_value.as_bytes().to_vec();
        let bad = corrupt_pop_signature(&p4).unwrap();
        assert_eq!(bad.len(), p4.len(), "corruption changed the length");
        let diff = p4.iter().zip(&bad).filter(|(a, b)| a != b).count();
        assert_eq!(diff, 1, "expected exactly one byte to change, got {diff}");
        // the signature still parses as an ECDSA-Sig-Value
        let (_v, _va, _a, sig, _s) = {
            let mut pos = 0;
            let seq = crate::rcc16_validate::read_tlv(&bad, &mut pos).unwrap();
            let inner = seq.val; let mut ip = 0;
            let a = crate::rcc16_validate::read_tlv(inner, &mut ip).unwrap();
            let b = crate::rcc16_validate::read_tlv(inner, &mut ip).unwrap();
            let c2 = crate::rcc16_validate::read_tlv(inner, &mut ip).unwrap();
            let d = crate::rcc16_validate::read_tlv(inner, &mut ip).unwrap();
            let e2 = crate::rcc16_validate::read_tlv(inner, &mut ip).unwrap();
            (a, b, c2, d, e2)
        };
        assert!(p256::ecdsa::Signature::from_der(&sig.val[1..]).is_ok(),
                "corrupted signature no longer parses as an ECDSA-Sig-Value");
    }
}

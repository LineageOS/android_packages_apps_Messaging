//
// SPDX-FileCopyrightText: The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

//! RCC.16 certificate encoding: the issuing half of what [`crate::rcc16_validate`] checks.
//! `tbs_participant_info` composes the validator's own `der_seq` over the same five elements that
//! `verify_participant_pop` reconstructs, so a layout error fails both directions at once.
//! Private-key operations stay in Java: callers pass an encoded SPKI, sign the returned bytes with
//! JCE and hand the signature back. See docs/mls/rust-core.md.

use crate::rcc16_validate::{der_int, der_seq};
use x509_cert::attr::AttributeTypeAndValue;
use x509_cert::der::asn1::{Any, GeneralizedTime, Ia5String, ObjectIdentifier, SetOfVec, UtcTime};
use x509_cert::der::{Encode, Tag};
use x509_cert::ext::pkix::name::GeneralName;
use x509_cert::name::{RdnSequence, RelativeDistinguishedName};
use x509_cert::time::Time;

/// id-at-commonName; `const_oid::db` is behind a feature the Soong build does not enable.
const OID_CN: &str = "2.5.4.3";
const OID_O: &str = "2.5.4.10";

/// ecdsa-with-SHA-256 `AlgorithmIdentifier` with the parameters absent (RFC 5758 §3.2):
/// `30 0a 06 08 2a 86 48 ce 3d 04 03 02`.
pub(crate) const ALGID_ECDSA_SHA256: &[u8] = &[
    0x30, 0x0a, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02,
];

#[derive(Debug)]
pub struct Rcc16BuildError(pub String);

impl core::fmt::Display for Rcc16BuildError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        write!(f, "{}", self.0)
    }
}

fn e<E: core::fmt::Display>(what: &str) -> impl Fn(E) -> Rcc16BuildError + '_ {
    move |err| Rcc16BuildError(format!("{what}: {err}"))
}

/// `Name` with one RDN, `CN=<cn>`, as a UTF8String. The key directory rejects a PrintableString
/// CN, so the string type is not left to `RdnSequence::from_str`.
pub fn name_cn_utf8(cn: &str) -> Result<Vec<u8>, Rcc16BuildError> {
    let oid = ObjectIdentifier::new(OID_CN).map_err(e("cn oid"))?;
    let value = Any::new(Tag::Utf8String, cn.as_bytes()).map_err(e("cn value"))?;
    let atv = AttributeTypeAndValue { oid, value };
    let set = SetOfVec::try_from(vec![atv]).map_err(e("cn rdn set"))?;
    let rdn = RelativeDistinguishedName(set);
    RdnSequence(vec![rdn]).to_der().map_err(e("subject der"))
}

/// `Name` with two RDNs, `O=<org>` then `CN=<cn>`, as PrintableString: the CA subject form the
/// fixture chain was minted with. Separate from [`name_cn_utf8`] so neither is reached by accident.
pub fn name_o_cn_printable(org: &str, cn: &str) -> Result<Vec<u8>, Rcc16BuildError> {
    let mut rdns = Vec::with_capacity(2);
    for (id, v) in [(OID_O, org), (OID_CN, cn)] {
        let atv = AttributeTypeAndValue {
            oid: ObjectIdentifier::new(id).map_err(e("dn oid"))?,
            value: Any::new(Tag::PrintableString, v.as_bytes()).map_err(e("dn value"))?,
        };
        rdns.push(RelativeDistinguishedName(
            SetOfVec::try_from(vec![atv]).map_err(e("dn rdn set"))?,
        ));
    }
    RdnSequence(rdns).to_der().map_err(e("dn der"))
}

/// `GeneralNames` with one `uniformResourceIdentifier`; the caller passes the full `tel:` URI.
pub fn san_uri(uri: &str) -> Result<Vec<u8>, Rcc16BuildError> {
    let ia5 = Ia5String::new(uri).map_err(e("san uri"))?;
    let names = vec![GeneralName::UniformResourceIdentifier(ia5)];
    names.to_der().map_err(e("san der"))
}

/// One X.509 `Time`: UTCTime through 2049, GeneralizedTime from 2050 (RFC 5280 §4.1.2.5).
pub(crate) fn time_of(unix_secs: u64) -> Result<Time, Rcc16BuildError> {
    const Y2050: u64 = 2_524_608_000; // 1 Jan 2050 00:00:00 UTC
    let d = core::time::Duration::from_secs(unix_secs);
    if unix_secs < Y2050 {
        Ok(Time::UtcTime(UtcTime::from_unix_duration(d).map_err(e("utctime"))?))
    } else {
        Ok(Time::GeneralTime(GeneralizedTime::from_unix_duration(d).map_err(e("gentime"))?))
    }
}

fn time_der(unix_secs: u64) -> Result<Vec<u8>, Rcc16BuildError> {
    time_of(unix_secs)?.to_der().map_err(e("time der"))
}

/// `SEQUENCE { notBefore, notAfter }`, shared by the leaf and the `.4` binding.
pub fn validity(not_before: u64, not_after: u64) -> Result<Vec<u8>, Rcc16BuildError> {
    if not_after <= not_before {
        return Err(Rcc16BuildError(format!(
            "validity is empty or inverted: notBefore={not_before} notAfter={not_after}"
        )));
    }
    Ok(der_seq(&[&time_der(not_before)?, &time_der(not_after)?]))
}

/// `tbsParticipantInfo`, the five elements the `.4` proof-of-possession signs (RCC.16 A.3.8.9):
/// the leaf subject, the `.4` vendorId, the `.4` validity (not the certificate's), the leaf's
/// certified SPKI (not the participant key in `.4[4]`), and the leaf SAN.
/// `verify_participant_pop` reconstructs the same; change both together.
pub fn tbs_participant_info(
    subject_der: &[u8],
    vendor_id: u64,
    validity_der: &[u8],
    leaf_spki_der: &[u8],
    san_der: &[u8],
) -> Vec<u8> {
    der_seq(&[
        subject_der,
        &der_int(vendor_id),
        validity_der,
        leaf_spki_der,
        san_der,
    ])
}

/// The `.4 ParticipantInformation` extension value around a signature over
/// [`tbs_participant_info`]:
/// `SEQUENCE { vendorId, validity, AlgorithmIdentifier, signature, spki }`.
/// `participantKeyRolls` is omitted: `SIZE(1..5)` forbids an empty one, and the validator refuses a
/// `.4` that carries rolls.
pub fn ext4(
    vendor_id: u64,
    validity_der: &[u8],
    pop_signature_der: &[u8],
    participant_spki_der: &[u8],
) -> Result<Vec<u8>, Rcc16BuildError> {
    // A bit string with zero unused bits around the DER ECDSA-Sig-Value.
    let mut bits = Vec::with_capacity(pop_signature_der.len() + 1);
    bits.push(0x00);
    bits.extend_from_slice(pop_signature_der);
    let mut sig_bitstring = vec![0x03];
    sig_bitstring.extend_from_slice(&crate::rcc16_validate::der_len(bits.len()));
    sig_bitstring.extend_from_slice(&bits);

    Ok(der_seq(&[
        &der_int(vendor_id),
        validity_der,
        ALGID_ECDSA_SHA256,
        &sig_bitstring,
        participant_spki_der,
    ]))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The CN tag must be UTF8String (0x0c), not PrintableString (0x13); a round trip would accept
    /// either.
    #[test]
    fn cn_is_utf8string_not_printablestring() {
        let der = name_cn_utf8("d6f3c1a0-0000-4000-8000-000000000000").unwrap();
        assert!(
            der.windows(1).any(|w| w[0] == 0x0c),
            "no UTF8String tag in {der:02x?}"
        );
        assert!(
            !der.contains(&0x13),
            "a PrintableString tag is present: {der:02x?}"
        );
    }

    /// An empty or inverted window is refused, since the validator would reject it anyway.
    #[test]
    fn validity_refuses_inverted_window() {
        assert!(validity(2_000, 1_000).is_err());
        assert!(validity(1_000, 1_000).is_err());
        assert!(validity(1_000, 2_000).is_ok());
    }

    #[test]
    fn time_encoding_switches_at_2050() {
        let before = time_der(1_700_000_000).unwrap(); // 2023
        let after = time_der(2_600_000_000).unwrap(); // 2052
        assert_eq!(before[0], 0x17, "pre-2050 must be UTCTime");
        assert_eq!(after[0], 0x18, "post-2050 must be GeneralizedTime");
    }

    /// RFC 5758 §3.2: the AlgorithmIdentifier is a SEQUENCE holding only the OID.
    #[test]
    fn algid_has_absent_parameters() {
        assert_eq!(ALGID_ECDSA_SHA256[0], 0x30);
        assert_eq!(
            ALGID_ECDSA_SHA256.len(),
            2 + ALGID_ECDSA_SHA256[1] as usize,
            "declared length must cover the whole structure"
        );
        assert_eq!(
            ALGID_ECDSA_SHA256[2], 0x06,
            "first and only element must be the OID"
        );
        assert_eq!(
            ALGID_ECDSA_SHA256.len(),
            4 + ALGID_ECDSA_SHA256[3] as usize,
            "nothing may follow the OID"
        );
    }
}

#[cfg(test)]
mod fixture_tests {
    use super::*;
    use crate::rcc16_validate::{read_tlv, sec1_point_from_spki};
    use p256::ecdsa::{signature::Verifier, Signature, VerifyingKey};
    use x509_cert::der::{Decode, Encode};
    use x509_cert::Certificate;

    fn td(f: &str) -> Vec<u8> {
        std::fs::read(format!("testdata/{f}")).unwrap()
    }

    const OID_P4: &str = "2.23.146.2.1.4";
    const OID_SAN: &str = "2.5.29.17";

    fn split_p4(p4: &[u8]) -> (u64, &[u8], &[u8], &[u8], &[u8]) {
        let mut pos = 0;
        let seq = read_tlv(p4, &mut pos).expect(".4 outer SEQUENCE");
        let inner = seq.val;
        let mut ip = 0;
        let vendor = read_tlv(inner, &mut ip).expect(".4[0] vendorId");
        let validity = read_tlv(inner, &mut ip).expect(".4[1] validity");
        let algid = read_tlv(inner, &mut ip).expect(".4[2] algid");
        let sigbits = read_tlv(inner, &mut ip).expect(".4[3] signature");
        let spki = read_tlv(inner, &mut ip).expect(".4[4] participant spki");
        let vid = vendor.val.iter().fold(0u64, |a, &b| (a << 8) | b as u64);
        // sigbits.val[0] is the unused-bits octet.
        (vid, validity.full, algid.full, &sigbits.val[1..], spki.full)
    }

    fn ext_value(cert: &Certificate, oid: &str) -> Vec<u8> {
        cert.tbs_certificate
            .extensions
            .as_ref()
            .expect("extensions")
            .iter()
            .find(|e| e.extn_id.to_string() == oid)
            .unwrap_or_else(|| panic!("extension {oid} not present"))
            .extn_value
            .as_bytes()
            .to_vec()
    }

    /// Rebuilds the `.4` of a leaf minted by `testdata/regen-chain.py`, an independent
    /// implementation, and requires byte identity. It is the only fixture whose participant key
    /// differs from its certified key, so it is the only one that can tell the layouts apart.
    #[test]
    fn rebuilds_an_independently_minted_ext4_byte_for_byte() {
        let cert = Certificate::from_der(&td("pki2_kds_leaf.der")).unwrap();
        let p4 = ext_value(&cert, OID_P4);
        let (vid, validity_der, algid_der, sig, participant_spki) = split_p4(&p4);

        assert_eq!(
            algid_der, ALGID_ECDSA_SHA256,
            "ecdsa-with-SHA256 AlgorithmIdentifier differs from the independent minter"
        );

        let rebuilt = ext4(vid, validity_der, sig, participant_spki).unwrap();
        assert_eq!(
            rebuilt, p4,
            "ext4 is not byte-identical to the independently minted extension"
        );
    }

    /// The TBS is the exact bytes the fixture's signature was made over: checked by verifying the
    /// signature with the participant key, which catches element order or provenance errors.
    #[test]
    fn tbs_is_exactly_what_the_committed_signature_covers() {
        let cert = Certificate::from_der(&td("pki2_kds_leaf.der")).unwrap();
        let p4 = ext_value(&cert, OID_P4);
        let san_der = ext_value(&cert, OID_SAN);
        let (vid, validity_der, _algid, sig, participant_spki) = split_p4(&p4);

        let tbs = &cert.tbs_certificate;
        let subject_der = tbs.subject.to_der().unwrap();
        let leaf_spki_der = tbs.subject_public_key_info.to_der().unwrap();

        let rebuilt =
            tbs_participant_info(&subject_der, vid, validity_der, &leaf_spki_der, &san_der);

        let point = sec1_point_from_spki(participant_spki).expect("participant spki is P-256");
        let vk = VerifyingKey::from_sec1_bytes(&point).expect("verifying key");
        let signature = Signature::from_der(sig).expect("ECDSA-Sig-Value");
        vk.verify(&rebuilt, &signature)
            .expect("the committed .4 signature does not cover the TBS we built");
    }

    /// The fixture's two keys differ; if a re-mint collapsed them the tests above would lose their
    /// power silently.
    #[test]
    fn the_fixture_still_has_two_distinct_keys() {
        let cert = Certificate::from_der(&td("pki2_kds_leaf.der")).unwrap();
        let p4 = ext_value(&cert, OID_P4);
        let (_vid, _v, _a, _s, participant_spki) = split_p4(&p4);
        let leaf_spki = cert.tbs_certificate.subject_public_key_info.to_der().unwrap();
        assert_ne!(
            participant_spki, &leaf_spki[..],
            "pki2_kds_leaf collapsed to one key; it can no longer discriminate the .4 layout"
        );
    }
}

#[cfg(test)]
mod dn_fixture_tests {
    use super::*;
    use x509_cert::der::{Decode, Encode};
    use x509_cert::Certificate;

    /// The CA subject encoding reproduces the fixture chain's byte for byte, so a re-minted root
    /// still matches the issuer bytes of certificates under it.
    #[test]
    fn ca_subject_matches_the_fixture_chain() {
        for (f, cn) in [("pki2_root.der", None::<&str>), ("pki2_ica.der", None)] {
            let der = std::fs::read(format!("testdata/{f}")).unwrap();
            let c = Certificate::from_der(&der).unwrap();
            let want = c.tbs_certificate.subject.to_der().unwrap();
            // read O and CN from the fixture so the test pins the encoding, not the values
            let s = c.tbs_certificate.subject.to_string();
            let org = s.split("O=").nth(1).unwrap().split(',').next().unwrap().trim();
            let common = cn.map(|x| x.to_string()).unwrap_or_else(||
                s.split("CN=").nth(1).unwrap().split(',').next().unwrap().trim().to_string());
            assert_eq!(name_o_cn_printable(org, &common).unwrap(), want,
                       "{f}: CA subject encoding differs from the fixture");
        }
    }
}

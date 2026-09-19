#!/usr/bin/env python3
# Copyright (C) 2026 The LineageOS Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Re-mint every synthetic certificate in `testdata/`, from nothing.

    python3 testdata/regen-chain.py [--out DIR]     # DIR defaults to this script's directory

Run it from anywhere; it writes only into `--out`. The real-world artefacts
(`google_kds_*.der`, `live_root_*.der`) are CAPTURES, not mints — this script never touches
them, and if they are lost they must be captured again.

WHY THIS FILE EXISTS
--------------------
The two lab chains used to be minted ad hoc: chain v1 by a script nobody kept, chain v2
(`lab2_*`) by `MlsCredential` running on a device and pulled off with `adb`. Neither could
be reproduced from the tree, so every fixture property was frozen — including two §14.2.3 rules
that had to be RELAXED IN THE VALIDATOR because the fixture that could not satisfy them could
not be re-issued either. A fixture set nobody can re-mint silently becomes the ceiling on how
strict the validator is allowed to be. This script is what removes that ceiling.

WHAT THE TESTS PIN, AND THEREFORE WHAT THIS SCRIPT MUST REPRODUCE
-----------------------------------------------------------------
Everything in this list is asserted somewhere in `src/rcc16_validate.rs` or `src/ffi.rs`. If you
change a number here, run `cargo +1.85.1 test` and expect to be told which assertion you moved.

  * VALIDITY WINDOWS ARE FIXED INSTANTS, not offsets from "now". `LAB2_NOW` (rcc16_validate.rs)
    pins the instant every lab2 test evaluates at, and `ffi.rs` hardcodes `PA_NOT_AFTER`
    (1791387137) and `RENEWED_NOT_AFTER` (1791892800). A 76-day-capped profile means every
    fixture expires, so a suite that read the wall clock would go red on a date nobody chose.
    Changing `LAB2` below means re-pinning all three constants.
  * THE `.4` PoP SIGNATURE MUST VERIFY under `verify_participant_pop`, whose reconstruction is
        SEQ{ leafSubject, INTEGER vendorId, participantSignatureValidity, LEAF SPKI, leafSAN }
    Element 4 is the LEAF's SubjectPublicKeyInfo — NOT the participant key, which is the key the
    signature is made WITH and which appears as `.4[4]` in the extension value. In a one-key
    fixture the two are equal and the distinction is invisible; `lab2_kds_leaf` is the two-key
    fixture that exists to catch getting it backwards, and it caught the author of the reference
    minter. `a_one_key_fixture_cannot_discriminate_the_layout_but_a_two_key_one_can` pins BOTH
    halves: lab2_leaf_pa must be one-key, lab2_kds_leaf must be two-key.
  * `.4` HAS EXACTLY FIVE ELEMENTS. A sixth (participantKeyRolls [0] IMPLICIT) is REFUSED by the
    validator, and `a_participant_information_with_a_trailing_element_is_refused` appends one to
    `lab2_kds_leaf` and asserts the re-encode is exactly 5 bytes longer.
  * `.4` IS CRITICAL; `.5` IS NOT (a critical `.5` is its own named rejection).
  * `lab2_kds_leaf`'s `.5` IS PINNED TO THE BYTE: 81 bytes total = 8-byte big-endian expiry
    1791149096, the 2-byte MLS varint arm `40 47`, then a 71-byte DER ECDSA-Sig-Value beginning
    `30 45`. The expiry must also OUTLIVE the certificate's own notAfter (1789766788) by exactly
    1_382_308 s. ECDSA DER signatures are 70-72 bytes depending on the r/s high bits, so the
    mint below re-signs until it gets 71 — that is what puts the length on the 2-byte arm.
  * SAN MSISDNs are pinned: pa/pc share +15551110001 (two devices of one participant, which is
    what makes them distinct subjects with distinct keys and one SAN), pb is +15551110002.
  * `lab2_leaf_pa_renewed` IS A RENEWAL, NOT A SECOND IDENTITY: same subject CN (what
    `valid_successor` compares), same key, same SAN, and the SAME `.4` bytes — only the serial
    and the certificate validity move. The `.4` TBS covers none of those, so the copied
    signature still verifies. A fixture with a new subject would be rejected by the peer and the
    renewal test would be testing nothing.
  * `lab2_leaf_badpop` MUST STILL FAIL, and for its original reason: `.4 ParticipantInformation
    PoP signature INVALID`. The corruption is ONE BIT in the LAST byte of the DER signature —
    inside `s`, so the value still parses as an ECDSA-Sig-Value and only the arithmetic is
    wrong. An earlier attempt flipped the last byte of the whole `.4` structure, which lands in
    the participant key's SEC1 point: that fixture failed on an unparseable KEY while a test
    asserted it proved a bad SIGNATURE. Corruption is applied BEFORE the leaf is signed, so the
    certificate's own signature stays valid and the chain is not what fails.
  * THE RETIRED CHAIN (`root`/`ica`/`leaf_*`) IS DELIBERATELY NON-CONFORMANT and must stay that
    way. `leaf_a`/`leaf_b` carry NO EKU, no SKI and no certificatePolicies, and `.4` is a
    16-byte dummy rather than a PoP — `rcc16_full_validation` asserts they are REJECTED. Teaching
    the lab minter to emit broken certificates would be a worse trade than keeping the chain that
    already produced them, so they are reproduced literally here.

DETERMINISM
-----------
Every private key and serial number is derived from `SEED` by SHA-256 over a label, so two runs
produce the same keys, the same subjectKeyIdentifiers and the same serials. The SIGNATURES still
differ run to run — ECDSA picks a random nonce — so the output DER is not byte-reproducible, only
structurally so. That is the most determinism available without reimplementing RFC 6979.

VERIFY AFTER RUNNING
--------------------
    cd rust/rcs_mls_ffi && cargo +1.85.1 test
    cd testdata && for f in *.der; do openssl x509 -inform DER -in "$f" -noout -subject -issuer; done
"""

import argparse
import datetime
import hashlib
import os
import re
import struct
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID, ObjectIdentifier
from cryptography.x509.name import _ASN1Type

# ---- identity -------------------------------------------------------------------------------
#
# The organisation on every synthetic certificate. The short hex ids in the CA common names are
# kept from the chain this replaced: they are how a reader tells two chains apart, and both the
# testdata README and comments in the Rust sources cite them.
ORG = "LineageOS"
LAB2_ROOT_CN = "MLS Test Root 1a68132a"
LAB2_ICA_CN = "MLS Test ICA d6226205"
V1_ROOT_CN = "Test MLS Root CA"
V1_ICA_CN = "Test MLS ICA"

# ---- GSMA arc 2.23.146.2.1.x ------------------------------------------------------------------
OID_POLICY = ObjectIdentifier("2.23.146.2.1.2")          # the E2EE certificatePolicies policy
OID_EKU_RCSMLS = ObjectIdentifier("2.23.146.2.1.3")      # id-kp-rcsMlsClient
OID_PARTICIPANT_INFO = ObjectIdentifier("2.23.146.2.1.4")
OID_ACS_PROOF = ObjectIdentifier("2.23.146.2.1.5")       # id-acsParticipantInformation
OID_VENDOR = ObjectIdentifier("2.23.146.2.1.6")
VENDOR_ID = 2                                            # §14.4: Google == 2, Apple == 1
ECDSA_SHA256_OID = bytes([0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02])

# ---- fixed instants --------------------------------------------------------------------------
UTC = datetime.timezone.utc


def _t(y, mo, d, h, mi, s):
    return datetime.datetime(y, mo, d, h, mi, s, tzinfo=UTC)


# Chain v2 (lab2_*). One mint at 2026-08-08T15:32:17Z, backdated an hour, exactly as
# MlsCredential does: root +3650 d (A.1.5 caps 3652), ICA +1825 d (A.2.5 caps 1827),
# leaves +60 d (A.4.1 caps 76). `LAB2_NOW` = 1786752000 sits inside every leaf window.
LAB2_NB = _t(2026, 8, 8, 14, 32, 17)
LAB2_ROOT_NA = _t(2036, 8, 5, 15, 32, 17)
LAB2_ICA_NA = _t(2031, 8, 7, 15, 32, 17)
LAB2_LEAF_NA = _t(2026, 10, 7, 15, 32, 17)               # 1791387137 == ffi.rs PA_NOT_AFTER
# The renewal: a later window and nothing else.
RENEWED_NB = _t(2026, 8, 14, 12, 0, 0)
RENEWED_NA = _t(2026, 10, 13, 12, 0, 0)                  # 1791892800 == ffi.rs RENEWED_NOT_AFTER

# The two-key reference leaf. Its numbers are pinned to the byte by
# `acs_proof_decodes_to_its_documented_bytes` and `acs_proof_outlives_its_own_certificate`.
KDS_NB = _t(2026, 7, 6, 9, 26, 29)
KDS_NA = _t(2026, 9, 18, 21, 26, 28)                     # 1789766788
KDS_ACS_EXPIRY = 1791149096                              # 2026-10-04T21:24:56Z, na + 1_382_308 s

# Chain v1 (retired). Kept only for the deliberately non-conformant leaves.
V1_CA_NB = _t(2026, 7, 14, 21, 5, 30)
V1_ROOT_NA = _t(2036, 7, 11, 21, 5, 30)
V1_ICA_NA = _t(2031, 7, 13, 21, 5, 30)
V1_LEAF_NB = _t(2026, 7, 14, 21, 7, 21)
V1_LEAF_NA = _t(2026, 9, 27, 21, 7, 21)
V1_POP_NB = _t(2026, 7, 12, 0, 0, 0)
V1_POP_NA = _t(2026, 9, 25, 0, 0, 0)

# The 16-byte placeholder the retired leaf_a/leaf_b carry where a real leaf carries a PoP. It is
# not a ParticipantInformation at all, which is half of why those two must be REJECTED.
V1_DUMMY_P4 = bytes.fromhex("041000112233445566778899aabbccddeeff")
# The retired pa/pb leaves carry the pre-2026-08 key-roll stub in what is actually the ACS-proof
# slot. Reproduced literally; nothing reads it.
V1_KEYROLL_STUB = bytes.fromhex("040400000000")

SEED = "lineageos-rcs-mls-testdata-v1"


# ---- deterministic key / serial derivation ----------------------------------------------------

def _digest(label):
    return hashlib.sha256((SEED + "|" + label).encode()).digest()


def key(label):
    """A P-256 private key derived from `label`. Same label, same key, every run."""
    n = int("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    return ec.derive_private_key(int.from_bytes(_digest("key:" + label), "big") % (n - 1) + 1,
                                 ec.SECP256R1())


def serial(label):
    """A 159-bit serial: positive (top bit clear) and 20 octets, matching MlsCredential."""
    return int.from_bytes(_digest("serial:" + label), "big") % (1 << 159) or 1


# ---- DER helpers -----------------------------------------------------------------------------
#
# The `.4` extension is assembled by hand rather than through an ASN.1 library because its TBS
# must reproduce, byte for byte, what `verify_participant_pop` reconstructs out of the ISSUED
# certificate. Anything that re-encodes the subject or the SAN on the way through is a way for
# the two to drift apart without either looking wrong.

def der_len(n):
    if n < 0x80:
        return bytes([n])
    b = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(b)]) + b


def tlv(tag, val):
    return bytes([tag]) + der_len(len(val)) + val


def der_int(v):
    b = v.to_bytes(max(1, (v.bit_length() + 8) // 8), "big")
    return tlv(0x02, b)


def der_utctime(dt):
    return tlv(0x17, dt.strftime("%y%m%d%H%M%SZ").encode())


def spki_der(pub):
    return pub.public_bytes(serialization.Encoding.DER,
                            serialization.PublicFormat.SubjectPublicKeyInfo)


def point65(pub):
    return pub.public_bytes(serialization.Encoding.X962,
                            serialization.PublicFormat.UncompressedPoint)


def ski_bytes(pub):
    """RFC 5280 method 1: SHA-1 over the subjectPublicKey BIT STRING value (the SEC1 point)."""
    return hashlib.sha1(point65(pub)).digest()


def name(cn, org=None, printable=False, cn_first=True):
    """An X.500 name. `printable` and `cn_first` exist because the two chains differ on both and
    the `.4` TBS carries the subject's exact DER — a re-encoding is a broken PoP."""
    t = _ASN1Type.PrintableString if printable else _ASN1Type.UTF8String
    cn_attr = x509.NameAttribute(NameOID.COMMON_NAME, cn, _type=t)
    if org is None:
        return x509.Name([cn_attr])
    org_attr = x509.NameAttribute(NameOID.ORGANIZATION_NAME, org, _type=t)
    return x509.Name([cn_attr, org_attr] if cn_first else [org_attr, cn_attr])


def mls_varint(n):
    """RFC 9420 §2.1.2 — the top two bits give the width. The pinned `.5` uses the 2-byte arm."""
    if n < 0x40:
        return bytes([n])
    if n < 0x4000:
        return struct.pack(">H", 0x4000 | n)
    return struct.pack(">I", 0x80000000 | n)


# ---- the .4 ParticipantInformation PoP --------------------------------------------------------

def participant_info(subject, san, nb, na, leaf_pub, participant_priv, corrupt=False):
    """Build the `.4` extension value.

    The signature is made BY the participant key over a TBS whose element 4 is the LEAF's SPKI:
    "the holder of the participant key attests that this leaf's key is theirs". Passing one key
    as both `leaf_pub` and `participant_priv` gives the one-key shape our own minter produces;
    passing two different keys gives the shape a real KDS leaf has, which is the only shape that
    can tell the two candidate layouts apart.
    """
    validity = tlv(0x30, der_utctime(nb) + der_utctime(na))
    tbs = tlv(0x30, subject.public_bytes()
              + der_int(VENDOR_ID)
              + validity
              + spki_der(leaf_pub)
              + san.public_bytes())
    sig = bytearray(participant_priv.sign(tbs, ec.ECDSA(hashes.SHA256())))
    if corrupt:
        # ONE BIT, in the last byte of the DER signature, which lands inside `s`. The value stays
        # a structurally valid ECDSA-Sig-Value so the failure is the SIGNATURE CHECK and not a
        # parse — see the note in the module docstring.
        sig[-1] ^= 0x01
    algid = tlv(0x30, tlv(0x06, ECDSA_SHA256_OID))
    return tlv(0x30, der_int(VENDOR_ID)
               + validity
               + algid
               + tlv(0x03, b"\x00" + bytes(sig))
               + spki_der(participant_priv.public_key()))


# ---- certificate builders ---------------------------------------------------------------------

def sign_cert(subject, issuer_name, pub, signer, sn, nb, na, exts):
    b = (x509.CertificateBuilder()
         .subject_name(subject).issuer_name(issuer_name)
         .public_key(pub).serial_number(sn)
         .not_valid_before(nb).not_valid_after(na))
    # Extension ORDER is the order given. It is not semantically load-bearing, but keeping it
    # stable keeps a `openssl x509 -text` diff against the previous chain readable.
    for ext, critical in exts:
        b = b.add_extension(ext, critical=critical)
    return b.sign(signer, hashes.SHA256()).public_bytes(serialization.Encoding.DER)


def ca_exts(pub, issuer_ski, path_length=None, vendor=True):
    """The A.2 issuer profile: critical BasicConstraints(cA), critical KeyUsage limited to
    keyCertSign|cRLSign, SKI, AKI, and optionally the vendorId the leaf-to-root binding compares
    against."""
    ku = x509.KeyUsage(digital_signature=False, content_commitment=False, key_encipherment=False,
                       data_encipherment=False, key_agreement=False, key_cert_sign=True,
                       crl_sign=True, encipher_only=False, decipher_only=False)
    out = [
        (x509.BasicConstraints(ca=True, path_length=path_length), True),
        (ku, True),
        (x509.SubjectKeyIdentifier(ski_bytes(pub)), False),
        (x509.AuthorityKeyIdentifier(issuer_ski, None, None), False),
    ]
    if vendor:
        out.append((x509.UnrecognizedExtension(OID_VENDOR, der_int(VENDOR_ID)), False))
    return out


# ---- chain v2: the current lab chain (lab2_*) --------------------------------------------------

LAB2_LEAVES = [
    # name,      subject CN,                             MSISDN,          bad PoP?
    ("pa",       "31004c47-d65f-4c49-86be-6613b755f551", "+15551110001", False),
    ("pb",       "a7e662ed-a119-4a25-b5b1-5742aa94036a", "+15551110002", False),
    ("pc",       "ee58192b-2a06-445b-9dbf-1bbd041a028f", "+15551110001", False),
    ("badpop",   "1c6c7eeb-ae45-4325-af22-0dd08f2409ec", "+15551110001", True),
]


def leaf_exts(subject, san, pub, issuer_ski, p4):
    """The §14.2.3 leaf profile MlsCredential emits, in its order.

    BasicConstraints is deliberately ABSENT (§A.3.8), SKI and exactly one non-critical
    certificatePolicies are deliberately PRESENT — those two were added in 2026-08 and are what
    let the validator drop the conditional relaxations that existed only because our own fixture
    could not satisfy rules every real peer is held to.
    """
    ku = x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                       data_encipherment=False, key_agreement=False, key_cert_sign=False,
                       crl_sign=False, encipher_only=False, decipher_only=False)
    return [
        (ku, True),
        (x509.ExtendedKeyUsage([OID_EKU_RCSMLS]), False),
        (x509.SubjectKeyIdentifier(ski_bytes(pub)), False),
        (x509.AuthorityKeyIdentifier(issuer_ski, None, None), False),
        (san, False),
        (x509.UnrecognizedExtension(OID_VENDOR, der_int(VENDOR_ID)), False),
        (x509.CertificatePolicies([x509.PolicyInformation(OID_POLICY, None)]), False),
        (x509.UnrecognizedExtension(OID_PARTICIPANT_INFO, p4), True),
    ]


def mint_lab2(out, report):
    root_k = key("lab2/root")
    ica_k = key("lab2/ica")
    root_name = name(LAB2_ROOT_CN, ORG, printable=True, cn_first=False)
    ica_name = name(LAB2_ICA_CN, ORG, printable=True, cn_first=False)
    root_ski = ski_bytes(root_k.public_key())
    ica_ski = ski_bytes(ica_k.public_key())

    root = sign_cert(root_name, root_name, root_k.public_key(), root_k, serial("lab2/root"),
                     LAB2_NB, LAB2_ROOT_NA, ca_exts(root_k.public_key(), root_ski))
    ica = sign_cert(ica_name, root_name, ica_k.public_key(), root_k, serial("lab2/ica"),
                    LAB2_NB, LAB2_ICA_NA, ca_exts(ica_k.public_key(), root_ski))
    report(out, "lab2_root.der", root)
    report(out, "lab2_ica.der", ica)
    for nm, k in (("root", root_k), ("ica", ica_k)):
        # PKCS#8, unencrypted — committing these is the whole point of this chain: a leaf nobody
        # can re-issue is a rule nobody can tighten. They anchor nothing real.
        report(out, f"lab2_{nm}_priv.p8", k.private_bytes(
            serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption()))

    pa_parts = None
    for nm, cn, e164, bad in LAB2_LEAVES:
        k = key(f"lab2/leaf_{nm}")
        subject = name(cn)                       # bare CN, UTF8String, as the CA service emits
        san = x509.SubjectAlternativeName([x509.UniformResourceIdentifier("tel:" + e164)])
        # ONE key for both roles. That is our own minter's shape, and
        # `a_one_key_fixture_cannot_discriminate_the_layout_but_a_two_key_one_can` asserts it.
        p4 = participant_info(subject, san, LAB2_NB, LAB2_LEAF_NA, k.public_key(), k, corrupt=bad)
        der = sign_cert(subject, ica_name, k.public_key(), ica_k, serial(f"lab2/leaf_{nm}"),
                        LAB2_NB, LAB2_LEAF_NA,
                        leaf_exts(subject, san, k.public_key(), ica_ski, p4))
        report(out, f"lab2_leaf_{nm}.der", der)
        if not bad:
            # The badpop leaf gets no key companion: nothing may sign as an identity whose whole
            # purpose is to fail validation.
            report(out, f"lab2_leaf_{nm}_priv.bin",
                   k.private_numbers().private_value.to_bytes(32, "big"))
            report(out, f"lab2_leaf_{nm}_pub.bin", point65(k.public_key()))
        if nm == "pa":
            pa_parts = (k, subject, san, p4)

    # THE RENEWAL. Same subject, same key, same SAN, and the SAME `.4` bytes — only the serial and
    # the certificate validity move, and the `.4` TBS covers neither, so the copied signature
    # still verifies. Anything more than this would make it a second identity, which
    # `valid_successor` rejects and the renewal test cannot use.
    k, subject, san, p4 = pa_parts
    report(out, "lab2_leaf_pa_renewed.der",
           sign_cert(subject, ica_name, k.public_key(), ica_k, serial("lab2/leaf_pa_renewed"),
                     RENEWED_NB, RENEWED_NA,
                     leaf_exts(subject, san, k.public_key(), ica_ski, p4)))

    mint_kds_leaf(out, report, ica_name, ica_k)


def mint_kds_leaf(out, report, ica_name, ica_k):
    """The TWO-KEY reference leaf: the only fixture with power over the `.4` element-4 layout.

    A real KDS leaf certifies one key and binds a DIFFERENT participant key in `.4`. Every other
    synthetic leaf here uses one key for both, which makes the two candidate layouts
    byte-identical — so those fixtures cannot discriminate the thing they are testing, and a
    replacement that reused one key would keep the suite green while deleting its only evidence.
    """
    subject_k = key("lab2/kds_subject")          # the CERTIFIED key
    participant_k = key("lab2/kds_participant")  # the PoP-bound key — DISTINCT
    assert point65(subject_k.public_key()) != point65(participant_k.public_key())

    subject = name("00000000-0000-0000-5eed-1ab200000001")
    san = x509.SubjectAlternativeName([x509.UniformResourceIdentifier("tel:+15715550107")])
    p4 = participant_info(subject, san, KDS_NB, KDS_NA, subject_k.public_key(), participant_k)

    # `.5`, pinned to the byte. We cannot verify a real ACS proof — the verification key is
    # reached through an external handle nobody has traced — so the fixture carries a well-formed
    # signature over the same bytes, which is what the expiry and varint decoders are tested
    # against. Re-sign until the DER lands on 71 bytes so the length takes the 2-byte varint arm.
    for _ in range(200):
        sig5 = participant_k.sign(struct.pack(">Q", KDS_ACS_EXPIRY), ec.ECDSA(hashes.SHA256()))
        if len(sig5) == 71:
            break
    else:
        raise SystemExit("could not produce a 71-byte ECDSA signature in 200 tries")
    p5 = struct.pack(">Q", KDS_ACS_EXPIRY) + mls_varint(len(sig5)) + sig5
    assert p5[8:10] == b"\x40\x47", f"varint arm {p5[8:10].hex()} != 4047"
    assert len(p5) == 81, f"`.5` inner length {len(p5)} != 81"
    assert p5[10] == 0x30 and p5[11] == 69, "the signature must be a DER SEQUENCE of two INTEGERs"

    ku = x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                       data_encipherment=False, key_agreement=False, key_cert_sign=False,
                       crl_sign=False, encipher_only=False, decipher_only=False)
    exts = [
        (ku, True),
        (x509.ExtendedKeyUsage([OID_EKU_RCSMLS]), False),
        (x509.BasicConstraints(ca=False, path_length=None), True),
        (x509.SubjectKeyIdentifier(ski_bytes(subject_k.public_key())), False),
        (san, False),
        (x509.CertificatePolicies([x509.PolicyInformation(OID_POLICY, None)]), False),
        (x509.UnrecognizedExtension(OID_PARTICIPANT_INFO, p4), True),
        # NOT critical: a critical `.5` is its own named rejection in validate_x509_profile.
        (x509.UnrecognizedExtension(OID_ACS_PROOF, p5), False),
    ]
    report(out, "lab2_kds_leaf.der",
           sign_cert(subject, ica_name, subject_k.public_key(), ica_k, serial("lab2/kds_leaf"),
                     KDS_NB, KDS_NA, exts))
    for nm, k in (("subject", subject_k), ("participant", participant_k)):
        report(out, f"lab2_kds_leaf_{nm}_priv.bin",
               k.private_numbers().private_value.to_bytes(32, "big"))


# ---- chain v1: the retired chain ---------------------------------------------------------------

V1_POP_LEAVES = [
    ("pa",     "7288ba3c-0000-0000-0000-0000000000a1", "+15551110001", False),
    ("pb",     "7288ba3c-0000-0000-0000-0000000000b2", "+15551110002", False),
    ("badpop", "7288ba3c-0000-0000-0000-0000000000cc", "+15551110099", True),
]


def mint_v1(out, report):
    """The RETIRED chain. Reproduced literally, quirks included.

    `leaf_a`/`leaf_b` are the only two fixtures still reached by a test (`chain_v1`, and mls-rs's
    own validator in `rcc16::x509_roundtrip`), and what they are FOR is being non-conformant in
    ways the current minter cannot express: no EKU, no SKI, no certificatePolicies, and a `.4`
    that is a 16-byte placeholder rather than a PoP. `rcc16_full_validation` asserts they are
    REJECTED. Teaching MlsCredential to emit those would be a worse trade than keeping them.

    The root here carries NO KeyUsage and the ICA carries pathlen:0 — both differ from the lab2
    minter, and both are preserved because `real_issuers_carry_a_subject_key_identifier` runs the
    issuer profile over them as they are.
    """
    root_k = key("v1/root")
    ica_k = key("v1/ica")
    root_name = name(V1_ROOT_CN, ORG)            # CN first, UTF8String — this chain's shape
    ica_name = name(V1_ICA_CN, ORG)
    root_ski = ski_bytes(root_k.public_key())
    ica_ski = ski_bytes(ica_k.public_key())

    report(out, "root.der", sign_cert(
        root_name, root_name, root_k.public_key(), root_k, serial("v1/root"),
        V1_CA_NB, V1_ROOT_NA, [
            (x509.SubjectKeyIdentifier(root_ski), False),
            (x509.AuthorityKeyIdentifier(root_ski, None, None), False),
            (x509.BasicConstraints(ca=True, path_length=None), True),
        ]))
    report(out, "ica.der", sign_cert(
        ica_name, root_name, ica_k.public_key(), root_k, serial("v1/ica"),
        V1_CA_NB, V1_ICA_NA, ca_exts(ica_k.public_key(), root_ski, path_length=0, vendor=False)))

    # leaf_a / leaf_b: no EKU, no SKI, no certificatePolicies, dummy `.4`.
    ku = x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                       data_encipherment=False, key_agreement=False, key_cert_sign=False,
                       crl_sign=False, encipher_only=False, decipher_only=False)
    for nm, cn, e164 in (("a", "7288ba3c-0000-0000-0000-00000000000a", "+15551110001"),
                         ("b", "7288ba3c-0000-0000-0000-00000000000b", "+15551110002")):
        k = key(f"v1/leaf_{nm}")
        subject = name(cn, ORG)
        san = x509.SubjectAlternativeName([x509.UniformResourceIdentifier("tel:" + e164)])
        report(out, f"leaf_{nm}.der", sign_cert(
            subject, ica_name, k.public_key(), ica_k, serial(f"v1/leaf_{nm}"),
            V1_LEAF_NB, V1_LEAF_NA, [
                (san, False),
                (ku, True),
                (x509.UnrecognizedExtension(OID_PARTICIPANT_INFO, V1_DUMMY_P4), True),
                (x509.SubjectKeyIdentifier(ski_bytes(k.public_key())), False),
                (x509.AuthorityKeyIdentifier(ica_ski, None, None), False),
            ]))
        report(out, f"leaf_{nm}_priv.bin", k.private_numbers().private_value.to_bytes(32, "big"))
        report(out, f"leaf_{nm}_pub.bin", point65(k.public_key()))

    # leaf_pa / leaf_pb / leaf_badpop: a real one-key PoP, no SKI, no AKI, no certificatePolicies,
    # and the pre-2026-08 key-roll stub sitting in what is actually the ACS-proof slot. NO TEST
    # READS THESE. They are re-minted rather than dropped because deleting a fixture is a
    # different decision from re-minting one.
    for nm, cn, e164, bad in V1_POP_LEAVES:
        k = key(f"v1/leaf_{nm}")
        subject = name(cn)
        san = x509.SubjectAlternativeName([x509.UniformResourceIdentifier("tel:" + e164)])
        p4 = participant_info(subject, san, V1_POP_NB, V1_POP_NA, k.public_key(), k, corrupt=bad)
        exts = [
            (san, False),
            (x509.ExtendedKeyUsage([OID_EKU_RCSMLS]), False),
            (ku, True),
            (x509.UnrecognizedExtension(OID_PARTICIPANT_INFO, p4), True),
        ]
        if not bad:
            exts.append((x509.UnrecognizedExtension(OID_ACS_PROOF, V1_KEYROLL_STUB), False))
        report(out, f"leaf_{nm}.der", sign_cert(
            subject, ica_name, k.public_key(), ica_k, serial(f"v1/leaf_{nm}"),
            V1_POP_NB, V1_POP_NA, exts))
        if not bad:
            report(out, f"leaf_{nm}_priv.bin",
                   k.private_numbers().private_value.to_bytes(32, "big"))
            report(out, f"leaf_{nm}_pub.bin", point65(k.public_key()))


# ---- self-check ---------------------------------------------------------------------------------

def self_check(out):
    """Re-derive, from the files just written, the properties the Rust suite pins.

    A mint that reports what it INTENDED is worth very little; these read the artefacts back.
    """
    def load(f):
        return x509.load_der_x509_certificate(open(os.path.join(out, f), "rb").read())

    def ext(c, dotted):
        return next(e for e in c.extensions if e.oid.dotted_string == dotted)

    def p4_of(c):
        return ext(c, "2.23.146.2.1.4").value.public_bytes()

    def elements(seq):
        res, p = [], 0
        while p < len(seq):
            s, t = p, seq[p]
            p += 1
            l = seq[p]
            p += 1
            if l & 0x80:
                n = l & 0x7F
                l = int.from_bytes(seq[p:p + n], "big")
                p += n
            res.append((t, seq[s:p + l], seq[p:p + l]))
            p += l
        return res

    def pop_verifies(c):
        """The verifier's own reconstruction, re-derived here so this check cannot agree with the
        mint by sharing its code: SEQ{ subject, vendorId, validity, LEAF SPKI, SAN }."""
        els = elements(elements(p4_of(c))[0][2])
        body = (c.subject.public_bytes() + els[0][1] + els[1][1]
                + spki_der(c.public_key()) + ext(c, "2.5.29.17").value.public_bytes())
        recon = tlv(0x30, body)
        pk = serialization.load_der_public_key(els[4][1])
        try:
            pk.verify(els[3][2][1:], recon, ec.ECDSA(hashes.SHA256()))
            return True
        except Exception:
            return False

    fails = []

    def check(ok, what):
        print(f"  {'ok  ' if ok else 'FAIL'}  {what}")
        if not ok:
            fails.append(what)

    print("self-check:")
    # No branding anywhere in the names.
    for f in sorted(x for x in os.listdir(out) if x.endswith(".der")):
        c = load(f)
        txt = c.subject.rfc4514_string() + c.issuer.rfc4514_string()
        if f.startswith(("google_", "live_root_")):
            continue
        for org in re.findall(r"O=([^,]*)", txt):
            check(org == "LineageOS",
                  f"{f} carries a non-LineageOS organisation: {org!r}")

    # The `.4` PoP on every fixture that has a real one.
    for f in ["lab2_leaf_pa.der", "lab2_leaf_pb.der", "lab2_leaf_pc.der",
              "lab2_leaf_pa_renewed.der", "lab2_kds_leaf.der", "leaf_pa.der", "leaf_pb.der"]:
        check(pop_verifies(load(f)), f"{f}: .4 PoP verifies")
    for f in ["lab2_leaf_badpop.der", "leaf_badpop.der"]:
        c = load(f)
        els = elements(elements(p4_of(c))[0][2])
        # The negative must fail on the SIGNATURE, so the key must still parse and the signature
        # must still be a well-formed ECDSA-Sig-Value. A fixture that fails earlier proves nothing.
        serialization.load_der_public_key(els[4][1])
        check(els[3][2][1] == 0x30, f"{f}: .4 signature is still a DER SEQUENCE (parses)")
        check(not pop_verifies(c), f"{f}: .4 PoP FAILS, and on the signature")

    # `.4` arity: exactly five elements, no trailing participantKeyRolls.
    for f in ["lab2_leaf_pa.der", "lab2_kds_leaf.der", "lab2_leaf_badpop.der"]:
        check(len(elements(elements(p4_of(load(f)))[0][2])) == 5, f"{f}: .4 has exactly 5 members")

    # One key vs two keys — the discrimination the suite depends on.
    pa = load("lab2_leaf_pa.der")
    kds = load("lab2_kds_leaf.der")
    check(elements(elements(p4_of(pa))[0][2])[4][1] == spki_der(pa.public_key()),
          "lab2_leaf_pa is a ONE-key fixture (.4[4] == leaf SPKI)")
    check(elements(elements(p4_of(kds))[0][2])[4][1] != spki_der(kds.public_key()),
          "lab2_kds_leaf is a TWO-key fixture (.4[4] != leaf SPKI)")

    # `.5`, to the byte.
    p5 = ext(kds, "2.23.146.2.1.5")
    v = p5.value.public_bytes()
    na = int(kds.not_valid_after_utc.timestamp())
    check(not p5.critical, "lab2_kds_leaf: .5 is NOT critical")
    check(len(v) == 81 and v[:8] == struct.pack(">Q", KDS_ACS_EXPIRY) and v[8:10] == b"\x40\x47",
          "lab2_kds_leaf: .5 is 81 B = expiry 1791149096 + 2-byte varint 4047 + 71 B signature")
    check(v[10] == 0x30 and v[11] == 69, "lab2_kds_leaf: .5 signature is SEQUENCE(69)")
    check(na == 1789766788 and KDS_ACS_EXPIRY - na == 1_382_308,
          "lab2_kds_leaf: .5 expiry outlives notAfter by exactly 1_382_308 s")
    check(ext(kds, "2.23.146.2.1.4").critical, "lab2_kds_leaf: .4 is CRITICAL")

    # The renewal is a renewal.
    ren = load("lab2_leaf_pa_renewed.der")
    check(ren.subject == pa.subject and spki_der(ren.public_key()) == spki_der(pa.public_key()),
          "lab2_leaf_pa_renewed: same subject and same key as lab2_leaf_pa")
    check(p4_of(ren) == p4_of(pa), "lab2_leaf_pa_renewed: byte-identical .4")
    check(ren.serial_number != pa.serial_number, "lab2_leaf_pa_renewed: a NEW serial")
    check(int(pa.not_valid_after_utc.timestamp()) == 1791387137,
          "lab2_leaf_pa notAfter == ffi.rs PA_NOT_AFTER")
    check(int(ren.not_valid_after_utc.timestamp()) == 1791892800,
          "lab2_leaf_pa_renewed notAfter == ffi.rs RENEWED_NOT_AFTER")

    # LAB2_NOW must sit inside every lab2 leaf window, or the whole suite moves.
    LAB2_NOW = 1786752000
    for f in ["lab2_leaf_pa.der", "lab2_leaf_pb.der", "lab2_leaf_pc.der", "lab2_leaf_badpop.der",
              "lab2_leaf_pa_renewed.der"]:
        c = load(f)
        check(c.not_valid_before_utc.timestamp() <= LAB2_NOW < c.not_valid_after_utc.timestamp(),
              f"{f}: LAB2_NOW (1786752000) is inside the validity window")

    # The retired leaves must STAY non-conformant.
    for f in ["leaf_a.der", "leaf_b.der"]:
        c = load(f)
        oids = {e.oid.dotted_string for e in c.extensions}
        check("2.5.29.37" not in oids and "2.5.29.32" not in oids,
              f"{f}: still carries NO EKU and NO certificatePolicies")
        check(p4_of(c) == V1_DUMMY_P4, f"{f}: .4 is still the 16-byte placeholder")

    # Key companions correspond to the certificates they are named after.
    pairs = [("lab2_leaf_pa", "lab2_leaf_pa.der"), ("lab2_leaf_pb", "lab2_leaf_pb.der"),
             ("lab2_leaf_pc", "lab2_leaf_pc.der"), ("leaf_a", "leaf_a.der"),
             ("leaf_b", "leaf_b.der"), ("leaf_pa", "leaf_pa.der"), ("leaf_pb", "leaf_pb.der")]
    for stem, der in pairs:
        d = open(os.path.join(out, f"{stem}_priv.bin"), "rb").read()
        pub = open(os.path.join(out, f"{stem}_pub.bin"), "rb").read()
        k = ec.derive_private_key(int.from_bytes(d, "big"), ec.SECP256R1())
        c = load(der)
        check(spki_der(k.public_key()) == spki_der(c.public_key()) and point65(k.public_key()) == pub,
              f"{stem}_priv.bin / _pub.bin match {der}")
    for stem, want_spki in (("subject", spki_der(kds.public_key())), ("participant", None)):
        d = open(os.path.join(out, f"lab2_kds_leaf_{stem}_priv.bin"), "rb").read()
        k = ec.derive_private_key(int.from_bytes(d, "big"), ec.SECP256R1())
        if want_spki is not None:
            check(spki_der(k.public_key()) == want_spki,
                  "lab2_kds_leaf_subject_priv.bin certifies lab2_kds_leaf.der")
        else:
            check(elements(elements(p4_of(kds))[0][2])[4][1] == spki_der(k.public_key()),
                  "lab2_kds_leaf_participant_priv.bin is the key bound in .4")
    for nm in ("root", "ica"):
        k = serialization.load_der_private_key(
            open(os.path.join(out, f"lab2_{nm}_priv.p8"), "rb").read(), password=None)
        check(spki_der(k.public_key()) == spki_der(load(f"lab2_{nm}.der").public_key()),
              f"lab2_{nm}_priv.p8 matches lab2_{nm}.der")

    # Chain signatures, both chains.
    for leaf, issuer in [("lab2_leaf_pa.der", "lab2_ica.der"), ("lab2_kds_leaf.der", "lab2_ica.der"),
                         ("lab2_leaf_pa_renewed.der", "lab2_ica.der"),
                         ("lab2_ica.der", "lab2_root.der"), ("lab2_root.der", "lab2_root.der"),
                         ("leaf_a.der", "ica.der"), ("ica.der", "root.der"),
                         ("root.der", "root.der")]:
        c, i = load(leaf), load(issuer)
        try:
            i.public_key().verify(c.signature, c.tbs_certificate_bytes,
                                  ec.ECDSA(hashes.SHA256()))
            ok = True
        except Exception:
            ok = False
        check(ok, f"{leaf} is signed by {issuer}")

    if fails:
        print(f"\n{len(fails)} SELF-CHECK FAILURE(S) — the fixtures are NOT usable:")
        for f in fails:
            print(f"  - {f}")
        return 1
    print("  all self-checks passed")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)),
                    help="directory to write into (default: this script's directory)")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    def report(out, fname, data):
        with open(os.path.join(out, fname), "wb") as fh:
            fh.write(data)
        note = ""
        if fname.endswith(".der"):
            c = x509.load_der_x509_certificate(data)
            note = (f"  subject={c.subject.rfc4514_string()}"
                    f"  notAfter={c.not_valid_after_utc:%Y-%m-%dT%H:%M:%SZ}")
        print(f"  wrote {fname:38s} {len(data):5d} B{note}")

    print(f"regenerating into {args.out}")
    mint_lab2(args.out, report)
    mint_v1(args.out, report)
    return self_check(args.out)


if __name__ == "__main__":
    sys.exit(main())

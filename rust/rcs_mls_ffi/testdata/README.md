<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# `testdata/` — what each fixture is for

## The test chain: `pki2_*`

`pki2_root.der` → `pki2_ica.der` → `pki2_leaf_p{a,b,c}.der`, plus `pki2_leaf_badpop.der`.
Minted by `regen-chain.py` in this directory:

```
python3 rust/rcs_mls_ffi/testdata/regen-chain.py
```

It reproduces every property the Rust tests pin: the validity windows, the RCC.16 `.4`
ParticipantInformation, the SANs, and the one-byte corruption of `pki2_leaf_badpop`'s PoP
signature. Run `cargo test` after re-minting; a negative fixture that starts passing is the
failure to look for. The organisation and CN prefixes must match `LOCAL_PKI_ORG`,
`LOCAL_PKI_ROOT_CN_PREFIX` and `LOCAL_PKI_ICA_CN_PREFIX` in `MlsCredential.java`, which mints the
equivalent chain at runtime.

| file | what it is |
|---|---|
| `pki2_leaf_pa` | participant +15551110001, device 1 |
| `pki2_leaf_pb` | participant +15551110002 |
| `pki2_leaf_pc` | participant +15551110001, device 2: same SAN, different key and subject |
| `pki2_leaf_badpop` | same CA, valid certificate signature, one byte flipped inside the `.4` PoP signature |
| `pki2_leaf_pa_renewed` | `pki2_leaf_pa` re-issued: same subject CN, key, SAN and `.4`; new serial and a later `notAfter` |
| `pki2_kds_leaf` | the two-key leaf (below); its `.5` ACS proof is pinned to the byte |
| `pki2_root_priv.p8`, `pki2_ica_priv.p8` | the CA private keys, PKCS#8 |

`pki2_leaf_pa_renewed` is a renewal, not a second identity: the same CN (what `valid_successor`
compares) and the same key. Only the serial and validity differ, and neither is inside the `.4`
PoP's signed bytes (`SEQ{subject, vendorId, participantSignatureValidity, leafSPKI, SAN}`), so the
copied `.4` still verifies. It backs `a_self_update_carries_a_re_minted_credential_into_the_group`;
`pki2_leaf_pc` cannot, because its subject differs and `valid_successor` refuses it.

The CA private keys are committed so any leaf can be re-issued; a fixture set that cannot be
re-minted ends up limiting how strict the validator may be. They anchor nothing real.

`pki2_leaf_badpop` is corrupted in the signature, not the key: a corrupted participant key fails
as an unparseable key, which would not test PoP verification.

The leaves are valid for 60 days, inside RCC.16 A.4.1's 76-day cap. Tests evaluate at the pinned
`PKI2_NOW`, never the wall clock, so they do not expire; re-minting means re-pinning `PKI2_NOW`.

## The v1 chain: `root.der` / `ica.der` / `leaf_*`

Kept for fixtures that are deliberately non-conformant in ways unrelated to the profile rules:
`leaf_a.der` and `leaf_b.der` carry no RCC.16 EKU, and the v1 leaves carry no SKI and no
certificatePolicies. Reached through `chain_v1()` / `validator_v1()`. `regen-chain.py`
re-mints this chain with those defects on purpose; the runtime minter must never emit them.

## `pki2_kds_leaf.der` — the two-key fixture

A key-server leaf's certified subject key and its participant key are two different keys. This
is the only fixture where they differ, so it is the only one that detects the two roles being
swapped in the `tbsParticipantInfo` layout; every other leaf uses one key for both.
`regen-chain.py` mints it with two keys and asserts they differ. Its `.5` field reproduces a real
key-server leaf's structure (the expiry offset over `notAfter`, a 71-byte DER signature on the
2-byte varint arm), with synthetic identities.

## Public CA certificates

`google_kds_client_ca.der`, `google_kds_ica.der` and
`live_root_{android_mls,apple_rcs,google_kds}.der` are public CA certificates, sent to every peer.
They are not regenerated; `regen-chain.py` never touches them.

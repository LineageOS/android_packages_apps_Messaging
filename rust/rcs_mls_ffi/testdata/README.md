# `testdata/` — what each fixture is for

## The CURRENT lab chain: `lab2_*` (minted 2026-08-08)

`lab2_root.der` → `lab2_ica.der` → `lab2_leaf_p{a,b,c}.der`, plus `lab2_leaf_badpop.der`.

**Minted by `regen-chain.py`, in this directory.** Re-mint the whole chain with:

```
python3 rust/rcs_mls_ffi/testdata/regen-chain.py
```

It reproduces every property the Rust suite pins — the validity windows (so
`LAB2_NOW` does not move), the RCC.16 `.4` ParticipantInformation, the SANs, and
the deliberate one-byte corruption in `lab2_leaf_badpop`'s PoP signature. **Run
`cargo test` after any re-mint**: a negative fixture that quietly starts passing
is the failure this chain exists to prevent.

The organisation and CN prefixes here must stay in step with `LAB_ORG`,
`LAB_ROOT_CN_PREFIX` and `LAB_ICA_CN_PREFIX` in `MlsCredential.java`, which
mints the equivalent chain at runtime on device.

The chain was ORIGINALLY produced on a device by **`MlsCredential`** via

```
adb shell am broadcast -a com.android.messaging.debug.MLS_DUMP_PKI \
  -n com.android.messaging/.rcs.e2ee.MlsSelfTestReceiver \
  --es dir /data/user/0/com.android.messaging/cache/labpki \
  --es msisdns "+15551110001,+15551110002,+15551110001"
```

then pulled with `su 0 base64` (SELinux denies `platform_app` any write under
`/data/local/tmp`, so mint into the app's own cache dir).

| file | what it is |
|---|---|
| `lab2_leaf_pa` | participant **+15551110001**, device 1 |
| `lab2_leaf_pb` | participant **+15551110002** |
| `lab2_leaf_pc` | participant **+15551110001**, **device 2** — same SAN, different key/subject |
| `lab2_leaf_badpop` | same CA, **valid certificate signature**, one byte flipped inside the `.4` PoP *signature* |
| `lab2_leaf_pa_renewed` | **`lab2_leaf_pa` RE-MINTED**: same subject CN, same key, same SAN and same `.4` ParticipantInformation, re-issued by the same ICA with a new serial and `notAfter` 2026-10-13 12:00 Z (`lab2_leaf_pa`'s is 2026-10-07 15:32:17 Z) |
| `lab2_kds_leaf` | the **two-key** reference leaf — see below; its `.5` ACS proof is pinned to the byte |
| `lab2_root_priv.p8`, `lab2_ica_priv.p8` | the CA private keys, **PKCS#8** |

### `lab2_leaf_pa_renewed` — the certificate-renewal fixture

A real renewal and not a second identity, which is the whole point: **same CN**
(what `valid_successor` compares, so a peer accepts the change) and **same key**
(what a KDS renewal actually does — our subject key is derived deterministically
and does not move on a re-mint). Only the serial and the validity differ, and
neither is inside the `.4` PoP's signed bytes
(`SEQ{subject, vendorId, participantSignatureValidity, leafSPKI, SAN}`), so the
copied `.4` still verifies.

Minted on the host from the committed `lab2_ica_priv.p8`. It is the fixture
behind `a_self_update_carries_a_re_minted_credential_into_the_group`, which would be
untestable with `lab2_leaf_pc` (same SAN but a **different subject**, so
`valid_successor` refuses it and the peer would reject the Commit).

### Two things about this set that are deliberate

**The CA private keys are committed.** The previous chain's were not, so no leaf
could ever be re-issued under it — and two §14.2.3 rules (subjectKeyIdentifier,
certificatePolicies) had to be *relaxed in the validator* to accommodate a
fixture nobody could regenerate. A fixture set that cannot be re-minted silently
becomes the ceiling on how strict the validator is allowed to be. These anchor
nothing real; the cost of withholding them was measured at one frozen rule each.

**`lab2_leaf_badpop`'s corruption is in the signature, not the key.** The first
attempt flipped the last byte of the `.4` structure, which lands in the
participant key's SEC1 point — the fixture then failed with *"vk: signature
error"*, an unparseable **key**, while a test asserted it proved a bad **PoP**.
The distinction is the whole value of the fixture.

Validity is 2026-08-08 → 2026-10-07 (60 d, inside A.4.1's 76 d cap). Tests
evaluate at the **pinned** `LAB2_NOW` (2026-08-15), never the wall clock: a
76-day-capped profile means every fixture expires, and a suite that reads the
clock goes red on a date nobody chose. Re-minting means re-pinning `LAB2_NOW`.

## The RETIRED lab chain: `root.der` / `ica.der` / `leaf_*`

Kept **only** for fixtures that are deliberately non-conformant in ways
unrelated to the profile rules above — `leaf_a.der` and `leaf_b.der` carry no
RCC.16 EKU. Re-minting those would mean teaching the minter to emit broken
certificates, which is a worse trade than keeping the chain that already
produced them. Reached in tests through `chain_v1()` / `validator_v1()`.

`regen-chain.py` **does** re-mint this chain, reproducing those defects on
purpose — no EKU, no SKI, no certificatePolicies, and a 16-byte placeholder where
a leaf would carry its `.4` PoP. That is the distinction: the RUNTIME minter must
not learn to emit them, a regeneration script must.

The retired leaves carry no SKI and no certificatePolicies. That is what forced
the relaxations; do not treat it as an acceptable lab shape.

## `lab2_kds_leaf.der` — the TWO-KEY fixture

It is the **only** fixture with any power over the `tbsParticipantInfo` layout: a
real KDS leaf's certified subject key and its participant key are two *different*
keys, so getting their roles backwards fails here and nowhere else. Every other
synthetic leaf uses one key for both, which makes the two candidate layouts
byte-identical — those fixtures cannot discriminate the thing they are testing.
`regen-chain.py` mints it with two keys and asserts they differ; do not
"simplify" it to a one-key leaf.

It replaced `genuine_kds_leaf.der`, which was a REAL credential — issued by
Google's production KDS to one of our devices, with that device's MSISDN in its
SAN — and was the only binary in the repo carrying PII. Its `.5` is reproduced to
the byte (expiry 1791149096, the 1_382_308 s gap over `notAfter`, a 71-byte DER
signature on the 2-byte MLS varint arm), so nothing was loosened to accept the
replacement.

## Real-world artefacts (never regenerate — capture again if lost)

`google_kds_client_ca.der`, `google_kds_ica.der`,
`live_root_{android_mls,apple_rcs,google_kds}.der`.

These are public CA certificates transmitted to every peer, not identity
material. `regen-chain.py` never touches them.

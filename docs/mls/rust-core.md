<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# MLS: the Rust core and the JNI bridge

The MLS protocol itself (RFC 9420) and the RCC.16 rules that sit inside it run in a Rust crate,
`rust/rcs_mls_ffi`, built on the mls-rs library. Java reaches it through a small C bridge,
`jni/mls_openmls_bridge.c`. This page describes the crate's layout, the C ABI, storage, the RCC.16
rules enforced in Rust, and credential validation. The Java side is in [overview.md](overview.md).

## Build

| module | kind | source |
|---|---|---|
| `librcs_mls_ffi` | `rust_ffi_static`, crate `rcs_mls_ffi` | `rust/rcs_mls_ffi/src/lib.rs` |
| `libmlsopenmlsbridge` | `cc_library_shared`, `whole_static_libs: ["librcs_mls_ffi"]` | `jni/mls_openmls_bridge.c` |

Both are arm64-only. The crate is built from source by Soong against the mls-rs crates under
`external/mls-rs`, which carry a fork of mls-rs 0.55.2 with patches the RCS flows need (a
SELF_REMOVE proposal inside an external commit, and target resolution for an external leaf in batch
edits). Enabled mls-rs features: `last_resort_key_package_ext`, `self_remove_proposal`,
`export_key_generation`, `secret_tree_access`, `prior_epoch_membership_key`,
`gsma_rcs_e2ee_feature`.

`Cargo.toml` exists for `cargo test` and IDEs and resolves the same mls-rs crates Soong compiles:
it names them through `external/mls-rs/android/cargo/`, where the vendored `mls-rs-codec` 0.7.0,
`mls-rs-codec-derive` 0.2.0 and `mls-rs-crypto-rustcrypto` 0.22.1 stand in for the repository's
copies, and patches crates.io to the same paths, so host tests run the X.509 checks of
rustcrypto 0.22.1 as the device does. Soong does not read it (`Android.bp` names the dependencies),
so a dependency change goes into both files in the same change.

The module uses the `vendor` lint set rather than the platform default; clippy still runs and
warnings are errors. The platform set would add `missing-docs` and `unsafe_op_in_unsafe_fn`, which
this crate is not written to. `empty_line_after_outer_attr` is deny-level: no blank line may sit
between an attribute and its item, so deleting a doc comment above an attributed item must delete
its lines rather than blank them.

`lib.rs` allows `dead_code` and `unused_imports` outside `cfg(test)`, because Soong builds without
the test configuration and several helpers and imports exist only for the test suite.

## Crate layout

| module | role |
|---|---|
| `lib.rs` | module list; a minimal basic-credential session (`RcsMlsSession`) used only by the crate's own round-trip and restart tests |
| `ffi.rs` | the production session (`ProdSession`) and every `extern "C"` export (`rcs_mls_*`) |
| `storage.rs` | `FileGroupStateStorage` and `FileKeyPackageStorage`, mls-rs's storage traits over files |
| `rcc16.rs` | RCC.16 constants and rules: extension and proposal registries, advertisement lists, era rules (`Rcc16MlsRules`), the spec revision, `end_mls` payloads, extension framing, `AuthenticatedData` |
| `rcc16_validate.rs` | `Rcc16Validator`: X.509 chain validation plus the RCC.16 certificate profile |
| `rcc16_build.rs` | DER encoders for the client side of certificate issuance (subject, SAN, validity, TBS, `.4` ParticipantInformation) |
| `rcc16_mint.rs` | a local test PKI (root, intermediate, leaf) for the self-test APK; not a certificate authority |
| `treeless_welcome.rs` | rebuilds a ratchet tree from LeafNodes shipped beside a Welcome |

Test fixtures live in `rust/rcs_mls_ffi/testdata` and are minted by `regen-chain.py` there, which
reproduces every property the tests pin (validity windows, the `.4` extension, SANs, and the
deliberately corrupted proof-of-possession of the negative fixture). Tests that validate these
fixtures evaluate them at the pinned instant `PKI2_NOW`, never the wall clock, because the fixtures
have a capped lifetime and would otherwise expire on a date nobody chose. For engine-driven
validation the pin sits in `X509WithBasicCreds` under `cfg(test)`, and it is a constant because
mls-rs validates on a rayon worker pool where a thread-local override would not be seen. mls-rs
also checks each added KeyPackage's lifetime at the commit time, so under `cfg(test)` every engine
commit (`commit_builder`) and the leaf anchor's clock (`wall_clock_secs`) read `PKI2_NOW` too.

Smoke exports and test fixtures embedded with `include_bytes!` are `cfg(test)`-only, so no private
key material ends up in the shipped static library.

## Cipher suite and credentials

* Cipher suite `P256_AES128` (RFC 9420 `0x0002`, `MLS_128_DHKEMP256_AES128GCM_SHA256_P256`), with
  the RustCrypto provider.
* Credentials are X.509 chains, leaf first. The leaf certifies the device's P-256 key and carries
  the participant's `tel:` URI in its SAN.
* `X509WithBasicCreds` advertises both `basic(1)` and `x509(2)` in the leaf's credential
  capabilities so a peer presenting a basic credential is not seen as unsupported during capability
  negotiation. Validation is delegated unchanged to the X.509 provider, since our own credentials
  are always X.509.

`ProdConfig` layers the client: `FileKeyPackageStorage` → `Rcc16MlsRules` →
`FileGroupStateStorage` → `X509WithBasicCreds<X509IdentityProvider<_, Rcc16Validator>>` →
`RustCryptoProvider`.

## The C ABI

### Memory and handles

```c
typedef struct { unsigned char *data; size_t len; } RcsBytes;
```

* Every op returning bytes returns an owned `RcsBytes`; the caller releases it with
  `rcs_mls_bytes_free`. A failure returns `{NULL, 0}`.
* `rcs_mls_session_start(...)` returns a boxed `ProdSession*` or `NULL`; `rcs_mls_session_close`
  frees it. Every session op returns `{NULL, 0}` for a null handle.
* Byte inputs are `(pointer, length)`; a null pointer or zero length is an empty slice.
* Repeated inputs and outputs (certificate chains, trust anchors, revoked serials, KeyPackage lists,
  bundle slots) use `[u32 big-endian length][bytes]` records.

### The JNI bridge

`jni/mls_openmls_bridge.c` declares the `rcs_mls_*` prototypes and one JNI function per export,
named through `JNI_FN(name)` → `Java_com_android_messaging_rcs_engine_mls_OpenMlsNative_<name>`.
The Java side is `OpenMlsNative`, whose natives are wrapped by `OpenMlsSession` (session ops) and
`Rcc16Der` (the encoders). The session handle is the `ProdSession*` cast to `jlong`.

Two helpers do all the marshalling:

* `jba` copies a `jbyteArray` into a `malloc` buffer; a null or empty array becomes `NULL, 0`.
* `rb_to_jba` copies an `RcsBytes` into a new `jbyteArray` and frees the native buffer. A null or
  **zero-length** result becomes a Java `null`.

So on the Java side an empty result and a failure both read as `null`, and the status channel
below tells them apart. `nativeSetRcc16Version` maps an out-of-range `jint` to a refused value
rather than truncating it.

### Status and side channels

Results are bytes or null; what a null *meant* travels in thread-locals, read immediately after the
call on the same thread. A JNI call runs on the calling Java thread, so the read is exact for that
caller, and no return value needs to carry a status byte.

| export | thread-local | contract |
|---|---|---|
| `rcs_mls_last_status` | `LAST_STATUS` | `0 OK`, `1 NO_OP`, `2 NOT_FOUND`, `3 ERR` (`MlsStatus`); read by `MlsSession.lastStatus()` |
| `rcs_mls_take_state_bytes` | `LAST_STATE_BYTES` | largest group-state record written on this thread since the last read; reading clears it |
| `rcs_mls_last_aad` | `LAST_AAD` | `authenticated_data` of the last application message processed |
| `rcs_mls_set_request_message_id` | `EXPECTED_MESSAGE_ID` | the message id an inbound request is expected to carry (RCC.16 §7.5.3.1); null skips the check, and a non-null empty id is armed |
| `rcs_mls_last_message_id_mismatch`, `_detail` | `LAST_ID_MISMATCH` | set when the AAD's message id differs from the expected one (`MessageIdMismatch`); detail is `expected\0actual` |
| `rcs_mls_set_next_resent_component` | `NEXT_RESENT_COMPONENT` | an RCC.16 §10.3 resent-message component for the next AAD the engine builds; one-shot |
| `rcs_mls_last_sender_msisdn` | `LAST_SENDER_MSISDN` | the MSISDN certified in the leaf that signed the last application message; empty means unknown, never a match |

Both transports take the AAD message-id verdict from the engine; see [rcc16-map.md](rcc16-map.md).
`MlsEngineIdCheck.process` arms `EXPECTED_MESSAGE_ID` with the envelope id (an empty one when the
envelope carried none), processes, reads `lastMessageIdMismatch` on the same thread, and clears the
id in a `finally`, since it is sticky per thread. On a mismatch the provider leg refuses with
`AAD_MESSAGE_ID_MISBINDING`, naming the AAD's id from `_detail`, and the carrier leg drops the
message. There is no host-side parse beside it.

The sender MSISDN matters because mls-rs authenticates the sender only as *some current member*.
The transport envelope's sender is unauthenticated, so any member could otherwise send a message
that is displayed as coming from another member. The decrypt authenticates the sender's leaf index,
the roster maps it to a leaf, and the leaf's SAN carries the certified number. The value is cleared
by non-application messages so a stale identity is never attributed to the next message.

### Errors

Every op is wrapped by `logged(op, result)`: `Ok` sets `OK` and returns the bytes; `Err` sets the
error's status, logs a line tagged with that status, and returns `{NULL, 0}`. Errors default to
`ERR` (`From<String> for MlsError`). `NO_OP` and `NOT_FOUND` are reserved in the status space and
logged as such, and `not_found(..)` builds the latter, but no op returns either today.

Mutating ops use `logged_tx(op, session, result)`, which on failure also invalidates the session's
sender cache. One engine call is one atomic storage unit, but the in-memory cached group may already
have been touched by a failed op; the next encrypt would then ratchet from a state that was never
committed. Mutating ops also invalidate that cache eagerly at their start, which protects the
success path (a landed commit makes the cached group stale); the failure-path invalidation covers
the other case.

### The sender cache

`ProdSession.send_cache` keeps the group of the last encrypt in memory across consecutive encrypts
in one epoch. Reloading a group from storage for every encrypt re-derives the application secret
tree from generation 0 while the generation counter persists, which would encrypt message N with
generation 0's key. Every group-modifying op (process, commit, join, delete, restore) clears it.

### Read-only scope

Read-shaped verbs (`epoch_auth`, `era_epoch`, `next_app_gen`, `export_group_snapshot`,
`self_leaf_status`, the KeyPackage inspectors, and others) run inside `read_only(..)`. While a
read-only scope is open on the thread, both file stores refuse to write and log the refusal.
Write-freedom of a read path is thereby enforced at the one place every write passes through,
instead of being a convention. The scope is a depth counter with a `Drop` guard, so nesting unwinds
and a panic cannot leave the thread refusing writes.

### The artifact bundle

Group operations (create, add, remove, era advance, metadata commits) return a length-prefixed list
decoded on the Java side by `MlsArtifactBundle`:

| slot | contents |
|---|---|
| 0 | Welcome (empty when nobody is admitted) |
| 1 | commit |
| 2 | GroupInfo (for external commits; carries `0xF001`) |
| 3 | epoch authenticator of the new epoch, 32 bytes |
| 4 | MLS group id |
| 5 | ratchet tree, exported separately from the GroupInfo |
| 6 | era the engine built at, `u32` big-endian (`create_group_planned` only) |
| 7 | Welcome action, `u32` big-endian (`create_group_planned` only) |
| 8 | admitted MSISDNs, nested length-prefixed ASCII (action 3 only) |

Slots are appended and never reordered, so every existing index stays stable. The static library is
built separately from the Java code that reads it, so the two can be out of step, and a short bundle
is normal: it comes from an entry point that does not report the later slots. `MlsArtifactBundle`
decodes a missing trailing slot as absent (`era = -1`, `welcomeAction = null`), reads a slot of the
wrong width as absent, keeps the slots it already read when the tail is truncated, and never returns
a null `admittedMembers`. An absent slot must never decode to 0: `welcomeAction` 0 is `UNKNOWN`
("the engine named no action"), and an entry point that does not report an action and an engine
that refused to decide need opposite handling. The add arm's admitted list is what the RCS roster
change must name; a roster change naming anyone else is refused by the server as a mismatched group
state.

### Inbound processing results

* `rcs_mls_process_ex` returns `[status, payload…]`. The statuses (`MlsSession.ProcResult`):
  `0` application (payload = plaintext), `1` commit applied, `2` proposal cached, `3` other,
  `7` malformed (drop), `8` could not apply yet (buffer and retry), `9` a commit from an epoch
  behind ours (drop). For a proposal the result also carries its proposal type. `self_remove`
  (`0xF003`) is implemented natively by mls-rs; RCC.16 custom proposals such as `end_mls` and
  `server_remove` reach the engine as opaque custom proposals, so committing one consumes it
  without changing group state. `clearPendingProposals` clears the whole proposal cache (there is no
  per-proposal eviction), because a cached proposal blocks application messages until committed.
* `rcs_mls_process_results` and `rcs_mls_encrypt_results` return the repeated-result form decoded
  by `MlsEngineResult`: a one-byte version record (`FORMAT_VERSION` = 1), then `[u32 BE length]`
  records of `[status][context id][group id][aux][payload]`, where `aux` is the proposal type
  (`u16` BE) for status 2 and the payload is the plaintext for status 0. A version-only blob is an
  empty result list; an empty blob is an error; a different version throws, naming the build skew;
  every record needs all five fields. `process_results` wraps `process_ex` rather than
  reimplementing its classification.
* `encrypt_results` produces the ciphertext first, at the current epoch, and only then an optional
  key-update commit (`want_key_update`, chosen by the host, which keeps the usage counter). The
  other order would encrypt at an epoch the recipients have not seen. With the commit comes a third
  record (status 3): the group snapshot taken between the encrypt and the commit, which the host
  restores if the server refuses the commit, since the engine applies the commit locally first. All
  of them come back from one call so a caller cannot keep one and lose the other.

## Engine settings and revision

Four process-wide settings live in statics. One process can run sessions of both kinds (the app
runs the provider transport and the carrier transport), and each session reads the settings only
while it is built, so the host sets them on every session start and serialises the starts
(`OpenMlsEngine.startSession` holds a class-wide lock).

| static | setter | default | effect |
|---|---|---|---|
| `KP_FIXED_365_DAYS` | `rcs_mls_set_engine_settings`, bit `SETTING_KP_FIXED_365_DAYS` (0x01) | off (`WITHIN_CERTIFICATE`) | KeyPackage lifetime rule |
| `PEER_CERT_TOLERANT` | `rcs_mls_set_engine_settings`, bit `SETTING_PEER_CERT_TOLERANT` (0x02) | off (`RCC16_STRICT`) | peer-certificate strictness (`Rcc16Validator.strict`) |
| `POP_LENIENT` | `rcs_mls_set_pop_lenient` | off | whether a failed `.4` proof-of-possession rejects a peer's certificate |
| `RCC16_VERSION` | `rcs_mls_set_rcc16_version` | 30 (v3.0) | which revision's wire shapes the engine emits; refuses anything but 30 or 40 and returns the value in effect |

**Leaf lifetimes.** A leaf's `Lifetime` is the one field a peer validates against its own clock
(RFC 9420 §7.3), so a `not_before` of "now" is refused by any peer whose clock trails ours, and the
failure shows only against another implementation. No leaf is minted with mls-rs's default of now:

* **KeyPackages** (`kp_lifetime_window`) anchor `not_before` at the certificate's `notBefore`. The
  duration is the KeyPackage lifetime setting, because the two key directories have opposite
  rules: Google's requires a package to expire after its certificate (`FIXED_365_DAYS`, a fixed 365
  days); an RCC.16 §5.1 directory requires it not to outlive it (`WITHIN_CERTIFICATE`, the
  certificate window less 30 minutes). If the
  certificate does not parse, the fallback is now and 365 days. A package minted with less than 30
  days remaining is logged, because it uploads successfully and then cannot be used by peers.
* **The group creator's and an external joiner's leaf** (`leaf_lifetime_anchor`) anchor at
  `now - 24 h`, clamped to at most now and at least the certificate's `notBefore`. The certificate's
  `notBefore` alone is not used here because mls-rs also validates the chain at this instant, and a
  chain is valid only from its latest `notBefore`. Source-scan tests pin every call site and the
  count of lifetime mint sites in mls-rs (three: KeyPackage, group creation, external commit).

**Certificate strictness.** Under `DEPLOYMENT_TOLERANT` `Rcc16Validator.strict` is off: the RCC.16
A.4.1 lifetime floors (at most 76 days total, at least 30 days remaining) and some subject/policy
rules are relaxed, because deployed certificates have lifetimes that the 30-day floor would reject
for part of every certificate's life. The `.4` proof-of-possession check has its own flag,
`pop_strict`, which is on under both policies; `POP_LENIENT` (driven by `debug.rcs.mls_pop_lenient`,
which only a debug build reads) is the escape hatch that turns a failed proof back into a warning
without a rebuild.

**Revision.** The revision is announced by the transport and never inferred: RCC.16 has no in-band
version signal, and a detection heuristic would fail silently. Every version-dependent site reads
`is_v4_0_or_later()`. The rule is to decode tolerantly (accept both revisions' shapes inbound) and
encode strictly (emit exactly the announced revision's shape).

Loading the library sets none of these. `OpenMlsEngine.startSession` sets both engine settings and
the revision explicitly on every session start, before `nativeSessionStart`, under its lock,
through `nativeSetEngineSettings` (`rcs_mls_set_engine_settings`) and `nativeSetRcc16Version`.

## Groups and eras in the engine

* **Group ids.** `create_group_carry` mints a lowercase UUID-v4 string (36 ASCII bytes) when no id
  is given, because the server rejects a raw random group id; with an id (a group's RCS id, or a
  reused id on an era advance) it first deletes any stale state stored at that id, so leftover
  epoch data cannot make the new group's first apply fail.
* **Planning.** `plan_group` decides the era and the Welcome action from the group held at the id
  and the carried GroupInfo, read-only and before anything is replaced. `create_group_planned` runs
  the plan and appends slots 6-8. See
  [group-lifecycle.md](group-lifecycle.md#the-era-is-decided-by-the-engine).
* **Carrying extensions.** On an era advance the new GroupContext copies every extension of the
  carried GroupInfo byte-for-byte except `0xF001` (set to the new era) and, for the revival mode,
  `0xF002`. A deny-list, so a code point this build does not know is preserved rather than dropped
  by omission. `extension_data` is copied verbatim, since it is already framed; re-encoding would
  frame it twice.
* **Era arithmetic.** `rcc16::next_era` is the only increment and refuses to pass `u32::MAX`
  (a wrap reads as a move backwards to every peer). `check_era_advances` refuses a carried era that
  is not strictly below the new one.
* **Era immutability.** An era cannot change inside a group (below), so `commit_era_advance` refuses
  before building anything; the host's preserving modes then fall back to the create path.
* **`end_mls`.** Exactly two sites may drop `0xF002`: `create_group_carry` under
  `AdvanceEraKind::Revival`, and `commit_end_mls` under `EndMlsOp::RemoveForRevival`. Both take the
  intent from the caller as an enum rather than a boolean, so no recovery path can clear a
  downgrade by forgetting to carry it. `AdvanceEraKind::PhoenixDowngrade` installs `0xF002` in the
  new era whether or not a GroupInfo was carried. See [downgrade.md](downgrade.md).
* **Joins.** The three Welcome joins (`join`, `join_with_tree`, `join_treeless_welcome`) log the
  Welcome's decrypted GroupInfo extensions (`log_welcome_group_info_exts`, the `WELCOME-GI-EXT`
  line; log only, it never affects a join, and the mls-rs `grease` feature is off so nothing filters
  the list) and capture a continuity token if one is present. All four joins, `external_join`
  included, purge the group's retained prior-epoch archive before writing the joined state.
* **External-commit resync** (`external_commit_resync`) deletes nothing before the build. The build
  reads storage only through the prior-epoch archive's contiguity check, so the order is: attempt;
  only on `InvalidEpoch`, snapshot the record, purge the archive and attempt again; restore the
  snapshot if the retry also fails. A failed resync leaves the persisted record byte-identical
  (pinned by `a_failed_external_commit_resync_leaves_the_local_group_intact`). On
  `DuplicateLeafData` the builder retries with the fork's SelfRemove proposal, which replaces our
  existing leaf (the RFC 9420 §12.4.3.2 resync form). The result is five slots: group id, commit,
  GroupInfo, epoch authenticator, ratchet tree. `MlsExternalCommitResync` still restores its own
  snapshot after a failed build, because the native library is versioned separately from the Java
  side.
* **`rcs_signature`** (`rcs_sign`). The mls-rs codec for this custom proposal is asymmetric (encode
  writes the body bare; decode returns it empty without consuming bytes), so the body must stay
  empty or the rest of the message is misread; the signature rides in `authenticated_data`. It uses
  the fork's `rcs_signature_message`, since `propose_custom` would cache the proposal and make
  `commit_required()` true.
* **`ServerRemove`** (`0xF004`). mls-rs keeps it as `Proposal::Custom`, validating the body only by
  decoding and re-encoding a `RemoveProposal`; it never becomes `Proposal::Remove`. With the
  `gsma_rcs_e2ee_feature` feature enabled (it is; see [Build](#build)) the body is
  written bare; the default codec would length-prefix it. `can_support_proposal` requires every
  occupied leaf to declare the capability, so enabling it locally does not make a peer's `0xF004`
  supported.

### Continuity token hand-off

RCC.16 v4.0 §7.11.12.1 places the continuity token (`0xF010`) only in the encrypted GroupInfo inside
a Welcome, in `GroupInfo.extensions` rather than the GroupContext. It is in the clear only inside
`join_group`, so every join captures it there into `welcome_continuity` (at most 8 pending entries,
oldest evicted) and the host takes it with `rcs_mls_take_welcome_continuity_token` to persist it in
`MlsConversationRecord`. Taking removes it, so a group secret stays in the engine only until the
host has stored it. An undecodable token is logged and dropped; it never fails a join. Readers of
the GroupContext (`group_ext`, `group_info_ext_types`) cannot see this list by construction.
`group_info_continuity`, which reads a serialised GroupInfo, searches both the GroupInfo's own
extension list and its GroupContext's and logs which one answered (`GI-EXT-READ`); an absence from
both is the only absence it reports.

On the host, `MlsContinuityToken.noteContinuityToken` stores the token in the conversation record.
It logs each outcome as `FIRST`, `SAME` (no write), `REPLACED` (the received token wins), `OVERSIZE`
or `DROPPED` (the conversation could not be keyed, the record was unreadable, or the write failed).
An unreadable record is never overwritten, because it may hold an in-flight operation.
`MAX_STORED_TOKEN_BYTES` is 8 × the 32-byte token, so an unexpected framing is still stored while a
peer-chosen length stays bounded (the record is rewritten whole). The token is stored although
nothing consumes it yet, because it cannot be requested again on demand. The commitment helpers use
`RefHash` with the `"MLS 1.0 "` label prefix; `RccCommitment`'s Annex C.1 labels carry no prefix,
which is why the two are separate. Emission of continuity extensions is gated on v4.0
(`rcc16::emit_continuity`): emitting `0xF011` arms every peer's RCC.16 §10.5.1 check, whose
persistent failure can downgrade the conversation. See [metadata.md](metadata.md).

### Treeless Welcome

A Welcome whose GroupInfo omits the optional `ratchet_tree` extension fails `join_group(None, …)`
with `RatchetTreeNotFound`. Some senders ship the tree as the members' canonical RFC 9420 LeafNodes
in the same delivered payload, after the Welcome. `treeless_welcome::splice_ratchet_tree` locates
each LeafNode (it scans for the encryption-key marker, a varint 65 followed by an uncompressed
P-256 point, then walks the LeafNode canonically and requires more than `MIN_LEAF_LEN` = 1500
bytes, since a real leaf carries a certificate chain), copies the sender's whole node sequence
between the first and last leaf verbatim, and parses it with mls-rs's public
`ExportedTree::from_bytes`. The copy includes populated ParentNodes: a tree rebuilt from the leaves
alone, with blank parents, is correct only for a group that has seen nothing but adds, and fails the
tree hash otherwise. The length floor is what rejects false matches of the marker inside
ciphertext; it leaves under 10% of margin below leaves of about 1620 bytes, and a real leaf with a
shorter chain is skipped silently, after which the join fails with `TreeHashMismatch` rather than
naming the cause. mls-rs then verifies each leaf's
signature and the tree hash during the join. The JNI name `nativeJoinTreelessWelcome` is part of
the symbol table and is kept stable.

## RCC.16 rules inside mls-rs

### Era immutability: `Rcc16MlsRules`

`Rcc16MlsRules` wraps mls-rs's `DefaultMlsRules` and adds one check in `filter_proposals`, which
runs on both sending and receiving commits: a GroupContextExtensions proposal may not change or
remove an existing `0xF001` (`EraViolation::ChangesEra`, `EraViolation::RemovesEra`). Adding the
first era to a group that has none is allowed. Everything else (commit options, encryption options,
padding) delegates unchanged.

### Extension registry

| type | name | carried in | framing |
|---|---|---|---|
| `0xF001` | era | GroupContext | bare `u32` BE |
| `0xF002` | `end_mls` | GroupContext | bare; v3.0 payload is the ASCII `end_mls`, v4.0 a serialised `EndMlsMetadata` (`end_mls_payload`) |
| `0xF003` / `0xF005` | icon key / subject key | Welcome only | `opaque<V>` |
| `0xF004` / `0xF006` | icon / subject commitment | GroupInfo and Welcome | `opaque<V>`; a bare digest (32, 48 or 64 bytes) is accepted on decode |
| `0xF007` | group metadata keys requested | GroupContext | written bare under v3.0 (29 bytes), `opaque<V>` under v4.0 (30 bytes); decode accepts those and an empty payload |
| `0xF010` | continuity token | Welcome only | `opaque<V>`; a bare 32-byte token is accepted on decode |
| `0xF011` | continuity token commitment | GroupInfo | `opaque<V>`, 33 bytes; never accepted bare |
| `0xE000` | testing expiry override | — | advertised only |

`ext_encode` / `ext_decode` are the only framing code. Bare types are a closed list; every other
type is varint-framed, which is the safe default for a new code point. `0xF007` is the one type
whose framing depends on the revision: other clients write it three ways (framed, bare, empty) with
no in-band discriminator, nothing gates on its payload (presence is the flag), and the encoder picks
by the announced revision. Its code point is itself configurable (`debug.rcs.mls_metadata_keys_ext`
on a debug build, `rcc16::set_metadata_keys_ext`, 0 clears it), and the advertisement and the
framing rule follow that value. `0xF011` is a GroupInfo extension, not a GroupContext one, despite
its RCC.16 §7.11.12.2 heading: a GroupContext extension committing to its own epoch's authenticator
is unsatisfiable, since the authenticator is derived from that GroupContext. Extensions a peer
originated are mirrored verbatim into our GroupContext; only extensions we originate are framed
canonically, because re-framing a peer's bytes diverges the GroupContext. The framing is not
cosmetic: a 32-byte commitment written bare differs from the framed 33 bytes a peer writes, which
changes the GroupContext and makes the peer reject the group. `WELCOME_ONLY_EXTS` (`0xF003`,
`0xF005`, `0xF010`) are the secrets that must never appear in a GroupInfo sent to the server.

The proposal registry shares the `0xF0xx` numbering but is a separate namespace (`0xF002` is
`end_mls` as an extension and `rcs_signature` as a proposal). `SELF_REMOVE_PROP`,
`RCS_SIGNATURE_PROP` and `SERVER_REMOVE_PROP` are derived from mls-rs's `ProposalType` constants,
so a code point that moved upstream or a disabled feature breaks the build instead of the wire.

**Advertisement.** `advertised_extensions` and `advertised_proposals` build the KeyPackage
capability lists from what is implemented (`IMPLEMENTED_EXTENSIONS`, `IMPLEMENTED_PROPOSALS`) plus a
named advertised-only set, plus any extras a transport passes. Under v3.0 the vendor proposal band
is `0xF010..=0xF018`; under v4.0 the `end_mls` proposal (withdrawn in v4.0) is dropped and the
`reserved_for_future_use` band `0xF012..=0xF030` is advertised instead. The two bands are not
advertised together. An advertised extension that is not implemented costs nothing at receive time,
but an advertised proposal that is not implemented blocks application messages while it sits
uncommitted in the proposal cache, and proposal support is an all-leaves intersection, so
advertising more than every other member does gains nothing. The v3.0 band matches what other
clients advertise. Because they stop at `0xF018`, the v4.0 band's intersection with theirs loses
`0xF010` and `0xF011`; the peers' advertised bands need re-checking before the revision flips.

### `AuthenticatedData`

The engine builds and parses the RCC.16 `AuthenticatedData` of application messages
(`build_authenticated_data`, `parse_authenticated_data`):

```
uint16  version = 1
opaque  message_id<V>        (MLS varint length)
uint32  era                  (big-endian)
...     resent component     (0x00 when absent, §10.3)
```

`aad_for` builds it for every encrypt and commit: the host supplies only the message id (the
`message_id` argument of the operation) and, for a resend, the resent component
(`rcs_mls_set_next_resent_component`, one-shot); the era is read from the group's own `0xF001`,
never from the caller, and a group that cannot be loaded gets era 1. The AAD travels in the message,
so receivers read it rather than recompute it. The era field is not in the RCC.16 text, but other
clients emit it and it tracks the era rather than the epoch; dropping it makes peers fail to
decrypt. See [rcc16-map.md](rcc16-map.md#authenticateddata-and-receipt-metadata-as-deployed).

On decrypt the engine compares the AAD's message id with `EXPECTED_MESSAGE_ID` when one is armed and
records `MessageIdMismatch`; an AAD that does not parse, including an empty one, passes rather than
being reported as a mismatch. It takes any AAD version and compares raw bytes. Only
`MlsEngineIdCheck.process` sets the expected id (above).

## Storage

Both stores write under the session's storage directory,
`<filesDir>/openmls_store/<identity>/`, and create it only on write, so a read never has a
filesystem side effect.

### Group state: one group, one file, one rename

`GroupStateStorage::write` must make a group's state and the epoch records it references visible
together. Written as separate files, a crash between renames can publish a state that refers to
epoch records that do not exist, and mls-rs cannot repair that: the group loads and then fails per
message. So each group is one file, `g_<hex group id>.bin`, written to `.tmp` and renamed:

```
"RCSG"                       magic (also tags an exported snapshot)
u8     version = 2           (1 = no per-epoch timestamp; read, never written)
u32    state length, state bytes
u32    epoch record count
repeat:
  u64  epoch id
  u64  stored-at, ms since the UNIX epoch (0 = unknown)   (version 2 only)
  u32  length, epoch secret bytes
```

All integers are big-endian. Decoding is strict: a truncated or unknown record is an error, never
an empty group, because an empty group reads as "never joined" and would send the caller down the
create path for a conversation it is already in. The first write of a version-1 record rewrites it
as version 2.

Rules the writer enforces:

* **An update that matches no existing epoch record aborts the whole write**, checked before
  anything is applied. An update names a record the engine believes exists; a miss means the
  engine's model of storage and storage have diverged, and completing the write would persist a
  state built on that belief.
* **An update does not re-stamp.** The stamp records when the epoch became a previous epoch, which
  is what the retention clock measures; re-stamping would extend retention for every epoch that
  keeps receiving late traffic.
* **The KeyPackage half needs no transaction.** mls-rs writes the group state before deleting the
  consumed KeyPackage, so a crash between them leaves a durable group and an unused secret.

Retention of prior-epoch secrets (`GroupRecord::trim_at`) applies two bounds, age first:

| bound | value | source |
|---|---|---|
| age | 3 days under v3.0, 30 days under v4.0 (`epoch_retention_ms`) | RCC.16 §6.1.1 |
| count | 64 epochs (`EPOCH_RETENTION`) | per-write cost |

Age first, because the count cap keeps the newest ids and running it first could evict a young
epoch to keep an old one about to age out. The count exists because the whole record is rewritten
on every write, and `write_to_storage` runs for every application message encrypted and every
message processed, so the epoch set is a per-message write multiplier. 64 holds about three days of
a busy group plus a peer some 40 epochs behind; at roughly 840 bytes per retained epoch in a
four-member group, a full record is about 56 KB. A larger cap (v4.0's 30 days) first needs the
epoch archive taken off the per-message write path (a message within an epoch inserts or updates no
epoch) and must come together with the v4.0 deletion ceiling. An epoch with an unknown stamp (from a
version-1 record or the legacy layout) is never evicted by age, only by count, so a dormant group
keeps such epochs indefinitely; meeting the v4.0 ceiling would need them stamped at first load. The
age comparison saturates, so a device clock that moves backwards keeps an epoch longer rather than
deleting it early.

**The age pass never lowers the archive's highest id.** mls-rs archives the epoch it leaves only
when the stored maximum is one below it, and refuses anything else as `InvalidEpoch`. An undated
record below an aged stamped one (a v1 record written before stamps existed), or two records stamped
across a backwards clock jump, would otherwise leave an older record as the maximum, and every later
commit on the group, ours and the peers', fails. So when the highest record ages out, the rest goes
with it. A gap below a surviving maximum is harmless and kept.

Records already broken this way are repaired where they fail. `load_for_apply` loads the group for
every commit and every inbound message and first calls `heal_contiguity(gid, epoch)`: records at or
above the current epoch are dropped, then the whole archive unless its maximum is `epoch - 1`. It
writes only when it drops something and logs `dropped N discontinuous archived epoch(s)`. It never
touches the group state; the dropped secrets would only decrypt late messages from those epochs.
`every_apply_site_loads_through_the_heal` pins the sites by name, so a new one fails the test.

Prior epochs do not survive an era change: a new era is a new group at the same group id with a
restarted epoch id, so old entries would shadow live ones. Two sites clear them:
`create_group_carry` (the advancer) and `purge_prior_epochs_on_join` (every other member, on every
join path).

**Why a join purges the archive.** mls-rs archives the epoch it is leaving on each commit and
refuses a non-contiguous epoch id. A member removed and re-added within one era keeps the same MLS
group id and therefore its old archive; after re-joining by Welcome at epoch N with an archive
ending at M < N-1, its next commit is refused as `InvalidEpoch` at its own epoch. With an empty
archive mls-rs skips the contiguity check, as for a first-time joiner. The purged secrets belong to
the branch the member has left and are unusable after the re-join.

Other entry points:

| function | behaviour |
|---|---|
| `export_state` / `restore_state` | snapshot the whole record (tagged with the magic) and restore it. A tagged snapshot replaces the record (a snapshot that does not decode restores nothing); a bare state blob without the magic replaces only the state and keeps the stored epochs. Both forms then drop epoch records above `keep_max_epoch` and go through `store`, which also applies the retention trim. |
| `delete_group` | removes the record and any legacy files |
| `purge_epochs` | drops the prior-epoch archive, keeps the state |
| `heal_contiguity` | drops archive records mls-rs could not extend at the given epoch (above) |
| `prune_expired_epochs` | applies the age bound without an engine write; intended for the v4.0 deletion ceiling and not called yet |

The legacy layout (`s_<gid>.bin` for state, `e_<gid>_<epoch>.bin` per epoch) is read when no
combined record exists and removed after the next combined write is durable, so an interrupted
migration loses nothing.

### KeyPackage secrets

`FileKeyPackageStorage` persists the private half (`init_key`, leaf key) of every KeyPackage we
generate, as `kp_<hex ref>.bin`. Without it the secrets would live only in the session that
generated the package, and a later session could not open a Welcome sealed to it
(`WelcomeKeyPackageNotFound`). mls-rs deletes an entry when a join consumes it (KeyPackages are
single-use).

Deletion leaves a tombstone, `kp_<hex ref>.dead`, holding `<trusted ms>,<device ms>`. An expired
package may still be named by a Welcome in flight, so the fact of its removal is kept even though
its secret is gone; `delete_expired` hard-deletes tombstones 14 days old by the trusted clock. The
device clock is recorded for diagnosis only and never compared. Deleting an already tombstoned
package keeps the original stamp, so the 14 days count from the first deletion. A lookup of a
tombstoned package still returns not-found, and logs that the package was consumed.

### KeyPackage wire form

The key directory stores and serves the raw RFC 9420 `KeyPackage` struct, not the `MLSMessage`
wrapping that mls-rs serialises. A claimed package is re-wrapped with the 4-byte prefix
`00 01 00 05` (version `mls10`, wire format `mls_key_package`) before `add_member`; an input that
is already wrapped is accepted.

`kp_inspect` reads a claimed package without importing it: the last-resort flag (`u8`), the leaf
`Lifetime.not_after` (`u64` BE), the package's cipher suite (`u16` BE), a count and list of the
suites the leaf advertises, the leaf certificate's `notBefore` and `notAfter` (`u64` BE each, 0 when
unreadable), and last the SAN's `tel:` number as bare E.164 ASCII. The 30-day floor is applied to
the certificate window, which is the clock the key directory measures, not to the leaf lifetime.
See [credentials.md](credentials.md).

## Credential validation: `Rcc16Validator`

`Rcc16Validator` implements mls-rs's `X509CredentialValidator` and runs, for every credential the
engine validates (joins, adds, commits):

1. standard chain validation to a trusted root (signatures, path, expiry), by mls-rs's
   `X509Validator`;
2. the RCC.16 A.4.1 leaf profile (`validate_leaf_rcc16`): extended key usage exactly
   `id-kp-rcsMlsClient` (2.23.146.2.1.3), key usage with `digitalSignature`, a `tel:` URI in the SAN
   (when an MSISDN is expected, some `tel:` URI must equal it; otherwise any well-formed one
   passes), the lifetime floors when `strict`, and the `.4` ParticipantInformation extension
   (2.23.146.2.1.4), which must be critical and whose proof-of-possession must verify when
   `pop_strict`. The 30-day floor uses the timestamp mls-rs passes; where it passes none, the
   trusted system clock, and it fails closed when no trusted clock exists;
3. revocation: every certificate's serial, leaf included, against the host-supplied revoked-serial
   list, compared as integer values with leading zeroes stripped;
4. the RCC.16 A.2 CA profile on every issuer in the chain (`validate_ca_rcc16`), so an end-entity
   certificate cannot act as an issuer: `BasicConstraints` critical with cA true, `KeyUsage`
   critical with `keyCertSign`, and a validity of at most 3652 days for a self-signed root (A.1.5)
   or 1827 days for an intermediate (A.2.5). An EKU on a CA is tolerated when it is exactly
   `id-kp-rcsMlsClient`: A.2.8 lists none, but deployed CAs carry one;
5. the leaf's `vendorId` (the first element of `.4`) must equal the root's `2.23.146.2.1.6`
   extension (RCC.16 §14.4), so one vendor's CA cannot mint leaves claiming another; skipped when
   either side carries none, as our test CAs do.

The profile also restricts signature algorithms to ECDSA with SHA-256/384/512, keys to
`id-ecPublicKey` on P-256, P-384 or P-521, and extensions to the set the profile inspects. The RSA
restriction is imposed explicitly because the underlying path builder accepts RSA.
`validate_x509_profile` requires a subject key identifier on every certificate, and
`certificatePolicies` (exactly one E2EE policy, 2.23.146.2.1.2, non-critical, no qualifiers, no
`anyPolicy` on issuers) only on leaves, since deployed intermediates lack it. An extension outside
the allow-list is rejected under `strict` and logged otherwise. The leaf subject must carry a
commonName or a `clientIdentifier`; the `clientIdentifier` attribute OID is not known, and real
leaves carry the client UUID in commonName, which satisfies the rule. Chain length is checked as the
leaf plus at least one issuer, because the chain excludes the trust anchor.

Three checks sit outside both levers (`strict` and `pop_strict`) on purpose, so no runtime setting
disarms them: the `.4` validity window; the refusal of a `.4` carrying `participantKeyRolls`
(A.4.1.1(1c-ii) requires verifying the roll chain, which is not implemented); and the `.5` proof
expiry. Anything placed inside `verify_participant_pop` is disarmed by `debug.rcs.mls_pop_lenient`,
and a source-scan test pins where the `.5` check sits.

* **The `.5` proof** (`uint64 BE expiry || MLS varint length || DER ECDSA-Sig-Value`, the
  configuration server's signed encryption-identity proof) is decoded and its expiry logged, not
  enforced. Enforcement is the compile-time constant `ACS_PROOF_EXPIRY_ENFORCED`, off, rather than
  a system property, so no runtime lever can disarm it once it is on. A missing `.5` is not an error
  (A.3.8.10 says it shall be present, but our own leaves carry none) and is logged once per process.
  Its signature is not verified: no anchor for the configuration server's key is available.
* **The `.4` proof-of-possession hash** comes from `.4[2]`. SHA-224, -256, -384 and -512 are all
  accepted, a superset of the SHA-256/384 other clients accept; SHA-224 is the weakest of them.

The revoked-serial list, passed on every session start and normally present and empty, is the only
revocation input. RCC.16 leaves are deliberately non-revocable, so revoking an intermediate is the
only lever against a compromised issuer. The engine links no network stack and does not fetch CRLs
or OCSP.

## Certificate encoding and the self-test PKI

`rcc16_build.rs` encodes the client's side of certificate issuance: subject and SAN names, validity,
the TBS, and the `.4` ParticipantInformation. It composes the `.4` structure with the same `der_seq`
the validator uses to reconstruct it for the proof-of-possession check, so a layout error breaks
both directions at once instead of producing an issuer and a verifier that each agree with
themselves. Private-key operations stay in Java: these functions return bytes to be signed, Java
signs them with JCE, and the key never crosses the FFI boundary (so it may be KeyStore-backed).
A null result means the encoder refused and is a hard failure, never "empty". Java callers go
through `Rcc16Der`, used by `MlsCredential`.

`rcc16_mint.rs` mints a throwaway root, intermediate and leaf for the self-test APK
(`MlsSelfTestReceiver`), so the engine can be exercised on one device without a network. It
deliberately never emits `2.23.146.2.1.5` (the key directory's signed encryption-identity proof),
which a client cannot sign. `rcs_mls_rcc16_corrupt_pop` produces the negative fixture.

## Logging

Rust lines go to logcat at error priority under the tag `RcsMls`, prefixed with `[module:line]`
(the `alog!` macro). Off Android the macro compiles to nothing. Log lines carry lengths, types and
identifiers, never secret values; the library cannot tell a debug build, so an MSISDN is always
written through `masked_msisdn` (the last four digits).

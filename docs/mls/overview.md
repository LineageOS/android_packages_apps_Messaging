<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# MLS: overview

End-to-end encryption of RCS conversations uses MLS (RFC 9420) with the GSMA RCC.16 profile on
top of it. This page describes who owns what, how the code is laid out, and how a Java call
reaches the native protocol core. The other pages in this directory go deeper:

| page | covers |
|---|---|
| [transport-and-port.md](transport-and-port.md) | `MlsProviderTransport`, `MlsShellPort`, the delegates, the lock model, sealing and receiving |
| [group-lifecycle.md](group-lifecycle.md) | establish, join, add, remove, leave, era advance |
| [health-and-recovery.md](health-and-recovery.md) | health states, self-heal, the RCC.16 §10 failure ladder |
| [downgrade.md](downgrade.md) | `end_mls`, revival, re-upgrade |
| [credentials.md](credentials.md) | certificates, the 30-day floor, identity refresh |
| [metadata.md](metadata.md) | encrypted group subject and icon |
| [budgets.md](budgets.md) | fetch and claim ledgers, era-advance budget |
| [rcc16-map.md](rcc16-map.md) | RCC.16 section to class/method |
| [rust-core.md](rust-core.md) | the FFI surface, storage, RCC.16 validation in Rust |

For the app/provider split in general see [../rcs/architecture.md](../rcs/architecture.md), and
for the AIDL surface see [../rcs/provider-contract.md](../rcs/provider-contract.md).

## Ownership: the app encrypts, the provider carries

The app owns every piece of MLS state: the engine session, the group state and epoch secrets,
the per-conversation record, the KeyPackage secrets, and the framing of every MLS message
(the RCC.16 `AuthenticatedData`, the body counter, the CPIM wrapping). The RCS provider carries
opaque envelopes and performs the server-side RPCs the app asks for (create a conversation,
apply a commit, claim a peer's KeyPackages, fetch a GroupInfo). It never parses MLS.

Why the line sits there:

* **Nothing below the app can read a message.** The provider handles ciphertext only, so a
  compromised or replaced provider learns nothing about content.
* **One copy of each wire rule.** The two details that must match the ratchet exactly — the
  `AuthenticatedData` and the RCC.16 body counter, which must equal the generation the message is
  sealed at — are built next to the engine that produces that generation (`MlsAppMessage`,
  `MlsRecoveryPolicy`). There is no second copy on the far side of the process boundary to drift.
* **A conversation survives a provider change.** Group state lives with the app, so a different
  provider build (or a different provider) can carry the same groups.

Two transports implement the same `E2eeConversationTransport` contract over the same engine:

| transport | carries MLS over | KeyPackage lifetime, peer-certificate policy |
|---|---|---|
| `MlsProviderTransport` | the RCS provider's AIDL interface | `FIXED_365_DAYS`, `DEPLOYMENT_TOLERANT` |
| `MlsCarrierTransport` | the app's own carrier SIP/MSRP session, as `message/mls` CPIM bodies ([../rcs/carrier-transport.md](../rcs/carrier-transport.md)) | `WITHIN_CERTIFICATE`, `RCC16_STRICT` |

They are separate classes rather than one class with a switch because they differ in capability,
not just in addressing. The carrier path is peer-to-peer with no focus; the provider path has a
server that arbitrates the era, acknowledges convergence and may refuse member external commits.
The provider declares which of these apply through `RcsMlsTransportProfile`
(`serverArbitratesEra`, `requiresConvergenceAck`, `acceptsMemberExternalCommit`,
`hasServerGroupInfo`), and recovery decisions read that profile instead of branching on a
transport name.

## Module layout

| module (Android.bp) | contents | depends on |
|---|---|---|
| `messaging-mls-engine` (`java_library`) | everything under `engine/src/com/android/messaging/rcs/engine/mls` | no Android app code, no BouncyCastle |
| `messaging-mls-policy-host` (`java_library_host`) | a pure-Java subset of the engine (recovery policy, era election, RCC.16 spec crypto) compiled for the host | nothing |
| `librcs_mls_ffi` (`rust_ffi_static`) | the Rust MLS core, `rust/rcs_mls_ffi` | the mls-rs crates in `external/mls-rs` |
| `libmlsopenmlsbridge` (`cc_library_shared`) | the JNI bridge, `jni/mls_openmls_bridge.c`, whole-static-linking `librcs_mls_ffi` | `liblog` |
| app code, `src/com/android/messaging/rcs/e2ee` | the transports, the Android bindings of the engine's ports, the app stores | the engine library |

The engine is a separate library, not app code, so that another RCS provider or a self-test APK
can link the same classes instead of carrying a second implementation of one protocol contract.
Its `visibility` lists the repository and its subpackages (the self-test APK under `selftest/`).
It holds transport-neutral MLS only; the carrier and KDS glue that needs BouncyCastle
(`MlsCredential`, `RcsKdsClient`, `MlsTrustAnchors`) stays in `com.android.messaging.rcs.e2ee`.

The app APK packages `libmlsopenmlsbridge` inside the APK (`use_embedded_native_libs`) so an
`adb install` update loads the same `.so` as a system-image build. The library is built for arm64
only.

### The package name is load-bearing

The JNI symbols exported by `libmlsopenmlsbridge` are derived from the Java package:

```
Java_com_android_messaging_rcs_engine_mls_OpenMlsNative_<method>
```

The bridge spells this once, as the `JNI_FN(name)` macro. Renaming the package or `OpenMlsNative`
means changing that macro in the same change; one without the other links and then fails at the
first native call with `UnsatisfiedLinkError`.

### Inside the engine package

The engine package holds about 170 classes. They fall into a few families:

| family | examples | role |
|---|---|---|
| engine interface | `MlsEngine`, `MlsSession`, `OpenMlsEngine`, `OpenMlsSession`, `OpenMlsNative` | the Java face of the native core |
| ports | `MlsPorts`, `MlsShellPort`, `MlsProviderRpc`, `MlsPrefs`, `MlsSysProps`, `MlsLogSink`, `MlsTelemetry`, `MlsOffThread`, `*Access` interfaces | what engine code may reach, as interfaces |
| lifecycle flows | `MlsOneToOneGroup`, `MlsGroupEstablish`, `MlsMembership`, `MlsWelcomeAdmission`, `MlsEraAdvance`, `MlsConversationRebuild` | the transport's decisions, driven through `MlsShellPort` |
| send and receive | `MlsSealSend`, `MlsGroupSend`, `MlsCommitSend`, `MlsCommitApplication`, `MlsInboundDecrypt`, `MlsInboundHold`, `MlsResend` | the message planes |
| state and records | `MlsConversationRecord`, `MlsRecordState`, `MlsGroupState`, `MlsTransportTypes`, `MlsPendingQueue`, `MlsPendingOperation` | persisted and in-memory per-conversation state |
| policy | `MlsRecoveryPolicy`, `MlsAdvancerElection`, `MlsHealthMachine`, `MlsDowngradeLadder`, `MlsKeyPackagePolicy` | pure decisions, host-tested |
| budgets | `MlsFetchLedger`, `MlsClaimLedger`, `MlsEraAdvanceCharge`, `MlsRebuildLimits` | rate limits on peer-facing costs ([budgets.md](budgets.md)) |
| RCC.16 constructions | `RccCommitment`, `RccFileCrypto`, `RccFileInfo`, `VerifiableDerivedContent`, `RccMlsBody`, `Rcc16Version` | byte-level spec encodings |

## The engine boundary: `MlsPorts`

`MlsEngine.startSession(storageDir, identity, ports)` takes no `Context`. A `Context` is
unbounded ambient authority, network included, and nothing in a signature that takes one says what
it is used for. In its place the engine gets a storage directory and an `MlsPorts` bundle with six
capabilities, none of which can reach the network:

| port | capability |
|---|---|
| `GroupStateStore` | group state and epoch records, written as one batch |
| `PendingMessageStore` | an opaque key/value store the engine owns the encoding of |
| `KeyPackageStore` | KeyPackage secrets keyed by KeyPackageRef |
| `MessageAccessor` | read-only access to message rows |
| `MlsClock` | the one clock source |
| `MlsTelemetry` | counters |

`MlsPorts` is `final` with `final` fields, so adding a capability means editing that file. The
native engine does not call through the three store ports: storage is engine-owned
(`MlsPorts.withEngineOwnedStorage`) and lives in files under the storage directory, where each
engine call is one atomic storage unit ([rust-core.md](rust-core.md#storage)). The unrouted store
ports refuse at runtime rather than report a write that did not land. Telemetry is the only port
that may be absent (it defaults to `MlsTelemetry.NONE`), because a missing metrics plane must never
fail an operation. The default clock is the device wall clock and reports itself untrusted
(`isTrusted()` false), so every deadline can log which clock it ran on.

Store reads return a three-valued `StoreRead`: `Ok` with a non-null value (a zero-length array is
present, not absent), `NotFound` (a normal answer the caller judges), or `Err` (the reason is for
logging, never for control flow). `NotFound` is never turned into an `Ok` from another row.
`StoreRead.Required` is the two-valued form for reads the host may not answer with "none", such as
`MessageAccessor.generateMessageId`. The store
interfaces fix the shape of what the engine may ask for; `GroupStateStore.write` states the rule the
native store also enforces, that an epoch-record update matching no existing row aborts the whole
write.

In the app, `MlsHostPorts.forApp(ctx)` builds the ports, `MlsHostPorts.storageRoot(ctx)` is the
app's files directory, and `MlsHostPorts.logSink()` is the `MlsLogSink` that forwards to
`LogUtil`.

## From a Java call to mls-rs

```
MlsProviderTransport / MlsCarrierTransport
        │  MlsSession (interface)
        ▼
OpenMlsSession ── holds the native handle (a long)
        │  static native methods
        ▼
OpenMlsNative ── System.loadLibrary("mlsopenmlsbridge")
        │  JNI, Java_com_android_messaging_rcs_engine_mls_OpenMlsNative_*
        ▼
jni/mls_openmls_bridge.c ── byte[] <-> (pointer, length), frees returned buffers
        │  C ABI, rcs_mls_*
        ▼
rust/rcs_mls_ffi (ffi.rs: ProdSession) ── mls-rs, RCC.16 rules and validation, file storage
```

`OpenMlsEngine.startSession` checks the identity's host-side preconditions before the native call
(`MlsMetrics.preflightCreateClient`: a missing intermediate, trust anchor or device key pair is
reported as its own failure rather than as a generic native failure), selects the engine settings
and the RCC.16 revision, and opens the session under
`<storageDir>/openmls_store/<sanitised E.164>`. One native session serves every group of one
identity.

### Engine settings and spec revision

All three are process-wide settings in the native core, set explicitly on every session start so a
previous session in the same process cannot decide them. One process runs both transports, so
`OpenMlsEngine.startSession` holds a class-wide lock from setting them until the native session is
built; they are read only while a session is built.

| setting | Java | values | what it changes |
|---|---|---|---|
| KeyPackage lifetime | `OpenMlsEngine.KeyPackageLifetime` | `WITHIN_CERTIFICATE` (RCC.16 §5.1), `FIXED_365_DAYS` (Google's KDS) | the LeafNode lifetime of minted KeyPackages ([rust-core.md](rust-core.md#engine-settings-and-revision)) |
| peer-certificate policy | `OpenMlsEngine.PeerCertificatePolicy` | `RCC16_STRICT`, `DEPLOYMENT_TOLERANT` | whether the A.4.1 lifetime floors and some §14.2.3 rules reject a peer's certificate or are only logged |
| RCC.16 revision | `Rcc16Version` | `V3_0` (default), `V4_0` | wire shapes that differ between revisions (`end_mls` payload, continuity extensions, the advertised proposal band) |

`OpenMlsEngine` has no no-arg constructor, so every caller names both settings: each lifetime rule's
KeyPackage is rejected by the other deployment's key directory, so there is no safe default. A
`null` setting passed explicitly is taken as the RCC.16 one (`WITHIN_CERTIFICATE`,
`RCC16_STRICT`), which is also the native core's value before any session start. The
revision does have a default, `V3_0`, because it is the revision the deployed peers speak.
`MlsProviderTransport` passes `Rcc16Version.fromWire(mCfg.rcc16Version)`; the native side refuses an
unrecognised value and keeps its current one, and `startSession` logs an error when the revision in
effect differs from the one requested. The revision is announced by the transport, not inferred from
anything a peer sends.

### Result conventions across the boundary

* A `byte[]` return of `null` means "no bytes". An empty result is also `null`: the bridge maps a
  zero-length native buffer to `null`.
* What `null` meant is read from the status channel immediately afterwards, on the same thread:
  `MlsSession.lastStatus()` returns `OK`, `NO_OP`, `NOT_FOUND` or `ERR`. `NO_OP` means the
  operation correctly had nothing to do (a drive loop stops), `NOT_FOUND` means the group is not in
  local storage (a join or recovery situation, not a retry), `ERR` is a real failure.
  `OpStatus.fromCode` throws on an unknown code rather than guessing.
* Group operations return an artifact bundle decoded by `MlsArtifactBundle`
  ([rust-core.md](rust-core.md#the-artifact-bundle)).
* Calls that can produce several results return a list, each result stamped with the context id of
  the operation it belongs to (`MlsEngineResult`, `MlsResultBundle`). An encrypt can return a
  piggybacked commit beside the ciphertext. Results for another context must still be processed for
  their effects but are not returned to the caller. `demux`, `forContext` and `soleGroup` throw
  rather than return empty, because an empty result set and a discarded one look the same
  downstream; their messages match other clients' ("No results returned from handleResults...",
  "Mls context id is not found in the result map...", "Multiple MLS groups found in the results")
  so logs compare.
* Multi-valued inputs (certificate chains, trust anchors, KeyPackage lists) are a concatenation of
  `[u32 big-endian length][bytes]` records, on both sides.

## Routing inbound MLS bodies

`MlsContentRoute` is the one router for every transport, and it decides in two layers.
`isMlsContentType` says whether a body is on the MLS plane at all (`false` sends it down the
plaintext chain); `of` then routes it, and throws on a type with no arm, because a silent ignore at
the dispatcher would hide a routing regression. No type accepted by the first layer may throw at the
second. Routing strips parameters and ignores case (RFC 2045).

| content type | route |
|---|---|
| `message/mls`, `message/mls-ft`, `message/mls-rcs-file-info` | `RAW`: an encrypted application payload for the engine |
| `message/mls-rcs-server` | `SERVER` |
| `message/mls-rcs-client` | `CONTROL`: a peer-originated Welcome, commit or proposal |
| `message/mls-rcs-server-kick` | `REJECTED`: host-internal and never a dispatcher input, yet in the accepted set, so it is dropped |

## Message ids

Transport message ids minted by the app (`MlsMessageId`) are `"Mx"` followed by the 16 bytes of a
UUID in unpadded URL-safe Base64, with `_` mapped to `=`. Peers validate ids against
`^Mx(.){22,26}`, and an id they reject is a message that never arrives.

## Logging

Every MLS log line, from Java, the JNI bridge and Rust, uses the tag `RcsMls` (`MlsLog.TAG`, the
bridge's `TAG`, and `LOG_TAG` in `ffi.rs`); they are kept equal so one `logcat -s RcsMls` shows an
operation end to end. Rust lines carry `[module:line]` of their origin.

## Testing

The engine is host-testable because it takes ports rather than a `Context`. The host suites are
`messaging-mls-engine-host-tests` (under `tests/src/com/android/messaging/rcs/engine`) and the
Rust unit tests (`cargo test` in `rust/rcs_mls_ffi`, fixtures in `rust/rcs_mls_ffi/testdata`,
re-mintable with `regen-chain.py`). See [../testing.md](../testing.md).

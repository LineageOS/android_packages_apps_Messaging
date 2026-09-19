<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# The RCS provider contract

`aidl/` holds the interface between this app and an RCS provider app: `IRcsProvider`, which the app
calls, `IRcsProviderCallback`, which the provider calls, and the parcelables both carry. Nothing in
AOSP or LineageOS implements `IRcsProvider`. The contract exists so a provider can be supplied
separately without either side depending on the other's internals; with no provider installed the
app falls back to the carrier transport and then to SMS/MMS. How the app uses the contract is in
[architecture.md](architecture.md).

## Module

`messaging-rcs-contract-aidl` is a `java_library` containing the two interface stubs (generated
from `src/aidl/**/I*.aidl`) and hand-written `Parcelable` classes under `src/java`. The parcelable
`.aidl` declarations are not compiled; they only let the interfaces resolve their imports, and they
are exported through `export_include_dirs` so a consuming module can write its own AIDL against
them.

It is built with `sdk_version: "current"` (not `system_current`) and `min_sdk_version: "30"`.
`current` is a subset of both, so a provider built against the public SDK and this platform-API app
can link the same library. Both sides `static_libs` it; neither loads the other's copy.

No protocol buffers and no provider-specific types cross the boundary. Everything is an AIDL
primitive, a `String`, a `byte[]`, a `List<String>`, a `ParcelFileDescriptor`, or one of the
parcelables below.

## Access

| Item | Value |
|---|---|
| Bind action | `org.lineageos.rcs.provider.action.BIND_RCS_PROVIDER` |
| Permission the app holds | `org.lineageos.rcs.permission.BIND_RCS_PROVIDER` |
| Discovery | `PackageManager.queryIntentServices(..., MATCH_SYSTEM_ONLY)`: only providers on the system image |
| Wake broadcast (provider to app) | `org.lineageos.rcs.provider.action.WAKE_FOR_INBOUND`, received by `RcsProviderWakeReceiver`, which requires the same permission from the sender |
| Status broadcast action | `org.lineageos.rcs.provider.action.TRANSPORT_STATUS` (`RcsConstants`) |

Some methods return key material (`exportMlsIdentity`), so the permission gating the provider's
service is part of the contract's security, not a convenience.

## Session

| Call | Contract |
|---|---|
| `getContractVersion()` | The provider's contract revision. A label for log lines; never the compatibility check (see below). |
| `attach(callback, desiredContractVersion)` | Registers the callback sink and returns an opaque client token, or null when the provider refuses the pairing. From `CONTRACT_CONFIRMS_STORED` (3) the version also declares that the app confirms what it stores; see [Delivery confirmation](#delivery-confirmation). |
| `detach(token)` | Releases the session. Idempotent. |
| `startForSub(token, RcsSubInfo)` | Begin or resume provisioning and registration. Asynchronous; progress arrives as `onProvisioningStateChanged` and `onRegistrationStateChanged`. |
| `stopForSub(token, subId)` | Tear down. Idempotent. |
| `canServeSub(RcsSubInfo)` | Fast, side-effect free pre-flight. `LINE_MAYBE` means only `startForSub` can tell, for example when eligibility depends on live modem state. |
| `getProviderCaps()` / `getCapabilitiesForSub(subId)` | Static caps, and the per-subscription caps (null when the subscription is unknown or not started). |

Every call after `attach` carries the client token, which lets the provider scope state per bound
client. `RcsSubInfo.msisdn` and `imsi` may be null; a provider that needs them reads them itself
with its own privileges.

## Ordering: transaction codes are positional

The aidl compiler numbers each method `FIRST_CALL_TRANSACTION + n` in declaration order, and that
number is the wire ABI. Inserting or reordering a method re-points every code after it; a caller
built against the old order does not fail, it lands on a different method with a compatible-looking
parcel. Therefore:

* New methods are appended at the end of the interface. Never inserted, never reordered.
* A changed parameter list is a new method, appended. `sendMlsGroupImdn` exists beside
  `sendMlsImdn` for this reason: widening `sendMlsImdn` would leave an older caller marshalling a
  shorter parcel into a reader expecting a longer one.
* The one exception: a one-way callback may gain a trailing `@nullable` parameter in place, as
  `onMessageStatus` gained `e2eeSchemeId` and four callbacks gained `confirmId`. The generated
  reader takes a missing trailing argument as null and does not check for an extra one, a one-way
  call returns nothing, and the parameter is defined so that null is the conservative answer; an
  older peer on either side therefore under-claims rather than misreads. Ordinal and name are
  unchanged, so the layout digest is too. A synchronous method, or a parameter whose null is not the
  safe answer, still needs a new method.
* The MLS methods form one block at the end of each interface; new MLS methods go at the end of that
  block. A new method that is not MLS goes before the block, as `onIncomingFileUnavailable` did,
  which moves the block's ordinals: the layout probe then refuses an older peer in both
  directions, so both sides are updated together.
* The rule applies to both interfaces, each in its own direction: the app dials `IRcsProvider`, the
  provider dials `IRcsProviderCallback`, and a skew in the callback mis-delivers inbound messages,
  receipts and MLS control onto the wrong handler.

Appending keeps an older peer working for everything it already knows; it fails only on the call it
does not have.

## Layout verification

A contract version number maintained by hand goes stale, and even a correct "provider >= app" check
assumes appends only. The app therefore compares the two sides' actual layouts at bind time and
refuses the provider on any disagreement. The decision is `RcsContractLayout`, pure JDK and
host-tested; the plumbing is `RcsContractProbe`.

**Derivation.** `RcsContractProbe.deriveLocalLayout()` and `deriveCallbackLayout()` read the
`static int TRANSACTION_*` fields of this build's generated `IRcsProvider.Stub` and
`IRcsProviderCallback.Stub` by reflection and return the method names indexed by ordinal. The result
is cached. The derivation returns null, which every caller treats as a refusal, when:

* `RcsContractLayout.FIRST_ORDINAL` (1) differs from `IBinder.FIRST_CALL_TRANSACTION`;
* no `TRANSACTION_*` field is found (stub stripped or renamed);
* an ordinal is out of range, two methods share an ordinal, or an ordinal is missing;
* ordinal 1 is not the anchor (`getContractVersion` for the provider interface,
  `onIncomingMessage` for the callback);
* the count differs from the interface's declared method count, which catches R8 removing only some
  fields.

**Exchange.** The layouts travel on transaction code `IBinder.LAST_CALL_TRANSACTION` (0x00ffffff),
not on an AIDL method: an AIDL method is itself positional and could be renumbered by the change the
probe exists to detect. The parcels:

```
request  = interface token, int PROBE_REV (1), int app contract version,
           String[] app IRcsProvider layout, String[] app IRcsProviderCallback layout
reply    = no exception, int PROBE_REV, int provider contract version,
           String[] provider IRcsProvider layout, String[] provider IRcsProviderCallback layout
```

A provider answers by calling `RcsContractProbe.handleProbe` from its stub's `onTransact` before
delegating to `super`. It returns its own layouts and logs its own verdict on the caller's, so a
skew is visible in both processes' logs. A binder that does not know the code returns false from
`transact`, which the app treats as "cannot verify". A different `PROBE_REV` is also unverifiable.

**Decision.** `RcsContractLayout.compare(iface, anchor, caller, local, ..., callee, remote, ...)`
answers "may the caller dial the callee":

1. A missing local or remote layout: incompatible.
2. Either layout not starting at the anchor: incompatible.
3. The first ordinal where the names differ: incompatible, reported as an ordinal skew with up to
   three "we dial X, it runs Y" examples. Divergence is checked before length because it names the
   insertion.
4. The callee has fewer methods than the caller: incompatible.
5. Otherwise compatible. Extra trailing methods on the callee are fine: the caller never dials them.

`RcsContractProbe.check` runs this for `IRcsProvider` with the app as caller, then for
`IRcsProviderCallback` with the provider as caller. Every path that cannot establish an answer
refuses; a provider too old to answer the probe is refused like a skewed one, because the risk is
the same.

**Digest.** `RcsContractLayout.digest(names)` is the first 12 lowercase hex characters of SHA-256
over one line `"<ordinal>:<name>\n"` per method; `"none"` for an empty layout. It covers names and
order, so any insertion, removal or reorder changes it. `RcsContractProbe.localDigest()` is
`<IRcsProvider digest>/<IRcsProviderCallback digest>`. Both the refusal and the success log line
carry it, so two builds can be checked for pairing by comparing one string from each log.

**Pairing rule.** Install the app and the provider from one source tree. The probe is what enforces
it at runtime.

**Test injection.** `handleProbe(self, data, reply, version, injectSkewAtOrdinal)` makes a provider
report a synthetic method inserted at the given ordinal. It can only make the app refuse, never make
two skewed builds look compatible, so it is safe to ship; it lets the refusal path be exercised on a
device without building a broken provider.

**R8.** `proguard.flags` keeps `org.lineageos.rcs.provider.**` because the app runs R8 with
obfuscation and would otherwise rename the proxy and inline the `TRANSACTION_*` fields.

## Parcelables

Every parcelable is a flat, hand-written `Parcelable` with public final fields. Two rules:

* `writeToParcel` and the parcel constructor must agree field for field; a mismatch is silent.
* A new field is appended after the last one. Fields added this way are nullable or have a neutral
  default, and constructors are added as overloads so older call sites keep compiling. Both sides
  are still expected to come from the same source; `RcsMlsPeerCaps` additionally reads its trailing
  `lookupState` only when `Parcel.dataAvail() > 0`, defaulting to `LOOKUP_UNKNOWN`, and
  `RcsIncomingFile` its trailing `e2eeSchemeId`, defaulting to null.

| Parcelable | Direction | Content |
|---|---|---|
| `RcsSubInfo` | app to provider | `subId`, `slotIndex`, nullable `msisdn`, `imsi`, `mccMnc`, `carrierPolicy` (an advisory hint) |
| `RcsProviderCaps` | provider to app | `providerLabel`, `contractVersion`, `supportedTransports` (transport tags; `TRANSPORT_CARRIER_MSRP` = 2 is the carrier SIP/MSRP transport), `canSelfProvision`, `priority` (higher preferred, 0 unspecified), `featureFlags` |
| `RcsOutgoingMessage` | app to provider | client-minted `messageId`, `toUri`, `contentType`, `body` bytes, `e2eeSchemeId`, `groupId` |
| `RcsSendResult` | provider to app | `accepted`, `reasonCode`, free-text `reason` |
| `RcsIncomingMessage` | provider to app | `messageId`, `fromUri`, `contentType`, `body`, `serverTimestampUsec`, `wantsDeliveredImdn`, `wantsDisplayedImdn`, `groupId`, `e2eeSchemeId` |
| `RcsOutgoingFile` | app to provider | `messageId`, exactly one of `toUri` and `groupId`, `contentUri`, open `fd`, `mimeType`, `fileName`, `size`, `caption` |
| `RcsIncomingFile` | provider to app | resolved `contentUri` and/or `fd`, real `mimeType`, `fileName`, `size`, `caption`, eager thumbnail (`contentUriThumbnail`, `fdThumbnail`, `mimeTypeThumbnail`, `sizeThumbnail`), IMDN flags, `groupId`, `e2eeSchemeId` (the scheme the file was decrypted under; null for plaintext) |
| `RcsGroupInfo` | provider to app | `groupId`, `name`, `conferenceUri`, `members` |
| `RcsTosPrompt` | provider to app | `kind` (`KIND_CARRIER_TOS` = 1, or 2 for the provider service's own terms), `title`, `message`, button flags and labels, `tosUrl` |
| `RcsBotBrand` | provider to app | Business-messaging brand: name, description, colour, logo and hero URLs, verification, contact fields |
| `RcsIncomingBotMessage` | provider to app | `botId`, `contentType`, unparsed `jsonBody`, `fallbackText` |
| `RcsE2eeInfo` | provider to app | `available`, `enabled`, opaque `schemeId`, human `schemeLabel` |
| `RcsMlsPeerCaps` | provider to app | Parallel `names`/`values` arrays of the peer's advertised MLS feature tags, and `lookupState` |
| `RcsMlsControlResult` | provider to app | `verdict`, opaque `response`, diagnostic `detail` |
| `RcsMlsClaimResult` | provider to app | `outcome`, length-prefixed `keyPackages`, diagnostic `detail` |
| `RcsMlsTransportProfile` | provider to app | `serverArbitratesEra`, `requiresConvergenceAck`, `acceptsMemberExternalCommit`, `hasServerGroupInfo` |
| `RcsMlsIdentity` | provider to app | `e164`, `leafDer`, `chainDer`, `subjectPriv`, `subjectPub`, `roots`, `revokedSerials` (revoked intermediate-CA serials; leaf certificates are not revocable, so this is the only revocation lever; empty, or null, is the normal state) |

Notes that constrain implementers:

* **Files cross as descriptors, not bytes.** A binder transaction is limited to about 1 MB and media
  can be far larger. `RcsOutgoingFile` carries an open descriptor that the provider reads and
  closes. `RcsIncomingFile` carries a blob the provider has already downloaded, exposed as a
  `content://` URI with a read grant and/or a descriptor; it never carries a download URL, a
  credential or a transport descriptor, so any transport can produce the same object. `mimeType` is
  always the media's real type. A null `contentUri` with a thumbnail is the preview state before the
  user accepts the download; the second `onIncomingMedia` carries the file. The app uses a non-empty
  `contentUri` as is and closes the redundant descriptor; with no URI it copies the descriptor into
  its own scratch storage. Thumbnails are handled the same way.
* **E2EE schemes are strings, not an enum.** `RcsE2eeInfo.schemeId` is a reverse-DNS identifier
  the implementation chooses; the app's MLS scheme is `gsma.rcs-e2ee.mls`. A new scheme needs no
  contract change. `getE2eeInfo` reports encryption done inside the provider; app-layer MLS is the
  app's own and is composed with it above this struct. `RcsE2eeScheme` holds the scheme ids the app
  recognises and the MLS capability tags it reads from a peer's capability map.
* **Peer capabilities are an open map.** Vendors advertise different subsets of the MLS tags, so
  typed fields would freeze one subset. An empty map means "nothing known", never "not capable".
  `lookupState` is kept out of the map because it is a fact about the lookup, not about the peer:
  `LOOKUP_UNKNOWN` (the lookup failed; says nothing), `LOOKUP_REGISTERED`, `LOOKUP_NOT_REGISTERED`
  (the only value a caller may treat as a negative).
* **Length-prefixed byte lists.** Where a method returns several byte strings in one `byte[]`
  (`claimPeerKeyPackages`, `RcsMlsClaimResult.keyPackages`, `fetchMissedCommits`,
  `getMlsTrustAnchors`, the `onMlsControlBundle` payload) the packing is repeated
  `[u32 big-endian length][bytes]` records, in order.

### The vocabulary firewall

`RcsMlsControlResult.verdict` and `RcsMlsClaimResult.outcome` are spec-level. The provider
translates its backend's statuses into these constants before they cross, so no backend error code
or status string reaches the app, and a different provider (for example a carrier CPM/MSRP one) can
report the same verdicts from a different protocol. `detail` is for logs and may contain backend
text; callers branch on the constant and never parse `detail`.

| `RcsMlsControlResult` | Meaning |
|---|---|
| `VERDICT_OK` (0) | Succeeded. |
| `VERDICT_ERA_GAP` (1) | The group's era diverged from the server's. |
| `VERDICT_EXTERNAL_COMMIT_REFUSED` (2) | An existing member attempted an external commit (RFC 9420 §12.4.3.2 restricts `ExternalInit` to non-members). |
| `VERDICT_GROUP_ID_CHANGED` (3) | A fresh group id was offered for a conversation the server already holds. |
| `VERDICT_NOT_REGISTERED` (4) | Not registered or provisioned right now. |
| `VERDICT_TRANSPORT_FAILED` (5) | No verdict: the request never completed. Retryable; not a rejection. |
| `VERDICT_REJECTED` (6) | Evaluated and refused, with no dedicated verdict. |
| `VERDICT_NOT_IN_GROUP` (7) | The server does not count this line as a member. The local membership is stale; retrying cannot help. |

| `RcsMlsClaimResult` | Meaning |
|---|---|
| `OUTCOME_SERVED` (0) | At least one package returned. Says nothing about whether the packages are usable; only their certificate windows can. |
| `OUTCOME_PEER_HAS_NONE` (1) | The only outcome that is a fact about the peer's pool. |
| `OUTCOME_NOT_AUTHORIZED` (2) | The service refused us; says nothing about the peer. |
| `OUTCOME_REFUSED` (3) | Refused for another stated reason. |
| `OUTCOME_TRANSPORT_FAILED` (4) | No answer. |
| `OUTCOME_NOT_ATTEMPTED` (5) | Nothing was dialled. |

`OUTCOME_PEER_HAS_NONE` is the only outcome that blames the peer, so a provider must reach it only
when the server answered that the pool is empty. A transport status never maps onto it: a
not-found answer is the shape of asking the wrong server, not of an empty pool, and "no server
answered" is `OUTCOME_TRANSPORT_FAILED`. Nothing in this repository can check a provider's
translation; `MlsKeyPackageClaimLedgerGuardTest#theProviderBlameGuardActuallyFails` holds a detector
that a provider's own suite can run against its sources, together with an enumeration of the
provider's own callers of its claimer.

`RcsSendResult.reasonCode`: `REASON_OK` (0), `REASON_NOT_REGISTERED` (1), `REASON_NOT_PROVISIONED`
(2), `REASON_PEER_NOT_RCS` (3, teaches the app's capability cache), `REASON_RATE_LIMITED` (4),
`REASON_INTERNAL_ERROR` (5), `REASON_NOT_IN_GROUP` (6, stale local membership, not an internal
error; always on a send that was not accepted). An unknown non-OK value must be treated as a
failure. Separately, an accepted MLS send is not proof of delivery.

`RcsMlsTransportProfile` describes a transport instance, not a family, and adapts the app's recovery
policy to it. `getMlsTransportProfile` never returns null; an implementation that has not
characterised itself returns `conservativeDefault()` (all false), chosen so a missing profile cannot
hang a send: with `requiresConvergenceAck` false the app never opens a send gate that no signal
could close.

## `IRcsProvider`

Ordinals are fixed by the ordering rule and listed here because they are the ABI. Methods that can
reach the network block and must be called off the main thread; `getE2eeInfo` and `isMlsReady` read
cached state.

| # | Method | Purpose |
|---|---|---|
| 1 | `getContractVersion` | Revision label; the anchor ordinal. |
| 2 | `getProviderCaps` | Static caps. |
| 3 | `attach` | Register the callback; returns the client token. |
| 4 | `detach` | Release the session. |
| 5 | `startForSub` | Provision and register a line. |
| 6 | `stopForSub` | Tear down a line. |
| 7 | `getCapabilitiesForSub` | Per-line caps, nullable. |
| 8 | `lookupRcsCapability` | `CAP_UNKNOWN` (-1), `CAP_SMS_ONLY` (0), `CAP_RCS` (1) for an E.164 destination. The provider caches; on unknown the app sends optimistically. |
| 9 | `sendMessage` | 1:1 message; synchronous `RcsSendResult`, terminal status via `onMessageStatus`. |
| 10 | `sendImdn` | Delivered/displayed receipt for an inbound message. |
| 11 | `sendTyping` | Typing indicator; the provider may ignore it. |
| 12 | `submitOtp` | A provisioning one-time code the app captured (it holds the SMS role). |
| 13 | `submitTosConsent` | The user's answer to a terms prompt; idempotent. |
| 14 | `getTosState` | Current `TOS_*` state for a line. |
| 15 | `createGroup` | Create a group with a client-chosen id; returns the authoritative `RcsGroupInfo` or null. See [groups.md](groups.md). |
| 16 | `getGroupInfo` | A group's current name, conference URI and members, or null. |
| 17 | `getGroupIds` | Newline-separated ids of groups that list this client, or null. Distinct from `getGroupInfo`: membership rather than existence. |
| 18 | `addGroupUsers` | Add members; true when accepted. |
| 19 | `removeGroupUsers` | Remove members (also used to leave a plaintext group); true when accepted. |
| 20 | `renameGroup` | Change the group's name; true when accepted. |
| 21 | `sendGroupMessage` | Text to a group; the server fans out. |
| 22 | `sendLocation` | Location share (`application/vnd.gsma.rcspushlocation+xml`), 1:1 or group; `accuracyMeters <= 0` sends a point, otherwise a circle. |
| 23 | `sendFile` | File transfer from an `RcsOutgoingFile`; the provider closes the descriptor. |
| 24 | `acceptIncomingFile` | Download a file the user accepted; re-delivered through `onIncomingMedia`. Idempotent. |
| 25 | `sendGroupTyping` | Typing indicator to a group. |
| 26 | `sendReaction` | Add or remove an emoji reaction on a message id. A replace is remove then add. No reaction-specific receipt. |
| 27 | `getBotBrand` | Business-messaging brand for a bot id; cached by the provider; null when unknown. |
| 28 | `sendBotPostback` | A suggestion-chip response to a bot. No receipt. |
| 29 | `setChatbotEnabled` | Turn business messaging on or off; the provider persists it and re-registers. |
| 30 | `getE2eeInfo` | Provider-layer encryption state for a line; never blocks. |
| 31 | `setE2eeEnabled` | Provider-layer encryption toggle; fires `onE2eeStateChanged`. |
| 32 | `canServeSub` | Pre-flight eligibility (`LINE_*`). |
| 33 | `rejectIncomingFile` | Decline an offered file (a session transport sends a SIP 603). Idempotent. |
| 34 | `ackInboundMessages` | Confirm inbound messages were applied; see below. |
| 35 | `lookupPeerMlsCaps` | A peer's MLS tags, verbatim. The eligibility decision is the app's. |
| 36 | `isMlsReady` | This line holds a current MLS certificate and has published KeyPackages: the same predicate that decides whether MLS is advertised at registration. |
| 37 | `createMlsConversation` | Establish a 1:1 MLS conversation from app-built artifacts. |
| 38 | `applyMlsControl` | Deliver a commit for an existing conversation. |
| 39 | `getMlsGroupInfo` | The server's GroupInfo for a peer's conversation, opaque. |
| 40 | `claimPeerKeyPackage` | Deprecated: one package, and null for every failure. Superseded by 42; it stays because removing a method renumbers every method after it. |
| 41 | `claimPeerKeyPackages` | All of a peer's packages, one per device, length-prefixed. |
| 42 | `claimPeerKeyPackagesWithOutcome` | As 41, with an `RcsMlsClaimResult`; never null. Preferred wherever the result is reported or recorded. |
| 43 | `mlsForgetConversation` | Drop the provider's peer-to-group record for a 1:1. Local only. |
| 44 | `mlsForgetGroupConversation` | Drop the provider's record for a group; returns the postcondition "no record remains" (true also when there was none). Local only. |
| 45 | `uploadKeyPackages` | Publish KeyPackages the app generated (the private keys must live in the app's engine), plus a last-resort package, which must carry the `last_resort` extension (0x000A); a plain KeyPackage is rejected in that slot. |
| 46 | `getMlsServerEraEpoch` | `[era, epoch]` from the server, or null. What a divergence means is the app's decision. |
| 47 | `exportMlsIdentity` | Leaf certificate, chain, private key and trust roots. A permanent channel: the provider refreshes the certificate and the app must pick the new one up. |
| 48 | `getMlsTransportProfile` | `RcsMlsTransportProfile`. |
| 49 | `sendMlsCiphertext` | Send an app-sealed 1:1 application message verbatim. |
| 50 | `sendGroupMlsCiphertext` | The group form of 49. |
| 51 | `getMlsGroupIdForPeer` | The MLS group id the provider holds for a peer, or null. |
| 52 | `sendReconciliationReceipt` | A plaintext delivery receipt used when a peer believes an MLS conversation exists that we hold no group for. |
| 53 | `addGroupUsersMls` | Add members and carry the Add commit and Welcome in the same request. |
| 54 | `removeGroupUsersMls` | Remove members and carry the Remove commit in the same request. |
| 55 | `selfLeaveGroupMls` | Leave, carrying a SelfRemove proposal (RCC.16 §7.11.8.1) for a remaining member to commit. A cached SelfRemove makes the engine require a commit before any further send, and that persists across restarts, so a refused leave must restore the pre-proposal snapshot. |
| 56 | `fetchMissedCommits` | The commits after our epoch, in order, length-prefixed; empty when current, null on failure. |
| 57 | `fetchServerEpochAuthenticator` | The server's 32-byte epoch authenticator, or null. |
| 58 | `changeGroupSubjectMls` | The encrypted subject, its RCC.16 commitment, the commit and the key delivery (a PrivateMessage at the post-commit epoch) in one request: the commitment is validated against the encrypted content in the same request, and the key is expected there too. |
| 59 | `sendMlsNegativeDeliveryImdn` | RCC.16 §7.7.2.2 negative-delivery receipt, signed by the app. |
| 60 | `sendMlsImdn` | Delivered/displayed receipt on an MLS conversation with the RCC.16 §12.1 headers. A receipt with a null signature is still sent, and logged; peers drop it, but it reaches the wire for diagnosis. |
| 61 | `sendMlsGroupImdn` | As 60 for a message received in a group; a non-null group id routes to the group and stamps from its state. |
| 62 | `getMlsGroupInfoForGroup` | The server's GroupInfo addressed by group id, opaque. The app needs this form, with its `external_pub` extension, to build a resync external commit. |
| 63 | `changeGroupIconMls` | Encrypted icon: the provider uploads the ciphertext, then sends the reference, commit and key delivery in one request. An upload failure aborts the change. |
| 64 | `getMlsTrustAnchors` | A second, separately fetched and verified trust-anchor set, length-prefixed. Replaces the caller's set of that kind; never merged, so a withdrawn anchor stays withdrawn. The `uri`, `generation` and `signerSpkiB64` arguments exist because on a carrier deployment the configuration document reaches the app, not the provider; the provider fetches and verifies the list. Any of them may be null or 0 to use what the provider holds, and without a signer no remote list is installed (the provider falls back to its cache or bundled set). |

Rules common to the MLS block (35 to 64):

* Every `byte[]` argument is an artifact the app's engine built. The provider places it in the
  request and dials; it does not parse, validate, reorder, re-frame or re-encrypt it.
* The app supplies the stamps that must come from the group state that sealed a message: era,
  epoch authenticator, signatures, and the message or control id bound into the MLS
  AuthenticatedData. The provider never mints them, because an id it generated could not match the
  one bound at encryption time.
* A membership or metadata change carries its commit in the same request as the RCS operation;
  the two cannot be ordered separately. The app has already applied the commit locally, so any
  verdict other than `VERDICT_OK` means restore the pre-commit snapshot.

Two app-side wrappers in `ProviderTransport` carry rules of their own:

* `claimPeerKeyPackages(subId, e164, outcomeSink)` writes the sink only when an outcome is actually
  known; otherwise it keeps the caller's initial value, and an uninitialised `int` reads
  `OUTCOME_SERVED`, which blames nobody. Nothing defaults to `OUTCOME_PEER_HAS_NONE`. It falls back
  to the older claim methods only when no request was made (`OUTCOME_NOT_ATTEMPTED`); after any
  request, including one that ended in a `RemoteException`, it does not claim again, because each
  claim consumes a one-time KeyPackage from the peer's pool.
* `sendImdn` on an MLS conversation mints the receipt's era, epoch authenticator and
  MLS-Derived-Content-Signature from one group state (`MlsProviderTransport.imdnStampsFor`) and
  sends through `sendMlsGroupImdn`, falling back to `sendMlsImdn` only for a 1:1 receipt; a group
  receipt is dropped rather than sent through the 1:1 form when the provider lacks the group
  method. With no stamps it sends the plain receipt, which a peer uses to reconcile a stale belief
  that the conversation is on MLS.

What the app does with these calls is described in
[../mls/transport-and-port.md](../mls/transport-and-port.md),
[../mls/group-lifecycle.md](../mls/group-lifecycle.md) and
[../mls/credentials.md](../mls/credentials.md).

## `IRcsProviderCallback`

The interface is `oneway`. A synchronous binder transaction to a process the cached-app freezer has
frozen kills that process; an asynchronous one is queued for it. The provider holds a raw binder
from `attach` and calls straight through it, with nothing in the framework to unfreeze the app
first, so every callback must be one-way. Consequences for implementers: no callback returns a
value, and a `RemoteException` means "could not be queued" (dead process or full async buffer), not
"the app failed to handle it". The queue is finite and shared, so it is not a durability guarantee
on its own; that is `ackInboundMessages`.

| # | Callback | Content |
|---|---|---|
| 1 | `onIncomingMessage` | An inbound 1:1 or group message (`groupId` set for a group). |
| 2 | `onMessageStatus` | `STATUS_SENT` (1), `STATUS_DELIVERED` (2), `STATUS_DISPLAYED` (3), `STATUS_FAILED` (4), with a free-text reason on failure. On `STATUS_SENT`, `e2eeSchemeId` is the scheme the provider applied on the wire, null for plaintext or unknown; the app stamps the row's padlock and the conversation's provider-plane bit from it, never from its own prediction, and ignores it for statuses from the carrier transport. |
| 3 | `onImdnReceipt` | A receipt for one of our messages: `IMDN_DELIVERED` (1) or `IMDN_DISPLAYED` (2), and a `confirmId`. |
| 4 | `onImdnReceiptForPeer` | A thread-wide receipt naming no message id. |
| 5 | `onRegistrationStateChanged` | `REG_UNREGISTERED` (0), `REG_REGISTERING` (1), `REG_REGISTERED` (2), `REG_FAILED` (3, terminal). |
| 6 | `onProvisioningStateChanged` | `PROV_NOT_PROVISIONED` (0), `PROV_IN_PROGRESS` (1), `PROV_WAITING_FOR_OTP` (2), `PROV_CONFIGURED` (3), `PROV_DISABLED_BY_CARRIER` (4, terminal), `PROV_NEEDS_REPROVISION` (5), `PROV_WAITING_FOR_TOS` (6), with caps. |
| 7 | `onOtpRequired` | The provider needs a one-time code; answered by `submitOtp`. |
| 8 | `onTyping` | A peer's typing indicator. |
| 9 | `onCarrierTosStateChanged` | `TOS_NONE` (0), `TOS_REQUIRED` (1, with prompt text), `TOS_ACCEPTED` (2), `TOS_DECLINED` (3). |
| 10 | `onGroupEvent` | A group lifecycle event, and a `confirmId`; see [groups.md](groups.md#group-events). |
| 11 | `onGroupTyping` | Typing in a group, naming the member. |
| 12 | `onGroupImdnReceipt` | A per-member receipt for one of our group messages, and a `confirmId`. |
| 13 | `onIncomingMedia` | An `RcsIncomingFile`. |
| 14 | `onIncomingReaction` | A reaction on a message id, and a `confirmId`; the reaction is not also delivered as a message. |
| 15 | `onIncomingBotMessage` | A business-messaging message; not also delivered as a message. |
| 16 | `onE2eeStateChanged` | New `RcsE2eeInfo`. |
| 17 | `onIncomingFileUnavailable` | An accepted file cannot be downloaded; see [Files](#files). |
| 18 | `onEncryptedGroupSubject` | An encrypted subject's content type and ciphertext; the app holds the key. |
| 19 | `onEncryptedGroupIcon` | An encrypted icon reference as two strings: `first` is the content type, `second` the URL. |
| 20 | `onMlsControlBundle` | MLS messages that must be applied in the given order, length-prefixed, with a convergence flag and the outer-envelope era, epoch authenticator and original message id. |
| 21 | `onMlsNegativeDelivery` | A peer reports that one of our messages failed on its side, with an `MLS_FAIL_*` reason. |
| 22 | `onMlsControl` | One inbound MLS control payload. |
| 23 | `onMlsCiphertext` | An inbound MLS application message, still sealed, with the outer-envelope headers. |
| 24 | `onMlsIdentityChanged` | The provider replaced the MLS credential; the app must re-read it and republish KeyPackages. |
| 25 | `onEncryptedGroupIconContent` | The downloaded ciphertext behind an icon reference, when the fetch succeeded and the content is within the provider's size cap (256 KB by default). An oversize icon is dropped with a log line while `onEncryptedGroupIcon` still fires, so a missing icon shows as a reference without content. |

`MLS_FAIL_*` are the binary codes of RCC.16 §7.6.3.2 for the five failure reasons of RCC.16
§7.7.2.3: `UNKNOWN` (0), `MESSAGE_FROM_NON_MEMBER` (1), `INVALID_CREDENTIAL` (2), `INVALID_COMMIT`
(3), `FAILED_TO_DECRYPT` (4), `COMMIT_IN_PRIVATEMESSAGE` (5). The XML element for code 4 is
`<failed-to-decrypt>` while the binary enum name is `failure_to_decrypt`; mixing the two produces a
report a peer ignores.

`GROUP_OP_*` carry the wire operation numbers 7 to 12: `CREATE`, `ADD_USERS`, `KICK_USERS`,
`CHANGE_PROFILE`, `CHANGE_ROLE`, `CHANGE_INFO`.

## Delivery confirmation

Delivering a callback and applying its content are different events: the app can receive a callback
and die before persisting the result, and binder death notification is asynchronous, so the provider
cannot tell from "a client is attached" whether a hand-off landed. `ackInboundMessages(token, subId,
ids)` makes delivery a two-phase handshake. The provider holds a pulled message unacknowledged
upstream until the app confirms it; a message that is never confirmed is offered again.

* The app confirms only after the work is done, never on entry to a handler, and on failure paths
  too: "tried and did not apply" is a final answer, while an unconfirmed message is re-offered until
  the provider gives up on it. A handler that throws confirms nothing, so the message comes back.
* Unknown or already-confirmed ids are ignored, so confirming a redelivery twice is harmless.
* MLS control bundles and MLS ciphertext are confirmed at every contract revision.
* From `CONTRACT_CONFIRMS_STORED` (3), passed to `attach`, the app also confirms every other
  content-bearing callback once its effect is stored: `onIncomingMessage`, `onIncomingMedia` and
  `onIncomingBotMessage` by the message's `messageId`, and `onIncomingReaction`, `onGroupEvent`,
  `onImdnReceipt` and `onGroupImdnReceipt` by their trailing `confirmId`. A null `confirmId` asks
  for nothing. A redelivery the app already stored is confirmed as well. The provider acknowledges
  such a message upstream only when every confirmation it asked for has arrived; for an app below
  revision 3 it acknowledges the message once the app demonstrably ran the callback.
* One inbound message can produce several callbacks (a receipt reaches both `onImdnReceipt` and
  `onGroupImdnReceipt`). The provider gives each its own `confirmId`, so each confirms its own
  store.
* In this app `RcsCallbackRouter` hands each callback's confirmation to the receive action, which
  sends it (`RcsInboundConfirmation`) after the action's store returns, to the provider that made
  the call. The in-process carrier transport has nothing upstream to confirm.
* While the app is not bound, a confirmation is dropped with a log line; the message is simply
  redelivered.
* A message is redelivered, too, when the app dies during the provider's call. The receive actions
  therefore look up its `rcs_message_id` (the sender's Message-ID, across conversations) and store
  nothing when a row already has it; `ReceiveRcsMessageAction` still sends the delivered receipt.

## Files

`onIncomingMedia` carries the file, its thumbnail, both, or neither: a provider may download a file
the user did not ask for only up to a limit of its own, and a larger file then arrives with a
thumbnail at most, or with no handle at all.

* The app stores every such delivery and confirms it: the file; else the thumbnail; else a
  placeholder part that carries the file's name and size (`RcsFileAttachment`). A thumbnail or a
  placeholder row is pending, and a tap on it calls `acceptIncomingFile`, which delivers the file
  again under the same `messageId`; the row's part is then pointed at the file. A pending copy
  offered again after the file arrived leaves the file in place.
* A file the app received and could not copy into its storage is neither stored nor confirmed, so
  the provider offers it again. No pending row is made for it: the provider has nothing left to
  download on a tap.
* A file no media view draws (anything but an image, video, audio clip or vCard), and any
  placeholder, is shown as a row with its name, size and type and the caption. A tap opens the file
  in another app with `ACTION_VIEW` and read access to that one URI.
* A file's caption is its part's text. An image, video, audio clip or vCard shows its caption in
  the bubble after the message's text; a file row shows its own.
* An outgoing file's caption (`RcsOutgoingFile.caption`) is the text typed with it, after the
  attachment's own caption if it has one (`RcsFileAttachment.outgoingCaption`); the sent row keeps
  it as its part's text. Only the first attachment of a draft is sent over RCS.
* A file the provider can no longer download is reported by `onIncomingFileUnavailable` after the
  tap's `acceptIncomingFile`, with `FILE_UNAVAILABLE_EXPIRED` (1) when its link expired or
  `FILE_UNAVAILABLE_UNKNOWN` (0) when the provider holds nothing for it; it is not confirmed.
  The app marks the pending row unavailable (`RCS_FILE_UNAVAILABLE`): a file row says "File no
  longer available" instead of "Tap to download", and a tap says so instead of asking again. A
  pending copy offered again makes the row pending, and the file's delivery replaces it.

## Receipts

A provider may send receipts and the user's messages through one queue, so a burst of receipts
delays what the user sends. When a conversation is read, the app sends a displayed receipt only for
inbound messages newer than the newest one already reported, and for at most the newest
`RcsDisplayedReceipts.MAX_PER_PASS` (10) of those; the older rows are marked reported without a
receipt, so no later pass sends them. While read receipts are off nothing is marked, so the newest
rows are reported once they are turned back on.

## Size limits

Binder transactions are capped at about 1 MB per process. Files therefore cross as descriptors. An
encrypted group icon is uploaded by the provider rather than passed as a URL, and an inbound icon's
ciphertext is size-capped by the provider before it crosses; the sender scales the image before
`ManageRcsGroupAction.changeIcon` for the same reason. The app copies an inbound file descriptor
into its own storage up to 100 MiB, the file-transfer limit carriers advertise; a larger one is
neither stored nor confirmed (see "Files").

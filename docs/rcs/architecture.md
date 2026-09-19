<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# RCS architecture

The messaging app reaches an RCS network through one of two routes, and needs neither:

| Route | Where it runs | What it is |
|---|---|---|
| Provider | a separate, preinstalled provider app, bound over AIDL | anything that implements `IRcsProvider`; see [provider-contract.md](provider-contract.md) |
| Carrier IMS | in this app, in an isolated `:ims` process | SIP registration, pager-mode MESSAGE and MSRP sessions against the carrier's IMS core; see [carrier-transport.md](carrier-transport.md) |

With no provider installed and a carrier that does not offer RCS, the app behaves exactly as
upstream Messaging: SMS and MMS. Route selection is per subscription, so a dual-SIM device can use a
different route on each line.

End-to-end encryption sits above both routes: the app owns the MLS group state and seals the
payload; a route carries an opaque envelope. See [../mls/overview.md](../mls/overview.md).

## Components

| Class | Responsibility |
|---|---|
| `RcsTransport` | The seam the rest of the app talks to. One implementation per route. |
| `ProviderTransport` | Singleton binding to the first provider that resolves the bind action. Implements `RcsTransport` and carries the full provider surface (groups, files, reactions, business messaging, MLS). |
| `BoundProviderTransport` | One bound external provider, keyed by component. Implements only the `RcsTransport` seam. |
| `CarrierImsTransport` | Main-process facade of the carrier IMS transport; forwards to `CarrierImsService` in `:ims`. |
| `ProviderRegistry` | Holds every transport and the per-subscription selection state. |
| `RouteSelector` | The selection algorithm, the per-subscription registration/provisioning gate, the per-recipient capability cache and the terms-of-service state cache. |
| `RcsCallbackRouter` | The single inbound sink. Implements `IRcsProviderCallback`; every transport feeds it. |
| `RcsSendStatus` | Which `message_status` an RCS row may hold, and from what evidence. |
| `RcsMessageStore` | Reads and writes the RCS columns and side tables. |

Startup (`BugleApplication.initializeSync`, main process only):

1. `ProviderTransport.getInstance(context).init()` posts the provider bind and the first selection.
2. `RcsCallbackRouter` is created and installed as the registry's callback sink.
3. `ProviderTransport` is registered with `ProviderRegistry.registerLegacyTransport` together with
   the package it binds, so discovery never binds that package a second time.
4. `CarrierImsTransport.register` places the carrier transport in the registry's in-process slot.
5. On the registry worker thread: `ProviderRegistry.discover()` then
   `RouteSelector.selectForActiveSub("app-start")`.

## The transport seam

Every `RcsTransport` method is safe to call before the transport is bound: it no-ops or returns a
not-registered answer (`LINE_UNKNOWN`, `CAP_UNKNOWN`, a non-accepted `RcsSendResult`) instead of
throwing, so the caller can always fall back to SMS. Methods are called from DataModel action
threads, never from the main thread, because the provider calls may block on the network.

| Method | Contract |
|---|---|
| `canServeSub` | Pre-flight, side-effect free. Returns `LINE_INELIGIBLE`, `LINE_ELIGIBLE`, `LINE_MAYBE` or `LINE_UNKNOWN`. |
| `startForSub` / `stopForSub` | Begin or end provisioning and registration. Idempotent. Progress arrives as registration and provisioning callbacks. |
| `getProviderCaps` | Static caps: label, contract version, transports, declared `priority`, `featureFlags`. |
| `lookupRcsCapability` | Is this destination on RCS: `CAP_RCS`, `CAP_SMS_ONLY` or `CAP_UNKNOWN`. |
| `sendMessage` | Synchronous accept/reject; the terminal status follows as `onMessageStatus`. |
| `isMlsReady` / `sendMlsMessage` | Defaulted to `false`; only the carrier transport carries app-sealed MLS through this seam. |
| `sendImdn`, `sendTyping`, `submitOtp`, `rejectIncomingFile` | Fire-and-forget. |

The two-argument `sendImdn` overload with an RCS group id is defaulted to the three-argument form.
On an MLS conversation the group id selects which group state stamps the receipt; the carrier
transports have no such stamping and need no change.

## Provider discovery and binding

A provider is a service that resolves `org.lineageos.rcs.provider.action.BIND_RCS_PROVIDER`.
Discovery queries with `PackageManager.MATCH_SYSTEM_ONLY`, so only providers on the system image are
considered; that is why a provider's declared `priority` is trusted without a cap. Components are
sorted by package name for a deterministic order. With no provider installed,
`ProviderRegistry.resolveProviderPackage` returns null and the app stays SMS-only.

`ProviderTransport` binds with an explicit package and runs this handshake in
`onServiceConnected`:

1. `getContractVersion()`: ordinal 1, the one transaction an insertion cannot renumber.
2. `RcsContractProbe.check`: compares the two sides' derived transaction layouts in both directions
   and refuses the provider on any disagreement. See
   [provider-contract.md](provider-contract.md#layout-verification).
3. `attach(RcsCallbackRouter, CONTRACT_VERSION)`: returns the client token passed on every later
   call. A null or empty token unbinds and schedules a reconnect.
4. Replay the queued `startForSub` requests, re-drive selection for the active subscription, and
   start `RosterRefillAction` (see [groups.md](groups.md#roster-refill)).

Failure handling:

| Event | Handling |
|---|---|
| `onServiceDisconnected` | Clear state, clear the selector's cached registration, provisioning, capability and terms state, reconnect with backoff (2 s doubling to 60 s). The framework reconnects the binding itself. |
| `onBindingDied` | The binding is gone (package replaced or removed) and will not come back: unbind, clear, reconnect. Without the explicit unbind the pending-bind flag would stay set and every later bind attempt would return early. |
| `onNullBinding` | Release the pending bind and retry with backoff. |
| Bind request unanswered for 30 s (`BIND_REQUEST_STALE_MS`) | `ensureBound` treats it as lost, unbinds the stale connection first (so bindings do not leak), then binds again. |
| A provider call while unbound | `notBound` logs that the call was never made, returns null, and posts a rebind. Callers must not report a null from these methods as a network refusal. |

`RcsProviderWakeReceiver` handles `org.lineageos.rcs.provider.action.WAKE_FOR_INBOUND`, protected
by `org.lineageos.rcs.permission.BIND_RCS_PROVIDER`. A provider sends it when it holds inbound
traffic for an app process that is not running; receiving it starts the process, which binds and
lets the provider deliver. `RcsProviderPackageReceiver` turns package add, remove and replace into a
`discover()` and re-selection.

`BoundProviderTransport` repeats the same handshake for each additional provider component, with
the same backoff, and nudges selection (not a fresh cycle) once its caps are known.

## Route selection

`RouteSelector.selectTransportForSub` picks exactly one transport per subscription.

1. For every registered transport call `canServeSub`. Drop `LINE_INELIGIBLE`; keep `ELIGIBLE`,
   `MAYBE` and `UNKNOWN` (a provider that is still binding answers `UNKNOWN` and must not be
   excluded for it).
2. Rank by `RcsProviderCaps.priority`, highest first. A transport whose caps are not known yet is
   *pending* and sorts last. Ties break on the provider package name, else the class name.
3. If a transport is already selected and still eligible, keep it (sticky). It is preempted only by
   a strictly higher-priority transport that is `LINE_ELIGIBLE` now and was not at the previous
   evaluation. Steady-state eligibility never preempts, so a higher-priority transport that keeps
   failing to provision cannot churn the line.
4. Otherwise, if any candidate is pending, defer for up to 15 s (`PENDING_GRACE_MS`) so a
   provider-only device lands on the provider instead of briefly starting the carrier transport. A
   timer re-runs selection when the grace expires.
5. Commit to the first known candidate and call `startForSub` on it. None: SMS only.

The selected transport becomes the *active* transport (`ProviderRegistry.getActiveTransport`) only
when it reports `PROV_CONFIGURED`. Until then `getActiveTransport` returns null, so no caller can
route RCS to a transport that is not provisioned.

Terminal failure of the selected transport is `REG_FAILED` or `PROV_DISABLED_BY_CARRIER`;
`REG_UNREGISTERED` is transient and does not move selection. On a terminal failure the transport is
added to the per-subscription failed set, a 60 s cooldown (`SELECTION_COOLDOWN_MS`) suppresses
externally triggered re-selection, and the walk moves to the next candidate captured at the last
selection. If that list is exhausted the registry is ranked again before concluding SMS, because a
provider may have registered after the snapshot was taken; handing the line to a transport that was
never tried also clears the cooldown. Two terminal failures closer than 3 s (`ADVANCE_DEBOUNCE_MS`)
are coalesced: the callback sink carries no transport identity, so a late failure from a
just-deselected transport would otherwise knock the new one off.

Triggers: app start, boot, subscription change, carrier config change, provider install, remove or
replace (all fresh cycles, which reset the failed set), a provider handshake (not a fresh cycle),
and terminal failure. Selection is computed under the registry lock, but `startForSub` and
`stopForSub` are binder calls into another app and are issued after the lock is released, because
`getActiveTransport` takes the same lock on the send path.

## Send gating

`RouteSelector.isRcsAvailableForSub` is true only when the RCS master toggle
(`RcsFeatureSettings`, preference `rcs_enabled`) is on and the subscription's last reported states
are `REG_REGISTERED` and `PROV_CONFIGURED`. `isGroupRcsAvailableForSub` applies the same test to
a group send.

The send actions use `awaitRcsAvailableForSub` (and the group form) instead. A send can start the
process (a share, a notification reply) and reach the gate before the selected transport has
reported anything; routing SMS then sends over SMS a message the line would carry over RCS a few
hundred milliseconds later. While the state is settling (no provisioning state reported yet,
`PROV_IN_PROGRESS`, `REG_REGISTERING`, or `PROV_CONFIGURED` with no registration state yet) and
selection may still produce a transport (`ProviderRegistry.mayStillSelect`: one is selected, a
higher-priority provider is still binding, or no selection has run yet in this process), the send
waits up to `RcsSendReadiness.MAX_WAIT_MS` (3 s), woken by each state callback. Anything else
known to be down (the toggle off, no transport, a reported failure, a waiting OTP or ToS) routes
SMS at once. The UI keeps using the non-waiting test.

The per-recipient capability cache holds only the sticky answers `CAP_SMS_ONLY` and `CAP_RCS`,
keyed by E.164. `CAP_UNKNOWN` is never stored, so a transient failure is retried. The cache is
filled by `ProviderTransport.lookupRcsCapability` and by `RouteSelector.notePeerNotRcs` when a send
is rejected with `REASON_PEER_NOT_RCS`, and it is cleared when the provider goes away. The compose
UI reads it through `isPeerRcsCapable`, which never crosses the binder.

`InsertNewMessageAction` tries the RCS forks in this order and falls through to the unchanged
SMS/MMS path on any `false`:

| Fork | Condition | Route |
|---|---|---|
| Group text | conversation has an `rcs_group_id`, text only | `tryInsertSendingRcsGroupMessage` |
| Group media | conversation has an `rcs_group_id`, one media part | `tryInsertSendingRcsGroupFile` |
| 1:1 text | SMS protocol, one recipient, non-empty text | `tryInsertSendingRcsMessage` |
| 1:1 media | one recipient, one media part | `tryInsertSendingRcsFile` |

"Send as SMS" (`KEY_FORCE_SMS`) skips all four. Each fork resolves the transport from
`getActiveTransport`, falling back to the `ProviderTransport` singleton when selection has not run.
The send happens before the row is written, so a rejected send leaves no orphan row. The row carries
a client-minted UUID in `messages.rcs_message_id`; status callbacks and receipts find the row by it.

On a conversation the app presents as encrypted, a send that cannot be sealed is refused rather
than downgraded: the row is written as a visibly failed RCS message and nothing goes out in the
clear, over RCS or MMS. A file is never uploaded unsealed and a failed seal is never retried
unsealed. See [../mls/transport-and-port.md](../mls/transport-and-port.md).

### The encryption gate

`conversations.encryption_protocol` latches per conversation: `ReceiveRcsMessageAction` sets it
when an inbound message is MLS-tagged, and the UI draws the padlock from it. (Its provider-plane bit
is not a latch: it follows what the provider reports applying, per `onMessageStatus` in
[provider-contract.md](provider-contract.md#ircsprovidercallback), and inbound 1:1 tags.) A new send
verb therefore inherits the padlock without inheriting a gate, which is why every outbound path
belongs to one of three gate families:

| Family | Sends | Decided by |
|---|---|---|
| Content | group text, group media, 1:1 media, location, reactions | `MlsSendRouting.Verdict`, through `MlsProviderTransport.groupSendVerdict` and `oneToOneSendVerdict` |
| Membership and metadata | add, remove, rename, icon, leave | `GroupMembershipRouting`, through `ManageRcsGroupAction`; see [groups.md](groups.md#routing-through-the-mls-layer-first) |
| Receipts | delivered and displayed IMDNs | `ProviderTransport.sendImdn`, which sends `sendMlsImdn` or `sendMlsGroupImdn` when the conversation holds MLS stamps; see [provider-contract.md](provider-contract.md#ircsprovider) |

`MlsUngatedOutboundSendGuardTest` fails when a new content send is not classified. The one ungated
content send is the business-messaging suggestion-chip postback (`sendBotPostback`); a bot thread's
inbound path (`ReceiveRcsBotMessageAction`) writes no scheme, so it cannot latch the padlock.

`MlsSendRouting.decide` combines what the engine can do with the latch:

| Engine (`SealCapability`) | Latch | Verdict |
|---|---|---|
| `SEALABLE` | any | `SEAL` |
| `NO_MLS_STATE`, `DOWNGRADED`, `ENGINE_UNAVAILABLE`, `NO_MLS_IDENTITY` | `LATCHED` | `REFUSE` |
| the same | `CLEAR` or `UNREADABLE` | `PLAINTEXT` |

A missing input is `REFUSE`. The latch is `MlsLatch`, read from `EncryptionProtocolBits.mlsBit()`
alone, never from `isE2eeEncrypted()` (the padlock is drawn on either bit), so a conversation the
provider encrypts is not refused. `oneToOneSendVerdict` asks the engine the weaker question "do we
hold 1:1 state now". The error direction is chosen: a false `CLEAR` degrades to plaintext, a false
`LATCHED` costs one visible, retryable refusal. Membership changes make the opposite choice
(`changeGroupMembership` answers plaintext with no MLS identity, since a roster change carries no
content).

Only `PLAINTEXT` may send media, location or a reaction; there is no sealed form of any of them in
this path, and `SEAL` says only that text could be sealed:

* Media (`tryInsertSendingRcsFile`, `tryInsertSendingRcsGroupFile`) is refused rather than uploaded,
  because an unsealed attachment would sit at a URL on a content server. The 1:1 gate reads the
  E.164 form of the destination; the national form keys no state, would answer `NO_MLS_STATE` for a
  conversation that has state, and would fail open.
* Location (`SendRcsLocationAction`) writes a failed row on any other verdict.
* A reaction (`ConversationFragment.onReactionSelected`) is refused because a cleartext reaction
  names its target message id in a header. There is no row to mark failed, so the optimistic chip is
  reverted (`withdrawRefusedReaction`).

## Inbound path

`RcsCallbackRouter` receives every callback on a binder thread (a provider) or on the carrier
facade's main-looper handler (the carrier transport, in process). It hands each one to a DataModel
action, so database writes run on the action-service thread the SMS path already uses:

| Callback | Handling |
|---|---|
| `onIncomingMessage` | `ReceiveRcsMessageAction`. `contentType` and `body` are final: the producer has already removed any RCC.16 framing (see [Unframing](#unframing)). |
| `onIncomingMedia` | A file descriptor cannot survive the action queue, so the router copies it into `MediaScratchFileProvider` first, with the calling identity cleared (the write must run as this app's uid, not the provider's). Then `ReceiveRcsMediaAction`. |
| `onIncomingReaction` | `UpdateRcsReactionAction`; the reaction message itself never becomes a row. |
| `onIncomingBotMessage` | `ReceiveRcsBotMessageAction`; the GSMA JSON is parsed in the app (see [Business messaging](#business-messaging)). |
| `onMessageStatus`, `onImdnReceipt`, `onGroupImdnReceipt` | `UpdateRcsMessageStatusAction`. |
| `onGroupEvent` | `ReceiveRcsGroupEventAction`; see [groups.md](groups.md). |
| `onRegistrationStateChanged`, `onProvisioningStateChanged` | `RouteSelector`. |
| `onTyping`, `onGroupTyping` | Main-thread listener fan-out (suppressed when typing indicators are off) plus a package-scoped broadcast. No database write. |
| `onOtpRequired`, `onCarrierTosStateChanged`, `onE2eeStateChanged` | Cached where relevant and re-broadcast, package-scoped, to the UI. |
| MLS callbacks | `MlsProviderTransport`; see [../mls/overview.md](../mls/overview.md). |

A message id is claimed atomically before insertion (`claimInboundInsert`), so two concurrent
dispatches of one message cannot both pass the database existence check. Claims are never released
and are bounded by a 512-entry LRU; the window that matters is milliseconds.

For MLS traffic the app confirms each inbound message with `ackInboundMessages` once it has been
applied, including on the failure paths; see
[provider-contract.md](provider-contract.md#delivery-confirmation).

`MlsHeaderGate` requires `Era-ID` and `Epoch-Authenticator` on inbound MLS traffic. On the
provider path `RcsCallbackRouter` enforces it on `onMlsCiphertext` (the message is dropped) and only
logs the verdict on `onMlsControlBundle`, which is applied either way. A gate that throws accepts.
The carrier transport enforces it before decryption; see
[carrier-transport.md](carrier-transport.md#mls-on-this-transport).

### Unframing

A decrypted MLS payload is an RCC.16 MIME entity (`RccMlsBody`). Whoever builds the
`RcsIncomingMessage` unframes it exactly once: `RcsCallbackRouter` from the body `decryptInbound`
parsed, or `CarrierImsService.emitIncoming` on the carrier path. `ReceiveRcsMessageAction` takes the
type and body verbatim. `RccMlsBody.parse` cannot be made idempotent: a text message that is a
pasted header block, or an image containing a `Content-Type:` line, looks framed, and a second parse
would consume it.

A frameless payload whose 8-byte RCC.16 header (version `0x0001`) declares a payload type other than
text is `UNKNOWN_SECRET_PAYLOAD`, which `RccContentDisposition` classifies `DROP_UNKNOWN`. The drop
arm of `RcsCallbackRouter` returns before the insert that sets `wantsDeliveredImdn`, so such a
payload is neither rendered nor receipted. Raw text, which cannot start with `00 01`, still renders.
`RccCpimReaction` reads a reaction carried inside the ciphertext: nothing on the outer envelope
marks it, and the CPIM part holds `NS:` declarations for `http://www.gsma.com` and
`urn:rcs:message:reactions:`, `Reference-ID`, `Reference-Type: +Reaction` and
`Origin-Surface-Type`, a blank line, then a `text/plain; charset=UTF-8` part whose body is the
emoji. The parser finds the first `NS:` within the first 64 bytes rather than assuming the width of
the prefix before it.

### Received messages

* **Media type.** The media arm of `ReceiveRcsMessageAction` normalises the content type once,
  first, with `RccContentDisposition.canonicalType`: type and subtype lower-cased, parameter values
  untouched, blank left blank. MIME types are case-insensitive (RFC 2045 §5.1), and the renderers
  (`ContentType.isImageType` and the other predicates) compare case-sensitively.
* **Quoted-text tapbacks.** A text message such as `Liked “...”` is applied as a reaction
  (`UpdateRcsReactionAction`) to the unique message in the conversation whose text matches the
  quote; with no unique match it is stored as ordinary text. Only `TEXT` dispositions are
  candidates. `RcsIosTapback` holds the English grammar and verb table only; another locale needs
  its own patterns in `ADD`, `REMOVE` and `VERB_EMOJI`.
* **Statuses on incoming rows.** `UpdateRcsMessageStatusAction` drops a status or receipt that
  resolves to an incoming row. In a group every member stores its received copy under the sender's
  id; marking it `OUTGOING_DELIVERED` would make it look outgoing to
  `RcsMessageStore.findTextByRcsMessageId` and eligible as resend material, and RCC.16 §10.3 forbids
  resending another member's message.
* **Reactions.** `UpdateRcsReactionAction` keys a reaction on `MlsResendLedger.rootOf(target)`,
  because `ConversationMessageData` joins `rcs_reactions.target_rcs_message_id` against
  `messages.rcs_message_id`, which holds only the root id of a resend chain. A reaction to an
  original and to its resend collapse to one row (primary key `(target, reactor)`,
  `CONFLICT_REPLACE`).

### Business messaging

`ReceiveRcsBotMessageAction` stores an inbound business message with the raw GSMA bot-message JSON
as its body; the conversation snippet is `RbmSummary`, and the sender name is the verified brand
from `getBotBrand`. No delivered receipt is sent, because a bot address does not accept one. Tapping
a suggestion chip sends the postback and inserts, through `InsertRbmPostbackEchoAction`, an
already-sent outgoing row whose `rcs_message_id` is `RcsConstants.RBM_POSTBACK_ECHO_MARKER`; the
renderer draws it as the selected option.

### Location

A location share goes out through `sendLocation`, gated as above. The bubble's map thumbnail comes
from `OsmStaticMap`, which composites OpenStreetMap raster tiles so the point is centred; a single
tile would place it at an arbitrary offset, and hosted static-map endpoints are not usable by
third-party apps. Results are cached per `(lat, lon, zoom, size)`, and requests send an identifying
User-Agent, as the tile policy requires.

## Send status

An RCS row uses the same `message_status` column as SMS, plus `rcs_status`, which stores the
provider's `STATUS_*` value verbatim (`RCS_STATUS_NONE` = 0 until one arrives).

| Evidence | `message_status` | `rcs_status` |
|---|---|---|
| `STATUS_SENT` | `OUTGOING_COMPLETE` | 1 |
| `STATUS_DELIVERED`, `STATUS_DISPLAYED`, or a delivered/displayed IMDN | `OUTGOING_DELIVERED` | 2 or 3 |
| `STATUS_FAILED` | `OUTGOING_FAILED` | 4 |
| Synchronous hand-off accepted (MLS app-sealed sends, group sends, location) | `OUTGOING_COMPLETE` | 1 |
| Synchronous hand-off refused | `OUTGOING_FAILED` | 4 |

`RcsSendStatus.bugleStatusForMeasuredHandoff` and `rcsStatusForMeasuredHandoff` apply the same
mapping `UpdateRcsMessageStatusAction.mapBugleStatus` applies to the callback, so a synchronously
reported row is indistinguishable from one updated by `onMessageStatus`. An accept is a claim about
the send, never about delivery; only an IMDN moves a row to delivered.

Of the writers of an outgoing RCS row with a wire id, only the file sends
(`tryInsertSendingRcsFile`, `tryInsertSendingRcsGroupFile`) rely on the provider's
`onMessageStatus`. Every other writer, `SendRcsLocationAction` included, records its synchronous
outcome through `RcsSendStatus`, because no status callback follows it. `RcsSendStatusGuardTest`
fails on a new writer until it is classified.

`OUTGOING_YET_TO_SEND` (4) and `OUTGOING_AWAITING_RETRY` (7) are queue states owned by
`ProcessPendingMessagesAction`, and that query excludes `TRANSPORT_RCS` rows by name. An RCS row in
either state is therefore owned by nothing (`RcsSendStatus.strandedOnRcsTransport`). Three rules
keep rows out of it:

* A send whose outcome is known synchronously writes that outcome in the same transaction as the
  row.
* `FixupMessageStatusOnStartupAction` moves `TRANSPORT_RCS` rows at 4 or 7 to `OUTGOING_FAILED` at
  cold start. Every RCS send is dispatched before its row is written, so nothing can be in flight
  across a process restart. Failed is the only safe direction: a false "sent" hides a lost message,
  a false "failed" is visible and offers a retry. The sweep is corrected later only by a late
  `onMessageStatus` or a peer receipt; verbs that report synchronously (location, group messages,
  the MLS ciphertext verbs) get no late status, so a false "failed" on one of them would be
  permanent. That is why those sends record their outcome at the send site.
* `ResendMessageAction` never hands an RCS row to the SMS queue. From `OUTGOING_FAILED` it resends
  over RCS through `MlsProviderTransport.resendByUser`, the same machinery a peer-reported resend
  uses, and writes the synchronous result. The row keeps its original `rcs_message_id`; the resend
  goes out under a fresh wire id whose receipt resolves back to the row. A refused row has no
  `rcs_message_id`, because it never reached the wire; it is left failed and keeps "Send as SMS".
  One-click resend is offered on an RCS row only when `ConversationMessageData.canResendOverRcs`
  holds: the row is E2EE (the resend only seals; it has no plaintext arm) and has an
  `rcs_message_id` (the resend resolves the body from it).

## Persistence

RCS messages live in the upstream `messages` table so the existing cursors render them.

| Column / table | Meaning |
|---|---|
| `messages.transport_type` | 0 SMS/MMS, 1 RCS (`TRANSPORT_RCS`), 2 group status line (`TRANSPORT_RCS_SYSTEM`) |
| `messages.rcs_message_id` | The wire message id; indexed. On a status line, the de-duplication signature. |
| `messages.rcs_status` | Last provider `STATUS_*`; 100 (`RCS_FILE_PENDING`) for an inbound file whose thumbnail arrived and whose body awaits the user's accept. |
| `messages.rcs_delivered_timestamp`, `rcs_displayed_timestamp` | 1:1 receipts. |
| `messages.rcs_contribution_id` | CPM contribution id. |
| `messages.rcs_e2ee_scheme_id` | Opaque scheme id the message was sealed or opened under; drives the per-message padlock. Null for plaintext. |
| `conversations.rcs_group_id` | The group id this conversation maps to; indexed. See [groups.md](groups.md). |
| `conversations.needs_roster_refill`, `rcs_self_left` | Group roster and membership markers. |
| `conversations.encryption_protocol` | Latched per-conversation encryption bits. |
| `rcs_group_receipts` | Per-member delivered/displayed timestamps for our group messages, keyed `(message_id, participant_uri)`, cascading on message delete. |
| `rcs_reactions` | One row per `(target_rcs_message_id, reactor_uri)`; keyed by the target's wire id because a reaction can arrive before the target's local id is known. |

`database_version` 3 adds all of the above in `DatabaseUpgradeHelper.upgradeToVersion3`; version 4
adds the MLS schema on top, so a device can sit at 3 with RCS and no MLS. Every step is additive
(new tables, indexes, and columns with defaults). A published upgrade step is never edited; a later
schema change adds its own step. `onCreate` and the upgrade chain must produce the same schema; see
[../testing.md](../testing.md).

## Settings

| Preference | Default | Effect |
|---|---|---|
| `rcs_enabled` | on | Master toggle. Off routes everything to SMS/MMS and stops selection from provisioning. |
| `rcs_typing_indicators` | on | Governs both sending and showing typing indicators. |
| `rcs_send_read_receipts` | on | Global displayed-receipt switch, with a per-conversation override (default, on, off) in `ReadReceiptSettings`. |

## Build notes

`proguard.flags` keeps `org.lineageos.rcs.provider.**`. The layout probe reads the `TRANSACTION_*`
fields the aidl compiler generates; R8 renames the stub proxy and inlines those fields, which leaves
the probe with nothing to derive and the app refusing every provider. The probe fails closed if the
rule is lost, so the symptom is loud: no provider can attach.

The exported debug receivers (`RcsDebugSendReceiver`, `RcsDebugGroupReceiver`,
`RcsDebugFtSendReceiver`, `RcsDebugCarrierDriveReceiver` and others) return immediately unless the
build is debuggable.

<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# MLS: the provider transport and its port

`MlsProviderTransport` (in `src/com/android/messaging/rcs/e2ee`) is the app's MLS transport over
the RCS provider. It is split in two:

* **The transport** keeps what needs Android: a `Context`, `SharedPreferences`, the bound
  provider, the app database, WorkManager, notifications. It is the public API the rest of the app
  calls.
* **The engine module** holds the decisions: when to create, join, advance, heal, park, resend or
  refuse. Those live in engine classes such as `MlsGroupEstablish`, `MlsMembership`,
  `MlsEraAdvance` and `MlsCommitApplication`, and reach the transport's effects only through
  `MlsShellPort`.

The point of the split is testability. A recovery decision fires in failures that cannot be
produced on demand on a device, so it has to be reachable from a host JUnit run, and it cannot be
while it shares a method with a `Context` call. The rule the split maintains is that **no method in
the transport mixes an Android effect with a decision**; `MlsTransportDecisionCoverageGuardTest`
enforces it by running `tools/mls/transport-classify.py` over the transport's source.

See [overview.md](overview.md) for where this sits and [group-lifecycle.md](group-lifecycle.md)
for the flows that run through it.

## Shape of the transport

```
                        app callers (send path, receivers, UI actions)
                                        │
                ┌───────────────────────▼────────────────────────┐
                │ MlsProviderTransport                           │
                │   public API + E2eeConversationTransport       │
                │   delegates:  /** @see Target#method */        │
                │   effects:    pt(), lock(), putGroup(), ...    │
                │   mShell = new MlsShellPort() { ... }          │
                └──────┬──────────────────────────────▲──────────┘
                       │ Target.method(mShell, mLog,..)│ shell.x()
                ┌──────▼──────────────────────────────┴──────────┐
                │ engine: MlsGroupEstablish, MlsMembership, ...  │
                └────────────────────────────────────────────────┘
```

### Delegates

A method whose body moved to the engine keeps a same-signature delegate in the transport only
while something in the app calls it there: the public API (the send path, `ManageRcsGroupAction`,
`RcsCallbackRouter`, the debug receivers), other transport code, or the `MlsShellPort` adapter. A
moved method with no such caller has no delegate and appears only in the manifest described below;
the port's engine seams (below) run their targets without one.
A delegate's only javadoc is one line:

```java
/** @see MlsGroupEstablish#establishGroup */
public int establishGroup(final String rcsGroupId, final List<String> members,
        final byte[] carryGroupInfo) {
    return MlsGroupEstablish.establishGroup(mShell, mLog, rcsGroupId, members, carryGroupInfo);
}
```

Callers, including code outside the MLS package, see only the transport's method. The target is a
static method that takes the port (and, where it logs, the `MlsLogSink`) as leading arguments.

The moved methods are listed, one `Target#method` per line, in
`tests/src/com/android/messaging/rcs/transport-moved.txt`. `SourceScan` builds from that list an
*unsplit* view of the transport (`transportAsUnsplit()`, `transportUnsplitCode()`): the engine
bodies restored in place, so a source-scan guard that asserts something about a moved method's body
sees the body rather than a one-line forward, whether or not a delegate still stands.
`transportAndMoved()` is the view for "X appears nowhere in the transport's code", covering the
shell plus every listed destination. The one-line `/** @see Target#method */` form is
**machine-read** too: the unsplit view blanks each delegate that carries it, and
`TransportMovedManifestGuardTest` checks that every one is listed. So:

* list a newly moved method in the manifest; the guard fails until it is there;
* keep the delegate javadoc exactly, and keep it the only content of the delegate's javadoc;
* do not use that exact form anywhere else in the transport;
* a guard about a delegated method reads the unsplit view or the declaring engine file, never the
  shell alone. An absence check over the shell alone passes vacuously for a method that lives in the
  engine.

### What stays in the transport, and why

| kept | why it stays |
|---|---|
| `ensureSession`, `dropSession`, `openMls` | construct the engine with `MlsHostPorts` (a `Context`), read the provider's transport profile, own `mSelf` under `mSessionLock` |
| `pt(what)` | the single entry to `ProviderTransport`, and the lock assertion (below). The transport's own provider calls (bring-up's transport profile, `isMlsReady`, `anyParticipantUnroutable`, `publishKeyPackages`) go through the port's `rpc(what)`, which is built on it |
| `lock` / `unlock`, `conv` / `convIfAny`, `getGroup` / `putGroup`, `selfE164` | primitives every flow uses; moving them adds a hop and no separation |
| `publishKeyPackages` | uploads through the provider and writes a debug dump to the cache directory |
| `conversationIdFor`, `bugleRoster` | read the app's conversation database |
| `sealCapability` and the static send verdicts | answer the app's send routing (`MlsSendRouting`) |
| `forget`, `clearConversationState`, `perConversationStores`, `scopeFor` | tear down the app stores for one conversation (below) |
| the debug fixtures (`armOutboundHold`, `releaseOutboundHold`, `publishOutboundHold`, `dropOutboundHold`, `outboundHoldStatus`, inbound hold) | debug entry points and their stand-in publish |
| the `@Override` surface of `E2eeConversationTransport` and the public overloads | the API; each forwards to one delegate |

One-line forwarders that would only trade a one-liner for an equal delegate also stay.

## `MlsShellPort`

`MlsShellPort` (engine package) is what engine-side decision code needs from the transport. The
transport implements it as an anonymous class held in `mShell` whose every member calls straight
back into the transport's own method or field, so a decision in the engine runs with the same
effects, locks and ordering as it would inside the transport. Host tests implement it with
`FakeShellPort`.

Members are added when engine code first needs one, never speculatively, so the interface is
exactly the surface engine code uses. They fall into five kinds:

| kind | examples | contract |
|---|---|---|
| the transport's own methods | `ensureSession`, `getGroup`, `putGroup`, `commitAndSend`, `eraAdvance`, `selfHeal`, `claimOne` | called as the transport called them, same locking, same effects |
| live fields | `session()` (`mSelf`), `groups()`, `convStates()`, `convAlias()`, `keyToConvId()`, `records()`, `pendingBodies()`, `sealedCache()`, `driveLoop()` | used as the transport used them; never reassigned through the port |
| Android services behind a neutral type | `prefs()` → `MlsPrefs`, `sysprops()` → `MlsSysProps`, `rpc(what)` → `MlsProviderRpc`, `peerGuard()` → `MlsPeerGuards`, `resendLedger()`, `elapsedRealtime()`, `base64Decode` | the device binding forwards unchanged |
| app effects | `applyGroupIcon`, `applyGroupSubject`, `applyGroupDeparture`, `conversationMlsBit`, `downgradeMlsScheme`, `scheduleRetry`, `raiseStall` / `clearStall`, `loadIdentity`, `refreshIdentityFromProvider` | one line each on the device; bound to `mCtx` and `mSubId` |
| engine seams | `resolveInbound`, `moveHealth`, `lookServerEraEpoch`, `park` | `default`: runs one engine static |

An engine seam's `default` body runs its target with `this`, `log()` and, for three of them,
`cfg()`; the transport does not take part, and `log()` and `cfg()` return its `mLog` and `mCfg`.
Host tests stub a seam by name like any other member, because a `Proxy` sends default methods to
its handler too. `keyPackageUsable` is not a seam: it binds `mSelf`, an app-side cipher-suite
constant and the wall clock, so the transport implements it.

Rules that hold across the port:

* **`session()` is read live and never assigned.** The one write of `mSelf` outside
  `ensureSession` is `dropSession()`, taken under `mSessionLock`; the next `ensureSession` reopens
  against the identity on disk.
* **`conv(key)` creates, `convIfAny(key)` does not.** `conv` is for write paths only; a read path
  that calls it materialises an entry for every key it is asked about, which turns a map keyed by
  peer into a leak fed by strangers.
* **`conversationMlsBit` is three-valued.** `null` means no row or unreadable, not plaintext.
* **`applyGroupDeparture` opens a database transaction**, so it must never be called while
  holding a conversation lock.
* **`base64Decode` is the platform decoder** (`android.util.Base64.DEFAULT`), not
  `java.util.Base64`. The two differ on whitespace and padding in malformed input, and these
  strings come from peers and preferences. `NO_WRAP` and `DEFAULT` decode identically, so one
  member serves both.
* **`applyInboundControl` is on the port** because the inbound control path re-enters itself
  (applying a control can drain deferred controls, which applies controls). A cycle of calls needs
  one link through the port before any member of the cycle can live in the engine.

### The device bindings

| engine type | device binding | notes |
|---|---|---|
| `MlsPrefs` | `MlsAndroidPrefs` over the `mls_provider_conv` preferences file | the same file the transport writes, so reads and writes on both sides agree |
| `MlsSysProps` | `MlsAndroidSysProps.INSTANCE` | read-only `debug.rcs.*` |
| `MlsProviderRpc` | `MlsProviderRpcBinding` over `pt(what)` | method names and argument order are `ProviderTransport`'s minus the leading `subId`; AIDL results converted to value mirrors |
| `MlsPeerGuards` | `MlsPeerGuardBinding.INSTANCE` | static forwarder to `MlsPeerGuard` |
| `MlsResendLedgerAccess` | `MlsResendLedgerBinding` | static forwarder to `MlsResendLedger` |
| `MlsSendPayload` | `MlsSendPayloadBinding` | converts at the delegates |
| `MlsSealedCacheAccess`, `MlsRendezvousAccess`, `MlsPendingBodyAccess`, `MlsPendingQueueAccess`, `MlsReupgradeAccess`, `MlsRecordAccess` | the app stores implement them directly | `MlsCiphertextCache`, `MlsRendezvousStore`, `MlsPendingBodyStore`, `MlsPendingQueueStore`, `MlsReupgradeStore`, `MlsRecordStore` |
| `MlsLogSink` | `MlsHostPorts.logSink()` | forwards to `LogUtil` under `MlsLog.TAG`, so a line written from the engine is identical to one written by the transport |

`MlsProviderRpc` mirrors four AIDL result types as plain values (`ControlResult`, `SendResult`,
`TransportProfile`, `ClaimResult`) so engine code never names a `Parcelable`. The mirrors copy the
AIDL types' constants and fields, and each `toString` reproduces the AIDL type's text so a log line
reads the same whichever side writes it. `MlsProviderRpcMirrorTest` compares the
mirrors against the AIDL sources. The AIDL contract itself is unchanged by any of this.

## The lock model

One rule, three clauses:

> **One lock per `(identity, group)`. Held across the engine call and its storage write. Never
> held across transport I/O.**

| clause | reason |
|---|---|
| per `(identity, group)` | Two conversations share no MLS state, and a stuck conversation is the normal state while recovery runs; a process-wide lock would let one block all. The identity is part of the key because the engine is per identity: one RCS group id under two identities is two MLS groups in two storage directories. |
| across the engine call and its storage write | The native store makes one engine call one atomic file write; the lock makes the read-modify-write around it atomic. Released in between, a second caller could read a state the first is about to overwrite. |
| never across transport I/O | Holding it across an RPC serialises every inbound commit for that conversation behind its own outbound request, and the recovery paths, which do the most I/O, are where inbound progress matters most. |

Implementation:

* `MlsGroupLocks` maps `identity + "\0" + key` to a `ReentrantLock`. Reentrant so a helper called
  inside a critical section can re-acquire; entries are never evicted, because evicting one can
  hand two threads different monitors for the same key.
* `lock(key)` / `unlock(key)` take the lock for `(selfE164(), key)` and are always paired in a
  `finally`. `getGroup` reads under the group's lock.
* **The third clause is checked, not remembered.** Every provider call goes through `pt(what)`,
  which asks `MlsGroupLocks.anyHeldByCurrentThread()`. On `eng` and `userdebug` builds
  (`LOCK_ASSERTIONS`) a held lock throws `IllegalStateException` naming the held keys; on user
  builds it logs with a stack trace and continues, so a lock-ordering slip does not become a
  dropped message. The port's `rpc(what)` goes through `pt(what)`, so the assertion fires at the
  same point for engine code, before the RPC's arguments are evaluated.
* **The identity-scope lock is separate.** `mSessionLock` guards session bring-up and the fields
  that belong to the session rather than to a group (`mSelf`, `mProfile`, `mSelfE164`). It is a
  dedicated object rather than `this`, so no outside caller can join the lock ordering by
  synchronising on the instance. After bring-up `ensureSession` returns on the `mSelf != null` fast
  path without taking it.

The shape every mutating flow follows, as in `MlsMembership.mlsMembershipChange`:

```
lock(key)
    read group; snapshot = exportGroupSnapshot(gid); build commit in the engine
unlock(key)
RPC to the provider                                  // no lock held
lock(key)
    on refusal: restoreGroupSnapshot(gid, snapshot)  // or quarantine if the restore fails
    on success: update the Group and the record
unlock(key)
```

Because the lock is released during the RPC, state can move between building a request and
applying its response. The flows pay for that with a re-check (snapshot and restore, and the
server-state comparison in [group-lifecycle.md](group-lifecycle.md#verify-then-adopt)) instead of
a stall.

Connectivity is not checked up front. An operation is attempted and its failure classified
(`MlsTransportDisposition`: a transport failure is retryable, a refusal is not); a pre-flight
"is there a network" test is a race with its own answer. `VERDICT_NOT_IN_GROUP` is permanent, not
retryable: the server does not count this line as a member, so a resend loops, and the repair
(retiring the stale local membership) is unreachable while the verdict says retry.

## Session bring-up

`ensureSession()`:

1. Drains the previous operation's state-write size into metrics (`noteStateWrite`), once per
   operation, instead of instrumenting every engine call site.
2. Returns immediately if `mSelf` is set.
3. Under `mSessionLock`: refreshes the enrolment identity if the provider has a newer one
   (`maybeRefreshIdentity`, see [credentials.md](credentials.md)), loads it from
   `MlsIdentityStore`, and starts the engine with `FIXED_365_DAYS` KeyPackages, the
   `DEPLOYMENT_TOLERANT` peer-certificate policy and the configured RCC.16 revision. It then reads
   the provider's `RcsMlsTransportProfile` (the one
   binder call permitted under the session lock: it is on the bring-up path only, and `pt` checks
   group locks, not this one), records `mSelfE164`, and tops up the KeyPackage pool.
4. After the session lock is released, arms the background group sweep (`armGroupSweep`).
   Maintenance does transport I/O, so it cannot run under `mSessionLock`; bring-up only refreshes
   the identity and the pool, and a group nobody opens is otherwise never maintained
   ([health-and-recovery.md](health-and-recovery.md)).

`MlsProviderTransport.get(ctx, subId)` returns one instance per process, created on first use
with that call's `subId`, so the engine session and its sender-ratchet cache are reused rather than
reopened per send. `peek()` returns it without creating one.

## Configuration

Every behavioural knob is resolved once, in the constructor, into `MlsConfig` from an
`MlsConfig.Source`. On a device the source is `SystemProperties`; a test passes its own through
the three-argument constructor. The constructor logs the resolved values and says that they are
cached: changing one of these properties takes effect after the app restarts. The `dump*`
diagnostics and a few debug levers (`debug.rcs.mls_advance_*`) are read live through `sysprops()`.

## Conversation keys and the in-memory maps

Every per-conversation map, lock and record is keyed by the canonical key from
`MlsConversationKey.canonicalKey`: `"g:" + rcsGroupId` for a group, `"p:" + peerE164` for a 1:1.
The app's conversation id is not the key: inbound traffic carries no conversation id, and keying by
peer cannot represent a 1:1 and a group that contain the same person.

| field | maps |
|---|---|
| `mGroups` | canonical key → `MlsTransportTypes.Group` (group id, peer, RCS group id, era, epoch authenticator, send counters) |
| `mConv` | canonical key → `ConvState` (per-conversation in-memory state, one object instead of one map per concern) |
| `mConvAlias` | app conversation id → canonical key, so `encryptForSend(conversationId, …)` resolves |
| `mKeyToConvId` | canonical key → app conversation id, the inverse, used where a decision made in key space must land in conversation space (the encryption bit, re-upgrade columns) |

`ConvState` holds every in-memory per-conversation field, so `forget` is one removal and there is
one monitor per conversation. That monitor is a leaf lock: nothing inside `synchronized (state)`
takes another lock, so it cannot invert with the group or session locks. It is work in flight and
resets on restart; whatever must survive a restart (health, the pending operation, the self-heal
budget, resend counts) is in `MlsConversationRecord`. The `mGroups` row is a cache too: `getGroup`
rebuilds a missing row from the durable alias and record.

`putGroup` writes three things under the conversation lock: the `mGroups` row, the durable
conversation-to-group alias (`MlsRecordStore.putAlias`) and the whole `MlsConversationRecord`
([group-lifecycle.md](group-lifecycle.md#persistence)).

## Forgetting a conversation

`forget(rcsGroupId, peerE164)` deletes the engine's group and then calls
`clearConversationState(scope)`, which removes the in-memory entries and asks every durable
per-conversation store to forget the conversation. The durable stores are listed in one place,
`perConversationStores()`: the record store, the pending queue, the re-upgrade store, the resend
ledger and the sealed-ciphertext cache. Each implements `MlsPerConversationState`, and
`MlsPerConversationStateGuardTest` fails when a class implements that interface and is not listed
there, so a new store cannot be left out of the teardown silently. A store that throws is logged
and the loop continues, so one failing container does not leave the others behind.
`MlsPendingBodyStore` and `MlsRendezvousStore` are per-message with a message-scoped lifecycle and
do not implement the interface; `MlsCiphertextCache` is keyed per message but does, because each
entry records the conversation it was sealed in.

The stores key by three different identifiers, which is why `MlsPerConversationState.Scope`
carries all of them:

| key | stores | why |
|---|---|---|
| canonical key | the transport's maps, `MlsResendLedger` | the transport's own key space |
| `(identity, groupId)` | `MlsRecordStore`, `MlsPendingQueueStore` | the canonical key cannot express one RCS group under two identities |
| conversation id | `MlsReupgradeStore`, the record store's alias rows | they belong to the app's conversation row |

`mConvAlias` is keyed by conversation id, so removing from it by canonical key would do nothing;
the teardown removes it by the scope's conversation id. The durable conversation-to-group alias
may have been written under the canonical key (`g:<rcs group id>`) while the teardown's scope
carries the app's conversation id, so `MlsRecordStore.removeAliasesFor` selects alias rows by the
group id they name (`MlsConversationRecord.aliasKeysToForget`), and falls back to both candidate
keys when no group id is known; rows of other identities and other groups are untouched. The
single-key `removeAlias` is only for a caller that knows the key `putGroup` used
(`rollBackAdoption`).

The scope is built before the engine drops the group, because the group id is only readable while
the group is loaded, and the conversation id must be resolved before `mKeyToConvId` is cleared.

When the host holds no group for an RCS group, `forget` still deletes the engine state at that
group id (for a group, the MLS group id is the RCS group id), which is exactly what a discarded
create leaves behind.

`forget` drops the app half only. The provider keeps its own peer-to-group mapping unless it is
dropped separately, and then the next send re-establishes by an era advance on the same group id
rather than starting at era 1.

## Sealing an application message

`encryptForSend` delegates to `MlsSealSend.encryptForSend`. In order:

1. Refuse if the send gate is closed (`MlsResendBudget.sendBlockedByGate`) or the era cannot be
   read (a guessed era is a known-bad AAD).
2. If mls-rs reports a cached by-reference proposal (`commitRequired`), commit it first
   (`MlsStateChangeGate.commitPendingProposals`). This runs above the conversation lock because it
   does an RPC.
3. Under the conversation lock: replay a cached ciphertext for the same message id if there is one
   (re-encrypting would consume another generation and put a second ciphertext for one id on the
   wire); otherwise peek the next generation (`MlsSession.nextAppGen`), stamp it into the body
   header (`MlsRecoveryPolicy.stampBodyGeneration`), and only then encrypt. The lock covers the
   lookup, the peek and the encrypt because they are check-then-act on ratchet state; without it
   two concurrent sends took the same generation and one ciphertext became unreadable.
4. After the unlock, on every exit path, dispatch a piggybacked self-key-update if the engine
   produced one (`MlsRekeyPolicy.dispatchPendingKeyUpdate`).

The body is RCC.16 framing (`RccMlsBody`): an 8-byte header (`00 01` version, `00 01` payload type
for text, a `uint32` counter), then an `mls_varint` length and a MIME section (`Content-Type`,
`Content-Disposition`, `Content-Length`, body); receivers route on the inner `Content-Type`. The
counter carries the message's MLS `sender_data.generation`, which peers cross-check against the
decrypted message. `RccMlsBody.frame` writes 0 there, and each transport that frames must stamp the
real value before encrypting; both `MlsSealSend` and `MlsCarrierTransport` do. Stamped in the other
order, the counter would be one behind the generation the message is sealed at, and a peer that
checks it decrypts only the first message of each epoch, which is worse than sending unframed.
`stampBodyGeneration` leaves a body that is not RCC.16-framed unchanged.

A piggybacked key update (`MlsTransportTypes.PendingKeyUpdate`) is applied locally by the engine
inside the encrypt, so the cached era and epoch follow the engine at once, under the lock; only the
provider round trip is deferred until the lock is released. If the server refuses, the snapshot the
engine returned with the commit and the cached values are restored together. Deferring the cache
update as well would leave later sends sealing against a stale epoch.

The envelope's message id must equal the id bound into the AAD, since peers drop a mismatch. A send
with an app message id caches its ciphertext (`MlsCiphertextCache`) before returning, so a racing
retry replays it, and the framed body is kept (`MlsPendingBodyStore`) under the wire id before the
send, so a resend after a peer's failure report can re-encrypt it at the repaired epoch.

The resend chain uses only the sealed verbs, `sendMlsCiphertext` and `sendGroupMlsCiphertext`:
`sendBlockedByGate` and the `end_mls` status check decide whether and at which generation to seal,
never whether to send in the clear (`MlsUngatedOutboundSendGuardTest`).

### Commits

`MlsCommitSend.commitAndSend` builds a membership or rekey commit and sends it. The commit's
control message id is minted before the commit and is used both as the AAD's `message_id` and as
the request's id, because the AAD binds the id the request carries; the server refuses a commit
whose AAD it cannot parse. The base epoch authenticator it sends is the pre-commit one, which is
what the server validates the commit's base against. The engine applies a commit locally before the
server answers, so the flow exports a group snapshot first and restores it on a refusal; without
the rollback a refused commit leaves the device an epoch ahead of the server, and every later send
fails.

## Receiving from the provider

**Control bundles.** `MlsServerBundle.recognise` reads a relayed control payload whose provider
envelope has already been stripped: it takes the located arm of the server's message, never an
outer wrapper around it. Fed a wrapper, the arm walk reports the wrapper's field number as the arm
and `isAccepted()` never fires; the content read (variant and message list) is structural and is
unaffected by extra levels.

| arm (`MlsServerMessage.Arm`) | handling |
|---|---|
| 4, `ACCEPTED` | returns before any descent; carries no MLS message and nothing is forwarded |
| 1, `RAW` | the MLS message is taken verbatim, bare or `mls_varint`-prefixed; if it does not parse, the descent below runs instead of dropping it |
| 2, `SERVER_COMMIT_BUNDLE`; 3, `MLS_GROUP_INFO` | descend over exact protobuf lengths, bounded at depth 6 and 512 containers |

The descent returns every MLS message in bundle order, deduplicated; order matters because a
metadata commit's key delivery is at the post-commit epoch, so the commit comes first. A bundle
carrying a Welcome is forwarded whole, because the ratchet tree rides alongside it, and an
unrecognised blob is forwarded whole. Arm 3 is identified by value (its 32-byte `f4` is the epoch
authenticator); arm 2 is assigned by elimination, so it alone gets an evidence line
(`armEvidenceLine`), carrying the content variant and the `ARM_UNPROVEN_MARKER` token that
`MlsInvariantScan` looks for: a Welcome or commit corroborates the elimination, a GroupInfo
contradicts it.

**Resolving the conversation.** `MlsGroupState.resolveInbound` probes whether the engine can load
the group (`groupLoads`) before answering from the host store, and again before adopting the
provider's group id, since a readable era is not loadable state. A group with no loadable state
resolves to null, so `applyInboundControl` can reach `joinFromWelcome` and repair the conversation.

**Application messages.** After the decrypt, the body is read with `RccMlsBody`. A frameless payload
whose header declares a non-text payload type is dropped as `UNKNOWN_SECRET_PAYLOAD` rather than
rendered, because the text arm is the only inbound shape that sends a delivered receipt. An MLS
reaction is an ordinary `text/plain` application message whose reaction headers are inside the
encrypted CPIM (`n1.Reference-ID` and `n1.Reference-Type: +Reaction` or `-Reaction` in the
`http://www.gsma.com` namespace, alongside the `urn:rcs:message:reactions:` namespace; prefixes are
the sender's choice, so headers are keyed by the resolved URI). `RcsCallbackRouter` detects it with
`RccCpimReaction` after the decrypt and before the content-type switch, routes it to
`onIncomingReaction`, and sends a delivered receipt itself, since a reaction does not pass through
`onIncomingMessage`. `RccCpimReaction.parse` looks for an `NS:` declaration within the first 64
bytes; a payload without one there is not treated as a reaction.

## Threads

* `MlsOffThread` is the seam for running work off the caller's thread; `setOffThreadForTest`
  replaces it (not a constructor parameter, since the transport is a per-process singleton).
* `MlsRetryWorker.enqueue` (through `scheduleRetry`) schedules a re-drive of one conversation.
* `mSweepInFlight` keeps at most one group sweep running.

## Testing code behind the port

`FakeShellPort` answers only the members a test stubs, and throws naming the member for any other
call, so a test cannot pass by touching an effect it did not declare. A throw inside an engine
method's own `try`/`catch` is swallowed and logged, so an unstubbed member usually shows up as a
missing effect rather than a stack trace; stub first when a test sees nothing happen. Engine code
calls another engine method directly rather than through the port, so stubbing the port member of
the same name has no effect there; stub what the real callee reads instead.

Host tests: `messaging-mls-engine-host-tests`. See [../testing.md](../testing.md).

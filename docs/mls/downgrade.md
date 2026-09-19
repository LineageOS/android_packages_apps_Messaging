<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# MLS downgrade, revive and re-upgrade

How a conversation leaves MLS (`end_mls`), how it comes back (revive in place, revive in a new
era, re-upgrade), and the Phoenix-mode escape for a group whose ordinary downgrade cannot land.

Related: [health-and-recovery.md](health-and-recovery.md) (the state table and predicates used
here), [group-lifecycle.md](group-lifecycle.md) (era advance), [budgets.md](budgets.md) (era quota),
[metadata.md](metadata.md#the-continuity-token) (the continuity token).

## The model

A downgrade has two halves and both must happen:

| Half | Owner | What changes |
|---|---|---|
| Engine | `MlsDowngradeFlow`, the health machine | The group gains the `end_mls` GroupContext extension (`0xF002`, extension data `"end_mls"`) by a commit, and the persisted status moves through `EndMlsRequested (8)`, `OngoingEndMls (9)` to `DoneEndMls (10)`. |
| Host | `MlsDowngradeFlow.downgradeLocally`, `E2eeSchemeGate.downgradeMls` | The conversation's MLS encryption bit is cleared, and the re-upgrade bookkeeping is stamped. |

The engine decides *how* and owns the group state; the host decides *whether* and *why* and owns
the app's conversation state. Once `0xF002` is present RCC.16 §9.1.1 forbids encrypted sends, and
the server rejects further commits unless they remove the tag. `endMls` is also the realistic way
to leave a 1:1, since a 1:1 self-remove can never be committed by anyone else.

**Nothing may re-encrypt a group that someone deliberately downgraded or left** (logged as
`INVARIANT ED-1`). It is enforced twice over: by the extension, which travels with every commit and
GroupInfo, and by the persisted status (`MlsHealthPredicates.isDowngraded`: no new pending
operation, no key refresh, no self-heal), so the status still protects the group while a downgrade
is requested but not yet committed. The credential Self-Update and the floor rebuild decline a
downgraded group, and the maintenance pass and the floor rebuild decline a group we left
([credentials.md](credentials.md)). `0xF002` is removed only by the two revive shapes below.

### Status or extension

Operational guards read the persisted status (`MlsHealthPredicates.hasEndMls`, through
`MlsRecordState.hasEndMlsStatus`: states 10, 11, 12, 15, 16), not the `0xF002` extension; the two
disagree in five states. The extension is the right thing to read in three places: when building a
commit, when reconciling against a GroupInfo fetched from the server, and in the first clause of the
revive precondition. `MlsStateChangeGate.groupInfoHasEndMls` decodes a fetched GroupInfo's
extension list through the engine, which catches a remote downgrade we never applied locally; a
GroupInfo it cannot decode is logged and answers `false`.

## Downgrade reasons

`MlsDowngradeReason` answers "why is the app leaving MLS". It has 21 values; every number is
explicit and none is derived from declaration order (the wire numbers and the telemetry buckets are
each one-to-one).

| Column | Field | Meaning |
|---|---|---|
| A | `wire` | Reason number written into the MLS context's trace record. Not an RCS wire field. |
| v | `metric` | Telemetry bucket; `null` means no metric is emitted (only `UNKNOWN_DOWNGRADE_REASON`). |
| w | `expected` | `true`: do not plan to come back. `false`: stamp an unexpected downgrade (drives re-upgrade loop 1). |
| x | `postprocessResult` | There is an end-MLS commit result worth post-processing. |
| y | `zinniaAlreadyEnded` | The engine or a peer already ended MLS: generate no commit. |
| z | `suppressEagerLocalDowngrade` | Keep the app's MLS bit until the commit lands. |
| B | `zinniaCode` | Reason code for the engine request; 0 means none. |

Two table invariants are checked for every value when the class loads, so a bad edit fails the
first test that touches the class: `y && x` is forbidden, and `B != 0` implies `!y && !z`.

Selected rows:

| Reason | w | y | z | Raised by |
|---|---|---|---|---|
| `RECEIVED_END_MLS_COMMIT` | false | true | true | A peer's commit carrying `end_mls`, or a server GroupInfo carrying it during self-heal. |
| `UNRECOVERABLE_SERVER_FAILURE` | false | false | false | The user's "Turn off encryption" on the stall notification. |
| `ERA_ADVANCEMENT_QUOTA_REACHED` | false | false | false | Giving up on an exhausted repair (below). |
| `EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE` | true | true | false | Phoenix mode, first step. |
| `DEBUG_MENU` | false | false | false | `endMls(..., resume=false)` with no reason. |
| `ZINNIA_REQUESTED_END_MLS`, `ZINNIA_END_MLS_ONGOING`, `CANNOT_HEAL_DURING_END_MLS`, `SERVER_GROUP_STATE_NOT_FOUND` | | | | The engine-to-host funnel (below). |

`RECEIVED_END_MLS_COMMIT` is unexpected, so a peer's downgrade is eligible for re-upgrade; Phoenix's
own reason is expected.

`zinniaReasonFor(contextCarriesEngineHealthStatus)` returns `B` only for app-originated downgrades
(`B != 0` and the context has not round-tripped an engine status): the gate means "do not tell the
engine what it just told you". All five engine-funnel reasons have `B == 0`, and the only caller
(`MlsDowngradeFlow.endMls`) passes `false`. `endMlsRequestWireReason` maps the code to `B - 2` and
throws on `B == 0` (ask `zinniaReasonFor` first) and on `B == 1`, which names no request reason.
The app builds the end-MLS commit directly, so the computed reason is logged but not sent.

## `endMls`: the downgrade flow

`MlsDowngradeFlow.endMls(shell, log, rcsGroupId, peerE164, resume, reason)` with `resume == false`.
The order is the specification:

1. **Guard ladder** (`MlsDowngradeLadder.evaluate`), in fixed order:

   | Guard | Test | Verdict |
   |---|---|---|
   | 1 | `hasEndMls(status)`: 10, 11, 12, 15, 16 | `ALREADY_DONE`: a no-op, also for a repeated downgrade; returns the current era, not -1. |
   | 2 | An `END_MLS` or `PHOENIX_MODE` operation holds the pending slot | `ALREADY_PENDING` |
   | 3 | `isPhoenixOngoing(status)`: 16, 17 | `PHOENIX_ONGOING`: Phoenix will carry the downgrade itself. |

   A state tripping several guards reports the earliest, so "already done" wins over "already
   pending" (one means stop, the other means retry later). Guard 1 tests the status, so 8 and 9 fall
   through to guard 2. Guard 2 lets the *same* operation resume: an `END_MLS` slot whose stored
   context equals this request's reason, and whose attempts are not exhausted, is not "somebody
   else's"; a Phoenix run never adopts an `END_MLS` slot. Pending operations of other kinds do not
   block. Two rules are deliberately not guards: `isDowngraded` (true from `EndMlsRequested (8)`
   onwards, so the downgrade would refuse itself), and the pending-registry rule "downgraded, not
   starting a new pending operation" (a downgrade is itself a new pending operation).
2. **`EndMlsRequested`**, then claim the pending slot as `END_MLS`.
3. **Drain before downgrading.** `MlsInboundHold.drainDeferredControl` processes everything parked
   while the group can still read it. A message decrypted after the downgrade would be lost.
4. **Host half first** (`downgradeLocally`, see below), before any commit, so a downgrade that
   returns early still leaves the conversation eligible to come back.
5. **Column y.** If the reason says MLS is already ended, move to `DoneEndMls` and stop; a second
   commit would be a downgrade of a downgraded group.
6. **Era gap.** If the server holds a different era from ours, no commit we can sign will be
   accepted. The downgrade is applied **locally only**: `DoneEndMls` on this device, and the server
   and peer are not told. That is acceptable only because the conversation is already diverged,
   which is why it is gated on an observed gap; a refused era look keeps the gate shut and falls
   through to the ordinary commit.
7. **Build the commit** (`commitEndMls(..., resume=false)`), with a group snapshot taken first.
   A build failure restores the snapshot and marks the slot for retry.
8. **`OngoingEndMls`**, only after the commit exists. Moving first would record an in-flight
   downgrade with no commit behind it.
9. **Send** through `applyMlsControl`. On refusal: restore the snapshot, retry the slot, and route
   the verdict to `MlsSelfHeal.onControlRefused`. Only when the slot's retries are exhausted does
   the status move to `CannotHealDuringEndMls` (the wedge): that state forbids a fresh downgrade and
   a Phoenix run, so entering it on the first refusal would strand a conversation a retry could fix.
10. **Accepted:** record the new era and epoch authenticator, reset the send counters (the commit
    carries an UpdatePath, so our leaf rotates), move to `DoneEndMls`, clear the slot.

`endMls(rcsGroupId, peerE164, resume)` without a reason uses `DEBUG_MENU` for a downgrade.

### Other ways a downgrade arrives

| Path | Where | Result |
|---|---|---|
| A: inbound commit carries `end_mls` | `MlsCommitApplication.applyInboundControl` | `DoneEndMls` (`EndMlsAppliedByRemoteClient`) where the state table allows it, else `CannotHealDuringEndMls` (for example mid epoch advance: `OngoingEpochAdvancement` has no edge to `DoneEndMls`). Then the host downgrade with `RECEIVED_END_MLS_COMMIT`, recorded as unexpected, and no `end_mls` commit of our own. |
| B: server GroupInfo carries `end_mls` during self-heal | `MlsSelfHeal.selfHealInner` | `DoneEndMls` and host downgrade. Closes the case where a peer downgraded while we were offline and no message will ever arrive. |
| C: server `mismatched-rcs-group-state` on a downgraded group | `MlsFtdEscalation.onPeerReportedFailure` | Absorbed: no heal, nothing counted. |
| Cached `end_mls` proposal (`0xF001` in the proposal code-point space) | `MlsStateChangeGate.onInboundProposal`, then `commitPendingProposals` | Claims the slot as `END_MLS` and commits through `endMls`, so the proposal and the extension land in one commit. |

While an `END_MLS` (or `COMMIT_PENDING_PROPOSALS`) commit is owed, an unhonourable inbound proposal
is not dropped (`MlsStateChangeGate.dropUnhonourableProposal`): dropping clears the whole mls-rs
proposal cache, which would lose the proposal the owed commit exists to honour, while the owed
commit sweeps the unhonourable one harmlessly.

### The host half: `downgradeLocally`

`MlsDowngradeFlow.downgradeLocally(shell, log, key, reason, eager)`:

1. No app conversation for the key: log and return (engine state still moves).
2. **Idempotence.** If the conversation's MLS bit is already clear, absorb the repeat. The path
   legitimately runs twice per downgrade (the host decides, then the engine status reaches
   `DoneEndMls` and the funnel fires the same decision back). Absorbing matters because a second
   pass would re-stamp the re-upgrade timestamp and shift the whole backoff schedule. An
   *unreadable* bit does not absorb: clearing a clear bit is harmless, skipping a needed downgrade
   is not.
3. **Stamp first** (`MlsReupgradeStore.markDowngrade`), before anything that can return.
4. If `eager` (`MlsDowngradeReason.eagerFor`: not column z), clear the MLS bit
   (`E2eeSchemeGate.downgradeMls`, which leaves the other encryption plane's bit intact) and set
   `mls_eagerly_downgraded`. Otherwise the bit stays set until the commit lands, and the commit
   has landed once the status is one `MlsSealSend` refuses (`hasEndMlsStatus`): a call made then
   clears the bit and sets the mark whatever `eager` says. That call is the funnel's on arriving at
   `DoneEndMls`, or the receiving side's `RECEIVED_END_MLS_COMMIT` right after it. Kept, the bit
   would send every later message to a seal that refuses the conversation.

Storage failures in the re-upgrade bookkeeping degrade to "nothing recorded" rather than throwing:
failing to record a downgrade costs a re-upgrade, failing to perform one costs confidentiality.

Leaving a group clears the MLS bit directly rather than through this path: leaving is not a
downgrade, has no reason code and must not schedule a re-upgrade
([group-lifecycle.md](group-lifecycle.md#leaving)).

### The engine-to-host funnel

`MlsDowngradeLadder.downgradeFromEngineStatus` runs after every real health move. Most statuses map
to nothing (recovery in progress is not a reason to leave MLS).
`MlsDowngradeReason.forEngineHealthStatus` maps exactly four:

| Status | Reason |
|---|---|
| `EndMlsRequested (8)` | `ZINNIA_REQUESTED_END_MLS` |
| `DoneEndMls (10)` | `ZINNIA_END_MLS_ONGOING` |
| `CannotHealDuringEndMls (15)` | `CANNOT_HEAL_DURING_END_MLS` |
| `PhoenixModeRequested (17)` | `EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE` |

`OngoingPhoenixMode (16)` maps to nothing because Phoenix already downgraded at request time. The
funnel checks the reason against `ENGINE_FUNNEL_WHITELIST` (the four above plus
`SERVER_GROUP_STATE_NOT_FOUND`) first, through `requireEngineFunnelReason`, **and throws** on
anything else: an app-originated reason on the engine channel is a routing bug and must not quietly
turn into a downgrade. For the wedge (`CannotHealDuringEndMls`) the app always downgrades locally;
leaving the app offering encryption the engine has given up on would be a split brain.

## Phoenix mode

`MlsDowngradeFlow.initiatePhoenixMode` is the way out when the ordinary `end_mls` commit cannot be
made to land. Instead of committing into the current group, it advances the era with
`MlsAdvanceEraKind.PHOENIX_DOWNGRADE`, and the new era is **born downgraded** (its GroupContext
carries `0xF002`). It needs no key packages (`MlsAdvancePurpose.PHOENIX_MODE`) and no cooperation
from the existing tree. Reaching it means the ordinary downgrade failed, so it logs at error level.

1. If Phoenix is already requested or ongoing, stop (guard 3's message).
2. **Downgrade the app first**, unconditionally, with
   `EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE` (w = true: we chose this; y = true: the era
   advance carries `end_mls`).
3. `PhoenixModeRequested (17)`, claim the slot as `PHOENIX_MODE`, then `OngoingPhoenixMode (16)`
   (`StartedPhoenixMode`, the only inbound edge of 16).
4. Era advance. Success lands in `DoneEndMls` (not `Healthy`); failure lands in
   `CannotHealDuringEndMls` and the slot is marked for retry. A Phoenix era can also end `Healthy`
   through the table's `RevivedInNewEra` edge.

## Advance kinds and `0xF002`

`MlsAdvanceEraKind` is a mode, not a guard: every era advance states what it does to `end_mls`.

| Kind | Mode byte | `0xF002` |
|---|---|---|
| `NORMAL` | 0 | Carried unchanged. The advance clones the carried extension list and edits it, so nothing is dropped by omission; dropping `end_mls` on a plain advance would silently erase a peer's downgrade and re-encrypt a deliberately plaintext conversation. |
| `REVIVAL` | 1 | Removed. |
| `PHOENIX_DOWNGRADE` | 2 | Installed. A carry-only extension list could never originate `end_mls`. |

An unknown mode byte (above 2) decodes as `NORMAL`, so an advance never drops a downgrade. After the
engine builds the new era, `MlsEraAdvance` checks `0xF002` against the kind
(`MlsAdvanceEraKind.endMlsAfter`) and rolls back unsent on a mismatch: an engine that decoded a
revival or a Phoenix as `NORMAL` must not reach the server. The
values are persisted in a pending operation and cross the FFI boundary. Exactly two sites may
remove `0xF002`: the `REVIVAL` era advance and the in-place revive commit. A blanket "refuse to
advance a group carrying `end_mls`" would be wrong: it would make revival and Phoenix unreachable,
though the state table enters the revival advance from twelve states, `DoneEndMls`,
`OngoingEndMls` and `CannotHealDuringEndMls` among them. A `NORMAL` advance on a downgraded group is
allowed and logged: the conversation stays plaintext across the new era.

## Revive: coming back from `end_mls`

`endMls(..., resume=true, ...)` runs `MlsDowngradeFlow.reviveInner`.

### Precondition (`MlsHealthPredicates.canRevive`)

Evaluated in this order:

1. `(0xF002 present || status == CannotHealDuringEndMls)`, then
2. `(isDowngraded(status) || status == OngoingReviveMls)`.

Revive is the one decision that consults the extension, because it must work on a group whose
status and context disagree; that disagreement is what it repairs. Failing clause 1 logs "MLS is
already active, no need to revive" (plus an engine-bug diagnostic when the status is nevertheless
downgraded); failing clause 2 returns silently.

### Two shapes, chosen by the table

| Shape | Path | When |
|---|---|---|
| In place | `10 -> 11 -> 13` (or on to 2 / 7) | `isLegal(status, OngoingReviveMls)`. A commit that drops `0xF002`. |
| New era | `{0,1,2,4,6,7,9,10,11,13,14,15} -> 12 -> 13` or `-> 10` | Otherwise. The only way out of `CannotHealDuringEndMls`, which ends `Healthy` or back at `DoneEndMls`. |

The choice asks the table rather than enumerating states, so there is one specification.

**In place** (invariant: *transition before remove*). The move to `OngoingReviveMls` must succeed
before anything touches `0xF002`; a refused transition leaves the tag in place. Then claim the slot
as `REVIVE_MLS`, snapshot, build `commitEndMls(..., resume=true)` (which removes the tag), send, and
on acceptance move to `Healthy`, reset the send counters (the commit carries an UpdatePath, so our
leaf rotates) and clear the re-upgrade bookkeeping. Failures restore the snapshot, retry the slot,
and route refusals to `onControlRefused`.

**New era** (`reviveInNewEra`). Move to `OngoingEraAdvancementForRevive`, claim the slot as
`ERA_ADVANCEMENT_FOR_REVIVE`, and era-advance with `MlsAdvanceEraKind.REVIVAL`, whose new
GroupContext is the old one minus `end_mls` (`mayRemoveEndMls` is true for `REVIVAL` only). Success
is `RevivedInNewEra` to `Healthy`; failure is `RevivalFailed` back to `DoneEndMls`, a state every
path already handles. There is no partial outcome.

## Re-upgrade

A downgrade that the app did not choose should not be permanent. Three columns on the
`conversations` table hold the bookkeeping, read and written whole through `MlsReupgradeStore` and
modelled by `MlsReupgradeState` (immutable):

| Column | Field | Written when |
|---|---|---|
| `mls_last_unexpected_downgrade` | `lastUnexpectedDowngradeMs` (0 = never) | A downgrade whose reason has `expected == false`. |
| `mls_reupgrade_attempts` | `attemptCount` | Incremented before each re-upgrade attempt; never capped. |
| `mls_eagerly_downgraded` | `eagerlyDowngraded` | The downgrade flow cleared the app's MLS bit. |

### Loop 1: slow, with backoff

For "a peer downgraded us and we want to be encrypted again eventually".

* **Reset then stamp** (`markUnexpectedDowngrade`): consult the stability window against the
  *previous* timestamp and reset the counter if the conversation was stable for longer than the
  window, then write the new timestamp. The reverse order makes the window always read as zero and
  the backoff never grows.
* **Backoff** = `(long)(int)(1 << min(attempts, maxShift)) * base` seconds, counted from the last
  downgrade, not from the last attempt. The 32-bit intermediate is intentional and matches
  interoperating clients when the configured shift exceeds 30.
* **Increment before attempt** (`MlsReupgradeStore.claimReupgradeAttempt`): the counter is written
  before the attempt so an attempt that crashes still backs off. Inside the backoff period the
  attempt is a hard skip.
* There is no absolute attempt limit: the loop backs off forever but never gives up.

| Setting | Default | Key |
|---|---|---|
| Base (`MlsConfig.reupgradeBackoffBaseS`) | 60 s | `debug.rcs.mls_reupgrade_base_s` |
| Max shift (`reupgradeBackoffMaxShift`) | 8 (plateau about 4 h 16 min) | `debug.rcs.mls_reupgrade_max_shift` |
| Stability window (`reupgradeStabilityWindowS`) | 24 h | `debug.rcs.mls_reupgrade_stability_s` |

Other clients receive these three values from their server rather than shipping them, so the
defaults (`MlsReupgradeState.DEF_*`) are the app's own choices, configurable so they can be
corrected without a code change.

### Loop 2: fast, no counter

`MlsDowngradeFlow.maybeFastReupgrade` runs after every real health move. If the conversation was
eagerly downgraded and the persisted status is now exactly `Healthy (13)`, the reason for the
downgrade has gone, and the bookkeeping is cleared (`eligibleForFastReupgrade`). Other clients'
group-status query reports a coarse status in which `PhoenixModeRequested (17)` reads as healthy;
this app has no coarse projection and compares the persisted status with `Healthy` exactly, so a
Phoenix run never re-upgrades itself here. Both loops can fire for the same conversation.

### How the MLS bit comes back

The conversation's encryption bits (`EncryptionProtocolBits`, `conversations.encryption_protocol`)
accumulate: `E2eeSchemeGate` recomputes eligibility per conversation and a newly eligible plane's
bit latches on (MLS eligibility requires an MLS group id). A deliberate downgrade clears the MLS bit
with `withMlsCleared` and sets `mls_eagerly_downgraded`. The peer still advertises MLS afterwards,
so eligibility also requires that mark to be clear (`MlsProvisioning.mlsDowngraded`): otherwise the
next send would set the bit again and reach an MLS seal that refuses a downgraded conversation,
failing the message. The bit comes back on the first send after a re-upgrade (either loop, or a
revive) clears the bookkeeping. A successful revive clears it so a later downgrade computes its
backoff from a fresh anchor. Forgetting a conversation also clears it
(`MlsReupgradeStore.forgetConversation`).

## Giving up: downgrade on an exhausted repair budget

`MlsSelfHeal.terminateRepairIfQuotaBound` runs when the self-heal budget is exhausted.

1. Release the conversation's sealed ciphertexts from the cache (the permanent-failure arm of the
   retention policy). The pending message bodies are a separate store and are kept.
2. If `MlsConfig.downgradeOnRepairExhausted` is off
   (`debug.rcs.mls_downgrade_on_repair_exhausted=0`; default on), stop: the conversation stays
   encrypted and the stall choice is the user's. Default on because otherwise the group stalls and
   delivers nothing; other clients map the same reason to a requested `end_mls`.
3. Require the **era quota** to be established (`MlsEraAdvance.eraQuotaBound`), not merely the
   self-heal budget spent. The downgrade predicate is the quota; downgrading on budget alone would
   drop conversations out of encryption whose fault is elsewhere.
4. Try a resync by external commit first (`MlsExternalCommitResync.resyncViaExternalCommit`); it
   uses a different allowance (RCC.16 §11.2.2) and does not need an era to move. Success cancels the
   downgrade.
5. Otherwise `endMls` with `ERA_ADVANCEMENT_QUOTA_REACHED`. That reason is an unexpected downgrade,
   so re-upgrade is the ordinary path back.

Elsewhere, an era-quota failure during FTD escalation and the RCC.16 §10.3 FTD chain cap do **not**
downgrade automatically; RCC.16's plaintext fallback there is left to the user.

## The user's choice

When recovery has run out of moves the stall notification offers "Try again" and "Turn off
encryption" (see
[health-and-recovery.md](health-and-recovery.md#when-recovery-stops-the-stall-alert)). "Turn off
encryption" calls `endMls` with `UNRECOVERABLE_SERVER_FAILURE`: the pending queue is drained first,
and because the reason is not expected the re-upgrade loop can later bring the conversation back.

## Downgrades a peer would make

Two signals can make a peer take a conversation out of MLS. The app avoids sending the first and
does not act on the second.

**A plaintext reconciliation receipt.** When we hold no group for a conversation that sent us MLS,
a plaintext delivery IMDN could reconcile the message. Other clients read it as a downgrade signal:
they demote the destination one rung per receipt (MLS, then the provider-encrypted scheme, then
none) and do not re-establish. So it is off by default
(`MlsConfig.plaintextReconcileImdn`, `debug.rcs.mls_plaintext_reconcile_imdn`): the message stays
undelivered, the conversation stays on MLS, and re-establishment is driven from our side
([health-and-recovery.md](health-and-recovery.md)).

**A continuity-token mismatch** (RCC.16 §8.3.1.2). `MlsContinuityPolicy.evaluate` compares our
token with the commitment in a fetched GroupInfo, and the membership three ways (the RCS chat's
participants, the fetched group's members, our local members):

| Condition | `Action` |
|---|---|
| No local token, RCS participants all in the fetched group | `REQUEST_TOKEN` (absence is recoverable when membership is consistent, RCC.16 §8.3.1.3) |
| No local token, otherwise | `MINT_TOKEN` |
| (token mismatch and the fetched group has members we lack) or (the RCS chat names members the fetched group lacks) | `MINT_TOKEN_AND_MAY_DOWNGRADE` |
| Token mismatch alone | `MINT_TOKEN` (a re-mint or a lost token is benign) |
| Otherwise | `CARRY_TOKEN` |

A fetched GroupInfo with no token counts as a mismatch, not as agreement. The downgrade is a MAY,
and `downgradeIsPermitted` takes it only once a server GroupInfo has been seen carrying `0xF011`:
if no peer mints commitments, every fetched GroupInfo mismatches and every group would be
downgraded, and to the user a downgrade on this signal is indistinguishable from encryption failing.
No client has been seen publishing `0xF011` (and this app does not, see
[metadata.md](metadata.md#the-continuity-token)); an absent `0xF011` is a statement about clients,
not about the server. `downgradeReason` gives the RCC.16 §7.11.2.2 reason: 6 (token not received)
with no local token, else 5 (token mismatch). The policy is implemented and host-tested but has no
production caller.

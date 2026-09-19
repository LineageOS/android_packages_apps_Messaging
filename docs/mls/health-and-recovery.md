<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# MLS health and recovery

How the app knows whether an MLS conversation is healthy, and what it does when it is not: the
persisted health state machine, the per-conversation recovery state, the comparison against the
server, self-heal, rebuild and external-commit resync, the bounded drive loop, and the RCC.16 §10
failure-to-decrypt (FTD) and resend ladder.

Related: [downgrade.md](downgrade.md) (the end-MLS family of states),
[credentials.md](credentials.md) (credential refusals and the floor rebuild),
[budgets.md](budgets.md) (fetch, claim, era and rebuild budgets that recovery spends),
[group-lifecycle.md](group-lifecycle.md) (commit rollback, joining, era advance, persistence),
[transport-and-port.md](transport-and-port.md) (where these classes sit).

## Three different notions of "health"

They are separate on purpose and must not be merged.

| Axis | Type | Persisted | Question it answers |
|---|---|---|---|
| Health status | `int` wire value from `MlsHealthStates`, in `MlsConversationRecord.healthStatus` | yes | Where is this conversation in its lifecycle? |
| Self-heal kind | `MlsSelfHealKind`, in `MlsConversationRecord.selfHealKind` | yes | Which kind of repair is running (epoch advance, era advance, end-MLS, era advance for Phoenix)? |
| Live comparison | `MlsTransportTypes.Health`, `MlsWelcomeAdmission.ServerState` | no | Does our state match the server's right now? (costs a server look-up) |

The status alone cannot tell an ordinary era advance from a Phoenix one; that is what the kind is
for. The live comparison is a question about the world and is recomputed on demand (see
[Comparing with the server](#comparing-with-the-server)); the other two are lifecycle state that
survives a restart. `MlsSelfHealKind.wire` values are persisted and never renumbered; an unknown
value decodes as `NONE` so a record written by a newer build still loads.

## The health state machine

### States

`MlsHealthStates` defines 16 states on wire values 0..17. Values 3 and 5 are unused gaps in the
wire enum, not missing states; `STATE_SLOTS` is 18 and a decoder must not renumber.

| Value | State | Meaning |
|---|---|---|
| 0 | `Unknown` | No status recorded. |
| 1 | `EpochAdvancementRequested` | A self-heal was asked for. |
| 2 | `OngoingEpochAdvancement` | Self-heal is replaying missed commits. |
| 4 | `OngoingEraAdvancement` | An era advance (group re-creation) is in flight. |
| 6 | `SelfHealFailed` | A self-heal was killed; inbound is read inline again. |
| 7 | `EraAdvancementRequested` | Escalated to an era advance, not yet started. |
| 8 | `EndMlsRequested` | A downgrade was requested. |
| 9 | `OngoingEndMls` | The end-MLS commit is built and in flight. |
| 10 | `DoneEndMls` | The group is downgraded. |
| 11 | `OngoingReviveMls` | An in-place revive is in flight. |
| 12 | `OngoingEraAdvancementForRevive` | A revive by new era is in flight. |
| 13 | `Healthy` | Normal operation. |
| 14 | `Initializing` | The group is being created. |
| 15 | `CannotHealDuringEndMls` | Wedged: downgrade could not complete and the group cannot heal. |
| 16 | `OngoingPhoenixMode` | An era advance whose new era is born downgraded is in flight. |
| 17 | `PhoenixModeRequested` | Phoenix mode was requested. |

### Transitions

`MlsHealthStates` holds the complete adjacency table: 127 legal `(from, to)` pairs. Anything not
in the table is illegal. `edge(from, to)` returns the edge name or `null`; a `null` means the
caller has a bug, not a condition to route around. The table matches the state and edge vocabulary
used by interoperating clients, so the names in logs and telemetry line up.

`MlsHealthEdge` names the 32 edges. Each carries two numbers that are not derivable from each
other: `ordinalValue` (position in the name table) and `telemetryValue` (the metric value). For
example `RequestedPhoenixMode` is ordinal 15 and telemetry 26. Telemetry must report
`telemetryValue`, never `Enum.ordinal()`.

Points a reviewer should know:

* `StartedEraAdvancement` is the edge into both `OngoingEraAdvancement` and
  `EraAdvancementRequested`, so edges are looked up by pair (`MlsHealthEdge.forPair`), never by
  name.
* `OngoingEraAdvancement -> OngoingEraAdvancement` is in the table but unreachable
  (`isUnreachable`), because the machine answers `cur == to` first. It is kept so the table can be
  checked against its per-state out-degree.
* `INVALID_TRANSITION` is never produced by the table. It exists only for an "allow illegal"
  escape hatch that is compiled off at every call site (`allowIllegal` is always `false`), because
  that hatch keeps the illegal move and would be a corruption vector if it were switchable.
* There is no `EraAdvancementFailed` edge.
* `SelfHealKilled` (into `SelfHealFailed`) exists from 1, 2, 4, 7 and 15 only. It has two
  producers: `MlsRecoveryPolicy.killSelfHeal` and a claim that supersedes a held self-heal slot
  ([below](#the-pending-operation-slot-mlspendingoperation)).

### `MlsHealthMachine`

Pure Java with no clock read on the transition path; the current group `Moment` is passed in.
`decide(from, to, allowIllegal)` touches nothing; `transition(record, to, now, allowIllegal)`
returns an `Applied` with the decision and the new record. The guard order is the contract:

1. `cur == to` is a no-op that still runs the shared tail: no edge, no telemetry, no
   `on_transition`, but `on_enter` runs.
2. Look up the edge.
3. Illegal: reject; nothing changes.
4. Telemetry is emitted by the caller before the write (`Decision.emitsTelemetry()`).
5. The mutation.
6. `on_transition`, then `on_enter`, both after the mutation.

`on_transition` has two blocks:

* Block A (recovery landing). Arriving at `Healthy` from one of `{1, 2, 4, 6, 7, 12}` stamps
  `recoveredAt` and clears `storedStatusRequest`. `DoneEndMls (10)` is deliberately not in the set
  even though `DoneEndMls -> Healthy` is legal: `recoveredAt` is the comparand for the pending
  queue's resent-FTD admission (`MlsPendingQueue.Admission.RESENT_FTD`), and a spurious stamp would
  admit fewer resends.
* Block B. Entering `{2, 4, 12, 16}` clears `storedStatusRequest`.

`on_enter` for `Healthy` stamps `lastHealthyMoment`, including on the `Healthy -> Healthy` no-op.
No hook touches the self-heal budget or the pending operation's attempt count.

Seeding and the one bypass:

* `newState(..., seed)` accepts only `Unknown`, `Initializing` or `Healthy`. A storage "not found"
  seeds `Initializing`; a storage read error seeds `Healthy`, because a record we cannot read is
  not a group we have never seen and must not be sent down the recovery ladder.
* `forceCannotHealDuringEndMls` is the single table-bypassing write, legal only from
  `OngoingEraAdvancement (4)` and `EraAdvancementRequested (7)`; it throws from anywhere else.
  Those two pairs are intentionally absent from the table so the bypass stays visible.
  `MlsRecoveryPolicy.markCannotHealDuringEndMls` is its only caller; from other states it logs and
  records nothing.

### Applying a transition: `MlsRecordState.moveHealth`

All production transitions go through `moveHealth(shell, log, key, to, cause)`:

1. Takes the conversation lock and reads the record.
2. Runs `MlsHealthMachine.transition`. An illegal pair is logged as refused and returns `null`.
3. For a real move: counts `MlsMetrics.STATE_TRANSITION` with the edge's `telemetryValue`, emits a
   typed `MlsStateTransition` record, and logs the transition line, all before the write. A no-op
   logs the "already in state" and "skipping write" pair instead.
4. Writes the record (skipped when nothing changed); the "wrote group state" line appears only
   after a real write.
5. After releasing the lock, and only if the state actually moved: runs the engine-to-host
   downgrade funnel (`MlsDowngradeLadder.downgradeFromEngineStatus`) and the fast re-upgrade check
   (`MlsDowngradeFlow.maybeFastReupgrade`). They run outside the lock because both touch the
   database; they run only on a real move so arriving at `DoneEndMls` twice downgrades once.

### Status predicates

`MlsHealthPredicates` holds the membership tests every guard uses. They are questions about a
status number; none of them reads the group.

| Predicate | States | Used for |
|---|---|---|
| `hasEndMls` | 10, 11, 12, 15, 16 | Downgrade entry guard 1 ("already done"); absorbing `mismatched-rcs-group-state` on a downgraded group. |
| `isDowngraded` | 8, 9, 10, 11, 12, 15, 16, 17 | The hard stops: no new pending operation, no key refresh, no self-heal. |
| `isPhoenixOngoing` | 16, 17 | Downgrade entry guard 3. |
| `isHealing` | 1, 2, 4, 6, 7, 12 | "A heal is in progress". |
| `buffersInbound` | 1, 2, 4, 7, 12, 14, 16, 17 (mask `0x35096`) | Pending-queue gate G1: park inbound before decrypt. |
| `canRevive(status, ext)` | see [downgrade.md](downgrade.md) | The revive precondition. |

Things that look like simplifications and are not:

* `hasEndMls` excludes 8 and 9 (a requested or in-flight downgrade) and includes the two revive
  states.
* State 12 is in both `isDowngraded` and `isHealing`.
* `buffersInbound` is not `!isDowngraded`: 12, 16 and 17 are downgraded and buffer; 8, 9, 10, 11
  and 15 are downgraded and do not.

`end_mls` exists in two places: the `0xF002` GroupContext extension (cryptographically bound, read
when building commits, when reconciling against a fetched server GroupInfo, and by the revive
precondition) and the persisted status (read by every operational guard). Keying guards off the
extension alone would lose the protection that the status gives while a downgrade is requested
but not yet committed. The one guard that deliberately reads both is self-heal's end-MLS guard.

## Per-conversation recovery state

### The conversation record

`MlsConversationRecord` is the durable per-conversation state; where it is stored and how it is
keyed is in [group-lifecycle.md](group-lifecycle.md#persistence). The fields recovery depends on:

| Field | What it holds |
|---|---|
| `healthStatus`, `selfHealKind` | The two persisted health axes above. |
| `selfHealBudget` | `retryCount`, `firstAttemptAtMs`, `healedSinceClear`; [the self-heal budget](#the-self-heal-budget). |
| `pendingOperation` | The one in-flight operation, or `null`; [below](#the-pending-operation-slot-mlspendingoperation). |
| `recoveredAt`, `lastHealthyMoment`, `storedStatusRequest` | Written by the state machine's hooks. |
| `ftdResendCounts` | FTDs we have sent per message id, for the RCC.16 §10.3 cap. |
| `epochAuthenticators`, `epochAuthEra` | Epoch to authenticator, for one era only. |
| `membershipHistory` | Era to epoch to members; spans eras. |
| `sendsThisEpoch`, `sendsSinceLeafRotation` | The application generation and the rotation counter ([rekeying](#send-side-safeguards)). |
| `selfLeftAtMs` | Set when we leave; absent means not left. |

* `ftdResendCounts` is durable so a restart cannot reset the RCC.16 §10.3 cap and re-report a
  permanently undecryptable message forever. It is bounded at `MAX_FTD_RESEND_ENTRIES` (32) with
  first-seen eviction, not LRU: a message nearing its cap keeps being touched and would otherwise be
  the one that survives. The count of FTDs peers report to us is a different number and stays in
  memory, so unrelated failures weeks apart do not add up to an era advance.
* `epochAuthenticators` holds exactly one era's chain: `putEpochAuthenticator` clears the map when
  the era changes, since a prior era's authenticators describe a different group, and trims the
  oldest entries for retention. The decoder uses `restoreEpochAuthenticator`, which is not
  era-aware, because the era field is written after the entries. A map with no recorded era
  (`ERA_UNKNOWN`) keeps its entries on load and is cleared on the next write, so the current
  authenticator is still available until then; a 1:1 seal without an Epoch-Authenticator header
  would be refused by peers. Eras are unsigned `u32`; `ERA_UNKNOWN` is `-1` only, so eras above
  2^31 are not misread as unknown (it collides with exactly one reachable era, `0xFFFFFFFF`).
* `sendsSinceLeafRotation` is seeded from `sendsThisEpoch` when absent. `selfLeftAtMs` survives the
  record overlay on every write and is cleared only by `clearSelfLeftOnRejoin` on an accepted
  Welcome.

Encoding: `[version = 2][varint payload length][payload]`, where the payload is a sequence of
`(field, wireType, value)`. The length frame makes a truncation that lands on a field boundary, or
trailing bytes, decode to `null` rather than to a well-formed shorter record; it is not a checksum.
Unknown fields inside the frame are skipped, so a newer record loads on an older build, and unknown
enum numbers degrade to `NONE`. Version-1 unframed blobs are still read, and are rewritten framed
on the next write. Field 18 is reserved.

### The pending-operation slot (`MlsPendingOperation`)

One in-flight operation per conversation, held as a nullable field of the record. The "already
pending" guard is that one field, it survives a restart, and its `attemptCount` is persisted, so a
crash loop cannot reset it. `attemptCount` is separate from the self-heal budget; conflating them
would let a transition reset the retry limiter.

| Kind (wire) | Operation |
|---|---|
| `EPOCH_ADVANCEMENT` (1) | In-place self-heal. |
| `ERA_ADVANCEMENT` (2) | New era, new MLS group under the same RCS group id. |
| `END_MLS` (3), `REVIVE_MLS` (4), `ERA_ADVANCEMENT_FOR_REVIVE` (5), `PHOENIX_MODE` (6) | The downgrade family ([downgrade.md](downgrade.md)). |
| `COMMIT_PENDING_PROPOSALS` (7) | Commit proposals cached by reference (a peer's self-remove). |
| `GROUP_METADATA` (8) | A subject or icon commit. |

Kind and `Origin` wire numbers are persisted and never renumbered.

Staleness is moment-based: the operation is bound to the `(era, epoch)` at which it was created,
and is stale once the group has moved. An operation with no binding is not stale. Independently,
a slot is spent after `PENDING_OP_MAX_ATTEMPTS` (5) attempts or `PENDING_OP_MAX_AGE_MS` (10 min)
since the first; only the stale arm emits the stale-pending trace line.

The remedy when an operation dies depends on where it came from, not on what it was (`Origin`).
Only `PROCESS_MESSAGE_API` (raised while processing inbound: nobody is waiting and nothing else
retries it) escalates, to `EpochAdvancementRequested`. `EXPLICIT_API`, `USER_ACTION`, `OTHER` and
`UNKNOWN` (records written before the field existed) do not, so one failed user action cannot
churn era advances. A retry keeps the origin and the start time.

Verbs:

| Verb | Meaning |
|---|---|
| `claimPendingOp` | Take the slot (below). |
| `clearPendingOp` | Success: release. |
| `retryPendingOp` | Worth another go: bump the attempt count and keep the slot; exhausting the attempts converts to `failPendingOp`. |
| `failPendingOp` | Terminal: release and apply the origin's remedy. |
| `healAlreadyPending` | A repeat heal while a self-heal is held, neither stale nor spent, is a quiet no-op rather than a failure, because callers escalate on a failed heal. |
| `releaseOwnHealSlotForEscalation` | Release only an `EPOCH_ADVANCEMENT` slot, so an escalating heal can claim it for an era advance. |
| `claimAdvance` / `releaseAdvance` | The era-advance slot, context `eraAdvance:<why>`, so a held slot names its taker and a resume matches only the same advance. |

There is deliberately no abort verb. `dropPendingProposals` acts on the RFC 9420 proposal cache,
not on this slot.

`claimPendingOp` decides, under the conversation lock:

* A missing record is logged as such, not as a held slot.
* A held slot this process never claimed (`pendingOpsClaimedThisProcess`, empty at process start)
  belongs to a dead process and is reclaimed at once rather than after the 10-minute cap. MLS state
  lives in one process, so this needs no schema change. A key is added to the set only after the
  claim is persisted.
* The same kind with the same context is the operation resuming its own slot (a counted attempt).
* `PHOENIX_MODE` may supersede a held `END_MLS` (the one directed escalation pair: Phoenix mode
  exists for a failed outgoing `end_mls` commit, RCC.16 §7.11.2.2). Nothing else supersedes a live
  holder.
* A stale or spent holder is superseded. Superseding is the held operation dying, so its origin
  decides whether to escalate. A superseded self-heal is recorded as `SelfHealFailed` (not
  terminal: a later trigger can start a fresh heal) and the FTD reports held behind it are flushed.
  Both health moves happen outside the lock.

### The pending queue (RCC.16 §10.8)

`MlsPendingQueue` is an inbound deferral buffer: it holds only messages it can prove will become
processable, so it has no capacity, eviction, TTL or far-future cutoff, and admission is silent (no
counter, and it never triggers recovery by itself). It is persisted per `(identity, group)` by
`MlsPendingQueueStore` as one blob rewritten wholesale, because a partial write could make a
half-written bucket look drained. A blob that does not decode degrades to an empty queue with a
warning; the cost is that peers resend. It lives in the Java engine module rather than in Rust
because G1 and G3 read the health status, which lives in the record.

Admission (`admit`, pure) has three gates, in order:

| Gate | Condition |
|---|---|
| G1 | The group is mid-transition (`buffersInbound`). Checked before any decrypt, because processing consumes the message and advances ratchets even when it fails. |
| G2 | Processing failed with `OutOfOrderCommit` (29) or `is_from_future`, and the message moment is strictly after the group's (era-major, unsigned). At or behind is never queued. |
| G3 | A resent-message FTD (client reason 6) whose moment is `>=` `recoveredAt` (not the live moment, since a resend arrives after the group has committed past the recovery; an unset `recoveredAt` passes), not failing on the same application message, not downgraded. No live caller passes this reason yet. |

Everything else goes to the error or FTD path, never to the queue. The one failure that emits
nothing at all (`silentDrop`) is engine error `ExpectedSelfHealToBeOngoing` (52) without a
same-application-message failure; mls-rs has no producer of it, so the rule is kept in one place
but unreachable.

`store` validates in this order, returning the engine-error ordinal: variant tag 1 or 2
(`PublicMessage`, `PrivateMessage`; a Welcome, GroupInfo or KeyPackage could never drain from a
per-epoch bucket) (25), group id present (26), group id matches (28), epoch present (27). Group-id
match is checked before epoch presence, which is not the numeric order. Then it deduplicates by
message id within the `(era, epoch)` bucket only; a duplicate returns OK without inserting, and the
same id at another moment is a different delivery attempt.

Each entry records its `Plane`: whether it came through the control door or the application door.
Both are `PrivateMessage`, so the wire cannot tell them apart, and replaying an application message
through the control door decrypts and discards it. Codec version 2 appends the plane; version-1
entries decode as `CONTROL`. Version 3 appends the sender's E.164, which the ciphertext does not
name; earlier entries decode with none.

A drain (`take`) removes exactly `pending[current_era][current_epoch]`, in insertion order. It is
never a sweep of everything at or below the current moment: that would re-fail and re-buffer
messages on every intermediate commit. The exact-key take is what makes the absence of a bound
affordable; a bound combined with a sweep manufactures failures. The bucket is written back before
replaying, and the scheduling marker is stamped before the replay, so nothing the replay reaches
schedules more. Each entry is replayed as its recorded sender, not as the member whose commit
triggered the drain: the application door refuses a signer that is not the named sender.

Park and drain are ordered by the conversation lock, and the exact-key take makes that ordering
matter. Application messages and commits arrive on different threads, and the park decision reads
the group outside the lock. If a commit applies and drains the message's moment between that read
and the store, the stored entry would wait for a drain that never comes (seen on a device after an
outage delivered a remove commit and the next message in one pull). So `park` reads the group again
under the lock and does not store an entry the group has already reached (`OVERTAKEN`): a
from-the-future message is replayed at once through the door the drain would have used, and a
busy-group control is processed inline. The application decision also compares against the moment
the failed decrypt saw (recorded under the lock), so a commit that lands before the decision does
not send a readable message to the FTD path. The drain takes the queue under the same lock, since
its Welcome and downgrade callers do not hold it.

Entries in an era before the group's are pruned (`takeSupersededEras`), because they can never
decrypt and the exact-key take never reaches them. The drain prunes before it takes, and our own era
advance, which drains nothing, prunes when the funnel returns a new era
(`MlsInboundHold.pruneSupersededEras`). The queue is cleared when the conversation is forgotten (the
store's `remove`, part of the teardown) and when a Welcome is joined
([group-lifecycle.md](group-lifecycle.md#joinfromwelcome)): everything parked was framed against
the branch the Welcome replaces.

A pruned or cleared application entry is reported (`MlsInboundHold.reportDropped`): it goes into the
conversation's §7.7.2.2 report queue under its recorded sender and the queue is flushed, so the
§10.2 hold during a heal and the §10.3 attempt cap apply as for any other report. An entry with no
recorded sender is reported only in a 1:1, where the peer is the sender. A control entry is dropped
without a report. An entry already reported when it was parked (an era gap) can be reported twice;
the attempt cap bounds that.

Two classifications decide what else happens to a parked message:

* `awaitedMomentIsReachable`: an epoch gap we can close ourselves by replay, but an era gap needs a
  Welcome only a peer or the server can send. An era-crossing park is kept and a failure report is
  also sent, so the sender does not believe it delivered.
* `isFromASupersededEra`: a message from an era the group has left can never be decrypted. An era
  advance builds a new group under the same id and purges the prior era's secrets, and mls-rs keys
  its epoch archive by `(group_id, epoch_id)` only. It takes the FTD path, and the sender resends at
  the current era.

A downgraded group does not buffer strictly-future messages (the downgraded test, which differs
from the G1 mask on five states). Buffering counts fresh arrivals only, not replays.

## Comparing with the server

### Health verdicts

`MlsAheadChainCheck.detectHealth` reads the server's `(era, epoch)` and, where it is decisive, the
server's epoch authenticator, and returns a `MlsTransportTypes.Health`. An era gap dominates an
epoch gap: a new era is reached by a Welcome, not a commit.

| Verdict | When |
|---|---|
| `IN_SYNC` | Same era and epoch, and the authenticator matches. |
| `IN_SYNC_UNVERIFIED` | Same era and epoch, but our fetch ledger refused the authenticator look. |
| `DIVERGED` | Same era and epoch, different authenticator; or above the server's epoch on a different chain (below). |
| `AHEAD` | Our epoch is above the server's and no fork was shown. |
| `LOWER_EPOCH_CHAIN_UNKNOWN` | Our epoch is below the server's. |
| `ERA_GAP` | Our era differs from the server's. |
| `REJOIN` | We hold no group; the server has one. |
| `NOT_FOUND` | We hold no group and the server returned none. |
| `UNKNOWN` | Our era is unreadable, or the server's answer is unusable while we hold a group. |
| `LOOK_REFUSED` | Our own fetch ledger refused the era/epoch look, so nothing was asked. |

* Equal era and epoch is a position, not an identity; the epoch authenticator decides. Only a
  definite `DIFFERS` demotes to `DIVERGED`.
* `IN_SYNC_UNVERIFIED` is the second-stage sibling of `LOOK_REFUSED`: it does not demote, and it
  does not clear a stall alert, either in `refreshStallNotification` or in `reconcileAction`.
  `detectHealth` passes only the verdict on, which is safe because each stage has its own value.
* `LOOK_REFUSED` is never reported as `NOT_FOUND`, because `NOT_FOUND` routes the conversation to a
  "needs a Welcome from a peer" remedy. It is also kept apart from `UNKNOWN` (asked, answer
  unusable): same patience, different diagnosis.
* `LOWER_EPOCH_CHAIN_UNKNOWN` is not "behind": nothing reachable says whether we are on the
  server's chain. The server serves only its current anchor and no commit backfill, the
  authenticator look takes no epoch argument, and authenticators at different epochs always
  differ; reading the server's ratchet tree cannot settle it either (leaf presence proves nothing).
  Every consumer treats it as behind; classifying it as a fork would rebuild groups that were a
  commit away from fine.

`healthAgainstServer` also returns a `ServerComparison`, which carries the identity answer behind
the verdict. `serverConfirmedOurs()` is true only when the authenticator was actually compared and
matched, and it gates writing the server's roster as our baseline membership.

### Above the server's epoch: the chain test

Being ahead is normal (a commit we hold that the server has not yet taken) and also what a fork
above the server's epoch looks like. `aheadOrForked` separates them with our retained authenticator
for the server's `(era, epoch)`:

* `blockedBefore` refuses to test, before the server look is paid for, when the server era is
  unreadable (`SERVER_ERA_UNKNOWN`; keying the lookup on our own era would assume what the era gate
  failed to establish), or when the retained map is unstamped, stamped with another era, or holds
  nothing for the server's epoch (`NO_RETAINED_ANCHOR`). The server-era check comes first.
* `decide` then gives `LOOK_REFUSED` (the ledger refused; outranks an absent answer),
  `SERVER_ANCHOR_ABSENT`, `SAME_CHAIN` (our retained bytes match) or `DIFFERENT_CHAIN`.
* Only `DIFFERENT_CHAIN` is evidence of a fork, and only it demotes `AHEAD` to `DIVERGED`, which is
  what maintenance's fork abort keys on. Every not-knowing state keeps `AHEAD` and logs why.
  Without the chain test a fork above the server's epoch would pass maintenance, count the server's
  roster into the add arm, possibly spend an era advance, and write the server's roster as history
  onto a group that is not the server's.
* A `SAME_CHAIN` verdict leaves the identity unset: the server holds an earlier roster, which must
  not be written as our baseline.

A GroupInfo fetched at our own anchor is not always the server's state. The group-info fetch is
anchored at our era and authenticator, so a GroupInfo at our own epoch may be our anchor handed
back, signed by us. `MlsAnchorProvenance` classifies it (`AHEAD_SERVER_STATE`,
`SAME_EPOCH_MAY_BE_OUR_ANCHOR`, `BEHIND_UNEXPLAINED`, `UNREADABLE`), and only `AHEAD_SERVER_STATE`
makes the signer attributable.

The read-only diagnostic probe (`MlsTransportDiagnostics.probeAnchor`) uses its own ration
(`HEALTH_PROBE`, outside the shared ceiling); a refused look is `AnchorProbe.REFUSED_BY_LEDGER`,
distinct from `NO_LOCAL_STATE`.

### Quarantine after a failed rollback

An era advance builds the group locally before asking the server. If the server refuses and the
snapshot restore also fails, the engine sits at an era no other member has, and every inbound parks
at an unreachable moment with no FTD and no error. `quarantineIfAheadOfServer` reads the server's
era on its own ration (`QUARANTINE_CHECK`, since the audited operation has already spent the
caller's) and, if the engine is ahead, forgets the group. Dropping it is correct here and restores
the fresh-join path for an inbound Welcome. An unreadable comparison leaves the group alone.

### What reconcile does with each verdict

`MlsConversationRebuild.reconcileAction` is the drive loop's reconcile pass:

| Verdict | Action | Result |
|---|---|---|
| `IN_SYNC` | Clear the stall alert. | `NO_OP` |
| `IN_SYNC_UNVERIFIED` | Nothing; the alert is kept (identity untested). | `NO_OP` |
| `AHEAD` | Rebuild. We hold a commit the server did not take, or a rollback failed, and a member's external commit is refused on this transport. | `SUCCESS`, or `FAIL_RETRY` / `NOT_IN_THIS_DRIVE` |
| `DIVERGED` | Rebuild. | as above |
| `ERA_GAP`, `REJOIN`, `NOT_FOUND` | Rebuild, to land above the server's era; no commit crosses an era. | as above |
| `LOWER_EPOCH_CHAIN_UNKNOWN` | No work. Often a commit in flight; the decrypt-failure path escalates if it persists. | `FAIL_RETRY` / `NOT_IN_THIS_DRIVE` |
| `LOOK_REFUSED`, `UNKNOWN` | Wait a cooldown. | `PENDING` / `AFTER_A_COOLDOWN` |

`LOWER_EPOCH_CHAIN_UNKNOWN` must not be re-driven inside the same drive: passes over unchanged state
only spend fetches until the server throttles. `UNKNOWN` shares its `case` with `default:` so an
unforeseen verdict lands on the conservative arm. A failed rebuild that
`MlsRebuildOutcome.needsAPerson()` raises the stall alert
([below](#when-recovery-stops-the-stall-alert)).

The proactive maintenance pass only computes a membership delta; it never revives or creates, and
recovery is never gated on a membership delta, because a diverged conversation typically has a
correct roster.

## Commits whose outcome is silent

The engine applies a commit while building it
([group-lifecycle.md](group-lifecycle.md#rollback-is-part-of-every-commit)), so the question after
sending is whether the server took it. `MlsCommitApplication` partitions the provider's verdict:

| Outcome | Verdicts | Meaning |
|---|---|---|
| `APPLIED` | `VERDICT_OK` | Accepted. The response carries no other field, so an OK is an acceptance. |
| `NOT_APPLIED` | `VERDICT_ERA_GAP`, `VERDICT_EXTERNAL_COMMIT_REFUSED`, `VERDICT_GROUP_ID_CHANGED`, `VERDICT_REJECTED` | The server spoke and refused on the merits. The only outcome that authorises a rollback. |
| `SILENT` | `VERDICT_TRANSPORT_FAILED`, `VERDICT_NOT_REGISTERED`, `VERDICT_NOT_IN_GROUP`, anything unrecognised | The outcome does not say. |

A timeout, cancellation, dropped connection or dead binding is `SILENT`, which is ordinary network
weather. `VERDICT_NOT_IN_GROUP` is silent because it can arrive after the commit applied (a
concurrent removal).

A silent commit is kept, not discarded. The two errors are not symmetric: keeping a commit the
server refused leaves us `AHEAD`, which reconcile rebuilds (costly, automatic, terminating);
discarding one it applied leaves us `LOWER_EPOCH_CHAIN_UNKNOWN` below an epoch we signed, which
nothing repairs, because missed commits are not buffered.

`keepUnacknowledgedCommit` reads the server's era and epoch (`COMMIT_OUTCOME_CHECK`, its own ration
outside the shared ceiling) and `reconcile` classifies:

| Reconciliation | Disposition |
|---|---|
| `SERVER_LACKS_IT` (behind our post-commit epoch, or another era) | Roll back. |
| `SERVER_AT_OUR_EPOCH`, authenticator `MATCHES` | Keep and report success. |
| `SERVER_AT_OUR_EPOCH`, anything else | Keep, report unresolved. A `DIFFERS` still keeps: rolling back would leave us below a forked server with no repair, while keeping reads as `DIVERGED` and is rebuilt. |
| `SERVER_PAST_OUR_EPOCH` | Keep, report unresolved: another member may have committed on top, and nothing shows ours is in that chain. |
| `INDISTINGUISHABLE` (the commit did not move our epoch, e.g. a leave) or `UNREADABLE` | Keep, report unresolved. |

The authenticator look is spent only at equality, where it is decisive. Only the first
unacknowledged commit is held (`ConvState.unacknowledgedCommitEpoch`); any later silent commit
rolls back onto it, so an outage leaves us at most one epoch ahead. The bound is in memory; a
restart permits a second hold, and the reconcile each hold enqueues still bounds it.

"Keep but report unresolved" matters: `commitAndSend` then returns a negative era, which lets the
caller's conservative arms run (RCC.16 §9.5.3's marker release, which repairs a wedged
certificate, is gated on it; see [credentials.md](credentials.md)). On a proven success the verdict
is rewritten to OK and the stored control detail cleared, so that arm is not told the server never
looked. A held commit also schedules a retry, and `MlsRetryWorker` re-drives reconcile rather than
re-sending, so holding a commit and scheduling a retry are the same move.

On the inbound side, `applyInboundControl` opens the session before resolving the conversation:
resolution needs our own number, and a commit dropped because nothing resolved is gone for good
(the server does not backfill). Results are status-tagged, and rejections are classified from the
epoch on the wire for the log.

## Self-heal

`MlsSelfHeal.selfHeal(cfg, shell, log, rcsGroupId, peerE164)` repairs a conversation whose state has
fallen behind or away from the server's. It is reached from decrypt failures, divergence verdicts
on control messages, server-originated negative receipts, maintenance and the pre-check before a
metadata change.

### Return values

| Value | Meaning |
|---|---|
| `> 0` | Healed: commits replayed (count) or era advanced (`1`). |
| `0` | Nothing to do or declined (no-op heal, already current, left group, downgraded group, slot held). |
| `-1` | Looked, and could not repair. Counts toward the non-convergence run. |
| `MlsRecoveryPolicy.HEAL_LOOK_UNAVAILABLE` (`-2`) | The server look-up was refused by our own fetch ledger or unreadable, so nothing was learned. Does not count toward the run. |

`-2` is still negative, so every `healed < 0` check treats it as "not healed". The distinction
matters because three consecutive non-convergences rebuild the conversation, which claims a
KeyPackage per member; a throttled look must not spend that.

### Order of operations

The order is part of the contract.

1. Pending guard and slot claim. If a heal is already pending, return 0. Otherwise claim the
   single pending-operation slot as `EPOCH_ADVANCEMENT`. The slot is released in a `finally` on
   every exit. One pending operation per group is the throttle; there is no cooldown or timer.
2. Group resolution, before any budget is spent. No group returns -1.
3. Left group. A group we left (`MlsRecordState.weLeft`) is not healed: healing can era-advance,
   and an era advance re-creates the group with us in it. Checked before the budget.
4. Live yield re-look. If this device is yielding an era advance to another member,
   `MlsAdvancerElection.relookLiveYield` re-evaluates from local state and returns without
   charging the budget ([free looks](#free-looks-while-yielding)).
5. `EpochAdvancementRequested` is recorded, before the budget charge, so a heal refused for budget
   still leaves evidence it was asked for.
6. Budget charge (`chargeSelfHealBudget`), before any comparison. A heal that turns out to be a
   no-op still costs an attempt; the budget counts the work of checking.
7. End-MLS guard, under the lock: the local group carries `0xF002`, or the status is
   `isDowngraded`. Either declines the heal and calls `markCannotHealDuringEndMls`. Healing a
   downgraded group would era-advance and re-create it without `end_mls`, silently re-encrypting a
   conversation a peer made plaintext.
8. Fetch, outside the lock: `lookMissedCommits` returns either a commit list or a packed
   `{GroupInfo, ratchet_tree, roster...}`. A refused or unreadable look returns
   `HEAL_LOOK_UNAVAILABLE`.
9. Server `end_mls` short-circuit. If the pack is a GroupInfo pack (sniffed by the MLS
   `wire_format` 4 header) and the server GroupInfo carries `end_mls`, move to `DoneEndMls` and run
   the host downgrade with `RECEIVED_END_MLS_COMMIT`. This is how a downgrade made while we were
   offline reaches us. For a commit list the check cannot be evaluated and the log says so.
10. Moments match. `MlsWelcomeAdmission.serverStateCheck`: `MATCHES` moves straight to
    `Healthy` without passing through an `Ongoing*` state (a no-op heal must not look like an
    interrupted one); `REFUSED_BY_LEDGER` returns `HEAL_LOOK_UNAVAILABLE` rather than declaring
    health on a question that was never asked.
11. `OngoingEpochAdvancement`, then replay under the lock. Engine status 1 is applied, 9 is a
    commit we already hold (skipped and counted), anything else stops the replay.
12. Outcome of the replay.
    * All applied: reset the budget, move to `Healthy`, return the count.
    * Some applied, then a failure: keep the progress and reset the budget (it is forward
      progress), but do not move to `Healthy`; fall through to the era advance. A partial replay
      is not a heal.
    * Everything was already held and nothing failed: move to `Healthy` without escalating.
13. Era advance, outside the lock. The server roster comes from the pack. The
    [advancer election](#who-advances-the-era) decides whether this device advances or yields. To
    advance, the heal hands its own slot to the escalation
    (`MlsPendingOperation.releaseOwnHealSlotForEscalation`), moves to `OngoingEraAdvancement`, and
    calls `eraAdvance` with the server GroupInfo so the new era inherits the committed subject and
    icon. It re-creates the group rather than using an external commit, because the fetched
    GroupInfo lacks the `external_pub` extension.
14. Verify against the server. An accepted create is not proof the era moved: the server era is
    read back, and a mismatch kills the heal (`SelfHealFailed`) and returns -1. A refused
    verification look returns `HEAL_LOOK_UNAVAILABLE` without claiming or killing the heal.

`eraAdvance` snapshots the group before mutating and restores it on every failure path, so a failed
advance leaves the group where it was.

### The self-heal budget

`MlsConversationRecord.SelfHealBudget` holds `retryCount`, `firstAttemptAtMs` and
`healedSinceClear`. It is persisted so a crash loop cannot buy attempts.

| Setting | Default | Key |
|---|---|---|
| Attempts per window (`MlsConfig.selfHealRetryLimit`) | 5 | `debug.rcs.mls_self_heal_retries` |
| Window (`MlsConfig.selfHealWindowMs`) | 24 h (86400 s) | `debug.rcs.mls_self_heal_window_s` |

* The window rolls: when it has elapsed, a new window starts (`rolled()`). Exhaustion is only
  "count reached within the current window". Reading the window as a deadline would make any
  conversation that ever attempted a heal permanently unhealable a day later, because only forward
  progress resets the budget and that needs a successful heal.
* The budget is reset only by forward progress (`MlsRecoveryPolicy.noteForwardProgress`): a
  message processed, an era advanced and verified, a group operation succeeded, a reconcile that
  converged, or an accepted external-commit resync. Never by a transition hook (the limit would
  never be reached) and never at startup (a crash loop would buy attempts).
* The user's "Try again" on the stall notification resets it explicitly
  (`MlsRecoveryPolicy.resetSelfHealBudget`).
* There is one exhaustion reason, the retry limit.

On exhaustion `escalateExhaustedSelfHeal` moves to `EraAdvancementRequested` (which parks inbound),
offers the stall choice, and calls `terminateRepairIfQuotaBound` (see
[downgrade.md](downgrade.md#giving-up-downgrade-on-an-exhausted-repair-budget)), which tries an
[external-commit resync](#external-commit-resync) before the end-MLS terminal. From `Unknown` the
escalation is illegal and the log says the record should not be there. `EraAdvancementRequested`
is written by exactly two escalations, this one and the server's `epoch-advancement-quota-reached`;
both log that the conversation has stopped receiving.

### Free looks while yielding

`MlsSelfHealPass` keeps two facts explicit:

* `Kind.FREE_LOOK` charges 0 attempts, `Kind.CHARGED_ATTEMPT` charges 1. A re-look at a live yield
  is not a failed repair; it is a decision not to repair yet, with its own bound. If a look charged
  an attempt, the self-heal budget (5) would escalate before a member at rank 3 (budget 9 with the
  default base) could ever reach its takeover. Capping takeover budgets at the retry limit instead
  would give ranks equal budgets and reintroduce duels. `enclosingSelfHealAttempts` derives the
  budget argument for `MlsAdvancerElection.takeoverReachableWithin` from the charge table and the
  production config, and `reach` separates the three reasons a takeover can be unreachable (no
  decision, configured to yield forever, or the enclosing budget escalating first). Only the last
  is logged as a warning.
* `look(observationsHeld, budget)` is the single arithmetic both yield paths use. A path records
  the look exactly when it did not exhaust the budget, so the two paths reach the same verdict for
  the same pass.

`PARKS_BEFORE_UNSTICKING_A_HEAL` (3): three messages parked at an unchanged group moment means the
heal is not progressing. An epoch gap closes only while a heal runs, and a yielding heal advances
only when something looks, which parking does not do. Both G1 park gates
(`bufferInboundIfGroupLocked` on the control plane, `parkFutureCiphertext` on the application
plane) feed one counter (`noteParkAtUnchangedMoment`) and consult `MlsSelfHealPass.parksExhausted`.
At the bound they kill the heal and clear the run. The control plane then re-reads
`MlsPendingQueue.groupLocked`, because the kill leaves the buffering mask only from states with a
`SelfHealKilled` edge (1, 2, 4, 7). This is the automatic way out of `EraAdvancementRequested`; an
era advance deliberately is not. A commit at our current epoch is applied, not parked: applying it
is the repair.

### Killing a heal

`MlsRecoveryPolicy.killSelfHeal` moves a healing conversation to `SelfHealFailed`, and only if the
table has an edge to it (a `Healthy` group is left alone). The landing state matters: the `Ongoing*`
and `Requested` era states are in the G1 buffering mask, and the pending queue has no capacity, TTL
or eviction, so a status stuck there means receive nothing, forever. `SelfHealFailed` is outside
G1, so inbound is decrypted inline again, fails honestly, and reaches the FTD report that gets the
peer to re-Welcome us. It keeps edges back to `Healthy`, into an era advance and into the end-MLS
family.

### Enhanced self-heal (RCC.16 §10.1.2)

`MlsEnhancedSelfHeal` is a pure planner for the paginated Enhanced GroupInfo procedure: `COMPLETE`
when `latest_epoch_identifier` equals ours; `APPLY_CONTROL_MESSAGES` (the page's full list, in
sequential order) when a `paginated_epoch_identifier` is present; `REQUEST_NEXT_PAGE` until the
page boundary reaches the latest epoch; otherwise `FALL_BACK_TO_EXTERNAL_COMMIT`. A missing
`paginated_epoch_identifier` is a control signal, and any single failed message abandons the whole
attempt so the group is never left partially advanced. `shouldSendImdn` implements the IMDN rule:
no IMDN for a message applied only through this path, and a later MSRP copy of it is idempotent and
acknowledged. The production self-heal does not drive this planner yet.

RCC.16 §7.10.3 returns `committed_control_messages` in the enhanced GroupInfo response only when
the request's epoch identifier (`RccEpochIdentifier`: era, epoch and authenticator, always
together) is present, known to the server, and one the requester was a member throughout; a pull
without one gets an empty backfill list. `MlsGroupInfoGate` rejects a fetched GroupInfo with no
GroupInfo, no ratchet tree, no latest-epoch authenticator or no paginated epoch identifier before
the engine sees it. `preferredEpochIdentifier` prefers the server's `latest_epoch_identifier` and
falls back to the flat `(era, epoch)` pair only when it is absent. Peers key both components off
one presence bit, so a present but partial identifier is authoritative and yields 0 for an omitted
component rather than being patched from the flat pair.

## Who advances the era

An era advance by two members at once mints two eras and forks the group. `MlsAdvancerElection`
decides who advances and how long everyone else waits. Neither RFC 9420 nor RCC.16 defines a
tie-break.

* The designated advancer is the lowest E.164 in the roster, sorted (`order`) rather than taken
  in server pack order, so every member elects the same one. For a pair,
  `MlsRecoveryPolicy.weAreEraAdvancer` makes exactly one side advance.
* Presence never moves the designation; it only sets the wait. Liveness evidence is local and
  asymmetric, and two members who disagree about the designee would both advance.
* Unknown inputs fail open: an unknown own number, or a member not found in the roster it was
  given (rank -1), acts rather than waits. A duel is self-limiting; a conversation that never
  recovers is not.

Presence of the members ahead of us, from a per-conversation "heard from" ledger:

| Presence | Meaning | Presence looks |
|---|---|---|
| `UNKNOWN` | The ledger is empty (nobody heard here) | base |
| `NEVER_HEARD` | Heard from others, never from this member | 1 |
| `QUIET` | Heard before, not since this yield began (strict) | base |
| `ACTIVE` | Heard since this yield began; may be mid-advance | 2 × base |

The strongest presence among the members ahead decides. The takeover budget is
`presenceLooks + (rank - 1) × baseLooks`, strictly increasing in rank so no two members hold the
same budget; a takeover moves the group, which satisfies the next rank's yield, so fallback is
ordered. One look is one group-info fetch, and nothing reads a clock: a device asleep for a week
wakes with its budget intact. `baseLooks` is `MlsConfig.eraYieldLooks` (default 3,
`debug.rcs.mls_era_yield_looks`); `<= 0` means yield forever, which presence cannot override.

`noteHeardFrom` counts undecryptable inbound and peer failure reports on purpose: a member that is
behind is exactly the one whose traffic fails, and a designated advancer whose only traffic is
failure reports must get the patient arm rather than the `NEVER_HEARD` floor. This is presence, not
health; it must not clear `MlsPeerGuard`'s failure streak, which clears only on evidence the peer
processed something of ours. Other rejected presence signals: peer capability (a miss means
"nothing known"), the peer-guard failure count (a gone advancer scores zero like a healthy one) and
a KeyPackage probe (network under the conversation lock, and it consumes a KeyPackage). In a 1:1
the `NEVER_HEARD` arm cannot fire and the rank stagger is inert; telling "peer gone" from "not heard
yet" needs a third member.

The yield ends when the group moment moves (`MlsRecoveryPolicy.eraYieldSatisfied`: a new era, or a
new epoch in the same era; an unknown starting moment is not movement), checked before exhaustion.
The moment is read from the engine: the record's moment is written only on health transitions and
would stay frozen. `relookLiveYield` records one more look from local state (no fetch, no self-heal
charge) and returns -1 while still yielding, or `null` to run the full ladder when the group moved,
the election no longer says yield, or the look budget is reached (the takeover needs a fresh fetch).

## The drive loop

`MlsDriveLoop` is the bounded fixed point used whenever one MLS operation can produce more work
(a commit unblocks buffered controls, a refusal leads to a reconcile which may era-advance and send
a control that may be refused again).

It terminates on what a pass returned, never on a predicate re-read from the world. A
`MlsDriveLoop.Pass` receives the previous `MlsHostAction` and returns the next one; it has no way to
ask the loop about shared state. This is why the loop cannot spin while other threads change the
predicate it would otherwise test.

| Pass result | Loop behaviour |
|---|---|
| `NO_OP`, `SUCCESS`, `FAIL_NO_RETRY` | Stop. `NO_OP` is how convergence is detected. |
| `PENDING`, `FAIL_RETRY` with `Redrive.IMMEDIATE` | Run another pass. |
| Any other `Redrive` (declared) | Stop and keep the pass's status; the work is still owed. Not a fault. |
| Same `kind`, `status` and `MlsGroupSnapshot` as the previous pass (observed) | Stop and count `MlsMetrics.MAX_LOOP_REACHED`. A fault: a pass reported work without progress. |
| `DEFAULT_MAX_ITERATIONS` (10) reached | Return `FAIL_NO_RETRY` and count `MAX_LOOP_REACHED`. |
| `null` | Throws: silence is not convergence. |
| Exception | Propagates; converting it to a retry would hide the cause behind the cap. |

`MAX_LOOP_REACHED` is expected to stay at zero; any increment is a bug. The cap is a bug detector,
not a policy: real flows converge in two or three passes. Every reconcile pass costs at least one
server look-up, which is why no-progress passes are not re-driven, and the scarce resource is
bounded separately at the call site: `driveReconcileInner` allows `MlsFetchBudget.RECOVERY_LOOKS`
looks per drive and returns `FAIL_RETRY` with `Redrive.NOT_IN_THIS_DRIVE` when it is spent (see
[budgets.md](budgets.md)).

* `MlsGroupSnapshot` compares `(era, epoch)`, so an era advance's epoch restart is progress;
  `NONE` equals `NONE` (no evidence of movement).
* Unknown status wire values decode to `PENDING`. `PROPOSAL_CACHED` defaults to `PENDING`: a cached
  proposal blocks application messages until it is committed.
* Once a pending operation has failed in a drive, later `NO_OP`s are poisoned so the failure is not
  forgotten (never `SUCCESS`).
* `MlsResultBundle` demultiplexes one engine call's results by context id: an encrypt can return a
  piggybacked commit, and a result can belong to another operation, which must still be
  post-processed but not returned. `soleGroup` refuses two different groups, and the governing
  status is the worst in the list (`FAIL_NO_RETRY` beats `FAIL_RETRY`).
* A drive abandoned on lost connectivity returns `MlsDriveLoop.abandoned(...)`, a `FAIL_RETRY` with
  zero passes, so callers never handle `null`. The reconcile drive runs each pass through
  `MlsDriveLoop.livePass`: it clears the conversation's `lastControlVerdict`, and when the pass ends
  `FAIL_RETRY` after a provider call reported `NOT_REGISTERED` or `TRANSPORT_FAILED`
  (`MlsTransportDisposition.requireLive`), it throws `ConnectivityLost`, which `driveReconcile`
  turns into `abandoned`. Every create RPC records its verdict (`MlsDriveLoop.noteControlVerdict`),
  as `MlsCommitSend` does for a commit. The retry is scheduled the same way either way. The engine
  module is Android-free, so the loop returns its log lines (`Result.traceLines`,
  `Result.logLine()`) and the caller logs them.

The scheduling marker (`MlsSchedulingType`, `stampScheduling`, `maySchedule`) stops a retry flow
from scheduling another retry. It must be stamped before the results of the work can reach anything
that schedules more.

### Scheduled retries

`MlsRetryPolicy` decides and `MlsRetryWorker` schedules:

* One WorkManager chain named `mls-op-retry` (by worker type, not by conversation), enqueued with
  `APPEND_OR_REPLACE`. Work is deliberately not deduplicated, and `WorkItem` has no value equality:
  convergence comes from extra runs answering `NO_OP`.
* Backoff is linear, unlike the app's other exponential backoffs: three immediate steps of 30 s,
  then `DEFAULT_RETRY_DELAY_MS` (5 min) × (attempt − 3). The per-tranche delay is the initial
  delay of every tranche.
* Nested bounds, each terminating on its own: at most `MAX_ATTEMPTS_PER_ITEM` (1000) WorkManager
  attempts per item, `MAX_DRAIN_ITERATIONS` (100) per drain, and
  `MlsDriveLoop.DEFAULT_MAX_ITERATIONS` post-process passes. A run drives exactly one conversation
  (`MAX_ROWS_PER_BATCH` = 1, asserted at execution).
* A queued `FAIL_RETRY` schedules a follow-up. The inline path's `FAIL_RETRY` or `PENDING`
  (`NEED_MORE_RETRIES`) schedules nothing, because the inline trigger fires again and a second
  driver would stack.
* A drive that throws fails the item. The worker returns success unless the outcome is `FAILED`, so
  WorkManager's own backoff never compounds with this ladder.

### Refused control messages

`MlsSelfHeal.onControlRefused` routes only divergence verdicts (`VERDICT_ERA_GAP`,
`VERDICT_GROUP_ID_CHANGED`) into repair; construction errors and `mismatched-rcs-group-state` are
not fixed by reconciling. It claims the pending slot (so a persistently rejecting peer cannot drive
one repair per message), runs `MlsDriveLoop.driveReconcile`, and releases the slot by outcome: a
thrown drive fails the slot, a retryable status retries it and schedules `MlsRetryWorker`, a failure
fails it, and anything else clears it. When the drive stopped on an unreadable look
(`Redrive.AFTER_A_COOLDOWN`) the retry starts at `MlsFetchBudget.firstAttemptClearingTheCooldown()`
instead of attempt 1, so the 30 s rungs are not spent inside the look-up throttle window. `NO_OP`
and `SUCCESS` count as forward progress.

## Rebuild

`MlsConversationRebuild.rebuildConversation` is the heaviest automatic repair: forget the
conversation locally and at the provider, re-create it around the server's roster, and verify. It
is reached from reconcile, from the RCC.16 §10 non-convergence run, from an era advance whose create
did not move the server, and from the stall path. The ordering is the design; every check runs
before anything is destroyed.

1. Resolve the server roster, from one server-pack fetch that also supplies the carried GroupInfo.
   It must be resolved while state is intact: after the forget there is nothing to derive a roster
   from. An unresolvable group roster is `COULD_NOT_ATTEMPT`.
2. The join gate (`MlsMembership.allowedToJoinAll`), also while the conversation is intact.
3. Episode suppression (`MlsPeerGuard.claimRebuildEpisode`), before any allowance is charged, so a
   burst of triggers is one episode. It is durable, because a crash loop is a burst.
4. The server's era, charged to `REBUILD`. A refused look is `DEFERRED_BY_OUR_OWN_LEDGER` rather
   than a guess that would classify the rebuild as an uncharged first create.
5. `MlsReestablishPolicy.forksAtEraInitial`: a group rebuild with no carried GroupInfo would
   re-create at the initial era a group the server already holds. The post-create check compares
   era numbers only and would adopt the fork, so this refuses (`WOULD_FORK_AT_ERA_INITIAL`).
6. `MlsReestablishPolicy.reCreateWouldNotTake`: a group re-create under a context id the server
   already holds is not applied, and doing it would drop both halves of local state and leave the
   conversation waiting for a Welcome. Instead it asks a member for a re-Welcome
   (`requestReWelcome`) and returns `RECREATE_WOULD_NOT_TAKE`.
7. The era budget (`MlsPeerGuard.allowEraAdvance`), unless this recovery episode already paid it
   (`MlsRecreationEpisode.PriorCharge`, a required argument so a new arm cannot inherit a charge by
   omission).
8. The rebuild rate limiter (three per six hours per conversation), after the era budget, which
   refills faster and is the cheaper one to waste. Both charge on claim.
9. Forget at the provider (the 1:1 and group forms differ; a provider that did not drop its record
   aborts, since clearing only the engine half would reuse the group id through an era advance),
   then the engine forget.
10. Re-create: `establishGroup` with the carried GroupInfo for a group (the carry makes the new era
    the server's era + 1 and inherits the metadata extensions), `ensureReady` for a 1:1.
11. Verify by epoch authenticator, not by the create's return value.

`MlsRebuildOutcome` names each exit. `repaired()` and `needsAPerson()` are declared per constant
rather than derived, so a new constant must choose both.

| Outcome | Repaired | Needs a person | Meaning |
|---|---|---|---|
| `REPAIRED` | yes | no | Verified. Counts as forward progress. |
| `NOT_CONVERGED` | no | no | Ran, did not match; left to the self-heal ladder. |
| `REFUSED_BY_GUARD` | no | yes | A peer-protecting guard refused. |
| `RATE_LIMITED` | no | yes | The rebuild rate bound refused. |
| `SUPPRESSED_AS_ONE_EPISODE` | no | no | The 60 s episode guard; clears by itself. |
| `COULD_NOT_ATTEMPT` | no | no | No session, key or roster, or the provider kept its record. |
| `RAN_BUT_UNVERIFIED` | no | no | The verification look was refused by our ledger. |
| `DEFERRED_BY_OUR_OWN_LEDGER` | no | no | The era look was refused by our ledger. |
| `WOULD_FORK_AT_ERA_INITIAL` | no | no | Step 5. |
| `RECREATE_WOULD_NOT_TAKE` | no | no | Step 6. |

Only the two stops that "Try again" actually resets need a person. A refusal by our own fetch ledger
is never `REFUSED_BY_GUARD`, because an alert whose "Try again" does not reset the ledger would be
a false promise. `RAN_BUT_UNVERIFIED` is not repaired (an unverified repair is not claimed) and not
`NOT_CONVERGED` (a look we refused ourselves must not raise a stall alert).

## External-commit resync

`MlsExternalCommitResync.resyncViaExternalCommit` rejoins the server's current group from its
GroupInfo (RCC.16 §11.2.2), when our state cannot be reconciled by replay. Unlike a rebuild, which
creates a new group, it adopts the server's. It is tried from the stall path when we are behind
with the budget spent, and from `terminateRepairIfQuotaBound` before the end-MLS terminal.

Order:

1. Resolve the group.
2. Profile gate: `MlsRecoveryPolicy.externalCommitResyncApplies` is false for an existing member on
   a transport whose profile refuses `ExternalInit` from members
   (`RcsMlsTransportProfile.acceptsMemberExternalCommit`, RFC 9420 §12.4.3.2); a non-member always
   may. This is a client policy, checked before the budget so a refusing transport cannot spend the
   allowance discovering it. `forcePastProfile` is an operator override for re-testing the profile,
   never set from the product.
3. Claim the external-commit allowance (`xcBudget`), a separate allowance from the era quota
   ([budgets.md](budgets.md)). A dry run skips it.
4. Fetch the unanchored GroupInfo (`getMlsGroupInfoForGroup`). The anchored copy that self-heal
   reads is the same request trimmed to our anchor and may lack `external_pub`; only the unanchored
   copy is the group's current GroupInfo and what the commit is built from.
5. Fetch the server pack for the ratchet tree.
6. Refuse if the GroupInfo carries `end_mls`: rejoining would re-encrypt a deliberately plaintext
   conversation.
7. Read the server's epoch authenticator as the commit's base, never ours: ours is exactly what does
   not match. A refused or empty look refuses.
8. Snapshot the group. The build persists the post-commit group before the server answers, so a
   refusal must be able to roll back; no snapshot, no resync.
9. Pre-flight `external_pub` (`0x0004`). Without it RFC 9420 §12.4.3.2 has nothing to derive the
   `ExternalInit` from, so the attempt is refused before it spends an allowance. It is read with
   `groupInfoContinuity`, which walks the GroupInfo's own extension list; `groupInfoExtTypes` walks
   the GroupContext list, where `external_pub` can never appear.
10. A dry run stops here and returns `RESYNC_DRY_RUN_VIABLE` (-4) or `RESYNC_DRY_RUN_NOT_VIABLE`
    (-5), having transmitted nothing and charged no allowance, but having spent three ledger looks.
11. Build (`externalCommitResync`) with the self-remove forced (`removeLeafIndex >= 0`); the native
    side resolves the removal target by the committer's identity. The plain flavour
    (`removeLeafIndex < 0`) falls back to self-remove on `DuplicateLeafData`. A failed build
    restores the snapshot anyway, because the native library is versioned separately.
12. Log the sender type, which must be `new_member_commit` (the only sender RFC 9420 §12.4.3.2
    accepts an `ExternalInit` from), so a framing bug is not mistaken for a policy refusal.
13. `applyMlsControl`. A refusal restores the snapshot and returns the verdict without retrying in
    another shape. Acceptance is forward progress; our own old leaf is then seen removed, which is
    expected and not a kick.

`external_pub` is produced only by a committer (`MlsSession.selfUpdateExtPub`). Our commits publish
their GroupInfo without it unless `debug.rcs.mls_publish_external_pub` is set (default off), so a
server GroupInfo for a group we committed last cannot support a resync, and without the extension
the GroupInfo is only a state summary for the divergence check.

`MlsRecoveryPolicy.onResyncFailure` / `ResyncFailureAction` decides what a resync does when its
snapshot write-back failed (`MlsExternalCommitResync.afterFailedRollback`); the resync refuses up
front when it cannot snapshot, so only a failed write-back reaches it. The snapshot counts as held
while the group still loads. A group that no longer loads leaves a record that fails every send, so
the conversation is forgotten and the next send re-establishes. A group that still loads after a
server refusal may be the external-commit group the members never adopted, so it is dropped only
if it is ahead of the server (`quarantineIfAheadOfServer`, as `MlsMembership` does).

## Maintenance

`MlsMaintenancePass.runMaintenanceOnce` is the proactive pass for one conversation. It compares
with the server and refreshes by era advance only when `MlsMaintenancePolicy` warrants it. Order:

1. Decline with no group, or when we left the group: its recorded roster is empty, so every server
   member would read as new and the add arm would re-create the group with us in it.
2. RCC.16 §9.5.3 Self-Update of our own leaf ([credentials.md](credentials.md)). It needs no server
   look, so a ledger refusal below cannot postpone it.
3. One server-pack fetch (`MAINTENANCE`) serves the roster, the GroupInfo and the era comparison. A
   refused or empty fetch ends the pass.
4. `quarantineIfAheadOfServer`: a local era ahead of the server's is not a refresh question.
5. Same-position identity check (`healthAgainstServer` on `MAINTENANCE_IDENTITY`, taken only when
   era and epoch are equal). `DIVERGED` calls `MlsRecoveryPolicy.maintenanceFoundAFork`, which stops
   the pass (no roster delta, no era advance) and self-heals; the heavier rebuild stays behind
   `reconcileAction`'s `DIVERGED` arm, which re-derives the verdict, rather than being reachable
   from maintenance's frequent triggers.
6. The add arm's inputs: members the server has that we do not, and the server's metadata-keys
   request. When no membership is recorded at this era, the server's roster is recorded as a
   baseline only if `serverConfirmedOurs()`; otherwise the arm is not evaluable this pass.
7. The credential floor report (logged, not an input to the verdict) and the expired-member count.
8. The verdict. When no advance is warranted, the floor rebuild may run instead, so one pass yields
   at most one era advance.
9. A warranted advance is deferred when the ledger refused the identity look: the fork test did not
   run, and advancing a fork would spend budget and write it a history.
10. After a successful advance, record the server's roster.

The group sweep (`armGroupSweep`, `sweepOnePage`, `continueGroupSweep`) walks every stored
conversation so a group nobody opens is still maintained. It has no timer and no interval. It is
armed by state changes only (cold session bring-up, identity change), walks the stored set once in
pages (`MlsMaintenancePolicy` page budget) from a persisted cursor written after each entry, and
disarms; between armings it costs one preference read. It runs on its own thread, keeping network
work off callers' threads and outside any lock. Re-arming continues a walk in progress. A declined
entry advances the cursor too, and a downgraded group is never maintained (an era advance would
re-create it and silently re-encrypt).

## The RCC.16 §10 failure-to-decrypt and resend ladder

### Inbound: a message we could not decrypt

Before any of this, `RcsCallbackRouter` gates the inbound RCC.16 headers with `MlsHeaderGate`:
Era-ID (decimal ASCII) and Epoch-Authenticator (Base64 of 32 bytes) are required in the MLS header
namespace, and a message missing either, or with an unparseable value, is dropped silently with no
receipt. The gate enforces on the application-ciphertext arm (`onMlsCiphertext`) and only reports on
the control-bundle arm (`onMlsControlBundle`), where some control traffic, such as a small
convergence acknowledgement, can arrive without the headers; dropping it there would wedge the
conversation. The log lines use the same wording as other clients so traces can be compared.

When a decrypt fails and no group is held, the router first waits up to `JOIN_RACE_WINDOW_MS`
(3 s) for an in-flight Welcome (`awaitJoinAndRetryDecrypt`): the first application message can
beat its Welcome by tens of milliseconds.

`MlsFtdEscalation.onDecryptFailure` then runs.

1. Note the sender as heard from (`MlsAdvancerElection.noteHeardFrom`).
2. If the ciphertext is strictly from the future (RCC.16 §10.8), park it (`MlsInboundHold`) and
   stop: a message we cannot read yet is a deferral, not a failure, and must not trigger recovery or
   an FTD. An era-crossing park also reports ([the pending queue](#the-pending-queue-rcc16-108)).
3. Queue the message id for an FTD report, then self-heal. A burst of failures produces one
   recovery and N reports.
4. If the heal returned `HEAL_LOOK_UNAVAILABLE`, or the convergence check was refused by the ledger,
   the consecutive-failure run is left unchanged and the reports are flushed.
5. Otherwise check convergence against the server. Converged resets the run. Not converged
   increments it; at `MlsRecoveryPolicy.UNCONVERGED_BEFORE_REBUILD` (3) the conversation is rebuilt
   and the run is reset whether or not the rebuild repaired it. One or two can be a race with an
   in-flight commit; three is a state the ladder cannot leave.
6. No group at all. A group-less member cannot sign an MLS FTD. It sends a re-Welcome request
   (`MlsRecoveryPolicy.requestReWelcome`, sent for a group and for a 1:1) and, for a 1:1, also
   re-establishes outbound (`reestablishOutbound`). A plaintext reconciliation receipt is available
   behind `debug.rcs.mls_plaintext_reconcile_imdn` but is off by default: it does not make a peer
   re-Welcome; each receipt demotes the destination one rung (MLS, then another encryption plane,
   then plaintext) and the peer does not re-attempt MLS. With it off the message stays undelivered
   and recovery is driven outbound.
7. Flush the reports. Messages that failed are lost to us whatever happens next (epoch keys do not
   decrypt the past), so only the sender can put them back.

`requestReWelcome` sends a `control-message-failed` (reason 13) report rather than a
`failed-to-decrypt` one: on the receiving side an FTD on an application message only triggers a
resend at the current epoch, which a group-less member still cannot read, while a control-message
failure escalates to an era advance, which re-Welcomes every member. It is stamped on the outer CPIM
with the server's era and epoch authenticator (a report without an era is treated as era 1); a
refused look sends it unstamped rather than not at all. It builds no state. Only our own clients
act on it: other clients route client reasons through their own health status and cannot parse a
report from a group-less sender, which cannot compute the derived-content signature.

`reestablishOutbound` runs off-thread (it claims a KeyPackage) and is rate-limited by the durable
re-establish cooldown in `MlsPeerGuard`. It declines when the server still holds the 1:1 (a create
there would advance us past the server and fan no Welcome, leaving us encrypting into a group nobody
else is in) and when the fetch ledger refuses the look. Its result line distinguishes converged,
look refused, era and epoch converged with the fork test not run, and not converged.

### Emitting FTD reports

`MlsFtdEscalation.flushFtdReports` sends the queued RCC.16 §7.7.2.2 `failed-to-decrypt` receipts.

* Not while a heal is in flight (`MlsPendingOperation.healInFlight`: states 2, 4 or 12). RCC.16
  §10.2 puts the report after recovery; the queue is kept and the heal's completion flushes it.
* The per-message attempt count is durable (`ftdResendCounts`,
  [above](#the-conversation-record)).
* RCC.16 §10.3's chain cap: stop reporting a message after `MlsFtdEscalation.MAX_FTD_ATTEMPTS` (5)
  attempts (`MlsConfig.ftdMaxAttempts`, key `debug.rcs.mls_ftd_max_attempts`). Past the cap RCC.16
  hands off to plaintext fallback, which the app does not do automatically.
* Each report is stamped with the era and the epoch authenticator taken from one group read (a pair
  sampled at different moments is meaningless), resolving the group through `resolveInbound`
  because the in-memory group map is empty on a fresh process. A report without an Era-ID is
  defaulted to era 1 by peers, whose lookup then misses and drops it; a failure to read the era
  still sends the report unstamped rather than not at all.
* The receipt id is minted before signing, the derived content
  (`VerifiableDerivedContent.DELIVERY_FAILED`) is signed over it, and the same id goes in the
  envelope so the receiver's message-id check (RCC.16 §7.5.3.1) passes. A signing failure sends the
  report unsigned. The IMDN reason and the signed failure code carry the same value; their codes
  coincide for 1..5. `MlsPayloadCorruptor` can override the reason once, for testing.

Signing (`MlsImdnSigner.signImdn`, RCC.16 §7.6.2). The signed content is more than the RCC.16
§7.6.3 inner struct; it matches what peers compute:

```
u16     version = 1
opaque  receipt_id<V>     this receipt's own id, not the reported message's
u32     era
u8      type              1 delivery, 2 display
        VerifiableDeliveryImdn / VerifiableDisplayImdn
u8      0x00              an absent RCC.16 §10.3 resent-message component
```

Signing only the inner struct fails verification at the peer. On the negative delivery form, peers'
decoders read a `u16` option discriminant after `failure_reason`, so the app writes `0x0000`
(absent); a missing field fails their decode. Positive receipts end at the failure reason, and the
parser accepts exactly one trailing `u16` on the negative form. Any inner version other than 1
fails a peer's decode. Negative receipts are signed with the same producer as positive and display
ones (`debug.rcs.mls_sign_negative`, default true), because peers reject an unsigned negative; a
dedicated negative producer does not exist. The version, status, epoch and report-id levers in
`MlsImdnSigner` (`debug.rcs.mls_ftd_*`) are decoder probes only, read only on a debug build.

### Outbound: a peer or the server reported our message failed

`MlsFtdEscalation.onPeerReportedFailure` handles an inbound negative-delivery receipt. Peers send
two shapes: a signed one carrying `<mls-client-failure-reason>`, and a bare unsigned `<failed/>`
with no reason, which is what a decrypt failure always produces (there is nothing to sign over).
The bare form maps to `NO_REASON_GIVEN` (200). `RcsCallbackRouter` runs the remedy once for
identical reports (reporter, reported id, reason) within `NEGATIVE_DELIVERY_DEDUPE_MS` (30 s); a
duplicate dispatch would produce two resends, which the repeat-resend detector would read as a
failing resend and escalate.

Resolve the conversation first. A group receipt arrives addressed to the originator as a phone
number, indistinguishable from a 1:1 receipt, so the group is recovered from the message id: the
app's message store, then the legacy group wire id `mls-grp-<rcsGroupId>-<gen>`
(`MlsMessageId.groupIdFromLegacyWireId`), which is never written to the store. If the id is
group-shaped and still unresolved, the report is refused rather than applied to the 1:1 with the
reporter. For peer reports the reporter must be a member of the resolved conversation
(`MlsServerBundle.reporterIsAMember`). That check charges the fetch ledger (`PEER_REPORT_VERIFY`,
since the rate is driven by a peer) but fails open on a refusal or an unreadable roster: our rate
ledger is not evidence about the peer.

Server reasons (RCC.16 §7.7.2.1) arrive out of band as an IMDN, seconds after a request that
returned success. They are offset by `RccNegativeDeliveryImdn.SERVER_BASE` (100) so the client and
server code spaces never overlap (`invalid-commit` exists in both), and are attributed to us, the
sender, so the membership check does not apply: the reporter is our own number, which the server
roster never lists. Five codes beyond the published schema (12 to 16) follow peers' numbering, in
which 15 is `mls-group-has-end-mls` and 16 `epoch-advancement-quota-reached`. Except for
`transient-error`, a server reason invalidates the cached ciphertext for the message (RCC.16 §12.8,
`ServerReason.cleansCache`): the server will reject the same bytes again, so a retry must
re-encrypt. No client reason cleans the cache. Dispatch is by `ServerReason.disposition()`, which
is total:

| Disposition | Reasons | Action |
|---|---|---|
| `SELF_HEAL` | incorrect-era, incorrect-epoch, incorrect-epoch-authenticator, mismatched-rcs-group-state, mismatched-confirmation-tag | Self-heal. A `mismatched-rcs-group-state` on a group whose status `hasEndMls` is absorbed. |
| `RETRYABLE` | transient-error | Nothing; the send may be retried. |
| `TERMINAL` | mls-group-not-found, mls-group-has-end-mls | Neither heal nor resend. |
| `ESCALATE_TO_ERA` | epoch-advancement-quota-reached | Move to `EraAdvancementRequested`; the era allowance is a separate budget. |
| `OUT_OF_QUOTA` | era-advancement-quota-reached | Record the quota (`MlsEraAdvance.noteEraQuotaReported`); do not retry. |
| `OURS_TO_FIX` | invalid-input, unparsable-commit | Log; a bytes problem, not a state one. |
| `REFRESH_IDENTITY` | expired-credential | `MlsCredentialUpdate.expiredCredentialRemedy` (see [credentials.md](credentials.md)). |
| `COMMIT_PENDING_PROPOSALS` | pending-proposal | Commit the cached by-reference proposals instead of healing past them. |
| `NONE` | invalid-commit, encryption-not-available | Deliberately no automatic remedy. |

Client reasons (RCC.16 §7.7.2.2) carry peers' numbers, not declaration order, and resolve by XML
name (an unknown name resolves to `null`). They are either failures or outcomes. The six outcome
tokens (7, 8, 9, 10, 11, 12) report what the peer already did and get no remedy; applying one would
duplicate the peer's work (for `commit-failed-then-era-advancement` it would advance the era a
second time). The four `*-processed-in-*self-heal` outcomes clear the peer's health streak (below).
Failure reasons and a receipt with no reason raise it; an unmapped code does neither. An inbound
IMDN that violates the arm rule is rejected (`parse` returns `null`), never thrown on.

| Reason | Action |
|---|---|
| 1 message-from-non-member | Self-heal our own state. |
| 2 invalid-credential | Escalate (`escalateForDivergedPeer`). |
| 3 invalid-commit, 5 commit-in-privatemessage, 13 control-message-failed | Escalate. Reason 13 is also how a group-less member asks to be re-admitted. |
| 4 failed-to-decrypt | The resend ladder below. |
| 6 resent-message-for-me-failed-to-decrypt | Log. Only the resend's target can emit it, so it points at resend construction. |
| 7 commit-processed-in-enhanced-self-heal | Nothing. |
| no reason given | Resend at the current epoch, capped by `MlsResendBudget`; never escalates. |
| anything else | Nothing. An unrecognised code is not evidence of a failure. |

The reason-less arm carries its own cap (`resendBudgetSpent`, `TOTAL_RESENDS`) because it returns
before the durable repeat counter; without it, each resend would draw a fresh report and resend in
a tight loop. Reason 6 is parsed but never sent: some peers' client-reason enum lacks it and they
drop the whole receipt, so `Reason.forEmit()` sends `failed-to-decrypt` in its place.

Reason 4. If the peer has drawn `MlsFtdEscalation.ESCALATE_AT` (2) repeat resends within
`MlsFtdEscalation.WINDOW_MS` (1 h), resending is not working and the group is repaired instead.
Otherwise the RCC.16 §6.2 remedy runs: "advance to the latest epoch and resend". If our state
already matches the server's, there is nothing to advance to and only the resend runs; a new epoch
would put a behind peer further behind and strand the next member. If we are behind, or cannot tell
(including a ledger refusal), we rekey and then resend.

Three separate bounds on one event. They must not be derived from each other: raising one must not
silently move another.

| Bound | Value | Question |
|---|---|---|
| `MlsFtdEscalation.ESCALATE_AT` / `WINDOW_MS` | 2 repeats per hour | Is resending working, or is the peer's state broken? |
| `MlsResendBudget.MAX_PER_WINDOW` / `WINDOW_MS` | 2 resends per hour | How hard may we resend to one peer? |
| `MlsFtdEscalation.MAX_FTD_ATTEMPTS` | 5 per message | When does the spec say to stop reporting? |

`MlsFtdEscalation.ReportClass` states which rule each receipt class is judged by:
`DECRYPT_FAILURE` counts `REPEAT_RESENDS` and escalates at its cap; `NO_REASON_GIVEN` counts
`TOTAL_RESENDS` and stops at its cap. Both read the same ledger rows, so a resend drawn by either
counts toward both. Both caps are windowed so one burst does not disable resends in a conversation
permanently.

Escalation (`escalateForDivergedPeer`) never advances from a view it has not verified: it checks
our own state against the server, self-heals first if we differ, abandons if we still differ (we are
not the member who can repair the group), and defers without a verdict if the ledger refused the
look. Recovery-issued era advances are gated on state, never on a membership delta, which belongs
only to the proactive maintenance refresh. On a successful era advance every member is
re-Welcomed, so the peer's ledger rows for the conversation are dropped and the ladder starts from
zero. It does not downgrade on an era-quota failure; see [downgrade.md](downgrade.md).

### Resends

A resend is a new message with a new id, linked to the one it replaces (RCC.16 §11.1).
Re-encrypting under the original id would consume a second ratchet generation for one id, and the
server deduplicates on message id, so a reused id is dropped before any peer sees it.

* The register is the `mls_resends` table (`MlsResendLedger`): `rcs_message_id` (primary key),
  `original_rcs_message_id` (chain root), `manual_resend_of_rcs_message` (immediate parent),
  recipient address and client id, `ftd_resend_count`, conversation key and timestamp, indexed on
  the root and on the recipient. The count is `max(siblings) + 1` computed at insert
  (`MlsResendRecord`), so it is durable by construction.
* Record before send. `MlsResend.sendResendNow` writes the row first and refuses to send if it
  cannot, because an unrecorded resend never advances the ladder.
* Root-ward resolution. A peer names whichever id it saw, so a second failure names a resend's id,
  which has no chat row; `MlsResendLedger.rootOf` is applied before any lookup. Receipts need it
  too: a delivered or displayed receipt for a resend reaches the chat row only through the chain.
* Body source. The pending body store (`MlsPendingBodyStore`, written at seal time) first, the
  chat row second (written after dispatch, so a fast report may arrive before it exists). A resend
  recovers the plaintext and re-encrypts it; replaying the cached ciphertext instead is a TODO. A
  group wire id is minted by a group send with no chat row, so its body is unrecoverable by
  construction.
* Group vs 1:1. A group resend goes out through `sendFramedToGroup`. A 1:1 resend is sealed at
  the current epoch and respects the convergence send-gate: the rekey that the remedy just
  performed shut the gate, so an automatic resend is parked (`MlsResendBudget.deferGatedResend`, at
  most `MlsRecoveryPolicy.MAX_GATED_RESENDS` = 16) with no ledger row, and flushed off-thread when
  the peer converges or the gate expires (`GATE_DEADLINE_MS` = 90 s, strictly greater than; an
  `openedAt <= 0` means no gate and a deadline `<= 0` disables it). The gate applies only on a
  transport that acknowledges convergence; on the carrier MSRP path, which delivers the Welcome
  peer to peer, nothing could close it, so none is opened.
* User resend. `MlsResend.resendByUser` is the "Resend" action on a failed chat row
  (`ResendMessageAction`, from a failed RCS row; the chain seals, so it only succeeds on an MLS
  conversation). It applies no budget (a person pressing a button breaks loops rather than making
  them) and refuses rather than defers at a shut gate, because the caller writes the row's status
  from the answer. The chat row keeps its original `rcs_message_id`; receipts for the new id
  resolve back through the ledger.

### Send-side safeguards

Replay material. `MlsSealedMessage` keeps each sealed outbound message, with the era and epoch
authenticator headers current at seal time, so a repeat send of an id already sealed replays the
stored non-empty ciphertext instead of re-encrypting and burning a generation. The stored row grows
only by appending fields (5 fields: no conversation key; 6: wall stamp only; 7: elapsed stamp);
older rows decode, and a six-field row reads its elapsed stamp as `UNSTAMPED`, which the sweep
adopts at the current reading. The conversation key `""` matches nothing, so one terminal event
cannot wipe the store.

`MlsSendRetentionPolicy` decides how long replay material (sealed ciphertext and pending body) is
kept:

* The window is `DEFAULT_MAX_AGE_MS` (24 h), aged by `elapsedRealtime` through `MlsMonotonicAge`,
  never the wall clock, whose forward jumps would delete in-flight material. `UNSTAMPED` (and any
  negative stamp) is expired; a stamp of 0 means "written at boot"; a stamp ahead of now is a
  reboot and is retained until uptime reaches the window. It also backs the "Not sent" retry the
  app shows for rows failed at startup.
* A positive receipt releases a 1:1 message's whole resend chain (the receipt may name the resend
  that got through). For a group, one member's receipt says nothing about the others:
  `releaseSealedOnGroupReceipt` releases once every member of the current roster, re-read per
  receipt, has confirmed, so a member removed in flight is not waited on forever.
* A permanent failure (`STATUS_FAILED`) releases only the failed attempt's bytes. The chain's ledger
  rows stay live (`retireChainOnTerminal(delivered = false)` is false), because they are the only
  link from a resend's id to the root that holds the body. A failure naming the root of a chain
  that already has resends releases nothing: a resend row shows a peer received the root.
* A delivery retires the chain's ledger rows rather than deleting them (`retireChain`): the
  timestamp becomes minus the retirement time, which every count excludes, so a delivered chain is
  no longer escalation evidence. The rows stay because the router releases on a delivery receipt
  before `UpdateRcsMessageStatusAction` resolves that same receipt's id, and a displayed receipt
  follows later; both reach the original row only through `rootOf`. Deleting the rows at delivery
  left the resend's delivered and displayed receipts with "no local row". The retention sweep
  deletes retired rows `RETIRED_CHAIN_MAX_AGE_MS` (7 days) after retirement.
* The conversation of a sent message comes from the message store for a UI send, the id itself for
  a group wire id, and the resend ledger's conversation key (`g:<rcsGroupId>` or `p:<e164>`) for a
  resend, whose bare UUID has neither. An unclassifiable id is `UNKNOWN_CONVERSATION`, which
  callers treat as a group, so one member's receipt does not release material a resend needs.

`MlsPendingBodyStore` (preferences `mls_pending_body`, key `rcsMessageId`, value
`1|<elapsedMs>|<b64 framed body>`) caps at 1024 entries. At the cap it first sweeps expired
entries, then evicts the entry with the largest provable age, so the newest send always has resend
material and the cap heals itself whatever the traffic mix. Older one-field entries are expired and
two-field wall-stamped entries adopted.

`MlsRendezvous` / `MlsRendezvousStore` (preferences `mls_rendezvous`, key
`(self, sender, message id, stage)`) record a decrypt's result so a re-delivered message is
answered by replay instead of a second ratchet-advancing decrypt. Read before decrypt, write after;
at chat-row insert every stage and sender attribution of the message id is deleted (a narrower
delete would leak a row per message). Writes use `commit()`, since the window closed is process
death between decrypt and chat row. At 512 rows it refuses to store and logs; it never evicts. A
corrupt row reads as absent and is removed.

Sealing (`MlsSealSend`). The app's `rcs_message_id` is bound as the wire and AAD id, so a peer's
report names a row id. A synthesised id (debug and legacy paths) is
`mls-<conv>-e<era>p<epoch>-<gen>`: the generation restarts every epoch, so an id without the epoch
would collide inside one era and be dropped by the server as a duplicate. The epoch authenticator is
read live from the engine with no fallback to the cached copy, since a stale one hides a store at
the wrong epoch. A send refused while a peer's commit is landing is retried once with identical
bytes and headers; no generation is consumed. The provider's success reason carries the server
timestamp, and a repeated one is the only sign that an enqueue was deduplicated.

Rekeying (`MlsRekeyPolicy`). Leaf rotation piggybacks on encrypt and resets the application ratchet
to generation 0 before a long run passes the receiver's `max_skip` window. The threshold,
`REKEY_AFTER_SENDS` (256), is staggered by `weAreEraAdvancer`: the lower E.164 rotates at 256, the
other at 384, so two members under mutual traffic do not commit-duel. A refused rotation backs both
counters off to 192 rather than restoring them, so the loser of a commit race lets the winner's
commit land first. The out-of-band fallback rekey is unstaggered, because it runs only when the
piggybacked rotation did not happen at all. Sending also latches the conversation's MLS encryption
bit.

### Receiving a resend

`MlsInboundDecrypt.decryptInbound` handles one inbound application message: it replays the stored
result for an id already decrypted (a decrypt advances the ratchet, so a duplicate cannot be
decrypted again), refuses through `MlsInboundRefusal` a message whose AAD binds another id by the
engine's verdict (`AAD_MESSAGE_ID_MISBINDING`) or whose signer is not its sender
(`SENDER_IMPERSONATION`), and passes every decrypted message to `MlsResendReceive` for the resend
decision below.

The resent-message component rides in the trailing slot of the application AuthenticatedData
(`00 01 | varint(len) | message_id | era u32 | <trailing>`, with `0x00` when absent).
`MlsResendReceive.evaluate` fixes the receive order: parse the AAD, find the component (absent means
an ordinary message), check the 64-byte HMAC field length, evaluate the HMAC, and only then unwrap
the inner ciphertext. It runs over every successfully decrypted application message, because a
resend's outer message decrypts for every member.

| Disposition | Effect |
|---|---|
| `NOT_A_RESEND` | Deliver as an ordinary message. |
| `FOR_ME` | Deliver the inner plaintext, not the wrapper. |
| `FOR_ME_UNWRAP_FAILED` | The only disposition that reports, as `failed-to-decrypt` (4). |
| `NOT_FOR_ME`, `MALFORMED_COMPONENT`, `EMPTY_HMAC`, `INVALID_HMAC_LENGTH`, `SELECTOR_UNAVAILABLE`, `FOR_ME_UNWRAP_UNAVAILABLE` | Silent drop: no receipt, no FTD, no recovery, no health transition. |

Structural failures are silent too, even though other clients raise distinct errors for them,
because every member reaches them and a receipt per member for one resend would be a receipt storm
(and each FTD is a self-heal trigger). A lost resend resurfaces through the ordinary FTD path.

What is modelled and what is not:

* The HMAC field is `key(32) || tag(32)`, `tag = HMAC-SHA256(key, data)`, with no key derivation and
  the key in the clear. With a recipient-invariant datum every member computes the same tag, so the
  MAC does not select a recipient. `evaluate` tries named candidate inputs
  (`MlsResendBudget.resendMacCandidates`: our number as E.164, digits and `tel:` URI, the group id,
  and group id followed by our number; a leaf index is not exposed on this path) and logs which one
  matched; a wrong candidate cannot produce a false match.
* The 64-byte field is not the component's length-prefixed opaque; its offset within the AAD and
  the inner wrapped layout are unknown, so `resentSelectorField()` and `resentInnerUnwrap()` return
  `null`, and every present component stops at `SELECTOR_UNAVAILABLE`. The RCC.16 §11.3a unwrap path
  is therefore unreachable.
* `MlsResentMessage.TAG_RESENT` (`0x02`) is inferred from declaration-order tagging; what is
  established is that the receiver reads the tag as a single byte, so a TLS-style `optional<T>`
  (`01` + struct) would be rejected. `parse` models the present form as a tag and one
  length-prefixed opaque and tries every candidate prefix width, flagging ambiguity; the real
  structure has four fields (a `u32` and three `mls_varint` opaques, the last the HMAC field) behind
  unresolved outer framing, so a real resend may parse as malformed.
* The component's opaque is inferred to be the original message id. Peers set the outer CPIM header
  `Original-Message-ID` (MLS namespace) only on a resend; it is outside the AAD and readable without
  the key. `crossCheckOriginalMessageId` compares the two: a header with no component points at a
  wrong tag byte, a component with no header at a transport that dropped the header.
* Our own resends do not carry the component (only the debug corruptor encodes one); emitting it
  waits on the unknown fields above.

### Peer health streak (guard G4)

`MlsPeerGuard` stops state-changing operations toward a peer that keeps telling us our operations
failed on its side. The streak is `MlsPeerHealthRecord`:

* `MAX_CONSECUTIVE_FAILURES` = 3 peer-reported failures trips it. Only observed negatives count; a
  peer that is merely offline sends no reports and never trips it.
* It is persisted as `"1|<streak>@<elapsedRealtimeMs>"`. `decode` returns `NONE` for nothing
  stored and `null` for something unreadable, which callers treat as "cannot say this peer is
  healthy".
* It expires `EVIDENCE_MS` (one day of uptime, `SystemClock.elapsedRealtime`) after the last
  report. Durability and expiry are one decision: an in-memory streak would decay on every restart,
  and a durable one with no bound would permanently block the automatic rebuild. It re-arms itself,
  because a still-broken peer keeps failing and reporting.
* It saturates at 3; once saturated the stamp is refreshed at most every `STAMP_COALESCE_MS` (60 s)
  so a burst of reports is not a burst of disk writes.
* It is cleared by evidence the peer processed something of ours: a positive IMDN, or one of the
  `*-processed-in-*self-heal` outcomes.

### Participant key rotation (RCC.16 §10.1.1)

`MlsParticipantKeyResync` plans which clients of a participant whose key rotated are removed by a
resync external commit: one `Remove` proposal (an external commit carries only one) plus
`ServerRemove` proposals (RCC.16 §7.11.9) for the rest. A null or empty current key plans nothing
(otherwise every client of the participant would be removed), and a leaf with no recorded signing
key is never treated as stale. `MlsParticipantKeyLedger` supplies the current key by folding
successive roster readings: a roster is a set and leaf index is not add order, so the new key is the
one absent from the previous reading. A first sighting already split across keys, or two new keys at
once, yields no current key, and keys are never dropped from the history, so a returning key does
not look new. A leaf's participant key is attested by the issuer chain, so a peer cannot forge
another participant's key. Both are pure and host-tested; nothing in production calls them yet.

## When recovery stops: the stall alert

`MlsRecoveryPolicy.needsUserChoice` is true only when the self-heal budget is exhausted and the
awaited moment is unreachable without a Welcome only a peer or the server can send (an observed
`ERA_GAP`, `REJOIN` or `NOT_FOUND`; a refused look counts as reachable).
`MlsStallAlert.offerTheStallChoice` runs the decision off-thread, because the caller holds the
conversation lock and deciding needs server I/O (`STALL_CHOICE` ration). The off-thread half reads
the budget again from the record, so a conversation a heal reached in between is neither asked
about nor escalated:

* Unreachable: try a rebuild first, since it crosses `ERA_GAP`, `REJOIN` and `NOT_FOUND`, which no
  commit can. A repaired rebuild clears the alert; otherwise the alert is raised and names the rung
  that failed and how (`MlsRebuildOutcome.line()`). Raising it leaves encryption and parked
  messages unchanged (`MlsHostAction.Kind.USER_ACTION_REQUIRED`).
* Reachable but behind (`LOWER_EPOCH_CHAIN_UNKNOWN`) with the budget spent: nothing replays missed
  commits. The path first checks that the server roster still lists us (a definite "not listed"
  raises the alert: this needs a re-add; a refused or unreadable roster is "cannot tell"), then
  tries an [external-commit resync](#external-commit-resync) adopting the server's GroupInfo, then a
  rebuild. A rebuild alone creates a fresh group whose epoch authenticator still differs from the
  server's, which is why the resync goes first.
* Any other reachable verdict is backoff, not a stall.

`MlsStallAlert.surfaceIfNobodyElseWill` also raises the alert when reconcile's rebuild was refused
by a guard or the rebuild rate bound (`MlsRebuildOutcome.needsAPerson`), because re-drives cannot
lift those. `stallCleared` withdraws it once the conversation is healthy again, once per recovery
(guarded by `ConvState.stallAlertDown`, which starts false so an alert raised before a restart is
still cleared).

`MlsStalledActionReceiver` handles the two choices off the main thread:

* Try again resets the self-heal budget, the rebuild rate bound and its episode suppressor, the
  era budget, the peer-health streak for every member, the re-establish cooldown and the RCC.16
  §11.2.2 external-commit allowance ([budgets.md](budgets.md#the-try-again-lever)); then re-drives
  the reconcile and re-checks the alert against the server (`refreshStallNotification`, its own
  `STALL_REFRESH` ration because a person is waiting). `LOOK_REFUSED` and `IN_SYNC_UNVERIFIED`
  leave the alert as it was, `IN_SYNC` clears it, anything else re-raises it. Encryption is
  unchanged.
* Turn off encryption runs `endMls` with `UNRECOVERABLE_SERVER_FAILURE`, which is recorded as an
  unexpected downgrade so the re-upgrade loop can bring the conversation back. See
  [downgrade.md](downgrade.md).

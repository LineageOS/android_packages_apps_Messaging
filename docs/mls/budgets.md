<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# MLS: budgets and rate limits

MLS recovery is driven by retries, receipts and timers, and every one of those can repeat. Some of
what a repeat spends is cheap (a loop iteration). Some of it is not ours at all: a server read that
the server throttles, a KeyPackage taken out of a peer's one-time pool, or a group re-creation that
forces every member to re-join by Welcome. This page describes the bounds on those resources, why
each bound has the shape it has, and how the bounds are stored.

Related pages: [health-and-recovery.md](health-and-recovery.md) (the ladder these bounds sit on),
[group-lifecycle.md](group-lifecycle.md) (establish, era advance, rebuild),
[transport-and-port.md](transport-and-port.md) (where the charge points live),
[../testing.md](../testing.md) (the guards that keep every spend charged).

## The resources

| resource | whose it is | bounded by | keyed on |
|---|---|---|---|
| a server group-info read (five provider AIDL calls, each costs the provider exactly one) | the server's rate window | `MlsFetchLedger` (+ `MlsFetchBudget` inside one drive) | conversation |
| a claim of a peer's published KeyPackages | the peer's pool | `MlsClaimLedger` | peer |
| a peer-facing state change, above all a group re-creation that re-Welcomes every member | every member's client | `MlsStateChangeGate` + `MlsPeerGuard` (G1, G2, G4, G6) | group id, or peer for a 1:1 |
| an automatic conversation rebuild | ours (local state destroyed, KeyPackages claimed) | `MlsWindowBudget.REBUILD` via `MlsRebuildLimiter` | conversation |
| an external commit | the group (RCC.16 §11.2.2) | `MlsWindowBudget.EXTERNAL_COMMIT` via `MlsExternalCommitBudget` | conversation |
| a repeated rebuild attempt inside one burst; a 1:1 re-establish | ours; the peer's pool | `MlsCooldownRecord` stamps in `MlsPeerGuard` | conversation; peer |

## Rules every budget follows

These are the properties that make a bound a bound. Each one closes a specific way a budget can stop
counting while still looking correct.

| rule | what it prevents |
|---|---|
| **A rate, never a total.** Every window rolls; charges age out and the allowance comes back. | A permanently spent total turns a transient fault into a conversation that can never repair itself. |
| **Charge before the operation.** The record is written before the RPC, the create or the send. | An operation that crashes after spending, but before a post-hoc charge, would be free, and a reproducible crash would become an unthrottled loop. |
| **Durable.** Counters live in `SharedPreferences`, not in memory. | The loops these budgets bound are the ones that kill the process. An in-memory counter is handed back on every restart while the cost it protects is paid again. |
| **Aged by `elapsedRealtime`, through `MlsMonotonicAge`.** The wall clock is never used in window arithmetic. | Any wall-clock jump (NTP correction after a dead-RTC boot, a user setting the date) would silently drop entries and refill the allowance. |
| **Unreadable is not absent.** Every record's `decode` answers an `EMPTY`/`NONE` value for "nothing stored" and `null` for "stored and cannot be parsed". | Reading a damaged record as "no charges" would hand back a full allowance on the strength of a parse error. |
| **A caller is always declared.** Every charge names a `Caller`; a null caller is refused, never defaulted. | A default would give every new call site a free ration by omission. |
| **Refusals say whose bound it is.** Refusal lines state that the bound is ours, that nothing was asked, and which bound refused. | A refused look read as "the server has nothing", or a refused claim read as "the peer has no KeyPackages". |
| **Pure policy, thin adapter.** The arithmetic lives in engine classes that read no clock and hold no state; the app-side adapter reads the store, calls the policy, writes what the policy says, and logs. | Policy that needs a `Context` cannot be host-tested. |

### Which counters are durable

The durability rule applies to a bound whose loss across a restart lets a loop run more: a spent
allowance refilled, a suppressor reset, a cooldown on somebody else's resource forgotten. A bound
whose loss only makes us more patient, or whose counter dies with the thing it counts, stays in
memory:

| in memory | why losing it is safe |
|---|---|
| `MlsRecoveryPolicy.UNCONVERGED_BEFORE_REBUILD`, `MlsSelfHealPass.PARKS_BEFORE_UNSTICKING_A_HEAL` | they drive toward a repair, so a restart defers the escalation and never repeats it; the repair itself is rate-bounded durably |
| `MlsRecoveryPolicy.GATE_DEADLINE_MS`, `MAX_GATED_RESENDS` | the gate blocks our own sends, so losing it releases the gate, as its deadline would; the resend count is the depth of an in-memory queue that a restart empties with it |
| `MlsMetadataKeysPolicy.MAX_PENDING_SUBJECT`, `MAX_PENDING_ICON_BYTES` | the held keys die with the process. The icon bound is a byte budget (1 MiB), separate from the subject's holder count, and an overflow drops the newcomer rather than evicting a held entry. |
| the advancer-liveness ledger (`ConvState.heardAtSeq`, 256 entries) | it records who was heard since this process started |
| `RcsCallbackRouter.NEGATIVE_DELIVERY_DEDUPE_MS` (30 s) | it suppresses the provider dispatching one report twice within milliseconds; a restart hands back at most one extra remedy, and everything scarce downstream (the repeat-resend count, the escalation, the era budget) is bounded durably |

`MlsGateCounterDurabilityGuardTest` classifies every threshold constant as durable or declared
transient and fails on one that is neither. The identity refresh keeps two stamps for the same
reason: `PREF_LAST_IDENTITY_REFRESH` records successes only, because `onIdentityChanged` clears it
to force an immediate re-read, and `PREF_LAST_IDENTITY_ATTEMPT` is the separate failure backoff.

The durable budgets all write with `commit()`, so the charge is on disk before the operation:
`MlsPeerGuard`, `MlsRebuildLimiter`, `MlsExternalCommitBudget`, and the fetch and claim ledgers
(`storeLedger`, `storeClaimLedger`). `apply()` would change the in-process value before the RPC and
write the disk later, so a process death during the RPC could lose the charge.

### The clock: `MlsMonotonicAge`

`MlsMonotonicAge.ageMs(charged, now)` returns the largest age it can prove:

* same boot (`charged <= now`): `now - charged`, exact;
* the reading went backwards (`charged > now`): the device rebooted, so the entry is at least as
  old as the current uptime, and `now` is returned;
* a negative input: `0`, the most conservative age.

Every answer is a lower bound, so an entry can be retained longer than its window but never dropped
early. The price is paid after a reboot: a spent window refills only once the window has elapsed in
uptime. That is bounded (time refills it) and a person can clear it (see
[Try again](#the-try-again-lever)). `rebootedSince` is one-directional: `true` is proof, `false`
only means no reboot is detectable.

## Server reads: `MlsFetchBudget` and `MlsFetchLedger`

A conversation that cannot converge does not sit still: each recovery pass reads the server, and
enough reads in a short window make the server throttle the conversation. At that point recovery has
destroyed the resource it depends on. Two bounds address this at two scopes.

### Inside one drive: `MlsFetchBudget`

`MlsDriveLoop` caps iterations (`DEFAULT_MAX_ITERATIONS` = 10), but iterations are free and reads
are not. `MlsFetchBudget.mayLook(looksSpent, budget)` limits one recovery drive to
`RECOVERY_LOOKS` = 2 passes that reach the server: one to observe and act, one to verify. A third
look inside one drive is the non-convergence itself. The two limits are deliberately independent;
tying them would make raising one silently raise the other. `mayLook` clamps a budget below 1 to 1,
because a budget of zero would fail a drive that was never allowed to ask anything.

`MlsFetchBudget` is not a backoff and reads no clock. Its one duration,
`MEASURED_THROTTLE_COOLDOWN_MS` (200 s), is the only known throttle cooldown and is used so a retry
that is going to happen anyway lands outside it. `firstAttemptClearingTheCooldown()` computes, from
the `MlsRetryPolicy` ladder, the first attempt number whose delay exceeds that cooldown, so retuning
the ladder cannot schedule a throttled conversation back inside the window.

### Across all callers: `MlsFetchLedger`

The per-drive budget cannot bound the resource, because a drive is only one of the ways it is spent.
`MlsFetchLedger` counts the reads themselves, per conversation, across every caller.

**The primitives.** Five provider AIDL calls each cost the provider exactly one server group-info
read. The enum `MlsFetchLedger.Primitive` lists them, and the source-scan guard reads the names out
of this enum, so the two cannot drift.

| `Primitive` | AIDL method | what it asks |
|---|---|---|
| `FETCH_MISSED_COMMITS` | `fetchMissedCommits` | anchored at our era and epoch authenticator |
| `FETCH_SERVER_EPOCH_AUTHENTICATOR` | `fetchServerEpochAuthenticator` | the server's epoch authenticator |
| `GET_MLS_GROUP_INFO_FOR_GROUP` | `getMlsGroupInfoForGroup` | the full GroupInfo an external commit needs |
| `GET_MLS_SERVER_ERA_EPOCH` | `getMlsServerEraEpoch` | the server's era/epoch pair |
| `GET_MLS_GROUP_INFO` | `getMlsGroupInfo` | the 1:1 GroupInfo by peer (debug arm only) |

The resource is the read, not a call name. An inventory keyed on one spelling misses the others:
`detectHealth` spends through `getMlsServerEraEpoch` and `fetchServerEpochAuthenticator` and never
touches `fetchMissedCommits`.

**Charging.** There is exactly one wrapper per primitive, and those wrappers are the only places the
primitives are invoked: `lookMissedCommits`, `lookServerEpochAuthenticator`, `lookServerEraEpoch`
and `lookGroupInfo` (in `MlsFetchLedger`), and `lookGroupInfoForGroup` (in
`MlsExternalCommitResync`). Each reaches the provider through the port's `rpc(what)`; the transport
keeps a delegate for each. Funnels such as `fetchServerPack` call these wrappers rather than the
provider.
Each wrapper takes an `MlsFetchLedger.Caller` as its first parameter and calls
`MlsFetchLedger.spendOneLook(shell, log, caller, primitive, key, doIt)`:

1. read `elapsedRealtime` and the shared ceiling (`fetchLedgerCeiling`, read live);
2. read the record (`ledgerFor`). If it is unreadable, refuse (unless the caller is exempt);
3. compute this caller's spend and the ceiling spend in the window, and ask `mayFetch`;
4. on a refusal, log `describeRefusal` and return `Look.refusedByLedger(why)`, without calling the
   provider;
5. on `SPEND_EXEMPT`, log `describeExemption` and call the provider without charging;
6. on `SPEND`, write `record.charged(caller, now)` first (`storeLedger`, with `commit()`), log
   `describeCharge`, then call the provider; a null answer is logged with `describeUnreadableLook`,
   which reports what the whole conversation had already spent, since a throttle is provoked by the
   conversation and not by one caller.

The result type `MlsTransportTypes.Look<T>` keeps a refusal apart from an answer: `refused()` means
nothing was asked, and `orNull()` is always null in that case. A health verdict reached through a
refused look is `Health.LOOK_REFUSED`, never `UNKNOWN`: an unreadable answer is evidence about the
network or a throttle, a refusal is evidence about our own window, and they need different
diagnoses.

**Callers.** One constant per method that decides to spend. Funnels such as `fetchServerPack`,
`serverStateCheck`, `detectHealth` and `serverPackForRebuild` have no constant: they take a `Caller`
and pass it down, because attributing the spend to the funnel would put several decisions under one
ration and hide the competition the ledger exists to separate.

| `Caller` | ration | shared ceiling | charged from |
|---|---|---|---|
| `RECONCILE_DRIVE` | 4 | yes | `reconcileAction` in `driveReconcileInner`; `detectHealth` costs one read plus a second (the epoch authenticator) on its equal-epoch arm, so 4 is two passes |
| `HEALTH_PROBE` | 2 | no | `probeAnchor` |
| `SELF_HEAL` | 3 | yes | `selfHealInner` |
| `PEER_REPORT_VERIFY` | 2 | yes | `reporterIsAMember`, `onPeerReportedFailure` |
| `MAINTENANCE` | 2 | yes | `runMaintenanceOnce` |
| `MAINTENANCE_IDENTITY` | 1 | no | `runMaintenanceOnce`, the same-era identity check |
| `ERA_ADVANCE` | 4 | yes | `eraAdvanceLocked`, `freshContextIdRetry` |
| `ERA_QUOTA_CHECK` | 1 | yes | `eraQuotaBound` |
| `EXTERNAL_COMMIT_RESYNC` | 3 | yes | `resyncViaExternalCommit` |
| `STALL_CHOICE` | 1 | yes | `offerTheStallChoiceOffThread` |
| `STALL_REFRESH` | 2 | no | `refreshStallNotification` |
| `REBUILD` | 4 | yes | `rebuildConversation`, `serverPackForRebuild` |
| `REESTABLISH` | 2 | yes | `reestablishOutbound` |
| `RE_WELCOME_REQUEST` | 2 | yes | `requestReWelcome` |
| `RE_WELCOME_SWEEP` | 1 | yes | nothing; retired, kept for ordinal stability |
| `REJOIN_ON_ERA_ADVANCE` | 2 | yes | `rejoinOnEraAdvance` |
| `GROUP_ESTABLISH` | 2 | yes | `establishGroup`, `addMembersToExistingGroup` |
| `DIVERGED_PEER_ESCALATION` | 2 | yes | `escalateForDivergedPeer` |
| `DECRYPT_FAILURE_CHECK` | 2 | yes | `onDecryptFailure` |
| `SUBJECT_CHANGE` | 1 | yes | `changeGroupSubject`, `changeGroupIcon` (via `healBeforeMetadataChange`) |
| `END_MLS` | 2 | yes | `endMls` |
| `ENSURE_READY` | 4 | yes | `ensureReady` (create, era-bump and reclaim arms) |
| `QUARANTINE_CHECK` | 2 | no | `quarantineIfAheadOfServer`, reached from `mlsMembershipChange`, `runMaintenanceOnce` and the era-advance paths |
| `COMMIT_OUTCOME_CHECK` | 4 | no | `keepUnacknowledgedCommit` |
| `DEBUG_DUMP` | exempt | no | `dumpGroupExtensions`, `dumpServerValidity`, `debugGroupInfo` |
| `DEBUG_HEALTH` | exempt | no | `debugServerEraEpoch` and the debug receiver's health read |

Notes on the less obvious rows:

* **Per-caller rations, not one number.** `probeAnchor` is the divergence test. Starving it makes
  health unreadable, and unreadable health escalates the ladder to a heavier remedy than the fault
  needs. So a diagnostic read and a recovery look do not share a budget, and the readers whose job
  is to make health legible (`HEALTH_PROBE`, `MAINTENANCE_IDENTITY`, `STALL_REFRESH`,
  `QUARANTINE_CHECK`, `COMMIT_OUTCOME_CHECK`) are held outside the shared ceiling:
  `chargesTheSharedCeiling` is false, and `MlsFetchLedgerRecord.spentAgainstCeiling` excludes them
  from the sum as well as from the test. If they counted toward the total they would refuse
  recovery without ever being refused themselves.
* **Audit reads sit outside the ceiling because they run after eventful operations.**
  `QUARANTINE_CHECK` asks whether an operation left us ahead of the server, and
  `COMMIT_OUTCOME_CHECK` asks whether the server took a commit whose response was lost. Both run
  precisely when the conversation has just spent heavily; charging them to the ceiling would refuse
  them exactly then. A refused `COMMIT_OUTCOME_CHECK` holds the commit rather than discarding it
  (see `MlsCommitApplication`).
* **`COMMIT_OUTCOME_CHECK` is 4 because one invocation costs two reads** (the era/epoch pair, then
  the epoch authenticator that settles whose chain that epoch belongs to). The ration keeps the
  number of invocations per window constant; `MlsFetchLedgerRationGuardTest` ties the constant to
  the charge-site count.
* **`MAINTENANCE_IDENTITY` is separate from `MAINTENANCE`**: the maintenance pass already spends one
  of `MAINTENANCE`'s two looks on its pack, so folding the identity look in would halve how often a
  conversation can be maintained. It is asked at most once per pass, only when era and epoch both
  match (anywhere else two different epochs have different authenticators by construction). A
  refusal reports in-sync on numbers and says so. The extra read is not inherent: the reply to
  `fetchMissedCommits` carries the server's epoch authenticator, but the provider does not return
  it across the AIDL interface.
* **`QUARANTINE_CHECK` is outside the ceiling because it is an audit read**, not because its
  callers have already spent: `mlsMembershipChange` reaches it having spent no read at all.
* **`ENSURE_READY` is 4**: `ensureReady` has a create read, an era-bump arm and a reclaim arm, and
  the bump can be followed by the reclaim in one flow. Inside a rebuild these refusals fail open
  (below), so a ration that is too small does not stop the repair; it stops the ledger from
  measuring it.
* **`ERA_ADVANCE` is the innermost of three bounds on the rebuild rung.** Outermost first:
  `MlsPeerGuard`'s era budget (2 per hour, 5 per day), then the self-heal budget, then this ration.
  `selfHealInner` charges the self-heal budget before it consults this ledger, so a ledger refusal
  cannot prevent the self-heal charge or its escalation; it only costs that heal its read. When a
  recovery halts, check the three in that order.
* **`SUBJECT_CHANGE` covers the icon too**: subject and icon are one operation over two fields of
  the same request, typically changed in one sitting, so one ration covers the pair.
* **`RE_WELCOME_SWEEP` is retired but stays.** The record stores caller ordinals (below); deleting a
  constant would shift every later ordinal and reattribute stored charges to the wrong caller.
  Reclaiming a slot requires a migration.

**Window and ceiling.**

| constant | value | reason |
|---|---|---|
| `MlsFetchLedger.WINDOW_MS` | `MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS` (200 s) | the only known throttle window; referenced, not restated, so both move together. If the real window is longer we under-protect; if shorter we are only conservative. |
| `MlsFetchLedger.SHARED_CEILING` | 8 | below the burst size at which the server throttles a conversation (ten reads), and one above the usual heavy sequence (a reconcile drive, then a self-heal, plus the maintenance charge from session bring-up), so a healthy conversation is not refused on its next ordinary look. It still refuses the three heaviest ceiling callers maxing out together (4 + 3 + 2 = 9). It is not derived from ten: a bound set at the value that fails is a bound that fails. |

**Verdicts.** `mayFetch(caller, spentByThisCaller, spentAgainstCeiling, sharedCeiling)` checks, in
order: a null caller (`DENIED_CALLER_RATION`), an exempt caller (`SPEND_EXEMPT`), the caller's
ration (`DENIED_CALLER_RATION`), then the ceiling for callers that charge it
(`DENIED_SHARED_CEILING`), else `SPEND`. Negative spends read as zero; a ceiling below 1 is clamped
to 1. Because the ration is checked first, `describeRefusal` on a ration refusal checks the ceiling
before speaking for it: when both are exhausted, the line says so and tells the reader to wait out
the window rather than look for a loop in this caller.

**Fail-open callers.**

* `PEER_REPORT_VERIFY` (`failsOpenOnRefusal()`): a peer's RCC.16 §7.7.2.2 failure report that we
  cannot verify because of our own budget is still a report. Acting on a real report we could not
  verify costs a redundant repair; dropping one leaves a peer diverged indefinitely. It still
  charges, and `describeFailOpen` states that nothing was verified.
* Inside a rebuild that has already destroyed local state (`ensureReady` with
  `stateAlreadyDestroyed`), a refused look proceeds without the server's era. `rebuildConversation`
  hoists its own guards above its forgets for the same reason: a refusal arriving after the forget
  would leave the conversation with no provider record, no engine state and no group.

**Exempt callers.** `DEBUG_DUMP` and `DEBUG_HEALTH` are never refused (an operator asks because
something is already wrong) and never charged (a debug arm that silently spent a recovery look would
leave the next recovery short with nothing in the log). `describeExemption` says that the read still
costs the server.

**Unreadable record.** `ledgerFor` returns `null` for a record that is stored and does not parse, or
for a store that cannot be read, and `spendOneLook` treats that as the ceiling being spent: the
rationed resource is the server's, and a parse failure is not evidence that nothing was spent.
Exempt callers still run. A non-exempt caller is refused once, and then `discardUnreadable` removes
the record (with `commit()`), so the next look starts from an empty ledger: nothing else would ever
replace it, since an exempt caller writes nothing and neither teardown nor Try again touches the
ledger. This is what `MlsPeerGuard` and `MlsWindowBudget` do. The discard re-reads first and
removes only a value that still does not parse; a store that cannot be read is left alone.

**Persistence.** `MlsFetchLedgerRecord`, in the transport's preferences file under
`ledgerPrefKey(key)` = `mls_fetch_ledger_<canonical conversation key>`:

```
1|<elapsedRealtime>:<callerOrdinal>,<elapsedRealtime>:<callerOrdinal>,...
```

Oldest first. `decode` drops entries whose ordinal this build no longer has (a charge for a door
that no longer exists is uncountable, and refusing the whole record would deny every other caller),
refuses more than 256 entries, and refuses a malformed entry. `charged` prunes to the window first
and trims from the oldest at 256. `pruned` returns `this` when nothing changed so readers skip a
write. The ledger is not cleared when a conversation is torn down: a rebuild forgets the
conversation and immediately reads again, so clearing on teardown would return the allowance at the
moment a rebuild loop needs it withheld.

**Coverage table.** `MlsFetchLedgerCoverage` records, per caller, which refusal arms have been
exercised and on which call sites, with the host test that covers each claim and a falsifier.
`MlsFetchLedgerCoverageGuardTest` holds each row to a call-site count taken from the source.

### What a rebuild spends, in order

`MlsConversationRebuild.rebuildConversation` passes every bound before it destroys anything, and the
order decides what a refusal costs:

1. **The server pack** (`serverPackForRebuild`, charged to `REBUILD`): one fetch serves both the
   roster and the carried GroupInfo. Its `MlsServerPackOutcome` says whether a read was spent
   (`spentALook()`):

   | outcome | spent a read |
   |---|---|
   | `FETCHED`, `SERVER_HAD_NOTHING` | yes |
   | `NOT_A_GROUP` (a 1:1), `NO_LOCAL_STATE_TO_ASK_WITH`, `NOT_ASKED_BY_DESIGN`, `REFUSED_BY_LEDGER` | no |
   | `LOOK_FAILED` | unknown (`null`): the throw may come from either side of `mayFetch` |

   `NO_LOCAL_STATE_TO_ASK_WITH` returns before the ledger is consulted, so a conversation whose
   local group is already gone cannot exercise the `REBUILD` refusal here.
2. **G1 for every member** (`allowedToJoinAll`), asked while the conversation is still intact.
3. **The episode suppressor** (`claimRebuildEpisode`), before any allowance is charged.
4. **The opening look** (`lookServerEraEpoch`, `REBUILD`): does the server still hold the
   conversation? A ledger refusal here returns `DEFERRED_BY_OUR_OWN_LEDGER`, not
   `REFUSED_BY_GUARD`: an unasked question would classify as a first create and be charged nothing.
   This look is charged before the era budget, so a refused attempt costs no era slot.
5. **The era budget (G2)**, only when `MlsReestablishPolicy.classify(...).chargesEraBudget()` says
   this is a re-creation. A rebuild over a conversation the server still holds re-Welcomes every
   member, the same cost as an era advance; a first create is free. Whether it is a re-creation is
   asked of the server, never inferred from having a carried GroupInfo (a 1:1 rebuild never has one,
   and neither does a re-joining group). `MlsRecreationEpisode` answers a separate question,
   whether this episode has already paid
   ([below](#one-recovery-episode-several-doors-mlsrecreationepisode)).
6. **The rebuild limiter** (`MlsRebuildLimiter`), after the era budget, which refills faster and is
   the cheaper one to waste.
7. The forget (provider record, then app state) and the create.
8. **The confirming look** (`serverStateCheck`, `REBUILD`). A refusal here returns
   `RAN_BUT_UNVERIFIED`.

Neither `DEFERRED_BY_OUR_OWN_LEDGER` nor `RAN_BUT_UNVERIFIED` sets `needsAPerson()`: both are our
own window, and a stall alert would ask a person to act on something time already fixes. In
`reconcileAction`, every rebuild is paired with `MlsStallAlert.surfaceIfNobodyElseWill`, which
raises the stall notification only for an outcome that needs a person, and a converged drive clears
it with `stallCleared`.

## KeyPackage claims: `MlsClaimLedger`

A claim removes a consumable from somebody else's device. A peer with no claimable KeyPackage cannot
be added, re-Welcomed or brought into a rebuilt group, so draining a peer's pool makes a
conversation unrepairable from our side.

**One spend point, two spellings.** The provider's two claim calls reach the same server claim for
the peer, and that claim consumes one package per device of the peer whichever spelling asked; the
singular form only discards the extra packages afterwards. So `Primitive` has exactly one constant,
`CLAIM_KEY_PACKAGES`, and `AIDL_SPELLINGS` lists the two app-side names the source scan keys on:
`claimPeerKeyPackagesWithOutcome` and `claimPeerKeyPackages`. `RETIRED_AIDL_SPELLINGS`
(`claimPeerKeyPackage`) must appear zero times in the transport; keeping the name listed is what
keeps a reintroduced direct call from spending a pool with the guard green. The singular call
survives only inside `ProviderTransport`, as the plural's last fallback, behind a wrapper that has
already charged. The two wrappers are `MlsClaimLedger.claimOne` (over
`claimPeerKeyPackagesWithOutcome`) and `MlsClaimLedger.claimAll` (over `claimPeerKeyPackages`),
each the single invocation of its spelling, reached through the port's `rpc(what)`. The
engine-side callers, `MlsKeyPackageClaims.claimForUpgrade`
(`UPGRADE_PROBE`) and `MlsKeyPackageClaims.gatherInitialKeyPackages` (`GROUP_ESTABLISH`), reach
`claimAll` through `MlsShellPort`; `MlsKeyPackageClaims.logClaimBlocked` logs a refusal in the
wording `MlsClaimLedger` chose.

This ledger is keyed per peer (one pool per peer), where `MlsFetchLedger` is keyed per conversation
(one rate window per conversation).

A per-conversation claim ledger would give a peer in three conversations three allowances.

**Callers.**

| `Caller` | ration | charged from | notes |
|---|---|---|---|
| `ENSURE_READY` | 2 | `MlsOneToOneGroup.ensureReady` (1:1 initiator create) | one create and one retry after a create the server discarded. On the `reestablishOutbound` path the durable 10-minute re-establish cooldown is tighter; the ordinary 1:1 send and the rebuild path do not pass that cooldown, so this ration is what bounds them. |
| `REBUILD_RECREATE` | unrefusable | `ensureReady` reached from `rebuildConversation` with `stateAlreadyDestroyed` | the rebuild has already dropped our state and a claim's product is the input the re-create is made of; there is nothing to fail open to. It still charges, so a rebuild loop is visible here; the rebuild limiter and the era budget are what stop it. |
| `GROUP_ESTABLISH` | 2 | `gatherInitialKeyPackages` (from `establishGroup`), once per member | uses the plural spelling, so a member with two devices gets two leaves |
| `UPGRADE_PROBE` | 1 | `claimForUpgrade`, from `MlsConversationOpenListener.upgrade` | the tightest ration, and at most `GROUP_ESTABLISH`'s. A successful upgrade needs exactly one charge per peer (the create consumes this claim through `MlsUpgradeClaim`). A group whose upgrade is refused for another reason stays upgradeable, and the state-based throttle never stops it, so every open would claim again at the rate a person taps a conversation list; the second open inside the window is refused. |
| `ADD_MEMBER` | 2 | `addMember` | reached from a person's action, so one retry must work |
| `ERA_ADVANCE` | 2 | `claimRosterForAdvance` (from `eraAdvanceLocked`), once per member of the new roster | the era budget (2 per hour, 5 per day) binds long before this |
| `DEBUG_KP_COUNT` | unrefusable | `dumpKeyPackageCount` | operator probe; it really does consume what it reports on |
| `DEBUG_CLAIM_KP` | unrefusable | `debugClaimAllKeyPackages`, the debug receiver's claim arm | listed separately so the log says which arm spent it |
| `DEBUG_CTRL` | unrefusable | `debugClaimOneKeyPackage`, the debug receiver's control-plane smoke test | |

The unrefusable set is exactly `REBUILD_RECREATE` and the three debug callers, and an unrefusable
caller may be passed only from `ensureReady`, `dumpKeyPackageCount`, `debugClaimAllKeyPackages` and
`debugClaimOneKeyPackage`. Try again never resets this ledger: our own cooldowns are ours to
forgive, a peer's pool is not.

There is no "outside the ceiling" flag: the only reader that could be starved is the operator's
count, and it is already unrefusable.

**Where this departs from the fetch ledger.** Debug arms are unrefusable, as there, but they are
charged: a read against a rate window can be exempt, while a claim that happened and was not counted
leaves the next production caller told the pool is fuller than it is. `Verdict.charges()` is defined
as `permitted()`, and `MlsKeyPackageClaimLedgerGuardTest` pins that identity.

**Window and ceiling.**

| constant | value | reason |
|---|---|---|
| `MlsClaimLedger.WINDOW_MS` | 10 min | the same value as the per-peer re-establish cooldown (`MlsReestablishPolicy.REESTABLISH_COOLDOWN_MS`), which is the other bound on this resource. Deliberately not the same constant: they bound different things. No server-side claim throttle is known. |
| `MlsClaimLedger.SHARED_CEILING` | 4 | above the heaviest correct sequence against one peer (an upgrade: `UPGRADE_PROBE` then `GROUP_ESTABLISH`), with two claims of headroom; and at most our own replenish threshold (`MlsKeyPackagePolicy.KP_REPLENISH_AT` = 3) worth of packages removed per window. Both anchors are arguments rather than observations. `MlsKeyPackageClaimLedgerGuardTest` pins the ceiling at or above `UPGRADE_PROBE + GROUP_ESTABLISH`. |

`mayClaim` has the same shape as `mayFetch` without the ceiling flag. Unrefusable callers get
`SPEND_UNREFUSABLE`.

**Charge first, refund only what provably never happened.** `spendOneClaim` writes the charge before
it calls the provider, for the usual reason (a charge written after the claim is lost to a crash,
and that error spends somebody else's key material). The provider reports an outcome with the
result; `OUTCOME_NOT_ATTEMPTED` asserts that no request left the device (unbound provider,
forbidden answer), and only that outcome is refunded, by `notAttemptedAfterCharge`. A
transport failure is not refunded: a failure raised after the provider sent the request is
indistinguishable from one raised before. A refund is not a nicety: four phantom charges from
not-attempted claims inside the window would make the ledger refuse a real claim just as the
provider comes back. The refund re-reads the record rather than reversing a local copy, so a charge
another caller wrote in between survives. If the re-read is unreadable nothing is written, and the
log line carries `NO_COUNT` rather than claiming a refund.

The provider shim (`ProviderTransport.claimPeerKeyPackages`) follows the same rule: it makes its one
extra call, the older plural claim, only after `OUTCOME_NOT_ATTEMPTED`, and dials nothing after that
call, since an empty answer or a `RemoteException` from it can follow a real request. It reports the
provider's outcome, or asserts only `NOT_ATTEMPTED` or `SERVED` itself (`SERVED` only when it holds
the packages); it never asserts `PEER_HAS_NONE`.

**What an empty claim means.** `Claim<T>` distinguishes `refusedByLedger` (nothing claimed),
`notAttempted` (nothing sent) and `asked` (sent). An empty answer alone cannot tell the peer having
nothing from our own credentials being refused or the request going to the wrong key directory, so
the provider reports an outcome in spec-level terms rather than transport status codes (a carrier
provider can report the same outcomes). `attributionOf` reduces it to an `Attribution`, and
`describeEmptyClaim` logs it:

* `PEER_HAS_NONE` (only for `OUTCOME_PEER_HAS_NONE`): the server answered and returned nothing for
  this peer. Still not a statement about the pool's contents. Three causes produce it and one is
  not the peer's: they published none, their pool is drained, or their pool is intact and withheld
  because its credential is inside the RCC.16 30-day floor (Annex A.4.2.2, which reaches a client's
  claim through RCC.16 §5.3, not through A.4.2's own scope list). One-time and last-resort packages
  share the device's single leaf certificate, so an ageing certificate withholds the whole pool
  while the peer still advertises support.
* `NOT_ABOUT_THE_PEER`: every other outcome (refused, transport failed, never sent). An empty
  `OUTCOME_SERVED` also lands here: the server served and our unpack produced nothing, which is a
  fault on our side. `SERVED` says nothing about certificate age either; `keyPackageUsable` checks
  that.
* `UNKNOWN`: no outcome at all (the older plural claim reports none); the line names every
  possible cause.

The provider's free-text detail is appended to the line marked "do not parse" and is never branched
on. `Claim.attribution()` is read only where a line explains an empty claim (`blockedByEmptyClaim`,
`describeEmptyClaim` and the `*BlockedLine` helpers), because a claim can return packages together
with a non-`SERVED` outcome. Every transport site that refuses for lack of a KeyPackage composes its
own consequence plus the one shared attribution clause from `blockedByEmptyClaim`.

`CLAIM_REFUSED` (-1) is what `MlsUpgradeClaim.count()` reports when the ledger refused. It is
distinct from 0, which means every participant was claimed for and one had nothing; reporting 0 for
our own refusal would make `MlsUpgradePolicy` blame participants for a count nobody took.
`MlsUpgradePolicy` tests `CLAIM_REFUSED` before the shortfall check and answers
`CLAIM_REFUSED_BY_LEDGER`, never `NOT_ENOUGH_KEY_PACKAGES`.

**Unreadable record** is treated as fully spent, as for the fetch ledger: the strict direction for
someone else's key material. An unrefusable caller still runs and the log says the count is now
short by one. As with the fetch ledger, a refusable caller is refused once and the record is then
discarded, so the next claim starts from an empty ledger. Charges are written with `commit()`.

**Persistence.** `MlsClaimLedgerRecord`, in the transport's preferences file under
`claimLedgerPrefKey(peer)` = `mls_claim_ledger_<peer>`, in the same
`1|<elapsed>:<callerOrdinal>,...` form. `refunded(caller, chargedAt)` removes the newest entry
matching both the caller and the stamp (two callers can share a millisecond), and returns the record
unchanged when there is no such entry, so a double refund credits once and a refund of a pruned
charge credits nothing. It does not prune: `r.charged(c, t).refunded(c, t)` reproduces
`r.pruned(t)`, except for a record already at the entry cap. The record outlives conversation
teardown for a stronger reason than the fetch ledger's: a peer does not get its packages back
because we dropped a conversation.

**The upgrade claims once.** `MlsUpgradeClaim` carries the packages the upgrade-on-open claimed to
`establishGroup` on the same stack, so a successful upgrade costs one claim per participant rather
than a count followed by a second claim. Nothing is cached: a cache under the AIDL boundary would be
invisible to the ledger, and a reader that did not delete what it read could hand one KeyPackage to
two Welcomes. `take` consumes, and "not covered" (`null`) is kept apart from "the peer had none"
(an empty list). `gatherInitialKeyPackages` honours a refused pre-claim before it would claim
anything itself, and consumes a covered member through `covers()` and `take()`.

## Peer-facing state changes: `MlsStateChangeGate` and `MlsPeerGuard`

Every operation that transmits an MLS state change passes a gate before it can reach a peer. Purely
local cleanup (forgetting a conversation, its resend history, its rendezvous state) is never gated:
it is how we stop talking to a peer whose client is already stuck.

### The four guards

| guard | what it checks | store |
|---|---|---|
| G6 freeze | `debug.rcs.mls_freeze_state_changes` stops every state change | system property |
| G4 peer health | the peer has reported `MAX_CONSECUTIVE_FAILURES` (3) consecutive failures of our messages on its side | `MlsPeerHealthRecord` |
| G1 peer allowlist | debug builds: the peer is in `persist.rcs.mls_allowed_peers`, if set | property |
| G2 era budget | at most `MAX_PER_HOUR` (2) and `MAX_PER_DAY` (5) re-creations per group, or per peer for a 1:1 | `MlsEraBudgetRecord` |

### Which guards each operation takes

`MlsStateChangeGate.guardsFor(Tier)` is the table; `decide(Tier, Facts)` consults exactly those
guards in order and stops at the first refusal. `MlsPeerGuard` gathers the facts, performs the store
effects and logs; it does not restate the table.

| `MlsPeerGuard` entry point | `Tier` | guards, in order |
|---|---|---|
| `allowSelfDeparture` | `SELF_DEPARTURE` | G6 |
| `allowStateChange` | `ORGANIC_STATE_CHANGE` | G6, G4 |
| `allowDebugStateChange`, `allowJoiningPeer` | `ALLOWLISTED_STATE_CHANGE` | G6, G4, G1 |
| `allowEraAdvance` | `ERA_ADVANCE` | G6, G4, G2 |

* Leaving is gated by G6 only. G1 would refuse our escape from a group containing a peer outside the
  allowlist, and G4 would refuse it precisely when a peer looks stuck, which is when a person most
  wants out.
* Organic protocol operations take no allowlist, because real users message arbitrary numbers; the
  freeze and the peer-health streak still apply. Refusing a remove of a peer outside the allowlist
  would trap the one peer that most needs to be out of the group.
* Anything a debug arm initiates, and bringing any peer into a group, takes the allowlist.
* G1 restricts only a debug build whose `persist.rcs.mls_allowed_peers` is non-empty
  (`MlsStateChangeGate.allowlistFor`). A user build never reads the property and admits every
  peer, and an unset or empty list admits every peer too. There is no built-in list.
* The era tier takes G2, not G1. A group re-creation pays both separately: G2 once at the rebuild or
  era-advance funnel, G1 once per member through `allowedToJoinAll`.
* G4 blocks only the operations routed through these entry points (re-creations, joins, debug state
  changes). Ordinary sends and resends continue, so a peer can acknowledge and clear the streak.

### Outcomes

`MlsStateChangeGate.Outcome`:

| outcome | meaning |
|---|---|
| `ALLOW` | proceed; nothing to record |
| `CHARGE_AND_ALLOW` | proceed only after `record.charged(now)` is on disk. The era tier never yields `ALLOW`, so an uncharged re-creation is unrepresentable. |
| `REFUSE` | `guard()` and `reason()` name the gate and why; `discardRecord()` asks the host to drop an unreadable record |
| `UNANSWERABLE` | a tier with no row, or a fact the row needs was not supplied. Treated as a refusal and as a defect; never guessed in either direction. |

`RecordState` separates `NOT_CONSULTED`, `PRESENT` (a record storing nothing is present with a zero
value) and `UNREADABLE`. An unreadable G4 or G2 record refuses once and is discarded, so the next
attempt is judged on an empty record: refusing forever would turn a format skew into a dead
conversation, and reading it as empty would restore an allowance on a parse error.

### The era budget key

`MlsStateChangeGate.eraBudgetKey(groupId, peer)` returns the RCS group id, or `peer:<digits>` for a
1:1 (no group id), or `KeySource.NONE`. A conversation without a group id still has a cost; it is
charged to the peer. `normalisePeer` reduces a number to digits and tolerates a missing or extra
country code, so format cannot evade a budget. No key refuses (`NO_BUDGET_KEY`): an operation that
cannot be bounded is deferred.

### `MlsEraBudgetRecord`

Two rolling bounds, 2 per hour and 5 per day. Stored as `1|<elapsed>,<elapsed>,...`, oldest first,
at most 64 entries accepted; `charged` prunes to the day window and keeps at most `MAX_PER_DAY`
stamps. The wall clock is not stored alongside the stamp, so it cannot be used by mistake.

### `MlsPeerHealthRecord`

Counts consecutive operations the peer itself reported as failed (RCC.16 §7.7.2.2 negative-delivery
reports), fed by `onPeerReportedFailure`. Only observed negatives feed it: inferring failure from
silence (no receipt, an expired gate, a Welcome never applied) fires on any peer that was offline
for an afternoon. It is cleared by affirmative evidence that the peer processed something of ours (a
positive receipt, or a commit or proposal the peer processed), not by mere presence, because a stuck
peer keeps sending failure reports.

| constant | value | meaning |
|---|---|---|
| `MAX_CONSECUTIVE_FAILURES` | 3 | the trip point; the streak saturates here |
| `EVIDENCE_MS` | one day of uptime | how long a streak outlives the last report supporting it |
| `STAMP_COALESCE_MS` | 60 s | once saturated, the evidence stamp is refreshed at most this often, so a burst of reports is not a burst of synchronous writes |

Durability and decay are one decision: an in-memory streak already decays, arbitrarily, at every
restart; persisting it with no bound would stop automatic repair indefinitely. The evidence window
bounds how long a claim about a peer's health outlives the observation behind it, and a still-stuck
peer re-arms it by reporting again. Stored as `1|<streak>@<lastFailureElapsed>`.

### Storage

`MlsPeerGuard` keeps its records in its own preferences file, `mls_peer_guard`, with one key prefix
per format so that caller-supplied key spaces cannot collide:

| prefix | record | key |
|---|---|---|
| `era/` | `MlsEraBudgetRecord` | group id or `peer:<digits>` |
| `health/` | `MlsPeerHealthRecord` | normalised peer |
| `episode/` | `MlsCooldownRecord` (rebuild episode) | canonical conversation key |
| `reestablish/` | `MlsCooldownRecord` (1:1 re-establish) | normalised peer |

* Writes use `commit()`, not `apply()`: the loop these records bound can kill the process, so the
  charge must be on disk before the operation begins. The cost is bounded by the budgets themselves,
  and writes that would not change the stored value are skipped.
* It is not a per-conversation record: a rebuild starts by forgetting the conversation, and a budget
  cleared by teardown would erase its own evidence.
* With no application context, it degrades to a process-local map and says so once. Refusing every
  state change instead would also refuse the remove that gets a stuck peer out of a group.

### What an era advance is charged for: `MlsEraAdvanceCharge`

G2 is charged at the `eraAdvance` funnel, above the branch that picks an implementation. That is
exact, not conservative: the era is an RCC.16 GroupContext extension (0xF001) that a
GroupContextExtensions proposal may not change or remove, so the only way to move it is to build a
new group, which Welcomes every member. The default mode is the create (`MODE_CREATE`,
`MlsConfig.DEF_ERA_ADVANCE_MODE`). The two "preserving" modes (`MODE_PRESERVE`,
`MODE_PRESERVE_CTRL`) are refused by the engine (`commit_era_advance` always returns an error)
before anything is built, and their `BUILD_FAILED` falls through to the create in the same call.
`basisFor(mode)` gives each declared mode a `Basis` (`RE_WELCOMES_EVERY_MEMBER` or
`FALLS_BACK_TO_A_RE_CREATION`); an undeclared mode returns `null` and `chargeableAtTheFunnel`
refuses it by name rather than charging it for an unstated reason.

### One recovery episode, several doors: `MlsRecreationEpisode`

Several paths reach G2 within one recovery episode.
`priorCharge(spent, consumedByAnAcceptedRecreation)` classifies what the episode has already paid,
and `needsItsOwnCharge` decides:

| edge | `PriorCharge` | charges again? |
|---|---|---|
| `rebuildConversation` → `ensureReady` | `SPENT_AND_UNCONSUMED` (charged on the classification, nothing sent yet) | no |
| `ensureReady` era-bump arm → its reclaim arm (reached only when the bump's create was refused) | `SPENT_AND_UNCONSUMED` | no |
| `eraAdvance` → the rebuild fallback (the create was accepted but the server's era did not move) | `SPENT_AND_CONSUMED` | yes |

The fallback edge charges twice by design: to get there the advance claimed a KeyPackage per member,
built a Welcome for each, and had the create accepted. Whether a member re-joined on it cannot be
determined from here, and an outcome that cannot be determined is not treated as the cheap one.

Whether a rebuild is a re-creation at all (and so owes G2) is a different question, answered by
`MlsReestablishPolicy.classify(...).chargesEraBudget()` from what the server holds; the episode
classification only decides whether that charge has already been paid.

## Rolling windows: `MlsWindowBudget`

`MlsRebuildLimiter` and `MlsExternalCommitBudget` are the same shape (decode, roll, refuse if spent,
otherwise charge and store) with different numbers, so the policy is one class and the two are store
adapters. The adapter reads a string, calls `claim(key, store, raw, now)`, performs
`Decision.storeAction()` (`NOTHING`, `WRITE`, `DISCARD`) and logs. The key is derived by the
adapter; `claim` only refuses a missing key (`NO_KEY`).

| budget | allowance | window | posture with no store | unreadable record |
|---|---|---|---|---|
| `MlsWindowBudget.REBUILD` (`MlsRebuildLimiter`, file `mls_rebuild_limiter`) | `REBUILD_MAX_PER_WINDOW` = 3 | `REBUILD_WINDOW_MS` = 6 h | `REFUSE` | `REFUSE_AND_DISCARD` |
| `MlsWindowBudget.EXTERNAL_COMMIT` (`MlsExternalCommitBudget`, file `mls_external_commit_budget`) | `EXTERNAL_COMMIT_MAX_PER_DAY` = 50 | 24 h | `REFUSE` | `REFUSE_AND_DISCARD` |
| `MlsPeerGuard` (not a window; listed for its posture) | | | `DEGRADE_LOCAL` | `REFUSE_AND_DISCARD` |

* Three rebuilds per six hours covers "the first one raced something" without letting an
  unrepairable conversation spin.
* 50 external commits per group per day is RCC.16 §11.2.2 and is not a tuning knob. It is a separate
  allowance from the era budget and must never share a counter with it: the value of an external
  commit is that it can repair a group the era quota has stranded. Sharing the record type is not
  sharing the counter; the two adapters use separate preference files.
* The only mint of an external commit (`MlsSession.externalCommitResync`) is inside
  `MlsExternalCommitResync.resyncViaExternalCommit`, which calls `xcBudget().claim(key)` before the
  mint and the RPC and returns on refusal. Only a dry run skips the charge, and it returns before
  the mint. The operator's resync arm is charged, unlike the debug fetches. The key is the canonical
  conversation key, the same one `resetExternalCommitBudget` and `MlsStalledNotifier` use.
* `OnNoStore.ALLOW_UNCOUNTED` exists only so a test can assert that no shipped budget declares it.
* `Outcome.CHARGE_AND_ALLOW` always carries `WRITE` and a value, so an adapter cannot proceed
  without recording. `DEGRADE_TO_LOCAL_STORE` is an instruction to decide again against a
  process-local store, not an allow. A posture with no arm yields `UNANSWERABLE`.

**`MlsRebuildWindowRecord`** stores `1|<count>|<windowStartElapsedMs>` (`-` for a window not yet
opened), with the count capped at 1024 on decode. A charge never moves the window start, so the
refill counts from the first rebuild in a window, not the last; `remainingMs` may overstate
the wait and never understates it. The window is monotonic rather than wall-clock because the loop
it bounds is a process crash loop, which `elapsedRealtime` survives; the large wall-clock jump comes
just after a boot with a dead real-time clock; the reboot price is lifted by Try again; and a
rebuild also charges the monotonic era budget, so both bounds must read the same clock. It also
recognises the older unversioned `<count>:<wallStart>` form and adopts it by keeping the count and
opening a fresh window at the current reading. That can only make the throttle last longer than the
wall clock would have, never shorter; discarding the record would hand the allowance back on an
upgrade.

### Cooldowns: `MlsCooldownRecord`

Two gates are a single stamp and a window, stored as `1|<lastAttemptElapsedMs>` under
`mls_peer_guard`:

| cooldown | window | purpose |
|---|---|---|
| rebuild episode suppressor (`claimRebuildEpisode`) | `MlsWindowBudget.REBUILD_EPISODE_MS` = 60 s | one burst of traffic (several sends each running the ladder) must not spend hours of rebuild allowance on one fault. A suppressed attempt does not charge the rebuild budget: it is the same repair not being started twice. |
| 1:1 re-establish (`claimReestablishAttempt`) | `MlsReestablishPolicy.REESTABLISH_COOLDOWN_MS` = 10 min | most re-establish attempts claim one of the peer's KeyPackages. It meters attempts rather than claims (two arms return before claiming), which over-protects the pool and never under-protects it. |

Both are durable for the same reason as the budgets: a crash-restart loop is a burst whose
suppressor would otherwise reset on every restart. The re-establish stamp is keyed by the
normalised peer, because it protects that peer's pool. A stamp ahead of the current
`elapsedRealtime` means a reboot, and `MlsMonotonicAge` answers the current uptime. `NONE` (nothing
stored) is distinct from unreadable (`null`) and reads as the oldest possible attempt; 0 is a real
stamp, taken just after a boot, and a negative stamp is unreadable.

## Other bounds

These are described with the mechanisms they belong to; they are listed here so every rate in the
MLS subsystem is in one place.

| bound | value | class | see |
|---|---|---|---|
| self-heal attempts per conversation | 5 per rolling 24 h (`debug.rcs.mls_self_heal_retries`, `debug.rcs.mls_self_heal_window_s`) | `MlsConfig`, `MlsConversationRecord.selfHealBudget`, `MlsSelfHeal` | [health-and-recovery.md](health-and-recovery.md) |
| drive loop iterations | 10 | `MlsDriveLoop.DEFAULT_MAX_ITERATIONS` | [health-and-recovery.md](health-and-recovery.md) |
| unmoved looks before a non-designated member takes over an era advance | presence looks + (rank − 1) × base, base `debug.rcs.mls_era_yield_looks` = 3 | `MlsAdvancerElection.looksBeforeTakeover` | [group-lifecycle.md](group-lifecycle.md) |
| looks per drive | 2 | `MlsFetchBudget.RECOVERY_LOOKS` | above |
| retry ladder | 3 steps of 30 s, then linear 300 s × (attempt − 3); at most 1000 attempts per item | `MlsRetryPolicy` | [health-and-recovery.md](health-and-recovery.md) |
| RCC.16 §10.3 resends per peer per conversation | 2 per hour | `MlsResendBudget` | [health-and-recovery.md](health-and-recovery.md) |
| failure-report escalation | after 2 repeat resends to one peer within 1 h, repair the group instead; an RCC.16 §10.3 report chain stops after 5 attempts (`MlsConfig.ftdMaxAttempts`) | `MlsFtdEscalation` | [health-and-recovery.md](health-and-recovery.md) |
| resends held at a closed send gate | 16 per conversation; gate deadline 90 s | `MlsRecoveryPolicy` | [health-and-recovery.md](health-and-recovery.md) |
| our own KeyPackage pool | republish every 24 h, or when the estimate reaches 3; a repair after an unopenable Welcome at most hourly (its trigger is peer-supplied) | `MlsKeyPackagePolicy` | [credentials.md](credentials.md) |

One look is one group-info read, so the advancer's wait is also a fetch budget. The presence term is
1 look when the member ahead was never heard, the base when it is quiet or unknown, and twice the
base when it is active. The stagger is added rather than multiplied so the worst case stays linear
in group size: rank 9 behind an active member waits 2 × 3 + 8 × 3 = 30 looks, not 54.

## The Try again lever

The stalled-conversation notification's **Try again** (`MlsStalledActionReceiver`) is the manual
override for the budgets that fail closed. It resets, then re-drives recovery:

* the self-heal budget (`resetSelfHealBudget`);
* the rebuild rate bound and its episode suppressor, together (`resetRebuildRateBound`: one
  question, "let this conversation rebuild now");
* the era budget (`MlsPeerGuard.resetEraBudget`);
* the peer-health streak for every member (`resetPeerHealth` resolves the group roster, so one
  press clears every member's streak; a button press is not evidence that the peer processed
  anything, so it does not use `notePeerRecovered`);
* the re-establish cooldown (`resetReestablishCooldown`), for the named peer only: the conversation
  that needs it holds no group, so a roster lookup would come back empty, and a group has no such
  cooldown;
* the external-commit allowance (`resetExternalCommitBudget`). It is a spec bound and peer-facing,
  but so is the era budget cleared by the same press; clearing permits attempts without causing any
  (each still needs its own trigger), and a Try again that visibly does nothing on a spent
  allowance is worse. Every lever clears; none decrements.

It deliberately does not touch the fetch or claim ledgers. The fetch ledger protects the server's
window, which a button press does not reset; the claim ledger protects a peer's pool, and handing
back an allowance would hand back packages that are really gone.

## Operator overrides

| property | effect |
|---|---|
| `debug.rcs.mls_fetch_ceiling` | `MlsFetchLedger` shared ceiling; read live |
| `debug.rcs.mls_claim_ceiling` | `MlsClaimLedger` shared ceiling; read live |
| `debug.rcs.mls_freeze_state_changes` | G6: freeze every MLS state change |
| `persist.rcs.mls_allowed_peers` | G1 allowlist, comma-separated numbers; debug builds, empty = none |
| `debug.rcs.mls_self_heal_retries`, `debug.rcs.mls_self_heal_window_s` | self-heal budget |

The ceilings are read on every charge rather than at construction: they matter when someone is
looking at a conversation that has stopped, and requiring a restart would defeat the purpose.

## Tests

| test | pins |
|---|---|
| `MlsFetchLedgerTest`, `MlsFetchLedgerRecordTest`, `MlsFetchLedgerSplitTest` | verdicts, record codec, unreadable posture, the charge point |
| `MlsGetGroupInfoLedgerGuardTest` | only the transport's five `look*` wrappers (one invocation each) and the provider shim may invoke a primitive, and other engine classes are scanned for both `.name(` and `::name`; every caller constant is used; only `PEER_REPORT_VERIFY` fails open; the exempt set is exactly `DEBUG_DUMP` and `DEBUG_HEALTH`, passed only from `dumpGroupExtensions`, `debugServerEraEpoch`, `debugGroupInfo` and `dumpServerValidity`; the `MlsServerPackOutcome.spentALook()` answers |
| `MlsFetchLedgerRationGuardTest` | `COMMIT_OUTCOME_CHECK`'s ration equals its per-invocation charge count times the permitted invocations |
| `MlsFetchLedgerCoverageGuardTest` | every caller has a coverage row whose claims match the source |
| `MlsClaimLedgerAttributionTest`, `MlsClaimLedgerPrefKeyTest`, `MlsClaimLedgerSplitTest`, `MlsClaimNotAttemptedTest` | attribution, keying, refund |
| `MlsKeyPackageClaimLedgerGuardTest` | every claim site is charged; the two spellings are one spend point; every permitted claim charges; only `NOT_ATTEMPTED` is refunded |
| `MlsStateChangeGateTest`, `MlsStateChangeTierWiringTest` | the tier table as behaviour, and that `MlsPeerGuard` asks it |
| `MlsEraBudgetRecordTest`, `MlsPeerHealthRecordTest`, `MlsCooldownRecordTest`, `MlsRebuildWindowRecordTest`, `MlsMonotonicAgeTest` | codecs, windows, the clock rule |
| `MlsWindowBudgetTest` | every declared posture behaves as declared; no shipped budget proceeds uncounted |
| `MlsEraAdvanceChargeTest`, `MlsRecreationEpisodeTest` | the funnel charge and the per-episode reconciliation |
| `MlsPeerReJoinBudgetGuardTest`, `MlsExternalCommitLedgerGuardTest` | every Welcome-minting call and every external commit is charged, before anything is sent |
| `MlsGuardPersistenceTest`, `MlsGateCounterDurabilityGuardTest` | `MlsPeerGuard`, `MlsRebuildLimiter` and `MlsExternalCommitBudget` write with `commit()` and age through `MlsMonotonicAge`; `MlsPendingBodyStore` and `MlsCiphertextCache` stamp with `elapsedRealtime` and expire through `MlsSendRetentionPolicy.expiredMonotonic`, and the sweep passes an elapsed reading (a wall-clock reading against elapsed stamps would empty them); every rebuild in `reconcileAction` is paired with `surfaceIfNobodyElseWill`; the Try again resets reach their stores; every threshold is classified |

See [../testing.md](../testing.md) for how the source-scan guards work.

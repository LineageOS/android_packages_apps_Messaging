<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# MLS: group lifecycle

How an MLS group comes into being, how members join, are added and removed, how we leave, and how
an era advances. The flows live in the engine module and are reached through
`MlsProviderTransport`'s delegates ([transport-and-port.md](transport-and-port.md)); the RCS side of
group membership is in [../rcs/groups.md](../rcs/groups.md). What happens when a group goes wrong
is in [health-and-recovery.md](health-and-recovery.md), and `end_mls` in
[downgrade.md](downgrade.md).

## Vocabulary

| term | meaning |
|---|---|
| canonical key | `"g:" + rcsGroupId` for a group, `"p:" + peerE164` for a 1:1 (`MlsConversationKey`). Every map, lock and in-memory record is keyed by it. |
| MLS group id | For an RCS **group**, the UTF-8 bytes of the RCS group id. For a **1:1**, a lowercase UUID-v4 string (36 ASCII bytes) minted by the engine. Reused across era advances. |
| era | RCC.16 §7.11.1.1: GroupContext extension `0xF001`, `extension_data` a bare big-endian `uint32`. A new group is born at `ERA_INITIAL` = 1; era 0 means "emit no era extension", which peers refuse. |
| epoch | the RFC 9420 epoch inside one era. |
| epoch authenticator | RFC 9420 `epoch_authenticator` of the current epoch, 32 bytes; the value the server and we compare to decide whether we hold the same group. |
| Welcome action | what a group operation *is*, as decided by the engine (below). |

Why the MLS group id of a group equals the RCS group id: the server ties the MLS group to the RCS
conversation by that id, so a create under a random id describes a group the server holds no RCS
state for, and is refused as a mismatched group. An era advance reuses the existing id for the same
reason.

## The era is decided by the engine

The era is immutable for the lifetime of one MLS group instance. RFC 9420 allows a group-context
extension to change only through a GroupContextExtensions proposal, and RCC.16 forbids that
proposal from changing or removing `0xF001` (the engine enforces this in `Rcc16MlsRules`, see
[rust-core.md](rust-core.md#rcc16-rules-inside-mls-rs)). So **an era advance is always a new group
at the same group id**, built with a fresh Welcome for every member.

The host never chooses the era. It hands the engine a state — the group it holds at the group id,
and optionally the server's GroupInfo (`carryGroupInfo`) — and the engine derives the era from the
`0xF001` in those bytes and reports what it did. Era is an input to reads and an output of writes.
If the host chose the number, "which era did we create at" and "which era does the GroupInfo
claim" would be two facts that could disagree, and only the second reaches the server.

`MlsSession.createGroupPlanned(kps, groupIdOverride, carryGroupInfo, kind)` runs the engine's
`plan_group`:

| engine arm | condition | era | Welcome action |
|---|---|---|---|
| 1 | no local group at the id and no carried GroupInfo | `ERA_INITIAL` | `NEW_GROUP` |
| 3 | we hold the group, nothing was carried, and the request is additive (some requested members, by certified MSISDN, are members already and some are not) | unchanged | `NEW_MEMBERSHIP_EXISTING_GROUP` |
| 2 | anything else with prior state | `max(local era, carried era) + 1` | `NEW_ERA_EXISTING_GROUP` |

A carried GroupInfo disqualifies arm 3: fetching server state signals an advance, and quietly
turning a recovery into an add would leave the era where it is. Arm 3 compares members by the MSISDN
certified in their leaf, not by leaf or key, because a participant is a person: a second device of
someone already in the group is not an addition. An unreadable identity counts as new (a redundant
add is harmless; silently dropping someone is not).

The result comes back in the artifact bundle ([rust-core.md](rust-core.md#the-artifact-bundle)):
slot 6 is the era the engine built at, slot 7 the Welcome action, slot 8 the MSISDNs the add
admitted. Every caller routes on the **action**, never on which method it is standing in, and
refuses when the engine names no plan (`era < ERA_INITIAL` or no action).

### `MlsWelcomeAction`

| action | wire | accepted as a join | meaning |
|---|---|---|---|
| `UNKNOWN` | 0 | no | the engine named no action; also what an absent field decodes to, which is why it is not joinable |
| `NEW_GROUP` | 1 | yes | a group that did not exist |
| `NEW_ERA_EXISTING_GROUP` | 2 | yes | a new era of a group we are in |
| `NEW_MEMBERSHIP_EXISTING_GROUP` | 3 | no | members are being added; route to an add, never a join or create |
| `REFRESH_MEMBERSHIP_EXISTING_GROUP` | 4 | yes | same era, membership rebuilt and re-Welcomed |

`requireJoinable` is the single place the accept-set is enforced; it throws for an action outside
it, including an unnamed wire value, with the message
`The commit is not for advancing era, welcomeAction=<n>.` Callers whose contract is a return code
catch it and return failure. The engine never produces `REFRESH_MEMBERSHIP_EXISTING_GROUP` or
`UNKNOWN` outbound; action 4 is accepted inbound only.

Handling action 3 as a join would re-create local state for a group we already hold, discarding our
leaf and every message key; that is why it has its own routing method (`routesToAddMembers`).

## Rollback is part of every commit

The engine applies a commit while building it (it applies the pending commit and writes storage
before returning), so by the time the RPC goes out our epoch has already advanced. A refusal would
leave us an epoch or an era ahead of a group that never took the commit, and every later send
would fail. So every flow that commits:

1. takes `exportGroupSnapshot(gid)` before building,
2. builds and sends,
3. on refusal restores with `restoreGroupSnapshot(gid, snapshot)`,
4. and if the restore itself fails, calls `quarantineIfAheadOfServer`
   (`MlsAheadChainCheck`), which decides what to do with a group that may be ahead of the server
   ([health-and-recovery.md](health-and-recovery.md)).

A snapshot is the group's whole persisted record — state and retained epoch secrets — so a restore
is exact rather than a delete-above-a-mark.

A transport failure and a refusal are different outcomes: `MlsTransportDisposition` classifies the
provider's verdict, and only a transport failure is retried (the snapshot was restored, so the
retry rebuilds from current state rather than replaying a commit the server never took).

### The control message id and the base

`MlsCommitSend.commitAndSend` (rekey, add, remove and leave on an existing group) mints the control
message id **before** building the commit, as
`mls-<what>-<RCS group id, or the peer for a 1:1>-<ms>`: the engine binds that id into the commit's
`AuthenticatedData` together with the group's own era, and the server cross-checks the two, so an id
minted afterwards could never match. A group commit has no peer, which is why the id names the RCS
group. The request also carries the **pre-commit** epoch authenticator, against which the server
validates the commit's base. A local floor precheck runs first
([credentials.md](credentials.md#where-the-floor-is-applied-locally)).

### A silent outcome is not a refusal

Some outcomes say nothing about whether the server applied the commit: no result at all,
`VERDICT_TRANSPORT_FAILED`, `VERDICT_NOT_REGISTERED`, or an unknown verdict
(`MlsCommitApplication.SILENT`). Rolling back on those is the wrong default, because keeping a
commit the server refused leaves us ahead, which the reconcile path rebuilds, while discarding one
it applied leaves us behind an epoch we signed, which nothing repairs. So
`MlsCommitApplication.keepUnacknowledgedCommit` decides instead of rolling back: it reads the
server's era and epoch (charged to `MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK`), and when the
server sits at exactly our post-commit epoch it also compares epoch authenticators
(`serverStateCheck`), because another member's commit at our base epoch reaches the same numbers.
At most one unacknowledged commit is held per conversation (`ConvState.unacknowledgedCommitEpoch`),
so an outage leaves us at most one epoch ahead; later silent commits roll back onto it, and an
accepted commit releases it. A held commit is reported as a failure to the caller, since the
outcome is still unknown, and a reconcile is scheduled. The gate only decides; the single rollback
site stays in `commitAndSend`. Its log line records whether the server was actually asked, because
a ledger refusal and an empty read produce the same reconciliation. Details are in
[health-and-recovery.md](health-and-recovery.md).

## Establishing a 1:1

`MlsOneToOneGroup.ensureReady(conversationId, subId, peers, recreateAlreadyCharged,
stateAlreadyDestroyed)`, called through `E2eeConversationTransport.ensureReady`:

1. One peer only. Record the conversation-id alias both ways.
2. **Cached group:** return ready only if the engine can still load it
   (`MlsGroupState.groupLoads`). A cached entry can outlive the engine state it points at; an early
   "ready" for a group that no longer loads would make every send fail after reporting success. A
   dead entry is dropped and the flow continues.
3. **Adopt the provider's group for this peer** (`getMlsGroupIdForPeer`) if the engine can read its
   era *and* load it. Readable era is not loadable state, so both are checked. If the provider knows
   a group the engine cannot load, the conversation is re-established by an era advance on that
   group id from the server's era and roster (`NORMAL` kind, so a downgrade is not cleared); if
   that fails the conversation stays unusable with the group id intact for a later retry.
4. **Initiate:** claim one KeyPackage for the peer (`claimOne`, charged to the claim ledger;
   [budgets.md](budgets.md)), check it against the RCC.16 consume-side rules
   (`keyPackageUsable`, [credentials.md](credentials.md)), and build with
   `createGroupPlanned(peerKp, groupIdOverride = null)`, which mints a fresh UUID group id. A claim
   refused by our own ledger is logged as such, never as the peer having no KeyPackage.
5. Before creating, one charged, read-only look at the server's era: if the server already holds
   the conversation the flow logs `ESTABLISH-OVER-HELD`, and does not refuse on that reading.
6. `createMlsConversation` through the provider. The initial create names no era of the host's
   choosing: the engine derives it from its own state, and a server refusal is what reveals that
   the server holds something else. A host-picked era can be accepted and silently discarded, which
   is unrecoverable; a refusal is not.
7. After local state loss the server refuses with an era change (`Era changed from N to N`) before
   it will name its group id, so the flow reads the server's era and re-creates at `server era + 1`
   under the same id, to get past that refusal to the one that names the id. If the server names
   its own group id (`Group ID changed from X to Y`), it re-creates at that id and at
   `server era + 1`. These re-creations are the only places the host supplies an era, and both the
   id and the era come from the server's own answer. An era at the `u32` ceiling is refused rather
   than wrapped to 0. Both re-creations make the peer re-join by Welcome, so they spend the
   era-advance budget, **at most once per call** (the reclaim is reachable only through the bump's
   create being refused, so no member re-joined on the bump), and not at all when the caller
   already paid (`recreateAlreadyCharged`, threaded from a rebuild; `MlsRecreationEpisode`). A
   refused create rolls the engine back to the prior snapshot on the reclaim arm, and otherwise
   keeps what was built (`MlsUpgradePolicy.rollBackDiscardedCreate`, below).
8. On acceptance, store the `Group` (group id, peer, era, epoch authenticator) and move health to
   `HEALTHY`.

`stateAlreadyDestroyed` is set by the rebuild path, which forgets both halves of our state before
calling here. On that path a ledger refusal of an era read fails open: declining would leave the
conversation with no provider record, no engine state and no group, and nothing to drive a retry,
which is worse than a re-create at an era the server may have moved past. The claim itself is not
refused on that path (`MlsClaimLedger.Caller.REBUILD_RECREATE`), since the claimed package is what
the re-create is made of; the flow still checks for a refusal there and logs one as a disagreement
between the ledger and its caller, with no fail-open, because the claim's product is the create's
input.

## When a group becomes MLS

A group is created unencrypted: the RCS create carries no MLS control message. It is upgraded when
the conversation is **opened** (`MlsConversationOpenListener`); creating it and sending into it are
not triggers, and a 1:1 is established by the send path instead. The work runs off the UI thread,
and the throttle is state-based (the guards), not a timer. In order:

1. Read the participants and the RCS group id; skip a 1:1.
2. The free guards, through `MlsUpgradePolicy.evaluateBeforeClaiming`: online; not already MLS; the
   conversation's metadata was readable; at least one other participant; no participant is
   unroutable (zero registrations, which is distinct from a lookup failure); our own KeyPackages are
   published; the group is not initializing (the single-in-flight-operation registry); every
   participant may be brought into an MLS group (`upgradeMayWelcome`, the joining gate, ahead of
   the claim so no KeyPackage is spent on a create that cannot happen).
3. One claim for every participant (`claimForUpgrade`, the `UPGRADE_PROBE` ration), kept and passed
   to the create as an `MlsUpgradeClaim` rather than claimed again; a ledger refusal is
   `CLAIM_REFUSED_BY_LEDGER`, never read as the participants being short.
4. `MlsUpgradePolicy.evaluate` with the claim count, then `establishGroup` (next section).

When the answer is `ALREADY_MLS` and `debug.rcs.mls_maintenance_on_open` is on (the default), the
open runs the maintenance pass instead; conversation open is a production trigger for it, and the
pass decides for itself whether anything is due.

## Establishing a group

`MlsGroupEstablish.establishGroup(rcsGroupId, members, carryGroupInfo, preClaimed)`:

1. **Allowlist first.** `MlsMembership.allowedToJoinAll` asks `MlsPeerGuards.allowJoiningPeer` for
   every member, before the session and before any claim. A create Welcomes every member, so it is
   gated exactly like an add. The whole create is refused rather than a member dropped: a group
   smaller than the RCS roster is refused by the server, and a silently omitted member would
   believe they are in an encrypted conversation they cannot read.
2. **Never create over existing state.** If the host holds a group for the key, return its era.
   Creation is the one path that builds a GroupContext extension list from scratch, so running it
   over a downgraded group would drop `end_mls` and re-encrypt a conversation someone left on
   purpose. Reviving is a separate, explicit action ([downgrade.md](downgrade.md)).
3. **Claim every member's packages** (`MlsKeyPackageClaims.gatherInitialKeyPackages`): all devices
   of each member (`claimAll`), or the packages the upgrade-on-open path already claimed
   (`MlsUpgradeClaim`, consumed once, since a KeyPackage is single-use per RFC 9420 §10). Any member
   with no packages, a refused claim, or a package below the credential floor aborts the create.
   Every device of every member gets a leaf in the one initial commit, so one Welcome admits them
   all and the initial MLS membership equals the RCS roster.
4. **Snapshot** the engine's state at the group id (it may hold state the host does not; below),
   and read the current epoch authenticator for the add arm.
5. `createGroupPlanned(kps, rcsGroupId bytes, carryGroupInfo, NORMAL)`. A carried GroupInfo is used
   when this is a rebuild of a conversation the server still holds: it makes the engine derive
   `server era + 1` from bytes and inherit the group metadata extensions.
6. **Route on the action.** Action 3 goes to `MlsMembership.addMembersToExistingGroup` (below).
   Any action outside the accept-set is refused and the engine state rolled back.
7. Check that the `0xF001` inside the produced GroupInfo equals the era the engine reported, and
   refuse if the create produced no Welcome.
8. `createMlsConversation` with the RCS group id as the context id. On refusal, roll back.
9. Read the server's era. If it answers a different era, the create was accepted but not applied:
   roll the engine back and ask to be re-Welcomed (`MlsRecoveryPolicy.requestReWelcome`, the RCC.16
   §7.7.2.2 signal). A member that holds no state can neither create (discarded) nor advance
   (nothing to advance from); it has to be re-admitted by a member that is current.
10. **Verify, then adopt** (next section).
11. Move health to `HEALTHY` and record the roster the group was built around.

### Verify, then adopt

An RPC that returns OK is not evidence that the server applied the operation, and equal era and
epoch numbers are not equal state: two members can both be at era 2 epoch 1 in different groups.
Only the epoch authenticator answers "are we in the same group".
`MlsWelcomeAdmission.serverStateCheckFor(caller, rcsGroupId, peer, mlsGroupId)` compares ours
(read under the lock) with the server's (read outside it) and answers one of four values:

| `ServerState` | meaning | establish does |
|---|---|---|
| `MATCHES` | our state is the server's current state | adopt the group (`MlsRecordState.adoptGroup`) |
| `DIFFERS` | the server holds another state | `healOntoServerGroupOrUnadopt` |
| `UNKNOWN` | we could not ask, or the answer was empty | refuse, adopt nothing |
| `REFUSED_BY_LEDGER` | our own fetch ledger declined the read; nothing was asked | refuse, adopt nothing |

`UNKNOWN` and `DIFFERS` are kept apart because they are evidence about different things (our
network versus the peer's state), and `REFUSED_BY_LEDGER` is kept apart from `UNKNOWN` because it
is evidence about neither — it points at our own budget. There is deliberately no boolean form of
this check.

The group id is a parameter (rather than resolved through `getGroup`) so the check can run
**before** adoption. Adoption is the first irreversible step: once the host owns a group,
`establishGroup` short-circuits on it and reports its era as success, and inbound Welcomes stop
reaching `joinFromWelcome` for it. Adopting before verifying would make a fork permanent.

`healOntoServerGroupOrUnadopt` is the one arm that must adopt before it knows, because self-heal
resolves its group through the host; it captures the adoption first (`captureAdoption`), runs
`selfHeal`, and gives the adoption back (`rollBackAdoption`) when the heal does not converge.

The engine is not rolled back on `DIFFERS`, `UNKNOWN` or `REFUSED_BY_LEDGER`: the engine may hold
more than the host, but the host must never hold what was not verified.

### A create the server refused

`MlsUpgradePolicy.rollBackDiscardedCreate(session, gid, snapshot)` restores the snapshot when there
was prior engine state. When there was none it keeps what the engine built: retained engine state
is what makes a later create at that id plan as an era advance (`NEW_ERA_EXISTING_GROUP`) rather
than a fresh group, and a fresh group is the one classification that cannot succeed for a
conversation the server already holds. The consequence is that the engine can hold a group the host
does not; `forget` and the add arm below both account for that state.

## Adding members

Two paths:

* **`MlsMembership.addMember(rcsGroupId, peerE164, newMemberE164)`** — one member. Checks the join
  allowlist, claims one KeyPackage (`claimOne`, `ADD_MEMBER`), checks the credential floor, then:
  for an MLS group, `addMemberToMlsGroup`; otherwise it makes the RCS roster change and commits with
  `commitAndSend`.
* **`addMembersToExistingGroup`** — `establishGroup`'s action-3 arm, reached only when the engine
  holds the group and the host does not. Every route into that state is one where the host declined
  to own what the engine built (a refused create with no prior state, an unverified establish, a
  heal that did not converge), so the state this commit sits on has never been agreed with the
  server. It sends the engine's add commit with the RCS add of exactly the members the engine
  admitted (`art.admittedMembers`), rolls back on refusal, and then runs the same verify-then-adopt
  as the create.

`mlsMembershipChange` (shared by add and remove) builds the MLS commit and ships it **in the same
provider request as the RCS roster change** (`addGroupUsersMls` / `removeGroupUsersMls`), so the
roster and the commit cannot be applied separately. The control-message id is minted before the
commit is built and reused for the request: the engine binds it into the commit's
`AuthenticatedData` and the server cross-checks the two, so an id minted afterwards can never match.

mls-rs forces an UpdatePath (rotating our leaf) for a remove but not for an add-only commit, so
`sendsSinceLeafRotation` resets after a remove and keeps counting after an add
([credentials.md](credentials.md#rotating-our-leaf-key)).

After a successful change the recorded roster (`membershipHistory` in `MlsConversationRecord`) is
updated from the change just made, as a new `(era, epoch)` entry. With no recorded baseline for the
era nothing is written, since deriving from nothing would invent a roster; the maintenance pass
establishes one from the server.

When the RCS change succeeds and the MLS commit then fails, the RCS change is not rolled back. A
rollback is itself a roster change that can fail, and half-unwinding it is worse than a known
forward state; the next commit reconciles from the RCS state that exists
(`rcsMembershipChange`).

## Removing members

`MlsMembership.removeMember(rcsGroupId, peerE164, memberSigPub, removedE164)`:

* gated by `MlsPeerGuards.allowStateChange`;
* for an MLS group with a known MSISDN, the engine removes **by certified MSISDN**
  (`removeMemberByMsisdn`), which removes every leaf of that participant in one commit and removes
  the participant the RCS half names. Removing by signature key with an empty key means "the sole
  other member", which in a larger group removes an arbitrary leaf; that form is only the fallback
  when no leaf carries the MSISDN.
* A removal produces no Welcome and no ratchet tree, and none are sent.

Leaf identity is compared through `MlsTreeLeaf.isIdentity`, which delegates to
`RccIdentity.msisdnEquals`: the engine's leaf SAN MSISDN has no leading `+` while the host's own
identity carries one, so a plain `String.equals` would never match. Nothing is normalised into
storage.

### Departures we learn about from a commit

A peer's by-reference `self_remove` proposal is swept into our next commit (or theirs) and mls-rs
drops the proposer's leaf. `MlsMembership.applyDepartures` then compares the MLS roster before and
after the commit, removes the departed members from the recorded roster, and tells the conversation
through `applyGroupDeparture` (the same action the RCS group event uses, with the same
de-duplication signature, so both planes reporting one departure yield one status line). This is the
only notice when the departure arrived on the MLS plane with no RCS group event behind it. It is
best-effort: it runs on the inbound control path and must never fail control handling. The diff is
needed because a swept `self_remove` commit looks like a plain rekey. A refused proposal commit
counts as a retry of the pending operation, so the retry limit can be reached.

Inbound by-reference proposals are routed on their type (`MlsStateChangeGate.onInboundProposal`,
proposal code points, a separate space from the extension code points):

| Proposal | Handling |
|---|---|
| `self_remove` (`0xF003`) | Queued for commit (`COMMIT_PENDING_PROPOSALS`): the leaver cannot commit its own removal. |
| `end_mls` (`0xF001`) | Queued as an `END_MLS` operation and committed together with the `end_mls` GroupContext extension ([downgrade.md](downgrade.md)). |
| `server_remove` (`0xF004`), unknown types | Dropped: RCC.16 §9.5.1 gives removal authority to the server's notification, not to a peer's proposal. |

Dropping is damage control: a cached proposal blocks every application message, and committing an
unimplemented one would change nothing while reporting success. The drop clears the whole mls-rs
proposal cache, so it is skipped while a `COMMIT_PENDING_PROPOSALS` or `END_MLS` commit is owed
(that commit sweeps the unhonourable proposal harmlessly, and a clear would lose the proposal it
exists to honour) and after our own leave (it would drop our `SelfRemove` and un-leave us). A commit
result is never routed here: it has no proposal type, and routing it would clear the cache a pending
`self_remove` needs.

## Leaving

MLS does not let a member remove itself in its own commit, so leaving is a by-reference
**SelfRemove proposal** that another member (or the server) commits.

`MlsMembership.leaveGroup(rcsGroupId)` is the routed entry point a menu calls. It returns
`PLAINTEXT` (the caller makes the plain RCS removal), `APPLIED` or `REFUSED`, and it is gated by
`MlsPeerGuards.allowSelfDeparture` only, the kill switch: the peer allowlist would refuse leaving a
group that contains an unlisted peer, and the peer-health streak would refuse it exactly when the
peer is wedged. A 1:1 cannot be left; `end_mls` is the verb there.

`MlsMembership.leave(rcsGroupId, peerE164)`:

1. If the record already says we left, succeed.
2. Under the lock: snapshot, build the SelfRemove with the engine (`selfLeave`), with a control id
   minted first. With no snapshot it refuses to propose, since a refusal could then not be undone.
3. Outside the lock: `selfLeaveGroupMls`.
4. On refusal restore the snapshot. The engine caches the proposal it built, and a cached
   `SelfRemove` blocks every send (a commit is required, and MLS forbids committing your own
   removal), so an unrestored refusal would leave every send blocked; if the restore itself fails,
   the proposal-drop lever (`MlsStateChangeGate.dropPendingProposals`) is the way out, and it
   refuses once the record says we left.
5. On success, `MlsRecordState.recordSelfDeparture` writes, in order: the terminal `selfLeftAtMs`
   mark; an empty recorded roster (what we hold is nothing); the release of the pending-operation
   slot (every pending kind ends in a commit of ours, which mls-rs cannot build while our
   `SelfRemove` is cached); and finally the deletion of the engine group, last because the roster
   write reads era and epoch from it. The leaver never receives the commit that removes it, so no
   engine state could agree with the group, and deleting it also ends access to the epoch secrets
   and the leaf key. Then `announceSelfDeparture` clears the conversation's MLS bit (an unreadable
   bit is cleared too) and posts the "You left" status line: the group-event action with requester
   and affected both us.

Both writes are needed because nothing comes back: the server notifies the members who remain, so
the leaver never sees an echo. Without the terminal mark a group we left is indistinguishable from
a wedged one, and the recovery paths would try to repair it by putting us back in. Clearing the MLS
bit is done directly, not through `downgradeLocally`: leaving is not a downgrade, has no downgrade
reason code, and must not schedule a re-upgrade.

The mark is a record field, not a health state: the health states mirror the engine's, which have
no self-leave flow, and borrowing `DoneEndMls` would make revive, the downgrade funnel and the
re-encryption rule reason about an `end_mls` commit nobody made. Everything that could re-create a
group we left checks `MlsRecordState.weLeft`: the maintenance pass refuses on it before reading the
roster (an empty recorded roster minus the server's would read as all-new and trigger an era
advance), the floor rebuild, the upgrade guards and the UI actions. `MlsServerBundle.ourRoster`
reads a zero-length stored roster, which only `recordSelfDeparture` writes, as "no baseline", so the
first maintenance pass after a rejoin records the server's roster instead of treating every member
as new.

The mark is cleared only when a Welcome for the group is accepted: in `applyInboundControl`'s
`NEW_GROUP` arm and in `rejoinOnEraAdvance` once its verdict keeps the join
(`clearSelfLeftOnRejoin`). It is never cleared in `adoptGroup`, which also runs on the create path
(an era advance must not un-leave us), nor in `joinFromWelcome`, whose Welcome may have been minted
for another new member. A failed clear is logged at error level, because every self-leave guard
would keep declining a group we are back in; the recorded roster stays empty and is re-established
from the server.

The host `Group` entry and the record of a left group are kept indefinitely. `recordFor` resolves
through `getGroup`, so deleting the entry would make `weLeft` answer false and re-open every guard
above (and an upgrade on the next open), and deleting only the record would mint a not-left initial
one. A retention policy would need the mark stored outside the record.

The 1:1 overload `MlsMembership.leave(shell, log, peerE164)` passes no group id and therefore always
refuses.

## Joining

Inbound control reaches `MlsCommitApplication.applyInboundControl`:

* our own control fanned back to us is ignored (it was applied when sent);
* **no group resolves** for the sender and RCS group → `MlsWelcomeAdmission.joinFromWelcome`;
  success is the join (`NEW_GROUP`), failure drops the control;
* **a group resolves** and the payload contains a Welcome → `rejoinOnEraAdvance`;
* otherwise the payload is processed as a commit or proposal on the existing group.

The provider relays server bundles as protobuf, and `MlsServerBundle.recognise` finds the MLS
messages in them structurally: a server acknowledgement arm is treated as handled; a single-message
arm is taken verbatim; otherwise a bounded breadth-first walk over length-delimited fields (depth 6,
512 nodes) collects MLS messages, as they are or with an `mls_varint` prefix stripped, de-duplicated
in first-seen order. When a Welcome appears anywhere in the bundle, or nothing parses, the whole
bundle is forwarded, because a tree-less Welcome's ratchet tree rides in a sibling field.

### `joinFromWelcome`

1. Find the Welcome inside the delivered payload (`MlsWireScan.findWelcome`).
2. Try `joinTreelessWelcome(welcome, blob)` first, then plain `join(welcome)`. RFC 9420 makes the
   `ratchet_tree` GroupInfo extension optional; some senders omit it and ship the tree as member
   LeafNodes in the same payload, and a plain join of such a Welcome fails with
   `RatchetTreeNotFound`. The splice rebuilds the tree from those LeafNodes
   ([rust-core.md](rust-core.md#treeless-welcome)). The condition is protocol-general, not tied to a
   vendor.
3. **If neither join works**, republish our KeyPackage pool
   (`MlsKeyPackagePool.republishPoolAfterUnopenableWelcome`). A peer builds a Welcome only after
   claiming one of our published packages; if we cannot open it, the directory is serving a package
   whose private half this engine does not hold, and every peer that claims one will fail the same
   way. Group-state recovery cannot fix a pool problem. The republish has its own one-hour gate,
   separate from the ordinary publish gate, because the trigger is peer-supplied: any payload that
   looks like a Welcome and fails to join reaches it, the trigger is not classified by cause, and
   the gate is the only bound.
4. Adopt the group (`MlsRecordState.adoptGroup`), which also collects the continuity token captured
   from the Welcome ([metadata.md](metadata.md)).
5. **Drop everything parked for this group** in the durable pending queue. A Welcome puts us on the
   group's chain at the server's current epoch; controls parked earlier were framed against the
   branch we just left, and replaying them would fork the group at an equal epoch number
   (`dropParkedOnJoin`). The application messages among them are read before the clear and
   reported to their senders (§7.7.2.2), so they resend at the new moment.
6. Cross off the consumed KeyPackage by its KeyPackageRef, or decrement the pool estimate when the
   ref cannot be matched ([credentials.md](credentials.md)).

Every native join path (plain, with tree, treeless, and the external-commit join) also clears the
group's retained prior-epoch archive before writing the joined state; see
[rust-core.md](rust-core.md#storage) for why a re-joiner that kept it would have its next commit
refused.

### `rejoinOnEraAdvance`

A Welcome for a group we already hold replaces live state, so a stale or replayed Welcome would be a
downgrade. The flow snapshots, joins, and keeps the join only if `MlsWelcomeAdmission.decide`
says so:

| outcome | verdict | kept |
|---|---|---|
| the join produced no group for us | `NOT_ADDRESSED_TO_US` — the Welcome is for a member being added; the commit riding with it is ours to apply | no (caller continues) |
| joined, era unreadable | `REJECT_ERA_UNREADABLE` | no |
| era advanced | `ACCEPT_ERA_ADVANCE` (no server round trip) | yes |
| same or earlier era, server `MATCHES` | `ACCEPT_REFRESH` | yes |
| same or earlier era, server `DIFFERS` | `REJECT_REPLAY` | no |
| same or earlier era, server `UNKNOWN` or `REFUSED_BY_LEDGER` | `REJECT_UNVERIFIED` | no |

The server is asked only when ordering cannot answer (`needsServerConsult`): a refresh that
rebuilds the group restarts its epoch, so a legal refresh can land at a lower epoch than the one we
hold, which by ordering alone is indistinguishable from a downgrade. An era advance is
self-evidently forward and costs no round trip on the inbound path.

A rejected join restores the snapshot and the host's previous `Group`. An accepted one clears the
self-left mark, then drains the deferred controls parked for the group at the new moment
(`MlsInboundHold.drainDeferredControl`); anything that still does not apply is re-parked by the
normal path and aged out.

### External-commit join

`ProdSession::external_join` and `external_commit_resync` join from a published GroupInfo rather
than a Welcome. Whether a member may do that depends on the transport
(`RcsMlsTransportProfile.acceptsMemberExternalCommit`); the resync flow is
`MlsExternalCommitResync` ([health-and-recovery.md](health-and-recovery.md)). A joiner needs the
group's `external_pub`: with `debug.rcs.mls_publish_external_pub` on (default off) our rekey commits
publish a GroupInfo carrying it, tree-less, with the post-commit ratchet tree as a separate field;
without it nobody can resync-join our groups by external commit.

## Era advance

Entry: `MlsEraAdvance.eraAdvance(rcsGroupId, peerE164, carryGroupInfo, kind,
requireRebuildableRoster)`. It is the funnel every caller passes through, so the charges below
cannot be bypassed.

### Kinds

`MlsAdvanceEraKind` is a mode rather than a guard, so every caller states what the advance does to
`end_mls` (`0xF002`):

| kind | mode byte | `0xF002` |
|---|---|---|
| `NORMAL` | 0 | carried forward unchanged, so a downgraded group stays downgraded |
| `REVIVAL` | 1 | removed |
| `PHOENIX_DOWNGRADE` | 2 | installed; the new era is born downgraded |

An unknown mode decodes as `NORMAL`. The mode byte is persisted in a pending operation and crosses
the FFI boundary, so it must not be renumbered. See
[downgrade.md](downgrade.md#advance-kinds-and-0xf002).

`MlsAdvancePurpose` states the other half of an advance's contract: key packages are supplied **if
and only if** the purpose is `ERA_ADVANCEMENT`. An era advance with none would "succeed" into an era
containing only us while every other member still sees the conversation; an epoch advance or a
Phoenix run with some would add members as a side effect of recovery. Both throw rather than being
ignored.

### The funnel

1. **Mode check.** `MlsEraAdvanceCharge.chargeableAtTheFunnel` refuses an undeclared
   `debug.rcs.mls_era_advance_mode` value, so an unrecognised number cannot acquire a behaviour.
2. **Era-advance budget.** `MlsPeerGuards.allowEraAdvance`, an hourly and daily budget per
   conversation (`MlsEraBudgetRecord`). Every advance forces every member to re-join by Welcome;
   tripping the budget means something upstream is wrong (a peer that never joins, a fork that never
   converges) and is fixed there, not by raising the budget. See [budgets.md](budgets.md).
3. **One advance at a time.** The conversation's pending-operation slot is claimed
   (`MlsPendingOperation.claimAdvance`) with a context naming the kind and the peer, and released in
   a `finally`. Several recovery triggers can fire while an advance runs, and two advances mint two
   eras and fork the group. A conversation that does not resolve is refused rather than claimed
   under a null key, which would put every unresolvable conversation into one shared slot.

### `eraAdvanceLocked`

1. Resolve the group; the local era comes from the engine.
2. Read the server's era (charged to the fetch ledger). `base = max(local era, server era)`, so a
   member behind the server advances past the server's era rather than to an era the server already
   holds. `MlsAppMessage.nextEra` refuses to pass the `u32` ceiling: a wrap would read to every peer
   as a move backwards and cannot be undone.
3. **Roster.** For a group, the member list comes from the server's GroupInfo pack
   (`fetchServerPack`, `MlsServerBundle.rosterFromPack`); an unreadable server roster aborts the
   advance. For a 1:1 the peer is the roster. The roster is recorded.
4. **Claim** one KeyPackage per member (`MlsFloorRebuild.claimRosterForAdvance`). Every caller
   measures claimed certificates against the credential floor; only a floor rebuild
   (`requireRebuildableRoster`) refuses on it, since a recovery that proceeds around an ageing
   certificate still carries messages while a floor rebuild exists to leave the group committable
   ([credentials.md](credentials.md)).
5. Snapshot. If no GroupInfo was passed in, carry the server's. Check the server state with
   `MlsGroupInfoGate` (four ordered rejections: `EMPTY_GROUP_INFO`, `EMPTY_RATCHET_TREE`,
   `EMPTY_LATEST_EPOCH_AUTHENTICATOR`, `EMPTY_PAGINATED_EPOCH_IDENTIFIER`), so a missing field is
   named here rather than surfacing as a codec error or as `RatchetTreeNotFound` at the peer. Our
   own epoch authenticator is passed for both identifier slots, because the pack does not separate
   them, so the gate cannot prefer one; an incomplete state is logged and the advance proceeds,
   since the carried GroupInfo may cover it. (Where a response does separate them, the server's
   `latest_epoch_identifier` wins over our flat `(era, epoch)` pair,
   `MlsGroupInfoGate.preferredEpochIdentifier`; that rule is applied where the response is parsed.)
6. `createGroupPlanned(kps, gid, carry, kind)` reusing the group id. The engine inherits every
   GroupContext extension of the carried GroupInfo except `0xF001` (set to the new era) and, for
   `REVIVAL`, `0xF002`; it is a deny-list so extensions this build does not know ride along
   byte-for-byte. The server refuses an advance that drops committed metadata.
7. `createMlsConversation` with the new era. On refusal, roll back (or quarantine).
8. **Verify** the server's era. An OK from the create is not proof: the server assigns the era,
   and a create can be accepted while the era does not move ("accepted, not applied"), so the
   read-back is the only discriminator. If the fetch ledger refuses that read, the advance returns
   `ERA_ADVANCE_LOOK_UNAVAILABLE` without rolling back or rebuilding: rolling back an advance that
   in fact took would manufacture a divergence.
9. **Fresh context id.** A create that reuses a context id the server already holds returns OK and
   does not move the era, and an era advance sends the stored id by default. So when the create was
   accepted, the era was read back and it did not move, `MlsFreshContextRetry` re-dials once with
   the same artefacts and a freshly minted id (18 random bytes, base64url without padding: 24
   characters, the only shape the provider honours for a supplied id), then re-reads the era. No
   KeyPackage is claimed and no second budget charge is taken. The re-dial is on by default
   (`debug.rcs.mls_advance_fresh_ctxid_retry=0` disables it) and is skipped where the provider's own
   setting `debug.rcs.mls_advance_fresh_ctxid` could already have minted for this create (`all`, or
   `1to1` on a 1:1; `off`, empty and unrecognised values mint nothing; matching ignores case). An
   accepted re-dial whose result cannot be read is reported as unverified, like step 8.
10. If the era still did not move, roll back. When the rollback succeeded and
    `debug.rcs.mls_advance_create_fallback` is true (the default), fall back to
    `MlsConversationRebuild.rebuildConversation` (below): it drops the local group and
    re-establishes, which lands at `server era + 1` where the advance could not, because
    `ensureReady` is a no-op while a group exists and its era-reclaim arm runs only with none. The
    fallback costs every member a re-join by Welcome and claims a KeyPackage per member again; the
    advance's budget charge is already consumed, so the rebuild is charged as a second re-creation.
    The advance logs the two settings when it does not take
    (`MlsEraAdvance.eraAdvanceLeverDiagnostic`) but does not decline on them: for the automatic
    callers the destructive rebuild is the repair that works, and declining would turn "expensive
    but it recovers" into "stuck". Only the operator-triggered floor rebuild declines up front
    ([credentials.md](credentials.md#groups-no-commit-can-reach-the-floor-rebuild)).
11. On success update the `Group` (era, epoch authenticator, both send counters reset: the new group
    gives us a new leaf) and record the roster.

Return values: the new era; `-1` for a refusal or a failed build; `ERA_ADVANCE_LOOK_UNAVAILABLE`
(`-2`) when the fetch ledger declined a read the advance needs, or an advance that cannot be
verified; `MlsFloorRebuild.ERA_ADVANCE_ROSTER_NOT_READY` (`-3`) when a floor rebuild's claimed
roster is not above the floor. All are negative, so any `< 0` check reads them as "did not
advance".

`eraAdvancePreserving` and the `ERA_MODE_PRESERVE*` modes build the advance as an in-group commit.
The engine refuses to build that shape (the era cannot change within a group), so those modes return
`MlsEraAdvance.BUILD_FAILED` and the funnel falls through to the create above in the same call. That
is why the budget can charge the full re-Welcome cost before the mode is known. `BUILD_FAILED` is
also `-2`, the same number as `ERA_ADVANCE_LOOK_UNAVAILABLE`; it is consumed inside
`eraAdvanceLocked` and never returned from it.

`MlsAdvancerElection` decides who advances and when;
[health-and-recovery.md](health-and-recovery.md).

## Rebuilding a conversation

`MlsConversationRebuild.rebuildConversation` forgets both halves of our state and re-establishes
the conversation. Its triggers and outcomes are in [health-and-recovery.md](health-and-recovery.md)
and its charges in [budgets.md](budgets.md); this section is what it does to the group.

1. **Roster first**, since after the forget there is nothing to derive it from
   (`MlsServerBundle.rosterForRebuild`): the server pack's roster, else the last recorded
   membership, else the RCS conversation's participants, which survive a forget of all MLS state. A
   group with no roster is not rebuilt. The same one fetch supplies the GroupInfo to carry.
2. The join allowlist for every member, asked while the conversation is still intact.
3. Classify (`MlsReestablishPolicy.classify`) from a read of the server's era: the server holds the
   conversation (every member must re-join, charged as an era advance), holds nothing (a first
   create, free), or did not answer (charged).
4. Two refusals **before anything is dropped, claimed or charged**:

   | `MlsRebuildOutcome` | Condition | Why |
   |---|---|---|
   | `WOULD_FORK_AT_ERA_INITIAL` | a group, the server holds it, and there is no GroupInfo to carry (`MlsReestablishPolicy.forksAtEraInitial`) | with no local group and no carry the engine plans era 1: a second group beside the server's. When the server is at era 1 the post-create check passes and the fork is adopted permanently; above era 1 the create is rolled back after both halves of local state were dropped. No server era makes it a repair. |
   | `RECREATE_WOULD_NOT_TAKE` | a group, the server holds it, and the re-create would reuse the server's context id (`reCreateWouldNotTake`) | the server does not apply such a create at any era. |

   The second asks a current member to re-admit us instead (`requestReWelcome`, the RCC.16 §7.7.2.2
   channel). A group re-create always reuses the id while the provider derives a group's context id
   from the RCS group id, which is the same at every era; that holds while
   `debug.rcs.mls_ctxid_group_engine_id` is on (default 1), and the provider chooses it before its
   own store is consulted, so a rebuild that deleted the provider's record still reuses it. Setting
   it to 0 is the operator override. A 1:1 rebuild drops the provider record first, so its re-create
   mints a fresh id and is never refused this way. `debug.rcs.mls_advance_fresh_ctxid` is inert on a
   rebuild (it needs the provider record the rebuild has just deleted), and the fresh-context-id
   re-dial belongs to the era advance only.
5. The budgets, then **forget both halves**: the provider's record
   (`mlsForgetGroupConversation` for a group, which returns its postcondition;
   `mlsForgetConversation` for a 1:1, which resolves only by peer) and ours (`forget`). Clearing
   only the engine half would re-establish into the old group id by era advance.
6. Re-establish: a group through `establishGroup` with the server's GroupInfo carried, so the engine
   plans `max(local, carried) + 1` and inherits the group's metadata extensions (a carry-less create
   would drop the subject commitment, which the server refuses); a 1:1 through `ensureReady` with
   `stateAlreadyDestroyed`, which reaches `server era + 1` through its era-reclaim arm.
7. Verify by epoch authenticator, not by the create's return value.

## Persistence

| what | where | shape |
|---|---|---|
| MLS group state and retained epoch secrets | engine files under `<filesDir>/openmls_store/<identity>/` | one file per group, written atomically ([rust-core.md](rust-core.md#storage)) |
| KeyPackage secrets | the same directory, `kp_<ref>.bin` | one file per package |
| per-conversation record (`MlsConversationRecord`) | `mls_conversation_record` preferences, key `<identity>/<groupIdHex>` | one Base64 blob, written whole |
| conversation → group alias | `mls_conversation_alias` preferences (`MlsRecordStore.putAlias`) | per identity; removed by value (below) |
| transport scalars | `mls_provider_conv` preferences (`key.gid`, `.peer`, `.rcsgid`, `.era`, `.auth`, the `PREF_*` stamps) | removed on `forget` |

The record is keyed `(identity, group id)`, not by conversation: the same RCS group under two
identities is two MLS groups. Because an era advance reuses the group id, the record survives the
advance, which is what the continuity token needs. The record is written whole, never partially
updated, and never indexed or queried; `dumpRecords()` is the debug view. It holds, among other
things, health, the pending-operation slot, the membership history per `(era, epoch)`, the
self-left mark and the continuity token.

The record is `[version][varint payload length][payload]`, the payload a list of numbered fields.
Version 2 adds the length frame; version 1 (unframed) is still read, never written, and upgraded on
the next write, since refusing it would reset every existing conversation to `Unknown`. Unknown
fields are skipped, retired field numbers are never reused, and the version is bumped only for a
change the decoder cannot absorb that way. Selected fields:

| field | holds |
|---|---|
| 1 | health status |
| 3 | the pending-operation slot |
| 13 | the continuity token ([metadata.md](metadata.md#the-continuity-token)) |
| 14 | membership history per `(era, epoch)` |
| 15, 29 | retained epoch authenticators, and the era they belong to |
| 24, 27 | `sendsThisEpoch`, `sendsSinceLeafRotation` |
| 28 | `selfLeftAtMs` |
| 18 | reserved (pending queues are not stored in the record) |

The authenticator map is restored without the era-aware setter, because the era field (29) is
decoded after the entries and that setter would clear them.

`MlsGroupState.putGroup` writes the in-memory row, the alias and the record under the conversation
lock. The record write is best-effort: a record failure must not break a send.
`MlsRecordState.ensureRecord` persists the initial record on adoption, so a group that never had a
health transition still has somewhere to write its membership history (without it the maintenance
pass could not detect a roster change); it writes only when the record is absent, never over an
unreadable one, and makes no health claim.

`MlsRecordState.rollBackAdoption` gives back exactly what `adoptGroup` wrote (the in-memory row, the
alias and the record, the durable two only if that adoption created them, per the `captureAdoption`
taken immediately before) and then re-reads `getGroup` to prove the host is free. It does not use
`clearConversationState`, which would also drop the parked queue and the re-upgrade backoff, and it
leaves engine state alone.

Alias rows are removed **by value** (`MlsConversationRecord.aliasKeysToForget`, the group id), not
by key: `putAlias` is called with the transport's canonical key while a teardown scope may carry the
app's conversation row id, and a teardown cannot be sure which key space the writer used. The
candidate conversation keys are then tried as well, rows of other identities are never touched, and
only keys actually present are returned, so the caller's count is rows dropped.

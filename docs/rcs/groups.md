<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# RCS groups

An RCS group is a server-side object identified by an opaque group id; the server fans a message
sent to that id out to the members. The app maps each group onto the multi-participant conversation
model upstream Messaging already uses for group MMS, so the existing conversation UI renders it.
Group calls go through the provider (`IRcsProvider` methods 15 to 21 and 25; see
[provider-contract.md](provider-contract.md)). The carrier transport does not implement groups.

## The mapping

| Item | Value |
|---|---|
| Group id | 32 lowercase hex characters, minted by the app (`UUID` without dashes) and echoed by the server in `RcsGroupInfo.groupId`. Always opaque to the app. |
| Local key | `conversations.rcs_group_id`, indexed. Resolved by `BugleDatabaseOperations.getExistingGroupConversation`, read back by `getConversationRcsGroupId`. |
| Thread id | Synthetic and negative, derived from the group id's hash (`syntheticThreadIdForGroup`). A group has no telephony thread; the negative value occupies the `sms_thread_id` slot without colliding with a real thread. |
| Participants | The upstream `conversation_participants` table, self excluded. |

`BugleDatabaseOperations.getOrCreateGroupConversation(db, groupId, name, subId, members,
rosterIncomplete)` is the one writer of the mapping:

* On create: the self number is filtered out of `members`, the conversation is created with the
  synthetic thread id, and `rcs_group_id`, `needs_roster_refill` and (when supplied) the name are
  stamped on the row.
* On an existing conversation: members present in `members` but missing locally are added; nothing
  is ever removed here (removal is the KICK path). A supplied name wins; with no name, the existing
  name is kept, and a generated name from the participants is used only if the conversation has
  never had one. With `rosterIncomplete == false`, `needs_roster_refill` is cleared; it is never set
  on an existing conversation.

The group's title belongs to the group paths. `updateConversationNameAndAvatarInTransaction`, which
upstream rewrites the name from the participant list on every refresh, leaves the name of an RCS
group alone.

## Creation

Only the conversation list's "New group" action (`action_new_group`) creates an RCS group. Picking
several recipients through "Start chat" creates an ordinary group MMS conversation; since the group
send fork below requires `rcs_group_id` to be set already, such a conversation never takes the RCS
path.

`CreateRcsGroupAction` runs off the main thread and returns a conversation id or null. Null means
"fall back to group MMS" and hands the recipients to `onRcsGroupFallbackToMms`.

1. Fewer than two recipients: fall back.
2. Group RCS unavailable for the subscription (`RouteSelector.isGroupRcsAvailableForSub`): fall
   back.
3. Every recipient is canonicalised to E.164 and looked up with `lookupRcsCapability`. Any answer
   other than `CAP_RCS`, including unknown, falls the whole group back to MMS: a group with one
   member who cannot receive RCS would lose that member's messages.
4. Mint the group id and call `createGroup(subId, id, name, members, 0)`. The provider adds self.
   Null or an empty group id falls back.
5. `getOrCreateGroupConversation` with the user's name (else the server's) and the server's roster
   (else the requested one).

No message is sent at creation; an empty group is valid.

## Sending

`InsertNewMessageAction` routes a text draft in a conversation with an `rcs_group_id` through
`tryInsertSendingRcsGroupMessage`, and a draft with one media part through
`tryInsertSendingRcsGroupFile`. The draft's protocol is ignored for this decision: every
multi-recipient draft is MMS-shaped.

1. Group RCS must be available for the subscription, or the draft falls through to MMS.
2. With no group id yet (text only), create the group with `createGroup` and map it onto the
   conversation; a failed create falls through to MMS.
3. `sendGroupMessage(subId, groupId, text, rcsMessageId)`. A rejection falls through to MMS.

The send is synchronous and no status callback follows it, so the row is written at
`OUTGOING_COMPLETE` / `STATUS_SENT` in the same transaction (see
[architecture.md](architecture.md#send-status)).

Location (`sendLocation` with `isGroup`), reactions (`sendReaction` with a group id), typing
(`sendGroupTyping`) and files (`sendFile` with `RcsOutgoingFile.groupId`) address the group id the
same way.

## Inbound messages

An `RcsIncomingMessage` with a non-empty `groupId` is filed by `ReceiveRcsMessageAction` into the
group's conversation, not into a 1:1 thread with the sender; `fromUri` still names the member who
sent it.

When no conversation exists for the group yet (a content message arrived before the create event,
for example because the app was not bound when that event was delivered), the action asks the
provider for the roster with `getGroupInfo` so the new conversation is not a one-member thread that
the list would render as a 1:1. If that fails, the conversation is created with the sender alone and
flagged `needs_roster_refill`.

### Roster refill

`RosterRefillAction` runs whenever `ProviderTransport` attaches. For each conversation with
`needs_roster_refill = 1` it calls `getGroupInfo` and reconciles with `rosterIncomplete = false`,
which adds the missing members, recomputes `participant_count` and clears the flag. A group that
still returns nothing keeps the flag and is tried again on the next attach.

## Group events

The provider reports server-side lifecycle changes through `onGroupEvent(subId, op, groupId, name,
conferenceUri, requester, members, affectedMembers)`, which `RcsCallbackRouter` hands to
`ReceiveRcsGroupEventAction`. `members` is the current roster (never null, possibly empty);
`affectedMembers` lists who was added or removed.

| `op` | Local effect | Status line |
|---|---|---|
| `GROUP_OP_CREATE` (7) | Find or create the conversation; sync members (from `getGroupInfo` when the event carries none). | "Group created" / "X created the group" |
| `GROUP_OP_ADD_USERS` (8) | Same roster sync. | "X added Y", "X added you" |
| `GROUP_OP_KICK_USERS` (9) | `removeGroupParticipants` for the affected members. | "X left" when requester and affected are the same person (tested first, so our own departure reads "You left"), else "X removed you" or "X removed Y" |
| `GROUP_OP_CHANGE_PROFILE` (10) | Rename when a name is present. | "X renamed the group to N" |
| `GROUP_OP_CHANGE_ROLE` (11) | None. | None |
| `GROUP_OP_CHANGE_INFO` (12) | None: a generic "state changed" echo that accompanies other mutations; it is not a rename. | None |

An event with an empty group id is dropped. Only the primary user persists group events.

### Status lines

A status line is an ordinary received message row with `transport_type = TRANSPORT_RCS_SYSTEM` (2),
which the conversation view draws as a centred, muted line with no avatar or bubble, excluded from
message clustering. Its `rcs_message_id` holds a de-duplication signature, and
`ReceiveRcsGroupEventAction.insertStatusMessage` skips the insert when a system row with that
signature exists:

```
grpevt:<op>:<groupId>:<name>:<requester>:<sorted affected>
```

Requester and affected numbers are canonicalised to E.164 inside `buildEventSignature`, so a line
written locally and the server's echo of the same action produce the same key whatever format each
side used. For rename-class ops (`GROUP_OP_CHANGE_PROFILE`, `GROUP_OP_CHANGE_INFO`) the name is part
of the key and the requester is not, because the echo of our own rename carries no requester. For
add and remove the requester is part of the key and the name is not, because an echo may carry an
incidental group name the local write does not know. Whichever of the local write and the echo
arrives second is dropped.

### Membership: `rcs_self_left`

`conversations.rcs_self_left` is 1 when this line is no longer a member. The participants table
cannot answer that question: `removeGroupParticipants` ignores the self participant by design, so
leaving never deletes our own identity from the conversation. `ReceiveRcsGroupEventAction` is the
only writer:

* set on `GROUP_OP_KICK_USERS` whose `affectedMembers` includes the self number (the whole list is
  checked, not only the first entry);
* cleared on `GROUP_OP_ADD_USERS` or `GROUP_OP_CREATE` that lists the self number in
  `affectedMembers` or `members`. The clear is deliberately wider than the set: a non-member
  receives no such events, and hiding "Leave" from a user who is still in a group is worse than
  offering an action that ends in a refusal.

The column covers our own leave and removal by someone else. `PeopleAndOptionsFragment` reads it,
and on a read failure treats the user as still a member.

## Managing a group

The group section of People & options offers Rename, Add people, Remove (per member) and Leave,
only when `isManageableRcsGroup()` holds: the conversation has an `rcs_group_id`, we have
not left it, and group RCS is available. Each action runs `ManageRcsGroupAction` off the main
thread; a false result reaches `ManageRcsGroupListener.onManageFailed`, which shows a toast.

Leave asks for confirmation first, because it cannot be undone from this device: only another
member can add us back. The fragment writes nothing itself.

There is no Change photo entry: the contract has no group icon call.

| Op | Constant | Value |
|---|---|---|
| Rename | `OP_RENAME` | `GROUP_OP_CHANGE_PROFILE` |
| Add | `OP_ADD` | `GROUP_OP_ADD_USERS` |
| Remove | `OP_REMOVE` | `GROUP_OP_KICK_USERS` |
| Leave | `OP_LEAVE` | 1000, local only |

`OP_LEAVE` is outside the wire range 7 to 12 so it cannot be mistaken for a group event op.

### Provider calls

| Op | Provider call |
|---|---|
| Rename | `renameGroup` |
| Add | `addGroupUsers` |
| Remove | `removeGroupUsers` |
| Leave | `removeGroupUsers` naming our own number, then `GroupDepartureApplier` |

A leave with an unknown self number is refused without a call.

### Mirror after acceptance

The local mirror (`applyLocalMirror`) is written only after the provider call returned true. A
refused change writes nothing, so messaging.db never claims a roster the server does not hold.
Writing first would need an inverse on failure, and there is none that is honest:
`removeGroupParticipants` deletes rows and the add reconcile merges members, so undoing either means
snapshotting first, in code that runs only on failure. The action already runs off the main thread,
so waiting for the answer blocks nothing.

`applyLocalMirror` renames the conversation, reconciles added members through
`getOrCreateGroupConversation(..., rosterIncomplete=false)`, removes members through
`removeGroupParticipants`, then writes a
self-attributed status line ("You renamed...", "You added...", "You removed...") with the same
signature the echo will carry.

A leave calls `removeGroupUsers` with our own number and then `GroupDepartureApplier`,
because `applyLocalMirror` would render "You removed You" and cannot remove the self participant.

### `GroupDepartureApplier`

`GroupDepartureApplier.apply(subId, groupId, departedE164)` records a departure by dispatching an
ordinary `ReceiveRcsGroupEventAction` with `op = KICK_USERS`, `requester = affected = departedE164`
and an empty roster. It is used for our own leave.

* It resolves the conversation without creating one; a departure is never a reason to invent a
  conversation.
* `requester == affected` selects the "You left" line. There is no echo for a self-leave (the
  server tells the remaining members), so this is the only record.
* The empty roster cannot shorten the participant list: the create path only adds members.
* It never throws into the caller; a failure is logged and returns false.

### Conversation icon

`fillParticipantData` writes a participant-derived avatar into `conversations.icon` only over an
empty value or one it produced itself (`AvatarUriUtil.isDerivedAvatarUri`), so a roster change does
not replace an icon another path wrote.

## Receipts, reactions and typing

* **Receipts.** The provider fires `onGroupImdnReceipt` alongside `onImdnReceipt` for every inbound
  receipt, naming the member. `UpdateRcsMessageStatusAction` with `KIND_GROUP_IMDN` upserts
  `rcs_group_receipts(message_id, participant_uri)` with the member's E.164 when the message belongs
  to a group conversation and does nothing otherwise, so 1:1 rows keep their single delivered/read
  state. The group bubble's "read by N of M" is computed from that table.
* **Reactions.** A reaction targets a wire message id and is stored in `rcs_reactions`, one row per
  reactor; `ConversationMessageData` aggregates them by emoji, so a group shows counts per emoji.
* **Typing.** `onGroupTyping` names the group and the member. `ConversationFragment` keeps one
  indicator per sender, each expiring after 10 s, and renders the combined "X, Y are typing" row.

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

How an encrypted group is established, joined, changed and left is in
[../mls/group-lifecycle.md](../mls/group-lifecycle.md). This file covers the conversation side,
which is the same for plaintext and encrypted groups.

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
2. `MlsProviderTransport.groupSendVerdict` decides between sealing, plaintext, and refusing. The
   decision is owned by the MLS layer (`MlsSendRouting`; the table is in
   [architecture.md](architecture.md#the-encryption-gate)); a group that has no MLS state and has
   never latched the MLS bit always gets plaintext. MLS group state is keyed by the RCS group id
   (`MlsConversationKey.canonicalKey`), never resolved through a peer: one person can share both a
   1:1 and a group with us, and only the group id tells the two apart.
3. Seal: `MlsProviderTransport.sendToGroup(groupId, text, rcsMessageId)`. Passing the row's own id
   binds the wire id, the id in the MLS AuthenticatedData and the chat row to one value, so receipts
   correlate and a resend replays rather than re-seals.
4. Plaintext: `sendGroupMessage(subId, groupId, text, rcsMessageId)`. A rejection falls through to
   MMS.
5. Refuse (the conversation is presented as encrypted and cannot be sealed), or a seal that failed:
   `insertRefusedRcsGroupMessage` writes a failed RCS row and returns true, so the draft does not
   fall through to MMS. Falling through would send the same text in the clear over another
   transport. The refused row has no `rcs_message_id` because nothing reached the wire.

Both send arms are synchronous and no status callback follows them, so the row is written at
`OUTGOING_COMPLETE` / `STATUS_SENT` in the same transaction (see
[architecture.md](architecture.md#send-status)). A sealed row also gets
`rcs_e2ee_scheme_id = gsma.rcs-e2ee.mls` so the outgoing bubble shows the padlock.

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

This column covers a plaintext leave and removal by someone else. A successful MLS leave is recorded
in the MLS conversation state (`MlsProviderTransport.haveWeLeft`); the column neither reads nor
writes that mark. `PeopleAndOptionsFragment` combines both, and on a read failure treats the user as
still a member.

## Managing a group

The group section of People & options offers Rename, Add people, Remove (per member), Change photo
and Leave, only when `isManageableRcsGroup()` holds: the conversation has an `rcs_group_id`, we have
not left it, and group RCS is available. Each action runs `ManageRcsGroupAction` off the main
thread; a false result reaches `ManageRcsGroupListener.onManageFailed`, which shows a toast.

Change photo is offered only when the group is on the `MLS` plane
(`MlsProviderTransport.groupPlane`, see [Which plane a group is on](#which-plane-a-group-is-on)),
the only plane `iconChangeRouting` accepts. The fragment asks off the main thread together with the
membership check, and hides the row until the answer arrives or when the read fails.

Leave asks for confirmation first, because it cannot be undone locally: MLS forbids committing one's
own removal, so only another member can add us back. The fragment writes nothing itself; each route
writes its own "You left" line.

Change photo scales the picked image to 512 px on the long edge at JPEG quality 85
(`ICON_MAX_EDGE_PX`, `ICON_JPEG_QUALITY`) before `ManageRcsGroupAction.changeIcon`, so it stays
inside the provider's inbound icon cap (256 KB by default) and the binder transaction limit; the
bytes cross binder twice. The image is read in the fragment, while the activity still holds the
picker's URI grant, not in the action.

| Op | Constant | Value |
|---|---|---|
| Rename | `OP_RENAME` | `GROUP_OP_CHANGE_PROFILE` |
| Add | `OP_ADD` | `GROUP_OP_ADD_USERS` |
| Remove | `OP_REMOVE` | `GROUP_OP_KICK_USERS` |
| Leave | `OP_LEAVE` | 1000, local only |
| Change icon | `OP_CHANGE_ICON` | 1001, local only |

`OP_LEAVE` and `OP_CHANGE_ICON` are outside the wire range 7 to 12 so they cannot be mistaken for a
group event op.

### Routing through the MLS layer first

Every op first asks `MlsProviderTransport` which plane the conversation is on. The answer is a
`GroupMembershipRouting`:

| Answer | Meaning | What the action does |
|---|---|---|
| `APPLIED` | The MLS layer made the change (commit and RCS operation in one request). | Write the local mirror; report success. |
| `REFUSED` | Encrypted, and the change could not be made. | Write nothing; report failure. |
| `PLAINTEXT` | Not an MLS conversation. | Continue with the plain provider RPC. |

| Op | MLS entry point | Notes |
|---|---|---|
| Add, Remove | `changeGroupMembership` | On an MLS conversation the plain membership RPC is refused by the server, so there is no plain fallback. |
| Leave | `leaveGroup` | On `APPLIED` the MLS layer has already written all local state; the action writes nothing more. |
| Rename | `renameGroupRouting` | On an MLS conversation a rename is the encrypted subject (RCC.16 §9.7.1.5). The plain RPC would put the name on the server in the clear. |
| Change icon | `iconChangeRouting` | There is no plaintext icon path, so a plaintext group gets `REFUSED`. |

If the MLS layer cannot be asked at all (it throws), the action refuses. `PLAINTEXT` would send the
bare RPC on a conversation that might be encrypted: for a rename that leaks the name; for a leave it
is irreversible.

`REFUSED` is the verdict on the request as a whole, not a claim that the server is unchanged. If the
group's MLS state is forgotten between routing and the add, the plaintext arm of `addMember` may
already have changed the RCS roster.

#### Which plane a group is on

`MlsMembership.groupPlane` is the one evaluation for add, remove, rename and icon. The latch is
consulted only as the app's own UI state; whether MLS state exists is asked of the engine
(`getGroup`).

| Plane | When |
|---|---|
| `PLAINTEXT` | No MLS session and no MLS identity; or no MLS state for the group and a clear `encryption_protocol` latch. An unreadable latch also reads `PLAINTEXT`, as does a group we were dropped from before any MLS traffic. |
| `UNKNOWN` | An MLS identity exists but the engine would not open a session, so we cannot tell. |
| `MLS_LOCKED_OUT` | No MLS state, but the latch is set (for example after a rebuild, before rejoining): the server and the members still hold the group encrypted and the UI shows a padlock. |
| `MLS_DOWNGRADED` | We hold the group with an `end_mls` status; it is plaintext (RCC.16 §9.1.1). |
| `MLS` | We hold the group. |

It is not a send gate: a lost MLS identity reads `PLAINTEXT` before the latch is read, which is why
content sends use `MlsSendRouting` instead.

| Op | `PLAINTEXT` | `MLS_DOWNGRADED` | `UNKNOWN`, `MLS_LOCKED_OUT` | `MLS` |
|---|---|---|---|---|
| Add, Remove (`changeGroupMembership`) | bare RPC | the commit arm, as for `MLS` | refused | one member per commit; a request naming several is refused |
| Rename (`renameGroupRouting`) | bare RPC | bare RPC (the name is plaintext) | refused | encrypted subject; an empty name is refused |
| Change icon (`iconChangeRouting`) | refused (no plaintext icon path) | refused | refused | encrypted icon; empty bytes are refused |

Leave does not use `groupPlane`: `leaveGroup` is gated by the self-departure kill switch alone, and
a group we hold no MLS state for takes the bare RPC.

The routing lives in `MlsMembership` rather than in `ProviderTransport.addGroupUsers` because
`rcsMembershipChange` calls that method; an MLS-aware wrapper there would recurse and stop only
while two evaluations of "is this MLS" agreed.

On an MLS group the bare add or kick is refused by the server, and the server also refuses a
membership commit for someone not in the RCS roster, so neither half can go first: the roster
change and the commit ride one request (`addGroupUsersMls`, `removeGroupUsersMls`), as an encrypted
subject rides with its commit. A plaintext group keeps the order RCS change first, then commit.
`removeMember` checks the kill switch and the peer-health streak but not the joining allowlist,
which would refuse to remove exactly the peer that must go; it charges the peer who is leaving, and
it opens the MLS session before asking whether the conversation is MLS, because on a cold process
the group is invisible until the session is up.

### Mirror after acceptance

The local mirror (`applyLocalMirror`) is written only after the change was accepted, on every arm:
after `APPLIED`, or after the plain RPC returned true. A refused change writes nothing, so
messaging.db never claims a roster the server does not hold. Writing first would need an inverse on
failure, and there is none that is honest: `removeGroupParticipants` deletes rows and the add
reconcile merges members, so undoing either means snapshotting first, in code that runs only on
failure. The action already runs off the main thread, so waiting for the answer blocks nothing.

`applyLocalMirror` renames the conversation, reconciles added members through
`getOrCreateGroupConversation(..., rosterIncomplete=false)`, removes members through
`removeGroupParticipants`, or applies the icon through `GroupIconApplier`, then writes a
self-attributed status line ("You renamed...", "You added...", "You removed...", "You changed the
group photo") with the same signature the echo will carry.

For a rename and an icon change on an MLS group no inbound path ever applies the result on the
sending device: the key rides in an MLS private message, which the sender cannot decrypt. The engine
applies it on acceptance ([metadata.md](../mls/metadata.md)), and the mirror repeats it with the
status line. Both store the readable name, as the inbound subject path does; only the wire carries
ciphertext.

A plaintext leave calls `removeGroupUsers` with our own number and then `GroupDepartureApplier`,
because `applyLocalMirror` would render "You removed You" and cannot remove the self participant.

### `GroupDepartureApplier`

`GroupDepartureApplier.apply(subId, groupId, departedE164)` records a departure by dispatching an
ordinary `ReceiveRcsGroupEventAction` with `op = KICK_USERS`, `requester = affected = departedE164`
and an empty roster. It is used for a plaintext self-leave and for a member's MLS `self_remove`.

* It resolves the conversation without creating one; a departure is never a reason to invent a
  conversation.
* `requester == affected` selects the "X left" line and produces the same signature as the server's
  echo, when there is one, so the two collapse to one line. For a self-leave there is no echo (the
  server tells the remaining members), so this is the only record.
* The empty roster cannot shorten the participant list: the create path only adds members.
* It never throws into the caller; a failure is logged and returns false.

`MlsMembership.applyDepartures` calls it after a commit that swept a peer's `self_remove`: the
roster delta updates the recorded MLS membership (under the lock, and only against a recorded
baseline) and then applies each departure to the conversation outside the lock. The status line
uses the same action and de-duplication signature as the RCS group event, so both planes yield one
line, and it is the only notice when no RCS event accompanies the `self_remove`. A post-commit
roster that is only partly readable computes nothing, and our own number is never a departure.

### Group icon: `GroupIconApplier`

`GroupIconApplier.apply(context, groupId, iconBytes)` writes the decrypted icon to
`files/mls-group-icons/<groupId>` (the id sanitised to `[A-Za-z0-9_-]`) and points
`conversations.icon` at that file's `file://` URI. It is used for an inbound icon and for the
sender's mirror.

* The file is in `getFilesDir()`, not the cache: a cache eviction would leave the column pointing at
  nothing, and a peer does not resend an icon.
* The file is written before the conversation lookup and kept even when no conversation exists yet,
  because the roster path may create it moments later.
* The column has one owner: `fillParticipantData` writes a participant-derived avatar only over a
  value it produced itself (`AvatarUriUtil.isDerivedAvatarUri`), so a roster change does not
  replace a group's icon.
* The URI never leaves the process, so the `file://` scheme is safe.

The inbound encrypted subject is applied by `MlsSubjectApplier`; see
[../mls/metadata.md](../mls/metadata.md).

## Receipts, reactions and typing

* **Receipts.** The provider fires `onGroupImdnReceipt` alongside `onImdnReceipt` for every inbound
  receipt, naming the member. `UpdateRcsMessageStatusAction` with `KIND_GROUP_IMDN` upserts
  `rcs_group_receipts(message_id, participant_uri)` with the member's E.164 when the message belongs
  to a group conversation and does nothing otherwise, so 1:1 rows keep their single delivered/read
  state. The group bubble's "read by N of M" is computed from that table. A delivered receipt also
  lets the MLS layer release the message's resend material once every member has confirmed.
* **MLS receipts.** A delivered or displayed receipt on an MLS conversation carries exactly two MLS
  CPIM headers, `MLS-Derived-Content-Signature` then `Era-ID` (`MlsReceiptMetadata.headers`). It
  never carries `Epoch-Authenticator`, nor `Original-Message-ID`: the acknowledged id rides in the
  IMDN body, and peers treat a receipt carrying that header as failed. A receipt without a
  signature gets no MLS headers at all, since peers discard an unsigned MLS-tagged receipt.
  Generated headers are merged after the report's existing custom headers, replacing any with the
  same namespace and name. A group receipt is stamped from that group only: with no MLS group held
  for it, `imdnStampsFor` returns null rather than signing with the 1:1 group. Receipt type ordinal
  3 (`DELIVERY_FAILED`) uses the delivery handler; ordinal 0 is refused.
* **Reactions.** A reaction targets a wire message id and is stored in `rcs_reactions`, one row per
  reactor; `ConversationMessageData` aggregates them by emoji, so a group shows counts per emoji.
* **Typing.** `onGroupTyping` names the group and the member. `ConversationFragment` keeps one
  indicator per sender, each expiring after 10 s, and renders the combined "X, Y are typing" row.

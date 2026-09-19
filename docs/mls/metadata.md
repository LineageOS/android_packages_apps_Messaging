<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# Encrypted group subject and icon

On an MLS group the subject (the group's name) and the icon are encrypted end to end
(RCC.16 §9.7.1.4 and §9.7.1.5). The server stores ciphertext; the key travels inside the group; a
commitment in the GroupContext binds the two. This page covers sending, receiving, key storage and
the RCC.16 §10.5 key-recovery rules, plus the two things that share its formats: the continuity
token and sealed chat attachments. The code is `MlsGroupMetadata` (flows), `MlsMetadataKeysPolicy`
(pure decisions and bounds), `RccFileCrypto`, `RccFileInfo`, `RccCommitment` and
`RccGroupMetadataKeys` (spec formats), and `MlsSubjectApplier` / `GroupIconApplier` (writing the
result into the app).

Related: [health-and-recovery.md](health-and-recovery.md) (the pre-send heal),
[group-lifecycle.md](group-lifecycle.md) (era advance carries the metadata forward),
[downgrade.md](downgrade.md#downgrades-a-peer-would-make) (the continuity-token downgrade rule),
[rcc16-map.md](rcc16-map.md), [rust-core.md](rust-core.md) (extension framing in the engine).

## The pieces

| Item | RCC.16 | Format |
|---|---|---|
| Content encryption | Annex C.2 / C.3 | `RccFileCrypto`: fresh 32-byte key; `k_enc ‖ k_hmac = HKDF-SHA256(key, salt = 256-bit pi, Info)`; message padded with Padmé plus `messageLength` and `paddingLength` (u32 each); AES-CTR with a 96-bit random IV followed by four zero bytes; tag `HMAC-SHA256(k_hmac, IV ‖ ciphertext)`. |
| `Info` | §9.7.1.4 / §9.7.1.5 | `"group_icon"` / `"group_subject"` (`RccFileCrypto.INFO_GROUP_ICON`, `INFO_GROUP_SUBJECT`). It is a KDF input, not a label: a different string derives different keys and shows up only as a tag mismatch. |
| Key container | §7.8.1 | `RccFileInfo`: `FileInfo { file = 1, thumbnail = 2, subject = 3, icon = 4 }`, each a `FileMetadata { file_name, content_type, encryption_info }` with `FileEncryptionInfo { key_material, initialization_vector, hmac_tag, algorithm, file_length_hint }`. `file_length_hint` is protobuf `fixed32`, not a varint. Content type `message/mls-rcs-file-info`. |
| Commitment | Annex C.1 | `RccCommitment`: `SHA-256(varint(len L) ‖ L ‖ varint(len V) ‖ V)`, with `L` a bare ASCII label. Compared in constant time (`RccCommitment.verify`). |
| GroupContext extensions | §7.11.3–§7.11.6 | `icon_key` 0xF003, `icon_commitment` 0xF004, `subject_key` 0xF005, `subject_commitment` 0xF006. Each carries its value with an inner varint length, so a 32-byte commitment is 33 bytes of extension data. |
| Key request extension | §7.11.10.1, §10.5.2 | `group_metadata_keys_requested`, 0xF007 by default (below). |
| Key recovery body | §7.13.4 | `RccGroupMetadataKeys { continuity_token = 1, group_subject_icon_keys = 2 (a FileInfo) }`, content type `message/mls-rcs-group-metadata-keys`. RCC.16 v4.0 §7.5.1.2 names the payload variant but not a content type; this one is chosen by analogy with `message/mls-rcs-file-info` and is not confirmed against other clients. |

**The 0xF007 code point and framing.** `MlsConfig.metadataKeysExtType` (key
`debug.rcs.mls_metadata_keys_ext`, read on a debug build only) sets the number, so a peer using
another one can be honoured without a rebuild; `METADATA_KEYS_EXT_UNKNOWN` (0) is a separate
sentinel from the default, and the engine's advertisement and framing rule follow the configured
value. A wrong value fails silently in both directions: we never see the request, or we read another
extension as it. The payload is the 29 ASCII bytes `"group_metadata_keys_requested"`, and presence
is the flag. RCC.16 v4.0 frames it as `opaque<V>`, while other clients write it bare, framed or
empty (29, 30 or 0 bytes) with nothing in band to tell them apart. The engine encodes it bare under
v3.0 and framed under v4.0 (`rcc16::metadata_keys_is_bare`), and decodes all three forms.

**What the commitment covers.** The send path commits to the **key material** with the labels
`"icon_commitment"` / `"subject_commitment"` (`RccCommitment.iconCommitment(key)`,
`subjectCommitment(key)`), and the receive path verifies over the key material it was delivered. The
commitment is what binds the key to the ciphertext; a commitment over the ciphertext leaves the
ciphertext unbound and the request is refused. RCC.16 v4.0 changes both the labels (`"group_icon"`,
`"group_subject"`) and the committed value (the FileEncryption `hmac_tag`);
`RccCommitment.commitmentValue`, `iconCommitment(version, ...)` and
`subjectCommitment(version, ...)` select by `Rcc16Version` so the two cannot be swapped by accident.

**Padmé** is cited by RCC.16 but not restated there, so the rounding in `RccFileCrypto.padme` is an
interoperability assumption. It matters: C.3 rejects a file whose `paddingLength` differs from
`Padme(messageLength) - messageLength`.

The ciphertext blob is the C.2 output as is; the tag travels separately in `FileInfo.hmac_tag`.

## Changing the subject or icon

`MlsGroupMetadata.changeGroupSubject` and `changeGroupIcon` share one flow
(`changeIconOrSubject`). Both are group-only.

1. **Heal before sending** (`healBeforeMetadataChange`). A member that was offline while the group
   moved receives nothing on return and never sees a future-epoch control, so the stale state is
   only visible by comparing epoch authenticators with the server. If they differ, self-heal first;
   if that fails, refuse the change (a peer must re-Welcome us). If our fetch ledger refuses the
   look, proceed unverified: the check is an optimisation, and the server refuses a stale request
   cheaply. Both changes share one ration (`MlsFetchLedger.Caller.SUBJECT_CHANGE`).
2. **One matched set.** Mint the content key, encrypt with it, and compute the commitment over it.
   The key, the ciphertext and the commitment must come from one key; generated independently they
   can never validate.
3. **Commit** the key *and* its commitment (`commitGroupMetadata`) as a GroupContextExtensions
   commit, after snapshotting the group. The current extension list is merged, not replaced.
4. **Key delivery.** Encrypt an RCC.16 §7.8.1 `FileInfo` carrying the key under the **post-commit**
   epoch (the engine has already applied the commit), with the same AAD as the control message.
   Receivers apply the commit first and then decrypt the key. The key-delivery plaintext is
   assembled here, in the host, as an `RccMlsBody` frame; other clients assemble that payload inside
   their MLS engine. The receive side makes the same assumption (below), so a change to one must be
   checked against the other.
5. **One request** to the provider carries the ciphertext, the commit, GroupInfo, ratchet tree,
   epoch authenticator, the key delivery and the control message id:
   `changeGroupSubjectMls` (subject inline) or `changeGroupIconMls` (icon by reference: the
   provider uploads the ciphertext for file transfer and references the URL; the app never sees the
   URL, so the referenced blob and the committed one cannot come from two uploads). The declared
   content type is `message/mls-ft` (`RccFileInfo.CONTENT_TYPE_ENCRYPTED`); the content's own type
   survives only inside the `FileInfo`. One UUID is used both as the control message id and as the
   AAD message id, because the server cross-checks them.
6. **Outcome.** Refused: restore the snapshot. Accepted: record the new era and epoch
   authenticator and reset the send counters (the extension commit carries an UpdatePath, so our
   leaf rotates), then apply the subject or icon to our own conversation (`applyGroupSubject` /
   `applyGroupIcon`). There is no second message: the key went out in the same request.

**The sender cannot decrypt its own subject or icon.** The key rides in an MLS private message and
MLS gives a sender no way to decrypt its own, and the copy the group fans back is not re-processed.
So the engine applies the new name or photo to the sender's conversation in step 6, **only after**
the provider accepted the change, whoever called it; without that the setter would keep seeing the
old value. `ManageRcsGroupAction` also writes its local mirror after acceptance, which repeats the
value and adds the self-attributed status line. Whether a rename or icon change is encrypted is
decided once, by `MlsProviderTransport.renameGroupRouting` / `iconChangeRouting`: an MLS group
never sends its name through the plaintext rename call, and there is no plaintext icon path, so an
icon change on a non-MLS group is refused.

**Confidentiality note.** RCC.16 §9.7.1.4 wants the key extensions (0xF003 / 0xF005) present in the
Welcome but absent from the GroupInfo the server receives. The MLS library cannot build a GroupInfo
from a subset of the GroupContext, so the GroupInfo sent with the metadata commit carries the key.
The engine logs this at runtime; it is a known, unresolved confidentiality gap. RCC.16 v4.0's
`GroupMetadataKeys` delivery (below) is the direction that closes it.

`publishIconSubject` (commitments only, no content and no key delivery) is kept only as a debug
negative control; the server refuses it because it validates the commitment against content in the
same request. Its engine call, `MlsSession.commitIconSubject`, cannot set the key extensions at all.

The subject change is reached from the rename action; the icon change from the group options
screen's photo picker (`PeopleAndOptionsFragment`, through `ManageRcsGroupAction.changeIcon` and
`iconChangeRouting`; offered only on the `MLS` plane, see [groups.md](../rcs/groups.md)), and from
the debug receiver. An empty icon is refused: clearing an icon is a
different operation with no verb in the provider interface.

## Receiving

The ciphertext and its key arrive by **separate, concurrent deliveries**: the provider's
group-profile callback carries the ciphertext (for the icon, after the provider has downloaded it),
and the key arrives in a commit's private message. Either may win, so both halves are built to wait
for the other.

**Ciphertext first.** `onEncryptedSubject` / `onEncryptedIcon` (from
`RcsCallbackRouter.onEncryptedGroupSubject` and `onEncryptedGroupIconContent`) try to open it at
once; if the key is not there yet, the ciphertext is held on the conversation's `ConvState`
(`pendingSubject`, `pendingIcon`).

| Bound (`MlsMetadataKeysPolicy`) | Value | Over-bound behaviour |
|---|---|---|
| `MAX_PENDING_SUBJECT` | 8 conversations holding a subject | Not held. |
| `MAX_PENDING_ICON_BYTES` | 1 MiB of held icon ciphertext, all conversations | The newcomer is dropped; older holds are kept (they are likelier to open soon). |

These bound app-level metadata that may never become openable, not pending MLS messages, which are
never evicted.

**Key first.** A key delivery arrives as the application payload of a control frame
(`MlsCommitApplication.applyOneInboundResult`). Only the host-assembled `RccMlsBody` frame with
content type `message/mls-rcs-file-info` is recognised; any other payload in that slot is dropped
and logged with its length and a byte prefix, so a differently assembled key delivery is never
mis-parsed (the commitment check is over the key material) but surfaces later as a subject or icon
that cannot be opened. `onFileInfo` stores the `FileInfo` and then tries both held items (one
`FileInfo` carries one slot and we do not know which is waiting). A held item is also retried on
every applied commit, because the lookup follows the group's current commitment and a key that
arrives before its commit is only findable once the commit lands. A failed attempt puts the held
item back (within the bound) so the later trigger still has something to retry.

**Opening** (`openStoredIconSubject`):

1. Read the group's **current** commitment for the slot. If it cannot be read, stop; there is no
   fallback to an unscoped key.
2. Look up the stored `FileInfo` filed under that commitment.
3. Verify the commitment over the `FileInfo`'s key material against the same commitment used for
   the lookup (re-reading it could race a commit and report a false mismatch). A mismatch refuses to
   decrypt or display.
4. Decrypt (Annex C.3) and apply: `MlsSubjectApplier.apply` renames the existing group
   conversation (it never creates one); `GroupIconApplier` writes the icon to a persistent
   app-private file under `files/mls-group-icons/` and points `ConversationColumns.ICON` at its
   `file://` URI. The derived-avatar writer only replaces a value it produced itself, so a
   membership change does not revert a decrypted icon.

### Key storage

Keys are stored in the MLS preferences as Base64 `FileInfo` blobs, **filed by the commitment they
satisfy**: `fileinfo_<slot>_<conversationKey>_<first 8 bytes of the commitment, hex>`
(`MlsGroupMetadata.fileInfoPrefKey`). A stale or replayed key lands under a commitment nobody is
asking for and cannot displace the current one; a key that beats its commit is found as soon as the
commit lands. No write-time guard is needed, and none would work, because at write time the question
"is this key current?" is not yet answerable.

Retention is bounded per (conversation, slot) at `RETAINED_CONTENT_KEYS_PER_SLOT` (4), evicted
oldest first by an insertion-order list stored under `fileinfo_<slot>_<conversationKey>_order`
(`pruneRetainedContentKeys`). Keeping every content key forever would partly undo MLS forward
secrecy; keeping only the current one would drop a key whose commit has not landed yet. Keys are
never evicted on age, because the current subject and icon must stay openable indefinitely.

## RCC.16 §10.5: recovering the metadata keys

A member that joined without the keys (for example by external commit) holds ciphertext it cannot
open. RCC.16 §10.5 defines the recovery, and `MlsMetadataKeysPolicy` expresses both revisions as
pure decisions, selected by `Rcc16Version` (`debug.rcs.mls_rcc16_version`: 30, the default, or 40;
any other value reads as v3.0):

| Rule | v3.0 | v4.0 |
|---|---|---|
| Detect (RCC.16 §10.5.1, `detect`) | After a self-heal that consumed the server GroupInfo, a commit that changed a commitment, or a join: recompute the commitment from the local FileEncryption `hmac_tag` with the revision's label and compare. A commitment with no local key is out of sync; no commitment means no encrypted metadata. | Same. |
| Request (RCC.16 §10.5.2, `shouldRequestKeys`) | Not possible (0xF007 does not exist in v3.0). | Out of sync: set 0xF007. |
| Respond (RCC.16 §10.5.3, `shouldSendKeys`) | On an External Commit, send our keys unconditionally. | On a commit or Welcome, send if 0xF007 is present. |
| Never forward unverifiable keys | Both: send only if our own keys verify against the group's commitment. | |
| Remove the request (`shouldRemoveRequestExtension`) | n/a | The responder removes 0xF007 in the same commit, or every later commit re-triggers the send. |

Of these decisions only the request check is wired into production (below): `detect`,
`shouldRequestKeys`, `shouldSendKeys` and `shouldRemoveRequestExtension` are host-tested and have no
production caller yet.

The keys travel in a `GroupMetadataKeys` body as an ordinary encrypted application message, framed
as an attachment so a client that does not recognise the type drops it instead of rendering it
(`MlsGroupMetadata.sendGroupMetadataKeys`, reached today only from the debug receiver). The same
body can carry the continuity token.

On receipt, `onGroupMetadataKeys` routes by content type (a `GroupMetadataKeys` body is not a
`FileInfo`): a continuity token is stored on the conversation record (below), and field 2 is handed
to `onFileInfo`.

`RccContentDisposition.takesCharset` appends `;charset=UTF-8` to every `message/*` type, these
key-delivery types included, although their bodies are binary protobuf: that is the form already on
the wire. A peer's own RCC.16 §7.8.1 header block, which `RccMlsBody.Parsed.headers` retains, would
settle whether to drop it.

The maintenance pass reads whether the server GroupInfo carries 0xF007
(`metadataKeysRequestPresent`) as an input to its add arm. When the code point is configured as
unknown, it answers `false` and the pass reports that arm as un-evaluable rather than as "no refresh
needed".

## The continuity token

A continuity token (RCC.16 §7.11.12, §8.3.1.1) is a group secret that links a new era to its
predecessor; its commitment lets a peer check the link without the server learning the token.

| Code point | What | Where it may appear |
|---|---|---|
| `0xF010` | the token: 256 random bits, extension data framed as `opaque<V>` (33 bytes; a bare 32-byte value is also accepted) | only in the encrypted GroupInfo inside a Welcome; a token in a server-bound GroupInfo would hand the server the secret |
| `0xF011` | the commitment | every GroupInfo and Welcome; a GroupInfo extension, since a GroupContext extension would have to commit to an authenticator derived from itself |

`MlsContinuityCodePoints` holds the pair. The commitment (`MlsContinuityToken.commitment`) is

```
struct { opaque continuity_token<V>; opaque epoch_authenticator<V>; } TokenCommitment
token_commitment = RefHash("Continuity Token GroupInfo Commitment", TokenCommitment)
```

with RFC 9420 §5.2 `RefHash`, whose label carries the `"MLS 1.0 "` prefix, unlike the bare labels of
Annex C.1. A 32-byte commitment framed as `opaque<V>` is the 33 bytes found at `0xF011`.
Commitments are compared in constant time.

**Receiving.** A token arrives by two routes, both through the single write path
`MlsContinuityToken.noteContinuityToken`: the Welcome that admitted us (the engine reads it, and
`collectWelcomeContinuityToken` runs on adoption; no token is the ordinary case for groups we
created, external commits and peers that send none), and a `GroupMetadataKeys` body. The write:

* skips an empty token, and refuses one longer than `MAX_STORED_TOKEN_BYTES` (256 bytes, above the
  32 the spec gives) because the record is rewritten whole and a peer-chosen length would amplify
  every later write; the refusal is logged as `OVERSIZE`, which a peer can provoke, not as a drop;
* never overwrites a record it cannot read, since that record may hold an in-flight operation;
* logs `FIRST`, `SAME` or `REPLACED`: a received token that differs from ours replaces it, and only
  its length is ever logged.

The token is stored in the conversation record (field 13). An absent token decodes as an empty
array, not `null`, and costs no bytes, so a record without one is byte-identical to one written
before the field existed; the dump prints only the length. `MlsRecordState.continuityTokenFor`
reads it and migrates an older per-conversation preference (`mls_continuity_token_<key>`) into the
record: the preference is removed once the token is in the record, or when it does not decode; a
corrupt record leaves it untouched as possibly the last copy, and a missing record keeps it while
still returning the token. Nothing in production consumes the token yet; it is stored so that a
consumer can be added without losing tokens received meanwhile.

**Emitting.** The app emits neither code point. Publishing `0xF011` would arm every peer's
continuity check (on self-heal, a new-era Welcome, a failed subject or icon decrypt, and era
creation), and a persistent mismatch there may move the conversation to plaintext (RCC.16 §11.2),
while this app does not yet reproduce a token across restarts. The engine's
`group_info_with_continuity` would add `0xF011` only under v4.0 and has no caller. If continuity is
ever staged, the safe order is: persist the token, emit `0xF010` (Welcome-only, arms nothing), keep
`0xF011` off. The code points are defined in RCC.16 v4.0; other clients put a token in the Welcomes
they build under v3.0 as well, and none has been seen publishing `0xF011`. `0xF010` is Welcome-only,
so its absence from a GroupInfo says nothing. The downgrade rule that reads the commitment is in
[downgrade.md](downgrade.md#downgrades-a-peer-would-make).

## Chat attachments

A file sent in an MLS conversation uses the same Annex C.2 encryption and RCC.16 §7.8.1 `FileInfo`
as the subject and icon (`RccFileInfo.file`, `RccFileInfo.thumbnail`), planned by `RccMediaSeal`,
which is host-tested and not yet called from the send path.

* An attachment carries **two names**. `FileMetadata.file_name`, inside the sealed payload, is the
  plaintext name and is also the KDF `Info`. The file-transfer descriptor's `<file-name>` describes
  the uploaded ciphertext and is always the literal `"encrypted_file"`; peers reject anything else
  there, and it is never used as the KDF input.
* A preview is a second file, sealed under its own key and name, so a send with a thumbnail carries
  two `FileInfo`s.
* A file that cannot be sealed fails the send; a thumbnail that cannot be sealed is dropped.
* The key delivery is produced first, because the descriptor's `<mls-file>` carries what that send
  returns. What peers place in `<mls-file>`, and which message id goes in which slot, is not
  established, so `RccMediaSeal.Plan` exposes neither and leaves both to the caller.

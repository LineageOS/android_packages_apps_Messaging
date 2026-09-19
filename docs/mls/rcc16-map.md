<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# RCC.16 section map

Where each part of GSMA RCC.16 (the RCS end-to-end encryption profile of MLS) is implemented. The
tables are built from the section references in the code, so they list what the code cites, with the
subject the code cites it for. Classes are linked by name; `ffi`, `rcc16`, `rcc16_validate`,
`rcc16_mint`, `rcc16_build` and `storage` are modules of the Rust core (see
[rust-core.md](rust-core.md)).

The code follows two versions of the text. `Rcc16Version` (Java) and `rcc16::rcc16_version` (Rust)
select between v3.0 and v4.0 behaviour where they differ. The revision is a property of the
deployment and nothing on the wire announces it, so it is stated rather than detected: the transport
passes `Rcc16Version.fromWire(MlsConfig.rcc16Version)` to `OpenMlsEngine` when it opens the session
(default `DEF_RCC16_VERSION` = 30, i.e. v3.0; `debug.rcs.mls_rcc16_version` overrides it, and an
unknown value reads as v3.0). At every divergent site the rule is to decode both forms and encode
the announced one. Section numbers below are v4.0 unless marked.

No wire signal carries the revision. The `+g.gsma.rcs.mls.mls-version` feature tag is the fixed
`"v1"`, `+g.gsma.rcs.mls.mls-kds` selects a key directory, and the advertised extension and proposal
lists are per-code-point claims. v3.0 is the default because, unlike an extension type, there is no
"unknown" revision to fall back to, and it is what the deployed peers speak; forcing v4.0 against
them emits shapes they do not accept (the `end_mls` payload among them). One value covers both the
wire shapes and the capability lists, although peers can be ahead of v3.0 on a payload (the
`EndMlsMetadata` form of `0xF002`) while still advertising the `end_mls` proposal v4.0 withdrew; the
setting is not split until a deployment needs the two apart. The custom-proposal framing (`0xF002`,
`0xF004` bare rather than length-prefixed) has no revision switch in the MLS library and is bare,
as the deployed peers write it.

Other numbering in the code:

* `RFC 9420 §x` is the MLS protocol itself, cited with its prefix.
* Some log and exception strings cite a section without a prefix. Those numbers are RCC.16, except
  in strings about the health machine, the drive loop and the persisted record (the 16 health states
  and their transition table, the record's field list, the guard order of a transition), where they
  refer to the conversation state model that [health-and-recovery.md](health-and-recovery.md)
  describes, not to RCC.16.

## §4–§6: provisioning, safety numbers, epochs

| section | subject, as the code uses it | where |
|---|---|---|
| §4.1 | the client gives its public participant key to the configuration server, which retains it and signs over it | `MlsParticipantKeyDerivation`, `MlsParticipantIdentityKey`, `MlsCredential.buildCsr` |
| §5.1 | a KeyPackage's lifetime is clamped to the client certificate | `ffi::kp_lifetime_window` (`WITHIN_CERTIFICATE`), `MlsCarrierTransport` KeyPackage replenishment |
| §5.3 | the home KDS validates client credentials in KeyPackages per Annex A.4.2; this is what puts a client's claim in A.4.2.2's scope | `MlsClaimLedger.describeEmptyClaim` |
| §5.5 | identity verification (safety number) is a user-facing requirement | `RccIdentityVerification` (Annex C.5) |
| §6.1.1 | retain previous-epoch secrets: at least 3 days (v3.0); 30 days then delete (v4.0). A within-era promise: `(group_id, epoch)` does not identify an epoch across eras | `storage`: `EPOCH_RETENTION_MS_V3_0`, `EPOCH_RETENTION_MS_V4_0`, `prune_expired_epochs`; `MlsPendingQueue.isFromASupersededEra`; `MlsInboundHold` |
| §6.1.2 | the Conversation Focus arbitrates commits and proposals | `RcsMlsTransportProfile` |
| §6.2 | self-heal first; report a failure only if it persists; the sender advances to the latest epoch and resends | `MlsFtdEscalation`, `IRcsProvider.sendMlsNegativeDeliveryImdn` |
| §6.3.1 | enhanced self-heal preconditions | `MlsEnhancedSelfHeal` |

## §7: wire formats

### Application messages and receipts

| section | subject | where |
|---|---|---|
| §7.5.3 | encrypting with an explicit `authenticated_data` | `MlsSession.encryptWithAad`, `ffi::aad_for` |
| §7.5.3.1 | the AuthenticatedData `message_id` must equal the transport's message id. Decided by the engine (`MlsSession.setRequestMessageId`, `lastMessageIdMismatch`) on both legs through `MlsEngineIdCheck.process`, armed with the envelope id, or an empty id when there is none: the provider leg refuses with `MlsInboundRefusal.AAD_MESSAGE_ID_MISBINDING` (`MlsInboundDecrypt`), and the carrier leg drops the message before surfacing plaintext, comparing the CPIM `imdn.Message-ID` passed through `MlsInboundHandler.onMls` and `onInboundCpim`. The engine takes any AAD version, compares raw bytes and needs the era; an absent or unparseable AAD passes | `MlsEngineIdCheck`, `MlsInboundRefusal`, `MlsCarrierTransport`, `CarrierMessageReceiver`, `OpenMlsNative.nativeSetRequestMessageId` |
| §7.5.3.2 | inbound AuthenticatedData handling | `MlsInboundDecrypt.decryptInbound` |
| §7.6 | the complete signed content of a receipt | `VerifiableDerivedContent`, `MlsTransportTypes` |
| §7.6.2 | MLS-signed IMDNs: signing and signature validation | `MlsImdnSigner.signImdn`, `MlsImdnSigner.verifyImdn`, `MlsImdnSigner.verifyDecryptedImdn`, `MlsImdnSigner.HDR_DERIVED_SIGNATURE`, `MlsSession.rcsSign`, `MlsSession.rcsVerify` |
| §7.6.3 | the VerifiableDerivedContent a receipt signature covers | `VerifiableDerivedContent`, `MlsReceiptMetadata` |
| §7.6.3.1 | `VerifiableDerivedContentVersion` | `VerifiableDerivedContent` |
| §7.6.3.2 | the derived-content signature on a negative receipt, and the binary encoding of the client failure reasons | `RccNegativeDeliveryImdn`, `IRcsProvider.sendMlsNegativeDeliveryImdn` |
| §7.6.3.3 | `DisplayNotificationStatus`, `VerifiableDisplayImdn` (no failure-reason field) | `VerifiableDerivedContent` |
| §7.6.3.4 | `VerifiableVideoChatMessage`, wrapping an opaque protobuf | `VerifiableDerivedContent` |
| §7.7.2.1 | server-generated failure reasons | `RccNegativeDeliveryImdn.ServerReason` |
| §7.7.2.2 | client-generated negative-delivery IMDN (failure-to-decrypt report), both directions | `IRcsProvider.sendMlsNegativeDeliveryImdn`, `IRcsProviderCallback.onMlsNegativeDelivery`, `MlsFtdEscalation.onPeerReportedFailure`, `RccNegativeDeliveryImdn`, `CpimMessage` |
| §7.7.2.3 | the `mls-client-failure-reason` tokens | `RccNegativeDeliveryImdn` |
| §7.8.1 | FileInfo key delivery for the group icon and subject | `MlsGroupMetadata`, `RccFileInfo`, `RccContentDisposition`, `IRcsProviderCallback.onEncryptedGroupSubject`, `IRcsProviderCallback.onEncryptedGroupIconContent` |
| §7.8.2 | the file descriptor type; as a message type it is a transfer, not text | `RccContentDisposition` |
| §7.9 | MLS content types and the CPIM `mls` namespace; an MLS body must carry the era and epoch authenticator, and is dropped before decryption otherwise | `CpimMessage.CT_MLS`, `CpimMessage.CT_MLS_RCS_CLIENT`, `CpimMessage.newMls`, `CarrierMessageReceiver`, `MlsCarrierTransport`, `E2eeConversationTransport` |
| §7.9.2 | a GroupInfo carrying the continuity-token commitment | `MlsSession.groupInfoWithContinuity`, `OpenMlsNative.nativeGroupInfoWithContinuity` |
| §7.10.2–§7.10.4 | the epoch identifier, also the request body of the enhanced GroupInfo pull | `RccEpochIdentifier`, `RccProto` |
| §7.10.5 | `MlsEnhancedGroupInfo`, the response to that pull | `RccEnhancedGroupInfo`, `RccProto` |
| §7.12 | `SignedEncryptionIdentityProof`, as the certificate's `.5` extension carries it | `rcc16_validate::AcsProof` |
| §7.12.1 | the proof's delivery from the configuration document to KDS enrolment | `AcsImsConfigParser`, `RcsImsConfig` |
| §7.13, §7.13.1–§7.13.4 | encrypted group metadata (CPM group data, metadata keys) | `RccGroupData`, `RccGroupMetadataKeys`, `MlsGroupMetadata.onGroupMetadataKeys` |

### AuthenticatedData and receipt metadata, as deployed

The `AuthenticatedData` of an application message is built by the engine
(`rcc16::build_authenticated_data`, reached through `ffi::aad_for`); the host passes only the
message id and, for a resend, the resent component. `MlsAppMessage.buildAuthenticatedData` is a Java
copy of the same layout with no production caller, and nothing enforces that the two agree.

```
00 01                  uint16 version = 1
mls_varint len         message_id length
message_id             ASCII
uint32 era             big-endian; the RCC.16 era (0xF001), not the MLS epoch
trailing               the §10.3 resent-message component, or 0x00 when absent
```

The era field is not in the RCC.16 text, whose `AuthenticatedData` is `{version, message_id,
optional resent}`, but other clients emit it and it tracks the group's era (commits across several
epochs of one era all carry the same value). Omitting it, or sealing with an empty AAD as stock
mls-rs does, makes peers fail to decrypt, and the failure is reported as a key-generation mismatch,
far from the cause. The engine reads the era from the group's own `0xF001` rather than from the
caller, and uses 1 for a group it cannot load. The layout is the v3.0 interoperable form under both
revisions: moving to v4.0's restructured `AuthenticatedData` needs a v4.0 peer to interoperate with,
and would be gated on the announced revision as the `0xF002` payload is.

Era and epoch are unsigned (`u32`, `u64`) and ordered era first: `(1, 0)` is newer than
`(0, 2^63)` (`MlsAppMessage.Moment`, which compares with `compareUnsigned`). The era never wraps:
`MlsAppMessage.nextEra` and `rcc16::next_era` refuse to pass `0xFFFFFFFF`, and the value must fit a
`uint32` across the FFI, where a truncated era would read as 0, i.e. no era extension.

Receipt metadata (`MlsReceiptMetadata`), the protobufs a positive or display receipt signs over:

| message | fields |
|---|---|
| `DeliveryReceiptMetadata` | 1 version, 2 status, 3 `message_id`, 4/5 failure-reason oneof (never set outbound), 6 `original_message_id` |
| `DisplayReceiptMetadata` | 1 version, 2 status (1), 3 `message_id`, 4 `original_message_id` |

The id fields are bytes (UTF-8). `original_message_id` is set only when it is non-empty and differs
from the receipt's own id. The receipt's MLS CPIM headers are the signature, then the era; a receipt
never carries `Epoch-Authenticator` or `Original-Message-ID`. The signature is the §7.6.3
`VerifiableDerivedContent` construction; how peers transform this metadata before signing is not
known, so it is not assumed.

### Extensions and proposals (§7.11)

| section | code point | where |
|---|---|---|
| §7.11.1.1 | era, GroupContext extension `0xF001`, `uint32`, initial value 1 | `rcc16::ERA_EXT`, `rcc16::ERA_INITIAL` |
| §7.11.2.1 | the `end_mls` proposal (withdrawn in v4.0) | `rcc16::END_MLS_PROP`, `Rcc16Version` |
| §7.11.2.2 | `end_mls` GroupContext extension `0xF002`; data is the literal `"end_mls"` in v3.0 and a serialised `EndMlsMetadata` in v4.0. Ends MLS for the group | `rcc16::END_MLS_EXT`, `MlsDowngradeFlow.endMls`, `MlsSession.commitEndMls`, `MlsPendingOperation.supersedesAsEscalation`, `MlsStateChangeGate.onInboundProposal` |
| §7.11.3.1 | `icon_key` `0xF003` | `rcc16::ICON_KEY_EXT` |
| §7.11.4, §7.11.4.1 | `icon_commitment` `0xF004` | `rcc16::ICON_COMMITMENT_EXT`, `RccCommitment.LABEL_ICON`, `MlsGroupMetadata.publishIconSubject` |
| §7.11.5.1 | `subject_key` `0xF005` | `rcc16::SUBJECT_KEY_EXT` |
| §7.11.6, §7.11.6.1 | `subject_commitment` `0xF006` | `rcc16::SUBJECT_COMMITMENT_EXT`, `RccCommitment.LABEL_SUBJECT` |
| §7.11.7.1 | `rcs_signature` proposal | `rcc16::RCS_SIGNATURE_PROP` |
| §7.11.8.1 | `SelfRemove` proposal | `rcc16::SELF_REMOVE_PROP`, `ffi` |
| §7.11.9 | `ServerRemove` proposal `0xF004`, body `struct { uint32 to_remove; }` (four bytes big-endian, not a varint) | `rcc16::SERVER_REMOVE_PROP`, `MlsParticipantKeyResync.serverRemoveBody`, `parseServerRemoveBody` |
| §7.11.10.1 | group metadata keys requested, `0xF007`, data `"group_metadata_keys_requested"` | `rcc16::METADATA_KEYS_REQUESTED_EXT`, `MlsConfig.DEF_METADATA_KEYS_EXT` |
| §7.11.11.1, §7.11.11.2 | the reserved proposal and extension bands clients must advertise: proposals `0xF012..=0xF030` under v4.0 (`0xF010..=0xF018` under v3.0), extensions `0xF010..=0xF030` | `rcc16::RESERVED_FUTURE_PROP_RANGE_V4`, `rcc16::ADVERTISED_VENDOR_PROP_RANGE`, `rcc16::ADVERTISED_VENDOR_EXT_RANGE` |
| §7.11.12, §7.11.12.1 | `continuity_token` `0xF010`, Welcome-only, a secret. It rides only in the encrypted GroupInfo inside a Welcome, so it is never expected in the server's GroupInfo; the commitment `0xF011` belongs in every GroupInfo a continuity-capable client builds, so its absence from the server's GroupInfo means no member does continuity (`MlsTransportDiagnostics.dumpGroupExtensions` reports both) | `rcc16::CONTINUITY_TOKEN_EXT`, `MlsContinuityCodePoints`, `MlsContinuityToken.collectWelcomeContinuityToken`, `MlsSession.takeWelcomeContinuityToken`, `MlsConversationRecord.continuityToken` |
| §7.11.12.2 | the continuity-token commitment `0xF011`, a GroupInfo extension despite the heading: a GroupContext extension committing to its own epoch's authenticator is unsatisfiable, since the authenticator is derived from that GroupContext | `rcc16::CONTINUITY_TOKEN_COMMITMENT_EXT`, `MlsContinuityToken` |

Which of these are sent, advertised or only parsed is listed in `rcc16::IMPLEMENTED_EXTENSIONS`,
`IMPLEMENTED_PROPOSALS` and `ADVERTISED_UNIMPLEMENTED_EXTENSIONS_FIXED`; see
[rust-core.md](rust-core.md).

## §8: eras and continuity

| section | subject | where |
|---|---|---|
| §8.3 | era advancement. The engine refuses an in-group era change: the era moves only by creating a new group | `MlsSession.commitEraAdvance`, `ffi::rcs_mls_commit_era_advance`, `MlsEraAdvanceCharge` |
| §8.3.1.1 | minting a continuity token at group creation and carrying it in the Welcome | `MlsContinuityToken`, `MlsContinuityPolicy`, `MlsCarrierTransport` |
| §8.3.1.2 | validating the token on new-era creation | `MlsContinuityPolicy`, `MlsContinuityToken` |
| §8.3.1.3 | requesting a missing token | `MlsContinuityPolicy`, `MlsGroupMetadata.sendGroupMetadataKeys`, `RccGroupMetadataKeys` |

## §9: group operations

| section | subject | where |
|---|---|---|
| §9.1.1 | once `end_mls` is present, encrypted sends are forbidden | `MlsDowngradeFlow.endMls`, `MlsGroupState.isEndMls`, `MlsSealSend`, `MlsTransportTypes` |
| §9.2 | an era advance is a group re-creation that re-Welcomes every member; a GroupContextExtensions proposal may not change or remove the era | `MlsEraAdvance`, `MlsPeerGuard.allowEraAdvance`, `rcc16::Rcc16MlsRules`, `MlsEraAdvanceCharge` |
| §9.4 | leaving a group (our own removal) | `MlsConversationRecord.selfLeftAtMs`, `MlsRecordState.weLeft`, `MlsMembership`, `GroupDepartureApplier` |
| §9.5.1 | the removal decision travels with the RCS notification that participants were removed; the client commits the removal with an ordinary Remove (not a `ServerRemove` proposal), and an inbound `server_remove` proposal is dropped | `MlsServerMessage.removeOnServerNotify`, `MlsStateChangeGate.onInboundProposal` |
| §9.5.3 | credential Self-Update when our certificate changes | `MlsCredentialUpdate.maybeUpdateGroupCredential`, `MlsCredentialUpdateSeal`, `MlsFloorRebuild`, `MlsCommitApplication` |
| §9.7.1.1, §9.7.1.2 | icon and subject file encryption | `RccFileCrypto` |
| §9.7.1.1.1, §9.7.1.2.1 | commitment labels `group_icon`, `group_subject` | `RccCommitment.LABEL_ICON_V4`, `RccCommitment.LABEL_SUBJECT_V4` |
| §9.7.1.4 | encrypted group icon | `MlsGroupMetadata.changeGroupIcon`, `MlsSession.commitIconSubject`, `GroupIconApplier`, `IRcsProviderCallback.onEncryptedGroupIcon`, `IRcsProviderCallback.onEncryptedGroupIconContent`, `IRcsProvider.changeGroupIconMls` |
| §9.7.1.5 | encrypted group subject | `MlsSubjectApplier`, `MlsSession.commitGroupMetadata`, `IRcsProviderCallback.onEncryptedGroupSubject`, `ManageRcsGroupAction` |
| §9.7.1.6, §9.7.1.7 | deleting the icon or subject: the group data carries no `<data>` element | `RccGroupData` |

See [metadata.md](metadata.md) for §7.8.1, §7.11.3–§7.11.6, §9.7.1 and Annex C.1/C.2 together.

## §10: failure handling

| section | subject | where |
|---|---|---|
| §10.1 | self-heal on a decryption failure | `MlsProviderTransport.onDecryptFailure`, `MlsInboundDecrypt` |
| §10.1.1 | participant-key resync commit. `MlsParticipantKeyResync.plan`/`planFrom` compute it; nothing in production calls them yet | `MlsParticipantKeyResync`, `MlsParticipantKeyDerivation`, `MlsParticipantKeyLedger` |
| §10.1.2 | enhanced self-heal: pull the missing epochs from the Conversation Focus before falling back to an external commit | `MlsEnhancedSelfHeal`, `RccEnhancedGroupInfo` |
| §10.2 | the failure report is sent after self-heal, for messages that still cannot be decrypted | `RccNegativeDeliveryImdn`, `IRcsProvider.sendMlsNegativeDeliveryImdn`, `IRcsProviderCallback.onMlsNegativeDelivery` |
| §10.3 | resending a message a peer could not decrypt, with a resent-message component in the AuthenticatedData trailing slot; a report chain stops after 5 attempts | `MlsResend`, `MlsResendReceive`, `MlsResentMessage`, `MlsAppMessage`, `MlsFtdEscalation.MAX_FTD_ATTEMPTS`, `MlsResendBudget` |
| §10.5, §10.5.1–§10.5.4 | group metadata keys: detect out-of-sync, request via `0xF007`, respond and remove the extension, consume | `MlsMetadataKeysPolicy`, `MlsGroupMetadata.sendGroupMetadataKeys`, `MlsGroupMetadata.onGroupMetadataKeys`, `RccGroupMetadataKeys`, `MlsContinuityToken` |
| §10.8 | the pending queue: G1 busy-group lock, G2 from-the-future admission; an era advance does not purge it | `MlsPendingQueue`, `MlsPendingQueueStore`, `MlsInboundHold` |

See [health-and-recovery.md](health-and-recovery.md).

## §11–§12: downgrade, quotas, receipts

| section | subject | where |
|---|---|---|
| §11.1 | a resend is a new message with a new id, linked to the one it replaces; the cached ciphertext is cleared so the id can be re-encrypted | `MlsResendLedger`, `MlsResendRecord`, `MlsCiphertextCache`, `MlsResendReceive` |
| §11.2 | downgrade (the continuity-token validation failure ends there) | `MlsContinuityPolicy`, `MlsDowngradeFlow` |
| §11.2.2 | at most 50 external commits per group per day | `MlsWindowBudget.EXTERNAL_COMMIT`, `MlsExternalCommitBudget`, `MlsExternalCommitResync.resyncViaExternalCommit`, `MlsExternalCommitResync.resetExternalCommitBudget` |
| §11.2.3 | quotas | `MlsExternalCommitBudget`, `MlsWindowBudget` |
| §11.3a | the receiver's ordering of checks for a resent message. `MlsResendReceive.resentSelectorField` returns null while the selector layout is unknown, so a resend addressed to us stops at `SELECTOR_UNAVAILABLE` and the inner unwrap is not reached | `MlsResendReceive`, `MlsResentMessage`, `MlsInboundDecrypt` |
| §12.1 | the MLS header sidecar on positive and display receipts | `IRcsProvider.sendMlsImdn`, `MlsTransportTypes`, `MlsReceiptMetadata` |
| §12.8 | client receipts never clean the ciphertext cache; every server failure reason except an unset one or `transient-error` does | `MlsResend`, `MlsFtdEscalation` |
| §12.9 | legacy group ids | `MlsLegacyGroupWireIdTest` |

See [downgrade.md](downgrade.md) and [budgets.md](budgets.md).

## §14–§15: certificate profile, membership

| section | subject | where |
|---|---|---|
| §14.1, §15.2 | every KeyPackage of a participant is added in one commit, so all of their devices join together | `ffi::add_members` |
| §14.2.3 | the certificate profile: permitted extensions, exactly one E2EE `certificatePolicies` entry on a leaf | `rcc16_validate`, `rcc16_mint` |
| §14.4 | `vendorId` (2.23.146.2.1.6), carried in the root; a leaf's must match its root's | `rcc16_validate::Rcc16Validator` |
| §15.2 | removal selects by certified MSISDN, every leaf of that participant in one commit; an MSISDN with no leaf is refused | `ffi::remove_member_by_msisdn` |

## Annex A: certificates and the KDS

| section | subject | where |
|---|---|---|
| A.1.5 | root CA validity, up to 3652 days | `MlsCredential`, `rcc16_validate` |
| A.2.5 | intermediate CA validity, up to 1827 days | `MlsCredential`, `rcc16_validate` |
| A.2.8, A.2.8.3, A.2.8.5 | CA extensions: `KeyUsage` critical with `keyCertSign`; `BasicConstraints` critical with cA true | `rcc16_validate::validate_ca_rcc16` |
| A.3.8 | the client certificate profile | `MlsCredential.issueLeafFromCsr`, `rcc16_mint`, `rcc16_validate` |
| A.3.8.7 | the extended key usage: exactly `id-kp-rcsMlsClient` on a leaf; optional on a CA, and if present only that | `rcc16_validate` |
| A.3.8.9 | the participant-key extension | `MlsParticipantKeyDerivation`, `MlsParticipantIdentityKey`, `rcc16_build`, `rcc16_mint` |
| A.3.8.10 | `id-acsParticipantInformation` (`.5`), the configuration-server-signed encryption identity proof, filled by the KDS | `RcsKdsClient.enroll`, `AcsImsConfigParser`, `rcc16_mint` |
| A.4.1 | validating a peer's credential chain, including the SAN against the queried number | `rcc16_validate::Rcc16Validator`, `RccIdentity`, `MlsConfig.sanIdentityCheck`, `MlsKeyPackagePolicy.keyPackageUsable` |
| A.4.1.1 | the leaf's EKU is exactly `id-kp-rcsMlsClient`; a `.4` carrying `participantKeyRolls` is refused, since verifying the roll chain (A.4.1.1(1c-ii)) is not implemented | `rcc16_validate`, `Rcc16Der` |
| A.4.1.2 | refuse to consume a KeyPackage whose certificate has less than 30 days left | `MlsConfig.DEF_KP_MIN_REMAINING_DAYS`, `MlsKeyPackagePolicy.keyPackageUsable`, `MlsKeyPackageClaims.gatherInitialKeyPackages`, `MlsSession.inspectKeyPackage` |
| A.4.1.5 | no Self-Update whose new credential is itself inside the 30-day floor | `MlsSelfLeafStatus`, `MlsCredentialUpdate.maybeUpdateGroupCredential` |
| A.4.2.2 | the KDS must not return KeyPackages whose credential has less than 30 days left | `MlsClaimLedger.describeEmptyClaim`, `MlsCredentialFloor`, `MlsServerBundle.rosterFloorReport` |
| A.4.3.1, A.4.3.2 | every credential in the post-commit roster is checked at commit time | `MlsCredentialFloor`, `MlsFloorRebuild.floorRebuild`, `MlsServerBundle.rosterFloorReport`, `MlsConfig.KEY_FLOOR_PRECHECK` |

See [credentials.md](credentials.md).

## Annex C: cryptographic constructions

| section | subject | where |
|---|---|---|
| C.1 | icon and subject commitments | `RccCommitment`, `MlsGroupMetadata.publishIconSubject`, `MlsSubjectApplier`, `MlsContinuityToken` |
| C.2 | file encryption for icon and subject payloads (PADME padding) | `RccFileCrypto`, `RccFileInfo`, `MlsGroupMetadata.changeGroupIcon` |
| C.5 | identity verification digits; the digit derivation is left undefined by the text and is not guessed | `RccIdentityVerification` |

## Tests

Most rows have a host test named after the class (`RccCommitmentTest`, `RccFileCryptoTest`,
`RccEnhancedGroupInfoTest`, `RccIdentityVerificationTest`, `RccNegativeDeliveryImdnTest`,
`VerifiableDerivedContentTest`, `MlsAuthenticatedDataWireVectorTest`, `MlsResentMessageTest`, ...).
Spec constructions are tested by recomputing them from the specification text rather than
round-tripping our encoder through our decoder, because the peers they must interoperate with cannot
be asked. See [../testing.md](../testing.md).

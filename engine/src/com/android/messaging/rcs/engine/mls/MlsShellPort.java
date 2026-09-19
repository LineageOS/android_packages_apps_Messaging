/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraReconcile;
import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import java.util.Map;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
/**
 * The transport's effects (storage, locks, RPCs, sends) and a few of its maps, for decision code
 * moved out of MlsProviderTransport. The transport's adapter calls straight back into its own
 * methods, so effects, locks and ordering are unchanged; host tests implement it with fakes.
 * An undocumented method forwards to the transport's method of the same name; a map or object
 * accessor returns the transport's live field, which is never reassigned through here. A
 * {@code default} member is an engine seam: it runs the named engine static with this port, and
 * host tests stub it by name like any other member.
 * See docs/mls/transport-and-port.md.
 */
public interface MlsShellPort {
    boolean ensureSession();
    Group getGroup(final String conversationId);
    /** Engine seam: runs {@link MlsGroupState#resolveInbound}. */
    default String resolveInbound(final String rcsGroupId, final String peerE164) {
        return MlsGroupState.resolveInbound(this, log(), rcsGroupId, peerE164);
    }
    /** Engine-side decision code logs through this. */
    MlsLogSink log();
    /** The transport's resolved knobs. */
    MlsConfig cfg();
    MlsSession session();
    MlsTelemetry telemetry();
    /** The transport's own preferences file, where its {@code PREF_*} stamps live. */
    MlsPrefs prefs();
    /** The live session when it is the OpenMLS engine, else {@code null}. */
    MlsSession openMlsSession();
    /** The monotonic clock the fetch and claim ledgers are stamped with. */
    long elapsedRealtime();
    /** The operator's {@code debug.rcs.*} knobs, read-only. */
    MlsSysProps sysprops();
    /**
     * The provider RPCs, with the subscription bound in. The transport's lock-held-across-I/O
     * assertion fires here, before the arguments are evaluated.
     */
    MlsProviderRpc rpc(final String what);
    /** MlsPeerGuard's static allow/claim/reset surface. */
    MlsPeerGuards peerGuard();
    /** The durable rebuild rate limit. */
    MlsRebuildLimits rebuildLimiter();
    /** The durable external-commit budget. */
    MlsExternalCommitLimits xcBudget();
    /** The provider's declared transport profile, or the AIDL type's conservative default. */
    MlsProviderRpc.TransportProfile transportProfile();
    /** The per-conversation state map, read-only. Not {@code conv()}, which creates state. */
    Map<String, ConvState> convStates();
    String selfE164();
    /** Engine seam: runs {@link MlsServerBundle#fetchServerPack}. */
    default Look<byte[]> fetchServerPack(final MlsFetchLedger.Caller caller,
            final String rcsGroupId, final String peerE164, final Group g) {
        return MlsServerBundle.fetchServerPack(this, caller, rcsGroupId, peerE164, g);
    }
    void lock(final String key);
    /** Engine seam: runs {@link MlsFetchLedger#lookServerEpochAuthenticator}. */
    default Look<byte[]> lookServerEpochAuthenticator(final MlsFetchLedger.Caller caller,
            final String key, final String peerE164, final String rcsGroupId) {
        return MlsFetchLedger.lookServerEpochAuthenticator(this, log(), caller, key, peerE164,
                rcsGroupId);
    }
    void unlock(final String key);
    int commitAndSend(final String rcsGroupId, final String peerE164,
            final byte[] addPeerKeyPackage, final byte[] memberSigPub, final Op op,
            final String what);
    Claim<java.util.List<byte[]>> claimAll(final MlsClaimLedger.Caller caller, final String peer);
    ConvState conv(final String key);
    byte[] openStoredIconSubject(final String rcsGroupId, final String peerE164, final boolean icon,
            final byte[] encryptedContent);
    /** Engine seam: runs {@link MlsDowngradeFlow#downgradeLocally}. */
    default void downgradeLocally(final String key, final MlsDowngradeReason reason,
            final boolean eager) {
        MlsDowngradeFlow.downgradeLocally(this, log(), key, reason, eager);
    }
    ConvState convIfAny(final String key);
    /** Engine seam: runs {@link MlsFtdEscalation#flushFtdReports}. */
    default void flushFtdReports(final String rcsGroupId, final String flushPeerE164) {
        MlsFtdEscalation.flushFtdReports(cfg(), this, log(), rcsGroupId, flushPeerE164);
    }
    /** Engine seam: runs {@link MlsFetchLedger#lookMissedCommits}. */
    default Look<byte[]> lookMissedCommits(final MlsFetchLedger.Caller caller, final String key,
            final String peerE164, final String rcsGroupId, final int era, final byte[] auth) {
        return MlsFetchLedger.lookMissedCommits(this, log(), caller, key, peerE164, rcsGroupId,
                era, auth);
    }
    void putGroup(final String conversationId, final Group g);
    boolean keyPackageUsable(final byte[] kp, final String who);
    MlsRecordAccess records();
    Map<String, Group> groups();
    int selfHeal(final String rcsGroupId, final String peerE164);
    java.util.Set<String> pendingOpsClaimedThisProcess();
    /** Engine seam: runs {@link MlsRecordState#moveHealth}. */
    default MlsHealthEdge moveHealth(final String key, final int to, final String cause) {
        return MlsRecordState.moveHealth(this, log(), key, to, cause);
    }
    boolean onFileInfo(final String rcsGroupId, final String fromE164, final byte[] fileInfoProto);
    /** Engine seam: runs {@link MlsRecordState#continuityTokenFor}. */
    default byte[] continuityTokenFor(final String rcsGroupId, final String peerE164) {
        return MlsRecordState.continuityTokenFor(this, log(), rcsGroupId, peerE164);
    }
    int rekey(final String rcsGroupId, final String peerE164);
    Claim<byte[]> claimOne(final MlsClaimLedger.Caller caller, final String peer);
    int eraAdvance(final String rcsGroupId, final String peerE164, final byte[] carryGroupInfo,
            final MlsAdvanceEraKind kind, final boolean requireRebuildableRoster);
    /** Engine seam: runs {@link MlsFloorRebuild#eraAdvanceLeverRefusal}. */
    default String eraAdvanceLeverRefusal(final String key) {
        return MlsFloorRebuild.eraAdvanceLeverRefusal(this, key);
    }
    int eraAdvance(final String rcsGroupId, final String peerE164, final byte[] carryGroupInfo);
    /** Engine seam: runs {@link MlsAheadChainCheck#quarantineIfAheadOfServer}. */
    default EraReconcile quarantineIfAheadOfServer(final Group g, final String rcsGroupId,
            final String peerE164, final int attemptedEra, final String what) {
        return MlsAheadChainCheck.quarantineIfAheadOfServer(this, log(), g, rcsGroupId, peerE164,
                attemptedEra, what);
    }
    java.util.concurrent.atomic.AtomicBoolean sweepInFlight();
    void raiseStall(final String key, final String peerE164, final String rcsGroupId,
            final String detail);
    void clearStall(final String key);
    String selfE164Raw();
    boolean publishKeyPackages(final int count);
    /** Engine seam: runs {@link MlsInboundHold#park}. */
    default MlsInboundHold.ParkOutcome park(final String conversationId, final String messageId,
            final byte[] mlsBytes, final MlsAppMessage.Moment at,
            final MlsPendingQueue.Admission why, final String fromE164,
            final MlsPendingQueue.Plane plane) {
        return MlsInboundHold.park(this, log(), conversationId, messageId, mlsBytes, at, why,
                fromE164, plane);
    }
    /** Engine seam: runs {@link MlsResend#flushGatedResends}. */
    default void flushGatedResends(final String key) {
        MlsResend.flushGatedResends(this, log(), key);
    }
    /** Engine seam: runs {@link MlsFetchLedger#lookServerEraEpoch}. */
    default Look<long[]> lookServerEraEpoch(final MlsFetchLedger.Caller caller, final String key,
            final String peerE164, final String rcsGroupId) {
        return MlsFetchLedger.lookServerEraEpoch(this, log(), caller, key, peerE164, rcsGroupId);
    }
    void noteMlsPlaneInUse(final String conversationKey);
    int rekey(final String peerE164);
    /** Engine seam: runs {@link MlsStallAlert#offerTheStallChoice}. */
    default void offerTheStallChoice(final String key, final MlsConversationRecord rec) {
        MlsStallAlert.offerTheStallChoice(cfg(), this, log(), key, rec);
    }
    /** Engine seam: runs {@link MlsSelfHeal#terminateRepairIfQuotaBound}. */
    default void terminateRepairIfQuotaBound(final String key, final MlsConversationRecord rec) {
        MlsSelfHeal.terminateRepairIfQuotaBound(cfg(), this, log(), key, rec);
    }
    /** Drop the session; the next {@code ensureSession} reopens against the identity on disk. */
    void dropSession();
    /** The conversation's MLS encryption bit, or null (no row, or unreadable; not plaintext). */
    Boolean conversationMlsBit(String convId);
    /** Show a decrypted group icon. */
    boolean applyGroupIcon(String rcsGroupId, byte[] iconBytes);
    /** The test-fixture hold verdict for an outbound publish; publish when no fixture is set. */
    MlsOutboundHold.Verdict outboundHoldVerdict(String conversationKey, boolean isRekey);
    /** The test-fixture stand-in for a publish while an outbound hold is set. */
    int suppressCommit(String conversationId, MlsTransportTypes.Group g, MlsTransportTypes.Op op,
            String what, String ctrlId, String peerE164, String rcsGroupId, MlsGroupArtifacts art,
            byte[] baseEpochAuth, byte[] snapshot, int preEra, long preEpoch);
    /** Count a held publish the server refused. */
    void noteOutboundHoldRefused();
    /** Show a decrypted group subject. */
    boolean applyGroupSubject(String rcsGroupId, String subject);
    void scheduleRetry(final String rcsGroupId, final String peerE164, final int attempt);
    Map<String, String> convAlias();
    Map<String, String> keyToConvId();
    java.util.List<String> bugleRoster(final String rcsGroupId, final String self);
    int subId();
    boolean forget(final String rcsGroupId, final String peerE164);
    java.util.Map<String, Integer> eraAsked();
    java.util.Set<String> eraQuotaReported();
    MlsPendingBodyAccess pendingBodies();
    MlsSealedCacheAccess sealedCache();
    MlsDriveLoop driveLoop();
    com.android.messaging.rcs.engine.mls.MlsOffThread offThread();
    boolean ensureReady(final String conversationId, final int subId, final List<String> peers);
    MlsRendezvousAccess rendezvous();
    /** The durable RCC.16 §10.8 queue store. */
    MlsPendingQueueAccess pendingQueue();
    /** The per-conversation re-upgrade state. */
    MlsReupgradeAccess reupgradeStore();
    /** Clear the conversation's MLS bit. */
    void downgradeMlsScheme(String conversationId);
    /** The durable resend ledger. */
    MlsResendLedgerAccess resendLedger();
    /**
     * Tell the conversation a member left. Opens a database transaction, so never call it while
     * holding a conversation lock.
     */
    boolean applyGroupDeparture(String rcsGroupId, String departedE164);
    /** The provisioned identity, or null. */
    MlsIdentity loadIdentity();
    /** Re-read the identity from the provider. */
    boolean refreshIdentityFromProvider();
    /** True if an inbound test-fixture hold kept this message. */
    boolean offerInboundHold(String conversationKey, String fromE164, String messageId,
            byte[] mlsBytes);
    /** The inbound control entry point, re-entered by the deferred-control drain. */
    boolean applyInboundControl(String fromE164, String messageId, byte[] mlsBytes,
            boolean convergenceAck, String rcsGroupId);
    /**
     * The platform Base64 decoder, kept rather than java.util's because they differ on malformed
     * input from peers and preferences. Throws IllegalArgumentException on bad input.
     */
    byte[] base64Decode(String s);
    /** The chat row's text for a message id, or null. */
    String findTextByRcsMessageId(String rcsMessageId);
    /** The RCS group a sent message belongs to, or null. */
    String findGroupIdByRcsMessageId(String rcsMessageId);
    /** The application-plane replay door. */
    void replayParkedApplication(String conversationId, String fromE164, String rcsGroupId,
            MlsPendingQueue.Entry e, MlsAppMessage.Moment now);
    boolean sendFramedToGroup(final String rcsGroupId, final byte[] framedBody,
            final String idPrefix, final String rcsMessageId);
    String conversationIdFor(final String key);
    int eraAdvance(final String rcsGroupId, final String peerE164);
    com.android.messaging.rcs.engine.mls.MlsGroupDeliveryLedger groupDelivery();
}

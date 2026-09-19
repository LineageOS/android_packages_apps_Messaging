/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.RccMlsBody;

import com.android.messaging.rcs.GroupDepartureApplier;
import com.android.messaging.rcs.GroupIconApplier;
import com.android.messaging.rcs.RcsDebug;
import android.os.Build;
import android.os.SystemClock;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.lineageos.rcs.provider.RcsMlsControlResult;
import org.lineageos.rcs.provider.RcsMlsTransportProfile;
import com.android.messaging.rcs.engine.mls.MlsAdvanceEraKind;
import com.android.messaging.rcs.engine.mls.MlsAheadChainCheck;
import com.android.messaging.rcs.engine.mls.MlsAdvancePurpose;
import com.android.messaging.rcs.engine.mls.MlsAppMessage;
import com.android.messaging.rcs.engine.mls.MlsArtifactBundle;
import com.android.messaging.rcs.engine.mls.MlsCommitApplication;
import com.android.messaging.rcs.engine.mls.MlsDowngradeReason;
import com.android.messaging.rcs.engine.mls.MlsHealthPredicates;
import com.android.messaging.rcs.engine.mls.MlsConfig;
import com.android.messaging.rcs.engine.mls.MlsCredentialFloor;
import com.android.messaging.rcs.engine.mls.MlsCredentialUpdateSeal;
import com.android.messaging.rcs.engine.mls.MlsFloorRebuild;
import com.android.messaging.rcs.engine.mls.MlsParticipantKeyResync;
import com.android.messaging.rcs.engine.mls.MlsSelfLeafStatus;
import com.android.messaging.rcs.engine.mls.MlsTimeValidationRefusal;
import com.android.messaging.rcs.engine.mls.MlsContinuityCodePoints;
import com.android.messaging.rcs.engine.mls.RccGroupMetadataKeys;
import com.android.messaging.rcs.engine.mls.MlsConversationRecord;
import com.android.messaging.rcs.engine.mls.MlsPendingQueue;
import com.android.messaging.rcs.engine.mls.MlsPeerGuards;
import com.android.messaging.rcs.engine.mls.MlsExternalCommitLimits;
import com.android.messaging.rcs.engine.mls.MlsPrefs;
import com.android.messaging.rcs.engine.mls.MlsRebuildLimits;
import com.android.messaging.rcs.engine.mls.MlsProviderRpc;
import com.android.messaging.rcs.engine.mls.MlsSysProps;
import com.android.messaging.rcs.engine.mls.MlsResentMessage;
import com.android.messaging.rcs.engine.mls.RccContentDisposition;
import com.android.messaging.rcs.engine.mls.MlsHealthMachine;
import com.android.messaging.rcs.engine.mls.MlsHealthStates;
import com.android.messaging.rcs.engine.mls.MlsHealthEdge;
import com.android.messaging.rcs.engine.mls.StoreRead;
import com.android.messaging.rcs.engine.mls.MlsGroupLocks;
import com.android.messaging.rcs.engine.mls.MlsDriveLoop;
import com.android.messaging.rcs.engine.mls.MlsGroupInfoGate;
import com.android.messaging.rcs.engine.mls.MlsGroupSnapshot;
import com.android.messaging.rcs.engine.mls.MlsHostAction;
import com.android.messaging.rcs.engine.mls.MlsEngineResult;
import com.android.messaging.rcs.engine.mls.MlsClaimLedger;
import com.android.messaging.rcs.engine.mls.MlsFetchBudget;
import com.android.messaging.rcs.engine.mls.MlsFetchLedger;
import com.android.messaging.rcs.engine.mls.MlsMaintenancePolicy;
import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.MlsOutboundHold;
import com.android.messaging.rcs.engine.mls.MlsPayloadCorruptor;
import com.android.messaging.rcs.engine.mls.MlsEraAdvanceCharge;
import com.android.messaging.rcs.engine.mls.MlsFtdEscalation;
import com.android.messaging.rcs.engine.mls.MlsResendRecord;
import com.android.messaging.rcs.engine.mls.MlsSelfHealPass;
import com.android.messaging.rcs.engine.mls.MlsSendRetentionPolicy;
import com.android.messaging.rcs.engine.mls.MlsInboundHold;
import com.android.messaging.rcs.engine.mls.MlsKeyPackagePolicy;
import com.android.messaging.rcs.engine.mls.MlsLogSink;
import com.android.messaging.rcs.engine.mls.MlsProcStatus;
import com.android.messaging.rcs.engine.mls.MlsReestablishPolicy;
import com.android.messaging.rcs.engine.mls.MlsRendezvous;
import com.android.messaging.rcs.engine.mls.MlsResultStatus;
import com.android.messaging.rcs.engine.mls.MlsServerPackOutcome;
import com.android.messaging.rcs.engine.mls.MlsStateTransition;
import com.android.messaging.rcs.engine.mls.MlsTransportDisposition;
import com.android.messaging.rcs.engine.mls.MlsMetrics;
import com.android.messaging.rcs.engine.mls.MlsTelemetry;
import com.android.messaging.rcs.engine.mls.MlsGroupArtifacts;
import com.android.messaging.rcs.engine.mls.MlsIdentity;
import com.android.messaging.rcs.engine.mls.MlsRecoveryPolicy;
import com.android.messaging.rcs.engine.mls.MlsRetryPolicy;
import com.android.messaging.rcs.engine.mls.MlsSession;
import com.android.messaging.rcs.engine.mls.RccCommitment;
import com.android.messaging.rcs.engine.mls.RccFileCrypto;
import com.android.messaging.rcs.engine.mls.RccFileInfo;
import com.android.messaging.rcs.engine.mls.RccIdentity;
import com.android.messaging.rcs.engine.mls.RccNegativeDeliveryImdn;
import com.android.messaging.rcs.engine.mls.VerifiableDerivedContent;
import com.android.messaging.rcs.engine.mls.MlsUpgradeClaim;
import com.android.messaging.rcs.engine.mls.MlsAnchorProvenance;
import com.android.messaging.rcs.engine.mls.MlsCommitSend;
import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsConversationRebuild;
import com.android.messaging.rcs.engine.mls.MlsDowngradeFlow;
import com.android.messaging.rcs.engine.mls.MlsEraAdvance;
import com.android.messaging.rcs.engine.mls.MlsExternalCommitResync;
import com.android.messaging.rcs.engine.mls.MlsGroupDeliveryLedger;
import com.android.messaging.rcs.engine.mls.MlsGroupEstablish;
import com.android.messaging.rcs.engine.mls.MlsGroupMetadata;
import com.android.messaging.rcs.engine.mls.MlsGroupSend;
import com.android.messaging.rcs.engine.mls.MlsGroupState;
import com.android.messaging.rcs.engine.mls.MlsIdentityRefresh;
import com.android.messaging.rcs.engine.mls.MlsImdnSigner;
import com.android.messaging.rcs.engine.mls.MlsInboundDecrypt;
import com.android.messaging.rcs.engine.mls.MlsKeyPackageClaims;
import com.android.messaging.rcs.engine.mls.MlsKeyPackagePool;
import com.android.messaging.rcs.engine.mls.MlsMaintenancePass;
import com.android.messaging.rcs.engine.mls.MlsMembership;
import com.android.messaging.rcs.engine.mls.MlsOffThread;
import com.android.messaging.rcs.engine.mls.MlsOneToOneGroup;
import com.android.messaging.rcs.engine.mls.MlsPendingBodyAccess;
import com.android.messaging.rcs.engine.mls.MlsRecordAccess;
import com.android.messaging.rcs.engine.mls.MlsRecordState;
import com.android.messaging.rcs.engine.mls.MlsRendezvousAccess;
import com.android.messaging.rcs.engine.mls.MlsPendingQueueAccess;
import com.android.messaging.rcs.engine.mls.MlsResend;
import com.android.messaging.rcs.engine.mls.MlsReupgradeAccess;
import com.android.messaging.rcs.engine.mls.MlsResendLedgerAccess;
import com.android.messaging.rcs.engine.mls.MlsSealSend;
import com.android.messaging.rcs.engine.mls.MlsSealedCacheAccess;
import com.android.messaging.rcs.engine.mls.MlsSelfHeal;
import com.android.messaging.rcs.engine.mls.MlsServerBundle;
import com.android.messaging.rcs.engine.mls.MlsServerMessage;
import com.android.messaging.rcs.engine.mls.MlsShellPort;
import com.android.messaging.rcs.engine.mls.MlsStallAlert;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate;
import com.android.messaging.rcs.engine.mls.MlsTreeLeaf;
import com.android.messaging.rcs.engine.mls.MlsTrace;
import com.android.messaging.rcs.engine.mls.MlsTransportDiagnostics;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.AnchorProbe;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraReconcile;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.GroupMembershipRouting;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.GroupPlane;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ImdnCheck;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ImdnStamps;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsWelcomeAction;
import com.android.messaging.rcs.engine.mls.OpenMlsEngine;
import com.android.messaging.rcs.engine.mls.Rcc16Version;
import com.android.messaging.rcs.engine.mls.OpenMlsSession;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * App-owned MLS over the RCS provider: this app owns the engine, the group state and the framing,
 * and the provider carries envelopes without parsing MLS. The sibling of
 * {@link MlsCarrierTransport}, with the same contract and engine; kept a separate class because
 * the transports differ in capability ({@link RcsMlsTransportProfile}), not only in addressing.
 *
 * <p>This class keeps the Android effects; the decisions live in engine classes reached through
 * {@link MlsShellPort}; each delegate's one-line javadoc names its target. See
 * docs/mls/transport-and-port.md.
 */
public final class MlsProviderTransport implements E2eeConversationTransport {

    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    private static final String PREFS = "mls_provider_conv";
    private static final long ERA_INITIAL = MlsTransportTypes.ERA_INITIAL;
    public static final String HDR_SEALED_MESSAGE_ID = MlsSealSend.HDR_SEALED_MESSAGE_ID;

    /** @see MlsConversationKey#canonicalKey */
    private static String canonicalKey(final String rcsGroupId, final String peerE164) {
        return MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
    }

    /** @see MlsConversationKey#splitCanonicalKey */
    private static String[] splitCanonicalKey(final String key) {
        return MlsConversationKey.splitCanonicalKey(key);
    }

    private final Context mCtx;
    private final int mSubId;
    private final Map<String, Group> mGroups = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * App conversation id to canonical key, for the {@link E2eeConversationTransport} entry points.
     */
    private final Map<String, String> mConvAlias = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Canonical key to app conversation id, the inverse of {@link #mConvAlias}: decisions made in
     * key space (downgrade, health) must land where {@code encryption_protocol} and the re-upgrade
     * columns live. A map rather than a scan, which would pick an arbitrary winner when two ids
     * alias one key.
     */
    private final Map<String, String> mKeyToConvId = new java.util.concurrent.ConcurrentHashMap<>();

    public static final int ERA_ADVANCE_ROSTER_NOT_READY =
            MlsFloorRebuild.ERA_ADVANCE_ROSTER_NOT_READY;

    public static final int RESYNC_DRY_RUN_VIABLE = MlsExternalCommitResync.RESYNC_DRY_RUN_VIABLE;

    public static final int RESYNC_DRY_RUN_NOT_VIABLE =
            MlsExternalCommitResync.RESYNC_DRY_RUN_NOT_VIABLE;



    /** Canonical key → its in-memory state. */
    private final Map<String, ConvState> mConv = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The state for {@code key}, created if absent. Write paths only: a read path calling this
     * would create an entry for every key it is asked about.
     */
    private ConvState conv(final String key) {
        ConvState s = mConv.get(key);
        if (s == null) {
            final ConvState fresh = new ConvState();
            s = mConv.putIfAbsent(key, fresh);
            if (s == null) s = fresh;
        }
        return s;
    }

    /** The state for {@code key}, or null; the read-path accessor. */
    private ConvState convIfAny(final String key) {
        return key == null ? null : mConv.get(key);
    }

    private MlsSession mSelf;
    private RcsMlsTransportProfile mProfile;

    /**
     * Every behavioural knob, resolved once at construction (see {@link MlsConfig}); a changed
     * property takes effect after a restart.
     */
    private final MlsConfig mCfg;
    /** Engine-side decision code logs through this. */
    private final MlsLogSink mLog = MlsHostPorts.logSink();
    /** Engine code reaches this transport's effects through this port. */
    private final MlsShellPort mShell = new MlsShellPort() {
        @Override public boolean ensureSession() {
            return MlsProviderTransport.this.ensureSession();
        }
        @Override public MlsLogSink log() { return mLog; }
        @Override public MlsConfig cfg() { return mCfg; }
        @Override public Group getGroup(final String conversationId) {
            return MlsProviderTransport.this.getGroup(conversationId);
        }
        @Override public MlsSession session() { return MlsProviderTransport.this.mSelf; }
        @Override public MlsTelemetry telemetry() { return MlsProviderTransport.this.mTelemetry; }
        @Override public MlsPrefs prefs() {
            return MlsAndroidPrefs.wrap(mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        }
        @Override public MlsSession openMlsSession() { return MlsProviderTransport.this.openMls(); }
        @Override public long elapsedRealtime() { return android.os.SystemClock.elapsedRealtime(); }
        @Override public MlsSysProps sysprops() { return MlsAndroidSysProps.INSTANCE; }
        @Override public MlsPeerGuards peerGuard() { return MlsPeerGuardBinding.INSTANCE; }
        @Override public MlsExternalCommitLimits xcBudget() {
            return MlsProviderTransport.this.xcBudget();
        }
        @Override public MlsProviderRpc.TransportProfile transportProfile() {
            return MlsProviderRpcBinding.mirror(MlsProviderTransport.this.profile());
        }
        @Override public MlsRebuildLimits rebuildLimiter() {
            return MlsProviderTransport.this.rebuildLimiter();
        }
        @Override public MlsProviderRpc rpc(final String what) {
            return new MlsProviderRpcBinding(MlsProviderTransport.this.pt(what), mSubId);
        }
        @Override public Map<String, ConvState> convStates() {
            return MlsProviderTransport.this.mConv;
        }
        @Override public String selfE164() {
            return MlsProviderTransport.this.selfE164();
        }
        @Override public void lock(final String key) {
            MlsProviderTransport.this.lock(key);
        }
        @Override public void unlock(final String key) {
            MlsProviderTransport.this.unlock(key);
        }
        @Override public int commitAndSend(final String rcsGroupId, final String peerE164,
                final byte[] addPeerKeyPackage, final byte[] memberSigPub, final Op op,
                final String what) {
            return MlsProviderTransport.this.commitAndSend(rcsGroupId, peerE164, addPeerKeyPackage,
                    memberSigPub, op, what);
        }
        @Override public Claim<java.util.List<byte[]>> claimAll(final MlsClaimLedger.Caller caller,
                final String peer) {
            return MlsProviderTransport.this.claimAll(caller, peer);
        }
        @Override public ConvState conv(final String key) {
            return MlsProviderTransport.this.conv(key);
        }
        @Override public byte[] openStoredIconSubject(final String rcsGroupId,
                final String peerE164, final boolean icon, final byte[] encryptedContent) {
            return MlsProviderTransport.this.openStoredIconSubject(rcsGroupId, peerE164, icon,
                    encryptedContent);
        }
        @Override public ConvState convIfAny(final String key) {
            return MlsProviderTransport.this.convIfAny(key);
        }
        @Override public void putGroup(final String conversationId, final Group g) {
            MlsProviderTransport.this.putGroup(conversationId, g);
        }
        @Override public boolean keyPackageUsable(final byte[] kp, final String who) {
            return MlsProviderTransport.this.keyPackageUsable(kp, who);
        }
        @Override public MlsRecordAccess records() { return MlsProviderTransport.this.mRecords; }
        @Override public Map<String, Group> groups() { return MlsProviderTransport.this.mGroups; }
        @Override public int selfHeal(final String rcsGroupId, final String peerE164) {
            return MlsProviderTransport.this.selfHeal(rcsGroupId, peerE164);
        }
        @Override public java.util.Set<String> pendingOpsClaimedThisProcess(
                ) { return MlsProviderTransport.this.mPendingOpsClaimedThisProcess; }
        @Override public boolean onFileInfo(final String rcsGroupId, final String fromE164,
                final byte[] fileInfoProto) {
            return MlsProviderTransport.this.onFileInfo(rcsGroupId, fromE164, fileInfoProto);
        }
        @Override public int rekey(final String rcsGroupId, final String peerE164) {
            return MlsProviderTransport.this.rekey(rcsGroupId, peerE164);
        }
        @Override public Claim<byte[]> claimOne(final MlsClaimLedger.Caller caller,
                final String peer) {
            return MlsProviderTransport.this.claimOne(caller, peer);
        }
        @Override public int eraAdvance(final String rcsGroupId, final String peerE164,
                final byte[] carryGroupInfo, final MlsAdvanceEraKind kind,
                final boolean requireRebuildableRoster) {
            return MlsProviderTransport.this.eraAdvance(rcsGroupId, peerE164, carryGroupInfo, kind,
                    requireRebuildableRoster);
        }
        @Override public int eraAdvance(final String rcsGroupId, final String peerE164,
                final byte[] carryGroupInfo) {
            return MlsProviderTransport.this.eraAdvance(rcsGroupId, peerE164, carryGroupInfo);
        }
        @Override public java.util.concurrent.atomic.AtomicBoolean sweepInFlight(
                ) { return MlsProviderTransport.this.mSweepInFlight; }
        @Override public void raiseStall(final String key, final String peerE164,
                final String rcsGroupId, final String detail) {
            MlsProviderTransport.this.raiseStall(key, peerE164, rcsGroupId, detail);
        }
        @Override public void clearStall(final String key) {
            MlsProviderTransport.this.clearStall(key);
        }
        @Override public String selfE164Raw() { return MlsProviderTransport.this.mSelfE164; }
        @Override public boolean publishKeyPackages(final int count) {
            return MlsProviderTransport.this.publishKeyPackages(count);
        }
        @Override public void noteMlsPlaneInUse(final String conversationKey) {
            MlsProviderTransport.this.noteMlsPlaneInUse(conversationKey);
        }
        @Override public int rekey(final String peerE164) {
            return MlsProviderTransport.this.rekey(peerE164);
        }
        @Override public void dropSession() { MlsProviderTransport.this.dropSession(); }
        @Override public MlsOutboundHold.Verdict outboundHoldVerdict(final String conversationKey,
                final boolean isRekey) {
            return MlsProviderTransport.this.outboundHoldVerdict(conversationKey, isRekey);
        }
        @Override public int suppressCommit(final String conversationId, final Group g, final Op op,
                final String what, final String ctrlId, final String peerE164,
                final String rcsGroupId, final MlsGroupArtifacts art, final byte[] baseEpochAuth,
                final byte[] snapshot, final int preEra, final long preEpoch) {
            return MlsProviderTransport.this.suppressCommit(conversationId, g, op, what, ctrlId,
                    peerE164, rcsGroupId, art, baseEpochAuth, snapshot, preEra, preEpoch);
        }
        @Override public void noteOutboundHoldRefused() {
            MlsOutboundHoldStore.get(mCtx).noteRefused();
        }
        @Override public boolean applyGroupIcon(final String rcsGroupId, final byte[] iconBytes) {
            return GroupIconApplier.apply(mCtx, rcsGroupId, iconBytes);
        }
        @Override public boolean applyGroupSubject(final String rcsGroupId, final String subject) {
            return MlsSubjectApplier.apply(rcsGroupId, subject);
        }
        @Override public Boolean conversationMlsBit(final String convId) {
            return MlsProviderTransport.this.conversationMlsBit(convId);
        }
        @Override public void scheduleRetry(final String rcsGroupId, final String peerE164,
                final int attempt) {
            MlsProviderTransport.this.scheduleRetry(rcsGroupId, peerE164, attempt);
        }
        @Override public Map<String, String> convAlias(
                ) { return MlsProviderTransport.this.mConvAlias; }
        @Override public Map<String, String> keyToConvId(
                ) { return MlsProviderTransport.this.mKeyToConvId; }
        @Override public java.util.List<String> bugleRoster(final String rcsGroupId,
                final String self) {
            return MlsProviderTransport.this.bugleRoster(rcsGroupId, self);
        }
        @Override public int subId() { return MlsProviderTransport.this.mSubId; }
        @Override public boolean forget(final String rcsGroupId, final String peerE164) {
            return MlsProviderTransport.this.forget(rcsGroupId, peerE164);
        }
        @Override public java.util.Map<String, Integer> eraAsked(
                ) { return MlsProviderTransport.this.mEraAsked; }
        @Override public java.util.Set<String> eraQuotaReported(
                ) { return MlsProviderTransport.this.mEraQuotaReported; }
        @Override public MlsPendingBodyAccess pendingBodies(
                ) { return MlsProviderTransport.this.mPendingBodies; }
        @Override public MlsSealedCacheAccess sealedCache(
                ) { return MlsProviderTransport.this.mSealedCache; }
        @Override public MlsDriveLoop driveLoop() { return MlsProviderTransport.this.mDriveLoop; }
        @Override public com.android.messaging.rcs.engine.mls.MlsOffThread offThread(
                ) { return MlsProviderTransport.this.mOffThread; }
        @Override public boolean ensureReady(final String conversationId, final int subId,
                final List<String> peers) {
            return MlsProviderTransport.this.ensureReady(conversationId, subId, peers);
        }
        @Override public MlsRendezvousAccess rendezvous(
                ) { return MlsProviderTransport.this.mRendezvous; }
        @Override public MlsPendingQueueAccess pendingQueue() {
            return MlsProviderTransport.this.pendingQueue();
        }
        @Override public MlsReupgradeAccess reupgradeStore() {
            return MlsProviderTransport.this.reupgradeStore();
        }
        @Override public void downgradeMlsScheme(final String conversationId) {
            MlsProviderTransport.this.schemeGate().downgradeMls(conversationId);
        }
        @Override public MlsResendLedgerAccess resendLedger() {
            return MlsResendLedgerBinding.INSTANCE;
        }
        @Override public boolean applyGroupDeparture(final String rcsGroupId,
                final String departedE164) {
            return GroupDepartureApplier.apply(mSubId, rcsGroupId, departedE164);
        }
        @Override public MlsIdentity loadIdentity() { return MlsIdentityStore.loadIdentity(mCtx); }
        @Override public boolean refreshIdentityFromProvider() {
            return MlsIdentityStore.refreshFromProvider(mCtx, mSubId);
        }
        @Override public boolean offerInboundHold(final String conversationKey,
                final String fromE164, final String messageId, final byte[] mlsBytes) {
            return MlsInboundHoldStore.get(mCtx).offer(conversationKey, fromE164, messageId,
                    mlsBytes);
        }
        @Override public byte[] base64Decode(final String s) {
            return Base64.decode(s, Base64.DEFAULT);
        }
        @Override public String findTextByRcsMessageId(final String rcsMessageId) {
            return RcsMessageStore.findTextByRcsMessageId(rcsMessageId);
        }
        @Override public String findGroupIdByRcsMessageId(final String rcsMessageId) {
            return RcsMessageStore.findGroupIdByRcsMessageId(rcsMessageId);
        }
        @Override public boolean applyInboundControl(final String fromE164, final String messageId,
                final byte[] mlsBytes, final boolean convergenceAck, final String rcsGroupId) {
            return MlsProviderTransport.this.applyInboundControl(fromE164, messageId, mlsBytes,
                    convergenceAck, rcsGroupId);
        }
        @Override public void replayParkedApplication(final String conversationId,
                final String fromE164, final String rcsGroupId, final MlsPendingQueue.Entry e,
                final MlsAppMessage.Moment now) {
            MlsProviderTransport.this.replayParkedApplication(conversationId, fromE164, rcsGroupId,
                    e, now);
        }
        @Override public boolean sendFramedToGroup(final String rcsGroupId, final byte[] framedBody,
                final String idPrefix, final String rcsMessageId) {
            return MlsProviderTransport.this.sendFramedToGroup(rcsGroupId, framedBody, idPrefix,
                    rcsMessageId);
        }
        @Override public String conversationIdFor(final String key) {
            return MlsProviderTransport.this.conversationIdFor(key);
        }
        @Override public int eraAdvance(final String rcsGroupId, final String peerE164) {
            return MlsProviderTransport.this.eraAdvance(rcsGroupId, peerE164);
        }
        @Override public com.android.messaging.rcs.engine.mls.MlsGroupDeliveryLedger groupDelivery(
                ) { return MlsProviderTransport.this.mGroupDelivery; }
    };

    /** The metrics port; never null. */
    private final MlsTelemetry mTelemetry = LogcatMlsTelemetry.get();

    /** "Have we already decrypted this?" Assigned in the constructor. */
    private final MlsRendezvousStore mRendezvous;

    /** "Have we already sealed this?" Assigned in the constructor. */
    private final MlsCiphertextCache mSealedCache;
    /**
     * The framed body of an in-flight message, so a resend never depends on a chat row not yet
     * written.
     */
    private final MlsPendingBodyStore mPendingBodies;

    /**
     * The bounded fixed point; holds no per-drive state, so one instance serves every conversation.
     */
    private final MlsDriveLoop mDriveLoop = new MlsDriveLoop(mTelemetry);

    // The lock model: one lock per (identity, group), held across the engine call and its storage
    // write, never across transport I/O. See docs/mls/transport-and-port.md#the-lock-model.

    private final MlsGroupLocks mLocks = new MlsGroupLocks();

    /** The persisted per-conversation record, written whole, keyed {@code (identity, group)}. */
    private final MlsRecordStore mRecords;   // assigned in the constructor

    /**
     * The identity-scope lock: session bring-up and the session's fields ({@code mSelf}, {@code
     * mProfile}, {@code mSelfE164}). A dedicated object so no outside caller can join the lock
     * ordering by synchronising on the instance.
     */
    private final Object mSessionLock = new Object();

    /** Take the {@code (identity, group)} lock; always paired with {@link #unlock} in a finally. */
    private void lock(final String key) { mLocks.lockFor(selfE164(), key).lock(); }

    /** Release it. Reentrant. */
    private void unlock(final String key) { mLocks.lockFor(selfE164(), key).unlock(); }

    /**
     * The provider handle, and the check for the lock model's third clause: every provider call
     * goes through here, so holding a group lock across one is detected. Throws on a debuggable
     * build; logs with a stack trace otherwise, so a lock-ordering slip never drops a message.
     */
    private ProviderTransport pt(final String what) {
        if (mLocks.anyHeldByCurrentThread()) {
            final IllegalStateException e = new IllegalStateException(
                    "MLS group lock held across transport I/O (" + what + ") — holding: "
                    + mLocks.heldByCurrentThread() + ". The lock covers the engine call and its "
                    + "storage write ONLY; see MlsGroupLocks.");
            if (LOCK_ASSERTIONS) throw e;
            LogUtil.e(TAG, "MlsProviderTransport: " + e.getMessage(), e);
        }
        return ProviderTransport.getInstance(mCtx);
    }

    // Context-bound effects, one line each, reached by engine code through MlsShellPort.

    /** Schedule an MlsRetryWorker re-drive of this conversation. */
    private void scheduleRetry(final String rcsGroupId, final String peerE164, final int attempt) {
        MlsRetryWorker.enqueue(mCtx, mSubId, rcsGroupId, peerE164, attempt);
    }

    /** Post (or update) the stalled-conversation notification. */
    private void raiseStall(final String key, final String peerE164, final String rcsGroupId,
            final String detail) {
        MlsStalledNotifier.raise(mCtx, mSubId, key, peerE164, rcsGroupId, detail);
    }

    /** Withdraw the stalled-conversation notification, if one is up. */
    private void clearStall(final String key) {
        MlsStalledNotifier.clear(mCtx, key);
    }

    /**
     * The conversation's MLS encryption bit from its stored {@link EncryptionProtocolBits}, or null
     * when there is no row or it cannot be read. Null is not "plaintext".
     */
    private Boolean conversationMlsBit(final String convId) {
        final EncryptionProtocolBits bits = new ConversationBitsStore().loadOrNull(convId);
        return bits == null ? null : Boolean.valueOf(bits.mlsBit());
    }

    /**
     * Drop the open session so the next {@code ensureSession()} reopens against the identity on
     * disk. The only write of {@code mSelf} outside {@code ensureSession}.
     */
    private void dropSession() {
        synchronized (mSessionLock) {
            mSelf = null;
        }
    }

    /** Debuggable builds fail loudly on a lock-model violation; user builds log and continue. */
    private static final boolean LOCK_ASSERTIONS = RcsDebug.isDebugBuild();

    /** @see MlsMetrics#noteStateWrite */
    private void noteStateWrite() {
        MlsMetrics.noteStateWrite(mShell);
    }

    public MlsProviderTransport(final Context ctx, final int subId) {
        this(ctx, subId, SYSPROP_SOURCE);
    }

    /** Test seam: the same object with knobs the caller chooses. */
    public MlsProviderTransport(final Context ctx, final int subId, final MlsConfig.Source cfg) {
        mCtx = ctx.getApplicationContext();
        mSubId = subId;
        mCfg = MlsConfig.from(cfg);
        mRecords = new MlsRecordStore(mCtx);
        mRendezvous = new MlsRendezvousStore(mCtx);
        mSealedCache = new MlsCiphertextCache(mCtx);
        mPendingBodies = new MlsPendingBodyStore(mCtx);
        // Say that these values are cached: a property set now takes effect only after a restart.
        LogUtil.i(TAG, "MlsProviderTransport: subId=" + subId + " " + mCfg
                + " [POLICY VALUES ABOVE ARE CACHED AT CONSTRUCTION — setprop then restart "
                + "the app for them to take effect. The dump* diagnostics are read live.]");
    }

    /** The device's system properties, the production {@link MlsConfig.Source}. */
    private static final MlsConfig.Source SYSPROP_SOURCE = new MlsConfig.Source() {
        @Override public boolean getBoolean(final String k, final boolean d) {
            return android.os.SystemProperties.getBoolean(k, d);
        }
        @Override public int getInt(final String k, final int d) {
            return android.os.SystemProperties.getInt(k, d);
        }
        @Override public long getLong(final String k, final long d) {
            return android.os.SystemProperties.getLong(k, d);
        }
    };

    /**
     * Bring up the engine session for this identity. It is per identity, shared by every group, so
     * it takes {@link #mSessionLock} rather than a group lock; once up, the fast path takes no
     * lock. The one provider call inside it (the transport profile read) is a bring-up-only
     * exception to the lock model, which {@link #pt} permits because it checks group locks only.
     */
    private boolean ensureSession() {
        // Drain the previous operation's state-write size here, once per operation, rather than at
        // every engine call site. Reading clears the engine's counter.
        noteStateWrite();
        if (mSelf != null) return true;         // fast path: no lock once the session is up
        synchronized (mSessionLock) {
        if (mSelf != null) return true;         // another thread won the race while we waited
        // Pick up a renewed enrolment identity first: the provider re-mints the leaf, and a stale
        // one would stop MLS.
        maybeRefreshIdentity();
        final MlsIdentity id = MlsIdentityStore.loadIdentity(mCtx);
        if (id == null) {
            LogUtil.w(TAG,
                    "MlsProviderTransport: no adopted MLS identity — run the state migration "
                    + "first; the engine cannot open a session without it");
            return false;
        }
        // The deployment's RCC.16 revision is a property of the transport, not something the engine
        // can discover; the property exists to force another revision for interop testing.
        // Google's KDS wants 365-day KeyPackages; deployed peer leaves need the tolerant policy.
        mSelf = new OpenMlsEngine(OpenMlsEngine.KeyPackageLifetime.FIXED_365_DAYS,
                        OpenMlsEngine.PeerCertificatePolicy.DEPLOYMENT_TOLERANT,
                        Rcc16Version.fromWire(mCfg.rcc16Version))
                .startSession(MlsHostPorts.storageRoot(mCtx), id, MlsHostPorts.forApp(mCtx));
        if (mSelf == null) {
            LogUtil.e(TAG, "MlsProviderTransport: engine refused the adopted identity/state");
            return false;
        }
        mProfile = MlsProviderRpcBinding.rcs(
                mShell.rpc("getMlsTransportProfile").getMlsTransportProfile());
        mSelfE164 = id.e164;
        LogUtil.i(TAG, "MlsProviderTransport: session up for " + LogMask.number(id.e164)
                + " profile=" + mProfile);
        maybePublishKeyPackages();
        }
        // Arm the group sweep after the session lock is released: maintenance does transport I/O,
        // and without the sweep a group nobody opens is never maintained. Reached only on bring-up.
        armGroupSweep("session bring-up");
        return true;
    }

    @Override
    public boolean ensureReady(final String conversationId, final int subId,
            final List<String> peers) {
        return ensureReady(conversationId, subId, peers, /*recreateAlreadyCharged=*/ false,
                /*stateAlreadyDestroyed=*/ false);
    }

    /** @see MlsOneToOneGroup#ensureReady */
    private boolean ensureReady(final String conversationId, final int subId,
            final List<String> peers, final boolean recreateAlreadyCharged,
            final boolean stateAlreadyDestroyed) {
        return MlsOneToOneGroup.ensureReady(mShell, mLog, conversationId, subId, peers,
                recreateAlreadyCharged, stateAlreadyDestroyed);
    }

    /** @see MlsSealSend#encryptForSend */
    public Payload encryptForSend(final String conversationId, final byte[] framedBody,
            final String rcsMessageId) {
        return MlsSendPayloadBinding.payload(
                MlsSealSend.encryptForSend(mShell, mLog, conversationId, framedBody, rcsMessageId));
    }

    /**
     * Process an inbound MLS body through the seam the carrier transport uses. {@code
     * envelopeMessageId} is unused here: provider traffic arrives through {@code onMlsControl} and
     * the application decrypt path, where the RCC.16 §7.5.3.1 check already runs.
     */
    @Override
    public Inbound onInboundCpim(final String conversationId, final String senderE164,
            final String contentType, final byte[] payload, final String envelopeMessageId) {
        if (!ensureSession() || payload == null || payload.length == 0) return Inbound.ignored();
        final Group g = getGroup(conversationId);
        if (g == null || g.groupId == null) {
            LogUtil.w(TAG, "MlsProviderTransport: inbound for an unknown conversation "
                    + MlsConversationKey.forLog(conversationId)
                    + " — buffering is not implemented");
            return Inbound.ignored();
        }
        try {
            final byte[] plaintext = mSelf.process(g.groupId, payload);
            if (plaintext == null) return Inbound.control();
            return Inbound.message(plaintext);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsProviderTransport: inbound processing failed", t);
            return Inbound.ignored();
        }
    }

    /** @see MlsSealSend#sendSealed */
    public boolean sendSealed(final String peerE164, final Payload p) {
        return MlsSealSend.sendSealed(mShell, mLog, peerE164, MlsSendPayloadBinding.mirror(p));
    }

    /** @see MlsCommitApplication#applyInboundControl */
    public boolean applyInboundControl(final String fromE164, final String messageId,
            final byte[] mlsBytes, final boolean convergenceAck, final String rcsGroupId) {
        return MlsCommitApplication.applyInboundControl(mCfg, mShell, mLog, fromE164, messageId,
                mlsBytes, convergenceAck, rcsGroupId);
    }

    /** @see MlsTransportDiagnostics#probeAnchor */
    public AnchorProbe probeAnchor(final String rcsGroupId, final String peerE164) {
        return MlsTransportDiagnostics.probeAnchor(mShell, mLog, rcsGroupId, peerE164);
    }

    /** @see MlsSelfHeal#selfHeal */
    public int selfHeal(final String rcsGroupId, final String peerE164) {
        return MlsSelfHeal.selfHeal(mCfg, mShell, mLog, rcsGroupId, peerE164);
    }

    // RCC.16 §10 failure-to-decrypt reporting and escalation: self-heal first and report only what
    // still fails; the remedy depends on what failed. See docs/mls/health-and-recovery.md.

    /**
     * An inbound message did not decrypt: begin RCC.16 §10 recovery, self-heal first (RCC.16
     * §10.1) and report only what still fails (RCC.16 §10.2).
     */
    public void onDecryptFailure(final String rcsGroupId, final String fromE164,
            final String messageId) {
        onDecryptFailure(rcsGroupId, fromE164, messageId, /*ciphertext=*/ null, /*eraId=*/ -1L);
    }

    /** @see MlsFtdEscalation#onDecryptFailure */
    public void onDecryptFailure(final String rcsGroupId, final String fromE164,
            final String messageId, final byte[] ciphertext, final long eraId) {
        MlsFtdEscalation.onDecryptFailure(mCfg, mShell, mLog, rcsGroupId, fromE164, messageId,
                ciphertext, eraId);
    }

    /** Arm the one-shot failure-reason override for the next report. */
    public static void setNextFtdReason(final int code) {
        MlsPayloadCorruptor.armFtdReasonOverride(code);
    }

    private static final String PREF_FAIL_NEXT_DECRYPT = MlsPayloadCorruptor.PREF_FAIL_NEXT_DECRYPT;

    /** Arm the one-shot forced decrypt failure; survives an app restart. */
    public static void setFailNextDecrypt(final Context ctx) {
        if (ctx == null) return;
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_FAIL_NEXT_DECRYPT, true).commit();
    }

    /** Arm the one-shot post-seal ciphertext corruption for the next group send. */
    public static void setCorruptNextCt() {
        MlsPayloadCorruptor.armCorruptNextCt();
    }

    /** @see MlsFtdEscalation#reportFtdWithoutHealing */
    public void reportFtdWithoutHealing(final String rcsGroupId, final String fromE164,
            final String messageId) {
        MlsFtdEscalation.reportFtdWithoutHealing(mShell, mLog, rcsGroupId, fromE164, messageId);
    }

    /** @see MlsFtdEscalation#onPeerReportedFailure */
    public void onPeerReportedFailure(final String reportedGroupId, final String peerE164,
            final String messageId, final int failureReason) {
        MlsFtdEscalation.onPeerReportedFailure(mCfg, mShell, mLog, reportedGroupId, peerE164,
                messageId, failureReason);
    }

    /** @see MlsFtdEscalation#forgetResendHistory */
    public int forgetResendHistory(final String rcsGroupId, final String peerE164) {
        return MlsFtdEscalation.forgetResendHistory(mShell, mLog, rcsGroupId, peerE164);
    }

    /** @see MlsResend#resendByUser */
    public boolean resendByUser(final String rcsGroupId, final String peerE164,
            final String rcsMessageId) {
        return MlsResend.resendByUser(mShell, mLog, rcsGroupId, peerE164, rcsMessageId);
    }

    /**
     * Hands a parked application ciphertext back to the inbound pipeline, installed by
     * {@code RcsCallbackRouter}: classification, IMDN checks and insertion live there, so a drained
     * message and a punctual one take the same path.
     */
    public interface PendingAppReplay {
        /**
         * Replay one parked application ciphertext. The RCC.16 transport headers are not passed:
         * their check ran at admission, and they were not persisted.
         */
        void replayParkedMlsCiphertext(int subId, String fromE164, String messageId,
                byte[] ciphertext, String rcsGroupId, long eraId);
    }

    private static volatile PendingAppReplay sAppReplay;

    /** Install the application-plane replay; idempotent, last writer wins. */
    public static void setPendingAppReplay(final PendingAppReplay r) { sAppReplay = r; }

    private void replayParkedApplication(final String conversationId, final String fromE164,
            final String rcsGroupId, final MlsPendingQueue.Entry e,
            final MlsAppMessage.Moment now) {
        final PendingAppReplay r = sAppReplay;
        if (r == null) {
            // No fallback through the control path. The entry was already taken from the queue, so
            // log the message as lost.
            LogUtil.e(TAG, "MlsProviderTransport: " + MlsMessageId.forLog(e.messageId) + " from "
                    + LogMask.number(fromE164)
                    + " reached its moment " + now
                    + " but NO application-plane replay is installed "
                    + "— the message is LOST. RcsCallbackRouter installs it at startup; if this "
                    + "fires, that wiring is broken. Not falling back to the control path: that "
                    + "path decrypts the message and discards it.");
            return;
        }
        LogUtil.i(TAG, "MlsProviderTransport: replaying parked APPLICATION message "
                + MlsMessageId.forLog(e.messageId)
                + " from " + LogMask.number(fromE164) + " at its exact moment " + now + " ("
                + (e.blob == null ? 0 : e.blob.length) + "B) through the inbound message pipeline");
        r.replayParkedMlsCiphertext(mSubId, fromE164, e.messageId, e.blob, rcsGroupId,
                e.moment == null ? -1L : e.moment.era);
    }

    private MlsPendingQueueStore mPendingQueueStore;

    private synchronized MlsPendingQueueStore pendingQueue() {
        if (mPendingQueueStore == null) mPendingQueueStore = new MlsPendingQueueStore(mCtx);
        return mPendingQueueStore;
    }

    /**
     * Retry a failed decrypt once, if the join that gives us the keys is still in flight. A Welcome
     * and the first application message arrive on separate callbacks, so the decrypt can beat the
     * join by milliseconds; the pending queue cannot hold it because it is keyed by group, and
     * there is no group yet. Bounded: if no group appears within {@code windowMs} this returns
     * {@code null} and the caller takes the ordinary RCC.16 §10 path.
     *
     * @return the decrypted content if the join landed and the retry succeeded, else {@code null}
     */
    public RccMlsBody.Parsed awaitJoinAndRetryDecrypt(final String fromE164, final String messageId,
            final byte[] ciphertext, final String rcsGroupId, final long windowMs) {
        return awaitJoinAndRetryDecrypt(fromE164, messageId, ciphertext, rcsGroupId, windowMs,
                /*originalMessageId=*/ null);
    }

    /** @see MlsInboundDecrypt#awaitJoinAndRetryDecrypt */
    public RccMlsBody.Parsed awaitJoinAndRetryDecrypt(final String fromE164, final String messageId,
            final byte[] ciphertext, final String rcsGroupId, final long windowMs,
            final String originalMessageId) {
        return MlsInboundDecrypt.awaitJoinAndRetryDecrypt(mCfg, mShell, mLog, fromE164, messageId,
                ciphertext, rcsGroupId, windowMs, originalMessageId);
    }

    public RccMlsBody.Parsed decryptInbound(final String fromE164, final String messageId,
            final byte[] ciphertext, final String rcsGroupId) {
        return decryptInbound(fromE164, messageId, ciphertext, rcsGroupId,
                /*originalMessageId=*/ null);
    }

    /** @see MlsInboundDecrypt#decryptInbound */
    public RccMlsBody.Parsed decryptInbound(final String fromE164, final String messageId,
            final byte[] ciphertext, final String rcsGroupId, final String originalMessageId) {
        return MlsInboundDecrypt.decryptInbound(mCfg, mShell, mLog, fromE164, messageId, ciphertext,
                rcsGroupId, originalMessageId);
    }

    /**
     * Release this message's rendezvous rows once it has a durable chat row (see
     * {@link MlsRendezvousStore#deleteForMessage}).
     *
     * @return how many rows were released
     */
    public int forgetRendezvous(final String rcsMessageId) {
        return mRendezvous.deleteForMessage(selfE164(), rcsMessageId);
    }

    /** @see MlsSendRetentionPolicy#releaseSealedOnPositiveReceipt */
    public void releaseSealedOnPositiveReceipt(final String rcsMessageId) {
        MlsSendRetentionPolicy.releaseSealedOnPositiveReceipt(mShell, mLog, rcsMessageId);
    }

    /** @see MlsTransportDiagnostics#dumpMemberParticipantKeys */
    public String dumpMemberParticipantKeys(final String rcsGroupId, final String peerE164) {
        return MlsTransportDiagnostics.dumpMemberParticipantKeys(mShell, rcsGroupId, peerE164);
    }

    /** Per-message, per-member group delivery coverage. */
    private final com.android.messaging.rcs.engine.mls.MlsGroupDeliveryLedger mGroupDelivery =
            new com.android.messaging.rcs.engine.mls.MlsGroupDeliveryLedger();

    /** @see MlsSendRetentionPolicy#releaseSealedOnGroupReceipt */
    public void releaseSealedOnGroupReceipt(final String rcsMessageId, final String fromE164) {
        MlsSendRetentionPolicy.releaseSealedOnGroupReceipt(mShell, mLog, rcsMessageId, fromE164);
    }

    /** @see MlsSendRetentionPolicy#releaseSealedOnPermanentFailure */
    public void releaseSealedOnPermanentFailure(final String rcsMessageId) {
        MlsSendRetentionPolicy.releaseSealedOnPermanentFailure(mShell, mLog, rcsMessageId);
    }

    /** @see MlsTransportDiagnostics#probeProcessResults */
    public String probeProcessResults(final String rcsGroupId, final String peerE164,
            final byte[] wire, final String contextId) {
        return MlsTransportDiagnostics.probeProcessResults(mShell, rcsGroupId, peerE164, wire,
                contextId);
    }

    /** Cached-ciphertext count, for the debug dump. */
    public int sealedCacheSize() { return mSealedCache.size(); }

    /** Rendezvous row count, for the debug dump. */
    public int rendezvousSize() { return mRendezvous.size(); }

    // Recovery plumbing; the decisions are in MlsRecoveryPolicy.

    // The send-gate timestamp is ConvState.gateOpenedAt and the yield is ConvState.eraYield.

    /** @see MlsTransportDiagnostics#dumpAdvancerElection */
    public String dumpAdvancerElection(final String rcsGroupId, final String peerE164) {
        return MlsTransportDiagnostics.dumpAdvancerElection(mCfg, mShell, mLog, rcsGroupId,
                peerE164);
    }

    /**
     * Rekey the group with an app-built {@code selfUpdate} commit. The commit carries the
     * authenticator from before it, which is how the server knows the epoch we move from.
     *
     * @return the new era, or -1 if the commit was refused or could not be built
     */
    public int rekey(final String peerE164) {
        return commitAndSend(null, peerE164, null, null, Op.REKEY, "rekey");
    }

    /**
     * Rekey a specific conversation, which may be a group. The one-argument form resolves the
     * peer's 1:1, so for a group member it would rekey a different conversation.
     */
    public int rekey(final String rcsGroupId, final String peerE164) {
        return commitAndSend(rcsGroupId, peerE164, null, null, Op.REKEY, "rekey");
    }

    /**
     * Create the MLS group for an RCS group we own, every member's devices in the initial commit.
     * Every member must already be an RCS participant of {@code rcsGroupId}, or the commit is
     * refused; this adds nobody to the RCS group. See docs/mls/group-lifecycle.md.
     *
     * @return the era of the established group, or -1
     */
    public int establishGroup(final String rcsGroupId, final List<String> members) {
        return establishGroup(rcsGroupId, members, /*carryGroupInfo=*/ null);
    }

    /** @see MlsGroupEstablish#establishGroup */
    public int establishGroup(final String rcsGroupId, final List<String> members,
            final byte[] carryGroupInfo) {
        return MlsGroupEstablish.establishGroup(mShell, mLog, rcsGroupId, members, carryGroupInfo);
    }

    /** @see MlsMembership#allowedToJoinAll */
    private boolean allowedToJoinAll(final String op, final String rcsGroupId,
            final List<String> members) {
        return MlsMembership.allowedToJoinAll(mShell, mLog, op, rcsGroupId, members);
    }

    /** @see MlsGroupEstablish#establishGroup */
    public int establishGroup(final String rcsGroupId, final List<String> members,
            final byte[] carryGroupInfo, final MlsUpgradeClaim preClaimed) {
        return MlsGroupEstablish.establishGroup(mShell, mLog, rcsGroupId, members, carryGroupInfo,
                preClaimed);
    }

    /** @see MlsGroupState#hasMlsGroup */
    public boolean hasMlsGroup(final String rcsGroupId) {
        return MlsGroupState.hasMlsGroup(mShell, rcsGroupId);
    }

    /**
     * Whether any participant has no routing target. Only {@code LOOKUP_NOT_REGISTERED} counts: an
     * unknown lookup is a transient failure, not an unreachable peer.
     */
    public boolean anyParticipantUnroutable(final java.util.List<String> participants) {
        if (!ensureSession() || participants == null || participants.isEmpty()) return false;
        final MlsProviderRpc rpc = mShell.rpc("anyParticipantUnroutable");
        for (final String m : participants) {
            if (rpc.lookupPeerMlsLookupState(m)
                    == org.lineageos.rcs.provider.RcsMlsPeerCaps.LOOKUP_NOT_REGISTERED) {
                LogUtil.i(TAG, "MlsProviderTransport: " + LogMask.number(m)
                        + " has ZERO registrations — no routing "
                        + "target, which is the DUMMY destination token condition some peers check");
                return true;
            }
        }
        return false;
    }

    /** @see MlsRecordState#groupIsInitializing */
    public boolean groupIsInitializing(final String rcsGroupId) {
        return MlsRecordState.groupIsInitializing(mShell, mLog, rcsGroupId);
    }

    // Off-thread work goes through MlsOffThread, so a host test can run it inline; the default runs
    // a real thread.

    private volatile com.android.messaging.rcs.engine.mls.MlsOffThread mOffThread =
            com.android.messaging.rcs.engine.mls.MlsOffThread.REAL_THREADS;

    /**
     * Swap the executor, for tests; null restores real threads. Not a constructor parameter, since
     * this is a per-process singleton.
     */
    public void setOffThreadForTest(final com.android.messaging.rcs.engine.mls.MlsOffThread e) {
        mOffThread = (e == null)
                ? com.android.messaging.rcs.engine.mls.MlsOffThread.REAL_THREADS : e;
    }

    public boolean maintenanceOnOpen() { return mCfg.maintenanceOnOpen; }

    public boolean isMlsReady() {
        if (!ensureSession()) return false;
        try {
            return mShell.rpc("isMlsReady").isMlsReady();
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsProviderTransport: could not read MLS readiness", t);
            return false;
        }
    }

    /** @see MlsKeyPackageClaims#claimForUpgrade */
    public MlsUpgradeClaim claimForUpgrade(final java.util.List<String> participants) {
        return MlsKeyPackageClaims.claimForUpgrade(mShell, mLog, participants);
    }

    /**
     * Whether every participant may be brought into an MLS group: the joining gate
     * {@link #establishGroup} applies, asked before the upgrade claims any KeyPackage. A pure
     * predicate, so asking twice costs nothing.
     */
    public boolean upgradeMayWelcome(final String rcsGroupId,
            final java.util.List<String> participants) {
        return allowedToJoinAll("upgradeOnOpen", rcsGroupId, participants);
    }

    /** @see MlsGroupState#localEra */
    public int localEra(final String rcsGroupId, final String peerE164) {
        return MlsGroupState.localEra(mShell, mLog, rcsGroupId, peerE164);
    }

    /**
     * Add a member with an app-built commit sent through the provider.
     *
     * @return the new era, or -1 if the member could not be added
     */
    public int addMember(final String peerE164, final String newMemberE164) {
        return addMember(null, peerE164, newMemberE164);
    }

    /** @see MlsMembership#addMember */
    public int addMember(final String rcsGroupId, final String peerE164,
            final String newMemberE164) {
        return MlsMembership.addMember(mShell, mLog, rcsGroupId, peerE164, newMemberE164);
    }

    /**
     * Remove a member.
     *
     * @param memberSigPub the member's signature public key; empty or null removes the sole other
     *     member
     * @return the new era, or -1
     */
    public int removeMember(final String peerE164, final byte[] memberSigPub) {
        return removeMember(null, peerE164, memberSigPub);
    }

    /** Remove a member from a specific RCS group (null group id means the 1:1 with the peer). */
    public int removeMember(final String rcsGroupId, final String peerE164,
            final byte[] memberSigPub) {
        return removeMember(rcsGroupId, peerE164, memberSigPub, /*removedE164=*/ null);
    }

    /** @see MlsMembership#removeMember */
    public int removeMember(final String rcsGroupId, final String peerE164,
            final byte[] memberSigPub, final String removedE164) {
        return MlsMembership.removeMember(mShell, mLog, rcsGroupId, peerE164, memberSigPub,
                removedE164);
    }

    /** @see MlsMembership#groupPlane */
    public GroupPlane groupPlane(final String rcsGroupId, final String what) {
        return MlsMembership.groupPlane(mShell, mLog, rcsGroupId, what);
    }

    /** @see MlsMembership#iconChangeRouting */
    public GroupMembershipRouting iconChangeRouting(final String rcsGroupId,
            final byte[] iconBytes, final String contentType) {
        return MlsMembership.iconChangeRouting(mShell, mLog, rcsGroupId, iconBytes, contentType);
    }

    /** @see MlsMembership#renameGroupRouting */
    public GroupMembershipRouting renameGroupRouting(final String rcsGroupId,
            final String newName) {
        return MlsMembership.renameGroupRouting(mShell, mLog, rcsGroupId, newName);
    }

    /** @see MlsMembership#changeGroupMembership */
    public GroupMembershipRouting changeGroupMembership(final String rcsGroupId,
            final java.util.List<String> memberE164s, final boolean add) {
        return MlsMembership.changeGroupMembership(mShell, mLog, rcsGroupId, memberE164s, add);
    }

    /** @see MlsMembership#leaveGroup */
    public GroupMembershipRouting leaveGroup(final String rcsGroupId) {
        return MlsMembership.leaveGroup(mShell, mLog, rcsGroupId);
    }

    /** @see MlsMembership#leave */
    public boolean leave(final String peerE164) {
        return MlsMembership.leave(mShell, mLog, peerE164);
    }

    /** @see MlsMembership#leave */
    public boolean leave(final String rcsGroupId, final String peerE164) {
        return MlsMembership.leave(mShell, mLog, rcsGroupId, peerE164);
    }

    /**
     * Drop every in-memory per-conversation entry for one key, then ask every durable store in
     * {@link #perConversationStores} to forget the conversation. The host test {@code
     * forgetClearsEveryPerConversationMap} fails if a per-conversation map is declared and not
     * cleared here.
     */
    private void clearConversationState(final MlsPerConversationState.Scope scope) {
        final String key = scope.canonicalKey;
        if (key == null) return;
        mGroups.remove(key);
        // Resolved before mKeyToConvId is dropped; mConvAlias is keyed by conversation id.
        final String convId = scope.conversationId;
        if (convId != null) mConvAlias.remove(convId);
        mKeyToConvId.remove(key);
        // One removal covers every field of ConvState.
        mConv.remove(key);
        // High-water marks that gate behaviour; a rejoined conversation must not inherit them. A
        // remembered quota refusal goes too: if the server still refuses, it says so again.
        synchronized (mEraAsked) { mEraAsked.remove(key); }
        synchronized (mEraQuotaReported) { mEraQuotaReported.remove(key); }
        // This process's claim on the pending-operation slot. A stale entry would stop the reclaim
        // of a slot that is genuinely orphaned.
        synchronized (mPendingOpsClaimedThisProcess) {
            mPendingOpsClaimedThisProcess.remove(key);
        }

        // Each store reports what it dropped, so the log line is evidence rather than intent.
        final StringBuilder dropped = new StringBuilder();
        for (final MlsPerConversationState store : perConversationStores()) {
            final String name = store.getClass().getSimpleName();
            try {
                dropped.append(' ').append(name).append('=').append(
                        store.forgetConversation(scope));
            } catch (final RuntimeException e) {
                // One failing store must not leave the others behind.
                dropped.append(' ').append(name).append("=ERR");
                LogUtil.w(TAG, "MlsProviderTransport: " + name + " failed to forget " + scope, e);
            }
        }
        LogUtil.i(TAG, "MlsProviderTransport: cleared per-conversation state for "
                + MlsConversationKey.forLog(key)
                + " — 10 in-memory maps," + dropped);
    }

    /**
     * Every durable store holding state for one conversation. {@code
     * MlsPerConversationStateGuardTest} fails when a class implements {@link
     * MlsPerConversationState} and is not named here.
     */
    private java.util.List<MlsPerConversationState> perConversationStores() {
        // Named by type, so the guard can check the list against the implementing classes.
        final MlsRecordStore records = mRecords;
        final MlsPendingQueueStore queues = pendingQueue();
        final MlsReupgradeStore reupgrades = reupgradeStore();
        final MlsResendLedger.Teardown resends = new MlsResendLedger.Teardown();
        // Keyed per message, but each entry is valid only for the conversation it was sealed in.
        final MlsCiphertextCache sealed = mSealedCache;
        return java.util.Arrays.asList(records, queues, reupgrades, resends, sealed);
    }

    /**
     * Every identifier the stores key by. Built before the engine drops the group: the group id and
     * the conversation id are readable only until then.
     */
    private MlsPerConversationState.Scope scopeFor(final String key, final Group g) {
        final String[] parts = splitCanonicalKey(key);
        return new MlsPerConversationState.Scope(
                key,
                selfE164(),
                g == null ? null : g.groupId,
                conversationIdFor(key),
                parts == null ? null : parts[0],
                parts == null ? null : parts[1]);
    }

    /**
     * Forget a conversation's local MLS state: the engine group, then every in-memory and durable
     * per-conversation entry. Local only; use after leaving or when the conversation is deleted.
     * Notifies nobody, and the provider keeps its own record unless dropped separately.
     */
    public boolean forget(final String rcsGroupId, final String peerE164) {
        if (!ensureSession()) return false;
        final String key = canonicalKey(rcsGroupId, peerE164);
        if (key == null) return false;
        lock(key);
        try {
        final Group g = getGroup(key);
        if (g == null || g.groupId == null) {
            // A group the engine holds and the host does not, which a discarded create leaves
            // behind. For an RCS group the MLS group id is the RCS group id, so it is still
            // addressable.
            if (rcsGroupId == null || rcsGroupId.isEmpty()) return false;
            final byte[] orphanGid =
                    rcsGroupId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            final boolean dropped = mSelf.deleteGroup(orphanGid);
            LogUtil.i(TAG, "MlsProviderTransport: forget " + MlsConversationKey.forLog(key)
                    + " — the host holds no group, but "
                    + "the ENGINE may (a discarded create leaves exactly that). Deleting by the RCS "
                    + "group id → " + dropped + (dropped ? "" : " (nothing there either)"));
            if (dropped) LogUtil.i(TAG, MlsTrace.deletedGroupState());
            return dropped;
        }
        // Built before deleteGroup: the scope needs the group id.
        final MlsPerConversationState.Scope scope = scopeFor(key, g);
        final boolean ok = mSelf.deleteGroup(g.groupId);
        // Logged only when the engine actually dropped the group.
        if (ok) LogUtil.i(TAG, MlsTrace.deletedGroupState());
        clearConversationState(scope);
        mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(key + ".gid").remove(key + ".peer").remove(key + ".rcsgid")
                .remove(key + ".era").remove(key + ".auth").apply();
        // Only the app half is dropped here. Unless the provider also dropped its record, the next
        // send re-establishes on the same group id by an era advance.
        LogUtil.i(TAG, "MlsProviderTransport: forgot group " + MlsConversationKey.forLog(key)
                + " → engine=" + ok
                + ". This dropped the APP half. UNLESS the provider's record was ALSO dropped "
                + "(look for a 'deleteGroup(" + (g.rcsGroupId == null ? "<gid>" : g.rcsGroupId)
                + ")' line from MlsProvider.ConvStore near this one), the next send RE-ESTABLISHES "
                + "— reusing the group id via an ERA ADVANCE — rather than starting fresh, which "
                + "is an era advance. For a clean slate by hand, also fire the provider's "
                + "MLS_FORGET_CONVERSATION lever.");
        return ok;
        } finally { unlock(key); }
    }

    /** @see MlsCommitSend#commitAndSend */
    private int commitAndSend(final String rcsGroupId, final String peerE164,
            final byte[] addPeerKeyPackage, final byte[] memberSigPub, final Op op,
            final String what) {
        return MlsCommitSend.commitAndSend(mCfg, mShell, mLog, rcsGroupId, peerE164,
                addPeerKeyPackage, memberSigPub, op, what);
    }

    // The ahead fixture: withhold an outbound commit the engine has already applied. Persistence
    // and classification are in MlsOutboundHoldStore and MlsOutboundHold.

    /** Is this commit withheld? Never throws: a fixture must not break a real send. */
    private MlsOutboundHold.Verdict outboundHoldVerdict(final String conversationKey,
            final boolean isRekey) {
        try {
            return MlsOutboundHoldStore.get(mCtx).verdictFor(conversationKey, isRekey);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsProviderTransport: the AHEAD fixture could not be consulted — "
                    + "publishing normally, which is the behaviour with no fixture at all", t);
            return MlsOutboundHold.Verdict.PUBLISH;
        }
    }

    /**
     * Withhold this commit and return the post-commit era, as the accepted path does. The send-gate
     * is not opened (no acknowledgement can come) and nothing is rolled back, unless the stash
     * refuses the commit, in which case it is rolled back and nothing is published.
     */
    private int suppressCommit(final String conversationId, final Group g, final Op op,
            final String what, final String ctrlId, final String peerE164, final String rcsGroupId,
            final MlsGroupArtifacts art, final byte[] baseEpochAuth, final byte[] snapshot,
            final int preEra, final long preEpoch) {
        final MlsOutboundHoldStore store = MlsOutboundHoldStore.get(mCtx);
        // This commit's own pre-commit era and epoch; the store keeps them only with the first
        // snapshot.
        final MlsOutboundHoldStore.Held held = new MlsOutboundHoldStore.Held(ctrlId, peerE164,
                rcsGroupId, art.groupInfo, art.commit, art.tag, art.ratchetTree, baseEpochAuth,
                /*fromEpoch=*/ preEpoch);
        if (!store.suppress(held, snapshot, preEra, preEpoch)) {
            final boolean rolledBack = snapshot != null
                    && mSelf.restoreGroupSnapshot(g.groupId, snapshot);
            LogUtil.e(TAG, "MlsProviderTransport: the AHEAD fixture REFUSED to stash this " + what
                    + ", so it is rolled back" + (rolledBack ? "" : " (ROLLBACK FAILED)")
                    + " rather than withheld. Withholding a commit we cannot take back is the one "
                    + "thing this lever must never do by accident.");
            return -1;
        }
        final int era = MlsAppMessage.eraFrom(mSelf.eraEpoch(g.groupId));
        final long epoch = MlsAppMessage.epochFrom(mSelf.eraEpoch(g.groupId));
        if (era >= 0) {
            g.era = era;
            g.epochAuth = mSelf.epochAuth(g.groupId);
            g.sendsThisEpoch = 0;
            if (op != Op.ADD) g.sendsSinceLeafRotation = 0;
            putGroup(conversationId, g);
        }
        LogUtil.w(TAG, "MlsProviderTransport: " + what + " WITHHELD by the AHEAD fixture"
                + " → we are now at era=" + era + " epoch=" + epoch + " and the group is not. "
                + "No send-gate is opened: there can be no convergence ACK for a commit nobody "
                + "received. " + store.describe(era, epoch));
        return era;
    }

    /**
     * Arm the outbound hold on one conversation. The arm takes the peer allowlist over the whole
     * roster: an ahead conversation drives our ladder to a rebuild, which re-Welcomes every member,
     * and peers cannot decrypt what we send meanwhile. Disarm, release, publish, drop and status
     * stay ungated, since stopping and undoing is the recovery path.
     *
     * @return a verdict for the debug log; never null, never throws
     */
    public String armOutboundHold(final String rcsGroupId, final String peerE164,
            final String assertedMembers) {
        try {
            if (!MlsOutboundHoldStore.buildAllowsFixtures()) {
                return "REFUSED — Build.TYPE=" + Build.TYPE + " is not eng/userdebug";
            }
            if (!ensureSession()) return "REFUSED — no MLS session";
            final String key = canonicalKey(rcsGroupId, peerE164);
            if (key == null) return "REFUSED — pass --es rcsgid <gid> or --es to <e164>";
            final Group g = getGroup(key);
            if (g == null || g.groupId == null) {
                return "REFUSED — no MLS group for " + MlsConversationKey.forLog(key)
                        + ". Nothing would ever be withheld for "
                        + "this scope, and an arm that can never fire is worse than no arm.";
            }
            final MlsOutboundHoldStore store = MlsOutboundHoldStore.get(mCtx);
            if (store.heldCount() > 0) {
                return "REFUSED — " + store.heldCount() + " commit(s) are already withheld for "
                        + MlsConversationKey.forLog(store.scopeKey())
                        + ". Re-arming would rebase the measurement on a state "
                        + "that is already ahead, and the stashed snapshot restores to the OLD "
                        + "epoch. Release or drop first. " + store.describe(-1, -1L);
            }
            java.util.List<String> roster = mlsRosterMsisdns(g.groupId);
            if (roster == null || roster.isEmpty()) roster = ourRoster(key, g);
            if (assertedMembers != null && !assertedMembers.isEmpty()) {
                roster = new java.util.ArrayList<>();
                for (final String part : assertedMembers.split(",")) {
                    final String p = part.trim();
                    if (!p.isEmpty()) roster.add(p);
                }
            }
            if (roster == null || roster.isEmpty()) {
                return "REFUSED — cannot read this conversation's roster, so the peer allowlist "
                        + "gate cannot be evaluated. Re-run with --es members <e164,…> to assert "
                        + "who is in it (see this method's javadoc: an armed AHEAD hold points "
                        + "our own reconcile ladder at the REBUILD, which re-Welcomes every "
                        + "member).";
            }
            for (final String m : roster) {
                if (!MlsPeerGuard.allowDebugStateChange("mlsahead-arm", m)) {
                    return "REFUSED by MlsPeerGuard for member " + LogMask.number(m)
                            + " — see the MlsPeerGuard "
                            + "line above for which rule fired.";
                }
            }
            final byte[] ee = mSelf.eraEpoch(g.groupId);
            final int era = MlsAppMessage.eraFrom(ee);
            final long epoch = MlsAppMessage.epochFrom(ee);
            if (era < 0 || epoch < 0L) {
                return "REFUSED — cannot read this group's era/epoch, so there is no base to measure "
                        + "the lead against and no value for the release to check the restore "
                        + "against. A fixture that cannot be checked is not a fixture.";
            }
            store.arm(key, era, epoch);
            LogUtil.w(TAG, "MlsProviderTransport: OUTBOUND HOLD ARMED on "
                    + MlsConversationKey.forLog(key) + " at (era=" + era
                    + " epoch=" + epoch + "). The next rekey on this conversation will be applied "
                    + "LOCALLY and never published. This device will be AHEAD on purpose — it is a "
                    + "test fixture, not a fault, and our own reconcile ladder answers AHEAD with a "
                    + "REBUILD, so do not leave it armed.");
            return "ARMED scope=" + MlsConversationKey.forLog(key) + " at (era=" + era + " epoch="
                    + epoch + ")";
        } catch (final Throwable t) {
            return "FAILED — " + t;
        }
    }

    /** The current state of the ahead fixture. No server look-up. */
    public String outboundHoldStatus() {
        try {
            final MlsOutboundHoldStore store = MlsOutboundHoldStore.get(mCtx);
            int era = -1;
            long epoch = -1L;
            final String key = store.scopeKey();
            if (key != null && ensureSession()) {
                final Group g = getGroup(key);
                if (g != null && g.groupId != null) {
                    final byte[] ee = mSelf.eraEpoch(g.groupId);
                    era = MlsAppMessage.eraFrom(ee);
                    epoch = MlsAppMessage.epochFrom(ee);
                }
            }
            return store.describe(era, epoch);
        } catch (final Throwable t) {
            return "FAILED — " + t;
        }
    }

    /**
     * Disarm and restore the stashed snapshot, then read the era and epoch back and compare them
     * with the values captured with it ({@link MlsOutboundHold#restoredCleanly}). Reports restored
     * only when both agree; otherwise the stash is kept for another attempt.
     */
    public String releaseOutboundHold() {
        try {
            final MlsOutboundHoldStore store = MlsOutboundHoldStore.get(mCtx);
            final String key = store.scopeKey();
            store.disarm();
            if (key == null) return "nothing to release — the hold was never armed";
            if (store.heldCount() == 0) {
                store.clearStash();
                return "DISARMED " + MlsConversationKey.forLog(key)
                        + " — nothing was ever withheld, so there is nothing to "
                        + "undo. This is not a failed restore; it is an empty one.";
            }
            if (!ensureSession()) return "REFUSED — no MLS session";
            final Group g = getGroup(key);
            if (g == null || g.groupId == null) return "REFUSED — no MLS group for "
                    + MlsConversationKey.forLog(key);
            final byte[] snapshot = store.snapshot();
            if (snapshot == null) {
                return "FAILED — no stashed snapshot for " + MlsConversationKey.forLog(key)
                        + ", so the " + store.heldCount()
                        + " withheld commit(s) CANNOT be undone from here. This device is "
                        + "permanently ahead of a group that never saw them; the routes back are a "
                        + "rebuild, a re-Welcome, or an external commit. " + outboundHoldStatus();
            }
            final int expectedEra = store.snapshotEra();
            final long expectedEpoch = store.snapshotEpoch();
            final byte[] before = mSelf.eraEpoch(g.groupId);
            final int eraBefore = MlsAppMessage.eraFrom(before);
            final long epochBefore = MlsAppMessage.epochFrom(before);
            final boolean engineSaid = mSelf.restoreGroupSnapshot(g.groupId, snapshot);
            final byte[] after = mSelf.eraEpoch(g.groupId);
            final int eraAfter = MlsAppMessage.eraFrom(after);
            final long epochAfter = MlsAppMessage.epochFrom(after);
            // restoreGroupSnapshot returning true says only that the call did not fail.
            final boolean clean = MlsOutboundHold.restoredCleanly(engineSaid, expectedEra,
                    expectedEpoch, eraAfter, epochAfter);
            // Read before clearStash, which zeroes it.
            final int undone = store.heldCount();
            if (clean) {
                store.clearStash();
                final String ok = "RESTORED " + MlsConversationKey.forLog(key) + " — undid "
                        + undone
                        + " withheld commit(s): (era=" + eraBefore + " epoch=" + epochBefore
                        + ") → (era=" + eraAfter + " epoch=" + epochAfter + "), which is the "
                        + "pre-commit state the snapshot was taken at. IN_SYNC again.";
                LogUtil.w(TAG, "MlsProviderTransport: " + ok);
                return ok;
            }
            final String bad = "FAILED to restore " + MlsConversationKey.forLog(key)
                    + " — engine said " + engineSaid
                    + " and the state is (era=" + eraAfter + " epoch=" + epochAfter + ") where the "
                    + "snapshot was taken at (era=" + expectedEra + " epoch=" + expectedEpoch
                    + "). NOT reporting a successful release: this device is STILL AHEAD of a group "
                    + "that never saw " + undone + " commit(s). The stash is KEPT so "
                    + "this can be retried; if it cannot be made to land, the routes back are a "
                    + "rebuild, a re-Welcome, or an external commit.";
            LogUtil.e(TAG, "MlsProviderTransport: " + bad);
            return bad;
        } catch (final Throwable t) {
            return "FAILED — " + t;
        }
    }

    /**
     * Disarm and publish the withheld commits in order. Each commit's base authenticator names the
     * state its predecessor produced, so a refusal stops the run. The snapshot is dropped only
     * after a full publish; after a partial one it is the only way back.
     */
    public String publishOutboundHold() {
        try {
            final MlsOutboundHoldStore store = MlsOutboundHoldStore.get(mCtx);
            final String key = store.scopeKey();
            store.disarm();
            if (key == null) return "nothing to publish — the hold was never armed";
            final java.util.List<MlsOutboundHoldStore.Held> all = store.held();
            if (all.isEmpty()) {
                store.clearStash();
                return "DISARMED " + MlsConversationKey.forLog(key)
                        + " — nothing was withheld, so there is nothing to publish";
            }
            if (!ensureSession()) return "REFUSED — no MLS session";
            int sent = 0;
            String stoppedBy = null;
            for (final MlsOutboundHoldStore.Held h : all) {
                final RcsMlsControlResult r = pt("applyMlsControl").applyMlsControl(
                        mSubId, h.peerE164, h.ctrlId, h.groupInfo, h.commit, h.tag, h.ratchetTree,
                        h.baseEpochAuth, h.rcsGroupId);
                final boolean ok = r != null && r.verdict == RcsMlsControlResult.VERDICT_OK;
                LogUtil.i(TAG, "MlsProviderTransport: AHEAD release replayed the withheld commit "
                        + MlsMessageId.forLog(h.ctrlId) + " (from epoch " + h.fromEpoch + ") → "
                        + (ok ? "OK" : r));
                if (!ok) {
                    stoppedBy = String.valueOf(r);
                    break;
                }
                sent++;
            }
            final boolean full = sent == all.size();
            if (full) store.clearStash();
            final Group after = getGroup(key);
            final byte[] ee = (after == null || after.groupId == null) ? null
                    : mSelf.eraEpoch(after.groupId);
            final String verdict = "PUBLISHED " + sent + "/" + all.size() + " withheld commit(s) "
                    + "for " + MlsConversationKey.forLog(key) + " — we are at (era="
                    + MlsAppMessage.eraFrom(ee) + " epoch="
                    + MlsAppMessage.epochFrom(ee) + ")"
                    + (full
                            ? " and the server has now seen every one of them; the stash is cleared."
                            : " and the run STOPPED at " + stoppedBy
                            + ". The stash is KEPT — every "
                            + "commit after the refused one names a base the server never reached, "
                            + "so replaying them would fail identically. The undo is still available "
                            + "via release.");
            LogUtil.w(TAG, "MlsProviderTransport: " + verdict);
            return verdict;
        } catch (final Throwable t) {
            return "FAILED — " + t;
        }
    }

    /** Stop withholding; keep the divergence and the undo. */
    public String disarmOutboundHold() {
        try {
            final MlsOutboundHoldStore store = MlsOutboundHoldStore.get(mCtx);
            store.disarm();
            return "DISARMED (still holding " + store.heldCount() + " withheld commit(s) and their "
                    + "undo — release restores, publish sends them late). " + outboundHoldStatus();
        } catch (final Throwable t) {
            return "FAILED — " + t;
        }
    }

    /**
     * Discard the stash without undoing anything, leaving this device ahead with no local way back:
     * the fixture for the rebuild rung. Opt-in and logged.
     */
    public String dropOutboundHold() {
        try {
            final MlsOutboundHoldStore store = MlsOutboundHoldStore.get(mCtx);
            final int n = store.heldCount();
            store.disarm();
            store.clearStash();
            LogUtil.w(TAG, "MlsProviderTransport: DROPPED the AHEAD fixture's undo for "
                    + MlsConversationKey.forLog(store.scopeKey())
                    + " — " + n + " withheld commit(s) can no longer be taken "
                    + "back. This device stays AHEAD of a group that never saw them, and the only "
                    + "routes home are a rebuild, a re-Welcome, or an external commit. Deliberate.");
            return "DROPPED the undo for " + n + " withheld commit(s) — this divergence is now "
                    + "UNRECOVERABLE locally, on purpose";
        } catch (final Throwable t) {
            return "FAILED — " + t;
        }
    }

    private static String kpFingerprint(final byte[] kp) {
        return MlsKeyPackagePolicy.kpFingerprint(kp);
    }

    /**
     * Generate KeyPackages here, where the private halves must live to open a Welcome, and have the
     * provider upload them.
     *
     * @param count total pool size: {@code count - 1} claimable plus one last-resort package
     * @return true if the KDS accepted them
     */
    public boolean publishKeyPackages(final int count) {
        if (!ensureSession()) return false;
        // count is the total, including the last-resort package.
        final byte[] pool = mSelf.generateKeyPackages(Math.max(1, count - 1));
        if (pool == null || pool.length == 0) {
            LogUtil.w(TAG, "MlsProviderTransport: generateKeyPackages yielded nothing");
            return false;
        }
        // A dedicated last-resort package with the RFC 9420 last_resort extension (0x000A); the KDS
        // rejects a plain package in that slot.
        byte[] lastResort = mSelf.generateLastResortKeyPackage();
        if (lastResort == null || lastResort.length == 0) {
            LogUtil.w(TAG, "MlsProviderTransport: last-resort KP generation failed — the KDS may "
                    + "reject the upload without the last_resort extension");
            lastResort = new byte[0];
        }
        // Diagnostic (debug.rcs.mls_dump_kp): dump the first package, and log the published order
        // as 8-byte SHA-256 prefixes so claims can be compared with it without logging key
        // material.
        if (mCfg.dumpKeyPackagesLive()) {
            try {
                final java.util.List<byte[]> ordered = MlsArtifactBundle.splitLenPrefixed(pool);
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ordered.size(); i++) {
                    sb.append("\n    [").append(i).append("] ").append(
                            kpFingerprint(ordered.get(i)));
                }
                LogUtil.i(TAG, "KP-PUBLISH-ORDER n=" + ordered.size() + sb);
            } catch (final Throwable dt) {
                LogUtil.w(TAG, "KP-PUBLISH-ORDER logging failed", dt);
            }
        }
        if (mCfg.dumpKeyPackagesLive()) {
            try {
                final java.util.List<byte[]> kps = MlsArtifactBundle.splitLenPrefixed(pool);
                if (!kps.isEmpty() && kps.get(0) != null) {
                    final java.io.File f = new java.io.File(mCtx.getCacheDir(), "openmls-kp0.bin");
                    final java.io.FileOutputStream os = new java.io.FileOutputStream(f);
                    os.write(kps.get(0));
                    os.close();
                    LogUtil.i(TAG, "OPENMLS_KP0 dumped " + kps.get(0).length + "B → "
                            + f.getAbsolutePath());
                }
            } catch (final Throwable dt) {
                LogUtil.w(TAG, "OPENMLS_KP0 dump failed", dt);
            }
        }
        final boolean ok = mShell.rpc("uploadKeyPackages").uploadKeyPackages(pool, lastResort);
        LogUtil.i(TAG, "MlsProviderTransport: publishKeyPackages(" + count + ") pool=" + pool.length
                + "B lastResort=" + lastResort.length + "B → " + (ok ? "PUBLISHED" : "REJECTED"));
        // Only after the KDS accepted them: refs for a rejected upload could never be claimed.
        if (ok) {
            recordPublishedKeyPackages(pool, lastResort);
        }
        return ok;
    }

    // Era-advance modes (the others are MlsEraAdvance's). Only the create (rebuild from
    // KeyPackages) is legal: the era is GroupContext extension 0xF001, which a
    // GroupContextExtensions proposal may not change or remove, so an era advance is a new group
    // (RCC.16 §9.2). The engine refuses to build the two membership-preserving modes (via the
    // create RPC, via the apply RPC), which then fall through to the create; they are kept so the
    // server's refusals stay reproducible.
    private static final int ERA_MODE_PRESERVE = 1;

    /** @see MlsTransportDiagnostics#dumpGroupExtensions */
    public void dumpGroupExtensions(final String rcsGroupId, final String peerE164) {
        MlsTransportDiagnostics.dumpGroupExtensions(mShell, mLog, rcsGroupId, peerE164);
    }

    // The group-info read ledger. Each wrapper below is the only place its primitive is invoked and
    // takes the MlsFetchLedger.Caller as its first parameter; MlsGetGroupInfoLedgerGuardTest fails
    // on any other call site. See docs/mls/budgets.md.

    /** @see MlsFetchLedger#lookGroupInfo */
    private Look<MlsProviderRpc.ControlResult> lookGroupInfo(final MlsFetchLedger.Caller caller,
            final String key, final String peerE164) {
        return MlsFetchLedger.lookGroupInfo(mShell, mLog, caller, key, peerE164);
    }

    // The debug arms' reads go through here as declared exempt callers.

    /** @see MlsTransportDiagnostics#debugServerEraEpoch */
    public long[] debugServerEraEpoch(final String rcsGroupId, final String peerE164) {
        return MlsTransportDiagnostics.debugServerEraEpoch(mShell, rcsGroupId, peerE164);
    }

    /** The debug {@code ctrl} arm's 1:1 GroupInfo read; exempt, and logged as such. */
    public RcsMlsControlResult debugGroupInfo(final String peerE164) {
        return MlsProviderRpcBinding.rcs(lookGroupInfo(MlsFetchLedger.Caller.DEBUG_DUMP,
                canonicalKey(/*rcsGroupId=*/ null, peerE164), peerE164).orNull());
    }

    // The KeyPackage claim ledger, keyed on the peer, whose one pool is the resource. The two AIDL
    // spellings below are one spend point: both claim every device's package, and the singular form
    // discards the rest afterwards. See docs/mls/budgets.md.

    /** @see MlsClaimLedger#claimOne */
    private Claim<byte[]> claimOne(final MlsClaimLedger.Caller caller, final String peer) {
        return MlsClaimLedger.claimOne(mShell, mLog, caller, peer);
    }

    /** @see MlsClaimLedger#claimAll */
    private Claim<java.util.List<byte[]>> claimAll(final MlsClaimLedger.Caller caller,
            final String peer) {
        return MlsClaimLedger.claimAll(mShell, mLog, caller, peer);
    }

    // The debug arms' claims go through here as declared, charged, unrefusable callers.

    /** The debug {@code claimkp} device-count probe; unrefusable and charged. */
    public java.util.List<byte[]> debugClaimAllKeyPackages(final String peer) {
        return claimAll(MlsClaimLedger.Caller.DEBUG_CLAIM_KP, peer).orNull();
    }

    /** The debug {@code ctrl} arm's single claim; unrefusable and charged. */
    public byte[] debugClaimOneKeyPackage(final String peer) {
        return claimOne(MlsClaimLedger.Caller.DEBUG_CTRL, peer).orNull();
    }

    /**
     * The roster-wide KeyPackage count, the comparison the upgrade makes: if fewer packages come
     * back than there are remote participants, the whole upgrade is abandoned, so one member
     * without a claimable package keeps the group off MLS. This consumes a package per member, like
     * the operation it models; do not run it in a loop.
     *
     * @param csvOverride explicit members to test instead of the conversation's participants, or
     *     null
     */
    public String dumpKeyPackageCount(final String rcsGroupId, final String csvOverride) {
        if (!ensureSession()) return "no session";
        if (rcsGroupId == null || rcsGroupId.isEmpty()) return "no rcsgid";
        final java.util.List<String> participants = new java.util.ArrayList<>();
        if (csvOverride != null && !csvOverride.isEmpty()) {
            for (final String m : csvOverride.split(",")) {
                final String t = m.trim();
                if (!t.isEmpty() && !t.equals(selfE164())) participants.add(t);
            }
        } else {
            try {
                final com.android.messaging.datamodel.DatabaseWrapper db =
                        com.android.messaging.datamodel.DataModel.get().getDatabase();
                final String convId = com.android.messaging.datamodel.BugleDatabaseOperations
                        .getExistingGroupConversation(db, rcsGroupId);
                if (convId == null) return "no conversation for rcsgid " + rcsGroupId;
                for (final com.android.messaging.datamodel.data.ParticipantData p
                        : com.android.messaging.datamodel.BugleDatabaseOperations
                                .getParticipantsForConversation(db, convId)) {
                    if (p.isSelf()) continue;
                    final String d = p.getNormalizedDestination();
                    if (d != null && !d.isEmpty() && !participants.contains(d)) participants.add(d);
                }
            } catch (final Throwable t) {
                return "could not read the participants: " + t;
            }
        }
        if (participants.isEmpty()) return "no remote participants for " + rcsGroupId;

        int claimed = 0;
        final StringBuilder per = new StringBuilder();
        for (final String m : participants) {
            byte[] kp = null;
            String why = null;
            Claim<byte[]> claim = null;
            try {
                // Unrefusable and charged: the operator asks because something is wrong, and the
                // claim really does consume what it reports on.
                claim = claimOne(MlsClaimLedger.Caller.DEBUG_KP_COUNT, m);
                kp = claim.orNull();
            } catch (final Throwable t) {
                why = String.valueOf(t);
            }
            if (kp != null && kp.length > 0) {
                claimed++;
                // Fingerprint and date every claimed package: this is the one claim path that does
                // not go through keyPackageUsable. Reported, never refused; inspectKeyPackage only
                // parses.
                final MlsSession.KeyPackageInfo kpi = mSelf.inspectKeyPackage(kp);
                final long kpNow = System.currentTimeMillis() / 1000L;
                final long floorDays = mCfg.kpMinRemainingDays;
                per.append("\n  ").append(LogMask.number(m)).append(" → ").append(kp.length)
                   .append("B")
                   .append(" KP-CLAIMED fp=").append(kpFingerprint(kp))
                   // Print the role (last_resort, 0x000A): it distinguishes a KDS that withholds
                   // every package of an aged credential from one that withholds only the one-time
                   // pool and still serves the last-resort (RCC.16 A.4.2.2), and from one that does
                   // not enforce it here at all.
                   .append(kpi == null ? "" : " lastResort=" + kpi.lastResort)
                   .append(" ").append(kpi == null
                           ? "dates=UNREADABLE (the package would not parse or carries no "
                             + "key_package leaf source)"
                           : kpi.clocksText(kpNow)
                             + (MlsCredentialFloor.insideFloor(kpi.certNotAfterSecs, kpNow,
                                     floorDays)
                                 ? "   ← THIS CERTIFICATE IS INSIDE THE " + floorDays + "-DAY "
                                   + "FLOOR: a rebuild around it re-wedges the group, and a "
                                   + "membership Commit naming this member is refused"
                                 : ""));
            } else if (claim != null && claim.notAttempted()) {
                // Not asked is not "none": say that the claim never left the device and that its
                // charge was refunded.
                per.append("\n  ").append(LogMask.number(m)).append(" → NOT ASKED")
                   .append("   ← THE CLAIM WAS NEVER SENT — the RCS provider is not bound, or it "
                           + "reported that it sent nothing. NOTHING was "
                           + "consumed from this member's pool and the ledger charge was refunded, "
                           + "so this row says NOTHING about what they have. Fix OUR side and run "
                           + "it again; the ledger line above carries the provider's own reason.");
            } else {
                // Name the member, since one member blocks the whole upgrade, and let the engine's
                // attribution say whose fault the empty answer is (blockedByEmptyClaim) rather than
                // guessing here.
                per.append("\n  ").append(LogMask.number(m)).append(" → NONE")
                   .append(why == null ? "" : " (" + why + ")")
                   .append("   ← ").append(MlsClaimLedger.blockedByEmptyClaim(m,
                           claim == null ? null : claim.attribution(),
                           "NOTHING CLAIMED FOR THIS MEMBER, SO THE WHOLE UPGRADE IS BLOCKED"
                                   + " while they are in it."));
            }
        }
        final int m = participants.size();
        final boolean wouldProceed = claimed >= m;
        final StringBuilder out = new StringBuilder();
        out.append("keyPackages=").append(claimed).append(" participants=").append(m)
           .append(" → UPGRADE WOULD ").append(wouldProceed ? "PROCEED" : "SKIP").append(per);
        if (!wouldProceed) {
            // Kept verbatim so a trace diff can match the line as a literal.
            out.append("\n  Skip conversation update because keyPackage count ").append(claimed)
               .append(" is less than remote participants count ").append(m);
        }
        return out.toString();
    }

    /** @see MlsServerBundle#ourRoster */
    private java.util.List<String> ourRoster(final String key, final Group g) {
        return MlsServerBundle.ourRoster(mShell, mLog, key, g);
    }

    /** @see MlsFloorRebuild#debugFloorRebuild */
    public int debugFloorRebuild(final String rcsGroupId, final String peerE164) {
        return MlsFloorRebuild.debugFloorRebuild(mCfg, mShell, mLog, rcsGroupId, peerE164);
    }

    /** @see MlsMaintenancePass#runMaintenance */
    public MlsMaintenancePolicy.Refresh runMaintenance(final String rcsGroupId,
            final String peerE164, final String cause) {
        return MlsMaintenancePass.runMaintenance(mCfg, mShell, mLog, rcsGroupId, peerE164, cause);
    }

    /**
     * One group sweep at a time, process-wide. A trigger that arrives while a pass runs is dropped,
     * not queued: the cursor is durable, so the next trigger resumes from the same place, and
     * queueing would turn a burst of triggers into a sustained walk.
     */
    private final java.util.concurrent.atomic.AtomicBoolean mSweepInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** @see MlsMaintenancePass#armGroupSweep */
    private void armGroupSweep(final String cause) {
        MlsMaintenancePass.armGroupSweep(mCfg, mShell, mLog, cause);
    }

    /**
     * Era advance: re-create the group at {@code era+1} under the same group id, so every member
     * joins by Welcome. It sidesteps an epoch gap that commits cannot bridge, at the cost of an era
     * and a re-join for every member, so it is a last resort. Only a member that holds state can do
     * it; one that holds none must ask to be re-Welcomed.
     *
     * @return the new era, or -1
     */
    public int eraAdvance(final String rcsGroupId, final String peerE164) {
        return eraAdvance(rcsGroupId, peerE164, /*carryGroupInfo=*/ null);
    }

    // The single in-flight operation per conversation is one nullable field of the persisted
    // record, so it survives a restart and covers era advance, self-heal, end_mls and metadata
    // commits alike.

    // Downgrade, end_mls, revive and Phoenix. See docs/mls/downgrade.md.

    /** @see MlsRecordState#hasEndMlsStatus */
    private boolean hasEndMlsStatus(final String key) {
        return MlsRecordState.hasEndMlsStatus(mShell, mLog, key);
    }

    /** @see MlsGroupState#haveWeLeft */
    public boolean haveWeLeft(final String rcsGroupId, final String peerE164) {
        return MlsGroupState.haveWeLeft(mShell, mLog, rcsGroupId, peerE164);
    }

    /**
     * The app conversation id for a canonical key, or null. A group is found by its {@code
     * rcs_group_id}, a 1:1 by the peer's existing thread; the in-memory alias is only a cache,
     * empty after a restart and never filled on the inbound path. Resolves, never creates: a
     * downgrade must not make a conversation row for a peer the user never had a thread with.
     */
    private String conversationIdFor(final String key) {
        if (key == null) return null;
        final String cached = mKeyToConvId.get(key);
        if (cached != null) return cached;
        final String[] parts = splitCanonicalKey(key);
        if (parts == null) return null;
        try {
            final com.android.messaging.datamodel.DatabaseWrapper db =
                    com.android.messaging.datamodel.DataModel.get().getDatabase();
            final String id;
            if (parts[0] != null) {
                id = com.android.messaging.datamodel.BugleDatabaseOperations
                        .getExistingGroupConversation(db, parts[0]);
            } else if (parts[1] != null && !parts[1].isEmpty()) {
                final long threadId = com.android.messaging.sms.MmsSmsUtils.Threads
                        .getOrCreateThreadId(mCtx, parts[1]);
                id = threadId < 0 ? null
                        : com.android.messaging.datamodel.BugleDatabaseOperations
                                .getExistingConversation(db, threadId, /*senderBlocked=*/ false);
            } else {
                return null;
            }
            if (id != null) mKeyToConvId.put(key, id);
            return id;
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsProviderTransport: could not resolve a conversation id for "
                    + MlsConversationKey.forLog(key),
                    t);
            return null;
        }
    }

    private E2eeSchemeGate mSchemeGate;
    private MlsReupgradeStore mReupgradeStore;

    private synchronized E2eeSchemeGate schemeGate() {
        if (mSchemeGate == null) {
            // Only the bits store is used here. The other two ports are null so a downgrade cannot
            // trigger an eligibility recompute, which could set the MLS bit again while it is being
            // cleared.
            mSchemeGate = new E2eeSchemeGate(null, new ConversationBitsStore());
        }
        return mSchemeGate;
    }

    private synchronized MlsReupgradeStore reupgradeStore() {
        if (mReupgradeStore == null) mReupgradeStore = new MlsReupgradeStore(mCfg);
        return mReupgradeStore;
    }

    private MlsRebuildLimiter mRebuildLimiter;

    private synchronized MlsRebuildLimiter rebuildLimiter() {
        if (mRebuildLimiter == null) mRebuildLimiter = new MlsRebuildLimiter(mCtx);
        return mRebuildLimiter;
    }

    /** @see MlsConversationRebuild#debugResetRebuildAllowance */
    public void debugResetRebuildAllowance(final String rcsGroupId, final String peerE164) {
        MlsConversationRebuild.debugResetRebuildAllowance(mShell, mLog, rcsGroupId, peerE164);
    }

    /**
     * Keys whose pending-operation slot this process claimed. Empty at process start, which is what
     * lets {@code MlsPendingOperation.claimPendingOp} reclaim a slot a dead process left behind.
     */
    private final java.util.Set<String> mPendingOpsClaimedThisProcess =
            new java.util.HashSet<String>();

    public int eraAdvance(final String rcsGroupId, final String peerE164,
            final byte[] carryGroupInfo) {
        return eraAdvance(rcsGroupId, peerE164, carryGroupInfo, MlsAdvanceEraKind.NORMAL,
                /*requireRebuildableRoster=*/ false);
    }

    /** @see MlsEraAdvance#eraAdvance */
    public int eraAdvance(final String rcsGroupId, final String peerE164,
            final byte[] carryGroupInfo, final MlsAdvanceEraKind kind,
            final boolean requireRebuildableRoster) {
        return MlsEraAdvance.eraAdvance(mCfg, mShell, mLog, rcsGroupId, peerE164, carryGroupInfo,
                kind, requireRebuildableRoster);
    }

    /** @see MlsIdentityRefresh#maybeRefreshIdentity */
    private void maybeRefreshIdentity() {
        MlsIdentityRefresh.maybeRefreshIdentity(mCfg, mShell, mLog);
    }

    /**
     * Record that the MLS plane is in use for this conversation, so the encryption indicator shows
     * it. Runs on every sealed send, so it writes only on a change. An unreadable bit is left alone
     * and retried on the next send. Never throws.
     */
    private void noteMlsPlaneInUse(final String conversationKey) {
        try {
            final String convId = conversationIdFor(conversationKey);
            if (convId == null || convId.isEmpty()) return;
            final ConversationBitsStore store = new ConversationBitsStore();
            final EncryptionProtocolBits before = store.loadOrNull(convId);
            if (before == null || before.mlsBit()) return;
            store.store(convId, before.accumulate(/*scytale=*/ false, /*mls=*/ true));
            LogUtil.i(TAG, "MlsProviderTransport: latched the MLS encryption bit for conversation "
                    + MlsConversationKey.forLog(convId) + " ("
                    + MlsConversationKey.forLog(conversationKey)
                    + ") on a sealed SEND — the app's encryption "
                    + "indicator would otherwise stay off until this peer replied");
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsProviderTransport: could not latch the MLS encryption bit", t);
        }
    }

    /** @see MlsTransportDiagnostics#seedUsageCounter */
    public boolean seedUsageCounter(final String rcsGroupId, final String peerE164) {
        return MlsTransportDiagnostics.seedUsageCounter(mShell, mLog, rcsGroupId, peerE164);
    }

    // RCC.16 A.4.1.2 and A.4.2.2: the consume-side KeyPackage gate. The lifetime floor is
    // documented at MlsConfig#DEF_KP_MIN_REMAINING_DAYS, the identity check at
    // MlsConfig#sanIdentityCheck.

    /** @see MlsKeyPackagePolicy#keyPackageUsable */
    private boolean keyPackageUsable(final byte[] kp, final String who) {
        if (kp == null || kp.length == 0) return false;
        return MlsKeyPackagePolicy.keyPackageUsable(kp, mSelf.inspectKeyPackage(kp), who, mCfg,
                RcsE2eeScheme.MLS_CIPHERSUITE_P256_AES128, System.currentTimeMillis() / 1000L,
                mLog);
    }

    /** @see MlsKeyPackagePool#vetSelfKeyPackage */
    public String vetSelfKeyPackage() {
        return MlsKeyPackagePool.vetSelfKeyPackage(mShell);
    }

    /** @see MlsIdentityRefresh#onIdentityChanged */
    public void onIdentityChanged(final String reason) {
        MlsIdentityRefresh.onIdentityChanged(mCfg, mShell, mLog, reason);
    }

    /** @see MlsKeyPackagePool#maybePublishKeyPackages */
    private void maybePublishKeyPackages() {
        MlsKeyPackagePool.maybePublishKeyPackages(mCfg, mShell, mLog);
    }

    /** The engine as its concrete type, for calls not on the neutral interface, or null. */
    private com.android.messaging.rcs.engine.mls.OpenMlsSession openMls() {
        final MlsSession s = mSelf;
        return (s instanceof com.android.messaging.rcs.engine.mls.OpenMlsSession)
                ? (com.android.messaging.rcs.engine.mls.OpenMlsSession) s : null;
    }

    /** @see MlsKeyPackagePool#recordPublishedKeyPackages */
    private void recordPublishedKeyPackages(final byte[] pool, final byte[] lastResort) {
        MlsKeyPackagePool.recordPublishedKeyPackages(mShell, mLog, pool, lastResort);
    }

    /**
     * May this group message, text or media, go out in the clear? Composes the app's latch (read
     * first and without the engine, so an engine failure cannot turn a padlocked conversation into
     * a plaintext send) with the engine's seal capability through {@link MlsSendRouting}. Media has
     * no seal path, so its caller refuses any verdict but plaintext.
     *
     * @param conversationId the app's conversation id for the latch read; passed in because a
     *     conversation not yet mapped to an MLS record is exactly the one whose bit matters
     */
    public static MlsSendRouting.Verdict groupSendVerdict(final Context ctx,
            final int subId, final String rcsGroupId, final String conversationId) {
        final MlsSendRouting.MlsLatch latch = readMlsLatch(conversationId);
        MlsSendRouting.SealCapability engine;
        try {
            engine = get(ctx, subId).sealCapability(rcsGroupId, /*peerE164=*/ null);
        } catch (final Throwable th) {
            // "Could not ask" is not "no": ENGINE_UNAVAILABLE composes with the latch, so a
            // padlocked conversation still refuses.
            LogUtil.w(TAG, "MlsProviderTransport: could not determine the MLS seal capability for "
                    + rcsGroupId + " — treating it as UNAVAILABLE, not as plaintext", th);
            engine = MlsSendRouting.SealCapability.ENGINE_UNAVAILABLE;
        }
        final MlsSendRouting.Verdict verdict = MlsSendRouting.decide(engine, latch);
        if (verdict == MlsSendRouting.Verdict.REFUSE) {
            LogUtil.e(TAG, "MlsProviderTransport: REFUSING a group send on " + rcsGroupId
                    + " — the app has latched the MLS bit for conversation "
                    + MlsConversationKey.forLog(conversationId)
                    + " (so it is drawing a padlock on this thread) and the engine says " + engine
                    + ", so we cannot seal it. Sending it in the clear under that padlock is the "
                    + "defect this gate exists to stop; the message is left unsent and visible.");
        } else {
            LogUtil.i(TAG, "MlsProviderTransport: group send on " + rcsGroupId + " -> "
                    + verdict + " (engine=" + engine + " latch=" + latch + ")");
        }
        return verdict;
    }

    /**
     * May this 1:1 send go out in the clear? The same table and inputs as {@link
     * #groupSendVerdict}. The engine input is the weaker question (do we hold 1:1 state now), which
     * is right for deciding a refusal and wrong for deciding a seal; {@code
     * E2eeSendGate.resolveForSend} answers that. A first send to a new MLS-capable peer holds no
     * state and has latched nothing, so it goes plaintext; whether that is acceptable for a given
     * payload is the caller's call.
     *
     * @param peerE164 the canonical E.164 the send is addressed to; another form keys nothing and
     *     would fail open
     * @param conversationId the app's conversation id, for the latch read
     */
    public static MlsSendRouting.Verdict oneToOneSendVerdict(final Context ctx,
            final int subId, final String peerE164, final String conversationId) {
        final MlsSendRouting.MlsLatch latch = readMlsLatch(conversationId);
        MlsSendRouting.SealCapability engine;
        try {
            engine = get(ctx, subId).sealCapability(/*rcsGroupId=*/ null, peerE164);
        } catch (final Throwable th) {
            // "Could not ask" is not "no", as in groupSendVerdict.
            LogUtil.w(TAG, "MlsProviderTransport: could not determine the 1:1 MLS seal capability "
                    + "for " + LogMask.number(peerE164)
                    + " — treating it as UNAVAILABLE, not as plaintext", th);
            engine = MlsSendRouting.SealCapability.ENGINE_UNAVAILABLE;
        }
        final MlsSendRouting.Verdict verdict = MlsSendRouting.decide(engine, latch);
        if (verdict == MlsSendRouting.Verdict.REFUSE) {
            LogUtil.e(TAG, "MlsProviderTransport: REFUSING a 1:1 send to "
                    + LogMask.number(peerE164)
                    + " — the app has latched the MLS bit for conversation "
                    + MlsConversationKey.forLog(conversationId)
                    + " (so it is drawing a padlock on this thread) and the engine says " + engine
                    + ", so we cannot seal it.");
        } else {
            LogUtil.i(TAG, "MlsProviderTransport: 1:1 send to " + LogMask.number(peerE164) + " -> "
                    + verdict
                    + " (engine=" + engine + " latch=" + latch + ")");
        }
        return verdict;
    }

    /**
     * Whether the app shows this conversation as MLS-encrypted: the MLS bit only, never
     * {@code isE2eeEncrypted()}, which is also true for a provider-encrypted conversation. A failed
     * read is {@link MlsSendRouting.MlsLatch#UNREADABLE}.
     */
    private static MlsSendRouting.MlsLatch readMlsLatch(final String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return MlsSendRouting.MlsLatch.UNREADABLE;
        }
        try {
            final EncryptionProtocolBits bits =
                    new ConversationBitsStore().loadOrNull(conversationId);
            return MlsSendRouting.latchOf(bits == null ? null : Boolean.valueOf(bits.mlsBit()));
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsProviderTransport: could not read the encryption bits for "
                    + MlsConversationKey.forLog(conversationId), t);
            return MlsSendRouting.MlsLatch.UNREADABLE;
        }
    }

    /**
     * What the engine can do for this conversation now. It asks the same questions {@link
     * #sendFramedToGroup} asks against the same key, so {@code SEALABLE} means the group seal path
     * will not refuse. For a 1:1 it is sound in one direction only: {@link #sendAppOwned}
     * establishes a session on demand, so anything but {@code SEALABLE} means "no state yet", not
     * "will not seal".
     */
    private MlsSendRouting.SealCapability sealCapability(final String rcsGroupId,
            final String peerE164) {
        final String key = canonicalKey(rcsGroupId, peerE164);
        if (key == null) {
            return MlsSendRouting.SealCapability.NO_MLS_STATE;
        }
        if (!ensureSession()) {
            // No session: an absent identity and an unavailable engine are different answers.
            return MlsIdentityStore.loadIdentity(mCtx) == null
                    ? MlsSendRouting.SealCapability.NO_MLS_IDENTITY
                    : MlsSendRouting.SealCapability.ENGINE_UNAVAILABLE;
        }
        final Group g = getGroup(key);
        if (g == null || g.groupId == null) {
            return MlsSendRouting.SealCapability.NO_MLS_STATE;
        }
        if (hasEndMlsStatus(key)) {
            return MlsSendRouting.SealCapability.DOWNGRADED;
        }
        return MlsSendRouting.SealCapability.SEALABLE;
    }

    /**
     * Seal a message for an RCS group and send it, so the server fans it out. The AAD binds the
     * message id and era; the generation comes from the engine.
     *
     * @return true if the transport accepted it
     */
    public boolean sendToGroup(final String rcsGroupId, final String text) {
        return sendToGroup(rcsGroupId, text, /*rcsMessageId=*/ null);
    }

    /**
     * As above, with a caller-supplied message id. The id makes a group send cacheable; a
     * synthesised id is unique per call, so nothing could ask to send it twice.
     */
    public boolean sendToGroup(final String rcsGroupId, final String text,
            final String rcsMessageId) {
        return sendFramedToGroup(rcsGroupId, RccMlsBody.frameText(text), "mls-grp", rcsMessageId);
    }

    /** @see MlsPayloadCorruptor#sendMalformedPayloadToGroup */
    public boolean sendMalformedPayloadToGroup(final String rcsGroupId, final String text,
            final MlsPayloadCorruptor.Mode mode) {
        return MlsPayloadCorruptor.sendMalformedPayloadToGroup(mShell, mLog, rcsGroupId, text,
                mode);
    }

    /** @see MlsGroupMetadata#sendGroupMetadataKeys */
    public boolean sendGroupMetadataKeys(final String rcsGroupId, final byte[] keysFileInfo,
            final byte[] continuityToken) {
        return MlsGroupMetadata.sendGroupMetadataKeys(mShell, mLog, rcsGroupId, keysFileInfo,
                continuityToken);
    }

    /** @see MlsGroupSend#sendFramedToGroup */
    private boolean sendFramedToGroup(final String rcsGroupId, final byte[] framedBody,
            final String idPrefix, final String rcsMessageId) {
        return MlsGroupSend.sendFramedToGroup(mShell, mLog, rcsGroupId, framedBody, idPrefix,
                rcsMessageId);
    }

    /** @see MlsDowngradeFlow#endMls */
    public int endMls(final String rcsGroupId, final String peerE164, final boolean resume) {
        return MlsDowngradeFlow.endMls(mShell, mLog, rcsGroupId, peerE164, resume);
    }

    /** @see MlsDowngradeFlow#endMls */
    public int endMls(final String rcsGroupId, final String peerE164, final boolean resume,
            final MlsDowngradeReason reason) {
        return MlsDowngradeFlow.endMls(mShell, mLog, rcsGroupId, peerE164, resume, reason);
    }

    /** @see MlsDowngradeFlow#initiatePhoenixMode */
    public int initiatePhoenixMode(final String rcsGroupId, final String peerE164,
            final String why) {
        return MlsDowngradeFlow.initiatePhoenixMode(mShell, mLog, rcsGroupId, peerE164, why);
    }

    /** @see MlsServerMessage#removeOnServerNotify */
    public int removeOnServerNotify(final String rcsGroupId, final String peerE164,
            final byte[] memberSigPub) {
        return MlsServerMessage.removeOnServerNotify(mShell, mLog, rcsGroupId, peerE164,
                memberSigPub);
    }

    /** @see MlsGroupMetadata#publishIconSubject */
    public int publishIconSubject(final String rcsGroupId, final String peerE164,
            final byte[] iconKey, final byte[] subjectKey) {
        return MlsGroupMetadata.publishIconSubject(mShell, mLog, rcsGroupId, peerE164, iconKey,
                subjectKey);
    }

    /** @see MlsGroupMetadata#changeGroupIcon */
    public byte[] changeGroupIcon(final String rcsGroupId, final String peerE164,
            final byte[] iconBytes, final String contentType) {
        return MlsGroupMetadata.changeGroupIcon(mShell, mLog, rcsGroupId, peerE164, iconBytes,
                contentType);
    }

    /** @see MlsGroupMetadata#changeGroupSubject */
    public byte[] changeGroupSubject(final String rcsGroupId, final String peerE164,
            final byte[] subjectUtf8, final String contentType) {
        return MlsGroupMetadata.changeGroupSubject(mShell, mLog, rcsGroupId, peerE164, subjectUtf8,
                contentType);
    }

    /** @see MlsGroupMetadata#onGroupMetadataKeys */
    public boolean onGroupMetadataKeys(final String rcsGroupId, final String fromE164,
            final byte[] proto) {
        return MlsGroupMetadata.onGroupMetadataKeys(mShell, mLog, rcsGroupId, fromE164, proto);
    }

    /** @see MlsTransportDiagnostics#debugInjectContinuityToken */
    public String debugInjectContinuityToken(final String rcsGroupId, final String peerE164,
            final byte[] token) {
        return MlsTransportDiagnostics.debugInjectContinuityToken(mShell, mLog, rcsGroupId,
                peerE164, token);
    }

    /** @see MlsGroupMetadata#onFileInfo */
    public boolean onFileInfo(final String rcsGroupId, final String fromE164,
            final byte[] fileInfoProto) {
        return MlsGroupMetadata.onFileInfo(mShell, mLog, rcsGroupId, fromE164, fileInfoProto);
    }

    /** Our own MSISDN, cached at session bring-up, to exclude ourselves from a fetched roster. */
    private String mSelfE164;

    /** Our own MSISDN, or empty if the session is not up yet. */
    private String selfE164() { return mSelfE164 == null ? "" : mSelfE164; }

    // Subjects whose key has not arrived yet live on ConvState.pendingSubject.

    /** @see MlsGroupMetadata#onEncryptedSubject */
    public byte[] onEncryptedSubject(final String rcsGroupId, final String fromE164,
            final byte[] ciphertext) {
        return MlsGroupMetadata.onEncryptedSubject(mShell, mLog, rcsGroupId, fromE164, ciphertext);
    }

    /** @see MlsGroupMetadata#onEncryptedIcon */
    public byte[] onEncryptedIcon(final String rcsGroupId, final String fromE164,
            final byte[] ciphertext) {
        return MlsGroupMetadata.onEncryptedIcon(mShell, mLog, rcsGroupId, fromE164, ciphertext);
    }

    /** @see MlsGroupMetadata#openStoredIconSubject */
    public byte[] openStoredIconSubject(final String rcsGroupId, final String peerE164,
            final boolean icon, final byte[] encryptedContent) {
        return MlsGroupMetadata.openStoredIconSubject(mShell, mLog, rcsGroupId, peerE164, icon,
                encryptedContent);
    }

    /** @see MlsImdnSigner#signImdn */
    public String signImdn(final String rcsGroupId, final String peerE164,
            final String receiptMessageId, final String messageId,
            final boolean displayed, final int status, final int failureReason) {
        return MlsImdnSigner.signImdn(mShell, mLog, rcsGroupId, peerE164, receiptMessageId,
                messageId, displayed, status, failureReason);
    }

    /** @see MlsImdnSigner#verifyDecryptedImdn */
    public ImdnCheck verifyDecryptedImdn(final String rcsGroupId, final String peerE164,
            final byte[] decryptedCpim) {
        return MlsImdnSigner.verifyDecryptedImdn(mShell, mLog, rcsGroupId, peerE164, decryptedCpim);
    }

    /** @see MlsImdnSigner#verifyImdn */
    public ImdnCheck verifyImdn(final String rcsGroupId, final String peerE164,
            final String headerB64, final String messageId, final boolean displayed,
            final int status, final int failureReason) {
        return MlsImdnSigner.verifyImdn(mShell, mLog, rcsGroupId, peerE164, headerB64, messageId,
                displayed, status, failureReason);
    }

    /** @see MlsGroupState#isEndMls */
    public boolean isEndMls(final String rcsGroupId, final String peerE164) {
        return MlsGroupState.isEndMls(mShell, mLog, rcsGroupId, peerE164);
    }

    // TODO: remove once this proposal type is honoured or no longer advertised.

    /** @see MlsStateChangeGate#dropPendingProposals */
    public String dropPendingProposals(final String rcsGroupId, final String peerE164) {
        return MlsStateChangeGate.dropPendingProposals(mShell, mLog, rcsGroupId, peerE164);
    }

    /** @see MlsServerBundle#mlsRosterMsisdns */
    private java.util.List<String> mlsRosterMsisdns(final byte[] groupId) {
        return MlsServerBundle.mlsRosterMsisdns(mShell, mLog, groupId);
    }

    /** Key to the highest era we have asked the server to move to. */
    private final java.util.Map<String, Integer> mEraAsked = new java.util.HashMap<>();
    /** Keys for which the server reported era-advancement quota reached (reason 14). */
    private final java.util.Set<String> mEraQuotaReported = new java.util.HashSet<>();

    private MlsExternalCommitBudget mXcBudget;

    private synchronized MlsExternalCommitBudget xcBudget() {
        if (mXcBudget == null) mXcBudget = new MlsExternalCommitBudget(mCtx);
        return mXcBudget;
    }

    /** @see MlsExternalCommitResync#resyncViaExternalCommit */
    public int resyncViaExternalCommit(final String rcsGroupId, final String peerE164,
            final String why) {
        return MlsExternalCommitResync.resyncViaExternalCommit(mShell, mLog, rcsGroupId, peerE164,
                why);
    }

    /** @see MlsExternalCommitResync#resyncViaExternalCommit */
    public int resyncViaExternalCommit(final String rcsGroupId, final String peerE164,
            final String why, final boolean forcePastProfile) {
        return MlsExternalCommitResync.resyncViaExternalCommit(mShell, mLog, rcsGroupId, peerE164,
                why, forcePastProfile);
    }

    /** @see MlsExternalCommitResync#resyncViaExternalCommit */
    public int resyncViaExternalCommit(final String rcsGroupId, final String peerE164,
            final String why, final boolean forcePastProfile, final long removeLeafIndex) {
        return MlsExternalCommitResync.resyncViaExternalCommit(mShell, mLog, rcsGroupId, peerE164,
                why, forcePastProfile, removeLeafIndex);
    }

    /** @see MlsExternalCommitResync#resyncViaExternalCommit */
    public int resyncViaExternalCommit(final String rcsGroupId, final String peerE164,
            final String why, final boolean forcePastProfile, final long removeLeafIndex,
            final boolean dryRun) {
        return MlsExternalCommitResync.resyncViaExternalCommit(mShell, mLog, rcsGroupId, peerE164,
                why, forcePastProfile, removeLeafIndex, dryRun);
    }

    /** @see MlsTransportDiagnostics#dumpMemberValidity */
    public String dumpMemberValidity(final String rcsGroupId, final String peerE164) {
        return MlsTransportDiagnostics.dumpMemberValidity(mCfg, mShell, mLog, rcsGroupId, peerE164);
    }

    /** @see MlsTransportDiagnostics#dumpServerValidity */
    public String dumpServerValidity(final String rcsGroupId, final String peerE164) {
        return MlsTransportDiagnostics.dumpServerValidity(mCfg, mShell, rcsGroupId, peerE164);
    }


    /** @see MlsConversationRebuild#resetRebuildRateBound */
    public void resetRebuildRateBound(final String key) {
        MlsConversationRebuild.resetRebuildRateBound(mShell, key);
    }

    /** @see MlsExternalCommitResync#resetExternalCommitBudget */
    public void resetExternalCommitBudget(final String key) {
        MlsExternalCommitResync.resetExternalCommitBudget(mShell, key);
    }

    /** @see MlsTransportDiagnostics#debugProbeDurableCooldowns */
    public String debugProbeDurableCooldowns(final String rcsGroupId, final String peerE164) {
        return MlsTransportDiagnostics.debugProbeDurableCooldowns(mShell, rcsGroupId, peerE164);
    }

    /** @see MlsConversationRebuild#debugResetDurableCooldowns */
    public String debugResetDurableCooldowns(final String rcsGroupId, final String peerE164) {
        return MlsConversationRebuild.debugResetDurableCooldowns(mShell, mLog, rcsGroupId,
                peerE164);
    }

    /** @see MlsRecoveryPolicy#resetReestablishCooldown */
    public void resetReestablishCooldown(final String rcsGroupId, final String peerE164) {
        MlsRecoveryPolicy.resetReestablishCooldown(mShell, mLog, rcsGroupId, peerE164);
    }

    /** @see MlsRecoveryPolicy#resetPeerHealth */
    public void resetPeerHealth(final String rcsGroupId, final String peerE164) {
        MlsRecoveryPolicy.resetPeerHealth(mShell, mLog, rcsGroupId, peerE164);
    }

    /** @see MlsRecoveryPolicy#resetSelfHealBudget */
    public void resetSelfHealBudget(final String key) {
        MlsRecoveryPolicy.resetSelfHealBudget(mShell, mLog, key);
    }

    /** @see MlsStallAlert#refreshStallNotification */
    public void refreshStallNotification(final String rcsGroupId, final String peerE164,
            final String key) {
        MlsStallAlert.refreshStallNotification(mShell, mLog, rcsGroupId, peerE164, key);
    }

    /** @see MlsDriveLoop#driveReconcile */
    public MlsDriveLoop.Result driveReconcile(final String rcsGroupId, final String peerE164) {
        return MlsDriveLoop.driveReconcile(mShell, mLog, rcsGroupId, peerE164);
    }

    /**
     * The group's participants from the app database, minus us: the roster fallback for a rebuild.
     * Empty when the conversation is unknown or the read fails.
     */
    private java.util.List<String> bugleRoster(final String rcsGroupId, final String self) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        try {
            final com.android.messaging.datamodel.DatabaseWrapper db =
                    com.android.messaging.datamodel.DataModel.get().getDatabase();
            final String convId = com.android.messaging.datamodel.BugleDatabaseOperations
                    .getExistingGroupConversation(db, rcsGroupId);
            if (convId != null) {
                for (final com.android.messaging.datamodel.data.ParticipantData p
                        : com.android.messaging.datamodel.BugleDatabaseOperations
                                .getParticipantsForConversation(db, convId)) {
                    if (p.isSelf()) continue;
                    final String d = p.getNormalizedDestination();
                    if (d != null && !d.isEmpty() && !d.equals(self) && !out.contains(d)) {
                        out.add(d);
                    }
                }
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsProviderTransport: could not read RCS participants for "
                    + rcsGroupId, t);
        }
        return out;
    }

    /** @see MlsAheadChainCheck#detectHealth */
    public Health detectHealth(final MlsFetchLedger.Caller caller, final String rcsGroupId,
            final String peerE164) {
        return MlsAheadChainCheck.detectHealth(mShell, mLog, caller, rcsGroupId, peerE164);
    }

    /**
     * Act on a divergence. Each branch does only what it can: on this transport an external-commit
     * resync is refused while we are a member ({@link MlsExternalCommitResync#resyncApplies}).
     *
     * @return true if the conversation is healthy or was repaired
     */
    public boolean reconcile(final String rcsGroupId, final String peerE164) {
        final MlsResultStatus s = reconcileAction(rcsGroupId, peerE164).status;
        return s == MlsResultStatus.NO_OP || s == MlsResultStatus.SUCCESS;
    }

    /** @see MlsConversationRebuild#reconcileAction */
    public MlsHostAction reconcileAction(final String rcsGroupId, final String peerE164) {
        return MlsConversationRebuild.reconcileAction(mShell, mLog, rcsGroupId, peerE164);
    }

    private static volatile MlsProviderTransport sInstance;

    /**
     * One instance per process: the engine session and its sender ratchet must be reused, not
     * reopened per send, which would re-derive the ratchet from generation 0.
     */
    public static MlsProviderTransport get(final Context ctx, final int subId) {
        MlsProviderTransport t = sInstance;
        if (t == null) {
            synchronized (MlsProviderTransport.class) {
                if (sInstance == null) sInstance = new MlsProviderTransport(ctx, subId);
                t = sInstance;
            }
        }
        return t;
    }

    /** The live instance, or null if MLS has never been brought up in this process. */
    public static MlsProviderTransport peek() { return sInstance; }

    /** @see MlsImdnSigner#imdnStampsFor */
    public ImdnStamps imdnStampsFor(final String peerE164, final String originalMessageId,
            final boolean displayed) {
        return MlsImdnSigner.imdnStampsFor(mShell, mLog, peerE164, originalMessageId, displayed);
    }

    /** @see MlsImdnSigner#imdnStampsFor */
    public ImdnStamps imdnStampsFor(final String peerE164, final String rcsGroupId,
            final String originalMessageId, final boolean displayed) {
        return MlsImdnSigner.imdnStampsFor(mShell, mLog, peerE164, rcsGroupId, originalMessageId,
                displayed);
    }

    /** Outcome of the production app-owned send. */
    public enum SendOutcome {
        /** This path does not apply (no adopted identity or engine); the caller may use another. */
        NOT_APPLICABLE,
        /** Sealed and sent by this app. */
        SENT,
        /** We took ownership, consumed a generation, and the send failed. */
        FAILED
    }

    /**
     * The production entry point: seal and send one text message. {@link SendOutcome#FAILED} must
     * not be retried through another MLS owner, which would put a second ciphertext at another
     * generation on one ratchet; nor sent as plaintext, since the user was told the conversation is
     * encrypted. The message is left unsent and retryable.
     */
    public static SendOutcome sendAppOwned(final Context ctx, final int subId,
            final String conversationId, final String peerE164, final byte[] framedBody) {
        return sendAppOwned(ctx, subId, conversationId, peerE164, framedBody,
                /*rcsMessageId=*/ null);
    }

    /**
     * {@link #sendAppOwned} with the app's {@code rcs_message_id}: pass the id on the chat row. It
     * becomes the wire id and the AAD id, which the resend path and the ciphertext cache key on.
     */
    public static SendOutcome sendAppOwned(final Context ctx, final int subId,
            final String conversationId, final String peerE164, final byte[] framedBody,
            final String rcsMessageId) {
        try {
            final MlsProviderTransport t = get(ctx, subId);
            if (!t.ensureReady(conversationId, subId,
                    java.util.Collections.singletonList(peerE164))) {
                return SendOutcome.NOT_APPLICABLE;
            }
            final Payload p = t.encryptForSend(conversationId, framedBody, rcsMessageId);
            if (p == null) return SendOutcome.FAILED;
            return t.sendSealed(peerE164, p) ? SendOutcome.SENT : SendOutcome.FAILED;
        } catch (final Throwable th) {
            LogUtil.w(TAG, "MlsProviderTransport.sendAppOwned threw", th);
            return SendOutcome.FAILED;
        }
    }

    /** The transport's arbitration characteristics, for recovery decisions. */
    public RcsMlsTransportProfile profile() {
        return (mProfile == null) ? RcsMlsTransportProfile.conservativeDefault() : mProfile;
    }

    private Group getGroup(final String conversationId) {
        // Read under the group's lock: a read that informs a decision belongs in the same critical
        // section as the decision.
        lock(conversationId);
        try {
        // Accept either the canonical key or an aliased conversation id.
        final String alias = mConvAlias.get(conversationId);
        final String k = (alias != null && mGroups.containsKey(alias)) ? alias : conversationId;
        Group g = mGroups.get(k);
        if (g == null && alias != null) g = loadPersisted(alias);
        if (g == null) {
            g = loadPersisted(conversationId);
            if (g != null) mGroups.put(conversationId, g);
        }
        return g;
        } finally { unlock(conversationId); }
    }

    /** @see MlsGroupState#putGroup */
    private void putGroup(final String conversationId, final Group g) {
        MlsGroupState.putGroup(mShell, mLog, conversationId, g);
    }

    /** Every persisted record as text: the debug dump that stands in for an index. */
    public String dumpRecords() { return mRecords.dumpAll(); }

    /** @see MlsRecordState#loadFromRecord */
    private Group loadFromRecord(final String conversationId) {
        return MlsRecordState.loadFromRecord(mShell, mLog, conversationId);
    }

    /** Rebuild the in-memory {@link Group} from its persisted record, the only source. */
    private Group loadPersisted(final String conversationId) {
        // The mls_provider_conv file still holds live state that is not per conversation:
        // KeyPackage pool bookkeeping, the identity refresh stamp, fileinfo entries, and the group
        // sweep's flag and cursor. Only its per-conversation scalars are legacy; nothing reads them
        // and forget clears them.
        return loadFromRecord(conversationId);
    }

    // The behind fixture: arm, status and release for the debug arm. The release goes back through
    // applyInboundControl, the ordinary inbound path. See MlsInboundHoldStore and MlsInboundHold.

    /**
     * Arm the inbound hold on one conversation. See {@link MlsInboundHoldStore}.
     *
     * @return a verdict for the debug log; never null, never throws
     */
    public String armInboundHold(final String rcsGroupId, final String peerE164,
            final MlsInboundHold.Mode mode, final boolean discard, final String assertedMembers) {
        try {
            if (!MlsInboundHoldStore.buildAllowsFixtures()) {
                return "REFUSED — Build.TYPE=" + Build.TYPE + " is not eng/userdebug";
            }
            if (!ensureSession()) return "REFUSED — no MLS session";
            final String key = canonicalKey(rcsGroupId, peerE164);
            if (key == null) return "REFUSED — pass --es rcsgid <gid> or --es to <e164>";
            // Require a group we hold, or the arm would hold nothing and look like success.
            final Group g = getGroup(key);
            if (g == null || g.groupId == null) {
                return "REFUSED — no MLS group for " + MlsConversationKey.forLog(key)
                        + ". Nothing would ever match this "
                        + "scope, and an arm that can never fire is worse than no arm.";
            }
            // The arm takes the peer allowlist although it transmits nothing: a held conversation
            // never converges, and our ladder answers that with an era advance that re-Welcomes
            // every member. Disarm, release, drop and status stay ungated, since stopping is the
            // recovery path.
            java.util.List<String> roster = mlsRosterMsisdns(g.groupId);
            if (roster == null || roster.isEmpty()) roster = ourRoster(key, g);
            if (assertedMembers != null && !assertedMembers.isEmpty()) {
                roster = new java.util.ArrayList<>();
                for (final String part : assertedMembers.split(",")) {
                    final String p = part.trim();
                    if (!p.isEmpty()) roster.add(p);
                }
            }
            if (roster == null || roster.isEmpty()) {
                // Unreadable roster: refuse, unless the operator asserts the members explicitly.
                return "REFUSED — cannot read this conversation's roster, so the peer allowlist "
                        + "gate cannot be evaluated. Re-run with --es members <e164,…> to assert "
                        + "who is in it (an armed hold makes us non-converging, and the ladder "
                        + "answers that with era advances aimed at every member).";
            }
            for (final String m : roster) {
                if (!MlsPeerGuard.allowDebugStateChange("mlshold-arm", m)) {
                    return "REFUSED by MlsPeerGuard for member " + LogMask.number(m)
                            + " — see the MlsPeerGuard "
                            + "line above for which rule fired.";
                }
            }
            final byte[] ee = mSelf.eraEpoch(g.groupId);
            final int era = MlsAppMessage.eraFrom(ee);
            final long epoch = MlsAppMessage.epochFrom(ee);
            MlsInboundHoldStore.get(mCtx).arm(key, key, mode, discard, era, epoch);
            LogUtil.w(TAG, "MlsProviderTransport: INBOUND HOLD ARMED on "
                    + MlsConversationKey.forLog(key) + " mode=" + mode
                    + (discard ? " DISCARDING" : "") + " at (era=" + era + " epoch=" + epoch + "). "
                    + "This device will now fall BEHIND on this conversation on purpose. It is a "
                    + "test fixture, not a fault — check the hold status before diagnosing anything "
                    + "about this group.");
            return "ARMED scope=" + MlsConversationKey.forLog(key) + " mode=" + mode
                    + (discard ? " DISCARDING" : "")
                    + " at (era=" + era + " epoch=" + epoch + ")";
        } catch (final Throwable t) {
            return "FAILED — " + t;
        }
    }

    /** The current state of the behind fixture. No server look-up. */
    public String inboundHoldStatus() {
        try {
            final MlsInboundHoldStore store = MlsInboundHoldStore.get(mCtx);
            int era = -1;
            long epoch = -1L;
            final String key = store.scopeKey();
            if (key != null && ensureSession()) {
                final Group g = getGroup(key);
                if (g != null && g.groupId != null) {
                    final byte[] ee = mSelf.eraEpoch(g.groupId);
                    era = MlsAppMessage.eraFrom(ee);
                    epoch = MlsAppMessage.epochFrom(ee);
                }
            }
            return store.describe(era, epoch);
        } catch (final Throwable t) {
            return "FAILED — " + t;
        }
    }

    /**
     * Disarm, then hand the held payloads back through {@link #applyInboundControl} in arrival
     * order; disarming first, or the gate there would hold them again. Reports the epoch before and
     * after and how many payloads the engine accepted.
     */
    public String releaseInboundHold() {
        try {
            final MlsInboundHoldStore store = MlsInboundHoldStore.get(mCtx);
            final String key = store.scopeKey();
            store.disarm();
            if (key == null) return "nothing to release — the hold was never armed";
            if (!ensureSession()) return "REFUSED — no MLS session";
            final Group g = getGroup(key);
            final byte[] before = (g == null || g.groupId == null) ? null
                    : mSelf.eraEpoch(g.groupId);
            final int eraBefore = MlsAppMessage.eraFrom(before);
            final long epochBefore = MlsAppMessage.epochFrom(before);
            final int armEra = store.armEra();
            final StringBuilder note = new StringBuilder();
            if (armEra >= 0 && eraBefore >= 0 && armEra != eraBefore) {
                // Say before replaying: after an era change every held payload is stale and none
                // will apply.
                note.append(" · ERA MOVED while held (armed at era ").append(armEra)
                        .append(", now ").append(eraBefore).append("): a Welcome was applied, so "
                                + "the held commits are from a group that no longer exists and the "
                                + "replay below cannot close the gap. That is the era-advance route "
                                + "home working, not a failure of the release.");
            }
            final java.util.List<MlsInboundHoldStore.Held> all = store.takeAll();
            final String[] pair = splitCanonicalKey(key);
            final String gid = pair == null ? null : pair[0];
            // A held row with no sender falls back to the scope's peer; for a 1:1 the key is the
            // peer.
            final String scopePeer = pair == null ? null : pair[1];
            int applied = 0;
            for (final MlsInboundHoldStore.Held h : all) {
                final String from = (h.fromE164 == null || h.fromE164.isEmpty())
                        ? scopePeer : h.fromE164;
                final boolean ok = applyInboundControl(from, h.messageId, h.blob,
                        /*convergenceAck=*/ false, gid);
                if (ok) applied++;
                LogUtil.i(TAG, "MlsProviderTransport: hold RELEASE replayed a " + h.kind
                        + " at epoch " + h.epoch + " from " + LogMask.number(from) + " → applied="
                        + ok);
            }
            final Group g2 = getGroup(key);
            final byte[] after = (g2 == null || g2.groupId == null) ? null
                    : mSelf.eraEpoch(g2.groupId);
            final String verdict = "RELEASED " + MlsConversationKey.forLog(key) + " — replayed "
                    + all.size()
                    + " held payload(s), " + applied + " applied. (era=" + eraBefore + " epoch="
                    + epochBefore + ") → (era=" + MlsAppMessage.eraFrom(after) + " epoch="
                    + MlsAppMessage.epochFrom(after) + ")" + note;
            LogUtil.w(TAG, "MlsProviderTransport: " + verdict);
            return verdict;
        } catch (final Throwable t) {
            return "FAILED — " + t;
        }
    }

    /** Disarm and keep the held payloads, leaving the gap standing with the bytes in hand. */
    public String disarmInboundHold() {
        MlsInboundHoldStore.get(mCtx).disarm();
        return "disarmed (held payloads KEPT — use `release` to replay them, `drop` to destroy "
                + "them). " + inboundHoldStatus();
    }

    /** Destroy the held payloads; the gap can no longer be closed by replay. */
    public String dropInboundHold() {
        final int n = MlsInboundHoldStore.get(mCtx).dropHeld();
        LogUtil.w(TAG, "MlsProviderTransport: DROPPED " + n
                + " held inbound payload(s). The server "
                + "does not backfill commits, so this conversation can no longer be brought level "
                + "by REPLAY. Other routes home (a re-Welcome from a member, a rebuild, an external "
                + "commit where the group's GroupInfo allows one and our profile permits it) are "
                + "unaffected by this and are not evaluated here.");
        return "dropped " + n + " held payload(s) — the gap is now unrecoverable by replay";
    }

}

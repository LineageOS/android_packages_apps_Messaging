/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.engine.mls.MlsProviderRpc;

import org.lineageos.rcs.provider.RcsMlsClaimResult;
import org.lineageos.rcs.provider.RcsMlsControlResult;
import org.lineageos.rcs.provider.RcsMlsTransportProfile;
import org.lineageos.rcs.provider.RcsSendResult;

import java.util.List;

/**
 * {@link MlsProviderRpc} over a {@link ProviderTransport} and a subscription: every call forwards
 * unchanged with the subscription id in front, and every AIDL result is converted to its mirror.
 * The {@code mirror}/{@code rcs} converters are also what a transport delegate uses when a moved
 * method's own signature carries one of these types. All of them are null-safe.
 */
final class MlsProviderRpcBinding implements MlsProviderRpc {
    private final ProviderTransport mPt;
    private final int mSubId;

    MlsProviderRpcBinding(final ProviderTransport pt, final int subId) {
        mPt = pt;
        mSubId = subId;
    }

    @Override public ControlResult applyMlsControl(final String peerE164, final String controlMsgId,
            final byte[] groupInfo, final byte[] commit, final byte[] epochAuth,
            final byte[] ratchetTree, final byte[] baseEpochAuth, final String rcsGroupId) {
        return mirror(mPt.applyMlsControl(mSubId, peerE164, controlMsgId, groupInfo, commit,
                epochAuth, ratchetTree, baseEpochAuth, rcsGroupId));
    }
    @Override public ControlResult addGroupUsersMls(final String rcsGroupId,
            final List<String> memberE164s, final byte[] welcome, final byte[] commit,
            final byte[] groupInfo, final byte[] epochAuth, final byte[] ratchetTree,
            final byte[] baseEpochAuth, final String controlMsgId) {
        return mirror(mPt.addGroupUsersMls(mSubId, rcsGroupId, memberE164s, welcome, commit,
                groupInfo, epochAuth, ratchetTree, baseEpochAuth, controlMsgId));
    }
    @Override public ControlResult removeGroupUsersMls(final String rcsGroupId,
            final List<String> memberE164s, final byte[] commit, final byte[] groupInfo,
            final byte[] epochAuth, final byte[] prevEpochAuth, final String controlMsgId) {
        return mirror(mPt.removeGroupUsersMls(mSubId, rcsGroupId, memberE164s, commit, groupInfo,
                epochAuth, prevEpochAuth, controlMsgId));
    }
    @Override public ControlResult selfLeaveGroupMls(final String rcsGroupId, final byte[] proposal,
            final byte[] prevEpochAuth, final String controlMsgId) {
        return mirror(mPt.selfLeaveGroupMls(mSubId, rcsGroupId, proposal, prevEpochAuth,
                controlMsgId));
    }
    @Override public ControlResult changeGroupIconMls(final String rcsGroupId,
            final String contentType, final byte[] ciphertext, final byte[] groupInfo,
            final byte[] commit, final byte[] epochAuth, final byte[] ratchetTree,
            final byte[] baseEpochAuth, final byte[] privateMessages, final String controlMsgId) {
        return mirror(mPt.changeGroupIconMls(mSubId, rcsGroupId, contentType, ciphertext,
                groupInfo, commit, epochAuth, ratchetTree, baseEpochAuth, privateMessages,
                controlMsgId));
    }
    @Override public ControlResult changeGroupSubjectMls(final String rcsGroupId,
            final String contentType, final byte[] ciphertext, final byte[] groupInfo,
            final byte[] commit, final byte[] epochAuth, final byte[] ratchetTree,
            final byte[] baseEpochAuth, final byte[] privateMessages, final String controlMsgId) {
        return mirror(mPt.changeGroupSubjectMls(mSubId, rcsGroupId, contentType, ciphertext,
                groupInfo, commit, epochAuth, ratchetTree, baseEpochAuth, privateMessages,
                controlMsgId));
    }
    @Override public ControlResult createMlsConversation(final String peerE164,
            final byte[] mlsGroupId, final byte[] welcome, final byte[] commit,
            final byte[] groupInfo, final byte[] epochAuth, final byte[] ratchetTree, final int era,
            final String contextId, final String rcsGroupId) {
        return mirror(mPt.createMlsConversation(mSubId, peerE164, mlsGroupId, welcome, commit,
                groupInfo, epochAuth, ratchetTree, era, contextId, rcsGroupId));
    }
    @Override public ControlResult getMlsGroupInfo(final String phoneE164) {
        return mirror(mPt.getMlsGroupInfo(mSubId, phoneE164));
    }
    @Override public ControlResult getMlsGroupInfoForGroup(final String phoneE164,
            final String rcsGroupId) {
        return mirror(mPt.getMlsGroupInfoForGroup(mSubId, phoneE164, rcsGroupId));
    }
    @Override public boolean addGroupUsers(final String groupId, final List<String> memberE164s) {
        return mPt.addGroupUsers(mSubId, groupId, memberE164s);
    }
    @Override public boolean removeGroupUsers(final String groupId,
            final List<String> memberE164s) {
        return mPt.removeGroupUsers(mSubId, groupId, memberE164s);
    }
    @Override public SendResult sendMlsCiphertext(final String peerE164, final byte[] ciphertext,
            final String messageId, final int era, final byte[] epochAuth) {
        return mirror(mPt.sendMlsCiphertext(mSubId, peerE164, ciphertext, messageId, era,
                epochAuth));
    }
    @Override public SendResult sendGroupMlsCiphertext(final String rcsGroupId,
            final byte[] ciphertext, final String messageId, final int era,
            final byte[] epochAuth) {
        return mirror(mPt.sendGroupMlsCiphertext(mSubId, rcsGroupId, ciphertext, messageId, era,
                epochAuth));
    }
    @Override public boolean sendMlsNegativeDeliveryImdn(final String originalMessageId,
            final String toUri, final String rcsGroupId, final int failureReason, final long eraId,
            final String epochAuthB64) {
        return mPt.sendMlsNegativeDeliveryImdn(mSubId, originalMessageId, toUri, rcsGroupId,
                failureReason, eraId, epochAuthB64);
    }
    @Override public boolean sendMlsNegativeDeliveryImdn(final String originalMessageId,
            final String toUri, final String rcsGroupId, final int failureReason, final long eraId,
            final String epochAuthB64, final String derivedContentSigB64) {
        return mPt.sendMlsNegativeDeliveryImdn(mSubId, originalMessageId, toUri, rcsGroupId,
                failureReason, eraId, epochAuthB64, derivedContentSigB64);
    }
    @Override public boolean sendMlsNegativeDeliveryImdn(final String originalMessageId,
            final String toUri, final String rcsGroupId, final int failureReason, final long eraId,
            final String epochAuthB64, final String derivedContentSigB64,
            final String receiptMessageId) {
        return mPt.sendMlsNegativeDeliveryImdn(mSubId, originalMessageId, toUri, rcsGroupId,
                failureReason, eraId, epochAuthB64, derivedContentSigB64, receiptMessageId);
    }
    @Override public void sendReconciliationReceipt(final String originalMessageId,
            final String toUri) {
        mPt.sendReconciliationReceipt(originalMessageId, toUri);
    }
    @Override public TransportProfile getMlsTransportProfile() {
        return mirror(mPt.getMlsTransportProfile(mSubId));
    }
    @Override public byte[] getMlsGroupIdForPeer(final String peerE164) {
        return mPt.getMlsGroupIdForPeer(mSubId, peerE164);
    }
    @Override public boolean isMlsReady() { return mPt.isMlsReady(mSubId); }
    @Override public int lookupPeerMlsLookupState(final String phoneE164) {
        return mPt.lookupPeerMlsLookupState(mSubId, phoneE164);
    }
    @Override public boolean uploadKeyPackages(final byte[] keyPackages, final byte[] lastResort) {
        return mPt.uploadKeyPackages(mSubId, keyPackages, lastResort);
    }
    @Override public byte[] fetchMissedCommits(final String peerE164, final String rcsGroupId,
            final long era, final byte[] epochAuthenticator) {
        return mPt.fetchMissedCommits(mSubId, peerE164, rcsGroupId, era, epochAuthenticator);
    }
    @Override public byte[] fetchServerEpochAuthenticator(final String peerE164,
            final String rcsGroupId) {
        return mPt.fetchServerEpochAuthenticator(mSubId, peerE164, rcsGroupId);
    }
    @Override public long[] getMlsServerEraEpoch(final String peerE164, final String rcsGroupId) {
        return mPt.getMlsServerEraEpoch(mSubId, peerE164, rcsGroupId);
    }
    @Override public ClaimResult claimPeerKeyPackagesWithOutcome(final String phoneE164) {
        return mirror(mPt.claimPeerKeyPackagesWithOutcome(mSubId, phoneE164));
    }
    @Override public List<byte[]> claimPeerKeyPackages(final String phoneE164) {
        return mPt.claimPeerKeyPackages(mSubId, phoneE164);
    }
    @Override public List<byte[]> claimPeerKeyPackages(final String phoneE164,
            final int[] outcomeSink) {
        return mPt.claimPeerKeyPackages(mSubId, phoneE164, outcomeSink);
    }
    @Override public boolean mlsForgetConversation(final String phoneE164) {
        return mPt.mlsForgetConversation(mSubId, phoneE164);
    }
    @Override public boolean mlsForgetGroupConversation(final String rcsGroupId) {
        return mPt.mlsForgetGroupConversation(mSubId, rcsGroupId);
    }

    static ControlResult mirror(final RcsMlsControlResult r) {
        return r == null ? null : new ControlResult(r.verdict, r.response, r.detail);
    }
    static SendResult mirror(final RcsSendResult r) {
        return r == null ? null : new SendResult(r.accepted, r.reasonCode, r.reason);
    }
    static TransportProfile mirror(final RcsMlsTransportProfile r) {
        return r == null ? null : new TransportProfile(r.serverArbitratesEra,
                r.requiresConvergenceAck, r.acceptsMemberExternalCommit, r.hasServerGroupInfo);
    }
    static ClaimResult mirror(final RcsMlsClaimResult r) {
        return r == null ? null : new ClaimResult(r.outcome, r.keyPackages, r.detail);
    }

    static RcsMlsControlResult rcs(final ControlResult r) {
        return r == null ? null : new RcsMlsControlResult(r.verdict, r.response, r.detail);
    }
    static RcsSendResult rcs(final SendResult r) {
        return r == null ? null : new RcsSendResult(r.accepted, r.reasonCode, r.reason);
    }
    static RcsMlsTransportProfile rcs(final TransportProfile r) {
        return r == null ? null : new RcsMlsTransportProfile(r.serverArbitratesEra,
                r.requiresConvergenceAck, r.acceptsMemberExternalCommit, r.hasServerGroupInfo);
    }
    static RcsMlsClaimResult rcs(final ClaimResult r) {
        return r == null ? null : new RcsMlsClaimResult(r.outcome, r.keyPackages, r.detail);
    }
}

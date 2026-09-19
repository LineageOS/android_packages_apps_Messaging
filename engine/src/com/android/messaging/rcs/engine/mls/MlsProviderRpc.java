/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;

/**
 * The provider RPCs engine code makes: the {@code ProviderTransport} subset it calls, with the same
 * names and argument order minus the leading {@code subId}, and the AIDL result types mirrored as
 * plain values converted at the binding. See docs/mls/transport-and-port.md.
 */
public interface MlsProviderRpc {
    ControlResult applyMlsControl(String peerE164, String controlMsgId, byte[] groupInfo,
            byte[] commit, byte[] epochAuth, byte[] ratchetTree, byte[] baseEpochAuth,
            String rcsGroupId);
    ControlResult addGroupUsersMls(String rcsGroupId, List<String> memberE164s, byte[] welcome,
            byte[] commit, byte[] groupInfo, byte[] epochAuth, byte[] ratchetTree,
            byte[] baseEpochAuth, String controlMsgId);
    ControlResult removeGroupUsersMls(String rcsGroupId, List<String> memberE164s, byte[] commit,
            byte[] groupInfo, byte[] epochAuth, byte[] prevEpochAuth, String controlMsgId);
    ControlResult selfLeaveGroupMls(String rcsGroupId, byte[] proposal, byte[] prevEpochAuth,
            String controlMsgId);
    ControlResult changeGroupIconMls(String rcsGroupId, String contentType, byte[] ciphertext,
            byte[] groupInfo, byte[] commit, byte[] epochAuth, byte[] ratchetTree,
            byte[] baseEpochAuth, byte[] privateMessages, String controlMsgId);
    ControlResult changeGroupSubjectMls(String rcsGroupId, String contentType, byte[] ciphertext,
            byte[] groupInfo, byte[] commit, byte[] epochAuth, byte[] ratchetTree,
            byte[] baseEpochAuth, byte[] privateMessages, String controlMsgId);
    ControlResult createMlsConversation(String peerE164, byte[] mlsGroupId, byte[] welcome,
            byte[] commit, byte[] groupInfo, byte[] epochAuth, byte[] ratchetTree, int era,
            String contextId, String rcsGroupId);
    ControlResult getMlsGroupInfo(String phoneE164);
    ControlResult getMlsGroupInfoForGroup(String phoneE164, String rcsGroupId);
    boolean addGroupUsers(String groupId, List<String> memberE164s);
    boolean removeGroupUsers(String groupId, List<String> memberE164s);
    SendResult sendMlsCiphertext(String peerE164, byte[] ciphertext, String messageId, int era,
            byte[] epochAuth);
    SendResult sendGroupMlsCiphertext(String rcsGroupId, byte[] ciphertext, String messageId,
            int era, byte[] epochAuth);
    boolean sendMlsNegativeDeliveryImdn(String originalMessageId, String toUri, String rcsGroupId,
            int failureReason, long eraId, String epochAuthB64);
    boolean sendMlsNegativeDeliveryImdn(String originalMessageId, String toUri, String rcsGroupId,
            int failureReason, long eraId, String epochAuthB64, String derivedContentSigB64);
    boolean sendMlsNegativeDeliveryImdn(String originalMessageId, String toUri, String rcsGroupId,
            int failureReason, long eraId, String epochAuthB64, String derivedContentSigB64,
            String receiptMessageId);
    void sendReconciliationReceipt(String originalMessageId, String toUri);
    TransportProfile getMlsTransportProfile();
    byte[] getMlsGroupIdForPeer(String peerE164);
    boolean isMlsReady();
    int lookupPeerMlsLookupState(String phoneE164);
    boolean uploadKeyPackages(byte[] keyPackages, byte[] lastResort);
    byte[] fetchMissedCommits(String peerE164, String rcsGroupId, long era,
            byte[] epochAuthenticator);
    byte[] fetchServerEpochAuthenticator(String peerE164, String rcsGroupId);
    long[] getMlsServerEraEpoch(String peerE164, String rcsGroupId);
    ClaimResult claimPeerKeyPackagesWithOutcome(String phoneE164);
    List<byte[]> claimPeerKeyPackages(String phoneE164);
    List<byte[]> claimPeerKeyPackages(String phoneE164, int[] outcomeSink);
    boolean mlsForgetConversation(String phoneE164);
    boolean mlsForgetGroupConversation(String rcsGroupId);

    /**
     * {@code RcsMlsControlResult} as a value, with the same constants and helpers;
     * {@link #toString} prints the AIDL type's text exactly.
     */
    final class ControlResult {
        public static final int VERDICT_OK = 0;
        public static final int VERDICT_ERA_GAP = 1;
        public static final int VERDICT_EXTERNAL_COMMIT_REFUSED = 2;
        public static final int VERDICT_GROUP_ID_CHANGED = 3;
        public static final int VERDICT_NOT_REGISTERED = 4;
        public static final int VERDICT_TRANSPORT_FAILED = 5;
        public static final int VERDICT_REJECTED = 6;
        public static final int VERDICT_NOT_IN_GROUP = 7;

        public final int verdict;
        public final byte[] response;
        public final String detail;

        public ControlResult(final int verdict, final byte[] response, final String detail) {
            this.verdict = verdict;
            this.response = response;
            this.detail = detail;
        }

        public boolean ok() { return verdict == VERDICT_OK; }

        /** True when the request never reached a verdict — retryable, unlike a rejection. */
        public boolean transportFailed() { return verdict == VERDICT_TRANSPORT_FAILED; }

        @Override public String toString() {
            return "RcsMlsControlResult{verdict=" + verdict
                    + (response == null ? "" : " resp=" + response.length + "B")
                    + (detail == null ? "" : " detail=" + detail) + "}";
        }
    }

    /**
     * {@code RcsSendResult}, as a value. The AIDL type declares no {@code toString}; nor does this.
     */
    final class SendResult {
        public static final int REASON_OK = 0;
        public static final int REASON_NOT_REGISTERED = 1;
        public static final int REASON_NOT_PROVISIONED = 2;
        public static final int REASON_PEER_NOT_RCS = 3;
        public static final int REASON_RATE_LIMITED = 4;
        public static final int REASON_INTERNAL_ERROR = 5;
        public static final int REASON_NOT_IN_GROUP = 6;

        public final boolean accepted;
        public final int reasonCode;
        public final String reason;

        public SendResult(final boolean accepted, final int reasonCode, final String reason) {
            this.accepted = accepted;
            this.reasonCode = reasonCode;
            this.reason = reason;
        }
    }

    /**
     * {@code RcsMlsTransportProfile}, as a value; {@link #toString} is the AIDL type's, verbatim.
     */
    final class TransportProfile {
        public final boolean serverArbitratesEra;
        public final boolean requiresConvergenceAck;
        public final boolean acceptsMemberExternalCommit;
        public final boolean hasServerGroupInfo;

        public TransportProfile(final boolean serverArbitratesEra,
                final boolean requiresConvergenceAck, final boolean acceptsMemberExternalCommit,
                final boolean hasServerGroupInfo) {
            this.serverArbitratesEra = serverArbitratesEra;
            this.requiresConvergenceAck = requiresConvergenceAck;
            this.acceptsMemberExternalCommit = acceptsMemberExternalCommit;
            this.hasServerGroupInfo = hasServerGroupInfo;
        }

        /**
         * The AIDL type's own default for an unknown transport: nothing arbitrated, nothing
         * awaited.
         */
        public static TransportProfile conservativeDefault() {
            return new TransportProfile(false, false, false, false);
        }

        @Override public String toString() {
            return "RcsMlsTransportProfile{arbitratesEra=" + serverArbitratesEra
                    + " convergenceAck=" + requiresConvergenceAck
                    + " memberExternalCommit=" + acceptsMemberExternalCommit
                    + " serverGroupInfo=" + hasServerGroupInfo + "}";
        }
    }

    /** {@code RcsMlsClaimResult}, as a value; {@link #toString} is the AIDL type's, verbatim. */
    final class ClaimResult {
        public static final int OUTCOME_SERVED = 0;
        public static final int OUTCOME_PEER_HAS_NONE = 1;
        public static final int OUTCOME_NOT_AUTHORIZED = 2;
        public static final int OUTCOME_REFUSED = 3;
        public static final int OUTCOME_TRANSPORT_FAILED = 4;
        public static final int OUTCOME_NOT_ATTEMPTED = 5;

        public final int outcome;
        public final byte[] keyPackages;
        public final String detail;

        public ClaimResult(final int outcome, final byte[] keyPackages, final String detail) {
            this.outcome = outcome;
            this.keyPackages = keyPackages;
            this.detail = detail;
        }

        @Override public String toString() {
            return "RcsMlsClaimResult{outcome=" + outcome + " bytes="
                    + (keyPackages == null ? 0 : keyPackages.length) + " detail=" + detail + "}";
        }
    }
}

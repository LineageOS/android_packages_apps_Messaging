/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The RCC.16 §7.7.2.2 client-generated negative-delivery IMDN: build, parse, and the client and
 * server failure-reason vocabularies. Sent only for a message that failed to decrypt, a signature
 * that failed verification, a commit or proposal that failed validation, or an ignored RCC.16 §10.3
 * resend, and only after self-heal has run (RCC.16 §10.2). The report is a signed message over
 * {@link VerifiableDerivedContent}. See docs/mls/health-and-recovery.md.
 */
public final class RccNegativeDeliveryImdn {

    private RccNegativeDeliveryImdn() {}

    /**
     * Client reason tokens. Codes 1 to 5 are RCC.16 §7.6.3.2 binary values; the rest have no spec
     * encoding and their codes are local, for the AIDL only. Some tokens report an outcome rather
     * than a failure: branch on {@link #isFailure()}, never on a reason being present. Code 4's XML
     * element is {@code failed-to-decrypt}; its binary name is {@code failure_to_decrypt}.
     */
    public enum Reason {
        /** RCC.16 §7.7.2.3: the sender was not a member of the group. */
        MESSAGE_FROM_NON_MEMBER(1, "message-from-non-member"),
        /** RCC.16 §6.2: the commit carried an invalid credential; replace that leaf. */
        INVALID_CREDENTIAL(2, "invalid-credential"),
        /** RCC.16 §6.2: the commit failed validation. */
        INVALID_COMMIT(3, "invalid-commit"),
        /** RCC.16 §6.2: the sender advances to the latest epoch and resends. */
        FAILED_TO_DECRYPT(4, "failed-to-decrypt"),
        /** RCC.16 §6.2: a commit arrived as a PrivateMessage; resend it as a PublicMessage. */
        COMMIT_IN_PRIVATEMESSAGE(5, "commit-in-privatemessage"),

        // ---- Further failure tokens peers use; no RCC.16 §7.6.3.2 code.
        /** A control message could not be processed at all. */
        CONTROL_MESSAGE_FAILED(13, "control-message-failed", true),
        /**
         * An RCC.16 §10.3 resent message still failed to decrypt. Parsed but never sent: some peers
         * reject the whole receipt for this token, so {@link #forEmit} substitutes
         * {@link #FAILED_TO_DECRYPT}.
         */
        RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT(6, "resent-message-for-me-failed-to-decrypt", true),

        // ---- Outcome tokens: informational; applying a remedy to one is a bug.
        /** The peer recovered our commit during its self-heal. */
        COMMIT_PROCESSED_IN_SELF_HEAL(8, "commit-processed-in-self-heal", false),
        /** As above, via enhanced (commit-replay) self-heal. */
        COMMIT_PROCESSED_IN_ENHANCED_SELF_HEAL(7, "commit-processed-in-enhanced-self-heal", false),
        /** The peer recovered our proposal during its self-heal. */
        PROPOSAL_PROCESSED_IN_SELF_HEAL(12, "proposal-processed-in-self-heal", false),
        /** As above, via enhanced self-heal. */
        PROPOSAL_PROCESSED_IN_ENHANCED_SELF_HEAL(11, "proposal-processed-in-enhanced-self-heal",
                false),
        /** A commit failed and the peer already advanced the era; do not advance it again. */
        COMMIT_FAILED_THEN_ERA_ADVANCEMENT(9, "commit-failed-then-era-advancement", false),
        /** A control message failed because the conversation left MLS; not repairable. */
        CONTROL_MESSAGE_FAILED_DUE_TO_DOWNGRADE(10, "control-message-failed-due-to-downgrade",
                false);

        private final int mCode;
        private final String mXml;
        private final boolean mIsFailure;

        Reason(final int code, final String xml) {
            this(code, xml, true);
        }

        Reason(final int code, final String xml, final boolean isFailure) {
            mCode = code;
            mXml = xml;
            mIsFailure = isFailure;
        }

        /** True for a problem needing a remedy, false for an outcome the peer already handled. */
        public boolean isFailure() {
            return mIsFailure;
        }

        /** The binary value that crosses the AIDL (RCC.16 §7.6.3.2 for codes 1 to 5). */
        public int code() {
            return mCode;
        }

        /** Whether this token may be sent; see {@link #RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT}. */
        public boolean emittable() {
            return this != RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT;
        }

        /** The token to send: this one, or the closest emittable truth. */
        public Reason forEmit() {
            return emittable() ? this : FAILED_TO_DECRYPT;
        }

        /**
         * Always false: a client failure is a claim about the peer's state, not our bytes, so the
         * cached ciphertext stays valid and a resend is a new message with its own cache entry.
         */
        public boolean cleansCache() {
            return false;
        }

        /** The RCC.16 §7.7.2.3 XML element name. */
        public String xmlElement() {
            return mXml;
        }

        /** The reason for a code, or null for 0 or unknown. */
        public static Reason fromCode(final int code) {
            for (final Reason r : values()) {
                if (r.mCode == code) {
                    return r;
                }
            }
            return null;
        }

        /** The reason for an XML element name, or null if unknown. */
        public static Reason fromXmlElement(final String element) {
            if (element == null) {
                return null;
            }
            for (final Reason r : values()) {
                if (r.mXml.equals(element)) {
                    return r;
                }
            }
            return null;
        }
    }

    /**
     * The failure-reason arm rule: an arm is legal only on a delivery receipt with status
     * {@code failed} and no encrypted data, and at most one arm may be set (zero is legal). The
     * failure-reason IMDN is plaintext XML authenticated by a CPIM header, so an encrypted one is
     * refused by peers.
     *
     * @throws IllegalStateException with the peers' own message text
     */
    public static void checkArmRule(final boolean isDeliveryType, final boolean isFailedStatus,
            final boolean hasEncryptedData, final boolean hasClientArm,
            final boolean hasServerArm) {
        if (hasClientArm && hasServerArm) {
            throw new IllegalStateException("Either mls-server-failure-reason or "
                    + "mls-client-failure-reason should be set, but not both");
        }
        final boolean armLegal = isDeliveryType && isFailedStatus && !hasEncryptedData;
        if (hasServerArm && !armLegal) {
            throw new IllegalStateException(
                    "Only set mls-server-failure-reason for a failed MLS delivery receipt");
        }
        if (hasClientArm && !armLegal) {
            throw new IllegalStateException(
                    "Only set mls-client-failure-reason for a failed MLS delivery receipt");
        }
    }

    /**
     * Builds the negative-delivery IMDN XML.
     *
     * @param originalMessageId the message that failed (RFC 5438 {@code <message-id>})
     * @param reason the client reason; required
     * @param iso8601Datetime the RFC 5438 {@code <datetime>}, passed in for determinism
     */
    public static String build(final String originalMessageId, final Reason reason,
            final String iso8601Datetime) {
        if (originalMessageId == null || originalMessageId.isEmpty()) {
            throw new IllegalArgumentException("originalMessageId is required by RFC 5438");
        }
        if (reason == null) {
            // RCC.16 §7.7.2.2 allows the report only with a defined reason.
            throw new IllegalArgumentException("RCC.16 §7.7.2.2 requires a failure reason");
        }
        // Asserted, not assumed: a later change could encrypt this receipt.
        checkArmRule(/*isDeliveryType=*/ true, /*isFailedStatus=*/ true,
                /*hasEncryptedData=*/ false, /*hasClientArm=*/ true, /*hasServerArm=*/ false);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">"
                + "<message-id>" + originalMessageId + "</message-id>"
                + "<datetime>" + iso8601Datetime + "</datetime>"
                + "<delivery-notification><status><failed>"
                + "<mls-client-failure-reason><" + reason.xmlElement() + "/>"
                + "</mls-client-failure-reason>"
                + "</failed></status></delivery-notification>"
                + "</imdn>";
    }

    /**
     * Server failure reasons (RCC.16 §7.7.2.1), under {@code <mls-server-failure-reason>}; the
     * server's own reason for a rejection, arriving as an IMDN after the request itself succeeded.
     * Codes are local, offset by {@link #SERVER_BASE}; the spec has no binary encoding for these.
     */
    public enum ServerReason {
        // ---- The eleven in the RCC.16 §7.7.2.3 schema.
        INCORRECT_ERA(1, "incorrect-era"),
        INCORRECT_EPOCH(2, "incorrect-epoch"),
        INCORRECT_EPOCH_AUTHENTICATOR(3, "incorrect-epoch-authenticator"),
        EXPIRED_CREDENTIAL(4, "expired-credential"),
        MISMATCHED_RCS_GROUP_STATE(5, "mismatched-rcs-group-state"),
        UNPARSABLE_COMMIT(6, "unparsable-commit"),
        MISMATCHED_CONFIRMATION_TAG(7, "mismatched-confirmation-tag"),
        PENDING_PROPOSAL(8, "pending-proposal"),
        TRANSIENT_ERROR(9, "transient-error"),
        ENCRYPTION_NOT_AVAILABLE(10, "encryption-not-available"),
        INVALID_COMMIT(11, "invalid-commit"),

        // ---- Five more that peers use but the published schema does not list. These codes follow
        // the peers' proto numbering, not their token declaration order, which differs at 15 and
        // 16; do not renumber from a declaration-ordered listing.
        /** The server has no such group; do not heal or resend into it. */
        MLS_GROUP_NOT_FOUND(12, "mls-group-not-found"),
        /** Malformed request; retrying the same bytes cannot help. */
        INVALID_INPUT(13, "invalid-input"),
        /** The era advancement quota is spent; an accepted advance may still report this. */
        ERA_ADVANCEMENT_QUOTA_REACHED(14, "era-advancement-quota-reached"),
        /** The epoch advancement quota is spent. */
        EPOCH_ADVANCEMENT_QUOTA_REACHED(16, "epoch-advancement-quota-reached"),
        /** The group carries {@code end_mls}: healing or advancing is pointless. */
        MLS_GROUP_HAS_END_MLS(15, "mls-group-has-end-mls");

        private final int mLocalCode;
        private final String mXml;

        ServerReason(final int localCode, final String xml) {
            mLocalCode = localCode;
            mXml = xml;
        }

        /** The RCC.16 §7.7.2.3 XML element name. */
        public String xmlElement() {
            return mXml;
        }

        /**
         * AIDL code, disjoint from client codes by {@link #SERVER_BASE}. Explicit rather than
         * ordinal so inserting a token never renumbers the others.
         */
        public int code() {
            return SERVER_BASE + mLocalCode;
        }

        public static ServerReason fromXmlElement(final String element) {
            if (element == null) return null;
            for (final ServerReason r : values()) {
                if (r.mXml.equals(element)) return r;
            }
            return null;
        }

        public static ServerReason fromCode(final int code) {
            for (final ServerReason r : values()) {
                if (r.code() == code) return r;
            }
            return null;
        }

        /**
         * Whether this reason invalidates the cached outbound ciphertext: every reason except
         * {@code transient-error}, whose cached bytes are still valid and must be replayed. An
         * unknown reason is a null {@link ServerReason} and never reaches here.
         */
        public boolean cleansCache() {
            return this != TRANSIENT_ERROR;
        }

        /** The remedy class; total over the enum. See docs/mls/health-and-recovery.md. */
        public Disposition disposition() {
            switch (this) {
                // Our view of the group disagrees with the server's. A confirmation tag is derived
                // from group state, so its mismatch is a divergence too.
                case INCORRECT_ERA:
                case INCORRECT_EPOCH:
                case INCORRECT_EPOCH_AUTHENTICATOR:
                case MISMATCHED_RCS_GROUP_STATE:
                case MISMATCHED_CONFIRMATION_TAG:
                    return Disposition.SELF_HEAL;

                case TRANSIENT_ERROR:
                    return Disposition.RETRYABLE;

                case MLS_GROUP_NOT_FOUND:
                case MLS_GROUP_HAS_END_MLS:
                    return Disposition.TERMINAL;

                case EPOCH_ADVANCEMENT_QUOTA_REACHED:
                    return Disposition.ESCALATE_TO_ERA;

                // No higher rung: peers downgrade here, which stays gated behind
                // MlsConfig.downgradeOnRepairExhausted rather than automatic.
                case ERA_ADVANCEMENT_QUOTA_REACHED:
                    return Disposition.OUT_OF_QUOTA;

                // Our bytes, not our state: deliberately not self-heal.
                case INVALID_INPUT:
                case UNPARSABLE_COMMIT:
                    return Disposition.OURS_TO_FIX;

                case EXPIRED_CREDENTIAL:
                    return Disposition.REFRESH_IDENTITY;

                // Commit the cached by-reference proposal; self-heal would advance past it.
                case PENDING_PROPOSAL:
                    return Disposition.COMMIT_PENDING_PROPOSALS;

                // No safe automatic remedy: a server invalid-commit may be our state or our bytes,
                // which want opposite remedies, and encryption-not-available would mean an
                // automatic downgrade.
                case INVALID_COMMIT:
                case ENCRYPTION_NOT_AVAILABLE:
                    return Disposition.NONE;
            }
            // Unreachable; the mapping is tested to be total.
            return Disposition.NONE;
        }
    }

    /**
     * The remedy class for a {@link ServerReason}. The transport carries it out; the engine only
     * classifies.
     */
    public enum Disposition {
        /** Our state disagrees with the server's: repair, do not resend. */
        SELF_HEAL,
        /** Nothing is wrong; retry with the same bytes. */
        RETRYABLE,
        /** The conversation is gone or downgraded: neither heal nor resend. */
        TERMINAL,
        /** Out of era advancement quota; retrying spends more of it. */
        OUT_OF_QUOTA,
        /** The epoch quota is spent but the era quota may not be: advance the era. */
        ESCALATE_TO_ERA,
        /** Malformed on our side; needs a code fix, not a retry. */
        OURS_TO_FIX,
        /** The credential expired: a fresh identity or KeyPackage is needed. */
        REFRESH_IDENTITY,
        /** A by-reference proposal must be committed before anything else is accepted. */
        COMMIT_PENDING_PROPOSALS,
        /** Deliberately no automatic remedy. */
        NONE,
    }

    /**
     * A {@code <failed/>} report with no reason element, the common form from peers that cannot
     * sign over a message they could not decrypt. Distinct from 0, an unrecognised element.
     */
    public static final int NO_REASON_GIVEN = 200;

    /**
     * Offset for server reason codes. {@code invalid-commit} exists in both lists with different
     * meanings, so the code spaces must not overlap.
     */
    public static final int SERVER_BASE = 100;

    /** What {@link #parse} recovered from an inbound IMDN. */
    public static final class Parsed {
        /** The {@code <message-id>} reported on: one of our messages. */
        public final String messageId;
        /** The reason, or null when the report carried none we recognise. */
        public final Reason reason;
        /** The raw reason element name, kept even when unrecognised so it can be logged. */
        public final String rawReasonElement;
        /** Set instead of {@link #reason} for an {@code mls-server-failure-reason}. */
        public final ServerReason serverReason;

        Parsed(final String messageId, final Reason reason, final String rawReasonElement,
                final ServerReason serverReason) {
            this.messageId = messageId;
            this.reason = reason;
            this.rawReasonElement = rawReasonElement;
            this.serverReason = serverReason;
        }

        /**
         * The AIDL code: a client code, a server code (from {@link #SERVER_BASE}),
         * {@link #NO_REASON_GIVEN} when there was no reason element, or 0 for an unrecognised one.
         */
        public int code() {
            if (reason != null) return reason.code();
            if (serverReason != null) return serverReason.code();
            // No reason element: not the same as an unrecognised one, and must stay actionable.
            if (rawReasonElement == null) return NO_REASON_GIVEN;
            return 0;
        }
    }

    /**
     * Parses an inbound IMDN; null for a positive receipt or anything unusable. Lenient about
     * whitespace and self-closing forms.
     */
    public static Parsed parse(final String xml) {
        if (xml == null || xml.isEmpty()) {
            return null;
        }
        // Bare "<failed" matches both <failed/> and <failed>.
        if (!xml.contains("<failed")) {
            return null;
        }
        final java.util.regex.Matcher mid = java.util.regex.Pattern
                .compile("<message-id>\\s*([^<\\s]+)\\s*</message-id>").matcher(xml);
        if (!mid.find()) {
            // RFC 5438 requires the message-id; nothing to act on without it.
            return null;
        }
        final java.util.regex.Matcher rm = java.util.regex.Pattern
                .compile("<mls-client-failure-reason>\\s*<([A-Za-z-]+)\\s*/?>").matcher(xml);
        final String rawEl = rm.find() ? rm.group(1) : null;
        // The same arm may carry a server reason (RCC.16 §7.7.2.1).
        final java.util.regex.Matcher sm = java.util.regex.Pattern
                .compile("<mls-server-failure-reason>\\s*<([A-Za-z-]+)\\s*/?>").matcher(xml);
        final String rawSrv = sm.find() ? sm.group(1) : null;
        // Both arms set is illegal. Drop the receipt rather than throw: the input is
        // peer-controlled, and choosing either arm could pick the wrong remedy.
        if (rawEl != null && rawSrv != null) {
            return null;
        }
        return new Parsed(mid.group(1), Reason.fromXmlElement(rawEl),
                rawEl != null ? rawEl : rawSrv, ServerReason.fromXmlElement(rawSrv));
    }
}

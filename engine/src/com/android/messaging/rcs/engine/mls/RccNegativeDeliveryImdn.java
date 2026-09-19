/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.engine.mls;

/**
 * RCC.16 v3.0 §7.7.2.2 — the client-generated NEGATIVE-Delivery IMDN.
 *
 * <p>This is the distress signal that makes MLS divergence recoverable, and it is worth being
 * precise about why it matters. The server grants an era advance only to a member whose state
 * matches its own, so a DIVERGED member cannot repair itself; and peer state is not queryable, so a
 * HEALTHY member cannot detect that a peer needs repairing. Read that way it is a deadlock: the
 * member that knows cannot act, the member that can act cannot know.
 *
 * <p>The resolution is that the diverged member SAYS SO. §7.7.2.2 extends RFC 5438's
 * {@code <delivery-notification><status>} with a {@code failed} arm carrying a new
 * {@code <mls-client-failure-reason>}, and for {@code <failed-to-decrypt>} the spec states the
 * sender's obligation outright (§6.2): "The sender shall advance to the latest epoch and resend the
 * message." Emit it and handle it, and the deadlock dissolves.
 *
 * <p>Sent in exactly four situations, and §7.7.2.2 forbids any other: an encrypted message failed to
 * decrypt, a signed message failed verification, a commit/proposal failed validation, or an FTD was
 * ignored per §10.3.
 *
 * <p><b>Timing.</b> Not on first failure. §10.2 reports after the Self-Heal procedure completes, for
 * the messages that STILL failed, and §6.2 says the same for commit validation ("if the validation
 * failure persists"). Reporting eagerly tells every peer we are broken during a recovery that was
 * about to succeed.
 *
 * <p><b>Signed, and §7.7.2.2-conformant on this point.</b> §7.7.2.2 requires this be sent as a
 * Signed Message whose signature covers the §7.6.3.2 {@code VerifiableDeliveryImdn} struct (see
 * {@link VerifiableDerivedContent}), and it is. The §7.11 {@code rcs_signature} (0xF002) machinery
 * is BUILT and device-verified: {@code OpenMlsSession.rcsSign}, reached from
 * {@code MlsProviderTransport}'s IMDN builder, which returns {@code null} rather than emit an
 * unsigned report. {@code RCS_SIGNATURE_PROP} is in {@code IMPLEMENTED_PROPOSALS} ({@code rcc16.rs}).
 *
 * <p><b>CORRECTED 2026-09-09.</b> This paragraph previously said the 0xF002 machinery
 * &quot;is unbuilt&quot; and that the report goes out unsigned. That stopped being true on
 * when the reason-token sweep landed, and it was stale for a month in the one place a reader
 * would look before deciding to build it. It also hid a live finding by describing the wrong state:
 * signing is what CLEARED Google Messages' {@code CANNOT_PARSE_MESSAGE(16)} wall (unsigned → 16 every
 * time; signed → 16 gone, {@code mlsGroup} populated). What remains is NOT ours — a reason-4
 * report reaches {@code UNABLE_TO_DESERIALIZE_CUSTOM_PAYLOAD(51)}, and a one-variable probe
 * (same bytes, reason 1 vs reason 4) exonerated our receipt: 51 is Google Messages' own resend-preparation
 * state failing to deserialise. Do not &quot;fix&quot; our emitter in response to it; see
 * that sweep.
 *
 * <p>The alternative this replaced, for the record, was a plaintext DELIVERED receipt for a message
 * we failed to decrypt — which tells the sender everything is fine and guarantees the message is
 * never resent.
 *
 * <p>Pure and host-tested on purpose: the peers we must interoperate with cannot be asked, so the
 * construction is recomputed from the spec text rather than round-tripped against our own decoder.
 */
public final class RccNegativeDeliveryImdn {

    private RccNegativeDeliveryImdn() {}

    /**
     * The client reason tokens — §7.7.2.3 / §7.6.3.2 for the first five, Google Messages' own
     * enum for the rest.
     *
     * <p><b>The two spellings differ for code 4</b>: the XML element is {@code <failed-to-decrypt>}
     * while the binary enum constant is {@code failure_to_decrypt}. Both live here so the mismatch
     * cannot be reintroduced — picking the wrong one produces a report the peer silently ignores,
     * which is the failure class that has cost this project the most time.
     *
     * <p><b>The spec's five are not the whole set, and not all of them are failures.</b> Google Messages
     * carries thirteen, and six of those report an OUTCOME rather than a problem —
     * "your commit was processed during my self-heal", "your commit failed and I era-advanced". The
     * client arm is a diagnostic channel, not only a failure flag.
     *
     * <p>That distinction is safety-critical here: applying a failure remedy to an outcome token
     * means <b>resending a message the peer already told us it recovered</b>. {@link #isFailure()}
     * is what callers must branch on, never the mere presence of a reason.
     *
     * <p>Only codes 1–5 come from §7.6.3.2. The rest have no spec binary encoding — the values below
     * are OURS, for crossing the AIDL, and must not be mistaken for wire numbers.
     */
    public enum Reason {
        /** §7.7.2.3: the message came from a user who was not a member of the MLS group. */
        MESSAGE_FROM_NON_MEMBER(1, "message-from-non-member"),
        /**
         * §6.2: the commit carried an invalid credential. Remedy is to fetch a fresh KeyPackage for
         * the participant and replace their leaf node (§9.5.4).
         */
        INVALID_CREDENTIAL(2, "invalid-credential"),
        /** §6.2: the commit failed validation. Remedy is to fix the errors and try again. */
        INVALID_COMMIT(3, "invalid-commit"),
        /**
         * §6.2: the recipient could not decrypt. Remedy, stated by the spec rather than inferred:
         * "The sender shall advance to the latest epoch and resend the message."
         *
         * <p>Binary spelling is {@code failure_to_decrypt(4)}; XML is {@code failed-to-decrypt}.
         */
        FAILED_TO_DECRYPT(4, "failed-to-decrypt"),
        /**
         * §6.2: a commit arrived inside a PrivateMessage instead of a PublicMessage. Remedy is to
         * resend the commit in a PublicMessage.
         */
        COMMIT_IN_PRIVATEMESSAGE(5, "commit-in-privatemessage"),

        // ---- Google Messages' additional FAILURE tokens (no §7.6.3.2 code; ours are local) ----
        /** A control message could not be processed at all. */
        CONTROL_MESSAGE_FAILED(13, "control-message-failed", true),
        /**
         * A §10.3 ResentMessage STILL failed to decrypt. This is the IMDN that flags a broken resend
         * leg — including a ResentMessage whose HMAC we got wrong, which is the failure mode we
         * deliberately did not guess at.
         *
         * <p><b>DO NOT EMIT THIS TO A GOOGLE MESSAGES PEER — confirmed on a device.</b> The
         * spec defines code 6, but Google Messages' own client enum does NOT: sending it made
         * Google Messages throw before any processing —
         * <i>"Unknown client failure reason 'RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT' /
         * java.lang.IllegalArgumentException: No enum constant
         * …RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT"</i>
         * — and the whole receipt was DROPPED, which is strictly worse than reporting the underlying
         * failure. Google Messages' client arm is {1,2,3,4,5,7,8,9,10,11,12,13}. The constant stays because
         * we must still PARSE it (a spec-conformant peer may send it, and it is a real outcome we act
         * on); {@link #emittable()} is what gates the send side.
         */
        RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT(6, "resent-message-for-me-failed-to-decrypt", true),

        // ---- OUTCOME tokens: informational. Applying a remedy to these is a bug. ----
        /** The peer recovered our commit during its self-heal. Nothing to do. */
        COMMIT_PROCESSED_IN_SELF_HEAL(8, "commit-processed-in-self-heal", false),
        /** As above, via enhanced (commit-replay) self-heal. Nothing to do. */
        COMMIT_PROCESSED_IN_ENHANCED_SELF_HEAL(7, "commit-processed-in-enhanced-self-heal", false),
        /** The peer recovered our proposal during its self-heal. Nothing to do. */
        PROPOSAL_PROCESSED_IN_SELF_HEAL(12, "proposal-processed-in-self-heal", false),
        /** As above, via enhanced self-heal. Nothing to do. */
        PROPOSAL_PROCESSED_IN_ENHANCED_SELF_HEAL(11, "proposal-processed-in-enhanced-self-heal", false),
        /**
         * A commit failed and the peer's remedy was an ERA ADVANCE — Google Messages'
         * {@code ClientFailureCommitFailedThenEraAdvancement}. Reports what the peer already did, so
         * it is informational: escalating again on top of it would advance the era twice.
         */
        COMMIT_FAILED_THEN_ERA_ADVANCEMENT(9, "commit-failed-then-era-advancement", false),
        /**
         * A control message failed because the conversation had DOWNGRADED out of MLS. Terminal, and
         * not repairable by resending or healing into a group that is no longer encrypted.
         */
        CONTROL_MESSAGE_FAILED_DUE_TO_DOWNGRADE(10, "control-message-failed-due-to-downgrade", false);

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

        /**
         * True if this token reports a PROBLEM needing a remedy; false if it reports an OUTCOME the
         * peer is merely informing us of.
         *
         * <p>Branch on this, not on "a reason was present". An outcome token means the peer already
         * handled it — resending or escalating on top would duplicate work the peer has done, and in
         * the era-advancement case would advance the era a second time.
         */
        public boolean isFailure() {
            return mIsFailure;
        }

        /** The §7.6.3.2 binary enum value. This is what crosses the AIDL. */
        public int code() {
            return mCode;
        }

        /**
         * Whether this token may be SENT to a peer (device-proven 2026-08-09).
         *
         * <p>Parse-vs-emit are deliberately asymmetric. Every token here is PARSEABLE — a
         * spec-conformant peer may send any of them and we must act on what we are told. But
         * {@link #RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT} (code 6) is absent from Google Messages' own
         * client enum, and sending it makes Google Messages throw and DROP the entire receipt, losing the
         * report altogether. Reporting the underlying {@link #FAILED_TO_DECRYPT} instead is strictly
         * better: it is true (the resent message did fail to decrypt) and it is a token the peer acts
         * on. Callers on the send path must consult this rather than the enum's mere existence.
         */
        public boolean emittable() {
            return this != RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT;
        }

        /** The token to actually send for this reason — itself, or the closest emittable truth. */
        public Reason forEmit() {
            return emittable() ? this : FAILED_TO_DECRYPT;
        }

        /**
         * Whether a receipt carrying this reason cleans up the cached outbound ciphertext (§12.8).
         *
         * <p><b>Always false, for every client reason.</b> Stated as a method rather than left
         * implicit because the opposite is the intuitive thing to write and we wrote it: the
         * decrypt-failure arm invalidated the cache and re-encrypted, which burns a sender-ratchet
         * generation on the one arm Google Messages leaves alone.
         *
         * <p>The reason it is false is not arbitrary. A client saying "I could not decrypt this" is
         * making a claim about ITS state, not about our bytes — the ciphertext is still exactly what
         * we sent and is still what a retry of that message must replay. The remedy for a client
         * failure is a RESEND, which §11.1 makes a new message with a new id and its own cache entry;
         * it never touches the original's. Compare {@link ServerReason#cleansCache()}, where the
         * server is making a claim about our bytes and they really are unusable.
         */
        public boolean cleansCache() {
            return false;
        }

        /** The §7.7.2.3 XML element name. */
        public String xmlElement() {
            return mXml;
        }

        /** Resolve a §7.6.3.2 code, or null for 0/unknown. */
        public static Reason fromCode(final int code) {
            for (final Reason r : values()) {
                if (r.mCode == code) {
                    return r;
                }
            }
            return null;
        }

        /** Resolve a §7.7.2.3 element name, or null if it is not one of the five. */
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
     * Build the §7.7.2.3 negative-delivery IMDN XML.
     *
     * @param originalMessageId the message that failed, per RFC 5438 {@code <message-id>}
     * @param reason            one of the five §7.7.2.3 reasons
     * @param iso8601Datetime   the RFC 5438 {@code <datetime>}; passed in rather than read from the
     *                          clock so the construction is deterministic and testable
     */
    /**
     * The §12.2 ARM RULE, as an invariant (rework 10.1).
     *
     * <blockquote>A failure-reason arm is legal <b>iff</b> {@code type == DELIVERY ∧ status ==
     * "failed" ∧ encryptedData absent}, and then <b>at most one</b> arm may be set. Zero arms is
     * legal.</blockquote>
     *
     * <p>The third conjunct is the one that surprises: <b>the arms are illegal on an ENCRYPTED failed
     * delivery receipt.</b> The failure-reason IMDN is plaintext XML authenticated by a CPIM header,
     * not an encrypted IMDN — so a well-meaning "encrypt everything" change to this path produces a
     * receipt a Google Messages peer refuses, and refuses for a reason that has nothing to do with the
     * failure being reported.
     *
     * <p>Zero arms being legal is why this is a checker rather than a requirement: a plain
     * {@code <failed/>} with no reason is a valid receipt.
     *
     * @throws IllegalStateException with Google Messages' verbatim text, so a trace diff matches it
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

    public static String build(final String originalMessageId, final Reason reason,
            final String iso8601Datetime) {
        if (originalMessageId == null || originalMessageId.isEmpty()) {
            throw new IllegalArgumentException("originalMessageId is required by RFC 5438");
        }
        if (reason == null) {
            // §7.7.2.2 permits the report only for a defined reason and forbids it otherwise, so a
            // reasonless negative IMDN is not a thing we are allowed to emit.
            throw new IllegalArgumentException("RCC.16 §7.7.2.2 requires a failure reason");
        }
        // We emit DELIVERY + failed + unencrypted with exactly the CLIENT arm, so this always
        // passes today. It is asserted rather than assumed because all three conjuncts are things a
        // later change could flip without noticing — encrypting this receipt would be the easiest.
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
     * The eleven SERVER failure reasons of RCC.16 §7.7.2.3 (§7.7.2.1 generates them).
     *
     * <p>These arrive in the same {@code <failed>} arm as the client reasons but under
     * {@code <mls-server-failure-reason>}, and they are the messaging server telling us why IT
     * rejected something — which is information we get nowhere else. Device-observed 2026-07-29: a
     * refused rekey came back as {@code <incorrect-era/>} referencing the control message id, at a
     * moment when our local era was ahead of the server's.
     *
     * <p>That matters beyond decoding: we had concluded a failed era operation gives us "no error to
     * branch on" because the RPC returns success. It does — but the reason arrives **out of band, as
     * an IMDN**, some seconds later.
     *
     * <p>Codes are offset by {@link #SERVER_BASE} so a server reason can never be mistaken for a
     * client one across the AIDL. There is no binary enum for these in the spec; the offset is ours.
     */
    public enum ServerReason {
        // ---- the eleven in the RCC.16 §7.7.2.3 schema ----
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

        // ---- five more that Google Messages carries but the published schema does not list ----
        //
        // ⚠ THESE ARE PROTO NUMBERS, NOT DECLARATION ORDER, AND THE TWO DIVERGE AT 15/16.
        //
        // THE RULE, confirmed against both sources: Google Messages keeps TWO enums over this one
        // concept, in DIFFERENT orders. Use **the proto mirror for VALUES** and **the XML-token enum
        // for WIRE TOKENS**, and never infer one from the other's position. They agree through 14
        // and then:
        //
        //     proto (declared ordinals)              XML tokens (declaration order)
        //     15  MLS_GROUP_HAS_END_MLS              15  epoch-advancement-quota-reached
        //     16  EPOCH_ADVANCEMENT_QUOTA_REACHED    16  mls-group-has-end-mls
        //
        // So on the wire 15 IS mls-group-has-end-mls, despite being declared LAST in the token enum
        // — which
        // is why these codes are explicit rather than ordinal()-derived, and why
        // MlsFailureReasonCodeTest.serverCodesMatchTheReferenceClient pins them line by line.
        //
        // The pin has now earned its keep twice. An earlier session found the inversion and fixed it;
        // on 2026-08-03 I was handed a declaration-ordered listing, read the line numbers as ordinals,
        // swapped these two back — and the test failed inside a minute. If a future source states
        // these in declaration order, do NOT renumber from it.
        /** The server has no such MLS group. Do NOT self-heal or resend INTO it — it is gone. */
        MLS_GROUP_NOT_FOUND(12, "mls-group-not-found"),
        /** Malformed request. Ours to fix; retrying the same bytes cannot help. */
        INVALID_INPUT(13, "invalid-input"),
        /**
         * ERA advancement quota reached — the named wire form of what we spent this session chasing
         * as a silent no-move. If this arrives after an advance the RPC "accepted", it is the answer.
         */
        ERA_ADVANCEMENT_QUOTA_REACHED(14, "era-advancement-quota-reached"),
        /** EPOCH advancement quota reached — the same, for epoch commits. */
        EPOCH_ADVANCEMENT_QUOTA_REACHED(16, "epoch-advancement-quota-reached"),
        /**
         * The group carries {@code end_mls} — it has been downgraded out of encryption. Terminal for
         * MLS: healing or advancing into it is meaningless.
         */
        MLS_GROUP_HAS_END_MLS(15, "mls-group-has-end-mls");

        private final int mLocalCode;
        private final String mXml;

        ServerReason(final int localCode, final String xml) {
            mLocalCode = localCode;
            mXml = xml;
        }

        /** The §7.7.2.3 XML element name. */
        public String xmlElement() {
            return mXml;
        }

        /**
         * AIDL-safe code, disjoint from the client reasons by {@link #SERVER_BASE}.
         *
         * <p>EXPLICIT, not {@code ordinal()}-derived — deliberately. These started as eleven and
         * became sixteen within a day; an ordinal-based code silently renumbers every token after any
         * insertion, which would make an old client read a NEW meaning from an OLD number.
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
         * Whether a receipt carrying this reason CLEANS UP the cached outbound ciphertext (§12.8).
         *
         * <p>The rule lives here rather than at the call site because it is a property of the reason,
         * and because its two halves are easy to implement separately and get half-right — which is
         * what happened: the skip-list was honoured and the clean-up was never written, so no server
         * rejection ever cleared anything while the CLIENT arm cleared on every decrypt failure.
         * Exactly inverted.
         *
         * <p>Google Messages' skip-list is {@code {SERVER_FAILURE_UNSET, SERVER_FAILURE_TRANSIENT_ERROR}}.
         * {@code UNSET} has no constant here on purpose — it is the absent/unrecognised case, which
         * {@link #fromCode} already reports as {@code null}, so a caller that has a {@code
         * ServerReason} at all has already excluded it. Every other reason clears, including both
         * quota reasons and the ones that read as informational.
         *
         * <p>{@code transient-error} is the single reason Google Messages treats as "nothing is wrong with
         * our state, just retry" — and it is precisely because nothing is wrong that the cached bytes
         * stay valid and must be REPLAYED rather than re-encrypted (invariant 62).
         */
        public boolean cleansCache() {
            return this != TRANSIENT_ERROR;
        }

        /**
         * What a caller should DO about this reason — the classification that makes the remedy
         * dispatch exhaustive instead of accumulated.
         *
         * <p>Before this, eleven of the sixteen reasons had an arm and five fell to a
         * {@code default} that logged "no remedy implemented for this reason yet". That default was
         * honest, but it meant a new token silently joined the unhandled set, and "which reasons do
         * we actually act on" could only be answered by reading a switch. Now every reason names its
         * disposition here, one test asserts the mapping is total, and adding a token is a compile
         * -unit edit that a reader cannot forget.
         *
         * <p><b>{@link Disposition#NONE} is a decision, not a gap.</b> Several reasons genuinely have
         * no safe automatic remedy, and saying so explicitly is the point — it is different from
         * having been overlooked.
         */
        public Disposition disposition() {
            switch (this) {
                // OUR VIEW OF THE GROUP DISAGREES WITH THE SERVER'S. Repairing our own state is the
                // answer; resending the same bytes cannot be. mismatched-confirmation-tag joins the
                // three obvious ones because a confirmation tag is DERIVED from group state — a
                // mismatch is a divergence report wearing different words.
                case INCORRECT_ERA:
                case INCORRECT_EPOCH:
                case INCORRECT_EPOCH_AUTHENTICATOR:
                case MISMATCHED_RCS_GROUP_STATE:
                case MISMATCHED_CONFIRMATION_TAG:
                    return Disposition.SELF_HEAL;

                // Nothing is wrong with our state; the send may simply be retried. This is the ONLY
                // reason Google Messages keeps out of the §12.8 cache clean-up, which is the same statement
                // from the other side: the cached bytes are still valid.
                case TRANSIENT_ERROR:
                    return Disposition.RETRYABLE;

                // The conversation is gone or is no longer encrypted. Both remedies would make it
                // worse — self-healing re-creates state the server does not have, resending pushes
                // ciphertext into a conversation that is not encrypted.
                case MLS_GROUP_NOT_FOUND:
                case MLS_GROUP_HAS_END_MLS:
                    return Disposition.TERMINAL;

                // THE TWO QUOTAS ARE SEPARATE ALLOWANCES AND GOOGLE MESSAGES TREATS THEM DIFFERENTLY.
                //
                // Epoch spent -> advance the ERA instead. Era spent -> stop being an MLS group.
                // From Google Messages' own per-server-reason remedy switch:
                //     EPOCH_ADVANCEMENT_QUOTA_REACHED -> b(allowRetry=true, ERA_ADVANCEMENT_REQUESTED)
                //     ERA_ADVANCEMENT_QUOTA_REACHED   -> b(allowRetry=true, END_MLS_REQUESTED)
                //
                // We used to return OUT_OF_QUOTA for both and stop, which threw away a repair
                // Google Messages still had budget for.
                case EPOCH_ADVANCEMENT_QUOTA_REACHED:
                    return Disposition.ESCALATE_TO_ERA;

                // A quota is not cleared by consuming more of it, and for the ERA quota there is no
                // higher rung to escalate to — Google Messages' remedy here is END_MLS_REQUESTED, i.e. give
                // up on MLS for this conversation and downgrade. That IS the documented terminal
                // (it answers the predicate our own terminateRepairIfQuotaBound javadoc recorded as
                // unread), but dropping a conversation out of encryption stays gated behind
                // MlsConfig.downgradeOnRepairExhausted rather than becoming automatic here.
                case ERA_ADVANCEMENT_QUOTA_REACHED:
                    return Disposition.OUT_OF_QUOTA;

                // Malformed on OUR side. Retrying the same bytes cannot help; it needs a code fix.
                // unparsable-commit is the commit-shaped sibling of invalid-input: the server could
                // not PARSE what we sent, which is a bytes problem, not a state problem — so it is
                // deliberately NOT self-heal.
                case INVALID_INPUT:
                case UNPARSABLE_COMMIT:
                    return Disposition.OURS_TO_FIX;

                // The credential we presented is expired: a fresh identity/KeyPackage is needed.
                case EXPIRED_CREDENTIAL:
                    return Disposition.REFRESH_IDENTITY;

                // A proposal is cached by reference and must be committed before anything else will
                // be accepted. We have a real mechanism for this, so it gets one rather than a
                // shrug — and it is NOT self-heal, which would advance past the proposal instead of
                // honouring it.
                case PENDING_PROPOSAL:
                    return Disposition.COMMIT_PENDING_PROPOSALS;

                // NO SAFE AUTOMATIC REMEDY, and these are the two where saying so is the honest
                // answer rather than a placeholder:
                //
                //  - invalid-commit (SERVER arm): the server rejected a commit as invalid. It could
                //    be our state or our bytes, and the two want opposite remedies — self-healing on
                //    a bytes bug loops, and treating a state divergence as a code bug strands the
                //    conversation. Nothing on the wire distinguishes them. Needs a captured example.
                //  - encryption-not-available: the server says E2EE is not available here. The
                //    RCC.16 answer is a downgrade, and this project does not downgrade a conversation
                //    out of encryption automatically — that is a user-visible security change.
                case INVALID_COMMIT:
                case ENCRYPTION_NOT_AVAILABLE:
                    return Disposition.NONE;
            }
            // Unreachable for a real constant; a new token that reaches here is a bug the
            // totality test below catches before it ships.
            return Disposition.NONE;
        }
    }

    /**
     * The remedy class for a {@link ServerReason} — see {@link ServerReason#disposition()}.
     *
     * <p>Deliberately a CLASS of remedy rather than a callback: the engine knows what a reason
     * means, and the transport knows how to carry it out. Putting the verb here would drag group
     * resolution, locking and server round-trips into a package that has none of them.
     */
    public enum Disposition {
        /** Our state disagrees with the server's — repair ourselves, do not resend. */
        SELF_HEAL,
        /** Nothing is wrong; the send may be retried with the same bytes. */
        RETRYABLE,
        /** The conversation is gone or downgraded — neither heal nor resend. */
        TERMINAL,
        /** Out of ERA advancement quota — retrying consumes more of it. */
        OUT_OF_QUOTA,
        /**
         * The EPOCH advancement quota is spent, but the ERA one may not be — advance the era.
         *
         * <p>Google Messages escalates rather than giving up: {@code EPOCH_ADVANCEMENT_QUOTA_REACHED ->
         * b(allowRetry=true, ERA_ADVANCEMENT_REQUESTED)} in its per-server-reason remedy switch,
         * with the same values on every build checked. We previously lumped both quotas into
         * OUT_OF_QUOTA and stopped, which gave up a repair Google Messages still had budget for —
         * the two quotas are
         * separate allowances.
         */
        ESCALATE_TO_ERA,
        /** Malformed on our side; needs a code fix, not a retry. */
        OURS_TO_FIX,
        /** The credential expired — a fresh identity/KeyPackage is needed. */
        REFRESH_IDENTITY,
        /** A by-reference proposal must be committed before anything else is accepted. */
        COMMIT_PENDING_PROPOSALS,
        /** No safe automatic remedy. A decision, not an oversight — see the reason's javadoc. */
        NONE,
    }

    /**
     * Offset separating server reason codes from the five client ones.
     *
     * <p>{@code invalid-commit} exists in BOTH lists with different meanings — the client one means
     * "I could not validate your commit", the server one means "the messaging server rejected it" —
     * so a shared code space would silently conflate two different failures with two different
     * remedies.
     */
    /**
     * The report said {@code <failed/>} and gave NO reason — Google Messages' actual wire shape.
     *
     * <p>Numbered ABOVE the server range rather than inside it because it is neither a client nor a
     * server reason: it is the absence of both. Kept distinct from 0 (an element we did not
     * recognise) so a remedy can act on one and not the other.
     */
    public static final int NO_REASON_GIVEN = 200;

    public static final int SERVER_BASE = 100;

    /** What {@link #parse} recovered from an inbound IMDN. */
    public static final class Parsed {
        /** The {@code <message-id>} the report refers to — one of OUR messages. */
        public final String messageId;
        /** The reason, or null when the report carried none we recognise. */
        public final Reason reason;
        /** The raw reason element name, kept even when unrecognised so it can be logged. */
        public final String rawReasonElement;
        /** Set instead of {@link #reason} when the report carried an mls-SERVER-failure-reason. */
        public final ServerReason serverReason;

        Parsed(final String messageId, final Reason reason, final String rawReasonElement,
                final ServerReason serverReason) {
            this.messageId = messageId;
            this.reason = reason;
            this.rawReasonElement = rawReasonElement;
            this.serverReason = serverReason;
        }

        /**
         * The AIDL code for whichever reason this carried: a client code (1..5), a server code
         * ({@link #SERVER_BASE}+), or 0 when neither was recognised.
         */
        public int code() {
            if (reason != null) return reason.code();
            if (serverReason != null) return serverReason.code();
            // NO REASON ELEMENT AT ALL — which is what Google Messages sends. Captured from a
            // real Google Messages negative receipt, in full:
            //
            //   <imdn xmlns="urn:ietf:params:xml:ns:imdn">
            //     <message-id>…</message-id><datetime>…</datetime>
            //     <delivery-notification><status><failed/></status></delivery-notification>
            //   </imdn>
            //
            // That is the whole document. No mls-client-failure-reason, no MLS headers, no
            // signature, and sent unencrypted ("Sending unencrypted negative delivery receipt",
            // "SendRcsReportMlsSigningInterceptor skipping").
            //
            // CORRECTED, same day: this is ONE of Google Messages' two shapes, not its only one. Google Messages
            // also has a SIGNED path (sentOutSignedNegativeReceipt) that DOES write
            // <mls-client-failure-reason>, and SendRcsReportMlsSigningInterceptor routes between
            // them — it signs when the message supports MLS encryption and carries the CPIM header,
            // and otherwise falls through to this bare shape. A message it could not DECRYPT gives
            // it nothing to sign over, which is why a decrypt failure always produces the reasonless
            // form. The reason vocabulary is therefore alive and the remedy table stays; what this
            // constant handles is the (common) reasonless case, not "Google Messages never sends a reason".
            //
            // This MUST NOT collapse to 0 with "unrecognised element". They are different events
            // and they deserve different handling: an unrecognised element is an anomaly we should
            // not act on, while an ABSENT one is the single most common negative receipt on the
            // network and refusing to act on it means we can never respond to a Google Messages peer's
            // failure at all.
            if (rawReasonElement == null) return NO_REASON_GIVEN;
            return 0;
        }
    }

    /**
     * Parse an inbound IMDN, returning non-null only for a NEGATIVE-delivery report.
     *
     * <p>Returns null for a normal delivered/displayed receipt and for anything unparsable — the
     * caller's positive-receipt path stays untouched. Deliberately lenient about whitespace and
     * self-closing forms, since the peers sending these are other vendors' clients (Apple implements
     * RCC.16, so these may already have been arriving and being discarded).
     *
     * <p>Regex rather than a DOM parse, matching how this file's siblings read IMDN XML: the grammar
     * is fixed by the schema and a parser would need the whole namespace apparatus for two fields.
     */
    public static Parsed parse(final String xml) {
        if (xml == null || xml.isEmpty()) {
            return null;
        }
        // A <failed> status is what distinguishes this from a receipt. Bare "<failed" so both
        // <failed/> and <failed> match.
        if (!xml.contains("<failed")) {
            return null;
        }
        final java.util.regex.Matcher mid = java.util.regex.Pattern
                .compile("<message-id>\\s*([^<\\s]+)\\s*</message-id>").matcher(xml);
        if (!mid.find()) {
            // RFC 5438 requires the message-id; without it there is nothing actionable to report
            // against, so this is not usable even though it parsed.
            return null;
        }
        final java.util.regex.Matcher rm = java.util.regex.Pattern
                .compile("<mls-client-failure-reason>\\s*<([A-Za-z-]+)\\s*/?>").matcher(xml);
        final String rawEl = rm.find() ? rm.group(1) : null;
        // The same <failed> arm can instead carry an mls-SERVER-failure-reason (§7.7.2.1). Those are
        // the messaging server's own rejections — the only place it tells us WHY — so decode them
        // rather than reporting "unknown" and guessing a remedy.
        final java.util.regex.Matcher sm = java.util.regex.Pattern
                .compile("<mls-server-failure-reason>\\s*<([A-Za-z-]+)\\s*/?>").matcher(xml);
        final String rawSrv = sm.find() ? sm.group(1) : null;
        // THE ARM RULE ON THE WAY IN (§12.2, rework 10.1). Both arms set is illegal, and the
        // previous behaviour silently preferred the client one — which picks the WRONG remedy half
        // the time, because the two vocabularies mean different things and only one of them is
        // about our bytes. Refuse the receipt instead of guessing which half the sender meant.
        if (rawEl != null && rawSrv != null) {
            // REJECT, do not throw. Google Messages' three arm-rule exceptions are EMIT-side; this input is
            // peer-controlled, and throwing on peer-controlled bytes hands a remote party a way to
            // kill whatever thread is draining inbound. Returning null drops this one receipt, which
            // is the same outcome the throw would have produced minus the collateral.
            return null;
        }
        return new Parsed(mid.group(1), Reason.fromXmlElement(rawEl),
                rawEl != null ? rawEl : rawSrv, ServerReason.fromXmlElement(rawSrv));
    }
}

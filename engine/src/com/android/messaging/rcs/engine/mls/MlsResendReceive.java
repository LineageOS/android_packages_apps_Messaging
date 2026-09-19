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

import java.util.Map;

/**
 * RCC.16 §11.3a — <b>the receiver's ordering</b> for a §10.3 resent message, as one auditable
 * decision.
 *
 * <h2>Why this is a class and not four lines at the call site</h2>
 *
 * <p>{@link MlsResentMessage} already holds the wire details — the 64-byte field, the key in the
 * clear, the MAC. What it cannot hold is the ORDER those checks run in and WHAT EACH OUTCOME IS
 * ALLOWED TO DO, and that is the half this class exists for: the order is the correctness property,
 * and it lived in {@code MlsProviderTransport.decryptInbound}, which no host test can reach.
 *
 * <p>It did not survive there. Between 2026-08-08 and this class, the inbound path carried BOTH a
 * correct selector and, twenty lines above it, an earlier guard that returned "did not decrypt" for
 * <em>any</em> present component — so the selector was unreachable and every group resend took the
 * §10 recovery path instead. Two blocks that each read as careful, in the wrong order. That is
 * precisely the failure a pure, host-tested ordering function makes impossible to reintroduce
 * silently.
 *
 * <h2>THE ORDERING — and it is fixed</h2>
 *
 * <ol>
 *   <li>parse the AAD;</li>
 *   <li>find the resent-message sub-structure — absent means this is an ORDINARY message;</li>
 *   <li>check the HMAC <b>length</b>;</li>
 *   <li>evaluate the HMAC — <b>a mismatch is a SILENT DROP, not a failure</b>: you are not the
 *       target;</li>
 *   <li>only then decrypt the INNER WRAPPED ciphertext, and only <em>that</em> failure reports.</li>
 * </ol>
 *
 * <p>Evaluating the MAC after attempting the unwrap, or treating a mismatch as a decrypt failure,
 * produces a receipt from EVERY non-target member.
 *
 * <h2>THE SILENT-DROP RULE — and what is NOT proven about it</h2>
 *
 * <p><b>ONE thing here is established, and it is not a number.</b> NON-ESCALATION: a member the
 * resend is not for logs "not for me" and <b>does nothing else</b> — no negative IMDN, no FTD, no
 * {@code ClientFailure}, no health-status request, no state change. Google Messages has no variant
 * for the not-for-me outcome in any of the four enums that could carry one, so it <em>cannot</em>
 * escalate it even if it wanted to. That is a claim about what the enum SPACE contains, and
 * enumerating that space is the right instrument for it.
 *
 * <p><b>AND IT IS SCOPED TO THE BUILDS IT WAS CHECKED AGAINST, WHICH MATTERS BECAUSE WE INTEROP
 * ACROSS BUILDS.</b> Google Messages ships obfuscated, and its short class names rotate onto
 * unrelated classes between releases, so an enum inventory taken on one build does not carry to the
 * next — a rotated name resolves to something else that looks like a real answer. Non-escalation is
 * established on the builds it was checked against and on no other. The honest falsifier: a newer
 * Google Messages observed escalating a not-for-me resend, which nothing currently rules out.
 *
 * <p><b>The direction of an error in those inventories matters, and it differs between the two
 * claims they support.</b> If a member list is a SUPERSET of the real enum (variants merely
 * discussed padding the ones that exist), an ABSENCE still holds — so non-escalation is safe under
 * that error. If it is a SUBSET, absence is unsafe, and so is the uniqueness claim below: <b>"there
 * is exactly one resend-reporting variant" needs an EXACT member list</b>, because an unlisted
 * variant could report too. A subset is the dangerous error for uniqueness and the safe one for
 * absence; a superset is the reverse.
 *
 * <p><b>EXACTLY ONE RECEIPT <em>KIND</em> EXISTS — which is not the same as one receipt per resend,
 * and this class doc asserted the second on evidence that only supports the first.</b>
 * {@code CLIENT_FAILURE_RESENT_MESSAGE_FOR_ME_FTD} is the only resend outcome with a reporting
 * variant in Google Messages' client enum. That establishes the variant EXISTS and WHERE IT IS
 * REACHED FROM. <b>How many members reach it is an execution fact, and no structural evidence can
 * settle one.</b> "At most one receipt per resend" requires that exactly one member takes the
 * FOR_ME arm — the same unproven partition the "N-1" count below rests on.
 *
 * <p><b>THE RULE, stated so it generalises: A COUNT OF THINGS IN THE BINARY IS STRUCTURAL AND
 * PROVABLE; A COUNT OF RUNTIME OCCURRENCES IS NOT.</b> Structure proves a thing EXISTS and where it
 * is reached FROM; it cannot prove HOW MANY TIMES IT RUNS.
 *
 * <p><b>Apply it with the discriminator, not as a blanket distrust of numbers</b> — that was an
 * over-correction and it is worth naming, because it would have flagged everything and a marker that
 * flags everything stops being read. Ask what the count RANGES OVER. <em>How many call sites,
 * variants, exports or offsets EXIST</em> is a claim about what the artefact CONTAINS, and a static
 * enumeration is exactly the right instrument — "exactly one call site" is fine.
 * <em>How many times something RUNS, or how many participants REACH it</em>, is not — "fires exactly
 * once per message" is not fine. The two claims withdrawn above were the only EXECUTION counts in
 * this material; the defect was SPECIFIC, not systemic.
 *
 * <p><b>THE "N-1" COUNT IS NOT PART OF THAT PROOF, AND USED TO BE WRITTEN AS IF IT WERE.</b> This
 * section used to open "in a group of N, one resend causes N-1 members to log 'not for me' … Seeing
 * N-1 of these per resend is CORRECT", with the enumeration immediately below it. <b>The
 * enumeration shows something else entirely</b> — that the outcome cannot escalate. <b>It does not
 * say how many members hit the branch.</b> The count was an inference that inherited the
 * enumeration's standing by proximity and was then cited as evidence for a month. <b>Nobody has
 * ever counted those log lines on a real group.</b>
 *
 * <p>An earlier correction of this paragraph said the enumeration established "two other things —
 * non-escalation AND at-most-one-receipt", and kept the second. <b>That was the same error one
 * level up</b>: at most one receipt is also a count, and it also rested on structure. One narrowing
 * does not guarantee the narrowed version is itself in scope.
 *
 * <p><b>FALSIFIER for the count, and it is cheap and has never been run:</b> one resend in a
 * 3-member group must produce exactly two {@code "Resent message not for me …"} lines, one in each
 * non-target member's logcat. Any other number overturns it. Two ways it can come out wrong and
 * both are live: the u32 that disambiguates an HMAC failure (<b>field A</b>) may not partition the
 * group one-to-N-1 — <b>what the local value it is compared against holds is itself an open
 * item</b>, see the WARNING below — and a member that never reaches the branch logs nothing at
 * all.
 *
 * <p><b>Nothing in this class depends on the count</b>, which is why narrowing it costs nothing:
 * {@link #evaluate} decides one member's disposition from one message and never counts anybody. Do
 * not restore "N-1" to a proven claim on the strength of the enumeration below; it is not what the
 * enumeration is about.
 *
 * <p><b>The disposition survives the 2026-09-10 MECHANISM correction.</b> The not-for-me branch used
 * to be attributed to MAC mismatches — "the member whose HMAC verified". It is not: the MAC is
 * computed over the ORIGINAL MESSAGE ID with a key that travels in the clear, so it PASSES for every
 * member and cannot separate anybody. <b>NEITHER DOES FIELD A</b> — its test is downstream of an
 * HMAC FAILURE — the branch that reads it is taken only on a mismatch — so on well-formed
 * traffic, where every member's HMAC verifies, it never runs. <b>Nothing in this verifier selects.</b>
 * Whatever produces the N-1 is elsewhere and is NOT identified. See {@link MlsResentMessage}'s correction
 * section for the read and for how the wrong model survived a month.
 *
 * <p><b>Why this is dangerous and not merely noisy:</b> an FTD is a registered self-heal trigger
 * cause ({@code MlsHealthStatusRequestCause.ftd_application_message_id}). Getting step 4 wrong turns
 * one resend into a receipt storm and then a SELF-HEAL STORM across the whole group — while the
 * resend still never reaches the peer that needed it.
 *
 * <h2>WHAT THE AAD COMPONENT'S OPAQUE IS — and what it is NOT</h2>
 *
 * <p><b>The component's length-prefixed opaque is NOT the 64-byte recipient-selector field.</b>
 * This class shipped believing it was, and fed {@code component.payload} straight into the MAC
 * verifier. That binding was OURS: the framing is only ever
 * {@code <tag> || <length-prefixed opaque>}, nothing states what the opaque contains, and Google
 * Messages' own engine contradicts it.
 *
 * <p>In that engine — in two independent builds, so this is not a version artefact — the 80-byte
 * struct that holds the MAC is cloned from an existing buffer <em>before</em> the component tag is
 * ever read, and the verifier reads the 64-byte field (pointer, length, and the u32 field A) out of
 * THAT clone. The struct holding the MAC therefore cannot be derived from the component payload.
 * That argument needs no callee identified, which is why it is a measurement and not a reading.
 *
 * <p>What the opaque DOES feed, also measured: it is handed to a lookup whose failure arm logs,
 * verbatim, <i>"Failed to get message content for message: &#123;:?&#125;, group: &#123;:?&#125;,
 * with error: &#123;:?&#125;"</i>. So the opaque is the key to a message-content lookup.
 * <b>Inferred, and deliberately not asserted:</b> the opaque is the ORIGINAL MESSAGE ID — the
 * authenticated twin of the outer CPIM {@code Original-Message-ID} header, i.e. a "recognise on the
 * header, BIND on the AAD" split. {@link #crossCheckOriginalMessageId} is the instrument that tests
 * that inference on live traffic instead of assuming it.
 *
 * <h2>THE TWO SEAMS — what is still not known</h2>
 *
 * <p>There are two, and they are different gaps:
 *
 * <ol>
 *   <li><b>WHERE THE 64-BYTE SELECTOR FIELD LIVES IN A RESEND.</b> It is not the component payload
 *       (above); in Google Messages it arrives inside an 80-byte struct.
 *       <b>The struct's input IS now traced — it is the AUTHENTICATED DATA, not the outer
 *       plaintext</b>. What is still unknown is the field's
 *       <b>OFFSET WITHIN</b> that AAD, and that is why the selector still cannot run:
 *       {@link #SELECTOR_UNAVAILABLE}. Knowing the source buffer is not knowing the offset.
 *
 *       <p>Evidence and its strength, because both halves matter. That the AAD is parsed before the
 *       inner decrypt rests on three agreeing signals rather than one: the two diagnostics are
 *       adjacent in the string table in every build checked; they resolve to code in the same
 *       receive function, in that order; and the engine's own source line numbers agree
 *       independently — the absent-case reject precedes the inner decrypt in source, matching the
 *       order the two appear in the code. Not a formal dominance proof, since blocks can be laid
 *       out out of order. Still an inference, unchanged: the HMAC steps' position <i>between</i>
 *       those two, which comes from the diagnostic set rather than from control flow.
 *
 *       <p>Two routes to the offset are closed or hazardous. <b>Google Messages' Java side does not
 *       parse this</b> — the native engine hands the host {@code OutgoingFtd { bytes ciphertext;
 *       GroupMember target }}, opaque bytes and a bookkeeping target, so there is no field layout
 *       on that side to read. And reconstructing the layout from a sample is not obviously sound,
 *       because {@code bugle.enable_zinnia_pad_resent_messages} is <b>still flag-gated</b> (unlike
 *       {@code validate_resent_message_hmac}, which went unconditional), so the body's LENGTH is
 *       conditional on a server-controlled flag and a fixed-layout parser breaks on the other
 *       setting.
 *
 *       <p>The {@code selectorField} parameter of {@link #evaluate} is that seam, and production
 *       passes {@code null} today.</li>
 *   <li><b>THE INNER BYTE LAYOUT</b> of the wrapped resent message — {@link InnerUnwrap}. Supply
 *       one and {@link #FOR_ME_UNWRAP_UNAVAILABLE} becomes {@link #FOR_ME}/{@link
 *       #FOR_ME_UNWRAP_FAILED} with no other change anywhere.</li>
 * </ol>
 *
 * <p><b>WARNING — SEAM 1 IS NAMED FOR THE WRONG FIELD, and supplying it will not deliver a resend
 * on its own (2026-09-10).</b> {@code resentSelectorField} is named "selector" and returns the
 * 64-byte field, which is <b>D — NOT the selector</b> (what D is FOR is open: integrity check or
 * vestigial, and neither may be asserted). Nor is the selector <b>field A</b> — its test is
 * downstream of an HMAC failure and unreachable on well-formed traffic. Field A is the
 * u32 at {@code struct+0x48}, matched against our own equivalent of {@code client_ctx+0x648}. So
 * even once the outer framing anchor is resolved, plumbing D into a for-me/not-for-me decision
 * reproduces the model we just corrected, IN CODE. What the receive path actually needs for that
 * decision is A plus the local value to compare it against — <b>and what {@code client_ctx+0x648}
 * holds is not yet known</b>, which is a NEW open item, not a solved one. D remains needed, for the
 * integrity check it really is.
 *
 * <p>Everything ABOVE those two — the ordering, the selector semantics, the silent-drop
 * disposition, the disposition table and the single reporting KIND — is independent of both, which
 * is why this class ships complete with the gaps named rather than papered over. ("Single reporting
 * KIND", not "arm": one variant exists to report with. How many members use it per resend is not
 * established.)
 *
 * <p><b>The two for-me outcomes are deliberately distinct, and conflating them would be a lie on the
 * wire.</b> "We have no unwrapper" is OUR gap; "the unwrapper rejected these bytes" is a statement
 * about the SENDER'S construction, and only the second may be reported. Emitting a
 * resent-message-failed receipt because we never implemented the unwrap would tell a healthy peer
 * its resend is broken.
 */
public final class MlsResendReceive {

    private MlsResendReceive() {}

    /**
     * What the receiver must do with this message. Exactly three groups, and they PARTITION the
     * enum — see {@link #deliver}, {@link #silentDrop}, {@link #reports}.
     */
    public enum Disposition {
        /** No resent-message component: an ordinary application message. Deliver it. */
        NOT_A_RESEND,

        /**
         * The MAC did not match any input we can compute for ourselves.
         *
         * <p><b>THE ONE THAT MATTERS.</b> Not an error and not a decrypt failure: drop it, log it,
         * and do nothing else.
         *
         * <p><b>This javadoc used to read "a RECIPIENT SELECTOR result … every non-target member
         * reaches it BY CONSTRUCTION" — the model the class doc above WITHDRAWS.</b> The MAC'd
         * datum is recipient-INVARIANT and the key
         * rides in the clear, so the MAC passes for every member and this disposition should be
         * UNREACHABLE on well-formed traffic. <b>That is an EXECUTION claim DERIVED from structure,
         * never observed</b> — we have never seen a Google Messages resend. What produces a real not-for-me
         * outcome is elsewhere and is NOT identified.
         *
         * <p><b>The DISPOSITION is untouched by the correction, and that is the point:</b> under the
         * retracted model this is the routine non-target outcome and silence is right; under the
         * corrected one reaching it means corruption or a construction we do not understand, and
         * silence is still right, because reporting would blame the sender for our own gap. See
         * {@link #silentDrop} for the reasons it does rest on.
         */
        NOT_FOR_ME,

        /** The component tag is present but its payload does not parse. Structural; still silent. */
        MALFORMED_COMPONENT,

        /** The HMAC field is empty — Google Messages takes a separate earlier path. Structural; silent. */
        EMPTY_HMAC,

        /** The HMAC field is not 64 bytes — Google Messages' 105 {@code InvalidResentMessageHmacLength}. */
        INVALID_HMAC_LENGTH,

        /**
         * The component is present and well-formed and <b>we do not know where the 64-byte
         * recipient-selector field lives in a resend</b>, so the selector cannot be evaluated.
         *
         * <p><b>OUR gap, not a statement about the sender</b> — which is the whole reason it is its
         * own disposition rather than being folded into {@link #INVALID_HMAC_LENGTH}. Until
         * 2026-09-08 this case WAS folded there, because the code fed the component's opaque into
         * the MAC verifier: a Google Messages resend carrying a 24-byte opaque came out as
         * {@code INVALID_HMAC_LENGTH} and was logged as "structurally broken rather than simply
         * not-for-us" — a claim about the sender's construction that we had not measured and that
         * would have sent the next reader hunting a defect that is ours.
         *
         * <p>Silent, like every other outcome we cannot attribute to the peer.
         */
        SELECTOR_UNAVAILABLE,

        /**
         * The MAC verified — this resend IS for us — and we hold no {@link InnerUnwrap}.
         *
         * <p>The inner-unwrap gap, and it is SILENT on purpose. We cannot tell a broken resend
         * from our own missing decoder, so reporting would blame the sender for our gap. Log it
         * loudly: reaching this at all means a candidate MAC input matched, which is the
         * measurement this whole receive path has been waiting for.
         */
        FOR_ME_UNWRAP_UNAVAILABLE,

        /**
         * The MAC verified and the inner unwrap FAILED.
         *
         * <p><b>The only disposition that may put anything on the wire</b> — Google Messages'
         * {@code ClientFailureResentMessageForMeFtd}, IMDN token
         * {@code resent-message-for-me-failed-to-decrypt}. At most one per resend, from one member.
         */
        FOR_ME_UNWRAP_FAILED,

        /** The MAC verified and the inner unwrap produced the original plaintext. Deliver it. */
        FOR_ME
    }

    /**
     * Deliver the message to the conversation.
     *
     * <p>{@link Disposition#FOR_ME} delivers {@link Outcome#inner} — the ORIGINAL plaintext — not
     * the outer bytes. The outer plaintext of a resend is the wrapper, and rendering it would put
     * the wrapper in the chat.
     */
    public static boolean deliver(final Disposition d) {
        return d == Disposition.NOT_A_RESEND || d == Disposition.FOR_ME;
    }

    /**
     * Drop with NO wire effect whatsoever: no receipt, no FTD, no §10 recovery, no health
     * transition, no ClientFailure.
     *
     * <p>Every structural outcome is here alongside {@link Disposition#NOT_FOR_ME}, and that is a
     * DELIBERATE DEVIATION worth naming. Google Messages raises engine errors 104/105 for the malformed and
     * wrong-length cases, and those sit BEFORE the selector in its ordering — so on Google Messages they are
     * reached by every member of the group, not just the target. Whether Google Messages turns either into a
     * wire receipt is not established; what IS established is that a per-member receipt for one
     * resend is the storm this whole class exists to prevent. So the safe side of an unsettled
     * question is silence, and the cost is bounded: a malformed resend is one lost message that the
     * ordinary FTD path will surface again if it matters.
     */
    public static boolean silentDrop(final Disposition d) {
        return d == Disposition.NOT_FOR_ME
                || d == Disposition.MALFORMED_COMPONENT
                || d == Disposition.EMPTY_HMAC
                || d == Disposition.INVALID_HMAC_LENGTH
                || d == Disposition.SELECTOR_UNAVAILABLE
                || d == Disposition.FOR_ME_UNWRAP_UNAVAILABLE;
    }

    /**
     * May emit a §7.7.2.2 negative receipt.
     *
     * <p><b>Exactly one disposition, and a test pins that count.</b> That count is about THIS
     * ENUM — a property of our own code, which a host test can and does check. If it ever returns
     * true for a second disposition, OUR receive path can emit more than one receipt per resend.
     *
     * <p><b>It says nothing about Google Messages, and the two used to be conflated here.</b>
     * "Google Messages emits at most one receipt per resend" is an EXECUTION claim that was
     * withdrawn with the "N-1" count — what is established is that exactly one receipt KIND
     * exists in its client enum, not
     * that one member uses it. Our own single-disposition property is a design decision we enforce,
     * and it needs no evidence about Google Messages at all.
     *
     * <p>NOTE the token to actually send is {@code Reason.RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT
     * .forEmit()}, which is {@code failed-to-decrypt(4)}: code 6 is absent from Google Messages'
     * client enum and sending it makes Google Messages drop the whole receipt — observed on a
     * device.
     */
    public static boolean reports(final Disposition d) {
        return d == Disposition.FOR_ME_UNWRAP_FAILED;
    }

    /**
     * SEAM 2 of 2 — unwrap the inner wrapped resent message.
     *
     * <p>Its unknown is the inner byte layout. NONE of the ordering above depends on it, which is
     * why the rest of this class could be built without it. In Google Messages the failure of this
     * step is {@code "Failed to decrypt inner wrapped resent message"}; the surrounding module
     * contains an AES-CTR + HMAC construction (with an HKDF salt, Padmé padding and a suffix format
     * among its error variants) but <b>we have NOT established that this path uses it</b> and do
     * not assert it.
     *
     * <p>What is already known and does NOT need re-deriving, so an implementer starts here rather
     * than at the beginning:
     * <ul>
     *   <li>the HMAC field is 64 bytes: {@code [0..32)} is the MAC KEY <em>in the clear</em>,
     *       {@code [32..64)} is the tag ({@link MlsResentMessage});</li>
     *   <li>there is no HKDF and no derivation label — the primitive is
     *       {@code mac(key, data) -> [u8; 32]}, which has nowhere for one to go;</li>
     *   <li>Google Messages' failure string is "Failed to decrypt inner wrapped resent message" — a
     *       DIFFERENT error from the "does not contain a resent message" that a wrong TAG produces
     *       and from the parse error a wrong PREFIX WIDTH produces. Those three are distinguishable
     *       on a Google Messages peer's log, which is what makes the remaining questions settleable by
     *       experiment;</li>
     *   <li>Google Messages NESTS the original ciphertext rather than re-authoring the plaintext,
     *       so the unwrap yields the ORIGINAL sender's message, not ours.</li>
     * </ul>
     */
    public interface InnerUnwrap {
        /**
         * @param outerPlaintext what the group decrypt produced — the resend WRAPPER
         * @param selectorField  the full 64-byte selector field, whose first 32 bytes are the key.
         *                       NOTE this is <b>not</b> the AAD component's opaque — see the class
         *                       doc; it is whatever the caller supplied to {@link #evaluate}
         * @return the ORIGINAL message's plaintext, or {@code null} if these bytes cannot be read
         */
        byte[] unwrap(byte[] outerPlaintext, byte[] selectorField);
    }

    /** The decision, plus everything worth logging about how it was reached. */
    public static final class Outcome {
        public final Disposition disposition;
        /** The decoded component, or null when there is none. */
        public final MlsResentMessage.Parsed component;
        /**
         * Which named MAC input matched, or null.
         *
         * <p><b>Largely OBSOLETE since 2026-09-10, and kept as an instrument rather than a
         * question.</b> This existed because "the recipient-specific bytes the sender MACs" were
         * thought to be unreachable by static analysis. They were reachable, and they are not
         * recipient-specific: the MAC'd datum is the ORIGINAL MESSAGE ID ({@link MlsResentMessage}).
         * A candidate set is now belt-and-braces — if a real resend ever matches something OTHER
         * than the message id, that is a finding, so the field still names whatever matched.
         */
        public final String matchedCandidate;
        /** The original plaintext on {@link Disposition#FOR_ME}; null otherwise. */
        public final byte[] inner;

        Outcome(final Disposition d, final MlsResentMessage.Parsed c, final String cand,
                final byte[] inner) {
            this.disposition = d;
            this.component = c;
            this.matchedCandidate = cand;
            this.inner = inner;
        }

        /** True when this message is a resend at all — i.e. the component was present. */
        public boolean isResend() { return disposition != Disposition.NOT_A_RESEND; }

        @Override public String toString() {
            return disposition
                    + (component == null ? "" : " " + component)
                    + (matchedCandidate == null ? "" : " via '" + matchedCandidate + "'")
                    + (inner == null ? "" : " inner=" + inner.length + "B");
        }
    }

    /** The ordinary outcome, hoisted so the common path allocates nothing interesting. */
    private static final Outcome ORDINARY =
            new Outcome(Disposition.NOT_A_RESEND, null, null, null);

    /**
     * Run the §11.3a receiver ordering over one successfully-decrypted application message.
     *
     * <p>Call this AFTER the group decrypt succeeds and BEFORE anything else looks at the
     * plaintext. A resend's outer message decrypts for every member — that is the whole point of the
     * selector — so this cannot live on the decrypt-failure path.
     *
     * @param aadTrailing    {@link MlsAppMessage#aadTrailing} of the inbound AAD
     * @param outerPlaintext what the group decrypt produced
     * @param selectorField  the 64-byte recipient-selector field, or {@code null} when we do not
     *                       know where it lives in a resend (SEAM 1 — see the class doc; null is
     *                       {@link Disposition#SELECTOR_UNAVAILABLE}, OUR gap, and is NOT the same
     *                       as a field that is present and the wrong size). <b>Do not pass
     *                       {@code component.payload} here</b> — that opaque is measured NOT to be
     *                       this field.
     * @param macCandidates  the NAMED set of candidate MAC inputs we can compute for ourselves; a
     *                       no-match is {@link Disposition#NOT_FOR_ME} and is benign. <b>NOT
     *                       "recipient-specific"</b> — the MAC'd datum is recipient-INVARIANT;
     *                       this set exists to identify WHICH input Google Messages MACs,
     *                       not which member a resend is for
     * @param unwrap         SEAM 2, or null while the inner layout is unknown
     */
    public static Outcome evaluate(final byte[] aadTrailing, final byte[] outerPlaintext,
            final byte[] selectorField, final Map<String, byte[]> macCandidates,
            final InnerUnwrap unwrap) {

        // ---- 1/2: parse the AAD's trailing slot and find the resent-message sub-structure. ----
        // An AAD we cannot parse at all is NOT evidence of a resend. Ordinary messages are the
        // overwhelming majority, and inventing a resend out of an unparseable tail would route a
        // healthy message into this path.
        if (aadTrailing == null || aadTrailing.length == 0) return ORDINARY;
        if (aadTrailing[0] == MlsResentMessage.TAG_ABSENT) return ORDINARY;

        final MlsResentMessage.Parsed component = MlsResentMessage.parse(aadTrailing);
        if (component == null || !component.present()) {
            // A non-zero tag whose payload does not decode under ANY candidate prefix width. On
            // Google Messages this is the "PARSE error" arm — the tag was right and the width was wrong —
            // which is exactly how the width question gets settled. Structural, and silent.
            return new Outcome(Disposition.MALFORMED_COMPONENT, component, null, null);
        }

        // ---- 2b: DO WE EVEN HOLD THE FIELD THE SELECTOR RUNS ON? Our own gap, checked before
        // anything that could be read as a judgement about the sender. The component's opaque is
        // NOT that field (class doc, measured), and until we know where it is the remaining steps
        // have no input.
        if (selectorField == null) {
            return new Outcome(Disposition.SELECTOR_UNAVAILABLE, component, null, null);
        }

        // ---- 3/4: length gate, THEN the MAC. select() enforces that order internally, and it is
        // the order that matters: a wrong-length field must not be MAC'd, and a mismatching MAC
        // must not reach the unwrap.
        final MlsResentMessage.Selection sel =
                MlsResentMessage.select(selectorField, macCandidates);
        switch (sel.verdict) {
            case EMPTY:
                return new Outcome(Disposition.EMPTY_HMAC, component, null, null);
            case INVALID_LENGTH:
                return new Outcome(Disposition.INVALID_HMAC_LENGTH, component, null, null);
            case NOT_FOR_ME:
                // NO CANDIDATE MATCHED. Not an error. Not a decrypt failure. Nothing goes out.
                // NOT "the selector result": nothing in this verifier selects (the MAC
                // passes for everyone and field A is downstream of a mismatch). See
                // Disposition.NOT_FOR_ME for why the disposition is unaffected by that.
                return new Outcome(Disposition.NOT_FOR_ME, component, null, null);
            case FOR_ME:
            default:
                break;
        }

        // ---- 5: and ONLY now the inner unwrap. Reaching this line means one of OUR named
        // candidates reproduced the tag.
        //
        // IT DOES NOT MEAN "so we are the single member of the group for whom the next step can
        // produce a receipt" — that partition fell with the selector model and this
        // sentence outlived it. What actually bounds the receipts is a property of THIS
        // enum and of our own code: reports() is true for exactly one disposition and a host test
        // pins that count. It needs no claim about how Google Messages partitions a group, which is the
        // only reason it survived the correction intact.
        if (unwrap == null) {
            return new Outcome(Disposition.FOR_ME_UNWRAP_UNAVAILABLE, component, sel.candidate,
                    null);
        }
        byte[] inner = null;
        try {
            inner = unwrap.unwrap(outerPlaintext, selectorField);
        } catch (final RuntimeException e) {
            // A throwing unwrapper is a FAILED unwrap, not a crashed receive path. One member's
            // broken decoder must not take down the inbound thread for a message every other member
            // handled fine.
            inner = null;
        }
        return inner == null
                ? new Outcome(Disposition.FOR_ME_UNWRAP_FAILED, component, sel.candidate, null)
                : new Outcome(Disposition.FOR_ME, component, sel.candidate, inner);
    }

    // ---- MARKERS. The needle a log scan looks for IS the emitter's own constant. ----
    //
    // Not decoration. MlsInvariantScan already carries one marker that was written by hand as
    // "6.8 ARM 2/3 OBSERVED" while the emitter said "6.8 ARM 2 OBSERVED", so the scan built to catch
    // that discovery could never fire on it and the condition was found by a human reading logcat
    // 57 occurrences later. A shared constant makes a reword move both halves together.

    /**
     * The Original-Message-ID header and the AAD resent-message component DISAGREE.
     *
     * <p>Two independent statements about one fact, so a disagreement is real evidence rather than
     * noise — and each direction points at a different unknown:
     *
     * <ul>
     *   <li><b>PRESENCE.</b> Header says resend, component reads ABSENT ⇒ our inferred component
     *       TAG byte ({@link MlsResentMessage#TAG_RESENT}, {@code 0x02}) is wrong. That is the
     *       first open question about the component, answerable from one line of a device log
     *       rather than from a capture we cannot provoke.</li>
     *   <li><b>VALUE.</b> Both present but the component's opaque is not the header's id ⇒ the
     *       inference that the opaque IS the original message id is wrong, and we learn that
     *       without any capture at all. See {@link #crossCheckOriginalMessageId}.</li>
     * </ul>
     */
    public static final String MARKER_DISAGREEMENT = "RESEND MARKER DISAGREEMENT";

    /**
     * Whether the component's opaque and the outer {@code Original-Message-ID} header name the same
     * message — the test of the one inference this class rests on.
     *
     * <p>Deliberately NOT a boolean and NOT a nullable String: "the two disagree" and "there was
     * nothing to compare" are different facts, and a reader that refuses a case must not share a
     * return value with a reader that measured one.
     */
    public enum IdCrossCheck {
        /** No resent-message component in the AAD, so there is nothing to compare. */
        NO_COMPONENT,
        /** No {@code Original-Message-ID} header arrived, so there is nothing to compare. */
        NO_HEADER,
        /** The opaque is not printable US-ASCII, so it is not an id and the inference is WRONG. */
        PAYLOAD_NOT_TEXT,
        /** Both present, both text, and they name the same message. The inference SURVIVES. */
        AGREE,
        /** Both present, both text, and they name DIFFERENT messages. The inference is WRONG. */
        DISAGREE
    }

    /**
     * Compare the component's opaque, read as US-ASCII, against the outer CPIM
     * {@code Original-Message-ID} header.
     *
     * <p><b>This is an instrument, never a decision.</b> Nothing in {@link #evaluate} consults it
     * and no wire behaviour depends on it. It exists because the claim "the component's opaque is
     * the original message id" is inferred from Google Messages' own engine (the opaque feeds a
     * lookup whose failure says "Failed to get message content for message"), and the cheapest
     * possible test of it is a single log line the first time a real resend arrives — no capture,
     * no probe, no device.
     *
     * @param o      the outcome from {@link #evaluate}
     * @param header the {@code Original-Message-ID} header value, or null/empty when absent
     */
    /** Marker for the three-way id capture. Grep this to find the labelled candidate line. */
    public static final String MARKER_ID_CANDIDATES = "RESEND ID CANDIDATES";

    /**
     * Log THREE distinct things that could each be called "the original message id", side by side
     * and <b>labelled</b>, so a reader cannot conflate them (2026-09-10).
     *
     * <p>They live in three different layers and they are <b>not the same field</b>:
     * <ol>
     *   <li><b>{@code aad.message_id}</b> — the AAD's own top-level id (§10.2.4, wire-proven:
     *       {@code version(2) | mls_varint len | message_id | era uint32 BE | optional component}).
     *       On an ORDINARY message this is the carrying message's own id.</li>
     *   <li><b>{@code cpim.Original-Message-ID}</b> — an OUTER CPIM header (§13), living entirely
     *       outside the AAD. Google Messages sets it conditionally, only on a resend.</li>
     *   <li><b>{@code component.opaque}</b> — the resent-message component's length-prefixed opaque,
     *       measured feeding the message-content lookup.</li>
     * </ol>
     *
     * <p><b>WHAT IS NOT ESTABLISHED, and it is the thing that makes this instrument worth having:
     * what (1) holds ON A RESEND.</b> Per §11.1 a resend gets a NEW {@code rcs_message_id} (new row,
     * FK {@code manual_resend_of_rcs_message} at the original), so (1) may carry the RESEND's own id
     * rather than the original's. <b>Do not read equality on ordinary traffic as proof these are the
     * same field</b> — on ordinary traffic they would be equal under BOTH hypotheses, which is
     * exactly why ordinary traffic cannot settle it and a real resend can.
     *
     * <p>Length will not separate them either: all three carry a 24-byte {@code Mx}-form id, the same
     * trap as the u8-vs-mls_varint episode. <b>On a resend they should hold DIFFERENT values.</b>
     *
     * <p><b>BUT NOT EVERY INEQUALITY DISCRIMINATES, and this is written down BEFORE the first
     * capture so nobody over-reads one after.</b> An earlier draft of this javadoc said "an
     * inequality between any two names which is which". That is too strong:
     * <ul>
     *   <li><b>(1) DIFFER (3) has TWO readings</b> — either they are different layers holding
     *       different things, OR they are the same layer and the resend simply minted a new id
     *       (§11.1). It NARROWS the question without closing it.</li>
     *   <li><b>(2) vs (3) is the pair that actually discriminates</b>, because the outer CPIM header
     *       is documented as carrying the ORIGINAL id — so it has no §11.1 ambiguity to hide behind.
     *       Read that pair first.</li>
     * </ul>
     *
     * <p><b>Field A is deliberately absent.</b> A (the u32 at {@code struct+0x48} that disambiguates
     * an HMAC failure — it is NOT a selector)
     * is inside the component BODY, and we cannot parse that body — the outer framing anchor is
     * unresolved and our single-opaque model of the present form is known incomplete. Logging a value
     * we cannot correctly extract would be worse than logging none.
     *
     * @return a labelled one-line capture, or null when there is nothing to say (no id anywhere)
     */
    public static String idCandidateLine(final Outcome o, final String cpimHeader,
            final String aadMessageId) {
        final String opaque = componentOpaqueAsText(o);
        if ((aadMessageId == null || aadMessageId.isEmpty())
                && (cpimHeader == null || cpimHeader.isEmpty())
                && opaque == null) {
            return null;
        }
        return MARKER_ID_CANDIDATES
                + " aad.message_id=" + show(aadMessageId)
                + " cpim.Original-Message-ID=" + show(cpimHeader)
                + " component.opaque=" + show(opaque)
                + " | aad-vs-cpim=" + agreement(aadMessageId, cpimHeader)
                + " aad-vs-component=" + agreement(aadMessageId, opaque)
                + " cpim-vs-component=" + agreement(cpimHeader, opaque)
                + " | NOTE these are THREE LAYERS, not one field; (1) may hold the RESEND's own id"
                + " on a resend (RCC.16 §11.1), so aad-vs-component DIFFER has TWO readings and"
                + " narrows without closing — cpim-vs-component is the discriminating pair."
                + " Field A not shown: body unparseable.";
    }

    /** The component's opaque rendered as ASCII, or null when absent/binary. */
    private static String componentOpaqueAsText(final Outcome o) {
        if (o == null || o.component == null || !o.component.present()
                || o.component.payload == null || o.component.payload.length == 0) {
            return null;
        }
        for (final byte b : o.component.payload) {
            if (b < 0x20 || b > 0x7E) return null;
        }
        return new String(o.component.payload, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String show(final String s) {
        return (s == null || s.isEmpty()) ? "<absent>" : s;
    }

    /** Deliberately three-valued: "nothing to compare" must not share a value with "they agree". */
    private static String agreement(final String a, final String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return "N/A";
        return a.equals(b) ? "AGREE" : "DIFFER";
    }

    public static IdCrossCheck crossCheckOriginalMessageId(final Outcome o, final String header) {
        if (o == null || o.component == null || !o.component.present()
                || o.component.payload == null || o.component.payload.length == 0) {
            return IdCrossCheck.NO_COMPONENT;
        }
        if (header == null || header.isEmpty()) return IdCrossCheck.NO_HEADER;
        for (final byte b : o.component.payload) {
            // Message ids are base64url-ish ASCII. One non-printable byte is enough to say the
            // opaque is not a message id at all, which is a stronger result than a mismatch.
            if (b < 0x20 || b > 0x7E) return IdCrossCheck.PAYLOAD_NOT_TEXT;
        }
        final String asText =
                new String(o.component.payload, java.nio.charset.StandardCharsets.US_ASCII);
        return header.equals(asText) ? IdCrossCheck.AGREE : IdCrossCheck.DISAGREE;
    }

    /** The component's opaque rendered for a log line — text when it is text, hex when it is not. */
    public static String componentPayloadForLog(final Outcome o) {
        if (o == null || o.component == null || o.component.payload == null) return "<none>";
        final byte[] p = o.component.payload;
        boolean text = p.length > 0;
        for (final byte b : p) if (b < 0x20 || b > 0x7E) { text = false; break; }
        if (text) {
            return "'" + new String(p, java.nio.charset.StandardCharsets.US_ASCII) + "'";
        }
        final StringBuilder sb = new StringBuilder(p.length * 2);
        for (final byte b : p) sb.append(String.format("%02x", b));
        return "0x" + sb;
    }

    /**
     * A resent-message component that is PRESENT but does not decode under any candidate width.
     *
     * <p>On Google Messages this is the "PARSE error" arm, which is how the length-prefix width question
     * gets settled: a wrong TAG produces "does not contain a resent message" instead.
     */
    public static final String MARKER_MALFORMED = "RESEND COMPONENT MALFORMED";

    /**
     * <b>THE ARTIFACT.</b> A resend arrived and we could not run the selector on it at all.
     *
     * <p>Scanned because reaching it means a PRESENT-form resent-message component has been
     * observed — the thing four device probes and two capture campaigns failed to obtain — and the
     * emitting line carries its bytes. It is OUR gap ({@link Disposition#SELECTOR_UNAVAILABLE}) and
     * says nothing about the sender.
     */
    public static final String MARKER_SELECTOR_UNAVAILABLE = "CANNOT RUN THE SELECTOR";

    /**
     * <b>THE MEASUREMENT.</b> A resend's selector MAC matched one of OUR candidate inputs.
     *
     * <p>Scanned for because reaching it identifies the MAC INPUT — the one input static analysis
     * could not reach, and the thing blocking correctly-targeted resend EMISSION. <b>NOT the
     * "recipient-specific" input</b>: that description fell with the selector model, and
     * {@link MlsResentMessage}'s own open-items list says so in as many words — knowing WHICH slice
     * is MAC'd does not answer what that item was asking, so emission is not
     * unblocked on this basis alone.
     * It is not a fault; it is the event four device probes and two capture campaigns were trying to
     * produce, and it must not be allowed to scroll past in a log nobody is grepping.
     */
    public static final String MARKER_FOR_ME = "RESEND IS FOR US";

    /**
     * The Google Messages-shaped log line for a resend that is not ours.
     *
     * <p>Worded to match Google Messages' own ("Resent message not for me with original message id {}
     * fails HMAC verification, for group: {}") so a side-by-side device log reads the same on both
     * ends, and so the check "one resend in a 3-member group produces exactly two not-for-me lines"
     * can be run against OUR log as well as theirs.
     *
     * <p><b>That check has never been run, on either corpus, and it is the FALSIFIER for the
     * withdrawn "N-1" count</b> (see the class doc). It was written down
     * as a sanity check for a settled expectation; it is actually the measurement that would settle
     * it. Run it before quoting a count.
     *
     * <p><b>The count has to be captured automatically</b>, because a resend cannot be provoked —
     * every empirical route to one is closed on mechanism — and logcat is a ring buffer, so the
     * arrival is unattended by construction. A log drain collects this line, from our devices and
     * from Google Messages ones, and counts it per original-message-id.
     *
     * <p><b>On a Google Messages device this line is NATIVE, not Java</b>, and that gates the whole
     * measurement. The literal is a Rust format fragment inside its native MLS engine, so it
     * reaches logcat under
     * {@code CallFlogger} — gated on {@code log.tag.CallFlogger}, which does NOT survive a reboot
     * and is unset on most devices, where the drain then yields a clean zero that means
     * nothing. Rust {@code {:?}} also Debug-renders the args, so Google Messages' line reads
     * {@code …original message id MessageId: "Mx…" fails HMAC verification, for group: GroupId: "…"}
     * while ours is bare; the harvester greps the shared literal prefix and normalises the value.
     *
     * <p><b>What our corrected model predicts:</b> this line is
     * downstream of an HMAC mismatch AND a field-A mismatch, and on well-formed traffic every
     * member's HMAC verifies, so it should be UNREACHABLE. Zero lines is CONSISTENT; N-1 lines
     * means the model is wrong and the withdrawn count was right. Both outcomes are informative,
     * which is the point of leaving the instrument armed.
     */
    public static String notForMeLine(final String originalMessageId, final String groupId) {
        return "Resent message not for me with original message id "
                + (originalMessageId == null ? "<unknown>" : originalMessageId)
                + " fails HMAC verification, for group: " + (groupId == null ? "<none>" : groupId)
                + " — BENIGN. NOTE this line is downstream of an HMAC MISMATCH; field A only "
                + "disambiguates error-vs-drop and does NOT select (see MlsResentMessage). "
                + "No receipt, no FTD, no recovery, no state change. "
                + "How many members log this per resend is NOT established — 'N-1' was an "
                + "inference, withdrawn 2026-09-10; counting these lines on a real group is the "
                + "open measurement, so this line must not pre-announce its own answer.";
    }
}

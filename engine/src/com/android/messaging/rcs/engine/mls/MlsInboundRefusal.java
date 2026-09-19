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
 * <b>A REFUSAL IS NOT A DECRYPT FAILURE.</b> What the receiver does with a message it could read and
 * decided not to accept.
 *
 * <h2>The defect this exists to make unrepeatable</h2>
 *
 * <p>{@code MlsProviderTransport.decryptInbound} had two arms whose own comments said, in capitals,
 * that the message DECRYPTED and that this was therefore not a crypto failure — and both then did
 * {@code return null}. Null from that method is not a silent drop: {@code RcsCallbackRouter} reads it
 * as "did not decrypt" and calls {@code onDecryptFailure()}, which self-heals, emits a §7.7.2.2
 * negative delivery report, and after three consecutive non-convergences REBUILDS the conversation.
 *
 * <p>So a message we read perfectly well and refused was answered with a group repair and a report
 * saying we could not read it. Three separate things wrong with that:
 *
 * <ul>
 *   <li><b>The report is false.</b> It tells the sender to advance the epoch and resend, which cannot
 *       help — the resend is refused identically — so it is a loop bounded by the resend budget
 *       rather than by the cause being addressed.</li>
 *   <li><b>It spends the repair budget on a healthy group.</b> Era advances are rate-limited per
 *       conversation; burning one on a refusal leaves nothing for a real
 *       desync.</li>
 *   <li><b>One of the arms is attacker-driven.</b> Any member of a group can address an envelope in
 *       another member's name. Under the old behaviour each such message cost the group a self-heal
 *       and an FTD, i.e. a detected impersonation attempt was converted into a self-inflicted repair
 *       storm — an amplification primitive handed to the one participant we had already caught
 *       misbehaving, and a remote-triggerable way to force a conversation rebuild.</li>
 * </ul>
 *
 * <p>This is the same defect class as the resend receive path one level up (see {@link MlsResendReceive}),
 * and it is fixed the same way: the arm returns an INTERNAL MARKER content type instead of null, the
 * router classifies it {@link RccContentDisposition#DROP_CONTROL}, and the policy — what a refusal is
 * ALLOWED to do — lives here where a host test can hold it, rather than in a comment on a class that
 * needs a {@code Context} and a bound provider.
 *
 * <h2>The rule, and it is the same for every reason below</h2>
 *
 * <p>A refusal puts <b>nothing</b> on the wire, repairs <b>nothing</b>, and counts toward
 * <b>nothing</b>. {@link #reportsToSender}, {@link #healsGroup} and {@link #countsTowardRebuild} are
 * false for every {@link Reason}, and the host tests assert that over {@code Reason.values()} rather
 * than over a list, so a reason added later without a decision fails the build instead of inheriting
 * one silently.
 *
 * <p><b>Why nothing goes out even though a truthful signal would be better than the false one.</b>
 * There is no established token for either condition. Google Messages fails a mis-binding internally
 * ({@code MessageIdMismatch}) and emits nothing, and inventing a token would put bytes on the wire
 * that no peer has ever been observed to send. For the impersonation arm the case is stronger still:
 * the only address we could report to is {@code fromE164}, which on that arm is the <em>victim</em> —
 * we would be telling an innocent peer that we could not decrypt a message they never sent, and
 * asking them to resend something they do not have. Silence plus a loud log is the safe default, and
 * it is the same reasoning as {@link MlsResendReceive#silentDrop}.
 *
 * <h2>Why the two reasons are distinct and do not share a marker</h2>
 *
 * <p>They are the same disposition and different events, and the distinction is the whole diagnostic
 * value. A mis-binding is most likely a FRAMING fault — a peer that bound the wrong id, or a
 * transport that rewrote one — whose worst case is a replay. An impersonation has no benign reading:
 * the decrypt proves the signer is a current member of this group, so it is an authenticated peer
 * lying about who it is. Separate markers keep them separately greppable, separately severable in
 * {@link MlsInvariantScan}, and separately replayable out of the rendezvous table.
 */
public final class MlsInboundRefusal {

    private MlsInboundRefusal() { }

    /** Why an inbound message that DECRYPTED was refused. */
    public enum Reason {
        /**
         * RCC.16 §7.5.3.1 — the AAD's {@code message_id} does not equal the transport's.
         *
         * <p>RFC 9420 authenticates the AAD but assigns it no meaning, so mls-rs decrypts happily
         * when the two disagree; the binding is ours to enforce. Accepting the message would attribute
         * a ciphertext to an id its author did not bind, and every ledger we key on message id
         * (receipts, FTD correlation, resend) would then point at the wrong message.
         */
        AAD_MESSAGE_ID_MISBINDING,

        /**
         * The envelope names one sender and the MLS leaf that SIGNED it is
         * certified as another.
         *
         * <p>MLS authenticates the sender WITHIN the group and no further: a successful decrypt
         * proves some current member produced these bytes and says nothing about which one. The
         * identity that reaches the chat row comes from the transport envelope, which the sender
         * chooses. This is the attack that check exists to stop, and reaching it means it worked.
         */
        SENDER_IMPERSONATION
    }

    // ---- THE THREE PREDICATES. All false, for every reason, on purpose. ------------------------
    //
    // Written as exhaustive switches rather than `return false` so that adding a Reason is a
    // COMPILE-then-TEST failure instead of a silent inheritance. The tests iterate values(); a new
    // constant with no arm throws here and the test that walks the enum names it.

    /**
     * May this refusal emit a §7.7.2.2 negative delivery report?
     *
     * <p><b>No, for every reason, and a host test pins that.</b> The message decrypted, so an FTD is
     * a false statement; and the peer it would be addressed to is either the one that mis-bound the
     * id (who cannot fix it by resending) or, on the impersonation arm, the peer being impersonated.
     */
    public static boolean reportsToSender(final Reason r) {
        switch (r) {
            case AAD_MESSAGE_ID_MISBINDING: return false;
            case SENDER_IMPERSONATION:      return false;
            default: throw new IllegalArgumentException("unclassified refusal: " + r);
        }
    }

    /**
     * May this refusal run a §10.1 self-heal?
     *
     * <p><b>No, for every reason.</b> The outer message decrypted, which is the strongest available
     * evidence that our group state is in step with the sender's — the same evidence
     * {@code decryptInbound} uses to move the conversation to HEALTHY. There is nothing to repair,
     * and the era-advance quota spent trying is not available for a real desync afterwards.
     */
    public static boolean healsGroup(final Reason r) {
        switch (r) {
            case AAD_MESSAGE_ID_MISBINDING: return false;
            case SENDER_IMPERSONATION:      return false;
            default: throw new IllegalArgumentException("unclassified refusal: " + r);
        }
    }

    /**
     * May this refusal advance the consecutive-non-convergence counter that ends in a rebuild?
     *
     * <p><b>No, for every reason, and this is the security-relevant one.</b> The counter exists to
     * notice a conversation the §10 ladder cannot fix. A refusal is not evidence of that, and letting
     * one count means any member of the group can force a rebuild by sending three messages under
     * someone else's name.
     */
    public static boolean countsTowardRebuild(final Reason r) {
        switch (r) {
            case AAD_MESSAGE_ID_MISBINDING: return false;
            case SENDER_IMPERSONATION:      return false;
            default: throw new IllegalArgumentException("unclassified refusal: " + r);
        }
    }

    /**
     * Must the decision be stored in the rendezvous table so a REDELIVERY replays it?
     *
     * <p><b>Yes, for every reason</b>, and this reverses an explicit earlier decision on the
     * impersonation arm ("caching a refusal would make the rejection permanent for that message id
     * even if the id is later reused legitimately"). The cost that argument accepted is not the one
     * that is actually incurred. Tachyon really does redeliver — a Google Messages sender's ciphertext was
     * dispatched to us twice 4 ms apart on 2026-08-15 — and the second delivery of an unstored
     * refusal re-enters the destructive decrypt on a ratchet step the first one already consumed. It
     * then either fails, which lands on {@code onDecryptFailure} and reinstates the exact self-heal
     * plus FTD this class removes, or succeeds by burning another generation and refuses identically.
     * Neither is worth the hypothetical it was protecting.
     *
     * <p>The hypothetical is also not reachable in practice: message ids are client-generated UUIDs,
     * so "the id is later reused legitimately" requires an attacker to predict a peer's next id
     * before that peer's own copy of the message reaches us. Attackers in the group learn an id when
     * they receive it, which is when we receive it — by which point the legitimate delivery already
     * holds the row.
     */
    public static boolean replayable(final Reason r) {
        switch (r) {
            case AAD_MESSAGE_ID_MISBINDING: return true;
            case SENDER_IMPERSONATION:      return true;
            default: throw new IllegalArgumentException("unclassified refusal: " + r);
        }
    }

    /**
     * The INTERNAL content-type marker substituted for the refused body.
     *
     * <p>Never on the wire. It is what the decrypt path returns instead of {@code null}, and what is
     * framed into the rendezvous row so a redelivery replays the refusal rather than parsing the
     * refused bytes as an ordinary message. The constants live in {@link RccContentDisposition}
     * beside {@link RccContentDisposition#RESEND_NOT_FOR_ME} because that is the class whose
     * {@code classify} has to route them — a marker defined anywhere else is a marker that can be
     * added without anyone teaching the router about it.
     */
    public static String marker(final Reason r) {
        switch (r) {
            case AAD_MESSAGE_ID_MISBINDING: return RccContentDisposition.REFUSED_AAD_MISBINDING;
            case SENDER_IMPERSONATION:      return RccContentDisposition.REFUSED_IMPERSONATION;
            default: throw new IllegalArgumentException("unclassified refusal: " + r);
        }
    }

    /** The reason a marker content type stands for, or null if this is not a refusal marker. */
    public static Reason forMarker(final String contentType) {
        if (contentType == null) return null;
        final String ct = contentType.trim();
        for (final Reason r : Reason.values()) {
            if (marker(r).equals(ct)) return r;
        }
        return null;
    }

    /** True when this content type is one of the internal refusal markers. */
    public static boolean isRefusal(final String contentType) {
        return forMarker(contentType) != null;
    }

    // ---- MARKERS. The needle a log scan looks for IS the emitter's own constant. ---------------
    //
    // Same discipline as MlsResendReceive: MlsInvariantScan once carried a hand-typed needle that
    // differed from the emitted string by three characters, so the scan built to catch a discovery
    // could never fire on it and a human found the condition by reading logcat 57 occurrences later.

    /**
     * §7.5.3.1 fired: an inbound message's AAD named a different message than the envelope carrying
     * it, and we have no evidence the named message was ever delivered to us.
     *
     * <p>SUSPICIOUS rather than NEVER — a peer with a framing bug reaches this honestly.
     */
    public static final String MARKER_MISBINDING = "REFUSED: AAD MESSAGE-ID MIS-BINDING";

    /**
     * The same check fired, and the id the AAD names is one we PROVABLY already processed from this
     * same peer — i.e. this ciphertext is being replayed to us under a new transport id.
     *
     * <p>The stronger of the two readings §7.5.3.1 admits, and the reason the check exists. Emitted
     * INSTEAD of {@link #MARKER_MISBINDING}, never alongside it, so one event produces one hit.
     *
     * <p><b>The evidence is one-directional.</b> A rendezvous hit is positive proof; a miss proves
     * nothing (the row may have been evicted, or the original may never have reached us), so its
     * absence downgrades to MIS-BINDING rather than clearing anything.
     */
    public static final String MARKER_REPLAY =
            "REFUSED: CIPHERTEXT REPLAYED UNDER A NEW MESSAGE-ID";

    /**
     * Impersonation fired: an authenticated member of the group sent a message under another
     * member's name.
     *
     * <p>The wording is load-bearing and predates this class — {@link MlsInvariantScan} has carried
     * it as a NEVER since the check was written, and captures already grep for it. It is a constant
     * now so that a reword moves the emitter and the scan together.
     */
    public static final String MARKER_IMPERSONATION = "is impersonating another one";

    /**
     * The §7.5.3.1 refusal line.
     *
     * @param provenReplay true when the id the AAD names has a stored DECRYPT result from this same
     *                     peer, which turns "mis-binding" into "replay" as a matter of evidence
     *                     rather than of suspicion
     */
    public static String misbindingLine(final String messageId, final String fromE164,
            final String aadMessageId, final boolean provenReplay) {
        return "REFUSING " + messageId + " from " + fromE164 + " — "
                + (provenReplay ? MARKER_REPLAY : MARKER_MISBINDING)
                + ". Its AAD names message_id '" + aadMessageId
                + "' (§7.5.3.1 requires equality with the transport's)"
                + (provenReplay
                        ? ", and we have ALREADY PROCESSED a message under that id from this peer, so"
                          + " these bytes are being re-presented to us under a new id"
                        : "; we hold no stored result for that id, so this is a mis-binding or a"
                          + " replay we cannot prove")
                + ". THE MESSAGE DECRYPTED — this is a REFUSAL, not a crypto failure. Nothing goes "
                + "out: no FTD, no receipt, no §10 recovery, no rebuild credit.";
    }

    /**
     * The impersonation refusal line. Names BOTH identities, because the certified one is the
     * only thing that identifies the offending member and the envelope one is the victim.
     */
    public static String impersonationLine(final String messageId, final String fromE164,
            final String signerMsisdn) {
        return "REFUSING " + messageId + " — the envelope says it is from " + fromE164
                + " but the MLS leaf that SIGNED it is certified as " + signerMsisdn
                + ". THE MESSAGE DECRYPTED, so the signer really is a member of this group — it "
                + MARKER_IMPERSONATION + ". Attributing it to " + fromE164 + " is exactly the attack "
                + "this check exists to stop. Nothing goes out: reporting would send a "
                + "'could not decrypt' to " + fromE164 + ", who never sent this and cannot resend "
                + "it, and healing would spend the repair budget on a group that is demonstrably in "
                + "step.";
    }
}

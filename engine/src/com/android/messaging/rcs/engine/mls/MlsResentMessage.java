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

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RCC.16 §10.3 / §11.3a — the <b>resent-message component</b> that rides in the trailing slot of the
 * application AuthenticatedData, and the MAC that authenticates it.
 *
 * <p><b>The MAC does NOT select the recipient. Corrected 2026-09-10 — see "THE MODEL WE HAD WAS
 * WRONG" below.</b> NOTHING IN THIS VERIFIER SELECTS — not this field, and not field A either.
 * This 64-byte field is an
 * NOT the selector. What it IS instead — an integrity check on the already-selected message, or
 * VESTIGIAL — is <b>NOT established, and neither reading may be asserted</b>.
 *
 * <h2>Where this sits</h2>
 *
 * <p>{@link MlsAppMessage#buildAuthenticatedData} emits {@code 00 01 | varint(len) | message_id |
 * era u32 | <trailing>}, and the trailing slot is this component — {@code 0x00} when absent, which
 * is what every ordinary message carries. This class is the present form.
 *
 * <h2>WHAT THE OPAQUE CARRIES — corrected 2026-09-08</h2>
 *
 * <p><b>The component's length-prefixed opaque is NOT the 64-byte selector field.</b> That binding
 * was ours and unlabelled, and Google Messages' own engine contradicts it: the struct the verifier
 * reads the field out of is cloned <em>before</em> the component tag is even read. See
 * {@link MlsResendReceive} for the full reading and
 * for what the opaque does feed. The methods below still take a 64-byte field — they are correct
 * about the FIELD; what was wrong was where the caller got it from.
 *
 * <h2>THE MODEL WE HAD WAS WRONG, AND HOW IT SURVIVED A MONTH</h2>
 *
 * <p><b>What this class used to say:</b> that the MAC is a <em>recipient selector</em> — the sender
 * draws a random {@code K} and sends {@code K || HMAC(K, <recipient-specific bytes>)}, each member
 * recomputes with <em>its own</em> bytes, and only the intended target matches, so N-1 of every N
 * fail the MAC <em>by construction</em>. <b>That is false.</b> Two independent arguments kill it.
 *
 * <p><b>(1) The structural one, which needs no reference to Google Messages' implementation.</b> The MAC'd datum is the ORIGINAL
 * MESSAGE ID (established below) — identical for every recipient — and the key travels IN THE CLEAR
 * beside the tag it authenticates. So every member computes the SAME tag and every member PASSES.
 * <em>A selector that selects everyone is not a selector.</em> This argument stands on its own and
 * would survive even if the reading in (2) were re-litigated.
 *
 * <p><b>(2) The read.</b> In Google Messages' engine the MAC'd data reaching the verifier is its
 * THIRD ARGUMENT, and the same pointer/length pair is the single format argument of both
 * "…not for me with original message id " and "…fails HMAC verification" — i.e. the message id. The
 * only other length in the call is the 32-byte OUTPUT length, which is why the verifier checks the
 * result against 32 bytes.
 *
 * <p><b>HOW IT SURVIVED, which is the part worth keeping.</b> The founding evidence said it plainly
 * and in the right words: <em>"it compares the resend's field A against the client's own value and
 * branches — UNEQUAL is a benign skip … only EQUAL raises 106."</em> Read
 * literally, that is an integrity check on an ALREADY-SELECTED message, and it always was: the
 * field-A test decides "is this for me", and the HMAC failure is reachable only on the arm
 * where it IS for us. Someone summarised that into "a MAC mismatch is a recipient selector", and
 * from then on everyone cited the summary rather than the source. <b>It stood for a month because
 * a paraphrase got quoted instead of the sentence.</b>
 *
 * <p><b>What is actually true — and it names no selector, deliberately.</b> Field A (a u32 in the
 * resend struct, compared against the client's own copy) does NOT select
 * either; an earlier draft of this correction said it did, and that was the THIRD tidy replacement
 * model in one day to be wrong. <b>The field-A test is downstream of an HMAC FAILURE</b>, which the
 * branch structure settles:
 * <pre>
 *   the 32-byte compare returns 1 when the bytes are EQUAL, and the verdict is stashed
 *   the following branch is taken ONLY when the MAC MISMATCHED
 *     MAC verified  -> no log, no raise, proceed
 *     MAC mismatched-> LOG "fails HMAC verification" (gated on log level, not on any recipient test)
 *                      then compare field A against the client's own value:
 *                        EQUAL     -> raise 105
 *                        NOT EQUAL -> LOG "not for me", benign
 * </pre>
 * So A disambiguates an HMAC failure — <em>error</em> versus <em>benign drop</em>. Being "not for
 * me" alone produces neither log; a non-target whose HMAC verifies never reaches this code.
 *
 * <p><b>And the consequence is larger than the correction.</b> Because the key is
 * {@code field[0..32)} in the clear and the MAC'd datum is recipient-invariant, EVERY member's HMAC
 * verifies — so that branch is never taken and this entire block, both logs and the field-A
 * test,
 * is <b>unreachable on well-formed traffic</b>. <b>Note what kind of claim that is:</b> it is a
 * statement about EXECUTION, DERIVED from two structural facts (the branch condition, and the
 * invariance of both key and data) — not an observation. We have never seen a Google Messages resend. A
 * count or a reachability claim is about execution; structural citations prove structure, and the
 * two have been confused twice today in the other direction (the N-1 rule, and "at most one receipt
 * per resend", both retracted for exactly this). It is reachable only via a corrupt, truncated or
 * tampered 64-byte field.
 *
 * <p><b>WHERE FIELD A COMES FROM — measured, with the inference marked as one.</b> Google Messages'
 * engine has EXACTLY ONE non-stack 32-bit writer to the client's copy of A anywhere in its code,
 * found by scanning every 32-bit store with an unsigned offset. That writer sits inside the large
 * resend-construction function — the same body that reads the field, and the same neighbourhood as
 * the composition that decodes the 80-byte struct — and the write is <b>DOUBLY GUARDED</b>, both
 * guards in one basic block.
 *
 * <p>Established: it is the sole writer, it is doubly guarded, and it sits in the resend decoder
 * rather than at client init. <b>Inferred from that neighbourhood ALONE:</b> that this makes A
 * resend-derived rather than a static client identity. Where the written VALUE comes from is not
 * traced — it runs into the same outlined-fragment wall that stopped the other trace.
 *
 * <p><b>A HYPOTHESIS, RECORDED AS ONE AND NOT TO BE BUILT ON.</b> The since-retracted N-1 text
 * contained the clause "only the member whose HMAC VERIFIED <em>and whose inner decryption THEN
 * failed</em>", which implies the flow continues past the HMAC into a decryption — and MLS
 * per-recipient addressing already lives in the inner ciphertext. So <b>the inner ciphertext may be
 * the selector.</b> It explains every observation at once, which is exactly why it is written here
 * as a hypothesis to be SHOT DOWN rather than adopted. Three tidy replacement
 * models have been wrong in one day; do not make this the fourth.
 *
 * <p>What D is FOR is deliberately left open: an
 * integrity check on the already-selected message and "vestigial" both fit every fact we have,
 * and nothing in Google Messages' implementation separates them. Do not assert either. Note what this costs the
 * construct: with the key in the clear beside its own tag and the MAC'd datum recipient-invariant,
 * D authenticates nothing an attacker could not forge — which is exactly why "vestigial" is live.
 *
 * <p><b>What SURVIVES the correction, so it is not over-corrected away:</b> the silent-drop
 * DISPOSITION is still real and still required — a member the resend is not for logs "not for me"
 * and does nothing else. Its MECHANISM, however, is NOT in this verifier at all — not the MAC and
 * not field A, both of which are unreachable on well-formed traffic. Whatever produces the N-1 is
 * elsewhere and is <b>not identified</b>. It is emphatically not driven by
 * a MAC mismatch. Every disposition, the ordering, and the single-reporting-arm rule are unaffected.
 *
 * <p><b>What does NOT survive is the COUNT.</b> "N-1 members log it" was never established — it sat
 * beside an exhaustive enumeration that covers non-escalation and at-most-one-receipt
 * and says nothing about how many members reach the branch, and it was withdrawn.
 * Falsifier, never yet run: one resend in a 3-member group must
 * produce exactly two "not for me" lines.
 *
 * <h2>The computation — byte-level</h2>
 *
 * <pre>
 *   tag = HMAC-SHA256( key  = hmac_field[0 .. 32],
 *                      data = the ORIGINAL MESSAGE ID )
 *   accept iff tag == hmac_field[32 .. 64]
 * </pre>
 *
 * <p><b>There is NO HKDF and no derivation label, and that is a STRUCTURAL claim rather than a
 * failed search.</b> A label was relayed at first, then corrected twice — first because
 * proximity to the HKDF PRK constructors identified the <em>crate</em> ({@code bssl-crypto} ships
 * both an HKDF and an HMAC module) and not the function, then because the string sweep
 * backing "no label" was itself inadmissible as stated. The conclusion survived on better
 * evidence: BoringSSL's own signature is
 *
 * <pre>pub fn mac(key: &amp;[u8], data: &amp;[u8]) -&gt; [u8; 32]</pre>
 *
 * <p>HMAC takes <b>no info/label parameter</b> — an info string is HKDF's API and this
 * path never calls it. So it is not that a label was not found; <b>there is nowhere in the
 * primitive's signature for one to go</b>, which no better scan can overturn. (HKDF code really is
 * nearby — its error enum is reachable within depth 3 of the same closure — which is why the wrong
 * inference was easy to make.)
 *
 * <h2>Three failure modes on Google Messages, and one of them is NOT a failure</h2>
 *
 * <p>If you debug this against a Google Messages device's logcat, these are distinct paths:
 *
 * <ul>
 *   <li>{@code "… has EMPTY HMAC"} — the {@code len == 0} path.</li>
 *   <li>invalid <em>length</em> — {@code len != 64}, Google Messages' error 105.</li>
 *   <li>{@code "… has INVALID HMAC"} — the mismatch path, logged BEFORE the recipient-discriminator
 *       branch. <b>The old claim that you should expect this N-1 times per resend is WRONG</b> and
 *       fell with the selector model: the MAC is computed over a recipient-invariant datum with a
 *       cleartext key, so it should PASS for every member. Seeing this line at all now means a
 *       Google Messages mismatch — corruption, or a construction we have not understood — not routine
 *       not-for-me traffic. Note the "not for me" line is ALSO downstream of an HMAC mismatch, so
 *       on well-formed traffic you should see NEITHER —
 *       though <em>how many</em> members log it per resend is NOT established; see
 *       {@link MlsResendReceive}'s class doc for why "N-1" was withdrawn.</li>
 * </ul>
 *
 * <h2>What is NOT known, and is therefore not guessed here</h2>
 *
 * <ol>
 *   <li><b>The MAC'd slice — STILL OPEN, and half-answered 2026-09-10.</b> WHICH slice is MAC'd is
 *       now known: the ORIGINAL MESSAGE ID. But this item asked for "the recipient-specific bytes",
 *       and that was the WRONG DESCRIPTION of the thing — the MAC'd datum is recipient-INVARIANT.
 *       Knowing which slice does not answer the question this item was really posing, so it
 *       stays open and <b>emission is NOT unblocked on this basis</b>.</li>
 *   <li><b>The tag byte.</b> {@link #TAG_RESENT} is {@code 0x02}, and that is an inference. The one
 *       observed fact is that the tag is read as a single byte — which matters
 *       because it rules out niche layouts in which no tag byte exists and makes the question
 *       well-posed at all. Declaration-order tagging and the derived codec writing that index are
 *       inferred, and an explicit {@code #[repr(u8)]} would break both.</li>
 * </ol>
 *
 * <h2>SETTLED 2026-09-10 — the prefix width is mls_varint, and the capture we waited on could
 * never have said so</h2>
 *
 * <p>This section used to head the list above as an open question, with the note that a capture
 * would settle it. <b>Both halves were wrong in an instructive way.</b>
 *
 * <p><b>The width is {@code mls_varint}</b>, and Google Messages' decoder for it is RFC 9420
 * §2.1.2 verbatim: read one byte, {@code prefix = byte >>> 6},
 * total length {@code 1 << prefix} (so 1, 2 or 4 bytes), {@code prefix == 3} is a reserved error,
 * and the value accumulates big-endian from {@code byte & 0x3F}. Its caller reads the length and
 * bounds-checks it against the cursor's remaining
 * bytes. Every variable-length field of the resent-message struct goes through it.
 *
 * <p><b>And the instrument we were parked on could not have discriminated.</b> The paragraph this
 * replaces said so itself without drawing the conclusion: the widths diverge only at length
 * &ge; 64, and a 24-byte message id encodes as {@code 0x18} under {@code u8} AND under a one-byte
 * {@code mls_varint} — identical. So "we are blocked on a capture" was itself an unexamined claim;
 * the capture was structurally incapable of answering, and reading the decoder answered it in an
 * afternoon. Worth remembering as a shape: not a probe that measured nothing, but a WAITING
 * STRATEGY aimed at an instrument that could not tell the alternatives apart.
 *
 * <p><b>Why {@link #parse} still tries all three.</b> Not indecision — the two questions are at
 * different levels. {@code mls_varint} is settled for the fields <em>inside</em> the resent-message
 * struct; what {@link #parse} decodes is the OUTER tail, whose framing is still unresolved (below).
 * Until the outer anchor is fixed the tolerance costs nothing and {@link Parsed#width} still
 * reports which encoding actually matched, which is a measurement we would otherwise throw away.
 *
 * <h2>OUR MODEL OF THE PRESENT FORM IS INCOMPLETE — read this before trusting {@link #parse} on a
 * real resend (2026-09-10)</h2>
 *
 * <p>This class models the present-form tail as {@code tag || ONE length-prefixed opaque}. The
 * struct Google Messages' verifier actually consumes has <b>FOUR</b> fields, read in this order by
 * its deserializer off a single advancing cursor (so read order is wire
 * order):
 *
 * <pre>
 *   A   4-byte BIG-ENDIAN scalar      the u32 that disambiguates an HMAC failure
 *   B   mls_varint-prefixed opaque
 *   C   mls_varint-prefixed opaque
 *   D   mls_varint-prefixed opaque      THE 64-BYTE HMAC FIELD
 * </pre>
 *
 * <p>Field order and widths are settled. What is <b>not traced</b> is the framing in front of
 * A — whether a tag byte and/or an outer length precedes it. The deserializer begins at A and reads
 * no tag, so the slice it is handed starts at A; but that slice comes from an owned buffer whose
 * fill site is inside an outlined fragment we could not follow. Supporting but not conclusive: in
 * the whole 2352-byte-frame receive function, <em>no</em> tag byte is read before the parse, and the
 * only two tag tests against {@code 2} both sit AFTER it on a different stack slot — consistent
 * with a
 * decoded in-memory discriminant rather than a wire byte.
 *
 * <p>That the measured opaque (the one feeding the message-content lookup) is one of B or
 * C is an <b>inference</b>. <b>Which</b> of B or C it is has not been mapped, and is deliberately
 * not guessed here.
 *
 * <p><b>The practical consequence, stated plainly:</b> {@link #readOpaque} requires the opaque to
 * consume its input EXACTLY. If the present form really is {@code [tag][A][B][C][D]}, then no single
 * length-prefixed opaque consumes it exactly and {@link #parse} returns {@code null} — i.e. our
 * parser would call a Google Messages resend malformed. <b>{@link #parse} is correct for the ABSENT form,
 * which is 100% of the 4,925 inbound AADs we have ever observed, and is probably an incomplete model
 * of the PRESENT one.</b> It is left as-is rather than rewritten to the four-field shape because the
 * outer anchor is unresolved, and a one-byte error there would misclassify every real resend
 * silently — the exact failure this class exists to prevent.
 */
public final class MlsResentMessage {

    private MlsResentMessage() {}

    /** The trailing slot when there is no resent-message component. Every ordinary message. */
    public static final int TAG_ABSENT = 0x00;

    /**
     * The resent-message variant — {@code 0x02}, and that is an inference (see the class doc).
     *
     * <p><b>Falsifier:</b> one replay of {@code 02} where a capture has {@code 00}. Read it as TWO
     * outcomes, not pass/fail: {@code "does not contain a resent message"} again means the TAG is
     * wrong, while a <em>parse</em> error means the tag was right and the PREFIX width is wrong.
     */
    public static final int TAG_RESENT = 0x02;

    /** Google Messages' rejection when the component is missing — the string a wrong tag produces. */
    public static final String ERR_NOT_PRESENT =
            "Authenticated data struct for resent message does not contain a resent message";

    /** The HMAC field is exactly this long; anything else is its own error. */
    public static final int HMAC_FIELD_LEN = 64;
    /** {@code [0..32)} is the KEY, in the clear. */
    public static final int HMAC_KEY_LEN = 32;

    /** Which length-prefix encoding parsed. {@link #UNKNOWN} when the payload is empty. */
    public enum PrefixWidth { U8, U16, VARINT, UNKNOWN }

    /** One decoded component. */
    public static final class Parsed {
        public final int tag;
        public final byte[] payload;
        /** Which prefix encoding produced {@link #payload} — the measurement, not a setting. */
        public final PrefixWidth width;
        /** True when more than one candidate width parsed the same bytes cleanly. */
        public final boolean ambiguous;

        Parsed(final int tag, final byte[] payload, final PrefixWidth width,
                final boolean ambiguous) {
            this.tag = tag;
            this.payload = payload;
            this.width = width;
            this.ambiguous = ambiguous;
        }

        public boolean present() { return tag != TAG_ABSENT; }

        @Override public String toString() {
            return "resent{tag=0x" + Integer.toHexString(tag)
                    + " payload=" + (payload == null ? 0 : payload.length) + "B"
                    + " width=" + width + (ambiguous ? " AMBIGUOUS" : "") + "}";
        }
    }

    /**
     * Decode the trailing component from the bytes at and after the trailing slot.
     *
     * <p><b>This deliberately tries every candidate prefix width and tells you which matched.</b>
     * The width is not established, and a decoder that silently assumed one would answer the open
     * question by fiat and then be believed. When more than one width consumes the buffer exactly,
     * {@link Parsed#ambiguous} is set — which is itself the honest outcome for a short payload,
     * where {@code u8} and {@code varint} coincide.
     *
     * @return the component, or null if the slot is malformed
     */
    public static Parsed parse(final byte[] tail) {
        if (tail == null || tail.length == 0) return null;
        final int tag = tail[0] & 0xFF;
        if (tag == TAG_ABSENT) {
            // Absent is a bare tag; anything after it is not ours to interpret.
            return new Parsed(TAG_ABSENT, new byte[0], PrefixWidth.UNKNOWN, false);
        }
        final byte[] rest = Arrays.copyOfRange(tail, 1, tail.length);
        final List<PrefixWidth> matched = new ArrayList<>();
        byte[] first = null;
        for (final PrefixWidth w : new PrefixWidth[] {
                PrefixWidth.U8, PrefixWidth.U16, PrefixWidth.VARINT }) {
            final byte[] p = readOpaque(rest, w);
            if (p != null) {
                matched.add(w);
                if (first == null) first = p;
            }
        }
        if (matched.isEmpty()) return null;
        return new Parsed(tag, first, matched.get(0), matched.size() > 1);
    }

    /** Read a length-prefixed opaque that consumes {@code b} EXACTLY, or null. */
    private static byte[] readOpaque(final byte[] b, final PrefixWidth w) {
        if (b == null || b.length == 0) return null;
        final int len;
        final int off;
        switch (w) {
            case U8:
                len = b[0] & 0xFF;
                off = 1;
                break;
            case U16:
                if (b.length < 2) return null;
                len = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
                off = 2;
                break;
            case VARINT: {
                final int first = b[0] & 0xFF;
                final int n = 1 << (first >>> 6);
                if (n > 4 || b.length < n) return null;
                int v = first & 0x3F;
                for (int i = 1; i < n; i++) v = (v << 8) | (b[i] & 0xFF);
                len = v;
                off = n;
                break;
            }
            default:
                return null;
        }
        // EXACT consumption only. A prefix that merely "fits" would make every width match and the
        // instrument would report nothing.
        if (len < 0 || off + len != b.length) return null;
        return Arrays.copyOfRange(b, off, off + len);
    }

    /** The outcome of looking at a resend's MAC field. */
    public enum MacVerdict {
        /** The MAC matched: this resend IS for us. */
        FOR_ME,
        /**
         * The MAC did not match. <b>Benign — this resend is for another member.</b> Not an error, and
         * emphatically not a reason to send a negative receipt.
         */
        NOT_FOR_ME,
        /** The field is present but not {@link #HMAC_FIELD_LEN} bytes — Google Messages' error 105. */
        INVALID_LENGTH,
        /** The field is empty — Google Messages takes a separate earlier path for this. */
        EMPTY
    }

    /**
     * Classify a resend's MAC field.
     *
     * <p><b>Note what this method cannot return: a verification FAILURE.</b> That is deliberate and
     * is the whole point of the class. On the receive side a mismatch means the resend is for
     * someone else, so the only failure modes here are structural (empty / wrong length).
     *
     * <p>Google Messages does have a failure branch — it compares a recipient discriminator and raises
     * {@code ResentMessageHmacVerificationFailed} when the resend says it IS for us and the MAC
     * still fails. We have not identified that field, so we cannot distinguish "not for me" from
     * "for me and corrupt". <b>Defaulting to NOT_FOR_ME is the safe direction and the choice is
     * not arbitrary:</b> treating a for-me-but-corrupt resend as not-for-me loses one message that
     * the ordinary FTD path will report anyway, whereas treating a not-for-me resend as an error
     * emits a negative receipt for a healthy message and provokes a resend of the resend — in a
     * group, once per non-target member, every time.
     *
     * @param hmacField the 64-byte field: {@code [0..32)} key, {@code [32..64)} tag
     * @param macdData  the recipient-specific bytes WE compute for ourselves
     */
    public static MacVerdict classify(final byte[] hmacField, final byte[] macdData) {
        if (hmacField == null || hmacField.length == 0) return MacVerdict.EMPTY;
        if (hmacField.length != HMAC_FIELD_LEN) return MacVerdict.INVALID_LENGTH;
        final byte[] key = Arrays.copyOfRange(hmacField, 0, HMAC_KEY_LEN);
        final byte[] want = Arrays.copyOfRange(hmacField, HMAC_KEY_LEN, HMAC_FIELD_LEN);
        final byte[] got = mac(key, macdData == null ? new byte[0] : macdData);
        if (got == null) return MacVerdict.NOT_FOR_ME;
        // MessageDigest.isEqual is the constant-time compare. Google Messages uses a plain memcmp here, which
        // is fine for it because the key is public and the value authenticates nothing — but "they
        // are not constant-time" is an observation about Google Messages, not a licence to copy it.
        return MessageDigest.isEqual(got, want) ? MacVerdict.FOR_ME : MacVerdict.NOT_FOR_ME;
    }

    /** Which candidate MAC input matched, if any. */
    public static final class Selection {
        public final MacVerdict verdict;
        /** The name of the candidate that matched, or null. <b>This is the measurement.</b> */
        public final String candidate;

        Selection(final MacVerdict v, final String c) { this.verdict = v; this.candidate = c; }

        @Override public String toString() {
            return verdict + (candidate == null ? "" : " via '" + candidate + "'");
        }
    }

    /**
     * Try a NAMED SET of candidate MAC inputs and report which one matched.
     *
     * <p><b>Why this shape rather than a single input.</b> The recipient-specific bytes the sender
     * MACs are unidentified, and that is the one thing blocking correctly-targeted resends. A
     * receive path hard-coded to one guess would be wrong silently — every resend would come back
     * NOT_FOR_ME, which is indistinguishable from the honest "this one really is for someone else".
     *
     * <p>Trying a named set inverts that: on a captured Google Messages resend addressed to us, <b>whichever
     * candidate matches IS the answer</b>, and it identifies itself. Same pattern as the prefix
     * width — build the instrument, let the wire settle the question, do not pick and hope.
     *
     * <p>The complementary method, for anyone who can see two receivers at once: compare the MAC
     * input across two recipients of the same resend; the differing bytes are recipient-specific.
     *
     * <p>A no-match is still {@link MacVerdict#NOT_FOR_ME} — benign, never an error. See
     * {@link #classify} for why that direction is the safe one.
     */
    public static Selection select(final byte[] hmacField,
            final java.util.Map<String, byte[]> candidates) {
        if (hmacField == null || hmacField.length == 0) {
            return new Selection(MacVerdict.EMPTY, null);
        }
        if (hmacField.length != HMAC_FIELD_LEN) {
            return new Selection(MacVerdict.INVALID_LENGTH, null);
        }
        if (candidates != null) {
            for (final java.util.Map.Entry<String, byte[]> e : candidates.entrySet()) {
                if (classify(hmacField, e.getValue()) == MacVerdict.FOR_ME) {
                    return new Selection(MacVerdict.FOR_ME, e.getKey());
                }
            }
        }
        return new Selection(MacVerdict.NOT_FOR_ME, null);
    }

    /** {@code HMAC-SHA256(key, data)}, or null if the platform lacks it (it does not). */
    public static byte[] mac(final byte[] key, final byte[] data) {
        try {
            final Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (final Exception impossible) {
            return null;
        }
    }

    /**
     * Encode the component — <b>refuses unless the caller states the prefix width</b>.
     *
     * <p><b>ONE of the two reasons this gave for refusing is gone (2026-09-10); the other is not.</b>
     * It used to cite an unestablished width AND an unidentified recipient-specific MAC input. The
     * WIDTH is settled — it is {@code mls_varint} — so the explicit-width
     * argument is now a GUARD against callers assuming rather than a confession of ignorance; pass
     * {@link PrefixWidth#VARINT}. The MAC INPUT is only HALF answered: we know WHICH slice is MAC'd
     * (the original message id), but the question this file asked was for "the recipient-specific
     * bytes", and the MAC'd datum is recipient-INVARIANT — so the thing it was really asking for is
     * still missing. <b>Emission is NOT unblocked on that basis.</b>
     *
     * <p><b>Emission is still blocked, but by three OTHER things</b> — see the class doc:
     * (i) what value goes in FIELD A — NOT a selector, but the u32 that disambiguates
     * an HMAC failure — i.e. what the receiver compares it against; get it wrong and the resend
     * addresses nobody; (ii) the roles
     * and contents of fields B and C, unmapped; (iii) the outer framing, length-vs-tag, unresolved.
     * This is not wired into the send path and must not be until those are answered.
     */
    public static byte[] encode(final int tag, final byte[] payload, final PrefixWidth width) {
        if (width == null || width == PrefixWidth.UNKNOWN) {
            throw new IllegalArgumentException(
                    "the resent-message length-prefix width is not established — state it explicitly "
                            + "(u8/u16/varint) rather than taking a default that would be a guess");
        }
        final byte[] p = payload == null ? new byte[0] : payload;
        final byte[] pre;
        switch (width) {
            case U8:
                pre = new byte[] { (byte) p.length };
                break;
            case U16:
                pre = new byte[] { (byte) (p.length >>> 8), (byte) p.length };
                break;
            default:
                pre = MlsAppMessage.mlsVarint(p.length);
                break;
        }
        final byte[] out = new byte[1 + pre.length + p.length];
        out[0] = (byte) tag;
        System.arraycopy(pre, 0, out, 1, pre.length);
        System.arraycopy(p, 0, out, 1 + pre.length, p.length);
        return out;
    }
}

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
 * One row of the resend register: a resend is a NEW message with a NEW id, linked to the message it
 * replaces (rework item 7.4, §11.1, invariant 63).
 *
 * <h2>Why a resend cannot reuse the original id</h2>
 *
 * <p>The obvious implementation — re-encrypt the id the peer reported — is wrong twice over, and the
 * second reason is the one that bites silently:
 *
 * <ol>
 *   <li><b>Invariant 62.</b> A cached ciphertext already exists for that id. Encrypting it again
 *       consumes a second sender-ratchet generation and puts two ciphertexts on the wire under one
 *       id.</li>
 *   <li><b>The server dedupes on message id.</b> A resend carrying an id the server has already
 *       seen is dropped <em>before</em> any peer sees it. We have watched this happen: an era
 *       advance reissued used ids and the resends vanished with no error anywhere, which cost a long
 *       detour chasing an interop fault that did not exist.</li>
 * </ol>
 *
 * <p>So the resend gets a fresh id and the correlation moves into this register. The wire-level
 * correlation (the {@code ResentMessage} sub-structure of the AAD, §11.3a) is a separate, capture-
 * blocked item — this record is what the HOST needs regardless of which wire shape carries it.
 *
 * <h2>WHEN THE RECEIVE HALF IS BUILT: a mismatch is NOT an error — but the MAC is NOT the selector
 * either (heading corrected)</h2>
 *
 * <p><b>This heading used to read "a MAC mismatch is a RECIPIENT SELECTOR".</b> It is false: the MAC
 * is computed over the ORIGINAL MESSAGE ID with a key that travels in the clear beside its own tag,
 * so it PASSES for every member and cannot separate anybody. The recipient selector is <b>field A</b>
 * — a u32 in the resend struct, compared against the client's own copy. <b>A selects; the 64-byte
 * field authenticates.</b> See
 * {@link MlsResentMessage} for the read and {@link MlsResendReceive} for the ordering.
 *
 * <p>The operational advice below SURVIVES unchanged — run the selector test before raising an HMAC
 * failure — because it was always about the BRANCH ORDER, not about which field does the selecting.
 * Note the bullets quote the founding sentence <em>correctly</em> ("it compares two u32s … and
 * branches"). The wrong model was never in the evidence; it was in the heading somebody wrote over
 * it, and then everyone cited the heading.
 *
 * <p><b>Written before the code exists, deliberately</b> — this is the cheapest moment to get it,
 * and the natural implementation is wrong. From Google Messages' own receive/verify function:
 *
 * <ul>
 *   <li>The HMAC field is <b>64 bytes</b>, hard-coded ({@code 0x40}); a wrong length is its own
 *       error (105 {@code InvalidResentMessageHmacLength}) and an empty one takes a separate path.
 *       Bytes {@code [0..32)} are fed INTO the MAC computation; {@code [32..64)} is the tag that is
 *       compared.</li>
 *   <li><b>On mismatch Google Messages does NOT fail.</b> It compares two u32s — {@code resend+0x48}
 *       against {@code client_ctx+0x658} — and branches: <b>UNEQUAL is a benign skip</b> logging
 *       {@code "Resent message not for me with original message id {}"}, and only <b>EQUAL</b>
 *       raises 106 {@code ResentMessageHmacVerificationFailed}.</li>
 * </ul>
 *
 * <p>Note the direction, which is the trap: the error is the EQUAL branch, i.e. the arm where the
 * message IS for us. A receive path that raises an HMAC failure on every mismatch errors on group
 * resends not addressed to it, and if it emits a negative receipt on that error it will FTD messages
 * that are perfectly fine, provoking resends of resends.
 *
 * <p>(This paragraph used to end "every other member in the group fails it <b>by construction</b>",
 * which was the MAC-selector model. Under the corrected read the MAC passes for everyone; what
 * separates members is field A. The hazard is identical either way — it is the branch ORDER that
 * creates it.)
 *
 * <p><b>Therefore: run the selector test BEFORE raising an HMAC failure</b>, and make
 * {@code ResentMessageHmacVerificationFailed} reachable only when the resend is addressed to us.
 *
 * <p><b>THE "CORROBORATION" THAT USED TO BE HERE WAS CIRCULAR, AND IT IS THE MOST INSTRUCTIVE THING
 * IN THIS FILE.</b> It read: our corpus already carried the check <i>"one resend in a 3-member group
 * must produce exactly two 'Resent message not for me' lines"</i>, unexplained — two of three
 * members fail the MAC, so "the sanity check and this branch are the same mechanism seen from two
 * sides". <b>That check has never been run</b>, by anyone. It was not an observation
 * corroborating the model; it was a PREDICTION OF the model, written down elsewhere and then read
 * back as independent support. Two sides of the same derivation are not two directions of evidence.
 * The check is now tracked as the measurement it always was — and the count it
 * would settle is withdrawn until it runs.
 *
 * <p>Also noted — Google Messages' tag compare is {@code memcmp}, i.e. not constant-time; that is an
 * observation about Google Messages, not a licence for us to copy it.
 *
 * <p><b>The "unknown HKDF label" that used to be listed here does not exist.</b> There is no
 * derivation: the MAC key travels IN THE CLEAR as the first 32 bytes of the 64-byte field, and
 * BoringSSL's {@code mac(key, data)} has nowhere for an info string to go. Structural, not a failed
 * search — see {@link MlsResentMessage}. Leaving it listed sent readers looking for a label that
 * cannot be there.
 *
 * <h2>The counter is derived, never stored incrementally</h2>
 *
 * <p>{@link #ftdResendCount} is {@code max(siblings) + 1} computed at insert time from rows already
 * in the register. That is what makes it durable across process death <b>by construction</b>: there
 * is no in-memory counter to lose. The counter this replaced lived in a {@code HashMap} and reset on
 * every restart, so a peer that had failed four times looked like a first-time failure after a
 * reboot and the escalation ladder never reached its top rung.
 *
 * <h2>Chain shape</h2>
 *
 * <p>Two links, not one, and they answer different questions:
 *
 * <ul>
 *   <li>{@link #originalRcsMessageId} — the ROOT of the chain, i.e. the message the user actually
 *       sent. Every resend in a chain carries the same root, which is what makes "the siblings" a
 *       single indexed query rather than a recursive walk.</li>
 *   <li>{@link #manualResendOfRcsMessage} — the IMMEDIATE parent, the id this attempt replaces. For
 *       the first resend that equals the root; for the second it is the first resend's id.</li>
 * </ul>
 *
 * <p>The distinction is load-bearing on the receive side: when a peer reports a decrypt failure it
 * names whichever id it saw, which for a second failure is a RESEND's id and not the root's. The
 * body is only recoverable from the root's chat row, so a report must be resolved root-ward before
 * anything is looked up — see {@code MlsResendLedger.rootOf}.
 */
public final class MlsResendRecord {

    /** The NEW id minted for this resend. Same namespace as the app's {@code rcs_message_id}. */
    public final String rcsMessageId;
    /** The root of the chain — the id of the message the user sent. Never a resend's id. */
    public final String originalRcsMessageId;
    /** The id this attempt replaces: the root for the first resend, the previous resend after. */
    public final String manualResendOfRcsMessage;
    /**
     * The member this resend is FOR.
     *
     * <p>Bookkeeping only. §11.3a is explicit that the resend still goes to the whole group and this
     * is not a transport address — the addressing is an HMAC every recipient evaluates. Treating it
     * as a destination would produce a per-recipient fan-out Google Messages never sends.
     */
    public final String resendRecipientAddress;
    /** The target's client id, where one is known. Empty when the peer has a single client. */
    public final String resendRecipientClientId;
    /** {@code max(siblings) + 1}, computed at insert. The first resend of a message is 1. */
    public final int ftdResendCount;
    /** When it was recorded, ms since epoch. Diagnostic — never a staleness input. */
    public final long resendTimestampMs;

    public MlsResendRecord(final String rcsMessageId, final String originalRcsMessageId,
            final String manualResendOfRcsMessage, final String resendRecipientAddress,
            final String resendRecipientClientId, final int ftdResendCount,
            final long resendTimestampMs) {
        this.rcsMessageId = nonNull(rcsMessageId);
        this.originalRcsMessageId = nonNull(originalRcsMessageId);
        this.manualResendOfRcsMessage = nonNull(manualResendOfRcsMessage);
        this.resendRecipientAddress = nonNull(resendRecipientAddress);
        this.resendRecipientClientId = nonNull(resendRecipientClientId);
        this.ftdResendCount = Math.max(0, ftdResendCount);
        this.resendTimestampMs = resendTimestampMs;
    }

    /**
     * The count a NEW resend should carry, given the rows already in its chain.
     *
     * <p>{@code max + 1} rather than {@code size + 1} deliberately. They differ exactly when a row
     * has been evicted or a chain was migrated in, and in that case the count must keep climbing —
     * it drives an escalation ladder, so a counter that dips because a row went missing would hand a
     * long-failing conversation back to the bottom rung.
     *
     * @param siblings every record sharing this chain's root; {@code null} or empty means the first
     *                 resend
     * @return 1 for the first resend, else one past the highest count present
     */
    public static int nextFtdResendCount(final Iterable<MlsResendRecord> siblings) {
        int max = 0;
        if (siblings != null) {
            for (final MlsResendRecord r : siblings) {
                if (r != null && r.ftdResendCount > max) max = r.ftdResendCount;
            }
        }
        return max + 1;
    }

    /**
     * Which attempts' send material a TERMINAL event may release.
     *
     * <h2>The sign of the terminal decides the scope, and it used not to</h2>
     *
     * <p>{@code releaseSealed} was called for both terminals — a positive delivery receipt and a
     * permanent send failure — and released the WHOLE chain either way. For a delivery that is
     * right and deliberate: the receipt names the attempt that got through, the
     * message arrived, and every earlier attempt's bytes are dead weight no receipt will ever quote
     * again. For a FAILURE it is backwards. A failure means the message did <b>not</b> arrive, so
     * the chain's recovery material is precisely what is still needed, and destroying it is how a
     * §10.3 ladder stops at rung 1.
     *
     * <h2>Why a resend's OWN body is not the thing at stake</h2>
     *
     * <p>Nothing ever reads it. {@code resendOriginal} resolves the reported id root-ward first and
     * then looks the body up under the ROOT, because that is the only attempt with a chat row to
     * fall back on — a resend's id is a bare UUID minted by the ledger and no chat row is ever
     * written for it. So releasing the failed attempt's own material is pure garbage collection and
     * stays, which is what keeps a permanently-failed message from pinning a slot. What must not go
     * is the rest of the chain.
     *
     * <h2>The one asymmetric case: a failure naming the ROOT of a live chain</h2>
     *
     * <p>Then we release nothing. A resend row exists only because a peer received the root and
     * reported it, so "the provider permanently failed to send it" and "a peer is asking us to
     * resend it" cannot both be true — and between a contradicted failure report and the only bytes
     * that can still repair the message, the bytes win. The retention sweep still bounds them.
     *
     * @param terminalId the id the terminal event named; may be a root or any resend in the chain
     * @param root       {@code MlsResendLedger.rootOf(terminalId)} — equal to {@code terminalId}
     *                   when it is not a known resend
     * @param siblings   every ledger row sharing {@code root}; {@code null} or empty means no chain
     * @param delivered  {@code true} for a positive receipt, {@code false} for a permanent failure
     * @return the ids to release, in a stable order; possibly empty, never {@code null}
     */
    public static java.util.Set<String> materialToRelease(final String terminalId,
            final String root, final Iterable<MlsResendRecord> siblings, final boolean delivered) {
        final java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (terminalId == null || terminalId.isEmpty()) return out;
        if (delivered) {
            out.add(terminalId);
            if (root != null && !root.isEmpty()) out.add(root);
            if (siblings != null) {
                for (final MlsResendRecord r : siblings) {
                    if (r != null && r.rcsMessageId != null && !r.rcsMessageId.isEmpty()) {
                        out.add(r.rcsMessageId);
                    }
                }
            }
            return out;
        }
        // A failure naming the ROOT of a chain that already has resends: keep everything. See the
        // asymmetric case above — the report contradicts the ledger, and the ledger is evidence a
        // peer actually saw this message.
        if (rootOfALiveChain(terminalId, root, siblings)) return out;
        out.add(terminalId);
        return out;
    }

    /** True when {@code terminalId} is this chain's root AND the chain has at least one resend. */
    private static boolean rootOfALiveChain(final String terminalId, final String root,
            final Iterable<MlsResendRecord> siblings) {
        if (root != null && !root.isEmpty() && !root.equals(terminalId)) return false;
        if (siblings == null) return false;
        for (final MlsResendRecord r : siblings) {
            if (r != null && r.rcsMessageId != null && !r.rcsMessageId.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Whether this record targets {@code address}.
     *
     * <p>Compared on the address alone when {@code clientId} is empty: a peer whose client id we
     * never learned must still accumulate a count, and requiring an exact client match would start a
     * fresh chain on every attempt and pin the ladder at rung one forever.
     */
    public boolean targets(final String address, final String clientId) {
        if (address == null || !address.equals(resendRecipientAddress)) return false;
        if (clientId == null || clientId.isEmpty() || resendRecipientClientId.isEmpty()) return true;
        return clientId.equals(resendRecipientClientId);
    }

    @Override public String toString() {
        return "resend{" + rcsMessageId + " of=" + originalRcsMessageId
                + " replaces=" + manualResendOfRcsMessage
                + " to=" + resendRecipientAddress
                + (resendRecipientClientId.isEmpty() ? "" : "/" + resendRecipientClientId)
                + " n=" + ftdResendCount + "}";
    }

    private static String nonNull(final String s) { return s == null ? "" : s; }
}

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
package com.android.messaging.rcs.e2ee;

/**
 * <b>May this send go out in the clear?</b> — the send-side counterpart of the membership
 * routing in {@code MlsProviderTransport.changeGroupMembership} (the defect it
 * fixes is the same one, one row over and in a worse place).
 *
 * <h2>MANY callers, ONE table — the class name says GROUP and that is now history, not scope</h2>
 *
 * <p>It was written for the group TEXT path and the same defect turned out to cover group MEDIA and
 * 1:1 MEDIA as well, with the location and reaction paths behind them.
 * Nothing in {@link #decide} ever read a body or a group id, so all
 * of them are answered here, through exactly TWO entry points:
 *
 * <ul>
 *   <li>{@code MlsProviderTransport.groupSendVerdict} — anything addressed to an {@code
 *       rcs_group_id};</li>
 *   <li>{@code MlsProviderTransport.oneToOneSendVerdict} — anything addressed to a peer, whose
 *       engine input is the weaker "do we hold 1:1 state right now" question for the reason
 *       recorded there.</li>
 * </ul>
 *
 * <p><b>The ENTRY POINTS are enumerated here and the CALL SITES deliberately are not.</b> A list of
 * callers in a javadoc is a statement about mutable state in a file nobody re-checks: it was wrong
 * within a day of being written, because two more compose paths turned out to have the same defect
 * while this paragraph was being reviewed. {@code grep} answers "who calls this" correctly forever.
 *
 * <p><b>The two media callers do NOT treat {@link Verdict#SEAL} as permission to send</b>, and that
 * asymmetry is deliberate rather than an oversight to be tidied away. There is no media seal path
 * yet, so for them SEAL means "the engine could seal this and we cannot" — which is a refusal, not a
 * send. They gate on {@link Verdict#PLAINTEXT} alone. No fourth verdict was added to express it:
 * the table below is a statement about the CONVERSATION and would be wrong to make a statement
 * about the caller's feature set.
 *
 * <h2>The defect this exists to end</h2>
 *
 * <p>Every UI-composed group text went out as {@code text/plain}. {@code
 * InsertNewMessageAction.tryInsertSendingRcsGroupMessage} handed the raw string to {@code
 * ProviderTransport.sendGroupMessage} without ever consulting MLS state, so a group the app was
 * drawing a PADLOCK on — because an inbound MLS message had latched {@code
 * conversations.encryption_protocol} — sent its replies unencrypted, reported success, and wrote an
 * ordinary RCS row. The peer could tell; the user could not.
 *
 * <h2>Two predicates, and they answer different questions</h2>
 *
 * <p>Keeping them apart is the whole content of this class, because collapsing them is how both
 * halves of the defect were built.
 *
 * <ul>
 *   <li><b>{@link SealCapability} — the ENGINE.</b> Do we hold MLS group state for this
 *       {@code rcs_group_id} RIGHT NOW, and is the conversation not downgraded? That is the only
 *       authority on whether a seal is possible: the engine remains the authority on whether a
 *       group is MLS.</li>
 *   <li><b>{@link MlsLatch} — the APP'S OWN UI STATE.</b> Has this conversation latched the MLS bit,
 *       i.e. is the app currently telling the user the thread is end-to-end encrypted? That is what
 *       the latch IS. It must never be asked the ENGINE's question; that does not forbid
 *       asking it its own, which is what the membership site already does.</li>
 * </ul>
 *
 * <h2>The rule</h2>
 *
 * <p><b>Seal if we can. Otherwise refuse iff the app has told the user this thread is MLS.
 * Otherwise plaintext.</b> {@link #decide} is those three lines and nothing else, so the whole
 * 4&times;3 table is checkable by walking it.
 *
 * <p><b>{@link Verdict#SEAL} does not consult the latch</b>, deliberately. A group we joined by
 * Welcome and have neither sent to nor received from has NO latched bit — the bit records that a
 * plane was IN USE, never that one was established (Google Messages' rule, per {@code
 * EncryptionProtocolBits}) — and its peers still expect {@code message/mls}. The engine answer
 * covers that case and the latch cannot.
 *
 * <h2>What must NOT reach {@link Verdict#REFUSE}: an ordinary non-MLS RCS group</h2>
 *
 * <p>It cannot, and the reason is structural rather than careful: such a conversation has no MLS
 * group state ({@link SealCapability#NO_MLS_STATE}) and has never latched the MLS bit
 * ({@link MlsLatch#CLEAR}), because nothing sets that bit but an MLS message in one direction or the
 * other. Both inputs are at their default and the verdict is {@link Verdict#PLAINTEXT}. A device
 * that has never adopted an MLS identity answers {@link SealCapability#NO_MLS_IDENTITY} and
 * {@link MlsLatch#CLEAR} and therefore cannot refuse anything at all.
 *
 * <p><b>The trap in that sentence is the word MLS, and it is why {@link MlsLatch} is not "is the
 * padlock lit".</b> {@code encryption_protocol} is a BITSET — bit0 Etouffee/scytale, bit1 MLS — and
 * the padlock is drawn on {@code != 0}, i.e. on EITHER. An Etouffee group is genuinely encrypted,
 * by the PROVIDER, from the same plaintext string this path hands down; refusing it would break a
 * working encrypted conversation in the name of encryption. So the input here is
 * {@code EncryptionProtocolBits.mlsBit()} alone, exactly as the membership site reads it.
 *
 * <h2>Error directions</h2>
 *
 * <p>An UNREADABLE latch is treated as CLEAR and falls through to plaintext, matching the
 * membership site for the reason recorded there: refusing every group send on a database hiccup is the worse error,
 * and it would land on the ordinary groups this must not touch. The engine input is not affected by
 * that read, so a healthy MLS group still seals with an unreadable bit.
 *
 * <p>A false CLEAR therefore degrades to today's behaviour and a false LATCHED costs one visible,
 * retryable refusal. The asymmetry is deliberate and is the opposite way round from a membership
 * op: there, a refusal costs a toast; here it costs a message, and a wrong plaintext costs the
 * user's content.
 */
public final class MlsSendRouting {

    private MlsSendRouting() {}

    /** What the ENGINE can do for this {@code rcs_group_id} right now. */
    public enum SealCapability {
        /** We hold MLS group state and the conversation is not downgraded — a seal is possible. */
        SEALABLE,
        /**
         * No MLS group state for this conversation. Covers a group we never joined, a group we were
         * dropped from (REJOIN), and a conversation that is simply not encrypted.
         */
        NO_MLS_STATE,
        /**
         * We hold state but the conversation is DOWNGRADED — RCC.16 &sect;9.1.1 forbids sending
         * encrypted on it, so a seal is not merely impossible, it is illegal.
         */
        DOWNGRADED,
        /**
         * An MLS identity exists but the engine would not open a session, so we CANNOT TELL whether
         * this conversation is encrypted. Distinct from {@link #NO_MLS_IDENTITY} for the reason
         * {@code changeGroupMembership} separates them: routing "we do not know" together with
         * "there is nothing to know" is what made a cold process read an MLS group as plaintext.
         */
        ENGINE_UNAVAILABLE,
        /**
         * No MLS identity was ever adopted on this device, so no conversation on it can be MLS.
         * Plaintext is not a fallback here, it is the right answer.
         */
        NO_MLS_IDENTITY,
    }

    /** Whether the app is currently presenting this conversation as MLS-encrypted. */
    public enum MlsLatch {
        /** {@code encryption_protocol}'s MLS bit is set — the app is drawing a padlock on this. */
        LATCHED,
        /** The bit is clear. The app has never told the user this thread is MLS. */
        CLEAR,
        /** The bit could not be read. "We do not know" — NOT evidence that it is clear. */
        UNREADABLE,
    }

    /** What the caller must do with the message. */
    public enum Verdict {
        /** Seal it for the group and send the ciphertext. Never {@code text/plain}. */
        SEAL,
        /** Send it as an ordinary plaintext RCS group message — unchanged behaviour. */
        PLAINTEXT,
        /**
         * Do NOT send it, in any form. The app has told the user this conversation is encrypted and
         * we cannot honour that, so the message must be left unsent and visibly failed rather than
         * silently downgraded — the ruling this board already applied to the 1:1 path
         * ({@code InsertNewMessageAction}: "do NOT silently downgrade to plaintext — the user was
         * told this conversation is encrypted").
         *
         * <p><b>Falling back to MMS is a downgrade too.</b> The group-text fork reaches the
         * unchanged group-MMS path by returning false, so a refusal expressed that way would put the
         * same cleartext on a different transport. The caller must not express it that way.
         */
        REFUSE,
    }

    /**
     * The whole decision. Three lines, so the table below is the specification and not a summary of
     * one.
     *
     * <pre>
     *   engine             latch        verdict
     *   -----------------------------------------
     *   SEALABLE           any          SEAL
     *   NO_MLS_STATE       LATCHED      REFUSE      &lt;- the REJOIN lie, one row over
     *   NO_MLS_STATE       CLEAR        PLAINTEXT   &lt;- the ordinary RCS group
     *   NO_MLS_STATE       UNREADABLE   PLAINTEXT
     *   DOWNGRADED         LATCHED      REFUSE      &lt;- &sect;9.1.1 forbids the seal, the padlock is still up
     *   DOWNGRADED         CLEAR        PLAINTEXT   &lt;- a completed downgrade: plaintext is honest
     *   DOWNGRADED         UNREADABLE   PLAINTEXT
     *   ENGINE_UNAVAILABLE LATCHED      REFUSE      &lt;- we cannot tell, and the app claims encryption
     *   ENGINE_UNAVAILABLE CLEAR        PLAINTEXT
     *   ENGINE_UNAVAILABLE UNREADABLE   PLAINTEXT
     *   NO_MLS_IDENTITY    LATCHED      REFUSE      &lt;- see below; NOT what changeGroupMembership does
     *   NO_MLS_IDENTITY    CLEAR        PLAINTEXT
     *   NO_MLS_IDENTITY    UNREADABLE   PLAINTEXT
     * </pre>
     *
     * <p><b>The one row that departs from {@code changeGroupMembership} is
     * {@code NO_MLS_IDENTITY + LATCHED}</b>, and the departure is named rather than silently
     * normalised. There, no identity returns PLAINTEXT before the bit is even read, because nothing
     * on the device can be MLS and a roster change exposes no message content. Here the latched bit
     * says the app IS drawing a padlock on this thread, and sending its content in the clear under
     * that padlock is precisely the defect. The refusal is loud and the lie is silent, so the refusal
     * is the better failure — and its remedy already exists and is the right one: a downgrade clears
     * the bit and the conversation returns to honest plaintext.
     *
     * @param engine what the engine can do — never null
     * @param latch what the app is telling the user — never null
     */
    public static Verdict decide(final SealCapability engine, final MlsLatch latch) {
        if (engine == null || latch == null) {
            // A caller that cannot state its inputs has not asked a question this can answer.
            // Refusing is the only answer that cannot put cleartext on the wire by accident.
            return Verdict.REFUSE;
        }
        if (engine == SealCapability.SEALABLE) {
            return Verdict.SEAL;
        }
        return latch == MlsLatch.LATCHED ? Verdict.REFUSE : Verdict.PLAINTEXT;
    }

    /**
     * The {@link MlsLatch} for a bit that was read, or {@link MlsLatch#UNREADABLE} when the read
     * failed.
     *
     * <p><b>Takes the already-extracted MLS bit, not {@link EncryptionProtocolBits}</b>, even though
     * that class is right here in the same package. The narrower argument is what stops a caller
     * passing {@code isE2eeEncrypted()} — the {@code != 0} the padlock is actually drawn on — which
     * would fold Etouffee in and refuse a conversation the provider encrypts perfectly well. It
     * also keeps this class free of a database read, which is what lets the whole table be walked
     * on the host.
     *
     * @param mlsBit the MLS bit, or {@code null} when the read failed
     */
    public static MlsLatch latchOf(final Boolean mlsBit) {
        if (mlsBit == null) return MlsLatch.UNREADABLE;
        return mlsBit ? MlsLatch.LATCHED : MlsLatch.CLEAR;
    }
}

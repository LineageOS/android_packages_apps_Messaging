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
package com.android.messaging.rcs;

/**
 * What {@code message_status} an RCS row may be left in, and by whom.
 *
 * <p><b>The defect this exists to end.</b> {@code InsertNewMessageAction} has send routes that
 * answer SYNCHRONOUSLY — the provider call returns accepted or refused before the chat row is even
 * inserted — and then threw that answer away, inserting every row at
 * {@code BUGLE_STATUS_OUTGOING_YET_TO_SEND} (4) via {@code MessageData.updateSendingMessage}.
 * Nothing on the RCS transport could move a row out of 4. So the bubble read "Sending…" forever —
 * for a message that had been sent, and equally for one that had measurably failed.
 *
 * <p><b>THAT LIST IS THE STATE BEFORE THIS CLASS EXISTED, AND TWO OF ITS FOUR CLAUSES ARE NO LONGER
 * TRUE — two of them because of this class.</b> It is spelled out in the past tense because it kept
 * being quoted as current: this paragraph's earlier present-tense form was cited back at the author
 * as settling a live question, and one of its clauses travelled three hops into a code comment in
 * another file. As of 2026-09-13:
 * <ul>
 *   <li>the provider fires no status callback on the MLS ciphertext path — <b>still true</b>;</li>
 *   <li>{@code ProcessPendingMessagesAction} excludes {@code TRANSPORT_RCS} by name — <b>still
 *       true</b>, and {@code RcsSendStatusGuardTest} pins it;</li>
 *   <li>{@code FixupMessageStatusOnStartupAction} rewrote only 5/6 — <b>NO LONGER TRUE.</b> It
 *       gained a third arm sweeping {@code TRANSPORT_RCS} rows at 4/7 to FAILED at cold start;</li>
 *   <li>{@code ResendMessageAction} was reachable only from 8 — <b>NO LONGER THE POINT.</b> It now
 *       refuses a {@code TRANSPORT_RCS} row outright rather than re-stranding it.</li>
 * </ul>
 *
 * <p><b>Pure Java, no Android imports</b>, so the decision is host-testable
 * ({@code messaging2-rcs-send-status-host-tests}). The three status families it names are duplicated
 * here rather than imported because their owners are all Android-coupled — {@code MessageData}
 * (ContentValues/Parcel/Uri), {@code RcsConstants}, and the generated
 * {@code IRcsProviderCallback} stub. {@code RcsSendStatusMirrorTest} reads those three sources and
 * fails if any value here stops matching, so the duplication cannot drift silently.
 *
 * <p><b>The PACKAGE is deliberate: {@code com.android.messaging.rcs}, not {@code rcs.e2ee}.</b>
 * Recorded because it was right by accident until the cost of getting it wrong was measured. The
 * E2EE package is an audited layer, so anything declared inside it — and anything that has to
 * import it — is audited too. Moving this class in drags its whole importing surface along: one
 * attempt pulled {@code InsertNewMessageAction}, a 2,000-line action, into the layer and turned
 * six unrelated constants red. It must stay outside. This is not encryption policy — it is the RCS
 * row status contract, of which the encrypted send merely happens to be the worst-affected caller.
 */
public final class RcsSendStatus {

    private RcsSendStatus() {}

    // ---- mirror of MessageData.BUGLE_STATUS_OUTGOING_* ----
    /** "Sent" — the transport took it. Rendered as a plain timestamp, not as "Sending…". */
    public static final int BUGLE_STATUS_OUTGOING_COMPLETE    = 1;
    /** Queued for the SMS/MMS send queue. See {@link #strandedOnRcsTransport(int)}. */
    public static final int BUGLE_STATUS_OUTGOING_YET_TO_SEND = 4;
    /** Queued for a delayed SMS/MMS retry. Strands an RCS row for the same reason as 4. */
    public static final int BUGLE_STATUS_OUTGOING_AWAITING_RETRY = 7;
    /** Terminal failure. The only status from which the UI offers a resend affordance. */
    public static final int BUGLE_STATUS_OUTGOING_FAILED      = 8;

    // ---- mirror of RcsConstants.RCS_STATUS_NONE + IRcsProviderCallback.STATUS_* ----
    public static final int RCS_STATUS_NONE   = 0;
    public static final int RCS_STATUS_SENT   = 1;
    public static final int RCS_STATUS_FAILED = 4;

    /**
     * The {@code message_status} for a send whose outcome we have already measured.
     *
     * <p>{@code accepted} is the same evidence the asynchronous path reports as
     * {@code IRcsProviderCallback.STATUS_SENT}: the Tachyon {@code SendMessage} RPC returned OK.
     * It is a claim about the SEND, never about DELIVERY — delivery is only ever asserted by an
     * IMDN, which upgrades the row to {@code BUGLE_STATUS_OUTGOING_DELIVERED} when it arrives. So
     * mapping a measured accept to COMPLETE reports exactly what was measured and no more, and is
     * byte-for-byte the mapping {@code UpdateRcsMessageStatusAction.mapBugleStatus} already applies
     * to the plaintext path's callback.
     */
    public static int bugleStatusForMeasuredHandoff(final boolean accepted) {
        return accepted ? BUGLE_STATUS_OUTGOING_COMPLETE : BUGLE_STATUS_OUTGOING_FAILED;
    }

    /**
     * The {@code rcs_status} for the same measurement, in the provider's own vocabulary.
     *
     * <p>Stamping it makes a synchronously-sent row indistinguishable from one that went through
     * {@code onMessageStatus}, so every query keyed on {@code rcs_status} behaves identically on
     * both paths instead of treating the app-owned path as "no status yet, forever".
     */
    public static int rcsStatusForMeasuredHandoff(final boolean accepted) {
        return accepted ? RCS_STATUS_SENT : RCS_STATUS_FAILED;
    }

    /**
     * True for a {@code message_status} that NOTHING can move on a {@code TRANSPORT_RCS} row.
     *
     * <p>Not a guess about which statuses look transient — it is exactly the pair
     * {@code ProcessPendingMessagesAction.findNextMessageToSend} selects on
     * ({@code STATUS IN (YET_TO_SEND, AWAITING_RETRY)}) while excluding {@code TRANSPORT_RCS} from
     * the same query by name. An RCS row in either status is therefore owned by a queue that has
     * been told to skip it, and it will sit at "Sending…" until something else rewrites it.
     * {@code RcsSendStatusStrandGuardTest} pins that query so the two cannot drift apart.
     */
    public static boolean strandedOnRcsTransport(final int bugleStatus) {
        return bugleStatus == BUGLE_STATUS_OUTGOING_YET_TO_SEND
                || bugleStatus == BUGLE_STATUS_OUTGOING_AWAITING_RETRY;
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp.session;

import com.android.messaging.rcs.carrier.msrp.MsrpMessage;

import java.util.Locale;

/**
 * State machine for one MSRP chat session; {@link CarrierMsrpSessionManager} drives the SIP side
 * and {@link MsrpTlsConnection} the socket. Not synchronized: drive it from the SIP thread.
 *
 * <pre>
 *   IDLE --inviting() / acceptingInvite()--&gt; INVITING --established(info)--&gt; ESTABLISHED
 *   INVITING --inviteRejected()--&gt; CLOSED
 *   ESTABLISHED --closeLocal() / closeRemote() / closeError()--&gt; CLOSING --closed()--&gt; CLOSED
 * </pre>
 */
public final class MsrpChatSession {

    public enum State { IDLE, INVITING, ESTABLISHED, CLOSING, CLOSED }

    public enum CloseReason {
        LOCAL_BYE,
        REMOTE_BYE,
        /** The INVITE was answered 4xx or 5xx; no session opened. */
        INVITE_REJECTED,
        /** The peer's certificate did not match its SDP fingerprint (RFC 4572). */
        FINGERPRINT_MISMATCH,
        /** TLS handshake, socket or I/O failure. */
        TLS_ERROR,
        /** The peer answered a SEND with 4xx or 5xx. */
        PEER_ERROR,
        INACTIVITY,
    }

    public interface Listener {
        /** {@code reason} is null unless the state is closing or closed. */
        void onStateChanged(State newState, CloseReason reason, String detail);

        /**
         * A frame received while established; the listener tells SENDs, REPORTs and responses
         * apart.
         */
        void onMsrpMessage(MsrpMessage message);
    }

    private final String sessionTag;
    private State state = State.IDLE;
    private MsrpSessionInfo info;
    private Listener listener;
    private CloseReason lastReason;
    private String lastDetail;

    public MsrpChatSession(String sessionTag) {
        this.sessionTag = sessionTag == null ? "?" : sessionTag;
    }

    public String getSessionTag()         { return sessionTag; }
    public State getState()               { return state; }
    public MsrpSessionInfo getSessionInfo() { return info; }
    public CloseReason getLastCloseReason() { return lastReason; }
    public String getLastDetail()         { return lastDetail; }

    public void setListener(Listener l) { this.listener = l; }

    /** We send the INVITE. */
    public void inviting() {
        require(State.IDLE, "inviting");
        state = State.INVITING;
        notifyState(null, null);
    }

    /** We received the INVITE and will answer it. */
    public void acceptingInvite() {
        require(State.IDLE, "acceptingInvite");
        state = State.INVITING;
        notifyState(null, null);
    }

    public void established(MsrpSessionInfo info) {
        require(State.INVITING, "established");
        if (info == null) throw new IllegalArgumentException("info");
        this.info = info;
        state = State.ESTABLISHED;
        notifyState(null, null);
    }

    public void inviteRejected(String detail) {
        require(State.INVITING, "inviteRejected");
        state = State.CLOSED;
        recordReason(CloseReason.INVITE_REJECTED, detail);
        notifyState(CloseReason.INVITE_REJECTED, detail);
    }

    /** No-op once closing or closed. */
    public void closeLocal(String detail) {
        if (state == State.CLOSED || state == State.CLOSING) return;
        state = State.CLOSING;
        recordReason(CloseReason.LOCAL_BYE, detail);
        notifyState(CloseReason.LOCAL_BYE, detail);
    }

    /** No-op once closing or closed. */
    public void closeRemote(String detail) {
        if (state == State.CLOSED || state == State.CLOSING) return;
        state = State.CLOSING;
        recordReason(CloseReason.REMOTE_BYE, detail);
        notifyState(CloseReason.REMOTE_BYE, detail);
    }

    /**
     * From any state but closed; BYE reasons go through {@link #closeLocal} or
     * {@link #closeRemote}.
     */
    public void closeError(CloseReason reason, String detail) {
        if (reason == null) throw new IllegalArgumentException("reason");
        if (reason == CloseReason.LOCAL_BYE || reason == CloseReason.REMOTE_BYE) {
            throw new IllegalArgumentException(
                    "use closeLocal/closeRemote for BYE reasons");
        }
        if (state == State.CLOSED) return;
        state = State.CLOSING;
        recordReason(reason, detail);
        notifyState(reason, detail);
    }

    /** From any state. Idempotent. */
    public void closed() {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        notifyState(lastReason, lastDetail);
    }

    /** Drops frames that arrive before the session is established (RFC 4975 §6). */
    public void onMsrpFrame(MsrpMessage message) {
        if (state != State.ESTABLISHED) return;
        Listener l = listener;
        if (l != null && message != null) {
            l.onMsrpMessage(message);
        }
    }

    private void require(State expected, String op) {
        if (state != expected) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "MsrpChatSession[%s]: %s requires state=%s, was %s",
                    sessionTag, op, expected, state));
        }
    }

    private void recordReason(CloseReason r, String detail) {
        this.lastReason = r;
        this.lastDetail = detail;
    }

    private void notifyState(CloseReason r, String detail) {
        Listener l = listener;
        if (l != null) l.onStateChanged(state, r, detail);
    }

    @Override
    public String toString() {
        return "MsrpChatSession[" + sessionTag + " state=" + state
                + (lastReason != null ? " reason=" + lastReason : "")
                + "]";
    }
}

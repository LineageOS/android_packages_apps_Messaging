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
package com.android.messaging.rcs.carrier.msrp.session;

import com.android.messaging.rcs.carrier.msrp.MsrpMessage;

import java.util.Locale;

/**
 * Pure-Java state machine for the lifecycle of a single MSRP chat session.
 * Pairs with {@link MsrpSessionInfo} (immutable session params, set on the
 * IDLE → INVITING transition's resolution) and is driven by
 * {@link CarrierMsrpSessionManager} (SIP plane) and
 * {@link MsrpTlsConnection} (MSRP socket).
 *
 * <pre>
 *   IDLE
 *     │  inviting()                       — SIP INVITE sent (originating)
 *     │  acceptingInvite(...)             — SIP INVITE received (terminating)
 *     ▼
 *   INVITING ─── inviteRejected("4xx") ─▶ CLOSED
 *     │  established(info)                — 200/ACK + MSRP socket up
 *     ▼
 *   ESTABLISHED ── bye("local"/"remote") ─▶ CLOSING ─── closed() ─▶ CLOSED
 *                  │
 *                  │  fingerprintMismatch / tlsFailure / peerError
 *                  └────────────────────────────────────────────▶ CLOSING
 * </pre>
 *
 * <p>The state machine itself does NOT speak SIP or open sockets — those are
 * orchestrated by the manager. This class enforces ordering, holds the
 * {@link MsrpSessionInfo}, surfaces a {@link Listener} for observers, and
 * dispatches inbound MSRP frames to a single registered handler once
 * ESTABLISHED.
 *
 * <p>Threading: invariants assume external serialization (the manager pumps
 * lifecycle events on its SIP thread). Not internally synchronized; do not
 * call from multiple threads concurrently.
 *
 * <p>Pure Java, no Android/JAIN-SIP/Socket imports.
 */
public final class MsrpChatSession {

    public enum State { IDLE, INVITING, ESTABLISHED, CLOSING, CLOSED }

    /**
     * Reason for transition to {@link State#CLOSING} / {@link State#CLOSED}.
     * Drives diagnostics + UI-surfaced "why did the session drop".
     */
    public enum CloseReason {
        /** Local app called {@link #closeLocal(String)}. */
        LOCAL_BYE,
        /** Peer sent SIP BYE. */
        REMOTE_BYE,
        /** Peer's 4xx/5xx to our INVITE; no session ever opened. */
        INVITE_REJECTED,
        /** SDP a=fingerprint did not match peer cert — RFC 4572 binding broken. */
        FINGERPRINT_MISMATCH,
        /** TLS handshake failed, socket dropped, or read/write IO error. */
        TLS_ERROR,
        /** Peer responded to a SEND with 4xx/5xx (e.g. 413 Request Entity Too Large). */
        PEER_ERROR,
        /** Inactivity timer elapsed; the manager BYE'd the session. */
        INACTIVITY,
    }

    /** Observer for state transitions and inbound MSRP frames. */
    public interface Listener {
        /** Lifecycle state change. {@code reason} is null unless terminal. */
        void onStateChanged(State newState, CloseReason reason, String detail);

        /**
         * An inbound MSRP frame arrived after the session reached
         * {@link State#ESTABLISHED}. The listener is responsible for
         * dispatching SEND (chat payload) vs REPORT (delivery ack) vs
         * response frames; see {@link MsrpMessage}.
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

    // ---------- transitions ----------

    /** IDLE → INVITING (originating role). */
    public void inviting() {
        require(State.IDLE, "inviting");
        state = State.INVITING;
        notifyState(null, null);
    }

    /** IDLE → INVITING (terminating role; we received an INVITE and will send 200 OK). */
    public void acceptingInvite() {
        require(State.IDLE, "acceptingInvite");
        state = State.INVITING;
        notifyState(null, null);
    }

    /** INVITING → ESTABLISHED. {@code info} must be non-null and consistent with this session. */
    public void established(MsrpSessionInfo info) {
        require(State.INVITING, "established");
        if (info == null) throw new IllegalArgumentException("info");
        this.info = info;
        state = State.ESTABLISHED;
        notifyState(null, null);
    }

    /** INVITING → CLOSED on 4xx/5xx response to our INVITE. */
    public void inviteRejected(String detail) {
        require(State.INVITING, "inviteRejected");
        state = State.CLOSED;
        recordReason(CloseReason.INVITE_REJECTED, detail);
        notifyState(CloseReason.INVITE_REJECTED, detail);
    }

    /** Local-initiated close (we send BYE). ESTABLISHED → CLOSING. */
    public void closeLocal(String detail) {
        if (state == State.CLOSED || state == State.CLOSING) return;
        state = State.CLOSING;
        recordReason(CloseReason.LOCAL_BYE, detail);
        notifyState(CloseReason.LOCAL_BYE, detail);
    }

    /** Peer-initiated close (we received BYE). ESTABLISHED → CLOSING. */
    public void closeRemote(String detail) {
        if (state == State.CLOSED || state == State.CLOSING) return;
        state = State.CLOSING;
        recordReason(CloseReason.REMOTE_BYE, detail);
        notifyState(CloseReason.REMOTE_BYE, detail);
    }

    /** Error close from any state: forces CLOSING (then closed() drives to CLOSED). */
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

    /** CLOSING → CLOSED. Idempotent. */
    public void closed() {
        if (state == State.CLOSED) return;
        // Allow closed() from any state — clean teardown path.
        state = State.CLOSED;
        notifyState(lastReason, lastDetail);
    }

    // ---------- frame dispatch ----------

    /**
     * Dispatch an inbound MSRP frame to the listener. No-op unless we are
     * ESTABLISHED. Frames arriving before ESTABLISHED would be a peer bug
     * (RFC 4975 §6 — MSRP traffic before SDP completes is invalid); we
     * silently drop them so a misbehaving peer can't crash the session.
     */
    public void onMsrpFrame(MsrpMessage message) {
        if (state != State.ESTABLISHED) return;
        Listener l = listener;
        if (l != null && message != null) {
            l.onMsrpMessage(message);
        }
    }

    // ---------- helpers ----------

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

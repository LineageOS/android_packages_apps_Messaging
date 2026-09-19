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

import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.msrp.MsrpChunker;
import com.android.messaging.rcs.carrier.msrp.MsrpEndFlag;
import com.android.messaging.rcs.carrier.msrp.MsrpHeaders;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;
import com.android.messaging.rcs.carrier.msrp.MsrpTransactionId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbound MSRP SEND path for an established {@link MsrpChatSession}: chunks
 * a logical message body into RFC 4975 §5.1 SEND frames, writes each chunk
 * over the session's wire, and correlates per-chunk MSRP-200 responses and
 * peer-side REPORT requests back into {@link Transport.Listener} status
 * updates for the originating UI {@code messageId}.
 *
 * <p>The pure-Java half of "MSRP-session SEND for large messages". The
 * SIP-plane that sets the session up is {@link CarrierMsrpSessionManager};
 * this class handles the MSRP-plane SEND traffic afterwards.
 *
 * <p>Lifecycle of one outbound message:
 * <ol>
 *   <li>Caller invokes {@link #send(String, String, byte[], boolean)} on an
 *       ESTABLISHED session. We chunk the body, write each chunk via the
 *       {@link FrameWriter}, and dispatch
 *       {@code onMessageStatus(id, SENDING, null)}.</li>
 *   <li>For each chunk's MSRP transaction the peer returns either a 200 OK
 *       (transaction success) or a 4xx/5xx (transaction failure). Wire those
 *       in via {@link #onResponse(MsrpMessage)}; we correlate by
 *       transaction-id, which we recorded at send-time.</li>
 *   <li>The peer's MSRP stack also sends a REPORT request once the assembled
 *       message reaches it (we requested {@code Success-Report: yes}). Wire
 *       that in via {@link #onReport(MsrpMessage)}; on Status: 000 200 OK we
 *       transition to {@link Message.Status#SENT}. (Google Messages treats MSRP
 *       REPORT 200 as "delivered" — we intentionally
 *       map it to SENT here because a peer's MSRP-stack ack is not the
 *       same thing as the human-visible IMDN "delivered" notification. The
 *       latter still arrives as an inbound IMDN/CPIM and propagates through
 *       the existing receiver path.)</li>
 *   <li>If a chunk's transaction fails (4xx/5xx) or the session drops
 *       mid-send, we dispatch {@code onMessageStatus(id, FAILED, reason)}
 *       and stop sending further chunks for that message.</li>
 * </ol>
 *
 * <p>Pure java — no Android, no JAIN-SIP, no sockets. Host-testable via a
 * mock {@link FrameWriter}.
 *
 * <p>Threading: not internally synchronized. The carrier transport's SIP
 * thread is the canonical caller for {@link #send}; the MSRP read thread
 * (from {@link MsrpTlsConnection}) is the canonical caller for
 * {@link #onResponse} / {@link #onReport} / {@link #onSessionClosed}. We
 * rely on those threads not concurrently mutating a single outgoing
 * message's state — the read loop is single-threaded by construction.
 */
public final class CarrierMsrpSessionSender {

    /**
     * Strategy for writing an MSRP frame onto the session's underlying
     * wire. In production this delegates to
     * {@link CarrierMsrpSessionManager#send(MsrpChatSession, MsrpMessage)}
     * (which in turn calls {@link MsrpTlsConnection#send(MsrpMessage)});
     * tests inject a recording mock.
     */
    public interface FrameWriter {
        /** Write one MSRP frame. Throws on transient IO failure. */
        void write(MsrpMessage frame) throws IOException;
    }

    /** Per-outgoing-message bookkeeping. */
    private static final class Pending {
        final String uiMessageId;
        /** Per-chunk transaction-ids we are waiting on a 200 OK for. */
        final java.util.LinkedHashSet<String> pendingTids = new java.util.LinkedHashSet<>();
        /** MSRP Message-ID we used for the SEND — the same id arrives back
         *  on the peer's REPORT. */
        final String msrpMessageId;
        /** Did we request a Success-Report? When false, we transition to
         *  SENT as soon as the final chunk's transaction-200 is in. */
        final boolean wantsSuccessReport;
        /** True iff the peer has acknowledged every chunk we sent. */
        boolean allChunksAcked = false;
        /** True iff a peer REPORT with status 2xx has been observed (only
         *  meaningful when {@link #wantsSuccessReport} is set). */
        boolean reportSucceeded = false;
        /** True once we've dispatched a terminal status (SENT or FAILED). */
        boolean terminal = false;
        /** True iff the body is multi-chunk (i.e. we won't issue SENT until
         *  the final chunk's MSRP-200 arrives, even with Success-Report=no). */
        boolean multiChunk;

        Pending(String uiId, String msrpId, boolean wantsReport) {
            this.uiMessageId = uiId;
            this.msrpMessageId = msrpId;
            this.wantsSuccessReport = wantsReport;
        }
    }

    private final MsrpChatSession session;
    private final FrameWriter writer;
    private final Transport.Listener listener;
    private final MsrpChunker chunker;

    /** UI-message-id -> bookkeeping. Only completed/failed messages are removed. */
    private final Map<String, Pending> byUiMessageId = new HashMap<>();
    /** MSRP-message-id -> ui-message-id (for incoming REPORT correlation). */
    private final Map<String, String> uiIdByMsrpMessageId = new HashMap<>();
    /** transaction-id -> ui-message-id (for response correlation). */
    private final Map<String, String> uiIdByTransactionId = new HashMap<>();
    /** transaction-id -> "is this chunk the final one?" */
    private final Map<String, Boolean> finalChunkByTransactionId = new HashMap<>();

    public CarrierMsrpSessionSender(MsrpChatSession session, FrameWriter writer,
            Transport.Listener listener) {
        this(session, writer, listener, new MsrpChunker());
    }

    public CarrierMsrpSessionSender(MsrpChatSession session, FrameWriter writer,
            Transport.Listener listener, MsrpChunker chunker) {
        if (session == null) throw new IllegalArgumentException("session");
        if (writer == null) throw new IllegalArgumentException("writer");
        if (chunker == null) throw new IllegalArgumentException("chunker");
        this.session = session;
        this.writer = writer;
        this.listener = listener;
        this.chunker = chunker;
    }

    /**
     * Send a single logical message over the established session. Returns
     * {@code true} when the send was dispatched (all chunks written); returns
     * {@code false} and dispatches {@code FAILED} on the listener when the
     * session is not ESTABLISHED, when no SessionInfo is bound, or when a
     * chunk write fails before any further chunks could go out.
     *
     * @param uiMessageId the UI-visible message id (CPIM imdn.Message-ID for
     *     chat text; the same id flows on {@code onMessageStatus} callbacks).
     *     Used as the MSRP Message-ID too so a peer's REPORT correlates.
     * @param contentType the MSRP Content-Type. For carrier-RCS chat this is
     *     {@code "message/cpim"} (CPIM wrapping is done by the caller).
     * @param body the (already-wrapped, if applicable) bytes to ship
     * @param wantsSuccessReport whether to set {@code Success-Report: yes}.
     *     RFC 4975 §7.1.1 default is "no"; carrier-RCS chat typically asks
     *     for yes so the originating side learns when MSRP-level delivery
     *     succeeded — Google Messages' behavior.
     */
    public boolean send(String uiMessageId, String contentType, byte[] body,
            boolean wantsSuccessReport) {
        if (uiMessageId == null) {
            throw new IllegalArgumentException("uiMessageId");
        }
        if (session.getState() != MsrpChatSession.State.ESTABLISHED) {
            dispatchFailed(uiMessageId,
                    "MSRP session not ESTABLISHED (state="
                            + session.getState() + ")");
            return false;
        }
        MsrpSessionInfo info = session.getSessionInfo();
        if (info == null) {
            dispatchFailed(uiMessageId, "MSRP session has no SessionInfo");
            return false;
        }
        String resolvedCt = contentType != null && !contentType.isEmpty()
                ? contentType : "message/cpim";

        // The MSRP Message-ID and the UI-side message-id are the same string —
        // this is how a peer's REPORT correlates back to a UI message. Google
        // Messages does the same.
        Pending p = new Pending(uiMessageId, uiMessageId, wantsSuccessReport);

        MsrpMessage prototype = MsrpMessage.newSend()
                .transactionId(MsrpTransactionId.next())
                .toPath(info.getRemoteMsrpUri())
                .fromPath(info.getLocalMsrpUri())
                .messageId(uiMessageId)
                .contentType(resolvedCt)
                .successReport(wantsSuccessReport)
                .failureReport(MsrpHeaders.FAILURE_REPORT_YES)
                .body(body == null ? new byte[0] : body)
                .endFlag(MsrpEndFlag.COMPLETE)
                .build();

        List<MsrpMessage> chunks = chunker.chunk(prototype);
        p.multiChunk = chunks.size() > 1;
        // Pre-register everything before we start writing — otherwise a fast
        // peer could respond before we've recorded our own tids.
        for (int i = 0; i < chunks.size(); i++) {
            MsrpMessage chunk = chunks.get(i);
            p.pendingTids.add(chunk.getTransactionId());
            uiIdByTransactionId.put(chunk.getTransactionId(), uiMessageId);
            finalChunkByTransactionId.put(chunk.getTransactionId(),
                    chunk.getEndFlag() == MsrpEndFlag.COMPLETE);
        }
        byUiMessageId.put(uiMessageId, p);
        uiIdByMsrpMessageId.put(p.msrpMessageId, uiMessageId);

        // Tell the UI we have a SENDING attempt in flight before we drop
        // bytes on the wire. If a chunk write fails partway through we'll
        // promote that to FAILED below.
        notifyStatus(uiMessageId, Message.Status.SENDING, null);

        for (MsrpMessage chunk : chunks) {
            try {
                writer.write(chunk);
            } catch (IOException ioe) {
                // Drop this message: any chunks already on the wire are
                // unresolvable now (we can't roll back the peer's view).
                // The peer's MSRP stack will likely send a REPORT 408 / 4xx
                // for the partial message; we don't wait — fail fast on
                // the originating side.
                String reason = "MSRP SEND write failed: "
                        + ioe.getClass().getSimpleName()
                        + (ioe.getMessage() != null ? ": " + ioe.getMessage() : "");
                clearTracking(p);
                dispatchFailed(uiMessageId, reason);
                return false;
            }
        }
        return true;
    }

    // ---------- inbound correlation ----------

    /**
     * Wire in a peer's MSRP transaction response (the 200 OK / 4xx / 5xx that
     * follows each of our SEND chunks).
     *
     * <p>Per RFC 4975 §7.1.1 a 200 OK on a chunk means the peer's MSRP stack
     * has accepted the chunk for processing; aggregated across all chunks of
     * a message it indicates the message reached the peer's MSRP stack — but
     * NOT that the peer has parsed the CPIM body. The IMDN "delivered"
     * notification on top is the user-visible "delivered" signal.
     */
    public void onResponse(MsrpMessage response) {
        if (response == null) return;
        if (response.getKind() != MsrpMessage.Kind.RESPONSE) return;
        String tid = response.getTransactionId();
        String uiId = uiIdByTransactionId.remove(tid);
        Boolean wasFinal = finalChunkByTransactionId.remove(tid);
        if (uiId == null) return; // not for us / already terminal
        Pending p = byUiMessageId.get(uiId);
        if (p == null || p.terminal) return;

        int status = response.getStatusCode();
        if (status >= 200 && status < 300) {
            p.pendingTids.remove(tid);
            if (p.pendingTids.isEmpty()) {
                p.allChunksAcked = true;
                maybeCompleteSent(p);
            } else if (Boolean.TRUE.equals(wasFinal)) {
                // We've seen the final chunk's 200 but earlier chunks remain
                // in-flight (unusual ordering but spec-legal). Still wait.
            }
        } else {
            // 4xx / 5xx — chunk-level failure. RFC 4975 §13: a 413 means the
            // peer wants smaller chunks; the right answer there is to retry
            // with adaptive sizing, but that's a follow-up. For
            // now any non-2xx terminates the outgoing message as FAILED.
            String reason = "MSRP " + status
                    + (response.getStatusComment() != null
                            ? " " + response.getStatusComment() : "")
                    + " on chunk tid=" + tid;
            clearTracking(p);
            dispatchFailed(p.uiMessageId, reason);
        }
    }

    /**
     * Wire in a peer's inbound REPORT request (the {@code MSRP <tid> REPORT}
     * frame the peer sends per RFC 4975 §7.1.2 when we asked
     * {@code Success-Report: yes} and the assembled message reached the peer).
     *
     * <p>The REPORT carries the original SEND's {@code Message-ID} (NOT the
     * tid — REPORTs use a fresh tid per spec), and a {@code Status:} header
     * naming the namespace + code (e.g. {@code "000 200 OK"} on success or
     * {@code "000 4xx ..."} on a peer-side failure such as forwarding loop).
     */
    public void onReport(MsrpMessage report) {
        if (report == null) return;
        if (report.getKind() != MsrpMessage.Kind.REQUEST
                || report.getMethod() != MsrpMethod.REPORT) {
            return;
        }
        String msrpMsgId = report.getMessageId();
        if (msrpMsgId == null) return;
        String uiId = uiIdByMsrpMessageId.get(msrpMsgId);
        if (uiId == null) return;
        Pending p = byUiMessageId.get(uiId);
        if (p == null || p.terminal) return;

        int code = parseReportStatusCode(report.getStatus());
        if (code >= 200 && code < 300) {
            // Peer-side accept. If transaction-200s are already in, this
            // gives us the SENT moment. If they aren't, mark report-seen and
            // wait for the chunk acks to drain.
            p.reportSucceeded = true;
            p.allChunksAcked = p.allChunksAcked || p.pendingTids.isEmpty();
            maybeCompleteSent(p);
        } else {
            String reason = "MSRP REPORT " + code + " (peer-side failure) for msrp-id="
                    + msrpMsgId;
            clearTracking(p);
            dispatchFailed(p.uiMessageId, reason);
        }
    }

    /**
     * Called when the underlying MSRP session terminates. Any pending
     * outgoing messages are dispatched as FAILED with the supplied reason.
     */
    public void onSessionClosed(String reason) {
        // Snapshot first — we mutate the map while iterating below.
        List<Pending> snap = new ArrayList<>(byUiMessageId.values());
        for (Pending p : snap) {
            if (!p.terminal) {
                clearTracking(p);
                dispatchFailed(p.uiMessageId, reason != null ? reason
                        : "MSRP session closed");
            }
        }
        byUiMessageId.clear();
        uiIdByMsrpMessageId.clear();
        uiIdByTransactionId.clear();
        finalChunkByTransactionId.clear();
    }

    // ---------- diagnostics ----------

    /** Number of UI-messages currently awaiting at least one response/REPORT. */
    public int pendingCount() { return byUiMessageId.size(); }

    /** Returns true iff this sender is still expecting wire traffic for {@code uiMessageId}. */
    public boolean isPending(String uiMessageId) {
        Pending p = byUiMessageId.get(uiMessageId);
        return p != null && !p.terminal;
    }

    // ---------- helpers ----------

    private void maybeCompleteSent(Pending p) {
        if (p.terminal) return;
        boolean done;
        if (p.wantsSuccessReport) {
            // Need both: all chunk acks AND the peer's REPORT 200. The REPORT
            // path called this method after marking allChunksAcked when the
            // chunks were already done, OR after the response path drained
            // the last chunk-ack — but we need to know whether REPORT was
            // seen. Use a tri-state: track whether a successful REPORT has
            // been recorded.
            done = p.allChunksAcked && p.reportSucceeded;
        } else {
            done = p.allChunksAcked;
        }
        if (done) {
            p.terminal = true;
            clearTracking(p);
            notifyStatus(p.uiMessageId, Message.Status.SENT, null);
        }
    }

    private void dispatchFailed(String uiId, String reason) {
        Pending p = byUiMessageId.get(uiId);
        if (p != null) {
            p.terminal = true;
        }
        notifyStatus(uiId, Message.Status.FAILED, reason);
    }

    private void clearTracking(Pending p) {
        if (p == null) return;
        for (String tid : p.pendingTids) {
            uiIdByTransactionId.remove(tid);
            finalChunkByTransactionId.remove(tid);
        }
        uiIdByMsrpMessageId.remove(p.msrpMessageId);
        // Don't remove from byUiMessageId here — callers may still query
        // isPending() on a recently-failed id. The map is bounded by
        // onSessionClosed() and naturally drains when a new session starts.
    }

    private void notifyStatus(String uiId, Message.Status status, String reason) {
        Transport.Listener l = listener;
        if (l != null) {
            l.onMessageStatus(uiId, status, reason);
        }
    }

    /**
     * Parse the integer code out of an MSRP {@code Status:} header value of
     * the form {@code "<namespace> <code> [comment]"}, returning -1 if it
     * can't be parsed.
     */
    static int parseReportStatusCode(String statusHeader) {
        if (statusHeader == null) return -1;
        String s = statusHeader.trim();
        int sp1 = s.indexOf(' ');
        if (sp1 < 0) return -1;
        int sp2 = s.indexOf(' ', sp1 + 1);
        String codeStr = sp2 < 0 ? s.substring(sp1 + 1) : s.substring(sp1 + 1, sp2);
        try {
            return Integer.parseInt(codeStr.trim());
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }
}

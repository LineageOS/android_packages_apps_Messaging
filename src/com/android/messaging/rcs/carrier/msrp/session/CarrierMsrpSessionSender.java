/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp.session;

import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.msrp.MsrpChunker;
import com.android.messaging.rcs.carrier.msrp.MsrpEndFlag;
import com.android.messaging.rcs.carrier.msrp.MsrpHeaders;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;
import com.android.messaging.rcs.carrier.msrp.MsrpReportStatus;
import com.android.messaging.rcs.carrier.msrp.MsrpTransactionId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbound SENDs on an established {@link MsrpChatSession}: chunks the body with
 * {@link MsrpChunker}, writes the chunks, and turns the per-chunk responses and the peer's REPORT
 * into {@link Transport.Listener} statuses. A message is {@code SENT} once every chunk is
 * answered 200 and, if requested, a 2xx REPORT arrives; that means the peer's MSRP stack has it,
 * not that it was delivered, which only an IMDN asserts. A failed chunk or a closed session fails
 * the message.
 *
 * <p>Not synchronized: {@link #send} runs on the SIP thread and the correlation calls on the
 * single MSRP read thread.
 */
public final class CarrierMsrpSessionSender {

    /** Writes a frame to the session; {@link CarrierMsrpSessionManager#send} in production. */
    public interface FrameWriter {
        void write(MsrpMessage frame) throws IOException;
    }

    private static final class Pending {
        final String uiMessageId;
        /** Chunk transaction ids still awaiting a 200. */
        final java.util.LinkedHashSet<String> pendingTids = new java.util.LinkedHashSet<>();
        /** The SEND's Message-ID, which the peer's REPORT carries back. */
        final String msrpMessageId;
        /**
         * Without a Success-Report, the chunk acknowledgements alone make the message {@code SENT}.
         */
        final boolean wantsSuccessReport;
        boolean allChunksAcked = false;
        boolean reportSucceeded = false;
        /** A terminal status (SENT or FAILED) has been dispatched. */
        boolean terminal = false;
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

    /**
     * Cleared only by {@link #onSessionClosed}; terminal entries stay so {@link #isPending} can
     * answer.
     */
    private final Map<String, Pending> byUiMessageId = new HashMap<>();
    /** MSRP Message-ID to UI message id, for REPORTs. */
    private final Map<String, String> uiIdByMsrpMessageId = new HashMap<>();
    /** Transaction id to UI message id, for responses. */
    private final Map<String, String> uiIdByTransactionId = new HashMap<>();
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
     * Sends one message. Returns false, after reporting {@code FAILED}, if the session is not
     * established or a chunk write fails.
     *
     * @param uiMessageId the app's message id, also used as the MSRP Message-ID
     * @param contentType the MSRP Content-Type; {@code message/cpim} if null or empty
     * @param wantsSuccessReport request a REPORT (RFC 4975 §7.1.1 defaults to none)
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

        // The MSRP Message-ID is the app's id, so the peer's REPORT correlates.
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
        // Register every transaction id before writing, in case a response arrives first.
        for (int i = 0; i < chunks.size(); i++) {
            MsrpMessage chunk = chunks.get(i);
            p.pendingTids.add(chunk.getTransactionId());
            uiIdByTransactionId.put(chunk.getTransactionId(), uiMessageId);
            finalChunkByTransactionId.put(chunk.getTransactionId(),
                    chunk.getEndFlag() == MsrpEndFlag.COMPLETE);
        }
        byUiMessageId.put(uiMessageId, p);
        uiIdByMsrpMessageId.put(p.msrpMessageId, uiMessageId);

        notifyStatus(uiMessageId, Message.Status.SENDING, null);

        for (MsrpMessage chunk : chunks) {
            try {
                writer.write(chunk);
            } catch (IOException ioe) {
                // Chunks already written cannot be recalled; fail now rather than wait for a
                // REPORT.
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

    /** A response to one of our chunks, correlated by transaction id (RFC 4975 §7.1.1). */
    public void onResponse(MsrpMessage response) {
        if (response == null) return;
        if (response.getKind() != MsrpMessage.Kind.RESPONSE) return;
        String tid = response.getTransactionId();
        String uiId = uiIdByTransactionId.remove(tid);
        Boolean wasFinal = finalChunkByTransactionId.remove(tid);
        if (uiId == null) return; // not ours, or already terminal
        Pending p = byUiMessageId.get(uiId);
        if (p == null || p.terminal) return;

        int status = response.getStatusCode();
        if (status >= 200 && status < 300) {
            p.pendingTids.remove(tid);
            if (p.pendingTids.isEmpty()) {
                p.allChunksAcked = true;
                maybeCompleteSent(p);
            } else if (Boolean.TRUE.equals(wasFinal)) {
            }
        } else {
            // Any non-2xx fails the message. TODO: retry a 413 with smaller chunks (RFC 4975 §13).
            String reason = "MSRP " + status
                    + (response.getStatusComment() != null
                            ? " " + response.getStatusComment() : "")
                    + " on chunk tid=" + tid;
            clearTracking(p);
            dispatchFailed(p.uiMessageId, reason);
        }
    }

    /**
     * A REPORT (RFC 4975 §7.1.2), correlated by the original Message-ID; its transaction id is new.
     * The Status header carries namespace and code, e.g. {@code 000 200 OK}.
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
            // Complete now if every chunk is already answered; otherwise the last 200 completes it.
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

    /** Fails every message not yet terminal. */
    public void onSessionClosed(String reason) {
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

    /** Tracked messages, including terminal ones until the session closes. */
    public int pendingCount() { return byUiMessageId.size(); }

    public boolean isPending(String uiMessageId) {
        Pending p = byUiMessageId.get(uiMessageId);
        return p != null && !p.terminal;
    }

    private void maybeCompleteSent(Pending p) {
        if (p.terminal) return;
        boolean done;
        if (p.wantsSuccessReport) {
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
        // byUiMessageId keeps the entry so isPending() still answers; onSessionClosed clears it.
    }

    private void notifyStatus(String uiId, Message.Status status, String reason) {
        Transport.Listener l = listener;
        if (l != null) {
            l.onMessageStatus(uiId, status, reason);
        }
    }

    /** The code from a {@code "<namespace> <code> [comment]"} Status value, or -1. */
    static int parseReportStatusCode(String statusHeader) {
        return MsrpReportStatus.code(statusHeader);
    }
}

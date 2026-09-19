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

import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.msrp.MsrpEndFlag;
import com.android.messaging.rcs.carrier.msrp.MsrpException;
import com.android.messaging.rcs.carrier.msrp.MsrpHeaders;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;
import com.android.messaging.rcs.carrier.msrp.MsrpReassembler;
import com.android.messaging.rcs.carrier.msrp.MsrpTransactionId;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Inbound MSRP SEND path for an established {@link MsrpChatSession}: takes
 * frames off the session's wire (typically via the {@link MsrpChatSession}'s
 * {@code Listener.onMsrpMessage} callback), reassembles chunked SENDs via
 * {@link MsrpReassembler}, dispatches the assembled body to a
 * {@link Transport.Listener}, and emits per-RFC-4975 MSRP responses + REPORTs
 * back to the peer.
 *
 * <p>This is the receiver-side half of MSRP-session SEND (the sender lives in
 * {@link CarrierMsrpSessionSender}). The reassembly machinery is
 * {@link MsrpReassembler}; the response/REPORT emission uses the same
 * {@link CarrierMsrpSessionSender.FrameWriter} contract the sender does, so
 * the producer wires both sides to a single underlying
 * {@link MsrpTlsConnection} writer.
 *
 * <p>Receive flow per chunk:
 * <ol>
 *   <li>Validate the frame is a SEND request (REPORT / RESPONSE frames are
 *       not our responsibility — they go to the
 *       {@link CarrierMsrpSessionSender}'s correlation paths).</li>
 *   <li>If {@code Failure-Report != "no"}, queue an MSRP {@code 200 OK}
 *       transaction response for the chunk's tid.</li>
 *   <li>Feed the chunk into {@link MsrpReassembler}. On
 *       {@link MsrpReassembler.Outcome#COMPLETE}, extract the assembled body
 *       and dispatch
 *       {@link Transport.Listener#onIncomingMessage(String, String, String)}.
 *       Then if the SEND requested {@code Success-Report: yes}, emit an MSRP
 *       REPORT request with {@code Status: 000 200 OK} addressed back to
 *       the originator.</li>
 *   <li>On {@link MsrpReassembler.Outcome#ABORTED} or a
 *       {@link MsrpException} during feed: drop the partial state silently
 *       (Google Messages' behavior — there's no spec-mandated abort-response).</li>
 * </ol>
 *
 * <p>Pure java — no Android, no JAIN-SIP, no sockets. Host-testable via a
 * mock {@link CarrierMsrpSessionSender.FrameWriter}.
 *
 * <p>Threading: same constraints as
 * {@link CarrierMsrpSessionSender} — not internally synchronized; the
 * caller (typically the MSRP read thread) is the canonical mutator.
 */
public final class CarrierMsrpSessionReceiver {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** RFC 4975 §7.1.2 canonical success Status value. */
    private static final String STATUS_OK = "000 200 OK";

    private final MsrpChatSession session;
    private final CarrierMsrpSessionSender.FrameWriter writer;
    private final Transport.Listener listener;
    private final MsrpReassembler reassembler = new MsrpReassembler();

    /** When true, treat assembled bodies as UTF-8 text and forward via
     *  {@link Transport.Listener#onIncomingMessage}. The carrier transport
     *  sets this; the CPIM-unwrap is done downstream so the receiver remains
     *  agnostic about CPIM vs raw text vs other content types. */
    private final boolean dispatchAsText;

    public CarrierMsrpSessionReceiver(MsrpChatSession session,
            CarrierMsrpSessionSender.FrameWriter writer,
            Transport.Listener listener) {
        this(session, writer, listener, /*dispatchAsText=*/ true);
    }

    public CarrierMsrpSessionReceiver(MsrpChatSession session,
            CarrierMsrpSessionSender.FrameWriter writer,
            Transport.Listener listener,
            boolean dispatchAsText) {
        if (session == null) throw new IllegalArgumentException("session");
        if (writer == null) throw new IllegalArgumentException("writer");
        this.session = session;
        this.writer = writer;
        this.listener = listener;
        this.dispatchAsText = dispatchAsText;
    }

    /**
     * Feed an inbound MSRP SEND chunk into the reassembler. No-op (and no
     * response emission) when {@code chunk} is not a SEND request.
     */
    public void onSendChunk(MsrpMessage chunk) {
        if (chunk == null) return;
        if (chunk.getKind() != MsrpMessage.Kind.REQUEST
                || chunk.getMethod() != MsrpMethod.SEND) {
            return;
        }

        // RFC 4975 §7.1.1: Failure-Report=yes (default) or partial means we
        // MUST send back a transaction response. Failure-Report=no means we
        // MUST NOT send the response (the peer is fire-and-forget).
        boolean wantsTxResponse = !MsrpHeaders.FAILURE_REPORT_NO
                .equalsIgnoreCase(chunk.getFailureReport());
        if (wantsTxResponse) {
            tryWriteTxOk(chunk);
        }

        MsrpReassembler.Outcome outcome;
        try {
            outcome = reassembler.feed(chunk);
        } catch (MsrpException me) {
            // Bad chunk (range gap, missing Message-ID, length mismatch).
            // Drop and rely on the peer to recover. The reassembler will
            // already have purged its state for an abort, but for a malformed
            // chunk it leaves the message half-assembled — we explicitly drop.
            String mid = chunk.getMessageId();
            if (mid != null) {
                reassembler.drop(mid);
            }
            return;
        }
        if (outcome == MsrpReassembler.Outcome.IN_PROGRESS) return;
        if (outcome == MsrpReassembler.Outcome.ABORTED) return;

        // COMPLETE — pull the assembled body and dispatch.
        String messageId = chunk.getMessageId();
        byte[] body = reassembler.take(messageId);
        if (body == null) return; // race / aborted under us
        dispatchInbound(chunk, messageId, body);

        // Emit REPORT if the originator requested one.
        boolean wantsSuccessReport = MsrpHeaders.SUCCESS_REPORT_YES
                .equalsIgnoreCase(chunk.getSuccessReport());
        if (wantsSuccessReport) {
            tryWriteSuccessReport(chunk, body.length);
        }
    }

    /** Best-effort: drop any partial in-flight reassembly state. */
    public void reset() {
        // We don't keep an inventory of Message-IDs in flight here — the
        // reassembler does. Nothing to do beyond letting it GC naturally.
    }

    /** Number of inbound messages currently mid-reassembly. */
    public int inFlightCount() {
        return reassembler.inFlightCount();
    }

    // ---------- emit ----------

    private void tryWriteTxOk(MsrpMessage incoming) {
        // RFC 4975 §7.1.1 / §9: response shares the SEND's tid, From-Path is
        // the responder's (us; we are the To-Path of the SEND), To-Path is
        // the originator's (the SEND's From-Path).
        try {
            MsrpMessage ok = MsrpMessage.newResponse(200, "OK")
                    .transactionId(incoming.getTransactionId())
                    .toPath(orEmpty(incoming.getFromPath()))
                    .fromPath(orEmpty(incoming.getToPath()))
                    .endFlag(MsrpEndFlag.COMPLETE)
                    .build();
            writer.write(ok);
        } catch (IOException ignore) {
            // The session will get its own onClosed via the read loop; we
            // don't want to crash a producer-thread on a transient write
            // failure here.
        }
    }

    private void tryWriteSuccessReport(MsrpMessage finalChunk, int totalBytes) {
        try {
            MsrpMessage rep = MsrpMessage.newReport()
                    .transactionId(MsrpTransactionId.next())
                    .toPath(orEmpty(finalChunk.getFromPath()))
                    .fromPath(orEmpty(finalChunk.getToPath()))
                    .messageId(orEmpty(finalChunk.getMessageId()))
                    .byteRange(1, totalBytes, totalBytes)
                    .header(MsrpHeaders.STATUS, STATUS_OK)
                    .endFlag(MsrpEndFlag.COMPLETE)
                    .build();
            writer.write(rep);
        } catch (IOException ignore) {
            // Same rationale as tryWriteTxOk.
        }
    }

    private void dispatchInbound(MsrpMessage finalChunk, String messageId,
            byte[] body) {
        Transport.Listener l = listener;
        if (l == null) return;
        String fromUri = finalChunk.getFromPath();
        // Deliver the RAW BYTES: the session body is a message/cpim payload that may wrap
        // a BINARY inner type (message/mls). A UTF-8 String round-trip corrupts it (the receive-side
        // analogue of the AS's UnicodeDecodeError bug). The consumer parses the CPIM from bytes and
        // routes text/plain vs message/mls itself; the default onIncomingBytes still stringifies for
        // any legacy text-only consumer.
        l.onIncomingBytes(fromUri, body, messageId);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}

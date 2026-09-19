/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * Inbound SEND handling for an established {@link MsrpChatSession}: answers each chunk, reassembles
 * with {@link MsrpReassembler}, hands the body to {@link Transport.Listener#onIncomingBytes}, and
 * sends a success REPORT when asked (RFC 4975 §7.1). A malformed or aborted message is dropped.
 * Not synchronized; driven from the session's read thread.
 */
public final class CarrierMsrpSessionReceiver {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** RFC 4975 §7.1.2. */
    private static final String STATUS_OK = "000 200 OK";

    private final MsrpChatSession session;
    private final CarrierMsrpSessionSender.FrameWriter writer;
    private final Transport.Listener listener;
    private final MsrpReassembler reassembler = new MsrpReassembler();

    /** Stored but not consulted: bodies are always delivered as bytes. */
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

    /** Ignores anything but a SEND request. */
    public void onSendChunk(MsrpMessage chunk) {
        if (chunk == null) return;
        if (chunk.getKind() != MsrpMessage.Kind.REQUEST
                || chunk.getMethod() != MsrpMethod.SEND) {
            return;
        }

        // RFC 4975 §7.1.1: answer unless Failure-Report is "no".
        boolean wantsTxResponse = !MsrpHeaders.FAILURE_REPORT_NO
                .equalsIgnoreCase(chunk.getFailureReport());
        if (wantsTxResponse) {
            tryWriteTxOk(chunk);
        }

        MsrpReassembler.Outcome outcome;
        try {
            outcome = reassembler.feed(chunk);
        } catch (MsrpException me) {
            // A malformed chunk leaves the message half-assembled; drop it and let the peer
            // recover.
            String mid = chunk.getMessageId();
            if (mid != null) {
                reassembler.drop(mid);
            }
            return;
        }
        if (outcome == MsrpReassembler.Outcome.IN_PROGRESS) return;
        if (outcome == MsrpReassembler.Outcome.ABORTED) return;

        String messageId = chunk.getMessageId();
        byte[] body = reassembler.take(messageId);
        if (body == null) return;
        dispatchInbound(chunk, messageId, body);

        boolean wantsSuccessReport = MsrpHeaders.SUCCESS_REPORT_YES
                .equalsIgnoreCase(chunk.getSuccessReport());
        if (wantsSuccessReport) {
            tryWriteSuccessReport(chunk, body.length);
        }
    }

    /** Does nothing: partial state lives in the reassembler. */
    public void reset() {
    }

    public int inFlightCount() {
        return reassembler.inFlightCount();
    }

    private void tryWriteTxOk(MsrpMessage incoming) {
        // RFC 4975 §7.1.1: same transaction id; the paths are the SEND's, swapped.
        try {
            MsrpMessage ok = MsrpMessage.newResponse(200, "OK")
                    .transactionId(incoming.getTransactionId())
                    .toPath(orEmpty(incoming.getFromPath()))
                    .fromPath(orEmpty(incoming.getToPath()))
                    .endFlag(MsrpEndFlag.COMPLETE)
                    .build();
            writer.write(ok);
        } catch (IOException ignore) {
            // The read loop reports the closed session.
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
        }
    }

    private void dispatchInbound(MsrpMessage finalChunk, String messageId,
            byte[] body) {
        Transport.Listener l = listener;
        if (l == null) return;
        String fromUri = finalChunk.getFromPath();
        // Bytes, not a String: the CPIM body may wrap a binary inner type such as message/mls.
        l.onIncomingBytes(fromUri, body, messageId);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Host-side unit tests for {@link CarrierMsrpSessionSender} — the pure-Java
 * outbound MSRP SEND path for an established chat session.
 *
 * <p>The tests use a mock {@link CarrierMsrpSessionSender.FrameWriter} that
 * records every emitted frame, and a {@link RecordingListener} to capture
 * the {@link Transport.Listener} dispatches. No sockets, no Android, no
 * JAIN-SIP.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Single-chunk SEND emits one frame; per-chunk MSRP-200 + REPORT 200
 *       drives SENDING -&gt; SENT.</li>
 *   <li>Multi-chunk SEND emits N+1-frame range (chunker-driven): all
 *       transaction-200s + REPORT 200 -&gt; SENT.</li>
 *   <li>MSRP 4xx on any chunk -&gt; FAILED with diagnostic.</li>
 *   <li>REPORT with non-2xx Status -&gt; FAILED.</li>
 *   <li>Session-drop mid-send -&gt; FAILED on any pending UI message.</li>
 *   <li>send-on-not-ESTABLISHED -&gt; FAILED immediately.</li>
 *   <li>Header shape: From-Path, To-Path, Message-ID, Content-Type,
 *       Success-Report, Failure-Report, Byte-Range all emitted.</li>
 *   <li>Frame-writer IOException -&gt; FAILED with diagnostic.</li>
 * </ul>
 */
public class CarrierMsrpSessionSenderTest {

    private MsrpChatSession session;
    private RecordingWriter writer;
    private RecordingListener listener;
    private CarrierMsrpSessionSender sender;

    @Before
    public void setUp() {
        session = sessionInEstablished();
        writer = new RecordingWriter();
        listener = new RecordingListener();
        sender = new CarrierMsrpSessionSender(session, writer, listener);
    }

    // ============================================================
    // Single-chunk SEND happy path
    // ============================================================

    @Test
    public void singleChunkSend_emitsOneFrame_correlatesResponseToSent() {
        boolean ok = sender.send("ui-1", "message/cpim",
                "hello world".getBytes(), /*wantsSuccessReport=*/ true);
        assertTrue(ok);

        // One frame written, with the right shape.
        assertEquals(1, writer.frames.size());
        MsrpMessage frame = writer.frames.get(0);
        assertEquals(MsrpMessage.Kind.REQUEST, frame.getKind());
        assertEquals(MsrpMethod.SEND, frame.getMethod());
        assertEquals("ui-1", frame.getMessageId());
        assertEquals("message/cpim", frame.getContentType());
        assertEquals("msrps://10.0.0.2:2000/remote;tcp", frame.getToPath());
        assertEquals("msrps://10.0.0.1:1000/local;tcp", frame.getFromPath());
        assertEquals(MsrpHeaders.SUCCESS_REPORT_YES, frame.getSuccessReport());
        assertEquals(MsrpHeaders.FAILURE_REPORT_YES, frame.getFailureReport());
        assertEquals(MsrpEndFlag.COMPLETE, frame.getEndFlag());

        // SENDING dispatched.
        assertEquals(1, listener.statuses.size());
        assertEquals(Message.Status.SENDING, listener.statuses.get(0).status);

        // Wire in the transaction-200 — still no SENT (waiting on REPORT).
        sender.onResponse(response200(frame.getTransactionId()));
        assertEquals("only SENDING so far", 1, listener.statuses.size());
        assertTrue(sender.isPending("ui-1"));

        // Wire in the REPORT 200 — now SENT.
        sender.onReport(buildReport("ui-1", 11, 200));
        assertEquals(2, listener.statuses.size());
        assertEquals(Message.Status.SENT, listener.statuses.get(1).status);
        assertFalse(sender.isPending("ui-1"));
    }

    @Test
    public void singleChunkSend_reportFirstThenChunkResponse_stillSent() {
        sender.send("ui-1", "message/cpim", "x".getBytes(),
                /*wantsSuccessReport=*/ true);
        MsrpMessage frame = writer.frames.get(0);

        // Spec-legal interleaving: a peer may emit REPORT before our SEND's
        // 200 OK, since the REPORT and the chunk-response are independent
        // transactions.
        sender.onReport(buildReport("ui-1", 1, 200));
        // Not yet SENT — we still need the transaction-200.
        assertEquals(1, listener.statuses.size());

        sender.onResponse(response200(frame.getTransactionId()));
        assertEquals(2, listener.statuses.size());
        assertEquals(Message.Status.SENT, listener.statuses.get(1).status);
    }

    @Test
    public void singleChunkSend_noSuccessReportRequested_sentOnTxOnly() {
        sender.send("ui-1", "message/cpim", "x".getBytes(),
                /*wantsSuccessReport=*/ false);
        MsrpMessage frame = writer.frames.get(0);
        assertEquals(MsrpHeaders.SUCCESS_REPORT_NO, frame.getSuccessReport());

        sender.onResponse(response200(frame.getTransactionId()));
        // No REPORT needed — SENT comes from the tx-200 alone.
        assertEquals(2, listener.statuses.size());
        assertEquals(Message.Status.SENT, listener.statuses.get(1).status);
    }

    // ============================================================
    // Multi-chunk SEND
    // ============================================================

    @Test
    public void multiChunkSend_emitsExpectedChunks_completesOnAllAcks() {
        // Force small chunks so the chunker definitely splits.
        sender = new CarrierMsrpSessionSender(session, writer, listener,
                new MsrpChunker(10));
        byte[] body = new byte[25];
        for (int i = 0; i < body.length; i++) body[i] = (byte) ('a' + i % 26);
        sender.send("ui-big", "message/cpim", body,
                /*wantsSuccessReport=*/ true);

        // 25 bytes / 10-byte chunks => 3 chunks.
        assertEquals(3, writer.frames.size());

        // Last chunk has $; first two have +.
        assertEquals(MsrpEndFlag.CONTINUATION,
                writer.frames.get(0).getEndFlag());
        assertEquals(MsrpEndFlag.CONTINUATION,
                writer.frames.get(1).getEndFlag());
        assertEquals(MsrpEndFlag.COMPLETE,
                writer.frames.get(2).getEndFlag());

        // All chunks share the Message-ID + To-Path + From-Path + Content-Type.
        for (MsrpMessage f : writer.frames) {
            assertEquals("ui-big", f.getMessageId());
            assertEquals("message/cpim", f.getContentType());
            assertEquals("msrps://10.0.0.2:2000/remote;tcp", f.getToPath());
        }
        // But each chunk uses a fresh transaction-id.
        assertNotEquals(writer.frames.get(0).getTransactionId(),
                writer.frames.get(1).getTransactionId());

        // Drain all chunk-200s — still not SENT (no REPORT yet).
        for (MsrpMessage f : writer.frames) {
            sender.onResponse(response200(f.getTransactionId()));
        }
        assertEquals(1, listener.statuses.size()); // only SENDING

        // REPORT 200 — now SENT.
        sender.onReport(buildReport("ui-big", body.length, 200));
        assertEquals(Message.Status.SENT,
                listener.statuses.get(listener.statuses.size() - 1).status);
    }

    @Test
    public void multiChunkSend_chunk4xxMidStream_failsImmediately() {
        sender = new CarrierMsrpSessionSender(session, writer, listener,
                new MsrpChunker(10));
        sender.send("ui-big", "message/cpim", new byte[25], /*report=*/ true);

        // Ack the first chunk, fail the second with 413.
        sender.onResponse(response200(writer.frames.get(0).getTransactionId()));
        sender.onResponse(response(writer.frames.get(1).getTransactionId(),
                413, "Request Entity Too Large"));

        // FAILED has been dispatched with the diagnostic.
        Message.Status terminal = listener.statuses
                .get(listener.statuses.size() - 1).status;
        assertEquals(Message.Status.FAILED, terminal);
        String reason = listener.statuses
                .get(listener.statuses.size() - 1).errorReason;
        assertTrue("reason mentions 413: " + reason,
                reason != null && reason.contains("413"));
        assertFalse(sender.isPending("ui-big"));
    }

    // ============================================================
    // REPORT failures
    // ============================================================

    @Test
    public void report4xx_failsWithDiagnostic() {
        sender.send("ui-1", "message/cpim", "x".getBytes(), /*report=*/ true);
        sender.onResponse(response200(writer.frames.get(0).getTransactionId()));
        // REPORT carries Status: 000 408 Timeout — peer-side failure.
        sender.onReport(buildReport("ui-1", 1, 408));
        Message.Status terminal = listener.statuses
                .get(listener.statuses.size() - 1).status;
        assertEquals(Message.Status.FAILED, terminal);
        String reason = listener.statuses
                .get(listener.statuses.size() - 1).errorReason;
        assertTrue(reason != null && reason.contains("408"));
    }

    @Test
    public void report_forUnknownMessageId_dropped() {
        sender.send("ui-1", "message/cpim", "x".getBytes(), /*report=*/ true);
        // REPORT references an id we never sent.
        sender.onReport(buildReport("ui-other", 1, 200));
        // No terminal status promoted on ui-1.
        assertEquals(1, listener.statuses.size()); // just the initial SENDING
        assertTrue(sender.isPending("ui-1"));
    }

    // ============================================================
    // Session close
    // ============================================================

    @Test
    public void onSessionClosed_failsAllPending() {
        sender.send("ui-1", "message/cpim", "x".getBytes(), true);
        sender.send("ui-2", "message/cpim", "y".getBytes(), true);
        // No acks for either — just drop the session.
        sender.onSessionClosed("TLS read: SocketException: Connection reset");

        // Both ui-1 and ui-2 saw a FAILED.
        int failedCount = 0;
        for (StatusEvent se : listener.statuses) {
            if (se.status == Message.Status.FAILED) failedCount++;
        }
        assertEquals(2, failedCount);
        assertFalse(sender.isPending("ui-1"));
        assertFalse(sender.isPending("ui-2"));
    }

    @Test
    public void onSessionClosed_idempotent() {
        sender.send("ui-1", "message/cpim", "x".getBytes(), true);
        sender.onSessionClosed("first close");
        sender.onSessionClosed("second close"); // no-op
        // Still only one FAILED for ui-1.
        int failedCount = 0;
        for (StatusEvent se : listener.statuses) {
            if (se.status == Message.Status.FAILED) failedCount++;
        }
        assertEquals(1, failedCount);
    }

    // ============================================================
    // Pre-conditions
    // ============================================================

    @Test
    public void send_notEstablished_failsImmediately() {
        MsrpChatSession idle = new MsrpChatSession("idle");
        CarrierMsrpSessionSender s = new CarrierMsrpSessionSender(
                idle, writer, listener);
        boolean ok = s.send("ui-1", "message/cpim", "x".getBytes(), true);
        assertFalse(ok);
        assertEquals(0, writer.frames.size());
        assertEquals(1, listener.statuses.size());
        assertEquals(Message.Status.FAILED, listener.statuses.get(0).status);
    }

    @Test
    public void send_writeIoException_failsAfterFirstChunk() {
        FailingWriter fw = new FailingWriter();
        CarrierMsrpSessionSender s = new CarrierMsrpSessionSender(
                session, fw, listener);
        boolean ok = s.send("ui-1", "message/cpim", "x".getBytes(), true);
        assertFalse(ok);
        assertEquals(2, listener.statuses.size());
        assertEquals(Message.Status.SENDING, listener.statuses.get(0).status);
        assertEquals(Message.Status.FAILED, listener.statuses.get(1).status);
        assertTrue(listener.statuses.get(1).errorReason.contains("IOException"));
    }

    @Test
    public void constructor_rejectsNullArgs() {
        try {
            new CarrierMsrpSessionSender(null, writer, listener);
            fail("null session");
        } catch (IllegalArgumentException expected) {}
        try {
            new CarrierMsrpSessionSender(session, null, listener);
            fail("null writer");
        } catch (IllegalArgumentException expected) {}
        try {
            new CarrierMsrpSessionSender(session, writer, listener, null);
            fail("null chunker");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void send_nullUiMessageId_throws() {
        try {
            sender.send(null, "message/cpim", "x".getBytes(), false);
            fail("null id");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void send_nullBody_emitsEmptySend() {
        // RFC 4975 §6.4 keepalive form — single 0-byte SEND.
        boolean ok = sender.send("ui-1", "message/cpim", null, false);
        assertTrue(ok);
        assertEquals(1, writer.frames.size());
        assertEquals(0, writer.frames.get(0).getBodyLength());
    }

    // ============================================================
    // parseReportStatusCode helper
    // ============================================================

    @Test
    public void parseReportStatusCode_acceptsCanonicalShapes() {
        assertEquals(200, CarrierMsrpSessionSender.parseReportStatusCode("000 200 OK"));
        assertEquals(408, CarrierMsrpSessionSender.parseReportStatusCode("000 408 Timeout"));
        assertEquals(200, CarrierMsrpSessionSender.parseReportStatusCode("000 200"));
        assertEquals(-1,  CarrierMsrpSessionSender.parseReportStatusCode(null));
        assertEquals(-1,  CarrierMsrpSessionSender.parseReportStatusCode("garbage"));
        assertEquals(-1,  CarrierMsrpSessionSender.parseReportStatusCode("000 notanint"));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static MsrpChatSession sessionInEstablished() {
        MsrpChatSession s = new MsrpChatSession("call-1");
        s.inviting();
        s.established(MsrpSessionInfo.builder()
                .sipCallId("call-1")
                .contributionId("contrib-1")
                .localMsrpUri("msrps://10.0.0.1:1000/local;tcp")
                .remoteMsrpUri("msrps://10.0.0.2:2000/remote;tcp")
                .localRole(SdpOffer.SetupRole.ACTIVE)
                .remoteHost("10.0.0.2")
                .remotePort(2000)
                .acceptTypes(Arrays.asList("message/cpim"))
                .acceptWrappedTypes(Arrays.asList("text/plain"))
                .build());
        return s;
    }

    private static MsrpMessage response200(String tid) {
        return response(tid, 200, "OK");
    }

    private static MsrpMessage response(String tid, int code, String comment) {
        return MsrpMessage.newResponse(code, comment)
                .transactionId(tid)
                .toPath("msrps://10.0.0.1:1000/local;tcp")
                .fromPath("msrps://10.0.0.2:2000/remote;tcp")
                .endFlag(MsrpEndFlag.COMPLETE)
                .build();
    }

    private static MsrpMessage buildReport(String msgId, int totalBytes, int code) {
        return MsrpMessage.newReport()
                .transactionId(MsrpTransactionId.next())
                .toPath("msrps://10.0.0.1:1000/local;tcp")
                .fromPath("msrps://10.0.0.2:2000/remote;tcp")
                .messageId(msgId)
                .byteRange(1, Math.max(1, totalBytes), totalBytes)
                .status(code, code == 200 ? "OK" : "Failure")
                .endFlag(MsrpEndFlag.COMPLETE)
                .build();
    }

    private static final class RecordingWriter
            implements CarrierMsrpSessionSender.FrameWriter {
        final List<MsrpMessage> frames = new ArrayList<>();

        @Override public void write(MsrpMessage frame) throws IOException {
            frames.add(frame);
        }
    }

    private static final class FailingWriter
            implements CarrierMsrpSessionSender.FrameWriter {
        @Override public void write(MsrpMessage frame) throws IOException {
            throw new IOException("nope");
        }
    }

    private static final class StatusEvent {
        final String messageId, errorReason;
        final Message.Status status;
        StatusEvent(String m, Message.Status s, String e) {
            messageId = m; status = s; errorReason = e;
        }
    }

    private static final class RecordingListener implements Transport.Listener {
        final List<StatusEvent> statuses = new ArrayList<>();
        @Override public void onIncomingMessage(String f, String b, String id) {}
        @Override public void onMessageStatus(String id, Message.Status s, String e) {
            statuses.add(new StatusEvent(id, s, e));
        }
        @Override public void onRegistrationStateChanged(
                Transport.RegistrationState s, String r) {}
    }
}

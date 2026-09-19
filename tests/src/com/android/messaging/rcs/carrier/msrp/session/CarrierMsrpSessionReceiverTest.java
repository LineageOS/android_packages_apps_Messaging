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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.carrier.Transport;
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
 * Host-side unit tests for {@link CarrierMsrpSessionReceiver}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Single-chunk inbound SEND -&gt; {@code onIncomingMessage} dispatch + MSRP 200 OK emit.</li>
 *   <li>Multi-chunk inbound SEND -&gt; reassembled body + MSRP 200 OK per chunk + final REPORT.</li>
 *   <li>{@code Success-Report: yes} triggers REPORT emission; {@code Success-Report: no} suppresses it.</li>
 *   <li>{@code Failure-Report: no} suppresses the per-chunk 200 OK response.</li>
 *   <li>Aborted reassembly drops state silently.</li>
 *   <li>Non-SEND frames are no-ops on the receiver.</li>
 * </ul>
 */
public class CarrierMsrpSessionReceiverTest {

    private MsrpChatSession session;
    private RecordingWriter writer;
    private RecordingListener listener;
    private CarrierMsrpSessionReceiver receiver;

    @Before
    public void setUp() {
        session = sessionInEstablished();
        writer = new RecordingWriter();
        listener = new RecordingListener();
        receiver = new CarrierMsrpSessionReceiver(session, writer, listener);
    }

    // ============================================================
    // Single-chunk inbound SEND
    // ============================================================

    @Test
    public void singleChunkInbound_dispatchesAndAcks() {
        MsrpMessage chunk = buildSend("incoming-1", "hello".getBytes(),
                1, 5, 5, MsrpEndFlag.COMPLETE,
                /*successReport=*/ true, /*failureReport=*/ "yes");
        receiver.onSendChunk(chunk);

        // Listener got the text + correct id.
        assertEquals(1, listener.incoming.size());
        Event e = listener.incoming.get(0);
        assertEquals("incoming-1", e.messageId);
        assertEquals("hello", e.body);
        assertEquals("msrps://10.0.0.2:2000/remote;tcp", e.fromUri);

        // Writer received: MSRP-200 + REPORT.
        assertEquals(2, writer.frames.size());

        MsrpMessage tx200 = writer.frames.get(0);
        assertEquals(MsrpMessage.Kind.RESPONSE, tx200.getKind());
        assertEquals(200, tx200.getStatusCode());
        assertEquals(chunk.getTransactionId(), tx200.getTransactionId());

        MsrpMessage rep = writer.frames.get(1);
        assertEquals(MsrpMessage.Kind.REQUEST, rep.getKind());
        assertEquals(MsrpMethod.REPORT, rep.getMethod());
        assertEquals("incoming-1", rep.getMessageId());
        // Status header carries 000 200 OK.
        assertTrue("status header: " + rep.getStatus(),
                rep.getStatus().startsWith("000 200"));
    }

    @Test
    public void singleChunkInbound_successReportNo_noReportEmitted() {
        MsrpMessage chunk = buildSend("incoming-1", "x".getBytes(),
                1, 1, 1, MsrpEndFlag.COMPLETE,
                /*successReport=*/ false, /*failureReport=*/ "yes");
        receiver.onSendChunk(chunk);
        // Just the MSRP-200; no REPORT (peer didn't ask for one).
        assertEquals(1, writer.frames.size());
        assertEquals(MsrpMessage.Kind.RESPONSE, writer.frames.get(0).getKind());
        // But the listener still received the message.
        assertEquals(1, listener.incoming.size());
    }

    @Test
    public void singleChunkInbound_failureReportNo_noTxResponseEmitted() {
        MsrpMessage chunk = buildSend("incoming-1", "x".getBytes(),
                1, 1, 1, MsrpEndFlag.COMPLETE,
                /*successReport=*/ false, /*failureReport=*/ "no");
        receiver.onSendChunk(chunk);
        // RFC 4975 §7.1.1: Failure-Report:no means MUST NOT respond.
        // We still dispatch the inbound to the UI.
        assertEquals(0, writer.frames.size());
        assertEquals(1, listener.incoming.size());
    }

    // ============================================================
    // Multi-chunk inbound SEND
    // ============================================================

    @Test
    public void multiChunkInbound_reassemblesAndAcksEachChunk() {
        // 9-byte message split into 3-byte chunks.
        MsrpMessage c1 = buildSend("msg-x", "abc".getBytes(),
                1, 3, 9, MsrpEndFlag.CONTINUATION, false, "yes");
        MsrpMessage c2 = buildSend("msg-x", "def".getBytes(),
                4, 6, 9, MsrpEndFlag.CONTINUATION, false, "yes");
        MsrpMessage c3 = buildSend("msg-x", "ghi".getBytes(),
                7, 9, 9, MsrpEndFlag.COMPLETE, true, "yes");

        receiver.onSendChunk(c1);
        // Chunk 1 acked but no incoming yet.
        assertEquals(1, writer.frames.size());
        assertEquals(0, listener.incoming.size());

        receiver.onSendChunk(c2);
        assertEquals(2, writer.frames.size());
        assertEquals(0, listener.incoming.size());

        receiver.onSendChunk(c3);
        // 3 chunk-acks + 1 REPORT = 4 frames; 1 incoming.
        assertEquals(4, writer.frames.size());
        assertEquals(1, listener.incoming.size());
        assertEquals("abcdefghi", listener.incoming.get(0).body);
        assertEquals("msg-x", listener.incoming.get(0).messageId);
    }

    @Test
    public void abortedChunk_noListenerDispatch_noReport() {
        MsrpMessage c1 = buildSend("msg-y", "abc".getBytes(),
                1, 3, 9, MsrpEndFlag.CONTINUATION, false, "yes");
        receiver.onSendChunk(c1);
        // Abort flag arrives mid-stream.
        MsrpMessage abort = buildSend("msg-y", null,
                1, 0, 0, MsrpEndFlag.ABORT, false, "yes");
        receiver.onSendChunk(abort);
        // No incoming dispatch.
        assertEquals(0, listener.incoming.size());
    }

    // ============================================================
    // Non-SEND frames
    // ============================================================

    @Test
    public void nonSendFrame_isNoop() {
        MsrpMessage response = MsrpMessage.newResponse(200, "OK")
                .transactionId(MsrpTransactionId.next())
                .toPath("msrps://10.0.0.1:1000/local;tcp")
                .fromPath("msrps://10.0.0.2:2000/remote;tcp")
                .endFlag(MsrpEndFlag.COMPLETE)
                .build();
        receiver.onSendChunk(response);
        // Receiver doesn't process RESPONSE (the sender does); writer + listener
        // both untouched.
        assertEquals(0, writer.frames.size());
        assertEquals(0, listener.incoming.size());
    }

    @Test
    public void nullFrame_isNoop() {
        receiver.onSendChunk(null);
        assertEquals(0, writer.frames.size());
    }

    @Test
    public void constructor_rejectsNullArgs() {
        try {
            new CarrierMsrpSessionReceiver(null, writer, listener);
            fail("null session");
        } catch (IllegalArgumentException expected) {}
        try {
            new CarrierMsrpSessionReceiver(session, null, listener);
            fail("null writer");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void writerIoException_isSwallowed_listenerStillReceives() {
        // The receiver should not throw out of onSendChunk even if the
        // writer fails (the session's read thread can't usefully react
        // mid-frame — the connection's close path will take it from here).
        CarrierMsrpSessionReceiver r = new CarrierMsrpSessionReceiver(
                session, new FailingWriter(), listener);
        MsrpMessage chunk = buildSend("incoming-1", "hi".getBytes(),
                1, 2, 2, MsrpEndFlag.COMPLETE, true, "yes");
        try {
            r.onSendChunk(chunk);
        } catch (RuntimeException e) {
            fail("onSendChunk must swallow writer IO failures, got " + e);
        }
        // Inbound was still dispatched.
        assertEquals(1, listener.incoming.size());
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

    /**
     * Build an inbound SEND chunk. We orient as if the peer is the sender:
     * From-Path is the peer's URI, To-Path is ours.
     */
    private static MsrpMessage buildSend(String messageId, byte[] body,
            int start, int end, int total, MsrpEndFlag flag,
            boolean successReport, String failureReportValue) {
        MsrpMessage.Builder b = MsrpMessage.newSend()
                .transactionId(MsrpTransactionId.next())
                .toPath("msrps://10.0.0.1:1000/local;tcp")
                .fromPath("msrps://10.0.0.2:2000/remote;tcp")
                .messageId(messageId)
                .byteRange(start, end, total)
                .contentType("message/cpim")
                .successReport(successReport)
                .header(MsrpHeaders.FAILURE_REPORT, failureReportValue)
                .endFlag(flag);
        if (body != null) b.body(body);
        return b.build();
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
        @Override public void write(MsrpMessage f) throws IOException {
            throw new IOException("nope");
        }
    }

    private static final class Event {
        final String fromUri, body, messageId;
        Event(String f, String b, String m) {
            fromUri = f; body = b; messageId = m;
        }
    }

    private static final class RecordingListener implements Transport.Listener {
        final List<Event> incoming = new ArrayList<>();
        @Override public void onIncomingMessage(String f, String b, String id) {
            incoming.add(new Event(f, b, id));
        }
        @Override public void onMessageStatus(String id, Message.Status s, String e) {}
        @Override public void onRegistrationStateChanged(
                Transport.RegistrationState s, String r) {}
    }
}

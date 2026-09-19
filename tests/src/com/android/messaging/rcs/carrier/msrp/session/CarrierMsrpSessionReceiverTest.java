/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * {@link CarrierMsrpSessionReceiver}: responses, reports, reassembly and dispatch of inbound SENDs.
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

    // ---- Single-chunk inbound SEND

    @Test
    public void singleChunkInbound_dispatchesAndAcks() {
        MsrpMessage chunk = buildSend("incoming-1", "hello".getBytes(),
                1, 5, 5, MsrpEndFlag.COMPLETE,
                /*successReport=*/ true, /*failureReport=*/ "yes");
        receiver.onSendChunk(chunk);

        assertEquals(1, listener.incoming.size());
        Event e = listener.incoming.get(0);
        assertEquals("incoming-1", e.messageId);
        assertEquals("hello", e.body);
        assertEquals("msrps://10.0.0.2:2000/remote;tcp", e.fromUri);

        // The 200, then the report.
        assertEquals(2, writer.frames.size());

        MsrpMessage tx200 = writer.frames.get(0);
        assertEquals(MsrpMessage.Kind.RESPONSE, tx200.getKind());
        assertEquals(200, tx200.getStatusCode());
        assertEquals(chunk.getTransactionId(), tx200.getTransactionId());

        MsrpMessage rep = writer.frames.get(1);
        assertEquals(MsrpMessage.Kind.REQUEST, rep.getKind());
        assertEquals(MsrpMethod.REPORT, rep.getMethod());
        assertEquals("incoming-1", rep.getMessageId());
        assertTrue("status header: " + rep.getStatus(),
                rep.getStatus().startsWith("000 200"));
    }

    @Test
    public void singleChunkInbound_successReportNo_noReportEmitted() {
        MsrpMessage chunk = buildSend("incoming-1", "x".getBytes(),
                1, 1, 1, MsrpEndFlag.COMPLETE,
                /*successReport=*/ false, /*failureReport=*/ "yes");
        receiver.onSendChunk(chunk);
        // No report unless the peer asked for one.
        assertEquals(1, writer.frames.size());
        assertEquals(MsrpMessage.Kind.RESPONSE, writer.frames.get(0).getKind());
        assertEquals(1, listener.incoming.size());
    }

    @Test
    public void singleChunkInbound_failureReportNo_noTxResponseEmitted() {
        MsrpMessage chunk = buildSend("incoming-1", "x".getBytes(),
                1, 1, 1, MsrpEndFlag.COMPLETE,
                /*successReport=*/ false, /*failureReport=*/ "no");
        receiver.onSendChunk(chunk);
        // RFC 4975 §7.1.1: no response to Failure-Report no; the message is still dispatched.
        assertEquals(0, writer.frames.size());
        assertEquals(1, listener.incoming.size());
    }

    // ---- Multi-chunk inbound SEND

    @Test
    public void multiChunkInbound_reassemblesAndAcksEachChunk() {
        MsrpMessage c1 = buildSend("msg-x", "abc".getBytes(),
                1, 3, 9, MsrpEndFlag.CONTINUATION, false, "yes");
        MsrpMessage c2 = buildSend("msg-x", "def".getBytes(),
                4, 6, 9, MsrpEndFlag.CONTINUATION, false, "yes");
        MsrpMessage c3 = buildSend("msg-x", "ghi".getBytes(),
                7, 9, 9, MsrpEndFlag.COMPLETE, true, "yes");

        receiver.onSendChunk(c1);
        assertEquals(1, writer.frames.size());
        assertEquals(0, listener.incoming.size());

        receiver.onSendChunk(c2);
        assertEquals(2, writer.frames.size());
        assertEquals(0, listener.incoming.size());

        receiver.onSendChunk(c3);
        // Three chunk responses and one report.
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
        MsrpMessage abort = buildSend("msg-y", null,
                1, 0, 0, MsrpEndFlag.ABORT, false, "yes");
        receiver.onSendChunk(abort);
        assertEquals(0, listener.incoming.size());
    }

    // ---- Non-SEND frames

    @Test
    public void nonSendFrame_isNoop() {
        MsrpMessage response = MsrpMessage.newResponse(200, "OK")
                .transactionId(MsrpTransactionId.next())
                .toPath("msrps://10.0.0.1:1000/local;tcp")
                .fromPath("msrps://10.0.0.2:2000/remote;tcp")
                .endFlag(MsrpEndFlag.COMPLETE)
                .build();
        receiver.onSendChunk(response);
        // Responses belong to the sender.
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
        // A writer failure does not escape onSendChunk; the connection's close path handles it.
        CarrierMsrpSessionReceiver r = new CarrierMsrpSessionReceiver(
                session, new FailingWriter(), listener);
        MsrpMessage chunk = buildSend("incoming-1", "hi".getBytes(),
                1, 2, 2, MsrpEndFlag.COMPLETE, true, "yes");
        try {
            r.onSendChunk(chunk);
        } catch (RuntimeException e) {
            fail("onSendChunk must swallow writer IO failures, got " + e);
        }
        assertEquals(1, listener.incoming.size());
    }

    // ---- Helpers

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

    /** An inbound SEND chunk: From-Path is the peer's, To-Path ours. */
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

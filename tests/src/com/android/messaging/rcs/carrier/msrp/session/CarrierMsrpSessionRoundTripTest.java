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
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.msrp.MsrpChunker;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Round-trip integration test exercising {@link CarrierMsrpSessionSender} and
 * {@link CarrierMsrpSessionReceiver} against each other through a pair of
 * in-memory "wires". Each side's {@link CarrierMsrpSessionSender.FrameWriter}
 * funnels frames into the OTHER side's session — exactly the wire shape we
 * expect over a real {@link MsrpTlsConnection}, just without sockets.
 *
 * <p>This pre-stages the loopback harness work (which adds a real
 * SIP+MSRP loopback server) by validating that the sender / receiver pair
 * negotiates correctly when wired symmetrically: the originating side's
 * REPORT (emitted by the peer's receiver in response to {@code
 * Success-Report: yes}) reaches the originating sender and drives SENT;
 * the peer's MSRP-200 transaction responses do the same.
 */
public class CarrierMsrpSessionRoundTripTest {

    @Test
    public void singleChunkRoundTrip_drivesPeerOnIncomingAndOriginatorToSent() {
        Pair p = newPair();

        // Originator (A) sends; we'll observe A.listener for SENDING/SENT,
        // B.listener for onIncomingMessage.
        p.aSender.send("m-1", "message/cpim", "hello".getBytes(),
                /*wantsSuccessReport=*/ true);

        // A.listener saw SENDING; B.listener saw onIncomingMessage; A saw SENT.
        assertEquals(2, p.aListener.statuses.size());
        assertEquals(Message.Status.SENDING, p.aListener.statuses.get(0).status);
        assertEquals(Message.Status.SENT, p.aListener.statuses.get(1).status);

        assertEquals(1, p.bListener.incoming.size());
        assertEquals("hello", p.bListener.incoming.get(0).body);
        assertEquals("m-1", p.bListener.incoming.get(0).messageId);

        assertFalse(p.aSender.isPending("m-1"));
    }

    @Test
    public void multiChunkRoundTrip_reassembledAndAcked() {
        Pair p = newPairWithChunker(10);
        byte[] body = new byte[35]; // 4 chunks
        for (int i = 0; i < body.length; i++) body[i] = (byte) ('A' + i % 26);

        p.aSender.send("m-big", "message/cpim", body, true);

        assertEquals(Message.Status.SENT,
                p.aListener.statuses.get(p.aListener.statuses.size() - 1).status);
        assertEquals(1, p.bListener.incoming.size());
        assertEquals(35, p.bListener.incoming.get(0).body.length());
    }

    @Test
    public void roundTrip_noSuccessReportRequested_stillSent() {
        Pair p = newPair();
        // wantsSuccessReport=false: SENT must come from MSRP-200 alone, no
        // REPORT round-trip required.
        p.aSender.send("m-quiet", "message/cpim", "hi".getBytes(), false);
        assertEquals(Message.Status.SENT,
                p.aListener.statuses.get(p.aListener.statuses.size() - 1).status);
        // Receiver still got the body (Failure-Report defaults to yes -> we
        // still emit MSRP-200 on it, but no REPORT outbound).
        assertEquals(1, p.bListener.incoming.size());
    }

    @Test
    public void roundTrip_bothDirections_independentMessages() {
        Pair p = newPair();
        p.aSender.send("a-to-b", "message/cpim", "from A".getBytes(), true);
        p.bSender.send("b-to-a", "message/cpim", "from B".getBytes(), true);

        // Each side sees one inbound from the other.
        assertEquals(1, p.aListener.incoming.size());
        assertEquals("from B", p.aListener.incoming.get(0).body);
        assertEquals(1, p.bListener.incoming.size());
        assertEquals("from A", p.bListener.incoming.get(0).body);

        // Each side promotes its own outgoing to SENT.
        assertEquals(Message.Status.SENT,
                p.aListener.statuses.get(p.aListener.statuses.size() - 1).status);
        assertEquals(Message.Status.SENT,
                p.bListener.statuses.get(p.bListener.statuses.size() - 1).status);
    }

    // ---------- harness ----------

    /** A symmetric pair of (sender, receiver, listener) wired so each side's
     *  writer feeds the other side's session inputs. */
    private static final class Pair {
        final CarrierMsrpSessionSender aSender, bSender;
        @SuppressWarnings("unused")  // kept so the receiver doesn't get GC'd
        final CarrierMsrpSessionReceiver aReceiver, bReceiver;
        final RecordingListener aListener = new RecordingListener();
        final RecordingListener bListener = new RecordingListener();

        Pair(MsrpChatSession aSession, MsrpChatSession bSession, MsrpChunker chunker) {
            // Cross-wired writers: A writes -> B reads as inbound;
            // B writes -> A reads as inbound.
            CrossWriter[] writers = new CrossWriter[2];
            writers[0] = new CrossWriter();  // A's writer
            writers[1] = new CrossWriter();  // B's writer

            aReceiver = new CarrierMsrpSessionReceiver(
                    aSession, writers[0], aListener);
            bReceiver = new CarrierMsrpSessionReceiver(
                    bSession, writers[1], bListener);

            aSender = new CarrierMsrpSessionSender(
                    aSession, writers[0], aListener, chunker);
            bSender = new CarrierMsrpSessionSender(
                    bSession, writers[1], bListener, chunker);

            // A's writer feeds B; B's writer feeds A.
            writers[0].peerSender = bSender;
            writers[0].peerReceiver = bReceiver;
            writers[1].peerSender = aSender;
            writers[1].peerReceiver = aReceiver;
        }
    }

    /**
     * Frame-writer that delivers each frame straight to the peer's
     * sender (for RESPONSE/REPORT correlation) or receiver (for SEND
     * reassembly) — matching what the production wire does after
     * {@link MsrpTlsConnection} read-loop dispatches a frame to
     * {@link MsrpChatSession.Listener#onMsrpMessage}.
     */
    private static final class CrossWriter
            implements CarrierMsrpSessionSender.FrameWriter {
        CarrierMsrpSessionSender peerSender;
        CarrierMsrpSessionReceiver peerReceiver;

        @Override public void write(MsrpMessage frame) throws IOException {
            if (frame.getKind() == MsrpMessage.Kind.RESPONSE) {
                peerSender.onResponse(frame);
            } else if (frame.getMethod() == MsrpMethod.REPORT) {
                peerSender.onReport(frame);
            } else if (frame.getMethod() == MsrpMethod.SEND) {
                peerReceiver.onSendChunk(frame);
            }
        }
    }

    /** Pair of mirrored sessions (To-Path/From-Path crossed). */
    private static Pair newPair() {
        return newPairWithChunker(2048);
    }

    private static Pair newPairWithChunker(int maxChunkBytes) {
        MsrpChatSession a = newSession("call-A", "10.0.0.1", 1000, "10.0.0.2", 2000);
        MsrpChatSession b = newSession("call-B", "10.0.0.2", 2000, "10.0.0.1", 1000);
        return new Pair(a, b, new MsrpChunker(maxChunkBytes));
    }

    private static MsrpChatSession newSession(String tag, String localHost,
            int localPort, String remoteHost, int remotePort) {
        MsrpChatSession s = new MsrpChatSession(tag);
        s.inviting();
        s.established(MsrpSessionInfo.builder()
                .sipCallId(tag)
                .contributionId("contrib-1")
                .localMsrpUri("msrps://" + localHost + ":" + localPort + "/local;tcp")
                .remoteMsrpUri("msrps://" + remoteHost + ":" + remotePort + "/remote;tcp")
                .localRole(SdpOffer.SetupRole.ACTIVE)
                .remoteHost(remoteHost)
                .remotePort(remotePort)
                .acceptTypes(Arrays.asList("message/cpim"))
                .acceptWrappedTypes(Arrays.asList("text/plain"))
                .build());
        return s;
    }

    private static final class Event {
        final String fromUri, body, messageId;
        Event(String f, String b, String m) {
            fromUri = f; body = b; messageId = m;
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
        final List<Event> incoming = new ArrayList<>();
        final List<StatusEvent> statuses = new ArrayList<>();
        @Override public void onIncomingMessage(String f, String b, String id) {
            incoming.add(new Event(f, b, id));
        }
        @Override public void onMessageStatus(String id, Message.Status s, String e) {
            statuses.add(new StatusEvent(id, s, e));
        }
        @Override public void onRegistrationStateChanged(
                Transport.RegistrationState s, String r) {}
    }
}

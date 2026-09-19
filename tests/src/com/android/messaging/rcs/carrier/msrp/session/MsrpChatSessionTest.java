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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.carrier.msrp.MsrpEndFlag;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Host-side unit tests for {@link MsrpChatSession} — the pure-Java state
 * machine driving MSRP chat sessions through IDLE → INVITING → ESTABLISHED
 * → CLOSING → CLOSED, with optional error transitions.
 */
public class MsrpChatSessionTest {

    // ============================================================
    // Happy-path lifecycle
    // ============================================================

    @Test
    public void originating_idleToInvitingToEstablishedToClosed() {
        MsrpChatSession s = new MsrpChatSession("call-1");
        RecordingListener rec = new RecordingListener();
        s.setListener(rec);

        assertEquals(MsrpChatSession.State.IDLE, s.getState());

        s.inviting();
        assertEquals(MsrpChatSession.State.INVITING, s.getState());

        MsrpSessionInfo info = newInfo();
        s.established(info);
        assertEquals(MsrpChatSession.State.ESTABLISHED, s.getState());
        assertEquals(info, s.getSessionInfo());

        s.closeLocal("user hangup");
        assertEquals(MsrpChatSession.State.CLOSING, s.getState());
        s.closed();
        assertEquals(MsrpChatSession.State.CLOSED, s.getState());

        // Listener saw the transitions in order.
        assertEquals(Arrays.asList(
                MsrpChatSession.State.INVITING,
                MsrpChatSession.State.ESTABLISHED,
                MsrpChatSession.State.CLOSING,
                MsrpChatSession.State.CLOSED), rec.states);
    }

    @Test
    public void terminating_idleToInvitingToEstablished() {
        MsrpChatSession s = new MsrpChatSession("call-1");
        s.acceptingInvite();
        assertEquals(MsrpChatSession.State.INVITING, s.getState());
        s.established(newInfo());
        assertEquals(MsrpChatSession.State.ESTABLISHED, s.getState());
    }

    @Test
    public void closeRemote_byePathFromEstablished() {
        MsrpChatSession s = sessionInState(MsrpChatSession.State.ESTABLISHED);
        s.closeRemote("peer BYE");
        assertEquals(MsrpChatSession.State.CLOSING, s.getState());
        assertEquals(MsrpChatSession.CloseReason.REMOTE_BYE,
                s.getLastCloseReason());
        s.closed();
        assertEquals(MsrpChatSession.State.CLOSED, s.getState());
    }

    // ============================================================
    // Rejection paths
    // ============================================================

    @Test
    public void inviteRejected_transitionsToClosedDirectly() {
        MsrpChatSession s = sessionInState(MsrpChatSession.State.INVITING);
        s.inviteRejected("486 Busy Here");
        assertEquals(MsrpChatSession.State.CLOSED, s.getState());
        assertEquals(MsrpChatSession.CloseReason.INVITE_REJECTED,
                s.getLastCloseReason());
        assertEquals("486 Busy Here", s.getLastDetail());
    }

    @Test
    public void closeError_fingerprintMismatchFromEstablished() {
        MsrpChatSession s = sessionInState(MsrpChatSession.State.ESTABLISHED);
        s.closeError(MsrpChatSession.CloseReason.FINGERPRINT_MISMATCH,
                "expected != actual");
        assertEquals(MsrpChatSession.State.CLOSING, s.getState());
        assertEquals(MsrpChatSession.CloseReason.FINGERPRINT_MISMATCH,
                s.getLastCloseReason());
    }

    @Test
    public void closeError_tlsErrorFromInviting() {
        MsrpChatSession s = sessionInState(MsrpChatSession.State.INVITING);
        s.closeError(MsrpChatSession.CloseReason.TLS_ERROR, "handshake failed");
        assertEquals(MsrpChatSession.State.CLOSING, s.getState());
        assertEquals(MsrpChatSession.CloseReason.TLS_ERROR,
                s.getLastCloseReason());
    }

    @Test
    public void closeError_byeReasonsRejected() {
        MsrpChatSession s = sessionInState(MsrpChatSession.State.ESTABLISHED);
        try {
            s.closeError(MsrpChatSession.CloseReason.LOCAL_BYE, "x");
            fail("LOCAL_BYE rejected");
        } catch (IllegalArgumentException expected) {}
        try {
            s.closeError(MsrpChatSession.CloseReason.REMOTE_BYE, "x");
            fail("REMOTE_BYE rejected");
        } catch (IllegalArgumentException expected) {}
    }

    // ============================================================
    // Frame dispatch
    // ============================================================

    @Test
    public void onMsrpFrame_dispatchedWhenEstablished() {
        MsrpChatSession s = sessionInState(MsrpChatSession.State.ESTABLISHED);
        RecordingListener rec = new RecordingListener();
        s.setListener(rec);
        MsrpMessage frame = MsrpMessage.newSend()
                .transactionId("abcd1234")
                .toPath("msrp://h:1/a;tcp")
                .fromPath("msrp://h:2/b;tcp")
                .messageId("m-1")
                .byteRange(1, 5, 5)
                .contentType("text/plain")
                .body("hello".getBytes())
                .endFlag(MsrpEndFlag.COMPLETE)
                .build();
        s.onMsrpFrame(frame);
        assertEquals(1, rec.frames.size());
        assertEquals(frame, rec.frames.get(0));
    }

    @Test
    public void onMsrpFrame_droppedBeforeEstablished() {
        MsrpChatSession s = new MsrpChatSession("call-1");
        RecordingListener rec = new RecordingListener();
        s.setListener(rec);
        s.inviting();
        // Frame arrives before 200 OK + ACK — must be silently dropped.
        MsrpMessage frame = MsrpMessage.newSend()
                .transactionId("abcd1234")
                .toPath("msrp://h:1/a;tcp")
                .fromPath("msrp://h:2/b;tcp")
                .messageId("m-1")
                .byteRange(1, 5, 5)
                .contentType("text/plain")
                .body("hello".getBytes())
                .build();
        s.onMsrpFrame(frame);
        assertEquals(0, rec.frames.size());
    }

    @Test
    public void onMsrpFrame_droppedAfterClose() {
        MsrpChatSession s = sessionInState(MsrpChatSession.State.ESTABLISHED);
        RecordingListener rec = new RecordingListener();
        s.setListener(rec);
        s.closeLocal("bye");
        s.closed();
        MsrpMessage frame = MsrpMessage.newSend()
                .transactionId("abcd1234")
                .toPath("msrp://h:1/a;tcp")
                .fromPath("msrp://h:2/b;tcp")
                .messageId("m-1")
                .byteRange(1, 5, 5)
                .contentType("text/plain")
                .body("hello".getBytes())
                .build();
        s.onMsrpFrame(frame);
        assertEquals(0, rec.frames.size());
    }

    // ============================================================
    // Illegal transitions
    // ============================================================

    @Test
    public void inviting_rejectedFromEstablished() {
        MsrpChatSession s = sessionInState(MsrpChatSession.State.ESTABLISHED);
        try {
            s.inviting();
            fail("ESTABLISHED -> INVITING not allowed");
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void established_rejectedFromIdle() {
        MsrpChatSession s = new MsrpChatSession("c");
        try {
            s.established(newInfo());
            fail("IDLE -> ESTABLISHED not allowed");
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void established_rejectsNullInfo() {
        MsrpChatSession s = sessionInState(MsrpChatSession.State.INVITING);
        try {
            s.established(null);
            fail("null info");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void closed_idempotent() {
        MsrpChatSession s = sessionInState(MsrpChatSession.State.ESTABLISHED);
        s.closeLocal("a");
        s.closed();
        s.closed();
        s.closed();
        assertEquals(MsrpChatSession.State.CLOSED, s.getState());
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static MsrpChatSession sessionInState(MsrpChatSession.State target) {
        MsrpChatSession s = new MsrpChatSession("c");
        switch (target) {
            case IDLE: return s;
            case INVITING: s.inviting(); return s;
            case ESTABLISHED:
                s.inviting();
                s.established(newInfo());
                return s;
            default:
                throw new AssertionError("unsupported target " + target);
        }
    }

    private static MsrpSessionInfo newInfo() {
        return MsrpSessionInfo.builder()
                .sipCallId("call-1")
                .contributionId("contrib-1")
                .localMsrpUri("msrps://10.0.0.1:1000/local;tcp")
                .remoteMsrpUri("msrps://10.0.0.2:2000/remote;tcp")
                .localRole(SdpOffer.SetupRole.ACTIVE)
                .remoteHost("10.0.0.2")
                .remotePort(2000)
                .acceptTypes(Arrays.asList("message/cpim"))
                .acceptWrappedTypes(Arrays.asList("text/plain"))
                .build();
    }

    /** Collecting listener for assertions. */
    private static final class RecordingListener implements MsrpChatSession.Listener {
        final List<MsrpChatSession.State> states = new ArrayList<>();
        final List<MsrpChatSession.CloseReason> reasons = new ArrayList<>();
        final List<MsrpMessage> frames = new ArrayList<>();

        @Override
        public void onStateChanged(MsrpChatSession.State s,
                MsrpChatSession.CloseReason r, String detail) {
            states.add(s);
            reasons.add(r);
        }

        @Override
        public void onMsrpMessage(MsrpMessage m) {
            frames.add(m);
        }
    }
}

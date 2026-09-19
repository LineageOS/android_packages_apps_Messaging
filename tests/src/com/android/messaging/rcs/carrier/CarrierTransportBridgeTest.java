/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.carrier.Transport;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * {@link CarrierTransportBridge}: the pure-Java glue {@code CarrierRcsTransport} uses to turn
 * registrar state and receiver callbacks into {@link Transport.Listener} calls.
 */
public class CarrierTransportBridgeTest {

    private CarrierTransportBridge bridge;

    @Before
    public void setUp() {
        bridge = new CarrierTransportBridge();
    }

    // ---- State mapping

    @Test
    public void mapRegistrarState_translatesAllStates() {
        assertEquals(Transport.RegistrationState.UNREGISTERED,
                CarrierTransportBridge.mapRegistrarState(
                        CarrierSipRegistrarState.UNREGISTERED));
        assertEquals(Transport.RegistrationState.REGISTERING,
                CarrierTransportBridge.mapRegistrarState(
                        CarrierSipRegistrarState.REGISTERING));
        assertEquals(Transport.RegistrationState.REGISTERED,
                CarrierTransportBridge.mapRegistrarState(
                        CarrierSipRegistrarState.REGISTERED));
        assertEquals(Transport.RegistrationState.FAILED,
                CarrierTransportBridge.mapRegistrarState(
                        CarrierSipRegistrarState.FAILED));
    }

    @Test
    public void mapRegistrarState_nullDefaultsToUnregistered() {
        assertEquals(Transport.RegistrationState.UNREGISTERED,
                CarrierTransportBridge.mapRegistrarState(null));
    }

    // ---- Config gating

    @Test
    public void isConfigUsable_nullOrIncomplete() {
        assertFalse("null config", CarrierTransportBridge.isConfigUsable(null));

        RcsImsConfig.Builder b = new RcsImsConfig.Builder();
        assertFalse("no fields", CarrierTransportBridge.isConfigUsable(b.build()));

        b.pcscfAddress = "p-cscf.mnc260.mcc310.pub.3gppnetwork.org";
        assertFalse("only pcscf", CarrierTransportBridge.isConfigUsable(b.build()));

        b.domain = "mnc260.mcc310.3gppnetwork.org";
        assertFalse("missing user", CarrierTransportBridge.isConfigUsable(b.build()));

        b.userName = "+15551234567";
        assertFalse("missing publicId", CarrierTransportBridge.isConfigUsable(b.build()));

        b.publicIdentity = "tel:+15551234567";
        assertTrue("complete", CarrierTransportBridge.isConfigUsable(b.build()));
    }

    @Test
    public void isConfigUsable_emptyStringsAreNotUsable() {
        RcsImsConfig.Builder b = new RcsImsConfig.Builder();
        b.pcscfAddress = "";
        b.domain = "";
        b.userName = "";
        b.publicIdentity = "";
        assertFalse(CarrierTransportBridge.isConfigUsable(b.build()));
    }

    // ---- Switchover-size routing

    @Test
    public void shouldUseMsrpSession_belowThresholdFalse() {
        assertFalse(CarrierTransportBridge.shouldUseMsrpSession(0));
        assertFalse(CarrierTransportBridge.shouldUseMsrpSession(1));
        assertFalse(CarrierTransportBridge.shouldUseMsrpSession(
                CarrierTransportBridge.MSRP_SWITCHOVER_SIZE_BYTES - 1));
    }

    @Test
    public void shouldUseMsrpSession_atOrAboveThresholdTrue() {
        assertTrue(CarrierTransportBridge.shouldUseMsrpSession(
                CarrierTransportBridge.MSRP_SWITCHOVER_SIZE_BYTES));
        assertTrue(CarrierTransportBridge.shouldUseMsrpSession(
                CarrierTransportBridge.MSRP_SWITCHOVER_SIZE_BYTES + 1));
        assertTrue(CarrierTransportBridge.shouldUseMsrpSession(10_000));
    }

    @Test
    public void msrpSwitchoverSize_matchesBugleConstant() {
        // The pager-mode limit peers use on the wire.
        assertEquals(1300, CarrierTransportBridge.MSRP_SWITCHOVER_SIZE_BYTES);
    }

    // ---- Terminal-status classification

    @Test
    public void isTerminal_classification() {
        assertTrue(CarrierTransportBridge.isTerminal(Message.Status.SENT));
        assertTrue(CarrierTransportBridge.isTerminal(Message.Status.DELIVERED));
        assertTrue(CarrierTransportBridge.isTerminal(Message.Status.DISPLAYED));
        assertTrue(CarrierTransportBridge.isTerminal(Message.Status.FAILED));

        // In flight or not applicable.
        assertFalse(CarrierTransportBridge.isTerminal(Message.Status.PENDING));
        assertFalse(CarrierTransportBridge.isTerminal(Message.Status.SENDING));
        assertFalse(CarrierTransportBridge.isTerminal(Message.Status.RECEIVED));
        assertFalse(CarrierTransportBridge.isTerminal(null));
    }

    // ---- Outgoing-id tracking

    @Test
    public void trackOutgoing_addsToRegistry() {
        assertEquals(0, bridge.outgoingCount());
        bridge.trackOutgoing("msg-1");
        bridge.trackOutgoing("msg-2");
        assertEquals(2, bridge.outgoingCount());
        assertTrue(bridge.isTrackingOutgoing("msg-1"));
        assertTrue(bridge.isTrackingOutgoing("msg-2"));
        assertFalse(bridge.isTrackingOutgoing("msg-3"));
    }

    @Test
    public void trackOutgoing_nullIsNoOp() {
        bridge.trackOutgoing(null);
        assertEquals(0, bridge.outgoingCount());
    }

    @Test
    public void clearTracking_drainsRegistry() {
        bridge.trackOutgoing("msg-1");
        bridge.trackOutgoing("msg-2");
        bridge.clearTracking();
        assertEquals(0, bridge.outgoingCount());
        assertFalse(bridge.isTrackingOutgoing("msg-1"));
    }

    // ---- Listener bridge

    @Test
    public void wrap_forwardsOnIncomingMessage() {
        RecordingListener rec = new RecordingListener();
        Transport.Listener wrapped = bridge.wrap(rec);

        wrapped.onIncomingMessage("tel:+15551234567", "hello", "msg-1");

        assertEquals(1, rec.incoming.size());
        Event e = rec.incoming.get(0);
        assertEquals("tel:+15551234567", e.from);
        assertEquals("hello", e.body);
        assertEquals("msg-1", e.messageId);
    }

    @Test
    public void wrap_forwardsMultipleIncomingInOrder() {
        RecordingListener rec = new RecordingListener();
        Transport.Listener wrapped = bridge.wrap(rec);

        wrapped.onIncomingMessage("tel:+15550001", "one",   "msg-1");
        wrapped.onIncomingMessage("tel:+15550002", "two",   "msg-2");
        wrapped.onIncomingMessage("tel:+15550003", "three", "msg-3");

        assertEquals(3, rec.incoming.size());
        assertEquals("msg-1", rec.incoming.get(0).messageId);
        assertEquals("msg-2", rec.incoming.get(1).messageId);
        assertEquals("msg-3", rec.incoming.get(2).messageId);
    }

    /**
     * An MSRP session hands its CPIM envelope over as bytes, and the envelope can wrap a binary
     * type: the bytes must reach the downstream byte form unchanged, not through a String.
     */
    @Test
    public void wrap_forwardsOnIncomingBytesVerbatim() {
        RecordingListener rec = new RecordingListener();
        Transport.Listener wrapped = bridge.wrap(rec);
        // Not valid UTF-8: an invalid sequence, bytes UTF-8 never uses, a NUL, a lone continuation.
        byte[] body = {'C', 'P', 'I', 'M', ' ', (byte) 0xC3, 0x28, (byte) 0xFF, (byte) 0xFE, 0x00,
                (byte) 0x80};

        wrapped.onIncomingBytes("tel:+15551234567", body, "msg-1");

        assertEquals(1, rec.bytes.size());
        assertArrayEquals(body, rec.bytes.get(0));
        assertTrue("nothing reaches the String form", rec.incoming.isEmpty());
    }

    @Test
    public void wrap_forwardsOnMessageStatus() {
        RecordingListener rec = new RecordingListener();
        Transport.Listener wrapped = bridge.wrap(rec);

        wrapped.onMessageStatus("msg-1", Message.Status.SENDING, null);
        wrapped.onMessageStatus("msg-1", Message.Status.DELIVERED, null);

        assertEquals(2, rec.statuses.size());
        assertEquals(Message.Status.SENDING,   rec.statuses.get(0).status);
        assertEquals(Message.Status.DELIVERED, rec.statuses.get(1).status);
    }

    @Test
    public void wrap_terminalStatusClearsTrackedId() {
        bridge.trackOutgoing("msg-1");
        bridge.trackOutgoing("msg-2");
        assertTrue(bridge.isTrackingOutgoing("msg-1"));
        assertTrue(bridge.isTrackingOutgoing("msg-2"));

        RecordingListener rec = new RecordingListener();
        Transport.Listener wrapped = bridge.wrap(rec);

        wrapped.onMessageStatus("msg-1", Message.Status.SENDING, null);
        assertTrue("SENDING is not terminal", bridge.isTrackingOutgoing("msg-1"));

        wrapped.onMessageStatus("msg-1", Message.Status.DELIVERED, null);
        assertFalse("DELIVERED prunes", bridge.isTrackingOutgoing("msg-1"));

        assertTrue(bridge.isTrackingOutgoing("msg-2"));

        assertEquals(2, rec.statuses.size());
    }

    @Test
    public void wrap_terminalStatusForUnknownIdStillForwards() {
        RecordingListener rec = new RecordingListener();
        Transport.Listener wrapped = bridge.wrap(rec);

        // An unknown id can be a receipt for another process's message; the app de-duplicates.
        wrapped.onMessageStatus("foreign-id", Message.Status.DELIVERED, null);

        assertEquals(1, rec.statuses.size());
        assertEquals("foreign-id", rec.statuses.get(0).messageId);
        assertEquals(Message.Status.DELIVERED, rec.statuses.get(0).status);
    }

    @Test
    public void wrap_nullDownstreamDoesNotThrow() {
        Transport.Listener wrapped = bridge.wrap(null);

        bridge.trackOutgoing("msg-1");
        // Tracking still happens; the forward is a no-op.
        wrapped.onIncomingMessage("tel:+1", "body", "msg-1");
        wrapped.onIncomingBytes("tel:+1", new byte[] {1}, "msg-1");
        wrapped.onMessageStatus("msg-1", Message.Status.SENT, null);
        wrapped.onRegistrationStateChanged(
                Transport.RegistrationState.REGISTERED, null);

        assertFalse(bridge.isTrackingOutgoing("msg-1"));
    }

    @Test
    public void wrap_forwardsRegistrationStateChange() {
        RecordingListener rec = new RecordingListener();
        Transport.Listener wrapped = bridge.wrap(rec);

        wrapped.onRegistrationStateChanged(
                Transport.RegistrationState.UNREGISTERED, null);
        wrapped.onRegistrationStateChanged(
                Transport.RegistrationState.REGISTERING, null);
        wrapped.onRegistrationStateChanged(
                Transport.RegistrationState.REGISTERED, null);
        wrapped.onRegistrationStateChanged(
                Transport.RegistrationState.FAILED, "REGISTER 403");

        assertEquals(4, rec.states.size());
        assertEquals(Transport.RegistrationState.UNREGISTERED,
                rec.states.get(0).state);
        assertEquals(Transport.RegistrationState.REGISTERING,
                rec.states.get(1).state);
        assertEquals(Transport.RegistrationState.REGISTERED,
                rec.states.get(2).state);
        assertEquals(Transport.RegistrationState.FAILED,
                rec.states.get(3).state);
        assertEquals("REGISTER 403", rec.states.get(3).reason);
    }

    @Test
    public void wrap_failedStatusClearsTracking() {
        bridge.trackOutgoing("msg-fail");
        RecordingListener rec = new RecordingListener();
        Transport.Listener wrapped = bridge.wrap(rec);

        wrapped.onMessageStatus("msg-fail", Message.Status.FAILED,
                "carrier 480 Temporarily Unavailable");

        assertFalse("FAILED is terminal, prunes id",
                bridge.isTrackingOutgoing("msg-fail"));
        assertEquals(1, rec.statuses.size());
        assertEquals("carrier 480 Temporarily Unavailable",
                rec.statuses.get(0).errorReason);
    }

    // ---- Helpers

    private static final class Event {
        final String from, body, messageId;
        Event(String f, String b, String m) { from = f; body = b; messageId = m; }
    }
    private static final class StatusEvent {
        final String messageId, errorReason;
        final Message.Status status;
        StatusEvent(String m, Message.Status s, String e) {
            messageId = m; status = s; errorReason = e;
        }
    }
    private static final class StateEvent {
        final Transport.RegistrationState state;
        final String reason;
        StateEvent(Transport.RegistrationState s, String r) { state = s; reason = r; }
    }

    /** Collecting {@link Transport.Listener} for assertions. */
    private static final class RecordingListener implements Transport.Listener {
        final List<Event>       incoming = new ArrayList<>();
        final List<StatusEvent> statuses = new ArrayList<>();
        final List<StateEvent>  states   = new ArrayList<>();
        final List<byte[]>      bytes    = new ArrayList<>();

        @Override public void onIncomingMessage(String from, String body, String id) {
            incoming.add(new Event(from, body, id));
        }
        @Override public void onIncomingBytes(String from, byte[] body, String id) {
            bytes.add(body);
        }
        @Override public void onMessageStatus(String id, Message.Status s, String err) {
            statuses.add(new StatusEvent(id, s, err));
        }
        @Override public void onRegistrationStateChanged(
                Transport.RegistrationState s, String r) {
            states.add(new StateEvent(s, r));
        }
    }
}

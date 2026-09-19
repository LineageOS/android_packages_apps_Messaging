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
package com.android.messaging.rcs.carrier;

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
 * Host-side unit tests for {@link CarrierTransportBridge} — the pure-Java
 * glue used by {@code CarrierRcsTransport} to translate registrar state and
 * receiver callbacks into {@link Transport.Listener} dispatches.
 *
 * <p>These tests exercise the GLUE, not the underlying SIP/CPIM/IMDN pieces
 * (those have their own tests). Specifically:
 *
 * <ul>
 *   <li>Listener dispatch on incoming MESSAGE
 *       ({@link #wrap_forwardsOnIncomingMessage}).</li>
 *   <li>Status update propagation on incoming IMDN
 *       ({@link #wrap_forwardsOnMessageStatus},
 *       {@link #wrap_terminalStatusClearsTrackedId}).</li>
 *   <li>State machine: UNREGISTERED → REGISTERING → REGISTERED → FAILED
 *       ({@link #mapRegistrarState_translatesAllStates},
 *       {@link #wrap_forwardsRegistrationStateChange}).</li>
 *   <li>Behavior when SIP creds aren't configured
 *       ({@link #isConfigUsable_nullOrIncomplete}).</li>
 * </ul>
 *
 * <p>{@code CarrierRcsTransport} itself isn't host-testable because it pulls
 * {@code android.util.Log}, {@code android.content.Context}, and the
 * JAIN-SIP / CarrierMessageSender stack — all on-device. The {@code Bridge}
 * is the slice the tests target, by design.
 */
public class CarrierTransportBridgeTest {

    private CarrierTransportBridge bridge;

    @Before
    public void setUp() {
        bridge = new CarrierTransportBridge();
    }

    // ============================================================
    // State mapping
    // ============================================================

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

    // ============================================================
    // Config gating
    // ============================================================

    @Test
    public void isConfigUsable_nullOrIncomplete() {
        assertFalse("null config", CarrierTransportBridge.isConfigUsable(null));

        RcsImsConfig.Builder b = new RcsImsConfig.Builder();
        // Empty Builder: every required field null.
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

    // ============================================================
    // Switchover-size routing
    // ============================================================

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
        // T-Mobile's mSwitchoverSize, as observed on the wire for a SIP MESSAGE.
        assertEquals(1300, CarrierTransportBridge.MSRP_SWITCHOVER_SIZE_BYTES);
    }

    // ============================================================
    // Terminal-status classification
    // ============================================================

    @Test
    public void isTerminal_classification() {
        // Terminal: a server-side disposition has been reached.
        assertTrue(CarrierTransportBridge.isTerminal(Message.Status.SENT));
        assertTrue(CarrierTransportBridge.isTerminal(Message.Status.DELIVERED));
        assertTrue(CarrierTransportBridge.isTerminal(Message.Status.DISPLAYED));
        assertTrue(CarrierTransportBridge.isTerminal(Message.Status.FAILED));

        // In-flight or non-applicable: not terminal.
        assertFalse(CarrierTransportBridge.isTerminal(Message.Status.PENDING));
        assertFalse(CarrierTransportBridge.isTerminal(Message.Status.SENDING));
        assertFalse(CarrierTransportBridge.isTerminal(Message.Status.RECEIVED));
        assertFalse(CarrierTransportBridge.isTerminal(null));
    }

    // ============================================================
    // Outgoing-id tracking
    // ============================================================

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

    // ============================================================
    // Listener bridge
    // ============================================================

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

        // Non-terminal status: registry untouched.
        wrapped.onMessageStatus("msg-1", Message.Status.SENDING, null);
        assertTrue("SENDING is not terminal", bridge.isTrackingOutgoing("msg-1"));

        // Terminal status: id pruned.
        wrapped.onMessageStatus("msg-1", Message.Status.DELIVERED, null);
        assertFalse("DELIVERED prunes", bridge.isTrackingOutgoing("msg-1"));

        // The other id is unaffected.
        assertTrue(bridge.isTrackingOutgoing("msg-2"));

        // The downstream still sees both events.
        assertEquals(2, rec.statuses.size());
    }

    @Test
    public void wrap_terminalStatusForUnknownIdStillForwards() {
        RecordingListener rec = new RecordingListener();
        Transport.Listener wrapped = bridge.wrap(rec);

        // The app de-dupes on its own database; the bridge mustn't drop unknown ids
        // (they can be IMDNs for messages from another process / session).
        wrapped.onMessageStatus("foreign-id", Message.Status.DELIVERED, null);

        assertEquals(1, rec.statuses.size());
        assertEquals("foreign-id", rec.statuses.get(0).messageId);
        assertEquals(Message.Status.DELIVERED, rec.statuses.get(0).status);
    }

    @Test
    public void wrap_nullDownstreamDoesNotThrow() {
        Transport.Listener wrapped = bridge.wrap(null);

        bridge.trackOutgoing("msg-1");
        // Should not NPE — bridge still does its side-effects (id tracking),
        // and the forward is a no-op.
        wrapped.onIncomingMessage("tel:+1", "body", "msg-1");
        wrapped.onMessageStatus("msg-1", Message.Status.SENT, null);
        wrapped.onRegistrationStateChanged(
                Transport.RegistrationState.REGISTERED, null);

        // Terminal status still pruned even without a downstream.
        assertFalse(bridge.isTrackingOutgoing("msg-1"));
    }

    @Test
    public void wrap_forwardsRegistrationStateChange() {
        RecordingListener rec = new RecordingListener();
        Transport.Listener wrapped = bridge.wrap(rec);

        // The transport drives this through the registrar in production;
        // here we exercise the bridge directly to verify the listener path
        // works for the full state-transition sequence:
        //   UNREGISTERED -> REGISTERING -> REGISTERED -> FAILED
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

    // ============================================================
    // Helpers
    // ============================================================

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

        @Override public void onIncomingMessage(String from, String body, String id) {
            incoming.add(new Event(from, body, id));
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

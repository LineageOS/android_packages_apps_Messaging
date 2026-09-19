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

import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.carrier.Transport;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure-Java glue that {@link CarrierRcsTransport} uses to translate
 * registrar state and receiver callbacks into {@link Transport.Listener}
 * dispatches. Kept dependency-free (no Android imports, no JAIN-SIP) so it
 * can be unit-tested on the host JVM via {@code messaging-rcs-carrier-host-tests}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Map {@link CarrierSipRegistrarState} -&gt;
 *       {@link Transport.RegistrationState}.</li>
 *   <li>Expose a {@link Transport.Listener} bridge that intercepts the
 *       {@code messageId} on {@link Transport.Listener#onMessageStatus}
 *       (drops it from the outgoing-id registry once a terminal status
 *       arrives) and forwards everything to the underlying listener.</li>
 *   <li>Validate an {@link RcsImsConfig} for "usable enough to REGISTER".</li>
 *   <li>Compute whether a body should travel pager-mode or MSRP-session
 *       based on the GSMA UP {@code mSwitchoverSize} threshold.</li>
 * </ul>
 *
 * <p>Threading: the bridge holds a {@link ConcurrentHashMap} for outgoing
 * ids so producers (the transport's send thread) and consumers (the
 * receiver's SIP thread) can hit it concurrently without external locking.
 */
public final class CarrierTransportBridge {

    /** The per-carrier switchover-size constant (T-Mobile config:
     *  {@code mSwitchoverSize=1300}). At or above this the message body
     *  should travel over an MSRP session instead of SIP MESSAGE. The
     *  carrier transport consults {@link #shouldUseMsrpSession} and, when
     *  true, opens / reuses an MSRP chat session via
     *  {@code CarrierMsrpSessionManager}. */
    public static final int MSRP_SWITCHOVER_SIZE_BYTES = 1300;

    /** Outgoing message-ids the transport has emitted but not yet seen a
     *  terminal IMDN report for. Bounded by stop() / removal on report. */
    private final ConcurrentHashMap<String, Long> outgoingIds =
            new ConcurrentHashMap<>();

    /** Track-only-no-op: lets tests confirm an id is being tracked. */
    public void trackOutgoing(String messageId) {
        if (messageId == null) return;
        outgoingIds.put(messageId, System.currentTimeMillis());
    }

    public boolean isTrackingOutgoing(String messageId) {
        return messageId != null && outgoingIds.containsKey(messageId);
    }

    public int outgoingCount() { return outgoingIds.size(); }

    public Set<String> trackedIds() {
        return Collections.unmodifiableSet(outgoingIds.keySet());
    }

    public void clearTracking() { outgoingIds.clear(); }

    // ---------- state mapping ----------

    /**
     * Translate registrar state to transport state. The two enums have a
     * 1:1 correspondence today but are kept distinct so a transport
     * implementation could synthesize additional sub-states (e.g.
     * "REGISTERED but no media") without leaking into the public
     * {@link Transport.RegistrationState} surface.
     */
    public static Transport.RegistrationState mapRegistrarState(
            CarrierSipRegistrarState s) {
        if (s == null) return Transport.RegistrationState.UNREGISTERED;
        switch (s) {
            case UNREGISTERED: return Transport.RegistrationState.UNREGISTERED;
            case REGISTERING: return Transport.RegistrationState.REGISTERING;
            case REGISTERED:  return Transport.RegistrationState.REGISTERED;
            case FAILED:      return Transport.RegistrationState.FAILED;
            default:          return Transport.RegistrationState.UNREGISTERED;
        }
    }

    // ---------- config gating ----------

    /**
     * Returns true iff {@code c} has the minimum field set a
     * {@link CarrierSipRegistrar#buildRegister} needs to assemble a
     * well-formed REGISTER. Mirrors the explicit null-check at the top of
     * that method so the transport can short-circuit before spinning up a
     * SIP stack we know will fail.
     */
    public static boolean isConfigUsable(RcsImsConfig c) {
        return c != null
                && nonEmpty(c.pcscfAddress)
                && nonEmpty(c.domain)
                && nonEmpty(c.userName)
                && nonEmpty(c.publicIdentity);
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    // ---------- send-path routing ----------

    /**
     * True iff a body of length {@code bodyByteLen} should travel via the
     * MSRP-session path instead of pager-mode SIP MESSAGE. The carrier
     * transport calls this to pick a route — when true, an MSRP chat
     * session is opened and the body is delivered as an MSRP SEND
     *; when false, the body fits in a single pager-mode CPIM
     * SIP MESSAGE.
     */
    public static boolean shouldUseMsrpSession(int bodyByteLen) {
        return bodyByteLen >= MSRP_SWITCHOVER_SIZE_BYTES;
    }

    // ---------- listener bridge ----------

    /**
     * Wrap {@code downstream} (typically the UI's Transport.Listener) with
     * a delegating listener that tracks outgoing-id lifecycle:
     *
     * <ul>
     *   <li>{@code onIncomingMessage} / {@code onIncomingContent} forward verbatim
     *       (the latter keeps the body as BYTES).</li>
     *   <li>{@code onMessageStatus} drops the id from the outgoing-id
     *       registry if the status is terminal (SENT, DELIVERED,
     *       DISPLAYED, FAILED) — keeping the registry from leaking
     *       memory across long sessions — then forwards.</li>
     *   <li>{@code onRegistrationStateChanged} forwards verbatim.</li>
     * </ul>
     *
     * <p>Null {@code downstream} is permitted (e.g. transport pre-listener-
     * registration) — forwarding becomes a no-op but tracking-side-effects
     * still run.
     */
    public Transport.Listener wrap(final Transport.Listener downstream) {
        return new Transport.Listener() {
            @Override
            public void onIncomingMessage(String fromUri, String body, String messageId) {
                if (downstream != null) {
                    downstream.onIncomingMessage(fromUri, body, messageId);
                }
            }
            @Override
            public void onIncomingMessage(String fromUri, String body, String messageId,
                    String e2eeSchemeId) {
                if (downstream != null) {
                    downstream.onIncomingMessage(fromUri, body, messageId, e2eeSchemeId);
                }
            }
            @Override
            public void onIncomingContent(String fromUri, byte[] body, String contentType,
                    String messageId, String e2eeSchemeId) {
                // Forward the BYTES, never the inherited String fallback: this hop
                // carries decrypted MLS content, which is binary for anything but plain text.
                if (downstream != null) {
                    downstream.onIncomingContent(fromUri, body, contentType, messageId,
                            e2eeSchemeId);
                }
            }

            @Override
            public void onMessageStatus(String messageId,
                    Message.Status status, String errorReason) {
                if (messageId != null && isTerminal(status)) {
                    outgoingIds.remove(messageId);
                }
                if (downstream != null) {
                    downstream.onMessageStatus(messageId, status, errorReason);
                }
            }

            @Override
            public void onRegistrationStateChanged(
                    Transport.RegistrationState s, String reason) {
                if (downstream != null) {
                    downstream.onRegistrationStateChanged(s, reason);
                }
            }
        };
    }

    /**
     * Status values that conclude the lifecycle of an outgoing message —
     * after one of these the transport no longer expects further
     * server-side updates and can drop tracking state.
     */
    public static boolean isTerminal(Message.Status s) {
        if (s == null) return false;
        switch (s) {
            case SENT:
            case DELIVERED:
            case DISPLAYED:
            case FAILED:
                return true;
            case PENDING:
            case SENDING:
            case RECEIVED:
            default:
                return false;
        }
    }
}

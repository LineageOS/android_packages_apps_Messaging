/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.carrier.Transport;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host-testable glue for {@link CarrierRcsTransport}: registrar state mapping, configuration
 * checks, the pager/session size rule, and a listener wrapper that stops tracking an outgoing id
 * once its status is terminal. The id map is concurrent; send and SIP threads both use it.
 */
public final class CarrierTransportBridge {

    /**
     * Bodies of at least this many UTF-8 bytes go over an MSRP session rather than as a
     * pager-mode SIP message; the GSMA UP {@code SwitchoverSize}.
     */
    public static final int MSRP_SWITCHOVER_SIZE_BYTES = 1300;

    /** Outgoing ids without a terminal status yet. */
    private final ConcurrentHashMap<String, Long> outgoingIds =
            new ConcurrentHashMap<>();

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

    /** True when {@code c} has what {@link CarrierSipRegistrar#buildRegister} needs. */
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

    public static boolean shouldUseMsrpSession(int bodyByteLen) {
        return bodyByteLen >= MSRP_SWITCHOVER_SIZE_BYTES;
    }

    /**
     * Forwards everything to {@code downstream}, which may be null, and drops an id from tracking
     * on a terminal status. Each inbound form is forwarded as itself: a byte form that fell back to
     * its String default would replace invalid UTF-8 with U+FFFD.
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
            public void onIncomingBytes(String fromUri, byte[] body, String messageId) {
                // An MSRP session body: a CPIM envelope that may wrap a binary inner type.
                if (downstream != null) {
                    downstream.onIncomingBytes(fromUri, body, messageId);
                }
            }
            @Override
            public void onIncomingContent(String fromUri, byte[] body, String contentType,
                    String messageId, String e2eeSchemeId) {
                // Forward the bytes: decrypted MLS content is binary for anything but text.
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

    /** After one of these no further status is expected. */
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

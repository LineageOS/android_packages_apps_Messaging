/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.RcsOutgoingMessage;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsSendResult;
import org.lineageos.rcs.provider.RcsSubInfo;

/**
 * The seam the rest of Messaging uses to reach an RCS route, one implementation per route. Every
 * method is safe before the transport is bound (it no-ops or answers not-registered) and is called
 * off the main thread. See docs/rcs/architecture.md.
 */
public interface RcsTransport {

    /** True once attach() has succeeded and a clientToken is held. */
    boolean isAttached();

    /**
     * Static capabilities, including the declared {@code priority}; null until known. Non-blocking.
     */
    @Nullable
    RcsProviderCaps getProviderCaps();

    /**
     * Side-effect-free line eligibility ({@code IRcsProvider.LINE_*}); {@code LINE_UNKNOWN} if
     * unattached.
     */
    int canServeSub(RcsSubInfo sub);

    /** Begin or resume provisioning and registration. Idempotent. */
    void startForSub(RcsSubInfo sub);

    /** Tear down registration for a sub. Idempotent. */
    void stopForSub(int subId);

    /** Latest negotiated capabilities for the subscription, or null. */
    RcsProviderCaps getCapabilitiesForSub(int subId);

    /** Whether the destination is on RCS ({@code IRcsProvider.CAP_*}). May block on the network. */
    int lookupRcsCapability(int subId, String phoneE164);

    /** Send a 1:1 text; a synchronous accept or reject, never a throw. */
    RcsSendResult sendMessage(RcsOutgoingMessage msg);

    /** Whether this transport can carry app-sealed MLS now; only the carrier transport says yes. */
    default boolean isMlsReady(int subId) {
        return false;
    }

    /**
     * Send an MLS 1:1 message that the send gate has already routed here. The transport owns the
     * MLS flow; the terminal status arrives as a status callback correlated by {@code messageId}.
     *
     * @param toUri {@code tel:+E164} of the peer
     * @param framedBody an RCC.16 MIME entity from {@code RccMlsBody.frame} or {@code frameText}
     * @return true if the transport took the send, false if it cannot carry MLS
     */
    default boolean sendMlsMessage(int subId, String toUri, byte[] framedBody, String messageId) {
        return false;
    }

    /** Send a delivered or displayed receipt ({@code IRcsProviderCallback.IMDN_*}). */
    void sendImdn(String originalMessageId, String toUri, int imdnType);

    /**
     * As {@link #sendImdn}, naming the group the message arrived in; on MLS the group id selects
     * which group state stamps the receipt.
     */
    default void sendImdn(String originalMessageId, String toUri, int imdnType,
            String rcsGroupId) {
        sendImdn(originalMessageId, toUri, imdnType);
    }

    /** Typing indicator. Best-effort; no-op if not attached. */
    void sendTyping(String toUri, boolean active);

    /** Pass an OTP caught by the app (as default SMS app) to the provisioning flow. */
    void submitOtp(int subId, String otp);

    /** Decline an inbound file the user chose not to download (SIP 603 on a session transport). */
    void rejectIncomingFile(int subId, String messageId);
}

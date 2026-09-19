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

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;

import com.android.messaging.util.LogUtil;

/**
 * The Dual-Registration (DR) fallback seam (design §6.3, D6). When the per-sub
 * probe ({@link CarrierImsMode#probe}) reports {@link CarrierImsMode#DR} -- the
 * modem/carrier does not expose the {@code SipDelegate} single-registration API
 * -- {@link CarrierImsService} drives an implementation of THIS interface instead
 * of the in-tree {@code rcs/sip/**} SR code.
 *
 * <p>The real DR implementation is the OpenRCSChat
 * the provider app's carrier stack ({@code
 * CarrierSipRegistrar} + JAIN-SIP {@code REGISTER} + its own MSRP via {@code
 * CarrierMsrpSessionManager}, glued by {@code CarrierTransportBridge}). That code
 * does its OWN SIP REGISTER over the IMS APN (NOT role-gated) and is a co-equal
 * body of work to the SR path.
 *
 * <hr>
 * <b>Shared-library question: RESOLVED — option (a) taken.</b>
 *
 * <p>The DR stack was previously host-test-only. It now lives in this app's own
 * sources (it pulls in {@code nist-sip} for JAIN-SIP). The live implementation of this
 * interface is {@link CarrierStackDrModeDriver}, which drives {@code
 * CarrierRcsTransport} + {@code CarrierSipRegistrar} in the {@code :ims} process
 * — the design's "one transport, two internal modes" (§6.3, D6). It builds cleanly
 * as part of the app; the only remaining device-only gap is the RCC.14 autoconfig ->
 * {@code RcsImsConfig} mapping (RIG-VERIFY inside {@link CarrierStackDrModeDriver}).
 *
 * <p>The {@link NoOp} below is retained only as a defensive fallback (e.g. a unit
 * context without the carrier stack); {@link CarrierImsService} uses
 * {@link CarrierStackDrModeDriver}.
 */
public interface CarrierDrModeDriver {

    /** Callbacks from the DR stack back to {@link CarrierImsService}. Mirrors the
     *  event surface {@link CarrierImsService} already emits toward the main
     *  process, so the service can forward DR events through the same seam as SR. */
    interface Listener {
        /** state == one of IRcsProviderCallback.REG_*; reason free-form/nullable. */
        void onRegistrationState(int subId, int state, @Nullable String reason);
        /** state == one of IRcsProviderCallback.PROV_*. */
        void onProvisioningState(int subId, int state);
        /** An inbound 1-1 text arrived (fromE164, UTF-8 body, peer/server messageId). */
        void onIncomingText(int subId, String fromE164, String body, String messageId);
        /** As above, with the E2EE provenance scheme it was decrypted under; null=plaintext.
         *  Default drops the tag to the 4-arg form. */
        default void onIncomingText(int subId, String fromE164, String body, String messageId,
                @Nullable String e2eeSchemeId) {
            onIncomingText(subId, fromE164, body, messageId);
        }
        /**
         * An inbound message whose CONTENT is bytes plus its real (already unframed) content type —
         * the binary-safe form. Everything decrypted from MLS arrives here:
         * {@code text/plain} for a chat bubble, {@code image/*} for inline media, {@code
         * message/imdn+xml} for a receipt. The String forms above cannot carry any of the latter —
         * {@code new String(bytes, UTF_8)} destroys every byte sequence that is not valid UTF-8 —
         * so this is the form the MLS leg uses and the String forms stay for plaintext RCS text.
         *
         * <p>The default degrades to the lossy String form so existing implementers keep compiling;
         * {@code CarrierImsService} overrides it and is the one that matters.
         */
        default void onIncomingContent(int subId, String fromE164, byte[] body, String contentType,
                String messageId, @Nullable String e2eeSchemeId) {
            onIncomingText(subId, fromE164,
                    body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8),
                    messageId, e2eeSchemeId);
        }
        /** Terminal status for one of our sends (status == IRcsProviderCallback.STATUS_*). */
        void onMessageStatus(int subId, String messageId, int status, @Nullable String reason);
    }

    /** Begin DR: own SIP REGISTER over the IMS APN for this sub. Idempotent. */
    void startForSub(int subId, @Nullable String msisdn, @Nullable String mccMnc,
            Listener listener);

    /** Tear down the DR registration. Idempotent. */
    void stopForSub(int subId);

    /**
     * Send a 1-1 text. Returns true if accepted for delivery (pager-mode SIP
     * MESSAGE or an MSRP session per the switchover-size rule); false to fall
     * back to SMS.
     */
    boolean sendText(int subId, String fromE164, String toE164, String messageId, String body);

    /** Whether DR is currently registered enough to attempt a send for the sub. */
    boolean isRegistered(int subId);

    /** Send an isComposing typing indicator (active/idle) as a pager-mode MESSAGE.
     *  Best-effort; default no-op for impls without a typing path. */
    default void sendTyping(int subId, String fromE164, String toE164, boolean active) { }

    /** Send an IMDN report (e.g. display/read, {@code imdnType} per
     *  {@link CarrierImsSeam}) for {@code messageId} back to {@code toE164} as a
     *  pager-mode MESSAGE. Default no-op for impls without an explicit IMDN path. */
    default void sendImdn(int subId, String fromE164, String toE164,
            String messageId, int imdnType) { }

    /** DEBUG: upload {@code path} to the ACS FT content server and log the
     *  descriptor (FT-HTTP upload-leg test). Default no-op. */
    default void debugFtUpload(int subId, String path, String toE164) { }

    /** DEBUG: send a PLAIN (non-CPM) text/plain MESSAGE to {@code toE164} to
     *  exercise the pure IMS terminating path (no oma.cpm iFC match). */
    default void sendPlainDebug(int subId, String fromE164, String toE164,
            String text, String messageId) { }

    /** Send an MLS-E2EE ({@code message/mls}) chat message to {@code toE164} over the carrier
     *  CPM/MSRP session — the app-provided-MLS path. {@code framedBody} is an RCC.16
     *  MIME entity, not text: the inner content type rides inside the frame. Default
     *  no-op. */
    default void sendMls(int subId, String fromE164, String toE164,
            byte[] framedBody, String messageId) { }

    /** Feed the RCC.14/RCC.07 autoconfig doc from the ACS (fetched by the modem)
     *  so the driver can build the real RcsImsConfig from the IMS-Settings. Default
     *  no-op for impls that don't consume it. */
    default void setAcsConfig(@Nullable byte[] configXml) { }

    /**
     * Defensive logging fallback (superseded by {@link CarrierStackDrModeDriver},
     * which is what {@link CarrierImsService} actually uses now that the DR stack
     * is on the classpath). Every call returns "not registered" so a DR-mode sub
     * cleanly falls back to SMS. Retained for contexts without the carrier stack.
     */
    final class NoOp implements CarrierDrModeDriver {
        private static final String TAG = LogUtil.BUGLE_TAG;

        @Override
        public void startForSub(final int subId, @Nullable final String msisdn,
                @Nullable final String mccMnc, final Listener listener) {
            LogUtil.w(TAG, "CarrierDrModeDriver.NoOp: startForSub sub=" + subId
                    + " -- NoOp fallback; reporting REG_FAILED (SMS fallback)");
            // Surface a terminal failure so RouteSelector falls this sub through
            // to SMS (design §5.4 step 5). The real driver is
            // CarrierStackDrModeDriver.
            if (listener != null) {
                listener.onProvisioningState(subId,
                        IRcsProviderCallback.PROV_DISABLED_BY_CARRIER);
                listener.onRegistrationState(subId, IRcsProviderCallback.REG_FAILED,
                        "DR mode not implemented in messaging2 build");
            }
        }

        @Override
        public void stopForSub(final int subId) {
            LogUtil.i(TAG, "CarrierDrModeDriver.NoOp: stopForSub sub=" + subId);
        }

        @Override
        public boolean sendText(final int subId, final String fromE164, final String toE164,
                final String messageId, final String body) {
            LogUtil.w(TAG, "CarrierDrModeDriver.NoOp: sendText dropped (DR not wired)");
            return false;
        }

        @Override
        public boolean isRegistered(final int subId) {
            return false;
        }
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;

import com.android.messaging.util.LogUtil;

/**
 * The DR engine as {@link CarrierImsService} drives it when {@link CarrierImsMode#probe} reports
 * {@link CarrierImsMode#DR}. The live implementation is {@link CarrierStackDrModeDriver}.
 */
public interface CarrierDrModeDriver {

    /** DR events, mirroring what {@link CarrierImsService} emits for SR. */
    interface Listener {
        /** {@code state} is an {@code IRcsProviderCallback.REG_*}. */
        void onRegistrationState(int subId, int state, @Nullable String reason);
        /** {@code state} is an {@code IRcsProviderCallback.PROV_*}. */
        void onProvisioningState(int subId, int state);
        void onIncomingText(int subId, String fromE164, String body, String messageId);
        /** As above, with the E2EE scheme the body was decrypted under, or null for plaintext. */
        default void onIncomingText(int subId, String fromE164, String body, String messageId,
                @Nullable String e2eeSchemeId) {
            onIncomingText(subId, fromE164, body, messageId);
        }
        /**
         * Content bytes and their real, already unframed content type; everything decrypted from
         * MLS arrives here. The default is the lossy String form; {@code CarrierImsService}
         * overrides it.
         */
        default void onIncomingContent(int subId, String fromE164, byte[] body, String contentType,
                String messageId, @Nullable String e2eeSchemeId) {
            onIncomingText(subId, fromE164,
                    body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8),
                    messageId, e2eeSchemeId);
        }
        /**
         * Terminal status of a send; {@code status} is an {@code IRcsProviderCallback.STATUS_*}.
         */
        void onMessageStatus(int subId, String messageId, int status, @Nullable String reason);
    }

    /** Starts the app's own SIP registration for the subscription. Idempotent. */
    void startForSub(int subId, @Nullable String msisdn, @Nullable String mccMnc,
            Listener listener);

    /** Idempotent. */
    void stopForSub(int subId);

    /** True if accepted for delivery, pager mode or session by size; false to fall back to SMS. */
    boolean sendText(int subId, String fromE164, String toE164, String messageId, String body);

    boolean isRegistered(int subId);

    /** Best effort. */
    default void sendTyping(int subId, String fromE164, String toE164, boolean active) { }

    /** Sends an IMDN; {@code imdnType} as in {@link CarrierImsSeam#KEY_IMDN_TYPE}. */
    default void sendImdn(int subId, String fromE164, String toE164,
            String messageId, int imdnType) { }

    /** Debug: uploads {@code path} to the content server and logs the descriptor. */
    default void debugFtUpload(int subId, String path, String toE164) { }

    /** Debug: sends a non-CPM {@code text/plain} SIP message. */
    default void sendPlainDebug(int subId, String fromE164, String toE164,
            String text, String messageId) { }

    /** {@code framedBody} is an RCC.16-framed entity, not text; the inner type rides inside it. */
    default void sendMls(int subId, String fromE164, String toE164,
            byte[] framedBody, String messageId) { }

    /**
     * The autoconfiguration document the modem fetched, from which the driver builds its config.
     */
    default void setAcsConfig(@Nullable byte[] configXml) { }

    /** Reports every subscription as failed, so the line falls back to SMS. */
    final class NoOp implements CarrierDrModeDriver {
        private static final String TAG = LogUtil.BUGLE_TAG;

        @Override
        public void startForSub(final int subId, @Nullable final String msisdn,
                @Nullable final String mccMnc, final Listener listener) {
            LogUtil.w(TAG, "CarrierDrModeDriver.NoOp: startForSub sub=" + subId
                    + " -- NoOp fallback; reporting REG_FAILED (SMS fallback)");
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

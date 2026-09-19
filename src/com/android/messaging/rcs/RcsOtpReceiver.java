/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony.Sms;
import android.telephony.SmsMessage;

import com.android.messaging.receiver.SmsReceiver;
import com.android.messaging.sms.MmsConfig;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

/**
 * Catches a provisioning OTP SMS, which only the default SMS app can see. On a
 * {@link Pev3OtpMatcher} match it passes the code to the provider with
 * {@link ProviderTransport#submitOtp} and consumes the message, so it never becomes a bubble; any
 * other message continues to {@link SmsReceiver#deliverSmsIntent} as usual. The manifest routes
 * only {@code DATA_SMS_RECEIVED} here; {@code SMS_DELIVER} stays with {@code SmsDeliverReceiver}.
 */
public final class RcsOtpReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static final String ACTION_DATA_SMS_RECEIVED =
            "android.intent.action.DATA_SMS_RECEIVED";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent.getAction();
        if (action == null) {
            return;
        }
        if (!PhoneUtils.getDefault().isSmsEnabled()) {
            // Not the default SMS app; let the normal path (if any) handle it.
            fallThrough(context, intent);
            return;
        }

        final boolean isDataSms = ACTION_DATA_SMS_RECEIVED.equals(action);
        final boolean isSmsDeliver = Sms.Intents.SMS_DELIVER_ACTION.equals(action);
        if (!isDataSms && !isSmsDeliver) {
            return;
        }

        final SmsMessage[] messages = SmsReceiver.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) {
            fallThrough(context, intent);
            return;
        }

        final int subId = PhoneUtils.getDefault()
                .getEffectiveIncomingSubIdFromSystem(intent, "subscription");

        final Pev3OtpMatcher.Match match = Pev3OtpMatcher.tryMatch(messages, subId);
        if (match != null) {
            LogUtil.i(TAG, "RcsOtpReceiver: swallowed Pev3 OTP SMS, submitting to provider");
            final ProviderTransport transport = ProviderTransport.peekInstance();
            if (transport != null) {
                transport.submitOtp(subId, match.otp);
            } else {
                LogUtil.w(TAG, "RcsOtpReceiver: provider not bound; OTP dropped");
            }
            // Consume: do not deliver as a chat message.
            if (isOrderedBroadcast()) {
                abortBroadcast();
            }
            return;
        }

        // Not an OTP: normal delivery. A data SMS has no normal path, so only SMS_DELIVER
        // continues.
        if (isSmsDeliver) {
            fallThrough(context, intent);
        }
    }

    private void fallThrough(final Context context, final Intent intent) {
        if (Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) {
            SmsReceiver.deliverSmsIntent(context, intent);
        }
    }
}

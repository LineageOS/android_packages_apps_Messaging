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
 * OTP catcher. The main app holds the default-SMS role, so it is the only
 * component that can see the Pev3 verification SMS. This receiver sits in
 * front of the normal SMS pipeline:
 *
 * <ul>
 *   <li>{@code DATA_SMS_RECEIVED} -- port-addressed Pev3 verify channel; and
 *   <li>{@code SMS_DELIVER} -- ordinary OTP-shortcode SMS.
 * </ul>
 *
 * <p>On a {@link Pev3OtpMatcher} hit it forwards the OTP to the provider via
 * {@link ProviderTransport#submitOtp} and <b>swallows</b> the message (does
 * NOT call {@link SmsReceiver#deliverSmsIntent}), so the verification code
 * never appears as a chat bubble. On a miss it falls through to the normal
 * delivery path exactly as {@link com.android.messaging.receiver.SmsDeliverReceiver}
 * would, so non-OTP SMS behaviour is unchanged.
 *
 * <p>Manifest ordering: this receiver is declared with a HIGHER priority than
 * SmsDeliverReceiver on the SMS_DELIVER filter and is the sole DATA_SMS
 * receiver. Because SMS_DELIVER is an ordered broadcast to the default-SMS
 * app, only one receiver should consume it; on a miss we re-dispatch to the
 * normal SmsReceiver path here rather than relying on a second receiver
 * firing. (See manifest notes -- SmsDeliverReceiver's SMS_DELIVER filter is
 * removed in favour of this one to avoid double-delivery.)
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
            // Swallow: do NOT deliver as a chat message.
            if (isOrderedBroadcast()) {
                abortBroadcast();
            }
            return;
        }

        // Not an OTP -- normal delivery. For DATA_SMS (port-addressed) the
        // normal app ignores it, so we only re-dispatch SMS_DELIVER.
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

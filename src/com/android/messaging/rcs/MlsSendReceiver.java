/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import com.android.messaging.rcs.carrier.CarrierImsTransport;
import com.android.messaging.rcs.engine.mls.RccMlsBody;
import com.android.messaging.util.LogUtil;

import java.util.UUID;

/**
 * Debug-only driver that sends one MLS message over the in-process carrier transport
 * ({@link CarrierImsTransport#sendMls}), bypassing the compose UI. Ignored unless the build is
 * debuggable. The body is framed exactly as production frames it.
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.MLS_SEND \
 *     -n com.android.messaging/.rcs.MlsSendReceiver --es to &lt;e164&gt; --es body "hi" \
 *     [--es contenttype &lt;mime&gt;]
 * </pre>
 */
public final class MlsSendReceiver extends BroadcastReceiver {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    static final String ACTION = "com.android.messaging.debug.MLS_SEND";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        if (!RcsDebug.isDebugBuild()) {
            LogUtil.w(TAG, "MLS_SEND ignored: build is not debuggable");
            return;
        }
        final String to = intent.getStringExtra("to");
        final String body = intent.getStringExtra("body");
        final String contentType = intent.getStringExtra("contenttype");
        if (TextUtils.isEmpty(to) || TextUtils.isEmpty(body)) {
            LogUtil.w(TAG, "MLS_SEND missing --es to / --es body");
            return;
        }
        final CarrierImsTransport transport = CarrierImsTransport.peek();
        if (transport == null) {
            LogUtil.w(TAG,
                    "MLS_SEND ignored: CarrierImsTransport not registered (carrier RCS up?)");
            return;
        }
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            subId = SubscriptionManager.getDefaultSubscriptionId();
        }
        final String toUri = to.startsWith("tel:") ? to : "tel:" + to;
        final String messageId = "mls-" + UUID.randomUUID();
        // Same framing as InsertNewMessageAction; --es contenttype frames a non-text inner type.
        final byte[] framed = TextUtils.isEmpty(contentType)
                ? RccMlsBody.frameText(body)
                : RccMlsBody.frame(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        contentType, /*inline=*/ true);
        LogUtil.i(TAG, "MLS_SEND to=" + toUri + " mid=" + messageId + " ct="
                + (TextUtils.isEmpty(contentType) ? "text/plain" : contentType)
                + " framed=" + framed.length + "B -> CarrierImsTransport.sendMls");
        transport.sendMls(subId, toUri, framed, messageId);
    }
}

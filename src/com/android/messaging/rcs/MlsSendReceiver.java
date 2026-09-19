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
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import com.android.messaging.rcs.carrier.CarrierImsTransport;
import com.android.messaging.rcs.e2ee.RccMlsBody;
import com.android.messaging.util.LogUtil;

import java.util.UUID;

/**
 * Debug-only driver for an MLS-E2EE chat message over the carrier CPM/MSRP session — the
 * app-provided-MLS path end to end (enrol/claim/group/Welcome/encrypt in the :ims transport). Drives
 * {@link CarrierImsTransport#sendMls} on the in-process carrier transport, bypassing the compose UI.
 *
 * <p>Exported for adb, gated on {@link Build#TYPE} (eng/userdebug). Read {@code MessagingApp} +
 * {@code MlsCarrierXport} in logcat.
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.MLS_SEND \
 *     -n com.android.messaging/.rcs.MlsSendReceiver --es to +11012026331 --es body "padlock hi"
 *
 *   # exercise a NON-TEXT inner content type — the capability the carrier leg
 *   # gained when it stopped being String-typed. --es body is still the payload, as UTF-8.
 *   ... --es to +1... --es body '&lt;xml/&gt;' --es contenttype application/vnd.gsma.rcspushlocation+xml
 * </pre>
 *
 * <p><b>This frames exactly as production does</b> ({@code RccMlsBody.frameText}, the same call
 * {@code InsertNewMessageAction} makes). A debug driver that takes a different path than production
 * proves the wrong thing — and this one used to send raw text, which is precisely
 * the difference that hid the missing framing.
 */
public final class MlsSendReceiver extends BroadcastReceiver {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    static final String ACTION = "com.android.messaging.debug.MLS_SEND";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        if (!"eng".equals(Build.TYPE) && !"userdebug".equals(Build.TYPE)) {
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
            LogUtil.w(TAG, "MLS_SEND ignored: CarrierImsTransport not registered (carrier RCS up?)");
            return;
        }
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            subId = SubscriptionManager.getDefaultSubscriptionId();
        }
        final String toUri = to.startsWith("tel:") ? to : "tel:" + to;
        final String messageId = "mls-" + UUID.randomUUID();
        // FRAME IT — the same entity production builds. An explicit --es contenttype
        // routes through RccMlsBody.frame so a non-text type can be exercised at all; without one
        // this is byte-identical to what InsertNewMessageAction sends.
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

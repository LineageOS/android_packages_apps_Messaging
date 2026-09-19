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

import com.android.messaging.datamodel.action.InsertNewMessageAction;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.util.LogUtil;

/**
 * Debug-only driver that sends a 1-1 message through the <b>real compose send path</b>
 * ({@link InsertNewMessageAction}) — the same entry the UI uses — so the E2EE gate
 * ({@code E2eeSendGate} → auto MLS/Etouffee routing, own-bubble padlock
 * stamp, and the durable {@code conversation.encryption_protocol} latch) is exercised
 * end to end. Unlike {@link RcsDebugSendReceiver} (which calls the transport directly
 * and bypasses the gate), this proves automatic activation from a composed message.
 *
 * <p>Gated on {@link Build#TYPE} (eng/userdebug); inert on user builds.
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.COMPOSE_SEND \
 *     -n com.android.messaging/.rcs.RcsDebugComposeSendReceiver \
 *     --es to +11012026331 --es body helloE2ee
 * </pre>
 * Grep logcat for {@code MessagingApp} (the {@code InsertNewMessageAction} gate lines).
 */
public final class RcsDebugComposeSendReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;
    static final String ACTION = "com.android.messaging.debug.COMPOSE_SEND";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        if (!"eng".equals(Build.TYPE) && !"userdebug".equals(Build.TYPE)) {
            LogUtil.w(TAG, "COMPOSE_SEND ignored: build is not debuggable");
            return;
        }
        final String to = intent.getStringExtra("to");
        final String body = intent.getStringExtra("body");
        if (TextUtils.isEmpty(to) || TextUtils.isEmpty(body)) {
            LogUtil.w(TAG, "COMPOSE_SEND missing --es to / --es body");
            return;
        }
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            subId = ParticipantData.DEFAULT_SELF_SUB_ID;
        }
        LogUtil.i(TAG, "COMPOSE_SEND to=" + to + " -> InsertNewMessageAction (real gate path)");
        // The same entry the compose UI uses: creates/finds the conversation, runs the
        // E2EE gate, routes MLS/Etouffee/plaintext, and stamps the sent row.
        InsertNewMessageAction.insertNewMessage(subId, to, body, null /* subject */);
    }
}

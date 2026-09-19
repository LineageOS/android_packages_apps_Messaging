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

import com.android.messaging.datamodel.action.InsertNewMessageAction;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.util.LogUtil;

/**
 * Debug-only driver that sends a 1:1 message through {@link InsertNewMessageAction}, the entry
 * the compose UI uses, so the E2EE send gate and the encryption latch run as they do for a typed
 * message. Ignored unless the build is debuggable.
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.COMPOSE_SEND \
 *     -n com.android.messaging/.rcs.RcsDebugComposeSendReceiver --es to &lt;e164&gt; --es body hi
 * </pre>
 */
public final class RcsDebugComposeSendReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;
    static final String ACTION = "com.android.messaging.debug.COMPOSE_SEND";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        if (!RcsDebug.isDebugBuild()) {
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
        InsertNewMessageAction.insertNewMessage(subId, to, body, null /* subject */);
    }
}

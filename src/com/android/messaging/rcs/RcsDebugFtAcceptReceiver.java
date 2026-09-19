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

import com.android.messaging.util.LogUtil;

/**
 * Debug-only trigger for {@link ProviderTransport#acceptIncomingFile} by {@code rcs_message_id},
 * for files that have no tappable thumbnail bubble. Ignored unless the build is debuggable.
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.ACCEPT_FT \
 *     -n com.android.messaging/.rcs.RcsDebugFtAcceptReceiver \
 *     --es id "&lt;rcs_message_id&gt;" [--ei sub &lt;subId&gt;]
 * </pre>
 */
public final class RcsDebugFtAcceptReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;
    static final String ACTION_ACCEPT_FT = "com.android.messaging.debug.ACCEPT_FT";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION_ACCEPT_FT.equals(intent.getAction())) {
            return;
        }
        if (!RcsDebug.isDebugBuild()) {
            LogUtil.w(TAG, "DEBUG ACCEPT_FT ignored: build is not debuggable");
            return;
        }
        final String id = intent.getStringExtra("id");
        if (TextUtils.isEmpty(id)) {
            LogUtil.w(TAG, "DEBUG ACCEPT_FT missing 'id' extra; ignoring");
            return;
        }
        int subId = intent.getIntExtra("sub", SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        }
        LogUtil.i(TAG, "DEBUG ACCEPT_FT id=" + id + " subId=" + subId
                + " -> ProviderTransport.acceptIncomingFile");
        ProviderTransport.getInstance(context.getApplicationContext())
                .acceptIncomingFile(subId, id);
    }
}

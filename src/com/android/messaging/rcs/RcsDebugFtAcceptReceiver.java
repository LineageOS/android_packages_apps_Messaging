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

import com.android.messaging.util.LogUtil;

/**
 * Debug-only trigger for {@link ProviderTransport#acceptIncomingFile} — the
 * full-file download of a pending inbound RCS-media message. The normal accept
 * affordance is a tap on a thumbnail-only image bubble; a
 * non-image / large file has no tappable bubble, so this receiver drives the
 * download path (and thus {@code FtHttpDownloader}'s multi-pass / Range-resume
 * loop) by {@code rcs_message_id} for testing.
 *
 * <p>Exercise from adb:
 * <pre>
 *   adb shell am broadcast \
 *     -a com.android.messaging.debug.ACCEPT_FT \
 *     -n com.android.messaging/.rcs.RcsDebugFtAcceptReceiver \
 *     --es id "&lt;rcs_message_id&gt;" [--ei sub 3]
 * </pre>
 * Exported but inert on a user build (gated on {@code Build.TYPE}).
 */
public final class RcsDebugFtAcceptReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;
    static final String ACTION_ACCEPT_FT = "com.android.messaging.debug.ACCEPT_FT";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION_ACCEPT_FT.equals(intent.getAction())) {
            return;
        }
        if (!("eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE))) {
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

/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.messaging.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.messaging.BugleApplication;
import com.android.messaging.Factory;
import com.android.messaging.datamodel.action.UpdateMessageNotificationAction;
import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.util.BuglePrefsKeys;
import com.android.messaging.util.LogUtil;

/**
 * Receives notification of boot completion and package replacement
 */
public class BootAndPackageReplacedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Repost unseen notifications
            Factory.get().getApplicationPrefs().putLong(
                    BuglePrefsKeys.LATEST_NOTIFICATION_MESSAGE_TIMESTAMP, Long.MIN_VALUE);
            UpdateMessageNotificationAction.updateMessageNotification();

            BugleApplication.updateAppConfig(context);
            // Also bind the provider; idempotent.
            kickProvider(context, action);
            kickRcsRegistry(context, action);
        } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
            // Credential-encrypted storage is readable from here, so (re)bind the provider.
            kickProvider(context, action);
            kickRcsRegistry(context, action);
        } else {
            LogUtil.i(LogUtil.BUGLE_TAG, "BootAndPackageReplacedReceiver got unexpected action: "
                    + action);
        }
    }

    private static void kickProvider(final Context context, final String action) {
        try {
            ProviderTransport.getInstance(context).init();
            LogUtil.i(LogUtil.BUGLE_TAG,
                    "BootAndPackageReplacedReceiver: ProviderTransport.init() on " + action);
        } catch (final Throwable t) {
            LogUtil.w(LogUtil.BUGLE_TAG,
                    "BootAndPackageReplacedReceiver: ProviderTransport.init() failed", t);
        }
    }

    /**
     * Boot, package replacement and user unlock rerun provider discovery and selection. The
     * binder calls run on the registry worker, with goAsync() keeping the process alive.
     */
    private void kickRcsRegistry(final Context context, final String action) {
        try {
            final ProviderRegistry registry = ProviderRegistry.peek();
            if (registry == null) {
                return;
            }
            final PendingResult pending = goAsync();
            ProviderRegistry.postWork(() -> {
                try {
                    registry.discover();
                    registry.reselect("boot/" + action, /* freshCycle= */ true);
                } catch (final Throwable t) {
                    LogUtil.w(LogUtil.BUGLE_TAG,
                            "BootAndPackageReplacedReceiver: RCS re-selection failed", t);
                } finally {
                    pending.finish();
                }
            });
        } catch (final Throwable t) {
            LogUtil.w(LogUtil.BUGLE_TAG,
                    "BootAndPackageReplacedReceiver: RCS re-selection failed", t);
        }
    }
}


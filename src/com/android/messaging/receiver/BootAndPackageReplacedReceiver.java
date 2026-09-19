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
 * Receives notification of boot completion, user unlock, and package replacement.
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
            // G16: kick the provider bind on package replace too (boot bind is
            // also driven by the provider's own boot receiver). Cheap + idempotent.
            kickProvider(context, action);
            kickRcsRegistry(context, action);
        } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
            // G16: on USER_UNLOCKED the provider's CE token store is readable, so
            // this is the canonical point to (re)establish the provider bind. The
            // provider-side cold-provision check (no token -> enqueue) lives in
            // OpenRCSChat's TachyonBindBootReceiver, also keyed off USER_UNLOCKED;
            // here we just make sure the provider transport is initialized/bound.
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
     * Multi-transport framework (design §4.1, §5.4): boot / package-replace /
     * user-unlock are selection triggers. Re-enumerate external providers and re-run
     * the sticky selection for the active sub. Registry is process-wide and already
     * wired by BugleApplication.initializeSync (runs first on a cold boot); a null
     * peek() just means the app isn't fully up yet -- app-start selection covers that.
     *
     * <p>HIGH-2: discover() (queryIntentServices) + reselect (canServeSub /
     * getProviderCaps) are binder calls -- run them on the shared background worker,
     * not the main (broadcast) thread, and keep the process alive across the hop with
     * goAsync() / finish().
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


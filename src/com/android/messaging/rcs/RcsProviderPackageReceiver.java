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
import android.net.Uri;

import com.android.messaging.util.LogUtil;

/**
 * Provider install / uninstall / replace is a discovery + selection trigger
 * (design §4.1, §5.4). This receiver listens for package add/remove/replace on
 * the device (data scheme {@code package}) and, on any change, re-enumerates the
 * external RCS providers ({@link ProviderRegistry#discover}) and re-runs the
 * sticky per-sub selection ({@link RouteSelector#selectTransportForSub}).
 *
 * <p>The framework can't declare a manifest {@code <data android:path>} filter
 * scoped to "an RCS provider package" (the provider set is only known at runtime,
 * by resolving the bind action), so this fires on <b>every</b> package change and
 * {@code discover()} does the cheap re-resolve; a no-op when the changed package
 * isn't a provider (nothing added/removed in the registry -> no reselect). We
 * ignore the self-replace ({@code MY_PACKAGE_REPLACED}, handled by
 * {@link com.android.messaging.receiver.BootAndPackageReplacedReceiver}).
 */
public final class RcsProviderPackageReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        final String action = intent.getAction();
        if (action == null) {
            return;
        }
        // Ignore package-updating "removed" halves (the paired "added" fires next),
        // and our own package (covered elsewhere).
        if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            return;
        }
        final Uri data = intent.getData();
        final String changedPkg = (data != null) ? data.getSchemeSpecificPart() : null;
        if (changedPkg != null && changedPkg.equals(context.getPackageName())) {
            return;
        }
        try {
            final ProviderRegistry registry = ProviderRegistry.get(context);
            LogUtil.i(TAG, "RcsProviderPackageReceiver: " + action + " pkg=" + changedPkg
                    + " -> discover + reselect");
            // HIGH-2: discover() (queryIntentServices) + reselect (canServeSub /
            // getProviderCaps) are binder calls -- run them on the shared background
            // worker, not the main (broadcast) thread, and hold the process alive
            // across the hop with goAsync() / finish().
            // discover() re-enumerates and, when the provider set actually changed,
            // itself triggers a fresh-cycle reselect (design §5.4). Calling reselect
            // again is harmless (cooldown/sticky guard churn) and covers the case
            // where a provider's component became newly resolvable/unresolvable.
            final PendingResult pending = goAsync();
            ProviderRegistry.postWork(() -> {
                try {
                    registry.discover();
                    registry.reselect("package-changed", /* freshCycle= */ true);
                } catch (final Throwable t) {
                    LogUtil.w(TAG, "RcsProviderPackageReceiver work failed", t);
                } finally {
                    pending.finish();
                }
            });
        } catch (final Throwable t) {
            LogUtil.w(TAG, "RcsProviderPackageReceiver failed", t);
        }
    }
}

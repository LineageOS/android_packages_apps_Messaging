/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.android.messaging.util.LogUtil;

/**
 * Re-runs provider discovery and selection on any package add, remove or replace. A manifest
 * filter cannot be scoped to provider packages (the set is known only by resolving the bind
 * action), so it fires on every change and {@link ProviderRegistry#discover} does the cheap
 * re-resolve. Our own replace is handled by
 * {@link com.android.messaging.receiver.BootAndPackageReplacedReceiver}.
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
        // Ignore the "removed" half of an update (the "added" follows) and our own package.
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
            // Both are binder calls, so they run on the registry worker, with goAsync keeping the
            // process alive. discover() already reselects when the provider set changed; the second
            // reselect covers a component that became resolvable or unresolvable.
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

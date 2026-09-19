/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024-2026 The LineageOS Project
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

package com.android.messaging;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.mms.CarrierConfigValuesLoader;
import android.support.v7.mms.MmsManager;
import android.telephony.CarrierConfigManager;

import androidx.annotation.NonNull;

import com.android.messaging.datamodel.DataModel;
import com.android.messaging.receiver.SmsReceiver;
import com.android.messaging.sms.BugleApnSettingsLoader;
import com.android.messaging.sms.BugleUserAgentInfoLoader;
import com.android.messaging.sms.MmsConfig;
import com.android.messaging.ui.ConversationDrawables;
import com.android.messaging.util.BuglePrefsKeys;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.Trace;

import java.lang.Thread.UncaughtExceptionHandler;

/**
 * The application object
 */
public class BugleApplication extends Application implements UncaughtExceptionHandler {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private UncaughtExceptionHandler sSystemUncaughtExceptionHandler;

    @Override
    public void onCreate() {
        Trace.beginSection("app.onCreate");
        super.onCreate();

        FactoryImpl.register(getApplicationContext(), this);

        sSystemUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
        Trace.endSection();
    }

    @Override
    public void onConfigurationChanged(@NonNull final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Update conversation drawables when changing writing systems
        // (Right-To-Left / Left-To-Right)
        ConversationDrawables.get().updateDrawables();
    }

    /**
     * True in the {@code :ims} process that hosts
     * {@link com.android.messaging.rcs.carrier.CarrierImsService}.
     */
    private static boolean isImsProcess() {
        try {
            final String proc = Application.getProcessName();
            return proc != null && proc.endsWith(":ims");
        } catch (final Throwable t) {
            // Treat as the main process.
            return false;
        }
    }

    // Called by the "real" factory from FactoryImpl.register() (i.e. not run in tests)
    public void initializeSync(final Factory factory) {
        Trace.beginSection("app.initializeSync");
        // The :ims process hosts only CarrierImsService and must not run the main-process
        // initialization.
        if (isImsProcess()) {
            LogUtil.i(TAG, "initializeSync: :ims process -- skipping main-app init");
            Trace.endSection();
            return;
        }
        final Context context = factory.getApplicationContext();
        final DataModel dataModel = factory.getDataModel();
        final CarrierConfigValuesLoader carrierConfigValuesLoader =
                factory.getCarrierConfigValuesLoader();

        BugleApplication.updateAppConfig(context);

        // Initialize MMS lib
        initMmsLib(context, carrierConfigValuesLoader);
        // Fixup messages in flight if we crashed and send any pending
        dataModel.onApplicationCreated();
        // Binds the RCS provider off-thread; a no-op when none is installed.
        com.android.messaging.rcs.ProviderTransport.getInstance(context).init();
        // Wire the transport registry (see docs/rcs/architecture.md). The shared RcsCallbackRouter
        // is the inbound sink for every transport, including the in-process carrier transport.
        try {
            final com.android.messaging.rcs.RcsCallbackRouter router =
                    com.android.messaging.rcs.RcsCallbackRouter.getInstance(context);
            final com.android.messaging.rcs.ProviderRegistry registry =
                    com.android.messaging.rcs.ProviderRegistry.get(context);
            registry.setCallbackSink(router);
            // The existing provider binding joins the registry as one transport; its package is
            // passed so discovery does not bind it twice.
            registry.registerLegacyTransport(
                    com.android.messaging.rcs.ProviderTransport.getInstance(context),
                    com.android.messaging.rcs.ProviderRegistry.resolveProviderPackage(context));
            com.android.messaging.rcs.carrier.CarrierImsTransport.register(
                    context, registry, router);
            // Discovery and selection make binder calls, so they run on the registry worker.
            com.android.messaging.rcs.ProviderRegistry.postWork(() -> {
                registry.discover();
                router.getRouteSelector().selectForActiveSub("app-start");
            });
        } catch (final Throwable t) {
            LogUtil.w(TAG, "RCS registry / carrier-IMS wiring failed", t);
        }
        initEmojiCompat(context);
        // Register carrier config change receiver
        registerCarrierConfigChangeReceiver(context);

        Trace.endSection();
    }

    /**
     * Initializes EmojiCompat, used to validate a custom reaction glyph. Without a downloadable
     * emoji font provider it stays uninitialized and ConversationFragment.validateReactionEmoji
     * falls back to a single-grapheme check.
     */
    private static void initEmojiCompat(final Context context) {
        try {
            final androidx.emoji2.text.EmojiCompat.Config config =
                    androidx.emoji2.text.DefaultEmojiCompatConfig.create(context);
            if (config != null) {
                // Loaded lazily; the validity check tests getLoadState() first.
                config.setMetadataLoadStrategy(
                        androidx.emoji2.text.EmojiCompat.LOAD_STRATEGY_MANUAL);
                final androidx.emoji2.text.EmojiCompat ec =
                        androidx.emoji2.text.EmojiCompat.init(config);
                ec.load();
            } else {
                LogUtil.i(TAG, "EmojiCompat: no default config; custom-reaction "
                        + "validity uses heuristic fallback");
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, "EmojiCompat init failed; custom-reaction heuristic fallback", t);
        }
    }

    private static void registerCarrierConfigChangeReceiver(final Context context) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                LogUtil.i(TAG, "Carrier config changed. Reloading MMS config.");
                MmsConfig.loadAsync();
                // A new carrier config can change which transport serves the subscription, so
                // reselect, giving previously failed transports another chance.
                try {
                    final com.android.messaging.rcs.ProviderRegistry registry =
                            com.android.messaging.rcs.ProviderRegistry.peek();
                    if (registry != null) {
                        // reselect makes binder calls; keep them off the receiver thread.
                        com.android.messaging.rcs.ProviderRegistry.postWork(
                                () -> registry.reselect("carrier-config-changed",
                                        /* freshCycle= */ true));
                    }
                } catch (final Throwable t) {
                    LogUtil.w(TAG, "carrier-config re-selection failed", t);
                }
            }
        }, new IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED),
        Context.RECEIVER_EXPORTED/*UNAUDITED*/);
    }

    private static void initMmsLib(final Context context,
            final CarrierConfigValuesLoader carrierConfigValuesLoader) {
        MmsManager.setApnSettingsLoader(new BugleApnSettingsLoader(context));
        MmsManager.setCarrierConfigValuesLoader(carrierConfigValuesLoader);
        MmsManager.setUserAgentInfoLoader(new BugleUserAgentInfoLoader(context));
    }

    public static void updateAppConfig(final Context context) {
        // Make sure we set the correct state for the SMS/MMS receivers
        SmsReceiver.updateSmsReceiveHandler(context);
    }

    // Called from thread started in FactoryImpl.register() (i.e. not run in tests)
    public void initializeAsync(final Factory factory) {
        // Handle shared prefs upgrade & Load MMS Configuration
        Trace.beginSection("app.initializeAsync");
        // Not in the :ims process: racing the shared-prefs upgrade from there corrupts the
        // versioned migration.
        if (isImsProcess()) {
            LogUtil.i(TAG, "initializeAsync: :ims process -- skipping main-app init");
            Trace.endSection();
            return;
        }
        maybeHandleSharedPrefsUpgrade(factory);
        MmsConfig.load();
        Trace.endSection();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();

        if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
            LogUtil.d(TAG, "BugleApplication.onLowMemory");
        }
        Factory.get().reclaimMemory();
    }

    @Override
    public void uncaughtException(@NonNull final Thread thread, @NonNull final Throwable ex) {
        final boolean background = getMainLooper().getThread() != thread;
        if (background) {
            LogUtil.e(TAG, "Uncaught exception in background thread " + thread, ex);

            final Handler handler = new Handler(getMainLooper());
            handler.post(() -> sSystemUncaughtExceptionHandler.uncaughtException(thread, ex));
        } else {
            sSystemUncaughtExceptionHandler.uncaughtException(thread, ex);
        }
    }

    private void maybeHandleSharedPrefsUpgrade(final Factory factory) {
        final int existingVersion = factory.getApplicationPrefs().getInt(
                BuglePrefsKeys.SHARED_PREFERENCES_VERSION,
                BuglePrefsKeys.SHARED_PREFERENCES_VERSION_DEFAULT);
        final int targetVersion = Integer.parseInt(getString(R.string.pref_version));
        if (targetVersion > existingVersion) {
            LogUtil.i(LogUtil.BUGLE_TAG, "Upgrading shared prefs from " + existingVersion +
                    " to " + targetVersion);
            try {
                // Perform upgrade on application-wide prefs.
                factory.getApplicationPrefs().onUpgrade(existingVersion, targetVersion);
                // Perform upgrade on each subscription's prefs.
                PhoneUtils.forEachActiveSubscription(subId -> factory.getSubscriptionPrefs(subId)
                        .onUpgrade(existingVersion, targetVersion));
                factory.getApplicationPrefs().putInt(BuglePrefsKeys.SHARED_PREFERENCES_VERSION,
                        targetVersion);
            } catch (final Exception ex) {
                // Upgrade failed. Don't crash the app because we can always fall back to the
                // default settings.
                LogUtil.e(LogUtil.BUGLE_TAG, "Failed to upgrade shared prefs", ex);
            }
        } else if (targetVersion < existingVersion) {
            // We don't care about downgrade since real user shouldn't encounter this, so log it
            // and ignore any prefs migration.
            LogUtil.e(LogUtil.BUGLE_TAG, "Shared prefs downgrade requested and ignored. " +
                    "oldVersion = " + existingVersion + ", newVersion = " + targetVersion);
        }
    }
}

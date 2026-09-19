/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024-2025 The LineageOS Project
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
     * True when running in the isolated {@code :ims} process that hosts
     * {@link com.android.messaging.rcs.carrier.CarrierImsService} (design §6.4).
     * The process name is {@code <pkg>:ims}; the main/UI process is just
     * {@code <pkg>}.
     */
    private static boolean isImsProcess() {
        try {
            final String proc = Application.getProcessName();
            return proc != null && proc.endsWith(":ims");
        } catch (final Throwable t) {
            // getProcessName() is API 28+; the platform target is Android 14, so
            // this should not throw. Fail safe to "not :ims" (run full init).
            return false;
        }
    }

    // Called by the "real" factory from FactoryImpl.register() (i.e. not run in tests)
    public void initializeSync(final Factory factory) {
        Trace.beginSection("app.initializeSync");
        // Multi-transport framework (design §6.4): the isolated :ims process only
        // hosts CarrierImsService (the SIP/MSRP engine). It must NOT run the full
        // main-app initialization (MMS lib, DataModel, RCS provider bind, emoji,
        // carrier-config receiver) -- those belong to the UI/main process. Bail
        // early; CarrierImsService needs only the application context + system
        // services, which are available without this init.
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
        // 2-app split: bind the RCS provider add-on (off-thread). No-op if
        // the provider APK isn't installed (RCS routes to SMS).
        com.android.messaging.rcs.ProviderTransport.getInstance(context).init();
        // Multi-transport framework (design §6): register the in-process
        // carrier-IMS (SIP/MSRP) transport into the ProviderRegistry, feeding the
        // shared process-wide RcsCallbackRouter as its inbound sink so carrier-IMS
        // inbound / registration / provisioning events reach the DataModel
        // in-process (design §6.5). initializeSync already returned above for the
        // :ims process, so this only runs in the main/UI process. Injecting the
        // same router as the registry's callback sink means every discovered
        // BoundProviderTransport also attaches with the real sink (not null).
        try {
            final com.android.messaging.rcs.RcsCallbackRouter router =
                    com.android.messaging.rcs.RcsCallbackRouter.getInstance(context);
            final com.android.messaging.rcs.ProviderRegistry registry =
                    com.android.messaging.rcs.ProviderRegistry.get(context);
            registry.setCallbackSink(router);
            // Register the legacy provider binding as ONE registry transport (design
            // §4.3, build-plan §2): the ProviderTransport singleton keeps binding
            // the provider, but the registry now ranks it uniformly with the carrier +
            // external transports. Tell discovery which package it already owns so
            // the provider is never double-bound.
            registry.registerLegacyTransport(
                    com.android.messaging.rcs.ProviderTransport.getInstance(context),
                    com.android.messaging.rcs.ProviderRegistry.resolveProviderPackage(context));
            // Register the in-process carrier-IMS (SIP/MSRP) transport (design §6).
            com.android.messaging.rcs.carrier.CarrierImsTransport.register(
                    context, registry, router);
            // Discover any external providers (queryIntentServices on the bind
            // action, design §4.1) and run the boot/app-start selection trigger
            // (design §5.4). HIGH-2: queryIntentServices + canServeSub/getProviderCaps
            // are binder calls, so run them on the shared background worker -- NOT the
            // main looper -- so they never block the main thread; selection re-runs as
            // each transport handshakes.
            com.android.messaging.rcs.ProviderRegistry.postWork(() -> {
                registry.discover();
                router.getRouteSelector().selectForActiveSub("app-start");
            });
        } catch (final Throwable t) {
            LogUtil.w(TAG, "RCS registry / carrier-IMS wiring failed", t);
        }
        // Emoji-reactions: init the AndroidX EmojiCompat trie so the custom-
        // reaction ("+") path can validate an arbitrary picked glyph (a validity
        // check, not an allowlist). Uses the device's default downloadable-font
        // provider; if none is present the singleton stays in a non-SUCCEEDED
        // state and the picker falls back to a single-grapheme heuristic. Wrapped
        // defensively so a missing provider never blocks app startup.
        initEmojiCompat(context);
        // Register carrier config change receiver
        registerCarrierConfigChangeReceiver(context);

        Trace.endSection();
    }

    /**
     * Emoji-reactions: initialize EmojiCompat once, off the critical path. Best
     * effort — {@link androidx.emoji2.text.DefaultEmojiCompatConfig#create} returns
     * null when the device has no downloadable-font emoji provider, in which case
     * we simply don't init and the custom-reaction validity gate degrades to a
     * single-grapheme heuristic (see ConversationFragment.validateReactionEmoji).
     */
    private static void initEmojiCompat(final Context context) {
        try {
            final androidx.emoji2.text.EmojiCompat.Config config =
                    androidx.emoji2.text.DefaultEmojiCompatConfig.create(context);
            if (config != null) {
                // Load lazily so the metadata fetch never blocks startup; the
                // validity gate checks getLoadState() before using the trie.
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
                // Multi-transport framework (design §5.4): ACTION_CARRIER_CONFIG_CHANGED
                // is a re-selection trigger -- carrier RCS provisioning / SR availability
                // can flip with a new carrier config, so re-evaluate which transport
                // serves the active sub. Fresh cycle: give previously-failed transports
                // another chance under the new config.
                try {
                    final com.android.messaging.rcs.ProviderRegistry registry =
                            com.android.messaging.rcs.ProviderRegistry.peek();
                    if (registry != null) {
                        // HIGH-2: reselect ranks via canServeSub/getProviderCaps binder
                        // calls -- hop off the main (receiver) thread.
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
        // Multi-transport framework (design §6.4): the isolated :ims process must
        // NOT run the cross-process shared-prefs upgrade or MmsConfig.load -- those
        // are main/UI-process concerns and racing the prefs upgrade from :ims
        // corrupts the versioned migration. Bail early, mirroring initializeSync.
        // (C1) Factory + LogUtil are already safe in :ims: Factory.register() runs
        // in onCreate before either initialize* path, so the factory/prefs handles
        // exist; we simply decline to touch them here.
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

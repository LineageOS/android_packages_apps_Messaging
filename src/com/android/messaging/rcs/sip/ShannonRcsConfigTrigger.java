/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sip;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.RcsClientConfiguration;

import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.util.LogUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Has the modem fetch its RCS configuration itself: declares this client
 * ({@code setRcsClientConfiguration}), registers a provisioning callback, then calls
 * {@code triggerRcsReconfiguration}. These require {@code PERFORM_IMS_SINGLE_REGISTRATION},
 * which only the SMS role holder has, so this runs in the app. See docs/rcs/carrier-transport.md.
 */
public final class ShannonRcsConfigTrigger {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "ShannonRcsConfigTrigger";

    // GSMA Universal Profile client configuration the modem matches its autoconfiguration
    // profile against.
    private static final String RCS_VERSION = "6.0";
    private static final String RCS_PROFILE = RcsClientConfiguration.RCS_PROFILE_2_4;
    private static final String CLIENT_VENDOR = "Goog";
    private static final String CLIENT_VERSION = "RCSAndrd-1.0";    // single_reg_client_version

    private static volatile ShannonRcsConfigTrigger sInstance;

    private final Context mAppContext;
    private final ExecutorService mExecutor;
    // Held for the registration's lifetime so the binder callback is not collected.
    private final ProvCallback mCallback = new ProvCallback();
    private volatile ProvisioningManager mProvisioningManager;
    private volatile int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private volatile String mLastStatus = "idle";

    /** The last configuration document from the modem, or null. */
    @androidx.annotation.Nullable
    private volatile byte[] mLastConfigXml;

    /** Receives each configuration document the modem delivers. */
    public interface ConfigListener {
        void onConfig(byte[] configXml);
    }

    @androidx.annotation.Nullable
    private volatile ConfigListener mConfigListener;

    /** Replays the last document at once if one has arrived. Null clears. */
    public void setConfigListener(@androidx.annotation.Nullable ConfigListener l) {
        mConfigListener = l;
        final byte[] last = mLastConfigXml;
        if (l != null && last != null) {
            try {
                l.onConfig(last);
            } catch (Throwable t) {
                LogUtil.w(TAG, SUBTAG + ": ConfigListener replay threw", t);
            }
        }
    }

    private ShannonRcsConfigTrigger(Context context) {
        mAppContext = context.getApplicationContext();
        mExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ShannonRcsConfigTrigger");
            t.setDaemon(true);
            return t;
        });
    }

    public static ShannonRcsConfigTrigger getInstance(Context context) {
        ShannonRcsConfigTrigger local = sInstance;
        if (local == null) {
            synchronized (ShannonRcsConfigTrigger.class) {
                local = sInstance;
                if (local == null) {
                    local = new ShannonRcsConfigTrigger(context);
                    sInstance = local;
                }
            }
        }
        return local;
    }

    private int resolveSubId() {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultSubscriptionId();
        }
        return subId;
    }

    /**
     * Declare, register the callback and trigger. Safe to repeat: each run re-registers and
     * re-triggers.
     */
    public synchronized void trigger() {
        final int subId = resolveSubId();
        mSubId = subId;
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            mLastStatus = "no-valid-sub";
            LogUtil.w(TAG, SUBTAG + ": no valid sub; cannot trigger");
            return;
        }

        final ImsManager imsManager = mAppContext.getSystemService(ImsManager.class);
        if (imsManager == null) {
            mLastStatus = "no-ImsManager";
            LogUtil.e(TAG, SUBTAG + ": ImsManager unavailable");
            return;
        }
        final ProvisioningManager pm = imsManager.getProvisioningManager(subId);
        mProvisioningManager = pm;

        try {
            boolean singleRegCapable = pm.isRcsVolteSingleRegistrationCapable();
            LogUtil.i(TAG, SUBTAG + ": isRcsVolteSingleRegistrationCapable=" + singleRegCapable);
        } catch (Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": isRcsVolteSingleRegistrationCapable threw (continuing): "
                    + t);
        }

        final RcsClientConfiguration rcc = new RcsClientConfiguration(
                RCS_VERSION, RCS_PROFILE, CLIENT_VENDOR, CLIENT_VERSION);
        LogUtil.i(TAG, SUBTAG + ": RcsClientConfiguration rcs_version=" + RCS_VERSION
                + " rcs_profile=" + RCS_PROFILE + " client_vendor=" + CLIENT_VENDOR
                + " single_reg_client_version=" + CLIENT_VERSION);

        try {
            pm.setRcsClientConfiguration(rcc);
            LogUtil.i(TAG, SUBTAG + ": setRcsClientConfiguration OK");
        } catch (ImsException e) {
            mLastStatus = "setRcsClientConfiguration-threw:" + e.getCode();
            LogUtil.e(TAG, SUBTAG + ": setRcsClientConfiguration threw code=" + e.getCode(), e);
            return;
        } catch (SecurityException e) {
            mLastStatus = "setRcsClientConfiguration-SecurityException";
            LogUtil.e(TAG, SUBTAG + ": setRcsClientConfiguration SecurityException — missing"
                    + " PERFORM_IMS_SINGLE_REGISTRATION (must be ROLE_SMS holder)", e);
            return;
        }

        // The document returns through onConfigurationChanged.
        try {
            pm.registerRcsProvisioningCallback(mExecutor, mCallback);
            LogUtil.i(TAG, SUBTAG + ": registerRcsProvisioningCallback OK");
        } catch (ImsException e) {
            // Registering a callback that is already registered may throw.
            LogUtil.w(TAG, SUBTAG + ": registerRcsProvisioningCallback threw code="
                    + e.getCode() + " (may already be registered)", e);
        } catch (Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": registerRcsProvisioningCallback failed: " + t);
        }

        try {
            pm.triggerRcsReconfiguration();
            mLastStatus = "triggered";
            LogUtil.i(TAG, SUBTAG + ": triggerRcsReconfiguration OK — awaiting"
                    + " onConfigurationChanged; expect Shannon to SIP-REGISTER RCS");
        } catch (SecurityException e) {
            mLastStatus = "triggerRcsReconfiguration-SecurityException";
            LogUtil.e(TAG, SUBTAG + ": triggerRcsReconfiguration SecurityException", e);
        } catch (Throwable t) {
            mLastStatus = "triggerRcsReconfiguration-threw";
            LogUtil.e(TAG, SUBTAG + ": triggerRcsReconfiguration failed: " + t, t);
        }
    }

    public String getStatus() {
        return "subId=" + mSubId + " status=" + mLastStatus;
    }

    private final class ProvCallback extends ProvisioningManager.RcsProvisioningCallback {
        @Override
        public void onConfigurationChanged(byte[] configXml) {
            final int len = configXml == null ? 0 : configXml.length;
            mLastStatus = "config-received:" + len + "B";
            LogUtil.i(TAG, SUBTAG + ": onConfigurationChanged bytes=" + len
                    + " — Shannon fetched its RCS ACS config; it should now"
                    + " SIP-REGISTER RCS with the CPM profile on its IMS bearer");
            // On the SR path the modem owns registration; the document is not parsed here. It
            // can carry credentials, so its text is logged only on a debug build.
            if (len > 0 && RcsDebug.isDebugBuild()) {
                int show = Math.min(len, 200);
                LogUtil.i(TAG, SUBTAG + ":   config[0.." + show + "]="
                        + new String(configXml, 0, show, java.nio.charset.StandardCharsets.UTF_8)
                                .replaceAll("[\\r\\n]+", " "));
            }
            mLastConfigXml = configXml;
            final ConfigListener l = mConfigListener;
            if (l != null && configXml != null) {
                try {
                    l.onConfig(configXml);
                } catch (Throwable t) {
                    LogUtil.w(TAG, SUBTAG + ": ConfigListener.onConfig threw", t);
                }
            }
        }

        @Override
        public void onAutoConfigurationErrorReceived(int errorCode, String errorString) {
            mLastStatus = "config-error:" + errorCode;
            LogUtil.w(TAG, SUBTAG + ": onAutoConfigurationErrorReceived code=" + errorCode
                    + " msg=" + errorString);
        }

        @Override
        public void onConfigurationReset() {
            LogUtil.i(TAG, SUBTAG + ": onConfigurationReset");
        }

        @Override
        public void onRemoved() {
            LogUtil.i(TAG, SUBTAG + ": onRemoved");
        }
    }
}

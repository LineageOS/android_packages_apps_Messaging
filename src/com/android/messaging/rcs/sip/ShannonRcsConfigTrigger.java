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
package com.android.messaging.rcs.sip;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.RcsClientConfiguration;

import com.android.messaging.util.LogUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google-Messages-faithful vendor-IMS <b>PULL</b> trigger (Path A) that makes Shannon
 * (the Pixel vendor RcsFeature/ImsService) self-fetch its RCS ACS config and
 * SIP-REGISTER RCS — with the FULL CPM/RCS profile — on its own trusted IMS
 * bearer.
 *
 * <p>Mirrors what Google Messages does:
 * <ol>
 *   <li>{@code ProvisioningManager.setRcsClientConfiguration(new
 *       RcsClientConfiguration(rcs_version, rcs_profile, client_vendor,
 *       single_reg_client_version))} — declares OUR client to the modem,</li>
 *   <li>{@code registerRcsProvisioningCallback(executor, callback)} — the config
 *       comes back via {@code RcsProvisioningCallback.onConfigurationChanged([B])},
 *       NOT via a push,</li>
 *   <li>{@code triggerRcsReconfiguration()} — asks the modem
 *       to (re)fetch its RCS config itself.</li>
 * </ol>
 *
 * <p><b>Why PULL, not push:</b> Google Messages NEVER calls
 * {@code notifyRcsAutoConfigurationReceived}. It declares
 * its client config and lets the modem autoconfig (TS.43 / vendor ACS over the
 * IMS PDN). Our earlier <i>push</i> of a partial config (provider-side
 * {@code notifyRcsAutoConfigurationReceived}) likely left Shannon with minimal
 * caps (no {@code oma.cpm.msg}); the PULL path is what should hand Shannon the
 * full CPM messaging profile.
 *
 * <p><b>Why this lives in messaging2 (not the provider):</b> all three of
 * {@code setRcsClientConfiguration} / {@code triggerRcsReconfiguration} enforce
 * {@code PERFORM_IMS_SINGLE_REGISTRATION} (verified in
 * {@code PhoneInterfaceManager} lines 10946/10971 +
 * {@code ProvisioningManager} {@code @RequiresPermission}). That permission is
 * {@code internal|role} — granted ONLY to the system {@code ROLE_SMS} holder,
 * which is messaging2, NOT the provider. The provider holds only
 * {@code MODIFY_PHONE_STATE}, which suffices for
 * {@code notifyRcsAutoConfigurationReceived} (the alternate FED path) but NOT
 * for the PULL trigger. Hence the PULL trigger is forced into messaging2.
 */
public final class ShannonRcsConfigTrigger {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "ShannonRcsConfigTrigger";

    // RcsClientConfiguration values, mirroring what Google Messages declares.
    // These are the GSMA Universal Profile client-config strings the modem
    // matches its autoconfig profile against. Defaults below are the standard
    // UP 2.4 set; if Shannon rejects them (onAutoConfigurationErrorReceived),
    // these are the first knobs to tune against a captured value.
    private static final String RCS_VERSION = "6.0";
    private static final String RCS_PROFILE = RcsClientConfiguration.RCS_PROFILE_2_4; // "UP_2.4"
    private static final String CLIENT_VENDOR = "Goog";
    private static final String CLIENT_VERSION = "RCSAndrd-1.0";    // single_reg_client_version

    private static volatile ShannonRcsConfigTrigger sInstance;

    private final Context mAppContext;
    private final ExecutorService mExecutor;
    // Held for the registration's lifetime so the binder callback isn't GC'd.
    private final ProvCallback mCallback = new ProvCallback();
    private volatile ProvisioningManager mProvisioningManager;
    private volatile int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private volatile String mLastStatus = "idle";

    /** Last RCS autoconfig XML the modem handed us (retained for the ToS/<MSG>
     *  gate parse — spec §7). Null until the first onConfigurationChanged. */
    @androidx.annotation.Nullable
    private volatile byte[] mLastConfigXml;

    /** Observer of the pulled RCS config (so {@code CarrierImsService} can parse
     *  the autoconfig {@code <MSG>} T&C gate and emit EVT_TOS_STATE). */
    public interface ConfigListener {
        void onConfig(byte[] configXml);
    }

    @androidx.annotation.Nullable
    private volatile ConfigListener mConfigListener;

    /** Register the config observer. Immediately replays the last config if one
     *  has already arrived (attach-after-config ordering). Pass null to clear. */
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
     * Run the full PULL sequence: setRcsClientConfiguration +
     * registerRcsProvisioningCallback + triggerRcsReconfiguration. Idempotent
     * per process: re-running re-registers the callback and re-triggers.
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

        // (Optional pre-check, as Google Messages does before triggering.)
        try {
            boolean singleRegCapable = pm.isRcsVolteSingleRegistrationCapable();
            LogUtil.i(TAG, SUBTAG + ": isRcsVolteSingleRegistrationCapable=" + singleRegCapable);
        } catch (Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": isRcsVolteSingleRegistrationCapable threw (continuing): " + t);
        }

        final RcsClientConfiguration rcc = new RcsClientConfiguration(
                RCS_VERSION, RCS_PROFILE, CLIENT_VENDOR, CLIENT_VERSION);
        LogUtil.i(TAG, SUBTAG + ": RcsClientConfiguration rcs_version=" + RCS_VERSION
                + " rcs_profile=" + RCS_PROFILE + " client_vendor=" + CLIENT_VENDOR
                + " single_reg_client_version=" + CLIENT_VERSION);

        // 1) setRcsClientConfiguration.
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

        // 2) registerRcsProvisioningCallback — config returns via onConfigurationChanged.
        try {
            pm.registerRcsProvisioningCallback(mExecutor, mCallback);
            LogUtil.i(TAG, SUBTAG + ": registerRcsProvisioningCallback OK");
        } catch (ImsException e) {
            // Re-register on an already-registered callback can throw; log + continue.
            LogUtil.w(TAG, SUBTAG + ": registerRcsProvisioningCallback threw code="
                    + e.getCode() + " (may already be registered)", e);
        } catch (Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": registerRcsProvisioningCallback failed: " + t);
        }

        // 3) triggerRcsReconfiguration — modem self-fetches its RCS config.
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

    /** Receives the modem-provisioned config + errors. */
    private final class ProvCallback extends ProvisioningManager.RcsProvisioningCallback {
        @Override
        public void onConfigurationChanged(byte[] configXml) {
            final int len = configXml == null ? 0 : configXml.length;
            mLastStatus = "config-received:" + len + "B";
            LogUtil.i(TAG, SUBTAG + ": onConfigurationChanged bytes=" + len
                    + " — Shannon fetched its RCS ACS config; it should now"
                    + " SIP-REGISTER RCS with the CPM profile on its IMS bearer");
            // We do NOT parse it here (Google Messages parses it into its own
            // Configuration POJO); for the ride-Shannon path the modem owns the
            // registration. Dump a short prefix for sanity.
            if (len > 0) {
                int show = Math.min(len, 200);
                LogUtil.i(TAG, SUBTAG + ":   config[0.." + show + "]="
                        + new String(configXml, 0, show, java.nio.charset.StandardCharsets.UTF_8)
                                .replaceAll("[\\r\\n]+", " "));
            }
            // Retain + fan out to the ToS/<MSG> observer (spec §7). We still do
            // not drive registration off this (the modem owns that on the SR
            // path); we only surface the carrier T&C gate if present.
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

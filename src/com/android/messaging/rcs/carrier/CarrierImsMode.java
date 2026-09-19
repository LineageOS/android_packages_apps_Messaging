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
package com.android.messaging.rcs.carrier;

import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.SipDelegateManager;

import com.android.messaging.util.LogUtil;

/**
 * The two internal carrier-IMS registration modes and the runtime, per-sub probe
 * that picks between them (design §6.3, D6).
 *
 * <ul>
 *   <li>{@link #SR} -- Single Registration. Ride the platform/modem IMS
 *       registration: request a {@code SipDelegate} for the CPM feature tags and
 *       ride that registration for RCS signalling. Requires <b>both</b> carrier
 *       support (provisioned RCS config) <b>and</b> device/modem capability
 *       (vendor {@code ImsService} exposes the {@code SipDelegate} API). This is
 *       the primary path (design §6.2) and the one the in-tree
 *       {@code rcs/sip/**} code implements.</li>
 *   <li>{@link #DR} -- Dual Registration. The RCS client does its <b>own</b> SIP
 *       {@code REGISTER} over the IMS APN, independent of the modem. The fallback
 *       for older / non-SR carriers (design §6.3); implemented by the OpenRCSChat
 *       {@code transport/carrier/**} stack, which is NOT compiled into messaging2
 *       this pass (see {@link CarrierDrModeDriver}).</li>
 *   <li>{@link #UNKNOWN} -- the probe could not run (no valid sub, no ImsManager,
 *       {@code isSupported} threw). Treated as "not eligible yet"; {@code
 *       startForSub} may retry.</li>
 * </ul>
 */
public enum CarrierImsMode {
    SR,
    DR,
    UNKNOWN;

    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CarrierImsMode";

    /**
     * The combined "does the platform hand me a delegate" probe (design §6.3):
     * {@code SipDelegateManager.isSupported()} answers the carrier-declares AND
     * modem-supports factors at once. True -&gt; {@link #SR}; false -&gt; {@link #DR}.
     *
     * <p>This is non-side-effecting (it does not create a delegate). It is called
     * off the main thread from {@link CarrierImsService} when a {@code startForSub}
     * arrives; it is NOT wired to {@code canServeSub}, which returns {@code MAYBE}
     * unconditionally in v1 (design D5) precisely because this probe depends on
     * live modem IMS state and isn't guaranteed instant.
     *
     * RIG-VERIFY(rcs-framework): §12 open item -- confirm the exact CarrierConfig
     * key that gates SR (candidate: {@code
     * CarrierConfigManager.Ims.KEY_IMS_SINGLE_REGISTRATION_REQUIRED_BOOL} /
     * {@code "ims.ims_single_registration_required_bool"}, referenced in
     * SipDelegateClient) and whether {@code isSupported()} alone is authoritative
     * or must be AND-ed with a provisioned-RCS-config check. Cannot be settled off
     * a rig: {@code isSupported()} depends on live modem IMS state + the carrier's
     * provisioned RCS config, neither reproducible without a carrier IMS network.
     * We treat {@code isSupported()==true -> SR}, {@code false -> DR}, and an
     * ImsException as retryable-UNKNOWN; verify the DR-fallback branch against the
     * private IMS test network before trusting it.
     */
    public static CarrierImsMode probe(final Context context, final int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            LogUtil.w(TAG, SUBTAG + ": invalid subId=" + subId + " -> UNKNOWN");
            return UNKNOWN;
        }
        // Debug lab override: force the DR (standalone JAIN-SIP) path even when the
        // modem exposes the SipDelegate SR API, so the app-owned carrier stack can be
        // exercised against a lab IMS (e.g. open5gs). Debuggable builds only.
        if (("eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE))
                && SystemProperties.getBoolean("debug.rcs.dr.force", false)) {
            LogUtil.i(TAG, SUBTAG + ": debug.rcs.dr.force=true -> DR (override probe)");
            return DR;
        }
        final ImsManager imsManager = context.getSystemService(ImsManager.class);
        if (imsManager == null) {
            LogUtil.e(TAG, SUBTAG + ": ImsManager unavailable -> UNKNOWN");
            return UNKNOWN;
        }
        try {
            final SipDelegateManager manager = imsManager.getSipDelegateManager(subId);
            final boolean supported = manager.isSupported();
            LogUtil.i(TAG, SUBTAG + ": subId=" + subId + " isSupported=" + supported
                    + " -> " + (supported ? SR : DR));
            return supported ? SR : DR;
        } catch (final ImsException e) {
            // ImsService not up for this sub yet. Not proof of DR -- retryable.
            LogUtil.w(TAG, SUBTAG + ": isSupported threw code=" + e.getCode()
                    + " -> UNKNOWN (retryable)", e);
            return UNKNOWN;
        } catch (final SecurityException e) {
            // Missing PERFORM_IMS_SINGLE_REGISTRATION. In the :ims process of the
            // SMS-role app this should not happen (permission is per-UID); if it
            // does, SR is not reachable -> fall to DR.
            LogUtil.e(TAG, SUBTAG + ": isSupported SecurityException -> DR fallback", e);
            return DR;
        } catch (final Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": isSupported failed -> UNKNOWN", t);
            return UNKNOWN;
        }
    }
}

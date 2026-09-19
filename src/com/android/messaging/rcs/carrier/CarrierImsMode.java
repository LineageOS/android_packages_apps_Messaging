/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import android.content.Context;
import android.os.SystemProperties;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.SipDelegateManager;

import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.util.LogUtil;

/**
 * Registration mode for a subscription: {@link #SR} rides the modem's IMS registration through a
 * framework {@code SipDelegate}; {@link #DR} performs its own SIP registration; {@link #UNKNOWN}
 * means the probe could not decide yet and may be retried. See docs/rcs/carrier-transport.md.
 */
public enum CarrierImsMode {
    SR,
    DR,
    UNKNOWN;

    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CarrierImsMode";

    /**
     * {@code SipDelegateManager.isSupported()} covers both the carrier declaring single
     * registration and the modem offering {@code SipDelegate}. Creates nothing; may block on live
     * IMS state, so call it off the main thread.
     *
     * <p>TODO: confirm on a carrier IMS network whether {@code isSupported()} alone is
     * authoritative or must be combined with a provisioned-configuration check.
     */
    public static CarrierImsMode probe(final Context context, final int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            LogUtil.w(TAG, SUBTAG + ": invalid subId=" + subId + " -> UNKNOWN");
            return UNKNOWN;
        }
        // Debuggable builds only: force DR even when SR is supported, to exercise the app's own
        // stack.
        if (RcsDebug.isDebugBuild()
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
            // The ImsService is not up for this subscription yet: retryable, not proof of DR.
            LogUtil.w(TAG, SUBTAG + ": isSupported threw code=" + e.getCode()
                    + " -> UNKNOWN (retryable)", e);
            return UNKNOWN;
        } catch (final SecurityException e) {
            // The permission is per uid, so this should not happen in :ims; if it does, SR is
            // unreachable.
            LogUtil.e(TAG, SUBTAG + ": isSupported SecurityException -> DR fallback", e);
            return DR;
        } catch (final Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": isSupported failed -> UNKNOWN", t);
            return UNKNOWN;
        }
    }
}

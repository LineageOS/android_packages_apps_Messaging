/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024 The LineageOS Project
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

import static android.telephony.SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.messaging.datamodel.ParticipantRefresh;

/**
 * Responds to default SMS subscription selection changes from system Settings.
 */
public class DefaultSmsSubscriptionChangeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED.equals(intent.getAction())) {
            ParticipantRefresh.refreshSelfParticipants();
            // Multi-transport framework (design §5.4): a subscription change (SIM
            // swap / default-SMS-sub change) is a re-selection trigger -- the active
            // line, its carrier, and thus which RCS transport serves it may all have
            // changed. Re-run the sticky selection for the new active sub. HIGH-2:
            // reselect ranks via canServeSub/getProviderCaps binder calls, so hop off
            // the main (broadcast) thread onto the shared worker and hold the process
            // alive across the hop with goAsync() / finish().
            try {
                final com.android.messaging.rcs.ProviderRegistry registry =
                        com.android.messaging.rcs.ProviderRegistry.peek();
                if (registry != null) {
                    final PendingResult pending = goAsync();
                    com.android.messaging.rcs.ProviderRegistry.postWork(() -> {
                        try {
                            registry.reselect("default-sms-sub-changed", /* freshCycle= */ true);
                        } catch (final Throwable t) {
                            com.android.messaging.util.LogUtil.w(
                                    com.android.messaging.util.LogUtil.BUGLE_TAG,
                                    "DefaultSmsSubscriptionChangeReceiver: RCS re-selection failed",
                                    t);
                        } finally {
                            pending.finish();
                        }
                    });
                }
            } catch (final Throwable t) {
                com.android.messaging.util.LogUtil.w(com.android.messaging.util.LogUtil.BUGLE_TAG,
                        "DefaultSmsSubscriptionChangeReceiver: RCS re-selection failed", t);
            }
        }
    }
}

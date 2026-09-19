/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.messaging.util.LogUtil;

/**
 * Handles a provider's wake broadcast, sent when it holds inbound traffic while this app is not
 * running. Receiving it starts the process, which binds the provider; the provider then delivers
 * what it holds. Only a sender holding the bind permission can send it.
 */
public final class RcsProviderWakeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, final Intent intent) {
        LogUtil.i(LogUtil.BUGLE_TAG,
                "RcsProviderWakeReceiver: woken by provider for held inbound — ensuring bind");
        try {
            // A cold start already binds from BugleApplication.onCreate; wake() covers a live but
            // unbound process. Idempotent.
            ProviderTransport.getInstance(context.getApplicationContext()).wake();
        } catch (Throwable t) {
            LogUtil.w(LogUtil.BUGLE_TAG, "RcsProviderWakeReceiver: ensure-bind failed", t);
        }
    }
}

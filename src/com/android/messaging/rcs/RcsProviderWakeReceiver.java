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

import com.android.messaging.util.LogUtil;

/**
 * Woken by the the RCS provider app when an inbound RCS message arrived while this app
 * (the UI) was unbound — typically on a dormant device, where the 466 FCM tickle
 * wakes only the provider process and this app may have been killed.
 *
 * <p>Receiving this broadcast cold-starts our process, which runs
 * {@code BugleApplication.onCreate → ProviderTransport.getInstance(ctx).init()} and
 * (re)binds the provider. The bind's {@code attach} triggers the provider's drain
 * pull, which redelivers the held message → it lands in the DB + posts the
 * notification. We also touch the singleton explicitly so the bind kicks even if a
 * future onCreate path changes. Signature-permission-gated (only the same-signed
 * provider can send it).
 */
public final class RcsProviderWakeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, final Intent intent) {
        LogUtil.i(LogUtil.BUGLE_TAG,
                "RcsProviderWakeReceiver: woken by provider for held inbound — ensuring bind");
        try {
            // Cold-starting this process already runs BugleApplication.onCreate →
            // ProviderTransport.init() (which binds). If the process was already alive
            // but unbound, wake() (re)binds. Idempotent either way.
            ProviderTransport.getInstance(context.getApplicationContext()).wake();
        } catch (Throwable t) {
            LogUtil.w(LogUtil.BUGLE_TAG, "RcsProviderWakeReceiver: ensure-bind failed", t);
        }
    }
}

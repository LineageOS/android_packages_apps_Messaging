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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

import com.android.messaging.util.LogUtil;

/**
 * Debug-only trigger for the native SipDelegate ride (the production
 * replacement for an out-of-process probe). Exported so {@code adb shell am
 * broadcast} can reach it; inert on a non-debuggable build.
 *
 * <p>Three actions:
 *
 * <pre>
 *   # 1) create the delegate (createSipDelegate with CPM 1-1 tags) and dump grants
 *   adb shell am broadcast \
 *     -a com.android.messaging.debug.SIPDELEGATE_CREATE \
 *     -n com.android.messaging/.rcs.sip.SipDelegateDebugReceiver
 *
 *   # 2) dump current status (delegate held? tags granted? config?)
 *   adb shell am broadcast \
 *     -a com.android.messaging.debug.SIPDELEGATE_STATUS \
 *     -n com.android.messaging/.rcs.sip.SipDelegateDebugReceiver
 *
 *   # 3) send a CPM 1-1 text MESSAGE over the delegate
 *   adb shell am broadcast \
 *     -a com.android.messaging.debug.SIPDELEGATE_SEND \
 *     -n com.android.messaging/.rcs.sip.SipDelegateDebugReceiver \
 *     --es from "+15715550113" --es to "+12025550101" --es body "hi over delegate"
 *
 *   # 4) destroy the delegate
 *   adb shell am broadcast \
 *     -a com.android.messaging.debug.SIPDELEGATE_DESTROY \
 *     -n com.android.messaging/.rcs.sip.SipDelegateDebugReceiver
 * </pre>
 *
 * Grep logcat for {@code SipDelegateClient} lines (tag {@code MessagingApp}).
 */
public final class SipDelegateDebugReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    static final String ACTION_CREATE = "com.android.messaging.debug.SIPDELEGATE_CREATE";
    static final String ACTION_STATUS = "com.android.messaging.debug.SIPDELEGATE_STATUS";
    static final String ACTION_SEND = "com.android.messaging.debug.SIPDELEGATE_SEND";
    static final String ACTION_SESSION_SEND =
            "com.android.messaging.debug.SIPDELEGATE_SESSION_SEND";
    static final String ACTION_DESTROY = "com.android.messaging.debug.SIPDELEGATE_DESTROY";
    // Path A: the PULL trigger Google Messages uses (setRcsClientConfiguration +
    // registerRcsProvisioningCallback + triggerRcsReconfiguration).
    static final String ACTION_PULL_CONFIG = "com.android.messaging.debug.SHANNON_PULL_CONFIG";
    // FED/push path: hand Shannon a FULL RCC.07 config doc directly via
    // ProvisioningManager.notifyRcsAutoConfigurationReceived, bypassing the
    // modem's trigger->fetch (which stalls on the open5gs lab). Use ONLY with a
    // complete, ACS-issued doc — a partial hand-rolled doc leaves minimal caps
    // (no oma.cpm.msg). Reads the XML from --es config_file <path>.
    static final String ACTION_INJECT_CONFIG = "com.android.messaging.debug.SHANNON_INJECT_CONFIG";
    /** Drive an RCS UCE requestCapabilities — a capability CONSUMER that forces the
     *  modem's lazily-registered RcsFeature to actually SIP-REGISTER its granted
     *  feature tags (oma.cpm) on the wire (registeredCapabilityBitmask 0->N).
     *  Without a consumer the delegate holds the tags but never emits the REGISTER.
     *  Optional --es to <e164> (default: own MSISDN). */
    static final String ACTION_UCE = "com.android.messaging.debug.SIPDELEGATE_UCE";
    /** Force a full IMS re-registration so the modem re-REGISTERs with the
     *  delegate's granted oma.cpm feature tags in its Contact
     *  (registeredCapabilityBitmask 0->N). Optional --ei code <sipCode>
     *  (default 403) --es reason <text>. Requires a delegate (CREATE first). */
    static final String ACTION_REREGISTER = "com.android.messaging.debug.SIPDELEGATE_REREGISTER";

    private static final String EXTRA_FROM = "from";
    private static final String EXTRA_TO = "to";
    private static final String EXTRA_BODY = "body";
    private static final String EXTRA_CONFIG_FILE = "config_file";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        // Gate: inert on a user (non-debuggable) build. Use the build TYPE, not
        // ApplicationInfo.FLAG_DEBUGGABLE (false for system apps even on userdebug).
        final boolean debuggable =
                "eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE);
        if (!debuggable) {
            LogUtil.w(TAG, "SipDelegate debug ignored: build is not debuggable");
            return;
        }

        final SipDelegateClient client = SipDelegateClient.getInstance(context);
        switch (intent.getAction()) {
            case ACTION_PULL_CONFIG:
                LogUtil.i(TAG, "DEBUG SHANNON_PULL_CONFIG");
                ShannonRcsConfigTrigger.getInstance(context).trigger();
                break;
            case ACTION_INJECT_CONFIG: {
                final String path = intent.getStringExtra(EXTRA_CONFIG_FILE);
                if (TextUtils.isEmpty(path)) {
                    LogUtil.w(TAG, "DEBUG SHANNON_INJECT_CONFIG missing config_file extra");
                    return;
                }
                // Robust push: Shannon only APPLIES the config when its RCS
                // ImsService config is bound AND its service subId matches the
                // system subId AND connectivity is ready. Those align only in a
                // narrow window, so loop the notify (default 20 reps x 3s = ~60s)
                // to catch it. Each miss just re-stores harmlessly.
                final int reps = intent.getIntExtra("reps", 20);
                final int delayMs = intent.getIntExtra("delay_ms", 3000);
                final byte[] cfg;
                try {
                    cfg = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
                } catch (Throwable t) {
                    LogUtil.e(TAG, "DEBUG SHANNON_INJECT_CONFIG read failed", t);
                    return;
                }
                int sid = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
                if (!android.telephony.SubscriptionManager.isValidSubscriptionId(sid)) {
                    sid = android.telephony.SubscriptionManager.getDefaultSubscriptionId();
                }
                final int subId = sid;
                final PendingResult pr = goAsync();
                new Thread(() -> {
                    try {
                        final android.telephony.ims.ProvisioningManager pm =
                                android.telephony.ims.ProvisioningManager
                                        .createForSubscriptionId(subId);
                        for (int i = 0; i < reps; i++) {
                            try {
                                pm.notifyRcsAutoConfigurationReceived(cfg, false);
                                LogUtil.i(TAG, "DEBUG SHANNON_INJECT_CONFIG rep " + (i + 1)
                                        + "/" + reps + " subId=" + subId + " len=" + cfg.length);
                            } catch (Throwable t) {
                                LogUtil.e(TAG, "DEBUG SHANNON_INJECT_CONFIG rep " + (i + 1)
                                        + " threw: " + t);
                            }
                            if (i < reps - 1) android.os.SystemClock.sleep(delayMs);
                        }
                    } finally {
                        pr.finish();
                    }
                }, "shannon-inject").start();
                break;
            }
            case ACTION_CREATE:
                LogUtil.i(TAG, "DEBUG SIPDELEGATE_CREATE");
                client.create();
                break;
            case ACTION_STATUS:
                LogUtil.i(TAG, "DEBUG SIPDELEGATE_STATUS: " + client.getStatus());
                break;
            case ACTION_DESTROY:
                LogUtil.i(TAG, "DEBUG SIPDELEGATE_DESTROY");
                client.destroy();
                break;
            case ACTION_REREGISTER: {
                final int code = intent.getIntExtra("code", 403);
                final String reason = intent.getStringExtra("reason");
                LogUtil.i(TAG, "DEBUG SIPDELEGATE_REREGISTER code=" + code
                        + " reason=" + reason);
                client.triggerReregister(code,
                        TextUtils.isEmpty(reason) ? "reregister" : reason);
                break;
            }
            case ACTION_UCE: {
                final int subId = android.telephony.SubscriptionManager
                        .getDefaultSmsSubscriptionId();
                final String toExtra = intent.getStringExtra(EXTRA_TO);
                final String num = TextUtils.isEmpty(toExtra) ? "+11012026331" : toExtra;
                try {
                    final android.telephony.ims.ImsManager im =
                            context.getSystemService(android.telephony.ims.ImsManager.class);
                    final android.telephony.ims.RcsUceAdapter uce =
                            im.getImsRcsManager(subId).getUceAdapter();
                    final java.util.List<android.net.Uri> uris =
                            java.util.Collections.singletonList(
                                    android.net.Uri.parse("tel:" + num));
                    final java.util.concurrent.Executor ex =
                            java.util.concurrent.Executors.newSingleThreadExecutor();
                    LogUtil.i(TAG, "DEBUG SIPDELEGATE_UCE requestCapabilities subId="
                            + subId + " for tel:" + num
                            + " (drives lazy RcsFeature -> wire REGISTER of oma.cpm)");
                    uce.requestCapabilities(uris, ex,
                            new android.telephony.ims.RcsUceAdapter.CapabilitiesCallback() {
                        @Override public void onCapabilitiesReceived(
                                java.util.List<android.telephony.ims.RcsContactUceCapability> caps) {
                            LogUtil.i(TAG, "DEBUG SIPDELEGATE_UCE onCapabilitiesReceived n="
                                    + (caps == null ? 0 : caps.size()) + " " + caps);
                        }
                        @Override public void onComplete() {
                            LogUtil.i(TAG, "DEBUG SIPDELEGATE_UCE onComplete");
                        }
                        @Override public void onError(int code, long retryAfterMs) {
                            LogUtil.w(TAG, "DEBUG SIPDELEGATE_UCE onError code=" + code
                                    + " retryAfterMs=" + retryAfterMs);
                        }
                    });
                } catch (final Throwable t) {
                    LogUtil.e(TAG, "DEBUG SIPDELEGATE_UCE failed", t);
                }
                break;
            }
            case ACTION_SEND: {
                final String from = intent.getStringExtra(EXTRA_FROM);
                final String to = intent.getStringExtra(EXTRA_TO);
                final String body = intent.getStringExtra(EXTRA_BODY);
                if (TextUtils.isEmpty(from) || TextUtils.isEmpty(to) || body == null) {
                    LogUtil.w(TAG, "DEBUG SIPDELEGATE_SEND missing from/to/body extra");
                    return;
                }
                LogUtil.i(TAG, "DEBUG SIPDELEGATE_SEND from=" + from + " to=" + to);
                final String callId = client.sendText(from, to, body);
                LogUtil.i(TAG, "DEBUG SIPDELEGATE_SEND issued callId=" + callId);
                break;
            }
            case ACTION_SESSION_SEND: {
                final String from = intent.getStringExtra(EXTRA_FROM);
                final String to = intent.getStringExtra(EXTRA_TO);
                final String body = intent.getStringExtra(EXTRA_BODY);
                if (TextUtils.isEmpty(from) || TextUtils.isEmpty(to) || body == null) {
                    LogUtil.w(TAG, "DEBUG SIPDELEGATE_SESSION_SEND missing from/to/body extra");
                    return;
                }
                LogUtil.i(TAG, "DEBUG SIPDELEGATE_SESSION_SEND (INVITE+SDP+MSRP) from="
                        + from + " to=" + to);
                client.sendSessionText(from, to, body);
                LogUtil.i(TAG, "DEBUG SIPDELEGATE_SESSION_SEND dispatched; grep CpmSessionEngine");
                break;
            }
            default:
                break;
        }
    }
}

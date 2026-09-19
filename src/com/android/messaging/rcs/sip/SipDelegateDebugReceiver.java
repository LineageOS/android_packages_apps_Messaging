/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.util.LogUtil;

/**
 * Debuggable builds only: {@code adb shell am broadcast} actions that drive
 * {@link SipDelegateClient} and {@link ShannonRcsConfigTrigger} by hand. Exported, and inert on
 * other builds. Extras: {@code from}, {@code to}, {@code body} for the send actions.
 */
public final class SipDelegateDebugReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    static final String ACTION_CREATE = "com.android.messaging.debug.SIPDELEGATE_CREATE";
    static final String ACTION_STATUS = "com.android.messaging.debug.SIPDELEGATE_STATUS";
    static final String ACTION_SEND = "com.android.messaging.debug.SIPDELEGATE_SEND";
    static final String ACTION_SESSION_SEND =
            "com.android.messaging.debug.SIPDELEGATE_SESSION_SEND";
    static final String ACTION_DESTROY = "com.android.messaging.debug.SIPDELEGATE_DESTROY";
    // Asks the modem to fetch the RCS configuration itself.
    static final String ACTION_PULL_CONFIG = "com.android.messaging.debug.SHANNON_PULL_CONFIG";
    // Hands the modem a complete configuration document from --es config_file <path>, skipping its
    // own fetch. A partial document leaves the pager-mode tag ungranted.
    static final String ACTION_INJECT_CONFIG = "com.android.messaging.debug.SHANNON_INJECT_CONFIG";
    /**
     * A UCE capability request, which makes the modem register the granted feature tags on the
     * network; without a consumer it may hold them unregistered. Optional {@code --es to <e164>}.
     */
    static final String ACTION_UCE = "com.android.messaging.debug.SIPDELEGATE_UCE";
    /**
     * Forces a full IMS re-registration carrying the delegate's tags. Optional
     * {@code --ei code} (default 403) and {@code --es reason}. Needs a delegate.
     */
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
        // ro.debuggable, not FLAG_DEBUGGABLE, which is false for system apps even on userdebug.
        final boolean debuggable = RcsDebug.isDebugBuild();
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
                // The modem applies the document only while its RCS service, subscription and
                // connectivity line up, so repeat the notify (--ei reps, --ei delay_ms).
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

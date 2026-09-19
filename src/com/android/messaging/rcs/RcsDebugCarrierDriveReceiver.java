/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import org.lineageos.rcs.provider.RcsOutgoingMessage;
import org.lineageos.rcs.provider.RcsSendResult;
import org.lineageos.rcs.provider.RcsSubInfo;

import com.android.messaging.rcs.carrier.AcsImsConfigParser;
import com.android.messaging.rcs.carrier.CarrierImsTransport;
import com.android.messaging.rcs.carrier.RcsImsConfig;
import com.android.messaging.util.LogUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Debug-only driver for the carrier transport ({@link CarrierImsTransport}), bypassing route
 * selection so the {@code :ims} registration, MSRP session setup and send path can be exercised
 * before the line is provisioned. Ignored unless {@link RcsDebug#isDebugBuild()} (a system app is
 * never marked debuggable). {@code sub} defaults to the default SMS subscription.
 *
 * <pre>
 *   # Start carrier provisioning and registration:
 *   adb shell am broadcast -a com.android.messaging.debug.CARRIER_START \
 *     -n com.android.messaging/.rcs.RcsDebugCarrierDriveReceiver --ei sub &lt;subId&gt;
 *
 *   # Pre-establish an MSRP session to a peer:
 *   adb shell am broadcast -a com.android.messaging.debug.CARRIER_WARM \
 *     -n com.android.messaging/.rcs.RcsDebugCarrierDriveReceiver --es to &lt;e164&gt;
 *
 *   # Send a 1:1 text. The first send to a cold peer is refused and starts a session; send
 *   # again once the session is up:
 *   adb shell am broadcast -a com.android.messaging.debug.CARRIER_SEND \
 *     -n com.android.messaging/.rcs.RcsDebugCarrierDriveReceiver --es to &lt;e164&gt; --es body hi
 * </pre>
 */
public final class RcsDebugCarrierDriveReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    static final String ACTION_CARRIER_START = "com.android.messaging.debug.CARRIER_START";
    static final String ACTION_CARRIER_WARM = "com.android.messaging.debug.CARRIER_WARM";
    static final String ACTION_CARRIER_SEND = "com.android.messaging.debug.CARRIER_SEND";
    static final String ACTION_CARRIER_ACS_PARSE =
            "com.android.messaging.debug.CARRIER_ACS_PARSE";
    static final String ACTION_CARRIER_TYPING =
            "com.android.messaging.debug.CARRIER_TYPING";
    static final String ACTION_CARRIER_IMDN =
            "com.android.messaging.debug.CARRIER_IMDN";
    static final String ACTION_CARRIER_STOP =
            "com.android.messaging.debug.CARRIER_STOP";
    static final String ACTION_CARRIER_FT =
            "com.android.messaging.debug.CARRIER_FT";
    static final String ACTION_CARRIER_PLAIN =
            "com.android.messaging.debug.CARRIER_PLAIN";

    private static final String EXTRA_SUB = "sub";
    private static final String EXTRA_TO = "to";
    private static final String EXTRA_BODY = "body";
    private static final String EXTRA_FILE = "file";
    private static final String EXTRA_ACTIVE = "active";
    private static final String EXTRA_MID = "mid";
    private static final String CONTENT_TYPE = "text/plain;charset=UTF-8";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }
        final String action = intent.getAction();

        // Ignored on a non-debuggable build, although exported.
        final boolean debuggable = RcsDebug.isDebugBuild();
        if (!debuggable) {
            LogUtil.w(TAG, "DEBUG " + action + " ignored: build is not debuggable");
            return;
        }

        // ACS_PARSE needs no transport: it runs AcsImsConfigParser on an OMA-CP document read from
        // --es file <path>, which exercises the on-device XML parser that host tests cannot.
        if (ACTION_CARRIER_ACS_PARSE.equals(action)) {
            final PendingResult acsPending = goAsync();
            final String file = intent.getStringExtra(EXTRA_FILE);
            ProviderRegistry.postWork(() -> {
                try {
                    parseAcs(file);
                } finally {
                    acsPending.finish();
                }
            });
            return;
        }

        final CarrierImsTransport transport = CarrierImsTransport.peek();
        if (transport == null) {
            LogUtil.w(TAG, "DEBUG " + action + " ignored: CarrierImsTransport not registered");
            return;
        }

        int resolvedSub =
                intent.getIntExtra(EXTRA_SUB, SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (!SubscriptionManager.isValidSubscriptionId(resolvedSub)) {
            resolvedSub = SubscriptionManager.getDefaultSmsSubscriptionId();
        }
        final int subId = resolvedSub;
        final String to = intent.getStringExtra(EXTRA_TO);
        final String body = intent.getStringExtra(EXTRA_BODY);
        final boolean active = intent.getBooleanExtra(EXTRA_ACTIVE, true);
        final String mid = intent.getStringExtra(EXTRA_MID);
        final String file = intent.getStringExtra(EXTRA_FILE);

        // These calls can block, so they run on the registry worker with goAsync holding the
        // process.
        final PendingResult pending = goAsync();
        ProviderRegistry.postWork(() -> {
            try {
                driveCarrier(transport, action, subId, to, body, active, mid, file);
            } finally {
                pending.finish();
            }
        });
    }

    /** Run {@link AcsImsConfigParser} on the OMA-CP document at {@code path} and log the result. */
    private static void parseAcs(final String path) {
        if (path == null || path.isEmpty()) {
            LogUtil.w(TAG, "DEBUG CARRIER_ACS_PARSE missing 'file' extra; ignoring");
            return;
        }
        final byte[] xml;
        try {
            final File f = new File(path);
            final byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                    off += n;
                }
            }
            xml = buf;
        } catch (final IOException | RuntimeException e) {
            LogUtil.w(TAG, "DEBUG CARRIER_ACS_PARSE cannot read " + path + ": " + e);
            return;
        }
        LogUtil.i(TAG, "DEBUG CARRIER_ACS_PARSE file=" + path + " bytes=" + xml.length);
        final AcsImsConfigParser.ImsSettings s = AcsImsConfigParser.parse(xml);
        if (s == null) {
            LogUtil.w(TAG, "DEBUG CARRIER_ACS_PARSE: parse() returned null (bad XML)");
            return;
        }
        LogUtil.i(TAG, "DEBUG CARRIER_ACS_PARSE settings:"
                + " pcscf=" + s.pcscfAddress
                + " realm=" + s.realm
                + " userName=" + s.userName
                + " pwdLen=" + (s.userPwd == null ? -1 : s.userPwd.length())
                + " pwdIsHa1=" + s.pwdIsHa1
                + " publicIdSip=" + s.publicIdSip
                + " publicIdMsisdn=" + s.publicIdMsisdn
                + " hasSipEssentials=" + s.hasSipEssentials());
        final RcsImsConfig cfg = AcsImsConfigParser.toRcsImsConfig(s, null, "SIPoUDP");
        if (cfg == null) {
            LogUtil.w(TAG, "DEBUG CARRIER_ACS_PARSE: toRcsImsConfig returned null"
                    + " (missing SIP essentials or Digest secret)");
            return;
        }
        LogUtil.i(TAG, "DEBUG CARRIER_ACS_PARSE config: " + cfg
                + " authDigestUser=" + cfg.authDigestUsername
                + " authDigestIsHa1=" + cfg.authDigestIsHa1);
    }

    private static void driveCarrier(final CarrierImsTransport transport, final String action,
            final int subId, final String to, final String body,
            final boolean active, final String mid, final String file) {
        switch (action) {
            case ACTION_CARRIER_TYPING: {
                if (TextUtils.isEmpty(to)) {
                    LogUtil.w(TAG, "DEBUG CARRIER_TYPING missing 'to' extra; ignoring");
                    return;
                }
                LogUtil.i(TAG, "DEBUG CARRIER_TYPING subId=" + subId + " to=" + to
                        + " active=" + active + " -> CarrierImsTransport.sendTyping");
                transport.sendTyping(to, active);
                break;
            }
            case ACTION_CARRIER_IMDN: {
                if (TextUtils.isEmpty(to) || TextUtils.isEmpty(mid)) {
                    LogUtil.w(TAG, "DEBUG CARRIER_IMDN missing 'to' or 'mid' extra; ignoring");
                    return;
                }
                LogUtil.i(TAG, "DEBUG CARRIER_IMDN subId=" + subId + " to=" + to
                        + " mid=" + mid + " -> CarrierImsTransport.sendImdn (display)");
                // imdnType is advisory; the DR path emits a display/read report.
                transport.sendImdn(mid, to, /*imdnType=*/ 1);
                break;
            }
            case ACTION_CARRIER_START: {
                LogUtil.i(TAG, "DEBUG CARRIER_START subId=" + subId
                        + " -> CarrierImsTransport.startForSub");
                transport.startForSub(new RcsSubInfo(subId, -1, null, null, null, null));
                break;
            }
            case ACTION_CARRIER_STOP: {
                LogUtil.i(TAG, "DEBUG CARRIER_STOP subId=" + subId
                        + " -> CarrierImsTransport.stopForSub (Expires:0 de-REGISTER)");
                transport.stopForSub(subId);
                break;
            }
            case ACTION_CARRIER_FT: {
                if (TextUtils.isEmpty(file)) {
                    LogUtil.w(TAG, "DEBUG CARRIER_FT missing 'file' extra; ignoring");
                    return;
                }
                LogUtil.i(TAG, "DEBUG CARRIER_FT subId=" + subId + " file=" + file
                        + " to=" + to + " -> CarrierImsTransport.debugFtUpload");
                transport.debugFtUpload(subId, file, to);
                break;
            }
            case ACTION_CARRIER_WARM: {
                if (TextUtils.isEmpty(to)) {
                    LogUtil.w(TAG, "DEBUG CARRIER_WARM missing 'to' extra; ignoring");
                    return;
                }
                LogUtil.i(TAG, "DEBUG CARRIER_WARM subId=" + subId + " to=" + to
                        + " -> CarrierImsTransport.warmPeer");
                transport.warmPeer(subId, to);
                break;
            }
            case ACTION_CARRIER_SEND: {
                if (TextUtils.isEmpty(to) || body == null) {
                    LogUtil.w(TAG, "DEBUG CARRIER_SEND missing 'to' or 'body' extra; ignoring");
                    return;
                }
                final String messageId = UUID.randomUUID().toString();
                LogUtil.i(TAG, "DEBUG CARRIER_SEND subId=" + subId + " to=" + to
                        + " messageId=" + messageId + " -> CarrierImsTransport.sendMessage");
                final RcsSendResult result = transport.sendMessage(new RcsOutgoingMessage(
                        subId, messageId, to, CONTENT_TYPE, body.getBytes(StandardCharsets.UTF_8)));
                LogUtil.i(TAG, "DEBUG CARRIER_SEND result accepted=" + result.accepted
                        + " reasonCode=" + result.reasonCode + " reason=" + result.reason
                        + " messageId=" + messageId
                        + " (accepted=false when cold -> keep-warm kicked; re-run when warm)");
                break;
            }
            case ACTION_CARRIER_PLAIN: {
                if (TextUtils.isEmpty(to) || body == null) {
                    LogUtil.w(TAG, "DEBUG CARRIER_PLAIN missing 'to' or 'body' extra; ignoring");
                    return;
                }
                final String messageId = TextUtils.isEmpty(mid)
                        ? UUID.randomUUID().toString() : mid;
                LogUtil.i(TAG, "DEBUG CARRIER_PLAIN subId=" + subId + " to=" + to
                        + " mid=" + messageId
                        + " -> CarrierImsTransport.sendPlainDebug (non-CPM text/plain)");
                transport.sendPlainDebug(subId, to, body, messageId);
                break;
            }
            default:
                LogUtil.w(TAG, "DEBUG carrier-drive: unknown action " + action);
        }
    }
}

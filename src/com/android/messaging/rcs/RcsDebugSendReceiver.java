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
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import com.android.messaging.datamodel.action.ReceiveRcsMessageAction;
import org.lineageos.rcs.provider.RcsIncomingMessage;
import org.lineageos.rcs.provider.RcsOutgoingMessage;
import org.lineageos.rcs.provider.RcsSendResult;

import com.android.messaging.rcs.e2ee.E2eeSendGate;
import com.android.messaging.util.LogUtil;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Debug-only trigger used by the E2E harness to force an outgoing RCS send
 * straight through the provider seam, bypassing the normal compose UI and the
 * SMS-fallback RouteSelector logic.
 *
 * <p>Exported (so {@code adb shell am broadcast} can reach it) but inert on a
 * user build: {@link #onReceive} short-circuits unless the app is flagged
 * {@link ApplicationInfo#FLAG_DEBUGGABLE}. On a debuggable build it builds an
 * {@link RcsOutgoingMessage} from the {@code to} / {@code body} string extras
 * (subId = default-SMS sub) and calls
 * {@link ProviderTransport#sendMessage(RcsOutgoingMessage)} directly.
 *
 * <p>Exercise from adb:
 * <pre>
 *   adb shell am broadcast \
 *     -a com.android.messaging.debug.SEND_TEST_RCS \
 *     -n com.android.messaging/.rcs.RcsDebugSendReceiver \
 *     --es to "+15555550123" --es body "hello from e2e"
 * </pre>
 * Grep logcat for the "DEBUG SEND_TEST_RCS" lines (tag {@code MessagingApp}).
 */
public final class RcsDebugSendReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    static final String ACTION_SEND_TEST_RCS = "com.android.messaging.debug.SEND_TEST_RCS";
    private static final String EXTRA_TO = "to";
    private static final String EXTRA_BODY = "body";
    private static final String CONTENT_TYPE = "text/plain;charset=UTF-8";

    /**
     * Which arm this broadcast selects, for the one-line trace at the top of {@link #onReceive}.
     *
     * <p><b>Ordering mirrors the if-chain below and is for LOGGING ONLY</b> — dispatch remains the
     * chain itself, so a drift here misnames a line but cannot misroute a broadcast. Keep it in
     * step anyway: a trace that names the wrong arm is worse than no trace.
     */
    private static String armOf(final Intent i) {
        if (i.getStringExtra("recvct") != null) return "recvct";
        if (i.getStringExtra("plainrename") != null) return "plainrename";
        if (i.getBooleanExtra("gate", false)) return "gate";
        return "send";
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION_SEND_TEST_RCS.equals(intent.getAction())) {
            return;
        }

        // Gate: inert on a user (non-debuggable) build even though exported. Use
        // the build TYPE (eng/userdebug), NOT ApplicationInfo.FLAG_DEBUGGABLE —
        // the latter reflects the manifest android:debuggable attr and is false
        // for system apps even on a userdebug image.
        final boolean debuggable =
                "eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE);
        if (!debuggable) {
            LogUtil.w(TAG, "DEBUG SEND_TEST_RCS ignored: build is not debuggable");
            return;
        }

        final String to = intent.getStringExtra(EXTRA_TO);
        final String body = intent.getStringExtra(EXTRA_BODY);

        // NO to/body GATE HERE.
        //
        // This used to require BOTH extras before ANY arm dispatched, which is wrong: the guard
        // exists only for the DEFAULT send path at the bottom of this method, and every arm below
        // returns long before reaching it.
        // The gate now sits immediately before the default send path, which is the only code here
        // that actually consumes both extras.
        final String arm = armOf(intent);
        LogUtil.i(TAG, "DEBUG SEND_TEST_RCS arm=" + arm
                + " to=" + (to == null ? "<unset>" : to)
                + " body=" + (body == null ? "<unset>" : (body.length() + "ch"))
                + " rcsgid=" + intent.getStringExtra("rcsgid"));

        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultDataSubscriptionId();
        }

        final String messageId = UUID.randomUUID().toString();
        LogUtil.i(TAG, "DEBUG SEND_TEST_RCS to=" + to + " body=" + body
                + " subId=" + subId + " messageId=" + messageId
                + " -> ProviderTransport.sendMessage");

        // --es displayreceipt-mid <messageId> --es to <peer> [--es rcsgid <group>]
        // → emit a DISPLAYED IMDN (read receipt) for <messageId> to <peer>. The app normally
        // sends this on read; this lets a test send it on demand.
        if (intent.getStringExtra("displayreceipt-mid") != null) {
            final Context appCtxDr = context.getApplicationContext();
            final String drMid = intent.getStringExtra("displayreceipt-mid");
            final String drTo = to;
            final String drGid = intent.getStringExtra("rcsgid"); // null for 1:1
            if (drTo == null) {
                LogUtil.w(TAG, "DEBUG MLS DISPLAYRECEIPT needs --es to <peer>");
                return;
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    ProviderTransport.getInstance(appCtxDr).sendImdn(drMid, drTo,
                            org.lineageos.rcs.provider.IRcsProviderCallback.IMDN_DISPLAYED, drGid);
                    LogUtil.i(TAG, "DEBUG MLS DISPLAYRECEIPT: sent DISPLAYED for " + drMid
                            + " to " + drTo + (drGid != null ? " grp=" + drGid : ""));
                }
            }, "mls-display-receipt").start();
            return;
        }

        // --es recvct <content-type> [--es from <e164>] [--es recvfile <path>] [--es body <text>]
        //   [--es rcsgid <id>]
        //   → drive ONE inbound message through the real receive path with a content type WE
        //     choose, and render it.
        //
        // WHY A LEVER RATHER THAN TWO PHONES. The defect is a peer sending a
        // conforming-but-uppercase MIME — "IMAGE/JPEG" is legal per RFC 2045 §5.1 — and neither of
        // our own two producers can emit one: Google Messages sends lowercase, and our own send path
        // frames whatever the media layer hands it, which on Android is lowercase by convention.
        // So the interesting case is exactly the one no natural trigger produces, which is the
        // "instrument that cannot discriminate" trap.
        //
        // WHERE IT ENTERS, stated so nobody over-reads the proof: at RcsCallbackRouter's inbound
        // funnel, whose whole body is `new ReceiveRcsMessageAction(msg).start()`. That covers
        // classify(), the MEDIA arm, staging, the part row and every renderer — i.e. the whole
        // chain from the point the content type and bytes are already unframed.
        //
        // LOCAL ONLY: no network and no peer's key material — it inserts one row.
        if (intent.getStringExtra("recvct") != null) {
            final Context appCtxR = context.getApplicationContext();
            final int rcSubId = subId;
            final String rcType = intent.getStringExtra("recvct");
            final String rcFrom = to != null ? to : intent.getStringExtra("from");
            final String rcFile = intent.getStringExtra("recvfile");
            final String rcGid = intent.getStringExtra("rcsgid");
            final String rcBody = body;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        byte[] bytes;
                        if (rcFile != null) {
                            final java.io.File f = new java.io.File(rcFile);
                            bytes = new byte[(int) f.length()];
                            final java.io.FileInputStream in = new java.io.FileInputStream(f);
                            int off = 0, n;
                            while (off < bytes.length
                                    && (n = in.read(bytes, off, bytes.length - off)) > 0) {
                                off += n;
                            }
                            in.close();
                        } else {
                            bytes = (rcBody == null ? "" : rcBody)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        }
                        if (rcFrom == null || rcFrom.isEmpty()) {
                            LogUtil.w(TAG, "DEBUG RECVCT needs --es to <e164> (or --es from)");
                            return;
                        }
                        final String rcMid = "recvct-" + UUID.randomUUID();
                        LogUtil.i(TAG, "DEBUG RECVCT ct=<<" + rcType + ">> from=" + rcFrom
                                + " body=" + bytes.length + "B mid=" + rcMid
                                + " rcsgid=" + rcGid + " — the type is passed VERBATIM, which is "
                                + "the whole point: a conforming peer may send it in any case");
                        // The funnel RcsCallbackRouter.onIncomingMessage uses, verbatim — its body
                        // is this one line, and going through the Action directly keeps the lever
                        // from depending on a router instance it would have to invent.
                        new ReceiveRcsMessageAction(new RcsIncomingMessage(rcSubId, rcMid, rcFrom,
                                rcType, bytes, /*serverTimestampUsec=*/ 0L,
                                /*wantsDeliveredImdn=*/ false, /*wantsDisplayedImdn=*/ false,
                                rcGid, null)).start();
                        LogUtil.i(TAG, "DEBUG RECVCT handed to ReceiveRcsMessageAction — watch for "
                                + "the classify line and the part row's media mime");
                    } catch (final Throwable e) {
                        LogUtil.w(TAG, "DEBUG RECVCT failed", e);
                    }
                }
            }, "debug-recvct").start();
            return;
        }

        // --es plainrename <text> [--es rcsgid <id>] → set the RCS group name in the clear.
        if (intent.getStringExtra("plainrename") != null) {
            final Context appCtxN = context.getApplicationContext();
            final int nSubId = subId;
            final String nGid = intent.getStringExtra("rcsgid");
            final String nName = intent.getStringExtra("plainrename");
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG RCS PLAINRENAME '" + nName + "' → "
                            + com.android.messaging.rcs.ProviderTransport.getInstance(appCtxN)
                                    .renameGroup(nSubId, nGid, nName));
                }
            }, "rcs-plain-rename").start();
            return;
        }

        // --ez gate true → run the REAL eligibility gate (E2eeSendGate.resolveForSend) and log its
        // verdict.
        if (intent.getBooleanExtra("gate", false)) {
            final String scheme = E2eeSendGate.get()
                    .resolveForSend(/*conversationId=*/ "debug-gate-probe", subId, to,
                            /*isGroup=*/ false);
            LogUtil.i(TAG, "DEBUG GATE PROBE to=" + to + " subId=" + subId
                    + " → selected scheme=" + scheme);
            return;
        }

        // THE DEFAULT SEND PATH — the only code in this receiver that consumes BOTH extras, and so
        // the only place the to/body requirement belongs (it used to gate every arm).
        if (TextUtils.isEmpty(to) || body == null) {
            LogUtil.w(TAG, "DEBUG SEND_TEST_RCS: no arm extra matched, so this is the plain send "
                    + "path, which needs --es to <e164> --es body <text>. Nothing sent.");
            return;
        }

        final byte[] outBody = body.getBytes(StandardCharsets.UTF_8);
        final RcsOutgoingMessage msg =
                new RcsOutgoingMessage(subId, messageId, to, CONTENT_TYPE, outBody);

        final ProviderTransport transport = ProviderTransport.getInstance(context);
        final RcsSendResult result = transport.sendMessage(msg);
        LogUtil.i(TAG, "DEBUG SEND_TEST_RCS result accepted=" + result.accepted
                + " reasonCode=" + result.reasonCode + " reason=" + result.reason
                + " messageId=" + messageId);
    }
}

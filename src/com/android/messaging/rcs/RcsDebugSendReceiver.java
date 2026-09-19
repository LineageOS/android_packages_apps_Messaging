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

import com.android.messaging.datamodel.action.ReceiveRcsMessageAction;
import org.lineageos.rcs.provider.RcsIncomingMessage;
import org.lineageos.rcs.provider.RcsOutgoingMessage;
import org.lineageos.rcs.provider.RcsSendResult;

import com.android.messaging.util.LogUtil;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Debug-only driver for RCS operations, bypassing the compose UI and route selection.
 * Ignored unless {@link RcsDebug#isDebugBuild()} (a system app is never marked
 * debuggable). With no arm extra it sends {@code body} to {@code to} through
 * {@link ProviderTransport#sendMessage(RcsOutgoingMessage)}; each arm extra below selects another
 * operation.
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.SEND_TEST_RCS \
 *     -n com.android.messaging/.rcs.RcsDebugSendReceiver --es to &lt;e164&gt; --es body hi
 * </pre>
 */
public final class RcsDebugSendReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    static final String ACTION_SEND_TEST_RCS = "com.android.messaging.debug.SEND_TEST_RCS";
    private static final String EXTRA_TO = "to";
    private static final String EXTRA_BODY = "body";
    private static final String CONTENT_TYPE = "text/plain;charset=UTF-8";

    /**
     * Which arm this broadcast selects, for the trace line and the gates. The order mirrors the
     * dispatch chain in {@link #onReceive}, which remains the actual dispatch.
     */
    private static String armOf(final Intent i) {
        if (i.getStringExtra("recvct") != null) return "recvct";
        if (i.getStringExtra("plainrename") != null) return "plainrename";
        return "send";
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION_SEND_TEST_RCS.equals(intent.getAction())) {
            return;
        }

        // Inert on a non-debuggable build, although exported. ro.debuggable rather than
        // FLAG_DEBUGGABLE, which is false for a system app even on a userdebug image.
        final boolean debuggable = RcsDebug.isDebugBuild();
        if (!debuggable) {
            LogUtil.w(TAG, "DEBUG SEND_TEST_RCS ignored: build is not debuggable");
            return;
        }

        final String to = intent.getStringExtra(EXTRA_TO);
        final String body = intent.getStringExtra(EXTRA_BODY);

        // No to/body requirement here: only the default send path at the bottom needs both.
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
        LogUtil.i(TAG, "DEBUG SEND_TEST_RCS to=" + to
                + " body=" + (body == null ? "<unset>" : (body.length() + "ch"))
                + " subId=" + subId + " messageId=" + messageId
                + " -> ProviderTransport.sendMessage");

        // --es recvct <content-type> [--es from <e164>] [--es recvfile <path>] [--es body <text>]
        // [--es rcsgid <id>]: run one inbound message with a chosen content type through
        // ReceiveRcsMessageAction, for example an uppercase MIME type (RFC 2045 §5.1). Starts after
        // unframing. Local only: inserts one row.
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
                        // The same entry RcsCallbackRouter.onIncomingMessage uses.
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

        // --es plainrename <name> --es rcsgid <id>: rename a group through the provider.
        if (intent.getStringExtra("plainrename") != null) {
            final Context appCtxN = context.getApplicationContext();
            final int nSubId = subId;
            final String nGid = intent.getStringExtra("rcsgid");
            final String nName = intent.getStringExtra("plainrename");
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean renamed = com.android.messaging.rcs.ProviderTransport
                            .getInstance(appCtxN).renameGroup(nSubId, nGid, nName);
                    LogUtil.i(TAG, "DEBUG RCS PLAINRENAME " + nName.length() + "ch → " + renamed);
                }
            }, "rcs-plain-rename").start();
            return;
        }

        // Default send path: the only code here that needs both `to` and `body`.
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

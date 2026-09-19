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

import org.lineageos.rcs.provider.RcsGroupInfo;
import org.lineageos.rcs.provider.RcsSendResult;

import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Debug-only driver for group create, group send and group lookups through the provider, bypassing
 * the compose UI. Ignored unless the build is debuggable. The blocking binder calls run off the
 * main thread under {@link #goAsync()}.
 *
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.CREATE_GROUP \
 *     -n com.android.messaging/.rcs.RcsDebugGroupReceiver \
 *     --es members &lt;e164,...&gt; [--es name x]
 *
 *   adb shell am broadcast -a com.android.messaging.debug.SEND_GROUP \
 *     -n com.android.messaging/.rcs.RcsDebugGroupReceiver --es gid &lt;32hex&gt; --es body hi
 *
 *   adb shell am broadcast -a com.android.messaging.debug.GROUP_INFO \
 *     -n com.android.messaging/.rcs.RcsDebugGroupReceiver --es gid &lt;32hex&gt;
 * </pre>
 */
public final class RcsDebugGroupReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    static final String ACTION_CREATE_GROUP = "com.android.messaging.debug.CREATE_GROUP";
    static final String ACTION_SEND_GROUP = "com.android.messaging.debug.SEND_GROUP";
    /**
     * Read-only lookup of one group id: {@code getGroupInfo} (does the service know the group) plus
     * {@code getGroupIds} (does it list us as a member). Together they tell a group the service has
     * no record of from one whose membership is missing, without mutating anything.
     */
    static final String ACTION_GROUP_INFO = "com.android.messaging.debug.GROUP_INFO";
    private static final int GROUP_TYPE_DEFAULT = 0; // default group type, omitted on the wire

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = intent.getAction();
        if (!ACTION_CREATE_GROUP.equals(action) && !ACTION_SEND_GROUP.equals(action)
                && !ACTION_GROUP_INFO.equals(action)) {
            return;
        }
        final boolean debuggable = RcsDebug.isDebugBuild();
        if (!debuggable) {
            LogUtil.w(TAG, "DEBUG group receiver ignored: build is not debuggable");
            return;
        }

        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultDataSubscriptionId();
        }
        final int sub = subId;
        final Context appCtx = context.getApplicationContext();
        final PendingResult pr = goAsync();

        new Thread(() -> {
            try {
                final ProviderTransport transport = ProviderTransport.getInstance(appCtx);
                if (ACTION_GROUP_INFO.equals(action)) {
                    final String gid = intent.getStringExtra("gid");
                    if (TextUtils.isEmpty(gid)) {
                        LogUtil.w(TAG, "DEBUG GROUP_INFO missing 'gid'; ignoring");
                        return;
                    }
                    LogUtil.i(TAG, "DEBUG GROUP_INFO subId=" + sub + " gid=" + gid
                            + " -> Group/GetGroupInfo (a READ; nothing is mutated)");
                    final RcsGroupInfo gi = transport.getGroupInfo(sub, gid);
                    // getGroupIds is membership-scoped (it carries no group id), so it answers a
                    // different question from the per-id read above.
                    final List<String> mine = transport.getGroupIds(sub);
                    final boolean listed = mine != null && mine.contains(gid);
                    if (mine == null) {
                        LogUtil.w(TAG, "DEBUG GROUP_INFO: the membership query FAILED or is "
                                + "unavailable (provider older than v49). Existence is still "
                                + "answered below; the 2x2 collapses to one bit.");
                    } else {
                        LogUtil.i(TAG, "DEBUG GROUP_INFO: the service associates " + mine.size()
                                + " group(s) with us; " + gid + " is "
                                + (listed ? "LISTED" : "NOT listed"));
                        // The list also shows every group the service associates with us.
                        for (final String m : mine) LogUtil.i(TAG, "  member-of: " + m);
                    }
                    // Name the combined verdict.
                    if (gi != null && listed) {
                        LogUtil.i(TAG, "DEBUG GROUP_INFO VERDICT: EXISTS + LISTED — the service "
                                + "knows this group and calls it ours. PROVENANCE IS OUT; the "
                                + "request header builder is the last candidate standing.");
                    } else if (gi != null) {
                        LogUtil.w(TAG, "DEBUG GROUP_INFO VERDICT: EXISTS but NOT LISTED — the "
                                + "joined-group MEMBERSHIP gap in its purest form. The missing step "
                                + "is whatever establishes membership, not creation.");
                    } else if (mine != null && !listed) {
                        LogUtil.w(TAG, "DEBUG GROUP_INFO VERDICT: NO RECORD (absent from both) — "
                                + "provenance CONFIRMED. No AddGroupUsers field will ever help.");
                    } else if (listed) {
                        LogUtil.e(TAG, "DEBUG GROUP_INFO VERDICT: LISTED but NOT READABLE — "
                                + "contradictory. Worth reporting.");
                    }
                    if (gi == null) {
                        // null covers both "not found" and "not permitted" (and an unbound
                        // provider); the provider's log line has the actual status.
                        LogUtil.w(TAG, "DEBUG GROUP_INFO result=NULL for " + gid
                                + " — if the provider logged NOT_FOUND, the Group service has no "
                                + "record of this id and NO AddGroupUsers field will ever help "
                                + "(the provenance candidate CONFIRMED). Check the provider's "
                                + "'getGroupInfo(<id>) -> ' line for the actual status before "
                                + "concluding anything: null also covers an unbound provider.");
                    } else {
                        LogUtil.i(TAG, "DEBUG GROUP_INFO result groupId=" + gi.groupId
                                + " name=" + (gi.name == null ? "<none>" : gi.name.length() + "ch")
                                + " members=" + gi.members
                                + " confUri=" + gi.conferenceUri
                                + " — the service KNOWS this group, so provenance is OUT and "
                                + "the request header builder comes back to the front.");
                    }
                } else if (ACTION_CREATE_GROUP.equals(action)) {
                    final String membersCsv = intent.getStringExtra("members");
                    final String name = intent.getStringExtra("name");
                    if (TextUtils.isEmpty(membersCsv)) {
                        LogUtil.w(TAG, "DEBUG CREATE_GROUP missing 'members' csv; ignoring");
                        return;
                    }
                    final List<String> members = new ArrayList<>();
                    for (final String m : membersCsv.split(",")) {
                        final String t = m.trim();
                        if (!t.isEmpty()) {
                            members.add(t);
                        }
                    }
                    LogUtil.i(TAG, "DEBUG CREATE_GROUP subId=" + sub
                            + " name=" + (name == null ? "<unset>" : name.length() + "ch")
                            + " members=" + members + " -> ProviderTransport.createGroup");
                    // desiredGroupId=null: the provider mints the 32-hex id; the server echoes it.
                    final RcsGroupInfo gi = transport.createGroup(
                            sub, null, name, members, GROUP_TYPE_DEFAULT);
                    if (gi == null) {
                        LogUtil.w(TAG, "DEBUG CREATE_GROUP result=NULL (unbound or failed)");
                    } else {
                        LogUtil.i(TAG, "DEBUG CREATE_GROUP result groupId=" + gi.groupId
                                + " name=" + (gi.name == null ? "<none>" : gi.name.length() + "ch")
                                + " members=" + gi.members
                                + " confUri=" + gi.conferenceUri);
                    }
                } else { // SEND_GROUP
                    final String gid = intent.getStringExtra("gid");
                    final String body = intent.getStringExtra("body");
                    if (TextUtils.isEmpty(gid) || body == null) {
                        LogUtil.w(TAG, "DEBUG SEND_GROUP missing 'gid' or 'body'; ignoring");
                        return;
                    }
                    final String clientMessageId = UUID.randomUUID().toString();
                    LogUtil.i(TAG, "DEBUG SEND_GROUP subId=" + sub + " gid=" + gid
                            + " body=" + body.length() + "ch clientMessageId=" + clientMessageId
                            + " -> ProviderTransport.sendGroupMessage");
                    final RcsSendResult r =
                            transport.sendGroupMessage(sub, gid, body, clientMessageId);
                    LogUtil.i(TAG, "DEBUG SEND_GROUP result accepted=" + r.accepted
                            + " reasonCode=" + r.reasonCode + " reason=" + r.reason
                            + " clientMessageId=" + clientMessageId);
                }
            } catch (final Throwable t) {
                LogUtil.e(TAG, "DEBUG group receiver failed", t);
            } finally {
                pr.finish();
            }
        }, "rcs-debug-group").start();
    }
}

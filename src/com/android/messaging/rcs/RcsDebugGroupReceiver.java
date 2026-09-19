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

import org.lineageos.rcs.provider.RcsGroupInfo;
import org.lineageos.rcs.provider.RcsSendResult;

import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Debug-only trigger for the FLOW-4b group harness: drives CreateGroup +
 * group SendMessage straight through the provider seam, bypassing the compose
 * UI, so the inbound-group wire can be confirmed via {@code adb}.
 *
 * <p>Exported but inert on a non-debuggable build (same gate as
 * {@link RcsDebugSendReceiver}). Runs the blocking binder calls off the main
 * thread via {@link #goAsync()}.
 *
 * <pre>
 *   # A creates a group including B (group_id minted by the provider, echoed by server):
 *   adb -s A shell am broadcast \
 *     -a com.android.messaging.debug.CREATE_GROUP \
 *     -n com.android.messaging/.rcs.RcsDebugGroupReceiver \
 *     --es members "+15715550107" [--es name "test"]
 *   # -> grep "DEBUG CREATE_GROUP result groupId=" on A
 *
 *   # A sends a message into that group:
 *   adb -s A shell am broadcast \
 *     -a com.android.messaging.debug.SEND_GROUP \
 *     -n com.android.messaging/.rcs.RcsDebugGroupReceiver \
 *     --es gid "&lt;32hex&gt;" --es body "hi group"
 *   # -> on B: grep "INBOUND" to read to_id / groupId
 * </pre>
 */
public final class RcsDebugGroupReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    static final String ACTION_CREATE_GROUP = "com.android.messaging.debug.CREATE_GROUP";
    static final String ACTION_SEND_GROUP = "com.android.messaging.debug.SEND_GROUP";
    /**
     * A READ against the Group service for one id — the provenance discriminator.
     *
     * <p>{@code GetGroupInfo} is a SIBLING RPC of {@code AddGroupUsers} on the same stub: the stub
     * carries the whole lifecycle — Create/Add/Kick/Delete/ChangeProfile/
     * ChangeMemberRole/GetInfo/GetIds/InviteLink/JoinViaLink. So a read on the same service and the
     * same subject separates <b>"the service does not know this group"</b> from <b>"the service
     * knows it and refuses this mutation"</b> — and does it <b>without mutating anything</b>.
     *
     * <p>That matters because we have now ruled out every field INSIDE the AddGroupUsers request,
     * and a failure invariant to all of them is the shape of a request whose SUBJECT the service has
     * no record of. One call can retire the whole candidate.
     */
    static final String ACTION_GROUP_INFO = "com.android.messaging.debug.GROUP_INFO";
    private static final int GROUP_TYPE_DEFAULT = 0; // dpod DEFAULT_TYPE (omitted on wire)

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
        final boolean debuggable =
                "eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE);
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
                    // The read-only provenance discriminator. Run it against the EXACT
                    // id that AddGroupUsers is refused on.
                    final String gid = intent.getStringExtra("gid");
                    if (TextUtils.isEmpty(gid)) {
                        LogUtil.w(TAG, "DEBUG GROUP_INFO missing 'gid'; ignoring");
                        return;
                    }
                    LogUtil.i(TAG, "DEBUG GROUP_INFO subId=" + sub + " gid=" + gid
                            + " -> Group/GetGroupInfo (a READ; nothing is mutated)");
                    final RcsGroupInfo gi = transport.getGroupInfo(sub, gid);
                    // THE MEMBERSHIP HALF (contract v49). GetGroupIds carries
                    // ONLY a header — no group id — so it is membership-scoped by construction, and
                    // it answers a different question from the per-id read above. Together they are
                    // a 2x2 rather than one bit.
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
                        // The useful accident: this set also reconstructs provenance that was
                        // never recorded — diff it against any id whose origin is in doubt.
                        for (final String m : mine) LogUtil.i(TAG, "  member-of: " + m);
                    }
                    // THE 2x2, named so the result is not re-derived later.
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
                        // Read the provider's own log line for the gRPC status — NOT_FOUND and
                        // PERMISSION_DENIED mean different things here and null flattens them.
                        LogUtil.w(TAG, "DEBUG GROUP_INFO result=NULL for " + gid
                                + " — if the provider logged NOT_FOUND, the Group service has no "
                                + "record of this id and NO AddGroupUsers field will ever help "
                                + "(the provenance candidate CONFIRMED). Check the provider's "
                                + "'getGroupInfo(<id>) -> ' line for the actual status before "
                                + "concluding anything: null also covers an unbound provider.");
                    } else {
                        LogUtil.i(TAG, "DEBUG GROUP_INFO result groupId=" + gi.groupId
                                + " name=" + gi.name + " members=" + gi.members
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
                    LogUtil.i(TAG, "DEBUG CREATE_GROUP subId=" + sub + " name=" + name
                            + " members=" + members + " -> ProviderTransport.createGroup");
                    // desiredGroupId=null -> provider mints the 32-hex id; server echoes it.
                    final RcsGroupInfo gi = transport.createGroup(
                            sub, null, name, members, GROUP_TYPE_DEFAULT);
                    if (gi == null) {
                        LogUtil.w(TAG, "DEBUG CREATE_GROUP result=NULL (unbound or failed)");
                    } else {
                        LogUtil.i(TAG, "DEBUG CREATE_GROUP result groupId=" + gi.groupId
                                + " name=" + gi.name + " members=" + gi.members
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
                            + " body=" + body + " clientMessageId=" + clientMessageId
                            + " -> ProviderTransport.sendGroupMessage");
                    final RcsSendResult r = transport.sendGroupMessage(sub, gid, body, clientMessageId);
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

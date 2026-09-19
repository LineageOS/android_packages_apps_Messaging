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

/**
 * Shared constants for the main-app RCS seam: the additive messages-table
 * column names (mirror the DatabaseHelper.MessageColumns additions) and the
 * internal broadcast actions ProviderTransport fires for OTP / typing.
 *
 * <p>The column-name string literals are duplicated from
 * {@code DatabaseHelper.MessageColumns} on purpose -- the DAO
 * ({@link RcsMessageStore}) imports the canonical constants from there; this
 * class only carries the broadcast contract plus the {@code transport_type}
 * enum so non-DB callers (renderer, RouteSelector) need not pull in
 * DatabaseHelper.
 */
public final class RcsConstants {
    private RcsConstants() {}

    // transport_type column values.
    public static final int TRANSPORT_DEFAULT = 0; // SMS / MMS (by protocol)
    public static final int TRANSPORT_RCS = 1;
    // WAVE-B: a group lifecycle event (created / member added / removed / left /
    // renamed). Stored SMS-shaped like a normal RCS row but rendered as a
    // centered, muted, no-avatar, no-bubble status line (NOT a sender bubble)
    // and excluded from message clustering. See ConversationMessageView
    // .setSystemMessageStyle and ConversationMessageData.getIsRcsSystem.
    public static final int TRANSPORT_RCS_SYSTEM = 2;

    // rcs_status column values: stored verbatim from
    // IRcsProviderCallback.STATUS_*; 0 == none yet.
    public static final int RCS_STATUS_NONE = 0;

    // Local-only sentinel (NOT an IRcsProviderCallback.STATUS_*): an inbound RCS
    // media row whose THUMBNAIL was eagerly downloaded but whose full FILE awaits
    // an explicit user accept. Kept far above the 1-4 STATUS_* codes.
    // Tapping the thumbnail triggers acceptIncomingFile; on file re-delivery the
    // row's part URI is updated in place and the status flips to STATUS_DELIVERED.
    public static final int RCS_FILE_PENDING = 100;

    // ---- RCS provider bind/discovery surface (multi-transport framework) ----
    /**
     * Action the external RCS provider service advertises (and the framework
     * enumerates via {@code PackageManager.queryIntentServices}) to discover
     * bindable providers. Single source of truth -- {@link ProviderTransport},
     * {@link ProviderRegistry}, {@link BoundProviderTransport} and the manifest
     * {@code <queries>}/{@code <intent-filter>} all reference this string.
     */
    public static final String ACTION_BIND_RCS_PROVIDER =
            "org.lineageos.rcs.provider.action.BIND_RCS_PROVIDER";
    /** Permission gating the bind + the reverse wake broadcast (signature|privileged). */
    public static final String PERMISSION_BIND_RCS_PROVIDER =
            "org.lineageos.rcs.permission.BIND_RCS_PROVIDER";
    /** Reverse-wake action a provider fires to cold-start the main app for a held inbound. */
    public static final String ACTION_WAKE_FOR_INBOUND =
            "org.lineageos.rcs.provider.action.WAKE_FOR_INBOUND";
    /** Optional provider diagnostic activity (opened from RCS settings). */
    public static final String ACTION_TRANSPORT_STATUS =
            "org.lineageos.rcs.provider.action.TRANSPORT_STATUS";

    // Internal broadcasts (package-scoped via setPackage()).
    /** Broadcast (in-package) when the generic E2EE state changed; carries EXTRA_SUB_ID.
     *  An open settings screen re-reads ProviderTransport.getE2eeInfo() to refresh its toggle. */
    public static final String ACTION_E2EE_STATE_CHANGED =
            "com.android.messaging.rcs.action.E2EE_STATE_CHANGED";
    public static final String ACTION_OTP_REQUIRED =
            "com.android.messaging.rcs.action.OTP_REQUIRED";
    public static final String ACTION_TYPING =
            "com.android.messaging.rcs.action.TYPING";
    // WAVE-D: group typing (an im-iscomposing fanned out to a GROUP_ID). Carries
    // EXTRA_SUB_ID + EXTRA_GROUP_ID + EXTRA_FROM_URI (the member sender) +
    // EXTRA_TYPING_ACTIVE.
    public static final String ACTION_GROUP_TYPING =
            "com.android.messaging.rcs.action.GROUP_TYPING";

    /**
     * Fired by {@link ProviderTransport} when the provider reports
     * {@code onCarrierTosStateChanged(TOS_REQUIRED)}: the carrier/Google RCS
     * Terms-of-Service gate is blocked on user consent. Package-scoped; the
     * extras carry the {@link org.lineageos.rcs.provider.RcsTosPrompt} fields so the
     * receiver can show the dialog (or post a notification) without re-crossing
     * the binder.
     */
    public static final String ACTION_CARRIER_TOS_REQUIRED =
            "com.android.messaging.rcs.action.CARRIER_TOS_REQUIRED";

    public static final String EXTRA_SUB_ID = "sub_id";
    public static final String EXTRA_OTP_HINT = "otp_hint";
    public static final String EXTRA_FROM_URI = "from_uri";
    public static final String EXTRA_TYPING_ACTIVE = "typing_active";
    // WAVE-D: GROUP_ID carried by ACTION_GROUP_TYPING.
    public static final String EXTRA_GROUP_ID = "group_id";

    // ---- carrier-ToS broadcast extras (mirror RcsTosPrompt fields) ----
    public static final String EXTRA_TOS_SUB_ID = "tos_sub_id";
    public static final String EXTRA_TOS_TITLE = "tos_title";
    public static final String EXTRA_TOS_BODY = "tos_body";
    public static final String EXTRA_TOS_ACCEPT_LABEL = "tos_accept_label";
    public static final String EXTRA_TOS_REJECT_LABEL = "tos_reject_label";
    public static final String EXTRA_TOS_KIND = "tos_kind";

    /**
     * RBM: sentinel stored in {@code rcs_message_id} on a
     * suggestion-reply echo bubble ({@link
     * com.android.messaging.datamodel.action.InsertRbmPostbackEchoAction}) so the
     * renderer can style it as a "Selected option" rather than a normal sent
     * message. Not a real wire id (a bot raises no IMDN for a postback), so it
     * never collides with an inbound IMDN correlation.
     */
    public static final String RBM_POSTBACK_ECHO_MARKER = "rcs:rbm-postback-echo";
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

/** Shared RCS constants: column values, provider bind actions, and package-scoped broadcasts. */
public final class RcsConstants {
    private RcsConstants() {}

    // transport_type column values.
    public static final int TRANSPORT_DEFAULT = 0; // SMS / MMS (by protocol)
    public static final int TRANSPORT_RCS = 1;
    // A group status line: stored as a received row, drawn as a centred, muted line with no avatar
    // or bubble, and excluded from clustering.
    public static final int TRANSPORT_RCS_SYSTEM = 2;

    // rcs_status column values: IRcsProviderCallback.STATUS_* verbatim; 0 == none yet.
    public static final int RCS_STATUS_NONE = 0;

    // Local-only: an inbound file whose thumbnail arrived and whose body awaits the user's accept.
    // Far above the STATUS_* codes; the accepted delivery moves the row to STATUS_DELIVERED.
    public static final int RCS_FILE_PENDING = 100;

    /** The action a provider service resolves for discovery and binding (also in the manifest). */
    public static final String ACTION_BIND_RCS_PROVIDER =
            "org.lineageos.rcs.provider.action.BIND_RCS_PROVIDER";

    // Internal broadcasts (package-scoped via setPackage()).
    /** E2EE state changed; carries EXTRA_SUB_ID. */
    public static final String ACTION_E2EE_STATE_CHANGED =
            "com.android.messaging.rcs.action.E2EE_STATE_CHANGED";
    public static final String ACTION_OTP_REQUIRED =
            "com.android.messaging.rcs.action.OTP_REQUIRED";
    public static final String ACTION_TYPING =
            "com.android.messaging.rcs.action.TYPING";
    // Group typing; carries EXTRA_SUB_ID, EXTRA_GROUP_ID, EXTRA_FROM_URI and EXTRA_TYPING_ACTIVE.
    public static final String ACTION_GROUP_TYPING =
            "com.android.messaging.rcs.action.GROUP_TYPING";

    /** The provider needs the user's ToS consent; the extras carry the RcsTosPrompt fields. */
    public static final String ACTION_CARRIER_TOS_REQUIRED =
            "com.android.messaging.rcs.action.CARRIER_TOS_REQUIRED";

    public static final String EXTRA_SUB_ID = "sub_id";
    public static final String EXTRA_OTP_HINT = "otp_hint";
    public static final String EXTRA_FROM_URI = "from_uri";
    public static final String EXTRA_TYPING_ACTIVE = "typing_active";
    public static final String EXTRA_GROUP_ID = "group_id";

    // Carrier ToS extras, mirroring the RcsTosPrompt fields.
    public static final String EXTRA_TOS_SUB_ID = "tos_sub_id";
    public static final String EXTRA_TOS_TITLE = "tos_title";
    public static final String EXTRA_TOS_BODY = "tos_body";
    public static final String EXTRA_TOS_ACCEPT_LABEL = "tos_accept_label";
    public static final String EXTRA_TOS_REJECT_LABEL = "tos_reject_label";
    public static final String EXTRA_TOS_KIND = "tos_kind";

    /** {@code rcs_message_id} of a suggestion-reply echo row; not a wire id. */
    public static final String RBM_POSTBACK_ECHO_MARKER = "rcs:rbm-postback-echo";
}

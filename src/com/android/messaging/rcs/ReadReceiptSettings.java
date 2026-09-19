/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.messaging.rcs;

import android.text.TextUtils;

import com.android.messaging.util.BuglePrefs;

/**
 * Whether to send read receipts (the displayed IMDN) for a conversation. A global switch (default
 * on) and a per-conversation override that wins in both directions. Suppression is client-side:
 * {@code MarkAsReadAction} simply does not request the receipt. Delivery receipts are not affected.
 */
public final class ReadReceiptSettings {

    /** Global default for read receipts; the settings switch binds to it (preferences_rcs.xml). */
    public static final String PREF_KEY_GLOBAL = "rcs_send_read_receipts";

    /** The global switch is on by default. */
    public static final boolean DEFAULT_GLOBAL = true;

    /** Per-conversation override prefix; full key is prefix + conversationId. */
    private static final String PREF_KEY_OVERRIDE_PREFIX = "rcs_read_receipt_override_";

    /** Per-conversation override: fall through to the global default. */
    public static final int OVERRIDE_DEFAULT = 0;
    /** Per-conversation override: always send read receipts in this thread. */
    public static final int OVERRIDE_ON = 1;
    /** Per-conversation override: never send read receipts in this thread. */
    public static final int OVERRIDE_OFF = 2;

    private ReadReceiptSettings() {
    }

    private static BuglePrefs prefs() {
        return BuglePrefs.getApplicationPrefs();
    }

    private static String overrideKey(final String conversationId) {
        return PREF_KEY_OVERRIDE_PREFIX + conversationId;
    }

    /** The global default (true = read receipts on). */
    public static boolean getGlobal() {
        return prefs().getBoolean(PREF_KEY_GLOBAL, DEFAULT_GLOBAL);
    }

    /** The per-conversation override, or {@link #OVERRIDE_DEFAULT} if none is stored. */
    public static int getThreadOverride(final String conversationId) {
        if (TextUtils.isEmpty(conversationId)) {
            return OVERRIDE_DEFAULT;
        }
        return prefs().getInt(overrideKey(conversationId), OVERRIDE_DEFAULT);
    }

    /** Store a per-conversation override; {@link #OVERRIDE_DEFAULT} clears it. */
    public static void setThreadOverride(final String conversationId, final int triState) {
        if (TextUtils.isEmpty(conversationId)) {
            return;
        }
        if (triState == OVERRIDE_DEFAULT) {
            clearThreadOverride(conversationId);
            return;
        }
        prefs().putInt(overrideKey(conversationId), triState);
    }

    /** Remove any per-conversation override (revert to global default). */
    public static void clearThreadOverride(final String conversationId) {
        if (TextUtils.isEmpty(conversationId)) {
            return;
        }
        prefs().remove(overrideKey(conversationId));
    }

    /** Whether to send read receipts for {@code conversationId}. */
    public static boolean resolve(final String conversationId) {
        switch (getThreadOverride(conversationId)) {
            case OVERRIDE_ON:
                return true;
            case OVERRIDE_OFF:
                return false;
            case OVERRIDE_DEFAULT:
            default:
                return getGlobal();
        }
    }
}

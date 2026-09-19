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

import android.text.TextUtils;

import com.android.messaging.util.BuglePrefs;

/**
 * Resolve + store helper for the RCS read-receipt (DISPLAYED IMDN) control.
 *
 * <p>The DISPLAYED IMDN <em>is</em> the RCS read receipt. It is sent over the
 * existing {@code Messaging.SendMessage} RPC, content-type
 * {@code message/imdn+xml}, by the provider on request. This class governs
 * whether messaging2 issues that request at all — a purely client-side
 * suppression. There is <b>no wire/proto/AIDL change</b>: when the resolved
 * value is OFF for a thread, {@code MarkAsReadAction} simply never calls
 * {@code ProviderTransport.sendImdn(DISPLAYED)} for that thread's rows.
 *
 * <p>The DELIVERED IMDN (delivery receipt) is on a separate path
 * ({@code ReceiveRcsMessageAction}) and is <b>never</b> gated by this class.
 *
 * <h3>Resolution</h3>
 * <ul>
 *   <li>A <b>global default</b> (boolean, default ON) stored under
 *       {@link #PREF_KEY_GLOBAL} in the app-wide {@code "bugle"} prefs file.
 *       This is the same key a {@code SwitchPreferenceCompat} persists to, so
 *       the settings switch needs no custom write logic.</li>
 *   <li>A <b>per-conversation tri-state override</b>
 *       ({@link #OVERRIDE_DEFAULT}/{@link #OVERRIDE_ON}/{@link #OVERRIDE_OFF})
 *       keyed by conversationId. The override <b>wins in both directions</b>: a
 *       globally-ON user can silence one thread; a globally-OFF user can enable
 *       one thread.</li>
 * </ul>
 *
 * <p>{@link #resolve(String)}: override ON &rarr; send; override OFF &rarr;
 * suppress; else &rarr; global default.
 */
public final class ReadReceiptSettings {

    /**
     * App-wide global default for sending RCS read receipts. Persisted to the
     * {@code "bugle"} prefs file. The RCS-settings {@code SwitchPreferenceCompat}
     * binds directly to this key (must match
     * {@code res/xml/preferences_rcs.xml}).
     */
    public static final String PREF_KEY_GLOBAL = "rcs_send_read_receipts";

    /** Out-of-the-box default for the global switch — ON, mirroring Google Messages. */
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

    /** Set the global default. */
    public static void setGlobal(final boolean enabled) {
        prefs().putBoolean(PREF_KEY_GLOBAL, enabled);
    }

    /**
     * The per-conversation override tri-state, or {@link #OVERRIDE_DEFAULT}
     * when none is stored (or the id is empty).
     */
    public static int getThreadOverride(final String conversationId) {
        if (TextUtils.isEmpty(conversationId)) {
            return OVERRIDE_DEFAULT;
        }
        return prefs().getInt(overrideKey(conversationId), OVERRIDE_DEFAULT);
    }

    /**
     * Store a per-conversation override. Passing {@link #OVERRIDE_DEFAULT}
     * clears it (falls back to global).
     */
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

    /**
     * Resolve whether DISPLAYED (read) receipts should be sent for
     * {@code conversationId}. The per-conversation override wins in both
     * directions; otherwise the global default applies.
     */
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

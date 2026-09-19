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

import com.android.messaging.util.BuglePrefs;

/**
 * Resolve/store helper for the user-facing RCS feature toggles shown on the RCS
 * chats settings screen. Both values live in the app-wide
 * {@code "bugle"} prefs file so the {@code SwitchPreferenceCompat} rows persist
 * directly to these keys with no custom write logic — same convention as
 * {@link ReadReceiptSettings#PREF_KEY_GLOBAL}.
 *
 * <ul>
 *   <li>{@link #PREF_KEY_RCS_ENABLED}: master enable for RCS chats (default ON).
 *       {@link RouteSelector#isRcsAvailableForSub} consults this, so when OFF the
 *       1:1 and group send paths fall through to SMS/MMS. Registration/transport
 *       state is left intact (status still reflects real connectivity).</li>
 *   <li>{@link #PREF_KEY_TYPING_INDICATORS}: send + show typing indicators
 *       (default ON). When OFF, {@code ComposeMessageView} never emits typing and
 *       inbound typing is suppressed at the dispatch point.</li>
 * </ul>
 */
public final class RcsFeatureSettings {

    /** Master enable/disable for RCS chats. Must match preferences_rcs.xml. */
    public static final String PREF_KEY_RCS_ENABLED = "rcs_enabled";

    /** Send + show typing indicators. Must match preferences_rcs.xml. */
    public static final String PREF_KEY_TYPING_INDICATORS = "rcs_typing_indicators";

    /** Out-of-the-box defaults — both ON, mirroring prior (always-on) behavior. */
    public static final boolean DEFAULT_RCS_ENABLED = true;
    public static final boolean DEFAULT_TYPING_INDICATORS = true;

    private RcsFeatureSettings() {
    }

    private static BuglePrefs prefs() {
        return BuglePrefs.getApplicationPrefs();
    }

    /** Whether the user has RCS chats enabled (true by default). */
    public static boolean isRcsEnabled() {
        return prefs().getBoolean(PREF_KEY_RCS_ENABLED, DEFAULT_RCS_ENABLED);
    }

    /** Set the master RCS-enabled flag. */
    public static void setRcsEnabled(final boolean enabled) {
        prefs().putBoolean(PREF_KEY_RCS_ENABLED, enabled);
    }

    /** Whether typing indicators should be sent + shown (true by default). */
    public static boolean isTypingIndicatorsEnabled() {
        return prefs().getBoolean(PREF_KEY_TYPING_INDICATORS, DEFAULT_TYPING_INDICATORS);
    }

    /** Set the typing-indicators flag. */
    public static void setTypingIndicatorsEnabled(final boolean enabled) {
        prefs().putBoolean(PREF_KEY_TYPING_INDICATORS, enabled);
    }
}

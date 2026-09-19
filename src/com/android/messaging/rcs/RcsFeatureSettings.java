/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.messaging.rcs;

import com.android.messaging.util.BuglePrefs;

/**
 * The RCS feature toggles on the RCS settings screen, stored in the app-wide prefs so the switch
 * rows write the keys directly. Turning RCS off makes {@link RouteSelector#isRcsAvailableForSub}
 * false, so sends fall through to SMS/MMS; registration state is untouched. Turning typing
 * indicators off stops both sending and showing them.
 */
public final class RcsFeatureSettings {

    /** Master enable/disable for RCS chats. Must match preferences_rcs.xml. */
    public static final String PREF_KEY_RCS_ENABLED = "rcs_enabled";

    /** Send + show typing indicators. Must match preferences_rcs.xml. */
    public static final String PREF_KEY_TYPING_INDICATORS = "rcs_typing_indicators";

    /** Both on by default. */
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

    /** Whether typing indicators should be sent + shown (true by default). */
    public static boolean isTypingIndicatorsEnabled() {
        return prefs().getBoolean(PREF_KEY_TYPING_INDICATORS, DEFAULT_TYPING_INDICATORS);
    }
}

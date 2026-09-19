/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.os.SystemProperties;

/** The one app-side test for a debug build. The engine's is {@code MlsSysProps.debuggableBuild}. */
public final class RcsDebug {

    private RcsDebug() {}

    /**
     * True on an eng or userdebug build ({@code ro.debuggable=1}). Every diagnostic that logs
     * content or relaxes a check needs it as well as its own {@code debug.*} knob: adb can set a
     * {@code debug.*} property on a user build, but not an {@code ro.*} one. {@code
     * Build.IS_DEBUGGABLE} reads the same property but is hidden from {@code system_current}.
     */
    public static boolean isDebugBuild() {
        return SystemProperties.getInt("ro.debuggable", 0) == 1;
    }
}

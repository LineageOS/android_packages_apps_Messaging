/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Read-only system properties, so decision code gated on a {@code debug.rcs.*} knob needs no
 * {@code android.os} dependency. On device each call forwards to SystemProperties; a host test
 * answers from a map.
 */
public interface MlsSysProps {
    String get(String key, String def);
    boolean getBoolean(String key, boolean def);
    int getInt(String key, int def);
    long getLong(String key, long def);

    /**
     * True on an eng/userdebug build ({@code ro.debuggable=1}). Required, together with its own
     * knob, by any dump of message content: adb can set a {@code debug.*} prop on a user build but
     * not an {@code ro.*} one.
     */
    default boolean debuggableBuild() {
        return getInt("ro.debuggable", 0) == 1;
    }
}

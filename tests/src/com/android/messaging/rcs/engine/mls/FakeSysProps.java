/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.HashMap;
import java.util.Map;

/**
 * An in-memory {@link MlsSysProps}: unset keys answer their default, as SystemProperties does.
 * Values are held as the strings a property would hold and parsed on read, so a test sets a knob
 * exactly as an operator's {@code setprop} would.
 */
public final class FakeSysProps implements MlsSysProps {
    public final Map<String, String> values = new HashMap<>();

    public FakeSysProps set(final String key, final Object value) {
        values.put(key, String.valueOf(value));
        return this;
    }

    @Override public String get(final String key, final String def) {
        final String v = values.get(key);
        return (v == null || v.isEmpty()) ? def : v;
    }
    @Override public boolean getBoolean(final String key, final boolean def) {
        final String v = values.get(key);
        if (v == null || v.isEmpty()) return def;
        if (v.equals("1") || v.equals("y") || v.equals("yes") || v.equals("on")
                || v.equals("true")) return true;
        if (v.equals("0") || v.equals("n") || v.equals("no") || v.equals("off")
                || v.equals("false")) return false;
        return def;
    }
    @Override public int getInt(final String key, final int def) {
        try {
            return Integer.parseInt(values.getOrDefault(key, ""));
        } catch (final NumberFormatException e) {
            return def;
        }
    }
    @Override public long getLong(final String key, final long def) {
        try {
            return Long.parseLong(values.getOrDefault(key, ""));
        } catch (final NumberFormatException e) {
            return def;
        }
    }
}

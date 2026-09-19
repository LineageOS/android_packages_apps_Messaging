/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Set;

/**
 * The subset of {@code android.content.SharedPreferences} engine code uses, with the same names and
 * semantics; {@link Editor#commit} is a synchronous write. See docs/mls/transport-and-port.md.
 */
public interface MlsPrefs {
    boolean getBoolean(String key, boolean def);
    int getInt(String key, int def);
    long getLong(String key, long def);
    String getString(String key, String def);
    Set<String> getStringSet(String key, Set<String> def);
    boolean contains(String key);
    Editor edit();

    /**
     * A batch of writes, applied together; the same contract as {@code SharedPreferences.Editor}.
     */
    interface Editor {
        Editor putBoolean(String key, boolean value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putString(String key, String value);
        Editor putStringSet(String key, Set<String> value);
        Editor remove(String key);
        /** Write asynchronously. */
        void apply();
        /** Write synchronously; {@code false} if the write did not reach storage. */
        boolean commit();
    }
}

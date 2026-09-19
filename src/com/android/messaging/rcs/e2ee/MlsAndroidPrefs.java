/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.SharedPreferences;

import com.android.messaging.rcs.engine.mls.MlsPrefs;

import java.util.Set;

/** {@link MlsPrefs} over an Android preferences file: every call forwards unchanged. */
final class MlsAndroidPrefs implements MlsPrefs {
    private final SharedPreferences mPrefs;

    private MlsAndroidPrefs(final SharedPreferences prefs) {
        mPrefs = prefs;
    }

    static MlsPrefs wrap(final SharedPreferences prefs) {
        return new MlsAndroidPrefs(prefs);
    }

    @Override public boolean getBoolean(final String key, final boolean def) {
        return mPrefs.getBoolean(key, def);
    }
    @Override public int getInt(final String key, final int def) { return mPrefs.getInt(key, def); }
    @Override public long getLong(final String key, final long def) { return mPrefs.getLong(key,
            def); }
    @Override public String getString(final String key, final String def) {
        return mPrefs.getString(key, def);
    }
    @Override public Set<String> getStringSet(final String key, final Set<String> def) {
        return mPrefs.getStringSet(key, def);
    }
    @Override public boolean contains(final String key) { return mPrefs.contains(key); }
    @Override public MlsPrefs.Editor edit() { return new Editor(mPrefs.edit()); }

    private static final class Editor implements MlsPrefs.Editor {
        private final SharedPreferences.Editor mEdit;

        Editor(final SharedPreferences.Editor edit) {
            mEdit = edit;
        }

        @Override public MlsPrefs.Editor putBoolean(final String key, final boolean value) {
            mEdit.putBoolean(key, value);
            return this;
        }
        @Override public MlsPrefs.Editor putInt(final String key, final int value) {
            mEdit.putInt(key, value);
            return this;
        }
        @Override public MlsPrefs.Editor putLong(final String key, final long value) {
            mEdit.putLong(key, value);
            return this;
        }
        @Override public MlsPrefs.Editor putString(final String key, final String value) {
            mEdit.putString(key, value);
            return this;
        }
        @Override public MlsPrefs.Editor putStringSet(final String key, final Set<String> value) {
            mEdit.putStringSet(key, value);
            return this;
        }
        @Override public MlsPrefs.Editor remove(final String key) {
            mEdit.remove(key);
            return this;
        }
        @Override public void apply() { mEdit.apply(); }
        @Override public boolean commit() { return mEdit.commit(); }
    }
}

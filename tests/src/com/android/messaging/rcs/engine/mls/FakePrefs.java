/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An in-memory {@link MlsPrefs}. {@link #failCommits} makes {@code commit()} report failure (the
 * values still land, as a failed Android commit leaves the in-memory map updated), so a test can
 * drive a caller's "the stamp did not persist" arm. {@link #onDisk} holds only what a successful
 * {@code commit()} wrote: {@code apply()} updates {@link #values} and only schedules the disk
 * write, so a crash can lose it.
 */
public final class FakePrefs implements MlsPrefs {
    public final Map<String, Object> values = new HashMap<>();
    /** What a crash would leave: only successful {@code commit()}s reach it. */
    public final Map<String, Object> onDisk = new HashMap<>();
    public boolean failCommits;

    @Override public boolean getBoolean(final String k, final boolean d) {
        return values.containsKey(k) ? (Boolean) values.get(k) : d;
    }
    @Override public int getInt(final String k, final int d) {
        return values.containsKey(k) ? (Integer) values.get(k) : d;
    }
    @Override public long getLong(final String k, final long d) {
        return values.containsKey(k) ? (Long) values.get(k) : d;
    }
    @Override public String getString(final String k, final String d) {
        return values.containsKey(k) ? (String) values.get(k) : d;
    }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(final String k, final Set<String> d) {
        return values.containsKey(k) ? (Set<String>) values.get(k) : d;
    }
    @Override public boolean contains(final String k) { return values.containsKey(k); }

    /**
     * What {@code remove} records: Android keeps one pending map, so the last put-or-remove wins.
     */
    private static final Object REMOVED = new Object();

    @Override public Editor edit() {
        final Map<String, Object> mods = new HashMap<>();
        return new Editor() {
            @Override public Editor putBoolean(final String k, final boolean v) { mods.put(k,
                    v); return this; }
            @Override public Editor putInt(final String k, final int v) { mods.put(k,
                    v); return this; }
            @Override public Editor putLong(final String k, final long v) { mods.put(k,
                    v); return this; }
            @Override public Editor putString(final String k, final String v) {
                mods.put(k, v == null ? REMOVED : v);
                return this;
            }
            @Override public Editor putStringSet(final String k, final Set<String> v) {
                mods.put(k, v == null ? REMOVED : new HashSet<>(v));
                return this;
            }
            @Override public Editor remove(final String k) { mods.put(k, REMOVED); return this; }
            @Override public void apply() {
                for (final Map.Entry<String, Object> e : mods.entrySet()) {
                    if (e.getValue() == REMOVED) values.remove(e.getKey());
                    else values.put(e.getKey(), e.getValue());
                }
            }
            @Override public boolean commit() {
                apply();
                if (failCommits) return false;
                for (final Map.Entry<String, Object> e : mods.entrySet()) {
                    if (e.getValue() == REMOVED) onDisk.remove(e.getKey());
                    else onDisk.put(e.getKey(), e.getValue());
                }
                return true;
            }
        };
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A peer's advertised MLS feature tags as an open name-to-value map, passed up verbatim; whether
 * the peer is MLS-eligible is the app's decision. A map rather than typed fields because peers
 * advertise different subsets of the tags. See docs/rcs/provider-contract.md.
 */
public final class RcsMlsPeerCaps implements Parcelable {

    /**
     * The lookup failed; says nothing about the peer. Treating it as a negative would disable MLS
     * on a transient failure.
     */
    public static final int LOOKUP_UNKNOWN = 0;
    /** The peer has at least one registration. */
    public static final int LOOKUP_REGISTERED = 1;
    /** The peer has no registrations: the only state a caller may treat as a negative. */
    public static final int LOOKUP_NOT_REGISTERED = 2;

    /** One of LOOKUP_*; kept out of the tag map because it describes the lookup, not the peer. */
    public final int lookupState;

    /**
     * Tag names as advertised, e.g. {@code "+g.gsma.rcs.mls.mls-kds"}. Parallel to {@link #values}.
     */
    @NonNull public final String[] names;
    /** Tag values, positionally matched to {@link #names}. */
    @NonNull public final String[] values;

    public RcsMlsPeerCaps(@Nullable String[] names, @Nullable String[] values) {
        this(names, values, LOOKUP_UNKNOWN);
    }

    /** As above with the lookup state; the two-argument form defaults to the safe UNKNOWN. */
    public RcsMlsPeerCaps(@Nullable String[] names, @Nullable String[] values,
            final int lookupState) {
        this.lookupState = lookupState;
        final String[] n = (names == null) ? new String[0] : names;
        final String[] v = (values == null) ? new String[0] : values;
        // Truncate a length mismatch to the common prefix rather than mis-pair tags.
        final int len = Math.min(n.length, v.length);
        if (n.length == len && v.length == len) {
            this.names = n;
            this.values = v;
        } else {
            this.names = new String[len];
            this.values = new String[len];
            System.arraycopy(n, 0, this.names, 0, len);
            System.arraycopy(v, 0, this.values, 0, len);
        }
    }

    /** Build from a map (provider side), lookup state UNKNOWN. */
    public static RcsMlsPeerCaps fromMap(@Nullable Map<String, String> tags) {
        return fromMap(tags, LOOKUP_UNKNOWN);
    }

    /** Build from a map with the lookup state (provider side). */
    public static RcsMlsPeerCaps fromMap(@Nullable Map<String, String> tags,
            final int lookupState) {
        if (tags == null || tags.isEmpty()) {
            return new RcsMlsPeerCaps(new String[0], new String[0], lookupState);
        }
        final String[] n = new String[tags.size()];
        final String[] v = new String[tags.size()];
        int i = 0;
        for (final Map.Entry<String, String> e : tags.entrySet()) {
            n[i] = e.getKey();
            v[i] = (e.getValue() == null) ? "" : e.getValue();
            i++;
        }
        return new RcsMlsPeerCaps(n, v, lookupState);
    }

    /** Read as a map (app side). Never null; empty when the peer advertised nothing. */
    @NonNull public Map<String, String> asMap() {
        if (names.length == 0) return Collections.emptyMap();
        final Map<String, String> out = new LinkedHashMap<>(names.length);
        for (int i = 0; i < names.length; i++) {
            out.put(names[i], values[i]);
        }
        return out;
    }

    /**
     * True when the peer advertised no MLS tags at all — i.e. nothing is known, not "not capable".
     */
    public boolean isEmpty() {
        return names.length == 0;
    }

    protected RcsMlsPeerCaps(Parcel in) {
        final String[] n = in.createStringArray();
        final String[] v = in.createStringArray();
        this.names = (n == null) ? new String[0] : n;
        this.values = (v == null) ? new String[0] : v;
        this.lookupState = in.dataAvail() > 0 ? in.readInt() : LOOKUP_UNKNOWN;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeStringArray(names);
        dest.writeStringArray(values);
        // Appended last; the reader defaults it when the parcel ends early.
        dest.writeInt(lookupState);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<RcsMlsPeerCaps> CREATOR = new Creator<RcsMlsPeerCaps>() {
        @Override public RcsMlsPeerCaps createFromParcel(Parcel in) { return new RcsMlsPeerCaps(
                in); }
        @Override public RcsMlsPeerCaps[] newArray(int size) { return new RcsMlsPeerCaps[size]; }
    };

    @Override public String toString() {
        final String st = (lookupState == LOOKUP_REGISTERED) ? "REGISTERED"
                : (lookupState == LOOKUP_NOT_REGISTERED) ? "NOT_REGISTERED" : "UNKNOWN";
        return "RcsMlsPeerCaps[" + st + "]" + asMap();
    }
}

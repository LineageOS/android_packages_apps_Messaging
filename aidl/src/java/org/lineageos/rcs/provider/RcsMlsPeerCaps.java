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
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A peer's advertised MLS capability feature-tags, as an <b>open name→value map</b>.
 *
 * <p><b>Why a map and not typed fields.</b> This follows the same doctrine as {@link RcsE2eeInfo},
 * which deliberately refuses to enumerate E2EE schemes so that "a new scheme needs zero AIDL change".
 * The MLS tag set is demonstrably not stable — vendors advertise different subsets, and the subset a
 * given vendor sends changes over time:
 *
 * <ul>
 *   <li><b>Google Messages</b> (observed across two shipping versions): {@code mls-kds},
 *       {@code mls-launch-iteration}, {@code mls-supports-groups} — and <b>no {@code mls-version}</b>, because that tag is gated by
 *       the GMS Phenotype rollout on the advertising side.</li>
 *   <li><b>Apple</b>: {@code mls-version=v1} <i>and</i> {@code mls-kds} — unaffected by Google's rollout.</li>
 *   <li><b>our own reimpl</b>: all four (a superset of both).</li>
 * </ul>
 *
 * Typed fields would have frozen whichever subset we happened to have observed. A map lets the
 * consumer's eligibility policy evolve without a contract bump, which matters because that policy has
 * already been wrong twice in both directions.
 *
 * <p><b>Layering.</b> This carries <i>advertised capability tags</i> only — no Tachyon vocabulary, no
 * provider internals. The provider reads whatever the RCS capability layer exposes and passes it up
 * verbatim; the <b>decision</b> (is this peer MLS-eligible?) belongs to the app, not the transport.
 * A carrier/MSRP provider can populate the same map from its own capability exchange.
 */
public final class RcsMlsPeerCaps implements Parcelable {

    /**
     * The lookup could not be performed or threw. <b>Says nothing about the peer.</b>
     *
     * <p>This value exists because the empty map was previously ambiguous between "we could not
     * ask" and "the peer has no registrations", and this class's own doc warned that a caller "must
     * distinguish the two" without giving it any way to. Anything that treats UNKNOWN as a negative
     * assertion disables MLS on a transient failure.
     */
    public static final int LOOKUP_UNKNOWN = 0;
    /** The lookup succeeded and the peer HAS at least one registration. */
    public static final int LOOKUP_REGISTERED = 1;
    /**
     * The lookup succeeded and the peer has ZERO registrations — no routing target.
     *
     * <p>A positive assertion, and the only one of the three a caller may act on as a negative.
     * This is the condition Google Messages' own upgrade guard calls a DUMMY destination token.
     */
    public static final int LOOKUP_NOT_REGISTERED = 2;

    /**
     * Which of the three above — contract v54.
     *
     * <p>Kept OUT of the tag map deliberately. The map is an open name→value space carrying what the
     * PEER advertised; this is a fact about our LOOKUP, and putting it there would make it
     * indistinguishable from a capability and leak into every consumer that iterates tags.
     */
    public final int lookupState;

    /** Tag names as advertised, e.g. {@code "+g.gsma.rcs.mls.mls-kds"}. Parallel to {@link #values}. */
    @NonNull public final String[] names;
    /** Tag values, positionally matched to {@link #names}. */
    @NonNull public final String[] values;

    public RcsMlsPeerCaps(@Nullable String[] names, @Nullable String[] values) {
        this(names, values, LOOKUP_UNKNOWN);
    }

    /**
     * As above, stating what the LOOKUP did. The two-arg form defaults to {@link #LOOKUP_UNKNOWN},
     * which is the safe direction: an existing caller that does not set it cannot accidentally
     * assert "not registered" and disable MLS.
     */
    public RcsMlsPeerCaps(@Nullable String[] names, @Nullable String[] values,
            final int lookupState) {
        this.lookupState = lookupState;
        final String[] n = (names == null) ? new String[0] : names;
        final String[] v = (values == null) ? new String[0] : values;
        // Defensive: a length mismatch would silently mis-pair tags, which is exactly the kind of
        // quiet wrongness this subsystem has been bitten by. Truncate to the common prefix instead.
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

    /** Build from a map, stating what the lookup did (provider side, contract v54). */
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

    /** True when the peer advertised no MLS tags at all — i.e. nothing is known, not "not capable". */
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
        // APPENDED at the end, and this is a LOCKSTEP change even so: RcsMlsPeerCaps is a
        // hand-rolled Parcelable, so both APKs must be built from the same aidl source. Contract v54.
        dest.writeInt(lookupState);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<RcsMlsPeerCaps> CREATOR = new Creator<RcsMlsPeerCaps>() {
        @Override public RcsMlsPeerCaps createFromParcel(Parcel in) { return new RcsMlsPeerCaps(in); }
        @Override public RcsMlsPeerCaps[] newArray(int size) { return new RcsMlsPeerCaps[size]; }
    };

    @Override public String toString() {
        final String st = (lookupState == LOOKUP_REGISTERED) ? "REGISTERED"
                : (lookupState == LOOKUP_NOT_REGISTERED) ? "NOT_REGISTERED" : "UNKNOWN";
        return "RcsMlsPeerCaps[" + st + "]" + asMap();
    }
}

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

import androidx.annotation.Nullable;

/**
 * Generic end-to-end-encryption state for a subscription — <b>provider-agnostic and
 * scheme-opaque</b>.
 *
 * <p>The contract deliberately does NOT enumerate E2EE schemes. The scheme is an
 * <b>opaque, reverse-DNS-namespaced identifier</b> ({@link #schemeId}) that the
 * implementation chooses, plus a human {@link #schemeLabel}. A consumer that needs to
 * reason about a specific scheme compares the string against IDs IT knows — the
 * interface stays open, so a new scheme (or a different provider/app) needs zero AIDL
 * change. Treat {@code schemeId} like a MIME type: a string, not an enum.
 *
 * <p><b>Layering.</b> E2EE can be provided at two layers, and this struct describes
 * whichever is active for the sub:
 * <ul>
 *   <li><b>Provider/transport-layer</b> — a vendor/carrier-specific impl in the
 *       provider app (e.g. Etouffee, {@code schemeId
 *       "google.etouffee"}). Reported here via {@code IRcsProvider.getE2eeInfo}.</li>
 *   <li><b>App-layer</b> — the generic GSMA RCS E2EE (MLS-based) that lives in the
 *       messaging app itself ({@code schemeId} like {@code "gsma.rcs-e2ee.mls"}),
 *       transport-agnostic. The app composes this with what the provider reports.</li>
 * </ul>
 * The Etouffee↔RCS-E2EE coexistence policy lives above this struct, not in it.
 */
public final class RcsE2eeInfo implements Parcelable {

    /** Provider/app has an E2EE implementation for this sub (show the toggle). */
    public final boolean available;
    /** User-toggle state. Meaningful only when {@link #available}. */
    public final boolean enabled;
    /** Opaque reverse-DNS scheme id (e.g. {@code "google.etouffee"},
     *  {@code "gsma.rcs-e2ee.mls"}); {@code null}/empty when E2EE is off. */
    @Nullable public final String schemeId;
    /** Human label for the scheme (e.g. "Signal Protocol", "RCS E2EE"). */
    @Nullable public final String schemeLabel;

    public RcsE2eeInfo(boolean available, boolean enabled, @Nullable String schemeId,
                       @Nullable String schemeLabel) {
        this.available = available;
        this.enabled = enabled;
        this.schemeId = schemeId;
        this.schemeLabel = schemeLabel;
    }

    protected RcsE2eeInfo(Parcel in) {
        this.available = in.readInt() != 0;
        this.enabled = in.readInt() != 0;
        this.schemeId = in.readString();
        this.schemeLabel = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(available ? 1 : 0);
        dest.writeInt(enabled ? 1 : 0);
        dest.writeString(schemeId);
        dest.writeString(schemeLabel);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsE2eeInfo> CREATOR = new Creator<RcsE2eeInfo>() {
        @Override
        public RcsE2eeInfo createFromParcel(Parcel in) {
            return new RcsE2eeInfo(in);
        }

        @Override
        public RcsE2eeInfo[] newArray(int size) {
            return new RcsE2eeInfo[size];
        }
    };
}

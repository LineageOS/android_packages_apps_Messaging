/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * Encryption state of a line as the provider implements it. The scheme is an opaque reverse-DNS
 * string the implementation chooses, compared like a MIME type, so a new scheme needs no contract
 * change.
 */
public final class RcsE2eeInfo implements Parcelable {

    /** An implementation exists for this line, so the toggle is shown. */
    public final boolean available;
    /** The user toggle; meaningful only when {@link #available}. */
    public final boolean enabled;
    /** Opaque reverse-DNS scheme id; null or empty when encryption is off. */
    @Nullable public final String schemeId;
    /** Human-readable scheme name. */
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

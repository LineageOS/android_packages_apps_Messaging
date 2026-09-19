/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * A line the app asks the provider to provision and register. {@link #msisdn} and {@link #imsi} may
 * be null; a provider that needs them reads them with its own privileges.
 */
public final class RcsSubInfo implements Parcelable {

    public final int subId;
    public final int slotIndex;
    @Nullable public final String msisdn;
    @Nullable public final String imsi;
    /** Concatenated MCC+MNC, e.g. "310260". */
    @Nullable public final String mccMnc;
    /** The app's carrier-policy hint; advisory only. */
    @Nullable public final String carrierPolicy;

    public RcsSubInfo(int subId,
                      int slotIndex,
                      @Nullable String msisdn,
                      @Nullable String imsi,
                      @Nullable String mccMnc,
                      @Nullable String carrierPolicy) {
        this.subId = subId;
        this.slotIndex = slotIndex;
        this.msisdn = msisdn;
        this.imsi = imsi;
        this.mccMnc = mccMnc;
        this.carrierPolicy = carrierPolicy;
    }

    protected RcsSubInfo(Parcel in) {
        this.subId = in.readInt();
        this.slotIndex = in.readInt();
        this.msisdn = in.readString();
        this.imsi = in.readString();
        this.mccMnc = in.readString();
        this.carrierPolicy = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(subId);
        dest.writeInt(slotIndex);
        dest.writeString(msisdn);
        dest.writeString(imsi);
        dest.writeString(mccMnc);
        dest.writeString(carrierPolicy);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsSubInfo> CREATOR = new Creator<RcsSubInfo>() {
        @Override
        public RcsSubInfo createFromParcel(Parcel in) {
            return new RcsSubInfo(in);
        }

        @Override
        public RcsSubInfo[] newArray(int size) {
            return new RcsSubInfo[size];
        }
    };
}

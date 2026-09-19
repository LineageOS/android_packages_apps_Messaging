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
 * Identifies a SIM/subscription the main app asks the provider to provision
 * + register. Flat {@link Parcelable}. msisdn/imsi are nullable because the
 * main app may not have them at startForSub() time (the provider can also
 * resolve them itself via privileged phone state).
 */
public final class RcsSubInfo implements Parcelable {

    public final int subId;
    public final int slotIndex;
    @Nullable public final String msisdn;
    @Nullable public final String imsi;
    /** Concatenated MCC+MNC, e.g. "310260". */
    @Nullable public final String mccMnc;
    /** Main-app carrier-policy hint (e.g. "TMOBILE_US"); advisory only. */
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

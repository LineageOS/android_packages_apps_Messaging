/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * What a provider can do, statically or for one line. Fields are appended at the end of the parcel
 * and read in the order written. See docs/rcs/provider-contract.md.
 */
public final class RcsProviderCaps implements Parcelable {

    // supportedTransports tags.
    public static final int TRANSPORT_TACHYON = 1;
    /** Carrier IMS SIP/MSRP transport. */
    public static final int TRANSPORT_CARRIER_MSRP = 2;

    // featureFlags bits.
    /** 1:1 and group text. */
    public static final int FEATURE_TEXT     = 1 << 0;
    /** Delivered and displayed receipts. */
    public static final int FEATURE_IMDN     = 1 << 1;
    /** Typing indicators. */
    public static final int FEATURE_TYPING   = 1 << 2;
    /** Group management and messaging. */
    public static final int FEATURE_GROUP    = 1 << 3;
    /** File transfer. */
    public static final int FEATURE_FT       = 1 << 4;
    /** Emoji reactions. */
    public static final int FEATURE_REACTION = 1 << 5;
    /** Business messaging. */
    public static final int FEATURE_RBM      = 1 << 6;
    /** Location sharing. */
    public static final int FEATURE_LOCATION = 1 << 7;
    /** Provider-layer end-to-end encryption. */
    public static final int FEATURE_E2EE     = 1 << 8;

    /** Human-readable provider label. */
    @Nullable public final String providerLabel;

    /** Contract version these caps were produced under. */
    public final int contractVersion;

    /** TRANSPORT_* tags this provider implements. */
    @Nullable public final int[] supportedTransports;

    /** Whether the provider provisions the line itself rather than needing credentials. */
    public final boolean canSelfProvision;

    /**
     * Selection priority, higher preferred; 0 is unspecified. Trusted as declared because only
     * providers on the system image are bound.
     */
    public final int priority;

    /** OR of the {@code FEATURE_*} bits supported on this line; 0 = none advertised. */
    public final int featureFlags;

    public RcsProviderCaps(@Nullable String providerLabel,
                           int contractVersion,
                           @Nullable int[] supportedTransports,
                           boolean canSelfProvision,
                           int priority,
                           int featureFlags) {
        this.providerLabel = providerLabel;
        this.contractVersion = contractVersion;
        this.supportedTransports = supportedTransports;
        this.canSelfProvision = canSelfProvision;
        this.priority = priority;
        this.featureFlags = featureFlags;
    }

    /** Defaults {@link #priority} and {@link #featureFlags} to 0. */
    public RcsProviderCaps(@Nullable String providerLabel,
                           int contractVersion,
                           @Nullable int[] supportedTransports,
                           boolean canSelfProvision) {
        this(providerLabel, contractVersion, supportedTransports, canSelfProvision,
                0, 0);
    }

    protected RcsProviderCaps(Parcel in) {
        this.providerLabel = in.readString();
        this.contractVersion = in.readInt();
        this.supportedTransports = in.createIntArray();
        this.canSelfProvision = in.readInt() != 0;
        this.priority = in.readInt();
        this.featureFlags = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(providerLabel);
        dest.writeInt(contractVersion);
        dest.writeIntArray(supportedTransports);
        dest.writeInt(canSelfProvision ? 1 : 0);
        dest.writeInt(priority);
        dest.writeInt(featureFlags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsProviderCaps> CREATOR = new Creator<RcsProviderCaps>() {
        @Override
        public RcsProviderCaps createFromParcel(Parcel in) {
            return new RcsProviderCaps(in);
        }

        @Override
        public RcsProviderCaps[] newArray(int size) {
            return new RcsProviderCaps[size];
        }
    };
}

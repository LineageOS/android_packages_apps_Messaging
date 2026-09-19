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
 * Static + per-sub capability description published by an RCS provider.
 *
 * <p>Flat {@link Parcelable} -- no proto, no SafeParcel. v1 kept fields
 * minimal: provider identity, contract version, the supported transport
 * tags, and a self-provision flag.
 *
 * <p>Contract-v17 (lockstep parcel-layout bump, spec §9.2) appends two
 * trailing fields: {@link #priority} (provider-declared selection priority)
 * and {@link #featureFlags} (a per-transport feature bitmask so a provider
 * can advertise a subset of the v16 surface -- e.g. carrier-MSRP declaring
 * text+IMDN+FT but no RBM/reactions). Both are read/written at the END, after
 * {@code canSelfProvision}; write order == read order (a mismatched
 * createFromParcel is silently broken). Both APKs must ship together.
 */
public final class RcsProviderCaps implements Parcelable {

    // Transport tag values used in supportedTransports.
    public static final int TRANSPORT_TACHYON = 1;
    /** Carrier IMS / SIP-MSRP RCS transport. Contract-v17. */
    public static final int TRANSPORT_CARRIER_MSRP = 2;

    // ---- featureFlags bits (contract-v17) ----
    /** 1:1 + group text messaging. */
    public static final int FEATURE_TEXT     = 1 << 0;
    /** Delivered/displayed IMDN receipts. */
    public static final int FEATURE_IMDN     = 1 << 1;
    /** is-composing typing indicators. */
    public static final int FEATURE_TYPING   = 1 << 2;
    /** Group management + group messaging. */
    public static final int FEATURE_GROUP    = 1 << 3;
    /** File transfer (media). */
    public static final int FEATURE_FT       = 1 << 4;
    /** Emoji reactions (tapbacks). */
    public static final int FEATURE_REACTION = 1 << 5;
    /** RCS Business Messaging (bots). */
    public static final int FEATURE_RBM      = 1 << 6;
    /** Location share (geopush). */
    public static final int FEATURE_LOCATION = 1 << 7;
    /** End-to-end encryption. */
    public static final int FEATURE_E2EE     = 1 << 8;

    /** Human-readable provider label, e.g. "Example RCS". */
    @Nullable public final String providerLabel;

    /** Contract version this caps blob was produced under. */
    public final int contractVersion;

    /** Transport tags this provider implements (e.g. TRANSPORT_TACHYON). */
    @Nullable public final int[] supportedTransports;

    /**
     * Whether the provider can self-provision (run Pev3 bootstrap itself)
     * vs. needing borrowed credentials. The v1 reference provider reports true.
     */
    public final boolean canSelfProvision;

    /**
     * Provider-declared selection priority; higher = preferred. The registry
     * sorts eligible transports by this (desc) with a deterministic tiebreak.
     * Safe without a trust-cap because providers are preinstalled-privileged
     * only (design D3). Contract-v17. 0 = unspecified/lowest.
     */
    public final int priority;

    /**
     * Per-transport feature bitmask: an OR of the {@code FEATURE_*} bits this
     * provider actually supports on this sub. Lets the main app gate UI
     * affordances per-transport instead of assuming the full v16 surface from
     * the contract-version int alone. Contract-v17. 0 = none advertised.
     */
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

    /**
     * Backward-compat overload (pre-v17 call sites). Defaults {@link #priority}
     * and {@link #featureFlags} to 0. Matches the version-ladder-of-constructors
     * idiom used elsewhere in this contract (e.g. RcsIncomingMessage).
     */
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
        // ---- contract-v17 trailing fields (append at END; order == write) ----
        this.priority = in.readInt();
        this.featureFlags = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(providerLabel);
        dest.writeInt(contractVersion);
        dest.writeIntArray(supportedTransports);
        dest.writeInt(canSelfProvision ? 1 : 0);
        // ---- contract-v17 trailing fields (append at END; order == read) ----
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

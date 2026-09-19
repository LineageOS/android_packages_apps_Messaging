/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * The outcome of a KeyPackage claim, in spec-level terms that separate "the peer has none" from
 * "the service refused us". Backend statuses never cross; {@link #detail} is for logs and is never
 * parsed. {@link #OUTCOME_SERVED} says nothing about whether the packages are usable: only their
 * certificate windows can. See docs/rcs/provider-contract.md.
 */
public final class RcsMlsClaimResult implements Parcelable {

    /** At least one package was returned. */
    public static final int OUTCOME_SERVED = 0;
    /** The peer has no packages: the only outcome that is a fact about the peer's pool. */
    public static final int OUTCOME_PEER_HAS_NONE = 1;
    /** The service refused us; says nothing about the peer. */
    public static final int OUTCOME_NOT_AUTHORIZED = 2;
    /** Refused for another stated reason; not evidence that the peer has no packages. */
    public static final int OUTCOME_REFUSED = 3;
    /** The request never completed; not a rejection. */
    public static final int OUTCOME_TRANSPORT_FAILED = 4;
    /** Nothing was sent: no credential, or nothing to ask for. */
    public static final int OUTCOME_NOT_ATTEMPTED = 5;

    public final int outcome;
    /** The KeyPackages as {@code [u32 BE length][bytes]} records; null when none were served. */
    @Nullable public final byte[] keyPackages;
    /** Diagnostic text for logs; branch on {@link #outcome}, never on this. */
    @Nullable public final String detail;

    public RcsMlsClaimResult(int outcome, @Nullable byte[] keyPackages, @Nullable String detail) {
        this.outcome = outcome;
        this.keyPackages = keyPackages;
        this.detail = detail;
    }

    protected RcsMlsClaimResult(Parcel in) {
        outcome = in.readInt();
        keyPackages = in.createByteArray();
        detail = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(outcome);
        dest.writeByteArray(keyPackages);
        dest.writeString(detail);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsMlsClaimResult> CREATOR = new Creator<RcsMlsClaimResult>() {
        @Override public RcsMlsClaimResult createFromParcel(Parcel in) {
            return new RcsMlsClaimResult(in);
        }
        @Override public RcsMlsClaimResult[] newArray(int size) {
            return new RcsMlsClaimResult[size];
        }
    };

    @Override
    public String toString() {
        return "RcsMlsClaimResult{outcome=" + outcome + " bytes="
                + (keyPackages == null ? 0 : keyPackages.length) + " detail=" + detail + "}";
    }
}

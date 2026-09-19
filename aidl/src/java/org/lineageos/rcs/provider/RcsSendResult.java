/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * Synchronous result of a send. {@link #accepted} means the provider owns the send and the terminal
 * status follows through onMessageStatus(); otherwise the app falls back to SMS at once. An unknown
 * non-OK {@link #reasonCode} is a failure; {@link #reason} is free text for logs.
 */
public final class RcsSendResult implements Parcelable {

    // reasonCode values.
    public static final int REASON_OK             = 0;
    public static final int REASON_NOT_REGISTERED = 1;
    public static final int REASON_NOT_PROVISIONED = 2;
    public static final int REASON_PEER_NOT_RCS   = 3;
    public static final int REASON_RATE_LIMITED   = 4;
    public static final int REASON_INTERNAL_ERROR = 5;
    /**
     * The server does not count this line as a group member. Not an internal error: the local
     * membership is stale and retrying cannot help. Always a failed send.
     */
    public static final int REASON_NOT_IN_GROUP = 6;

    public final boolean accepted;
    public final int reasonCode;
    @Nullable public final String reason;

    public RcsSendResult(boolean accepted, int reasonCode, @Nullable String reason) {
        this.accepted = accepted;
        this.reasonCode = reasonCode;
        this.reason = reason;
    }

    protected RcsSendResult(Parcel in) {
        this.accepted = in.readInt() != 0;
        this.reasonCode = in.readInt();
        this.reason = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(accepted ? 1 : 0);
        dest.writeInt(reasonCode);
        dest.writeString(reason);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsSendResult> CREATOR = new Creator<RcsSendResult>() {
        @Override
        public RcsSendResult createFromParcel(Parcel in) {
            return new RcsSendResult(in);
        }

        @Override
        public RcsSendResult[] newArray(int size) {
            return new RcsSendResult[size];
        }
    };
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * A terms-of-service prompt the provider is waiting on, delivered with
 * {@code IRcsProviderCallback#onCarrierTosStateChanged} and {@code TOS_REQUIRED}. All text comes
 * from the terms source; the provider supplies no strings of its own.
 */
public final class RcsTosPrompt implements Parcelable {

    /** The carrier's terms, from the provisioning configuration. */
    public static final int KIND_CARRIER_TOS = 1;
    /** The provider service's own terms. */
    public static final int KIND_GOOGLE_TOS = 2;

    public final int subId;

    /** One of KIND_*. */
    public final int kind;

    /** Title; may be null. */
    @Nullable public final String title;

    /** Body; may be null. */
    @Nullable public final String message;

    /** Whether an accept button is present. */
    public final boolean hasAccept;

    /** Whether a reject button is present. */
    public final boolean hasReject;

    /** Optional accept-button label. */
    @Nullable public final String acceptLabel;

    /** Optional reject-button label. */
    @Nullable public final String rejectLabel;

    /** Terms URL for {@link #KIND_GOOGLE_TOS}; null otherwise. */
    @Nullable public final String tosUrl;

    public RcsTosPrompt(int subId,
                        int kind,
                        @Nullable String title,
                        @Nullable String message,
                        boolean hasAccept,
                        boolean hasReject,
                        @Nullable String acceptLabel,
                        @Nullable String rejectLabel,
                        @Nullable String tosUrl) {
        this.subId = subId;
        this.kind = kind;
        this.title = title;
        this.message = message;
        this.hasAccept = hasAccept;
        this.hasReject = hasReject;
        this.acceptLabel = acceptLabel;
        this.rejectLabel = rejectLabel;
        this.tosUrl = tosUrl;
    }

    protected RcsTosPrompt(Parcel in) {
        this.subId = in.readInt();
        this.kind = in.readInt();
        this.title = in.readString();
        this.message = in.readString();
        this.hasAccept = in.readInt() != 0;
        this.hasReject = in.readInt() != 0;
        this.acceptLabel = in.readString();
        this.rejectLabel = in.readString();
        this.tosUrl = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(subId);
        dest.writeInt(kind);
        dest.writeString(title);
        dest.writeString(message);
        dest.writeInt(hasAccept ? 1 : 0);
        dest.writeInt(hasReject ? 1 : 0);
        dest.writeString(acceptLabel);
        dest.writeString(rejectLabel);
        dest.writeString(tosUrl);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsTosPrompt> CREATOR = new Creator<RcsTosPrompt>() {
        @Override
        public RcsTosPrompt createFromParcel(Parcel in) {
            return new RcsTosPrompt(in);
        }

        @Override
        public RcsTosPrompt[] newArray(int size) {
            return new RcsTosPrompt[size];
        }
    };
}

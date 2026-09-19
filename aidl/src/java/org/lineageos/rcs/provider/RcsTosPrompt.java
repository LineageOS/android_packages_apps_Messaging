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
 * Carrier/Google RCS Terms-of-Service prompt text surfaced to the main app
 * when the provider is blocked on user consent
 * (see {@code IRcsProviderCallback#onCarrierTosStateChanged} with
 * {@code TOS_REQUIRED}).
 *
 * <p>Flat {@link Parcelable} -- no proto, no SafeParcel. All prompt text comes
 * off the wire (carrier-ToS) or from GMS (Google-ToS); the provider surfaces no
 * UI strings of its own. The {@link #kind} discriminates the two consent
 * surfaces: {@link #KIND_CARRIER_TOS} (ACS {@code <MSG>} ServerMessage, the
 * operational user-visible gate) vs {@link #KIND_GOOGLE_TOS} (Asterism, dormant
 * on stock phenotype).
 */
public final class RcsTosPrompt implements Parcelable {

    // ---- kind values ----
    /** Carrier ToS = ACS {@code <MSG>} ServerMessage gate. */
    public static final int KIND_CARRIER_TOS = 1;
    /** Google ToS = GMS Asterism consent surface. */
    public static final int KIND_GOOGLE_TOS = 2;

    /** Sub this prompt belongs to. */
    public final int subId;

    /** One of KIND_CARRIER_TOS / KIND_GOOGLE_TOS. */
    public final int kind;

    /** ServerMessage title (may be null). */
    @Nullable public final String title;

    /** ServerMessage body (may be null). */
    @Nullable public final String message;

    /** Whether an accept button is present. */
    public final boolean hasAccept;

    /** Whether a reject button is present. */
    public final boolean hasReject;

    /** Optional override label for the accept button (may be null). */
    @Nullable public final String acceptLabel;

    /** Optional override label for the reject button (may be null). */
    @Nullable public final String rejectLabel;

    /** Google-ToS URL when kind == KIND_GOOGLE_TOS (else null). */
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

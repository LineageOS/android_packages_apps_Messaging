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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Flat {@link Parcelable} snapshot of a Tachygram group's wire identity, the
 * proto-free seam carrier for the FLOW4b group surface. Mirrors the provider's
 * parsed {@code GroupInfo} without leaking any proto type across the
 * binder.
 *
 * <p>The provider returns it from {@link IRcsProvider#createGroup} /
 * {@link IRcsProvider#getGroupInfo}, and feeds the member/profile fields into
 * the inbound {@link IRcsProviderCallback#onGroupEvent} parameters.
 *
 * <p>{@code groupId} is the opaque GROUP_ID endpoint string the provider
 * addresses for all subsequent group sends/management — the main app treats it
 * as an opaque conversation key, NOT a phone number.
 */
public final class RcsGroupInfo implements Parcelable {

    /** The GROUP_ID endpoint string (opaque; used as the conversation key). */
    @Nullable public final String groupId;
    /** Group display name (profile), may be null/empty. */
    @Nullable public final String name;
    /** Conference URI from the server, may be null/empty. */
    @Nullable public final String conferenceUri;
    /** Current member endpoint strings (E.164 MSISDNs); never null. */
    public final List<String> members;

    public RcsGroupInfo(@Nullable String groupId,
                        @Nullable String name,
                        @Nullable String conferenceUri,
                        @Nullable List<String> members) {
        this.groupId = groupId;
        this.name = name;
        this.conferenceUri = conferenceUri;
        this.members = members != null
                ? new ArrayList<>(members)
                : Collections.<String>emptyList();
    }

    protected RcsGroupInfo(Parcel in) {
        this.groupId = in.readString();
        this.name = in.readString();
        this.conferenceUri = in.readString();
        final ArrayList<String> m = new ArrayList<>();
        in.readStringList(m);
        this.members = m;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(groupId);
        dest.writeString(name);
        dest.writeString(conferenceUri);
        dest.writeStringList(members);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsGroupInfo> CREATOR = new Creator<RcsGroupInfo>() {
        @Override
        public RcsGroupInfo createFromParcel(Parcel in) {
            return new RcsGroupInfo(in);
        }

        @Override
        public RcsGroupInfo[] newArray(int size) {
            return new RcsGroupInfo[size];
        }
    };
}

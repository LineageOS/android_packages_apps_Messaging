/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A group as the provider knows it, returned by {@link IRcsProvider#createGroup} and
 * {@link IRcsProvider#getGroupInfo}. See docs/rcs/groups.md.
 */
public final class RcsGroupInfo implements Parcelable {

    /** Opaque group id, used as the conversation key; not a phone number. */
    @Nullable public final String groupId;
    /** Display name; may be null or empty. */
    @Nullable public final String name;
    /** Conference URI; may be null or empty. */
    @Nullable public final String conferenceUri;
    /** Current members as E.164 numbers; never null. */
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

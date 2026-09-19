/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.os;

/** Host stand-in for {@code android.os.Parcelable}. */
public interface Parcelable {
    int CONTENTS_FILE_DESCRIPTOR = 0x0001;

    int describeContents();

    void writeToParcel(Parcel dest, int flags);

    interface Creator<T> {
        T createFromParcel(Parcel source);

        T[] newArray(int size);
    }
}

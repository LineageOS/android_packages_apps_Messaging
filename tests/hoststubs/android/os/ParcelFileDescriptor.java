/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.os;

/** Host stand-in for {@code android.os.ParcelFileDescriptor}: a descriptor number in a parcel. */
public class ParcelFileDescriptor implements Parcelable {
    public final int fd;

    public ParcelFileDescriptor(final int fd) {
        this.fd = fd;
    }

    @Override
    public int describeContents() {
        return CONTENTS_FILE_DESCRIPTOR;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        dest.writeInt(fd);
    }

    public static final Creator<ParcelFileDescriptor> CREATOR =
            new Creator<ParcelFileDescriptor>() {
                @Override
                public ParcelFileDescriptor createFromParcel(final Parcel in) {
                    return new ParcelFileDescriptor(in.readInt());
                }

                @Override
                public ParcelFileDescriptor[] newArray(final int size) {
                    return new ParcelFileDescriptor[size];
                }
            };
}

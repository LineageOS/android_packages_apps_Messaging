/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * An inbound file the provider has already downloaded, exposed as a {@code content://} URI with a
 * read grant and/or an open descriptor. It never carries a download URL, a credential or a
 * transport descriptor, so every transport produces the same object. A null {@link #contentUri}
 * with a thumbnail is the preview before the user accepts the download.
 * See docs/rcs/provider-contract.md.
 */
public final class RcsIncomingFile implements Parcelable {

    public final int subId;
    /** Message id, for receipt correlation. */
    public final String messageId;
    /** E.164 sender. */
    public final String fromUri;
    /** URI of the downloaded file; null while the file awaits the user's accept. */
    @Nullable public final String contentUri;
    /** Optional readable descriptor of the file; the receiver closes it. */
    @Nullable public final ParcelFileDescriptor fd;
    /** The media's real type, e.g. "image/jpeg"; never a transport descriptor type. */
    public final String mimeType;
    /** Original file name. */
    @Nullable public final String fileName;
    /** File size in bytes; -1 if unknown. */
    public final long size;
    /** Optional caption. */
    @Nullable public final String caption;
    /** URI of the thumbnail, downloaded without waiting for an accept; null when none. */
    @Nullable public final String contentUriThumbnail;
    /** The thumbnail's type; null when there is no thumbnail. */
    @Nullable public final String mimeTypeThumbnail;
    /** Thumbnail size in bytes; -1 if unknown or absent. */
    public final long sizeThumbnail;
    /** Server timestamp, microseconds since epoch (0 if unknown). */
    public final long serverTimestampUsec;
    public final boolean wantsDeliveredImdn;
    public final boolean wantsDisplayedImdn;
    /** The group id for a group file; null or empty for 1:1. */
    @Nullable public final String groupId;
    /**
     * Optional readable descriptor of the thumbnail, for a provider that shares it this way rather
     * than as {@link #contentUriThumbnail}; the receiver closes it.
     */
    @Nullable public final ParcelFileDescriptor fdThumbnail;
    /**
     * The scheme the provider decrypted the file under, or {@code null} for a plaintext transfer;
     * as {@link RcsIncomingMessage#e2eeSchemeId}. A parcel that ends before it reads as null.
     */
    @Nullable public final String e2eeSchemeId;

    public RcsIncomingFile(int subId,
                           String messageId,
                           String fromUri,
                           @Nullable String contentUri,
                           @Nullable ParcelFileDescriptor fd,
                           String mimeType,
                           @Nullable String fileName,
                           long size,
                           @Nullable String caption,
                           @Nullable String contentUriThumbnail,
                           @Nullable String mimeTypeThumbnail,
                           long sizeThumbnail,
                           long serverTimestampUsec,
                           boolean wantsDeliveredImdn,
                           boolean wantsDisplayedImdn,
                           @Nullable String groupId,
                           @Nullable ParcelFileDescriptor fdThumbnail) {
        this(subId, messageId, fromUri, contentUri, fd, mimeType, fileName, size, caption,
                contentUriThumbnail, mimeTypeThumbnail, sizeThumbnail, serverTimestampUsec,
                wantsDeliveredImdn, wantsDisplayedImdn, groupId, fdThumbnail,
                null);
    }

    public RcsIncomingFile(int subId,
                           String messageId,
                           String fromUri,
                           @Nullable String contentUri,
                           @Nullable ParcelFileDescriptor fd,
                           String mimeType,
                           @Nullable String fileName,
                           long size,
                           @Nullable String caption,
                           @Nullable String contentUriThumbnail,
                           @Nullable String mimeTypeThumbnail,
                           long sizeThumbnail,
                           long serverTimestampUsec,
                           boolean wantsDeliveredImdn,
                           boolean wantsDisplayedImdn,
                           @Nullable String groupId,
                           @Nullable ParcelFileDescriptor fdThumbnail,
                           @Nullable String e2eeSchemeId) {
        this.subId = subId;
        this.messageId = messageId;
        this.fromUri = fromUri;
        this.contentUri = contentUri;
        this.fd = fd;
        this.mimeType = mimeType;
        this.fileName = fileName;
        this.size = size;
        this.caption = caption;
        this.contentUriThumbnail = contentUriThumbnail;
        this.mimeTypeThumbnail = mimeTypeThumbnail;
        this.sizeThumbnail = sizeThumbnail;
        this.serverTimestampUsec = serverTimestampUsec;
        this.wantsDeliveredImdn = wantsDeliveredImdn;
        this.wantsDisplayedImdn = wantsDisplayedImdn;
        this.groupId = groupId;
        this.fdThumbnail = fdThumbnail;
        this.e2eeSchemeId = e2eeSchemeId;
    }

    protected RcsIncomingFile(Parcel in) {
        this.subId = in.readInt();
        this.messageId = in.readString();
        this.fromUri = in.readString();
        this.contentUri = in.readString();
        this.fd = in.readInt() != 0
                ? ParcelFileDescriptor.CREATOR.createFromParcel(in) : null;
        this.mimeType = in.readString();
        this.fileName = in.readString();
        this.size = in.readLong();
        this.caption = in.readString();
        this.contentUriThumbnail = in.readString();
        this.mimeTypeThumbnail = in.readString();
        this.sizeThumbnail = in.readLong();
        this.serverTimestampUsec = in.readLong();
        this.wantsDeliveredImdn = in.readInt() != 0;
        this.wantsDisplayedImdn = in.readInt() != 0;
        this.groupId = in.readString();
        this.fdThumbnail = in.readInt() != 0
                ? ParcelFileDescriptor.CREATOR.createFromParcel(in) : null;
        this.e2eeSchemeId = in.dataAvail() > 0 ? in.readString() : null;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(subId);
        dest.writeString(messageId);
        dest.writeString(fromUri);
        dest.writeString(contentUri);
        if (fd != null) {
            dest.writeInt(1);
            fd.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeString(mimeType);
        dest.writeString(fileName);
        dest.writeLong(size);
        dest.writeString(caption);
        dest.writeString(contentUriThumbnail);
        dest.writeString(mimeTypeThumbnail);
        dest.writeLong(sizeThumbnail);
        dest.writeLong(serverTimestampUsec);
        dest.writeInt(wantsDeliveredImdn ? 1 : 0);
        dest.writeInt(wantsDisplayedImdn ? 1 : 0);
        dest.writeString(groupId);
        if (fdThumbnail != null) {
            dest.writeInt(1);
            fdThumbnail.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeString(e2eeSchemeId);
    }

    @Override
    public int describeContents() {
        return (fd != null || fdThumbnail != null) ? CONTENTS_FILE_DESCRIPTOR : 0;
    }

    public static final Creator<RcsIncomingFile> CREATOR = new Creator<RcsIncomingFile>() {
        @Override
        public RcsIncomingFile createFromParcel(Parcel in) {
            return new RcsIncomingFile(in);
        }

        @Override
        public RcsIncomingFile[] newArray(int size) {
            return new RcsIncomingFile[size];
        }
    };
}

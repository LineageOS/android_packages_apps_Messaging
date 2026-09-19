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
 * An outgoing file. The content crosses as an open descriptor, not bytes, because a binder
 * transaction is limited to about 1 MB; the provider reads and closes {@link #fd}. The app mints
 * {@link #messageId} to correlate onMessageStatus() and receipts.
 */
public final class RcsOutgoingFile implements Parcelable {

    public final int subId;
    /** Client-minted UUID, echoed in onMessageStatus(). */
    public final String messageId;
    /** E.164 recipient; null for a group send. */
    @Nullable public final String toUri;
    /** URI of the picked attachment, for logging; the upload reads {@link #fd}. */
    @Nullable public final String contentUri;
    /** Readable descriptor for the content; the provider closes it. */
    @Nullable public final ParcelFileDescriptor fd;
    /** The media's real MIME, e.g. "image/jpeg". */
    public final String mimeType;
    /** Original file name. */
    @Nullable public final String fileName;
    /** Size in bytes; -1 if unknown. */
    public final long size;
    /** Optional caption. */
    @Nullable public final String caption;
    /** The group id for a group send; exactly one of {@link #toUri} and this is set. */
    @Nullable public final String groupId;

    public RcsOutgoingFile(int subId,
                           String messageId,
                           @Nullable String toUri,
                           @Nullable String contentUri,
                           @Nullable ParcelFileDescriptor fd,
                           String mimeType,
                           @Nullable String fileName,
                           long size,
                           @Nullable String caption) {
        this(subId, messageId, toUri, contentUri, fd, mimeType, fileName, size,
                caption, /*groupId=*/ null);
    }

    public RcsOutgoingFile(int subId,
                           String messageId,
                           @Nullable String toUri,
                           @Nullable String contentUri,
                           @Nullable ParcelFileDescriptor fd,
                           String mimeType,
                           @Nullable String fileName,
                           long size,
                           @Nullable String caption,
                           @Nullable String groupId) {
        this.subId = subId;
        this.messageId = messageId;
        this.toUri = toUri;
        this.contentUri = contentUri;
        this.fd = fd;
        this.mimeType = mimeType;
        this.fileName = fileName;
        this.size = size;
        this.caption = caption;
        this.groupId = groupId;
    }

    protected RcsOutgoingFile(Parcel in) {
        this.subId = in.readInt();
        this.messageId = in.readString();
        this.toUri = in.readString();
        this.contentUri = in.readString();
        this.fd = in.readInt() != 0
                ? ParcelFileDescriptor.CREATOR.createFromParcel(in) : null;
        this.mimeType = in.readString();
        this.fileName = in.readString();
        this.size = in.readLong();
        this.caption = in.readString();
        this.groupId = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(subId);
        dest.writeString(messageId);
        dest.writeString(toUri);
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
        dest.writeString(groupId);
    }

    @Override
    public int describeContents() {
        return fd != null ? CONTENTS_FILE_DESCRIPTOR : 0;
    }

    public static final Creator<RcsOutgoingFile> CREATOR = new Creator<RcsOutgoingFile>() {
        @Override
        public RcsOutgoingFile createFromParcel(Parcel in) {
            return new RcsOutgoingFile(in);
        }

        @Override
        public RcsOutgoingFile[] newArray(int size) {
            return new RcsOutgoingFile[size];
        }
    };
}

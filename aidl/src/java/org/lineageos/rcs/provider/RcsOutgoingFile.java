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
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * FLOW4c — a 1-1 outgoing FILE (media / attachment) handed from the main app
 * to the provider for FT-HTTP upload + send.
 *
 * <p>CRITICAL: this carries a content URI + an open {@link ParcelFileDescriptor}
 * — NOT inline bytes. A raw {@code byte[]} over Binder is a hard ~1 MiB cap;
 * media can be up to {@code MaxSizeFileTransfer} (100 MiB on VZW). The provider
 * reads the FD into provider-local memory, uploads via
 * {@code FtHttpUploader}, then sends the resulting download URL as a kind=36
 * TACHYGRAM FT-HTTP XML body.
 *
 * <p>The main app mints {@link #messageId} (a UUID) so it can correlate the
 * later async {@code onMessageStatus()} callback (and any IMDN receipt).
 *
 * <p>The provider OWNS closing {@link #fd}. The {@link #contentUri} is carried
 * as a fallback/diagnostic; the upload reads {@link #fd}.
 */
public final class RcsOutgoingFile implements Parcelable {

    public final int subId;
    /** Client-minted UUID, echoed back in onMessageStatus(). */
    public final String messageId;
    /** E.164 recipient, e.g. "+15555550123". Null for a group send (see {@link #groupId}). */
    @Nullable public final String toUri;
    /** content:// URI of the picked attachment (for logging / fallback). */
    @Nullable public final String contentUri;
    /** Open, readable descriptor for the file content. Provider closes it. */
    @Nullable public final ParcelFileDescriptor fd;
    /** The media's real MIME, e.g. "image/jpeg". */
    public final String mimeType;
    /** Original file name, e.g. "IMG_0042.jpg". */
    @Nullable public final String fileName;
    /** File size in bytes if known (-1 if unknown). */
    public final long size;
    /** Optional caption text to send alongside the file (may be null). */
    @Nullable public final String caption;
    /**
     * When non-null, this is a 32-hex GROUP_ID and the file is sent to
     * the group (server fans out); {@link #toUri} is then null. When null, a 1-1
     * send to {@link #toUri}. Exactly one of {toUri, groupId} is set.
     */
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

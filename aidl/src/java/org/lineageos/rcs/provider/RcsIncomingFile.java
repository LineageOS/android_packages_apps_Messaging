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
 * FLOW4c — a 1-1 INBOUND media (file / attachment) handed from the provider UP
 * to the main app for rendering. The transport-neutral mirror of
 * {@link RcsOutgoingFile}, and the inbound complement of
 * {@link RcsIncomingMessage} (which stays text/typing/imdn only).
 *
 * <p><b>PLUGGABLE-TRANSPORT INVARIANT (load-bearing).</b> This descriptor carries
 * ONLY RESOLVED media: a {@link #contentUri content:// URI} (and/or an open
 * {@link #fd ParcelFileDescriptor}) of a blob the provider has ALREADY downloaded
 * INTERNALLY, plus its real {@link #mimeType MIME}, {@link #fileName}, {@link #size},
 * optional {@link #caption}, and an EAGER-downloaded {@link #contentUriThumbnail
 * thumbnail} (brief §4e / §5). It NEVER carries a copper / googleapis download URL,
 * an {@code Authorization}/{@code Bearer} token, an {@code rcs-ft-http+xml}
 * descriptor, or any other transport detail. The whole FT-HTTP exchange (parse the
 * {@code rcs-ft-http+xml} XML, GET the pre-signed URL, write the temp
 * file) happens INSIDE the provider before this parcel is built; a future
 * {@code TRANSPORT_CARRIER_MSRP} provider delivers the identical descriptor
 * unchanged. {@link #mimeType} is the file {@code <content-type>} (e.g.
 * {@code image/jpeg}) — it is NEVER {@code application/vnd.gsma.rcs-ft-http+xml}.
 *
 * <p>The blob is exposed to the main app as a cross-package FileProvider
 * {@code content://} URI with a READ grant to the main-app package, so the main app
 * can {@code openInputStream(contentUri)} durably (mirror of how
 * {@link RcsOutgoingFile} carries a content URI + FD in the reverse direction). The
 * {@link #fd} is the same dual URI+FD shape as {@link RcsOutgoingFile} so the main
 * app can read either way: the provider grants/opens, the main app reads then closes.
 *
 * <p>{@link #contentUri} may be {@code null} when only the thumbnail has been
 * eagerly downloaded and the file awaits an explicit user ACCEPT (brief §4e); a
 * non-null {@link #contentUriThumbnail} with a null file {@link #contentUri} is the
 * thumbnail-push preview state. On the single-part FILE-only path (no thumbnail —
 * {@code FtThumbnailSupported} false, e.g. our VZW line) the thumbnail fields are
 * all {@code null}/-1.
 */
public final class RcsIncomingFile implements Parcelable {

    public final int subId;
    /** Server/peer-assigned message id (for IMDN correlation; mirrors
     *  {@link RcsIncomingMessage#messageId}). */
    public final String messageId;
    /** E.164 sender, e.g. "+15555550123". */
    public final String fromUri;
    /**
     * content:// URI of the provider-downloaded FILE blob, exposed via the
     * provider's FileProvider with a READ grant to the main-app package. May be
     * {@code null} when the file is not yet downloaded (thumbnail-only push,
     * awaiting user ACCEPT).
     */
    @Nullable public final String contentUri;
    /**
     * Open, readable descriptor of the resolved FILE blob (same dual URI+FD shape
     * as {@link RcsOutgoingFile}). May be {@code null} (the durable {@link #contentUri}
     * grant is the primary handle; the FD is an optional fast path). Whoever opens
     * it owns closing it.
     */
    @Nullable public final ParcelFileDescriptor fd;
    /** The file's REAL media MIME, e.g. "image/jpeg" — the {@code <content-type>}
     *  element from the FT-HTTP XML. NEVER application/vnd.gsma.rcs-ft-http+xml. */
    public final String mimeType;
    /** Original file name, e.g. "IMG_0042.jpg" (the {@code <file-name>} element). */
    @Nullable public final String fileName;
    /** File size in bytes (the {@code <file-size>} element); -1 if unknown. */
    public final long size;
    /** Optional caption text received alongside the file (may be null). */
    @Nullable public final String caption;
    /**
     * content:// URI of the EAGER-downloaded THUMBNAIL blob (brief §4e: the
     * thumbnail downloads on push receipt, no user accept), also resolved via the
     * provider's FileProvider with a READ grant. {@code null} when there is no
     * thumbnail (single-part FILE-only path).
     */
    @Nullable public final String contentUriThumbnail;
    /** The thumbnail's real MIME (the thumbnail {@code <content-type>}, e.g.
     *  "image/jpeg"); {@code null} when no thumbnail. */
    @Nullable public final String mimeTypeThumbnail;
    /** Thumbnail size in bytes; -1 if unknown / no thumbnail. */
    public final long sizeThumbnail;
    /** Server timestamp, microseconds since epoch (0 if unknown). */
    public final long serverTimestampUsec;
    public final boolean wantsDeliveredImdn;
    public final boolean wantsDisplayedImdn;
    /**
     * Group routing key (mirror of {@link RcsIncomingMessage#groupId}). {@code null}
     * or empty for a 1-1 media message; the opaque 32-char lowercase-hex GROUP_ID
     * for a future fanned-out group-FT message. Carried for forward-compat (group
     * FT is NOT exercised in 1:1 scope). Appended last as a trailing nullable string
     * so it round-trips cleanly on the parcel.
     */
    @Nullable public final String groupId;
    /**
     * Open, readable descriptor of the EAGER-downloaded THUMBNAIL blob (symmetric
     * to {@link #fd} for the file). The provider has no cross-package FileProvider,
     * so it hands the downloaded thumbnail across as an FD; the main app ingests it
     * into its own scratch space and resolves {@link #contentUriThumbnail}. May be
     * {@code null} (no thumbnail, or the thumbnail came as a {@link #contentUriThumbnail}
     * URI). Whoever opens it owns closing it. Appended last for parcel round-trip.
     */
    @Nullable public final ParcelFileDescriptor fdThumbnail;

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

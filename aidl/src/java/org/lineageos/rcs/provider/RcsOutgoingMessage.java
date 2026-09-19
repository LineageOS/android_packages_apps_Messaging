/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * An outgoing 1:1 or group message. {@link #body} is raw bytes in {@link #contentType} (UTF-8 for
 * text). The app mints {@link #messageId} to correlate onMessageStatus().
 */
public final class RcsOutgoingMessage implements Parcelable {

    public final int subId;
    /** Client-minted UUID, echoed in onMessageStatus(). */
    public final String messageId;
    /** E.164 recipient. */
    public final String toUri;
    /** MIME type, e.g. "text/plain;charset=UTF-8". */
    public final String contentType;
    @Nullable public final byte[] body;
    /**
     * Encryption scheme for this send; null for plaintext. With a scheme set, {@link #body} is
     * already framed by the app and the provider does not re-frame it.
     */
    @Nullable public final String e2eeSchemeId;
    /** Target group id for a group send, or {@code null} for 1-1. */
    @Nullable public final String groupId;

    public RcsOutgoingMessage(int subId,
                              String messageId,
                              String toUri,
                              String contentType,
                              @Nullable byte[] body) {
        this(subId, messageId, toUri, contentType, body, /*e2eeSchemeId=*/ null, /*groupId=*/ null);
    }

    public RcsOutgoingMessage(int subId,
                              String messageId,
                              String toUri,
                              String contentType,
                              @Nullable byte[] body,
                              @Nullable String e2eeSchemeId,
                              @Nullable String groupId) {
        this.subId = subId;
        this.messageId = messageId;
        this.toUri = toUri;
        this.contentType = contentType;
        this.body = body;
        this.e2eeSchemeId = e2eeSchemeId;
        this.groupId = groupId;
    }

    protected RcsOutgoingMessage(Parcel in) {
        this.subId = in.readInt();
        this.messageId = in.readString();
        this.toUri = in.readString();
        this.contentType = in.readString();
        this.body = in.createByteArray();
        this.e2eeSchemeId = in.readString();
        this.groupId = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(subId);
        dest.writeString(messageId);
        dest.writeString(toUri);
        dest.writeString(contentType);
        dest.writeByteArray(body);
        dest.writeString(e2eeSchemeId);
        dest.writeString(groupId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsOutgoingMessage> CREATOR = new Creator<RcsOutgoingMessage>() {
        @Override
        public RcsOutgoingMessage createFromParcel(Parcel in) {
            return new RcsOutgoingMessage(in);
        }

        @Override
        public RcsOutgoingMessage[] newArray(int size) {
            return new RcsOutgoingMessage[size];
        }
    };
}

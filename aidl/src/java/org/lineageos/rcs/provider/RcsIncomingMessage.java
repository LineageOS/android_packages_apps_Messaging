/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * An inbound 1:1 or group message. The IMDN flags say which receipts the peer asked for; the
 * displayed receipt is the app's to send when the user reads the thread.
 */
public final class RcsIncomingMessage implements Parcelable {

    public final int subId;
    /** Message id, for receipt correlation. */
    public final String messageId;
    /** E.164 sender. */
    public final String fromUri;
    public final String contentType;
    @Nullable public final byte[] body;
    /** Server timestamp, microseconds since epoch (0 if unknown). */
    public final long serverTimestampUsec;
    public final boolean wantsDeliveredImdn;
    public final boolean wantsDisplayedImdn;
    /**
     * The group id for a group message, null or empty for 1:1. When set, {@code fromUri} is still
     * the member who sent it and the message belongs to the group conversation.
     */
    @Nullable public final String groupId;
    /** The provider-layer encryption scheme it arrived under; null or empty for plaintext. */
    @Nullable public final String e2eeSchemeId;

    public RcsIncomingMessage(int subId,
                              String messageId,
                              String fromUri,
                              String contentType,
                              @Nullable byte[] body,
                              long serverTimestampUsec,
                              boolean wantsDeliveredImdn,
                              boolean wantsDisplayedImdn) {
        this(subId, messageId, fromUri, contentType, body, serverTimestampUsec,
                wantsDeliveredImdn, wantsDisplayedImdn, null, null);
    }

    public RcsIncomingMessage(int subId,
                              String messageId,
                              String fromUri,
                              String contentType,
                              @Nullable byte[] body,
                              long serverTimestampUsec,
                              boolean wantsDeliveredImdn,
                              boolean wantsDisplayedImdn,
                              @Nullable String groupId) {
        this(subId, messageId, fromUri, contentType, body, serverTimestampUsec,
                wantsDeliveredImdn, wantsDisplayedImdn, groupId, null);
    }

    public RcsIncomingMessage(int subId,
                              String messageId,
                              String fromUri,
                              String contentType,
                              @Nullable byte[] body,
                              long serverTimestampUsec,
                              boolean wantsDeliveredImdn,
                              boolean wantsDisplayedImdn,
                              @Nullable String groupId,
                              @Nullable String e2eeSchemeId) {
        this.subId = subId;
        this.messageId = messageId;
        this.fromUri = fromUri;
        this.contentType = contentType;
        this.body = body;
        this.serverTimestampUsec = serverTimestampUsec;
        this.wantsDeliveredImdn = wantsDeliveredImdn;
        this.wantsDisplayedImdn = wantsDisplayedImdn;
        this.groupId = groupId;
        this.e2eeSchemeId = e2eeSchemeId;
    }

    protected RcsIncomingMessage(Parcel in) {
        this.subId = in.readInt();
        this.messageId = in.readString();
        this.fromUri = in.readString();
        this.contentType = in.readString();
        this.body = in.createByteArray();
        this.serverTimestampUsec = in.readLong();
        this.wantsDeliveredImdn = in.readInt() != 0;
        this.wantsDisplayedImdn = in.readInt() != 0;
        this.groupId = in.readString();
        this.e2eeSchemeId = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(subId);
        dest.writeString(messageId);
        dest.writeString(fromUri);
        dest.writeString(contentType);
        dest.writeByteArray(body);
        dest.writeLong(serverTimestampUsec);
        dest.writeInt(wantsDeliveredImdn ? 1 : 0);
        dest.writeInt(wantsDisplayedImdn ? 1 : 0);
        dest.writeString(groupId);
        dest.writeString(e2eeSchemeId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsIncomingMessage> CREATOR = new Creator<RcsIncomingMessage>() {
        @Override
        public RcsIncomingMessage createFromParcel(Parcel in) {
            return new RcsIncomingMessage(in);
        }

        @Override
        public RcsIncomingMessage[] newArray(int size) {
            return new RcsIncomingMessage[size];
        }
    };
}

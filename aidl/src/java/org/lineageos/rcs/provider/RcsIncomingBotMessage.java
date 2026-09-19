/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * An inbound business-messaging agent message. The sender is an agent id, not an E.164 number, so
 * it has its own callback; the JSON body is carried unparsed and the app parses and renders it. The
 * provider sends no delivery receipt to an agent.
 */
public final class RcsIncomingBotMessage implements Parcelable {

    public final int subId;
    /** Message id, for correlation. */
    public final String messageId;
    /** The agent address. */
    public final String botId;
    /**
     * Type of the primary part, e.g. {@code application/vnd.gsma.botmessage.v1.0+json} or
     * {@code text/plain}.
     */
    public final String contentType;
    /** The JSON body for a {@code vnd.gsma.bot*} type; null for a plain-text message. */
    @Nullable public final String jsonBody;
    /** The agent's plain-text part; null when absent. */
    @Nullable public final String fallbackText;
    /** Server timestamp, microseconds since epoch (0 if unknown). */
    public final long serverTimestampUsec;

    public RcsIncomingBotMessage(int subId,
                                 String messageId,
                                 String botId,
                                 String contentType,
                                 @Nullable String jsonBody,
                                 @Nullable String fallbackText,
                                 long serverTimestampUsec) {
        this.subId = subId;
        this.messageId = messageId;
        this.botId = botId;
        this.contentType = contentType;
        this.jsonBody = jsonBody;
        this.fallbackText = fallbackText;
        this.serverTimestampUsec = serverTimestampUsec;
    }

    protected RcsIncomingBotMessage(Parcel in) {
        this.subId = in.readInt();
        this.messageId = in.readString();
        this.botId = in.readString();
        this.contentType = in.readString();
        this.jsonBody = in.readString();
        this.fallbackText = in.readString();
        this.serverTimestampUsec = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(subId);
        dest.writeString(messageId);
        dest.writeString(botId);
        dest.writeString(contentType);
        dest.writeString(jsonBody);
        dest.writeString(fallbackText);
        dest.writeLong(serverTimestampUsec);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsIncomingBotMessage> CREATOR =
            new Creator<RcsIncomingBotMessage>() {
        @Override
        public RcsIncomingBotMessage createFromParcel(Parcel in) {
            return new RcsIncomingBotMessage(in);
        }

        @Override
        public RcsIncomingBotMessage[] newArray(int size) {
            return new RcsIncomingBotMessage[size];
        }
    };
}

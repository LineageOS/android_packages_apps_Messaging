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
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * A 1-1 outgoing message handed from the main app to the provider.
 * Flat {@link Parcelable}. v1 = single recipient text; body is raw bytes
 * per contentType (for "text/plain;charset=UTF-8" this is the UTF-8 bytes).
 * The main app mints messageId (a UUID) so it can correlate the later
 * async onMessageStatus() callback.
 */
public final class RcsOutgoingMessage implements Parcelable {

    public final int subId;
    /** Client-minted UUID, echoed back in onMessageStatus(). */
    public final String messageId;
    /** E.164 recipient, e.g. "+15555550123". */
    public final String toUri;
    /** MIME content type, e.g. "text/plain;charset=UTF-8". */
    public final String contentType;
    @Nullable public final byte[] body;
    /**
     * E2EE scheme for this send (mirrors {@link RcsIncomingMessage#e2eeSchemeId}). {@code null} =
     * plaintext RCS. When set to the MLS scheme, {@link #body} is the ALREADY-FRAMED RCC.16 MLS
     * application payload (the main app frames it via its generic MLS layer); the provider MLS-
     * encrypts + carries it verbatim (it does NOT re-frame). This is what lets an MLS send cross the
     * provider AIDL as a first-class typed message rather than being mistaken for plaintext.
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

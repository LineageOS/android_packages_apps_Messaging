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
 * A 1-1 inbound message streamed from the provider up to the main app,
 * which persists it. Flat {@link Parcelable}. The wantsDeliveredImdn /
 * wantsDisplayedImdn flags tell the main app whether the peer requested
 * receipts (so the main app can drive IRcsProvider.sendImdn() on
 * receive/read). The provider may auto-fire the delivered IMDN itself; the
 * displayed IMDN needs the main app's "user opened thread" signal.
 */
public final class RcsIncomingMessage implements Parcelable {

    public final int subId;
    /** Server/peer-assigned message id (for IMDN correlation). */
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
     * Group routing key (contract-v3 addition). {@code null} or empty for a 1-1
     * message; the opaque 32-char lowercase-hex GROUP_ID for a fanned-out group
     * message. When non-empty, {@code fromUri} is still the individual member
     * sender (E.164) and the main app files the message into the group
     * conversation keyed by {@code groupId} rather than by {@code fromUri}.
     * Appended last for backward-compat: a v1/v2 sender simply leaves it null,
     * and the field round-trips as a trailing nullable string on the parcel.
     */
    @Nullable public final String groupId;
    /**
     * The opaque E2EE scheme id this message arrived under (contract-v16), or
     * {@code null}/empty for a plaintext message. Non-empty (e.g. {@code
     * "google.etouffee"}) when the message was decrypted. The UI renders a
     * per-message lock/"encrypted" indicator when non-empty, scheme-blind.
     * Appended last for parcel backward-compat (defaults to null).
     */
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

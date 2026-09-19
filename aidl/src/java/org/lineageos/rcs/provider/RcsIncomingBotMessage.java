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
 * An inbound RCS Business Messaging (RBM) agent message streamed from the
 * provider up to the main app. Contract-v10.
 *
 * <p>RBM rides our Tachygram/Tachyon stream as GSMA JSON: an ordinary kind=36
 * message whose sender is an {@code RCS_BOT} id ({@code <agent>@rbm.goog}) and
 * whose primary content part is content-type
 * {@code application/vnd.gsma.botmessage.v1.0+json} (the rich card) — see the
 * C1 capture + memory {@code rbm-tachygram-gsma-json-contract-2026-06-10}.
 *
 * <p>Unlike {@link RcsIncomingMessage}, the sender is NOT an E.164 number, so a
 * bot message gets its own typed callback ({@code onIncomingBotMessage}) rather
 * than being smuggled through the phone-centric text path. The provider never
 * auto-fires a phone-style delivered IMDN for a bot sender (that fails server
 * side with INVALID_ARGUMENT); the user-driven response is the postback
 * (Phase 5), not a receipt.
 *
 * <p>The raw {@link #jsonBody} is carried UNPARSED — the GSMA JSON parser lives
 * in the main app (Phase 2) and the renderer in Phase 4. {@link #fallbackText}
 * is the agent's text/plain fallback (title/description or a confirmation line)
 * when the part is plain text rather than a card; either field may be null.
 */
public final class RcsIncomingBotMessage implements Parcelable {

    public final int subId;
    /** Server/peer-assigned message id (for correlation). */
    public final String messageId;
    /** The bot/agent address, e.g. {@code admin@rbm.goog} (RCS_BOT id). */
    public final String botId;
    /** Content-type of the primary part, e.g.
     *  {@code application/vnd.gsma.botmessage.v1.0+json} or {@code text/plain}. */
    public final String contentType;
    /** Raw GSMA JSON body when {@link #contentType} is a {@code vnd.gsma.bot*}
     *  type; null for a plain-text agent message. */
    @Nullable public final String jsonBody;
    /** Human-readable fallback text (agent text/plain part); null when absent. */
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

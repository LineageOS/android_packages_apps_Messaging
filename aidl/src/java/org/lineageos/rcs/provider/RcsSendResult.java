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
 * Synchronous result of IRcsProvider.sendMessage(). accepted==true means
 * the provider took ownership of the send (terminal status follows async on
 * onMessageStatus); accepted==false means the main app should fall back to
 * SMS immediately. reasonCode is a stable enum; reason is a free-form
 * detail string for logs/UI.
 */
public final class RcsSendResult implements Parcelable {

    // reasonCode values.
    public static final int REASON_OK             = 0;
    public static final int REASON_NOT_REGISTERED = 1;
    public static final int REASON_NOT_PROVISIONED = 2;
    public static final int REASON_PEER_NOT_RCS   = 3;
    public static final int REASON_RATE_LIMITED   = 4;
    public static final int REASON_INTERNAL_ERROR = 5;
    /**
     * The server does not count this line as a member of the group — {@code TachyonError} wire 36
     * {@code USER_NOT_IN_GROUP}.
     *
     * <p><b>Why it is not {@link #REASON_INTERNAL_ERROR}.</b> That reads as "our bug, try again",
     * and it is the opposite of both halves: nothing is wrong on our side, and retrying is exactly
     * the thing that cannot work. The message was refused because the LOCAL membership is stale,
     * and the remedy is to retire it (Google Messages' {@code REMOVE_SELF_FROM_GROUP}) — which no
     * amount of resending reaches.
     *
     * <p><b>A 36 always FAILS the send</b> — it is a grpc-7 refusal, so it can never coexist with a
     * send we treated as accepted. That is worth stating next to the separate rule that "{@code
     * sendMlsMessage OK} is not proof of delivery", because the two are different failures: that
     * one is a send that reports OK and is not delivered, this is a send that reports failure and
     * is mis-described. Neither implies the other.
     *
     * <p>Behaviour-neutral for an app that does not handle it, checked: the only comparisons
     * against this field in messaging2 are two {@code == REASON_PEER_NOT_RCS} tests.
     */
    public static final int REASON_NOT_IN_GROUP = 6;

    public final boolean accepted;
    public final int reasonCode;
    @Nullable public final String reason;

    public RcsSendResult(boolean accepted, int reasonCode, @Nullable String reason) {
        this.accepted = accepted;
        this.reasonCode = reasonCode;
        this.reason = reason;
    }

    protected RcsSendResult(Parcel in) {
        this.accepted = in.readInt() != 0;
        this.reasonCode = in.readInt();
        this.reason = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(accepted ? 1 : 0);
        dest.writeInt(reasonCode);
        dest.writeString(reason);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsSendResult> CREATOR = new Creator<RcsSendResult>() {
        @Override
        public RcsSendResult createFromParcel(Parcel in) {
            return new RcsSendResult(in);
        }

        @Override
        public RcsSendResult[] newArray(int size) {
            return new RcsSendResult[size];
        }
    };
}

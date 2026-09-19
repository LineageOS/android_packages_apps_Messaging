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
 * The outcome of an MLS control-plane operation, expressed in <b>spec-level</b> terms.
 *
 * <p><b>This is the vocabulary firewall.</b> The provider translates its own transport verdicts into
 * the {@code VERDICT_*} constants below before they cross, so no Tachyon vocabulary — no
 * {@code tachyonerror}, no gRPC status, no Google error string, no backend proto field
 * name — ever reaches the app. The app reasons about "the era diverged" or "the server refused an
 * external commit", never about how a particular backend said so.
 *
 * <p>Why that matters beyond tidiness: a carrier CPM/MSRP provider must be able to implement this same
 * interface and report the same verdicts from a completely different protocol. If the app switched on
 * Tachyon strings it could never do that, and the whole point of keeping the API semantic would be
 * lost.
 *
 * <p>{@link #detail} is a human diagnostic for logs — it MAY contain backend-specific text and callers
 * MUST NOT parse it. Branch on {@link #verdict}.
 */
public final class RcsMlsControlResult implements Parcelable {

    /** Operation succeeded. */
    public static final int VERDICT_OK = 0;
    /** The group's era diverged from the server's view (Tachyon: INCORRECT_ERA). */
    public static final int VERDICT_ERA_GAP = 1;
    /** Server refused an external commit from an existing member (RFC 9420 §12.4.3.2 is
     *  non-member-only). */
    public static final int VERDICT_EXTERNAL_COMMIT_REFUSED = 2;
    /** A fresh group_id was offered for a conversation the server already holds. */
    public static final int VERDICT_GROUP_ID_CHANGED = 3;
    /** We are not registered/provisioned to perform this operation right now. */
    public static final int VERDICT_NOT_REGISTERED = 4;
    /** No server verdict at all — the request never completed. NOT a rejection. */
    public static final int VERDICT_TRANSPORT_FAILED = 5;
    /** Server rejected for a reason with no dedicated verdict yet; see {@link #detail}. */
    public static final int VERDICT_REJECTED = 6;
    /**
     * The server does not count this line as a member of the group — {@code TachyonError} wire 36
     * {@code USER_NOT_IN_GROUP}.
     *
     * <p><b>Not a verdict about the request.</b> The server refused before evaluating anything we
     * sent, because we are not in the group to send it to. That is why it is separate from
     * {@link #VERDICT_REJECTED}, which asserts the opposite — that the request WAS evaluated and
     * refused on its merits. Previously a 36 fell through to REJECTED and the distinction the wire
     * makes was discarded.
     *
     * <p><b>The remedy is the app's, and it is not "retry".</b> Google Messages' defined recovery
     * for this code is {@code REMOVE_SELF_FROM_GROUP}: the LOCAL membership is stale and should be retired.
     * Retrying, re-registering or advancing an era all do nothing — the provider names the
     * condition, the app owns the group state that has to change.
     *
     * <p><b>Adding this value is behaviour-neutral for an app that does not handle it</b>, which
     * was checked rather than assumed: every comparison against this field in messaging2 is
     * {@code == VERDICT_OK} / {@code != VERDICT_OK}, plus one {@code ERA_GAP || GROUP_ID_CHANGED}
     * divergence test. Nothing reads {@code VERDICT_REJECTED}, so moving 36 out of it changes no
     * existing branch. An int constant is also not a parcel-layout change — an older app simply
     * sees an unknown non-OK value, which is what it already did with the codes it does not name.
     */
    public static final int VERDICT_NOT_IN_GROUP = 7;

    public final int verdict;
    /** Raw response bytes on success (opaque to the provider); null otherwise. */
    @Nullable public final byte[] response;
    /** Human diagnostic. Never parse this — branch on {@link #verdict}. */
    @Nullable public final String detail;

    public RcsMlsControlResult(int verdict, @Nullable byte[] response, @Nullable String detail) {
        this.verdict = verdict;
        this.response = response;
        this.detail = detail;
    }

    public boolean ok() { return verdict == VERDICT_OK; }

    /** True when the request never reached a verdict — retryable, unlike a rejection. */
    public boolean transportFailed() { return verdict == VERDICT_TRANSPORT_FAILED; }

    protected RcsMlsControlResult(Parcel in) {
        verdict = in.readInt();
        response = in.createByteArray();
        detail = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(verdict);
        dest.writeByteArray(response);
        dest.writeString(detail);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<RcsMlsControlResult> CREATOR = new Creator<RcsMlsControlResult>() {
        @Override public RcsMlsControlResult createFromParcel(Parcel in) {
            return new RcsMlsControlResult(in);
        }
        @Override public RcsMlsControlResult[] newArray(int size) {
            return new RcsMlsControlResult[size];
        }
    };

    @Override public String toString() {
        return "RcsMlsControlResult{verdict=" + verdict
                + (response == null ? "" : " resp=" + response.length + "B")
                + (detail == null ? "" : " detail=" + detail) + "}";
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * The outcome of an MLS control operation, in spec-level terms. The provider translates its
 * backend's statuses into these verdicts before they cross, so any transport can report them;
 * {@link #detail} is for logs and is never parsed. See docs/rcs/provider-contract.md.
 */
public final class RcsMlsControlResult implements Parcelable {

    /** Succeeded. */
    public static final int VERDICT_OK = 0;
    /** The group's era diverged from the server's. */
    public static final int VERDICT_ERA_GAP = 1;
    /** An existing member attempted an external commit (RFC 9420 §12.4.3.2). */
    public static final int VERDICT_EXTERNAL_COMMIT_REFUSED = 2;
    /** A fresh group_id was offered for a conversation the server already holds. */
    public static final int VERDICT_GROUP_ID_CHANGED = 3;
    /** Not registered or provisioned right now. */
    public static final int VERDICT_NOT_REGISTERED = 4;
    /** The request never completed; retryable, not a rejection. */
    public static final int VERDICT_TRANSPORT_FAILED = 5;
    /** Evaluated and refused, with no dedicated verdict. */
    public static final int VERDICT_REJECTED = 6;
    /**
     * The server does not count this line as a member, so the request was never evaluated. The
     * local membership is stale; retrying, re-registering or advancing the era cannot help.
     */
    public static final int VERDICT_NOT_IN_GROUP = 7;

    public final int verdict;
    /** Opaque response bytes on success; null otherwise. */
    @Nullable public final byte[] response;
    /** Diagnostic text for logs; branch on {@link #verdict}, never on this. */
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

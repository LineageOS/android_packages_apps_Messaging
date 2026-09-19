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
 * The outcome of a KeyPackage claim, in <b>spec-level</b> terms — contract v60.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code claimPeerKeyPackages} returns {@code null} for every unhappy path, so the app could not
 * tell <b>"this peer has published nothing"</b> from <b>"the KDS would not talk to US"</b>.
 * Device-measured 2026-09-10: a claim returned grpc-16 UNAUTHENTICATED — the caller's
 * register token had expired behind a {@code tachyonerror=32} — and the app's ledger recorded it as
 * a fact about the PEER's pool. The peer was innocent and its pool was full; another line claimed
 * from it successfully seconds later. A wrong attribution here sends someone to the wrong device.
 *
 * <h2>THE VOCABULARY FIREWALL</h2>
 *
 * <p>The provider translates its transport status into the {@code OUTCOME_*} constants below before
 * they cross, exactly as {@link RcsMlsControlResult} does: <b>no gRPC status, no
 * {@code tachyonerror}, no Google error string ever reaches the app.</b> A carrier CPM/MSRP provider
 * must be able to report these same outcomes from a completely different protocol.
 * {@link #detail} is a human diagnostic for logs, MAY contain backend text, and callers
 * <b>MUST NOT parse it</b> — branch on {@link #outcome}.
 *
 * <h2>{@link #OUTCOME_SERVED} DOES NOT MEAN THE PACKAGES ARE ANY GOOD</h2>
 *
 * <p>Deliberately, and this is the trap worth stating twice. A device was observed answering a
 * claim perfectly with a leaf certificate <b>five weeks stale</b>: the RPC succeeded, the status
 * was OK, and the defect was entirely in the bytes. No value here can express that and none should
 * try — an enum that implied validity would re-create that invisibility one layer up. Only
 * inspecting the returned KeyPackage's certificate window answers "are these usable".
 */
public final class RcsMlsClaimResult implements Parcelable {

    /** The KDS answered and returned at least one package. Says NOTHING about the bytes. */
    public static final int OUTCOME_SERVED = 0;
    /** The KDS answered and the peer had nothing. <b>The only outcome that IS a fact about their
     *  pool</b> — not enrolled, or the pool is drained. */
    public static final int OUTCOME_PEER_HAS_NONE = 1;
    /** The KDS refused US. Says nothing whatever about the peer. */
    public static final int OUTCOME_NOT_AUTHORIZED = 2;
    /** The KDS refused the REQUEST for some other stated reason (wrong KDS instance, bad
     *  argument). Not evidence that the peer has no pool. */
    public static final int OUTCOME_REFUSED = 3;
    /** No answer at all — the request never completed. NOT a rejection. */
    public static final int OUTCOME_TRANSPORT_FAILED = 4;
    /** We never dialled: no credential to dial with, or nothing to ask for. */
    public static final int OUTCOME_NOT_ATTEMPTED = 5;

    public final int outcome;
    /**
     * The claimed KeyPackages, <b>length-prefixed</b> ({@code [u32 BE length][bytes]} repeated) —
     * the same packing {@code claimPeerKeyPackages} and {@code createGroupMulti} already use across
     * this boundary. Null when nothing was served.
     */
    @Nullable public final byte[] keyPackages;
    /** Human diagnostic. Never parse this — branch on {@link #outcome}. */
    @Nullable public final String detail;

    public RcsMlsClaimResult(int outcome, @Nullable byte[] keyPackages, @Nullable String detail) {
        this.outcome = outcome;
        this.keyPackages = keyPackages;
        this.detail = detail;
    }

    protected RcsMlsClaimResult(Parcel in) {
        outcome = in.readInt();
        keyPackages = in.createByteArray();
        detail = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(outcome);
        dest.writeByteArray(keyPackages);
        dest.writeString(detail);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsMlsClaimResult> CREATOR = new Creator<RcsMlsClaimResult>() {
        @Override public RcsMlsClaimResult createFromParcel(Parcel in) {
            return new RcsMlsClaimResult(in);
        }
        @Override public RcsMlsClaimResult[] newArray(int size) {
            return new RcsMlsClaimResult[size];
        }
    };

    @Override
    public String toString() {
        return "RcsMlsClaimResult{outcome=" + outcome + " bytes="
                + (keyPackages == null ? 0 : keyPackages.length) + " detail=" + detail + "}";
    }
}

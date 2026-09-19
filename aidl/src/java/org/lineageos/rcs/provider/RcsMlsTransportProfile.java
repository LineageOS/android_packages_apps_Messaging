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

/**
 * What a transport does about MLS <b>arbitration</b> — the inputs the app's recovery policy needs in
 * order to stop hardcoding one backend's behaviour.
 *
 * <p><b>Describes a transport INSTANCE, not a family.</b> "Carrier" does not imply these values:
 * RCC.16 §6.1.2/§8.1 defines a Conversation Focus that arbitrates Commits and Proposals, so a full
 * carrier deployment would answer very differently from our current peer-to-peer MSRP lab path. Each
 * implementation reports what IT does; nobody infers it from the transport's name.
 *
 * <p>Derived from two real implementations rather than imagined:
 *
 * <table>
 *   <tr><th></th><th>Tachyon (the RCS provider app)</th><th>carrier MSRP (messaging2 lab)</th></tr>
 *   <tr><td>{@link #serverArbitratesEra}</td><td>true — rejects a stale era/epoch-authenticator</td>
 *       <td>false — peer-to-peer, era is client-managed from 1</td></tr>
 *   <tr><td>{@link #requiresConvergenceAck}</td><td>true — a 66-byte control ACK signals the peer
 *       converged</td><td>false — the Welcome rides MSRP directly, nothing to await</td></tr>
 *   <tr><td>{@link #acceptsMemberExternalCommit}</td><td>false — refuses ExternalInit from a member
 *       (RFC 9420 §12.4.3.2)</td><td>false — no server to ask</td></tr>
 *   <tr><td>{@link #hasServerGroupInfo}</td><td>true — an authoritative GroupInfo is fetchable</td>
 *       <td>false — no focus, no authoritative copy</td></tr>
 * </table>
 *
 * <p>Why this matters concretely: the app's send-gate exists solely to await peer convergence. On a
 * transport with no convergence signal there is nothing to wait for, so opening a gate would stall
 * sends forever waiting for an event that cannot arrive — a hang caused purely by carrying one
 * backend's assumptions into another.
 */
public final class RcsMlsTransportProfile implements Parcelable {

    /** A server validates era/epoch and rejects a stale one (so divergence is detectable remotely). */
    public final boolean serverArbitratesEra;
    /** Outbound app messages must await a peer-convergence signal after a commit. */
    public final boolean requiresConvergenceAck;
    /** An existing member may send an {@code ExternalInit} commit. */
    public final boolean acceptsMemberExternalCommit;
    /** An authoritative server-side GroupInfo can be fetched for divergence detection. */
    public final boolean hasServerGroupInfo;

    public RcsMlsTransportProfile(boolean serverArbitratesEra, boolean requiresConvergenceAck,
            boolean acceptsMemberExternalCommit, boolean hasServerGroupInfo) {
        this.serverArbitratesEra = serverArbitratesEra;
        this.requiresConvergenceAck = requiresConvergenceAck;
        this.acceptsMemberExternalCommit = acceptsMemberExternalCommit;
        this.hasServerGroupInfo = hasServerGroupInfo;
    }

    /**
     * The conservative default for an unknown/unreachable transport: assume nothing is arbitrated and
     * nothing must be awaited.
     *
     * <p>Chosen so a missing profile cannot HANG a send. The opposite default (assume a convergence
     * ACK is required) would make an unbound provider look identical to "waiting forever", which is
     * the worse failure — a stalled conversation with no error anywhere.
     */
    public static RcsMlsTransportProfile conservativeDefault() {
        return new RcsMlsTransportProfile(false, false, false, false);
    }

    protected RcsMlsTransportProfile(Parcel in) {
        serverArbitratesEra = in.readInt() != 0;
        requiresConvergenceAck = in.readInt() != 0;
        acceptsMemberExternalCommit = in.readInt() != 0;
        hasServerGroupInfo = in.readInt() != 0;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(serverArbitratesEra ? 1 : 0);
        dest.writeInt(requiresConvergenceAck ? 1 : 0);
        dest.writeInt(acceptsMemberExternalCommit ? 1 : 0);
        dest.writeInt(hasServerGroupInfo ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<RcsMlsTransportProfile> CREATOR =
            new Creator<RcsMlsTransportProfile>() {
        @Override public RcsMlsTransportProfile createFromParcel(Parcel in) {
            return new RcsMlsTransportProfile(in);
        }
        @Override public RcsMlsTransportProfile[] newArray(int size) {
            return new RcsMlsTransportProfile[size];
        }
    };

    @Override public String toString() {
        return "RcsMlsTransportProfile{arbitratesEra=" + serverArbitratesEra
                + " convergenceAck=" + requiresConvergenceAck
                + " memberExternalCommit=" + acceptsMemberExternalCommit
                + " serverGroupInfo=" + hasServerGroupInfo + "}";
    }
}

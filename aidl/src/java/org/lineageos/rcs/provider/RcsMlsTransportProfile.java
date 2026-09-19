/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * What a transport instance does about MLS arbitration, so the app's recovery policy can adapt to
 * it. It describes an instance, not a family: a carrier deployment with a Conversation Focus
 * (RCC.16 §6.1.2) answers differently from a peer-to-peer one. A send gate must never be opened
 * against a transport without {@link #requiresConvergenceAck}, since nothing could close it.
 */
public final class RcsMlsTransportProfile implements Parcelable {

    /** A server validates era and epoch and rejects a stale one. */
    public final boolean serverArbitratesEra;
    /** Outbound app messages must await a peer-convergence signal after a commit. */
    public final boolean requiresConvergenceAck;
    /** An existing member may send an {@code ExternalInit} commit. */
    public final boolean acceptsMemberExternalCommit;
    /** An authoritative server GroupInfo can be fetched. */
    public final boolean hasServerGroupInfo;

    public RcsMlsTransportProfile(boolean serverArbitratesEra, boolean requiresConvergenceAck,
            boolean acceptsMemberExternalCommit, boolean hasServerGroupInfo) {
        this.serverArbitratesEra = serverArbitratesEra;
        this.requiresConvergenceAck = requiresConvergenceAck;
        this.acceptsMemberExternalCommit = acceptsMemberExternalCommit;
        this.hasServerGroupInfo = hasServerGroupInfo;
    }

    /**
     * All false: nothing arbitrated, nothing awaited. Chosen so a missing profile cannot hang a
     * send.
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

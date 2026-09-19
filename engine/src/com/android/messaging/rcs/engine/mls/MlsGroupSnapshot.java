/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Where a group landed, captured when an operation produced its result, so the caller never reads
 * the position back separately and races a concurrent commit. Immutable; arrays are copied in and
 * out. {@link #describe()} prints only the epoch authenticator's length.
 */
public final class MlsGroupSnapshot {

    /** Sentinel for "this operation touched no group"; never {@code null}. */
    public static final MlsGroupSnapshot NONE = new MlsGroupSnapshot(null, -1, -1L, null);

    private final byte[] mGroupId;
    /** The RCC.16 era, or {@code -1} if unknown. */
    public final int era;
    /** The MLS epoch, or {@code -1} if unknown. */
    public final long epoch;
    private final byte[] mEpochAuthenticator;

    public MlsGroupSnapshot(final byte[] groupId, final int era, final long epoch,
            final byte[] epochAuthenticator) {
        mGroupId = copy(groupId);
        this.era = era;
        this.epoch = epoch;
        mEpochAuthenticator = copy(epochAuthenticator);
    }

    /** @return a copy of the MLS group id, or {@code null} */
    public byte[] groupId() { return copy(mGroupId); }

    /** @return a copy of the epoch authenticator, or {@code null} */
    public byte[] epochAuthenticator() { return copy(mEpochAuthenticator); }

    /** Whether this snapshot carries a usable position. */
    public boolean isKnown() { return era >= 0 && epoch >= 0L; }

    /** Whether this snapshot names a group at all. */
    public boolean hasGroup() { return mGroupId != null && mGroupId.length > 0; }

    /**
     * Whether {@code other} is strictly ahead of this one. Era dominates epoch, since the epoch
     * restarts on an era advance.
     */
    public boolean isBehind(final MlsGroupSnapshot other) {
        if (other == null || !other.isKnown() || !isKnown()) return false;
        if (other.era != era) return other.era > era;
        return other.epoch > epoch;
    }

    /**
     * Whether {@code other} describes the same state: group, position and epoch authenticator. A
     * drive loop uses it to tell progress from a repeat. Not {@code equals()}: two {@link #NONE}
     * snapshots are identical here.
     */
    public boolean identicalTo(final MlsGroupSnapshot other) {
        if (other == null) return false;
        if (other == this) return true;
        return era == other.era && epoch == other.epoch
                && java.util.Arrays.equals(mGroupId, other.mGroupId)
                && java.util.Arrays.equals(mEpochAuthenticator, other.mEpochAuthenticator);
    }

    /** Renders lengths only. */
    public String describe() {
        if (!hasGroup() && !isKnown()) return "snapshot{none}";
        return "snapshot{gid=" + (mGroupId == null ? "null" : mGroupId.length + "B")
                + " era=" + era
                + " epoch=" + epoch
                + " epochAuth=" + (mEpochAuthenticator == null
                        ? "null" : mEpochAuthenticator.length + "B") + "}";
    }

    @Override public String toString() { return describe(); }

    private static byte[] copy(final byte[] b) {
        if (b == null) return null;
        final byte[] out = new byte[b.length];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }
}

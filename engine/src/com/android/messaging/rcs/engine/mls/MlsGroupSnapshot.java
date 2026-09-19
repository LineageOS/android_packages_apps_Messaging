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
package com.android.messaging.rcs.engine.mls;

/**
 * Where a group landed, captured AT the moment an operation produced its result (rework item 4.1b).
 *
 * <p>Google Messages attaches {@code (group_id, epoch_id, era_id, epoch_authenticator)} to every result
 * unconditionally — fields 9, 11, 12 and 14 of {@code ZinniaResult}. We attach none, and instead the
 * caller makes a SEPARATE {@code nativeEraEpoch} call after every mutating operation to find out
 * what just happened.
 *
 * <p>That is a TOCTOU seam, not a style difference. Between the mutation and the follow-up read the
 * group can move again — an inbound commit applying on another thread is the ordinary case, not an
 * exotic one — and the value the caller then persists describes a state its own operation never
 * produced. The bug this creates is quiet: the record says epoch N+2 for an operation that landed at
 * N+1, and nothing downstream can tell.
 *
 * <p>So: a mutating call returns where it landed, and the caller never has to ask.
 *
 * <h2>An epoch authenticator is a fingerprint, not a secret</h2>
 *
 * <p>It is safe to log its length and to compare it; it is not safe to assume two members agreeing
 * on it means they agree on membership — that is what the RCC.16 checks are for. {@link #describe()}
 * deliberately prints only its length.
 *
 * <p>Immutable, and the byte arrays are defensively copied on both the way in and the way out: a
 * snapshot whose contents can be edited after the fact is not a snapshot.
 */
public final class MlsGroupSnapshot {

    /** Sentinel for "this operation touched no group" — never {@code null}, so callers need no branch. */
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
     * Whether {@code other} is strictly ahead of this one.
     *
     * <p>Era dominates epoch: an era advance rebuilds the group, so its epoch counter restarts and a
     * numerically smaller epoch in a later era is still forward progress. Comparing epochs alone —
     * the intuitive reading — reports an era advance as going BACKWARDS.
     */
    public boolean isBehind(final MlsGroupSnapshot other) {
        if (other == null || !other.isKnown() || !isKnown()) return false;
        if (other.era != era) return other.era > era;
        return other.epoch > epoch;
    }

    /**
     * Whether {@code other} describes the SAME state — group, position and epoch authenticator all
     * identical.
     *
     * <p>Distinct from {@link #isBehind}, which answers "which is further along?" and says nothing
     * when the two are equal. This answers "did anything move?", which is what a drive loop needs to
     * tell a pass that progressed from a pass that re-read the world and reported the same thing.
     *
     * <p>Deliberately NOT {@code equals()}. Two {@link #NONE} snapshots are identical here — the
     * honest reading, because a pass that cannot say where the group landed has given no evidence of
     * movement — and that is a useful answer for a loop while being a surprising one for a hash key.
     */
    public boolean identicalTo(final MlsGroupSnapshot other) {
        if (other == null) return false;
        if (other == this) return true;
        return era == other.era && epoch == other.epoch
                && java.util.Arrays.equals(mGroupId, other.mGroupId)
                && java.util.Arrays.equals(mEpochAuthenticator, other.mEpochAuthenticator);
    }

    /** Length-only rendering — see the class doc on the epoch authenticator. */
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

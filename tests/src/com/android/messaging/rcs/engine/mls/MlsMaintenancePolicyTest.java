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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Google Messages' {@code maybeRefresh} gate. */
public final class MlsMaintenancePolicyTest {

    /**
     * The case that explains our first silent no-move: no delta, so no request.
     *
     * <p>A blind 2→3 with no new member and a 70-second-old era had zero membership delta. Google Messages'
     * own code would never have generated it, so we were off-path before the server ever saw it.
     * This is the assertion that stops that being written again.
     */
    @Test
    public void noDelta_isNotWarranted() {
        assertEquals(MlsMaintenancePolicy.Refresh.NOT_NEEDED,
                MlsMaintenancePolicy.evaluate(0, 0, /*metadataKeysRequestPresent=*/ true));
        assertFalse(MlsMaintenancePolicy.evaluate(0, 0, true).warranted());
    }

    /**
     * New members WITHOUT the server's metadata-keys-request extension is NOT warranted.
     *
     * <p>Google Messages' string is one sentence with an "and" — the extension present in the SERVER group
     * info, AND new members added. Treating the extension as decoration would issue an advance the
     * server never asked for, which is exactly the shape of request that gets silently ignored.
     */
    @Test
    public void newMembersWithoutTheExtension_isNotWarranted() {
        assertEquals(MlsMaintenancePolicy.Refresh.NOT_NEEDED,
                MlsMaintenancePolicy.evaluate(3, 0, /*metadataKeysRequestPresent=*/ false));
    }

    @Test
    public void newMembersWithTheExtension_isWarranted() {
        assertEquals(MlsMaintenancePolicy.Refresh.NEW_MEMBERS,
                MlsMaintenancePolicy.evaluate(1, 0, true));
    }

    /** The expiry arm has no extension precondition in any observed string, so it is not given one. */
    @Test
    public void expiredMembers_areWarrantedWithoutTheExtension() {
        assertEquals(MlsMaintenancePolicy.Refresh.EXPIRED_MEMBERS,
                MlsMaintenancePolicy.evaluate(0, 2, /*metadataKeysRequestPresent=*/ false));
    }

    /** Both reasons at once is ONE advance, not two. */
    @Test
    public void bothReasons_collapseToOneAdvance() {
        assertEquals(MlsMaintenancePolicy.Refresh.BOTH,
                MlsMaintenancePolicy.evaluate(2, 2, true));
        assertTrue(MlsMaintenancePolicy.Refresh.BOTH.warranted());
    }

    /** Negative/garbage counts must not be read as a delta. */
    @Test
    public void negativeCountsAreNotADelta() {
        assertEquals(MlsMaintenancePolicy.Refresh.NOT_NEEDED,
                MlsMaintenancePolicy.evaluate(-1, -1, true));
    }

    /**
     * <b>A fork is not a refresh, and it is not "nothing to do" either.</b>
     *
     * <p>{@code DIVERGED} exists because the transport's callers log the pass's verdict and had no
     * way to say that the pass STOPPED: the group it was asked to maintain is a different group at
     * the same position. {@code NOT_NEEDED} would have reported that as a conversation with no
     * membership delta, which is true and beside the point.
     *
     * <p>Two things have to hold together, and each one is a way the value could be wrong:
     * {@link MlsMaintenancePolicy.Refresh#warranted()} must be FALSE — nothing about an era advance
     * crosses from one group to another, and issuing one would rebuild the FORK around the server's
     * roster — and {@link MlsMaintenancePolicy#evaluate} must never produce it, because this policy
     * decides membership deltas and the identity question is asked before a delta means anything.
     */
    @Test
    public void aFork_isNotWarrantedAndIsNotAMembershipVerdict() {
        assertFalse("an era advance re-creates the group it is issued on; issued on a fork it "
                + "re-creates the fork", MlsMaintenancePolicy.Refresh.DIVERGED.warranted());
        assertFalse(MlsMaintenancePolicy.Refresh.NOT_NEEDED.warranted());
        assertTrue(MlsMaintenancePolicy.Refresh.NEW_MEMBERS.warranted());
        assertTrue(MlsMaintenancePolicy.Refresh.EXPIRED_MEMBERS.warranted());
        assertTrue(MlsMaintenancePolicy.Refresh.BOTH.warranted());
        // evaluate() decides a DELTA. It has no input that could carry an identity, so it must not
        // be able to reach this verdict from any combination of the three it does have.
        for (int added = -1; added <= 3; added++) {
            for (int expired = -1; expired <= 3; expired++) {
                for (int k = 0; k < 2; k++) {
                    assertNotEquals("evaluate() reached DIVERGED from (" + added + ", " + expired
                                    + ", " + (k == 1) + "). Whether the group is ours is not a "
                                    + "function of a membership delta and must not be inferable "
                                    + "from one.",
                            MlsMaintenancePolicy.Refresh.DIVERGED,
                            MlsMaintenancePolicy.evaluate(added, expired, k == 1));
                }
            }
        }
    }

    /**
     * The §20.4 lines are emitted verbatim, in Google Messages' Rust {@code {:?}} quoting.
     *
     * <p>A trace diff matches these as literals; a paraphrase breaks the comparison exactly when a
     * membership regression is what you are looking for.
     */
    @Test
    public void theTraceLinesUseTheReferenceClientsWording() {
        assertEquals("Group metadata keys request extension present in the server group info for "
                        + "group \"g1\"; and new members added; will be requesting a new era.",
                MlsMaintenancePolicy.newMembersLine("g1"));
        assertEquals("Generating new request to refresh others as the list of expired members has "
                        + "changed. For group: \"g1\"",
                MlsMaintenancePolicy.expiredMembersChangedLine("g1"));
        assertEquals("Expired members count: 0, for group: \"g1\"",
                MlsMaintenancePolicy.expiredCountLine(0, "g1"));
        assertEquals("Member with client ID: \"c1\" needs key rotation, for group: \"g1\"",
                MlsMaintenancePolicy.needsRotationLine("c1", "g1"));
    }

    /**
     * The sweep page, moved here by Stage 6 — a BURST bound, not a throughput number.
     *
     * <p>The boundary matters in the direction that is easy to get wrong: the production counter is
     * incremented only for entries actually FETCHED, and the question is asked before the fetch, so
     * a page of four means four fetches per triggering event and not three.
     */
    @Test
    public void theSweepPageIsFourFetchesPerTrigger() {
        assertEquals(4, MlsMaintenancePolicy.SWEEP_PAGE);
        assertFalse(MlsMaintenancePolicy.sweepPageExhausted(0));
        assertFalse(MlsMaintenancePolicy.sweepPageExhausted(MlsMaintenancePolicy.SWEEP_PAGE - 1));
        assertTrue(MlsMaintenancePolicy.sweepPageExhausted(MlsMaintenancePolicy.SWEEP_PAGE));
    }

}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link MlsMaintenancePolicy}: when a maintenance pass warrants a new era. */
public final class MlsMaintenancePolicyTest {

    /** No membership delta, no request: other clients never issue a delta-free advance. */
    @Test
    public void noDelta_isNotWarranted() {
        assertEquals(MlsMaintenancePolicy.Refresh.NOT_NEEDED,
                MlsMaintenancePolicy.evaluate(0, 0, /*metadataKeysRequestPresent=*/ true));
        assertFalse(MlsMaintenancePolicy.evaluate(0, 0, true).warranted());
    }

    /**
     * New members without the server's metadata-keys-request extension are not warranted: the rule
     * needs both, and an advance the server never asked for is ignored.
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

    /** The expiry arm has no extension precondition. */
    @Test
    public void expiredMembers_areWarrantedWithoutTheExtension() {
        assertEquals(MlsMaintenancePolicy.Refresh.EXPIRED_MEMBERS,
                MlsMaintenancePolicy.evaluate(0, 2, /*metadataKeysRequestPresent=*/ false));
    }

    /** Both reasons at once is one advance, not two. */
    @Test
    public void bothReasons_collapseToOneAdvance() {
        assertEquals(MlsMaintenancePolicy.Refresh.BOTH,
                MlsMaintenancePolicy.evaluate(2, 2, true));
        assertTrue(MlsMaintenancePolicy.Refresh.BOTH.warranted());
    }

    /** Negative counts are not a delta. */
    @Test
    public void negativeCountsAreNotADelta() {
        assertEquals(MlsMaintenancePolicy.Refresh.NOT_NEEDED,
                MlsMaintenancePolicy.evaluate(-1, -1, true));
    }

    /**
     * A fork is not warranted (an advance on it rebuilds the fork) and never comes out of
     * {@link MlsMaintenancePolicy#evaluate}, which decides deltas, not identity.
     */
    @Test
    public void aFork_isNotWarrantedAndIsNotAMembershipVerdict() {
        assertFalse("an era advance re-creates the group it is issued on; issued on a fork it "
                + "re-creates the fork", MlsMaintenancePolicy.Refresh.DIVERGED.warranted());
        assertFalse(MlsMaintenancePolicy.Refresh.NOT_NEEDED.warranted());
        assertTrue(MlsMaintenancePolicy.Refresh.NEW_MEMBERS.warranted());
        assertTrue(MlsMaintenancePolicy.Refresh.EXPIRED_MEMBERS.warranted());
        assertTrue(MlsMaintenancePolicy.Refresh.BOTH.warranted());
        // evaluate() has no input that carries an identity, so no combination may reach DIVERGED.
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
     * The trace lines match other clients' wording verbatim, so a trace diff can compare them as
     * literals.
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

    /** A burst bound, asked before each fetch: a page of four is four fetches per trigger. */
    @Test
    public void theSweepPageIsFourFetchesPerTrigger() {
        assertEquals(4, MlsMaintenancePolicy.SWEEP_PAGE);
        assertFalse(MlsMaintenancePolicy.sweepPageExhausted(0));
        assertFalse(MlsMaintenancePolicy.sweepPageExhausted(MlsMaintenancePolicy.SWEEP_PAGE - 1));
        assertTrue(MlsMaintenancePolicy.sweepPageExhausted(MlsMaintenancePolicy.SWEEP_PAGE));
    }

}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link MlsUpgradePolicy}: the guards that decide whether a conversation upgrades to MLS. */
public final class MlsUpgradePolicyTest {

    /** Everything fine: two participants, two claimable key packages. */
    private static MlsUpgradePolicy.Decision healthy() {
        return MlsUpgradePolicy.evaluate(/*online=*/ true, /*dummy=*/ false, /*alreadyMls=*/ false,
                /*metadata=*/ true, /*selfKp=*/ true, "grp", /*initializing=*/ false,
                /*claimed=*/ 2, /*participants=*/ 2, /*engineGroupExists=*/ false);
    }

    @Test
    public void theHealthyCaseUpgrades() {
        assertTrue(healthy().proceed());
    }

    /** The key-package guard is all-or-nothing: one member short holds the whole group. */
    @Test
    public void oneMissingKeyPackageBlocksTheWholeUpgrade() {
        final MlsUpgradePolicy.Decision d = MlsUpgradePolicy.evaluate(true, false, false, true,
                true, "grp", false, /*claimed=*/ 2, /*participants=*/ 3, false);
        assertEquals(MlsUpgradePolicy.Decision.NOT_ENOUGH_KEY_PACKAGES, d);
        assertEquals("Skip conversation update because keyPackage count 2 is less than remote "
                + "participants count 3", MlsUpgradePolicy.notEnoughKeyPackagesLine(2, 3));
    }

    /** With nobody to encrypt to, {@code 0 >= 0} must not pass the key-package guard. */
    @Test
    public void anEmptyRosterIsNotAnUpgradeCandidate() {
        final MlsUpgradePolicy.Decision d = MlsUpgradePolicy.evaluate(true, false, false, true,
                true, "grp", false, /*claimed=*/ 0, /*participants=*/ 0, false);
        assertTrue("0 >= 0 must not read as 'enough key packages'", !d.proceed());
    }

    /**
     * Guard order is part of the contract: connectivity before a round trip, identity before a
     * claim. The first failing guard's line is the reported diagnosis.
     */
    @Test
    public void theCheapGuardsWinWhenSeveralApply() {
        final MlsUpgradePolicy.Decision d = MlsUpgradePolicy.evaluate(/*online=*/ false, false,
                /*alreadyMls=*/ true, true, true, "grp", false, 0, 3, true);
        assertEquals(MlsUpgradePolicy.Decision.OFFLINE, d);
    }

    @Test
    public void eachGuardReportsItsOwnReason() {
        assertEquals(MlsUpgradePolicy.Decision.DUMMY_TOKEN, MlsUpgradePolicy.evaluate(
                true, /*dummy=*/ true, false, true, true, "grp", false, 2, 2, false));
        assertEquals(MlsUpgradePolicy.Decision.ALREADY_MLS, MlsUpgradePolicy.evaluate(
                true, false, /*alreadyMls=*/ true, true, true, "grp", false, 2, 2, false));
        assertEquals(MlsUpgradePolicy.Decision.NO_METADATA, MlsUpgradePolicy.evaluate(
                true, false, false, /*metadata=*/ false, true, "grp", false, 2, 2, false));
        assertEquals(MlsUpgradePolicy.Decision.NO_SELF_KEY_PACKAGES, MlsUpgradePolicy.evaluate(
                true, false, false, true, /*selfKp=*/ false, "grp", false, 2, 2, false));
        assertEquals(MlsUpgradePolicy.Decision.NULL_GROUP_ID, MlsUpgradePolicy.evaluate(
                true, false, false, true, true, /*rcsGroupId=*/ null, false, 2, 2, false));
        assertEquals(MlsUpgradePolicy.Decision.INITIALIZING, MlsUpgradePolicy.evaluate(
                true, false, false, true, true, "grp", /*initializing=*/ true, 2, 2, false));
        assertEquals(MlsUpgradePolicy.Decision.GROUP_EXISTS, MlsUpgradePolicy.evaluate(
                true, false, false, true, true, "grp", false, 2, 2, /*engineExists=*/ true));
    }

    /** Every skip carries its verbatim line, matched as a literal when traces are diffed. */
    @Test
    public void everySkipCarriesTheReferenceClientsVerbatimLine() {
        for (final MlsUpgradePolicy.Decision d : MlsUpgradePolicy.Decision.values()) {
            if (d == MlsUpgradePolicy.Decision.PROCEED) continue;
            if (d == MlsUpgradePolicy.Decision.NOT_ENOUGH_KEY_PACKAGES) {
                // Formatted with two counts; covered above.
                continue;
            }
            assertNotNull(d + " has no line; a silent skip is the failure mode this is about",
                    d.line());
            assertTrue(d + "'s line should be a sentence", d.line().length() > 20);
        }
    }
}

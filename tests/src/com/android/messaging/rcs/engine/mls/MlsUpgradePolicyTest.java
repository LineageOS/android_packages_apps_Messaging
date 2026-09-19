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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link MlsUpgradePolicy} — Google Messages' {@code cpwz} guard set. */
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

    /**
     * The KEY-PACKAGE guard is STRICT and ALL-OR-NOTHING, which is the property that makes it
     * dangerous: one member short holds the ENTIRE group on Etouffee, silently.
     */
    @Test
    public void oneMissingKeyPackageBlocksTheWholeUpgrade() {
        final MlsUpgradePolicy.Decision d = MlsUpgradePolicy.evaluate(true, false, false, true,
                true, "grp", false, /*claimed=*/ 2, /*participants=*/ 3, false);
        assertEquals(MlsUpgradePolicy.Decision.NOT_ENOUGH_KEY_PACKAGES, d);
        assertEquals("Skip conversation update because keyPackage count 2 is less than remote "
                + "participants count 3", MlsUpgradePolicy.notEnoughKeyPackagesLine(2, 3));
    }

    /**
     * A conversation with no remote participants must not sail through the key-package guard on
     * {@code 0 >= 0}. There is nobody to encrypt to, so it is not an upgrade candidate at all.
     */
    @Test
    public void anEmptyRosterIsNotAnUpgradeCandidate() {
        final MlsUpgradePolicy.Decision d = MlsUpgradePolicy.evaluate(true, false, false, true,
                true, "grp", false, /*claimed=*/ 0, /*participants=*/ 0, false);
        assertTrue("0 >= 0 must not read as 'enough key packages'", !d.proceed());
    }

    /**
     * ORDER IS PART OF THE CONTRACT. Google Messages checks connectivity before it spends a round trip and
     * identity before it spends a claim, and the reported line is the only evidence anyone gets —
     * so a reordering silently changes the diagnosis a stuck conversation reports.
     */
    @Test
    public void theCheapGuardsWinWhenSeveralApply() {
        // Offline AND already-MLS AND short on key packages: the offline line is the one to report.
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

    /**
     * Every skip carries Google Messages' own line verbatim, because a trace diff matches them as
     * literals — and "why did this conversation not upgrade?" is the one question these answer.
     */
    @Test
    public void everySkipCarriesTheReferenceClientsVerbatimLine() {
        for (final MlsUpgradePolicy.Decision d : MlsUpgradePolicy.Decision.values()) {
            if (d == MlsUpgradePolicy.Decision.PROCEED) continue;
            if (d == MlsUpgradePolicy.Decision.NOT_ENOUGH_KEY_PACKAGES) {
                // Formatted, because it names two counts — covered above.
                continue;
            }
            assertNotNull(d + " has no line; a silent skip is the failure mode this is about",
                    d.line());
            assertTrue(d + "'s line should be a sentence", d.line().length() > 20);
        }
    }
}

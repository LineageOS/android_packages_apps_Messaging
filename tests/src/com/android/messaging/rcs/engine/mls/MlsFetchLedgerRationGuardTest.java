/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * {@link MlsFetchLedger.Caller#COMMIT_OUTCOME_CHECK}'s ration must follow the number of looks one
 * invocation of its caller spends, so a look added to that method cannot silently halve how many
 * invocations the window allows. See docs/mls/budgets.md.
 */
public final class MlsFetchLedgerRationGuardTest {

    private static final String CALLER = "keepUnacknowledgedCommit";
    private static final String CHARGE = "MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK";

    /**
     * Two invocations per window is the design point: the hold bound permits one unacknowledged
     * commit per conversation, so a second silent commit is the most that can usefully be resolved.
     */
    @Test
    public void theRationMatchesWhatOneInvocationActuallyCosts() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), CALLER);
        assertTrue(CALLER + "() is gone or was renamed, so this guard is reading an empty string "
                + "and would certify the ration against a method that no longer exists.",
                !body.isEmpty());

        final int chargeSites = SourceScan.count(body, CHARGE);
        assertTrue(CALLER + "() no longer charges " + CHARGE
                + " at all. Either the ledger call was "
                + "removed — making an unbounded GetMlsGroupInfo — or the caller was renamed and "
                + "this guard is watching nothing.", chargeSites > 0);

        final int allowedInvocations = 2;
        assertEquals(CHARGE + "'s ration is " + MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK.ration
                        + " but " + CALLER + "() now spends " + chargeSites + " look(s) per "
                        + "invocation, so the door permits "
                        + (MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK.ration / (double) chargeSites)
                        + " invocations per window rather than " + allowedInvocations + ". A look "
                        + "added to an already-ledgered method halves the door without failing "
                        + "anything: the next check is refused on its FIRST look and scored "
                        + "UNREADABLE, so 'we could not establish' stands in for 'we did not ask'. "
                        + "Either raise the ration to " + (chargeSites * allowedInvocations)
                        + " or argue here why fewer invocations per window is right now.",
                chargeSites * allowedInvocations,
                MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK.ration);
    }

    /**
     * The audit read runs on a path that has already failed, where the shared ceiling is most
     * likely drawn down; charging it there would refuse the commits whose fate is least knowable.
     */
    @Test
    public void raisingTheRationIsNotUndoneByTheSharedCeiling() {
        assertTrue(
                "COMMIT_OUTCOME_CHECK now charges the shared ceiling, so the ration above is not "
                + "the binding constraint and the extra look it pays for can be refused by spend "
                + "that has nothing to do with this door.",
                !MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK.chargesTheSharedCeiling);
    }
}

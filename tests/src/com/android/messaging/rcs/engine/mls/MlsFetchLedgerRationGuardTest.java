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

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * <b>A ration must be a function of what one invocation COSTS, not a number somebody typed once.</b>
 *
 * <h2>The failure this exists to stop, measured rather than imagined</h2>
 *
 * <p>{@link MlsFetchLedger.Caller#COMMIT_OUTCOME_CHECK} was set to <b>2</b> when its caller spent one
 * look. The identity fix added a second charge to the same method — the
 * {@code fetchServerEpochAuthenticator} that settles whose chain the server's epoch belongs to — and
 * the constant did not move. Device-measured on deviceB, 2026-09-11 22:56:12:
 *
 * <pre>
 *   22:56:12.127  charged getMlsServerEraEpoch to COMMIT_OUTCOME_CHECK — 1 of 2
 *   22:56:12.301  charged fetchServerEpochAuthenticator to COMMIT_OUTCOME_CHECK — 2 of 2
 * </pre>
 *
 * <p>One silent commit, 174 ms, the whole allowance. <b>The door had silently halved</b>, and the
 * symptom would not have looked like a budget problem: a second silent commit inside the window is
 * refused on its FIRST look and scored {@code UNREADABLE} — "we could not establish" standing in for
 * "we did not ask", which is precisely the collapse {@code LOOK_REFUSED} was split out
 * of {@code UNKNOWN} to end. Nothing fails; an arm is simply taken for the wrong reason.
 *
 * <h2>Why this is a guard and not a comment</h2>
 *
 * <p>Adding a look to an already-ledgered method is the easiest possible change to make without
 * thinking about budget: the charge point already exists, the call compiles, the tests pass, and the
 * only evidence is a counter on a device nobody is watching. So the constant is tied here to the
 * COUNT of charge sites in its caller's body. A third look cannot be added without this failing and
 * the number being re-argued — which is the argument being forced, not the arithmetic.
 *
 * <p><b>Scoped to the one caller whose cost per invocation is not one.</b> Every other
 * {@code Caller} charges once per invocation, so ration and invocation-count coincide and there is
 * nothing to tie. Generalising this to the whole enum would assert a relationship that does not hold
 * for any of them.
 */
public final class MlsFetchLedgerRationGuardTest {

    /** The method that spends {@link MlsFetchLedger.Caller#COMMIT_OUTCOME_CHECK}. */
    private static final String CALLER = "keepUnacknowledgedCommit";
    private static final String CHARGE = "MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK";

    /**
     * The ration equals the charge-site count times the invocations the door is meant to allow.
     *
     * <p>Two invocations is the design point and is stated here rather than derived: the hold bound
     * permits ONE unacknowledged commit per conversation, so a second silent commit in the same
     * window is the most that can usefully be resolved before the bound itself takes over.
     */
    @Test
    public void theRationMatchesWhatOneInvocationActuallyCosts() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), CALLER);
        assertTrue(CALLER + "() is gone or was renamed, so this guard is reading an empty string "
                + "and would certify the ration against a method that no longer exists.",
                !body.isEmpty());

        final int chargeSites = SourceScan.count(body, CHARGE);
        assertTrue(CALLER + "() no longer charges " + CHARGE + " at all. Either the ledger call was "
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
     * The caller stays outside the shared ceiling, which is the other half of its design.
     *
     * <p>This read happens on a path that has ALREADY failed once, so the conversation it audits is
     * by construction the one most likely to have drawn its ceiling down on the failure. Charging it
     * there would refuse exactly the commits whose fate is least knowable — and raising the ration
     * would not help, because the ceiling would bind first.
     */
    @Test
    public void raisingTheRationIsNotUndoneByTheSharedCeiling() {
        assertTrue("COMMIT_OUTCOME_CHECK now charges the shared ceiling, so the ration above is not "
                + "the binding constraint and the extra look it pays for can be refused by spend "
                + "that has nothing to do with this door.",
                !MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK.chargesTheSharedCeiling);
    }
}

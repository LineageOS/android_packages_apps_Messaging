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

import java.io.IOException;

import org.junit.Test;

/**
 * <b>A HEALTH VERDICT REACHED WITHOUT RUNNING THE DIVERGED TEST MUST NOT BE SPENDABLE AS A VERIFIED
 * ONE</b> — the blindness half.
 *
 * <h2>What was wrong, and why the log line was not enough</h2>
 *
 * <p>{@code healthAgainstServer} already logged the refusal in full — "the DIVERGED test was NOT RUN
 * … this verdict rests on numbers alone, which is the comparison already proved insufficient" — and
 * then returned plain {@code Health.IN_SYNC}. {@code detectHealth} took {@code .health} off the
 * {@code ServerComparison} and dropped the rest. From there no consumer could tell a verified
 * verdict from an unverified one, and <b>two of them made a claim to the USER on it</b>:
 *
 * <ul>
 *   <li>{@code refreshStallNotification} cleared the stall alert after a user-requested retry —
 *       telling that person their retry worked;</li>
 *   <li>{@code reconcileAction} called {@code stallCleared} on the {@code IN_SYNC} arm.</li>
 * </ul>
 *
 * <p><b>Both sites already refuse to do that on {@code LOOK_REFUSED}</b>, with the reason written
 * beside them: "Raising it would tell a person their retry failed, and clearing it would tell them
 * it worked; we asked nothing and know neither." The first-stage refusal had a distinct verdict and
 * the second-stage one did not, so the identical hazard was handled at one stage and discarded at
 * the next — inside the same method.
 *
 * <h2>The fix this pins</h2>
 *
 * <p>{@code Health.IN_SYNC_UNVERIFIED} — the sibling of {@code LOOK_REFUSED} one stage later. It
 * does NOT demote to a mismatch: only a definite {@code DIFFERS} demotes, and that rule is unchanged.
 * It says in-sync-as-far-as-we-looked and names how far that was, which is what makes it impossible
 * for a consumer to spend it as a measurement by accident.
 *
 * <h2>The falsifier — each test names the edit that turns it red</h2>
 *
 * <p>Revert the producer to {@code asked(Health.IN_SYNC, anchor)} and
 * {@link #theRefusedForkTestDoesNotReportAVerifiedInSync()} fails. Delete either consumer arm and
 * its test fails. Move either arm BELOW the {@code IN_SYNC} it guards and the ordering assertion
 * fails — which is the half a presence check cannot catch, because an arm that runs after the alert
 * has been cleared is a log line, not a gate. All four were run before this landed.
 *
 * <p><b>What this does NOT claim.</b> It says the distinction survives to the consumers and that the
 * two user-facing ones act on it. It says nothing about whether the ledger SHOULD be refusing the
 * look — that is a separate fix, an AIDL append and a contract bump, scheduled separately.
 * This guard is the blindness, not the ration.
 */
public class MlsUnverifiedHealthGuardTest {

    private static String transport() throws IOException {
        return SourceScan.transport();
    }

    private static String body(final String src, final String method) {
        final String b = SourceScan.bodyOf(src, method);
        assertTrue("could not find the body of " + method + " — this guard would otherwise read "
                + "nothing and pass on an empty string", b.length() > 0);
        return b;
    }

    /** The verdict must exist at all; every other test here keys on its name. */
    @Test
    public void theUnverifiedVerdictIsDeclared() throws IOException {
        final String src = transport();
        assertTrue("Health must declare IN_SYNC_UNVERIFIED — the whole point of the blindness "
                + "half is that 'we did not run the test' is a VERDICT rather than a log line",
                SourceScan.count(src, "IN_SYNC_UNVERIFIED") >= 4);
        assertTrue("and its first-stage sibling must still exist — if LOOK_REFUSED went away, the "
                + "symmetry this fix rests on is gone and these arms need rereading",
                SourceScan.count(src, "LOOK_REFUSED") >= 3);
    }

    /**
     * <b>The producer.</b> The arm that logs "the DIVERGED test was NOT RUN" must not then return a
     * verdict indistinguishable from one where it did.
     *
     * <p>Counted, not merely located: exactly ONE {@code asked(Health.IN_SYNC,} may remain in the
     * transport — the genuinely verified arm. A second would mean the refused path had been quietly
     * rejoined to it, which is precisely the state before this fix.
     */
    @Test
    public void theRefusedForkTestDoesNotReportAVerifiedInSync() throws IOException {
        final String b = body(transport(), "healthAgainstServer");

        assertTrue("the refused-authenticator arm must return IN_SYNC_UNVERIFIED",
                b.contains("ServerComparison.asked(Health.IN_SYNC_UNVERIFIED, anchor)"));
        assertEquals("exactly one arm may still answer a VERIFIED IN_SYNC — the one where the "
                + "authenticator look actually came back. A second is the refused path rejoined to "
                + "the verified one, which is the defect the blindness half exists to remove",
                1, SourceScan.count(b, "ServerComparison.asked(Health.IN_SYNC,"));
        assertTrue("a definite DIFFERS must still demote to DIVERGED — this fix must not have "
                + "weakened the one comparison that IS conclusive",
                b.contains("ServerComparison.asked(Health.DIVERGED, anchor)"));
    }

    /**
     * <b>The user-visible one.</b> A person who asked for a retry must not be told it worked on the
     * strength of a test we declined to run.
     *
     * <p>The ordering is the property, not the presence: an arm that runs after
     * {@code MlsStalledNotifier.clear} has already fired is a log line.
     */
    @Test
    public void theStallAlertIsNotClearedOnAnUnrunForkTest() throws IOException {
        final String b = body(transport(), "refreshStallNotification");

        final int unverified = b.indexOf("Health.IN_SYNC_UNVERIFIED");
        final int clear = b.indexOf("MlsStalledNotifier.clear(");
        assertTrue("refreshStallNotification must branch on IN_SYNC_UNVERIFIED — without it a "
                + "matching era and epoch clears this person's alert on an unrun DIVERGED test",
                unverified >= 0);
        assertTrue("the clear must still be here — a guard that passes because the behaviour was "
                + "deleted is not a guard", clear >= 0);
        assertTrue("the unverified arm must be reached BEFORE the alert is cleared (arm at "
                + unverified + ", clear at " + clear + ")", unverified < clear);

        final int refused = b.indexOf("Health.LOOK_REFUSED");
        assertTrue("and the first-stage refusal must still be handled here too — the two say "
                + "different things and dropping either re-opens half the hazard", refused >= 0);
    }

    /**
     * The same property on the self-driven path. {@code reconcileAction} clears the alert on
     * {@code IN_SYNC} for a stated reason — "an alert that outlives the condition is its own defect"
     * — and that reason needs the condition to have been measured.
     */
    @Test
    public void theReconcileDriveDoesNotClearOnAnUnrunForkTest() throws IOException {
        final String b = body(transport(), "reconcileAction");

        final int arm = b.indexOf("case IN_SYNC_UNVERIFIED:");
        final int inSync = b.indexOf("case IN_SYNC:");
        assertTrue("reconcileAction must carry an explicit IN_SYNC_UNVERIFIED arm. Letting a new "
                + "enum value fall out of the switch would be right by ACCIDENT — which is what "
                + "ERA_GAP, REJOIN, NOT_FOUND, UNKNOWN and LOOK_REFUSED already do there", arm >= 0);
        assertTrue("the verified arm must still exist", inSync >= 0);
        assertTrue("the unverified arm must come first, so it cannot fall through into the arm "
                + "that clears (unverified at " + arm + ", verified at " + inSync + ")",
                arm < inSync);

        final String unverifiedArm = b.substring(arm, inSync);
        assertEquals("the unverified arm must NOT call stallCleared — that is the claim it exists "
                + "to withhold", 0, SourceScan.count(unverifiedArm, "stallCleared("));
        assertTrue("and it must return rather than fall through into the clearing arm",
                unverifiedArm.contains("return "));
    }

    /**
     * <b>{@code detectHealth} is where the distinction was lost, so it is pinned there too.</b> It
     * takes {@code .health} off the comparison by design — the callers want a verdict, not a record
     * — which is only safe while the verdict itself carries the distinction. If someone re-flattens
     * the producer, this still passes; that is what the producer test is for. What this catches is
     * the first stage losing its own distinct verdict, which would leave the two stages inconsistent
     * again in the other direction.
     */
    @Test
    public void theFirstStageRefusalStillHasItsOwnVerdict() throws IOException {
        final String b = body(transport(), "detectHealth");
        assertTrue("detectHealth must still answer LOOK_REFUSED when the era/epoch look was refused",
                b.contains("return Health.LOOK_REFUSED;"));
        assertTrue("and must still delegate the second stage to healthAgainstServer",
                b.contains("healthAgainstServer("));
    }
}

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * <b>The posture is declared, and the declaration is what happens</b> — Stage 3,
 * decision D2(a), plus invariant I3(a) for both shipped budgets.
 *
 * <h2>What D2 asked for and what this asserts</h2>
 *
 * <p>The plan's §3.2 measured that three budget classes answer "I cannot read my own record" three
 * different ways, that all three answers are individually defensible, and that <b>the divergence is
 * invisible</b>: nothing states them side by side and nothing tests them. D2 (a) types the posture as
 * a field and asks for "one host test asserting declared posture matches behaviour". This is it, and
 * it is deliberately a LAW over {@code OnNoStore.values()} rather than three hand-written cases —
 * the source-scan recommendation applied to a test rather than to a scan: a fourth posture added
 * without an arm must FAIL here, not be silently unasserted.
 *
 * <h2>I3(a), for both budgets rather than for the record</h2>
 *
 * <p>{@code MlsRebuildWindowRecordTest} pins the record. What was never pinned is the pair of
 * PROPERTIES the plan states for Stage 3 — <i>a charge survives an encode/decode restart</i>, and
 * <i>no clock reading a rebooted device can produce refills a spent window</i> — <b>through the
 * policy that ships</b>, at the numbers that ship. Asserting them against a record built by the test
 * would be invariant I5's defect (an invariant asserted with hand-supplied arguments is not asserted
 * about production); {@link MlsWindowBudget#REBUILD} and {@link MlsWindowBudget#EXTERNAL_COMMIT} are
 * the instances the two adapters construct by default, so these are statements about what shipped.
 */
public final class MlsWindowBudgetTest {

    /** An arbitrary elapsedRealtime reading, well inside a boot. */
    private static final long NOW = 4_000_000L;

    /** The two budgets the adapters ship. Enumerated, so a third one added is covered on day one. */
    private static List<MlsWindowBudget> shipped() {
        final List<MlsWindowBudget> out = new ArrayList<>();
        out.add(MlsWindowBudget.REBUILD);
        out.add(MlsWindowBudget.EXTERNAL_COMMIT);
        return out;
    }

    private static MlsWindowBudget with(final MlsWindowBudget.OnNoStore posture) {
        return new MlsWindowBudget("test", 3, 60_000L, posture,
                MlsWindowBudget.OnUnreadable.REFUSE_AND_DISCARD);
    }

    /** Spend {@code n} charges against {@code b}, threading the stored string through each. */
    private static String spend(final MlsWindowBudget b, final int n, final long nowElapsedMs) {
        String raw = null;
        for (int i = 0; i < n; i++) {
            final MlsWindowBudget.Decision d =
                    b.claim("k", MlsWindowBudget.Store.AVAILABLE, raw, nowElapsedMs);
            assertEquals("charge " + (i + 1) + " of " + n + " was refused: " + d,
                    MlsWindowBudget.Outcome.CHARGE_AND_ALLOW, d.outcome());
            raw = d.value();
        }
        return raw;
    }

    // ---- D2(a): the posture law ----------------------------------------------------------------

    /**
     * <b>Every {@code OnNoStore} value has an arm, and the arm is what the name says.</b>
     *
     * <p>Enumerated from {@code values()}, so a fourth posture cannot be added and left unasserted —
     * which is the whole hazard, transposed from a source needle onto a test's
     * subject list. The mapping below is the SPECIFICATION; {@link MlsWindowBudget#claim} is the
     * implementation, and this test is the only place the two are reconciled.
     */
    @Test
    public void everyDeclaredPostureBehavesAsItIsDeclared() {
        assertTrue("OnNoStore has no values — this whole class is asserting about nothing",
                MlsWindowBudget.OnNoStore.values().length >= 3);
        final List<String> wrong = new ArrayList<>();
        for (final MlsWindowBudget.OnNoStore posture : MlsWindowBudget.OnNoStore.values()) {
            final MlsWindowBudget.Outcome expected;
            switch (posture) {
                case REFUSE:
                    expected = MlsWindowBudget.Outcome.REFUSE;
                    break;
                case DEGRADE_LOCAL:
                    expected = MlsWindowBudget.Outcome.DEGRADE_TO_LOCAL_STORE;
                    break;
                case ALLOW_UNCOUNTED:
                    expected = MlsWindowBudget.Outcome.ALLOW_UNCOUNTED;
                    break;
                default:
                    fail("OnNoStore." + posture + " is declared and this test has no expectation "
                            + "for it. A posture nobody stated the meaning of is exactly the state "
                            + "D2 exists to end — add the row here and the arm in claim().");
                    return;
            }
            final MlsWindowBudget.Decision d = with(posture)
                    .claim("k", MlsWindowBudget.Store.UNREACHABLE, null, NOW);
            if (d.outcome() != expected) {
                wrong.add(posture + " declares " + expected + " and behaves " + d.outcome());
            }
            if (d.reason() != MlsWindowBudget.Reason.NO_STORE) {
                wrong.add(posture + " answers reason " + d.reason() + ", not NO_STORE — the log "
                        + "line would name the wrong fact");
            }
            if (d.storeAction() != MlsWindowBudget.StoreAction.NOTHING) {
                wrong.add(posture + " asks the adapter to " + d.storeAction() + " a store it just "
                        + "said was unreachable");
            }
        }
        if (!wrong.isEmpty()) fail("Declared posture does not match behaviour: " + wrong);
    }

    /**
     * <b>The posture table, as the two shipped budgets declare it.</b>
     *
     * <p>Hand-written on purpose and it is the ONE place in this file that is: a posture table's
     * content is a decision, not a derivation, and a test that derived the expectation from the
     * class under test would assert that the class equals itself. This is the row a review reads.
     *
     * <p>The third row — {@code MlsPeerGuard}'s {@code DEGRADE_LOCAL} — is asserted by
     * {@link #theThirdPostureRowIsStillDegradeLocal} against source, because that class needs a
     * {@code Context} and is not an instance of this policy.
     */
    @Test
    public void theShippedBudgetsDeclareThePostureTheyAreDocumentedWith() {
        assertEquals("MlsWindowBudget.REBUILD's no-store posture changed. The rebuild limiter's "
                + "argument is that an unbounded rebuild loop is worse than a conversation that "
                + "stays diverged one cycle longer; changing it is a decision, not a refactor.",
                MlsWindowBudget.OnNoStore.REFUSE, MlsWindowBudget.REBUILD.onNoStore());
        assertEquals("MlsWindowBudget.EXTERNAL_COMMIT's no-store posture changed. It is REFUSE "
                + "since D2's flip, and the argument is at MlsExternalCommitBudget: a bound argued "
                + "for on the grounds that it protects a peer who cannot ask us to stop must not "
                + "have an arm whose effect is no bound at all. Moving it back re-opens the hole "
                + "the plan's §3.2 called \"an unbounded path that reads as a log line\".",
                MlsWindowBudget.OnNoStore.REFUSE,
                MlsWindowBudget.EXTERNAL_COMMIT.onNoStore());
        for (final MlsWindowBudget b : shipped()) {
            assertEquals(b.name() + " no longer refuses-and-discards an unreadable record. All "
                    + "three budget classes agreed on this arm and they agreed for a reason: "
                    + "reading a record we cannot parse as \"nothing spent\" restores an allowance "
                    + "on the strength of a parse error.",
                    MlsWindowBudget.OnUnreadable.REFUSE_AND_DISCARD, b.onUnreadable());
        }
    }

    /**
     * <b>No shipped budget proceeds with nothing counted.</b>
     *
     * <p>The mirror of the assertion this file carried before D2's flip, which pinned that exactly
     * one did. That hole was the plan's §3.2 "unbounded path that reads as a log line", and it was
     * the one path in a class written to protect a peer that reached the state the class exists to
     * prevent.
     *
     * <p>Both halves are asserted per budget — what it DECLARES and what it DOES — because the
     * whole point of typing the posture is that those two cannot come apart quietly.
     */
    @Test
    public void noShippedBudgetProceedsUncounted() {
        final List<String> holes = new ArrayList<>();
        for (final MlsWindowBudget b : shipped()) {
            if (b.onNoStore() == MlsWindowBudget.OnNoStore.ALLOW_UNCOUNTED) {
                holes.add(b.name() + " declares ALLOW_UNCOUNTED");
            }
            final MlsWindowBudget.Decision d =
                    b.claim("k", MlsWindowBudget.Store.UNREACHABLE, null, NOW);
            if (d.mayProceed()) {
                holes.add(b.name() + " proceeds with no store reachable (" + d + ")");
            }
        }
        if (!holes.isEmpty()) {
            fail("These budgets allow an operation nothing counts: " + holes + ". A bound whose "
                    + "failure mode is no bound at all is not a bound — and for the external "
                    + "commit the argument for having one is that it protects a peer who has no "
                    + "way to ask us to stop. If this is a deliberate new "
                    + "posture it needs its own review, as D2's flip in the other direction did.");
        }
    }

    /** {@code DEGRADE_TO_LOCAL_STORE} is an instruction, never an allow. */
    @Test
    public void degradingIsNotProceeding() {
        final MlsWindowBudget.Decision d = with(MlsWindowBudget.OnNoStore.DEGRADE_LOCAL)
                .claim("k", MlsWindowBudget.Store.UNREACHABLE, null, NOW);
        assertEquals(MlsWindowBudget.Outcome.DEGRADE_TO_LOCAL_STORE, d.outcome());
        assertFalse("DEGRADE_TO_LOCAL_STORE reads as mayProceed(). An adapter that treated it as an "
                + "allow would have implemented ALLOW_UNCOUNTED while declaring DEGRADE_LOCAL — the "
                + "posture lying about itself, which is the exact defect D2 exists to make "
                + "impossible.", d.mayProceed());
        assertTrue("a degraded decision is still an ANSWER — it names what to do next", d.answered());
    }

    /**
     * {@code MlsPeerGuard} still degrades to a process-local store, so the third posture row is not
     * only prose.
     *
     * <p>Source-level because that class needs a {@code Context}. Keyed on the DECLARATION of the
     * fallback map and on the invoked method name that announces it — never on a log string, which
     * is the first instance of the needle failure. Zero hits fail.
     */
    @Test
    public void theThirdPostureRowIsStillDegradeLocal() throws IOException {
        final String src = SourceScan.codeOnly(
                SourceScan.read("src/com/android/messaging/rcs/e2ee/MlsPeerGuard.java"));
        assertTrue("MlsPeerGuard.java did not read, or read empty — the posture table's third row "
                + "is asserting about nothing", src.length() > 1000);
        assertTrue("MlsPeerGuard no longer declares sFallbackStore. Its row in MlsWindowBudget's "
                + "posture table says DEGRADE_LOCAL, and a posture with no fallback to degrade TO "
                + "is ALLOW_UNCOUNTED wearing another name.",
                src.contains("sFallbackStore"));
        assertTrue("MlsPeerGuard no longer tracks whether it has announced the degradation. A "
                + "silent degradation is how a guard stops being one — its own words.",
                src.contains("sWarnedNoStore"));
        assertTrue("MlsPeerGuard no longer READS sFallbackStore on the load path, so the fallback "
                + "is declared and unused", SourceScan.count(src, "sFallbackStore.get(") >= 1);
        assertTrue("MlsPeerGuard no longer WRITES sFallbackStore, so a degraded charge counts "
                + "nothing", SourceScan.count(src, "sFallbackStore.put(") >= 1);
    }

    // ---- I3(a): the charge survives a restart --------------------------------------------------

    /**
     * <b>A charge survives an encode/decode restart</b>, for every shipped budget at its own
     * numbers.
     *
     * <p>The restart is modelled the way it actually happens: the only thing that crosses it is the
     * STRING. Anything the policy holds in memory is gone, so a property that needs an object to
     * survive is a property that does not hold on a device.
     */
    @Test
    public void aChargeSurvivesAnEncodeDecodeRestart() {
        for (final MlsWindowBudget b : shipped()) {
            final String afterOne = spend(b, 1, NOW);
            assertNotNull(b.name() + " charged and stored nothing", afterOne);
            assertEquals(b.name() + ": a charge did not survive the round trip through the store — "
                    + "the loop these budgets bound is the one that kills the process, so a charge "
                    + "that dies with the process is no charge at all",
                    1, b.countIn(afterOne, NOW));

            // And all the way to the limit, one restart per charge.
            final String spentOut = spend(b, b.maxPerWindow(), NOW);
            assertEquals(b.name() + ": the window does not read as spent after " + b.maxPerWindow()
                    + " charges", b.maxPerWindow(), b.countIn(spentOut, NOW));
            final MlsWindowBudget.Decision refused =
                    b.claim("k", MlsWindowBudget.Store.AVAILABLE, spentOut, NOW);
            assertEquals(b.name() + ": charge " + (b.maxPerWindow() + 1) + " was ALLOWED",
                    MlsWindowBudget.Outcome.REFUSE, refused.outcome());
            assertEquals(MlsWindowBudget.Reason.WINDOW_SPENT, refused.reason());
        }
    }

    /**
     * <b>No clock reading a rebooted device can produce refills a spent window</b> — for every
     * shipped budget, swept rather than sampled.
     *
     * <p>A rebooted device's {@code elapsedRealtime} starts at 0 and climbs, so every reading BELOW
     * the stored stamp is reachable and each one must keep the window spent. {@link MlsMonotonicAge}
     * answers "the current uptime" for those, which is why the sweep stops at the window length: at
     * an uptime of a full window the allowance HAS refilled, and it must, because this is a rate and
     * not a total.
     */
    @Test
    public void noRebootReadingRefillsASpentWindow() {
        for (final MlsWindowBudget b : shipped()) {
            final long stamp = 10L * b.windowMs();          // charged well into a long uptime
            final String spentOut = spend(b, b.maxPerWindow(), stamp);

            // Every uptime a rebooted device can show below one full window: still spent.
            final long step = Math.max(1L, b.windowMs() / 97L);
            for (long uptime = 0L; uptime < b.windowMs(); uptime += step) {
                final MlsWindowBudget.Decision d =
                        b.claim("k", MlsWindowBudget.Store.AVAILABLE, spentOut, uptime);
                assertEquals(b.name() + ": a device that rebooted and has been up for " + uptime
                        + " ms was handed its allowance back. The stored stamp is " + stamp
                        + ", so this reading went BACKWARDS and the only provable age is the "
                        + "uptime — which is less than the window.",
                        MlsWindowBudget.Outcome.REFUSE, d.outcome());
                assertEquals(MlsWindowBudget.Reason.WINDOW_SPENT, d.reason());
            }
            // And it IS a rate: at a full window of uptime the allowance is back.
            final MlsWindowBudget.Decision refilled =
                    b.claim("k", MlsWindowBudget.Store.AVAILABLE, spentOut, b.windowMs());
            assertEquals(b.name() + ": the window never refills, so this is a TOTAL and not a rate "
                    + "— which converts a transient fault into a permanently dead conversation "
                    + "(a transient fault must not be permanent)",
                    MlsWindowBudget.Outcome.CHARGE_AND_ALLOW, refilled.outcome());
        }
    }

    /** A charge inside a window does not extend the window. */
    @Test
    public void aChargeDoesNotPushTheRefillOut() {
        final MlsWindowBudget b = MlsWindowBudget.REBUILD;
        final MlsWindowBudget.Decision first =
                b.claim("k", MlsWindowBudget.Store.AVAILABLE, null, NOW);
        final long halfway = NOW + b.windowMs() / 2;
        final MlsWindowBudget.Decision second =
                b.claim("k", MlsWindowBudget.Store.AVAILABLE, first.value(), halfway);
        assertEquals(MlsWindowBudget.Outcome.CHARGE_AND_ALLOW, second.outcome());
        assertTrue("a second charge pushed the refill out — three rebuilds in quick succession "
                + "would then throttle for a window past the LAST one instead of the first",
                second.remainingMs() <= b.windowMs() / 2 + 1);
    }

    // ---- the charge is unrepresentable-uncharged -----------------------------------------------

    /**
     * {@code CHARGE_AND_ALLOW} always carries the write.
     *
     * <p>The uncharged-door defect, prevented in the type: an adapter cannot fall out of the switch into a
     * proceeding path that skipped the charge, because the proceeding outcome and the write are one
     * value. Same move {@link MlsStateChangeGate.Outcome#CHARGE_AND_ALLOW} makes for the era
     * advance.
     */
    @Test
    public void anAllowedChargeAlwaysCarriesItsWrite() {
        for (final MlsWindowBudget b : shipped()) {
            for (int i = 0; i < b.maxPerWindow(); i++) {
                final MlsWindowBudget.Decision d =
                        b.claim("k", MlsWindowBudget.Store.AVAILABLE, spend(b, i, NOW), NOW);
                assertEquals(MlsWindowBudget.Outcome.CHARGE_AND_ALLOW, d.outcome());
                assertEquals(b.name() + ": an allowed charge does not ask the store to write",
                        MlsWindowBudget.StoreAction.WRITE, d.storeAction());
                assertNotNull(b.name() + ": an allowed charge carries no value to write, so the "
                        + "adapter would write null and the charge would vanish", d.value());
            }
        }
    }

    /** Only {@link MlsWindowBudget.StoreAction#WRITE} carries a value, and it always does. */
    @Test
    public void aValueIsCarriedExactlyWhenTheStoreIsToldToWrite() {
        final List<MlsWindowBudget.Decision> all = new ArrayList<>();
        final MlsWindowBudget b = MlsWindowBudget.REBUILD;
        all.add(b.claim(null, MlsWindowBudget.Store.AVAILABLE, null, NOW));
        all.add(b.claim("k", MlsWindowBudget.Store.UNREACHABLE, null, NOW));
        all.add(b.claim("k", MlsWindowBudget.Store.AVAILABLE, "gibberish", NOW));
        all.add(b.claim("k", MlsWindowBudget.Store.AVAILABLE, null, NOW));
        all.add(b.claim("k", MlsWindowBudget.Store.AVAILABLE,
                spend(b, b.maxPerWindow(), NOW), NOW));
        all.add(b.claim("k", MlsWindowBudget.Store.AVAILABLE, "4:1", NOW));   // legacy, over limit
        for (final MlsWindowBudget.Decision d : all) {
            if (d.storeAction() == MlsWindowBudget.StoreAction.WRITE) {
                assertNotNull("WRITE with nothing to write: " + d, d.value());
            } else {
                assertNull("a non-WRITE decision carries a value the adapter will not store, so a "
                        + "reader would believe a charge was recorded: " + d, d.value());
            }
        }
    }

    // ---- unreadable is not absent --------------------------------------------------------------

    /** An unreadable record refuses, asks for a discard, and reports {@code -1} rather than 0. */
    @Test
    public void unreadableIsNotAbsentAndNotUnspent() {
        for (final MlsWindowBudget b : shipped()) {
            final MlsWindowBudget.Decision d =
                    b.claim("k", MlsWindowBudget.Store.AVAILABLE, "not a record", NOW);
            assertEquals(b.name() + ": an unparseable record was treated as an empty window, so a "
                    + "parse error restores the whole allowance",
                    MlsWindowBudget.Outcome.REFUSE, d.outcome());
            assertEquals(MlsWindowBudget.Reason.RECORD_UNREADABLE, d.reason());
            assertEquals(b.name() + ": the unreadable record is not discarded, so the refusal is "
                    + "permanent rather than costing one deferred operation",
                    MlsWindowBudget.StoreAction.DISCARD, d.storeAction());
            assertEquals(b.name() + ": countIn answers 0 for a record it cannot read, i.e. the same "
                    + "number as \"nothing recorded\" — the one number a person would use to check "
                    + "the bound reports a full allowance for a corrupt file",
                    -1, b.countIn("not a record", NOW));
            assertEquals(b.name() + ": countIn no longer answers 0 for an ABSENT record",
                    0, b.countIn(null, NOW));
        }
    }

    /** Nothing stored is a full allowance, and it is a different answer from unreadable. */
    @Test
    public void absentIsAFullAllowance() {
        for (final MlsWindowBudget b : shipped()) {
            final MlsWindowBudget.Decision d =
                    b.claim("k", MlsWindowBudget.Store.AVAILABLE, null, NOW);
            assertEquals(MlsWindowBudget.Outcome.CHARGE_AND_ALLOW, d.outcome());
            assertEquals(0, d.count());
        }
    }

    // ---- the legacy record ---------------------------------------------------------------------

    /**
     * A legacy wall-clock record at its limit is ADOPTED and PERSISTED even though the
     * decision is a refusal.
     *
     * <p>The trap, restated at the policy: without the write, a legacy record
     * already at its limit is re-adopted on every attempt and its window restarts each time — a
     * throttle that never expires, which is the permanent total in a different disguise. And the
     * refusal arm is the one that does not otherwise write, so it is the one where this is easy to
     * miss.
     */
    @Test
    public void aLegacyRecordAtItsLimitIsAdoptedOnTheRefusal() {
        final MlsWindowBudget b = MlsWindowBudget.REBUILD;
        final MlsWindowBudget.Decision d = b.claim(
                "k", MlsWindowBudget.Store.AVAILABLE, b.maxPerWindow() + ":1700000000000", NOW);
        assertEquals(MlsWindowBudget.Outcome.REFUSE, d.outcome());
        assertEquals(MlsWindowBudget.Reason.WINDOW_SPENT, d.reason());
        assertTrue("the legacy record was not recognised as one", d.adoptedLegacyRecord());
        assertEquals("a legacy record at its limit is refused and NOT written back, so its window "
                + "restarts on every attempt and the throttle never expires",
                MlsWindowBudget.StoreAction.WRITE, d.storeAction());
        assertFalse("the adopted record was written back in the legacy shape",
                d.value().contains(":"));
    }

    /** Adoption keeps the count — discarding it would hand the allowance back on upgrade. */
    @Test
    public void adoptingALegacyRecordKeepsItsCount() {
        final MlsWindowBudget b = MlsWindowBudget.REBUILD;
        final MlsWindowBudget.Decision d =
                b.claim("k", MlsWindowBudget.Store.AVAILABLE, "1:1700000000000", NOW);
        assertEquals(MlsWindowBudget.Outcome.CHARGE_AND_ALLOW, d.outcome());
        assertEquals("the legacy count was discarded, which hands the allowance back on upgrade — "
                + "the exact refill this is about, delivered by its own fix", 1, d.count());
        assertEquals(2, b.countIn(d.value(), NOW));
    }

    // ---- the vocabulary ------------------------------------------------------------------------

    /** No key means no bound, and it says so rather than sharing a spelling with a spent window. */
    @Test
    public void aMissingKeyIsItsOwnRefusal() {
        for (final String key : new String[] {null, ""}) {
            final MlsWindowBudget.Decision d =
                    MlsWindowBudget.REBUILD.claim(key, MlsWindowBudget.Store.AVAILABLE, null, NOW);
            assertEquals(MlsWindowBudget.Outcome.REFUSE, d.outcome());
            assertEquals("a keyless claim reads as a spent window, so a log line would blame a "
                    + "budget that was never consulted", MlsWindowBudget.Reason.NO_KEY, d.reason());
            assertEquals(MlsWindowBudget.StoreAction.NOTHING, d.storeAction());
        }
    }

    /**
     * Every {@code Outcome} is reachable and no two of them mean the same thing to a caller.
     *
     * <p>Enumerated from {@code values()}: an outcome added later and never produced is a value a
     * caller must switch on and can never see, and one produced by nothing is indistinguishable
     * from a missing arm.
     */
    @Test
    public void everyOutcomeIsReachableAndDistinguishable() {
        final Set<MlsWindowBudget.Outcome> seen = EnumSet.noneOf(MlsWindowBudget.Outcome.class);
        final MlsWindowBudget b = MlsWindowBudget.REBUILD;
        seen.add(b.claim("k", MlsWindowBudget.Store.AVAILABLE, null, NOW).outcome());
        seen.add(b.claim("k", MlsWindowBudget.Store.AVAILABLE,
                spend(b, b.maxPerWindow(), NOW), NOW).outcome());
        seen.add(b.claim("k", MlsWindowBudget.Store.UNREACHABLE, null, NOW).outcome());
        seen.add(with(MlsWindowBudget.OnNoStore.DEGRADE_LOCAL)
                .claim("k", MlsWindowBudget.Store.UNREACHABLE, null, NOW).outcome());
        seen.add(with(MlsWindowBudget.OnNoStore.ALLOW_UNCOUNTED)
                .claim("k", MlsWindowBudget.Store.UNREACHABLE, null, NOW).outcome());

        final List<MlsWindowBudget.Outcome> unreachable = new ArrayList<>();
        for (final MlsWindowBudget.Outcome o : MlsWindowBudget.Outcome.values()) {
            // UNANSWERABLE is reachable only by adding a posture and forgetting its arm, which is a
            // code change and not an input. It is asserted by everyDeclaredPostureBehavesAsItIsDeclared
            // (which FAILS on exactly that edit) rather than produced here.
            if (o == MlsWindowBudget.Outcome.UNANSWERABLE) continue;
            if (!seen.contains(o)) unreachable.add(o);
        }
        if (!unreachable.isEmpty()) {
            fail("These outcomes are declared and nothing produces them: " + unreachable
                    + ". A caller must switch on a value it can never see, and a reader cannot tell "
                    + "it apart from an arm that was forgotten.");
        }
        assertTrue("mayProceed() is true for a refusal",
                !b.claim("k", MlsWindowBudget.Store.AVAILABLE,
                        spend(b, b.maxPerWindow(), NOW), NOW).mayProceed());
    }

    /** The two shipped budgets are separate policies, at the numbers argued for. */
    @Test
    public void theTwoBudgetsAreNotOneBudget() {
        assertFalse("REBUILD and EXTERNAL_COMMIT are the same object, so spending one spends the "
                + "other — the property MlsExternalCommitBudget's first paragraph is about",
                MlsWindowBudget.REBUILD == MlsWindowBudget.EXTERNAL_COMMIT);
        assertEquals("the rebuild allowance moved off 3 per window",
                3, MlsWindowBudget.REBUILD.maxPerWindow());
        assertEquals("the rebuild window moved off six hours",
                6L * 60 * 60 * 1000, MlsWindowBudget.REBUILD.windowMs());
        assertEquals("MAX_PER_DAY moved off RCC.16 §11.2.2's 50. It is not a tuning knob: no value "
                + "of it can match Google Messages, which has no budget at all, so tuning yields neither "
                + "parity nor a principled bound.",
                50, MlsWindowBudget.EXTERNAL_COMMIT.maxPerWindow());
        assertEquals("the §11.2.2 window moved off a day",
                24L * 60 * 60 * 1000, MlsWindowBudget.EXTERNAL_COMMIT.windowMs());
        assertSame(MlsWindowBudget.REBUILD, MlsWindowBudget.REBUILD);
    }

    /**
     * Episode suppression is a MINUTE and the allowance is SIX HOURS — Stage 6.
     *
     * <p>Asserted together, in one test, because the whole reason the episode window was moved next
     * to the allowance is that the two are only comprehensible as a pair: the short one exists so
     * that a burst cannot spend the long one. A change that made them the same order of magnitude
     * would be the bug a device run produced, and this is what says so.
     */
    @Test
    public void theEpisodeSuppressorIsFarShorterThanTheAllowanceItProtects() {
        assertEquals(60_000L, MlsWindowBudget.REBUILD_EPISODE_MS);
        assertEquals(6L * 60 * 60 * 1000, MlsWindowBudget.REBUILD_WINDOW_MS);
        assertTrue("the episode suppressor must be far shorter than the window it protects, or a "
                + "burst still spends the whole allowance",
                MlsWindowBudget.REBUILD_EPISODE_MS * 60 <= MlsWindowBudget.REBUILD_WINDOW_MS);
    }

}

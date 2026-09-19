/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * {@link MlsWindowBudget}: each declared no-store posture behaves as declared, over
 * {@code OnNoStore.values()}, and the two shipped budgets ({@link MlsWindowBudget#REBUILD},
 * {@link MlsWindowBudget#EXTERNAL_COMMIT}) keep a charge across a restart and cannot be refilled by
 * a post-reboot clock. See docs/mls/budgets.md.
 */
public final class MlsWindowBudgetTest {

    /** An arbitrary elapsedRealtime reading, well inside a boot. */
    private static final long NOW = 4_000_000L;

    /** The two budgets the adapters ship. */
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

    /** The switch below is the specification that {@link MlsWindowBudget#claim} must match. */
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

    /** Hand-written: a posture is a decision, not a derivation. */
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

    /** Asserted per budget on both what it declares and what it does. */
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
        assertFalse(
                "DEGRADE_TO_LOCAL_STORE reads as mayProceed(). An adapter that treated it as an "
                + "allow would have implemented ALLOW_UNCOUNTED while declaring DEGRADE_LOCAL — the "
                + "posture lying about itself, which is the exact defect D2 exists to make "
                + "impossible.", d.mayProceed());
        assertTrue("a degraded decision is still an ANSWER — it names what to do next",
                d.answered());
    }

    /** {@code MlsPeerGuard} still degrades to a process-local store; a source scan. */
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

    /** Only the stored string crosses a restart. */
    @Test
    public void aChargeSurvivesAnEncodeDecodeRestart() {
        for (final MlsWindowBudget b : shipped()) {
            final String afterOne = spend(b, 1, NOW);
            assertNotNull(b.name() + " charged and stored nothing", afterOne);
            assertEquals(b.name() + ": a charge did not survive the round trip through the store — "
                    + "the loop these budgets bound is the one that kills the process, so a charge "
                    + "that dies with the process is no charge at all",
                    1, b.countIn(afterOne, NOW));

            // To the limit, one restart per charge.
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
     * After a reboot every reading below the stored stamp is reachable; {@link MlsMonotonicAge}
     * answers the uptime for those, so the window refills only after a full window of uptime.
     */
    @Test
    public void noRebootReadingRefillsASpentWindow() {
        for (final MlsWindowBudget b : shipped()) {
            final long stamp = 10L * b.windowMs();          // charged well into a long uptime
            final String spentOut = spend(b, b.maxPerWindow(), stamp);

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
            // A rate, not a total: at a full window of uptime the allowance is back.
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

    /** The proceeding outcome and the write are one value, so no path proceeds uncharged. */
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
            assertEquals(b.name()
                    + ": countIn answers 0 for a record it cannot read, i.e. the same "
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

    /**
     * A legacy record at its limit is adopted and written back even on a refusal; otherwise its
     * window restarts on every attempt and the throttle never expires.
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

    /** Adoption keeps the count; discarding it would hand the allowance back on upgrade. */
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
            // UNANSWERABLE needs a posture without an arm, which
            // everyDeclaredPostureBehavesAsItIsDeclared covers.
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
                + "of it can match peers that have no budget at all, so tuning yields neither "
                + "parity nor a principled bound.",
                50, MlsWindowBudget.EXTERNAL_COMMIT.maxPerWindow());
        assertEquals("the §11.2.2 window moved off a day",
                24L * 60 * 60 * 1000, MlsWindowBudget.EXTERNAL_COMMIT.windowMs());
        assertSame(MlsWindowBudget.REBUILD, MlsWindowBudget.REBUILD);
    }

    /** The short episode window exists so that one burst cannot spend the long allowance. */
    @Test
    public void theEpisodeSuppressorIsFarShorterThanTheAllowanceItProtects() {
        assertEquals(60_000L, MlsWindowBudget.REBUILD_EPISODE_MS);
        assertEquals(6L * 60 * 60 * 1000, MlsWindowBudget.REBUILD_WINDOW_MS);
        assertTrue("the episode suppressor must be far shorter than the window it protects, or a "
                + "burst still spends the whole allowance",
                MlsWindowBudget.REBUILD_EPISODE_MS * 60 <= MlsWindowBudget.REBUILD_WINDOW_MS);
    }

}

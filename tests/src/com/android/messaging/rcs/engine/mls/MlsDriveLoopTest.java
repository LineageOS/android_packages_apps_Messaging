/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The result vocabulary and the bounded drive loop: termination, the cap, the no-progress rules,
 * the NO_OP/PENDING distinction, and a failed pending operation poisoning a later NO_OP. See
 * docs/mls/health-and-recovery.md.
 */
public class MlsDriveLoopTest {

    /** A telemetry sink that records, so the cap metric is assertable. */
    private static final class RecordingTelemetry implements MlsTelemetry {
        final List<String> counted = new ArrayList<>();
        @Override public void count(final String metric) { counted.add(metric); }
        @Override public void count(final String metric, final int value) {
            for (int i = 0; i < value; i++) counted.add(metric);
        }
        int countOf(final String metric) {
            int n = 0;
            for (final String m : counted) if (m.equals(metric)) n++;
            return n;
        }
    }

    @Test public void stoppingStatusesAreExactlyThree() {
        assertTrue("NO_OP is how convergence is detected", MlsResultStatus.NO_OP.stopsLoop());
        assertTrue(MlsResultStatus.SUCCESS.stopsLoop());
        assertTrue(MlsResultStatus.FAIL_NO_RETRY.stopsLoop());
        // An in-flight operation is what a re-drive follows up on, so PENDING does not stop the
        // loop.
        assertFalse("PENDING must not stop the loop", MlsResultStatus.PENDING.stopsLoop());
        assertFalse(MlsResultStatus.FAIL_RETRY.stopsLoop());
    }

    @Test public void unknownWireDecodesToPendingNotNoOp() {
        // Unknown wire values decode to PENDING, the safe direction: NO_OP would declare an
        // in-flight operation finished and drop it, PENDING costs one extra pass. Never null.
        for (final int wire : new int[] {-1, 5, 99, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            final MlsResultStatus s = MlsResultStatus.fromWire(wire);
            assertNotNull(s);
            assertEquals("wire " + wire + " must be PENDING, never NO_OP",
                    MlsResultStatus.PENDING, s);
        }
    }

    @Test public void wireNumbersRoundTripAndAreDistinct() {
        for (final MlsResultStatus s : MlsResultStatus.values()) {
            assertEquals(s, MlsResultStatus.fromWire(s.wire));
        }
        // Wire values, not ordinals: a renumbering collision must not be silent.
        final java.util.Set<Integer> wires = new java.util.HashSet<>();
        for (final MlsResultStatus s : MlsResultStatus.values()) {
            assertTrue("duplicate wire number " + s.wire, wires.add(s.wire));
        }
    }

    @Test public void stopsImmediatelyOnNoOp() {
        final AtomicInteger passes = new AtomicInteger();
        final MlsDriveLoop loop = new MlsDriveLoop(MlsTelemetry.NONE);
        final MlsDriveLoop.Result r = loop.drive("converged", previous -> {
            passes.incrementAndGet();
            return MlsHostAction.none("nothing left to do");
        });
        assertEquals(1, passes.get());
        assertEquals(1, r.iterations);
        assertFalse(r.cappedOut);
        assertEquals(MlsResultStatus.NO_OP, r.status());
    }

    @Test public void reDrivesWhilePendingThenSettles() {
        final AtomicInteger passes = new AtomicInteger();
        final MlsDriveLoop loop = new MlsDriveLoop(MlsTelemetry.NONE);
        final MlsDriveLoop.Result r = loop.drive("buffered-control", previous -> {
            final int pass = passes.incrementAndGet();
            if (pass < 3) {
                // Each pass drains one buffered control, so the group moves and the snapshot says
                // so; the same arm over an unchanged snapshot is the no-progress shape and is
                // stopped.
                return MlsHostAction.of(MlsHostAction.Kind.BUFFER_AND_RETRY,
                        new MlsGroupSnapshot(new byte[] {7}, 2, pass, null), "future epoch");
            }
            return MlsHostAction.of(MlsHostAction.Kind.EPOCH_ADVANCED, "the commit landed");
        });
        assertEquals(3, r.iterations);
        assertFalse(r.cappedOut);
        assertEquals(MlsResultStatus.SUCCESS, r.status());
        assertEquals(MlsHostAction.Kind.EPOCH_ADVANCED, r.action.kind);
    }

    @Test public void eachPassSeesThePreviousAction() {
        // A pass is handed the last result and nothing else, which keeps the loop result-driven.
        // The snapshots advance because the loop stops on two indistinguishable results.
        final List<String> seen = new ArrayList<>();
        final AtomicInteger n = new AtomicInteger();
        new MlsDriveLoop(MlsTelemetry.NONE).drive("chain", previous -> {
            seen.add(previous == null ? "null" : previous.kind.name());
            final int pass = n.incrementAndGet();
            if (pass < 3) {
                return MlsHostAction.of(MlsHostAction.Kind.SELF_HEAL_REQUIRED,
                        new MlsGroupSnapshot(new byte[] {1}, 1, pass, null), "stranded");
            }
            return MlsHostAction.none("healed");
        });
        assertEquals(3, seen.size());
        assertEquals("first pass has no predecessor", "null", seen.get(0));
        assertEquals("SELF_HEAL_REQUIRED", seen.get(1));
        assertEquals("SELF_HEAL_REQUIRED", seen.get(2));
    }

    @Test public void capStopsAnEndlessPassAndIsTerminal() {
        final RecordingTelemetry telemetry = new RecordingTelemetry();
        final AtomicInteger passes = new AtomicInteger();
        final MlsDriveLoop loop = new MlsDriveLoop(telemetry);
        // Alternating snapshots reach the cap the only way left: results that all differ and none
        // progress. MlsFetchBudget bounds the look-ups in that case; the pass cap does not.
        final MlsDriveLoop.Result r = loop.drive("never-settles", previous -> {
            final int pass = passes.incrementAndGet();
            return MlsHostAction.of(MlsHostAction.Kind.SELF_HEAL_REQUIRED,
                    new MlsGroupSnapshot(new byte[] {1}, 1, pass, null), "still stranded");
        });
        assertEquals(MlsDriveLoop.DEFAULT_MAX_ITERATIONS, passes.get());
        assertEquals(MlsDriveLoop.DEFAULT_MAX_ITERATIONS, r.iterations);
        assertTrue(r.cappedOut);
        assertFalse(r.stoppedWithoutProgress);
        assertFalse(r.stoppedDeclaredInert);
        // Terminal, not FAIL_RETRY: a capped drive reporting "retry" would be re-driven by its
        // caller.
        assertEquals(MlsResultStatus.FAIL_NO_RETRY, r.status());
        assertEquals(1, telemetry.countOf(MlsMetrics.MAX_LOOP_REACHED));
        assertTrue(r.logLine().contains("FLAT AT ZERO"));
    }

    @Test public void aPassThatDeclaresItChangedNothingIsNotReDriven() {
        // One pass: every pass of a recovery drive costs a GetMlsGroupInfo, so re-driving a pass
        // that changed nothing spends a look-up the drive's own self-heal may need.
        final RecordingTelemetry telemetry = new RecordingTelemetry();
        final AtomicInteger passes = new AtomicInteger();
        final MlsDriveLoop.Result r = new MlsDriveLoop(telemetry).drive("behind", previous -> {
            passes.incrementAndGet();
            return MlsHostAction.withRedrive(MlsHostAction.Kind.SELF_HEAL_REQUIRED,
                    MlsResultStatus.FAIL_RETRY, MlsGroupSnapshot.NONE,
                    MlsHostAction.Redrive.NOT_IN_THIS_DRIVE, "BEHIND, and nothing to replay from");
        });
        assertEquals("exactly one look", 1, passes.get());
        assertEquals(1, r.iterations);
        assertTrue(r.stoppedDeclaredInert);
        assertFalse(r.cappedOut);
        assertFalse(r.stoppedWithoutProgress);
        // The status is preserved: the caller still owes the work and schedules the retry.
        assertEquals(MlsResultStatus.FAIL_RETRY, r.status());
        // Not the bug counter: a producer declaring it is done is the loop working.
        assertEquals(0, telemetry.countOf(MlsMetrics.MAX_LOOP_REACHED));
    }

    @Test public void everyNonImmediateRedriveStopsTheLoopAfterOnePass() {
        // Enumerated: any arm that is not IMMEDIATE stops the loop, so a new Redrive value cannot
        // silently re-drive.
        for (final MlsHostAction.Redrive rd : MlsHostAction.Redrive.values()) {
            final AtomicInteger passes = new AtomicInteger();
            final MlsDriveLoop.Result r = new MlsDriveLoop(MlsTelemetry.NONE)
                    .drive("arm-" + rd, previous -> {
                        final int pass = passes.incrementAndGet();
                        return MlsHostAction.withRedrive(MlsHostAction.Kind.SELF_HEAL_REQUIRED,
                                MlsResultStatus.FAIL_RETRY,
                                // A moving snapshot, so only the Redrive can stop it.
                                new MlsGroupSnapshot(new byte[] {3}, 1, pass, null), rd, "x");
                    });
            if (rd == MlsHostAction.Redrive.IMMEDIATE) {
                assertEquals(rd.name(), MlsDriveLoop.DEFAULT_MAX_ITERATIONS, passes.get());
                assertFalse(rd.name(), r.stoppedDeclaredInert);
            } else {
                assertEquals(rd.name(), 1, passes.get());
                assertTrue(rd.name(), r.stoppedDeclaredInert);
                assertEquals(rd.name(), MlsResultStatus.FAIL_RETRY, r.status());
            }
        }
    }

    @Test public void aRepeatedActionStopsTheDriveRatherThanSpendingAnotherLook() {
        // The backstop for a producer that has not declared NOT_IN_THIS_DRIVE: two
        // indistinguishable results mean nothing moved, so the third look is refused.
        final RecordingTelemetry telemetry = new RecordingTelemetry();
        final AtomicInteger passes = new AtomicInteger();
        final MlsDriveLoop.Result r = new MlsDriveLoop(telemetry).drive("spins", previous -> {
            passes.incrementAndGet();
            return MlsHostAction.of(MlsHostAction.Kind.SELF_HEAL_REQUIRED, "still stranded");
        });
        assertEquals("two looks, not ten", 2, passes.get());
        assertEquals(2, r.iterations);
        assertTrue(r.stoppedWithoutProgress);
        assertFalse(r.cappedOut);
        // The status is the pass's own, so the caller still owes the work.
        assertEquals(MlsResultStatus.FAIL_RETRY, r.status());
        // The alarm still sounds, so stopping early does not hide the fault.
        assertEquals(1, telemetry.countOf(MlsMetrics.MAX_LOOP_REACHED));
        assertTrue(r.logLine(), r.logLine().contains("reported work without progressing"));
    }

    @Test public void theSnapshotIsWhatDistinguishesProgressFromRepetition() {
        // Same arm and status with a different snapshot is progress, and the loop continues; a pass
        // that is advancing says where it landed.
        final AtomicInteger passes = new AtomicInteger();
        final MlsDriveLoop.Result r = new MlsDriveLoop(MlsTelemetry.NONE)
                .drive("draining", previous -> {
                    final int pass = passes.incrementAndGet();
                    if (pass < 4) {
                        return MlsHostAction.of(MlsHostAction.Kind.BUFFER_AND_RETRY,
                                new MlsGroupSnapshot(new byte[] {9}, 3, pass, null), "one more");
                    }
                    return MlsHostAction.none("drained");
                });
        assertEquals(4, r.iterations);
        assertFalse(r.stoppedWithoutProgress);
        assertEquals(MlsResultStatus.NO_OP, r.status());
    }

    @Test public void twoUnknownSnapshotsCountAsNoMovement() {
        // A pass that cannot say where the group landed gives no evidence of movement, and none is
        // assumed.
        assertTrue(MlsGroupSnapshot.NONE.identicalTo(MlsGroupSnapshot.NONE));
        assertFalse(MlsGroupSnapshot.NONE.identicalTo(null));
        final MlsGroupSnapshot a = new MlsGroupSnapshot(new byte[] {1}, 4, 7L, new byte[] {8});
        assertTrue(a.identicalTo(new MlsGroupSnapshot(new byte[] {1}, 4, 7L, new byte[] {8})));
        assertFalse("a different authenticator is a different group at the same position",
                a.identicalTo(new MlsGroupSnapshot(new byte[] {1}, 4, 7L, new byte[] {9})));
        assertFalse(a.identicalTo(new MlsGroupSnapshot(new byte[] {2}, 4, 7L, new byte[] {8})));
        assertFalse(a.identicalTo(new MlsGroupSnapshot(new byte[] {1}, 5, 7L, new byte[] {8})));
        assertFalse(a.identicalTo(new MlsGroupSnapshot(new byte[] {1}, 4, 8L, new byte[] {8})));
    }

    @Test public void redriveDefaultsToImmediateAndSurvivesPoisoning() {
        // The default is IMMEDIATE: a producer that has not chosen gets the loop's own rules rather
        // than a silent early stop.
        assertEquals(MlsHostAction.Redrive.IMMEDIATE, MlsHostAction.none("x").redrive);
        assertEquals(MlsHostAction.Redrive.IMMEDIATE,
                MlsHostAction.of(MlsHostAction.Kind.DROP, "x").redrive);
        assertEquals(MlsHostAction.Redrive.IMMEDIATE, MlsHostAction.withStatus(
                MlsHostAction.Kind.NONE, MlsResultStatus.PENDING,
                MlsGroupSnapshot.NONE, "x").redrive);
        // Poisoning re-maps the outcome; whether the pass changed anything is unchanged.
        final MlsHostAction inert = MlsHostAction.withRedrive(MlsHostAction.Kind.NONE,
                MlsResultStatus.NO_OP, MlsGroupSnapshot.NONE,
                MlsHostAction.Redrive.AFTER_A_COOLDOWN, "could not look");
        assertEquals(MlsHostAction.Redrive.AFTER_A_COOLDOWN, inert.poison().redrive);
        assertEquals(MlsResultStatus.FAIL_NO_RETRY, inert.poison().status);
    }

    @Test public void capMetricIsNotTouchedOnASettlingDrive() {
        // The counter's value is that it stays at zero, so a drive that settles leaves it alone.
        final RecordingTelemetry telemetry = new RecordingTelemetry();
        new MlsDriveLoop(telemetry).drive("fine", previous -> MlsHostAction.none("done"));
        assertEquals(0, telemetry.countOf(MlsMetrics.MAX_LOOP_REACHED));
        assertTrue(telemetry.counted.isEmpty());
    }

    @Test public void capIsTheReferenceClientsDefault() {
        assertEquals(10, MlsDriveLoop.DEFAULT_MAX_ITERATIONS);
    }

    @Test public void aCapBelowOneStillRunsOnePass() {
        // A zero or negative cap would report FAIL_NO_RETRY on a conversation never asked to do
        // anything.
        for (final int cap : new int[] {0, -1, Integer.MIN_VALUE}) {
            final AtomicInteger passes = new AtomicInteger();
            final MlsDriveLoop.Result r = new MlsDriveLoop(MlsTelemetry.NONE, cap)
                    .drive("clamped", previous -> {
                        passes.incrementAndGet();
                        return MlsHostAction.none("done");
                    });
            assertEquals("cap " + cap, 1, passes.get());
            assertFalse(r.cappedOut);
        }
    }

    @Test public void aNullReturnThrowsRatherThanReadingAsNoOp() {
        try {
            new MlsDriveLoop(MlsTelemetry.NONE).drive("sloppy", previous -> null);
            fail("a pass returning null must not be read as convergence");
        } catch (final IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("returned null"));
            assertTrue(expected.getMessage().contains("MlsHostAction.none"));
        }
    }

    @Test public void aThrowingPassPropagatesRatherThanRetrying() {
        // A null return throws rather than retrying, which would re-drive a broken pass to the cap
        // and report the cap instead of the cause.
        final AtomicInteger passes = new AtomicInteger();
        try {
            new MlsDriveLoop(MlsTelemetry.NONE).drive("broken", previous -> {
                passes.incrementAndGet();
                throw new ArithmeticException("boom");
            });
            fail("expected the pass's exception to propagate");
        } catch (final ArithmeticException expected) {
            assertEquals("boom", expected.getMessage());
        }
        assertEquals("must not retry a throwing pass", 1, passes.get());
    }

    @Test public void pendingOperationFailurePoisonsALaterNoOp() {
        // Otherwise a failed pending operation beside an idle group reads as converged and the
        // failure is lost.
        final AtomicInteger passes = new AtomicInteger();
        final MlsDriveLoop.Result r = new MlsDriveLoop(MlsTelemetry.NONE)
                .drive("poisoned", previous -> {
                    if (passes.incrementAndGet() == 1) {
                        return MlsHostAction.withStatus(
                                MlsHostAction.Kind.PENDING_OPERATION_FAILURE,
                                MlsResultStatus.FAIL_RETRY, MlsGroupSnapshot.NONE, "send failed");
                    }
                    return MlsHostAction.none("nothing to do");
                });
        assertEquals(2, r.iterations);
        assertEquals("a NO_OP after a pending-op failure is not convergence",
                MlsResultStatus.FAIL_NO_RETRY, r.status());
        assertTrue(r.action.reason.contains("re-mapped"));
    }

    @Test public void poisoningLeavesNonNoOpStatusesAlone() {
        final MlsHostAction success =
                MlsHostAction.of(MlsHostAction.Kind.EPOCH_ADVANCED, "applied");
        assertSame("only NO_OP is re-mapped", success, success.poison());
    }

    @Test public void onlyPendingOperationFailurePoisons() {
        for (final MlsHostAction.Kind k : MlsHostAction.Kind.values()) {
            final boolean poisons = MlsHostAction.of(k, "x").poisonsNoOp();
            assertEquals(k.name(), k == MlsHostAction.Kind.PENDING_OPERATION_FAILURE, poisons);
        }
    }

    @Test public void everyArmCarriesAStatusAndNoArmIsNoOpByAccident() {
        for (final MlsHostAction.Kind k : MlsHostAction.Kind.values()) {
            assertNotNull(k.name(), k.defaultStatus);
            if (k != MlsHostAction.Kind.NONE) {
                assertFalse("only NONE may default to NO_OP — " + k.name(),
                        k.defaultStatus == MlsResultStatus.NO_OP);
            }
        }
        assertEquals(MlsResultStatus.NO_OP, MlsHostAction.Kind.NONE.defaultStatus);
    }

    @Test public void terminalArmsAreTerminal() {
        // These three are unconditionally terminal; a retry can only repeat a permanent failure.
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsHostAction.Kind.DELETE_LOCAL_GROUP_STATE.defaultStatus);
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsHostAction.Kind.PENDING_OPERATION_FAILURE.defaultStatus);
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsHostAction.Kind.CANNOT_HEAL_DURING_END_MLS.defaultStatus);
    }

    @Test public void aCachedProposalIsPendingNotSuccess() {
        // A cached proposal blocks application messages until committed (mls-rs commit_required()),
        // so calling it done wedges the conversation.
        assertEquals(MlsResultStatus.PENDING, MlsHostAction.Kind.PROPOSAL_CACHED.defaultStatus);
    }

    @Test public void rejectUnhandledNamesTheArmAndTheFlow() {
        // The error names the arm, which points at the producer rather than the enum.
        try {
            MlsHostAction.rejectUnhandled("inbound",
                    MlsHostAction.of(MlsHostAction.Kind.DELETE_LOCAL_GROUP_STATE, "gone"));
            fail("expected rejection");
        } catch (final IllegalStateException expected) {
            final String m = expected.getMessage();
            assertTrue(m, m.contains("inbound"));
            assertTrue(m, m.contains("DELETE_LOCAL_GROUP_STATE"));
            assertTrue(m, m.contains("FAIL_NO_RETRY"));
        }
    }

    @Test public void rejectUnhandledSurvivesANullAction() {
        try {
            MlsHostAction.rejectUnhandled("inbound", null);
            fail("expected rejection");
        } catch (final IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("null"));
        }
    }

    @Test public void deliverCarriesItsPayload() {
        final byte[] payload = {1, 2, 3};
        final MlsHostAction a =
                MlsHostAction.deliver(payload, MlsGroupSnapshot.NONE, "app message");
        assertEquals(MlsHostAction.Kind.DELIVER_MESSAGE, a.kind);
        assertEquals(MlsResultStatus.SUCCESS, a.status);
        assertEquals(3, a.payload.length);
    }

    @Test public void snapshotIsNeverNull() {
        // So no caller needs a null branch to read where a group landed.
        assertNotNull(MlsHostAction.none("x").snapshot);
        assertNotNull(MlsHostAction.of(MlsHostAction.Kind.DROP, null, "x").snapshot);
        assertSame(MlsGroupSnapshot.NONE, MlsHostAction.none("x").snapshot);
    }

    @Test public void aNullKindIsRejected() {
        try {
            MlsHostAction.of(null, "x");
            fail("expected rejection");
        } catch (final IllegalArgumentException expected) { /* expected */ }
    }

    @Test public void snapshotDefensivelyCopiesBothWays() {
        // A snapshot whose contents can be edited afterwards is not a snapshot.
        final byte[] gid = {1, 2, 3};
        final MlsGroupSnapshot s = new MlsGroupSnapshot(gid, 4, 9L, new byte[] {7});
        gid[0] = 99;
        assertEquals("mutating the source must not reach in", 1, s.groupId()[0]);
        s.groupId()[0] = 42;
        assertEquals("mutating a handout must not reach in", 1, s.groupId()[0]);
    }

    @Test public void eraDominatesEpochWhenComparing() {
        // An era advance rebuilds the group and restarts its epoch, so comparing epochs alone
        // reports progress as going backwards.
        final MlsGroupSnapshot oldEra = new MlsGroupSnapshot(new byte[] {1}, 3, 900L, null);
        final MlsGroupSnapshot newEra = new MlsGroupSnapshot(new byte[] {1}, 4, 0L, null);
        assertTrue("era 4 epoch 0 is AHEAD of era 3 epoch 900", oldEra.isBehind(newEra));
        assertFalse(newEra.isBehind(oldEra));
    }

    @Test public void withinAnEraEpochDecides() {
        final MlsGroupSnapshot a = new MlsGroupSnapshot(new byte[] {1}, 4, 7L, null);
        final MlsGroupSnapshot b = new MlsGroupSnapshot(new byte[] {1}, 4, 8L, null);
        assertTrue(a.isBehind(b));
        assertFalse(b.isBehind(a));
        assertFalse("equal is not behind", a.isBehind(a));
    }

    @Test public void unknownSnapshotsCompareToNothing() {
        final MlsGroupSnapshot known = new MlsGroupSnapshot(new byte[] {1}, 4, 7L, null);
        assertFalse(MlsGroupSnapshot.NONE.isBehind(known));
        assertFalse(known.isBehind(MlsGroupSnapshot.NONE));
        assertFalse(known.isBehind(null));
        assertFalse(MlsGroupSnapshot.NONE.isKnown());
        assertFalse(MlsGroupSnapshot.NONE.hasGroup());
        assertNull(MlsGroupSnapshot.NONE.groupId());
    }

    @Test public void describeNeverPrintsEpochAuthenticatorContents() {
        // A fingerprint; the log needs only its length.
        final MlsGroupSnapshot s = new MlsGroupSnapshot(new byte[] {1, 2},
                4, 7L, new byte[] {(byte) 0xAB, (byte) 0xCD});
        final String d = s.describe();
        assertTrue(d, d.contains("2B"));
        assertFalse(d, d.toLowerCase().contains("abcd"));
    }
}

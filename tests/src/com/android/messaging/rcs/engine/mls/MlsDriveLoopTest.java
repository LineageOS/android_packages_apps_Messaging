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
 * Stage F (rework items 4.1-4.4): the result vocabulary and the bounded drive loop.
 *
 * <p>These are the properties the drive loop's correctness rests on — termination, the cap, the
 * NO_OP/PENDING distinction, and §10.5's poisoning rule — rather than a tour of the API.
 */
public class MlsDriveLoopTest {

    /** A telemetry sink that records, so the cap metric is assertable rather than inferred. */
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

    // -- 4.4: the five-value status axis ----------------------------------------------------------

    @Test public void stoppingStatusesAreExactlyThree() {
        assertTrue("NO_OP is how convergence is detected", MlsResultStatus.NO_OP.stopsLoop());
        assertTrue(MlsResultStatus.SUCCESS.stopsLoop());
        assertTrue(MlsResultStatus.FAIL_NO_RETRY.stopsLoop());
        // The one that most invites a wrong answer: an in-flight operation is exactly what a
        // re-drive exists to follow up on, so PENDING must NOT stop the loop.
        assertFalse("PENDING must not stop the loop", MlsResultStatus.PENDING.stopsLoop());
        assertFalse(MlsResultStatus.FAIL_RETRY.stopsLoop());
    }

    @Test public void unknownWireDecodesToPendingNotNoOp() {
        // §3.10's safe direction. NO_OP would declare an in-flight operation finished and drop it;
        // PENDING costs one extra pass. Also covers null-safety by construction: never null.
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
        // Guards the ordinal-vs-wire trap: if someone renumbers, collisions must not be silent.
        final java.util.Set<Integer> wires = new java.util.HashSet<>();
        for (final MlsResultStatus s : MlsResultStatus.values()) {
            assertTrue("duplicate wire number " + s.wire, wires.add(s.wire));
        }
    }

    // -- 4.3: termination -------------------------------------------------------------------------

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
                // Each pass drains one buffered control, so the group moves — and says so. A pass
                // that returns BUFFER_AND_RETRY over an UNCHANGED snapshot is the no-progress shape and is
                // stopped; see aRepeatedActionStopsTheDriveRatherThanSpendingAnotherLook.
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
        // The mechanism that makes the loop RESULT-driven: a pass is handed the last result and
        // nothing else. If this ever became a state query, invariant 55 would be gone.
        //
        // The snapshots ADVANCE deliberately. The loop stops on two
        // indistinguishable results, so a pass that keeps returning the same arm has to say the
        // group moved — which is what a pass that really is making progress does anyway.
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

    // -- 4.3: the cap -----------------------------------------------------------------------------

    @Test public void capStopsAnEndlessPassAndIsTerminal() {
        final RecordingTelemetry telemetry = new RecordingTelemetry();
        final AtomicInteger passes = new AtomicInteger();
        final MlsDriveLoop loop = new MlsDriveLoop(telemetry);
        // ALTERNATING snapshots, so this reaches the cap the only way it still can: a pass whose
        // results are all DIFFERENT and none of which progresses. That is the residual hazard
        // MlsFetchBudget exists for — the no-progress rule cannot see it, and the pass cap is not a
        // bound on look-ups. A pass that simply repeats itself no longer gets ten passes; see
        // aRepeatedActionStopsTheDriveRatherThanSpendingAnotherLook.
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
        // Terminal, NOT FAIL_RETRY — a capped drive that reported "retry" would be re-driven by its
        // own caller, which is the loop we just refused to run.
        assertEquals(MlsResultStatus.FAIL_NO_RETRY, r.status());
        assertEquals(1, telemetry.countOf(MlsMetrics.MAX_LOOP_REACHED));
        assertTrue(r.logLine().contains("FLAT AT ZERO"));
    }

    // -- a pass that makes no progress may not spend another look-up ------------------------------

    @Test public void aPassThatDeclaresItChangedNothingIsNotReDriven() {
        // THE INVARIANT, stated directly: one pass, not two, and certainly not ten. Every pass of a
        // recovery drive costs a GetMlsGroupInfo, so re-driving a pass that changed nothing spends a
        // server look-up to be told what we already know — which is how the reconcile drive
        // exhausted the quota its own self-heal then needed.
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
        // THE STATUS IS PRESERVED. The work is still owed — the caller must still record it and
        // schedule the retry. Downgrading this to FAIL_NO_RETRY to make the loop stop would fix the
        // fetches by abandoning the conversation.
        assertEquals(MlsResultStatus.FAIL_RETRY, r.status());
        // And it is NOT the bug counter: a producer being honest is the loop working.
        assertEquals(0, telemetry.countOf(MlsMetrics.MAX_LOOP_REACHED));
    }

    @Test public void everyNonImmediateRedriveStopsTheLoopAfterOnePass() {
        // Enumerated rather than exampled: any arm that is not IMMEDIATE must stop the loop, so
        // adding a fourth Redrive value cannot silently get today's re-drive behaviour.
        for (final MlsHostAction.Redrive rd : MlsHostAction.Redrive.values()) {
            final AtomicInteger passes = new AtomicInteger();
            final MlsDriveLoop.Result r = new MlsDriveLoop(MlsTelemetry.NONE)
                    .drive("arm-" + rd, previous -> {
                        final int pass = passes.incrementAndGet();
                        return MlsHostAction.withRedrive(MlsHostAction.Kind.SELF_HEAL_REQUIRED,
                                MlsResultStatus.FAIL_RETRY,
                                // A moving snapshot, so ONLY the Redrive can be what stops it.
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
        // The backstop for a producer that has NOT declared NOT_IN_THIS_DRIVE — which is exactly
        // what reconcile's BEHIND arm was on 2026-09-08. Two indistinguishable results are evidence
        // that nothing moved, so the third look is refused. Two passes, not ten.
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
        // AND THE ALARM STILL SOUNDS. Stopping earlier must not silence the counter that names this
        // fault — otherwise the fix would hide the bug it was written for.
        assertEquals(1, telemetry.countOf(MlsMetrics.MAX_LOOP_REACHED));
        assertTrue(r.logLine(), r.logLine().contains("reported work without progressing"));
    }

    @Test public void theSnapshotIsWhatDistinguishesProgressFromRepetition() {
        // Same arm, same status, DIFFERENT snapshot = progress, and the loop must keep going. This
        // is the property that makes the repeat rule safe for a real multi-pass drain: a pass that
        // is genuinely advancing says where it landed, which is what MlsGroupSnapshot is for.
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
        // The direction of the safe error. A pass that cannot say where the group landed has given
        // the loop no evidence of movement, and the loop is not entitled to assume any — assuming it
        // is what let ten identical passes each spend a fetch.
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
        // The default has to be IMMEDIATE: a producer that has not thought about this gets today's
        // behaviour, bounded by the loop's own rules, rather than a silent early stop.
        assertEquals(MlsHostAction.Redrive.IMMEDIATE, MlsHostAction.none("x").redrive);
        assertEquals(MlsHostAction.Redrive.IMMEDIATE,
                MlsHostAction.of(MlsHostAction.Kind.DROP, "x").redrive);
        assertEquals(MlsHostAction.Redrive.IMMEDIATE, MlsHostAction.withStatus(
                MlsHostAction.Kind.NONE, MlsResultStatus.PENDING,
                MlsGroupSnapshot.NONE, "x").redrive);
        // Poisoning re-maps the OUTCOME; whether the pass changed anything is a fact about what it
        // did, and the re-mapping does not alter it.
        final MlsHostAction inert = MlsHostAction.withRedrive(MlsHostAction.Kind.NONE,
                MlsResultStatus.NO_OP, MlsGroupSnapshot.NONE,
                MlsHostAction.Redrive.AFTER_A_COOLDOWN, "could not look");
        assertEquals(MlsHostAction.Redrive.AFTER_A_COOLDOWN, inert.poison().redrive);
        assertEquals(MlsResultStatus.FAIL_NO_RETRY, inert.poison().status);
    }

    @Test public void capMetricIsNotTouchedOnASettlingDrive() {
        // The whole value of this counter is that it is flat at zero, so a drive that settles must
        // leave it completely alone.
        final RecordingTelemetry telemetry = new RecordingTelemetry();
        new MlsDriveLoop(telemetry).drive("fine", previous -> MlsHostAction.none("done"));
        assertEquals(0, telemetry.countOf(MlsMetrics.MAX_LOOP_REACHED));
        assertTrue(telemetry.counted.isEmpty());
    }

    @Test public void capIsTheReferenceClientsDefault() {
        assertEquals(10, MlsDriveLoop.DEFAULT_MAX_ITERATIONS);
    }

    @Test public void aCapBelowOneStillRunsOnePass() {
        // A zero/negative cap would report FAIL_NO_RETRY on a conversation never asked to do
        // anything — a self-inflicted outage rather than a tight bound.
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

    // -- 4.4: silence is not convergence ----------------------------------------------------------

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
        // Converting a defect into a retry would re-drive a broken pass up to the cap and then
        // report the cap — hiding the actual cause behind a bound.
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

    // -- §10.5: a failed pending operation poisons NO_OP -------------------------------------------

    @Test public void pendingOperationFailurePoisonsALaterNoOp() {
        // Without this, a failed pending operation sitting next to an idle group reads as
        // "converged" and the failure is silently lost.
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

    // -- 4.2: the closed action set ---------------------------------------------------------------

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
        // §10.5 names these three as unconditionally terminal. A retry on any of them can only
        // repeat an action that already failed for good.
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsHostAction.Kind.DELETE_LOCAL_GROUP_STATE.defaultStatus);
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsHostAction.Kind.PENDING_OPERATION_FAILURE.defaultStatus);
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsHostAction.Kind.CANNOT_HEAL_DURING_END_MLS.defaultStatus);
    }

    @Test public void aCachedProposalIsPendingNotSuccess() {
        // A cached proposal BLOCKS application messages until committed, so calling it done wedges
        // the conversation — mls-rs's own commit_required() contract.
        assertEquals(MlsResultStatus.PENDING, MlsHostAction.Kind.PROPOSAL_CACHED.defaultStatus);
    }

    @Test public void rejectUnhandledNamesTheArmAndTheFlow() {
        // "unexpected status 4" sends a reader to the enum; the arm name sends them to the producer.
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
        final MlsHostAction a = MlsHostAction.deliver(payload, MlsGroupSnapshot.NONE, "app message");
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

    // -- 4.1b: the state envelope -----------------------------------------------------------------

    @Test public void snapshotDefensivelyCopiesBothWays() {
        // A snapshot whose contents can be edited after the fact is not a snapshot.
        final byte[] gid = {1, 2, 3};
        final MlsGroupSnapshot s = new MlsGroupSnapshot(gid, 4, 9L, new byte[] {7});
        gid[0] = 99;
        assertEquals("mutating the source must not reach in", 1, s.groupId()[0]);
        s.groupId()[0] = 42;
        assertEquals("mutating a handout must not reach in", 1, s.groupId()[0]);
    }

    @Test public void eraDominatesEpochWhenComparing() {
        // An era advance REBUILDS the group, so its epoch counter restarts. Comparing epochs alone
        // reports that forward progress as going backwards.
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
        // It is a fingerprint, and a length is all a log needs from it.
        final MlsGroupSnapshot s = new MlsGroupSnapshot(new byte[] {1, 2},
                4, 7L, new byte[] {(byte) 0xAB, (byte) 0xCD});
        final String d = s.describe();
        assertTrue(d, d.contains("2B"));
        assertFalse(d, d.toLowerCase().contains("abcd"));
    }
}

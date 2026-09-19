/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The {@code Map<contextId, List<Action>>} demux of a drive's results. See
 * docs/mls/health-and-recovery.md.
 */
public class MlsResultBundleTest {

    private static final byte[] G1 = { 1, 2, 3 };
    private static final byte[] G2 = { 9, 9 };

    private static MlsHostAction act(final MlsHostAction.Kind k) {
        return MlsHostAction.of(k, "test");
    }

    private static MlsResultBundle.Result res(final String ctx, final MlsHostAction.Kind k,
            final byte[] gid) {
        return new MlsResultBundle.Result(ctx, act(k), gid);
    }

    @Test public void resultsGroupByContextInOrder() {
        final List<MlsResultBundle.Result> results = Arrays.asList(
                res("ctx-a", MlsHostAction.Kind.DELIVER_MESSAGE, G1),
                res("ctx-b", MlsHostAction.Kind.EPOCH_ADVANCED, G1),
                res("ctx-a", MlsHostAction.Kind.NONE, G1));
        final Map<String, List<MlsHostAction>> m = MlsResultBundle.demux(results);
        assertEquals(2, m.size());
        assertEquals(2, m.get("ctx-a").size());
        assertEquals(MlsHostAction.Kind.DELIVER_MESSAGE, m.get("ctx-a").get(0).kind);
        assertEquals(MlsHostAction.Kind.NONE, m.get("ctx-a").get(1).kind);
        assertEquals(1, m.get("ctx-b").size());
    }

    @Test public void anEmptyResultListTHROWS() {
        // Not "returns empty": an empty result set and a discarded one are indistinguishable
        // downstream.
        for (final List<MlsResultBundle.Result> empty : Arrays.asList(
                null, Collections.<MlsResultBundle.Result>emptyList())) {
            try {
                MlsResultBundle.demux(empty);
                fail("an empty result list must throw");
            } catch (final IllegalStateException expected) {
                assertEquals("No results returned from handleResults. This should not happen.",
                        expected.getMessage());
            }
        }
    }

    @Test public void aListOfNothingButNullsAlsoThrows() {
        try {
            MlsResultBundle.demux(Arrays.<MlsResultBundle.Result>asList(null, null));
            fail("a list of nulls is the same condition as an empty one");
        } catch (final IllegalStateException expected) {
            assertEquals("No results returned from handleResults. This should not happen.",
                    expected.getMessage());
        }
    }

    @Test public void ourContextComesBack() {
        final Map<String, List<MlsHostAction>> m = MlsResultBundle.demux(Arrays.asList(
                res("mine", MlsHostAction.Kind.DELIVER_MESSAGE, G1),
                res("theirs", MlsHostAction.Kind.EPOCH_ADVANCED, G1)));
        final List<MlsHostAction> ours = MlsResultBundle.forContext(m, "mine");
        assertEquals(1, ours.size());
        assertEquals(MlsHostAction.Kind.DELIVER_MESSAGE, ours.get(0).kind);
    }

    @Test public void otherContextsAreKEPTForPostProcessing() {
        // Only the return value is filtered; the side work still runs.
        final Map<String, List<MlsHostAction>> m = MlsResultBundle.demux(Arrays.asList(
                res("mine", MlsHostAction.Kind.DELIVER_MESSAGE, G1),
                res("theirs", MlsHostAction.Kind.EPOCH_ADVANCED, G1),
                res("third", MlsHostAction.Kind.NONE, G1)));
        final Map<String, List<MlsHostAction>> others = MlsResultBundle.otherContexts(m, "mine");
        assertEquals(2, others.size());
        assertTrue(others.containsKey("theirs"));
        assertTrue(others.containsKey("third"));
        assertFalse(others.containsKey("mine"));
    }

    @Test public void aMissingOwnContextTHROWSWithTheKeysListed() {
        final Map<String, List<MlsHostAction>> m = MlsResultBundle.demux(Collections.singletonList(
                res("someone-else", MlsHostAction.Kind.EPOCH_ADVANCED, G1)));
        try {
            MlsResultBundle.forContext(m, "mine");
            fail("the engine attributed no result to what we asked about");
        } catch (final IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().startsWith("Mls context id is not found in the result "
                            + "map. mlsContextId=mine, resultMap.keys="));
            assertTrue(expected.getMessage().contains("someone-else"));
        }
    }

    @Test public void theReturnedListIsNotWritable() {
        final Map<String, List<MlsHostAction>> m = MlsResultBundle.demux(
                Collections.singletonList(res("mine", MlsHostAction.Kind.NONE, G1)));
        try {
            MlsResultBundle.forContext(m, "mine").add(act(MlsHostAction.Kind.DROP));
            fail("a caller must not be able to add to the engine's answer");
        } catch (final UnsupportedOperationException expected) {
            // the point
        }
    }

    @Test public void oneGroupAcrossTheListIsFine() {
        assertArrayEquals(G1, MlsResultBundle.soleGroup(Arrays.asList(
                res("a", MlsHostAction.Kind.DELIVER_MESSAGE, G1),
                res("b", MlsHostAction.Kind.EPOCH_ADVANCED, G1))));
    }

    @Test public void resultsWithNoGroupAreIgnoredNotCountedAsAnAnswer() {
        // NONE and DROP name no group; treating "absent" as a group would fail every mixed list.
        assertArrayEquals(G1, MlsResultBundle.soleGroup(Arrays.asList(
                res("a", MlsHostAction.Kind.NONE, null),
                res("b", MlsHostAction.Kind.DELIVER_MESSAGE, G1),
                res("c", MlsHostAction.Kind.DROP, new byte[0]))));
    }

    @Test public void noGroupAnywhereTHROWS() {
        try {
            MlsResultBundle.soleGroup(Collections.singletonList(
                    res("a", MlsHostAction.Kind.NONE, null)));
            fail("no group named anywhere must throw");
        } catch (final IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().startsWith("No MLS group found in the results: "));
        }
    }

    @Test public void twoGroupsTHROW() {
        // The caller applies a remedy to "the group"; two answers would land it on whichever came
        // first.
        try {
            MlsResultBundle.soleGroup(Arrays.asList(
                    res("a", MlsHostAction.Kind.DELIVER_MESSAGE, G1),
                    res("b", MlsHostAction.Kind.EPOCH_ADVANCED, G2)));
            fail("two groups in one result list must throw");
        } catch (final IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().startsWith("Multiple MLS groups found in the results: "));
        }
    }

    @Test public void aFailedPendingOperationPoisonsNoOpSiblings() {
        final List<MlsHostAction> pass = Arrays.asList(
                act(MlsHostAction.Kind.NONE),
                act(MlsHostAction.Kind.PENDING_OPERATION_FAILURE));
        assertEquals(MlsResultStatus.NO_OP, pass.get(0).status);
        final List<MlsHostAction> after = MlsResultBundle.applyPoisonRule(pass);
        assertEquals(MlsResultStatus.FAIL_NO_RETRY, after.get(0).status);
        assertEquals(MlsResultStatus.FAIL_NO_RETRY, after.get(1).status);
    }

    @Test public void withoutAPendingFailureNothingIsPoisoned() {
        final List<MlsHostAction> pass = Arrays.asList(
                act(MlsHostAction.Kind.NONE),
                act(MlsHostAction.Kind.DELIVER_MESSAGE));
        final List<MlsHostAction> after = MlsResultBundle.applyPoisonRule(pass);
        assertEquals(MlsResultStatus.NO_OP, after.get(0).status);
        assertEquals(MlsResultStatus.SUCCESS, after.get(1).status);
    }

    @Test public void poisoningLeavesNonNoOpSiblingsAlone() {
        // Only NO_OP is re-mapped; rewriting a SUCCESS would erase a delivered message.
        final List<MlsHostAction> after = MlsResultBundle.applyPoisonRule(Arrays.asList(
                act(MlsHostAction.Kind.DELIVER_MESSAGE),
                act(MlsHostAction.Kind.PENDING_OPERATION_FAILURE)));
        assertEquals(MlsResultStatus.SUCCESS, after.get(0).status);
    }

    @Test public void thePoisonRuleDoesNotMutateItsInput() {
        final List<MlsHostAction> pass = new ArrayList<>(Arrays.asList(
                act(MlsHostAction.Kind.NONE),
                act(MlsHostAction.Kind.PENDING_OPERATION_FAILURE)));
        MlsResultBundle.applyPoisonRule(pass);
        assertEquals(MlsResultStatus.NO_OP, pass.get(0).status);
    }

    @Test public void theWorstOutcomeGoverns() {
        // The first status would let a SUCCESS at the head hide a failure behind it.
        assertEquals(MlsResultStatus.FAIL_RETRY, MlsResultBundle.governing(Arrays.asList(
                act(MlsHostAction.Kind.DELIVER_MESSAGE),
                act(MlsHostAction.Kind.SELF_HEAL_REQUIRED))).status);
        assertEquals(MlsResultStatus.PENDING, MlsResultBundle.governing(Arrays.asList(
                act(MlsHostAction.Kind.NONE),
                act(MlsHostAction.Kind.DELIVER_MESSAGE),
                act(MlsHostAction.Kind.BUFFER_AND_RETRY))).status);
    }

    @Test public void terminalFailureOutranksRetryableFailure() {
        // A retryable sibling does not make an unretryable failure retry.
        assertEquals(MlsResultStatus.FAIL_NO_RETRY, MlsResultBundle.governing(Arrays.asList(
                act(MlsHostAction.Kind.SELF_HEAL_REQUIRED),
                act(MlsHostAction.Kind.DELETE_LOCAL_GROUP_STATE))).status);
    }

    @Test public void governingAnEmptyPassIsNull() {
        assertNull(MlsResultBundle.governing(null));
        assertNull(MlsResultBundle.governing(Collections.<MlsHostAction>emptyList()));
        assertNull(MlsResultBundle.governing(Arrays.<MlsHostAction>asList(null, null)));
    }
}

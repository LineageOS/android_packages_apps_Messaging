/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** The exhaustive engine-status decoder and its hard failure on an unknown value. */
public class MlsProcStatusTest {

    @Test public void everyKnownStatusMapsToItsArm() {
        assertEquals(MlsHostAction.Kind.DELIVER_MESSAGE,
                MlsProcStatus.toActionKind(MlsProcStatus.APP));
        assertEquals(MlsHostAction.Kind.EPOCH_ADVANCED,
                MlsProcStatus.toActionKind(MlsProcStatus.COMMIT));
        assertEquals(MlsHostAction.Kind.PROPOSAL_CACHED,
                MlsProcStatus.toActionKind(MlsProcStatus.PROPOSAL));
        assertEquals(MlsHostAction.Kind.NONE,
                MlsProcStatus.toActionKind(MlsProcStatus.OTHER));
        assertEquals(MlsHostAction.Kind.DROP,
                MlsProcStatus.toActionKind(MlsProcStatus.MALFORMED));
        assertEquals(MlsHostAction.Kind.BUFFER_AND_RETRY,
                MlsProcStatus.toActionKind(MlsProcStatus.APPLY_FAILED));
        assertEquals(MlsHostAction.Kind.DROP,
                MlsProcStatus.toActionKind(MlsProcStatus.PAST_EPOCH));
    }

    @Test public void theStatusNumbersAreTheEnginesAndAreSparse() {
        // Pinned against MlsSession.ProcResult. 4, 5 and 6 do not exist, which is why the decoder
        // throws rather than absorbs an unrecognised value.
        assertEquals(0, MlsProcStatus.APP);
        assertEquals(1, MlsProcStatus.COMMIT);
        assertEquals(2, MlsProcStatus.PROPOSAL);
        assertEquals(3, MlsProcStatus.OTHER);
        assertEquals(7, MlsProcStatus.MALFORMED);
        assertEquals(8, MlsProcStatus.APPLY_FAILED);
        assertEquals(9, MlsProcStatus.PAST_EPOCH);
        for (final int gap : new int[] {4, 5, 6}) {
            assertFalse("status " + gap + " must not be claimed", MlsProcStatus.isKnown(gap));
        }
    }

    @Test public void anUnknownStatusThrowsAndSaysWhatIsKnown() {
        for (final int unknown : new int[] {4, 5, 6, 10, -1, Integer.MAX_VALUE}) {
            try {
                MlsProcStatus.toActionKind(unknown);
                fail("status " + unknown + " must not be silently absorbed");
            } catch (final IllegalStateException expected) {
                final String m = expected.getMessage();
                assertTrue(m, m.contains(String.valueOf(unknown)));
                assertTrue("must list the known arms", m.contains("APPLY_FAILED"));
                assertTrue("must not invite widening a default",
                        m.contains("Add the arm rather than widening a default"));
            }
        }
    }

    @Test public void appDecodesToDeliverEvenOnTheControlPlane() {
        // RCC.16 §7.8.1: a metadata commit ships its key as an APP payload on a control frame;
        // dropping it leaves a receiver that applied the epoch but cannot open the subject.
        assertEquals(MlsHostAction.Kind.DELIVER_MESSAGE,
                MlsProcStatus.toActionKind(MlsProcStatus.APP));
    }

    @Test public void bothDropStatusesAreTerminal() {
        // Both stop rather than retry; PAST_EPOCH is dropped so it cannot re-spam the flush cap.
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsProcStatus.toActionKind(MlsProcStatus.MALFORMED).defaultStatus);
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsProcStatus.toActionKind(MlsProcStatus.PAST_EPOCH).defaultStatus);
    }

    @Test public void futureEpochIsPendingSoItIsRetried() {
        // A future-epoch control is buffered and retried after the next commit.
        assertEquals(MlsResultStatus.PENDING,
                MlsProcStatus.toActionKind(MlsProcStatus.APPLY_FAILED).defaultStatus);
    }

    @Test public void nameOfNeverThrows() {
        // A log line must not be the thing that fails.
        assertEquals("COMMIT", MlsProcStatus.nameOf(MlsProcStatus.COMMIT));
        assertEquals("UNKNOWN(42)", MlsProcStatus.nameOf(42));
        assertEquals("UNKNOWN(-1)", MlsProcStatus.nameOf(-1));
    }
}

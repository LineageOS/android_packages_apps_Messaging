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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Rework item 4.1d — the exhaustive engine-status decoder and its hard failure on an unknown arm. */
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
        // Pinned against MlsSession.ProcResult. 4, 5 and 6 do not exist — which is the reason the
        // decoder must throw rather than absorb an unrecognised value.
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
        // The §7.8.1 key delivery: a metadata commit ships the key its 0xF006 commitment commits to
        // as an APP payload riding a control frame. Dropping it leaves a receiver that applied the
        // epoch change but can never open the subject it was just told about.
        assertEquals(MlsHostAction.Kind.DELIVER_MESSAGE,
                MlsProcStatus.toActionKind(MlsProcStatus.APP));
    }

    @Test public void bothDropStatusesAreTerminal() {
        // MALFORMED and PAST_EPOCH are unactionable for different reasons and both must stop, not
        // retry: PAST_EPOCH in particular is dropped so it cannot re-spam the flush cap.
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsProcStatus.toActionKind(MlsProcStatus.MALFORMED).defaultStatus);
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsProcStatus.toActionKind(MlsProcStatus.PAST_EPOCH).defaultStatus);
    }

    @Test public void futureEpochIsPendingSoItIsRetried() {
        // "Not yet" is not "no". A future-epoch control is buffered and retried after the next
        // commit — dropping it loses a key that is milliseconds away.
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

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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.android.messaging.rcs.engine.mls.MlsRequestContext.Cause;

/** Rework item 6.5 — the request context: redaction, the two ids, and the 500-char cap. */
public class MlsRequestContextTest {

    // -- the id is carried TWICE and the asymmetry is load-bearing (§10.3 points 1-3) -------------

    @Test public void theCorrelationIdIsSuffixedAndTheMessageIdIsNot() {
        // Collapsing these into one field is the obvious simplification and it is the mistake:
        // suffix the message id and the AAD cross-check fails against every Google Messages peer.
        final MlsRequestContext root =
                MlsRequestContext.root("base-1", "MxABCDEF", Cause.PROCESS_MESSAGE, null);
        final MlsRequestContext kid = root.child("redrive", 1234L);
        assertEquals("base-1_redrive_1234", kid.correlationId);
        assertEquals("the message id must NOT move", "MxABCDEF", kid.messageId);
    }

    @Test public void everyChildIsDistinguishableFromItsParent() {
        // What the suffix is for: a re-drive has to tell its own results from the ones that
        // produced it.
        final MlsRequestContext root = MlsRequestContext.root("b", "m", Cause.SELF_HEAL, null);
        final MlsRequestContext a = root.child("x", 1L);
        final MlsRequestContext b = root.child("x", 2L);
        assertNotEquals(root.correlationId, a.correlationId);
        assertNotEquals("two passes at different moments must differ",
                a.correlationId, b.correlationId);
    }

    @Test public void childrenNest() {
        final MlsRequestContext c = MlsRequestContext.root("b", "m", Cause.SEND_MESSAGE, null)
                .child("one", 1L).child("two", 2L);
        assertEquals("b_one_1_two_2", c.correlationId);
    }

    @Test public void causeAndGroupSurviveAChild() {
        final byte[] gid = {1, 2, 3};
        final MlsRequestContext kid =
                MlsRequestContext.root("b", "m", Cause.END_MLS, gid).child("x", 9L);
        assertEquals(Cause.END_MLS, kid.cause);
        assertEquals(3, kid.groupId().length);
    }

    // -- the 500-char cap --------------------------------------------------------------------------

    @Test public void theBaseIsCappedAtFiveHundred() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 900; i++) sb.append('x');
        final MlsRequestContext c =
                MlsRequestContext.root(sb.toString(), "m", Cause.UNKNOWN, null);
        assertEquals(MlsRequestContext.MAX_BASE_ID_CHARS, c.correlationId.length());
    }

    @Test public void theSuffixIsNeverWhatGetsTruncated() {
        // A truncated suffix would produce two children that compare EQUAL — exactly the confusion
        // the suffix exists to prevent. So the cap applies to the base, then the suffix is appended.
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 900; i++) sb.append('x');
        final MlsRequestContext a = MlsRequestContext.root(sb.toString(), "m", Cause.UNKNOWN, null)
                .child("lbl", 111L);
        final MlsRequestContext b = MlsRequestContext.root(sb.toString(), "m", Cause.UNKNOWN, null)
                .child("lbl", 222L);
        assertTrue(a.correlationId.endsWith("_lbl_111"));
        assertTrue(b.correlationId.endsWith("_lbl_222"));
        assertNotEquals(a.correlationId, b.correlationId);
    }

    @Test public void aShortBaseIsUntouched() {
        assertEquals("short", MlsRequestContext.truncateBase("short"));
        assertEquals("", MlsRequestContext.truncateBase(null));
    }

    // -- the ciphertext is stripped ----------------------------------------------------------------

    @Test public void thePayloadIsZeroedNotRemoved() {
        // Zeroed rather than removed so offsets elsewhere in the envelope stay valid — a redaction
        // that shifted the bytes would corrupt anything indexing into it.
        final byte[] env = {1, 2, 3, 4, 5, 6, 7, 8};
        final byte[] red = MlsRequestContext.redactPayload(env, 2, 4);
        assertEquals("length must not change", env.length, red.length);
        assertEquals(1, red[0]);
        assertEquals(2, red[1]);
        assertEquals(0, red[2]);
        assertEquals(0, red[5]);
        assertEquals("bytes after the payload must survive", 7, red[6]);
    }

    @Test public void theStoredEnvelopeNeverHoldsTheCiphertext() {
        final byte[] env = {9, 9, 7, 7, 7, 7, 9};
        final MlsRequestContext c = MlsRequestContext.root("b", "m", Cause.PROCESS_MESSAGE, null)
                .withRedactedEnvelope(env, 2, 4);
        final byte[] stored = c.redactedEnvelope();
        for (int i = 2; i < 6; i++) {
            assertEquals("payload byte " + i + " leaked into the context", 0, stored[i]);
        }
        assertEquals(9, stored[0]);
        assertEquals(9, stored[6]);
    }

    @Test public void redactionSurvivesNonsenseSpans() {
        final byte[] env = {1, 2, 3};
        assertEquals(3, MlsRequestContext.redactPayload(env, -1, 2).length);
        assertEquals(3, MlsRequestContext.redactPayload(env, 0, 0).length);
        assertEquals(3, MlsRequestContext.redactPayload(env, 99, 5).length);
        // A length running off the end must clamp, not throw.
        final byte[] over = MlsRequestContext.redactPayload(env, 1, Integer.MAX_VALUE);
        assertEquals(1, over[0]);
        assertEquals(0, over[1]);
        assertEquals(0, over[2]);
        assertEquals(0, MlsRequestContext.redactPayload(null, 0, 1).length);
    }

    @Test public void theEnvelopeIsDefensivelyCopied() {
        final byte[] env = {1, 2, 3, 4};
        final MlsRequestContext c = MlsRequestContext.root("b", "m", Cause.UNKNOWN, null)
                .withRedactedEnvelope(env, 3, 1);
        env[0] = 42;
        assertEquals("mutating the source must not reach in", 1, c.redactedEnvelope()[0]);
        c.redactedEnvelope()[0] = 43;
        assertEquals("mutating a handout must not reach in", 1, c.redactedEnvelope()[0]);
    }

    @Test public void toStringPrintsTheEnvelopeLengthOnly() {
        // The envelope is redacted, not empty — its un-redacted parts are still routing metadata.
        final MlsRequestContext c = MlsRequestContext.root("b", "m", Cause.PROCESS_MESSAGE, null)
                .withRedactedEnvelope(new byte[] {77, 77, 77}, 0, 3);
        final String s = c.toString();
        assertTrue(s, s.contains("env=3B"));
        assertFalse(s, s.contains("77"));
    }

    @Test public void causeWireNumbersRoundTrip() {
        for (final Cause c : Cause.values()) {
            assertEquals(c, Cause.fromWire(c.wire));
        }
        assertEquals(Cause.UNKNOWN, Cause.fromWire(99));
    }
}

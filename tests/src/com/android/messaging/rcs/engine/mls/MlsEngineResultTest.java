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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Rework item 6.6 — the repeated-result wire format between the Rust engine and the Java host.
 *
 * <p>The fixtures are HAND-BUILT bytes, not output from {@code encodeList}. A decoder tested only
 * against its own encoder proves the two agree, not that either matches what the Rust actually
 * emits — and the Rust builds these records with {@code join_len_prefixed}, so the layout the
 * fixtures spell out is the contract being checked.
 */
public class MlsEngineResultTest {

    /** The u32-big-endian framing {@code join_len_prefixed} produces, written out by hand. */
    private static byte[] frame(final byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] p : parts) {
            out.write(p.length >>> 24);
            out.write(p.length >>> 16);
            out.write(p.length >>> 8);
            out.write(p.length);
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] record(final int status, final String ctx, final byte[] gid,
            final byte[] aux, final byte[] payload) {
        return frame(new byte[] { (byte) status }, ctx.getBytes(StandardCharsets.UTF_8), gid, aux,
                payload);
    }

    private static final byte[] VERSION_OK = { (byte) MlsEngineResult.FORMAT_VERSION };
    private static final byte[] GID = { 0x0A, 0x0B };

    @Test public void anApplicationResultCarriesItsPlaintext() {
        final byte[] blob = frame(VERSION_OK,
                record(0, "ctx-1", GID, new byte[0], "hello".getBytes(StandardCharsets.UTF_8)));
        final List<MlsEngineResult> results = MlsEngineResult.decodeList(blob);
        assertEquals(1, results.size());
        final MlsEngineResult r = results.get(0);
        assertEquals("ctx-1", r.contextId);
        assertEquals(0, r.status);
        assertEquals(-1, r.proposalType);
        assertArrayEquals(GID, r.groupId());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), r.payload());
    }

    @Test public void aProposalResultCarriesItsTypeAndNoPayload() {
        // 0xF003 self_remove — mls-rs implements it natively; an RCC.16 type we merely advertise
        // does not, and the type is the only thing that tells them apart.
        final byte[] blob = frame(VERSION_OK,
                record(2, "ctx-1", GID, new byte[] { (byte) 0xF0, 0x03 }, new byte[0]));
        final MlsEngineResult r = MlsEngineResult.decodeList(blob).get(0);
        assertEquals(2, r.status);
        assertEquals(0xF003, r.proposalType);
        assertEquals(0, r.payload().length);
    }

    @Test public void severalResultsKeepEngineOrderAndTheirOwnContexts() {
        // The shape 7.3 will produce: the caller's result plus a piggybacked commit.
        final byte[] blob = frame(VERSION_OK,
                record(0, "mine", GID, new byte[0], "body".getBytes(StandardCharsets.UTF_8)),
                record(1, "rekey-42", GID, new byte[0], new byte[0]));
        final List<MlsEngineResult> results = MlsEngineResult.decodeList(blob);
        assertEquals(2, results.size());
        assertEquals("mine", results.get(0).contextId);
        assertEquals(1, results.get(1).status);
        assertEquals("rekey-42", results.get(1).contextId);
    }

    @Test public void aVersionOnlyBlobDecodesToNothingWithoutThrowing() {
        // Legal: the engine had a version to state and no results. Distinct from an EMPTY blob,
        // which means it did not even manage that.
        assertTrue(MlsEngineResult.decodeList(frame(VERSION_OK)).isEmpty());
    }

    @Test public void anUnknownVersionTHROWS() {
        try {
            MlsEngineResult.decodeList(frame(new byte[] { 99 },
                    record(0, "c", GID, new byte[0], new byte[0])));
            fail("an unknown format version must not be best-effort parsed");
        } catch (final IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("unknown format version 99"));
            // The message must name the real cause, because the symptom (garbage results) looks
            // like a corrupt message and sends the reader to the wrong place entirely.
            assertTrue(expected.getMessage(), expected.getMessage().contains("built at different"));
        }
    }

    @Test public void anEmptyBlobTHROWS() {
        for (final byte[] nothing : Arrays.asList(null, new byte[0], new byte[] { 0, 0 })) {
            try {
                MlsEngineResult.decodeList(nothing);
                fail("an empty blob must throw — not even a version byte arrived");
            } catch (final IllegalStateException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().contains("empty result blob"));
            }
        }
    }

    @Test public void aTruncatedRecordTHROWSRatherThanDecodingPartially() {
        // Four fields where five are required. A partial decode here attributes a result to
        // whatever field happened to land in the context slot.
        final byte[] shortRecord = frame(new byte[] { 0 }, "ctx".getBytes(StandardCharsets.UTF_8),
                GID, new byte[0]);
        try {
            MlsEngineResult.decodeList(frame(VERSION_OK, shortRecord));
            fail("a 4-field record must throw");
        } catch (final IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("expected 5"));
        }
    }

    @Test public void aMalformedVersionRecordSaysItIsABuildSkew() {
        try {
            MlsEngineResult.decodeList(frame(new byte[] { 1, 2, 3 }));
            fail("a multi-byte version record must throw");
        } catch (final IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("build skew, not a corrupt message"));
        }
    }

    @Test public void theEncoderAgreesWithTheHandBuiltLayout() {
        final byte[] mine = MlsEngineResult.encodeList(Collections.singletonList(
                new MlsEngineResult("ctx-1", 0, -1, GID,
                        "hello".getBytes(StandardCharsets.UTF_8))));
        final byte[] byHand = frame(VERSION_OK,
                record(0, "ctx-1", GID, new byte[0], "hello".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(byHand, mine);
    }

    @Test public void theRoundTripPreservesEveryField() {
        final List<MlsEngineResult> in = Arrays.asList(
                new MlsEngineResult("a", 0, -1, GID, new byte[] { 1, 2, 3 }),
                new MlsEngineResult("b", 2, 0xF001, GID, new byte[0]),
                new MlsEngineResult("", 9, -1, new byte[0], new byte[0]));
        final List<MlsEngineResult> out =
                MlsEngineResult.decodeList(MlsEngineResult.encodeList(in));
        assertEquals(in.size(), out.size());
        for (int i = 0; i < in.size(); i++) {
            assertEquals(in.get(i).contextId, out.get(i).contextId);
            assertEquals(in.get(i).status, out.get(i).status);
            assertEquals(in.get(i).proposalType, out.get(i).proposalType);
            assertArrayEquals(in.get(i).groupId(), out.get(i).groupId());
            assertArrayEquals(in.get(i).payload(), out.get(i).payload());
        }
    }

    @Test public void everyProcStatusSurvivesTheEncoding() {
        // The status set is SPARSE — 4, 5 and 6 do not exist — so an encoding that assumed a
        // contiguous range would silently mangle the tail.
        final int[] statuses = {
                MlsProcStatus.APP, MlsProcStatus.COMMIT, MlsProcStatus.PROPOSAL,
                MlsProcStatus.OTHER, MlsProcStatus.MALFORMED, MlsProcStatus.APPLY_FAILED,
                MlsProcStatus.PAST_EPOCH,
        };
        for (final int s : statuses) {
            final MlsEngineResult r = MlsEngineResult.decodeList(MlsEngineResult.encodeList(
                    Collections.singletonList(
                            new MlsEngineResult("c", s, -1, GID, new byte[0])))).get(0);
            assertEquals(s, r.status);
            assertTrue("status " + s + " must stay known after a round trip",
                    MlsProcStatus.isKnown(r.status));
        }
    }

    @Test public void aByteArrayIsCopiedNotAliased() {
        final byte[] payload = { 1, 2, 3 };
        final MlsEngineResult r = new MlsEngineResult("c", 0, -1, GID, payload);
        payload[0] = 99;
        assertEquals(1, r.payload()[0]);
        r.payload()[0] = 42;
        assertEquals(1, r.payload()[0]);
    }
}

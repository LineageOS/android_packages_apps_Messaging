/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * The repeated-result wire format between the Rust engine and the Java host. The fixtures are
 * hand-built bytes in the layout Rust's {@code join_len_prefixed} produces, not output from
 * {@code encodeList}, so they check the contract rather than self-agreement. See
 * docs/mls/rust-core.md.
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
        // 0xF003 self_remove: mls-rs implements it natively, unlike an RCC.16 type we only
        // advertise, and the type is what tells them apart.
        final byte[] blob = frame(VERSION_OK,
                record(2, "ctx-1", GID, new byte[] { (byte) 0xF0, 0x03 }, new byte[0]));
        final MlsEngineResult r = MlsEngineResult.decodeList(blob).get(0);
        assertEquals(2, r.status);
        assertEquals(0xF003, r.proposalType);
        assertEquals(0, r.payload().length);
    }

    @Test public void severalResultsKeepEngineOrderAndTheirOwnContexts() {
        // The caller's result plus a piggybacked commit.
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
        // Legal: a version and no results. Distinct from an empty blob, which lacks even the
        // version.
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
            // The message names the real cause (a build skew); the symptom looks like a corrupt
            // message.
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
        // Four fields where five are required; a partial decode would misattribute a result.
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
        // The status set is sparse (4, 5 and 6 do not exist), so a contiguous-range encoding would
        // mangle the tail.
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

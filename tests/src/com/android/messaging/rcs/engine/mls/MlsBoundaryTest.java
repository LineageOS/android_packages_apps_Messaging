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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The Stage C boundary — {@link StoreRead}, {@link MlsPorts}, {@link MlsMessageId} (items 1.1–1.4). */
public class MlsBoundaryTest {

    // ---- 1.2: the three-valued read contract ---------------------------------------------------

    @Test
    public void theThreeValues_areDistinguishable() {
        final StoreRead<byte[]> ok = StoreRead.ok(new byte[] {1});
        final StoreRead<byte[]> nf = StoreRead.notFound();
        final StoreRead<byte[]> err = StoreRead.err("disk on fire");

        assertTrue(ok.isOk() && !ok.isNotFound() && !ok.isErr());
        assertTrue(nf.isNotFound() && !nf.isOk() && !nf.isErr());
        assertTrue(err.isErr() && !err.isOk() && !err.isNotFound());
    }

    /** An empty value is a PRESENT value. Conflating it with absence is the two-valued bug again. */
    @Test
    public void anEmptyValue_isStillOk() {
        final StoreRead<byte[]> ok = StoreRead.ok(new byte[0]);
        assertTrue(ok.isOk());
        assertEquals(0, ((StoreRead.Ok<byte[]>) ok).value.length);
    }

    /**
     * {@code Ok(null)} must not be constructible: it would force the caller to null-check a value
     * the type says is present, which is precisely the world this type replaces.
     */
    @Test
    public void okOfNull_becomesAnError_notASilentOk() {
        final StoreRead<byte[]> r = StoreRead.ok(null);
        assertTrue("Ok(null) must not read as a successful read", r.isErr());
        assertFalse(r.isOk());
    }

    @Test
    public void orElse_treatsNotFoundAndErrAlike_andSaysSoInTheName() {
        final byte[] fallback = new byte[] {9};
        assertArrayEquals(new byte[] {1}, StoreRead.ok(new byte[] {1}).orElse(fallback));
        assertArrayEquals(fallback, StoreRead.<byte[]>notFound().orElse(fallback));
        assertArrayEquals(fallback, StoreRead.<byte[]>err("x").orElse(fallback));
        assertNull(StoreRead.<byte[]>notFound().orNullDiscardingTheDistinction());
    }

    /**
     * The two-valued sibling has NO NotFound constructor. §3.3: generateMessageId is the one
     * callback where the host may not answer "I have none" — a host that cannot mint an id has a
     * bug, not an absence, and the type refuses to let it report one as the other.
     */
    @Test
    public void requiredHasNoAbsentValue() {
        assertTrue(StoreRead.Required.ok("Mx123").isOk());
        assertFalse(StoreRead.Required.err("no generator").isOk());
        assertNull(StoreRead.Required.err("no generator").orNull());
        assertFalse("Required.ok(null) must not be a success",
                StoreRead.Required.ok(null).isOk());
    }

    // ---- 1.1: the ports ------------------------------------------------------------------------

    /**
     * The declared-but-unrouted storage ports must REFUSE, never silently succeed. A silent success
     * would let the engine believe a write landed — far worse than a refusal.
     */
    @Test
    public void engineOwnedStorage_refusesRatherThanPretends() {
        final MlsPorts p = MlsPorts.withEngineOwnedStorage(null, MlsPorts.SYSTEM_CLOCK, null);

        assertTrue(p.groupState.state(new byte[] {1}).isErr());
        assertTrue(p.groupState.epoch(new byte[] {1}, 3L).isErr());
        assertTrue(p.groupState.maxEpochId(new byte[] {1}).isErr());
        assertNotNull("a refused write must return a reason, not null-for-success",
                p.groupState.write(new byte[] {1}, new byte[0],
                        Collections.<MlsPorts.EpochRecord>emptyList(),
                        Collections.<MlsPorts.EpochRecord>emptyList()));

        assertTrue(p.pendingMessages.get(new byte[] {1}).isErr());
        assertNotNull(p.pendingMessages.put(new byte[] {1}, new byte[] {2}));
        assertNotNull(p.pendingMessages.delete(new byte[] {1}));

        assertTrue(p.keyPackages.get(new byte[] {1}).isErr());
        assertNotNull(p.keyPackages.put(new byte[] {1}, new byte[] {2}));
        assertNotNull(p.keyPackages.delete(new byte[] {1}));
    }

    /**
     * Telemetry is the ONLY port allowed to be absent: a missing metrics plane must never be the
     * reason an operation fails. The other five have no safe default — silently discarding a state
     * write is not a degraded mode, it is data loss.
     */
    @Test
    public void telemetryIsTheOnlyOptionalPort() {
        final MlsPorts p = MlsPorts.withEngineOwnedStorage(null, MlsPorts.SYSTEM_CLOCK, null);
        assertSame(MlsTelemetry.NONE, p.telemetry);
        p.telemetry.count(MlsMetrics.ZINNIA_STATE_SIZE, 12);   // must not throw
    }

    /** The default clock reports itself untrusted rather than claiming an authority it lacks. */
    @Test
    public void systemClock_doesNotClaimToBeTrusted() {
        assertFalse(MlsPorts.SYSTEM_CLOCK.isTrusted());
        assertTrue(MlsPorts.SYSTEM_CLOCK.nowMs() > 0L);
    }

    // ---- 1.4: MessageContent + the message-id generator -----------------------------------------

    /** Field 8 is self-recursive, and that is the interesting property of this type. */
    @Test
    public void messageContent_nestsItselfAtFieldEight() {
        final MlsMessageContent original = MlsMessageContent.builder()
                .content(new byte[] {1, 2, 3}).clientId("client-a").build();
        final MlsMessageContent m = MlsMessageContent.builder()
                .content(new byte[] {4}).original(original).groupId("g").build();

        assertSame(original, m.original);
        final byte[] wire = m.encode();
        // field 8, wire type 2 => tag byte 0x42
        assertTrue("the nested original must be encoded at field 8", contains(wire, (byte) 0x42));
    }

    /** Optional-with-presence: a zero-valued field is OMITTED, so absent stays distinguishable. */
    @Test
    public void absentFields_areOmittedNotZeroEncoded() {
        assertEquals(0, MlsMessageContent.builder().build().encode().length);
        assertTrue(MlsMessageContent.builder().f2(1L).build().encode().length > 0);
    }

    @Test
    public void ftdRetryCounts_areRepeatedAtFieldSix() {
        final byte[] wire = MlsMessageContent.builder()
                .addFtdRetryCount(2, "client-a")
                .addFtdRetryCount(5, "client-b")
                .build().encode();
        int n = 0;
        for (final byte b : wire) if (b == (byte) 0x32) n++;      // field 6, wire type 2
        assertEquals("both retry counts must be present", 2, n);
    }

    /** The IMDN ladder's ORDER is load-bearing: a decrypt failure outranks a read timestamp. */
    @Test
    public void imdnLadder_reportsDecryptFailureAheadOfRead() {
        assertEquals(MlsMessageContent.ImdnState.UNAVAILABLE,
                MlsMessageContent.ImdnState.of(false, true, 5L, 5L));
        assertEquals(MlsMessageContent.ImdnState.FAILED_TO_DECRYPT,
                MlsMessageContent.ImdnState.of(true, true, 5L, 5L));
        assertEquals(MlsMessageContent.ImdnState.READ,
                MlsMessageContent.ImdnState.of(true, false, 5L, 5L));
        assertEquals(MlsMessageContent.ImdnState.DELIVERED,
                MlsMessageContent.ImdnState.of(true, false, 0L, 5L));
        assertEquals(MlsMessageContent.ImdnState.UNAVAILABLE,
                MlsMessageContent.ImdnState.of(true, false, 0L, 0L));
    }

    /**
     * The id shape Google Messages validates against. If a peer or the server checks it, an id we mint that
     * they reject is a message that silently never arrives.
     */
    @Test
    public void generatedIds_matchTheValidatedShape() {
        for (int i = 0; i < 200; i++) {
            final String id = MlsMessageId.generate();
            assertTrue(id, MlsMessageId.isWellFormed(id));
            assertTrue(id, id.startsWith("Mx"));
            assertEquals("16 bytes always base64 to 22 chars", 24, id.length());
        }
    }

    /** Pure in its UUID, so the encoding itself is pinned and not merely the shape. */
    @Test
    public void theEncoding_isPinnedForAKnownUuid() {
        final UUID u = UUID.fromString("00000000-0000-0000-0000-000000000000");
        assertEquals("MxAAAAAAAAAAAAAAAAAAAAAA", MlsMessageId.of(u));
        // All-ones exercises the tail branch and the '_' -> '=' substitution Google Messages applies.
        // All-ones is the case that actually exercises the '_' -> '=' substitution: the 64th
        // alphabet character only appears for a run of set bits.
        // 16 bytes = five full 3-byte groups (20 chars, every one index 0x3F) plus a 1-byte tail
        // (2 chars: 0x3F then 0x30='w'), so 21 '=' and a trailing 'w' — the tail branch is not
        // symmetric with the body, which is exactly why it is pinned rather than assumed.
        final String s = MlsMessageId.of(new UUID(-1L, -1L));
        assertEquals("Mx" + repeat('=', 21) + "w", s);
        assertFalse("Google Messages maps '_' to '=' — no underscore may survive", s.contains("_"));
    }

    @Test
    public void generatedIds_areUnique() {
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) seen.add(MlsMessageId.generate());
        assertEquals(1000, seen.size());
    }

    @Test
    public void malformedIds_areRejected() {
        assertFalse(MlsMessageId.isWellFormed(null));
        assertFalse(MlsMessageId.isWellFormed(""));
        assertFalse(MlsMessageId.isWellFormed("Mx"));
        assertFalse(MlsMessageId.isWellFormed("Mxtooshort"));
        assertFalse("the Mx prefix is part of the shape",
                MlsMessageId.isWellFormed("XX" + repeat('a', 22)));
        assertFalse(MlsMessageId.isWellFormed("Mx" + repeat('a', 27)));
        assertTrue(MlsMessageId.isWellFormed("Mx" + repeat('a', 22)));
        assertTrue(MlsMessageId.isWellFormed("Mx" + repeat('a', 26)));
    }

    private static String repeat(final char c, final int n) {
        final char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    private static boolean contains(final byte[] hay, final byte needle) {
        for (final byte b : hay) if (b == needle) return true;
        return false;
    }
}

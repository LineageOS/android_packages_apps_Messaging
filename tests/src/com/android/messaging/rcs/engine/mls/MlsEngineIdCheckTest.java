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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.Test;

/** The engine's RCC.16 §7.5.3.1 check, which decides; the wiring is in the guard test. */
public final class MlsEngineIdCheckTest {

    private static MlsEngineIdCheck.Processed processed(final boolean engineMismatch,
            final String detail) {
        final FakeShellPort f = new FakeShellPort();
        return MlsEngineIdCheck.process(f.stub(MlsSession.class, "setRequestMessageId", null,
                "process", new byte[] {1}, "lastMessageIdMismatch", engineMismatch,
                "lastMessageIdMismatchDetail",
                detail == null ? null : detail.getBytes(StandardCharsets.UTF_8)),
                new byte[] {2}, new byte[] {3}, "m1");
    }

    @Test
    public void theVerdictIsTheEngines() {
        assertTrue(processed(true, "m1\0x").idMismatch);
        assertFalse(processed(false, null).idMismatch);
    }

    @Test
    public void theDetailIsReadOnlyOnAMismatch() {
        assertNull(processed(false, "ignored").detail);
        assertNull(processed(false, "ignored").aadMessageId());
        assertArrayEquals("m1\0x".getBytes(StandardCharsets.UTF_8),
                processed(true, "m1\0x").detail);
        assertEquals("the AAD's id, the half after the NUL", "x",
                processed(true, "m1\0x").aadMessageId());
    }

    /** The armed id is cleared even when process throws: it is sticky per engine thread. */
    @Test
    public void theIdIsDisarmedEvenWhenProcessThrows() {
        final List<Object> armed = new ArrayList<>();
        final FakeShellPort f = new FakeShellPort();
        final MlsSession s = f.stub(MlsSession.class,
                "setRequestMessageId", (Function<Object[], Object>) a -> {
                    armed.add(a[0] == null ? null : new String((byte[]) a[0],
                            StandardCharsets.UTF_8));
                    return null;
                },
                "process", (Function<Object[], Object>) a -> {
                    throw new IllegalStateException("boom");
                });
        try {
            MlsEngineIdCheck.process(s, new byte[] {2}, new byte[] {3}, "m1");
            fail("the exception propagates");
        } catch (final IllegalStateException expected) {
            // expected
        }
        assertEquals(java.util.Arrays.asList("m1", null), armed);
    }

    /**
     * An envelope with no id arms an EMPTY id, not nothing: the engine then refuses an AAD that
     * names one, which is what the host check did. Null only disarms.
     */
    @Test
    public void anAbsentIdArmsAnEmptyId() {
        for (final String absent : new String[] {"", null}) {
            final List<Object> armed = new ArrayList<>();
            final FakeShellPort f = new FakeShellPort();
            MlsEngineIdCheck.process(f.stub(MlsSession.class,
                    "setRequestMessageId", (Function<Object[], Object>) a -> {
                        armed.add(a[0]);
                        return null;
                    }, "process", null, "lastMessageIdMismatch", false), new byte[] {2},
                    new byte[] {3}, absent);
            assertEquals(2, armed.size());
            assertArrayEquals("armed for " + absent, new byte[0], (byte[]) armed.get(0));
            assertNull("disarmed", armed.get(1));
        }
    }

    @Test
    public void aDetailWithoutASeparatorIsNotSplit() {
        assertNull(MlsEngineIdCheck.splitDetail("nonul".getBytes(StandardCharsets.UTF_8)));
        assertNull(MlsEngineIdCheck.splitDetail(null));
        assertEquals("", new String(MlsEngineIdCheck.splitDetail(new byte[] {0, 'x'})[0],
                StandardCharsets.UTF_8));
    }
}

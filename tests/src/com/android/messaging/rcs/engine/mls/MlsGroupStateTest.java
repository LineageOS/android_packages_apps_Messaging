/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsGroupStateTest {

    private static Group group(final byte[] id, final long era) {
        final Group g = new Group();
        g.groupId = id;
        g.era = era;
        return g;
    }

    @Test
    public void hasMlsGroupNeedsASessionAndAGroupWithAnId() {
        assertFalse(MlsGroupState.hasMlsGroup(new FakeShellPort()
                .returns("ensureSession", false).port(), "grp"));

        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("getGroup", group(new byte[] {1}, 1));
        assertTrue(MlsGroupState.hasMlsGroup(f.port(), "grp"));
        assertTrue("the group is looked up by its canonical key",
                f.calls.contains("getGroup(g:grp)"));

        assertFalse(MlsGroupState.hasMlsGroup(new FakeShellPort().returns("ensureSession", true)
                .returns("getGroup", group(null, 1)).port(), "grp"));
        assertFalse(MlsGroupState.hasMlsGroup(new FakeShellPort().returns("ensureSession", true)
                .returns("getGroup", null).port(), "grp"));
    }


    @Test
    public void conversationIsMlsResolvesTheInboundKeyFirst() {
        final FakeShellPort f = new FakeShellPort().returns("resolveInbound", "p:+1")
                .returns("getGroup", group(new byte[] {2}, 1));
        assertTrue(MlsGroupState.conversationIsMls(f.port(), null, "+1"));
        assertTrue(f.calls.contains("getGroup(p:+1)"));
        assertFalse(MlsGroupState.conversationIsMls(new FakeShellPort()
                .returns("resolveInbound", null).port(), null, "+1"));
    }


    @Test
    public void localEraPrefersTheHostRowAndOnlyFallsBackToTheEngineForAGroupId() {
        assertEquals(5, MlsGroupState.localEra(new FakeShellPort()
                .returns("getGroup", group(new byte[] {1}, 5)).port(), MlsLogSink.NONE, "grp",
                null));
        assertEquals("a 1:1 with no host row has no engine era to read", -1,
                MlsGroupState.localEra(new FakeShellPort().returns("getGroup", null).port(),
                        MlsLogSink.NONE, null, "+1"));
        assertEquals(-1, MlsGroupState.localEra(new FakeShellPort().returns("getGroup", null)
                .returns("ensureSession", false).port(), MlsLogSink.NONE, "grp", null));
    }


    @Test
    public void groupLoadsIsOkOrNoOpAndAThrowIsUnloadableNotAnError() {
        final FakeShellPort f = new FakeShellPort();
        for (final MlsSession.OpStatus st : MlsSession.OpStatus.values()) {
            final MlsSession s =
                    f.stub(MlsSession.class, "commitRequired", false, "lastStatus", st);
            assertEquals(st.name(), st == MlsSession.OpStatus.OK || st == MlsSession.OpStatus.NO_OP,
                    MlsGroupState.groupLoads(s, MlsLogSink.NONE, new byte[] {1}));
        }
        final FakeShellPort.Log log = new FakeShellPort.Log();
        final MlsSession boom = f.stub(MlsSession.class, "commitRequired",
                (java.util.function.Function<Object[], Object>) a ->
                { throw new IllegalStateException(); });
        assertFalse(MlsGroupState.groupLoads(boom, log, new byte[] {1}));
        assertTrue(log.said("W", "treating as unloadable"));
    }


    @Test
    public void groupIdForTraceIsEmptyRatherThanThrowingInsideALogLine() {
        final byte[] id = {0x0a, 0x0b};
        assertEquals(MlsTrace.groupId(id), MlsGroupState.groupIdForTrace(new FakeShellPort()
                .returns("getGroup", group(id, 1)).port(), "g:x"));
        assertEquals("", MlsGroupState.groupIdForTrace(new FakeShellPort().port(), null));
        assertEquals("", MlsGroupState.groupIdForTrace(new FakeShellPort()
                .returns("getGroup", null).port(), "g:x"));
        assertEquals("", MlsGroupState.groupIdForTrace(new FakeShellPort().on("getGroup",
                a -> { throw new IllegalStateException(); }).port(), "g:x"));
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.DeferredResend;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsGroupStateSplitTest {


    @Test
    public void isEndMlsNeedsTheGroupAndAnEndMlsHealth() {
        assertTrue(MlsGroupState.isEndMls(
                SplitFixtures.port(storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS)))
                .port(), MlsLogSink.NONE, "grp", null));
        assertFalse(MlsGroupState.isEndMls(
                SplitFixtures.port(storeWith(b -> b.healthStatus(MlsHealthStates.HEALTHY)))
                .port(), MlsLogSink.NONE, "grp", null));
    }


    @Test
    public void haveWeLeftAsksTheCanonicalKeysRecord() {
        assertTrue(MlsGroupState.haveWeLeft(
                SplitFixtures.port(storeWith(b -> b.selfLeftAtMs(1L))).port(),
                MlsLogSink.NONE, "grp", null));
        assertFalse(MlsGroupState.haveWeLeft(SplitFixtures.port(storeWith(b -> b)).port(),
                MlsLogSink.NONE, null, null));
    }


    private static MlsSession.OpStatus notLoaded() {
        for (final MlsSession.OpStatus st : MlsSession.OpStatus.values()) {
            if (st != MlsSession.OpStatus.OK && st != MlsSession.OpStatus.NO_OP) return st;
        }
        throw new AssertionError("no failing OpStatus");
    }

    private static FakeShellPort inboundPort(final MlsSession.OpStatus status) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).on("putGroup", a -> null);
        f.returns("session", f.stub(MlsSession.class, "commitRequired", false, "lastStatus", status,
                "eraEpoch", new byte[12], "epochAuth", new byte[] {1}));
        return f;
    }

    @Test
    public void aHeldGroupAnswersOnlyIfTheEngineCanStillLoadIt() {
        assertEquals(KEY, MlsGroupState.resolveInbound(inboundPort(MlsSession.OpStatus.OK).port(),
                MlsLogSink.NONE, "grp", "+2"));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsGroupState.resolveInbound(inboundPort(notLoaded()).port(), log, "grp", "+2"));
        assertTrue(log.said("W", "can no longer LOAD"));
    }

    @Test
    public void aOneToOneTheProviderKnowsIsAdopted() {
        final FakeShellPort f = inboundPort(MlsSession.OpStatus.OK).returns("getGroup", null);
        f.returns("rpc", f.stub(MlsProviderRpc.class, "getMlsGroupIdForPeer", GID));
        final String key = MlsConversationKey.canonicalKey(null, "+2");
        assertEquals(key, MlsGroupState.resolveInbound(f.port(), MlsLogSink.NONE, null, "+2"));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("putGroup(" + key + ",")));
    }


    @Test
    public void putGroupHoldsTheGroupAliasesItAndWritesItsRecordUnderTheLock() {
        final FakeRecords s = new FakeRecords();
        final java.util.Map<String, Group> groups = new java.util.HashMap<>();
        final FakeShellPort f = SplitFixtures.port(s).returns("groups", groups);
        final Group g = grp();
        MlsGroupState.putGroup(f.port(), MlsLogSink.NONE, KEY, g);
        assertSame(g, groups.get(KEY));
        assertFalse(s.aliases.isEmpty());
        assertTrue(f.calls.indexOf("lock(" + KEY + ")") < f.calls.indexOf("unlock(" + KEY + ")"));
    }
}

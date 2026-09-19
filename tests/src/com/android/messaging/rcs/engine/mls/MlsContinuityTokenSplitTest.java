/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.port;
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

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.MlsAdoptionUndo;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsContinuityTokenSplitTest {


    @Test
    public void noteContinuityTokenStoresFirstReplacesDifferentAndSkipsTheSame() {
        final byte[] gid = {1, 2};
        final Group g = new Group();
        g.groupId = gid;
        final FakeRecords store = new FakeRecords();
        final FakeShellPort f = new FakeShellPort().returns("getGroup", g).returns("selfE164", "+1")
                .returns("records", store).on("lock", a -> null).on("unlock", a -> null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertTrue(MlsContinuityToken.noteContinuityToken(f.port(), log, "g:x", new byte[] {1},
                "welcome"));
        assertTrue(log.said("I", "continuity FIRST"));
        assertTrue(MlsContinuityToken.noteContinuityToken(f.port(), log, "g:x", new byte[] {1},
                "commit"));
        assertTrue(log.said("I", "continuity SAME"));
        assertTrue(MlsContinuityToken.noteContinuityToken(f.port(), log, "g:x", new byte[] {2},
                "commit"));
        assertTrue(log.said("I", "continuity REPLACED (was 1B)"));
        assertFalse("a peer-chosen length is a write amplifier",
                MlsContinuityToken.noteContinuityToken(
                f.port(), log, "g:x", new byte[MlsContinuityToken.MAX_STORED_TOKEN_BYTES + 1],
                "peer"));
        store.unreadable = "x";
        assertFalse("an unreadable record is not overwritten to store a token",
                MlsContinuityToken.noteContinuityToken(f.port(), log, "g:x", new byte[] {3},
                        "commit"));
    }


    @Test
    public void aWelcomesContinuityTokenIsStoredAndAThrowLeavesTheJoinStanding() {
        final FakeRecords s = storeWith(b -> b);
        final FakeShellPort f = port(s);
        f.returns("session",
                f.stub(MlsSession.class, "takeWelcomeContinuityToken", new byte[] {4, 5}));
        MlsContinuityToken.collectWelcomeContinuityToken(f.port(), MlsLogSink.NONE, KEY, GID);
        assertArrayEquals(new byte[] {4, 5}, rec(s).continuityToken);
        final FakeShellPort boom = port(storeWith(b -> b));
        boom.returns("session", boom.stub(MlsSession.class, "takeWelcomeContinuityToken",
                (java.util.function.Function<Object[], Object>) a ->
                { throw new IllegalStateException(); }));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsContinuityToken.collectWelcomeContinuityToken(boom.port(), log, KEY, GID);
        assertTrue(log.said("W", "the join stands"));
    }
}

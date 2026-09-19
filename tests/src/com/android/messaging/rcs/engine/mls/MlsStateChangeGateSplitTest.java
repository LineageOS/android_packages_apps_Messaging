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

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsStateChangeGateSplitTest {


    @Test
    public void anUnhonourableProposalIsDroppedUnlessWeLeftOrACommitIsOwed() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b));
        final boolean[] cleared = {false};
        f.returns("session", f.stub(MlsSession.class, "clearPendingProposals",
                (java.util.function.Function<Object[], Object>) a -> { cleared[0] =
                        true; return true; }));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsStateChangeGate.dropUnhonourableProposal(f.port(), log, KEY, "+2",
                MlsSession.PROP_SERVER_REMOVE);
        assertTrue(cleared[0]);
        assertTrue(log.said("W", "Dropped on POLICY"));

        cleared[0] = false;
        final FakeShellPort left = SplitFixtures.port(storeWith(b -> b.selfLeftAtMs(9L)));
        left.returns("session", f.port().session());
        final FakeShellPort.Log l2 = new FakeShellPort.Log();
        MlsStateChangeGate.dropUnhonourableProposal(left.port(), l2, KEY, "+2", -1);
        assertFalse("our own SelfRemove would go with it", cleared[0]);
        assertTrue(l2.said("W", "NOT dropped: WE LEFT"));

        final FakeShellPort owed = SplitFixtures.port(
                storeWith(b -> b.pendingOperation(op(MlsPendingOperation.Kind.END_MLS))));
        owed.returns("session", f.port().session());
        MlsStateChangeGate.dropUnhonourableProposal(owed.port(), MlsLogSink.NONE, KEY, "+2", 0x77);
        assertFalse("the owed commit sweeps it; a drop would lose what the commit honours",
                cleared[0]);
    }


    @Test
    public void theDropLeverRefusesAGroupWeLeft() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b.selfLeftAtMs(9L)));
        assertTrue(MlsStateChangeGate.dropPendingProposals(f.port(), MlsLogSink.NONE, "grp", null)
                .contains("REFUSED: we LEFT"));
        final FakeShellPort ok = SplitFixtures.port(storeWith(b -> b));
        ok.returns("session",
                ok.stub(MlsSession.class, "commitRequired", true, "clearPendingProposals", true));
        assertTrue(MlsStateChangeGate.dropPendingProposals(ok.port(), MlsLogSink.NONE, "grp", null)
                .startsWith("key=g:grp wasBlocked=true cleared=true"));
    }


    @Test
    public void aSelfRemoveOrEndMlsProposalClaimsItsCommitAndAnythingElseIsDropped() {
        final FakeRecords a = storeWith(b -> b);
        MlsStateChangeGate.onInboundProposal(SplitFixtures.port(a).port(), MlsLogSink.NONE, KEY,
                "+2", "grp", MlsSession.PROP_SELF_REMOVE);
        assertEquals(MlsPendingOperation.Kind.COMMIT_PENDING_PROPOSALS,
                rec(a).pendingOperation.kind);
        final FakeRecords b = storeWith(x -> x);
        MlsStateChangeGate.onInboundProposal(SplitFixtures.port(b).port(), MlsLogSink.NONE, KEY,
                "+2", "grp", MlsSession.PROP_END_MLS);
        assertEquals(MlsPendingOperation.Kind.END_MLS, rec(b).pendingOperation.kind);
        final FakeShellPort c = SplitFixtures.port(storeWith(x -> x));
        c.returns("session", c.stub(MlsSession.class, "clearPendingProposals", true));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsStateChangeGate.onInboundProposal(c.port(), log, KEY, "+2", "grp",
                MlsSession.PROP_SERVER_REMOVE);
        assertTrue(log.said("W", "DROPPED rather than committed"));
    }


    private static FakeShellPort proposalPort(final boolean required, final int era) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("commitAndSend", era)
                .returns("openMlsSession", null);
        f.returns("session", f.stub(MlsSession.class, "commitRequired", required));
        return f;
    }

    @Test
    public void aCachedProposalIsCommittedAsARekeyAndItsSlotReleased() {
        final FakeShellPort f = proposalPort(true, 4);
        assertTrue(MlsStateChangeGate.commitPendingProposals(f.port(), MlsLogSink.NONE,
                "grp", "+2"));
        assertTrue(f.calls.stream()
                .anyMatch(c -> c.startsWith("commitAndSend(grp, +2, null, null, REKEY")));
    }

    @Test
    public void nothingToCommitOrARefusedCommitReturnsFalse() {
        final FakeShellPort none = proposalPort(false, 4);
        assertFalse(MlsStateChangeGate.commitPendingProposals(none.port(),
                MlsLogSink.NONE, "grp", "+2"));
        assertFalse(none.calls.stream().anyMatch(c -> c.startsWith("commitAndSend(")));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsStateChangeGate.commitPendingProposals(proposalPort(true, -1).port(),
                log, "grp", "+2"));
        assertTrue(log.said("I", "FAILED (peer remains a member)"));
    }
}

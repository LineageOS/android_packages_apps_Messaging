/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertNotEquals;
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

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PendingKeyUpdate;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraReconcile;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsCredentialUpdateSplitTest {


    private static final long NOW = System.currentTimeMillis() / 1000L;
    private static final long CLIENT_NOT_AFTER = NOW + 60 * 86400L;

    private static MlsSelfLeafStatus leaf(final boolean stale, final long clientNotAfter) {
        return new MlsSelfLeafStatus(0, NOW - 86400L, NOW + 10 * 86400L, NOW - 86400L,
                clientNotAfter, stale);
    }

    /**
     * A port whose session reports a stale leaf first, then {@code after}; rekey runs
     * {@code rekey}.
     */
    private static FakeShellPort port(final ConvState cs, final MlsSelfLeafStatus first,
            final MlsSelfLeafStatus after, final Function<Object[], Object> rekey) {
        final FakeShellPort f =
                SplitFixtures.port(storeWith(b -> b)).returns("conv", cs).on("rekey", rekey);
        final int[] n = {0};
        f.returns("session", f.stub(MlsSession.class,
                "selfLeafStatus", (Function<Object[], Object>) a -> n[0]++ == 0 ? first : after,
                "eraEpoch", new byte[12], "memberValidity", null));
        return f;
    }

    private static boolean update(final FakeShellPort f, final FakeShellPort.Log log) {
        return MlsCredentialUpdate.maybeUpdateGroupCredential(MlsConfig.defaults(), f.port(), log,
                KEY, grp(), "grp", null, "test");
    }

    @Test
    public void anAcceptedUpdateSpendsTheCertificatesOneAttempt() {
        final ConvState cs = new ConvState();
        final FakeShellPort f =
                port(cs, leaf(true, CLIENT_NOT_AFTER), leaf(false, CLIENT_NOT_AFTER), a -> {
            cs.lastControlVerdict = MlsTransportDisposition.VERDICT_OK;
            return 3;
        });
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertTrue(update(f, log));
        assertEquals(CLIENT_NOT_AFTER, cs.credentialUpdateAttemptedFor);
        assertTrue(log.said("I", "ACCEPTED → era=3"));
    }

    @Test
    public void aCommitNoServerJudgedGivesTheAttemptBack() {
        final ConvState cs = new ConvState();
        // verdict never set
        final FakeShellPort f = port(cs, leaf(true, CLIENT_NOT_AFTER), null, a -> 3);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(update(f, log));
        assertEquals("the marker is restored, not spent", 0L, cs.credentialUpdateAttemptedFor);
        assertTrue(log.said("E", "WITHHELD BY THE AHEAD FIXTURE"));
    }

    @Test
    public void aThrowAfterTakingTheAttemptGivesItBack() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = port(cs, leaf(true, CLIENT_NOT_AFTER), null,
                a -> { throw new IllegalStateException("boom"); });
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(update(f, log));
        assertEquals(0L, cs.credentialUpdateAttemptedFor);
        assertTrue(log.said("W", "AFTER taking this certificate's attempt"));
    }

    @Test
    public void theDeclinesThatNeverTakeTheAttempt() {
        final ConvState cs = new ConvState();
        final Function<Object[], Object> noRekey =
                a -> { throw new AssertionError("must not rekey"); };
        final FakeShellPort.Log floor = new FakeShellPort.Log();
        assertFalse("our NEW credential inside the floor: a fresh mint is the remedy, not a Commit",
                update(port(cs, leaf(true, NOW + 5 * 86400L), null, noRekey), floor));
        assertTrue(floor.said("E", "forbids a Self-Update"));
        assertFalse(update(port(cs, leaf(false, CLIENT_NOT_AFTER), null, noRekey),
                new FakeShellPort.Log()));
        final FakeShellPort.Log unevaluable = new FakeShellPort.Log();
        assertFalse(update(port(cs, null, null, noRekey), unevaluable));
        assertTrue(unevaluable.said("W", "UN-EVALUABLE"));
        final FakeShellPort down =
                SplitFixtures.port(storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS)));
        down.returns("session", down.stub(MlsSession.class));
        final FakeShellPort.Log ed1 = new FakeShellPort.Log();
        assertFalse(MlsCredentialUpdate.maybeUpdateGroupCredential(MlsConfig.defaults(),
                down.port(), ed1, KEY, grp(), "grp", null, "test"));
        assertTrue(ed1.said("I", "INVARIANT ED-1"));
        assertEquals(0L, cs.credentialUpdateAttemptedFor);
    }


    private static final String REFUSAL =
            "FAIL PERMISSION_DENIED: Time-related validation error: client "
            + "\"42274bf1-064b-4678-8a6a-c24f65829b8b\" with MSISDN \"+15715550104\" error: "
            + "Validation error: Validity { not_before: 2026-07-23 04:25:14 (1784780714), "
            + "not_after: 2026-10-06 03:25:14 (1791257114) } with participant signature validity "
            + "Some(Validity { not_before: 2026-07-23 03:25:16, not_after: 2026-10-06 15:25:16 }) "
            + "is not valid at time: LoggedMlsTime { epoch_seconds: 1791612709, date: "
            + "\"2026-10-10 06:11:49\" }";

    @Test
    public void aRefusalNamingAnotherMemberReportsTheRosterAndAnythingElseIsSilent() {
        final FakeShellPort f = new FakeShellPort().returns("selfE164", "+1");
        f.returns("session", f.stub(MlsSession.class, "memberValidity", null));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsCredentialUpdate.reportCredentialRefusal(MlsConfig.defaults(), f.port(), log, KEY, grp(),
                "grp", "+2",
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, REFUSAL), "remove");
        assertTrue(log.lines.stream().anyMatch(l -> l.startsWith("E ") && l.contains("***0104")));
        assertTrue("the member's number is masked",
                log.lines.stream().noneMatch(l -> l.contains("5715550104")));
        assertTrue(log.said("I", "the roster is unreadable"));
        for (final MlsProviderRpc.ControlResult quiet : new MlsProviderRpc.ControlResult[] {null,
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, "Era changed from 6 to 5")}) {
            final FakeShellPort.Log none = new FakeShellPort.Log();
            MlsCredentialUpdate.reportCredentialRefusal(MlsConfig.defaults(),
                    new FakeShellPort().port(), none, KEY, grp(), "grp", "+2", quiet, "remove");
            assertTrue("not a credential refusal: nothing to say", none.lines.isEmpty());
        }
    }


    @Test
    public void anExpiredCredentialWeCannotAttributeOrOurOwnInsideTheFloorRefreshesTheIdentity() {
        final FakeShellPort unknown = SplitFixtures.port(storeWith(b -> b))
                .returns("getGroup", null)
                .returns("prefs", new FakePrefs()).returns("refreshIdentityFromProvider", false);
        unknown.returns("session", unknown.stub(MlsSession.class));
        MlsCredentialUpdate.expiredCredentialRemedy(MlsConfig.defaults(), unknown.port(),
                MlsLogSink.NONE, KEY, "grp", "+2", "m1");
        assertTrue(unknown.calls.contains("refreshIdentityFromProvider()"));
        final FakeShellPort floor = SplitFixtures.port(storeWith(b -> b))
                .returns("prefs", new FakePrefs())
                .returns("refreshIdentityFromProvider", false);
        floor.returns("session",
                floor.stub(MlsSession.class, "selfLeafStatus", leaf(false, NOW + 5 * 86400L)));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsCredentialUpdate.expiredCredentialRemedy(MlsConfig.defaults(), floor.port(), log, KEY,
                "grp", "+2", "m1");
        assertTrue(log.said("W", "a fresh identity/KeyPackage is needed"));
        assertTrue(floor.calls.contains("refreshIdentityFromProvider()"));
    }
}

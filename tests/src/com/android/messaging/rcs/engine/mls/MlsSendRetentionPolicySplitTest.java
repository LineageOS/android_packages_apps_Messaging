/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static org.junit.Assert.assertNull;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import org.junit.Test;

public final class MlsSendRetentionPolicySplitTest {


    @Test
    public void expiredBodiesTakeTheirSealedCiphertextWithThem() {
        final FakeSealedCache sealed = new FakeSealedCache();
        final FakeShellPort f = new FakeShellPort().returns("sealedCache", sealed)
                .returns("elapsedRealtime", 1000L);
        f.returns("pendingBodies", f.stub(MlsPendingBodyAccess.class, "sweepExpired",
                java.util.Arrays.asList("m1", "m2")));
        MlsSendRetentionPolicy.sweepExpiredSendMaterial(f.port(), MlsLogSink.NONE);
        assertEquals(java.util.Arrays.asList("m1", "m2"), sealed.released);
    }


    private static FakeShellPort releasePort(final Object siblings) {
        final FakeShellPort f = new FakeShellPort();
        f.returns("resendLedger", f.stub(MlsResendLedgerAccess.class, "rootOf", "root", "siblings",
                siblings, "retireChain", 2));
        f.returns("sealedCache", f.stub(MlsSealedCacheAccess.class, "release", null));
        f.returns("pendingBodies", f.stub(MlsPendingBodyAccess.class, "release", null));
        return f;
    }

    @Test
    public void aTerminalMessageReleasesItsChainsMaterialAndTheLedgerFollowsThePolicy() {
        for (final boolean delivered : new boolean[] {true, false}) {
            final FakeShellPort f = releasePort(java.util.Collections.emptyList());
            MlsSendRetentionPolicy.releaseSealed(f.port(), MlsLogSink.NONE, "m1", delivered);
            assertTrue(f.calls.contains("MlsSealedCacheAccess.release"));
            assertTrue(f.calls.contains("MlsPendingBodyAccess.release"));
            assertEquals(MlsSendRetentionPolicy.retireChainOnTerminal(delivered),
                    f.calls.contains("MlsResendLedgerAccess.retireChain"));
        }
    }

    @Test
    public void anUnreadableChainStillReleasesTheMessageItself() {
        final FakeShellPort f = releasePort((Function<Object[], Object>) a -> {
            throw new IllegalStateException("db");
        });
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsSendRetentionPolicy.releaseSealed(f.port(), log, "m1", true);
        assertTrue(log.said("W", "could not enumerate the resend chain"));
        assertTrue(f.calls.contains("MlsSealedCacheAccess.release"));
    }


    @Test
    public void aPermanentFailureReleasesOnlyWhatThePolicyAllows() {
        final FakeShellPort f = releasePort(java.util.Collections.emptyList());
        MlsSendRetentionPolicy.releaseSealedOnPermanentFailure(f.port(), MlsLogSink.NONE, "m1");
        assertEquals(MlsSendRetentionPolicy.releaseOnPermanentFailure(),
                f.calls.contains("MlsSealedCacheAccess.release"));
        assertEquals(MlsSendRetentionPolicy.releaseOnPermanentFailure()
                        && MlsSendRetentionPolicy.retireChainOnTerminal(false),
                f.calls.contains("MlsResendLedgerAccess.retireChain"));
    }


    /** A port for a receipt: the ledger files the message under {@code ledgerKey}. */
    private static FakeShellPort receiptPort(final String ledgerKey) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("findGroupIdByRcsMessageId", null)
                .returns("elapsedRealtime", 0L).returns("convIfAny", null)
                .returns("conv", new MlsTransportTypes.ConvState())
                .returns("groupDelivery", new MlsGroupDeliveryLedger());
        f.returns("resendLedger", f.stub(MlsResendLedgerAccess.class, "conversationKeyOf",
                ledgerKey, "rootOf", (Function<Object[], Object>) a -> a[0], "siblings",
                java.util.Collections.emptyList(), "retireChain", 0, "forgetRetiredChains", 0));
        f.returns("sealedCache",
                f.stub(MlsSealedCacheAccess.class, "release", null, "sweepExpired", 0));
        f.returns("pendingBodies", f.stub(MlsPendingBodyAccess.class, "release", null,
                "sweepExpired", java.util.Collections.emptyList()));
        f.returns("peerGuard", f.stub(MlsPeerGuards.class, "notePeerRecovered", null));
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        return f;
    }

    @Test
    public void aSentMessagesGroupComesFromTheLedgerAndAnUnreadableStoreIsTheSafeSide() {
        assertEquals("grp", MlsSendRetentionPolicy.groupIdForSentMessage(
                receiptPort("g:grp").port(), MlsLogSink.NONE, "m1"));
        assertNull(MlsSendRetentionPolicy.groupIdForSentMessage(receiptPort("p:+2").port(),
                MlsLogSink.NONE, "m1"));
        final FakeShellPort broken = receiptPort(null).on("findGroupIdByRcsMessageId",
                a -> { throw new IllegalStateException("db"); });
        assertEquals(MlsMessageId.UNKNOWN_CONVERSATION,
                MlsSendRetentionPolicy.groupIdForSentMessage(broken.port(), MlsLogSink.NONE, "m1"));
    }


    @Test
    public void theOneArgumentReleaseIsADelivery() {
        final FakeShellPort f = receiptPort("p:+2");
        MlsSendRetentionPolicy.releaseSealed(f.port(), MlsLogSink.NONE, "m1");
        assertTrue(f.calls.contains("MlsSealedCacheAccess.release"));
        assertEquals(MlsSendRetentionPolicy.retireChainOnTerminal(true),
                f.calls.contains("MlsResendLedgerAccess.retireChain"));
    }


    @Test
    public void aOneToOneReceiptReleasesAndAGroupMembersReceiptKeepsTheMaterial() {
        final FakeShellPort one = receiptPort("p:+2");
        MlsSendRetentionPolicy.releaseSealedOnPositiveReceipt(one.port(), MlsLogSink.NONE, "m1");
        assertTrue(one.calls.contains("MlsSealedCacheAccess.release"));
        final FakeShellPort group = receiptPort("g:grp");
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsSendRetentionPolicy.releaseSealedOnPositiveReceipt(group.port(), log, "m1");
        assertTrue(log.said("I", "is for GROUP grp"));
        assertFalse(group.calls.contains("MlsSealedCacheAccess.release"));
        assertTrue("the retention sweep runs either way",
                group.calls.contains("MlsSealedCacheAccess.sweepExpired"));
    }


    @Test
    public void aGroupsMaterialIsReleasedOnlyOnceEveryOtherMemberHasConfirmed() {
        final FakeShellPort f = receiptPort("g:grp");
        MlsRecordState.recordMembership(f.port(), MlsLogSink.NONE, KEY, grp(),
                java.util.Arrays.asList("+1", "+2", "+3"), true);
        MlsSendRetentionPolicy.releaseSealedOnGroupReceipt(f.port(), MlsLogSink.NONE, "m1", "+2");
        assertFalse(f.calls.contains("MlsSealedCacheAccess.release"));
        MlsSendRetentionPolicy.releaseSealedOnGroupReceipt(f.port(), MlsLogSink.NONE, "m1", "+3");
        assertTrue(f.calls.contains("MlsSealedCacheAccess.release"));
    }

    @Test
    public void anUnreadableRosterKeepsTheMaterial() {
        final FakeShellPort f = receiptPort("g:grp");
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsSendRetentionPolicy.releaseSealedOnGroupReceipt(f.port(), log, "m1", "+2");
        assertTrue(log.said("I", "roster is unreadable"));
        assertFalse(f.calls.contains("MlsSealedCacheAccess.release"));
    }
}

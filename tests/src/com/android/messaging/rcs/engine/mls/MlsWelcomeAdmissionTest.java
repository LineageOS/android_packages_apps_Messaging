/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsWelcomeAdmission.ServerState;
import com.android.messaging.rcs.engine.mls.MlsWelcomeAdmission.Verdict;

import org.junit.Test;

/**
 * {@link MlsWelcomeAdmission#decide}: which re-Welcomes replace live state, without weakening the
 * downgrade guard. See docs/mls/group-lifecycle.md.
 */
public final class MlsWelcomeAdmissionTest {

    @Test
    public void anEraAdvanceIsAcceptedWithoutAskingTheServer() {
        assertEquals(Verdict.ACCEPT_ERA_ADVANCE,
                MlsWelcomeAdmission.decide(true, 1, 2, null));
        assertFalse(MlsWelcomeAdmission.needsServerConsult(true, 1, 2));
        assertFalse(Verdict.ACCEPT_ERA_ADVANCE.consultedServer());
        assertTrue(Verdict.ACCEPT_ERA_ADVANCE.keepsJoin());
    }

    @Test
    public void aSameEraRefreshTheServerConfirmsIsAccepted() {
        final Verdict v = MlsWelcomeAdmission.decide(true, 2, 2, ServerState.MATCHES);
        assertEquals(Verdict.ACCEPT_REFRESH, v);
        assertTrue(v.keepsJoin());
        assertEquals(MlsWelcomeAction.REFRESH_MEMBERSHIP_EXISTING_GROUP, v.action());
        assertTrue("the accepted arm must be in the join accept-set",
                v.action().acceptedAsJoin());
    }

    @Test
    public void aSameEraWelcomeTheServerDoesNotHoldIsRefusedAsAReplay() {
        final Verdict v = MlsWelcomeAdmission.decide(true, 2, 2, ServerState.DIFFERS);
        assertEquals(Verdict.REJECT_REPLAY, v);
        assertFalse(v.keepsJoin());
    }

    /** An unreachable server is neither consent nor evidence of a replay. */
    @Test
    public void anUnreachableServerRollsBackAndIsNotCalledAReplay() {
        final Verdict v = MlsWelcomeAdmission.decide(true, 2, 2, ServerState.UNKNOWN);
        assertEquals(Verdict.REJECT_UNVERIFIED, v);
        assertFalse(v.keepsJoin());
        assertEquals("a null ServerState is the same unknown, not a crash",
                Verdict.REJECT_UNVERIFIED, MlsWelcomeAdmission.decide(true, 2, 2, null));
        final String line = MlsWelcomeAdmission.line(v, 2, 2);
        assertTrue("the line must say it is transient, not an accusation",
                line.contains("transient"));
        assertFalse("and must not call it a replay",
                line.contains("replay") && !line.contains("not an accusation"));
    }

    /** A backwards era is resolved by the server: if it is current, we were the ones ahead. */
    @Test
    public void aBackwardsWelcomeIsRefusedUnlessTheServerSaysItIsCurrent() {
        assertEquals(Verdict.REJECT_REPLAY,
                MlsWelcomeAdmission.decide(true, 5, 2, ServerState.DIFFERS));
        assertEquals("being AHEAD of the server is our problem, not the sender's",
                Verdict.ACCEPT_REFRESH,
                MlsWelcomeAdmission.decide(true, 5, 2, ServerState.MATCHES));
    }

    /** A Welcome that admits no group is an add seen from inside; its commit is ours to apply. */
    @Test
    public void aWelcomeAddressedToSomebodyElseIsNotARefusal() {
        final Verdict v = MlsWelcomeAdmission.decide(false, 2, -1, null);
        assertEquals(Verdict.NOT_ADDRESSED_TO_US, v);
        assertFalse(v.keepsJoin());
        assertEquals(MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP, v.action());
        assertTrue(MlsWelcomeAdmission.line(v, 2, -1).contains("addMembers"));
    }

    /** Both roll back, but "joined, era unreadable" points at parsing, not at addressing. */
    @Test
    public void joinedButUnreadableEraIsItsOwnVerdictNotAnAddressingProblem() {
        final Verdict v = MlsWelcomeAdmission.decide(true, 2, -1, ServerState.MATCHES);
        assertEquals(Verdict.REJECT_ERA_UNREADABLE, v);
        assertFalse("we do not keep a join whose era we cannot establish", v.keepsJoin());
        assertNotEquals("it must not read as an addressing failure",
                Verdict.NOT_ADDRESSED_TO_US, v);
    }

    /** On the inbound control path, only the arm ordering cannot answer pays for a round trip. */
    @Test
    public void onlyTheAmbiguousArmPaysForARoundTrip() {
        assertFalse("an era advance needs no consult",
                MlsWelcomeAdmission.needsServerConsult(true, 1, 9));
        assertTrue("a same-era Welcome does", MlsWelcomeAdmission.needsServerConsult(true, 2, 2));
        assertTrue("so does a backwards one", MlsWelcomeAdmission.needsServerConsult(true, 5, 2));
        assertFalse("a Welcome that admitted nobody does not",
                MlsWelcomeAdmission.needsServerConsult(false, 2, -1));
        assertFalse(MlsWelcomeAdmission.needsServerConsult(true, 2, -1));
    }

    /** Stronger than forward-only: a non-advancing Welcome is kept only on server confirmation. */
    @Test
    public void nothingTheOldRuleRefusedBecomesAcceptedWithoutTheServerSayingSo() {
        for (int oldEra = 0; oldEra <= 4; oldEra++) {
            for (int newEra = 0; newEra <= 4; newEra++) {
                for (final ServerState s : ServerState.values()) {
                    final Verdict v = MlsWelcomeAdmission.decide(true, oldEra, newEra, s);
                    if (newEra <= oldEra && v.keepsJoin()) {
                        assertEquals("the ONLY way a non-advancing Welcome may be kept is the "
                                + "server confirming it is current", ServerState.MATCHES, s);
                    }
                }
            }
        }
    }

    @Test
    public void everyVerdictExplainsItself() {
        for (final Verdict v : Verdict.values()) {
            final String line = MlsWelcomeAdmission.line(v, 1, 2);
            assertTrue(v + " has no usable line", line != null && line.length() > 20);
        }
    }
}

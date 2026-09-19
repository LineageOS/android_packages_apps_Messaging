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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsWelcomeAdmission.ServerState;
import com.android.messaging.rcs.engine.mls.MlsWelcomeAdmission.Verdict;

import org.junit.Test;

/** {@link MlsWelcomeAdmission} — the admission gap and the guard it must not weaken. */
public final class MlsWelcomeAdmissionTest {

    /** An era advance is self-evidently forward and costs no round trip. */
    @Test
    public void anEraAdvanceIsAcceptedWithoutAskingTheServer() {
        assertEquals(Verdict.ACCEPT_ERA_ADVANCE,
                MlsWelcomeAdmission.decide(true, 1, 2, null));
        assertFalse(MlsWelcomeAdmission.needsServerConsult(true, 1, 2));
        assertFalse(Verdict.ACCEPT_ERA_ADVANCE.consultedServer());
        assertTrue(Verdict.ACCEPT_ERA_ADVANCE.keepsJoin());
    }

    /**
     * THE WHOLE POINT: a same-era re-Welcome that the server confirms is current is a LEGAL
     * refresh, and the forward-only rule refused it.
     */
    @Test
    public void aSameEraRefreshTheServerConfirmsIsAccepted() {
        final Verdict v = MlsWelcomeAdmission.decide(true, 2, 2, ServerState.MATCHES);
        assertEquals(Verdict.ACCEPT_REFRESH, v);
        assertTrue(v.keepsJoin());
        assertEquals(MlsWelcomeAction.REFRESH_MEMBERSHIP_EXISTING_GROUP, v.action());
        assertTrue("the accepted arm must be in Google Messages' accept-set",
                v.action().acceptedAsJoin());
    }

    /** The guard still holds: same era, server holds something else → a replay, rolled back. */
    @Test
    public void aSameEraWelcomeTheServerDoesNotHoldIsRefusedAsAReplay() {
        final Verdict v = MlsWelcomeAdmission.decide(true, 2, 2, ServerState.DIFFERS);
        assertEquals(Verdict.REJECT_REPLAY, v);
        assertFalse(v.keepsJoin());
    }

    /**
     * UNKNOWN IS NOT CONSENT. A server we could not reach must not be read as permission to replace
     * live group state — but it must also not be reported as a replay, because the two are evidence
     * about completely different things (the network vs the sender).
     */
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

    /**
     * A BACKWARDS era is the downgrade the guard exists for — and it is still resolved by the
     * authority, not by ordering. If the server says the earlier era IS current, we were the ones
     * who were ahead, and accepting is correct.
     */
    @Test
    public void aBackwardsWelcomeIsRefusedUnlessTheServerSaysItIsCurrent() {
        assertEquals(Verdict.REJECT_REPLAY,
                MlsWelcomeAdmission.decide(true, 5, 2, ServerState.DIFFERS));
        assertEquals("being AHEAD of the server is our problem, not the sender's",
                Verdict.ACCEPT_REFRESH,
                MlsWelcomeAdmission.decide(true, 5, 2, ServerState.MATCHES));
    }

    /**
     * A Welcome that admits no group is an ADD-MEMBERS commit seen from inside the group. It must
     * never read as a refusal — the commit riding with it is ours to apply.
     */
    @Test
    public void aWelcomeAddressedToSomebodyElseIsNotARefusal() {
        final Verdict v = MlsWelcomeAdmission.decide(false, 2, -1, null);
        assertEquals(Verdict.NOT_ADDRESSED_TO_US, v);
        assertFalse(v.keepsJoin());
        assertEquals(MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP, v.action());
        assertTrue(MlsWelcomeAdmission.line(v, 2, -1).contains("addMembers"));
    }

    /**
     * A join that SUCCEEDED but produced no readable era is NOT the same thing, and this test used
     * to assert that it was.
     *
     * <p>Both roll back, which is what made them easy to conflate — but they are different facts
     * with different follow-ups. "The Welcome was not addressed to us" sends the next reader to look
     * at addressing; "we joined and cannot tell where it put us" sends them to look at parsing. The
     * observed case was a re-join refused with {@code offered=-1}, reported as an addressing
     * problem it never had.
     */
    @Test
    public void joinedButUnreadableEraIsItsOwnVerdictNotAnAddressingProblem() {
        final Verdict v = MlsWelcomeAdmission.decide(true, 2, -1, ServerState.MATCHES);
        assertEquals(Verdict.REJECT_ERA_UNREADABLE, v);
        assertFalse("we do not keep a join whose era we cannot establish", v.keepsJoin());
        assertNotEquals("it must not read as an addressing failure",
                Verdict.NOT_ADDRESSED_TO_US, v);
    }

    /**
     * THE COST MODEL IS PART OF THE CONTRACT. This runs on the inbound control path, so an
     * unconditional RPC per Welcome would serialise behind every group's traffic. The consult
     * happens only on the ambiguous arm.
     */
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

    /**
     * The new rule must be STRICTLY STRONGER than the forward-only one it replaces, which is the bar
     * the bar set for touching a downgrade guard. Everything the old rule refused is still refused
     * — the only additions are on the accept side, and each is server-confirmed.
     */
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

    /** Every verdict carries a line, and no line is a placeholder. */
    @Test
    public void everyVerdictExplainsItself() {
        for (final Verdict v : Verdict.values()) {
            final String line = MlsWelcomeAdmission.line(v, 1, 2);
            assertTrue(v + " has no usable line", line != null && line.length() > 20);
        }
    }
}

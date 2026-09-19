/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MlsTransportTypesTest {


    @Test
    public void aLookIsEitherAnAnswerOrALedgerRefusal_neverBoth() {
        final MlsTransportTypes.Look<String> asked = MlsTransportTypes.Look.asked("v");
        assertFalse(asked.refused());
        assertEquals("v", asked.orNull());
        assertNull(asked.why());
        final MlsTransportTypes.Look<String> no = MlsTransportTypes.Look.refusedByLedger("budget");
        assertTrue(no.refused());
        assertNull("a refused look carries no value to misread as the server's", no.orNull());
        assertEquals("budget", no.why());
        assertNull(MlsTransportTypes.Look.asked(null).orNull());
        assertFalse("asked-and-got-nothing is not a refusal",
                MlsTransportTypes.Look.asked(null).refused());
    }


    @Test
    public void serverComparisonSaysWhetherTheIdentityWasAskedAndNamesTheChainVerdictFirst() {
        final MlsTransportTypes.Health h = MlsTransportTypes.Health.values()[0];
        final MlsTransportTypes.ServerComparison notAsked =
                MlsTransportTypes.ServerComparison.notAsked(h);
        assertSame(h, notAsked.health);
        assertFalse(notAsked.identityAsked());
        assertFalse(notAsked.serverConfirmedOurs());
        assertEquals("NOT ASKED (not decisive at this position)", notAsked.identityLine());

        final MlsTransportTypes.ServerComparison matches = MlsTransportTypes.ServerComparison.asked(
                h, MlsWelcomeAdmission.ServerState.MATCHES);
        assertTrue(matches.identityAsked());
        assertTrue(matches.serverConfirmedOurs());
        assertFalse(matches.identityRefusedByLedger());
        assertEquals("MATCHES", matches.identityLine());

        assertTrue(MlsTransportTypes.ServerComparison.asked(h,
                MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER).identityRefusedByLedger());

        final MlsAheadChainCheck.Verdict v = MlsAheadChainCheck.Verdict.values()[0];
        assertEquals("CHAIN " + v.name(),
                MlsTransportTypes.ServerComparison.chainTested(h, v).identityLine());
    }


    @Test
    public void eraReconcileDistinguishesUnknownFromAServerPairFromADroppedOne() {
        final long[] pair = {3, 7};
        assertNull(MlsTransportTypes.EraReconcile.unknown().serverEraEpoch);
        assertFalse(MlsTransportTypes.EraReconcile.unknown().quarantined);
        assertSame(pair, MlsTransportTypes.EraReconcile.server(pair).serverEraEpoch);
        assertFalse(MlsTransportTypes.EraReconcile.server(pair).quarantined);
        assertTrue(MlsTransportTypes.EraReconcile.dropped(pair).quarantined);
        assertSame(pair, MlsTransportTypes.EraReconcile.dropped(pair).serverEraEpoch);
    }


    @Test
    public void aClaimSeparatesRefusedFromNotAttemptedAndNeverHasANullAttribution() {
        final MlsTransportTypes.Claim<String> ok = MlsTransportTypes.Claim.asked("kp");
        assertEquals("kp", ok.orNull());
        assertFalse(ok.refused());
        assertFalse(ok.notAttempted());
        assertEquals(MlsClaimLedger.Attribution.UNKNOWN, ok.attribution());

        final MlsTransportTypes.Claim<String> no =
                MlsTransportTypes.Claim.refusedByLedger("rationed");
        assertTrue(no.refused());
        assertFalse(no.notAttempted());
        assertNull(no.orNull());
        assertEquals("rationed", no.why());

        final MlsTransportTypes.Claim<String> never =
                MlsTransportTypes.Claim.notAttempted(null, "unbound");
        assertTrue("no dial: the charge is a phantom and must be refunded", never.notAttempted());
        assertFalse("not attempted is not refused", never.refused());
        assertEquals(MlsClaimLedger.Attribution.UNKNOWN, never.attribution());
        for (final MlsClaimLedger.Attribution a : MlsClaimLedger.Attribution.values()) {
            assertEquals(a, MlsTransportTypes.Claim.asked("x", a).attribution());
        }
    }


    @Test
    public void aRosterClaimCarriesPackagesOrAFailureCode() {
        final java.util.List<byte[]> kps = java.util.Collections.singletonList(new byte[] {1});
        assertSame(kps, MlsTransportTypes.RosterClaim.of(kps).kps);
        assertEquals(0, MlsTransportTypes.RosterClaim.of(kps).failure);
        assertNull(MlsTransportTypes.RosterClaim.refused(5).kps);
        assertEquals(5, MlsTransportTypes.RosterClaim.refused(5).failure);
    }


    @Test
    public void anImdnCheckIsOkOnlyWhenTheSignatureAndTheContentBothHold() {
        assertTrue(new MlsTransportTypes.ImdnCheck(true, true, 2).ok());
        assertFalse(new MlsTransportTypes.ImdnCheck(true, false, 2).ok());
        assertFalse(new MlsTransportTypes.ImdnCheck(false, true, 2).ok());
        assertEquals("ImdnCheck{sig=true content=false leaf=4}",
                new MlsTransportTypes.ImdnCheck(true, false, 4).toString());
    }


    @Test
    public void aServerPackIsFetchedBytesOrANamedAbsence() {
        final byte[] b = {9};
        assertSame(b, MlsTransportTypes.ServerPack.of(b).bytes());
        assertEquals(MlsServerPackOutcome.FETCHED, MlsTransportTypes.ServerPack.of(b).outcome());
        for (final MlsServerPackOutcome o : MlsServerPackOutcome.values()) {
            assertNull(MlsTransportTypes.ServerPack.none(o).bytes());
            assertEquals(o, MlsTransportTypes.ServerPack.none(o).outcome());
        }
    }
}

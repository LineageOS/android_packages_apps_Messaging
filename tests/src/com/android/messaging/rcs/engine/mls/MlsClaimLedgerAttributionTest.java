/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsClaimLedger.Attribution;
import com.android.messaging.rcs.engine.mls.MlsClaimLedger.Caller;
import com.android.messaging.rcs.log.LogMask;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What an empty KeyPackage claim may say about the peer. The provider reports the claim's outcome
 * and the app reduces it to one {@link Attribution}; these tests pin the wording each attribution
 * produces, since the wording is what an operator acts on. See docs/mls/budgets.md.
 */
public final class MlsClaimLedgerAttributionTest {

    private static final String PEER = "+15715550107";
    private static final Caller ANY = Caller.DEBUG_KP_COUNT;

    /** No arm may say this: an empty claim is never a fact about the peer's pool. */
    private static final String THE_FORBIDDEN_ASSERTION = "IS a fact about their pool";

    private static String line(final Attribution a) {
        return MlsClaimLedger.describeEmptyClaim(ANY, PEER, 0, a, null);
    }

    /**
     * Every {@link Attribution} produces a distinct sentence; the loop is over {@code values()} so
     * a new constant cannot silently fall through the {@code default} into an existing arm.
     */
    @Test
    public void everyAttributionHasItsOwnSentence() {
        final Set<String> seen = new HashSet<>();
        for (final Attribution a : Attribution.values()) {
            final String s = line(a);
            assertTrue(a + " produced an empty line", s != null && !s.isEmpty());
            assertTrue("Two Attribution values produce the SAME line, so the distinction the "
                    + "provider paid an AIDL contract to carry is destroyed in the log: " + a,
                    seen.add(s));
        }
        assertEquals("Attribution has grown or shrunk; every test in this class enumerates it by "
                + "hand and must be revisited rather than merely recompiled.",
                3, Attribution.values().length);
    }

    /** "Nothing to compare" never shares a representation with "they have none". */
    @Test
    public void notKnowingIsNotTheSameAsTheirPoolBeingEmpty() {
        assertNotEquals("An absent outcome and an answered-empty claim are different facts and "
                        + "must not print the same sentence.",
                line(Attribution.UNKNOWN), line(Attribution.PEER_HAS_NONE));
        assertNotEquals(line(Attribution.UNKNOWN), line(Attribution.NOT_ABOUT_THE_PEER));
        assertNotEquals(line(Attribution.NOT_ABOUT_THE_PEER), line(Attribution.PEER_HAS_NONE));
    }

    /** The refusal arm disclaims the peer in words rather than merely omitting blame. */
    @Test
    public void aRefusalSaysOutrightThatItIsNotAboutThePeer() {
        final String s = line(Attribution.NOT_ABOUT_THE_PEER);
        assertTrue("The refusal arm must say so explicitly: " + s,
                s.contains("NOT A FACT ABOUT THE PEER"));
        assertTrue("It must send the reader at OUR state, not at the peer's: " + s,
                s.contains("OUR OWN"));
        assertFalse(s.contains(THE_FORBIDDEN_ASSERTION));
    }

    /**
     * The arm for a provider that reports no outcome names both possibilities and picks neither.
     */
    @Test
    public void theUnknownArmRefusesToPickASide() {
        final String s = line(Attribution.UNKNOWN);
        assertTrue("It must offer BOTH readings: " + s, s.contains("EITHER"));
        assertTrue("…and warn against concluding from it: " + s,
                s.contains("DO NOT conclude the peer is at fault"));
        assertFalse(s.contains(THE_FORBIDDEN_ASSERTION));
    }

    /**
     * Even the arm that is evidence about the peer does not assert what is in their pool: a KDS
     * withholds a pool whose credential is inside the
     * {@link MlsCredentialFloor#RCC16_MIN_REMAINING_DAYS}-day floor (RCC.16 A.4.2.2) while the peer
     * keeps advertising, so an empty answer has a third cause that is not theirs.
     */
    @Test
    public void evenPeerHasNoneDoesNotAssertWhatIsInTheirPool() {
        final String s = line(Attribution.PEER_HAS_NONE);
        assertFalse("PEER_HAS_NONE must not claim to know the CONTENTS of the peer's pool — "
                        + "A.4.2.2 withholding produces this outcome with the pool INTACT "
                        + ": " + s,
                s.contains(THE_FORBIDDEN_ASSERTION));
        assertTrue("It must still report what the outcome DOES support — that the KDS answered: "
                        + s,
                s.contains("The KDS ANSWERED"));
        assertTrue("…and it must name the third cause, the one that is not the peer's: " + s,
                s.contains("WITHHOLDING"));
        assertTrue("…and cite the floor that causes it, so the reader can check it: " + s,
                s.contains("A.4.2.2"));
        assertTrue("…with the actual floor, not a hand-typed number: " + s,
                s.contains(MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS + "-day floor"));
        assertTrue("…and say what to look at instead of at the peer: " + s,
                s.contains("READ THE CERTIFICATE WINDOW"));
    }

    /** The arm names three causes and says only two are the peer's. */
    @Test
    public void thePeerHasNoneArmEnumeratesAllThreeCauses() {
        final String s = line(Attribution.PEER_HAS_NONE);
        assertTrue(s, s.contains("(1)") && s.contains("(2)") && s.contains("(3)"));
        assertTrue("The reader must be told which causes are the peer's: " + s,
                s.contains("only two are theirs"));
    }

    /**
     * The third cause cites A.4.2.2, the clause that forbids returning, and §5.3, which applies
     * A.4.2 to a client's claim; A.4.2.1 §1(a) only requires verification.
     */
    @Test
    public void thePeerHasNoneArmCitesTheWithholdingClauseAndWhatPutsItInScope() {
        final String s = line(Attribution.PEER_HAS_NONE);
        assertFalse("A.4.2.1 §1(a) is a VERIFY duty, not a withholding one, and A.4.2's scope list "
                        + "does not include a client's claim — citing it here sends the reader to a "
                        + "clause that does not say this: " + s,
                s.contains("A.4.2.1"));
        assertTrue("The arm must name §5.3, which is what makes A.4.2 apply to a CLIENT's claim at "
                        + "all. Without it a reader checking A.4.2 finds the client path absent and "
                        + "retracts a correct conclusion: " + s,
                s.contains("§5.3"));
    }

    /**
     * No arm says this member is the sole blocker: an outcome about one peer supports nothing about
     * the others.
     */
    @Test
    public void noArmClaimsThisMemberIsTheOnlyBlocker() {
        for (final Attribution a : Attribution.values()) {
            final String s = line(a);
            assertFalse(a + " asserts sole blame, which one peer's outcome cannot support: " + s,
                    s.toLowerCase(java.util.Locale.US).contains("alone blocks"));
            assertTrue(a + " must still say the upgrade cannot proceed with this member in it, "
                            + "which IS supported — nothing was claimed for them: " + s,
                    s.contains("the upgrade is blocked while they are in it"));
        }
    }

    /**
     * {@code detail} is appended for a human and marked unparseable; it is the only place a
     * backend's own words appear, and the app branches on the outcome instead (see
     * {@code RcsMlsControlResult}).
     */
    @Test
    public void theProviderDetailIsCarriedAndMarkedDoNotParse() {
        final String detail = "failed status=INTERNAL (Internal error encountered.)";
        final String s = MlsClaimLedger.describeEmptyClaim(
                ANY, PEER, 0, Attribution.NOT_ABOUT_THE_PEER, detail);
        assertTrue(s, s.contains("[provider detail, do not parse: " + detail + "]"));
        assertTrue("The detail must come AFTER the finding, not inside it: " + s,
                s.indexOf("NOT A FACT ABOUT THE PEER") < s.indexOf("[provider detail"));
    }

    /** An absent detail adds nothing: no empty bracket, no change to the finding. */
    @Test
    public void anAbsentDetailAddsNothing() {
        final String bare = line(Attribution.PEER_HAS_NONE);
        assertEquals(bare, MlsClaimLedger.describeEmptyClaim(ANY, PEER, 0,
                Attribution.PEER_HAS_NONE, ""));
        assertFalse(bare, bare.contains("provider detail"));
    }

    /**
     * The detail cannot move the attribution: a gRPC status name in it changes no word of the
     * finding.
     */
    @Test
    public void aDetailNamingAGrpcStatusChangesNothing() {
        final String withStatus = MlsClaimLedger.describeEmptyClaim(
                ANY, PEER, 0, Attribution.PEER_HAS_NONE, "grpcStatus=16 (UNAUTHENTICATED)");
        final String plain = line(Attribution.PEER_HAS_NONE);
        assertEquals("The finding must be identical either side of the detail.",
                plain, withStatus.substring(0, withStatus.indexOf(" [provider detail")));
    }

    /**
     * A null attribution gives the vague wording, never the blaming one (an out-parameter left at
     * its initial value produces null).
     */
    @Test
    public void aMissingAttributionFallsBackToTheVagueWordingNotTheBlamingOne() {
        assertEquals(line(Attribution.UNKNOWN),
                MlsClaimLedger.describeEmptyClaim(ANY, PEER, 0, null, null));
    }

    /**
     * The three-argument overload, used by callers that have no outcome, gives the vague wording
     * verbatim.
     */
    @Test
    public void theOverloadWithoutAnOutcomeIsTheUnknownWording() {
        assertEquals(line(Attribution.UNKNOWN), MlsClaimLedger.describeEmptyClaim(ANY, PEER, 0));
    }

    /**
     * The accounting half survives: who asked, about whom, and how many claims that peer has
     * absorbed in the window.
     */
    @Test
    public void everyLineStillCarriesTheCallerThePeerAndTheCount() {
        for (final Attribution a : Attribution.values()) {
            final String s = MlsClaimLedger.describeEmptyClaim(ANY, PEER, 4, a, null);
            assertTrue(a + ": " + s, s.contains(ANY.name()));
            assertTrue(a + ": " + s, s.contains(LogMask.number(PEER)));
            assertTrue(a + ": " + s, s.contains("4 claim(s) against this peer"));
            assertTrue(a + ": " + s,
                    s.contains("last " + (MlsClaimLedger.WINDOW_MS / 60000L) + " min"));
            assertTrue("The MADE/refused distinction must survive: " + s,
                    s.contains("was MADE and came back EMPTY"));
        }
    }

    /**
     * A missing caller or peer degrades to a placeholder rather than "null", which would read as a
     * peer identifier.
     */
    @Test
    public void anAbsentCallerOrPeerNeverPrintsNull() {
        final String s = MlsClaimLedger.describeEmptyClaim(
                null, null, 0, Attribution.PEER_HAS_NONE, null);
        assertFalse(s, s.contains("null"));
        assertTrue(s, s.contains("<no caller declared>") && s.contains("<no peer>"));
    }

    // The era advance's own consequence line, attributed the same way as the ledger's claim line.

    private static String roster(final Attribution a) {
        return MlsClaimLedger.rosterClaimBlockedLine(PEER, a);
    }

    /**
     * Every {@link Attribution} produces a distinct advance line, over {@code values()} so a new
     * constant cannot silently reuse an arm.
     */
    @Test
    public void eachAttributionGivesTheAdvanceItsOwnSentence() {
        final Set<String> seen = new HashSet<>();
        for (final Attribution a : Attribution.values()) {
            assertTrue("two attributions share an era-advance sentence: " + roster(a),
                    seen.add(roster(a)));
        }
        assertEquals(Attribution.values().length, seen.size());
    }

    /**
     * When the provider says the empty answer is not about the peer, the line says so and points at
     * our own side, never at the peer's device.
     */
    @Test
    public void anUnauthenticatedClaimDoesNotSendAnyoneToThePeersDevice() {
        final String s = roster(Attribution.NOT_ABOUT_THE_PEER);
        assertTrue(s, s.contains("NOT A FACT ABOUT " + LogMask.number(PEER)));
        assertTrue(s, s.contains("DO NOT go and look at their device"));
        // Names our own side without committing to which part: a claim that never reached a server
        // reduces to this arm too.
        assertTrue(s, s.contains("look at OUR OWN side first"));
        assertTrue(s, s.contains("whether we reached a KDS at all"));
    }

    /**
     * PEER_HAS_NONE may place the block on the peer's side but defers to the ledger's causes rather
     * than restating them, so the two wordings cannot drift.
     */
    @Test
    public void theOnlyArmThatMayAttributeStillDefersToTheLedgerLine() {
        final String s = roster(Attribution.PEER_HAS_NONE);
        assertTrue(s, s.contains("The KDS ANSWERED about them"));
        assertTrue(s, s.contains("see the ledger line directly above"));
    }

    /** UNKNOWN invents no verdict in either direction. */
    @Test
    public void anUnknownOutcomeBlamesNobody() {
        final String s = roster(Attribution.UNKNOWN);
        assertTrue(s, s.contains("WHOSE BLOCK THIS IS, IS UNKNOWN"));
        assertFalse(s, s.contains("DO NOT go and look at their device"));
        assertFalse(s, s.contains("The KDS ANSWERED about them"));
        assertEquals("a null attribution must degrade to UNKNOWN, not throw or blame",
                s, MlsClaimLedger.rosterClaimBlockedLine(PEER, null));
    }

    /** The line carries the consequence the ledger's line lacks: no partial roster is built. */
    @Test
    public void everyArmStillSaysWhatTheAdvanceDid() {
        for (final Attribution a : Attribution.values()) {
            final String s = roster(a);
            assertTrue(s, s.contains("era advance ABORTED"));
            assertTrue(s, s.contains("builds NO roster rather than a partial one"));
            assertFalse("the ledger's assertion may not reappear here: " + s,
                    s.contains(THE_FORBIDDEN_ASSERTION));
            assertNotEquals("this must not become a copy of the ledger's line", line(a), s);
        }
    }

    /** An absent peer degrades to the placeholder rather than printing "null". */
    @Test
    public void theAdvanceLineNeverPrintsNullAsAPeer() {
        for (final Attribution a : Attribution.values()) {
            final String s = MlsClaimLedger.rosterClaimBlockedLine(null, a);
            assertFalse(s, s.contains("null"));
            assertTrue(s, s.contains("<no peer>"));
        }
    }

    // Transport sites that refuse because no KeyPackage came back have different consequences but
    // one attribution: the caller owns what it did, MlsClaimLedger owns what it means about the
    // peer.

    /**
     * Callers with different consequences produce the same attribution clause byte for byte, so
     * there is one copy of that judgement.
     */
    @Test
    public void everyCallerGetsTheIdenticalAttributionClause() {
        for (final Attribution a : Attribution.values()) {
            final String clause = canonicalClause(a);
            assertFalse("the clause must not be empty", clause.trim().isEmpty());
            for (final Method wrapper : blockedLineWrappers()) {
                final String line = invoke(wrapper, a);
                assertTrue(wrapper.getName() + " does not end in the shared attribution clause, so "
                        + "it is carrying a SECOND copy of that judgement. Got:\n  " + line
                        + "\nexpected to end with:\n  " + clause, line.endsWith(" " + clause));
            }
        }
    }

    /**
     * The wrappers are found by signature, not by name, so a new wrapper is covered automatically
     * and the comparison is not the shared method against itself. {@code blockedByEmptyClaim} takes
     * three arguments and is therefore not matched.
     */
    private static List<Method> blockedLineWrappers() {
        final List<Method> out = new ArrayList<>();
        for (final Method m : MlsClaimLedger.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getReturnType() != String.class) continue;
            final Class<?>[] params = m.getParameterTypes();
            if (params.length == 2 && params[0] == String.class
                    && params[1] == Attribution.class) {
                out.add(m);
            }
        }
        assertTrue("No (String, Attribution) -> String wrapper was found in MlsClaimLedger. Either "
                + "they were all removed, or their shape changed and this scan now certifies "
                + "nothing — an absent needle must FAIL, not report green.",
                out.size() >= 3);
        return out;
    }

    /** The attribution clause every wrapper must end with, from the one method that owns it. */
    private static String canonicalClause(final Attribution a) {
        final String marker = "CONSEQUENCE-MARKER.";
        final String whole = MlsClaimLedger.blockedByEmptyClaim(PEER, a, marker);
        assertTrue("blockedByEmptyClaim must put the caller's consequence first: " + whole,
                whole.startsWith(marker + " "));
        return whole.substring(marker.length() + 1);
    }

    private static String invoke(final Method m, final Attribution a) {
        try {
            return (String) m.invoke(null, PEER, a);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("could not call " + m.getName(), e);
        }
    }

    /**
     * Every wrapper, on every attribution, is held to the rules for
     * {@link MlsClaimLedger#describeEmptyClaim}: no pool fact, no sole blame, and distinct clauses
     * per attribution.
     */
    @Test
    public void noWrapperBlamesThePeerOnAnyAttribution() {
        for (final Method wrapper : blockedLineWrappers()) {
            final Set<String> clauses = new HashSet<>();
            for (final Attribution a : Attribution.values()) {
                final String line = invoke(wrapper, a);
                assertFalse(wrapper.getName() + " reinstates the ledger's assertion: " + line,
                        line.contains(THE_FORBIDDEN_ASSERTION));
                assertFalse(wrapper.getName() + " asserts sole blame, which one peer's outcome "
                                + "cannot support: " + line,
                        line.toLowerCase(java.util.Locale.US).contains("alone blocks"));
                // The wrapper's own output, not canonicalClause(a); a wrapper's consequence is
                // constant across attributions, so distinct lines means distinct clauses.
                clauses.add(line);
            }
            assertEquals(wrapper.getName() + " must distinguish every Attribution",
                    Attribution.values().length, clauses.size());
        }
    }

    /** Each wrapper states a different consequence, one that describes its own call site. */
    @Test
    public void everyWrapperStatesItsOwnConsequence() {
        final Set<String> consequences = new HashSet<>();
        final String clause = canonicalClause(Attribution.UNKNOWN);
        for (final Method wrapper : blockedLineWrappers()) {
            final String line = invoke(wrapper, Attribution.UNKNOWN);
            assertTrue(wrapper.getName() + " must end in the shared clause before its consequence "
                    + "can be read off the front: " + line, line.endsWith(clause));
            final String consequence = line.substring(0, line.length() - clause.length()).trim();
            assertFalse(wrapper.getName() + " has no consequence of its own",
                    consequence.isEmpty());
            assertTrue(wrapper.getName() + " repeats another wrapper's consequence: " + consequence,
                    consequences.add(consequence));
            assertTrue(wrapper.getName() + " must name the peer it is about: " + consequence,
                    consequence.contains(LogMask.number(PEER)));
        }
    }

    /** The caller's consequence reaches the log verbatim; the method composes none of its own. */
    @Test
    public void theCallersConsequenceSurvivesVerbatim() {
        final String mine = "NOT adding +15550000000 — no KeyPackage came back, and an Add is "
                + "built FROM it.";
        final String s = MlsClaimLedger.blockedByEmptyClaim(
                PEER, Attribution.NOT_ABOUT_THE_PEER, mine);
        assertTrue(s, s.startsWith(mine + " "));
        assertFalse("no arm may reinstate the ledger's assertion: " + s,
                s.contains(THE_FORBIDDEN_ASSERTION));
    }

    /**
     * A caller that supplies no consequence still gets a line that names the peer, not a leading
     * space and a bare clause.
     */
    @Test
    public void anAbsentConsequenceStillProducesAWholeSentence() {
        for (final String consequence : new String[] { null, "" }) {
            final String s = MlsClaimLedger.blockedByEmptyClaim(
                    null, Attribution.UNKNOWN, consequence);
            assertFalse(s, s.startsWith(" "));
            assertFalse(s, s.contains("null"));
            assertTrue(s, s.contains("<no peer>"));
        }
    }
}

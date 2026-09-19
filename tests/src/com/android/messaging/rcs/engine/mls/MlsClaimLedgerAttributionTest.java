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

import com.android.messaging.rcs.engine.mls.MlsClaimLedger.Attribution;
import com.android.messaging.rcs.engine.mls.MlsClaimLedger.Caller;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>WHAT AN EMPTY CLAIM IS ALLOWED TO SAY ABOUT A PEER</b>, and the residue of that rule in the
 * one arm that may attribute.
 *
 * <h2>The line this pins, and why prose was not enough</h2>
 *
 * <p>{@code claimPeerKeyPackages} returned null for every unhappy path, so the app could not tell
 * "this peer has published nothing" from "the KDS would not talk to US". The ledger then reported an
 * UNAUTHENTICATED claim as a fact about the peer's pool, <b>in a line that named an innocent
 * device</b> and told the reader that device alone blocked the upgrade. Device-measured on
 * {@code 00AU} 2026-09-11; the named peer's pool was full and another device claimed from it
 * successfully 53 seconds later.
 *
 * <p>Contract v60 carries six outcomes across the AIDL and the app reduces them to the one question
 * this line answers — {@link Attribution}. That reduction and its three sentences had <b>no unit
 * test at all</b>: the only coverage was a source-scan guard over the call sites, which cannot see
 * what the line SAYS. The distinctions were held by prose, and prose is what was wrong the first
 * time.
 *
 * <h2>The rule these tests encode</h2>
 *
 * <p><i>A check that cannot fail is not evidence</i>, so each
 * test below names the state that makes it RED. They are wording assertions on purpose: the wording
 * IS the defect, the wording is what an operator acts on, and a paraphrase that quietly reinstates
 * "this IS a fact about their pool" is exactly the regression to catch.
 */
public final class MlsClaimLedgerAttributionTest {

    private static final String PEER = "+15715550107";
    private static final Caller ANY = Caller.DEBUG_KP_COUNT;

    /** The assertion this was filed for. No arm may say this. */
    private static final String THE_FORBIDDEN_ASSERTION = "IS a fact about their pool";

    private static String line(final Attribution a) {
        return MlsClaimLedger.describeEmptyClaim(ANY, PEER, 0, a, null);
    }

    // ---- 1. the three outcomes stay three ---------------------------------------------------------

    /**
     * Every {@link Attribution} produces a DISTINCT sentence, and the loop is over
     * {@code values()} so a fourth value cannot be added that silently reuses one.
     *
     * <p>RED WHEN: a new enum constant falls through to an existing {@code case}, or two arms are
     * edited into the same wording. Reachable — the switch has a {@code default}, which is what a
     * new constant lands in today.
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

    /**
     * <b>"Nothing to compare" must never share a representation with "they have none".</b>
     *
     * <p>RED WHEN: {@code UNKNOWN} is mapped onto the {@code PEER_HAS_NONE} arm — the one-line
     * "simplification" an older provider's caller invites, since both hand back no bytes.
     */
    @Test
    public void notKnowingIsNotTheSameAsTheirPoolBeingEmpty() {
        assertNotEquals("An absent outcome and an answered-empty claim are different facts and "
                        + "must not print the same sentence.",
                line(Attribution.UNKNOWN), line(Attribution.PEER_HAS_NONE));
        assertNotEquals(line(Attribution.UNKNOWN), line(Attribution.NOT_ABOUT_THE_PEER));
        assertNotEquals(line(Attribution.NOT_ABOUT_THE_PEER), line(Attribution.PEER_HAS_NONE));
    }

    // ---- 2. what each arm may and may not assert --------------------------------------------------

    /**
     * The refusal arm must DISCLAIM the peer, in words, not merely omit blame. This is the arm that
     * is device-verified (010T, 2026-09-11, {@code outcome=3 REFUSED}) and the one the original
     * defect got wrong.
     *
     * <p>RED WHEN: the disclaimer is softened to an omission — e.g. "the claim came back empty" with
     * no statement of whose evidence it is. An operator reading an unqualified empty result went to
     * the wrong device once already.
     */
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
     * The pre-v60 arm must not resolve the ambiguity it does not have. It may name both
     * possibilities; it may not pick one.
     *
     * <p>RED WHEN: the vague wording is "tidied" into either of its neighbours — which is tempting
     * precisely because it reads as the least useful of the three.
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
     * <b>The residue.</b> Even the one outcome licensed to be evidence
     * about the peer may not assert what is IN their pool, because a third cause produces the same
     * empty answer and it is not theirs: RCC.16 A.4.2.2 (reaching a client's claim via §5.3 — see
     * {@link #thePeerHasNoneArmCitesTheWithholdingClauseAndWhatPutsItInScope}) has the KDS WITHHOLD a
     * pool whose credential is inside the {@link MlsCredentialFloor#RCC16_MIN_REMAINING_DAYS}-day
     * floor, while a sticky uploaded-flag keeps the peer advertising. A test fleet was
     * measured inside that floor for weeks — so on a fleet in trouble the
     * two-cause sentence would be wrong most of the time it fired.
     *
     * <p>RED WHEN: the arm is reverted to "this IS a fact about their pool — they have published
     * none, or one has been drained", which is what it said until this change. That is the exact
     * string this test forbids, so the revert cannot land quietly.
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

    /**
     * The three causes stay THREE, and the arm says which are the peer's. Counting them in the
     * sentence is what stops the enumeration silently shrinking back to two.
     *
     * <p>RED WHEN: a cause is dropped, or the "only two are theirs" split is removed.
     */
    @Test
    public void thePeerHasNoneArmEnumeratesAllThreeCauses() {
        final String s = line(Attribution.PEER_HAS_NONE);
        assertTrue(s, s.contains("(1)") && s.contains("(2)") && s.contains("(3)"));
        assertTrue("The reader must be told which causes are the peer's: " + s,
                s.contains("only two are theirs"));
    }

    /**
     * <b>The third cause cites the clause that actually forbids RETURNING, and the clause that puts
     * it in scope for a CLIENT's claim.</b> 2026-09-11.
     *
     * <p>This whole tree cited the withholding rule as <i>"A.4.2.1 §1(a) / A.4.2.2"</i>, sourced to
     * {@code MlsCertPolicy}'s paraphrase rather than to RCC.16. Read against the spec, half of it is
     * the wrong clause and the load-bearing half was missing:
     *
     * <ul>
     *   <li><b>A.4.2.2</b> is the whole withholding rule — <i>"KDSs must not return KeyPackages to a
     *       query …"</i>. <b>A.4.2.1 §1(a)</b> only says <i>"Verify that the client certificate has
     *       a remaining lifetime of at least 30 days"</i>. Verifying is not withholding.</li>
     *   <li>A.4.2's own scope list is <i>"KeyPackage update"</i> and <i>"Query receipt from a peer
     *       KDS"</i>. <b>A client's claim is in neither.</b> <b>§5.3</b> is what puts it in scope:
     *       <i>"The Home KDS shall validate the Client Credentials in the KeyPackages according to
     *       Annex A.4.2."</i></li>
     * </ul>
     *
     * <p>That matters here and not only in a comment: this line is what sends an operator to the
     * spec. Sent to A.4.2 alone they find the client path absent and conclude the third cause is
     * invented — retracting a conclusion that is correct, which is the failure this repo has
     * already paid for once (AGENTS.md, "RE-DERIVE BEFORE RETRACTING").
     *
     * <p>RED WHEN: the citation reverts to naming A.4.2.1 (the first assertion), or §5.3 is dropped
     * by any rewrite, verbatim revert or not (the second). The second is the one that survives a
     * paraphrase: nothing else in the arm mentions §5.3, so it cannot be satisfied by accident.
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
     * No arm may tell the reader this member is the SOLE blocker. Nothing in an outcome about one
     * peer supports a statement about the others, and the original line ended
     * "THIS MEMBER ALONE BLOCKS THE WHOLE UPGRADE" — an instruction to go and look at one device.
     *
     * <p>RED WHEN: any arm reinstates "alone". The fact that survives is the one the outcome
     * carries: nothing was claimed for this member, so the upgrade is blocked while they are in it.
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

    // ---- 3. the vocabulary firewall ---------------------------------------------------------------

    /**
     * {@code detail} is appended for a human and marked unparseable. It is the ONLY place a
     * backend's own words survive, and the app must never branch on it — that is what the outcome
     * is for (see {@code RcsMlsControlResult}).
     *
     * <p>RED WHEN: the marker is dropped, or the detail is interpolated into the sentence where it
     * would read as part of the finding.
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

    /**
     * A detail that is absent adds nothing at all — no empty bracket to be mistaken for a missing
     * diagnostic, and no change to the finding.
     *
     * <p>RED WHEN: the null/empty guard is dropped and the line grows a bare "[provider detail, do
     * not parse: null]".
     */
    @Test
    public void anAbsentDetailAddsNothing() {
        final String bare = line(Attribution.PEER_HAS_NONE);
        assertEquals(bare, MlsClaimLedger.describeEmptyClaim(ANY, PEER, 0,
                Attribution.PEER_HAS_NONE, ""));
        assertFalse(bare, bare.contains("provider detail"));
    }

    /**
     * <b>The detail cannot move the attribution.</b> A gRPC status name in the diagnostic must not
     * change one word of the finding — the translation happened provider-side and the app branches
     * on the outcome.
     *
     * <p>RED WHEN: someone "helpfully" scans the detail for UNAUTHENTICATED and re-labels the line,
     * which re-creates the coupling the firewall exists to prevent.
     */
    @Test
    public void aDetailNamingAGrpcStatusChangesNothing() {
        final String withStatus = MlsClaimLedger.describeEmptyClaim(
                ANY, PEER, 0, Attribution.PEER_HAS_NONE, "grpcStatus=16 (UNAUTHENTICATED)");
        final String plain = line(Attribution.PEER_HAS_NONE);
        assertEquals("The finding must be identical either side of the detail.",
                plain, withStatus.substring(0, withStatus.indexOf(" [provider detail")));
    }

    // ---- 4. the fail-safe default and the accounting half -----------------------------------------

    /**
     * A null attribution is the vague wording, never the blaming one. The reduction is computed by
     * a caller that may not have run — an out-parameter left at its initial value is the shape that
     * produces null here.
     *
     * <p>RED WHEN: the {@code null} guard is removed (NPE) or defaulted to {@code PEER_HAS_NONE}.
     */
    @Test
    public void aMissingAttributionFallsBackToTheVagueWordingNotTheBlamingOne() {
        assertEquals(line(Attribution.UNKNOWN),
                MlsClaimLedger.describeEmptyClaim(ANY, PEER, 0, null, null));
    }

    /**
     * The three-argument overload — every pre-v60 caller — is the vague wording, verbatim.
     *
     * <p>RED WHEN: the overload is repointed at a different default, which would make an older
     * provider's caller start asserting an outcome it never received.
     */
    @Test
    public void theOverloadWithoutAnOutcomeIsTheUnknownWording() {
        assertEquals(line(Attribution.UNKNOWN), MlsClaimLedger.describeEmptyClaim(ANY, PEER, 0));
    }

    /**
     * The accounting half of the line survives the rewrite: WHO asked, about WHOM, and how many
     * claims that peer has already absorbed in the window. That count is the whole
     * subject and it is the part a wording change is most likely to drop.
     *
     * <p>RED WHEN: the prefix is reworded without the caller, the peer, or the count.
     */
    @Test
    public void everyLineStillCarriesTheCallerThePeerAndTheCount() {
        for (final Attribution a : Attribution.values()) {
            final String s = MlsClaimLedger.describeEmptyClaim(ANY, PEER, 4, a, null);
            assertTrue(a + ": " + s, s.contains(ANY.name()));
            assertTrue(a + ": " + s, s.contains(PEER));
            assertTrue(a + ": " + s, s.contains("4 claim(s) against this peer"));
            assertTrue(a + ": " + s,
                    s.contains("last " + (MlsClaimLedger.WINDOW_MS / 60000L) + " min"));
            assertTrue("The MADE/refused distinction must survive: " + s,
                    s.contains("was MADE and came back EMPTY"));
        }
    }

    /**
     * A missing caller or peer degrades to a placeholder rather than printing "null", which would
     * read as a peer identifier.
     *
     * <p>RED WHEN: {@code safe()}/{@code name()} are bypassed by a reworded prefix.
     */
    @Test
    public void anAbsentCallerOrPeerNeverPrintsNull() {
        final String s = MlsClaimLedger.describeEmptyClaim(
                null, null, 0, Attribution.PEER_HAS_NONE, null);
        assertFalse(s, s.contains("null"));
        assertTrue(s, s.contains("<no caller declared>") && s.contains("<no peer>"));
    }

    // ---- 5. the ERA ADVANCE's own line, which did not inherit v60 -------------------
    //
    // The ledger line above is the CLAIM's. The advance then logs its own CONSEQUENCE line, and that
    // one stayed unconditional through v60: "no KeyPackage for +1...; the new era would be missing a
    // member the server expects". On 00AU, whose ClaimKeyPackages returns UNAUTHENTICATED every
    // time, it named a peer whose pool was full. That was flagged at the time and left unfixed.

    private static String roster(final Attribution a) {
        return MlsClaimLedger.rosterClaimBlockedLine(PEER, a);
    }

    /**
     * Every {@link Attribution} produces a DISTINCT advance line, over {@code values()} so a fourth
     * constant cannot silently reuse an arm.
     *
     * <p>RED WHEN: a new constant falls through the {@code default}, or two arms are edited into the
     * same wording. Reachable by construction — the switch HAS a default.
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
     * THE DEFECT ITSELF. When the provider says the empty answer is not about the peer, the line
     * must say so and must point at OUR OWN state — never send the reader to the peer's device.
     *
     * <p>RED WHEN: the arm is reworded back to a bare "no KeyPackage for &lt;peer&gt;", or the
     * NOT_ABOUT_THE_PEER arm is dropped so it falls through to the UNKNOWN wording.
     */
    @Test
    public void anUnauthenticatedClaimDoesNotSendAnyoneToThePeersDevice() {
        final String s = roster(Attribution.NOT_ABOUT_THE_PEER);
        assertTrue(s, s.contains("NOT A FACT ABOUT " + PEER));
        assertTrue(s, s.contains("DO NOT go and look at their device"));
        // NOT "OUR OWN KDS credentials" — that was too specific, and a later change made the gap reachable:
        // a claim that never reached a server (TRANSPORT_FAILED) reduces to this same arm, and
        // sending that operator to check tokens points confidently at the wrong place. The line
        // must name our own side WITHOUT committing to which part of it.
        assertTrue(s, s.contains("look at OUR OWN side first"));
        assertTrue(s, s.contains("whether we reached a KDS at all"));
    }

    /**
     * PEER_HAS_NONE may say the block is on their side — it is the only outcome that is evidence
     * about them — but it must DEFER to the ledger's causes rather than restating them, because two
     * wordings of one fact is how the two drift.
     *
     * <p>RED WHEN: the arm grows its own copy of the three causes, or starts asserting their pool is
     * empty. The forbidden assertion is checked on every arm below.
     */
    @Test
    public void theOnlyArmThatMayAttributeStillDefersToTheLedgerLine() {
        final String s = roster(Attribution.PEER_HAS_NONE);
        assertTrue(s, s.contains("The KDS ANSWERED about them"));
        assertTrue(s, s.contains("see the ledger line directly above"));
    }

    /**
     * UNKNOWN keeps the pre-v60 posture: it may not invent a verdict in either direction.
     *
     * <p>RED WHEN: someone "tidies" UNKNOWN into the peer arm because it is the common case on an
     * older provider — which is this defect pointing the other way.
     */
    @Test
    public void anUnknownOutcomeBlamesNobody() {
        final String s = roster(Attribution.UNKNOWN);
        assertTrue(s, s.contains("WHOSE BLOCK THIS IS, IS UNKNOWN"));
        assertFalse(s, s.contains("DO NOT go and look at their device"));
        assertFalse(s, s.contains("The KDS ANSWERED about them"));
        assertEquals("a null attribution must degrade to UNKNOWN, not throw or blame",
                s, MlsClaimLedger.rosterClaimBlockedLine(PEER, null));
    }

    /**
     * The line carries the CONSEQUENCE, which is the half the ledger's line does not have: no
     * partial roster is built. That is what makes it worth a second line at all.
     *
     * <p>RED WHEN: the consequence is dropped and this becomes a duplicate of
     * {@link MlsClaimLedger#describeEmptyClaim}.
     */
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

    /**
     * An absent peer degrades to the placeholder rather than printing "null", which would read as an
     * identifier — the same rule the ledger line follows.
     *
     * <p>RED WHEN: {@code safe()} is bypassed by string-concatenating the peer directly.
     */
    @Test
    public void theAdvanceLineNeverPrintsNullAsAPeer() {
        for (final Attribution a : Attribution.values()) {
            final String s = MlsClaimLedger.rosterClaimBlockedLine(null, a);
            assertFalse(s, s.contains("null"));
            assertTrue(s, s.contains("<no peer>"));
        }
    }

    // ---- 6. ONE attribution clause, many consequences ------------------------------
    //
    // Four transport sites refuse an operation because no KeyPackage came back, and their
    // consequences differ. What must not differ is who we say the block belongs to. These pin the
    // split: the caller owns what it did, MlsClaimLedger owns what that means about the peer.

    /**
     * THE POINT OF THE SPLIT. Two callers with completely different consequences produce the SAME
     * attribution clause, byte for byte — so there is one copy of that judgement, not one per site.
     *
     * <p>RED WHEN: someone "specialises" an arm for one caller, or re-inlines the switch into a
     * second method. Reachable — that is exactly what a sibling-per-caller design would do, and it
     * was the alternative considered here.
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
     * <b>The wrappers are found BY SIGNATURE, never by name.</b>
     *
     * <p>The version of {@link #everyCallerGetsTheIdenticalAttributionClause} this replaced compared
     * {@code rosterClaimBlockedLine} against two direct calls to {@link
     * MlsClaimLedger#blockedByEmptyClaim} standing in for the 1:1 and the add. Two of its three arms
     * were therefore <b>the shared method compared against itself</b>, which cannot fail: had
     * {@code oneToOneCreateBlockedLine} or {@code addMemberBlockedLine} inlined its own copy of the
     * switch — the precise drift the split exists to prevent — the test would have stayed green.
     * This is about a line asserting more than its evidence supports; that was the same thing
     * aimed at the split's own guard.
     *
     * <p>Enumerating by signature rather than by a name list also means <b>a new wrapper is picked
     * up without anyone remembering to add it</b>, and a name-keyed scan would be the same
     * defect one field over. {@code blockedByEmptyClaim} itself takes three arguments and so is not
     * matched, which is what makes the comparison meaningful rather than circular.
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
     * Every wrapper, on every attribution, is held to what {@link MlsClaimLedger#describeEmptyClaim}
     * is held to — because the split gave the attribution vocabulary ONE copy, and the one copy must
     * not be the copy nothing tests.
     *
     * <p>RED WHEN: any wrapper reinstates "IS a fact about their pool" or claims sole blame, or two
     * attributions collapse to one clause. All three are reachable: the first is the exact sentence
     * this was filed for, and the third is what a {@code default:} swallowing a new constant
     * produces.
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
                // THE WRAPPER'S OWN OUTPUT, not canonicalClause(a) — which would be
                // blockedByEmptyClaim compared against itself, the very circularity this
                // rewrite removed. A wrapper's consequence is constant across attributions,
                // so distinct LINES is exactly distinct clauses.
                clauses.add(line);
            }
            assertEquals(wrapper.getName() + " must distinguish every Attribution",
                    Attribution.values().length, clauses.size());
        }
    }

    /**
     * Each wrapper says something DIFFERENT about what the caller did. Identical clauses, distinct
     * consequences — the two halves of the split, pinned together.
     *
     * <p>RED WHEN: a wrapper is added that copies another's consequence, or one is repointed at the
     * wrong sentence. A wrapper whose consequence does not describe its own call site is a line that
     * tells an operator the wrong thing happened.
     */
    @Test
    public void everyWrapperStatesItsOwnConsequence() {
        final Set<String> consequences = new HashSet<>();
        final String clause = canonicalClause(Attribution.UNKNOWN);
        for (final Method wrapper : blockedLineWrappers()) {
            final String line = invoke(wrapper, Attribution.UNKNOWN);
            assertTrue(wrapper.getName() + " must end in the shared clause before its consequence "
                    + "can be read off the front: " + line, line.endsWith(clause));
            final String consequence = line.substring(0, line.length() - clause.length()).trim();
            assertFalse(wrapper.getName() + " has no consequence of its own", consequence.isEmpty());
            assertTrue(wrapper.getName() + " repeats another wrapper's consequence: " + consequence,
                    consequences.add(consequence));
            assertTrue(wrapper.getName() + " must name the peer it is about: " + consequence,
                    consequence.contains(PEER));
        }
    }

    /**
     * The CONSEQUENCE is the caller's and reaches the log verbatim — that is the half the shared
     * clause must not swallow.
     *
     * <p>RED WHEN: the method starts composing its own consequence, which is how four call sites
     * drift back into one generic sentence that describes none of them.
     */
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
     * A caller that supplies no consequence still gets a line that names the peer safely rather than
     * a leading space or a bare clause.
     *
     * <p>RED WHEN: the null guard is dropped, which prints " THIS IS NOT A FACT ABOUT…" with no
     * subject — a line that reads as though something else above it was the subject.
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

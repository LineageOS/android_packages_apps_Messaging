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

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>DoD-1 — zero policy constants declared in the transport.</b> Decoupling plan §7, Stage 7.
 *
 * <p>The target is that what remains in {@code MlsProviderTransport} is persistence, network
 * effects, scheduling and reporting, <i>with no decisions left in it</i>. A tunable declared there
 * is a decision declared there. Stage 6 moves the nineteen of them into {@code MlsConfig} behind the
 * existing {@code Source} port; eight further constants are wire/ABI and correctly stay.
 *
 * <h2>What this is keyed on, and why it is not a spelling</h2>
 *
 * <p>The ENUMERATION is structural: a {@code static final} scalar declaration is found by its SHAPE
 * ({@link SourceScan#scalarConstantsDeclaredIn}), over {@link SourceScan#codeOnly} source, so
 * a constant cannot hide from it by being renamed, re-ordered, re-indented or documented
 * differently. The CLASSIFICATION is by name — it has to be, since "is this tunable a policy or a
 * wire value" is a human judgement — but the two lists are not typed here: they are read out of
 * {@code tools/mls/transport-classify.py}, the checked-in DoD-5 instrument that already carries
 * them. One ground truth, so the two halves of the same work cannot disagree about which constants
 * are policy.
 *
 * <p>The failure that matters is the one a name-keyed guard normally allows: <b>a constant that is
 * on NEITHER list fails</b> ({@link #everyScalarConstantDeclaredInTheTransportIsClassified}). So a
 * new tunable cannot arrive unclassified, which is the direction a hand list goes stale in.
 *
 * <p>And the opposite evasion is checked too. A policy constant "leaves" the transport when Stage 6
 * moves it — but a constant can also leave a {@code static final} declaration and come back as an
 * instance field, a local, or a literal at the one site that used it, which would show as progress.
 * {@link #noPolicyConstantThatHasLeftIsStillReferencedHere} requires a departed constant to have
 * ZERO code references, not merely no declaration.
 */
public final class MlsTransportPolicyConstantGuardTest {

    /** The checked-in classification, read rather than re-typed. */
    private static final String TOOL = "tools/mls/transport-classify.py";

    /**
     * Policy constants still declared in the transport. <b>DoD-1's target is 0</b>; Stage 6 lowers
     * it. Measured 2026-09-09 at the working tree ({@code MlsProviderTransport.java} = 19,622
     * lines). The plan's baseline is 19 — {@code PEER_FTD_ESCALATE_AT} left separately,
     * which found it was {@code MlsResendBudget.MAX_PER_WINDOW} and that the derivation was the
     * finding.
     */
    private static final int POLICY_CONSTANTS_DECLARED = 0;

    /**
     * Code (not javadoc) reference sites of those constants. The plan's baseline is 56 total sites,
     * 40 in code and 16 in prose; measured here over comment- and string-stripped source, so this
     * is the 40 as it stands today.
     *
     * <p><b>39 → 38.</b> {@code REESTABLISH_COOLDOWN_MS} lost its second site:
     * the cooldown's stamp became durable, so the comparison and the "next eligible in Ns" refusal
     * line both moved into {@code MlsPeerGuard.claimReestablishAttempt} and the transport passes the
     * window as an argument. A door closed rather than a decision being deleted — the constant is
     * still read here, once, and DoD-3 re-classified it from {@code TRANSIENT_UNDECLARED} to
     * {@code DURABLE_RECORD} in the same change, which is what this number exists to force.
     *
     * <p><b>38 → 42 in the same change:</b> {@code debugProbeDurableCooldowns} reads both windows to
     * ask the two durable stamps whether they would allow their operation now. That is a Google Messages
     * second door onto each — the probe stamps the same record production stamps and really does
     * suppress the next real operation — and it exists because the property in question is
     * survival across a PROCESS RESTART, while the operations these bounds gate need a diverged
     * conversation to reach. Two sites each: the claim, and the probe printing the window it used.
     * DoD-3 carries the matching per-constant counts.
     */
    private static final int POLICY_CONSTANT_CODE_SITES = 0;

    // ---- the checks ----------------------------------------------------------------------------

    /**
     * Every {@code static final} scalar the transport declares is on one of the tool's two lists.
     *
     * <p>This is the check that makes the other four survive. An unclassified constant is not a
     * failure of tidiness: it is a policy decision that DoD-1 does not know about, in the class the
     * work exists to empty.
     */
    @Test
    public void everyScalarConstantDeclaredInTheTransportIsClassified() throws IOException {
        final List<String> declared = declaredConstants();
        final Set<String> policy = new HashSet<>(policyConstants());
        final Set<String> wire = new HashSet<>(wireConstants());

        assertTrue("ZERO HITS MUST FAIL: declaredConstants() for the transport is EMPTY, so the loop below never runs "
                        + "and this assertion certifies green having examined nothing. A zero-match "
                        + "scan is a broken scan, not a clean tree.",
                !declared.isEmpty());
        final List<String> unclassified = new ArrayList<>();
        for (final String c : declared) {
            if (!policy.contains(c) && !wire.contains(c)) unclassified.add(c);
        }
        Collections.sort(unclassified);
        if (!unclassified.isEmpty()) {
            fail("These scalar constants are declared in MlsProviderTransport and are on NEITHER "
                    + "the POLICY_CONSTANTS nor the WIRE_CONSTANTS list in " + TOOL + ": "
                    + unclassified + ". Add each to whichever it is — a tunable nobody classified "
                    + "is a policy decision DoD-1 cannot see. (" + declared.size() + " declared, "
                    + policy.size() + " policy names known, " + wire.size() + " wire names known.)");
        }
    }

    /**
     * The tool's two lists are non-empty and the transport declares constants at all.
     *
     * <p>A source scan that finds ZERO occurrences of its own subject must
     * FAIL. Every assertion in this class degrades to "found fewer than exist" if the locator or
     * the declaration shape changes, and a scan that can silently find none is that bug at its
     * limit.
     */
    @Test
    public void theSubjectsOfThisGuardAreAllNonEmpty() throws IOException {
        // WAS 20, LOWERED TO 8 BY STAGE 6 — and 8 is not an arbitrary floor: it is exactly the
        // wire/ABI exemption list, every member of which everyWireExemptionIsStillDeclaredHere
        // separately requires to still be declared here. So the interesting assertion has moved to
        // that test, and this one is now only the guard against the SCAN going blind.
        assertTrue("no scalar static-final constants matched in MlsProviderTransport — the "
                + "declaration shape has changed and every check in this class is now silently "
                + "passing on an empty set",
                declaredConstants().size() >= 8);
        assertTrue("POLICY_CONSTANTS is empty or unparseable in " + TOOL + " — DoD-1 has no subject",
                policyConstants().size() >= 15);
        assertTrue("WIRE_CONSTANTS is empty or unparseable in " + TOOL
                + " — the exemption list has no subject, so every wire constant would read as an "
                + "unclassified policy constant",
                wireConstants().size() >= 8);
    }

    /**
     * Every named wire/ABI exemption is still a constant the transport declares.
     *
     * <p>An exemption for a constant that is gone excuses nothing and misleads the next reader —
     * and worse, the next constant to take that name inherits an exemption nobody granted it. Same
     * rule {@code MlsEngineHostClasspathGuardTest} applies to {@code ANDROID_DEPENDENT}.
     *
     * <p>Deliberately NOT asserted: that each is still USED. {@code ERA_MODE_PRESERVE} has no code
     * reference today — the mode is illegal under design §9.2 and the constant documents a wire
     * value the server can still send. A wire/ABI constant earns its place by being part of the
     * protocol, not by being read.
     */
    @Test
    public void everyWireExemptionIsStillDeclaredHere() throws IOException {
        final Set<String> declared = new HashSet<>(declaredConstants());
        final List<String> stale = new ArrayList<>();
        for (final String w : wireConstants()) {
            if (!declared.contains(w)) stale.add(w);
        }
        if (!stale.isEmpty()) {
            fail("WIRE_CONSTANTS in " + TOOL + " exempts constants MlsProviderTransport no longer "
                    + "declares: " + stale + ". Delete the entry — an exemption for a name that is "
                    + "gone excuses nothing, and the next constant to take that name would inherit "
                    + "it silently.");
        }
    }

    /**
     * A policy constant that has left the transport is gone from its CODE, not only from its
     * declarations.
     *
     * <p>The evasion this exists for: a constant is deleted, its one site takes the literal (or an
     * instance field, or a local), and the DoD-1 count falls by one while the decision never moved.
     * DoD-1 would report progress. Departure means zero references.
     */
    @Test
    public void noPolicyConstantThatHasLeftIsStillReferencedHere() throws IOException {
        final String code = SourceScan.transport();
        final Set<String> declared = new HashSet<>(declaredConstants());
        assertTrue("ZERO HITS MUST FAIL: declaredConstants() for the transport is EMPTY, so the check below is satisfied "
                        + "by having read nothing. A zero-match scan is a broken scan, not a clean "
                        + "tree.",
                !declared.isEmpty());
        final List<String> ghosts = new ArrayList<>();
        for (final String p : policyConstants()) {
            if (declared.contains(p)) continue;
            final int uses = SourceScan.usesOf(code, p).size();
            if (uses > 0) ghosts.add(p + " (" + uses + " code references)");
        }
        if (!ghosts.isEmpty()) {
            fail("These policy constants are no longer DECLARED in MlsProviderTransport but are "
                    + "still referenced by its code: " + ghosts + ". Either the declaration moved "
                    + "and the references should now read it from MlsConfig, or the constant was "
                    + "replaced in place by something DoD-1 cannot count.");
        }
    }

    /**
     * The number of policy constants declared here only ever falls. <b>DoD-1 is met at 0.</b>
     *
     * <p>Per-constant, not per-total: {@link #eachPolicyConstantStillHereIsStillUsedHere} pins the
     * individual reference counts, because a total that stays put while one constant vanishes and
     * another gains four sites is a total that proved nothing.
     */
    @Test
    public void theCountOfPolicyConstantsDeclaredHereOnlyFalls() throws IOException {
        final Set<String> policy = new HashSet<>(policyConstants());
        assertTrue("ZERO HITS MUST FAIL: declaredConstants() for the transport is EMPTY, so the check below is satisfied "
                        + "by having read nothing. A zero-match scan is a broken scan, not a clean "
                        + "tree — and this is a RATCHET already at 0, so an empty corpus reads as the floor being held.",
                !declaredConstants().isEmpty());
        final List<String> here = new ArrayList<>();
        for (final String c : declaredConstants()) {
            if (policy.contains(c)) here.add(c);
        }
        Collections.sort(here);
        if (here.size() > POLICY_CONSTANTS_DECLARED) {
            fail("DoD-1 went BACKWARDS: " + here.size() + " policy constants are declared in "
                    + "MlsProviderTransport, up from " + POLICY_CONSTANTS_DECLARED + ". " + here);
        }
        if (here.size() < POLICY_CONSTANTS_DECLARED) {
            fail("DoD-1 IMPROVED — " + here.size() + " policy constants remain, down from "
                    + POLICY_CONSTANTS_DECLARED + ". Lower POLICY_CONSTANTS_DECLARED in this test "
                    + "to " + here.size() + " in the SAME commit, so the next regression is caught "
                    + "against the new floor rather than against a number a stage already beat. "
                    + "Remaining: " + here);
        }
    }

    /**
     * Every policy constant still declared here is still read by this class's code, and the count
     * of sites per constant is pinned.
     *
     * <p>Two failures at once, and they are opposite. A constant with ZERO code references is dead
     * weight whose removal DoD-1 would score as progress without a decision having moved. A
     * constant whose site count CHANGES is a decision that grew or shrank a door — which is
     * DoD-3's subject ({@code MlsGateCounterDurabilityGuardTest} classifies each of these sites),
     * so the two must not drift apart silently.
     */
    @Test
    public void eachPolicyConstantStillHereIsStillUsedHere() throws IOException {
        final String code = SourceScan.transport();
        final Set<String> policy = new HashSet<>(policyConstants());
        assertTrue("ZERO HITS MUST FAIL: declaredConstants() for the transport is EMPTY, so the loop below never runs "
                        + "and this assertion certifies green having examined nothing. A zero-match "
                        + "scan is a broken scan, not a clean tree.",
                !declaredConstants().isEmpty());
        final Map<String, Integer> sites = new LinkedHashMap<>();
        int total = 0;
        final List<String> dead = new ArrayList<>();
        for (final String c : declaredConstants()) {
            if (!policy.contains(c)) continue;
            final int n = SourceScan.usesOf(code, c).size();
            sites.put(c, Integer.valueOf(n));
            total += n;
            if (n == 0) dead.add(c);
        }
        if (!dead.isEmpty()) {
            fail("These policy constants are declared in MlsProviderTransport and read nowhere in "
                    + "its code: " + dead + ". Deleting one lowers DoD-1's count without moving a "
                    + "decision; say why it is here or remove it deliberately.");
        }
        if (total != POLICY_CONSTANT_CODE_SITES) {
            fail("The transport's policy-constant code reference sites moved from "
                    + POLICY_CONSTANT_CODE_SITES + " to " + total + ". That is a decision gaining "
                    + "or losing a door. Re-classify it in MlsGateCounterDurabilityGuardTest (DoD-3)"
                    + " and update POLICY_CONSTANT_CODE_SITES here, in the same commit. Per "
                    + "constant: " + sites);
        }
    }

    /**
     * <b>A policy constant may not "leave" the transport for a sibling in the same layer.</b>
     *
     * <p>DoD-1's subject is one FILE, and that is the blindness DoD-3 nearly shipped with: <i>a
     * check whose subject is defined by WHERE a thing lives rather than by WHAT it is</i>. Measured
     * on a mirror, 2026-09-09: declaring {@code PEER_REJOIN_GRACE_MS} in
     * {@code MlsProviderTransport.java} reds three checks here; declaring the SAME constant in
     * {@code MlsPeerGuard.java} — one file over, same package, same not-host-tested layer — leaves
     * every check GREEN and DoD-1 still reads <b>MET, 0 declared</b>.
     *
     * <p>So the ratchet is widened by the thing it is actually about. The target is that the
     * transport keeps <i>persistence, network effects, scheduling and reporting, with no decisions
     * left in it</i> — and a tunable that moves from the transport to a provider sibling has not
     * moved a decision anywhere. Keyed on the tool's own {@code POLICY_CONSTANTS}, the same
     * authoritative list every other check here reads, and each name RE-LOCATED by declaration
     * shape across both trees, exactly as DoD-3's {@code everyPolicyConstantTheToolNamesHasARow}
     * re-locates its own.
     *
     * <p>Measured at HEAD: <b>17 of 19 in the engine, 2 declared nowhere, 0 in the provider.</b> The
     * two are not a loophole — {@code PEER_FTD_ESCALATE_AT} became
     * {@code MlsFtdEscalation.ESCALATE_AT} and {@code IDENTITY_REFRESH_MS}
     * became an {@code MlsConfig} field, neither of which is a {@code static final} scalar under
     * either name — and {@link #noPolicyConstantThatHasLeftIsStillReferencedHere} independently
     * requires a departed constant to have zero references here, so it cannot have been replaced in
     * place by a literal.
     */
    @Test
    public void noPolicyConstantHasMerelyRelocatedWithinTheProviderLayer() throws IOException {
        final List<String> inTheProvider = new ArrayList<>();
        final List<String> nowhere = new ArrayList<>();
        int located = 0;
        for (final String c : policyConstants()) {
            final String[] owner = SourceScan.declaringOwnerOfScalar(c);
            if (owner == null) { nowhere.add(c); continue; }
            located++;
            if ("PROVIDER".equals(owner[1])) inTheProvider.add(c + " -> " + owner[0]);
        }
        assertTrue("not one of the " + policyConstants().size() + " policy constants " + TOOL
                + " names is declared anywhere under the engine or src/ — the locator is wrong, not "
                + "the code, and this check is passing on an empty set", located >= 15);
        if (!inTheProvider.isEmpty()) {
            fail("These policy constants are declared in the PROVIDER layer: " + inTheProvider
                    + ". DoD-1's count is 0 because none is in MlsProviderTransport.java, and that "
                    + "is not the property — a tunable that moves one file sideways has not left "
                    + "the layer this work is emptying, and it is now in a class with no host test "
                    + "either. Move the decision to an engine policy class beside the code that "
                    + "uses it, or to MlsConfig if it has no policy sibling. (" + located
                    + " located, " + nowhere.size() + " declared nowhere: " + nowhere + ".)");
        }
    }

    // ---- the subjects --------------------------------------------------------------------------

    private static List<String> declaredConstants() throws IOException {
        return SourceScan.scalarConstantsDeclaredIn(SourceScan.transport());
    }

    /**
     * A comment in either list cannot become a constant.
     *
     * <p>The mutation: put {@code # this list\u0027s own criterion} inside the brackets of
     * POLICY_CONSTANTS or WIRE_CONSTANTS. Before {@link SourceScan#withoutPythonComments} the
     * apostrophe opened a quote, paired with the next one, and the guards reported phantom
     * constants built out of the prose. Pinned here rather than left to the parser's own javadoc
     * because THIS is the file whose failure message a reader would be holding.
     */
    @Test public void proseInsideEitherListIsNotReadAsAConstant() {
        final String py = "POLICY_CONSTANTS = [\n"
                + "    # NB this file\u0027s parser used to read the next quoted \"phrase\" as a name.\n"
                + "    \u0027REKEY_AFTER_SENDS\u0027, \u0027SWEEP_PAGE\u0027,\n"
                + "]\n";
        final List<String> got = SourceScan.pythonListLiteral(py, "POLICY_CONSTANTS");
        assertEquals("a comment inside the brackets leaked into the parsed list: " + got,
                java.util.Arrays.asList("REKEY_AFTER_SENDS", "SWEEP_PAGE"), got);
    }

    static List<String> policyConstants() throws IOException {
        return SourceScan.pythonListLiteral(SourceScan.read(TOOL), "POLICY_CONSTANTS");
    }

    static List<String> wireConstants() throws IOException {
        return SourceScan.pythonListLiteral(SourceScan.read(TOOL), "WIRE_CONSTANTS");
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * No policy constant is declared in the transport; its remaining constants are wire or ABI values.
 * Declarations are found by shape over code-only source; the policy and wire lists are read from
 * the checked-in classifier, and a constant on neither list fails. A departed constant must have
 * no code references left. See docs/testing.md.
 */
public final class MlsTransportPolicyConstantGuardTest {

    /** The checked-in classification, read rather than re-typed. */
    private static final String TOOL = "tools/mls/transport-classify.py";

    /** Policy constants still declared in the transport; a two-sided ratchet. */
    private static final int POLICY_CONSTANTS_DECLARED = 0;

    /** Code reference sites of those constants, kept in step with the durability guard. */
    private static final int POLICY_CONSTANT_CODE_SITES = 0;

    @Test
    public void everyScalarConstantDeclaredInTheTransportIsClassified() throws IOException {
        final List<String> declared = declaredConstants();
        final Set<String> policy = new HashSet<>(policyConstants());
        final Set<String> wire = new HashSet<>(wireConstants());

        assertTrue(
                        "ZERO HITS MUST FAIL: declaredConstants() for the transport is EMPTY, so the loop below never runs "
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
                    + "is a policy decision the constant count cannot see. (" + declared.size()
                    + " declared, " + policy.size() + " policy names known, " + wire.size()
                    + " wire names known.)");
        }
    }

    @Test
    public void theSubjectsOfThisGuardAreAllNonEmpty() throws IOException {
        // 6 is the size of the wire list, each member of which must still be declared here.
        assertTrue("no scalar static-final constants matched in MlsProviderTransport — the "
                + "declaration shape has changed and every check in this class is now silently "
                + "passing on an empty set",
                declaredConstants().size() >= 6);
        assertTrue("POLICY_CONSTANTS is empty or unparseable in " + TOOL
                + " — the constant count has no subject", policyConstants().size() >= 15);
        assertTrue("WIRE_CONSTANTS is empty or unparseable in " + TOOL
                + " — the exemption list has no subject, so every wire constant would read as an "
                + "unclassified policy constant",
                wireConstants().size() >= 6);
    }

    /**
     * A stale exemption would pass to the next constant of that name. Use is not required: a wire
     * value such as {@code ERA_MODE_PRESERVE} is kept because the server can still send it.
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

    /** A constant replaced in place by a literal, field or local has not left. */
    @Test
    public void noPolicyConstantThatHasLeftIsStillReferencedHere() throws IOException {
        final String code = SourceScan.transport();
        final Set<String> declared = new HashSet<>(declaredConstants());
        assertTrue(
                        "ZERO HITS MUST FAIL: declaredConstants() for the transport is EMPTY, so the check below is satisfied "
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
                    + "replaced in place by something the constant count cannot see.");
        }
    }

    @Test
    public void theCountOfPolicyConstantsDeclaredHereOnlyFalls() throws IOException {
        final Set<String> policy = new HashSet<>(policyConstants());
        assertTrue(
                        "ZERO HITS MUST FAIL: declaredConstants() for the transport is EMPTY, so the check below is satisfied "
                        + "by having read nothing. A zero-match scan is a broken scan, not a clean "
                        + "tree — and this is a RATCHET already at 0, so an empty corpus reads as the floor being held.",
                !declaredConstants().isEmpty());
        final List<String> here = new ArrayList<>();
        for (final String c : declaredConstants()) {
            if (policy.contains(c)) here.add(c);
        }
        Collections.sort(here);
        if (here.size() > POLICY_CONSTANTS_DECLARED) {
            fail("the constant count went BACKWARDS: " + here.size()
                    + " policy constants are declared in "
                    + "MlsProviderTransport, up from " + POLICY_CONSTANTS_DECLARED + ". " + here);
        }
        if (here.size() < POLICY_CONSTANTS_DECLARED) {
            fail("the constant count IMPROVED — " + here.size()
                    + " policy constants remain, down from "
                    + POLICY_CONSTANTS_DECLARED + ". Lower POLICY_CONSTANTS_DECLARED in this test "
                    + "to " + here.size() + " in the SAME commit, so the next regression is caught "
                    + "against the new floor rather than against an older, higher number. "
                    + "Remaining: " + here);
        }
    }

    /**
     * A dead constant would be removable without moving a decision; a changed site count must be
     * re-classified in {@code MlsGateCounterDurabilityGuardTest}.
     */
    @Test
    public void eachPolicyConstantStillHereIsStillUsedHere() throws IOException {
        final String code = SourceScan.transport();
        final Set<String> policy = new HashSet<>(policyConstants());
        assertTrue(
                        "ZERO HITS MUST FAIL: declaredConstants() for the transport is EMPTY, so the loop below never runs "
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
                    + "its code: " + dead
                    + ". Deleting one lowers the constant count without moving a "
                    + "decision; say why it is here or remove it deliberately.");
        }
        if (total != POLICY_CONSTANT_CODE_SITES) {
            fail("The transport's policy-constant code reference sites moved from "
                    + POLICY_CONSTANT_CODE_SITES + " to " + total + ". That is a decision gaining "
                    + "or losing a door. Re-classify it in MlsGateCounterDurabilityGuardTest"
                    + " and update POLICY_CONSTANT_CODE_SITES here, in the same commit. Per "
                    + "constant: " + sites);
        }
    }

    /**
     * Each policy constant is located by declaration shape across the engine and the provider; one
     * declared in a provider sibling has not left the untested layer. Two are declared nowhere
     * under their old names ({@code MlsFtdEscalation.ESCALATE_AT}, an {@code MlsConfig} field).
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
                    + ". The constant count is 0 because none is in MlsProviderTransport.java, and that "
                    + "is not the property — a tunable that moves one file sideways has not left "
                    + "the layer this work is emptying, and it is now in a class with no host test "
                    + "either. Move the decision to an engine policy class beside the code that "
                    + "uses it, or to MlsConfig if it has no policy sibling. (" + located
                    + " located, " + nowhere.size() + " declared nowhere: " + nowhere + ".)");
        }
    }

    private static List<String> declaredConstants() throws IOException {
        return SourceScan.scalarConstantsDeclaredIn(SourceScan.transport());
    }

    /** An apostrophe in a Python comment inside either list must not open a quote. */
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

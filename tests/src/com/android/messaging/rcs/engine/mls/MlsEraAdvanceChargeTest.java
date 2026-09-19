/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link MlsEraAdvanceCharge}: the era-advance budget is charged at the {@code eraAdvance} funnel,
 * above the mode branch, and that is exact because (1) the shipped default is the re-creating mode,
 * (2) the engine refuses to build a preserving era advance, and (3) that {@code BUILD_FAILED} falls
 * through to the create in the same call. Each fact is read from production: the transport and the
 * Rust by source scan, keyed on invoked or declared names, with zero hits failing. If the engine
 * learns to build a preserving advance this fails, and the charge needs a decision. See
 * docs/mls/budgets.md.
 */
public final class MlsEraAdvanceChargeTest {

    /**
     * The shipped default ({@link MlsConfig#DEF_ERA_ADVANCE_MODE}) is charged as a re-creation, the
     * reason the budget's javadoc gives.
     */
    @Test
    public void theShippedDefaultModeIsTheOneThatReWelcomesEveryMember() {
        final int shipped = MlsConfig.DEF_ERA_ADVANCE_MODE;
        assertTrue("MlsConfig.DEF_ERA_ADVANCE_MODE is " + shipped + ", which MlsEraAdvanceCharge "
                + "does not classify. An era-advance mode nobody has classified is one G2 would be "
                + "charging on an unstated basis.", MlsEraAdvanceCharge.isDeclared(shipped));
        assertEquals(
                "the shipped default era-advance mode is no longer the one that re-creates the "
                + "group and re-Welcomes every member. G2's javadoc — and MlsEraBudgetRecord's — say "
                + "that is what the budget bounds; if the default has moved, those sentences and this "
                + "assertion have to move together.",
                MlsEraAdvanceCharge.Basis.RE_WELCOMES_EVERY_MEMBER,
                MlsEraAdvanceCharge.basisFor(shipped));
    }

    /**
     * Every selectable mode is chargeable at the funnel, which lets the charge sit above the
     * branch.
     */
    @Test
    public void everyDeclaredModeIsChargeableAtTheFunnel() {
        final int[] modes = {
            MlsEraAdvanceCharge.MODE_CREATE,
            MlsEraAdvanceCharge.MODE_PRESERVE,
            MlsEraAdvanceCharge.MODE_PRESERVE_CTRL,
        };
        for (final int m : modes) {
            assertNotNull("mode " + m + " has no declared basis", MlsEraAdvanceCharge.basisFor(m));
            assertTrue("mode " + m + " is declared but not chargeable at the funnel, so the charge "
                    + "at eraAdvance() would be taken for an operation this mode does not perform. "
                    + "Move the charge below the mode branch, or explain the mode.",
                    MlsEraAdvanceCharge.chargeableAtTheFunnel(m));
        }
    }

    /**
     * An undeclared mode from the {@code debug.rcs.mls_era_advance_mode} knob is refused, not
     * charged and not executed as another mode.
     */
    @Test
    public void anUndeclaredModeIsRefusedRatherThanCharged() {
        for (final int bogus : new int[] {-1, 3, 7, 99, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertNull("mode " + bogus + " must have no basis",
                    MlsEraAdvanceCharge.basisFor(bogus));
            assertFalse("mode " + bogus + " is not declared, so nothing in the tree says what it "
                    + "costs a peer — it must be REFUSED, never charged on an unstated basis",
                    MlsEraAdvanceCharge.chargeableAtTheFunnel(bogus));
            assertTrue("the refusal line must say the mode is undeclared",
                    MlsEraAdvanceCharge.line(bogus).contains("NOT DECLARED"));
        }
    }

    /**
     * Every {@link MlsEraAdvanceCharge.Basis} is reachable from some mode, so a basis added later
     * fails here rather than falling through {@code chargeableAtTheFunnel}'s switch.
     */
    @Test
    public void everyBasisIsReachableFromSomeMode() {
        for (final MlsEraAdvanceCharge.Basis b : MlsEraAdvanceCharge.Basis.values()) {
            boolean reached = false;
            for (int m = -8; m <= 32 && !reached; m++) {
                reached = b == MlsEraAdvanceCharge.basisFor(m);
            }
            assertTrue(b + " is declared but no era-advance mode produces it. Either wire the mode "
                    + "that needs it, or delete it — an unreachable basis is a claim about the "
                    + "budget that nothing in production makes.", reached);
        }
    }

    /**
     * The engine refuses to build a preserving era advance: the era is GroupContext extension
     * {@code 0xF001}, which a GroupContextExtensions proposal may neither change nor remove, so an
     * era advance cannot be a commit. This is why
     * {@link MlsEraAdvanceCharge.Basis#FALLS_BACK_TO_A_RE_CREATION} holds.
     */
    @Test
    public void theEngineRefusesToBuildAPreservingEraAdvance() throws IOException {
        // Raw read: SourceScan.codeOnly() is Java-shaped and treats a Rust lifetime tick as a char
        // literal. Raw is safe here because a positive check (Err( present) is paired with
        // negatives (Ok( absent, parameters unused), and a comment can only make a negative fail,
        // never pass. An ordering is two positives and is therefore never asserted over raw Rust.
        final String rust = SourceScan.read("rust/rcs_mls_ffi/src/ffi.rs");
        final String decl = "pub fn commit_era_advance(";
        final int at = rust.indexOf(decl);
        assertTrue("commit_era_advance is gone from the FFI. It is the primitive both preserving "
                + "era-advance modes call FIRST, and its unconditional refusal is why "
                + "MlsEraAdvanceCharge classifies them as falling back to a re-creation. Renamed, "
                + "moved or deleted, this guard has stopped watching the fact it certifies.",
                at >= 0);
        final String body = rustBodyAt(rust, at);
        assertTrue("could not read commit_era_advance's body — the scan has gone stale",
                body.length() > 0);
        assertTrue("commit_era_advance no longer refuses: its body has no Err(. If it now builds a "
                + "commit, a preserving era advance can reach the wire with welcome=new byte[0] and "
                + "MlsEraAdvanceCharge.Basis.FALLS_BACK_TO_A_RE_CREATION is FALSE for modes 1 and 2 "
                + "— which means G2 is charging the full re-Welcome price for an operation that "
                + "re-Welcomes nobody. Decide that deliberately.",
                body.contains("Err("));
        assertFalse("commit_era_advance has an Ok( in it, so it can now succeed — see the message "
                + "above; the preserving modes no longer fall back and the funnel charge needs a "
                + "decision rather than this classification.", body.contains("Ok("));
        // The parameters are underscore-prefixed because nothing reads them; no implementation can
        // avoid reading the group id and target era, so this fires however it returns.
        final String signature = rust.substring(at, rust.indexOf('{', at));
        for (final String unused : new String[] {"_gid", "_new_era"}) {
            assertTrue("commit_era_advance's " + unused + " parameter is no longer unused, so the "
                    + "function reads its inputs and is being IMPLEMENTED — whatever it returns. "
                    + "A preserving era advance would then be buildable, both preserving modes stop "
                    + "falling through to the create, and MlsEraAdvanceCharge.Basis"
                    + ".FALLS_BACK_TO_A_RE_CREATION is false for them. That is the review "
                    + "asks for, and it is due now. Signature: " + signature.trim(),
                    signature.contains(unused));
        }
    }

    /**
     * {@code BUILD_FAILED} falls through to the create in the same call: the preserving arm is
     * called first and the Welcome-minting create after it.
     */
    @Test
    public void theBuildFailedPathFallsThroughToTheCreateInTheSameCall() throws IOException {
        final String body =
                SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "eraAdvanceLocked");
        assertTrue("eraAdvanceLocked not found — it is where the mode branch lives and this guard "
                + "has stopped watching it", body.length() > 0);
        final int preserving = body.indexOf("eraAdvancePreserving(");
        final int buildFailed = body.indexOf("BUILD_FAILED");
        final int create = body.indexOf("createGroupPlanned(");
        assertTrue(
                "eraAdvanceLocked no longer calls eraAdvancePreserving — the mode branch is gone "
                + "and MlsEraAdvanceCharge's two preserving modes describe nothing", preserving
                >= 0);
        assertTrue("eraAdvanceLocked no longer mentions BUILD_FAILED, which is the ONLY outcome of "
                + "the preserving arm that may fall through. Without it a preserving mode terminates "
                + "on its own and the funnel charge is no longer exact.", buildFailed >= 0);
        assertTrue("eraAdvanceLocked no longer reaches createGroupPlanned — the Welcome-minting "
                + "create is the thing the preserving arm falls back TO", create >= 0);
        assertTrue("createGroupPlanned is reached BEFORE eraAdvancePreserving in eraAdvanceLocked "
                + "(create at " + create + ", preserving at " + preserving
                + "). The preserving arm "
                + "can then no longer fall through to it, so modes 1 and 2 terminate on their own "
                + "and are not FALLS_BACK_TO_A_RE_CREATION.", preserving < create);
        assertTrue(
                "BUILD_FAILED is tested after the create rather than between the preserving call "
                + "and it, so it is no longer the preserving arm's fall-through condition",
                buildFailed > preserving && buildFailed < create);
    }

    /**
     * The predicate has a production call site, and it runs before the charge; asking after
     * charging would change nothing.
     */
    @Test
    public void theFunnelAsksTheChargePredicateBeforeItCharges() throws IOException {
        // No closing paren, so the selector does not pin the funnel's parameter count. The unsplit
        // view: the eraAdvance funnel lives in MlsEraAdvance.
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "eraAdvance",
                "final byte[] carryGroupInfo, final MlsAdvanceEraKind kind");
        assertTrue("the eraAdvance funnel overload not found — CHARGED_BY in "
                + "MlsPeerReJoinBudgetGuardTest names it as G2's charge site, so this guard and that "
                + "one have both gone stale", body.length() > 0);
        final int ask = body.indexOf("MlsEraAdvanceCharge.chargeableAtTheFunnel(");
        final int charge = body.indexOf("MlsPeerGuard.allowEraAdvance(");
        assertTrue("the eraAdvance funnel does not ask MlsEraAdvanceCharge.chargeableAtTheFunnel. "
                + "The charge then sits above the mode branch for no stated reason — "
                + "and a predicate with no production call site is wired to nothing.",
                ask >= 0);
        assertTrue("the eraAdvance funnel no longer charges G2 at all", charge >= 0);
        assertTrue("the funnel charges G2 (at " + charge
                + ") before asking whether this mode may be "
                + "charged there (at " + ask + "). Asking afterwards cannot refuse anything.",
                ask < charge);
        assertTrue("the funnel asks chargeableAtTheFunnel but does not pass the live mode — "
                + "MlsConfig.eraAdvanceMode, through eraAdvanceMode(), is the production argument "
                + "and a literal here would be a predicate wired to nothing in the transport",
                body.contains("chargeableAtTheFunnel(eraAdvanceMode())"));
    }

    /**
     * Every {@code ERA_MODE_} the transport declares is classified here with the same value; zero
     * declarations fail.
     */
    @Test
    public void everyModeTheTransportDeclaresIsClassified() throws IOException {
        final Matcher m = Pattern.compile(
                "(?m)^\\s*private static final int (ERA_MODE_\\w+)\\s*=\\s*(-?\\d+)\\s*;")
                .matcher(SourceScan.transport());
        final List<String> found = new ArrayList<>();
        final List<String> unclassified = new ArrayList<>();
        final List<String> drifted = new ArrayList<>();
        while (m.find()) {
            final String name = m.group(1);
            final int value = Integer.parseInt(m.group(2));
            found.add(name + "=" + value);
            if (!MlsEraAdvanceCharge.isDeclared(value)) unclassified.add(name + "=" + value);
            final int mirrored = mirrorOf(name);
            if (mirrored != Integer.MIN_VALUE && mirrored != value) {
                drifted.add(name + " is " + value + " in the transport and " + mirrored + " here");
            }
        }
        assertFalse("no ERA_MODE_ constants found in MlsProviderTransport. They are what "
                + "debug.rcs.mls_era_advance_mode selects between, and this guard exists to make "
                + "sure each has a declared basis for being charged to G2. Zero hits is not a pass: "
                + "the constants have been renamed or moved and nothing is watching them.",
                found.isEmpty());
        assertTrue(
                "these era-advance modes are selectable and MlsEraAdvanceCharge does not classify "
                + "them: " + unclassified
                + ". Say whether each re-Welcomes every member itself, or "
                + "falls back to one that does — then add it to Basis. Do NOT default it: an "
                + "unclassified mode is charged for a reason nobody has stated.",
                unclassified.isEmpty());
        assertTrue("MlsEraAdvanceCharge's mode constants have drifted from the transport's, so a "
                + "basis is being reported for a mode number that selects a different "
                + "implementation: " + drifted + ". These are two spellings of one table and they "
                + "must agree.", drifted.isEmpty());
    }

    /** The transport's constant name → the value {@link MlsEraAdvanceCharge} mirrors it with. */
    private static int mirrorOf(final String transportConstant) {
        switch (transportConstant) {
            case "ERA_MODE_CREATE":        return MlsEraAdvanceCharge.MODE_CREATE;
            case "ERA_MODE_PRESERVE":      return MlsEraAdvanceCharge.MODE_PRESERVE;
            case "ERA_MODE_PRESERVE_CTRL": return MlsEraAdvanceCharge.MODE_PRESERVE_CTRL;
            default:                       return Integer.MIN_VALUE;   // not mirrored here
        }
    }

    /**
     * The body of a Rust {@code impl} method starting at {@code at}, to the 4-space closing brace.
     * Indentation rather than brace counting, since the body holds a long string literal. If the
     * convention changes the span over-collects, which only makes the {@code Ok(} negative more
     * likely to fire.
     */
    private static String rustBodyAt(final String rust, final int at) {
        final int open = rust.indexOf('{', at);
        if (open < 0) return "";
        final int close = rust.indexOf("\n    }", open);
        return close < 0 ? "" : rust.substring(open, close);
    }
}

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
 * <b>The G2 charge sits above the era-advance mode branch, and this says why that is exact.</b>
 *
 * <h2>What is being asserted, and against which arguments</h2>
 *
 * <p>Review found that {@code MlsProviderTransport.eraAdvance} charges G2 at the funnel,
 * more than a hundred lines above the branch that picks between two implementations — and that G2's
 * own javadoc justifies the budget with a sentence that is <i>false as source shape</i> on one of
 * those two branches. Nothing in the tree said which reading was intended.
 *
 * <p>The answer is that the charge is <b>exact</b>, and the three facts that make it so are each
 * bound to production here rather than restated:
 *
 * <ol>
 *   <li><b>The shipped default is the re-creating mode.</b> Read from
 *       {@link MlsConfig#DEF_ERA_ADVANCE_MODE}, not from a literal.</li>
 *   <li><b>The preserving modes cannot be built.</b> {@code ProdSession::commit_era_advance} refuses
 *       unconditionally, so both take {@code BUILD_FAILED}. Read from the Rust source, because that
 *       is the only place the fact lives.</li>
 *   <li><b>{@code BUILD_FAILED} falls through to the create in the same call.</b> Read from
 *       {@code eraAdvanceLocked}'s ordering.</li>
 * </ol>
 *
 * <h2>Why three of these are source scans</h2>
 *
 * <p>{@code MlsProviderTransport} needs a {@code Context} and a bound provider and cannot run in the
 * host suite, and the Rust is not Java at all — the model and the precedent is
 * {@code MlsPeerReJoinBudgetGuardTest}, which found a call site two hand inventories had missed.
 * Every needle here is an <b>invoked or declared name</b>, never a
 * label or a receiver, and <b>zero occurrences FAIL</b> rather than pass — a scan that has lost
 * sight of its own subject has certified something it cannot see.
 *
 * <p><b>If the second fact ever stops being true</b> — someone makes {@code commit_era_advance}
 * work — this test fails, and it should: {@link MlsEraAdvanceCharge.Basis#FALLS_BACK_TO_A_RE_CREATION}
 * would then be wrong for those modes and G2 would be over-charging a genuinely cheaper operation.
 * That is the review this asks for, arriving at the moment it is needed instead of never.
 */
public final class MlsEraAdvanceChargeTest {

    // ---- the arithmetic, over production constants -------------------------------------------

    /**
     * THE SHIPPED DEFAULT charges for the reason G2's javadoc actually gives.
     *
     * <p>The argument is {@link MlsConfig#DEF_ERA_ADVANCE_MODE}, read from production. A build that
     * flipped the default to a preserving mode would land in the other arm and this fails, which is
     * the point: G2's javadoc would then be describing a mode nobody selects.
     */
    @Test
    public void theShippedDefaultModeIsTheOneThatReWelcomesEveryMember() {
        final int shipped = MlsConfig.DEF_ERA_ADVANCE_MODE;
        assertTrue("MlsConfig.DEF_ERA_ADVANCE_MODE is " + shipped + ", which MlsEraAdvanceCharge "
                + "does not classify. An era-advance mode nobody has classified is one G2 would be "
                + "charging on an unstated basis.", MlsEraAdvanceCharge.isDeclared(shipped));
        assertEquals("the shipped default era-advance mode is no longer the one that re-creates the "
                + "group and re-Welcomes every member. G2's javadoc — and MlsEraBudgetRecord's — say "
                + "that is what the budget bounds; if the default has moved, those sentences and this "
                + "assertion have to move together.",
                MlsEraAdvanceCharge.Basis.RE_WELCOMES_EVERY_MEMBER,
                MlsEraAdvanceCharge.basisFor(shipped));
    }

    /**
     * Every selectable mode is chargeable at the funnel — which is what lets the charge sit above
     * the branch at all.
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
     * An UNDECLARED mode is refused, not charged and not waved through.
     *
     * <p>{@code debug.rcs.mls_era_advance_mode} is an operator knob taking an {@code int}. Before
     * this, mode 7 fell into {@code mode != ERA_MODE_CREATE} and was executed as though it were the
     * preserving mode — an unrecognised value silently acquiring a behaviour, which is the shape the
     * {@code default} arm of {@code onPeerReportedFailure} was written to refuse one file over.
     */
    @Test
    public void anUndeclaredModeIsRefusedRatherThanCharged() {
        for (final int bogus : new int[] {-1, 3, 7, 99, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertNull("mode " + bogus + " must have no basis", MlsEraAdvanceCharge.basisFor(bogus));
            assertFalse("mode " + bogus + " is not declared, so nothing in the tree says what it "
                    + "costs a peer — it must be REFUSED, never charged on an unstated basis",
                    MlsEraAdvanceCharge.chargeableAtTheFunnel(bogus));
            assertTrue("the refusal line must say the mode is undeclared",
                    MlsEraAdvanceCharge.line(bogus).contains("NOT DECLARED"));
        }
    }

    /**
     * Every {@link MlsEraAdvanceCharge.Basis} is reachable from some mode.
     *
     * <p>Walking the enum is what makes a basis added later — the interesting one being a mode that
     * genuinely re-Welcomes nobody — a test failure rather than an unused constant that
     * {@code chargeableAtTheFunnel} happens to answer {@code true} for by falling through its
     * switch.
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

    // ---- the production facts the classification rests on --------------------------------------

    /**
     * <b>Fact 2.</b> The engine refuses to BUILD a preserving era advance, unconditionally.
     *
     * <p>This is the whole of why {@link MlsEraAdvanceCharge.Basis#FALLS_BACK_TO_A_RE_CREATION} is
     * true. The era is GroupContext extension {@code 0xF001}, a GroupContextExtensions proposal may
     * neither change nor remove it (§9.2), so an era advance cannot be a commit and
     * {@code commit_era_advance} returns an error before building anything.
     *
     * <p>Asserted over the Rust because that is where the refusal is. If it is ever implemented,
     * {@code eraAdvancePreserving} could reach the wire with an empty Welcome, the preserving modes
     * would no longer fall back, and the funnel charge would become an over-charge that wants a
     * decision rather than an inheritance.
     */
    @Test
    public void theEngineRefusesToBuildAPreservingEraAdvance() throws IOException {
        // RAW READ ON PURPOSE. Do NOT "tidy" this into SourceScan.codeOnly(): that stripper is
        // JAVA-SHAPED, and applied to Rust it treats a lifetime tick as a char-literal opener.
        // ffi.rs has hundreds of ticks — &'static str, slice<'a>, cstr<'a> — so every odd one opens
        // a blanking run to the next and hundreds of lines vanish in a single span, at boundaries
        // nobody chose. (Its escape arm also eats the newline after a Rust line-continuation inside
        // a string, so line offsets shift too.) Reported by a reviewer who measured the tick
        // count; not reproduced here because the fix is to never apply it.
        //
        // WHY RAW IS SAFE HERE, stated precisely, because the loose version of this is wrong and
        // was corrected in review. It is NOT that the assertion is "one-directional". It is
        // that the three checks below have OPPOSITE SENSES over the same body: one POSITIVE (Err(
        // is present) and two NEGATIVE (Ok( is absent; the parameters are still unused). A comment
        // can only ADD text, so it can satisfy a positive — which is the d834c492 hole — but it can
        // never satisfy a negative, and it can only ever break one. So a comment can make this test
        // FAIL spuriously, never pass.
        //
        // THE COROLLARY MATTERS MORE THAN THE RULE: a pair of two POSITIVES over raw text has no
        // such protection, and an ORDERING is two positives. That is the case the Rust warning
        // was actually about, and it is why nothing in this file asserts an ordering over ffi.rs.
        // Read "raw is allowed when a negative assertion covers the positive one", not "raw is
        // allowed for one-directional assertions".
        //
        // The Java assertions in this file are single positives and read codeOnly for exactly that
        // reason: a review found a scan of raw source passing on a charge that had been
        // DELETED and left behind in a comment (Messaging d834c492).
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
        // THE THIRD CHECK, AND IT IS THE ONE THAT CLOSES THE RESIDUAL HOLE. `Err( present and Ok(
        // absent` still reports green on an implementation that returns success by some other
        // spelling — a `?`, a map, a helper, a bare variable — which is a needle problem of the kind
        // this file is about, sitting inside the guard written to avoid one.
        //
        // The parameters cannot be spelled around. commit_era_advance takes _gid, _aad and _new_era,
        // underscore-prefixed because nothing reads them, and NOTHING CAN IMPLEMENT THIS FUNCTION
        // WITHOUT READING THE GROUP ID AND THE TARGET ERA. So an implementation has to un-prefix
        // them, and this fires — independently of how it returns.
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
     * <b>Fact 3.</b> {@code BUILD_FAILED} falls through to the create, inside the same call.
     *
     * <p>Asserted as an ORDERING over invoked names, the same way
     * {@code MlsPeerReJoinBudgetGuardTest.theRebuildChargesBeforeItDestroysAnything} is: the
     * preserving arm is called first and the Welcome-minting create is reached after it, so the
     * fall-through path exists. If the create moved above the preserving call — or disappeared —
     * modes 1 and 2 would terminate on their own and the classification would be wrong.
     */
    @Test
    public void theBuildFailedPathFallsThroughToTheCreateInTheSameCall() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), "eraAdvanceLocked");
        assertTrue("eraAdvanceLocked not found — it is where the mode branch lives and this guard "
                + "has stopped watching it", body.length() > 0);
        final int preserving = body.indexOf("eraAdvancePreserving(");
        final int buildFailed = body.indexOf("BUILD_FAILED");
        final int create = body.indexOf("createGroupPlanned(");
        assertTrue("eraAdvanceLocked no longer calls eraAdvancePreserving — the mode branch is gone "
                + "and MlsEraAdvanceCharge's two preserving modes describe nothing", preserving >= 0);
        assertTrue("eraAdvanceLocked no longer mentions BUILD_FAILED, which is the ONLY outcome of "
                + "the preserving arm that may fall through. Without it a preserving mode terminates "
                + "on its own and the funnel charge is no longer exact.", buildFailed >= 0);
        assertTrue("eraAdvanceLocked no longer reaches createGroupPlanned — the Welcome-minting "
                + "create is the thing the preserving arm falls back TO", create >= 0);
        assertTrue("createGroupPlanned is reached BEFORE eraAdvancePreserving in eraAdvanceLocked "
                + "(create at " + create + ", preserving at " + preserving + "). The preserving arm "
                + "can then no longer fall through to it, so modes 1 and 2 terminate on their own "
                + "and are not FALLS_BACK_TO_A_RE_CREATION.", preserving < create);
        assertTrue("BUILD_FAILED is tested after the create rather than between the preserving call "
                + "and it, so it is no longer the preserving arm's fall-through condition",
                buildFailed > preserving && buildFailed < create);
    }

    /**
     * <b>The predicate has a production call site, and it runs BEFORE the charge.</b>
     *
     * <p>The failure this avoids: a reconciliation predicate that exists,
     * is tested, and is wired to nothing. Asserted on the INVOKED NAME, and ordered — asking after
     * charging would answer a question whose answer no longer changes anything.
     */
    @Test
    public void theFunnelAsksTheChargePredicateBeforeItCharges() throws IOException {
        // NO CLOSING PAREN — see the note on MlsPeerReJoinBudgetGuardTest's eraAdvanceLocked row.
        // With one, this selector asserts the funnel's PARAMETER COUNT, which is not the property
        // under test and which went red for a fifth parameter while the charge it guards
        // was exactly where it should be.
        final String body = SourceScan.bodyOf(SourceScan.transport(), "eraAdvance",
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
        assertTrue("the funnel charges G2 (at " + charge + ") before asking whether this mode may be "
                + "charged there (at " + ask + "). Asking afterwards cannot refuse anything.",
                ask < charge);
        assertTrue("the funnel asks chargeableAtTheFunnel but does not pass the live mode — "
                + "MlsConfig.eraAdvanceMode, through eraAdvanceMode(), is the production argument "
                + "and a literal here would be a predicate wired to nothing in the transport",
                body.contains("chargeableAtTheFunnel(eraAdvanceMode())"));
    }

    /**
     * Every {@code ERA_MODE_} the transport declares is classified here, with the same value.
     *
     * <p>Zero declarations FAIL: the transport's constants are the ground truth
     * for what an operator can select, and a scan that finds none of them has certified a mode table
     * it cannot see.
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
        assertTrue("these era-advance modes are selectable and MlsEraAdvanceCharge does not classify "
                + "them: " + unclassified + ". Say whether each re-Welcomes every member itself, or "
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

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * The body of a Rust {@code impl} method whose declaration starts at {@code at}, to the closing
     * brace at its own indentation.
     *
     * <p>Private rather than in {@link SourceScan}: that class's locator is written for Java
     * class-level declarations, and indentation rather than brace counting is deliberate here
     * because the body being read contains a long string literal — a brace counter that does not lex
     * Rust strings would be a scanner with its own bug. Every method in that {@code impl} block
     * closes on a 4-space {@code \}}.
     *
     * <p><b>This is a span between two landmarks, a shape that has gone
     * wrong in one of our own scans</b> ({@code 0aefded1}): a span acquires whatever is inserted
     * between its ends. Here the failure direction is the safe one — if the closing convention
     * changes and the span over-runs into the next function, it picks up MORE text, so the
     * {@code Ok(} negative is more likely to fire, not less. A span that can only over-collect is
     * safe under a negative assertion and would not be under a positive one.
     */
    private static String rustBodyAt(final String rust, final int at) {
        final int open = rust.indexOf('{', at);
        if (open < 0) return "";
        final int close = rust.indexOf("\n    }", open);
        return close < 0 ? "" : rust.substring(open, close);
    }
}

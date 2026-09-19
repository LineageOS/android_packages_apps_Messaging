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

/**
 * Why G2 may be charged <b>above</b> the era-advance mode branch.
 *
 * <h2>The question this answers</h2>
 *
 * <p>{@code MlsProviderTransport.eraAdvance} charges {@code MlsPeerGuard.allowEraAdvance} (G2) at the
 * funnel, and {@code eraAdvanceLocked} chooses between two implementations more than a hundred lines
 * below that charge: {@code eraAdvancePreserving} for {@link #MODE_PRESERVE} / {@link
 * #MODE_PRESERVE_CTRL}, the legacy create for {@link #MODE_CREATE}. The preserving arm sends
 * {@code welcome = new byte[0]} and its success line says <i>"membership untouched, no Welcome
 * needed"</i>, while G2's own javadoc justifies the budget on the grounds that <i>"every advance
 * re-creates the group and makes EVERY member re-join via a Welcome"</i>.
 *
 * <p>Read as source shapes those two statements contradict each other, and this class was
 * filed on the contradiction. <b>The charge is right and so is the javadoc</b>, but the reason they
 * are both right is not the one written down, and this class is where the real one lives.
 *
 * <h2>The reason, and it is a structural fact rather than a conservative posture</h2>
 *
 * <p>An era advance <b>cannot</b> be a commit. The era is GroupContext extension {@code 0xF001}, and
 * RFC 9420 makes a GroupContextExtensions proposal the only in-group way to alter one — but a
 * GroupContextExtensions proposal may neither change nor remove the Era (design doc §9.2; Google Messages
 * carries {@code GroupContextExtensionProposalChangesEraError} and
 * {@code GroupContextExtensionProposalRemovesEraError} for exactly these two). So the era is
 * immutable for the lifetime of an MLS group instance, and the only way to move it is to build a NEW
 * group — which re-Welcomes every member.
 *
 * <p>Our engine enforces that at the source: {@code ProdSession::commit_era_advance} refuses
 * unconditionally, before building anything. {@code eraAdvancePreserving} therefore reaches
 * {@code BUILD_FAILED} on <b>every</b> call, in <b>both</b> preserving modes, and
 * {@code eraAdvanceLocked} falls through to the legacy create <b>inside the same call</b>. Nothing
 * the preserving arm would have sent ever reaches the wire.
 *
 * <p><b>So the funnel charge is EXACT, not merely conservative.</b> Whatever mode is selected, what
 * reaches the server is a create that mints a Welcome per member — the operation ~17 of which wedged
 * a real third party's phone for a month. That is what {@link
 * Basis#FALLS_BACK_TO_A_RE_CREATION} records, and it is the half of the answer no comment in the
 * tree stated.
 *
 * <h2>What this class is FOR, given the answer is "keep charging"</h2>
 *
 * <p>Not to change behaviour — it changes none. It exists so the answer stops being an emergent
 * property of where one statement sits relative to a branch a hundred lines below it. A mode added
 * later that <b>can</b> reach the wire without re-Welcoming has no {@link Basis} here, so
 * {@code eraAdvance} refuses it by name instead of charging it for a reason that is false, and
 * {@link MlsEraAdvanceChargeTest} fails until someone declares which it is.
 *
 * <p><b>Do not use this to make a mode free.</b> The budget is the circuit breaker written after an
 * incident; a mode that genuinely re-Welcomes nobody needs its own argument, its own review and its
 * own {@link Basis} constant, not a silent exemption.
 */
public final class MlsEraAdvanceCharge {

    private MlsEraAdvanceCharge() {}

    /**
     * Legacy create: rebuild the roster from freshly claimed KeyPackages and create a new group at
     * era+1 reusing the RCS group id. Mirrors {@code MlsProviderTransport.ERA_MODE_CREATE}, and it is
     * {@link MlsConfig#DEF_ERA_ADVANCE_MODE}.
     */
    public static final int MODE_CREATE = 0;

    /** Preserve membership via a GroupContextExtensions commit. §9.2-illegal. */
    public static final int MODE_PRESERVE = 1;

    /** Preserve membership via a control commit. §9.2-illegal. */
    public static final int MODE_PRESERVE_CTRL = 2;

    /** Why one selectable era-advance mode is chargeable at the funnel, above the mode branch. */
    public enum Basis {
        /**
         * This mode itself mints a Welcome per member. G2's javadoc describes this mode and is
         * literally true of it.
         */
        RE_WELCOMES_EVERY_MEMBER,
        /**
         * This mode cannot be built — the engine refuses it before anything is sent — so it reaches
         * {@code BUILD_FAILED} and falls through to a re-creation <b>within the same call</b>. G2's
         * javadoc is true of what actually reaches the wire, which is the only thing a peer pays
         * for.
         */
        FALLS_BACK_TO_A_RE_CREATION,
    }

    /**
     * The basis on which {@code mode} may be charged at the funnel, or {@code null} if this mode has
     * never been classified.
     *
     * <p><b>{@code null} means UNDECLARED and nothing else.</b> It is not "free" and it is not
     * "chargeable" — the caller must refuse the operation and say so, exactly as
     * {@code MlsPeerGuard.allowEraAdvance} refuses an advance it cannot key a budget against. An
     * unclassified mode selected by {@code debug.rcs.mls_era_advance_mode} used to be treated as
     * {@link #MODE_PRESERVE} by fall-through, which is the quiet version of the same mistake.
     */
    public static Basis basisFor(final int mode) {
        switch (mode) {
            case MODE_CREATE:
                return Basis.RE_WELCOMES_EVERY_MEMBER;
            case MODE_PRESERVE:
            case MODE_PRESERVE_CTRL:
                return Basis.FALLS_BACK_TO_A_RE_CREATION;
            default:
                return null;
        }
    }

    /** Whether {@code mode} has a declared {@link Basis}. */
    public static boolean isDeclared(final int mode) {
        return basisFor(mode) != null;
    }

    /**
     * <b>The reconciliation predicate.</b> May the G2 charge be taken at the {@code eraAdvance}
     * funnel, above the branch that picks the implementation?
     *
     * <p>True exactly when the mode is declared — because every declared basis says the same thing
     * about what reaches the server. The predicate is stated over the {@link Basis} rather than over
     * the mode number so that adding a basis which does <em>not</em> re-Welcome forces this method
     * to be changed too, rather than silently inheriting the answer.
     *
     * @param mode the live {@code MlsConfig.eraAdvanceMode}, never a literal
     */
    public static boolean chargeableAtTheFunnel(final int mode) {
        final Basis b = basisFor(mode);
        if (b == null) return false;
        switch (b) {
            case RE_WELCOMES_EVERY_MEMBER:
            case FALLS_BACK_TO_A_RE_CREATION:
                return true;
            default:
                return false;
        }
    }

    /** One line for the log, saying which of the two reasons applies to the selected mode. */
    public static String line(final int mode) {
        final Basis b = basisFor(mode);
        if (b == null) {
            return "era-advance mode " + mode + " is NOT DECLARED — nothing in the tree says whether "
                    + "it makes every member re-join by Welcome, so the era budget cannot say what it "
                    + "would be charging for. Refusing rather than charging on an unstated basis "
                    + "";
        }
        if (b == Basis.RE_WELCOMES_EVERY_MEMBER) {
            return "era-advance mode " + mode + " re-creates the group and makes EVERY member "
                    + "re-join by Welcome, which is exactly the cost the era budget bounds";
        }
        return "era-advance mode " + mode + " is §9.2-illegal — the engine refuses to BUILD it, so it "
                + "takes the BUILD_FAILED path into the legacy create inside this same call and what "
                + "reaches the server still re-Welcomes every member. The funnel charge is exact, not "
                + "conservative";
    }
}

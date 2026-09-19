/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Why the era budget (G2) is charged at the {@code eraAdvance} funnel, above the mode branch. The
 * era is GroupContext extension {@code 0xF001}, which no in-group proposal may change (RCC.16
 * §8.3), so every advance that reaches the server is a new group re-Welcoming every member: the
 * preserving modes cannot be built and fall through to the create in the same call. Each declared
 * mode has a {@link Basis}; an undeclared one is refused rather than charged. See
 * docs/mls/budgets.md.
 */
public final class MlsEraAdvanceCharge {

    private MlsEraAdvanceCharge() {}

    /**
     * Create: a new group at the next era around freshly claimed KeyPackages, reusing the RCS group
     * id. {@link MlsConfig#DEF_ERA_ADVANCE_MODE}.
     */
    public static final int MODE_CREATE = 0;

    /** Preserve membership via a GroupContextExtensions commit; the engine refuses to build it. */
    public static final int MODE_PRESERVE = 1;

    /** Preserve membership via a control commit; the engine refuses to build it. */
    public static final int MODE_PRESERVE_CTRL = 2;

    /** Why one selectable era-advance mode is chargeable at the funnel, above the mode branch. */
    public enum Basis {
        /** This mode itself mints a Welcome per member. */
        RE_WELCOMES_EVERY_MEMBER,
        /**
         * The engine refuses to build this mode, so it falls through to a re-creation in the same
         * call.
         */
        FALLS_BACK_TO_A_RE_CREATION,
    }

    /**
     * The basis on which {@code mode} may be charged at the funnel, or {@code null} for an
     * undeclared mode, which the caller must refuse (it is neither free nor chargeable).
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
     * May the G2 charge be taken at the funnel? Stated over the {@link Basis}, so adding a basis
     * that does not re-Welcome forces this method to change.
     *
     * @param mode the live {@code MlsConfig.eraAdvanceMode}
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

    /** One line for the log, saying which basis applies to the selected mode. */
    public static String line(final int mode) {
        final Basis b = basisFor(mode);
        if (b == null) {
            return "era-advance mode " + mode
                    + " is NOT DECLARED — nothing in the tree says whether "
                    + "it makes every member re-join by Welcome, so the era budget cannot say what it "
                    + "would be charging for. Refusing rather than charging on an unstated basis "
                    + "";
        }
        if (b == Basis.RE_WELCOMES_EVERY_MEMBER) {
            return "era-advance mode " + mode + " re-creates the group and makes EVERY member "
                    + "re-join by Welcome, which is exactly the cost the era budget bounds";
        }
        return "era-advance mode " + mode
                + " is §9.2-illegal — the engine refuses to BUILD it, so it "
                + "takes the BUILD_FAILED path into the legacy create inside this same call and what "
                + "reaches the server still re-Welcomes every member. The funnel charge is exact, not "
                + "conservative";
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Whether a door into G2 (the era budget) must charge again when an earlier door in the same
 * recovery episode already did. A charge whose create the server accepted is used up, since a
 * Welcome and a KeyPackage claim per member were spent. See docs/mls/budgets.md.
 */
public final class MlsRecreationEpisode {

    private MlsRecreationEpisode() {}

    /** What G2 has already been charged in the episode reaching this door. */
    public enum PriorCharge {
        /** Nothing charged yet; this door pays. */
        NONE,
        /**
         * Charged, and no create the server took yet (none sent, or refused); no member re-joined.
         */
        SPENT_AND_UNCONSUMED,
        /**
         * Charged, and the server accepted the create it paid for (the RPC returned ok, whether or
         * not the era then moved). A further re-creation is a second peer-facing event.
         */
        SPENT_AND_CONSUMED,
    }

    /**
     * @param spent whether G2 has been charged at all in this episode
     * @param consumedByAnAcceptedRecreation whether the server accepted the create that charge paid
     *     for; ignored when nothing was spent
     */
    public static PriorCharge priorCharge(final boolean spent,
            final boolean consumedByAnAcceptedRecreation) {
        if (!spent) return PriorCharge.NONE;
        return consumedByAnAcceptedRecreation
                ? PriorCharge.SPENT_AND_CONSUMED : PriorCharge.SPENT_AND_UNCONSUMED;
    }

    /** Whether this door charges G2 itself. A switch, so a new state must answer explicitly. */
    public static boolean needsItsOwnCharge(final PriorCharge prior) {
        switch (prior) {
            case NONE:
            case SPENT_AND_CONSUMED:
                return true;
            case SPENT_AND_UNCONSUMED:
            default:
                return false;
        }
    }

    public static String line(final PriorCharge prior) {
        switch (prior) {
            case NONE:
                return "nothing has been charged to the era budget in this recovery episode, so this "
                        + "re-creation pays for itself";
            case SPENT_AND_UNCONSUMED:
                return "the era budget was already charged in this episode and the server has not "
                        + "taken a re-creation on it — no member can have re-joined yet, so this is "
                        + "the SAME re-creation reached by a second door and it is not charged again";
            case SPENT_AND_CONSUMED:
            default:
                return "the era budget was already charged in this episode AND the server ACCEPTED "
                        + "the re-creation it paid for — a Welcome was minted per member and a "
                        + "KeyPackage claimed for each, whatever the server then did with the era. "
                        + "This is a SECOND peer-facing re-creation and it is charged again";
        }
    }
}

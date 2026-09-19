/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Test fixture: decides whether to withhold the publish of a rekey commit the engine has already
 * applied, putting this device a controlled number of epochs ahead of its group (the mirror of
 * {@link MlsInboundHold}). Only self-update commits are held. At capacity the commit is rolled back
 * and refused rather than published, since the server would refuse it anyway.
 */
public final class MlsOutboundHold {

    private MlsOutboundHold() { }

    /** Whether one outbound commit is withheld from the transport. */
    public enum Verdict {
        /** Not our fixture's business: publish normally. */
        PUBLISH,
        /** Withhold it; the engine has already applied it locally. */
        SUPPRESS,
        /** The store is full: roll the commit back and refuse it. */
        REFUSE,
    }

    /** How many suppressed commits the store holds; each entry carries a whole group snapshot. */
    public static final int CAPACITY = 8;

    /**
     * @param armed        the lever is armed on this device
     * @param scopeMatches this commit belongs to the one conversation the lever names
     * @param isRekey      this is a self-update commit
     * @param held         how many commits are already suppressed
     */
    public static Verdict decide(final boolean armed, final boolean scopeMatches,
            final boolean isRekey, final int held) {
        if (!armed || !scopeMatches || !isRekey) return Verdict.PUBLISH;
        return held >= CAPACITY ? Verdict.REFUSE : Verdict.SUPPRESS;
    }

    /**
     * Whether the snapshot restore put the state back: the engine's return value and the re-read
     * era and epoch must all agree. An unreadable reading (-1) is a failure.
     */
    public static boolean restoredCleanly(final boolean engineSaidRestored, final int expectedEra,
            final long expectedEpoch, final int actualEra, final long actualEpoch) {
        if (!engineSaidRestored) return false;
        if (expectedEra < 0 || actualEra < 0 || expectedEpoch < 0L
                || actualEpoch < 0L) return false;
        return expectedEra == actualEra && expectedEpoch == actualEpoch;
    }

    /**
     * The fixture's state, computed from the engine's era/epoch and the withheld count; nothing
     * here queries the server.
     */
    public static final class Gap {
        /** Our engine's current era and epoch, or -1 when unreadable. */
        public final int ourEra;
        public final long ourEpoch;
        /** The era and epoch recorded when the lever was armed, or -1. */
        public final int armEra;
        public final long armEpoch;
        /** Commits withheld from the transport. */
        public final int suppressed;
        /**
         * The group's epoch: ours minus the commits it never saw. Survives inbound commits but not
         * an era change; see {@link #eraMoved}.
         */
        public final long groupEpoch;
        /** How many epochs ahead of the group we are. -1 when it cannot be computed. */
        public final long epochsAhead;
        /** An era advance happened while held, so the arithmetic above no longer applies. */
        public final boolean eraMoved;
        /**
         * Our epoch advanced by exactly the number of commits withheld since arming; false means
         * something else moved it and a restore will not land where the release claims.
         */
        public final boolean consistent;

        Gap(final int ourEra, final long ourEpoch, final int armEra, final long armEpoch,
                final int suppressed, final long groupEpoch, final long epochsAhead,
                final boolean eraMoved, final boolean consistent) {
            this.ourEra = ourEra;
            this.ourEpoch = ourEpoch;
            this.armEra = armEra;
            this.armEpoch = armEpoch;
            this.suppressed = suppressed;
            this.groupEpoch = groupEpoch;
            this.epochsAhead = epochsAhead;
            this.eraMoved = eraMoved;
            this.consistent = consistent;
        }

        @Override public String toString() {
            return "ourEra=" + ourEra + " ourEpoch=" + ourEpoch
                    + " armedAt=(era=" + armEra + " epoch=" + armEpoch + ")"
                    + " suppressed=" + suppressed
                    + " groupEpoch=" + (groupEpoch < 0 ? "unknown" : Long.toString(groupEpoch))
                    + " epochsAhead=" + (epochsAhead < 0 ? "unknown" : Long.toString(epochsAhead))
                    + (eraMoved ? " ERA MOVED while held" : "")
                    + " consistent=" + consistent;
        }
    }

    /** Measure the fixture. No network. */
    public static Gap measure(final int armEra, final long armEpoch, final int ourEra,
            final long ourEpoch, final int suppressed) {
        final boolean eraMoved = armEra >= 0 && ourEra >= 0 && armEra != ourEra;
        final boolean readable = ourEpoch >= 0L && suppressed >= 0;
        final long groupEpoch = (!readable || eraMoved) ? -1L : ourEpoch - suppressed;
        final long ahead = (groupEpoch < 0L) ? -1L : suppressed;
        final boolean consistent = !eraMoved && readable && armEpoch >= 0L
                && ourEpoch - armEpoch == suppressed;
        return new Gap(ourEra, ourEpoch, armEra, armEpoch, suppressed, groupEpoch, ahead, eraMoved,
                consistent);
    }

    /** Parse a {@code --es mlsahead} verb. Null for anything unrecognised. */
    public static String verbOf(final String s) {
        if (s == null) return null;
        final String t = s.trim().toLowerCase(java.util.Locale.US);
        switch (t) {
            case "arm":
            case "status":
            case "disarm":
            case "release":
            case "publish":
            case "drop":
                return t;
            default:
                return null;
        }
    }
}

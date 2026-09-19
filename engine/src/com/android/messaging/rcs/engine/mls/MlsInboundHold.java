/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;
/**
 * Inbound parking under RCC.16 §10.8 (the G1 busy-group lock, G2 from-the-future admission, the
 * exact-key drain), plus a test fixture: a reversible hold on one conversation's handshake plane
 * that puts this device a controllable number of epochs behind its group. The fixture holds
 * commits (and, by default, proposals, so a release replays in order) and passes Welcomes, which
 * are the route back in; application messages never reach it. The durable half of the hold is the
 * app's {@code MlsInboundHoldStore}. See docs/mls/health-and-recovery.md.
 */
public final class MlsInboundHold {

    private MlsInboundHold() { }

    /** What an inbound control payload is, as far as the hold is concerned. */
    public enum Kind {
        /** Advances the epoch; the gap is made of these. */
        COMMIT,
        /** Does not advance the epoch, but a later commit may reference it by hash. */
        PROPOSAL,
        /** A join, re-add or era advance: the route home, passed unless {@link Mode#CONTROL}. */
        WELCOME,
        /** A readable MLSMessage that is neither ({@code application}, GroupInfo, KeyPackage). */
        OTHER,
        /** Could not be classified; always passed, so the fixture never changes the experiment. */
        UNREADABLE,
    }

    /** How much of the handshake plane to hold. */
    public enum Mode {
        /** Commits only; proposal traffic keeps flowing. */
        COMMIT,
        /** Commits and proposals: the default, and the only mode that replays cleanly. */
        HANDSHAKE,
        /** Handshake plus Welcomes: an era gap, forfeiting the re-Welcome route home. */
        CONTROL,
    }

    /** Whether one inbound control payload is held back from the engine. */
    public enum Verdict { PASS, HOLD }

    /**
     * Classify an inbound control payload without decrypting it.
     *
     * @param mlsBytes the bare MLS bytes the provider handed up (envelope already stripped)
     */
    public static Kind classify(final byte[] mlsBytes) {
        if (mlsBytes == null || mlsBytes.length == 0) return Kind.UNREADABLE;
        // Content type first: findWelcome tolerates wrapped blobs and could misread a bare commit
        // whose body happens to contain a Welcome prefix.
        final int ct = MlsWireScan.contentTypeOf(mlsBytes);
        if (ct == MlsWireScan.CONTENT_COMMIT) return Kind.COMMIT;
        if (ct == MlsWireScan.CONTENT_PROPOSAL) return Kind.PROPOSAL;
        if (ct == MlsWireScan.CONTENT_APPLICATION) return Kind.OTHER;
        if (MlsWireScan.findWelcome(mlsBytes) != null) return Kind.WELCOME;
        return MlsWireScan.isMlsMessage(mlsBytes) ? Kind.OTHER : Kind.UNREADABLE;
    }

    /**
     * Whether to hold one inbound control payload.
     *
     * @param scopeMatches this payload belongs to the one conversation the lever names
     * @param atCapacity the held store is full; see {@link #CAPACITY}
     */
    public static Verdict decide(final boolean armed, final boolean scopeMatches, final Mode mode,
            final Kind kind, final boolean atCapacity) {
        if (!armed || !scopeMatches || mode == null || kind == null) return Verdict.PASS;
        // At capacity pass, never drop: a far-future commit is parked by the pending queue, and the
        // gap just stops growing.
        if (atCapacity) return Verdict.PASS;
        switch (kind) {
            case COMMIT:
                return Verdict.HOLD;
            case PROPOSAL:
                return mode == Mode.COMMIT ? Verdict.PASS : Verdict.HOLD;
            case WELCOME:
                return mode == Mode.CONTROL ? Verdict.HOLD : Verdict.PASS;
            case OTHER:
            case UNREADABLE:
            default:
                return Verdict.PASS;
        }
    }

    /** Payloads held before the store passes them through; reaching it is reported. */
    public static final int CAPACITY = 64;

    /** The fixture's current gap, computed from the engine's epoch and the held commits. */
    public static final class Gap {
        /** Our engine's current epoch, or -1 if it could not be read. */
        public final long ourEpoch;
        /** How many held payloads are commits. */
        public final int heldCommits;
        /** The lowest / highest epoch stamped on a held commit, or -1 when none are held. */
        public final long lowestCommitEpoch;
        public final long highestCommitEpoch;
        /** The group's epoch: a held commit stamped {@code N} produces {@code N+1}; -1 if none. */
        public final long groupEpoch;
        /** {@link #groupEpoch} - {@link #ourEpoch}, or -1 when either is unknown. */
        public final long epochGap;
        /**
         * The held commits are exactly {@code ourEpoch, ourEpoch+1, ...} with no holes. A hole
         * means a commit went missing some other way, and replaying what we hold will not close the
         * gap.
         */
        public final boolean contiguous;

        Gap(final long ourEpoch, final int heldCommits, final long lowest, final long highest,
                final long groupEpoch, final long epochGap, final boolean contiguous) {
            this.ourEpoch = ourEpoch;
            this.heldCommits = heldCommits;
            this.lowestCommitEpoch = lowest;
            this.highestCommitEpoch = highest;
            this.groupEpoch = groupEpoch;
            this.epochGap = epochGap;
            this.contiguous = contiguous;
        }

        @Override public String toString() {
            return "ourEpoch=" + ourEpoch + " heldCommits=" + heldCommits
                    + " commitEpochs=" + (heldCommits == 0 ? "none"
                            : (lowestCommitEpoch + ".." + highestCommitEpoch))
                    + " groupEpoch=" + groupEpoch + " epochGap=" + epochGap
                    + " contiguous=" + contiguous;
        }
    }

    /**
     * Measures the fixture with no network: the held bytes carry every number needed, and a server
     * look-up is the scarce resource.
     *
     * @param ourEpoch the engine's current epoch, or -1 if unknown
     * @param heldCommitEpochs the epoch on each held commit in arrival order; -1 entries are
     *     counted but excluded from the arithmetic
     */
    public static Gap measure(final long ourEpoch, final long[] heldCommitEpochs) {
        final int n = heldCommitEpochs == null ? 0 : heldCommitEpochs.length;
        long lowest = -1;
        long highest = -1;
        int readable = 0;
        for (int i = 0; i < n; i++) {
            final long e = heldCommitEpochs[i];
            if (e < 0) continue;
            readable++;
            if (lowest < 0 || e < lowest) lowest = e;
            if (e > highest) highest = e;
        }
        final long groupEpoch = highest < 0 ? -1 : highest + 1;
        final long gap = (groupEpoch < 0 || ourEpoch < 0) ? -1 : groupEpoch - ourEpoch;
        final boolean contiguous = n > 0 && readable == n && ourEpoch >= 0 && lowest == ourEpoch
                && (highest - lowest + 1) == n && distinct(heldCommitEpochs);
        return new Gap(ourEpoch, n, lowest, highest, groupEpoch, gap, contiguous);
    }

    private static boolean distinct(final long[] xs) {
        for (int i = 0; i < xs.length; i++) {
            for (int j = i + 1; j < xs.length; j++) {
                if (xs[i] == xs[j]) return false;
            }
        }
        return true;
    }

    /** Parse a {@code --es holdmode} value; {@code dflt} for anything unrecognised or absent. */
    public static Mode modeOf(final String s, final Mode dflt) {
        if (s == null) return dflt;
        final String t = s.trim().toLowerCase(java.util.Locale.US);
        if ("commit".equals(t) || "commits".equals(t)) return Mode.COMMIT;
        if ("handshake".equals(t)) return Mode.HANDSHAKE;
        if ("control".equals(t) || "all".equals(t)) return Mode.CONTROL;
        return dflt;
    }
}

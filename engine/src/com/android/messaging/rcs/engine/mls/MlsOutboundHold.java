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
 * <b>TEST FIXTURE.</b> The decision half of the OUTBOUND commit hold — the lever that puts this
 * device an arbitrary, controllable number of epochs AHEAD of a group.
 *
 * <h2>The mirror of {@link MlsInboundHold}, and a DIFFERENT KIND of divergence</h2>
 *
 * <p>The inbound-hold lever holds INBOUND handshake, so the group advances and we do not: BEHIND. This
 * one suppresses the PUBLISH of a commit the engine has already applied locally, so we advance and
 * the group does not: AHEAD. {@code Health.AHEAD} is a real state with its own reconcile arm and was
 * just as unreachable on demand.
 *
 * <p>They are not symmetric, and the asymmetry decides what the release has to prove. The inbound
 * hold is recoverable because this device keeps the ONLY COPY of what it withheld — the bytes are in
 * the store and replaying them closes the gap. An unpublished local commit is a rung the group will
 * never have: nothing anywhere can hand it to them after the fact, and our own undo is purely local
 * — {@code restoreGroupSnapshot} of the state captured before the commit was applied. So the release
 * needs nothing from the server and nothing from a peer, and correspondingly it MUST MEASURE that
 * the restore actually happened. If it silently did not, this device sits permanently one epoch
 * ahead of a group that never saw the commit, which is recoverable only by a rebuild, a re-Welcome,
 * or an external commit — the expensive routes the fixture exists to avoid consuming.
 *
 * <p>{@link #restoredCleanly} is that measurement, and it is a function rather than a log line for
 * the reason this project keeps relearning: <b>a tool reporting success is not evidence.</b>
 * {@code restoreGroupSnapshot} returning true says the call did not fail; only comparing the era and
 * epoch against the values captured before the commit says the state went back.
 *
 * <h2>REKEY only</h2>
 *
 * <p>The lever fires on the self-update commit and on nothing else. A rekey is the minimal
 * epoch-advancing operation: no membership change, no RCS half, nothing on the other side of the
 * request to fall out of step. Suppressing an add or a remove would leave the RCS roster and the MLS
 * roster disagreeing, which is a different experiment with a different failure mode — and on an MLS
 * GROUP those two do not pass through this choke point at all (they ride
 * {@code addGroupUsersMls} / {@code removeGroupUsersMls}, not {@code applyMlsControl}).
 *
 * <h2>At capacity we REFUSE the commit, where the inbound lever PASSES it</h2>
 *
 * <p>The inbound lever passes a payload through once full, because dropping it would be a silent,
 * unrecoverable loss of someone else's message. Nothing here belongs to anyone else: a rekey is our
 * own housekeeping. Publishing the Nth commit while the server has not seen 1..N-1 would be refused
 * anyway (its base epoch authenticator names a state the server does not hold), so the honest
 * behaviour is to roll the commit back and refuse it. The gap stops growing and nothing is lost.
 */
public final class MlsOutboundHold {

    private MlsOutboundHold() { }

    /** Whether one outbound commit is withheld from the transport. */
    public enum Verdict {
        /** Not our fixture's business: publish normally. */
        PUBLISH,
        /** Withhold it. The engine has already applied it locally; the group will never see it. */
        SUPPRESS,
        /** The store is full. Roll the commit back and refuse it — see the class javadoc. */
        REFUSE,
    }

    /**
     * How many suppressed commits the store will hold.
     *
     * <p>Much smaller than {@link MlsInboundHold#CAPACITY}, deliberately. Each entry here carries a
     * whole group SNAPSHOT rather than one wire payload, and the largest AHEAD gap anyone has wanted
     * to study is a handful of epochs. A fixture that can silently accumulate megabytes of engine
     * state in {@code SharedPreferences} is a fixture that fails in a way nothing reports.
     */
    public static final int CAPACITY = 8;

    /**
     * The decision.
     *
     * @param armed        the lever is armed on this device
     * @param scopeMatches this commit belongs to the ONE conversation the lever names
     * @param isRekey      this is a self-update commit; see the class javadoc for why only those
     * @param held         how many commits are already suppressed
     */
    public static Verdict decide(final boolean armed, final boolean scopeMatches,
            final boolean isRekey, final int held) {
        if (!armed || !scopeMatches || !isRekey) return Verdict.PUBLISH;
        return held >= CAPACITY ? Verdict.REFUSE : Verdict.SUPPRESS;
    }

    /**
     * Did the snapshot restore actually put the state back?
     *
     * <p><b>Every input is a MEASUREMENT, and the boolean is not enough on its own.</b>
     * {@code engineSaidRestored} is what {@code restoreGroupSnapshot} returned — that the call did
     * not fail. The era and epoch are read from the engine before and after. A release may report
     * success only when all three agree, because the failure this guards against is exactly the one
     * where the call succeeds and the state does not move.
     *
     * <p>An UNREADABLE reading (-1 from {@code eraFrom} / {@code epochFrom}) is a failure here, not a
     * pass. "I could not tell" must not be spelled the same way as "it went back".
     */
    public static boolean restoredCleanly(final boolean engineSaidRestored, final int expectedEra,
            final long expectedEpoch, final int actualEra, final long actualEpoch) {
        if (!engineSaidRestored) return false;
        if (expectedEra < 0 || actualEra < 0 || expectedEpoch < 0L || actualEpoch < 0L) return false;
        return expectedEra == actualEra && expectedEpoch == actualEpoch;
    }

    /**
     * What the fixture IS right now, computed from readings rather than from the arm's intent.
     *
     * <p>Every field is derived from the engine's own era/epoch and the count of commits we withheld.
     * Nothing here asks the server, for the fetch budget's reason: a {@code GetMlsGroupInfo} is the
     * scarce resource, and a status read a test takes repeatedly must not spend one.
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
         * The epoch the GROUP is at: ours minus the commits it never saw.
         *
         * <p>Exact across INBOUND traffic as well as ours — a peer's commit advances both sides by
         * one, so the difference is unchanged. What it cannot survive is an ERA change, which resets
         * the epoch counter; {@link #eraMoved} reports that rather than letting the subtraction lie.
         */
        public final long groupEpoch;
        /** How many epochs ahead of the group we are. -1 when it cannot be computed. */
        public final long epochsAhead;
        /** An era advance happened while held, so the arithmetic above no longer applies. */
        public final boolean eraMoved;
        /**
         * Our epoch advanced by exactly the number of commits we withheld.
         *
         * <p>The assertion that makes the fixture trustworthy: if it is false, something OTHER than
         * this lever moved our epoch, so the gap is real but the lever did not make all of it — and
         * restoring the stashed snapshot will not land where the release claims.
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

    /** Measure the fixture. No network; see the class javadoc. */
    public static Gap measure(final int armEra, final long armEpoch, final int ourEra,
            final long ourEpoch, final int suppressed) {
        final boolean eraMoved = armEra >= 0 && ourEra >= 0 && armEra != ourEra;
        final boolean readable = ourEpoch >= 0L && suppressed >= 0;
        final long groupEpoch = (!readable || eraMoved) ? -1L : ourEpoch - suppressed;
        final long ahead = (groupEpoch < 0L) ? -1L : suppressed;
        // CONSISTENT means our epoch moved by exactly the number of commits we withheld, measured
        // against the epoch recorded at arm time. Nothing is inferred from "we armed it and then N
        // things happened" — that is the shape that let an earlier one-shot arm report success while
        // doing nothing.
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

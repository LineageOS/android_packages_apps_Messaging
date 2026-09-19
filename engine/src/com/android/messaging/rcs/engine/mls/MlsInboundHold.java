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
 * <b>TEST FIXTURE.</b> The decision half of the inbound-control hold — the lever that puts this
 * device an arbitrary, controllable, reversible number of epochs BEHIND a group.
 *
 * <h2>Why a lever exists at all</h2>
 *
 * <p>Nearly every recovery mechanism we have built is reachable only on a group that is actually
 * diverged: the designated-advancer election and its bounded yield and takeover, the
 * drive loop and its fetch budget, the rebuild rung and the era-advance fallback
 *. Producing that state by improvisation costs a group every time — recovery either
 * repairs the divergence or abandons it, so a natural fixture is single-use — and four groups were
 * consumed that way in one day. The one-shot {@code failnextdecrypt} arm could not do it either: it
 * races everything else on the wire and was consumed by unrelated inbound before the commit arrived.
 *
 * <h2>WHICH inbound is held, and why not the others</h2>
 *
 * <p>The whole class of this decision is "hold the HANDSHAKE plane for one named conversation, pass
 * everything else":
 *
 * <ul>
 *   <li><b>Commits are held.</b> A commit is the only thing that advances an epoch, so holding
 *       commits is what makes the gap, and the count of held commits IS the size of the gap. That is
 *       the property a test can assert instead of assume.</li>
 *   <li><b>Proposals are held with them</b> ({@link Mode#HANDSHAKE}, the default). A by-reference
 *       proposal is committed later by hash: hold the commit but pass the proposal and the group is
 *       coherent; pass the proposal but hold the commit and it is still coherent; hold the commit
 *       and pass a proposal that the NEXT commit references, and on release that commit fails
 *       validation against a proposal store we filled out of order. Freezing the plane as a unit is
 *       the only shape that replays.</li>
 *   <li><b>Welcomes are PASSED by default</b>, and this is the load-bearing exclusion. A Welcome is
 *       an era advance or a re-add — i.e. it is the mechanism by which a behind member gets back in.
 *       With no commit backfill, and with external commit refused by our own profile
 *       ({@code acceptsMemberExternalCommit=false}), another member re-Welcoming us is the route
 *       home in practice. Holding Welcomes by default would build the fixture out of the thing that
 *       repairs it. {@link Mode#CONTROL} holds them too, for the deliberate era-gap case, and says
 *       so loudly.
 *
 *       <p>CORRECTED: this used to cite an earlier reading — "the server's GroupInfo
 *       carries no external_pub" — which was never a measurement. The reader answered ABSENT for
 *       any non-continuity code point without calling native. With it fixed, {@code external_pub}
 *       measures 67 B on 2 of 3 groups we hold, including the Google Messages-created one. The exclusion is
 *       unchanged, because it never rested on that: a Welcome is the route home whether or not an
 *       external commit is also possible, and the fixture must not consume it.</li>
 *   <li><b>Application messages are never held</b>, because they never reach this decision — they
 *       arrive on the ciphertext path, not the control path. That is the right answer as well as the
 *       structural one: an application message we cannot decrypt because we are behind is precisely
 *       the §10 trigger the recovery ladder is supposed to answer, so silencing it would make the
 *       fixture inert. Holding a group's whole traffic would also leave us behind, but it would be a
 *       device that has gone dark rather than a member that has missed commits.</li>
 * </ul>
 *
 * <h2>Pure on purpose</h2>
 *
 * <p>Everything here is a function of bytes and numbers, so the classification and the gap arithmetic
 * are host-testable without a device — which matters more than usual for a fixture, because a fixture
 * that is wrong produces experiments that are wrong and nothing reports an error. The durable half
 * (what is held, and replaying it) is {@code MlsInboundHoldStore} on the app side, where the
 * SharedPreferences and the transport live.
 */
public final class MlsInboundHold {

    private MlsInboundHold() { }

    /** What an inbound control payload is, as far as the hold is concerned. */
    public enum Kind {
        /** Advances the epoch. The thing the gap is made of. */
        COMMIT,
        /** Does not advance the epoch, but a later commit may reference it by hash. */
        PROPOSAL,
        /** A join, a re-add or an era advance — the route home, passed unless {@link Mode#CONTROL}. */
        WELCOME,
        /** A readable MLSMessage that is neither ({@code application}, GroupInfo, KeyPackage). */
        OTHER,
        /**
         * The bytes could not be classified.
         *
         * <p><b>Always passed.</b> A fixture that swallows what it cannot identify is a fixture that
         * silently changes the experiment; and a payload we cannot read is exactly the case where a
         * guess is worth least.
         */
        UNREADABLE,
    }

    /** How much of the handshake plane to hold. */
    public enum Mode {
        /** Commits only. The narrowest gap-maker; leaves proposal traffic flowing. */
        COMMIT,
        /** Commits and proposals — the default, and the only mode that replays cleanly. */
        HANDSHAKE,
        /** Handshake plus Welcomes. Produces an ERA gap, and forfeits the re-Welcome route home. */
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
        // THE CONTENT TYPE FIRST, THE WELCOME SEARCH SECOND, and the order is deliberate.
        //
        // findWelcome does not test bytes 0-3; it descends length-delimited protobuf fields and then
        // scans a short prefix window, because an era-advance Welcome legitimately arrives wrapped
        // (Apple's kind=5 add carries the ratchet tree alongside it in the same blob). That
        // tolerance is right for its job and wrong as a FIRST question here: a bare Commit whose
        // body happened to contain the four bytes 00 01 00 03 in a walkable position would be
        // classified WELCOME and PASSED — a commit the fixture was armed to hold, let through with
        // nothing logged as unusual.
        //
        // A blob that is a bare PublicMessage/PrivateMessage at offset 0 with a readable content
        // type IS that thing, so asking that first removes the ambiguity entirely, and the Welcome
        // search keeps the wrapped cases it exists for.
        final int ct = MlsWireScan.contentTypeOf(mlsBytes);
        if (ct == MlsWireScan.CONTENT_COMMIT) return Kind.COMMIT;
        if (ct == MlsWireScan.CONTENT_PROPOSAL) return Kind.PROPOSAL;
        if (ct == MlsWireScan.CONTENT_APPLICATION) return Kind.OTHER;
        if (MlsWireScan.findWelcome(mlsBytes) != null) return Kind.WELCOME;
        // A readable MLSMessage whose content type we could not walk to is not the same as a blob
        // that is not an MLSMessage at all — but both are PASS, so they share an arm.
        return MlsWireScan.isMlsMessage(mlsBytes) ? Kind.OTHER : Kind.UNREADABLE;
    }

    /**
     * The decision itself.
     *
     * @param armed        the lever is armed on this device
     * @param scopeMatches this payload belongs to the ONE conversation the lever names
     * @param atCapacity   the held store is full — see {@link #CAPACITY}
     */
    public static Verdict decide(final boolean armed, final boolean scopeMatches, final Mode mode,
            final Kind kind, final boolean atCapacity) {
        if (!armed || !scopeMatches || mode == null || kind == null) return Verdict.PASS;
        // AT CAPACITY WE PASS, WE DO NOT DROP. Passing a commit that is far in the future does not
        // close the gap — §10.8's pending queue parks it at its own moment, un-capped by design —
        // so the fixture survives, the message survives, and the gap simply stops growing. Dropping
        // instead would be a silent, unrecoverable loss committed by the instrument, which is the
        // one thing a fixture must never do to the state it is measuring.
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

    /**
     * How many payloads the store will hold before it starts passing them through.
     *
     * <p>A bound is needed because the held blobs are persisted and the group can commit while nobody
     * is watching. The number is generous relative to any fixture anyone has wanted — the largest gap
     * this project has ever observed in the wild is 6 (on a real group, local epoch 1 vs
     * server epoch 7) — and reaching it is reported rather than silent.
     */
    public static final int CAPACITY = 64;

    /**
     * What the fixture actually is right now, computed from measurements rather than from the arm's
     * intent.
     *
     * <p>Every field here is read off something: the engine's own epoch, and the epochs the held
     * commits carry on the wire. Nothing is inferred from "we armed it and then N things happened",
     * which is the shape that let the earlier one-shot arm report success while doing nothing.
     */
    public static final class Gap {
        /** Our engine's current epoch, or -1 if it could not be read. */
        public final long ourEpoch;
        /** How many held payloads are commits. */
        public final int heldCommits;
        /** The lowest / highest epoch stamped on a held commit, or -1 when none are held. */
        public final long lowestCommitEpoch;
        public final long highestCommitEpoch;
        /**
         * The epoch the GROUP is at, derived from the held commits: a commit stamped {@code N} is a
         * commit made AT epoch {@code N} that produces epoch {@code N+1}. -1 when nothing is held.
         */
        public final long groupEpoch;
        /** {@link #groupEpoch} - {@link #ourEpoch}, or -1 when either is unknown. */
        public final long epochGap;
        /**
         * The held commits are exactly {@code ourEpoch, ourEpoch+1, … } with no holes.
         *
         * <p>This is the assertion that makes the fixture trustworthy. A hole means a commit went
         * missing by some route OTHER than this lever — so the gap is real but the lever did not make
         * all of it, and replaying what we hold will not close it.
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
     * Measure the fixture from the engine's epoch and the epochs the held commits carry.
     *
     * <p><b>No network.</b> The obvious way to report a gap is to ask the server for its era/epoch,
     * and that is exactly what the fetch budget shows we cannot afford to do casually: a look-up is the
     * scarce resource, and a recovery run that has already spent its budget is one
     * {@code RESOURCE_EXHAUSTED} away from looking like a server-side wall. The held bytes already
     * carry every number needed, so a status read costs nothing and can be taken as often as a test
     * likes.
     *
     * @param ourEpoch          the engine's current epoch, or -1 if unknown
     * @param heldCommitEpochs  the epoch stamped on each held COMMIT, in arrival order; may contain
     *                          -1 for a commit whose epoch could not be read, and those are counted
     *                          but excluded from the arithmetic
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
        // CONTIGUOUS means the held set covers ourEpoch..highest with no repeats and no holes, which
        // for a set of distinct epochs is exactly "as many readable commits as the span".
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

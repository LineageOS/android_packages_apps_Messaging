/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.log.LogMask;
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

    /**
     * A log suffix for the two sites that write {@code EraAdvancementRequested}: that state parks
     * all inbound. It records that an era advance is the remedy and does not perform one; it is
     * left when an outbound send restarts the heal or when {@link MlsSelfHealPass#parksExhausted}
     * kills it. Computed from the record the caller already wrote.
     */
    public static String inboundNowParked(final MlsConversationRecord written) {
        if (written == null || !MlsPendingQueue.groupLocked(written.healthStatus)) return "";
        return " AND THE CONVERSATION HAS STOPPED RECEIVING: "
                + MlsHealthStates.name(written.healthStatus) + " is inside §10.8's G1 mask, so "
                + "every inbound message for it is now parked. This escalation RECORDS that an era "
                + "advance is the remedy; it does NOT perform one. It leaves this state when an "
                + "outbound send restarts the heal, or when enough inbound parks accumulate at an "
                + "unchanged moment for MlsSelfHealPass.parksExhausted to unstick it. Until one of "
                + "those happens the conversation is silently one-way.";
    }

    /**
     * Counts one park at the group's moment and returns the run of consecutive parks without the
     * group moving. Per conversation, not per plane, so mixed traffic cannot keep each half under
     * the threshold.
     */
    public static int noteParkAtUnchangedMoment(final MlsShellPort shell, final String key,
            final MlsAppMessage.Moment group) {
        final ConvState cs = shell.conv(key);
        synchronized (cs) {
            if (group != null && group.equals(cs.lastParkGroupMoment)) {
                return ++cs.consecutiveParksAtSameMoment;
            }
            cs.lastParkGroupMoment = group;
            return cs.consecutiveParksAtSameMoment = 1;
        }
    }

    /** Resets the park run; called when it has been acted on. */
    public static void clearParksAtUnchangedMoment(final MlsShellPort shell, final String key) {
        final ConvState cs = shell.conv(key);
        synchronized (cs) { cs.consecutiveParksAtSameMoment = 0; }
    }

    /**
     * Records the group moment an application decrypt failed at. Called under the conversation
     * lock by the decrypt, for {@link #parkFutureCiphertext} to take.
     */
    public static void noteDecryptFailure(final MlsShellPort shell, final String key,
            final String messageId, final MlsAppMessage.Moment at) {
        if (key == null || messageId == null || at == null) return;
        final ConvState cs = shell.conv(key);
        synchronized (cs) { cs.decryptFailedAt.put(messageId, at); }
    }

    /** Removes and returns the moment {@link #noteDecryptFailure} recorded, or null. */
    static MlsAppMessage.Moment takeDecryptFailure(final MlsShellPort shell, final String key,
            final String messageId) {
        if (key == null || messageId == null) return null;
        final ConvState cs = shell.conv(key);
        synchronized (cs) { return cs.decryptFailedAt.remove(messageId); }
    }

    /** What {@link #park} did with one entry. */
    public enum ParkOutcome {
        STORED,
        /** Already in its bucket. */
        DUPLICATE,
        /** A store-time validation failed; logged with its error ordinal. */
        REFUSED,
        /** No group or no identity; nothing was read or written. */
        NO_GROUP,
        /**
         * Not stored: while this thread waited for the conversation lock, the group moved past the
         * reason to park (a from-the-future entry's moment was reached, or a busy group moved or
         * left the G1 mask). The drain for that moment has already run, so a stored entry would
         * wait for a drain that never comes. The caller processes the message now.
         */
        OVERTAKEN,
    }

    /** Parks an inbound control that failed and is strictly from the future (RCC.16 §10.8 G2). */
    public static void bufferFromFuture(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId, final String messageId,
            final byte[] mlsBytes, final String fromE164) {
        final Group g = shell.getGroup(conversationId);
        if (g == null || g.groupId == null) return;
        final MlsAppMessage.Moment group =
                MlsAppMessage.Moment.from(shell.session().eraEpoch(g.groupId));
        final MlsAppMessage.Moment msg = MlsAppMessage.inboundMoment(mlsBytes, group);
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, conversationId);
        final int status = rec == null ? MlsHealthStates.UNKNOWN : rec.healthStatus;

        final MlsPendingQueue.Admission a = MlsPendingQueue.admit(status, msg, group,
                /*recoveredAt=*/ rec == null ? null : rec.recoveredAt,
                /*processingFailed=*/ true, /*outOfOrderCommit=*/ true, /*isFromFuture=*/ true,
                /*resentMessageForMeFtd=*/ false, /*sameApplicationMessageFailing=*/ false);
        if (!a.admitted()) {
            // Strictly later only; a message at or behind our moment takes the failure path.
            log.i("MlsInboundHold: inbound control from " + LogMask.number(fromE164)
                    + " failed and "
                    + "is NOT strictly from the future (msg=" + msg + " group=" + group + ") — not "
                    + "queued; this is an error, not a deferral");
            return;
        }
        if (shell.park(conversationId, messageId, mlsBytes, msg, a, fromE164,
                MlsPendingQueue.Plane.CONTROL) == ParkOutcome.OVERTAKEN) {
            replayNow(shell, log, conversationId, g.rcsGroupId, new MlsPendingQueue.Entry(
                    messageId, msg, MlsWireScan.wireFormatOf(mlsBytes), g.groupId, mlsBytes,
                    MlsPendingQueue.Plane.CONTROL, fromE164), fromE164);
        }
    }

    /**
     * Parks an application ciphertext that is strictly from the future instead of reporting an FTD
     * (RCC.16 §10.8). The transport's Era-ID makes an era-crossing message visible, since a new era
     * restarts the epoch.
     *
     * @return true if it was parked and the FTD path must not run
     */
    public static boolean parkFutureCiphertext(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String messageId,
            final byte[] ciphertext, final long eraId, final String rcsGroupId,
            final String fromE164) {
        final Group g = shell.getGroup(key);
        if (g == null || g.groupId == null) return false;
        final MlsAppMessage.Moment group =
                MlsAppMessage.Moment.from(shell.session().eraEpoch(g.groupId));
        if (group == null) return false;
        final long epoch = MlsWireScan.epochOf(ciphertext);
        if (epoch < 0) return false;                 // not a group message, or truncated
        final int era = eraId >= 0 ? (int) eraId : group.era;
        final MlsAppMessage.Moment msg = new MlsAppMessage.Moment(era, epoch);
        // Admit against the moment the decrypt failed at: a commit that landed since would make a
        // readable message look current and send it down the FTD path. park() re-checks.
        final MlsAppMessage.Moment failedAt = takeDecryptFailure(shell, key, messageId);
        final MlsAppMessage.Moment seen = failedAt != null ? failedAt : group;

        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        final int status = rec == null ? MlsHealthStates.UNKNOWN : rec.healthStatus;
        final MlsPendingQueue.Admission a = MlsPendingQueue.admit(status, msg, seen,
                /*recoveredAt=*/ rec == null ? null : rec.recoveredAt,
                /*processingFailed=*/ true, /*outOfOrderCommit=*/ false, /*isFromFuture=*/ true,
                /*resentMessageForMeFtd=*/ false, /*sameApplicationMessageFailing=*/ false);
        if (!a.admitted()) {
            // A ciphertext from a superseded era can never decrypt (prior-era secrets are gone on
            // every member), so the FTD is the remedy; say so, since it lands just after a
            // recovery.
            if (MlsPendingQueue.isFromASupersededEra(msg, group)) {
                log.i("MlsInboundHold: " + MlsMessageId.forLog(messageId) + " from "
                        + LogMask.number(fromE164)
                        + " was sealed in a SUPERSEDED ERA (msg=" + msg + " group=" + group + ") — "
                        + "it is not decryptable and never will be, and that is by construction, not "
                        + "a fault of this group's state. Do NOT read this as evidence the recovery "
                        + "that advanced the era broke something. Reporting it (§6.2 FTD) is the "
                        + "remedy: the sender resends at the current era.");
            }
            return false;                            // not from the future: the FTD path is correct
        }

        // Log the admission verdict, not the caller's isFromFuture flag.
        log.i("MlsInboundHold: the ciphertext " + MlsMessageId.forLog(messageId) + " from "
                + LogMask.number(fromE164)
                + " is PARKED as " + a + " (msg=" + msg + " group=" + group + ") rather than "
                + "reporting an FTD. It is retried when the group reaches that moment; telling the "
                + "sender we cannot read a message we may yet be able to read would provoke a "
                + "pointless resend."
                + (MlsPendingQueue.Admission.FROM_FUTURE.equals(a) ? ""
                        : " NOTE: parked for a reason OTHER than being from the future — compare "
                                + "the two moments above before assuming this message is ahead of us."));
        // The only application-plane park; see Plane.
        if (shell.park(key, messageId, ciphertext, msg, a, fromE164,
                MlsPendingQueue.Plane.APPLICATION) == ParkOutcome.OVERTAKEN) {
            // The commit that makes it readable landed, and drained, while we waited for the lock.
            replayNow(shell, log, key, rcsGroupId, new MlsPendingQueue.Entry(messageId, msg,
                    MlsWireScan.wireFormatOf(ciphertext), g.groupId, ciphertext,
                    MlsPendingQueue.Plane.APPLICATION, fromE164), fromE164);
            return true;
        }

        // An era gap needs a Welcome we cannot obtain ourselves, so keep the park and also report:
        // silence would leave the conversation one-way with the sender believing it delivered. This
        // goes beyond RCC.16 §10.8, which assumes the awaited moment arrives.
        if (!MlsPendingQueue.awaitedMomentIsReachable(msg, group)) {
            log.w("MlsInboundHold: " + MlsMessageId.forLog(messageId) + " is an ERA ahead (msg="
                    + msg
                    + " group=" + group
                    + ") — parked, but REPORTING anyway. We cannot reach that era "
                    + "by ourselves (it needs a Welcome only the peer or server can send), so staying "
                    + "silent would leave this conversation permanently one-way with the sender "
                    + "believing it is delivered.");
            return false;                            // park kept; let the §6.2 FTD path run
        }
        // An epoch gap closes only while a heal runs; a yielding heal advances only when something
        // looks, and parking returns first. Repeated parks at an unchanged moment kill the heal, so
        // the group leaves the buffering mask and the FTD path runs. Shared with the control plane.
        final int parksHere = MlsInboundHold.noteParkAtUnchangedMoment(shell, key, group);
        if (MlsSelfHealPass.parksExhausted(parksHere)) {
            log.w("MlsInboundHold: " + MlsConversationKey.forLog(key) + " has parked " + parksHere
                    + " messages at an UNCHANGED group moment " + group + " while health is "
                    + MlsHealthStates.name(status)
                    + ". The heal that would close this epoch gap is "
                    + "not progressing, so 'we may yet be able to read it' has stopped being true. "
                    + "Killing the heal so the group leaves the buffering mask and the honest FTD "
                    + "path can run.");
            MlsInboundHold.clearParksAtUnchangedMoment(shell, key);
            MlsRecoveryPolicy.killSelfHeal(shell, log, key, "parked " + parksHere
                    + " messages at an unchanged moment " + group);
            return false;                            // park kept; let the §6.2 FTD path run
        }
        return true;
    }

    /**
     * Parks an inbound control before decrypt if the group is mid-transition (RCC.16 §10.8 G1).
     *
     * @return true if the message was parked and must not be processed
     */
    public static boolean bufferInboundIfGroupLocked(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId, final String messageId,
            final byte[] mlsBytes, final String fromE164) {
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, conversationId);
        if (rec == null || !MlsPendingQueue.groupLocked(rec.healthStatus)) return false;

        // A commit at our current epoch is applied, not parked: applying it is the repair, and the
        // server serves no commit backfill, so a skipped commit is lost for good.
        final Group lg = shell.getGroup(conversationId);
        final MlsAppMessage.Moment nowMoment = (lg == null || lg.groupId == null)
                ? null : MlsAppMessage.Moment.from(shell.session().eraEpoch(lg.groupId));
        final long msgEpoch = MlsWireScan.epochOf(mlsBytes);
        if (nowMoment != null && msgEpoch >= 0 && msgEpoch == nowMoment.epoch) {
            log.i("MlsInboundHold: " + MlsMessageId.forLog(messageId) + " is at our CURRENT epoch "
                    + msgEpoch + " and the group is health-locked ("
                    + MlsHealthStates.name(rec.healthStatus) + ") — applying it anyway rather than "
                    + "parking it. It is the next rung of the chain, so applying it IS the repair; "
                    + "parking it lets the group move on without us, and the server does not backfill "
                    + "commits, so what we skip here we can never get back.");
            // Kill the heal as well: the engine buffers on the same status. A real operation
            // pre-empts the heal, as it does in other clients.
            MlsRecoveryPolicy.killSelfHeal(shell, log, conversationId,
                    "an applicable commit arrived at our current epoch "
                    + msgEpoch + " — applying it is better than the heal that is blocking it");
            return false;
        }

        // Park at the group's moment (G1 means we are busy), taken from the engine, since the drain
        // looks up by the engine's moment.
        final MlsAppMessage.Moment parkAt = nowMoment != null ? nowMoment : rec.moment;

        // The same park-run bound as the application plane. The kill leaves the mask only from
        // states with a SelfHealKilled edge ({1, 2, 4, 7} of the buffering set), so the release is
        // checked below. This is the automatic way out of EraAdvancementRequested; an era advance
        // is deliberately not.
        final int parksHere =
                MlsInboundHold.noteParkAtUnchangedMoment(shell, conversationId, parkAt);
        if (MlsSelfHealPass.parksExhausted(parksHere)) {
            log.w("MlsInboundHold: " + MlsConversationKey.forLog(conversationId) + " has parked "
                    + parksHere
                    + " CONTROL messages at an UNCHANGED group moment " + parkAt + " while health "
                    + "is " + MlsHealthStates.name(rec.healthStatus) + ", which is inside §10.8's "
                    + "G1 buffering mask. This conversation is RECEIVING NOTHING and nothing has "
                    + "moved it, so 'we may yet be able to read it' has stopped being true. Killing "
                    + "the heal to leave the mask.");
            MlsInboundHold.clearParksAtUnchangedMoment(shell, conversationId);
            MlsRecoveryPolicy.killSelfHeal(shell, log, conversationId, "parked " + parksHere
                    + " control messages at an unchanged "
                    + "moment " + parkAt);
            // Read the record back rather than trusting the kill.
            final MlsConversationRecord after =
                    MlsRecordState.recordFor(shell, log, conversationId);
            if (after != null && !MlsPendingQueue.groupLocked(after.healthStatus)) {
                log.i("MlsInboundHold: " + MlsConversationKey.forLog(conversationId)
                        + " left the G1 mask ("
                        + MlsHealthStates.name(rec.healthStatus) + " → "
                        + MlsHealthStates.name(after.healthStatus) + ") — processing "
                        + MlsMessageId.forLog(messageId)
                        + " INLINE rather than parking it. Not parked deliberately: the drain is an "
                        + "exact-key take, so an entry filed at a moment the group then leaves is "
                        + "never replayed.");
                return false;
            }
            log.w("MlsInboundHold: " + MlsConversationKey.forLog(conversationId)
                    + " is STILL inside the G1 "
                    + "mask after the kill — health is "
                    + MlsHealthStates.name(after == null ? MlsHealthStates.UNKNOWN
                            : after.healthStatus)
                    + ", which has no SelfHealKilled edge out (§5.3 offers one only from "
                    + "EpochAdvancementRequested, OngoingEpochAdvancement, OngoingEraAdvancement, "
                    + "EraAdvancementRequested and CannotHealDuringEndMls). Parking "
                    + MlsMessageId.forLog(messageId)
                    + " as before — this conversation cannot be unstuck from the inbound path and "
                    + "needs a deliberate action.");
        }
        if (shell.park(conversationId, messageId, mlsBytes, parkAt,
                MlsPendingQueue.Admission.GROUP_LOCKED, fromE164, MlsPendingQueue.Plane.CONTROL)
                == ParkOutcome.OVERTAKEN) {
            // Filed at a moment the group has left, it would never be drained; process it inline.
            return false;
        }
        return true;
    }

    /**
     * Drains only the entries parked at exactly the current {@code (era, epoch)} (RCC.16 §10.8).
     * A sweep would retry later-moment entries early, and each failure would count toward
     * stranding a recoverable conversation.
     */
    public static void drainDeferredControl(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId, final String fromE164, final String rcsGroupId) {
        final Group g = shell.getGroup(conversationId);
        if (g == null || g.groupId == null) return;
        final MlsAppMessage.Moment now =
                MlsAppMessage.Moment.from(shell.session().eraEpoch(g.groupId));
        if (now == null) return;
        final String self = shell.selfE164();
        if (self.isEmpty()) return;

        final List<MlsPendingQueue.Entry> superseded;
        final List<MlsPendingQueue.Entry> taken;
        final int left;
        // Under the lock, as park() is: the Welcome and downgrade callers do not hold it, and an
        // unguarded load-take-store would write back over an entry parked in between.
        shell.lock(conversationId);
        try {
            final MlsPendingQueue q = shell.pendingQueue().load(self, g.groupId);
            superseded = q.takeSupersededEras(now.era);
            taken = q.take(now);
            if (taken.isEmpty() && superseded.isEmpty()) return;
            // Write back before replaying, or a replay re-entering here would find the bucket
            // again.
            shell.pendingQueue().store(self, g.groupId, q);
            left = q.size();
        } finally { shell.unlock(conversationId); }
        reportDropped(shell, log, conversationId, rcsGroupId, superseded,
                "parked in an era before " + now.era);
        if (taken.isEmpty()) return;

        log.i("MlsInboundHold: taking " + taken.size() + " pending message(s) at "
                + "the exact key " + now + " for " + MlsConversationKey.forLog(conversationId)
                + " (" + left
                + " still parked at other moments)");
        replay(shell, conversationId, rcsGroupId, taken, fromE164, now);
    }

    /**
     * Processes one message whose park was {@link ParkOutcome#OVERTAKEN}, through the door the
     * drain would have used.
     */
    static void replayNow(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId, final String rcsGroupId, final MlsPendingQueue.Entry e,
            final String fromE164) {
        final Group g = shell.getGroup(conversationId);
        final MlsAppMessage.Moment now = (g == null || g.groupId == null) ? null
                : MlsAppMessage.Moment.from(shell.session().eraEpoch(g.groupId));
        log.i("MlsInboundHold: " + MlsMessageId.forLog(e.messageId) + " was to be parked at "
                + e.moment + " but the group reached " + now + " while it waited for the lock, "
                + "and the drain for that moment has run. Processing it NOW rather than storing "
                + "it where no drain will look.");
        replay(shell, conversationId, rcsGroupId, java.util.Collections.singletonList(e),
                fromE164, now);
    }

    /**
     * Replays taken entries in order, each through the door it arrived by and as its recorded
     * sender; {@code fallbackSender} only for an entry with none (codec v1/v2).
     */
    private static void replay(final MlsShellPort shell, final String conversationId,
            final String rcsGroupId, final List<MlsPendingQueue.Entry> taken,
            final String fallbackSender, final MlsAppMessage.Moment now) {
        // Stamped before the replay, so nothing it reaches can schedule more.
        MlsDriveLoop.stampScheduling(shell, conversationId, MlsSchedulingType.RETRY_FLOW);
        try {
            // Insertion order, which is why the bucket is a List and not a Set.
            for (final MlsPendingQueue.Entry e : taken) {
                // Replayed as its own sender: the drain runs on whichever member's commit arrived,
                // and the application door refuses a signer that is not the named sender.
                final String from = e.sender != null ? e.sender : fallbackSender;
                // Route by plane: the control door would discard an application plaintext, and only
                // the application door runs the AAD, sender and rendezvous checks.
                if (e.plane == MlsPendingQueue.Plane.APPLICATION) {
                    shell.replayParkedApplication(conversationId, from, rcsGroupId, e, now);
                } else {
                    shell.applyInboundControl(from, e.messageId, e.blob,
                            /*convergenceAck=*/ false,
                            rcsGroupId);
                }
            }
        } finally {
            MlsDriveLoop.stampScheduling(shell, conversationId, MlsSchedulingType.NORMAL);
        }
    }

    /**
     * Drops the entries parked in eras the group has left and reports the application ones. The
     * exact-key drain never reaches them, and they can never decrypt; see
     * {@link MlsPendingQueue#takeSupersededEras}. Called after our own era advance, which drains
     * nothing.
     *
     * @return how many application entries were reported
     */
    public static int pruneSupersededEras(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId, final String rcsGroupId) {
        final Group g = shell.getGroup(conversationId);
        if (g == null || g.groupId == null) return 0;
        final MlsAppMessage.Moment now =
                MlsAppMessage.Moment.from(shell.session().eraEpoch(g.groupId));
        if (now == null) return 0;
        final String self = shell.selfE164();
        if (self.isEmpty()) return 0;
        final List<MlsPendingQueue.Entry> superseded;
        shell.lock(conversationId);
        try {
            final MlsPendingQueue q = shell.pendingQueue().load(self, g.groupId);
            superseded = q.takeSupersededEras(now.era);
            if (superseded.isEmpty()) return 0;
            shell.pendingQueue().store(self, g.groupId, q);
        } finally { shell.unlock(conversationId); }
        return reportDropped(shell, log, conversationId, rcsGroupId, superseded,
                "parked in an era before " + now.era);
    }

    /**
     * Queues an RCC.16 §7.7.2.2 failure report for each dropped application entry and flushes
     * them, so the sender resends at the current moment. A control entry needs no report. The
     * report goes to the entry's recorded sender; an entry without one is reported only in a 1:1,
     * where the peer is the sender.
     *
     * @return how many application entries were reported
     */
    public static int reportDropped(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId, final String rcsGroupId,
            final List<MlsPendingQueue.Entry> dropped, final String why) {
        if (dropped == null || dropped.isEmpty()) return 0;
        final String[] parts = MlsConversationKey.splitCanonicalKey(conversationId);
        final String onlyPeer = parts == null ? null : parts[1];
        int reported = 0;
        int unattributed = 0;
        String flushPeer = null;
        final ConvState cs = shell.conv(conversationId);
        for (final MlsPendingQueue.Entry e : dropped) {
            if (e.plane != MlsPendingQueue.Plane.APPLICATION) continue;
            final String sender = e.sender != null ? e.sender : onlyPeer;
            if (sender == null || e.messageId.isEmpty()) {
                unattributed++;
                continue;
            }
            synchronized (cs) { cs.ftdPending.put(e.messageId, sender); }
            flushPeer = sender;
            reported++;
        }
        log.i("MlsInboundHold: dropped " + dropped.size() + " parked message(s) for "
                + MlsConversationKey.forLog(conversationId) + " (" + why + "); reporting "
                + reported
                + " application message(s) so the sender resends"
                + (unattributed == 0 ? "" : ", " + unattributed + " with no recorded sender"));
        if (flushPeer != null) shell.flushFtdReports(rcsGroupId, flushPeer);
        return reported;
    }

    /**
     * Whether the group, read under the lock, has moved past the reason an entry was admitted:
     * the exact-key drain takes only the current moment, so an entry filed at or behind it after
     * that drain ran is never replayed. A from-the-future entry is overtaken once its moment is
     * reached; a busy-group entry once the group moves or leaves the G1 mask. Other admissions
     * are not judged against the live moment.
     */
    static boolean overtaken(final MlsPendingQueue.Admission why, final MlsAppMessage.Moment at,
            final MlsAppMessage.Moment now, final boolean stillLocked) {
        if (at == null || now == null) return false;
        if (why == MlsPendingQueue.Admission.FROM_FUTURE) {
            return !MlsPendingQueue.strictlyAfter(at, now);
        }
        if (why == MlsPendingQueue.Admission.GROUP_LOCKED) {
            if (MlsPendingQueue.strictlyAfter(at, now)) return false;
            return at.compareTo(now) < 0 || !stillLocked;
        }
        return false;
    }

    /**
     * Stores one entry, running the RCC.16 §10.8 validations and per-bucket dedup. The caller's
     * admission read the group outside the lock; a commit applied and drained since is caught
     * here and reported as {@link ParkOutcome#OVERTAKEN} instead of stored.
     */
    public static ParkOutcome park(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId, final String messageId, final byte[] mlsBytes,
            final MlsAppMessage.Moment at, final MlsPendingQueue.Admission why,
            final String fromE164, final MlsPendingQueue.Plane plane) {
        final Group g = shell.getGroup(conversationId);
        if (g == null || g.groupId == null) return ParkOutcome.NO_GROUP;
        final String self = shell.selfE164();
        if (self.isEmpty()) return ParkOutcome.NO_GROUP;
        shell.lock(conversationId);
        try {
            final MlsAppMessage.Moment now =
                    MlsAppMessage.Moment.from(shell.session().eraEpoch(g.groupId));
            final MlsConversationRecord held = why == MlsPendingQueue.Admission.GROUP_LOCKED
                    ? MlsRecordState.recordFor(shell, log, conversationId) : null;
            if (overtaken(why, at, now,
                    held != null && MlsPendingQueue.groupLocked(held.healthStatus))) {
                return ParkOutcome.OVERTAKEN;
            }
            final MlsPendingQueue q = shell.pendingQueue().load(self, g.groupId);
            final MlsPendingQueue.Entry e = new MlsPendingQueue.Entry(messageId, at,
                    MlsWireScan.wireFormatOf(mlsBytes), g.groupId, mlsBytes, plane, fromE164);
            final MlsPendingQueue.StoreResult r = q.store(e, g.groupId);
            switch (r) {
                case STORED:
                    shell.pendingQueue().store(self, g.groupId, q);
                    // Info, not warning: buffering is the system working.
                    log.i("Result is a pending message. Pending reason: " + why
                            + ", health status: " + MlsHealthStates.name(
                                    MlsRecordState.recordFor(shell, log, conversationId) == null
                                            ? MlsHealthStates.UNKNOWN
                                            : MlsRecordState.recordFor(shell, log,
                                                    conversationId).healthStatus)
                            + ", result: parked at " + at);
                    return ParkOutcome.STORED;
                case DUPLICATE:
                    log.i(MlsPendingQueue.duplicateLine(messageId));
                    return ParkOutcome.DUPLICATE;
                default:
                    // A validation failure is a defect and cannot be retried; name the check.
                    log.w("MlsInboundHold: refusing to park a message from "
                            + LogMask.number(fromE164) + " for "
                            + MlsConversationKey.forLog(conversationId) + " — " + r
                            + " (fkht "
                            + r.errorOrdinal + ")");
                    return ParkOutcome.REFUSED;
            }
        } finally { shell.unlock(conversationId); }
    }
}

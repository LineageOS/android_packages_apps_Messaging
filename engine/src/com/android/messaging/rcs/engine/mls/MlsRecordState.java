/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Map;
import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.MlsAdoptionUndo;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
/**
 * Reads and writes one conversation's persisted {@link MlsConversationRecord} and the in-memory
 * {@link Group} row built from it, including capturing and undoing an adoption. The store is
 * reached through {@link MlsShellPort#records()}. See docs/mls/group-lifecycle.md.
 */
public final class MlsRecordState {
    private MlsRecordState() {}

    /** This conversation's record, creating an initial one if none is persisted yet. */
    public static MlsConversationRecord recordFor(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) return null;
        final String self = shell.selfE164();
        if (self.isEmpty()) return null;
        final StoreRead<MlsConversationRecord> r = shell.records().get(self, g.groupId);
        if (r.isOk()) return ((StoreRead.Ok<MlsConversationRecord>) r).value;
        if (r.isErr()) {
            // Err is not NotFound: starting fresh would discard an in-flight operation.
            log.e("MlsRecordState: record unreadable for " + MlsConversationKey.forLog(key) + " — "
                    + ((StoreRead.Err<MlsConversationRecord>) r).reason);
            return null;
        }
        return MlsConversationRecord.initial(self, g.groupId, g.rcsGroupId, g.peerE164);
    }

    /**
     * The persisted record for the group we hold, by MLS group id, without re-resolving the
     * conversation key (which could answer about a different group). Null when nothing is stored,
     * which callers treat as "cannot tell", never as a mismatch.
     */
    public static MlsConversationRecord recordForGroup(final MlsShellPort shell,
            final MlsLogSink log, final Group g) {
        if (g == null || g.groupId == null) return null;
        final String self = shell.selfE164();
        if (self.isEmpty()) return null;
        final StoreRead<MlsConversationRecord> r = shell.records().get(self, g.groupId);
        if (r.isOk()) return ((StoreRead.Ok<MlsConversationRecord>) r).value;
        // Either way there is no retained anchor; a storage fault must not become a fork verdict.
        if (r.isErr()) {
            log.e("MlsRecordState: record unreadable while testing whether the "
                    + "server's anchor is on our chain — "
                    + ((StoreRead.Err<MlsConversationRecord>) r).reason);
        }
        return null;
    }

    /**
     * Persist an initial record for a group that has just become real, if none is stored.
     * {@link #recordFor} mints one without saving it, so without this a group with no health
     * transition has no record for health or membership history. Makes no health claim; idempotent.
     */
    public static void ensureRecord(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) return;
        final String self = shell.selfE164();
        if (self.isEmpty()) return;
        final StoreRead<MlsConversationRecord> r = shell.records().get(self, g.groupId);
        // Only NotFound creates; writing over an unreadable record would discard what it held.
        if (!r.isNotFound()) return;
        final String err = shell.records().put(
                MlsConversationRecord.initial(self, g.groupId, g.rcsGroupId, g.peerE164));
        if (err != null) {
            log.w("MlsRecordState: could not create the initial record for "
                    + MlsConversationKey.forLog(key)
                    + ": " + err);
            return;
        }
        log.i("MlsRecordState: created the initial MlsConversationRecord for "
                + MlsConversationKey.forLog(key)
                + " — without it every reader of this group's health gets Unknown and membershipHistory "
                + "has nowhere to be written");
    }

    public static void writeRecord(final MlsShellPort shell, final MlsLogSink log, final Group g) {
        if (g == null || g.groupId == null) return;
        final String self = shell.selfE164();
        if (self == null || self.isEmpty()) return;
        try {
            final StoreRead<MlsConversationRecord> prior = shell.records().get(self, g.groupId);
            final MlsConversationRecord.Builder b = prior.isOk()
                    ? ((StoreRead.Ok<MlsConversationRecord>) prior).value.toBuilder()
                    : MlsConversationRecord.initial(self, g.groupId, g.rcsGroupId, g.peerE164)
                            .toBuilder();
            final MlsAppMessage.Moment now = shell.session() == null
                    ? null : MlsAppMessage.Moment.from(shell.session().eraEpoch(g.groupId));
            b.rcsGroupId(g.rcsGroupId).peerE164(g.peerE164).sendsThisEpoch(g.sendsThisEpoch)
                    .sendsSinceLeafRotation(g.sendsSinceLeafRotation);
            if (now != null) {
                b.moment(now);
                if (g.epochAuth != null && g.epochAuth.length > 0) {
                    // Keyed (era, epoch): a new era restarts the epoch. The builder clears the map
                    // when the era changes.
                    b.putEpochAuthenticator(now.era, now.epoch, g.epochAuth);
                }
            }
            final String err = shell.records().put(b.build());
            if (err != null) log.w("MlsRecordState: record write failed — " + err);
        } catch (final Throwable t) {
            log.w("MlsRecordState: record write threw (legacy scalars stand)", t);
        }
    }

    /**
     * The {@link Group} view of a persisted record, or null if there is none for this conversation.
     * The group id comes from the store's conversation-to-group alias, a key mapping rather than an
     * index on record contents.
     */
    public static Group loadFromRecord(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId) {
        final String self = shell.selfE164();
        if (self.isEmpty()) return null;
        final byte[] gid = shell.records().groupIdFor(self, conversationId);
        if (gid == null || gid.length == 0) return null;
        final StoreRead<MlsConversationRecord> r = shell.records().get(self, gid);
        if (!r.isOk()) {
            if (r.isErr()) {
                log.w("MlsRecordState: record for " + MlsConversationKey.forLog(conversationId)
                        + " unreadable — "
                        + ((StoreRead.Err<MlsConversationRecord>) r).reason
                        + "; falling back to the legacy scalars");
            }
            return null;
        }
        final MlsConversationRecord rec = ((StoreRead.Ok<MlsConversationRecord>) r).value;
        final Group g = new Group();
        g.groupId = gid;
        g.peerE164 = rec.peerE164.isEmpty() ? null : rec.peerE164;
        g.rcsGroupId = rec.rcsGroupId.isEmpty() ? null : rec.rcsGroupId;
        g.sendsThisEpoch = rec.sendsThisEpoch;
        g.sendsSinceLeafRotation = rec.sendsSinceLeafRotation;
        if (rec.moment != null) {
            g.era = rec.moment.era;
            g.epochAuth = rec.epochAuthenticators.get(rec.moment.epoch);
        }
        return g;
    }

    /**
     * Read what the host holds for {@code key} before adopting, so {@link #rollBackAdoption}
     * removes only what the adoption created. Call it immediately before {@link #adoptGroup}.
     */
    public static MlsAdoptionUndo captureAdoption(final MlsShellPort shell, final String key,
            final byte[] mlsGroupId) {
        final String self = shell.selfE164();
        if (key == null || mlsGroupId == null || self.isEmpty()) {
            // Nothing can be attributed, so a rollback built from this deletes nothing.
            return new MlsAdoptionUndo(/*hadRecord=*/ true, /*hadAlias=*/ true);
        }
        return new MlsAdoptionUndo(
                !shell.records().get(self, mlsGroupId).isNotFound(),
                shell.records().groupIdFor(self, key) != null);
    }

    /**
     * Give back an {@link #adoptGroup} whose verification then refused it (the establish path's
     * differs arm, where the adoption is a precondition of the repair). Removes exactly what
     * {@code adoptGroup} wrote, the durable parts only if this adoption created them
     * ({@code MlsVerifyBeforeAdoptGuardTest} keeps the two in step). Engine state is untouched: the
     * engine may hold more than the host, but the host must never hold what was not verified.
     *
     * @return whether the host is now free of this conversation
     */
    public static boolean rollBackAdoption(final MlsShellPort shell, final MlsLogSink log,
            final String key, final byte[] mlsGroupId, final MlsAdoptionUndo undo) {
        if (key == null || undo == null) return false;
        shell.lock(key);
        try {
            shell.groups().remove(key);
            final String self = shell.selfE164();
            if (self.isEmpty()) {
                // Without an identity the durable rows stay, and getGroup() would reload them.
                log.w("MlsRecordState: rolled back only the in-memory half of the "
                        + "adoption for " + MlsConversationKey.forLog(key)
                        + " — no self identity, so the persisted record and "
                        + "alias could not be removed and getGroup() will load the group back");
                return false;
            }
            if (!undo.hadAlias) shell.records().removeAlias(self, key);
            if (!undo.hadRecord && mlsGroupId != null) shell.records().remove(self, mlsGroupId);
            // Verify with the same reader the next establish will use.
            final Group still = shell.getGroup(key);
            if (still != null) {
                log.e("MlsRecordState: the host adoption for " + MlsConversationKey.forLog(key)
                        + " SURVIVED "
                        + "its rollback — getGroup() still answers, so the next establishGroup will "
                        + "short-circuit on it and report its era as a success");
                return false;
            }
            return true;
        } catch (final Throwable t) {
            log.w("MlsRecordState: rolling back the host adoption for "
                    + MlsConversationKey.forLog(key)
                    + " threw — the store may still hold an unverified group", t);
            return false;
        } finally { shell.unlock(key); }
    }

    public static boolean isDowngradedStatus(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        return rec != null && MlsHealthPredicates.isDowngraded(rec.healthStatus);
    }

    /**
     * The {@code has_end_mls} status test, which operational guards must use rather than the
     * 0xF002 extension: the two differ in five states. The extension read stays correct when
     * building a commit, reconciling against a fetched server GroupInfo, and in the revive
     * precondition. See docs/mls/downgrade.md.
     */
    public static boolean hasEndMlsStatus(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        return rec != null && MlsHealthPredicates.hasEndMls(rec.healthStatus);
    }

    /**
     * Whether we have left this group ({@code selfLeftAtMs}, RCC.16 §9.4). Terminal: a self-remove
     * is committed by another member and we see no echo. A record field, not a health status,
     * because no health state means "left" and {@code DoneEndMls} would claim an end_mls commit.
     */
    public static boolean weLeft(final MlsShellPort shell, final MlsLogSink log, final String key) {
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        return rec != null && rec.selfLeft();
    }

    /**
     * Whether an operation is already in flight for this group, in which case it is not a candidate
     * for a fresh upgrade.
     */
    public static boolean groupIsInitializing(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId) {
        if (!shell.ensureSession()) return false;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, /*peerE164=*/ null);
        final MlsConversationRecord rec = (key == null) ? null
                : MlsRecordState.recordFor(shell, log, key);
        return rec != null && rec.pendingOperation != null;
    }

    /**
     * The most recent recorded membership for this conversation, minus us: the latest entry of the
     * highest era.
     */
    public static java.util.List<String> recordedRoster(final MlsShellPort shell,
            final MlsLogSink log, final String key, final String self) {
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        if (rec == null || rec.membershipHistory == null || rec.membershipHistory.isEmpty()) {
            return null;
        }
        int bestEra = Integer.MIN_VALUE;
        long bestMoment = Long.MIN_VALUE;
        String[] best = null;
        for (final java.util.Map.Entry<Integer, java.util.Map<Long, String[]>> e
                : rec.membershipHistory.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            for (final java.util.Map.Entry<Long, String[]> m : e.getValue().entrySet()) {
                if (m.getKey() == null || m.getValue() == null) continue;
                if (e.getKey() > bestEra
                        || (e.getKey() == bestEra && m.getKey() > bestMoment)) {
                    bestEra = e.getKey();
                    bestMoment = m.getKey();
                    best = m.getValue();
                }
            }
        }
        if (best == null) return null;
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (final String m : best) {
            if (m != null && !m.isEmpty() && !m.equals(self)) out.add(m);
        }
        return out;
    }

    /**
     * Clear the self-leave mark when a Welcome putting us back in the group has been accepted.
     * Called only where a join is kept ({@code applyInboundControl}'s NEW_GROUP arm and
     * {@link #rejoinOnEraAdvance}), never from {@link #adoptGroup} or {@code joinFromWelcome},
     * which also run for creates and for Welcomes addressed to others. Logged at warning.
     */
    public static void clearSelfLeftOnRejoin(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String fromE164) {
        if (key == null) return;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            // An initial record is not-left, so groups that never left return without a write.
            if (rec == null || !rec.selfLeft()) return;
            final long leftAt = rec.selfLeftAtMs;
            final String err = shell.records().put(rec.toBuilder().selfLeftAtMs(0L).build());
            if (err != null) {
                // The join stands, so a record still saying left now disagrees with the engine.
                log.e("MlsRecordState: REJOINED " + MlsConversationKey.forLog(key) + " from "
                        + LogMask.number(fromE164)
                        + " but could NOT clear the self-leave mark: " + err + ". The conversation "
                        + "is back but every ED-1 guard still reads it as LEFT — self-heal, the "
                        + "maintenance pass, the floor rebuild and commitPendingProposals will all "
                        + "decline, and the UI will keep hiding Rename / Add people / Leave group.");
                return;
            }
            log.w("MlsRecordState: " + MlsConversationKey.forLog(key) + " was marked LEFT at "
                    + leftAt
                    + " and a Welcome from " + LogMask.number(fromE164)
                    + " has just been ACCEPTED — we are in this "
                    + "group again, so the terminal self-leave mark is CLEARED; left in place, "
                    + "it would make a re-added member have every "
                    + "recovery path decline by name (INVARIANT ED-1) on a group it is "
                    + "demonstrably a member of. membershipHistory is left as the departure wrote it — "
                    + "EMPTY — and ourRoster reads that as 'no baseline' rather than as 'the group "
                    + "has no members', so the maintenance pass establishes the roster from the "
                    + "SERVER instead of reading every member as new and era-advancing.");
        } catch (final Throwable t) {
            log.e("MlsRecordState: could not clear the self-leave mark on "
                    + MlsConversationKey.forLog(key)
                    + " after rejoining from " + LogMask.number(fromE164), t);
        } finally {
            shell.unlock(key);
        }
    }

    /** Register/persist an MLS group for an RCS group we have joined (used by the join path). */
    public static String adoptGroup(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final byte[] mlsGroupId) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null || mlsGroupId == null) return null;
        shell.lock(key);
        try {
        final Group g = new Group();
        g.groupId = mlsGroupId;
        g.peerE164 = peerE164;
        g.rcsGroupId = rcsGroupId;
        final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(mlsGroupId));
        g.era = era >= 0 ? era : MlsTransportTypes.ERA_INITIAL;
        g.epochAuth = shell.session().epochAuth(mlsGroupId);
        shell.putGroup(key, g);
        MlsRecordState.ensureRecord(shell, log, key);
        // RCC.16 §7.11.12.1: collect the Welcome's continuity token here, inside the lock and after
        // ensureRecord guaranteed a record. A created group has no Welcome, so this is a no-op.
        MlsContinuityToken.collectWelcomeContinuityToken(shell, log, key, mlsGroupId);
        return key;
        } finally { shell.unlock(key); }
    }

    /**
     * The differs arm of {@link #establishGroup} and {@link #addMembersToExistingGroup}: our epoch
     * authenticator does not match the server's, so we hold a different group at the same era.
     * Adopts (self-heal needs a host group to resolve), heals onto the server's group, and gives
     * the adoption back through {@link #rollBackAdoption} if the heal does not converge.
     *
     * @return the era the heal converged on, or {@code -1} with the host holding nothing
     */
    public static int healOntoServerGroupOrUnadopt(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String first,
            final String key, final byte[] mlsGroupId, final long targetEra) {
        // Report only the authenticator comparison; not every caller read the server's era.
        log.e("MlsRecordState: " + rcsGroupId + " — we hold era " + targetEra
                + " and our epoch AUTHENTICATOR does not match the server's. That is a DIFFERENT "
                + "GROUP, not a joined one, and every send will be refused "
                + "incorrect-epoch-authenticator. Not claiming this as established; self-healing "
                + "onto the server's group instead.");
        final MlsAdoptionUndo pre = MlsRecordState.captureAdoption(shell, key, mlsGroupId);
        MlsRecordState.adoptGroup(shell, log, rcsGroupId, first, mlsGroupId);
        final int healed = shell.selfHeal(rcsGroupId, first);
        if (healed > 0) {
            log.i("MlsRecordState: self-heal converged " + rcsGroupId
                    + " onto the server's group at era=" + healed);
            return healed;
        }
        // A heal that did not converge must not leave the host owning a proven fork.
        final boolean unadopted = MlsRecordState.rollBackAdoption(shell, log, key, mlsGroupId, pre);
        log.w("MlsRecordState: self-heal did not converge " + rcsGroupId
                + " (returned " + healed + ") — the conversation is NOT usable yet"
                + (unadopted
                        ? " and the host adoption was ROLLED BACK, so a later establish cannot "
                          + "short-circuit on this proven fork and report its era as a success, "
                          + "and a re-Welcome can still reach joinFromWelcome"
                        : " ⚠ AND THE HOST ADOPTION COULD NOT BE ROLLED BACK — the store holds a "
                          + "group the epoch authenticator proved is a FORK, which every later "
                          + "establish will report as an established era"));
        return -1;
    }

    /**
     * @param includeSelf whether to add our own MSISDN to the recorded set; false only on the
     *     self-leave path, where the record must say we are gone
     */
    public static void recordMembership(final MlsShellPort shell, final MlsLogSink log,
            final String key, final Group g,
            final java.util.List<String> members, final boolean includeSelf) {
        if (key == null || g == null || g.groupId == null || members == null) return;
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        if (rec == null) return;
        final byte[] ee = shell.session().eraEpoch(g.groupId);
        final int era = MlsAppMessage.eraFrom(ee);
        final long epoch = MlsAppMessage.epochFrom(ee);
        if (era < 0) return;
        final java.util.List<String> all = new java.util.ArrayList<>(members);
        final String self = shell.selfE164();
        // The stored set includes us; the comparison against the server roster accounts for that.
        if (includeSelf && !self.isEmpty() && !all.contains(self)) all.add(self);
        final String err = shell.records().put(rec.toBuilder()
                .putMembership(era, epoch, all.toArray(new String[0])).build());
        if (err != null) {
            log.w("MlsRecordState: could not record membership for "
                    + MlsConversationKey.forLog(key) + ": "
                    + err);
            return;
        }
        log.i("MlsRecordState: recorded membership for " + MlsConversationKey.forLog(key)
                + " at era=" + era
                + " epoch=" + epoch + " → " + all.size() + " member(s)");
    }

    /**
     * Record the member set of a group at its current (era, epoch), wherever we know the roster
     * because we just built the group around it; the membership delta compares sets, not counts.
     */
    public static void recordMembership(final MlsShellPort shell, final MlsLogSink log,
            final String key, final Group g, final java.util.List<String> members) {
        MlsRecordState.recordMembership(shell, log, key, g, members, /*includeSelf=*/ true);
    }

    /**
     * We left: record it durably, under the conversation lock, right after the SelfRemove was
     * accepted. Marks the record terminal, empties the recorded membership, releases the pending
     * slot, and last deletes the engine group, which can never agree with the group again. The host
     * Group entry and record are kept: they are what makes {@link #weLeft} readable. See
     * docs/mls/group-lifecycle.md.
     */
    public static void recordSelfDeparture(final MlsShellPort shell, final MlsLogSink log,
            final String key, final Group g) {
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null) {
                log.w("MlsRecordState: we left " + MlsConversationKey.forLog(key)
                        + " but hold no record for "
                        + "it, so the departure cannot be marked. Sends will still be blocked by "
                        + "the cached SelfRemove — correctly — but as a wedge rather than a leave.");
                return;
            }
            final String err = shell.records().put(
                    rec.toBuilder().selfLeftAtMs(System.currentTimeMillis()).build());
            if (err != null) {
                log.w("MlsRecordState: could not mark " + MlsConversationKey.forLog(key)
                        + " as LEFT: " + err);
            }
            // After the mark: recordMembership re-reads the record it edits.
            MlsRecordState.recordMembership(shell, log, key, g,
                    java.util.Collections.<String>emptyList(),
                    /*includeSelf=*/ false);
            MlsPendingOperation.clearPendingOp(shell, log, key);
            // The engine group goes last, since recordMembership above reads era/epoch from it.
            // The host Group entry and record must survive: recordFor() resolves through
            // getGroup(), so removing either makes weLeft() answer false and re-opens every
            // self-leave guard.
            boolean engineDropped = false;
            if (g.groupId != null) {
                engineDropped = shell.session().deleteGroup(g.groupId);
                // Logged only when the engine really dropped it.
                if (engineDropped) log.i(MlsTrace.deletedGroupState());
            }
            log.i("MlsRecordState: " + MlsConversationKey.forLog(key)
                    + " is marked LEFT — membershipHistory now "
                    + "records no members, any pending operation is released, the cached SelfRemove "
                    + "is protected from the drop lever, and the engine group is deleted ("
                    + engineDropped
                    + ") so we no longer hold the epoch secrets, the ratchet tree or "
                    + "our leaf key for a group the rest of the members have already removed us "
                    + "from. The host record survives; it is what remembers that we left.");
        } catch (final Throwable t) {
            // The SelfRemove is already accepted; bookkeeping must not undo that.
            log.w("MlsRecordState: could not record our own departure from "
                    + MlsConversationKey.forLog(key)
                    + " — the SelfRemove was accepted regardless", t);
        }
    }

    public static MlsHealthEdge moveHealth(final MlsShellPort shell, final MlsLogSink log,
            final String key, final int to, final String cause) {
        if (key == null) return null;
        boolean moved = false;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null) return null;
            final MlsHealthMachine.Applied a =
                    MlsHealthMachine.transition(rec, to, rec.moment, /*allowIllegal=*/ false);
            final MlsHealthMachine.Decision d = a.decision;

            if (d.outcome == MlsHealthMachine.Outcome.ILLEGAL_REJECTED) {
                log.e(MlsTrace.cannotTransition(MlsGroupState.groupIdForTrace(shell, key),
                        MlsHealthStates.name(rec.healthStatus), MlsHealthStates.name(to)));
                log.e("MlsRecordState: REFUSED health transition "
                        + MlsHealthStates.name(rec.healthStatus) + " → " + MlsHealthStates.name(to)
                        + " on " + MlsConversationKey.forLog(key) + " (" + cause
                        + ") — that pair is not in the §5.3 table");
                return null;
            }
            if (d.emitsTelemetry()) {
                // Before the write, as a counter and as a typed record tests can assert on.
                shell.telemetry().count(MlsMetrics.STATE_TRANSITION, d.edge.telemetryValue);
                shell.telemetry().transition(MlsStateTransition.of(
                        rec.healthStatus, to, d.edge, cause));
                // The trace-format transition line, alongside our own line with key and cause.
                log.i(MlsTrace.transitioning(MlsGroupState.groupIdForTrace(shell, key),
                        MlsHealthStates.name(rec.healthStatus), MlsHealthStates.name(to),
                        d.edge.wireName));
                log.i("MlsRecordState: " + MlsConversationKey.forLog(key) + " health "
                        + MlsHealthStates.name(rec.healthStatus) + " → " + MlsHealthStates.name(to)
                        + " on edge " + d.edge.wireName + " (" + cause + ")");
            }
            if (d.outcome == MlsHealthMachine.Outcome.NO_OP) {
                // Both lines on this arm: the pair distinguishes "already there" from "moved".
                log.i(MlsTrace.alreadyInState(MlsHealthStates.name(to),
                        MlsGroupState.groupIdForTrace(shell, key)));
                log.i(MlsTrace.skippingHealthWrite(MlsHealthStates.name(to)));
            }
            if (a.record != rec) {
                final String err = shell.records().put(a.record);
                // Only after a real write; mutually exclusive with the NO_OP arm's "skipping" line.
                if (err == null) log.i(MlsTrace.wroteGroupState());
                if (err != null) {
                    log.w("MlsRecordState: health write failed for "
                            + MlsConversationKey.forLog(key) + " — " + err);
                }
            }
            moved = d.changesState();
            return d.edge;
        } finally {
            shell.unlock(key);
            // Outside the lock (both touch the database) and only on a real move, so arriving at
            // DoneEndMls twice downgrades once.
            if (moved) {
                MlsDowngradeLadder.downgradeFromEngineStatus(shell, log, key, to);   // engine->host
                MlsDowngradeFlow.maybeFastReupgrade(shell, log, key);              // re-upgrade
            }
        }
    }

    /**
     * The legacy preference key a continuity token was stored under before the record held it.
     * Read once per conversation by {@link #continuityTokenFor}, which migrates and removes it.
     */
    public static final String LEGACY_CONTINUITY_PREFIX = "mls_continuity_token_";

    /**
     * The RCC.16 §7.11.12.1 continuity token we hold for this conversation, or empty. Migrates a
     * legacy preference into the record on read; the preference is removed only once the token is
     * in the record, or if it does not decode. An empty answer after a failed lookup is logged as
     * "could not look". No production caller yet. See docs/mls/downgrade.md.
     */
    public static byte[] continuityTokenFor(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        final String self = shell.selfE164();
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (self.isEmpty() || g == null || g.groupId == null) {
            // Empty is ambiguous here, so the log line says we could not look.
            log.w("MlsRecordState: continuity UNKNOWN for key=" + MlsConversationKey.forLog(key)
                    + " — "
                    + (self.isEmpty()
                            ? "no MLS session is open, so the identity half of the record "
                            + "key is missing" : "no MLS group under that key")
                    + ". This is 'could not look', NOT 'we hold none'; do not read the empty answer "
                    + "as evidence.");
            return new byte[0];
        }
        shell.lock(key);
        try {
            final StoreRead<MlsConversationRecord> r = shell.records().get(self, g.groupId);
            if (r.isErr()) {
                // Err means the record is corrupt, so the legacy preference may be the last copy:
                // leave it untouched.
                log.w("MlsRecordState: continuity UNKNOWN for " + MlsConversationKey.forLog(key)
                        + " — the record is unreadable ("
                        + ((StoreRead.Err<MlsConversationRecord>) r).reason
                        + "). This is 'could not look', NOT 'we hold none', and the legacy "
                        + "preference is left untouched rather than migrated or dropped.");
                return new byte[0];
            }
            final MlsConversationRecord rec = r.isOk()
                    ? ((StoreRead.Ok<MlsConversationRecord>) r).value : null;
            if (rec != null && rec.hasContinuityToken()) return rec.continuityToken;

            // Migration; reached only when the record holds no token.
            final MlsPrefs prefs = shell.prefs();
            final String legacy = prefs.getString(LEGACY_CONTINUITY_PREFIX + key, null);
            if (legacy == null) return new byte[0];
            byte[] decoded;
            try {
                decoded = shell.base64Decode(legacy);
            } catch (final IllegalArgumentException bad) {
                decoded = null;
            }
            if (decoded == null || decoded.length == 0) {
                // A value that does not decode never will; discard it.
                prefs.edit().remove(LEGACY_CONTINUITY_PREFIX + key).apply();
                log.w("MlsRecordState: continuity MIGRATION discarded the legacy "
                        + "preference for " + MlsConversationKey.forLog(key)
                        + " — it did not decode");
                return new byte[0];
            }
            if (rec == null) {
                // NotFound: keep the preference for the next read, and still answer with the token.
                log.i("MlsRecordState: continuity MIGRATION DEFERRED for "
                        + MlsConversationKey.forLog(key)
                        + " — a " + decoded.length + "B legacy token, but no conversation record "
                        + "to fold it into. Kept in the legacy preference; the next read migrates "
                        + "it. We DO hold the token.");
                return decoded;
            }
            final String err =
                    shell.records().put(rec.toBuilder().continuityToken(decoded).build());
            if (err != null) {
                // Keep the preference: the fold failed.
                log.w("MlsRecordState: continuity MIGRATION could not write the "
                        + decoded.length + "B token for " + MlsConversationKey.forLog(key)
                        + " into the record's continuityToken (" + err
                        + "); the legacy preference is KEPT rather than dropped, so the token "
                        + "survives for the next attempt.");
                return decoded;
            }
            prefs.edit().remove(LEGACY_CONTINUITY_PREFIX + key).apply();
            log.i("MlsRecordState: continuity MIGRATED a " + decoded.length
                    + "B token for " + MlsConversationKey.forLog(key)
                    + " out of the legacy preference into the record's continuityToken");
            return decoded;
        } catch (final Throwable t) {
            log.w("MlsRecordState: reading the continuity token for "
                    + MlsConversationKey.forLog(key)
                    + " threw", t);
            return new byte[0];
        } finally { shell.unlock(key); }
    }
}

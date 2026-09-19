/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraReconcile;
import com.android.messaging.rcs.log.LogMask;
/**
 * The proactive maintenance pass for one conversation (compare with the server; refresh by era
 * advance when {@link MlsMaintenancePolicy} warrants it) and the group sweep that runs it over
 * every stored conversation. The sweep is armed by a state change (cold session bring-up, identity
 * change), walks the set once from a persisted cursor and disarms; there is no timer. Every advance
 * goes through the era-advance budget.
 */
public final class MlsMaintenancePass {
    private MlsMaintenancePass() {}

    /**
     * The pass itself, without continuing the sweep afterwards, so the sweep can call it without
     * recursing. Entry point: {@link #runMaintenance}.
     */
    public static MlsMaintenancePolicy.Refresh runMaintenanceOnce(final MlsConfig cfg,
            final MlsShellPort shell, final MlsLogSink log, final String rcsGroupId,
            final String peerE164, final String cause) {
        if (!shell.ensureSession()) return MlsMaintenancePolicy.Refresh.NOT_NEEDED;
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) {
            log.i("MlsMaintenancePass: maintenance (" + cause + ") — no MLS group for "
                    + LogMask.number(peerE164) + "; nothing to refresh");
            return MlsMaintenancePolicy.Refresh.NOT_NEEDED;
        }
        // Never maintain a group we left: its recorded roster is empty, so every server member
        // would read as new and the add arm would era-advance, re-creating the group with us in it.
        // Checked here so every caller is covered.
        if (MlsRecordState.weLeft(shell, log, key)) {
            log.i("MlsMaintenancePass: maintenance (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — WE LEFT this MLS group; declining (INVARIANT ED-1). Refreshing it could "
                    + "era-advance, and an era advance re-creates the group with us in it.");
            return MlsMaintenancePolicy.Refresh.NOT_NEEDED;
        }
        final String gid = MlsHex.groupIdHex(g.groupId);

        // RCC.16 §9.5.3 Self-Update first: it needs no server look, so a ledger refusal below
        // cannot postpone it, and our own leaf is the one credential this pass can repair.
        MlsCredentialUpdate.maybeUpdateGroupCredential(cfg, shell, log, key, g, rcsGroupId,
                peerE164, cause);

        // ONE fetch serves the roster, the GroupInfo and the era comparison.
        final Look<byte[]> maint =
                shell.fetchServerPack(MlsFetchLedger.Caller.MAINTENANCE, rcsGroupId, peerE164, g);
        if (maint.refused()) {
            // A proactive pass we cannot afford simply does not happen; nothing was asked.
            log.i("MlsMaintenancePass: maintenance (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — SKIPPED, the fetch ledger refused the look. Nothing was asked and nothing "
                    + "is concluded about this group; the next maintenance trigger re-runs it.");
            return MlsMaintenancePolicy.Refresh.NOT_NEEDED;
        }
        final byte[] pack = maint.orNull();
        if (pack == null) {
            log.w("MlsMaintenancePass: maintenance (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — could not read the server's view. NOT refreshing on a guess.");
            return MlsMaintenancePolicy.Refresh.NOT_NEEDED;
        }
        final byte[] serverGroupInfo = MlsWireScan.firstPacked(pack, 0);
        final java.util.List<String> serverRoster = MlsServerBundle.rosterFromPack(shell, pack);

        // Reconcile first: a local era ahead of the server's is not a refresh question.
        final EraReconcile reconciled = shell.quarantineIfAheadOfServer(g, rcsGroupId, peerE164,
                MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId)),
                "maintenance reconcile");
        if (reconciled.quarantined) {
            // The group was dropped; everything below would read or write through it.
            log.i("MlsMaintenancePass: maintenance (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — the reconcile QUARANTINED this group (we were ahead of an era the server "
                    + "never granted). Nothing left to maintain; an inbound Welcome is now the "
                    + "repair.");
            return MlsMaintenancePolicy.Refresh.NOT_NEEDED;
        }

        // Same era and epoch with a different authenticator is a different group at the same
        // position, which the era reconcile cannot see. Reuses the era/epoch already fetched; the
        // authenticator look is charged to MAINTENANCE_IDENTITY and taken only when both are equal.
        final ServerComparison identity = (reconciled.serverEraEpoch == null)
                ? ServerComparison.notAsked(Health.UNKNOWN)
                : MlsAheadChainCheck.healthAgainstServer(shell, log,
                        MlsFetchLedger.Caller.MAINTENANCE_IDENTITY, rcsGroupId,
                        peerE164, g, reconciled.serverEraEpoch);
        if (identity.health == Health.DIVERGED) {
            return MlsRecoveryPolicy.maintenanceFoundAFork(shell, log, key, rcsGroupId, peerE164,
                    cause);
        }

        // Logs extension types we cannot name, to find the metadata-keys request code point.
        MlsTransportDiagnostics.reportUnknownServerExtTypes(shell.session(), log, key,
                serverGroupInfo);

        // ---- the add arm's first input: members the server has that we do not -------------------
        int newMembers = 0;
        final java.util.List<String> ours = MlsServerBundle.ourRoster(shell, log, key, g);
        if (serverRoster == null) {
            log.w("MlsMaintenancePass: maintenance (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — the server roster is unreadable; treating the add arm as 0 rather than "
                    + "inventing a delta.");
        } else if (ours == null) {
            // No membership recorded at this era is not "everyone is new". The server's roster is
            // recorded as a baseline only when the authenticator confirmed this is the server's
            // group; anything weaker would give a fork a plausible membership history. The write
            // stays under the positive predicate.
            if (identity.serverConfirmedOurs()) {
                log.i("MlsMaintenancePass: maintenance (" + cause + ") for "
                        + MlsConversationKey.forLog(key)
                        + " — no membership recorded at this era, so the add arm cannot be "
                        + "evaluated. The server CONFIRMED this is its group (epoch authenticator "
                        + "MATCHES), so recording its roster now gives the NEXT pass a baseline.");
                MlsRecordState.recordMembership(shell, log, key, g, serverRoster);
            } else {
                log.i("MlsMaintenancePass: maintenance (" + cause + ") for "
                        + MlsConversationKey.forLog(key)
                        + " — no membership recorded at this era, and NOT recording the server's "
                        + "roster as a baseline: the epoch authenticator did not confirm this is the "
                        + "server's group (health=" + identity.health + " identity="
                        + identity.identityLine() + "). The add arm stays un-evaluable this pass. "
                        + "Recording a membership we cannot show we were part of is what makes a "
                        + "fork look reconciled.");
            }
        } else {
            for (final String m : serverRoster) if (!ours.contains(m)) newMembers++;
        }

        // ---- the add arm's second input: the server's metadata-keys request ---------------------
        final boolean keysRequested = MlsMetadataKeysPolicy.metadataKeysRequestPresent(cfg,
                shell.session(), serverGroupInfo);
        if (!cfg.metadataKeysExtKnown() && newMembers > 0) {
            // Say so, or an unknown code point reads like "the server has not asked".
            log.w("MlsMaintenancePass: maintenance (" + cause + ") for "
                    + MlsConversationKey.forLog(key) + " — "
                    + newMembers
                    + " new member(s), but the add arm is UN-EVALUABLE: we do not know "
                    + "the group_metadata_keys_requested extension type (set "
                    + MlsConfig.KEY_METADATA_KEYS_EXT
                    + " on a debug build once a capture yields it). NOT refreshing "
                    + "— an advance the server never asked for burns an era.");
        }

        // The credential floor is reported but is not an input to the verdict: expiredMemberCount
        // stays the single actor. The floor has its own repair, run below only when the verdict
        // did not warrant an advance, so one pass yields at most one era advance.
        final MlsCredentialFloor.Report floorReport =
                MlsServerBundle.rosterFloorReport(cfg, shell.session(), log, g);
        MlsFloorRebuild.logFloorReport(cfg, log, key, floorReport, cause);

        // ---- the expiry arm ---------------------------------------------------------------------
        final int expired = MlsCredentialFloor.expiredMemberCount(shell.session(), g);
        if (expired < 0) {
            log.w("MlsMaintenancePass: maintenance (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — member validity is unreadable; the expiry arm is un-evaluable.");
        } else {
            // Logged even when zero, as other clients do.
            log.i(MlsMaintenancePolicy.expiredCountLine(expired, gid));
        }

        final MlsMaintenancePolicy.Refresh verdict = MlsMaintenancePolicy.evaluate(
                newMembers, Math.max(0, expired), keysRequested);
        log.i("MlsMaintenancePass: maintenance (" + cause + ") for "
                + MlsConversationKey.forLog(key) + " → "
                + verdict + " [newMembers=" + newMembers + " expired=" + expired
                + " keysRequested=" + (cfg.metadataKeysExtKnown() ? String.valueOf(keysRequested)
                        : "UNKNOWN-CODE-POINT") + "]");
        if (!verdict.warranted()) {
            MlsFloorRebuild.maybeFloorRebuild(cfg, shell, log, key, g, rcsGroupId, peerE164,
                    floorReport, cause);
            return verdict;
        }

        // Defer the advance when our ledger refused the authenticator look: the fork test did not
        // run, and advancing a fork spends budget and writes it a history. Narrow on purpose:
        // gating on !serverConfirmedOurs() would stop maintenance for every group not exactly at
        // the server's epoch.
        if (identity.identityRefusedByLedger()) {
            log.w("MlsMaintenancePass: maintenance (" + cause + ") for "
                    + MlsConversationKey.forLog(key) + " → "
                    + verdict
                    + " WARRANTS an era advance, and it is NOT being taken this pass: the "
                    + "fetch ledger refused the epoch-authenticator look, so the DIVERGED test did "
                    + "not run and we cannot show this is the server's group. DEFERRED, not "
                    + "refused — the ration rolls within one window and the next pass decides with "
                    + "the answer in hand (the roster baseline is gated the same way "
                    + "one branch up).");
            return verdict;
        }

        if (verdict == MlsMaintenancePolicy.Refresh.NEW_MEMBERS
                || verdict == MlsMaintenancePolicy.Refresh.BOTH) {
            log.i(MlsMaintenancePolicy.newMembersLine(gid));
        }
        if (verdict == MlsMaintenancePolicy.Refresh.EXPIRED_MEMBERS
                || verdict == MlsMaintenancePolicy.Refresh.BOTH) {
            log.i(MlsMaintenancePolicy.expiredMembersChangedLine(gid));
        }

        final int era = shell.eraAdvance(rcsGroupId, peerE164, serverGroupInfo);
        if (era < 0) {
            log.w("MlsMaintenancePass: maintenance refresh for " + MlsConversationKey.forLog(key)
                    + " was REFUSED. "
                    + "Unlike a recovery this is not urgent — the next pass re-evaluates from the "
                    + "server's current view rather than retrying a stale decision.");
            return verdict;
        }
        // The advance rebuilt the group around the server's roster.
        if (serverRoster != null) MlsRecordState.recordMembership(shell, log, key, g, serverRoster);
        log.i("MlsMaintenancePass: maintenance refresh for " + MlsConversationKey.forLog(key)
                + " → era " + era);
        return verdict;
    }

    /**
     * Maintains one conversation from the stored set, or declines it from local state alone, so the
     * page budget bounds server traffic.
     *
     * @return true if the pass ran and spent a page slot; an upper bound on round trips
     */
    public static boolean sweepOne(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String self, final String convKey, final String cause,
            final java.util.Set<String> seenGroups) {
        final byte[] gid = shell.records().groupIdFor(self, convKey);
        if (gid == null || gid.length == 0) return false;
        // One pass per group, not per alias: a group can be stored under two conversation keys.
        if (!seenGroups.add(MlsHex.hex(gid))) return false;
        final StoreRead<MlsConversationRecord> r = shell.records().get(self, gid);
        if (!r.isOk()) {
            if (r.isErr()) {
                log.w("MlsMaintenancePass: group sweep — the record for "
                        + MlsConversationKey.forLog(convKey)
                        + " is unreadable (" + ((StoreRead.Err<MlsConversationRecord>) r).reason
                        + "); declining. It is NOT repaired here: rewriting a record the "
                        + "sweep cannot read would make this a second actor on state the "
                        + "record owns.");
            }
            return false;
        }
        final MlsConversationRecord rec = ((StoreRead.Ok<MlsConversationRecord>) r).value;
        // Never maintain a downgraded group: an era advance would re-create it and silently
        // re-encrypt. The open path declines these earlier; the sweep reaches them from storage.
        if (MlsHealthPredicates.isDowngraded(rec.healthStatus)) {
            log.i("MlsMaintenancePass: group sweep — " + MlsTrace.groupId(gid) + " is "
                    + MlsHealthStates.name(rec.healthStatus) + "; declining (INVARIANT ED-1: "
                    + "maintaining it could era-advance, and an era advance re-creates the group)");
            return false;
        }
        if (rec.peerE164 == null || rec.peerE164.isEmpty()) {
            log.i("MlsMaintenancePass: group sweep — no routable peer is recorded for "
                    + MlsTrace.groupId(gid)
                    + "; declining. The server fetch is addressed to a peer, "
                    + "so there is nothing to address it to.");
            return false;
        }
        // runMaintenanceOnce, not runMaintenance, which would recurse into the sweep.
        MlsMaintenancePass.runMaintenanceOnce(cfg, shell, log, rec.rcsGroupId.isEmpty() ? null
                : rec.rcsGroupId, rec.peerE164, cause);
        return true;
    }

    /** Durable: a walk of the group set is outstanding. This flag is the throttle. */
    public static final String PREF_SWEEP_ARMED = "group_sweep_armed";

    /**
     * Durable: the last conversation key the walk completed, or absent to start at the beginning.
     * Each page resumes strictly after it in {@link MlsRecordStore#conversationIds} order, so an
     * interrupted walk continues rather than restarting; written after each entry. Entries added
     * before the cursor mid-walk wait for the next arming.
     */
    public static final String PREF_SWEEP_CURSOR = "group_sweep_cursor";

    /**
     * Walks the next page of the outstanding sweep and records where it stopped.
     *
     * @return how many conversations were taken to the server
     */
    public static int sweepOnePage(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String cause) {
        // Concurrency and re-entrancy guard.
        if (!shell.sweepInFlight().compareAndSet(false, true)) return 0;
        try {
            final MlsPrefs p = shell.prefs();
            // Re-read under the guard: the previous page may have finished the walk.
            if (!p.getBoolean(PREF_SWEEP_ARMED, false)) return 0;
            if (!shell.ensureSession()) return 0;
            final String self = shell.selfE164();
            if (self.isEmpty()) return 0;
            String cursor = p.getString(PREF_SWEEP_CURSOR, "");
            // The durable set: the in-memory map is empty on a cold start, which arms the sweep.
            final java.util.List<String> all = shell.records().conversationIds(self);
            final java.util.Set<String> seenGroups = new java.util.HashSet<>();
            int maintained = 0;
            int declined = 0;
            boolean exhausted = true;
            for (final String convKey : all) {
                if (convKey.compareTo(cursor) <= 0) continue;   // walked by an earlier pass
                if (MlsMaintenancePolicy.sweepPageExhausted(maintained)) { exhausted =
                        false; break; }
                if (MlsMaintenancePass.sweepOne(cfg, shell, log, self, convKey, cause,
                        seenGroups)) maintained++; else declined++;
                // A decline advances the cursor too, or dead aliases could stall the walk.
                cursor = convKey;
                p.edit().putString(PREF_SWEEP_CURSOR, cursor).commit();
            }
            if (exhausted) {
                p.edit().putBoolean(PREF_SWEEP_ARMED, false).remove(PREF_SWEEP_CURSOR).commit();
                log.i("MlsMaintenancePass: group sweep COMPLETE (" + cause + ") — "
                        + maintained + " maintained, " + declined
                        + " declined on this pass, out of "
                        + all.size() + " conversation(s) known. DISARMED: nothing further happens "
                        + "until a state change arms it again.");
            } else {
                log.i("MlsMaintenancePass: group sweep page done (" + cause + ") — "
                        + maintained + " maintained, " + declined + " declined; stopped at '"
                        + MlsConversationKey.forLog(cursor) + "' of " + all.size()
                        + ". The next pass RESUMES there — including "
                        + "after a process restart, because the cursor is persisted.");
            }
            return maintained;
        } finally {
            shell.sweepInFlight().set(false);
        }
    }

    /**
     * Takes one page of the sweep on its own thread if a walk is outstanding. The armed check comes
     * first, so the common path is one preference read. The thread keeps network work off callers'
     * threads and outside any lock.
     */
    public static void continueGroupSweep(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String cause) {
        if (!cfg.groupSweep) return;
        try {
            if (!shell.prefs()
                    .getBoolean(MlsMaintenancePass.PREF_SWEEP_ARMED, false)) {
                return;                       // nothing outstanding: no work, and no connection
            }
        } catch (final Throwable t) {
            return;                           // a sweep is never worth failing an operation over
        }
        // a cheap pre-check; the CAS inside is the decision
        if (shell.sweepInFlight().get()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    MlsMaintenancePass.sweepOnePage(cfg, shell, log, cause);
                } catch (final Throwable t) {
                    // Background maintenance must never take the app down.
                    log.w("MlsMaintenancePass: the group sweep threw (" + cause + ")", t);
                }
            }
        }, "mls-group-sweep").start();
    }

    /**
     * Arms the sweep after a state change that may have changed every group's answer (a cold
     * session bring-up, an identity change) and takes its first page. Idempotent, and it does not
     * reset the cursor: re-arming continues a walk in progress.
     */
    public static void armGroupSweep(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String cause) {
        if (!cfg.groupSweep) {
            log.i("MlsMaintenancePass: the group sweep is OFF ("
                    + MlsConfig.KEY_GROUP_SWEEP + "=0) — not arming it for " + cause
                    + ". Conversations opened by hand are still maintained; groups nobody opens are "
                    + "not maintained at all while this is off.");
            return;
        }
        try {
            final MlsPrefs p = shell.prefs();
            if (p.getBoolean(MlsMaintenancePass.PREF_SWEEP_ARMED, false)) {
                log.i("MlsMaintenancePass: group sweep already armed — " + cause
                        + " CONTINUES the walk from '"
                        + MlsConversationKey.forLog(
                                p.getString(MlsMaintenancePass.PREF_SWEEP_CURSOR, ""))
                        + "' rather than restarting it");
            } else {
                p.edit().putBoolean(MlsMaintenancePass.PREF_SWEEP_ARMED, true).commit();
                log.i("MlsMaintenancePass: group sweep ARMED by " + cause + " — it walks "
                        + "the group set ONCE and disarms. Nothing re-arms it but another state "
                        + "change; there is no interval and no timer (§8.7).");
            }
        } catch (final Throwable t) {
            log.w("MlsMaintenancePass: could not arm the group sweep", t);
            return;
        }
        MlsMaintenancePass.continueGroupSweep(cfg, shell, log, cause);
    }

    /**
     * The maintenance pass for one conversation: one server read feeds the policy, and an era
     * advance is issued only when warranted. It also reconciles a local era ahead of the server's.
     * No clock and no timer; the throttle is state-based.
     *
     * @return the policy's verdict; never null
     */
    public static MlsMaintenancePolicy.Refresh runMaintenance(final MlsConfig cfg,
            final MlsShellPort shell, final MlsLogSink log, final String rcsGroupId,
            final String peerE164, final String cause) {
        try {
            return MlsMaintenancePass.runMaintenanceOnce(cfg, shell, log, rcsGroupId, peerE164,
                    cause);
        } finally {
            // Opening conversations also drains an armed sweep; it does nothing when none is armed.
            MlsMaintenancePass.continueGroupSweep(cfg, shell, log, "sweep continuation after "
                    + cause);
        }
    }
}

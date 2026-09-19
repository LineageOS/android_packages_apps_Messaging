/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.SystemProperties;

import com.android.messaging.Factory;
import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsCooldownRecord;
import com.android.messaging.rcs.engine.mls.MlsEraBudgetRecord;
import com.android.messaging.rcs.engine.mls.MlsPeerHealthRecord;
import com.android.messaging.rcs.engine.mls.MlsReestablishPolicy;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.BudgetKey;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Facts;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Guard;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.RecordState;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Tier;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Verdict;
import com.android.messaging.rcs.engine.mls.MlsWindowBudget;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * The gate every MLS operation that transmits a state change passes before it can reach a peer:
 * G6 freeze, G4 peer health, G1 peer allowlist and G2 era budget. Which guards an operation takes
 * is the table in {@link MlsStateChangeGate}; this class gathers the facts, performs the store
 * effects and logs every refusal. It fails closed. Purely local cleanup (forgetting a conversation,
 * its resend history, its rendezvous state) is never gated: it is how we stop talking to a peer
 * whose client is already stuck. See docs/mls/budgets.md.
 */
public final class MlsPeerGuard {

    private static final String TAG = LogUtil.BUGLE_TAG;

    /** G6, the kill switch: one property that freezes every MLS state change. */
    private static final String PROP_FREEZE = "debug.rcs.mls_freeze_state_changes";

    /**
     * G1, the peer allowlist: comma-separated E.164 numbers, read only on a debug build. Unset or
     * empty restricts nothing; a user build has no allowlist at all
     * ({@link MlsStateChangeGate#allowlistFor}). {@code persist.} so the list survives a reboot.
     */
    private static final String PROP_ALLOWED_PEERS = "persist.rcs.mls_allowed_peers";

    /**
     * G2, the era-advance budget; the allowance and window arithmetic are in {@link
     * MlsEraBudgetRecord}.
     */
    private static final int MAX_ERA_ADVANCES_PER_HOUR = MlsEraBudgetRecord.MAX_PER_HOUR;
    private static final int MAX_ERA_ADVANCES_PER_DAY = MlsEraBudgetRecord.MAX_PER_DAY;

    /**
     * G4, consecutive failures toward a peer before we stop. Fed only by the peer's own reports
     * that our message failed on its side (RCC.16 §7.7.2.2), never by inference from silence;
     * cleared only by evidence the peer processed something of ours. A tripped G4 refuses
     * re-creations, joins and debug state changes, including an automatic rebuild; ordinary sends
     * continue so the peer can clear it, and Try again resets it.
     */
    private static final int MAX_CONSECUTIVE_PEER_FAILURES =
            MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES;

    private static final long HOUR_MS = MlsEraBudgetRecord.HOUR_MS;
    private static final long DAY_MS = MlsEraBudgetRecord.DAY_MS;

    /**
     * Our own preferences file. Not an {@code MlsPerConversationState}: a rebuild starts by
     * forgetting the conversation, and a budget cleared by teardown would erase its own evidence.
     */
    private static final String PREFS = "mls_peer_guard";

    /**
     * Key prefixes, one per record format: the keys are built from caller-supplied strings (group
     * id, number, canonical key), so a prefix keeps each decoder on its own format.
     */
    private static final String K_ERA = "era/";
    private static final String K_HEALTH = "health/";
    /** Rebuild episode suppressor, keyed by canonical conversation key. */
    private static final String K_EPISODE = "episode/";
    /** 1:1 re-establish cooldown, keyed by normalised peer. */
    private static final String K_REESTABLISH = "reestablish/";

    /**
     * Guards every read-modify-write over the store. Every {@link #load}, {@link #store} and
     * {@link #drop} runs inside it, so {@link #sFallbackStore} and {@link #sWarnedNoStore} need no
     * locking of their own.
     */
    private static final Object sStoreLock = new Object();

    /** Resolved lazily; null until the app context is reachable. */
    private static volatile SharedPreferences sPrefs;

    /**
     * Used only when no application context is reachable. Degrading to a process-local map is
     * better than refusing every state change (which would also refuse the remove that gets a stuck
     * peer out of a group) or allowing them uncounted. Announced once.
     */
    private static final Map<String, String> sFallbackStore = new HashMap<>();
    private static boolean sWarnedNoStore;

    private MlsPeerGuard() {}

    private static SharedPreferences prefs() {
        SharedPreferences p = sPrefs;
        if (p != null) return p;
        final Factory f = Factory.get();
        final Context c = (f == null) ? null : f.getApplicationContext();
        if (c == null) return null;
        p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sPrefs = p;
        return p;
    }

    /** Announce a process-local budget once. */
    private static void warnFallbackOnce() {
        if (sWarnedNoStore) return;
        sWarnedNoStore = true;
        LogUtil.e(TAG, "MlsPeerGuard: no application context — the era budget and the peer-health "
                + "streak are PROCESS-LOCAL for this run and will be forgotten if the app restarts. "
                + "They still bound this process, but the restart-survival property the durable "
                + "store exists for is absent until a context is reachable.");
    }

    /** Read one record, or null when nothing is stored under {@code key}. */
    private static String load(final String key) {
        final SharedPreferences p = prefs();
        if (p != null) return p.getString(key, null);
        warnFallbackOnce();
        return sFallbackStore.get(key);
    }

    /**
     * Write one record with {@code commit()}: the loop these budgets bound can kill the process, so
     * a charge must be on disk before the operation it pays for begins. Callers write only when the
     * value changes.
     */
    private static void store(final String key, final String value) {
        final SharedPreferences p = prefs();
        if (p == null) {
            warnFallbackOnce();
            sFallbackStore.put(key, value);
            return;
        }
        if (!p.edit().putString(key, value).commit()) {
            LogUtil.e(TAG, "MlsPeerGuard: commit() FAILED writing " + MlsConversationKey.forLog(key)
                    + " — this charge is not "
                    + "on disk and will not survive a restart. Treating the operation as charged "
                    + "anyway; the in-memory preference map still holds it for this process.");
        }
    }

    private static void drop(final String key) {
        final SharedPreferences p = prefs();
        if (p == null) {
            warnFallbackOnce();
            sFallbackStore.remove(key);
            return;
        }
        p.edit().remove(key).commit();
    }

    /** The health key for a normalised peer, or null when there is no peer. */
    private static String healthKey(final String normalisedPeer) {
        return (normalisedPeer == null || normalisedPeer.isEmpty()) ? null
                : K_HEALTH + normalisedPeer;
    }

    /** Digits only, so a number with and without its country prefix is one peer. */
    private static String norm(final String e164) {
        return MlsStateChangeGate.normalisePeer(e164);
    }

    /** The raw G1 list, read only on a debug build. */
    private static String allowedPeers() {
        return RcsDebug.isDebugBuild() ? SystemProperties.get(PROP_ALLOWED_PEERS, "") : "";
    }

    /**
     * May {@code op} transmit to {@code peerE164}? Returns false and logs if not. Call at the top
     * of every state-changing entry point, before any engine work.
     */
    public static boolean allowStateChange(final String op, final String peerE164) {
        return decide(Tier.ORGANIC_STATE_CHANGE, op, /*rcsGroupId=*/ null, peerE164);
    }

    /**
     * As {@link #allowStateChange}, plus the peer allowlist (G1). For anything a debug arm
     * initiates.
     */
    public static boolean allowDebugStateChange(final String op, final String peerE164) {
        return decide(Tier.ALLOWLISTED_STATE_CHANGE, op, /*rcsGroupId=*/ null, peerE164);
    }

    /**
     * As {@link #allowStateChange}, plus the peer allowlist (G1), for bringing a peer into an MLS
     * group whoever initiates it, a menu action included. A remove takes {@link #allowStateChange}:
     * refusing it would trap the peer that most needs to be out. G1 restricts only a debug build
     * whose {@code persist.rcs.mls_allowed_peers} is set; everywhere else it admits every peer.
     */
    public static boolean allowJoiningPeer(final String op, final String peerE164) {
        return decide(Tier.ALLOWLISTED_STATE_CHANGE, op, /*rcsGroupId=*/ null, peerE164);
    }

    /**
     * G6 alone, for our own departure from a conversation. G1 would refuse leaving a group that
     * holds a peer outside the allowlist, and G4 would refuse it exactly when a peer looks stuck,
     * which is when a person most wants out. The freeze still applies because leaving transmits a
     * SelfRemove proposal.
     */
    public static boolean allowSelfDeparture(final String op) {
        return decide(Tier.SELF_DEPARTURE, op, /*rcsGroupId=*/ null, /*peerE164=*/ null);
    }

    /**
     * G2: may we era-advance {@code groupId} now? Charged at the {@code eraAdvance} funnel for
     * every mode, which is exact: the era is GroupContext extension 0xF001 and a
     * GroupContextExtensions proposal cannot change it, so every mode ends in a create that
     * re-Welcomes every member (see {@code MlsEraAdvanceCharge}).
     */
    public static boolean allowEraAdvance(final String groupId, final String peerE164) {
        return allowEraAdvance("eraAdvance", groupId, peerE164);
    }

    /**
     * G2 for any operation that makes every member re-join by Welcome: the era advance, a rebuild
     * over a conversation the server still holds, the 1:1 reclaim. All charge the one budget;
     * {@code op} names the caller in the refusal line.
     */
    public static boolean allowEraAdvance(final String op, final String groupId,
            final String peerE164) {
        return decide(Tier.ERA_ADVANCE, op, groupId, peerE164);
    }

    /**
     * Read the store, ask {@link MlsStateChangeGate}, apply what it decided, log. Every entry point
     * is one line into here with its {@link Tier}. Facts are gathered for every guard the tier
     * composes before the gate is asked, so the consultation order lives only in the engine; ask
     * {@link MlsStateChangeGate#consults} rather than restating it here.
     */
    private static boolean decide(final Tier tier, final String op, final String groupId,
            final String peerE164) {
        final Facts.Builder facts = Facts.builder();

        // G6. An unset property reads false: not frozen.
        if (MlsStateChangeGate.consults(tier, Guard.G6_FREEZE)) {
            facts.frozen(SystemProperties.getBoolean(PROP_FREEZE, false));
        }

        final String n = norm(peerE164);

        // G1. A debug-build restriction only; with a list set, a blank peer is NOT_LISTED.
        if (MlsStateChangeGate.consults(tier, Guard.G1_PEER_ALLOWLIST)) {
            facts.allowlist(MlsStateChangeGate.allowlistFor(
                    RcsDebug.isDebugBuild(), allowedPeers(), peerE164));
        }

        final boolean readsHealth = MlsStateChangeGate.consults(tier, Guard.G4_PEER_HEALTH);
        final boolean readsBudget = MlsStateChangeGate.consults(tier, Guard.G2_ERA_BUDGET);
        final String healthKey = readsHealth ? healthKey(n) : null;
        final BudgetKey budgetKey =
                readsBudget ? MlsStateChangeGate.eraBudgetKey(groupId, peerE164) : null;
        final String eraStoreKey =
                (budgetKey != null && budgetKey.derived()) ? K_ERA + budgetKey.key() : null;

        // Elapsed time, never the wall clock; see MlsMonotonicAge.
        final long nowElapsed = SystemClock.elapsedRealtime();

        final Verdict verdict;
        String healthRaw = null;
        String eraRaw = null;
        int streak = 0;
        int perHour = 0;
        int perDay = 0;
        boolean chargeImpossible = false;

        // One read-modify-write under the one lock; the gate is pure and cannot block.
        synchronized (sStoreLock) {
            if (readsHealth) {
                if (healthKey == null) {
                    // No peer named: NOT_CONSULTED, distinct from a record that says zero.
                    facts.peerHealth(RecordState.NOT_CONSULTED, 0);
                } else {
                    healthRaw = load(healthKey);
                    final MlsPeerHealthRecord rec = MlsPeerHealthRecord.decode(healthRaw);
                    if (rec == null) {
                        facts.peerHealth(RecordState.UNREADABLE, 0);
                    } else {
                        streak = rec.streakAt(nowElapsed);
                        facts.peerHealth(RecordState.PRESENT, streak);
                        // Evidence expired: drop the record rather than keep one that answers 0.
                        if (rec.stale(nowElapsed)) drop(healthKey);
                    }
                }
            }

            MlsEraBudgetRecord pruned = null;
            if (readsBudget) {
                if (eraStoreKey == null) {
                    facts.eraBudget(budgetKey, RecordState.NOT_CONSULTED, false);
                } else {
                    eraRaw = load(eraStoreKey);
                    final MlsEraBudgetRecord stored = MlsEraBudgetRecord.decode(eraRaw);
                    if (stored == null) {
                        facts.eraBudget(budgetKey, RecordState.UNREADABLE, false);
                    } else {
                        pruned = stored.pruned(nowElapsed);
                        perHour = pruned.countWithin(HOUR_MS, nowElapsed);
                        perDay = pruned.countWithin(DAY_MS, nowElapsed);
                        facts.eraBudget(budgetKey, RecordState.PRESENT, pruned.spent(nowElapsed));
                    }
                }
            }

            verdict = MlsStateChangeGate.decide(tier, facts.build());

            // The effects the verdict named, still under the lock.
            if (verdict.discardRecord()) {
                if (verdict.guard() == Guard.G4_PEER_HEALTH && healthKey != null) drop(healthKey);
                if (verdict.guard() == Guard.G2_ERA_BUDGET && eraStoreKey != null) drop(
                        eraStoreKey);
            }
            if (verdict.mustChargeEraBudget()) {
                if (pruned == null || eraStoreKey == null) {
                    // Unreachable: CHARGE_AND_ALLOW implies a present record under a derived key.
                    // Refuse rather than proceed uncharged.
                    chargeImpossible = true;
                } else {
                    // Charged before the operation runs and on disk before this returns, so a crash
                    // still counts.
                    store(eraStoreKey, pruned.charged(nowElapsed).encode());
                }
            }
        }

        if (chargeImpossible) {
            LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op
                    + " — the gate allowed it ON CONDITION of "
                    + "an era-budget charge and there is no record to charge it to. That should be "
                    + "impossible; it is refused rather than allowed uncharged, because an "
                    + "unbudgeted re-creation is the operation that wedged a real person's phone. "
                    + "Something is wrong in MlsPeerGuard.decide's gathering.");
            return false;
        }

        if (!verdict.mayProceed()) {
            report(verdict, tier, op, peerE164, budgetKey, healthRaw, eraRaw, streak, perHour,
                    perDay);
        }
        return verdict.mayProceed();
    }

    /** The refusal line, one per {@link MlsStateChangeGate.Reason}. */
    private static void report(final Verdict verdict, final Tier tier, final String op,
            final String peerE164, final BudgetKey budgetKey, final String healthRaw,
            final String eraRaw, final int streak, final int perHour, final int perDay) {
        switch (verdict.reason()) {
            case STATE_CHANGES_FROZEN:
                if (tier == Tier.SELF_DEPARTURE) {
                    LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " — MLS state changes are "
                            + "FROZEN (" + PROP_FREEZE
                            + "=true). This is the kill switch; it stops "
                            + "our own departure too, deliberately, because a self-leave transmits "
                            + "a SelfRemove proposal.");
                } else {
                    LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " to " + redact(peerE164)
                            + " — MLS state changes are FROZEN (" + PROP_FREEZE + "=true).");
                }
                return;

            case PEER_HEALTH_UNREADABLE:
                // Unreadable is not healthy: refuse once and discard the record.
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " to " + redact(peerE164)
                        + " — its peer-health record is UNREADABLE (" + healthRaw + "). We cannot "
                        + "say this peer is healthy, and 'cannot say' must not be spelled the same "
                        + "way as 'no failures'. Discarding the record; the next attempt is judged "
                        + "on a clean streak.");
                return;

            case PEER_WEDGED:
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " to " + redact(peerE164) + " — "
                        + streak + " consecutive failed/unacknowledged operations. The peer looks "
                        + "WEDGED. Investigate before sending it more state; clear with "
                        + "notePeerRecovered() once it acknowledges again, or from the stalled-"
                        + "conversation notification's Try again. (The streak "
                        + "is on disk, so a force-stop does not "
                        + "declare the peer healthy.)");
                return;

            case PEER_NOT_ALLOWLISTED:
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " to " + redact(peerE164)
                        + " — NOT AN ALLOWED PEER. This debug build restricts MLS state changes "
                        + "to the numbers in " + PROP_ALLOWED_PEERS + "; clear it to lift that. "
                        + "This gate exists because we wedged a real person's phone for a month; "
                        + "if you are about to add a number here, be certain it is a device you "
                        + "own.");
                return;

            case NO_BUDGET_KEY:
                // No key means no bound: refuse.
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " — neither a group id nor a peer "
                        + "number was supplied, so there is nothing to charge the era budget against "
                        + "and the operation cannot be bounded at all.");
                return;

            case ERA_BUDGET_UNREADABLE:
                // Unreadable is not uncharged: refuse once and discard the record.
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " on " + redactKey(budgetKey)
                        + " — its era budget record is UNREADABLE (" + eraRaw
                        + "), so we cannot say "
                        + "this advance is within budget. Discarding the record; the next attempt is "
                        + "judged on an empty one.");
                return;

            case ERA_BUDGET_SPENT:
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " on " + redactKey(budgetKey)
                        + " — budget spent (" + perHour + "/" + MAX_ERA_ADVANCES_PER_HOUR
                        + " this hour, " + perDay + "/" + MAX_ERA_ADVANCES_PER_DAY
                        + " today). Every advance re-creates the group and makes EVERY member "
                        + "re-join via a Welcome; ~17 of these on one group is what wedged a peer "
                        + "for a month. Something is wrong upstream — fix that, do not raise the "
                        + "budget. A person can reset it from the stalled-conversation "
                        + "notification's Try again.");
                return;

            case TIER_NOT_CLASSIFIED:
            case FACT_NOT_SUPPLIED:
            case NOTHING_REFUSED:
            default:
                // Unanswerable: a tier or guard nobody declared. Fails closed, and says so.
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op
                        + " — the state-change gate could not "
                        + "ANSWER for tier " + tier + " (" + verdict + "). This is not a guard "
                        + "refusing an operation; it is a composition nothing has declared, so "
                        + "there is no basis on which to allow it. Fix MlsStateChangeGate.guardsFor "
                        + "or the facts gathered in MlsPeerGuard.decide.");
        }
    }

    /**
     * Clear a conversation's era budget because a person asked (Try again). Removes the persisted
     * record; never automatic.
     */
    public static void resetEraBudget(final String groupId, final String peerE164) {
        final BudgetKey budgetKey = MlsStateChangeGate.eraBudgetKey(groupId, peerE164);
        if (!budgetKey.derived()) return;
        final String storeKey = K_ERA + budgetKey.key();
        final int had;
        synchronized (sStoreLock) {
            final MlsEraBudgetRecord rec = MlsEraBudgetRecord.decode(load(storeKey));
            had = (rec == null) ? -1 : rec.size();
            drop(storeKey);
        }
        LogUtil.w(TAG, "MlsPeerGuard: era budget RESET for " + redactKey(budgetKey) + " by request "
                + "— " + (had < 0 ? "an unreadable record" : had + " advance(s)") + " forgotten. A "
                + "person asked for this; nothing here earned it back.");
    }

    /**
     * Diagnostics: era advances recorded against this conversation in the rolling day.
     *
     * @return the count, 0 when nothing is recorded, or -1 when a record exists and cannot be read
     */
    public static int eraAdvancesInWindow(final String groupId, final String peerE164) {
        final BudgetKey budgetKey = MlsStateChangeGate.eraBudgetKey(groupId, peerE164);
        if (!budgetKey.derived()) return 0;
        final long nowElapsed = SystemClock.elapsedRealtime();
        synchronized (sStoreLock) {
            final MlsEraBudgetRecord rec = MlsEraBudgetRecord.decode(load(K_ERA + budgetKey.key()));
            return rec == null ? -1 : rec.countWithin(DAY_MS, nowElapsed);
        }
    }

    /** Record that the peer reported an operation of ours failed (G4). */
    public static void notePeerFailure(final String peerE164) {
        final String healthKey = healthKey(norm(peerE164));
        if (healthKey == null) return;
        final long nowElapsed = SystemClock.elapsedRealtime();
        final int c;
        synchronized (sStoreLock) {
            final MlsPeerHealthRecord cur = MlsPeerHealthRecord.decode(load(healthKey));
            // An unreadable record is replaced: a streak cannot continue from a number we could not
            // read.
            final MlsPeerHealthRecord base = (cur == null) ? MlsPeerHealthRecord.NONE : cur;
            final MlsPeerHealthRecord next = base.withFailureAt(nowElapsed);
            c = next.streakAt(nowElapsed);
            // Write only on change: past the trip point the evidence stamp is coalesced.
            if (!next.sameAs(base)) store(healthKey, next.encode());
        }
        if (c == MAX_CONSECUTIVE_PEER_FAILURES) {
            LogUtil.w(TAG, "MlsPeerGuard: " + redact(peerE164) + " has now failed " + c
                    + " consecutive operations — further MLS state changes to it are BLOCKED. This "
                    + "streak is on disk: a restart does not clear it, and it expires "
                    + "only after " + (MlsPeerHealthRecord.EVIDENCE_MS / DAY_MS) + " day(s) of "
                    + "uptime with no further report from the peer.");
        }
    }

    /** Record that this peer processed something of ours; clears the failure streak (G4). */
    public static void notePeerRecovered(final String peerE164) {
        final String healthKey = healthKey(norm(peerE164));
        if (healthKey == null) return;
        synchronized (sStoreLock) {
            // Hot path (every positive group receipt): only an existing streak reaches the disk.
            if (load(healthKey) == null) return;
            drop(healthKey);
        }
        LogUtil.i(TAG, "MlsPeerGuard: " + redact(peerE164) + " acknowledged something of ours — "
                + "peer-health streak CLEARED.");
    }

    /**
     * Clear this peer's failure streak because a person asked (Try again). Not
     * {@link #notePeerRecovered}, whose log line asserts the peer processed something of ours.
     */
    public static void resetPeerHealth(final String peerE164) {
        final String healthKey = healthKey(norm(peerE164));
        if (healthKey == null) return;
        final int had;
        synchronized (sStoreLock) {
            final MlsPeerHealthRecord rec = MlsPeerHealthRecord.decode(load(healthKey));
            had = (rec == null) ? -1 : rec.streakAt(SystemClock.elapsedRealtime());
            drop(healthKey);
        }
        LogUtil.w(TAG, "MlsPeerGuard: peer-health streak RESET for " + redact(peerE164) + " by "
                + "request — " + (had < 0 ? "an unreadable record" : had + " failure(s)")
                + " forgotten. A person asked for this; the peer has not told us anything.");
    }

    /**
     * Diagnostics: consecutive failures recorded for a peer.
     *
     * @return the streak, 0 when none is recorded or the evidence expired, or -1 when a record
     *     exists and cannot be read
     */
    public static int peerFailureCount(final String peerE164) {
        final String healthKey = healthKey(norm(peerE164));
        if (healthKey == null) return 0;
        final long nowElapsed = SystemClock.elapsedRealtime();
        synchronized (sStoreLock) {
            final MlsPeerHealthRecord rec = MlsPeerHealthRecord.decode(load(healthKey));
            return rec == null ? -1 : rec.streakAt(nowElapsed);
        }
    }

    // The rebuild episode suppressor and the 1:1 re-establish cooldown share this store, its lock,
    // fallback and commit() discipline; MlsCooldownRecord holds the codec and clock rule.

    /**
     * May a rebuild start a new episode for this conversation, and if so, stamp it. Not the rate
     * limit ({@code MlsRebuildLimiter}, over hours): this stops one burst spending that allowance
     * on a single fault, so it is asked first and a suppressed attempt charges nothing. Durable,
     * because a crash-restart loop is a burst; {@code resetRebuildRateBound} clears it.
     *
     * @return true if the caller may rebuild now
     */
    public static boolean claimRebuildEpisode(final String conversationKey) {
        return claimRebuildEpisode(conversationKey, rebuildEpisodeWindowMs());
    }

    /**
     * The episode window, {@link MlsWindowBudget#REBUILD_EPISODE_MS}, so callers never spell it.
     */
    public static long rebuildEpisodeWindowMs() {
        return MlsWindowBudget.REBUILD_EPISODE_MS;
    }

    /** Takes the window explicitly; production uses the overload above. */
    public static boolean claimRebuildEpisode(final String conversationKey, final long episodeMs) {
        if (conversationKey == null || conversationKey.isEmpty()) {
            // No key means no record: refuse.
            LogUtil.w(TAG,
                    "MlsPeerGuard: refusing a rebuild episode with no conversation key — the "
                    + "suppressor cannot record an attempt it cannot address, and an unrecorded "
                    + "burst is what spends hours of the rate allowance in seconds.");
            return false;
        }
        final String storeKey = K_EPISODE + conversationKey;
        final long nowElapsed = SystemClock.elapsedRealtime();
        final long remaining;
        synchronized (sStoreLock) {
            final MlsCooldownRecord rec = MlsCooldownRecord.decode(load(storeKey));
            if (rec == null) {
                // Unreadable is neither "no episode" nor "suppressed": refuse once and discard.
                drop(storeKey);
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING a rebuild episode for "
                        + MlsConversationKey.forLog(conversationKey)
                        + " — its episode record is UNREADABLE, so we cannot say whether one was "
                        + "just attempted. Discarding it; the next attempt is judged on an empty "
                        + "record.");
                return false;
            }
            remaining = rec.remainingMs(episodeMs, nowElapsed);
            if (remaining <= 0L) store(storeKey,
                    MlsCooldownRecord.attemptedAt(nowElapsed).encode());
        }
        if (remaining > 0L) {
            LogUtil.i(TAG, "MlsPeerGuard: NOT starting a rebuild episode for "
                    + MlsConversationKey.forLog(conversationKey)
                    + " — one was attempted " + ((episodeMs - remaining) / 1000L) + "s ago and "
                    + "nothing has changed since. This is one fault, not several, and charging the "
                    + "rate allowance again would spend hours of it on a single burst. Next "
                    + "eligible in " + (remaining / 1000L) + "s of uptime; Try again clears it.");
            return false;
        }
        return true;
    }

    /**
     * Diagnostics: how long ago this conversation's last rebuild attempt was stamped.
     *
     * @return the age in ms, {@link Long#MAX_VALUE} when nothing is recorded, or -1 when a record
     *     exists and cannot be read
     */
    public static long rebuildEpisodeAgeMs(final String conversationKey) {
        if (conversationKey == null || conversationKey.isEmpty()) return Long.MAX_VALUE;
        final long nowElapsed = SystemClock.elapsedRealtime();
        synchronized (sStoreLock) {
            final MlsCooldownRecord rec =
                    MlsCooldownRecord.decode(load(K_EPISODE + conversationKey));
            return rec == null ? -1L : rec.ageMs(nowElapsed);
        }
    }

    /**
     * Clear this conversation's rebuild episode, from {@code resetRebuildRateBound}: both refuse
     * the same operation, so clearing one without the other would do nothing.
     */
    public static void resetRebuildEpisode(final String conversationKey) {
        if (conversationKey == null || conversationKey.isEmpty()) return;
        final long had;
        synchronized (sStoreLock) {
            final MlsCooldownRecord rec =
                    MlsCooldownRecord.decode(load(K_EPISODE + conversationKey));
            had = rec == null ? -1L : rec.ageMs(SystemClock.elapsedRealtime());
            drop(K_EPISODE + conversationKey);
        }
        LogUtil.i(TAG, "MlsPeerGuard: rebuild episode suppressor CLEARED for "
                + MlsConversationKey.forLog(conversationKey)
                + " by request — " + (had < 0L ? "an unreadable record"
                        : had == Long.MAX_VALUE ? "nothing was recorded"
                        : "an attempt " + (had / 1000L) + "s ago") + " forgotten.");
    }

    /**
     * May we drive an outbound re-establish toward this peer, and if so, stamp it. Most attempts
     * claim one of the peer's one-time KeyPackages, so this protects the peer's pool. It meters
     * attempts rather than claims, which over-protects and never under-protects. Independent of
     * {@code MlsClaimLedger.WINDOW_MS}, the same ten minutes; on this path the ledger's ration of
     * two never binds, but other {@code ensureReady} callers skip this cooldown.
     *
     * @return true if the caller may re-establish now
     */
    public static boolean claimReestablishAttempt(final String peerE164) {
        return claimReestablishAttempt(peerE164, reestablishCooldownMs());
    }

    /** The cooldown, {@link MlsReestablishPolicy#REESTABLISH_COOLDOWN_MS}. */
    public static long reestablishCooldownMs() {
        return MlsReestablishPolicy.REESTABLISH_COOLDOWN_MS;
    }

    /** Takes the window explicitly; production uses the overload above. */
    public static boolean claimReestablishAttempt(final String peerE164, final long cooldownMs) {
        final String peer = norm(peerE164);
        if (peer.isEmpty()) {
            LogUtil.w(TAG, "MlsPeerGuard: refusing an outbound re-establish with no peer — the "
                    + "cooldown cannot record an attempt it cannot address, and each attempt claims "
                    + "one of the peer's KeyPackages.");
            return false;
        }
        final String storeKey = K_REESTABLISH + peer;
        final long nowElapsed = SystemClock.elapsedRealtime();
        final long remaining;
        synchronized (sStoreLock) {
            final MlsCooldownRecord rec = MlsCooldownRecord.decode(load(storeKey));
            if (rec == null) {
                drop(storeKey);
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING an outbound re-establish with "
                        + redact(peerE164)
                        + " — its cooldown record is UNREADABLE, so we cannot say "
                        + "whether one was just attempted, and each attempt claims one of their "
                        + "KeyPackages. Discarding it; the next attempt is judged on an empty "
                        + "record.");
                return false;
            }
            remaining = rec.remainingMs(cooldownMs, nowElapsed);
            if (remaining <= 0L) store(storeKey,
                    MlsCooldownRecord.attemptedAt(nowElapsed).encode());
        }
        if (remaining > 0L) {
            LogUtil.i(TAG, "MlsPeerGuard: not re-establishing with " + redact(peerE164)
                    + " — the last attempt was " + ((cooldownMs - remaining) / 1000L) + "s ago and "
                    + "each attempt claims one of their KeyPackages. Next eligible in "
                    + (remaining / 1000L) + "s of uptime.");
            return false;
        }
        return true;
    }

    /** Clear this peer's re-establish cooldown because a person asked (Try again). */
    public static void resetReestablishCooldown(final String peerE164) {
        final String peer = norm(peerE164);
        if (peer.isEmpty()) return;
        final long had;
        synchronized (sStoreLock) {
            final MlsCooldownRecord rec = MlsCooldownRecord.decode(load(K_REESTABLISH + peer));
            had = rec == null ? -1L : rec.ageMs(SystemClock.elapsedRealtime());
            drop(K_REESTABLISH + peer);
        }
        LogUtil.i(TAG, "MlsPeerGuard: re-establish cooldown CLEARED for " + redact(peerE164)
                + " by request — " + (had < 0L ? "an unreadable record"
                        : had == Long.MAX_VALUE ? "nothing was recorded"
                        : "an attempt " + (had / 1000L) + "s ago") + " forgotten.");
    }

    /** Test and debug hook: forget all recorded history, on disk as well as in memory. */
    public static void resetForTest() {
        synchronized (sStoreLock) {
            sFallbackStore.clear();
            final SharedPreferences p = prefs();
            if (p != null) p.edit().clear().commit();
        }
    }

    /** A budget key for the log: a 1:1 key is a phone number with a prefix, so it is redacted. */
    private static String redactKey(final BudgetKey budgetKey) {
        if (budgetKey == null || budgetKey.key() == null) return "";
        // By the derivation's reported source, not by the string's prefix.
        return budgetKey.source() == MlsStateChangeGate.KeySource.PEER
                ? "peer:" + LogMask.number(budgetKey.key().substring("peer:".length()))
                : budgetKey.key();
    }

    /** Last four digits only. */
    private static String redact(final String e164) {
        return LogMask.number(e164);
    }
}

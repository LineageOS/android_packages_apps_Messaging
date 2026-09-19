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
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.SystemProperties;

import com.android.messaging.Factory;
import com.android.messaging.rcs.engine.mls.MlsCooldownRecord;
import com.android.messaging.rcs.engine.mls.MlsEraBudgetRecord;
import com.android.messaging.rcs.engine.mls.MlsPeerHealthRecord;
import com.android.messaging.rcs.engine.mls.MlsReestablishPolicy;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Allowlist;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.BudgetKey;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Facts;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Guard;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.RecordState;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Tier;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Verdict;
import com.android.messaging.rcs.engine.mls.MlsWindowBudget;
import com.android.messaging.util.LogUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The gate every MLS STATE-CHANGING operation must pass before it can reach a peer.
 *
 * <h2>Why this exists — read the incident before relaxing anything here</h2>
 *
 * <p>Between 2026-08-04 and 2026-09-07 our experimentation <b>wedged a real third party's personal
 * iPhone for a month</b>. RCS stopped working for that peer, the failure spread to unrelated
 * conversations so its owner stopped receiving messages from other people, the modem reset itself
 * several times a day, and they had to disable RCS outright to keep receiving SMS.
 *
 * <p>What we did, from our own records: we added that number to our MLS test groups
 * and then churned those groups — era advances to era 3 (x9), era 5 (x3), era 4 (x2), era 2 (x2),
 * era 7 (x1), plus repeated {@code establishGroup} creates that were all "accepted-and-discarded",
 * plus removes and re-adds. <b>Every era advance re-creates the group and requires every member to
 * re-join via a Welcome.</b> We made a third-party client rebuild MLS group state ~20 times, much of
 * it invalid operations it could never complete.
 *
 * <p>And on 2026-08-05 we <b>wrote down that it was wedging</b> — "it had just failed both a remove
 * and a re-add from its own UI", "it may still be locally wedged" — filed that as a caveat, and kept
 * going, because the headline we were chasing was that a wedged advancer does not wedge the GROUP.
 * <b>Our success metric was group liveness; nobody was measuring peer health.</b> That is the
 * process failure this class exists to make impossible to repeat.
 *
 * <h2>The rule</h2>
 *
 * <p><b>FAIL CLOSED.</b> A peer that is not a known lab device is not reachable by a state change,
 * full stop. The number we wedged was never one of our own test lines — it was a personal
 * phone — so an allowlist seeded from the test fleet would have prevented the whole incident on day one.
 *
 * <p><b>This gate is for operations that TRANSMIT.</b> Purely local cleanup ({@code forget},
 * {@code forgetResendHistory}, {@code forgetRendezvous}) must NEVER be gated: those are the recovery
 * path, and the one thing that must keep working when a peer is already wedged is our ability to
 * stop talking to it.
 *
 * <h2>TWO TIERS, AND THE DISTINCTION IS LOAD-BEARING</h2>
 *
 * <p>This is a shipping RCS client. Real users message arbitrary numbers, so an allowlist applied to
 * every state change would break the product outside the lab. The damage was not done by the
 * protocol operating normally — it was done by US DRIVING DEBUG ARMS BY HAND, dozens of times. So:
 *
 * <ul>
 *   <li>{@link #allowDebugStateChange} — for anything initiated by a debug arm or an experiment.
 *       Enforces the LAB ALLOWLIST on top of everything below. A non-lab number is unreachable.</li>
 *   <li>{@link #allowStateChange} — for organic protocol operations. NO allowlist (that would break
 *       real messaging), but the kill switch, the era-advance budget and the peer-health gate all
 *       still apply, because ~17 era advances on one group is pathological however it was triggered,
 *       and a peer that has stopped acknowledging should stop receiving state changes whoever it
 *       belongs to. Those three protect real users too, not just the lab.</li>
 * </ul>
 *
 * <h2>WHERE THE COMPOSITION LIVES — {@link MlsStateChangeGate}, not here</h2>
 *
 * <p>Four entry points, four tiers, and which of G1/G2/G4/G6 each one composes is a value in the
 * engine rather than the shape of four methods calling each other in this file. That move is the
 * whole of that design decision, and the reason is that <b>three separate defects each got exactly
 * one row of that table wrong</b> — the rebuild reached an era advance without G2, and G2 never
 * charged a 1:1 at all; an ADD from a menu row skipped G1; a
 * self-departure was about to be given the tier that refuses a person's escape exactly when the peer
 * looks wedged. None was a wrong answer from a budget; each was the wrong SET of budgets consulted,
 * and until that move the set was not a value anywhere, so nothing could assert it.
 *
 * <p><b>What stayed here is everything with a side effect</b> — {@code prefs()}/{@code load}/
 * {@code store}/{@code drop}, the {@code SystemProperties} reads, the redaction and every refusal
 * line. The storage decisions are untouched; moving them behind an engine port was considered and
 * rejected for that reason.
 */
public final class MlsPeerGuard {

    private static final String TAG = LogUtil.BUGLE_TAG;

    /**
     * G6 — the kill switch. One sysprop that freezes every MLS state change fleet-wide.
     *
     * <p>During the incident there was no way to stop the bleeding short of not running commands.
     */
    private static final String PROP_FREEZE = "debug.rcs.mls_freeze_state_changes";

    /**
     * G1 — the lab allowlist. Comma-separated E.164s; overrides {@link #DEFAULT_LAB_PEERS}.
     *
     * <p>{@code persist.} on purpose: a safety allowlist that evaporates on reboot is not one.
     */
    private static final String PROP_LAB_PEERS = "persist.rcs.mls_lab_peers";

    /**
     * The test fleet. Both our own lines and the lines running Google Messages — those devices are
     * IRREPLACEABLE and wedging one would be worse than what we
     * already did, so they are in scope for every guard here, not exempt from them.
     */
    private static final String[] DEFAULT_LAB_PEERS = {
        "+15715550100", "+15715550101", "+15715550102", "+15715550103",
        "+15715550104", "+15715550105", "+15715550106", "+15715550107",
        "+15715550108", "+15715550109", "+15715550110", "+15715550111",
        "+15715550112", "+15715550113",
    };

    /**
     * G2 — era-advance budget per conversation. We performed ~17 on one group.
     *
     * <p>The allowance and the window arithmetic live in {@link MlsEraBudgetRecord}, in the engine
     * module, because they must be host-testable: the property that matters is that a charge
     * SURVIVES A PROCESS RESTART, and a device is the one place that is expensive to check.
     */
    private static final int MAX_ERA_ADVANCES_PER_HOUR = MlsEraBudgetRecord.MAX_PER_HOUR;
    private static final int MAX_ERA_ADVANCES_PER_DAY = MlsEraBudgetRecord.MAX_PER_DAY;

    /**
     * G4 — consecutive unacknowledged/failed operations toward a peer before we stop.
     *
     * <h3>What feeds this, and what must never feed it</h3>
     *
     * <p>This rung shipped with the rest of the class and then sat INERT for a day: nothing called
     * {@link #notePeerFailure}, so the map was always empty, {@link #peerFailureCount} always
     * returned 0, and the gate read "every peer is healthy, always" — the exact posture the class
     * was written to end. A health signal that always answers "fine" is worse than none, because
     * the next reader trusts it; {@code MlsAdvancerElection} had already had to reject it for that
     * reason.
     *
     * <p><b>THE RULE FOR PRODUCERS: observed negatives only, never inference from silence.</b> The
     * obvious candidates — a send that draws no receipt, an expired send-gate deadline, a Welcome
     * the peer never applies — all infer failure from ABSENCE, and every one of them fires on a
     * healthy peer that spent an afternoon offline. Three in a row is easy, and a false positive
     * here refuses state changes on a real user's working conversation. The producer that exists is
     * {@code MlsProviderTransport.onPeerReportedFailure}: the peer ITSELF reporting that our message
     * failed on ITS side (§7.7.2.2). An unreachable peer sends no reports, so silence yields zero
     * failures rather than three — which is what makes a streak safe to fail closed on.
     *
     * <p><b>AND THE RULE FOR THE CLEARING HALF: affirmative evidence the peer processed something
     * of OURS.</b> Not "we heard from it". The peer we wedged was loudly present for the whole
     * month — it was sending failure reports — so any clear keyed on mere presence would be reset by
     * the very traffic that should raise the count, and G4 would be inert again while LOOKING
     * wired. The two clearing producers are a positive IMDN
     * ({@code releaseSealedOnGroupReceipt} — it read something of ours) and a
     * {@code commit/proposal-processed-in-*-self-heal} outcome token — it processed our commit.
     *
     * <p><b>BLAST RADIUS, so a future change can weigh it.</b> {@link #allowStateChange} is reached
     * from {@link #allowEraAdvance} (every operation that makes a peer re-join by Welcome — the era
     * advance, the automatic rebuild, the 1:1 reclaim), from {@link #allowJoiningPeer} (bringing a
     * peer INTO a group) and from {@link #allowDebugStateChange}. So a tripped G4 refuses those and
     * nothing else — not ordinary sends, not resends, not local cleanup. That is deliberate and it
     * is the point: re-creating the group is the operation that did the damage, and ordinary traffic
     * must keep flowing precisely so the peer can acknowledge and clear the streak.
     *
     * <p><b>AND IT CAN NOW BLOCK A REPAIR, which is a real cost and is accepted deliberately.</b>
     * The automatic rebuild charges {@link #allowEraAdvance}, so a peer that
     * has told us three times that our control messages fail on its side will not be sent a fourth
     * re-Welcome — including the one our recovery ladder wants to send. That is the
     * rule ("peer stopped acknowledging must be a STOP condition, not a footnote") applied to the
     * operation that did the damage, and the exit is not "wait for a peer that cannot answer": the
     * stalled-conversation notification surfaces it and its <b>Try again</b> clears both this streak
     * and the era budget. Automatic fails closed; a person can say otherwise.
     *
     * <p><b>Now DURABLE, which is why it also has an evidence window</b>.
     * {@link MlsPeerHealthRecord} explains why those two are one decision and not two: an in-memory
     * total already decayed at every process restart, so persisting it without a bound would replace
     * an arbitrary decay with none at all, on the rung that gates the last step of automatic repair.
     */
    private static final int MAX_CONSECUTIVE_PEER_FAILURES =
            MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES;

    private static final long HOUR_MS = MlsEraBudgetRecord.HOUR_MS;
    private static final long DAY_MS = MlsEraBudgetRecord.DAY_MS;

    /**
     * WHERE THE BUDGETS LIVE — our own preferences file, on disk.
     *
     * <p>Both counters used to be static maps, so the budget that bounds "how often may a peer be
     * made to re-join by Welcome" forgot everything the moment the app died — and <b>the loop it
     * bounds is one that kills the process</b>. {@code MlsRebuildLimiter} is on disk for precisely
     * that reason and says so; the two bound the SAME operation, so leaving
     * one durable and the other not meant a crash-restart loop spent unlimited G2 slots while the
     * on-disk limiter kept counting. The incident's ~17 advances ran over three weeks, across many
     * process lifetimes; an in-memory day-window could not have counted them.
     *
     * <p><b>Its own file, and NOT an {@code MlsPerConversationState}</b> — the same reason
     * {@code MlsRebuildLimiter} gives and the reason is load-bearing: a rebuild's first act is to
     * forget the conversation, so a budget cleared by teardown would erase its own evidence, read
     * zero on the next attempt, and let the loop it bounds run free.
     */
    private static final String PREFS = "mls_peer_guard";

    /**
     * Key prefixes inside {@link #PREFS}. Four record formats share one file, and the key spaces are
     * built from caller-supplied strings — a group id, a phone number, a canonical conversation
     * key — so none is safe to assume distinct from the others. A prefix each means a decoder is
     * never handed another's format.
     */
    private static final String K_ERA = "era/";
    private static final String K_HEALTH = "health/";
    /** The rebuild EPISODE suppressor's stamp, keyed by CANONICAL CONVERSATION KEY. */
    private static final String K_EPISODE = "episode/";
    /** The 1:1 re-establish cooldown's stamp, keyed by normalised peer. */
    private static final String K_REESTABLISH = "reestablish/";

    /**
     * Guards read-modify-write over the store — and it is the ONLY thing that does, so
     * {@link #sFallbackStore} and {@link #sWarnedNoStore} need no locking of their own: every
     * {@link #load}, {@link #store} and {@link #drop} in this class runs inside it.
     */
    private static final Object sStoreLock = new Object();

    /** Resolved lazily and cached; null until the app context is reachable. */
    private static volatile SharedPreferences sPrefs;

    /**
     * The fallback store, used only when no application context is reachable.
     *
     * <p>{@code MlsRebuildLimiter} REFUSES when it cannot record, and that is right for it — it has
     * no other bound to fall back on. This class does: a process-local map is exactly the bound that
     * shipped before this change, so degrading to it is strictly better than either refusing every
     * state change (which would also refuse the REMOVE that gets a wedged peer out of a group, the
     * first thing the incident's own remediation reached for) or allowing them uncounted. It is
     * announced once, loudly, so nobody reads a bound as durable when it is not.
     */
    private static final Map<String, String> sFallbackStore = new HashMap<>();
    private static boolean sWarnedNoStore;

    private MlsPeerGuard() {}

    // ---- the store -----------------------------------------------------------------------------

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

    /** Announce a process-local budget ONCE. A silent degradation is how a guard stops being one. */
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
     * Write one record.
     *
     * <p><b>{@code commit()}, not {@code apply()}</b>, and the difference is the whole point:
     * {@code apply()} returns as soon as the value is in memory and flushes to disk on a background
     * thread, which survives an orderly process death and not the other kind. The loop this budget
     * bounds is the one that KILLS THE PROCESS — a reproducible crash on the rebuild path — so the
     * charge has to be on disk before the operation it pays for begins. Losing the last N charges to
     * a crash is exactly the failure mode being fixed.
     *
     * <p>The cost is bounded by the budgets themselves: an era charge can happen at most
     * {@link MlsEraBudgetRecord#MAX_PER_DAY} times a day per conversation, and every other write
     * here happens only when the stored value actually CHANGES — which is what keeps
     * {@link #notePeerRecovered}, called on every positive group receipt, off the disk entirely for
     * the peers that have no streak (i.e. almost all of them, almost always).
     */
    private static void store(final String key, final String value) {
        final SharedPreferences p = prefs();
        if (p == null) {
            warnFallbackOnce();
            sFallbackStore.put(key, value);
            return;
        }
        if (!p.edit().putString(key, value).commit()) {
            LogUtil.e(TAG, "MlsPeerGuard: commit() FAILED writing " + key + " — this charge is not "
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

    /** The health key for a normalised peer, or null when there is no peer to key on. */
    private static String healthKey(final String normalisedPeer) {
        return (normalisedPeer == null || normalisedPeer.isEmpty()) ? null
                : K_HEALTH + normalisedPeer;
    }

    /**
     * Digits-only comparison, so "+1571…" and "1571…" are the same peer.
     *
     * <p>In the engine, because it is the first half of
     * {@code eraBudgetKey}'s {@code peer:<digits>} derivation — the half that was found
     * wrong — and a normaliser that decides which budget a 1:1 is charged to should be exercised by
     * the same tests as the derivation it feeds.
     */
    private static String norm(final String e164) {
        return MlsStateChangeGate.normalisePeer(e164);
    }

    private static Set<String> labPeers() {
        final Set<String> out = new HashSet<>();
        final String override = SystemProperties.get(PROP_LAB_PEERS, "");
        final String[] src = (override == null || override.trim().isEmpty())
                ? DEFAULT_LAB_PEERS : override.split(",");
        for (final String s : src) {
            final String n = norm(s);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /**
     * May {@code op} transmit to {@code peerE164}? Returns false and logs LOUDLY if not.
     *
     * <p>Call this at the top of every state-changing entry point, before any engine work.
     */
    public static boolean allowStateChange(final String op, final String peerE164) {
        return decide(Tier.ORGANIC_STATE_CHANGE, op, /*rcsGroupId=*/ null, peerE164);
    }

    /**
     * As {@link #allowStateChange}, plus the LAB ALLOWLIST (G1). Use for anything a debug arm or an
     * experiment initiates — the category that actually caused the incident.
     */
    public static boolean allowDebugStateChange(final String op, final String peerE164) {
        return decide(Tier.ALLOWLISTED_STATE_CHANGE, op, /*rcsGroupId=*/ null, peerE164);
    }

    /**
     * As {@link #allowStateChange}, plus the LAB ALLOWLIST (G1) — for an operation that brings a
     * peer <b>into</b> an MLS group, <i>whoever</i> initiated it, including a user tapping a menu
     * row.
     *
     * <h3>Why an ADD gets the allowlist and a REMOVE does not</h3>
     *
     * <p>Item 1 of the incident report is literally this operation: "WE PUT THE IPHONE
     * IN OUR MLS GROUPS — {@code ADDED +1202…1468 to MLS group 22ac7628 … era=1}". That log line is
     * {@code MlsProviderTransport.mlsMembershipChange}'s success line, and the number it names was a
     * personal phone. Everything that followed — the era churn, the re-Welcomes, the month of
     * degraded RCS — needed the peer to be in the group first. So "may this number be brought into
     * MLS at all?" is exactly the question G1 was written to answer, and it is the same question
     * whether a debug arm or a menu row asks it.
     *
     * <p>The mirror operation is deliberately NOT gated this way. Refusing to REMOVE a non-lab peer
     * would trap the one peer you most need out of the group, and the incident's own remediation
     * reached for removal first. A removal takes {@link #allowStateChange}: the kill switch and the
     * peer-health streak still apply, the allowlist does not.
     *
     * <p><b>This is the conservative reading, and it has a cost worth stating.</b> The class doc
     * argues the allowlist must not touch organic product operations, because a shipping client's
     * users message arbitrary numbers. That argument is right for a shipping client and this is not
     * one yet: today every number this build reaches is either fleet or someone we must not touch.
     * When that changes, the one-line relaxation is to point this method at
     * {@link #allowStateChange}; {@code persist.rcs.mls_lab_peers} is the per-device escape hatch
     * in the meantime.
     */
    public static boolean allowJoiningPeer(final String op, final String peerE164) {
        return decide(Tier.ALLOWLISTED_STATE_CHANGE, op, /*rcsGroupId=*/ null, peerE164);
    }

    /**
     * <b>G6 ALONE</b> — the kill switch and nothing else. For OUR OWN DEPARTURE from a conversation
     * (a gating decision deliberately left to whoever wired the
     * row).
     *
     * <h3>Why leaving cannot take either of the tiers above</h3>
     *
     * <p>Both of them are questions about a COUNTERPARTY, and a self-leave has none — its only
     * identifier addresses the conversation we are getting out of:
     *
     * <ul>
     *   <li>{@link #allowJoiningPeer}'s allowlist would refuse our own departure from a group that
     *       contains a non-lab peer. That is backwards: leaving is how you STOP talking to a number
     *       you should not have been talking to, and it is the remediation the incident reached for
     *       first.</li>
     *   <li>{@link #allowStateChange}'s peer-health streak (G4) would refuse it precisely when the
     *       peer looks wedged — which is when a person most wants out. It would also refuse a GROUP
     *       departure because of ONE member's streak, since a group has no single peer to charge.</li>
     * </ul>
     *
     * <p>So the only defensible gate is the fleet-wide freeze, which means what it says: stop
     * everything. It is kept rather than dropped because a self-leave DOES transmit — a SelfRemove
     * proposal on {@code KickGroupUsers} — and the kill switch exists so that one sysprop stops
     * every outbound MLS state change during an incident, including the well-intentioned ones.
     *
     * <p><b>Do not reach for {@link #allowStateChange} here because it is nearest.</b> That is the
     * mistake this method exists to make impossible: it looks like the neutral choice and it is the
     * one that traps a person in a group with a peer that has stopped answering.
     */
    public static boolean allowSelfDeparture(final String op) {
        return decide(Tier.SELF_DEPARTURE, op, /*rcsGroupId=*/ null, /*peerE164=*/ null);
    }

    /**
     * G2 — may we era-advance {@code groupId} right now?
     *
     * <p>Separate from {@link #allowStateChange} because the era advance is the specific operation
     * that did the damage: it re-creates the group and forces every member to re-join.
     *
     * <p><b>This bounds era advances of EVERY mode, and that is exact rather than
     * conservative.</b> {@code MlsProviderTransport} charges here at the {@code eraAdvance}
     * funnel, above the branch that picks between the legacy create and
     * {@code eraAdvancePreserving} — and the preserving arm sends {@code welcome = new byte[0]} and
     * reports <i>"membership untouched, no Welcome needed"</i>, which reads as an over-charge. It is
     * not one: an era advance <b>cannot</b> be a commit (RCC.16 design §9.2 — the Era is
     * GroupContext extension {@code 0xF001} and a GroupContextExtensions proposal may neither change
     * nor remove it), so the engine refuses to build a preserving advance and every mode falls
     * through to the create <i>within the same call</i>. What reaches the server always re-Welcomes
     * every member. {@code MlsEraAdvanceCharge} holds that reasoning per mode and is host-tested;
     * a mode it does not classify is refused at the funnel rather than charged on an unstated basis.
     */
    public static boolean allowEraAdvance(final String groupId, final String peerE164) {
        return allowEraAdvance("eraAdvance", groupId, peerE164);
    }

    /**
     * G2, for any operation that makes every member of a conversation RE-JOIN BY WELCOME.
     *
     * <h3>ONE budget, several doors</h3>
     *
     * <p>The era advance is not the only way to inflict this cost. {@code rebuildConversation} drops
     * both halves of our state and re-establishes over a conversation the server still holds, which
     * lands above the server's era and re-Welcomes everyone; {@code ensureReady}'s reclaim arm does
     * the same for a 1:1. Those were bounded by their own limiters and charged nothing here, so a
     * conversation could be re-created a dozen times a day while G2's counter — the one written
     * because ~17 re-creations wedged a real phone — read zero.
     *
     * <p>They all charge THIS deque now. Two limiters counting one peer-facing cost with separate
     * accounting is how a total of seventeen stays plausible while both read "within budget", and it
     * is the same shape that made a takeover unreachable elsewhere. {@code op} exists so the
     * refusal log names which door was closed rather than always claiming "eraAdvance".
     */
    public static boolean allowEraAdvance(final String op, final String groupId,
            final String peerE164) {
        return decide(Tier.ERA_ADVANCE, op, groupId, peerE164);
    }

    // ---- the one decision path ------------------------------------------------------------------

    /**
     * <b>Read the store, ask {@link MlsStateChangeGate}, apply what it decided, say what happened.</b>
     * Every entry point above is one line into here with its {@link Tier}.
     *
     * <h3>Why the composition is not in this file any more</h3>
     *
     * <p>It used to be four methods calling each other, and "which of G1/G2/G4/G6 applies to this
     * operation" was answerable only by reading them in order. Three separate defects each got
     * exactly one row of that table wrong, and none of the three was a
     * wrong answer from a budget — each was the wrong SET of budgets consulted, and that set was not
     * a value anywhere, so nothing could assert it. It is a value now:
     * {@link MlsStateChangeGate#guardsFor} is the table, and this method asks it rather than
     * restating it. <b>Do not re-order the gathering below to "match" the guards; ask
     * {@link MlsStateChangeGate#consults} instead</b>, or there are two tables again.
     *
     * <h3>What stays here, and it is everything with a side effect</h3>
     *
     * <p>The {@code SystemProperties} reads, {@code prefs()}/{@code load}/{@code store}/{@code drop},
     * the redaction and every refusal line. The storage decisions are untouched —
     * its own preferences file, {@code commit()} not {@code apply()}, stamped with
     * {@code SystemClock.elapsedRealtime()} and aged by {@code MlsMonotonicAge} rather than the wall
     * clock. Moving the store behind an engine port was considered and rejected for
     * exactly that reason.
     *
     * <h3>One deliberate difference from the four methods this replaces</h3>
     *
     * <p>Facts are gathered for every guard the tier composes BEFORE the gate is asked, so a record
     * is now read even when an earlier guard is going to refuse. A {@code SharedPreferences} read is
     * a lookup in a map the framework already holds, no verdict depends on it, and the alternative —
     * short-circuiting the gathering — would put the consultation ORDER back in this file beside the
     * copy in the engine. The one visible consequence is that a stale peer-health record can now be
     * dropped on a call the freeze refuses; that is store hygiene either way.
     */
    private static boolean decide(final Tier tier, final String op, final String groupId,
            final String peerE164) {
        final Facts.Builder facts = Facts.builder();

        // G6 — the kill switch. No store, nothing that can be missing: an unread boolean sysprop
        // defaults false, which is the un-frozen fleet.
        if (MlsStateChangeGate.consults(tier, Guard.G6_FREEZE)) {
            facts.frozen(SystemProperties.getBoolean(PROP_FREEZE, false));
        }

        final String n = norm(peerE164);

        // G1 — the lab allowlist. An empty peer is NOT_LISTED rather than absent: the allowlist
        // answers "may this number be brought into MLS at all", and a blank is not a number that
        // question can be answered for.
        if (MlsStateChangeGate.consults(tier, Guard.G1_LAB_ALLOWLIST)) {
            facts.allowlist(!n.isEmpty() && labPeers().contains(n)
                    ? Allowlist.LISTED : Allowlist.NOT_LISTED);
        }

        final boolean readsHealth = MlsStateChangeGate.consults(tier, Guard.G4_PEER_HEALTH);
        final boolean readsBudget = MlsStateChangeGate.consults(tier, Guard.G2_ERA_BUDGET);
        final String healthKey = readsHealth ? healthKey(n) : null;
        final BudgetKey budgetKey =
                readsBudget ? MlsStateChangeGate.eraBudgetKey(groupId, peerE164) : null;
        final String eraStoreKey =
                (budgetKey != null && budgetKey.derived()) ? K_ERA + budgetKey.key() : null;

        // THE MONOTONIC CLOCK, NOT THE WALL CLOCK. A durable budget aged by
        // System.currentTimeMillis() is refilled by any clock jump large enough — an NTP correction
        // after a boot with a dead RTC, a user setting the date — and nothing in the log would say
        // that time did not actually pass. elapsedRealtime cannot be moved. MlsMonotonicAge carries
        // the rule, including what it costs across a reboot.
        final long nowElapsed = SystemClock.elapsedRealtime();

        final Verdict verdict;
        String healthRaw = null;
        String eraRaw = null;
        int streak = 0;
        int perHour = 0;
        int perDay = 0;
        boolean chargeImpossible = false;

        // ONE read-modify-write, under the one lock. The gate is pure and cannot block, so holding
        // the lock across it costs nothing and buys atomicity the four methods did not have: G4 and
        // G2 used to be read under two separate acquisitions with the decision in between.
        synchronized (sStoreLock) {
            if (readsHealth) {
                if (healthKey == null) {
                    // NO PEER NAMED: nothing to look up, so nothing to judge. NOT_CONSULTED, not an
                    // empty record — "we did not ask" must not be spelled the same way as "we asked
                    // and it said zero".
                    facts.peerHealth(RecordState.NOT_CONSULTED, 0);
                } else {
                    healthRaw = load(healthKey);
                    final MlsPeerHealthRecord rec = MlsPeerHealthRecord.decode(healthRaw);
                    if (rec == null) {
                        facts.peerHealth(RecordState.UNREADABLE, 0);
                    } else {
                        streak = rec.streakAt(nowElapsed);
                        facts.peerHealth(RecordState.PRESENT, streak);
                        // The evidence expired. Drop it here rather than leave a record that answers
                        // 0 forever: a stale row that still exists is a row a future reader will try
                        // to interpret.
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

            // THE EFFECTS THE VERDICT NAMED, still under the lock because both are
            // read-modify-write against records this method just read.
            if (verdict.discardRecord()) {
                if (verdict.guard() == Guard.G4_PEER_HEALTH && healthKey != null) drop(healthKey);
                if (verdict.guard() == Guard.G2_ERA_BUDGET && eraStoreKey != null) drop(eraStoreKey);
            }
            if (verdict.mustChargeEraBudget()) {
                if (pruned == null || eraStoreKey == null) {
                    // UNREACHABLE BY CONSTRUCTION — the gate only returns CHARGE_AND_ALLOW after
                    // seeing a PRESENT record under a derived key, and both are set together above.
                    // It is handled anyway because "allowed, and the charge quietly did not happen"
                    // is the exact defect this design exists to make
                    // unrepresentable. A defensive null check that skips the charge and proceeds
                    // would REINTRODUCE it in the one branch nobody reads.
                    chargeImpossible = true;
                } else {
                    // CHARGED BEFORE THE OPERATION RUNS, and on disk before this method returns.
                    // Same discipline as MlsRebuildLimiter.claim: an advance that CRASHES must still
                    // count, or a reproducible crash is an unthrottled loop — and that loop is the
                    // reason this record is durable at all.
                    store(eraStoreKey, pruned.charged(nowElapsed).encode());
                }
            }
        }

        if (chargeImpossible) {
            LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " — the gate allowed it ON CONDITION of "
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

    /**
     * The refusal line, one per {@link MlsStateChangeGate.Reason}.
     *
     * <p>The reporting is the half that deliberately did NOT move: the gate decides, and every
     * sentence explaining a refusal to a person — the incident it comes from, the lever that clears
     * it, the number it counted — stays where the redaction and the tag are.
     */
    private static void report(final Verdict verdict, final Tier tier, final String op,
            final String peerE164, final BudgetKey budgetKey, final String healthRaw,
            final String eraRaw, final int streak, final int perHour, final int perDay) {
        switch (verdict.reason()) {
            case STATE_CHANGES_FROZEN:
                if (tier == Tier.SELF_DEPARTURE) {
                    LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " — MLS state changes are "
                            + "FROZEN (" + PROP_FREEZE + "=true). This is the kill switch; it stops "
                            + "our own departure too, deliberately, because a self-leave transmits "
                            + "a SelfRemove proposal.");
                } else {
                    LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " to " + redact(peerE164)
                            + " — MLS state changes are FROZEN (" + PROP_FREEZE + "=true).");
                }
                return;

            case PEER_HEALTH_UNREADABLE:
                // UNREADABLE IS NOT HEALTHY. Reading a record we cannot parse as "no failures" would
                // answer "this peer is fine" on the strength of a parse error, which is the posture
                // this rung was already in. Refuse once, forget the unreadable record so
                // the next attempt starts from a state we can reason about, and say both.
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
                        + "conversation notification's Try again. (This is the check nobody had on "
                        + "2026-08-05. It is on disk now, so a force-stop no longer "
                        + "declares the peer healthy.)");
                return;

            case NOT_A_LAB_PEER:
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " to " + redact(peerE164)
                        + " — NOT A LAB PEER. MLS state changes may only target this build's "
                        + "known test devices (override: " + PROP_LAB_PEERS + "). This gate exists "
                        + "because we wedged a real person's phone for a month; if you "
                        + "are about to add a number here, be certain it is a device you own.");
                return;

            case NO_BUDGET_KEY:
                // NO KEY MEANS NO BOUND, and an unbounded re-creation is worse than one deferred —
                // the same call MlsRebuildLimiter makes when it has no context to record against.
                // Refusing is also the only honest answer available: we cannot say this is within a
                // budget we could not look up.
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " — neither a group id nor a peer "
                        + "number was supplied, so there is nothing to charge the era budget against "
                        + "and the operation cannot be bounded at all.");
                return;

            case ERA_BUDGET_UNREADABLE:
                // UNREADABLE IS NOT UNCHARGED — the same rule as the health record above, and the
                // stakes here are the operation that wedged a phone. Refuse once and discard, so a
                // format skew costs one deferred advance per conversation rather than a permanent
                // block or a silently restored allowance.
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " on " + redactKey(budgetKey)
                        + " — its era budget record is UNREADABLE (" + eraRaw + "), so we cannot say "
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
                // UNANSWERABLE, and it must not be logged as a refusal — a refusal is a guard doing
                // its job, this is a guard that could not be asked. Someone added a Tier or a Guard
                // and did not answer for it, or gathered facts that do not match the table above.
                // Fails CLOSED (the caller sees false), and says which so the omission is findable
                // rather than looking like a peer problem.
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING " + op + " — the state-change gate could not "
                        + "ANSWER for tier " + tier + " (" + verdict + "). This is not a guard "
                        + "refusing an operation; it is a composition nothing has declared, so "
                        + "there is no basis on which to allow it. Fix MlsStateChangeGate.guardsFor "
                        + "or the facts gathered in MlsPeerGuard.decide.");
        }
    }

    /**
     * Give a conversation its era budget back because a PERSON asked — G2's "on trip:
     * stop, log loudly, require manual reset", which had no reset at all until this.
     *
     * <p>Reached from the stalled-conversation notification's <b>Try again</b>, beside the self-heal
     * budget and the rebuild rate bound it already clears. Without it, a tripped G2 turns the last
     * rung of automatic repair off with no way back — and a guard whose only exit is a peer
     * acknowledging is unreachable for precisely the conversation that cannot reach the peer.
     *
     * <p>Deliberately NOT automatic and deliberately loud. Time refills the rolling window on its
     * own; this is the other justification, and being able to tell the two apart in a log is what
     * distinguishes a repair from a retry that only looked like one.
     *
     * <p><b>It removes the PERSISTED record, not just a cached one</b>. Making
     * the budget durable and leaving the operator lever reaching only into memory would build the
     * trap that lever exists to avoid: a person taps Try again, the process it ran in has no memory
     * of the conversation, and the record on disk that is actually refusing the repair is untouched.
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
     * @return the count, 0 when nothing is recorded, or -1 when a record EXISTS and cannot be read.
     *     A reader that refuses a case must not share a return value with "absent".
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

    /** Record that an operation toward this peer failed or went unacknowledged (G4). */
    public static void notePeerFailure(final String peerE164) {
        final String healthKey = healthKey(norm(peerE164));
        if (healthKey == null) return;
        final long nowElapsed = SystemClock.elapsedRealtime();
        final int c;
        synchronized (sStoreLock) {
            final MlsPeerHealthRecord cur = MlsPeerHealthRecord.decode(load(healthKey));
            // An unreadable record is replaced rather than resumed: we cannot count consecutively
            // from a number we could not read. The refusal it already caused was logged by
            // allowStateChange; starting a fresh streak here is the only thing left that is true.
            final MlsPeerHealthRecord base = (cur == null) ? MlsPeerHealthRecord.NONE : cur;
            final MlsPeerHealthRecord next = base.withFailureAt(nowElapsed);
            c = next.streakAt(nowElapsed);
            // WRITE ONLY ON CHANGE. Past the trip point withFailureAt coalesces the evidence stamp,
            // so a burst of reason-4 reports does not turn into a burst of synchronous commits to
            // record a number nothing reads.
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

    /** Record that this peer acknowledged something; clears the failure streak (G4). */
    public static void notePeerRecovered(final String peerE164) {
        final String healthKey = healthKey(norm(peerE164));
        if (healthKey == null) return;
        synchronized (sStoreLock) {
            // The hot path — this is called on every positive group receipt — and it costs one
            // lookup in the preferences map for the peers that have no streak, which is almost all
            // of them almost always. Only an actual streak reaches the disk.
            if (load(healthKey) == null) return;
            drop(healthKey);
        }
        LogUtil.i(TAG, "MlsPeerGuard: " + redact(peerE164) + " acknowledged something of ours — "
                + "peer-health streak CLEARED.");
    }

    /**
     * Forget this peer's failure streak because a PERSON asked — the G4 half of the stalled-
     * conversation notification's <b>Try again</b>.
     *
     * <p><b>Not {@link #notePeerRecovered}, and the difference is not cosmetic.</b> That method
     * means "the peer processed something of ours", which is affirmative evidence and is what its
     * log line claims. A button press is not evidence about the peer at all; routing an operator
     * reset through it would put a sentence in the log asserting something nobody measured. Same
     * separation, same reason, as {@link #resetEraBudget} versus the window refilling on its own.
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
     * @return the streak, 0 when none is recorded or the evidence has expired, or -1 when a record
     *     EXISTS and cannot be read.
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

    // ---- the two stamps that were made durable ---------------------------------------------------
    //
    // Neither is a NEW guard: both shipped, both bounded an operation on a crash-capable path, and
    // both were a bare field or a bare HashMap in MlsProviderTransport, so every restart handed the
    // whole allowance back. That is the durability argument applied to the counters an earlier
    // enumeration did not reach. They live HERE rather than beside their call sites for the same
    // reason: the store,
    // its lock, its no-context fallback and its commit() discipline are the hard part, and a third
    // and fourth copy of them is where the drift starts. MlsCooldownRecord holds the codec and the
    // clock rule; this file holds the side effects, which is the same split kept everywhere here.
    //
    // NOT keyed like the two above. The era budget is keyed by budget key and the health streak by
    // peer; the episode suppressor is keyed by CANONICAL CONVERSATION KEY, because that is what the
    // rebuild it suppresses is keyed by and what MlsRebuildLimiter — the allowance it protects —
    // already uses. Four key spaces, four prefixes, so no decoder is ever handed another's format.

    /**
     * May a rebuild START A NEW EPISODE for this conversation — and if so, stamp it.
     *
     * <p><b>Episode suppression is not the rate limit.</b> {@code MlsRebuildLimiter} bounds how often
     * a conversation may be rebuilt over HOURS; this stops a single BURST from spending that entire
     * allowance on ONE fault, which is why it is asked FIRST and why a suppressed attempt charges
     * nothing. That ordering is unchanged by making the stamp durable — this method sits exactly
     * where the in-memory comparison sat.
     *
     * <p><b>What durability changes.</b> A crash-restart loop is a burst, and it was the one kind of
     * burst this suppressor could not see: the stamp died with the process while the six-hour
     * allowance it protects did not. Six group sends ~6s apart already spent three claims in ten
     * seconds once (device-observed 2026-08-10); a reproducible crash on the rebuild path is the
     * same shape with nothing between the attempts at all.
     *
     * <p><b>And the trap this must not build.</b> A durable
     * refusal with no operator lever is a permanently stuck conversation, so
     * {@code MlsProviderTransport.resetRebuildRateBound} (the stalled-conversation notification's
     * <b>Try again</b>) drops this record beside the rate bound it already cleared. Without that a
     * person could tap Try again inside a minute of a crashed attempt, in a process that has no
     * memory of the conversation, and watch nothing happen.
     *
     * @return true if the caller may rebuild now
     */
    public static boolean claimRebuildEpisode(final String conversationKey) {
        return claimRebuildEpisode(conversationKey, rebuildEpisodeWindowMs());
    }

    /**
     * The episode window, {@link MlsWindowBudget#REBUILD_EPISODE_MS}.
     *
     * <p>Read through here rather than named at the call sites:
     * the transport must not declare the threshold and must not spell it either, or a constant that
     * "moved" is still a decision made in the class this work exists to empty.
     */
    public static long rebuildEpisodeWindowMs() {
        return MlsWindowBudget.REBUILD_EPISODE_MS;
    }

    /** The window-taking form. Production takes the overload above; tests pick their own window. */
    public static boolean claimRebuildEpisode(final String conversationKey, final long episodeMs) {
        if (conversationKey == null || conversationKey.isEmpty()) {
            // No key means no record, and an unbounded rebuild burst is worse than one deferred
            // cycle — the same call MlsRebuildLimiter makes when it has no context.
            LogUtil.w(TAG, "MlsPeerGuard: refusing a rebuild episode with no conversation key — the "
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
                // UNREADABLE IS NOT "NO EPISODE", and it is not "still suppressed" either — the same
                // rule MlsRebuildLimiter applies to its own record. Refuse once and discard, so a
                // format skew costs one deferred rebuild per conversation rather than a permanent
                // block or a silently restored allowance.
                drop(storeKey);
                LogUtil.e(TAG, "MlsPeerGuard: REFUSING a rebuild episode for " + conversationKey
                        + " — its episode record is UNREADABLE, so we cannot say whether one was "
                        + "just attempted. Discarding it; the next attempt is judged on an empty "
                        + "record.");
                return false;
            }
            remaining = rec.remainingMs(episodeMs, nowElapsed);
            if (remaining <= 0L) store(storeKey, MlsCooldownRecord.attemptedAt(nowElapsed).encode());
        }
        if (remaining > 0L) {
            LogUtil.i(TAG, "MlsPeerGuard: NOT starting a rebuild episode for " + conversationKey
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
     *     EXISTS and cannot be read. A reader that refuses a case must not share a return value
     *     with "absent" — and here the two are at opposite ends, so sharing one would be worse than
     *     usual.
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
     * Forget this conversation's rebuild episode because a PERSON asked, or because a test
     * instrument did.
     *
     * <p>Reached from {@code MlsProviderTransport.resetRebuildRateBound} — the same lever that
     * clears the rate bound — because the two now refuse the same operation for two different
     * reasons and clearing one without the other is a button that does nothing.
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
        LogUtil.i(TAG, "MlsPeerGuard: rebuild episode suppressor CLEARED for " + conversationKey
                + " by request — " + (had < 0L ? "an unreadable record"
                        : had == Long.MAX_VALUE ? "nothing was recorded"
                        : "an attempt " + (had / 1000L) + "s ago") + " forgotten.");
    }

    /**
     * May we drive an outbound re-establish toward this peer — and if so, stamp it.
     *
     * <p><b>This is a bound on somebody else's scarce resource.</b> It meters re-establish ATTEMPTS,
     * MOST of which reach a claim on one of the peer's one-time KeyPackages — out of a small pool we
     * do not replenish — and it fires in exactly the situation that produces bursts: undecryptable
     * inbound. In memory, a restart handed the whole ten-minute allowance back and the peer paid for
     * it a KeyPackage at a time, which is how a pool drains to last-resort.
     *
     * <p><b>ATTEMPTS, not claims, and the difference was measured</b> by walking the
     * chain. The stamp is taken before {@code reestablishOutbound}'s off-thread body, and
     * two arms inside return without ever reaching {@code ensureReady}: a fetch-ledger refusal on the
     * server era look-up, and "the server still holds this conversation". So some stamped attempts
     * claim nothing. The error is in the SAFE direction — it over-protects the pool, never under — so
     * the verdict is unchanged and only the reason needed correcting. It is corrected rather than
     * left because a right verdict resting on a wrong reason is the one thing no test fails on, and
     * two readers would each re-derive this constant differently.
     *
     * <p><b>Deliberately NOT tied to {@code MlsClaimLedger.WINDOW_MS}</b>, which carries the same ten
     * minutes and bounds the claim itself. They are independent on purpose — tying two
     * bounds is how raising one silently raises the other — and they nest rather than compete: this
     * one allows a single attempt per peer per window and the ledger's {@code ENSURE_READY} ration
     * allows two, so on THIS path the ledger can never be what refuses. It is not a dead ration:
     * {@code ensureReady}'s other callers never pass this cooldown.
     *
     * <p>The wall clock is gone with it. The in-memory version compared {@code currentTimeMillis()}
     * readings and carried a hand-written "a clock that moved BACKWARDS must not pin the cooldown
     * shut for ever" guard; {@link MlsCooldownRecord} answers that structurally, and in the safe
     * direction — a clock move can retain a spent cooldown, never refill one.
     *
     * @return true if the caller may re-establish now
     */
    public static boolean claimReestablishAttempt(final String peerE164) {
        return claimReestablishAttempt(peerE164, reestablishCooldownMs());
    }

    /** The cooldown, {@link MlsReestablishPolicy#REESTABLISH_COOLDOWN_MS}. See above. */
    public static long reestablishCooldownMs() {
        return MlsReestablishPolicy.REESTABLISH_COOLDOWN_MS;
    }

    /** The window-taking form. Production takes the overload above; tests pick their own window. */
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
                        + redact(peerE164) + " — its cooldown record is UNREADABLE, so we cannot say "
                        + "whether one was just attempted, and each attempt claims one of their "
                        + "KeyPackages. Discarding it; the next attempt is judged on an empty "
                        + "record.");
                return false;
            }
            remaining = rec.remainingMs(cooldownMs, nowElapsed);
            if (remaining <= 0L) store(storeKey, MlsCooldownRecord.attemptedAt(nowElapsed).encode());
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

    /**
     * Forget this peer's re-establish cooldown because a PERSON asked.
     *
     * <p>Reached from {@code MlsProviderTransport.resetReestablishCooldown}, beside the era budget
     * and the peer-health streak the notification's <b>Try again</b> already clears. Making the
     * cooldown durable without giving the lever a matching reach is the same trap G4 had:
     * a person taps Try again, the process has no memory of the peer, and the record on disk
     * that is actually refusing the repair is untouched.
     */
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

    /** Test/debug hook: forget all recorded history, on disk as well as in memory. */
    public static void resetForTest() {
        synchronized (sStoreLock) {
            sFallbackStore.clear();
            final SharedPreferences p = prefs();
            if (p != null) p.edit().clear().commit();
        }
    }

    /**
     * A budget key for the log. A group id is an opaque identifier and prints as itself; a 1:1 key
     * is a PHONE NUMBER wearing a prefix, and printing it whole would undo the redaction every other
     * line in this class applies.
     */
    private static String redactKey(final BudgetKey budgetKey) {
        if (budgetKey == null || budgetKey.key() == null) return "";
        // Keyed on the SOURCE the derivation reported, not on the string's prefix. A group id that
        // happened to begin "peer:" would otherwise be redacted as a phone number, and — worse in
        // the other direction — the redaction would follow a spelling rather than the fact that
        // decides it.
        return budgetKey.source() == MlsStateChangeGate.KeySource.PEER
                ? "peer:" + redact(budgetKey.key().substring("peer:".length()))
                : budgetKey.key();
    }

    /** Last 4 digits only — these lines belong to real people. */
    private static String redact(final String e164) {
        final String n = norm(e164);
        return n.length() <= 4 ? "***" : "***" + n.substring(n.length() - 4);
    }
}

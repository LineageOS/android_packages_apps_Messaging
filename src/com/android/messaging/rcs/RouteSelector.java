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
package com.android.messaging.rcs;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProvider;
import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsSubInfo;
import org.lineageos.rcs.provider.RcsTosPrompt;

import com.android.messaging.Factory;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-subId decision "use provider-RCS" vs "use SMS", driven by the latest
 * registration + provisioning callbacks. Conservative by design: the send
 * path defaults to SMS unless RCS is <b>affirmatively</b> up for the sub
 * (REG_REGISTERED AND PROV_CONFIGURED). The actual peer-is-RCS check is
 * authoritatively backstopped by the synchronous {@code IRcsProvider.sendMessage}
 * result ({@code REASON_PEER_NOT_RCS}). On top of that sub-level gate we keep a
 * small read-through <b>per-recipient</b> capability cache (phoneE164 -> CAP_*),
 * populated by {@link ProviderTransport#lookupRcsCapability} and by the send
 * backstop, so the send path and the compose UI can skip the RCS attempt for
 * peers already known to be SMS-only. Only the sticky CAP_SMS_ONLY / CAP_RCS
 * verdicts are cached; CAP_UNKNOWN is never stored so a transient miss is always
 * retried and preserves today's optimistic behavior.
 *
 * <p>Thread-safety: callbacks arrive on the router (binder) thread; the send
 * path reads from a DataModel action thread. State is guarded by the
 * monitor.
 */
public final class RouteSelector {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private final Object mLock = new Object();
    private final SparseIntArray mRegState = new SparseIntArray();
    private final SparseIntArray mProvState = new SparseIntArray();
    private final SparseArray<RcsProviderCaps> mCaps = new SparseArray<>();

    // Per-sub carrier/Google ToS gate state (one of IRcsProviderCallback.TOS_*)
    // and the latest prompt text. Populated from onCarrierTosStateChanged so the
    // settings row + the (re)prompt dialog can read it without re-crossing the
    // binder. Defaults to TOS_NONE for unseen subs.
    private final SparseIntArray mTosState = new SparseIntArray();
    private final SparseArray<RcsTosPrompt> mTosPrompt = new SparseArray<>();

    // Per-recipient capability cache (E.164 cache key -> CAP_SMS_ONLY / CAP_RCS).
    // Only sticky verdicts are stored; CAP_UNKNOWN is never inserted. Cleared
    // when the provider goes away (a re-attached/re-provisioned backend may
    // produce different verdicts).
    private final Map<String, Integer> mPeerCap = new HashMap<>();

    /** Main-looper handler for the grace-expiry re-select timer (design §5.4). */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /**
     * Pending grace-expiry re-select runnables, keyed by subId, so repeated defers
     * dedupe to a single armed timer per sub (NIT) instead of stacking N runnables.
     * Guarded by its own monitor (scheduled off the main thread, cleared on it).
     */
    private final SparseArray<Runnable> mGraceRunnables = new SparseArray<>();

    void onRegistrationStateChanged(final int subId, final int state) {
        synchronized (mLock) {
            mRegState.put(subId, state);
        }
        LogUtil.i(TAG, "RouteSelector: sub " + subId + " reg=" + state);
        // Active-transport terminal failure is a selection trigger (design §5.4):
        // REG_FAILED is the transport's *terminal* registration verdict (after its
        // own retries) -- distinct from a transient REG_UNREGISTERED drop, which is
        // within-transport self-recovery and must NOT churn selection. Fall to the
        // next candidate on REG_FAILED only.
        if (state == IRcsProviderCallback.REG_FAILED) {
            advanceSelectionOnTerminalFailure(subId, "reg-failed");
        }
    }

    void onProvisioningStateChanged(final int subId, final int provState,
            @Nullable final RcsProviderCaps caps) {
        synchronized (mLock) {
            mProvState.put(subId, provState);
            if (caps != null) {
                mCaps.put(subId, caps);
            }
        }
        LogUtil.i(TAG, "RouteSelector: sub " + subId + " prov=" + provState);
        // Terminal provisioning failure of the active transport -> fall to next
        // (design §5.4). PROV_CONFIGURED settles the walk (the sticky rule keeps it).
        if (provState == IRcsProviderCallback.PROV_DISABLED_BY_CARRIER) {
            advanceSelectionOnTerminalFailure(subId, "prov-disabled-by-carrier");
        } else if (provState == IRcsProviderCallback.PROV_CONFIGURED) {
            settleSelection(subId);
        }
    }

    void onProviderGone() {
        synchronized (mLock) {
            mRegState.clear();
            mProvState.clear();
            mCaps.clear();
            mPeerCap.clear();
            mTosState.clear();
            mTosPrompt.clear();
        }
    }

    // ---- carrier/Google ToS gate state ----

    /**
     * Update the cached ToS state for a sub (one of the
     * {@link IRcsProviderCallback}{@code .TOS_*} constants) and the latest
     * prompt. A non-null {@code prompt} is retained (so the settings row can
     * re-launch the dialog); a null {@code prompt} leaves the previous prompt
     * untouched (informational state transitions like TOS_ACCEPTED/_DECLINED
     * carry no text). Called from the binder thread.
     */
    void setTosState(final int subId, final int tosState,
            @Nullable final RcsTosPrompt prompt) {
        synchronized (mLock) {
            mTosState.put(subId, tosState);
            if (prompt != null) {
                mTosPrompt.put(subId, prompt);
            } else if (tosState == IRcsProviderCallback.TOS_NONE) {
                mTosPrompt.remove(subId);
            }
        }
        LogUtil.i(TAG, "RouteSelector: sub " + subId + " tos=" + tosState);
    }

    /**
     * Current ToS gate state for the sub, one of the
     * {@link IRcsProviderCallback}{@code .TOS_*} constants. Defaults to
     * {@link IRcsProviderCallback#TOS_NONE}. Pure cache read; safe on the main
     * thread (the settings screen calls it).
     */
    public int getTosState(final int subId) {
        synchronized (mLock) {
            return mTosState.get(subId, IRcsProviderCallback.TOS_NONE);
        }
    }

    /**
     * Latest cached ToS prompt for the sub, or null if none has been seen.
     * Used to re-launch the consent dialog from the settings row.
     */
    @Nullable
    public RcsTosPrompt getTosPrompt(final int subId) {
        synchronized (mLock) {
            return mTosPrompt.get(subId);
        }
    }

    // ---- per-recipient capability cache ----

    /**
     * Read the cached per-recipient verdict for an (already-normalized) cache
     * key, or {@link IRcsProvider#CAP_UNKNOWN} on a miss. Internal seam used by
     * {@link ProviderTransport#lookupRcsCapability}; callers outside that path
     * should prefer {@link #isPeerRcsCapable}.
     */
    int getCachedPeerCap(final String cacheKey) {
        if (cacheKey == null) {
            return IRcsProvider.CAP_UNKNOWN;
        }
        synchronized (mLock) {
            final Integer v = mPeerCap.get(cacheKey);
            return v != null ? v : IRcsProvider.CAP_UNKNOWN;
        }
    }

    /**
     * Store a sticky per-recipient verdict. CAP_UNKNOWN is ignored so a
     * transient miss never becomes sticky.
     */
    void putPeerCap(final String cacheKey, final int cap) {
        if (cacheKey == null
                || (cap != IRcsProvider.CAP_SMS_ONLY && cap != IRcsProvider.CAP_RCS)) {
            return;
        }
        synchronized (mLock) {
            mPeerCap.put(cacheKey, cap);
        }
    }

    /**
     * Convenience read for UI / gating: returns the cached per-recipient
     * verdict for {@code phoneE164} (one of the {@link IRcsProvider}{@code .CAP_*}
     * constants), or {@link IRcsProvider#CAP_UNKNOWN} if nothing has been
     * learned yet. Pure cache read -- never crosses the binder, so it is safe
     * on the main thread (the compose UI calls it). To proactively populate the
     * cache, call {@link ProviderTransport#lookupRcsCapability} off the main
     * thread.
     *
     * <p>Note: this does NOT consider the sub-level gate; a CAP_RCS peer still
     * only sends over RCS when {@link #isRcsAvailableForSub} is also true.
     */
    public int isPeerRcsCapable(final int subId, @Nullable final String phoneE164) {
        if (phoneE164 == null) {
            return IRcsProvider.CAP_UNKNOWN;
        }
        return getCachedPeerCap(phoneE164);
    }

    // ---- group route (FLOW4b SCAFFOLD) ----

    /**
     * FLOW4b: whether a group (multi-recipient) conversation should be routed
     * over provider-RCS rather than MMS. A thin wrapper over {@link
     * #isRcsAvailableForSub} gated on a build-time feature flag. Now ENABLED:
     * the group send/route/event DB plumbing (group-conversation creation,
     * participants-table sync, inbound fan-out routing, member-change system
     * messages) has landed, so group-RCS is reachable whenever the sub is
     * RCS-registered + provisioned.
     */
    public static final boolean GROUP_RCS_ENABLED = true;

    /**
     * Should this group send go over provider-RCS? Mirrors the 1:1 gating
     * ({@link #isRcsAvailableForSub}: REG_REGISTERED && PROV_CONFIGURED) and is
     * additionally guarded by {@link #GROUP_RCS_ENABLED}. When false the
     * InsertNewMessageAction group fork falls through to MMS unchanged.
     */
    public boolean isGroupRcsAvailableForSub(final int subId) {
        return GROUP_RCS_ENABLED && isRcsAvailableForSub(subId);
    }

    /**
     * Whether RCS is up enough on this sub to be worth attempting a send.
     * A false return means "go straight to SMS".
     */
    public boolean isRcsAvailableForSub(final int subId) {
        // User-facing master toggle: when the user turns RCS chats
        // off, route everything as SMS/MMS regardless of registration state.
        if (!RcsFeatureSettings.isRcsEnabled()) {
            return false;
        }
        synchronized (mLock) {
            final int reg = mRegState.get(subId, IRcsProviderCallback.REG_UNREGISTERED);
            final int prov = mProvState.get(subId, IRcsProviderCallback.PROV_NOT_PROVISIONED);
            return reg == IRcsProviderCallback.REG_REGISTERED
                    && prov == IRcsProviderCallback.PROV_CONFIGURED;
        }
    }

    @Nullable
    public RcsProviderCaps getCapsForSub(final int subId) {
        synchronized (mLock) {
            return mCaps.get(subId);
        }
    }

    /**
     * Raw cached registration state for the sub, one of the
     * {@link IRcsProviderCallback}{@code .REG_*} constants. Defaults to
     * {@link IRcsProviderCallback#REG_UNREGISTERED} when nothing has been
     * reported yet. Read-only; intended for the user-facing RCS status screen.
     */
    public int getRegState(final int subId) {
        synchronized (mLock) {
            return mRegState.get(subId, IRcsProviderCallback.REG_UNREGISTERED);
        }
    }

    /**
     * Raw cached provisioning state for the sub, one of the
     * {@link IRcsProviderCallback}{@code .PROV_*} constants. Defaults to
     * {@link IRcsProviderCallback#PROV_NOT_PROVISIONED} when nothing has been
     * reported yet. Read-only; intended for the user-facing RCS status screen.
     */
    public int getProvState(final int subId) {
        synchronized (mLock) {
            return mProvState.get(subId, IRcsProviderCallback.PROV_NOT_PROVISIONED);
        }
    }

    // =====================================================================
    // Trigger-driven sticky transport selection (design §5.4, D8)
    // =====================================================================
    //
    // The registry ({@link ProviderRegistry}) holds N transports (in-process
    // carrier-IMS + the legacy provider binding + any discovered external provider).
    // This selects EXACTLY ONE per sub:
    //
    //   1. enumerate -> canServeSub each -> keep everything not INELIGIBLE
    //      (ELIGIBLE / MAYBE / UNKNOWN; UNKNOWN == "not synchronously knowable /
    //      not bound yet" is kept as a candidate so a not-yet-bound provider isn't
    //      permanently excluded -- startForSub stays authoritative, design D5);
    //   2. rank by RcsProviderCaps.priority desc (higher = preferred, design §5.3),
    //      deterministic tiebreak on a stable per-transport key;
    //   3. startForSub the top; on a TERMINAL failure (PROV_DISABLED_BY_CARRIER /
    //      REG_FAILED) fall to the next -- the walk is completed asynchronously by
    //      the reg/prov callbacks above;
    //   4. first PROV_CONFIGURED wins and becomes the sub's active transport;
    //   5. none => SMS-only (active == null).
    //
    // Re-select ONLY on the design's triggers (boot/app-start, subscription change,
    // provider install/uninstall/replace, ACTION_CARRIER_CONFIG_CHANGED, active-
    // transport terminal failure) -- never on transient reg drops. Sticky: an
    // already-selected transport is kept across re-evals unless it went ineligible
    // OR a strictly-higher-priority transport became firmly (LINE_ELIGIBLE) newly
    // eligible. A post-fallback cooldown stops a flapping line churning selection.

    /**
     * Grace window (design §5.4): when a higher-priority provider is still binding
     * (its caps -- hence its declared priority -- aren't known yet), defer committing
     * to a lower-priority MAYBE transport for up to this long, so a provider-only
     * device lands on the provider rather than transiently starting the carrier
     * transport before the provider finishes its bind + handshake. A provider
     * handshake re-selects immediately (well within this window); the timer only
     * bounds the "provider never binds" case (e.g. no provider installed) so
     * selection still eventually commits to the carrier transport.
     * RIG-VERIFY(rcs-framework): 15s is chosen to comfortably cover a cold provider
     * bind + getContractVersion + attach on the test fleet; confirm it's long enough
     * on the slowest supported device and short enough that a provider-absent device
     * doesn't feel the delay before carrier-IMS is tried.
     */
    private static final long PENDING_GRACE_MS = 15_000L;

    /**
     * Debounce window for fallback advances (design §5.4 anti-thrash). The shared
     * callback sink carries no transport identity, so a terminal-failure callback is
     * attributed to the sub's active transport; two advances closer than this are
     * coalesced, which absorbs a stale callback arriving from a just-de-selected
     * transport (that stopForSub should have quiesced) right after a switch, so it
     * can't immediately knock the newly-active transport off.
     * RIG-VERIFY(rcs-framework): confirm de-selected transports stop emitting reg/prov
     * callbacks promptly after stopForSub on a real IMS rig; if a real second
     * candidate can terminally fail within this window, widen it or thread a transport
     * id through the (frozen) callback sink instead of attributing to active.
     */
    private static final long ADVANCE_DEBOUNCE_MS = 3_000L;

    /** One ranked selection candidate. */
    private static final class Cand {
        final RcsTransport transport;
        final int priority;      // caps.priority, or Integer.MIN_VALUE when pending
        final boolean pending;   // caps not yet known (transport mid-bind)
        final int eligibility;   // LINE_ELIGIBLE / LINE_MAYBE / LINE_UNKNOWN
        final String tiebreak;   // stable key for a deterministic order

        Cand(final RcsTransport t, final int prio, final boolean pend,
                final int elig, final String key) {
            transport = t;
            priority = prio;
            pending = pend;
            eligibility = elig;
            tiebreak = key;
        }
    }

    /** Deterministic tiebreak key: the bound provider's package, else the class. */
    private static String tiebreakKeyOf(final RcsTransport t) {
        if (t instanceof BoundProviderTransport) {
            return ((BoundProviderTransport) t).getPackageName();
        }
        return t.getClass().getName();
    }

    /**
     * Re-run selection for every sub the registry has state for, plus the device's
     * active sub (design §5.4). Called by {@link ProviderRegistry#reselect} on the
     * discovery / provider-handshake triggers.
     */
    void reselectAll(final String reason, final boolean freshCycle) {
        final ProviderRegistry registry = ProviderRegistry.peek();
        if (registry == null) {
            return;
        }
        final List<Integer> subs = registry.knownSubIds();
        final int activeSub = resolveActiveSubId();
        if (SubscriptionManager.isValidSubscriptionId(activeSub) && !subs.contains(activeSub)) {
            subs.add(activeSub);
        }
        for (final int subId : subs) {
            selectTransportForSub(subId, freshCycle, reason);
        }
    }

    /**
     * Entry point for the boot / app-start / sub-change / carrier-config triggers:
     * resolve the device's active sub and (fresh-cycle) select for it.
     */
    public void selectForActiveSub(final String reason) {
        final int subId = resolveActiveSubId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            LogUtil.i(TAG, "RouteSelector: no valid active sub; not selecting (" + reason + ")");
            return;
        }
        selectTransportForSub(subId, /* freshCycle= */ true, reason);
    }

    /**
     * Convenience overload for an explicit sub (used by tests / direct triggers).
     * Runs a fresh-cycle selection.
     */
    public void selectTransportForSub(final int subId) {
        selectTransportForSub(subId, /* freshCycle= */ true, "explicit");
    }

    /**
     * The selection algorithm (design §5.4). Ranks the registry's transports for
     * {@code subId} and drives {@code startForSub} on the chosen one. Non-blocking:
     * the provider-&gt;provider fallback walk completes asynchronously via the reg/prov
     * callbacks. Safe to call from any thread.
     *
     * <p><b>Lock discipline (HIGH-1):</b> the ranked decision is computed while
     * holding the registry lock, but the resulting {@code startForSub} /
     * {@code stopForSub} calls -- SYNCHRONOUS, binder-crossing calls into an external
     * provider -- are issued OUTSIDE the lock via {@link #applyDecision}. {@link
     * ProviderRegistry#getActiveTransport} takes the same lock, so holding it across a
     * janky provider's binder call would stall concurrent sends.
     *
     * @param freshCycle external trigger (resets the per-sub failed set) vs a
     *                   provider-handshake nudge (preserves it).
     */
    public void selectTransportForSub(final int subId, final boolean freshCycle,
            final String reason) {
        if (!RcsFeatureSettings.isRcsEnabled()) {
            // Master RCS toggle off: everything routes SMS; don't provision anything.
            return;
        }
        final ProviderRegistry registry = ProviderRegistry.peek();
        if (registry == null) {
            return;
        }

        // Cheap cooldown pre-check BEFORE paying the rankCandidates binder cost (NIT):
        // canServeSub / getProviderCaps cross the binder for external providers, so
        // don't rank at all when selection is in post-fallback cooldown (which would
        // only keep the active transport anyway). Re-checked under the lock below.
        synchronized (registry.lock()) {
            final ProviderRegistry.SelectionState s = registry.selectionState(subId);
            if (SystemClock.elapsedRealtime() < s.cooldownUntilMs) {
                LogUtil.i(TAG, "RouteSelector: sub " + subId
                        + " selection in cooldown; keeping active (" + reason + ")");
                return;
            }
        }

        final RcsSubInfo sub = buildSubInfo(subId);

        // Rank candidates OUTSIDE the registry lock (canServeSub / getProviderCaps
        // may touch a binder for an external provider; never hold the lock across a
        // potentially-blocking call).
        final List<Cand> ranked = rankCandidates(registry, sub);

        final Decision decision;
        synchronized (registry.lock()) {
            final ProviderRegistry.SelectionState s = registry.selectionState(subId);
            decision = computeSelectionLocked(s, subId, ranked, freshCycle, reason);
        }

        // HIGH-1: drive startForSub / stopForSub and arm the grace timer OUTSIDE the
        // registry lock -- these are the synchronous binder calls that must never run
        // while the lock (which getActiveTransport also takes) is held.
        applyDecision(subId, sub, decision);
    }

    /**
     * Compute the selection decision for a sub. Mutates the per-sub {@link
     * ProviderRegistry.SelectionState} but performs NO binder calls: it records the
     * transport(s) to stop/start into the returned {@link Decision} so the caller can
     * invoke {@code startForSub} / {@code stopForSub} OUTSIDE the registry lock
     * (HIGH-1). Caller MUST hold {@code registry.lock()}.
     */
    private Decision computeSelectionLocked(final ProviderRegistry.SelectionState s,
            final int subId, final List<Cand> ranked, final boolean freshCycle,
            final String reason) {
        final Decision d = new Decision();
        final long now = SystemClock.elapsedRealtime();

        // Post-fallback cooldown re-check under the lock (the pre-check raced it).
        if (now < s.cooldownUntilMs) {
            LogUtil.i(TAG, "RouteSelector: sub " + subId
                    + " selection in cooldown; keeping active (" + reason + ")");
            return d;
        }

        if (freshCycle) {
            s.failed.clear();
            s.graceDeadlineMs = 0L;
        }

        // Eligible, non-failed candidates in ranked order.
        final List<Cand> eligible = new ArrayList<>();
        for (final Cand c : ranked) {
            if (!s.failed.contains(c.transport)) {
                eligible.add(c);
            }
        }
        // Persist the ranked order for the async fallback walk.
        final List<RcsTransport> order = new ArrayList<>(eligible.size());
        for (final Cand c : eligible) {
            order.add(c.transport);
        }
        s.candidates = order;
        // THE WHOLE CANDIDATE LIST, not just the winner.
        //
        // Every selection line printed only the transport that won, so a sub routed to the wrong
        // transport gave no way to tell WHICH of the three possible reasons it was: the better
        // transport was absent from the registry, present but filtered as INELIGIBLE, or present
        // and merely `pending`. Those have completely different fixes, and distinguishing them cost
        // a full session of device work. rankCandidates already computes all of it.
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE) || ranked.size() != eligible.size()
                || eligible.size() < 2) {
            final StringBuilder sb = new StringBuilder();
            for (final Cand c : ranked) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(c.transport.getClass().getSimpleName())
                  .append("{prio=").append(c.pending ? "pending" : Integer.toString(c.priority))
                  .append(" elig=").append(c.eligibility)
                  .append(s.failed.contains(c.transport) ? " FAILED" : "")
                  .append('}');
            }
            LogUtil.i(TAG, "RouteSelector: sub " + subId + " candidates[" + ranked.size() + "]: "
                    + (sb.length() == 0 ? "(none registered)" : sb.toString()));
        }

        // Newly-eligible edge set (design §5.4 anti-thrash, MEDIUM): compute which
        // transports are firmly LINE_ELIGIBLE now and which BECAME so since the last
        // evaluation ("newly" = firm now minus firm before). Only a newly-eligible
        // strictly-higher-priority transport may preempt the sticky-selected one --
        // steady-state eligibility must not, else a higher-priority transport that
        // keeps failing to provision would preempt->fail->fallback->cooldown-churn on
        // every trigger. Update the memory for the next evaluation.
        final Set<RcsTransport> firmNow = new HashSet<>();
        for (final Cand c : eligible) {
            if (!c.pending && c.eligibility == IRcsProvider.LINE_ELIGIBLE) {
                firmNow.add(c.transport);
            }
        }
        final Set<RcsTransport> newlyEligible = new HashSet<>(firmNow);
        newlyEligible.removeAll(s.priorEligible);
        s.priorEligible.clear();
        s.priorEligible.addAll(firmNow);

        // ---- Sticky: keep a still-eligible selected transport ----
        if (s.selected != null) {
            final Cand activeCand = find(eligible, s.selected);
            if (activeCand != null) {
                // Preempt ONLY for a strictly-higher-priority transport that is FIRMLY
                // (LINE_ELIGIBLE) eligible AND became NEWLY eligible this evaluation
                // (design §5.4, MEDIUM) -- a MAYBE/UNKNOWN or a steady-state-eligible
                // transport never steals the selected transport.
                Cand preempt = null;
                for (final Cand c : eligible) {
                    if (c.transport == s.selected) {
                        continue;
                    }
                    if (!c.pending && c.eligibility == IRcsProvider.LINE_ELIGIBLE
                            && c.priority > activeCand.priority
                            && newlyEligible.contains(c.transport)) {
                        preempt = c;
                        break;
                    }
                }
                if (preempt == null) {
                    // No preemption: keep selected, re-drive startForSub so a re-attach
                    // re-registers (idempotent, provider-debounced).
                    LogUtil.i(TAG, "RouteSelector: sub " + subId
                            + " sticky-keep active (" + reason + ")");
                    d.toStart = s.selected;
                    return d;
                }
                LogUtil.i(TAG, "RouteSelector: sub " + subId
                        + " preempt -> higher-priority newly-eligible transport prio="
                        + preempt.priority + " (" + reason + ")");
                applySwitchLocked(s, preempt.transport, d);
                return d;
            }
            // Selected went ineligible/failed -> fall through to a fresh pick.
        }

        // ---- Fresh pick (no selection, or selected no longer eligible) ----
        // Pending-defer: if a higher-priority provider is still binding (caps
        // unknown), don't commit to a lower-priority known transport yet.
        boolean anyPending = false;
        Cand firstKnown = null;
        for (final Cand c : eligible) {
            if (c.pending) {
                anyPending = true;
            } else if (firstKnown == null) {
                firstKnown = c;
            }
        }
        if (anyPending) {
            if (s.graceDeadlineMs == 0L) {
                s.graceDeadlineMs = now + PENDING_GRACE_MS;
            }
            if (now < s.graceDeadlineMs) {
                LogUtil.i(TAG, "RouteSelector: sub " + subId + " defer: higher-"
                        + "priority provider still binding (" + reason + ")");
                // Re-select when the grace expires even if no handshake arrives
                // (covers "provider never binds" -> eventually commit).
                d.graceReselectDelayMs = s.graceDeadlineMs - now + 50L;
                return d;
            }
            LogUtil.i(TAG, "RouteSelector: sub " + subId
                    + " grace expired; committing to best known transport");
        }
        s.graceDeadlineMs = 0L;

        if (firstKnown == null) {
            // Nothing selectable now -> SMS-only. (If a transport is still pending past
            // grace it was treated as unavailable above.)
            applySwitchLocked(s, null, d);
            LogUtil.i(TAG, "RouteSelector: sub " + subId
                    + " no eligible transport -> SMS (" + reason + ")");
            return d;
        }
        LogUtil.i(TAG, "RouteSelector: sub " + subId + " selected transport prio="
                + firstKnown.priority + " elig=" + firstKnown.eligibility
                + " (" + reason + ")");
        applySwitchLocked(s, firstKnown.transport, d);
        return d;
    }

    /**
     * Apply a computed {@link Decision} OUTSIDE the registry lock (HIGH-1): stop the
     * de-selected transport, (re-)start the selected one, and arm the grace-expiry
     * re-select timer. These are the synchronous, binder-crossing calls that must
     * never run under the registry lock.
     */
    private void applyDecision(final int subId, final RcsSubInfo sub, final Decision d) {
        if (d == null) {
            return;
        }
        if (d.toStop != null) {
            try {
                d.toStop.stopForSub(subId);
            } catch (final Throwable e) {
                LogUtil.w(TAG, "RouteSelector: stopForSub on de-selected transport failed", e);
            }
        }
        if (d.toStart != null) {
            driveStart(d.toStart, sub);
        }
        if (d.graceReselectDelayMs >= 0L) {
            scheduleGraceReselect(subId, d.graceReselectDelayMs);
        }
    }

    /**
     * A computed selection outcome: the transport(s) to drive and an optional grace
     * re-select delay. Produced under the registry lock by {@link
     * #computeSelectionLocked} / {@link #advanceSelectionOnTerminalFailure}; applied
     * OUTSIDE the lock by {@link #applyDecision} so no binder call is held across the
     * lock (HIGH-1).
     */
    private static final class Decision {
        @Nullable RcsTransport toStop;      // previous selected -> stopForSub(subId)
        @Nullable RcsTransport toStart;     // selected -> startForSub(sub)
        long graceReselectDelayMs = -1L;    // >=0 -> schedule a grace re-select
    }

    /** Enumerate + rank the registry's transports for a sub (no registry lock held). */
    private List<Cand> rankCandidates(final ProviderRegistry registry, final RcsSubInfo sub) {
        final List<RcsTransport> all = registry.getTransports();
        final List<Cand> cands = new ArrayList<>(all.size());
        for (final RcsTransport t : all) {
            int elig;
            try {
                elig = t.canServeSub(sub);
            } catch (final Throwable e) {
                elig = IRcsProvider.LINE_UNKNOWN;
            }
            if (elig == IRcsProvider.LINE_INELIGIBLE) {
                // Say WHICH transport was dropped and why. A transport filtered out
                // here is invisible to every later log line — the selection line names only the
                // winner — so a sub silently routed to a lower-priority transport gave no signal at
                // all that a better one had been excluded one step earlier.
                LogUtil.i(TAG, "RouteSelector: DROPPED " + t.getClass().getSimpleName()
                        + " from ranking — canServeSub returned LINE_INELIGIBLE for sub "
                        + sub.subId);
                continue;
            }
            RcsProviderCaps caps = null;
            try {
                caps = t.getProviderCaps();
            } catch (final Throwable ignore) {
                // Treat as pending (unknown priority) below.
            }
            final boolean pending = (caps == null);
            final int prio = pending ? Integer.MIN_VALUE : caps.priority;
            cands.add(new Cand(t, prio, pending, elig, tiebreakKeyOf(t)));
        }
        // Priority desc; pending (unknown priority) sinks to the bottom; stable
        // tiebreak on the per-transport key so the order is deterministic.
        Collections.sort(cands, new Comparator<Cand>() {
            @Override
            public int compare(final Cand a, final Cand b) {
                if (a.priority != b.priority) {
                    return Integer.compare(b.priority, a.priority);
                }
                return a.tiebreak.compareTo(b.tiebreak);
            }
        });
        return cands;
    }

    private static Cand find(final List<Cand> list, final RcsTransport t) {
        for (final Cand c : list) {
            if (c.transport == t) {
                return c;
            }
        }
        return null;
    }

    /**
     * State-only half of a transport switch (design §5.4): repoint {@code s.selected}
     * to {@code next} and record the stop/start into {@code d}. Performs NO binder
     * call -- the caller drives {@code stopForSub} / {@code startForSub} OUTSIDE the
     * lock via {@link #applyDecision} (HIGH-1). Because the newly-selected transport
     * isn't provisioned yet, the committed {@code s.active} (what {@link
     * ProviderRegistry#getActiveTransport} returns) is cleared on any real change and
     * only re-committed when the transport reaches PROV_CONFIGURED (design §5.4 step 4,
     * MEDIUM -- see {@link #settleSelection}). Must hold the registry lock.
     */
    private void applySwitchLocked(final ProviderRegistry.SelectionState s,
            @Nullable final RcsTransport next, final Decision d) {
        final RcsTransport prev = s.selected;
        s.selected = next;
        if (prev != next) {
            // New attempt (or SMS): the committed active is no longer valid until the
            // new transport provisions (stays null for SMS).
            s.active = null;
        }
        if (prev != null && prev != next) {
            d.toStop = prev;
        }
        if (next != null) {
            d.toStart = next;
        }
    }

    /** Drive startForSub; startForSub impls are non-blocking + safe off-main. */
    private void driveStart(final RcsTransport t, final RcsSubInfo sub) {
        if (t == null || sub == null) {
            return;
        }
        try {
            t.startForSub(sub);
        } catch (final Throwable e) {
            LogUtil.w(TAG, "RouteSelector: startForSub failed", e);
        }
    }

    /**
     * The selected transport for a sub terminally failed (design §5.4 step 3): mark it
     * failed, arm the post-fallback cooldown, and fall to the next candidate from
     * the ranked order captured at the last selection. No cooldown gate here -- the
     * cooldown only suppresses subsequent EXTERNAL triggers, not this immediate walk.
     *
     * <p><b>Lock discipline (HIGH-1):</b> the failover is decided under the registry
     * lock but the resulting {@code stopForSub} / {@code startForSub} are driven
     * OUTSIDE it via {@link #applyDecision}.
     */
    private void advanceSelectionOnTerminalFailure(final int subId, final String reason) {
        final ProviderRegistry registry = ProviderRegistry.peek();
        if (registry == null) {
            return;
        }
        final RcsSubInfo sub = buildSubInfo(subId);
        final Decision decision;
        synchronized (registry.lock()) {
            final ProviderRegistry.SelectionState s = registry.selectionState(subId);
            if (s.selected == null) {
                return;  // nothing active to fail
            }
            final long now = SystemClock.elapsedRealtime();
            // Debounce: absorb a stale terminal-failure callback from a just-de-
            // selected transport (attributed to the selected one because the sink is
            // identity-less) right after a switch.
            if (now - s.lastAdvanceMs < ADVANCE_DEBOUNCE_MS) {
                LogUtil.i(TAG, "RouteSelector: sub " + subId
                        + " terminal-failure debounced (" + reason + ")");
                return;
            }
            s.lastAdvanceMs = now;
            final RcsTransport failed = s.selected;
            s.failed.add(failed);
            s.cooldownUntilMs = now + ProviderRegistry.SELECTION_COOLDOWN_MS;
            // Next candidate = first in the captured ranked order not yet failed.
            RcsTransport next = null;
            if (s.candidates != null) {
                for (final RcsTransport t : s.candidates) {
                    if (!s.failed.contains(t)) {
                        next = t;
                        break;
                    }
                }
            }
            // RE-RANK BEFORE CONCLUDING SMS-ONLY.
            //
            // s.candidates is a SNAPSHOT taken at the last selection, and transports do not all
            // register at the same time: an in-process one (carrier IMS) is there immediately,
            // while ProviderTransport appears only once it has bound to the provider service. So a
            // higher-priority transport that fails terminally BEFORE the provider attaches walks a
            // list that never contained the transport which can actually serve the line, concludes
            // SMS-only, and arms the cooldown — which then suppresses the provider-attach
            // re-selection that would have fixed it. The sub is latched to SMS with a working
            // transport sitting right there, unregistered at the wrong moment.
            //
            // Device-observed 2026-08-02: CarrierImsMode.probe threw
            // "ImsService is not currently available for subid 1" six times, the carrier transport
            // went terminal, and the sub went SMS-only for the whole session while the Tachyon
            // provider — which had served that exact line an hour earlier — was never driven once.
            //
            // Re-ranking asks the registry what exists NOW rather than what existed then. It is the
            // same query the ordinary selection path uses, so a transport reached this way is one
            // the selector would legitimately have picked; nothing here bypasses eligibility,
            // priority or the failed-set.
            if (next == null) {
                final List<Cand> fresh = rankCandidates(registry, sub);
                for (final Cand c : fresh) {
                    if (!s.failed.contains(c.transport)) {
                        next = c.transport;
                        LogUtil.i(TAG, "RouteSelector: sub " + subId + " re-ranked after terminal "
                                + "failure and found a transport the captured order did not have "
                                + "(prio=" + c.priority + " elig=" + c.eligibility
                                + (c.pending ? " pending" : "") + ") — it registered after the last "
                                + "selection");
                        break;
                    }
                }
                if (next != null) {
                    // The cooldown exists to stop a FLAPPING line churning selection. This is not
                    // flapping: we are handing the sub to a transport that has not been tried at
                    // all. Leaving it armed would make the very next evaluation bail out with
                    // "selection in cooldown; keeping active" and undo the recovery.
                    s.cooldownUntilMs = 0L;
                }
            }
            LogUtil.i(TAG, "RouteSelector: sub " + subId + " terminal-failure (" + reason
                    + ") -> " + (next != null ? "falling to next transport" : "SMS-only"));
            final Decision d = new Decision();
            applySwitchLocked(s, next, d);
            decision = d;
        }
        applyDecision(subId, sub, decision);
    }

    /**
     * PROV_CONFIGURED settled the walk (design §5.4 step 4): commit the currently-
     * selected transport as the sub's ACTIVE transport (what {@link
     * ProviderRegistry#getActiveTransport} returns) and clear the grace timer. Until
     * this commit getActiveTransport stays null, so a caller keying on it can't route
     * RCS to an un-provisioned transport (MEDIUM). The send path's
     * {@link #isRcsAvailableForSub} gate is preserved as the primary guard; this only
     * reconciles the getActiveTransport contract with it.
     */
    private void settleSelection(final int subId) {
        final ProviderRegistry registry = ProviderRegistry.peek();
        if (registry == null) {
            return;
        }
        synchronized (registry.lock()) {
            final ProviderRegistry.SelectionState s = registry.selectionState(subId);
            s.active = s.selected;
            s.graceDeadlineMs = 0L;
        }
    }

    /**
     * Arm the grace-expiry re-select timer, deduped per sub (NIT): cancel any prior
     * grace runnable for this sub before posting a new one, so repeated defers can't
     * stack N runnables that all fire.
     */
    private void scheduleGraceReselect(final int subId, final long delayMs) {
        final Runnable r = () -> {
            synchronized (mGraceRunnables) {
                mGraceRunnables.remove(subId);
            }
            selectTransportForSub(subId, /* freshCycle= */ false, "grace-expiry");
        };
        synchronized (mGraceRunnables) {
            final Runnable prev = mGraceRunnables.get(subId);
            if (prev != null) {
                mHandler.removeCallbacks(prev);
            }
            mGraceRunnables.put(subId, r);
        }
        mHandler.postDelayed(r, Math.max(delayMs, 0L));
    }

    /** Resolve the device's active sub (default-SMS, else default-data). */
    private static int resolveActiveSubId() {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultDataSubscriptionId();
        }
        return subId;
    }

    /**
     * Build a minimal {@link RcsSubInfo} for a sub (subId + best-effort slot/mccMnc);
     * msisdn/imsi are left null -- a transport that needs them holds privileged phone
     * state and reads them itself (mirrors {@code ProviderTransport.requestStartForActiveSub}).
     */
    private static RcsSubInfo buildSubInfo(final int subId) {
        int slot = -1;
        String mccMnc = null;
        try {
            final Context ctx = Factory.get().getApplicationContext();
            final SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
            final SubscriptionInfo info = (sm != null) ? sm.getActiveSubscriptionInfo(subId) : null;
            if (info != null) {
                slot = info.getSimSlotIndex();
                if (info.getMccString() != null && info.getMncString() != null) {
                    mccMnc = info.getMccString() + info.getMncString();
                }
            }
        } catch (final Throwable t) {
            // Best-effort enrichment; the transport re-reads sub details.
        }
        return new RcsSubInfo(subId, slot, null, null, mccMnc, null);
    }

    /**
     * Teach the per-recipient cache that a send was rejected because the peer isn't
     * on RCS ({@code REASON_PEER_NOT_RCS}). Normalizes to the same E.164 cache key as
     * {@link #isPeerRcsCapable} / the provider lookup so reads and writes agree.
     * Transport-agnostic: called by the send path regardless of which transport
     * handled the send.
     */
    public void notePeerNotRcs(@Nullable final String rawPhone) {
        if (rawPhone == null || rawPhone.trim().isEmpty()) {
            return;
        }
        String key = rawPhone.trim();
        try {
            final String e164 = PhoneUtils.getDefault().getCanonicalBySimLocale(rawPhone);
            if (e164 != null && !e164.isEmpty()) {
                key = e164;
            }
        } catch (final Throwable t) {
            // Fall back to the trimmed raw value.
        }
        putPeerCap(key, IRcsProvider.CAP_SMS_ONLY);
    }
}

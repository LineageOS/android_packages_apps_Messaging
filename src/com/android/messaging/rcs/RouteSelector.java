/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * Route selection and send gating for RCS. Holds the per-subscription registration, provisioning
 * and ToS state reported by the transports, the per-recipient capability cache, and the sticky
 * transport-selection algorithm over {@link ProviderRegistry}. The send path uses SMS unless RCS is
 * affirmatively up for the subscription. See docs/rcs/architecture.md.
 *
 * <p>Callbacks arrive on a binder thread and the send path reads from action threads; state is
 * guarded by {@code mLock}.
 */
public final class RouteSelector {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private final Object mLock = new Object();
    /** Notified on every registration, provisioning or provider change; a waiting send wakes. */
    private final Object mStateMonitor = new Object();
    private final SparseIntArray mRegState = new SparseIntArray();
    private final SparseIntArray mProvState = new SparseIntArray();
    private final SparseArray<RcsProviderCaps> mCaps = new SparseArray<>();

    // Per-subscription ToS state (IRcsProviderCallback.TOS_*) and the latest prompt, cached so the
    // settings row and the re-prompt need no binder call. TOS_NONE for an unseen subscription.
    private final SparseIntArray mTosState = new SparseIntArray();
    private final SparseArray<RcsTosPrompt> mTosPrompt = new SparseArray<>();

    // Per-recipient capability cache: E.164 -> CAP_SMS_ONLY or CAP_RCS. CAP_UNKNOWN is never
    // stored, so a transient miss is retried. Cleared when the provider goes away.
    private final Map<String, Integer> mPeerCap = new HashMap<>();

    /** Main-looper handler for the grace-expiry re-select timer. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Armed grace-expiry runnables by subscription, so repeated defers keep one timer each. */
    private final SparseArray<Runnable> mGraceRunnables = new SparseArray<>();

    void onRegistrationStateChanged(final int subId, final int state) {
        synchronized (mLock) {
            mRegState.put(subId, state);
        }
        LogUtil.i(TAG, "RouteSelector: sub " + subId + " reg=" + state);
        notifyStateChanged();
        // REG_FAILED is a terminal verdict and moves selection; REG_UNREGISTERED is a transient
        // drop the transport recovers from itself, and does not.
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
        notifyStateChanged();
        // A terminal provisioning failure moves selection; PROV_CONFIGURED commits it.
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
        notifyStateChanged();
    }

    private void notifyStateChanged() {
        synchronized (mStateMonitor) {
            mStateMonitor.notifyAll();
        }
    }

    // ---- ToS gate state ----

    /**
     * Update the cached ToS state ({@code IRcsProviderCallback.TOS_*}). A non-null prompt replaces
     * the stored one; a null prompt keeps it, except that TOS_NONE clears it. Called on a binder
     * thread.
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
     * Cached ToS state for the subscription; {@code TOS_NONE} by default. Safe on the main thread.
     */
    public int getTosState(final int subId) {
        synchronized (mLock) {
            return mTosState.get(subId, IRcsProviderCallback.TOS_NONE);
        }
    }

    /** Latest cached ToS prompt, or null; used to re-open the consent dialog from settings. */
    @Nullable
    public RcsTosPrompt getTosPrompt(final int subId) {
        synchronized (mLock) {
            return mTosPrompt.get(subId);
        }
    }

    // ---- per-recipient capability cache ----

    /**
     * Cached verdict for an already-normalised key, or {@code CAP_UNKNOWN}. Used by
     * {@link ProviderTransport#lookupRcsCapability}; other callers use {@link #isPeerRcsCapable}.
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

    /** Store a sticky verdict; {@code CAP_UNKNOWN} is ignored. */
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
     * Cached verdict for {@code phoneE164} ({@code IRcsProvider.CAP_*}), or {@code CAP_UNKNOWN}.
     * Never crosses binder, so the compose UI may call it on the main thread. Does not include the
     * subscription gate ({@link #isRcsAvailableForSub}).
     */
    public int isPeerRcsCapable(final int subId, @Nullable final String phoneE164) {
        if (phoneE164 == null) {
            return IRcsProvider.CAP_UNKNOWN;
        }
        return getCachedPeerCap(phoneE164);
    }

    // ---- group route ----

    /** Whether a group send may go over RCS; the same test as {@link #isRcsAvailableForSub}. */
    public boolean isGroupRcsAvailableForSub(final int subId) {
        return isRcsAvailableForSub(subId);
    }

    /** Whether RCS is up enough on this sub to be worth attempting a send; false means SMS. */
    public boolean isRcsAvailableForSub(final int subId) {
        // The master toggle routes everything to SMS/MMS regardless of registration.
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

    /**
     * {@link #isRcsAvailableForSub} for a send, which runs off the main thread. While the state is
     * still arriving (a process the send started, before the transport reports; provisioning in
     * progress; registering) it waits up to {@link RcsSendReadiness#MAX_WAIT_MS} for it instead of
     * routing SMS. When RCS is known to be unavailable it returns at once.
     */
    public boolean awaitRcsAvailableForSub(final int subId) {
        final long start = SystemClock.elapsedRealtime();
        final boolean first = readinessForSub(subId) == RcsSendReadiness.SETTLING;
        final boolean up = RcsSendReadiness.await(() -> readinessForSub(subId), mStateMonitor,
                RcsSendReadiness.MAX_WAIT_MS);
        if (first) {
            LogUtil.i(TAG, "RouteSelector: sub " + subId + " send waited "
                    + (SystemClock.elapsedRealtime() - start) + "ms for the RCS state -> "
                    + (up ? "RCS" : "SMS"));
        }
        return up;
    }

    /** {@link #awaitRcsAvailableForSub} for a group send. */
    public boolean awaitGroupRcsAvailableForSub(final int subId) {
        return awaitRcsAvailableForSub(subId);
    }

    /** {@link RcsSendReadiness} of the subscription from the cached state. */
    int readinessForSub(final int subId) {
        final boolean up;
        final boolean settling;
        synchronized (mLock) {
            final boolean regKnown = mRegState.indexOfKey(subId) >= 0;
            final boolean provKnown = mProvState.indexOfKey(subId) >= 0;
            final int reg = mRegState.get(subId, IRcsProviderCallback.REG_UNREGISTERED);
            final int prov = mProvState.get(subId, IRcsProviderCallback.PROV_NOT_PROVISIONED);
            up = reg == IRcsProviderCallback.REG_REGISTERED
                    && prov == IRcsProviderCallback.PROV_CONFIGURED;
            settling = !provKnown || prov == IRcsProviderCallback.PROV_IN_PROGRESS
                    || reg == IRcsProviderCallback.REG_REGISTERING
                    || (prov == IRcsProviderCallback.PROV_CONFIGURED && !regKnown);
        }
        final ProviderRegistry registry = ProviderRegistry.peek();
        final boolean selecting = registry != null
                && registry.mayStillSelect(subId, SystemClock.elapsedRealtime());
        return RcsSendReadiness.classify(RcsFeatureSettings.isRcsEnabled(), selecting, up,
                settling);
    }

    @Nullable
    public RcsProviderCaps getCapsForSub(final int subId) {
        synchronized (mLock) {
            return mCaps.get(subId);
        }
    }

    /**
     * Cached registration state ({@code REG_*}); {@code REG_UNREGISTERED} by default. For the
     * status screen.
     */
    public int getRegState(final int subId) {
        synchronized (mLock) {
            return mRegState.get(subId, IRcsProviderCallback.REG_UNREGISTERED);
        }
    }

    /**
     * Cached provisioning state ({@code PROV_*}); {@code PROV_NOT_PROVISIONED} by default. For the
     * status screen.
     */
    public int getProvState(final int subId) {
        synchronized (mLock) {
            return mProvState.get(subId, IRcsProviderCallback.PROV_NOT_PROVISIONED);
        }
    }

    // ---- Sticky transport selection ----
    //
    // One transport per subscription: drop LINE_INELIGIBLE (UNKNOWN is kept, since a provider still
    // binding answers it), rank by declared priority, start the top, fall to the next on a terminal
    // failure, and commit on PROV_CONFIGURED. Reselection runs only on the defined triggers, never
    // on a transient drop. See docs/rcs/architecture.md.

    /**
     * While a higher-priority provider is still binding, how long to defer committing to a
     * lower-priority transport, so a provider-only device does not start the carrier transport
     * first. A handshake reselects at once; the timer bounds the no-provider case. TODO: confirm 15
     * s covers a cold provider bind on the slowest supported device.
     */
    private static final long PENDING_GRACE_MS = 15_000L;

    /**
     * Two terminal failures closer than this are coalesced. The callback sink carries no transport
     * identity, so a late failure from a just-deselected transport would otherwise knock off the
     * new one. If a real second candidate can fail within this window, the fix is a transport id on
     * the callback rather than a wider window.
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

    /** Deterministic tie-break key: the bound provider's package, else the class. */
    private static String tiebreakKeyOf(final RcsTransport t) {
        if (t instanceof BoundProviderTransport) {
            return ((BoundProviderTransport) t).getPackageName();
        }
        return t.getClass().getName();
    }

    /** Re-run selection for every known subscription plus the active one. */
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
     * Fresh-cycle selection for the active subscription (boot, app start, subscription and carrier
     * config triggers).
     */
    public void selectForActiveSub(final String reason) {
        final int subId = resolveActiveSubId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            LogUtil.i(TAG, "RouteSelector: no valid active sub; not selecting (" + reason + ")");
            return;
        }
        selectTransportForSub(subId, /* freshCycle= */ true, reason);
    }

    /** Fresh-cycle selection for an explicit subscription. */
    public void selectTransportForSub(final int subId) {
        selectTransportForSub(subId, /* freshCycle= */ true, "explicit");
    }

    /**
     * Ranks the transports for {@code subId} and drives {@code startForSub} on the chosen one. The
     * fallback walk completes asynchronously through the state callbacks. Safe from any thread.
     *
     * <p>The decision is computed under the registry lock, but {@code startForSub} and
     * {@code stopForSub} are binder calls into another app and run after it is released:
     * {@link ProviderRegistry#getActiveTransport} takes the same lock on the send path.
     *
     * @param freshCycle true for an external trigger, which resets the failed set
     */
    public void selectTransportForSub(final int subId, final boolean freshCycle,
            final String reason) {
        if (!RcsFeatureSettings.isRcsEnabled()) {
            // Master toggle off: provision nothing.
            return;
        }
        final ProviderRegistry registry = ProviderRegistry.peek();
        if (registry == null) {
            return;
        }

        // Skip ranking (binder calls) during the post-fallback cooldown; re-checked under the lock.
        synchronized (registry.lock()) {
            final ProviderRegistry.SelectionState s = registry.selectionState(subId);
            if (SystemClock.elapsedRealtime() < s.cooldownUntilMs) {
                LogUtil.i(TAG, "RouteSelector: sub " + subId
                        + " selection in cooldown; keeping active (" + reason + ")");
                return;
            }
        }

        final RcsSubInfo sub = buildSubInfo(subId);

        // Rank outside the lock: canServeSub and getProviderCaps may cross binder.
        final List<Cand> ranked = rankCandidates(registry, sub);

        final Decision decision;
        synchronized (registry.lock()) {
            final ProviderRegistry.SelectionState s = registry.selectionState(subId);
            decision = computeSelectionLocked(s, subId, ranked, freshCycle, reason);
        }

        // Binder calls and the grace timer, outside the lock.
        applyDecision(subId, sub, decision);
    }

    /**
     * Computes the decision and updates the selection state without any binder call. Caller holds
     * {@code registry.lock()}.
     */
    private Decision computeSelectionLocked(final ProviderRegistry.SelectionState s,
            final int subId, final List<Cand> ranked, final boolean freshCycle,
            final String reason) {
        final Decision d = new Decision();
        final long now = SystemClock.elapsedRealtime();

        // Cooldown re-check under the lock.
        if (now < s.cooldownUntilMs) {
            LogUtil.i(TAG, "RouteSelector: sub " + subId
                    + " selection in cooldown; keeping active (" + reason + ")");
            return d;
        }

        s.evaluated = true;
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
        // Keep the ranked order for the fallback walk.
        final List<RcsTransport> order = new ArrayList<>(eligible.size());
        for (final Cand c : eligible) {
            order.add(c.transport);
        }
        s.candidates = order;
        // Log every candidate, not only the winner: absent, INELIGIBLE and pending need different
        // fixes.
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

        // Transports firmly LINE_ELIGIBLE now that were not at the last evaluation. Only such a
        // transport may preempt, so steady-state eligibility cannot churn the line.
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
                // Preempt only for a strictly higher-priority, firmly and newly eligible transport.
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
                    // Keep it, and re-drive startForSub so a re-attach re-registers (idempotent).
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

        // ---- Fresh pick ----
        // If a higher-priority provider is still binding, do not commit to a known one yet.
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
                // Re-select when the grace expires even if no handshake arrives.
                d.graceReselectDelayMs = s.graceDeadlineMs - now + 50L;
                return d;
            }
            LogUtil.i(TAG, "RouteSelector: sub " + subId
                    + " grace expired; committing to best known transport");
        }
        s.graceDeadlineMs = 0L;

        if (firstKnown == null) {
            // Nothing selectable: SMS only. A transport still pending past the grace counts as
            // unavailable.
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
     * Applies a {@link Decision} outside the registry lock: stop, start, and arm the grace timer.
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

    /** A computed selection outcome, produced under the registry lock and applied outside it. */
    private static final class Decision {
        @Nullable RcsTransport toStop;      // previous selected -> stopForSub(subId)
        @Nullable RcsTransport toStart;     // selected -> startForSub(sub)
        long graceReselectDelayMs = -1L;    // >=0 -> schedule a grace re-select
    }

    /** Enumerate and rank the transports for a subscription, without the registry lock. */
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
                // Log the drop; the selection line names only the winner.
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
        // Priority descending; pending (unknown priority) sorts last; stable tie-break.
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
     * State half of a switch: repoint {@code s.selected} and record the stop and start in {@code
     * d}. The committed {@code s.active} is cleared on a change and set again by {@link
     * #settleSelection}. Caller holds the registry lock.
     */
    private void applySwitchLocked(final ProviderRegistry.SelectionState s,
            @Nullable final RcsTransport next, final Decision d) {
        final RcsTransport prev = s.selected;
        s.selected = next;
        if (prev != next) {
            // The new transport is not provisioned yet (or this is SMS).
            s.active = null;
        }
        if (prev != null && prev != next) {
            d.toStop = prev;
        }
        if (next != null) {
            d.toStart = next;
        }
    }

    /** Drive startForSub; implementations are non-blocking and safe off the main thread. */
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
     * The selected transport failed terminally: mark it failed, arm the cooldown, and move to the
     * next candidate from the captured order. The cooldown gates later external triggers, not this
     * walk. Decided under the registry lock, applied outside it.
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
            // Debounce a late failure from a just-deselected transport.
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
            // Re-rank before concluding SMS. The captured order is a snapshot, and a provider
            // transport registers only once bound, so a transport that fails before the provider
            // attaches would otherwise latch the line to SMS. Same query as ordinary selection, so
            // eligibility, priority and the failed set all still apply.
            if (next == null) {
                final List<Cand> fresh = rankCandidates(registry, sub);
                for (final Cand c : fresh) {
                    if (!s.failed.contains(c.transport)) {
                        next = c.transport;
                        LogUtil.i(TAG, "RouteSelector: sub " + subId + " re-ranked after terminal "
                                + "failure and found a transport the captured order did not have "
                                + "(prio=" + c.priority + " elig=" + c.eligibility
                                + (c.pending ? " pending" : "")
                                + ") — it registered after the last "
                                + "selection");
                        break;
                    }
                }
                if (next != null) {
                    // Handing the line to an untried transport is not flapping; clear the cooldown
                    // so the next evaluation does not undo it.
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
     * PROV_CONFIGURED: commit the selected transport as active and clear the grace timer. Until
     * then {@link ProviderRegistry#getActiveTransport} returns null. {@link #isRcsAvailableForSub}
     * remains the primary send gate.
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

    /** Arm the grace-expiry reselect, replacing any timer already armed for the subscription. */
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
     * A minimal {@link RcsSubInfo}: subscription id, slot and MCC/MNC. MSISDN and IMSI are left
     * null; a transport that needs them reads them itself.
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
            // Best-effort; the transport re-reads the subscription details.
        }
        return new RcsSubInfo(subId, slot, null, null, mccMnc, null);
    }

    /**
     * Record that a send was refused with {@code REASON_PEER_NOT_RCS}, under the same E.164 key the
     * lookup uses. Called whichever transport handled the send.
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

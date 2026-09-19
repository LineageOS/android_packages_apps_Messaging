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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.text.TextUtils;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;

import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side registry of RCS transports (design §4.3): replaces the single 1:1
 * {@link ProviderTransport} singleton assumption. It holds N {@link RcsTransport}
 * instances --
 * <ul>
 *   <li>the discovered <b>external</b> providers, one {@link BoundProviderTransport}
 *       per component resolving {@link RcsConstants#ACTION_BIND_RCS_PROVIDER}
 *       (enumerated via {@link PackageManager#queryIntentServices}); and
 *   <li>the statically-registered <b>in-process</b> carrier-IMS transport (a later
 *       phase implements {@code CarrierImsTransport}; here we only expose the
 *       registration slot).
 * </ul>
 *
 * <p>{@link RouteSelector} consults the registry to pick one transport per subId
 * (design §5.4). Per-sub selection <b>state</b> lives here (keyed by subId, so
 * dual-SIM works: e.g. the provider on SIM1, carrier-IMS on SIM2 concurrently); the
 * selection <b>algorithm</b> is in {@link RouteSelector#selectTransportForSub}.
 *
 * <p>Trust model (design §4.2): providers are preinstalled-privileged only, so
 * every enumerated component is already trusted and priority is pure
 * provider-declared with no trust-cap.
 *
 * <p><b>Registry is the live RCS path (design §2, build-plan §2).</b> The legacy
 * {@link ProviderTransport} singleton is no longer <i>the</i> path: it is
 * registered here via {@link #registerLegacyTransport} as one {@link RcsTransport}
 * (the provider binding) alongside the in-process carrier transport and any discovered
 * {@link BoundProviderTransport}. {@link RouteSelector#selectTransportForSub} picks
 * one transport per sub across all of them, and the send path routes through
 * {@link #getActiveTransport}. On a provider-only device selection deterministically
 * lands on the legacy transport, so behaviour is byte-identical to the pre-registry
 * path (the legacy singleton still owns the app's rich provider-only surface --
 * createGroup/sendFile/reactions/RBM -- which stays on it directly).
 * {@link #discover()} therefore <b>skips</b> the package the legacy transport already
 * binds, so the provider is never double-bound (which would churn the Tachyon identity).
 */
public final class ProviderRegistry {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static volatile ProviderRegistry sInstance;

    private final Context mAppContext;
    private final Object mLock = new Object();

    /** Discovered external transports, keyed by component (flattened string). */
    private final Map<String, BoundProviderTransport> mExternal = new LinkedHashMap<>();

    /**
     * The statically-registered in-process transport slot (carrier-IMS),
     * registered from {@code CarrierImsTransport.register} at main-process startup
     * (design §4.3, §6, wired in {@code BugleApplication.initializeSync}).
     */
    @Nullable private RcsTransport mInProcessTransport;

    /**
     * The legacy {@link ProviderTransport} singleton, registered as one
     * {@link RcsTransport} (the provider binding) via
     * {@link #registerLegacyTransport}. Held so {@link RouteSelector} ranks it
     * uniformly with the carrier + external transports; {@link #discover()} skips
     * {@link #mLegacyPackage} so the same provider is never bound twice.
     */
    @Nullable private RcsTransport mLegacyTransport;
    /** The package the legacy transport already binds; excluded from discovery. */
    @Nullable private String mLegacyPackage;

    /**
     * Shared inbound callback sink handed to each {@link BoundProviderTransport}:
     * the app's process-wide {@link RcsCallbackRouter}, injected once via
     * {@link #setCallbackSink} from {@code BugleApplication} (design §6.5). Nullable
     * only for the brief window before that wiring runs / in tests; a bound
     * transport constructed with a null sink binds + handshakes but skips attach.
     */
    @Nullable private IRcsProviderCallback mCallbackSink;

    /** Per-sub selection state (design §5.4). */
    private final SparseArray<SelectionState> mSelection = new SparseArray<>();

    /** Post-fallback cooldown so a flapping line can't churn selection. */
    static final long SELECTION_COOLDOWN_MS = 60_000L;

    /**
     * Shared single-thread background worker for off-main RCS-framework work
     * (HIGH-2): discovery's {@link PackageManager#queryIntentServices} and
     * selection's {@code canServeSub} / {@code getProviderCaps} are binder calls
     * that must never run on the main thread. Every trigger (app-start, boot,
     * package / sub / carrier-config change) posts its discover + reselect work
     * here instead of running it inline on the main / receiver thread.
     */
    private static final ExecutorService sWorker =
            Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "rcs-registry");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    /**
     * Post RCS-framework work (discovery / selection) to the shared background
     * worker so it never runs on the main / binder thread (HIGH-2). Callers that
     * originate on a broadcast thread should hold the process alive across the hop
     * with {@code goAsync()} and {@code finish()} the {@code PendingResult} in the
     * posted work.
     */
    public static void postWork(final Runnable work) {
        sWorker.execute(work);
    }

    private ProviderRegistry(final Context context) {
        mAppContext = context.getApplicationContext();
    }

    public static ProviderRegistry get(final Context context) {
        if (sInstance == null) {
            synchronized (ProviderRegistry.class) {
                if (sInstance == null) {
                    sInstance = new ProviderRegistry(context);
                }
            }
        }
        return sInstance;
    }

    @Nullable
    public static ProviderRegistry peek() {
        return sInstance;
    }

    /**
     * Inject the shared inbound callback sink used by every bound transport. Called
     * once from {@code BugleApplication.initializeSync} with the process-wide
     * {@link RcsCallbackRouter} (design §6.5), before {@link #discover()} creates
     * any {@link BoundProviderTransport}, so each attaches with the real sink.
     */
    public void setCallbackSink(@Nullable final IRcsProviderCallback sink) {
        synchronized (mLock) {
            mCallbackSink = sink;
        }
    }

    /**
     * Static-registration hook for the in-process carrier-IMS transport (design
     * §4.3, §6). A later phase constructs {@code CarrierImsTransport} in the
     * {@code :ims} process and calls this. Passing null clears the slot
     * (uninstall/disable).
     */
    public void registerInProcessTransport(@Nullable final RcsTransport transport) {
        synchronized (mLock) {
            mInProcessTransport = transport;
        }
        LogUtil.i(TAG, "ProviderRegistry: in-process transport "
                + (transport != null ? "registered" : "cleared"));
    }

    /**
     * Register the legacy {@link ProviderTransport} singleton as the provider's
     * {@link RcsTransport} (design §4.3, build-plan §2). {@code ownedPackage} is the
     * provider package it already binds; {@link #discover()} skips it so the same
     * provider is never bound a second time as a {@link BoundProviderTransport}
     * (double-attach churns the Tachyon identity -- see the "new provider install
     * needs full re-cold" hazard). Call once from {@code BugleApplication} in the
     * main process, before the first {@link #discover()}.
     */
    public void registerLegacyTransport(@Nullable final RcsTransport transport,
            @Nullable final String ownedPackage) {
        synchronized (mLock) {
            mLegacyTransport = transport;
            mLegacyPackage = ownedPackage;
        }
        LogUtil.i(TAG, "ProviderRegistry: legacy transport registered, owns pkg=" + ownedPackage);
    }

    /**
     * Enumerate installed external providers by action and (re)create a
     * {@link BoundProviderTransport} for each (design §4.1). Call on the selection
     * triggers: boot/app-start and provider install/uninstall/replace. Idempotent
     * -- existing bound transports are kept; vanished ones are shut down.
     */
    public void discover() {
        final List<ComponentName> resolved = resolveProviderComponents(mAppContext);
        final Set<String> live = new HashSet<>();
        boolean changed = false;
        final IRcsProviderCallback sink;
        synchronized (mLock) {
            sink = mCallbackSink;
            for (final ComponentName cn : resolved) {
                // The legacy ProviderTransport already binds this package -- do NOT
                // create a second BoundProviderTransport for it (double-attach
                // churns the Tachyon identity).
                if (mLegacyPackage != null && mLegacyPackage.equals(cn.getPackageName())) {
                    continue;
                }
                final String key = cn.flattenToShortString();
                live.add(key);
                BoundProviderTransport t = mExternal.get(key);
                if (t == null) {
                    t = new BoundProviderTransport(mAppContext, cn, sink);
                    mExternal.put(key, t);
                    LogUtil.i(TAG, "ProviderRegistry: discovered provider " + key);
                    t.ensureBound();
                    changed = true;
                }
            }
            // Shut down transports whose component is no longer installed.
            final List<String> gone = new ArrayList<>();
            for (final String key : mExternal.keySet()) {
                if (!live.contains(key)) {
                    gone.add(key);
                }
            }
            for (final String key : gone) {
                final BoundProviderTransport t = mExternal.remove(key);
                if (t != null) {
                    LogUtil.i(TAG, "ProviderRegistry: provider removed " + key);
                    t.shutdown();
                    changed = true;
                }
            }
        }
        // A provider set change is a selection trigger (design §5.4): re-evaluate
        // the active sub so a newly-installed higher-priority provider can take
        // over and a removed one falls back. Fresh cycle: give previously-failed
        // transports another chance.
        if (changed) {
            reselect("discover", /* freshCycle= */ true);
        }
    }

    /**
     * Re-run trigger-driven selection (design §5.4) for every sub the registry has
     * state for, plus the active sub. Delegates to {@link RouteSelector} (the
     * algorithm owner) via the process-wide {@link RcsCallbackRouter}. Safe no-op
     * before the router is wired.
     *
     * @param reason      human-readable trigger label (logs only)
     * @param freshCycle  true for an external trigger (boot / sub change / provider
     *                    install / carrier-config change): resets the per-sub
     *                    failed-transport set so the walk restarts. false for a
     *                    provider-handshake nudge: preserves the failed set and only
     *                    resolves a pending higher-priority provider / allows a
     *                    strictly-higher-priority preemption.
     */
    public void reselect(final String reason, final boolean freshCycle) {
        final RcsCallbackRouter router = RcsCallbackRouter.peek();
        if (router == null) {
            return;
        }
        router.getRouteSelector().reselectAll(reason, freshCycle);
    }

    /**
     * All transports the selector should consider: the in-process carrier-IMS
     * transport (if registered) plus every discovered external provider. Order is
     * not significant -- {@link RouteSelector} sorts by declared priority.
     */
    public List<RcsTransport> getTransports() {
        synchronized (mLock) {
            final List<RcsTransport> out = new ArrayList<>(mExternal.size() + 2);
            if (mInProcessTransport != null) {
                out.add(mInProcessTransport);
            }
            if (mLegacyTransport != null) {
                out.add(mLegacyTransport);
            }
            out.addAll(mExternal.values());
            return out;
        }
    }

    // ---- per-sub selection state (design §5.4) ----

    /**
     * Mutable per-sub selection state owned by the registry. Read/written under
     * the registry lock by {@link RouteSelector} via the accessors below.
     */
    static final class SelectionState {
        /**
         * The transport currently SELECTED and being driven ({@code startForSub})
         * for the sub, or null (=> SMS/none). This is the selection walk's in-flight
         * attempt; it is <b>not</b> what {@link #getActiveTransport} returns -- that
         * is {@link #active}, committed only at PROV_CONFIGURED (design §5.4 step 4).
         */
        @Nullable RcsTransport selected;
        /**
         * The transport COMMITTED as active for the sub: set only when {@link
         * #selected} reaches PROV_CONFIGURED (design §5.4 step 4). This is what
         * {@link ProviderRegistry#getActiveTransport} returns, so a caller keying on
         * a non-null active transport can never route RCS to an un-provisioned
         * transport (MEDIUM). Cleared whenever the selected transport changes.
         */
        @Nullable RcsTransport active;
        /** Transports that terminally failed for this sub this cycle (skip on walk). */
        final Set<RcsTransport> failed = new HashSet<>();
        /**
         * Transports that were firmly {@code LINE_ELIGIBLE} at the previous
         * evaluation. Lets the selector preempt the sticky-selected transport only on
         * the ineligible-&gt;eligible <i>edge</i> (design §5.4 anti-thrash, MEDIUM),
         * never on steady-state eligibility -- so a persistently-eligible higher-
         * priority transport that keeps failing to provision can't churn selection.
         */
        final Set<RcsTransport> priorEligible = new HashSet<>();
        /**
         * The ranked (priority-desc) candidate order captured at the last selection,
         * used by the async fallback walk to pick the next transport on a terminal
         * failure of {@link #active} without re-ranking.
         */
        @Nullable List<RcsTransport> candidates;
        /**
         * {@link android.os.SystemClock#elapsedRealtime} deadline (0 = none) after
         * which a still-<i>pending</i> higher-priority provider (one whose caps
         * aren't known yet because it's mid-bind) stops blocking a commit to a
         * lower-priority transport. See {@link RouteSelector} pending-defer.
         */
        long graceDeadlineMs;
        /** elapsedRealtime before which re-selection is suppressed (post-fallback cooldown). */
        long cooldownUntilMs;
        /**
         * elapsedRealtime of the last fallback advance. Used to debounce terminal-
         * failure callbacks: the shared callback sink carries no transport identity,
         * so a failure is attributed to {@link #active}; a short debounce absorbs a
         * stale callback from a just-de-selected transport (which stopForSub should
         * have quiesced) so it can't knock the newly-active transport off.
         */
        long lastAdvanceMs;
    }

    SelectionState selectionState(final int subId) {
        synchronized (mLock) {
            SelectionState s = mSelection.get(subId);
            if (s == null) {
                s = new SelectionState();
                mSelection.put(subId, s);
            }
            return s;
        }
    }

    /** SubIds the registry currently holds selection state for (design §5.4). */
    List<Integer> knownSubIds() {
        synchronized (mLock) {
            final List<Integer> out = new ArrayList<>(mSelection.size());
            for (int i = 0; i < mSelection.size(); i++) {
                out.add(mSelection.keyAt(i));
            }
            return out;
        }
    }

    /**
     * The COMMITTED active transport for a sub -- the selected transport once it has
     * reached PROV_CONFIGURED (design §5.4 step 4, MEDIUM) -- or null when none is
     * provisioned yet (route SMS). Returns null while a selected transport is still
     * provisioning, so a caller keying on {@code != null} can't route RCS pre-provision.
     */
    @Nullable
    public RcsTransport getActiveTransport(final int subId) {
        synchronized (mLock) {
            final SelectionState s = mSelection.get(subId);
            return s != null ? s.active : null;
        }
    }

    Object lock() {
        return mLock;
    }

    // ---- discovery helpers ----

    /**
     * Resolve every installed provider service matching the bind action. Sorted
     * by manifest {@code priority} desc then package name for determinism (the
     * declared {@link org.lineageos.rcs.provider.RcsProviderCaps#priority} is only
     * readable after bind, so manifest priority is the pre-bind ordering hint).
     */
    static List<ComponentName> resolveProviderComponents(final Context context) {
        final PackageManager pm = context.getPackageManager();
        final Intent intent = new Intent(RcsConstants.ACTION_BIND_RCS_PROVIDER);
        List<ResolveInfo> infos;
        try {
            infos = pm.queryIntentServices(intent, PackageManager.MATCH_SYSTEM_ONLY);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ProviderRegistry: queryIntentServices failed", t);
            infos = Collections.emptyList();
        }
        final List<ComponentName> out = new ArrayList<>();
        if (infos != null) {
            for (final ResolveInfo ri : infos) {
                final ServiceInfo si = (ri != null) ? ri.serviceInfo : null;
                if (si == null || TextUtils.isEmpty(si.packageName) || TextUtils.isEmpty(si.name)) {
                    continue;
                }
                out.add(new ComponentName(si.packageName, si.name));
            }
        }
        // Deterministic order: package name (manifest priority is not surfaced on
        // ResolveInfo for services uniformly across API levels).
        // TODO(rcs-framework): if two providers ever coexist, confirm the
        // pre-bind ordering hint we want here (manifest priority vs package).
        Collections.sort(out, (a, b) -> a.getPackageName().compareTo(b.getPackageName()));
        return out;
    }

    /**
     * The first provider package that resolves the bind action, or {@code null} when none does.
     *
     * <p>Null is the ordinary answer on a device with no RCS provider installed, which is most of
     * them: nothing in AOSP or LineageOS implements {@link IRcsProvider}. Callers must treat it as
     * "no provider" and fall back to carrier RCS or SMS/MMS — {@link ProviderTransport} says
     * "staying SMS-only" and reschedules, and the registry simply owns no legacy package.
     *
     * <p>There used to be a hard-coded fallback package here for a provider that had not yet
     * declared the action. It is gone: naming one specific application as the presumed provider is
     * not something a published app should do, and a caller that cannot handle "no provider" is
     * broken on the common device anyway.
     */
    @Nullable
    public static String resolveProviderPackage(final Context context) {
        final List<ComponentName> components = resolveProviderComponents(context);
        if (!components.isEmpty()) {
            return components.get(0).getPackageName();
        }
        LogUtil.i(TAG, "ProviderRegistry: no provider resolves the bind action");
        return null;
    }
}

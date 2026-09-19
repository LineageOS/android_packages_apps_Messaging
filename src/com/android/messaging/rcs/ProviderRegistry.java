/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * Holds every RCS transport (the {@link ProviderTransport} singleton, the in-process carrier
 * transport, a {@link BoundProviderTransport} per other provider) and the per-subscription
 * selection state that {@link RouteSelector} drives. See docs/rcs/architecture.md.
 */
public final class ProviderRegistry {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static volatile ProviderRegistry sInstance;

    private final Context mAppContext;
    private final Object mLock = new Object();

    /** Discovered external transports, keyed by component (flattened string). */
    private final Map<String, BoundProviderTransport> mExternal = new LinkedHashMap<>();

    /** The in-process carrier transport slot, filled by {@code CarrierImsTransport.register}. */
    @Nullable private RcsTransport mInProcessTransport;

    /** The {@link ProviderTransport} singleton; its package is skipped by {@link #discover()}. */
    @Nullable private RcsTransport mLegacyTransport;
    /** The package the legacy transport already binds; excluded from discovery. */
    @Nullable private String mLegacyPackage;

    /**
     * The inbound sink handed to each {@link BoundProviderTransport}; null before startup wiring.
     */
    @Nullable private IRcsProviderCallback mCallbackSink;

    private final SparseArray<SelectionState> mSelection = new SparseArray<>();

    /** Post-fallback cooldown so a flapping line can't churn selection. */
    static final long SELECTION_COOLDOWN_MS = 60_000L;

    /** Single worker for discovery and selection, which make binder calls. */
    private static final ExecutorService sWorker =
            Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "rcs-registry");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    /**
     * Post discovery or selection work to the worker. A broadcast caller holds the process with
     * {@code goAsync()} and finishes the {@code PendingResult} in the work.
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

    /** Inject the shared inbound sink, once at startup, before {@link #discover()}. */
    public void setCallbackSink(@Nullable final IRcsProviderCallback sink) {
        synchronized (mLock) {
            mCallbackSink = sink;
        }
    }

    /** Fill or (with null) clear the in-process carrier transport slot. */
    public void registerInProcessTransport(@Nullable final RcsTransport transport) {
        synchronized (mLock) {
            mInProcessTransport = transport;
        }
        LogUtil.i(TAG, "ProviderRegistry: in-process transport "
                + (transport != null ? "registered" : "cleared"));
    }

    /**
     * Register the singleton and the package it binds. Call once, before the first {@link
     * #discover()}.
     */
    public void registerLegacyTransport(@Nullable final RcsTransport transport,
            @Nullable final String ownedPackage) {
        synchronized (mLock) {
            mLegacyTransport = transport;
            mLegacyPackage = ownedPackage;
        }
        LogUtil.i(TAG, "ProviderRegistry: legacy transport registered, owns pkg=" + ownedPackage);
    }

    /** Create a transport per newly installed provider and shut down vanished ones; idempotent. */
    public void discover() {
        final List<ComponentName> resolved = resolveProviderComponents(mAppContext);
        final Set<String> live = new HashSet<>();
        boolean changed = false;
        final IRcsProviderCallback sink;
        synchronized (mLock) {
            sink = mCallbackSink;
            for (final ComponentName cn : resolved) {
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
        // A changed provider set is a fresh cycle: failed transports get another chance.
        if (changed) {
            reselect("discover", /* freshCycle= */ true);
        }
    }

    /**
     * Re-run selection for every known subscription and the active one.
     *
     * @param freshCycle true for an external trigger, which resets the failed set
     */
    public void reselect(final String reason, final boolean freshCycle) {
        final RcsCallbackRouter router = RcsCallbackRouter.peek();
        if (router == null) {
            return;
        }
        router.getRouteSelector().reselectAll(reason, freshCycle);
    }

    /** Every transport selection considers. Order is not significant. */
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

    /** Per-subscription selection state, guarded by the registry lock. */
    static final class SelectionState {
        /** The transport being driven for the subscription, or null for SMS. */
        @Nullable RcsTransport selected;
        /** The committed transport: set when {@link #selected} reaches PROV_CONFIGURED. */
        @Nullable RcsTransport active;
        /** Transports that terminally failed for this sub this cycle (skip on walk). */
        final Set<RcsTransport> failed = new HashSet<>();
        /** Transports firmly eligible at the last evaluation; preemption needs a new edge. */
        final Set<RcsTransport> priorEligible = new HashSet<>();
        /** Candidate order at the last selection, walked on a terminal failure. */
        @Nullable List<RcsTransport> candidates;
        /**
         * Deadline after which a still-binding higher-priority provider stops blocking a commit.
         */
        long graceDeadlineMs;
        /** elapsedRealtime before which re-selection is suppressed (post-fallback cooldown). */
        long cooldownUntilMs;
        /** Selection has run for the subscription at least once in this process. */
        boolean evaluated;
        /**
         * {@code elapsedRealtime} of the last fallback advance, for debouncing a late failure from
         * a just-deselected transport (the sink carries no transport identity).
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

    /**
     * Whether selection may still give the subscription a transport: one is selected, a
     * higher-priority provider is still binding, or no selection has run yet in this process and
     * there is a transport to select. The send path waits on this, bounded; false routes SMS.
     */
    boolean mayStillSelect(final int subId, final long nowMs) {
        synchronized (mLock) {
            final SelectionState s = mSelection.get(subId);
            if (s != null && s.evaluated) {
                return s.selected != null || s.graceDeadlineMs > nowMs;
            }
            for (int i = 0; i < mSelection.size(); i++) {
                if (mSelection.valueAt(i).evaluated) return false;
            }
            return mInProcessTransport != null || mLegacyTransport != null
                    || !mExternal.isEmpty();
        }
    }

    List<Integer> knownSubIds() {
        synchronized (mLock) {
            final List<Integer> out = new ArrayList<>(mSelection.size());
            for (int i = 0; i < mSelection.size(); i++) {
                out.add(mSelection.keyAt(i));
            }
            return out;
        }
    }

    /** The committed transport for the subscription, or null (route SMS). */
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

    /** Every system provider service that resolves the bind action, sorted by package name. */
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
        // Package-name order: manifest priority is not surfaced uniformly on a service ResolveInfo.
        // TODO: if two providers ever coexist, decide the pre-bind ordering.
        Collections.sort(out, (a, b) -> a.getPackageName().compareTo(b.getPackageName()));
        return out;
    }

    /** The first provider package that resolves the bind action, or {@code null} if none does. */
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

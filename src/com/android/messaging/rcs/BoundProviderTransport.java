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
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProvider;
import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsContractLayout;
import org.lineageos.rcs.provider.RcsContractProbe;
import org.lineageos.rcs.provider.RcsOutgoingMessage;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsSendResult;
import org.lineageos.rcs.provider.RcsSubInfo;

import com.android.messaging.util.LogUtil;

/**
 * ONE bound external RCS provider (design §3, §4.3): the AIDL bridge to a single
 * discovered provider APK, keyed by its {@link ComponentName}. This is the
 * general per-provider form the {@link ProviderRegistry} manages -- N of these
 * coexist (the RCS provider + any OTT provider), each with its own bind, version
 * handshake, {@code attach} token, and capped-backoff reconnect.
 *
 * <p>It implements {@link RcsTransport} by forwarding the seam methods across
 * {@link IRcsProvider}. It reuses the exact bind lifecycle of the legacy
 * {@link ProviderTransport} singleton (getContractVersion &gt;= ours gate,
 * attach, {@code BACKOFF_MIN..MAX} reconnect) but is parameterised by component
 * rather than hardcoding one package.
 *
 * <p>Callback routing: inbound provider-&gt;app callbacks are delivered to the
 * {@link IRcsProviderCallback} sink supplied at construction. The shared sink is
 * the app's process-wide {@link RcsCallbackRouter} (design §6.5), injected into
 * the {@link ProviderRegistry} via {@link ProviderRegistry#setCallbackSink} and
 * passed here at construction; it marshals every callback onto a DataModel Action.
 * A null sink (tests / the pre-wiring window) means we bind + handshake but skip
 * {@code attach} (no inbound).
 *
 * <p>Threading mirrors {@link ProviderTransport}: binds are posted to the main
 * looper; the seam methods are invoked from DataModel action threads and are
 * safe to call while unbound (they no-op / return a not-registered result).
 */
public final class BoundProviderTransport implements RcsTransport {
    private static final String TAG = LogUtil.BUGLE_TAG;

    /** Contract version this main app understands (mirrors {@link ProviderTransport}). */
    public static final int CONTRACT_VERSION = ProviderTransport.CONTRACT_VERSION;

    private static final long BACKOFF_MIN_MS = 2_000L;
    private static final long BACKOFF_MAX_MS = 60_000L;

    private final Context mAppContext;
    private final ComponentName mComponent;
    @Nullable private final IRcsProviderCallback mCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Object mLock = new Object();
    @Nullable private IRcsProvider mProvider;
    @Nullable private String mClientToken;
    /** Static caps fetched at handshake time; cached so selection can read priority. */
    @Nullable private volatile RcsProviderCaps mStaticCaps;
    private boolean mBindRequested;
    private long mBackoffMs = BACKOFF_MIN_MS;

    /**
     * @param appContext application context
     * @param component  the resolved provider service component to bind
     * @param callback   the shared inbound callback sink (may be null until the
     *                   router is extracted -- see class TODO)
     */
    public BoundProviderTransport(final Context appContext, final ComponentName component,
            @Nullable final IRcsProviderCallback callback) {
        mAppContext = appContext.getApplicationContext();
        mComponent = component;
        mCallback = callback;
    }

    public ComponentName getComponent() {
        return mComponent;
    }

    /** Deterministic selection tiebreak key (design §5.3): the package name. */
    public String getPackageName() {
        return mComponent.getPackageName();
    }

    /** Kick the bind. Idempotent; posted so it never blocks the caller. */
    public void ensureBound() {
        mHandler.post(this::bindLocked);
    }

    /** Tear down the bind (registry uninstall/replace path). */
    public void shutdown() {
        safeUnbind();
    }

    private void bindLocked() {
        synchronized (mLock) {
            if (mProvider != null || mBindRequested) {
                return;
            }
            mBindRequested = true;
        }
        final Intent intent = new Intent(RcsConstants.ACTION_BIND_RCS_PROVIDER)
                .setComponent(mComponent);
        boolean ok;
        try {
            ok = mAppContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        } catch (final SecurityException e) {
            LogUtil.w(TAG, "BoundProviderTransport: bind denied for " + mComponent, e);
            ok = false;
        }
        if (!ok) {
            LogUtil.i(TAG, "BoundProviderTransport: not bindable: " + mComponent);
            synchronized (mLock) {
                mBindRequested = false;
            }
            scheduleReconnect();
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder service) {
            final IRcsProvider provider = IRcsProvider.Stub.asInterface(service);
            String token = null;
            RcsProviderCaps caps = null;
            try {
                // Ordinal 1, the one transaction a mid-interface insertion cannot renumber.
                final int v = provider.getContractVersion();

                // THE CONTRACT-SKEW GUARD. This path carried the same false premise as
                // ProviderTransport's — "accept any provider whose contract is >= ours" is only
                // sound for an APPENDED method, and v59/v60 both inserted mid-interface, which
                // renumbers every transaction after the insertion point and mis-dispatches
                // silently. Verify the DERIVED layout, and fail CLOSED when it cannot be verified.
                final RcsContractLayout.Verdict contract =
                        RcsContractProbe.check(provider.asBinder(), CONTRACT_VERSION, v);
                if (!contract.compatible) {
                    LogUtil.e(TAG, "BoundProviderTransport: REFUSING " + mComponent
                            + " — layout=" + RcsContractProbe.localDigest() + " — "
                            + contract.reason);
                    safeUnbind();
                    return;
                }
                LogUtil.i(TAG, "BoundProviderTransport: contract OK (" + mComponent + ") — layout="
                        + RcsContractProbe.localDigest() + " — " + contract.reason);
                try {
                    caps = provider.getProviderCaps();
                } catch (final RemoteException ignore) {
                    // Caps are advisory for selection; proceed without them.
                }
                if (mCallback != null) {
                    token = provider.attach(mCallback, CONTRACT_VERSION);
                } else {
                    // No sink injected (tests / pre-wiring): skip attach so we don't
                    // hold a session with a sink that can't receive inbound;
                    // selection can still read caps and canServeSub. In normal app
                    // startup the shared RcsCallbackRouter is injected via
                    // ProviderRegistry.setCallbackSink before discover() runs.
                    LogUtil.w(TAG, "BoundProviderTransport: no callback sink; "
                            + "skipping attach for " + mComponent);
                }
            } catch (final RemoteException e) {
                LogUtil.w(TAG, "BoundProviderTransport: handshake failed for " + mComponent, e);
            }
            mStaticCaps = caps;
            if (mCallback != null && TextUtils.isEmpty(token)) {
                LogUtil.w(TAG, "BoundProviderTransport: attach returned no token: " + mComponent);
                safeUnbind();
                scheduleReconnect();
                return;
            }
            synchronized (mLock) {
                mProvider = provider;
                mClientToken = token;
                mBindRequested = false;
                mBackoffMs = BACKOFF_MIN_MS;
            }
            LogUtil.i(TAG, "BoundProviderTransport: attached " + mComponent + " token=" + token);
            // This provider's caps (hence its declared priority) are now known:
            // nudge selection so a pending higher-priority provider that was blocking
            // a commit resolves, or a strictly-higher-priority provider preempts
            // (design §5.4). Handshake nudge -> not a fresh cycle (keeps the failed set).
            final ProviderRegistry registry = ProviderRegistry.peek();
            if (registry != null) {
                registry.reselect("bound-provider-handshake", /* freshCycle= */ false);
            }
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            LogUtil.w(TAG, "BoundProviderTransport: disconnected " + mComponent);
            synchronized (mLock) {
                mProvider = null;
                mClientToken = null;
                mBindRequested = false;
            }
            scheduleReconnect();
        }
    };

    /** Capped-backoff reconnect (mirrors {@link ProviderTransport}). */
    private void scheduleReconnect() {
        final long delay;
        synchronized (mLock) {
            delay = mBackoffMs;
            mBackoffMs = Math.min(mBackoffMs * 2, BACKOFF_MAX_MS);
        }
        mHandler.postDelayed(this::bindLocked, delay);
    }

    private void safeUnbind() {
        try {
            mAppContext.unbindService(mConnection);
        } catch (final IllegalArgumentException ignored) {
            // Not bound.
        }
        synchronized (mLock) {
            mProvider = null;
            mClientToken = null;
            mBindRequested = false;
        }
    }

    // ---- RcsTransport ----

    @Override
    public boolean isAttached() {
        synchronized (mLock) {
            return mProvider != null && mClientToken != null;
        }
    }

    @Override
    @Nullable
    public RcsProviderCaps getProviderCaps() {
        final IRcsProvider provider;
        synchronized (mLock) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                final RcsProviderCaps caps = provider.getProviderCaps();
                if (caps != null) {
                    mStaticCaps = caps;
                }
                return caps;
            } catch (final RemoteException e) {
                LogUtil.w(TAG, "BoundProviderTransport.getProviderCaps failed", e);
            }
        }
        // Fall back to the handshake-time snapshot so selection can rank an
        // unbound-but-known provider by priority.
        return mStaticCaps;
    }

    @Override
    public int canServeSub(final RcsSubInfo sub) {
        if (sub == null) {
            return IRcsProvider.LINE_UNKNOWN;
        }
        final IRcsProvider provider;
        synchronized (mLock) {
            provider = mProvider;
        }
        if (provider == null) {
            return IRcsProvider.LINE_UNKNOWN;
        }
        try {
            return provider.canServeSub(sub);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "BoundProviderTransport.canServeSub failed", e);
            return IRcsProvider.LINE_UNKNOWN;
        }
    }

    @Override
    public void startForSub(final RcsSubInfo sub) {
        if (sub == null) {
            return;
        }
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            ensureBound();
            return;
        }
        try {
            provider.startForSub(token, sub);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "BoundProviderTransport.startForSub failed", e);
        }
    }

    @Override
    public void stopForSub(final int subId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return;
        }
        try {
            provider.stopForSub(token, subId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "BoundProviderTransport.stopForSub failed", e);
        }
    }

    @Override
    @Nullable
    public RcsProviderCaps getCapabilitiesForSub(final int subId) {
        final IRcsProvider provider;
        synchronized (mLock) {
            provider = mProvider;
        }
        if (provider == null) {
            return null;
        }
        try {
            return provider.getCapabilitiesForSub(subId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "BoundProviderTransport.getCapabilitiesForSub failed", e);
            return null;
        }
    }

    @Override
    public int lookupRcsCapability(final int subId, final String phoneE164) {
        if (TextUtils.isEmpty(phoneE164)) {
            return IRcsProvider.CAP_UNKNOWN;
        }
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return IRcsProvider.CAP_UNKNOWN;
        }
        try {
            return provider.lookupRcsCapability(token, subId, phoneE164);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "BoundProviderTransport.lookupRcsCapability failed", e);
            return IRcsProvider.CAP_UNKNOWN;
        }
    }

    @Override
    public RcsSendResult sendMessage(final RcsOutgoingMessage msg) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return new RcsSendResult(false, RcsSendResult.REASON_NOT_REGISTERED,
                    "provider not bound");
        }
        try {
            final RcsSendResult r = provider.sendMessage(token, msg);
            return r != null ? r
                    : new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR, "null result");
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "BoundProviderTransport.sendMessage failed", e);
            return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public void sendImdn(final String originalMessageId, final String toUri, final int imdnType) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return;
        }
        try {
            provider.sendImdn(token, originalMessageId, toUri, imdnType);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "BoundProviderTransport.sendImdn failed", e);
        }
    }

    @Override
    public void sendTyping(final String toUri, final boolean active) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return;
        }
        try {
            provider.sendTyping(token, toUri, active);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "BoundProviderTransport.sendTyping failed", e);
        }
    }

    @Override
    public void submitOtp(final int subId, final String otp) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return;
        }
        try {
            provider.submitOtp(token, subId, otp);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "BoundProviderTransport.submitOtp failed", e);
        }
    }

    @Override
    public void rejectIncomingFile(final int subId, final String messageId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return;
        }
        try {
            provider.rejectIncomingFile(token, subId, messageId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "BoundProviderTransport.rejectIncomingFile failed", e);
        }
    }
}

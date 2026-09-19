/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * One bound external provider, keyed by its {@link ComponentName}: the same bind, contract check,
 * {@code attach} and capped-backoff reconnect as {@link ProviderTransport}, parameterised by
 * component. Implements only the {@link RcsTransport} seam.
 *
 * <p>Inbound callbacks go to the sink given at construction, normally the process-wide
 * {@link RcsCallbackRouter}; with a null sink it binds and reads caps but does not attach. Binds
 * are posted to the main looper; seam methods are safe to call while unbound. See
 * docs/rcs/architecture.md.
 */
public final class BoundProviderTransport
        implements RcsTransport, RcsInboundConfirmation.Upstream {
    private static final String TAG = LogUtil.BUGLE_TAG;

    /** Contract version this app speaks (mirrors {@link ProviderTransport}). */
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
    /** The bound provider's uid, which confirmations are sent back to; -1 while unbound. */
    private volatile int mProviderUid = -1;
    /** Static caps fetched at handshake time; cached so selection can read priority. */
    @Nullable private volatile RcsProviderCaps mStaticCaps;
    private boolean mBindRequested;
    private long mBackoffMs = BACKOFF_MIN_MS;

    /**
     * @param component the resolved provider service component to bind
     * @param callback the shared inbound callback sink; null skips {@code attach}
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

    /** Deterministic selection tie-break key: the package name. */
    public String getPackageName() {
        return mComponent.getPackageName();
    }

    /** Kick the bind. Idempotent; posted so it never blocks the caller. */
    public void ensureBound() {
        mHandler.post(this::bindLocked);
    }

    /** Tear down the bind (provider removed or replaced). */
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
            mProviderUid = RcsInboundConfirmation.uidOf(mAppContext, name.getPackageName());
            String token = null;
            RcsProviderCaps caps = null;
            try {
                // Ordinal 1, the one transaction a mid-interface insertion cannot renumber.
                final int v = provider.getContractVersion();

                // "Contract >= ours" is sound only for appended methods; an insertion renumbers
                // every later transaction. Verify the derived layout and fail closed when it cannot
                // be verified. See docs/rcs/provider-contract.md.
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
                    // No sink: skip attach, so no session is held that cannot receive inbound.
                    // Selection can still read caps and canServeSub.
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
            // The token is the session capability every provider call presents.
            if (RcsDebug.isDebugBuild()) {
                LogUtil.i(TAG, "BoundProviderTransport: attached " + mComponent + " token="
                        + token);
            } else {
                LogUtil.i(TAG, "BoundProviderTransport: attached " + mComponent);
            }
            // Caps, and so priority, are now known: nudge selection so a pending or strictly
            // higher-priority provider resolves. Not a fresh cycle, so the failed set is kept.
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

    /** Capped-backoff reconnect (as {@link ProviderTransport}). */
    private void scheduleReconnect() {
        final long delay;
        synchronized (mLock) {
            delay = mBackoffMs;
            mBackoffMs = Math.min(mBackoffMs * 2, BACKOFF_MAX_MS);
        }
        mHandler.postDelayed(this::bindLocked, delay);
    }

    @Override
    public boolean deliversFromUid(final int uid) {
        return uid >= 0 && uid == mProviderUid;
    }

    /** Forwards {@code IRcsProvider.ackInboundMessages}; dropped while unbound (redelivered). */
    @Override
    public void ackInboundMessages(final int subId, final String... ids) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null || ids == null || ids.length == 0) return;
        try {
            provider.ackInboundMessages(token, subId, ids);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "BoundProviderTransport: ackInboundMessages failed for " + mComponent,
                    e);
        }
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
        // Fall back to the handshake snapshot so an unbound but known provider can still be ranked.
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

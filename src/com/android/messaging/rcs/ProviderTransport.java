/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProvider;
import org.lineageos.rcs.provider.RcsBotBrand;
import org.lineageos.rcs.provider.RcsContractLayout;
import org.lineageos.rcs.provider.RcsContractProbe;
import org.lineageos.rcs.provider.RcsGroupInfo;
import org.lineageos.rcs.provider.RcsOutgoingFile;
import org.lineageos.rcs.provider.RcsOutgoingMessage;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsSendResult;
import org.lineageos.rcs.provider.RcsSubInfo;

import com.android.messaging.datamodel.action.UpdateRcsReactionAction;
import com.android.messaging.util.LogUtil;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The binding to the RCS provider app: owns the {@link ServiceConnection} and implements
 * {@link RcsTransport}, plus the provider-only surface (groups, files, reactions, business
 * messaging), by forwarding to {@link IRcsProvider}. Singleton, created at startup.
 *
 * <p>Binds an explicit intent to the first package that resolves the bind action. On connect it
 * verifies the transaction layout with {@link RcsContractProbe}, then attaches the shared
 * {@link RcsCallbackRouter}. A disconnect or dead binding schedules a reconnect with capped
 * backoff; a send while unbound is refused so the caller falls back to SMS. See
 * docs/rcs/architecture.md and docs/rcs/provider-contract.md.
 */
public final class ProviderTransport implements RcsTransport {
    private static final String TAG = LogUtil.BUGLE_TAG;

    /**
     * The contract version this app implements, exchanged at bind time. A label for logs: whether a
     * pairing is safe is decided by {@link RcsContractLayout}, which compares the layouts derived
     * from each side's generated stub. Bump it when the transaction layout changes. New methods are
     * appended at the end of {@code IRcsProvider.aidl}; an insertion renumbers every later method.
     */
    public static final int CONTRACT_VERSION = 1;

    /** Bind action the provider's exported service advertises. */
    static final String BIND_ACTION = RcsConstants.ACTION_BIND_RCS_PROVIDER;

    private static final long BACKOFF_MIN_MS = 2_000L;
    private static final long BACKOFF_MAX_MS = 60_000L;

    private static volatile ProviderTransport sInstance;

    private final Context mAppContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /**
     * The shared inbound sink, passed to {@code attach}; typing, E2EE and routing accessors
     * delegate to it.
     */
    private final RcsCallbackRouter mRouter;

    private final Object mLock = new Object();
    @Nullable private IRcsProvider mProvider;
    @Nullable private String mClientToken;
    private boolean mBindRequested;
    /** When {@link #mBindRequested} was last set — the watchdog's clock. */
    private long mBindRequestedAtMs;
    private long mBackoffMs = BACKOFF_MIN_MS;

    // Typing sends may block in the provider; single-threaded so sends to one peer keep order.
    private final java.util.concurrent.ExecutorService mTypingExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // Subscriptions whose startForSub was requested, replayed after every attach.
    private final Deque<RcsSubInfo> mPendingStarts = new ArrayDeque<>();

    private ProviderTransport(final Context appContext) {
        mAppContext = appContext.getApplicationContext();
        mRouter = RcsCallbackRouter.getInstance(mAppContext);
    }

    public static ProviderTransport getInstance(final Context context) {
        if (sInstance == null) {
            synchronized (ProviderTransport.class) {
                if (sInstance == null) {
                    sInstance = new ProviderTransport(context);
                }
            }
        }
        return sInstance;
    }

    /** Cached accessor; only valid after getInstance() has run once. */
    @Nullable
    public static ProviderTransport peekInstance() {
        return sInstance;
    }

    /**
     * Bind if not already bound, so a provider holding inbound traffic can deliver it. Idempotent,
     * and lighter than {@link #init}.
     */
    public void wake() {
        mHandler.post(this::ensureBound);
    }

    /**
     * Logs that the provider is not bound, so the call was never made, and requests a rebind. A
     * caller must not report the resulting null as a network refusal. Narrower than {@link
     * #init()}: a failed call is no reason to re-run provisioning.
     *
     * @return {@code null}, typed to whatever the caller returns
     */
    private <T> T notBound(final String what) {
        LogUtil.w(TAG, "ProviderTransport." + what + ": NOT BOUND to the provider — the call was "
                + "never made. This is NOT a transport failure and NOT a server refusal; requesting "
                + "a rebind now. If a caller reports this as 'could not be sent', that report is "
                + "about the binder, not the network.");
        mHandler.post(this::ensureBound);
        return null;
    }

    /**
     * Start the bind, provisioning for the active subscription and the OTP observer; posted, so it
     * never blocks startup.
     */
    public void init() {
        mHandler.post(this::ensureBound);
        // Drive provisioning for the active subscription; startForSub queues until attach.
        mHandler.post(this::requestStartForActiveSub);
        // Watch the SMS inbox for the provisioning OTP: the carrier's port-addressed data SMS is
        // not reliably delivered to RcsOtpReceiver, and without the code the provisioning times out
        // and re-requests.
        mHandler.post(this::registerOtpSmsObserver);
    }

    private android.database.ContentObserver mOtpObserver;
    // Only SMS newer than this are considered: set to "now" at registration, advanced past every
    // scanned row.
    private volatile long mLastOtpScanDate = 0L;

    /** Register the SMS-inbox observer that submits the provisioning OTP. */
    private void registerOtpSmsObserver() {
        try {
            if (mOtpObserver != null) {
                return;
            }
            mLastOtpScanDate = System.currentTimeMillis();
            mOtpObserver = new android.database.ContentObserver(mHandler) {
                @Override public void onChange(final boolean selfChange) {
                    mHandler.post(ProviderTransport.this::scanInboxForPev3Otp);
                }
                @Override public void onChange(final boolean selfChange,
                        final android.net.Uri uri) {
                    mHandler.post(ProviderTransport.this::scanInboxForPev3Otp);
                }
            };
            mAppContext.getContentResolver().registerContentObserver(
                    android.provider.Telephony.Sms.CONTENT_URI, true, mOtpObserver);
            LogUtil.i(TAG, "ProviderTransport: Pev3 OTP sms-inbox observer registered");
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ProviderTransport: registerOtpSmsObserver failed", t);
        }
    }

    /**
     * Submits the newest provisioning OTP among inbox SMS newer than the last scan. De-duplicates
     * by row date, so a resent code is a new row and gets a fresh submit. submitOtp is a no-op when
     * no provisioning run is waiting for a code.
     */
    private void scanInboxForPev3Otp() {
        android.database.Cursor c = null;
        try {
            final long since = mLastOtpScanDate;
            c = mAppContext.getContentResolver().query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    new String[] { "address", "body", "date", "sub_id" },
                    "date > ?", new String[] { String.valueOf(since) },
                    "date DESC");
            if (c == null) {
                return;
            }
            String otp = null;
            int otpSubId = -1;
            long newest = since;
            while (c.moveToNext()) {
                final long date = c.getLong(2);
                if (date > newest) {
                    newest = date;
                }
                if (otp != null) {
                    continue;  // already found the newest OTP; keep advancing `newest`
                }
                final String match = Pev3OtpMatcher.matchBody(c.getString(1));
                if (match != null) {
                    otp = match;
                    otpSubId = c.getInt(3);
                }
            }
            mLastOtpScanDate = newest;
            if (otp == null) {
                return;
            }
            if (!android.telephony.SubscriptionManager.isValidSubscriptionId(otpSubId)) {
                otpSubId = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
            }
            LogUtil.i(TAG, "ProviderTransport: Pev3 OTP from sms inbox -> submitOtp (sub="
                    + otpSubId + ")");
            submitOtp(otpSubId, otp);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ProviderTransport: scanInboxForPev3Otp failed", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * Runs route selection for the active subscription, which starts whichever transport it
     * selects; this transport is not started directly. No-op before the router is wired.
     */
    private void requestStartForActiveSub() {
        try {
            mRouter.getRouteSelector().selectForActiveSub("provider-attach/init");
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ProviderTransport.requestStartForActiveSub failed", t);
        }
    }

    /**
     * How long a bind request may go unanswered before {@link #ensureBound} treats it as lost. A
     * request that is never answered would otherwise leave the app SMS-only with no error.
     */
    private static final long BIND_REQUEST_STALE_MS = 30_000L;

    private void ensureBound() {
        final boolean stale;
        synchronized (mLock) {
            if (mProvider != null) {
                return;
            }
            if (mBindRequested) {
                final long waited = android.os.SystemClock.elapsedRealtime() - mBindRequestedAtMs;
                if (waited < BIND_REQUEST_STALE_MS) {
                    return;                      // a request really is in flight; let it land
                }
                LogUtil.w(TAG, "ProviderTransport: a bind request has been outstanding for "
                        + waited + "ms with no onServiceConnected and no onBindingDied — treating "
                        + "it as lost and rebinding. While this persisted the app was SMS-only and "
                        + "the provider was holding inbound for a client that never attached.");
                stale = true;
            } else {
                stale = false;
            }
        }
        // Unbind the stale connection before binding again, or each retry leaks a binding. Outside
        // mLock: unbindService is a framework call.
        if (stale) {
            safeUnbind();
        }
        synchronized (mLock) {
            if (mProvider != null || mBindRequested) {
                return;                          // another thread got there first
            }
            mBindRequested = true;
            mBindRequestedAtMs = android.os.SystemClock.elapsedRealtime();
        }
        // Resolve the provider package by the bind action; this singleton binds one provider, and
        // ProviderRegistry handles any others.
        final String pkg = ProviderRegistry.resolveProviderPackage(mAppContext);
        if (pkg == null) {
            LogUtil.i(TAG, "ProviderTransport: no RCS provider resolves " + BIND_ACTION
                    + "; staying SMS-only");
            synchronized (mLock) {
                mBindRequested = false;
            }
            scheduleReconnect();
            return;
        }
        final Intent intent = new Intent(BIND_ACTION).setPackage(pkg);
        boolean ok;
        try {
            ok = mAppContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        } catch (final SecurityException e) {
            LogUtil.w(TAG, "ProviderTransport: bind denied (no provider / perm?)", e);
            ok = false;
        }
        if (!ok) {
            LogUtil.i(TAG, "ProviderTransport: provider not bindable; staying SMS-only");
            synchronized (mLock) {
                mBindRequested = false;
            }
            // One delayed retry covers a provider installed after boot.
            scheduleReconnect();
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder service) {
            final IRcsProvider provider = IRcsProvider.Stub.asInterface(service);
            String token = null;
            try {
                // getContractVersion is ordinal 1, the one transaction an insertion cannot
                // renumber, so it is safe before the layout is verified; nothing else here is.
                final int v = provider.getContractVersion();

                // Binder transaction codes are positional, so "contract >= ours" proves nothing: an
                // insertion renumbers every later method and a call can land on a different,
                // possibly destructive one. The layout derived from each side's stub is compared
                // instead, failing closed.
                final RcsContractLayout.Verdict contract =
                        RcsContractProbe.check(provider.asBinder(), CONTRACT_VERSION, v);
                if (!contract.compatible) {
                    LogUtil.e(TAG, "ProviderTransport: REFUSING the provider — layout="
                            + RcsContractProbe.localDigest() + " — " + contract.reason
                            + "; staying SMS-only");
                    safeUnbind();
                    return;
                }
                // The combined digest lets tools compare this pairing against the provider's log
                // without reproducing a failure.
                LogUtil.i(TAG, "ProviderTransport: contract OK — layout="
                        + RcsContractProbe.localDigest() + " — " + contract.reason);
                token = provider.attach(mRouter, CONTRACT_VERSION);
            } catch (final RemoteException e) {
                LogUtil.w(TAG, "ProviderTransport: attach failed", e);
            }
            if (TextUtils.isEmpty(token)) {
                LogUtil.w(TAG, "ProviderTransport: attach returned no token; SMS-only");
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
                LogUtil.i(TAG, "ProviderTransport: attached, token=" + token);
            } else {
                LogUtil.i(TAG, "ProviderTransport: attached");
            }
            replayPendingStarts();
            // Re-drive startForSub for the active subscription on every attach, so a provider
            // restart re-registers without user action. Idempotent on the provider side.
            mHandler.post(() -> requestStartForActiveSub());
            // Now that the provider is bound, fill in rosters of groups created while unbound.
            try {
                com.android.messaging.datamodel.action.RosterRefillAction.refillPendingRosters();
            } catch (final Throwable t) {
                LogUtil.w(TAG, "ProviderTransport: roster-refill kickoff failed", t);
            }
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            LogUtil.w(TAG, "ProviderTransport: provider disconnected");
            synchronized (mLock) {
                mProvider = null;
                mClientToken = null;
                mBindRequested = false;
            }
            mRouter.onProviderGone();
            scheduleReconnect();
        }

        /**
         * The binding is gone and will not come back, which is what a provider update delivers.
         * Unlike onServiceDisconnected, the framework does not reconnect it; without the unbind
         * here the pending flag would stay set and every later bind attempt would return early.
         */
        @Override
        public void onBindingDied(final ComponentName name) {
            LogUtil.w(TAG, "ProviderTransport: binding DIED for " + name + " (provider replaced or "
                    + "removed) — unbinding and rebinding. The framework does not reconnect this "
                    + "one by itself.");
            safeUnbind();
            mRouter.onProviderGone();
            scheduleReconnect();
        }

        /** The provider returned null from {@code onBind}; release the pending bind and retry. */
        @Override
        public void onNullBinding(final ComponentName name) {
            LogUtil.w(TAG, "ProviderTransport: provider " + name + " returned a NULL binding — "
                    + "nothing will connect. Releasing the bind request and retrying with backoff.");
            safeUnbind();
            scheduleReconnect();
        }
    };

    private void replayPendingStarts() {
        final RcsSubInfo[] subs;
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
            subs = mPendingStarts.toArray(new RcsSubInfo[0]);
        }
        if (provider == null) {
            return;
        }
        for (final RcsSubInfo sub : subs) {
            try {
                provider.startForSub(token, sub);
            } catch (final RemoteException e) {
                LogUtil.w(TAG, "ProviderTransport: replay startForSub failed", e);
            }
        }
    }

    private void scheduleReconnect() {
        final long delay;
        synchronized (mLock) {
            delay = mBackoffMs;
            mBackoffMs = Math.min(mBackoffMs * 2, BACKOFF_MAX_MS);
        }
        mHandler.postDelayed(this::ensureBound, delay);
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
    public void startForSub(final RcsSubInfo sub) {
        if (sub == null) {
            return;
        }
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            // Remember it so we can replay after a reconnect.
            mPendingStarts.remove(sub);
            mPendingStarts.addLast(sub);
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            init();
            return;
        }
        try {
            provider.startForSub(token, sub);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.startForSub failed", e);
        }
    }

    @Override
    public void stopForSub(final int subId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            for (final RcsSubInfo s : mPendingStarts.toArray(new RcsSubInfo[0])) {
                if (s.subId == subId) {
                    mPendingStarts.remove(s);
                }
            }
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return;
        }
        try {
            provider.stopForSub(token, subId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.stopForSub failed", e);
        }
    }

    @Override
    @Nullable
    public RcsProviderCaps getCapabilitiesForSub(final int subId) {
        final IRcsProvider provider;
        synchronized (mLock) {
            provider = mProvider;
        }
        if (provider == null) return notBound("getCapabilitiesForSub");
        try {
            return provider.getCapabilitiesForSub(subId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.getCapabilitiesForSub failed", e);
            return null;
        }
    }

    @Override
    @Nullable
    public RcsProviderCaps getProviderCaps() {
        final IRcsProvider provider;
        synchronized (mLock) {
            provider = mProvider;
        }
        if (provider == null) return notBound("getProviderCaps");
        try {
            return provider.getProviderCaps();
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.getProviderCaps failed", e);
            return null;
        }
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
            LogUtil.w(TAG, "ProviderTransport.canServeSub failed", e);
            return IRcsProvider.LINE_UNKNOWN;
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
            LogUtil.w(TAG, "rejectIncomingFile: provider not bound");
            return;
        }
        try {
            provider.rejectIncomingFile(token, subId, messageId);
            LogUtil.i(TAG, "rejectIncomingFile dispatched rcsId=" + messageId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.rejectIncomingFile failed", e);
        }
    }

    @Override
    public int lookupRcsCapability(final int subId, final String phoneE164) {
        // Is this destination on RCS? Off the main thread; the provider call may hit the network.
        // Sticky answers are cached in RouteSelector; any failure is CAP_UNKNOWN, so the caller
        // tries RCS and falls back to SMS.
        if (TextUtils.isEmpty(phoneE164)) {
            return IRcsProvider.CAP_UNKNOWN;
        }
        final String key = normalizeForCache(phoneE164);

        // Cache hit. CAP_UNKNOWN is never cached, so a transient miss is retried.
        final RouteSelector selector = getRouteSelector();
        final int cached = selector.getCachedPeerCap(key);
        if (cached != IRcsProvider.CAP_UNKNOWN) {
            return cached;
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
            final int cap = provider.lookupRcsCapability(token, subId, key);
            if (cap == IRcsProvider.CAP_SMS_ONLY || cap == IRcsProvider.CAP_RCS) {
                selector.putPeerCap(key, cap);
            }
            return cap;
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.lookupRcsCapability failed", e);
            return IRcsProvider.CAP_UNKNOWN;
        }
    }

    /**
     * Record that a send was refused with {@code REASON_PEER_NOT_RCS}, under the same key as
     * {@link #lookupRcsCapability}, so the next send to that recipient skips RCS.
     */
    public void notePeerNotRcs(final String phoneE164) {
        if (TextUtils.isEmpty(phoneE164)) {
            return;
        }
        getRouteSelector().putPeerCap(normalizeForCache(phoneE164), IRcsProvider.CAP_SMS_ONLY);
    }

    /** Canonicalise to an E.164 cache key; falls back to the trimmed input. */
    private String normalizeForCache(final String phone) {
        try {
            final String e164 = com.android.messaging.util.PhoneUtils.getDefault()
                    .getCanonicalBySimLocale(phone);
            if (!TextUtils.isEmpty(e164)) {
                return e164;
            }
        } catch (final Throwable t) {
            // Fall through to the raw value below.
        }
        return phone.trim();
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
            LogUtil.w(TAG, "ProviderTransport.sendMessage failed", e);
            return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR, e.getMessage());
        }
    }

    // ---- Group surface ---- Blocking binder calls, off the main thread only. Each returns null or
    // false when unbound so the caller can fall back to MMS. groupId is the opaque 32-char
    // lowercase-hex group id.

    /**
     * Create a group. {@code desiredGroupId} is the client-minted 32-char lowercase-hex id, which
     * the server echoes in the returned {@code RcsGroupInfo.groupId}. Null if unbound or on
     * failure.
     */
    @Nullable
    public RcsGroupInfo createGroup(final int subId, final String desiredGroupId,
            final String groupName, final java.util.List<String> memberE164s,
            final int groupType) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("createGroup");
        try {
            final RcsGroupInfo created = provider.createGroup(token, subId, desiredGroupId,
                    groupName, memberE164s, groupType);
            // Log how this id was obtained, so a later refused mutation on it can be traced to its
            // origin.
            if (created != null) {
                LogUtil.i(TAG, "GROUP-PROVENANCE: CREATED id=" + created.groupId
                        + " via Group/CreateGroup (desiredGroupId="
                        + (desiredGroupId == null ? "null → server-issued" : desiredGroupId)
                        + ") — this id WAS issued by the Group service, so a later mutation refused "
                        + "on it is not a provenance problem.");
            }
            return created;
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.createGroup failed", e);
            return null;
        }
    }

    /**
     * The groups the service lists us as a member of, as distinct from {@link #getGroupInfo}'s
     * per-id read. Null on failure.
     */
    @Nullable
    public java.util.List<String> getGroupIds(final int subId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("getGroupIds");
        try {
            final String packed = provider.getGroupIds(token, subId);
            if (packed == null) return null;
            final java.util.List<String> out = new java.util.ArrayList<>();
            for (final String line : packed.split("\n")) {
                final String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.getGroupIds failed", e);
            return null;
        }
    }

    /** Fetch the current RcsGroupInfo for a group, or null. Blocking; off-main. */
    @Nullable
    public RcsGroupInfo getGroupInfo(final int subId, final String groupId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("getGroupInfo");
        try {
            return provider.getGroupInfo(token, subId, groupId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.getGroupInfo failed", e);
            return null;
        }
    }

    /**
     * A business-messaging agent's brand for the conversation header, fetched and cached by the
     * provider. The image URLs are public, so the UI fetches the images. Null if unbound or on
     * failure. Off the main thread.
     */
    @Nullable
    public RcsBotBrand getBotBrand(final int subId, final String botId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("getBotBrand");
        try {
            return provider.getBotBrand(token, subId, botId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.getBotBrand failed", e);
            return null;
        }
    }

    /**
     * Send a suggestion-chip postback ({@code botsuggestion.response}) to the bot. Returns the
     * synchronous accept or reject, null if unbound. Off the main thread.
     */
    @Nullable
    public RcsSendResult sendBotPostback(final int subId, final String botId,
            final String contentType, final String jsonBody, final String messageId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("sendBotPostback");
        try {
            return provider.sendBotPostback(token, subId, botId, contentType, jsonBody, messageId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendBotPostback failed", e);
            return null;
        }
    }

    /**
     * Turn business messaging on or off; the provider persists it and re-registers with or without
     * the chatbot capability. No-op if unbound. Off the main thread.
     */
    public void setChatbotEnabled(final boolean enabled) {
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
            provider.setChatbotEnabled(token, enabled);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.setChatbotEnabled failed", e);
        }
    }

    /**
     * E2EE state for the settings row: a fresh binder read, else the cached push, else null. Off
     * the main thread.
     */
    @Nullable
    public org.lineageos.rcs.provider.RcsE2eeInfo getE2eeInfo(final int subId) {
        final IRcsProvider provider;
        synchronized (mLock) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                final org.lineageos.rcs.provider.RcsE2eeInfo info = provider.getE2eeInfo(subId);
                mRouter.cacheE2eeInfo(info);
                return info;
            } catch (final RemoteException e) {
                LogUtil.w(TAG, "ProviderTransport.getE2eeInfo failed", e);
            }
        }
        return mRouter.peekE2eeInfo();
    }

    /** Last cached E2EE state, or null; safe on the main thread. */
    @Nullable
    public org.lineageos.rcs.provider.RcsE2eeInfo peekE2eeInfo() {
        return mRouter.peekE2eeInfo();
    }

    /**
     * Set the E2EE toggle; the provider persists it and pushes onE2eeStateChanged. Off the main
     * thread.
     */
    public void setE2eeEnabled(final int subId, final boolean enabled) {
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
            provider.setE2eeEnabled(token, subId, enabled);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.setE2eeEnabled failed", e);
        }
    }

    /**
     * Send a text to a group. Returns a synchronous accept or reject; any status callback
     * correlates by {@code clientMessageId}. Off the main thread.
     */
    public RcsSendResult sendGroupMessage(final int subId, final String groupId,
            final String body, final String clientMessageId) {
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
            final RcsSendResult r = provider.sendGroupMessage(token, subId, groupId, body,
                    clientMessageId);
            return r != null ? r
                    : new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR, "null result");
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendGroupMessage failed", e);
            return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Send a location share. {@code recipient} is the E.164 for a 1:1 or the group id when
     * {@code isGroup}; {@code accuracyMeters} &lt;= 0 omits the radius. Off the main thread.
     */
    public RcsSendResult sendLocation(final int subId, final String recipient,
            final boolean isGroup, final double lat, final double lon,
            final double accuracyMeters, final String label, final String clientMessageId) {
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
            final RcsSendResult r = provider.sendLocation(token, subId, recipient, isGroup,
                    lat, lon, accuracyMeters, label, clientMessageId);
            return r != null ? r
                    : new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR, "null result");
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendLocation failed", e);
            return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR, e.getMessage());
        }
    }

    /** Add members to a group. Off the main thread. */
    public boolean addGroupUsers(final int subId, final String groupId,
            final java.util.List<String> memberE164s) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return false;
        }
        try {
            return provider.addGroupUsers(token, subId, groupId, memberE164s);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.addGroupUsers failed", e);
            return false;
        }
    }

    /** Remove members from a group. Blocking; off-main. */
    public boolean removeGroupUsers(final int subId, final String groupId,
            final java.util.List<String> memberE164s) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return false;
        }
        try {
            return provider.removeGroupUsers(token, subId, groupId, memberE164s);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.removeGroupUsers failed", e);
            return false;
        }
    }

    /** Rename a group. Blocking; off-main. */
    public boolean renameGroup(final int subId, final String groupId, final String newName) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return false;
        }
        try {
            return provider.renameGroup(token, subId, groupId, newName);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.renameGroup failed", e);
            return false;
        }
    }

    // ---- File send ---- Opens the content URI to a descriptor and forwards an RcsOutgoingFile
    // (URI and descriptor, not inline bytes, because of the binder size limit); the provider
    // uploads and sends.

    /**
     * Send a file to a 1:1 peer. Off the main thread (it opens a content URI and makes a binder
     * call). Returns a synchronous accept or reject; the terminal status follows as
     * onMessageStatus.
     *
     * @param messageId client-minted UUID (correlates onMessageStatus)
     * @param toUri E.164 recipient
     * @param size file size in bytes if known, else -1
     * @param caption optional caption, may be null
     */
    public RcsSendResult sendFile(final int subId, final String messageId,
            final String toUri, final Uri contentUri, final String mimeType,
            @Nullable final String fileName, final long size,
            @Nullable final String caption) {
        return sendFile(subId, messageId, toUri, contentUri, mimeType, fileName,
                size, caption, /*groupId=*/ null);
    }

    /**
     * As above; with a non-null {@code groupId} the file goes to the group and {@code toUri} is
     * null.
     */
    public RcsSendResult sendFile(final int subId, final String messageId,
            @Nullable final String toUri, final Uri contentUri, final String mimeType,
            @Nullable final String fileName, final long size,
            @Nullable final String caption, @Nullable final String groupId) {
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
        if (contentUri == null) {
            return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR,
                    "null content URI");
        }
        ParcelFileDescriptor pfd;
        try {
            pfd = mAppContext.getContentResolver().openFileDescriptor(contentUri, "r");
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ProviderTransport.sendFile: open URI failed", t);
            return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR,
                    "open URI failed: " + t.getMessage());
        }
        if (pfd == null) {
            return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR,
                    "null descriptor");
        }
        try {
            final RcsOutgoingFile file = new RcsOutgoingFile(subId, messageId,
                    toUri, contentUri.toString(), pfd, mimeType, fileName, size,
                    caption, groupId);
            final RcsSendResult r = provider.sendFile(token, file);
            return r != null ? r
                    : new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR,
                            "null result");
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendFile failed", e);
            return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR,
                    e.getMessage());
        } finally {
            // The provider has its own copy of the descriptor; close ours.
            try {
                pfd.close();
            } catch (final Throwable ignored) {
            }
        }
    }

    @Override
    public void sendImdn(final String originalMessageId, final String toUri, final int imdnType) {
        sendImdn(originalMessageId, toUri, imdnType, /*rcsGroupId=*/ null);
    }

    @Override
    public void sendImdn(final String originalMessageId, final String toUri, final int imdnType,
            final String rcsGroupId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            LogUtil.w(TAG, "sendImdn(" + originalMessageId
                    + "): provider not bound — receipt dropped");
            return;
        }
        try {
            provider.sendImdn(token, originalMessageId, toUri, imdnType);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendImdn failed", e);
        }
    }

    /**
     * Accept an inbound file whose thumbnail arrived: the provider downloads it and calls
     * {@link RcsCallbackRouter#onIncomingMedia} again, which updates the row in place. No-op if
     * unbound. Off the main thread.
     */
    public void acceptIncomingFile(final int subId, final String rcsMessageId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            LogUtil.w(TAG, "acceptIncomingFile: provider not bound");
            return;
        }
        try {
            provider.acceptIncomingFile(token, subId, rcsMessageId);
            LogUtil.i(TAG, "acceptIncomingFile dispatched rcsId=" + rcsMessageId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.acceptIncomingFile failed", e);
        }
    }

    /**
     * Send a reaction add ({@code add=true}) or removal on a target message. The caller records our
     * own reaction with {@link UpdateRcsReactionAction#recordSelfReaction} so the chip appears at
     * once. {@code groupId} null sends to {@code toUri}; a group id sends to the group and
     * {@code toUri} is ignored. No-op if unbound. Off the main thread.
     */
    public void sendReaction(final int subId, final String targetMessageId,
            final String toUri, final String emoji, final boolean add,
            @Nullable final String groupId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            LogUtil.w(TAG, "sendReaction: provider not bound");
            return;
        }
        try {
            final RcsSendResult result = provider.sendReaction(
                    token, subId, targetMessageId, toUri, emoji, add, groupId);
            if (result != null && !result.accepted) {
                LogUtil.w(TAG, "sendReaction rejected target=" + targetMessageId
                        + " reason=" + result.reason);
            }
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendReaction failed", e);
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
            LogUtil.w(TAG, "ProviderTransport.sendTyping failed", e);
        }
    }

    // ---- Typing ----

    /**
     * Outbound typing indicator for the compose UI. Safe on the main thread: the binder call runs
     * on a worker, and it is a silent no-op when unbound.
     *
     * @param subId the conversation's subscription (advisory; the provider routes by client)
     * @param toUri the peer's MSISDN or tel URI
     * @param active true on first keystroke or refresh, false on idle or send
     */
    public void sendTyping(final int subId, final String toUri, final boolean active) {
        if (TextUtils.isEmpty(toUri)) {
            return;
        }
        // Fast unbound check so we don't spin up work for nothing.
        synchronized (mLock) {
            if (mProvider == null) {
                return;
            }
        }
        mTypingExecutor.execute(() -> sendTyping(toUri, active));
    }

    /**
     * Outbound group typing indicator, as {@link #sendTyping(int, String, boolean)} but addressed
     * to a group id; the server fans it out.
     *
     * @param subId the conversation's subscription (advisory)
     * @param groupId the 32-hex group id
     * @param active true on first keystroke or refresh, false on idle or send
     */
    public void sendGroupTyping(final int subId, final String groupId,
            final boolean active) {
        if (TextUtils.isEmpty(groupId)) {
            return;
        }
        synchronized (mLock) {
            if (mProvider == null) {
                return;
            }
        }
        mTypingExecutor.execute(() -> sendGroupTypingInternal(groupId, active));
    }

    private void sendGroupTypingInternal(final String groupId, final boolean active) {
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
            provider.sendGroupTyping(token, groupId, active);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendGroupTyping failed", e);
        }
    }

    /** Inbound typing sink for the conversation UI. Callbacks arrive on the main thread. */
    public interface TypingListener {
        /**
         * @param subId  the receiving sub
         * @param fromUri the peer that is (or stopped) typing
         * @param active true = started/continuing, false = stopped/idle
         */
        void onTyping(int subId, String fromUri, boolean active);
    }

    /** Register a typing listener. Idempotent. */
    public void registerTypingListener(final TypingListener listener) {
        mRouter.registerTypingListener(listener);
    }

    /** Unregister a typing listener. Safe even if never registered. */
    public void unregisterTypingListener(final TypingListener listener) {
        mRouter.unregisterTypingListener(listener);
    }

    // ---- Group typing ----

    /**
     * Inbound group typing sink; the UI keeps one indicator per sender. Callbacks arrive on the
     * main thread.
     */
    public interface GroupTypingListener {
        /**
         * @param subId the receiving sub
         * @param groupId the 32-hex group id the indicator was sent to
         * @param fromUri the member that is (or stopped) typing
         * @param active true = started/continuing, false = stopped/idle
         */
        void onGroupTyping(int subId, String groupId, String fromUri, boolean active);
    }

    /** Register a group-typing listener. Idempotent. */
    public void registerGroupTypingListener(final GroupTypingListener listener) {
        mRouter.registerGroupTypingListener(listener);
    }

    /** Unregister a group-typing listener. Safe even if never registered. */
    public void unregisterGroupTypingListener(final GroupTypingListener listener) {
        mRouter.unregisterGroupTypingListener(listener);
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
            LogUtil.w(TAG, "ProviderTransport.submitOtp dropped: provider not bound");
            return;
        }
        try {
            provider.submitOtp(token, subId, otp);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.submitOtp failed", e);
        }
    }

    /**
     * Return the user's ToS decision after {@code onCarrierTosStateChanged(TOS_REQUIRED)}:
     * accepting lets the provider use the configuration it already holds; declining, or withdrawing
     * later, leaves the line SMS-only. Safe on the main thread; a no-op when unbound.
     */
    public void submitTosConsent(final int subId, final boolean accept) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            LogUtil.w(TAG, "ProviderTransport.submitTosConsent dropped: provider not bound");
            return;
        }
        try {
            provider.submitTosConsent(token, subId, accept);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.submitTosConsent failed", e);
        }
    }

    /** The shared route selector, owned by the router. */
    public RouteSelector getRouteSelector() {
        return mRouter.getRouteSelector();
    }

}

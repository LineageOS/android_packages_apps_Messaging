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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProvider;
import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsMlsClaimResult;
import org.lineageos.rcs.provider.RcsMlsControlResult;
import org.lineageos.rcs.provider.RcsMlsIdentity;
import org.lineageos.rcs.provider.RcsMlsTransportProfile;
import org.lineageos.rcs.provider.RcsMlsPeerCaps;
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
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes;

/**
 * The binding to the RCS provider app: owns the {@link ServiceConnection} and implements
 * {@link RcsTransport}, plus the provider-only surface (groups, files, reactions, business
 * messaging, MLS), by forwarding to {@link IRcsProvider}. Singleton, created at startup.
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
    public static final int CONTRACT_VERSION = 2;

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

    /**
     * Confirm that inbound messages have been applied, so the provider stops redelivering them.
     * Call only once the work is done, never on entry to a handler. Idempotent. See
     * docs/rcs/provider-contract.md.
     */
    public void ackInboundMessages(final int subId, final String... messageIds) {
        if (messageIds == null || messageIds.length == 0) return;
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            // Not a loss: the provider still holds the message and it will be redelivered.
            LogUtil.w(TAG, "ackInboundMessages: provider not bound — " + messageIds.length
                    + " confirmation(s) deferred to redelivery");
            return;
        }
        try {
            provider.ackInboundMessages(token, subId, messageIds);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.ackInboundMessages failed", e);
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
     * Claim a peer's KeyPackages and report what happened. Never null, so "the peer published
     * nothing" can be told from "the key server refused us". An unbound provider is {@link
     * RcsMlsClaimResult#OUTCOME_NOT_ATTEMPTED} and a binder failure {@link
     * RcsMlsClaimResult#OUTCOME_TRANSPORT_FAILED}; neither is a statement about the peer. There is
     * no fallback to the older call: that would return bytes with a guessed outcome. Off the main
     * thread.
     */
    @NonNull
    public RcsMlsClaimResult claimPeerKeyPackagesWithOutcome(final int subId,
            final String phoneE164) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return new RcsMlsClaimResult(RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED, null,
                    "the RCS provider is not bound");
        }
        try {
            final RcsMlsClaimResult r =
                    provider.claimPeerKeyPackagesWithOutcome(token, subId, phoneE164);
            if (r != null) return r;
            // A null across the binder is a provider bug; NOT_ATTEMPTED makes no claim about the
            // peer.
            LogUtil.w(TAG,
                    "ProviderTransport: claimPeerKeyPackagesWithOutcome returned null, which "
                    + "the contract forbids — reporting NOT_ATTEMPTED rather than inventing an "
                    + "outcome for the peer.");
            return new RcsMlsClaimResult(RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED, null,
                    "the provider returned null, which contract v60 forbids");
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.claimPeerKeyPackagesWithOutcome failed", e);
            return new RcsMlsClaimResult(RcsMlsClaimResult.OUTCOME_TRANSPORT_FAILED, null,
                    "the binder call failed: " + e);
        }
    }

    /**
     * Drop the provider's peer-to-group record for a 1:1.
     *
     * @return true if the provider took the call; false means this recovery is unavailable, and
     *     clearing the engine half alone re-enters the era advance that made it necessary
     */
    public boolean mlsForgetConversation(final int subId, final String phoneE164) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return false;
        try {
            provider.mlsForgetConversation(token, subId, phoneE164);
            return true;
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.mlsForgetConversation failed", e);
        }
        return false;
    }

    /**
     * Drop the provider's record for a group. Returns the provider's own answer (whether a record
     * was removed): a rebuild over a half-cleared group would reuse the group id through an era
     * advance.
     */
    public boolean mlsForgetGroupConversation(final int subId, final String rcsGroupId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return false;
        try {
            return provider.mlsForgetGroupConversation(token, subId, rcsGroupId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.mlsForgetGroupConversation failed", e);
        }
        return false;
    }

    /**
     * Claim all of a peer's KeyPackages, one per device. Off the main thread. Cannot say why an
     * empty answer is empty; use {@link #claimPeerKeyPackages(int, String, int[])} where the result
     * is reported to a person.
     */
    @Nullable
    public java.util.List<byte[]> claimPeerKeyPackages(final int subId, final String phoneE164) {
        return claimPeerKeyPackages(subId, phoneE164, null);
    }

    /**
     * As above, also reporting the claim's outcome.
     *
     * <p>The sink is written only when an outcome is actually known and is never guessed; otherwise
     * it keeps the caller's initial value, so the caller chooses what "unknown" means. An
     * uninitialised sink reads {@link RcsMlsClaimResult#OUTCOME_SERVED}, which blames nobody; no
     * default reaches {@code OUTCOME_PEER_HAS_NONE}, the only value that blames the peer.
     *
     * @param outcomeSink one-element array receiving an {@code RcsMlsClaimResult.OUTCOME_*}, or
     *     null
     */
    @Nullable
    public java.util.List<byte[]> claimPeerKeyPackages(final int subId, final String phoneE164,
            @Nullable final int[] outcomeSink) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            // NOT a claim about the peer, and no dial was spent.
            reportOutcome(outcomeSink, RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED);
            return notBound("claimPeerKeyPackages");
        }
        // Fall back to an older call only when no request was made (NOT_ATTEMPTED). Any other
        // outcome means the key server was asked; asking again cannot improve the answer and
        // consumes a peer's small one-time pool.
        final RcsMlsClaimResult withOutcome = claimPeerKeyPackagesWithOutcome(subId, phoneE164);
        final java.util.List<byte[]> served = unpackClaimedPackages(withOutcome.keyPackages);
        if (served != null) {
            reportOutcome(outcomeSink, withOutcome.outcome);
            return served;
        }
        if (withOutcome.outcome != RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED) {
            // Asked and got nothing: report whose fault it was, without a second request.
            reportOutcome(outcomeSink, withOutcome.outcome);
            return null;
        }
        // NOT_ATTEMPTED: nothing is known yet, so the sink is left alone. The older plural call
        // is the last dial: an empty answer or a RemoteException may follow a real request.
        byte[] packed = null;
        try {
            packed = provider.claimPeerKeyPackages(token, subId, phoneE164);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.claimPeerKeyPackages failed", e);
        }
        final java.util.List<byte[]> unpacked = unpackClaimedPackages(packed);
        if (unpacked != null) {
            // SERVED is a fact here: we hold the packages.
            reportOutcome(outcomeSink, RcsMlsClaimResult.OUTCOME_SERVED);
            return unpacked;
        }
        // Asked, and this provider cannot say what happened: the sink stays untouched.
        return null;
    }

    /**
     * Write an outcome into a caller's sink, if it wanted one. There is no "unknown" value: an
     * untouched sink means no outcome was available.
     */
    private static void reportOutcome(@Nullable final int[] sink, final int outcome) {
        if (sink != null && sink.length > 0) {
            sink[0] = outcome;
        }
    }

    /**
     * Unpack the {@code [u32 BE length][bytes]} reply both claim shapes use, or null when it
     * carries nothing (never empty). Public so {@code MlsProviderTransport} reads the format
     * through the same parser.
     */
    @Nullable
    public static java.util.List<byte[]> unpackClaimedPackages(@Nullable final byte[] packed) {
        if (packed == null || packed.length == 0) return null;
        final java.util.List<byte[]> out = new java.util.ArrayList<>();
        int o = 0;
        while (o + 4 <= packed.length) {
            final int n = ((packed[o] & 0xff) << 24) | ((packed[o + 1] & 0xff) << 16)
                    | ((packed[o + 2] & 0xff) << 8) | (packed[o + 3] & 0xff);
            o += 4;
            if (n < 0 || o + n > packed.length) break;
            final byte[] kp = new byte[n];
            System.arraycopy(packed, o, kp, 0, n);
            o += n;
            out.add(kp);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Logs, rate-limited, that a call returned its default because the provider is not bound. The
     * defaults look like real answers (a {@code false} upload looks like a rejection), so the log
     * is what tells them apart.
     */
    private void noteUnbound(final String what) {
        final long now = android.os.SystemClock.elapsedRealtime();
        synchronized (mLock) {
            if (now - mLastUnboundLogMs < UNBOUND_LOG_INTERVAL_MS) return;
            mLastUnboundLogMs = now;
        }
        LogUtil.w(TAG, "ProviderTransport." + what + ": NOT BOUND to the provider — returning the "
                + "no-op default WITHOUT calling it. This is not a server verdict. Binding is async "
                + "after process start and after a provider reinstall; retry once "
                + "'client callback REGISTERED' appears.");
    }

    private long mLastUnboundLogMs = -UNBOUND_LOG_INTERVAL_MS;
    private static final long UNBOUND_LOG_INTERVAL_MS = 5_000L;

    /**
     * This transport's MLS characteristics. Never null: an unbound provider, a null answer or a
     * failed call yields the conservative default (nothing arbitrated, nothing awaited), so a
     * missing profile can never leave a send waiting for a convergence ACK that will not come.
     */
    @NonNull
    public RcsMlsTransportProfile getMlsTransportProfile(final int subId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            noteUnbound("getMlsTransportProfile");
            return RcsMlsTransportProfile.conservativeDefault();
        }
        try {
            final RcsMlsTransportProfile p = provider.getMlsTransportProfile(token, subId);
            return (p == null) ? RcsMlsTransportProfile.conservativeDefault() : p;
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.getMlsTransportProfile failed", e);
            return RcsMlsTransportProfile.conservativeDefault();
        }
    }

    /**
     * Create an MLS conversation from artifacts this app built; the provider wraps them without
     * parsing. Off the main thread.
     */
    @Nullable
    public RcsMlsControlResult createMlsConversation(final int subId, final String peerE164,
            final byte[] mlsGroupId, final byte[] welcome, final byte[] commit,
            final byte[] groupInfo, final byte[] epochAuth, final byte[] ratchetTree,
            final int era, final String contextId, final String rcsGroupId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("createMlsConversation");
        try {
            return provider.createMlsConversation(token, subId, peerE164, mlsGroupId, welcome,
                    commit, groupInfo, epochAuth, ratchetTree, era, contextId, rcsGroupId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.createMlsConversation failed", e);
            return null;
        }
    }

    /**
     * Send MLS ciphertext this app already sealed; the provider must not re-encrypt.
     * {@code messageId} must be the id bound into the AAD. Off the main thread.
     */
    @Nullable
    public RcsSendResult sendMlsCiphertext(final int subId, final String peerE164,
            final byte[] ciphertext, final String messageId, final int era,
            final byte[] epochAuth) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("sendMlsCiphertext");
        try {
            return provider.sendMlsCiphertext(token, subId, peerE164, ciphertext, messageId,
                    era, epochAuth);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendMlsCiphertext failed", e);
            return null;
        }
    }

    /** Group counterpart of {@link #sendMlsCiphertext}. Off the main thread. */
    @Nullable
    public RcsSendResult sendGroupMlsCiphertext(final int subId, final String rcsGroupId,
            final byte[] ciphertext, final String messageId, final int era,
            final byte[] epochAuth) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("sendGroupMlsCiphertext");
        try {
            return provider.sendGroupMlsCiphertext(token, subId, rcsGroupId, ciphertext, messageId,
                    era, epochAuth);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendGroupMlsCiphertext failed", e);
            return null;
        }
    }

    /**
     * The group id the provider already holds for a peer, or null. Lets the app adopt an existing
     * conversation instead of creating a duplicate, which the server refuses and which costs an
     * era.
     */
    @Nullable
    public byte[] getMlsGroupIdForPeer(final int subId, final String peerE164) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("getMlsGroupIdForPeer");
        try {
            return provider.getMlsGroupIdForPeer(token, subId, peerE164);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.getMlsGroupIdForPeer failed", e);
            return null;
        }
    }

    /** Apply an app-built commit to an existing conversation. Off the main thread. */
    @Nullable
    public RcsMlsControlResult applyMlsControl(final int subId, final String peerE164,
            final String controlMsgId, final byte[] groupInfo, final byte[] commit,
            final byte[] epochAuth, final byte[] ratchetTree, final byte[] baseEpochAuth,
            final String rcsGroupId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("applyMlsControl");
        try {
            return provider.applyMlsControl(token, subId, peerE164, controlMsgId, groupInfo,
                    commit, epochAuth, ratchetTree, baseEpochAuth, rcsGroupId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.applyMlsControl failed", e);
            return null;
        }
    }

    /** The server's current epoch authenticator, the authoritative state check. */
    @Nullable
    public byte[] fetchServerEpochAuthenticator(final int subId, final String peerE164,
            final String rcsGroupId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("fetchServerEpochAuthenticator");
        try {
            return provider.fetchServerEpochAuthenticator(token, subId, peerE164, rcsGroupId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.fetchServerEpochAuthenticator failed", e);
            return null;
        }
    }

    /** Fetch the commits we missed, for self-heal. Null = fetch failed. */
    @Nullable
    public byte[] fetchMissedCommits(final int subId, final String peerE164,
            final String rcsGroupId, final long era, final byte[] epochAuthenticator) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("fetchMissedCommits");
        try {
            return provider.fetchMissedCommits(token, subId, peerE164, rcsGroupId, era,
                    epochAuthenticator == null ? new byte[0] : epochAuthenticator);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.fetchMissedCommits failed", e);
            return null;
        }
    }

    /**
     * Set an encrypted group subject, its RCC.16 commitment and the key it commits to, in one
     * request: the server checks the commitment against the encrypted content and expects the key
     * delivery in the same request.
     *
     * @param privateMessages an MLS PrivateMessage at the post-commit epoch carrying the subject
     *     key
     */
    @Nullable
    public RcsMlsControlResult changeGroupSubjectMls(final int subId, final String rcsGroupId,
            final String contentType, final byte[] ciphertext, final byte[] groupInfo,
            final byte[] commit, final byte[] epochAuth, final byte[] ratchetTree,
            final byte[] baseEpochAuth, final byte[] privateMessages, final String controlMsgId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("changeGroupSubjectMls");
        try {
            return provider.changeGroupSubjectMls(token, subId, rcsGroupId, contentType, ciphertext,
                    groupInfo, commit, epochAuth, ratchetTree, baseEpochAuth, privateMessages,
                    controlMsgId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.changeGroupSubjectMls failed", e);
            return null;
        }
    }

    /**
     * The icon counterpart of {@link #changeGroupSubjectMls}. The provider also uploads
     * {@code ciphertext} and references the resulting URL, since an icon is carried by reference.
     * Blocks on an upload; off the main thread only.
     */
    @Nullable
    public RcsMlsControlResult changeGroupIconMls(final int subId, final String rcsGroupId,
            final String contentType, final byte[] ciphertext, final byte[] groupInfo,
            final byte[] commit, final byte[] epochAuth, final byte[] ratchetTree,
            final byte[] baseEpochAuth, final byte[] privateMessages, final String controlMsgId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("changeGroupIconMls");
        try {
            return provider.changeGroupIconMls(token, subId, rcsGroupId, contentType, ciphertext,
                    groupInfo, commit, epochAuth, ratchetTree, baseEpochAuth, privateMessages,
                    controlMsgId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.changeGroupIconMls failed", e);
            return null;
        }
    }

    /** The MLS enrolment identity, refreshed by the provider. Off the main thread. */
    @Nullable
    public org.lineageos.rcs.provider.RcsMlsIdentity exportMlsIdentity(final int subId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("exportMlsIdentity");
        try {
            return provider.exportMlsIdentity(token, subId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.exportMlsIdentity failed", e);
            return null;
        }
    }

    /**
     * The MLS trust anchors, fetched and signature-verified by the provider. The pointers are
     * passed in because the configuration document may have reached this app rather than the
     * provider; any argument may be null or 0 to use what the provider holds. Returns the roots as
     * {@code OpenMlsSession.joinLenPrefixed} bytes, or null, which means "keep what you have",
     * never "trust nothing". May hit the network; off the main thread.
     */
    @Nullable
    public byte[] getMlsTrustAnchors(final int subId, @Nullable final String uri,
            final long generation, @Nullable final String signerSpkiB64) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("getMlsTrustAnchors");
        try {
            return provider.getMlsTrustAnchors(token, subId, uri, generation, signerSpkiB64);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.getMlsTrustAnchors failed", e);
            return null;
        }
    }

    /** The server's [era, epoch] for a conversation. Off the main thread. */
    @Nullable
    public long[] getMlsServerEraEpoch(final int subId, final String peerE164,
            final String rcsGroupId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("getMlsServerEraEpoch");
        try {
            return provider.getMlsServerEraEpoch(token, subId, peerE164, rcsGroupId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.getMlsServerEraEpoch failed", e);
            return null;
        }
    }

    /**
     * Tell a peer its message failed on our side: an RCC.16 §7.7.2.2 negative-delivery IMDN. Call
     * only after self-heal has run and the message still cannot be decrypted (RCC.16 §10.2).
     *
     * @param failureReason one of {@code IRcsProviderCallback.MLS_FAIL_*}
     * @return true if the report was handed to the provider
     */
    public boolean sendMlsNegativeDeliveryImdn(final int subId, final String originalMessageId,
            final String toUri, final String rcsGroupId, final int failureReason, final long eraId,
            final String epochAuthB64) {
        return sendMlsNegativeDeliveryImdn(subId, originalMessageId, toUri, rcsGroupId,
                failureReason, eraId, epochAuthB64, /*derivedContentSigB64=*/ null);
    }

    /** As above, with the RCC.16 §7.6.3.2 negative-receipt signature. */
    public boolean sendMlsNegativeDeliveryImdn(final int subId, final String originalMessageId,
            final String toUri, final String rcsGroupId, final int failureReason, final long eraId,
            final String epochAuthB64, final String derivedContentSigB64) {
        return sendMlsNegativeDeliveryImdn(subId, originalMessageId, toUri, rcsGroupId,
                failureReason,
                eraId, epochAuthB64, derivedContentSigB64, /*receiptMessageId=*/ null);
    }

    /**
     * As above, with the receipt's own id, which must equal the id the derived content was signed
     * with and the envelope carries; peers check the two match (RCC.16 §7.5.3.1).
     */
    public boolean sendMlsNegativeDeliveryImdn(final int subId, final String originalMessageId,
            final String toUri, final String rcsGroupId, final int failureReason, final long eraId,
            final String epochAuthB64, final String derivedContentSigB64,
            final String receiptMessageId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return false;
        try {
            provider.sendMlsNegativeDeliveryImdn(token, subId, originalMessageId, toUri, rcsGroupId,
                    failureReason, eraId, epochAuthB64, derivedContentSigB64, receiptMessageId);
            return true;
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendMlsNegativeDeliveryImdn failed", e);
            return false;
        }
    }

    /** Publish KeyPackages this app generated. Off the main thread. */
    public boolean uploadKeyPackages(final int subId, final byte[] keyPackages,
            final byte[] lastResort) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            noteUnbound("uploadKeyPackages");
            return false;
        }
        try {
            return provider.uploadKeyPackages(token, subId, keyPackages, lastResort);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.uploadKeyPackages failed", e);
            return false;
        }
    }

    /** The server's view of a conversation's GroupInfo. Off the main thread. */
    @Nullable
    public RcsMlsControlResult getMlsGroupInfo(final int subId, final String phoneE164) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("getMlsGroupInfo");
        try {
            return provider.getMlsGroupInfo(token, subId, phoneE164);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.getMlsGroupInfo failed", e);
            return null;
        }
    }

    /**
     * The group-addressed GroupInfo fetch. Not interchangeable with {@link #getMlsGroupInfo}: a
     * resync external commit needs the {@code external_pub} extension, which the other fetch lacks.
     */
    public RcsMlsControlResult getMlsGroupInfoForGroup(final int subId, final String phoneE164,
            final String rcsGroupId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("getMlsGroupInfoForGroup");
        try {
            return provider.getMlsGroupInfoForGroup(token, subId, phoneE164, rcsGroupId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.getMlsGroupInfoForGroup failed", e);
            return null;
        }
    }



    /**
     * Whether the provider is provisioned to carry MLS for {@code subId}. Overrides the {@link
     * RcsTransport} default of false, without which a provider-backed send could never select MLS.
     * Non-blocking; answered from cached state.
     */
    @Override
    public boolean isMlsReady(final int subId) {
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
            return provider.isMlsReady(token, subId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.isMlsReady failed", e);
            return false;
        }
    }

    /**
     * The peer-capability lookup's outcome ({@code RcsMlsPeerCaps.LOOKUP_*}) rather than its tags.
     * Only {@code LOOKUP_NOT_REGISTERED} may be read as a negative; an empty tag map means nothing
     * is known.
     */
    public int lookupPeerMlsLookupState(final int subId, final String phoneE164) {
        if (TextUtils.isEmpty(phoneE164)) return RcsMlsPeerCaps.LOOKUP_UNKNOWN;
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return RcsMlsPeerCaps.LOOKUP_UNKNOWN;
        try {
            final RcsMlsPeerCaps caps =
                    provider.lookupPeerMlsCaps(token, subId, normalizeForCache(phoneE164));
            return (caps == null) ? RcsMlsPeerCaps.LOOKUP_UNKNOWN : caps.lookupState;
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.lookupPeerMlsLookupState failed", e);
            return RcsMlsPeerCaps.LOOKUP_UNKNOWN;
        }
    }

    /**
     * The peer's advertised MLS capability tags. Off the main thread; may hit the network. Empty on
     * any failure, and empty means nothing is known, not "not MLS-capable".
     */
    @NonNull
    public Map<String, String> lookupPeerMlsCaps(final int subId, final String phoneE164) {
        if (TextUtils.isEmpty(phoneE164)) {
            return Collections.emptyMap();
        }
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) {
            return Collections.emptyMap();
        }
        try {
            final RcsMlsPeerCaps caps =
                    provider.lookupPeerMlsCaps(token, subId, normalizeForCache(phoneE164));
            return (caps == null) ? Collections.<String, String>emptyMap() : caps.asMap();
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.lookupPeerMlsCaps failed", e);
            return Collections.emptyMap();
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

    /**
     * Add members to an MLS group with the Add commit in the same request. The plain
     * {@link #addGroupUsers} is refused on an MLS group. Off the main thread.
     */
    public RcsMlsControlResult addGroupUsersMls(final int subId, final String rcsGroupId,
            final java.util.List<String> memberE164s, final byte[] welcome, final byte[] commit,
            final byte[] groupInfo, final byte[] epochAuth, final byte[] ratchetTree,
            final byte[] baseEpochAuth, final String controlMsgId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("addGroupUsersMls");
        try {
            return provider.addGroupUsersMls(token, subId, rcsGroupId, memberE164s, welcome, commit,
                    groupInfo, epochAuth, ratchetTree, baseEpochAuth, controlMsgId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.addGroupUsersMls failed", e);
            return null;
        }
    }

    /**
     * Remove members from an MLS group with the Remove commit in the same request. No Welcome: a
     * removal produces none.
     */
    public RcsMlsControlResult removeGroupUsersMls(final int subId, final String rcsGroupId,
            final java.util.List<String> memberE164s, final byte[] commit, final byte[] groupInfo,
            final byte[] epochAuth, final byte[] prevEpochAuth, final String controlMsgId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("removeGroupUsersMls");
        try {
            return provider.removeGroupUsersMls(token, subId, rcsGroupId, memberE164s, commit,
                    groupInfo, epochAuth, prevEpochAuth, controlMsgId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.removeGroupUsersMls failed", e);
            return null;
        }
    }

    /**
     * Leave an MLS group with a by-reference SelfRemove proposal. The caller must roll back on a
     * non-OK verdict: a cached SelfRemove blocks every later send on the conversation, across
     * restarts.
     */
    public RcsMlsControlResult selfLeaveGroupMls(final int subId, final String rcsGroupId,
            final byte[] proposal, final byte[] prevEpochAuth, final String controlMsgId) {
        final IRcsProvider provider;
        final String token;
        synchronized (mLock) {
            provider = mProvider;
            token = mClientToken;
        }
        if (provider == null) return notBound("selfLeaveGroupMls");
        try {
            return provider.selfLeaveGroupMls(token, subId, rcsGroupId, proposal, prevEpochAuth,
                    controlMsgId);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.selfLeaveGroupMls failed", e);
            return null;
        }
    }

    /** Add members to a group. Blocking; off-main. */
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
        // Diagnostic: debug.rcs.mls_suppress_positive_imdn suppresses our positive receipts, so a
        // peer's reaction to a failure report can be observed without a prior "delivered" for the
        // same id. Read live. Off by default; leaving it on makes us look like a client that never
        // acknowledges anything.
        if (android.os.SystemProperties.getBoolean("debug.rcs.mls_suppress_positive_imdn", false)) {
            LogUtil.w(TAG, "sendImdn(" + originalMessageId + "): SUPPRESSED by "
                    + "debug.rcs.mls_suppress_positive_imdn — this is a DIAGNOSTIC and we are "
                    + "deliberately not telling " + LogMask.number(toUri)
                    + " that we received this message. "
                    + "Turn it off when the run is done.");
            return;
        }
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
        // On an MLS conversation the receipt must carry the RCC.16 header sidecar, including the
        // MLS-Derived-Content-Signature, or a peer drops it. This app owns the engine, so the era,
        // epoch authenticator and signature are minted here from one group state.
        try {
            final com.android.messaging.rcs.e2ee.MlsProviderTransport mls =
                    com.android.messaging.rcs.e2ee.MlsProviderTransport.peek();
            final MlsTransportTypes.ImdnStamps stamps =
                    (mls == null) ? null : mls.imdnStampsFor(toUri, rcsGroupId, originalMessageId,
                            imdnType == IRcsProviderCallback.IMDN_DISPLAYED);
            if (stamps != null) {
                LogUtil.i(TAG, "sendImdn(" + originalMessageId + "): MLS receipt to "
                        + LogMask.number(toUri)
                        + (rcsGroupId == null ? " (1:1)" : " in group " + rcsGroupId)
                        + " era=" + stamps.eraId + " sig="
                        + (stamps.signatureB64 == null ? "NONE" : "yes"));
                // A provider without the group form cannot send a group receipt correctly; the 1:1
                // form would bind it to the wrong group, so it is dropped instead. A 1:1 receipt
                // falls back.
                try {
                    provider.sendMlsGroupImdn(token, stamps.subId, toUri, rcsGroupId,
                            originalMessageId, imdnType, stamps.eraId, stamps.epochAuthB64,
                            stamps.signatureB64, stamps.receiptMessageId);
                } catch (final Throwable notV46) {
                    if (rcsGroupId != null && !rcsGroupId.isEmpty()) {
                        LogUtil.w(TAG, "sendImdn(" + originalMessageId + "): provider has no "
                                + "sendMlsGroupImdn and this receipt is for group " + rcsGroupId
                                + " — dropping rather than sending it bound to the 1:1", notV46);
                        return;
                    }
                    provider.sendMlsImdn(token, stamps.subId, toUri, originalMessageId, imdnType,
                            stamps.eraId, stamps.epochAuthB64, stamps.signatureB64,
                            stamps.receiptMessageId);
                }
                return;
            }
            LogUtil.w(TAG, "sendImdn(" + originalMessageId + "): no MLS stamps for "
                    + LogMask.number(toUri)
                    + " (mls=" + (mls == null ? "null" : "present")
                    + ") — falling back to the PLAIN receipt. Some peers do not "
                    + "drop this: their report "
                    + "dispatcher RECONCILES on a plaintext report for an MLS message, downgrading "
                    + "off a stale encryption_protocol belief. That is the ONLY recovery a group-less "
                    + "peer can drive, since it cannot sign an MLS FTD.");
        } catch (final Throwable t) {
            // Not an MLS conversation, or no stamps: send the ordinary receipt.
            LogUtil.w(TAG, "sendImdn: no MLS stamps for " + originalMessageId
                    + " — sending on the ordinary path", t);
        }
        try {
            provider.sendImdn(token, originalMessageId, toUri, imdnType);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendImdn failed", e);
        }
    }

    /**
     * Send a forced-plaintext delivery receipt to reconcile a peer that still believes the
     * conversation is on MLS. See {@link
     * org.lineageos.rcs.provider.IRcsProvider#sendReconciliationReceipt}.
     */
    public void sendReconciliationReceipt(final String originalMessageId, final String toUri) {
        final IRcsProvider provider = mProvider;
        final String token = mClientToken;
        if (provider == null) {
            LogUtil.w(TAG, "sendReconciliationReceipt(" + originalMessageId
                    + "): provider not bound — reconciliation dropped");
            return;
        }
        try {
            provider.sendReconciliationReceipt(token, originalMessageId, toUri);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendReconciliationReceipt failed", e);
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

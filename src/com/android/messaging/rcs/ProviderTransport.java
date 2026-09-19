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
 * AIDL client adapter: owns the {@link ServiceConnection} to the RCS
 * provider APK and implements {@link RcsTransport} by forwarding to
 * {@link IRcsProvider}. Singleton; created from {@code BugleApplication
 * .initializeSync} via {@link #getInstance(Context)}.
 *
 * <p>Wire contract (grounded in aidl/Android.bp + the renamed provider APK):
 * <ul>
 *   <li>both APKs link {@code messaging-rcs-contract-aidl};
 *   <li>we bind an <b>explicit</b> Intent (action +
 *       {@code setPackage(...)}) -- implicit binds to another app
 *       are illegal on API 30+.
 * </ul>
 *
 * <p>Contract negotiation: on connect we call {@link
 * IRcsProvider#getContractVersion()} and refuse (stay SMS-only) if it isn't
 * {@link #CONTRACT_VERSION}, then {@link IRcsProvider#attach} and stash the
 * returned clientToken. All inbound callbacks are marshalled onto DataModel
 * Actions (so DB writes happen off the binder thread, on the action-service
 * thread that the existing SMS path already uses).
 *
 * <p>Death handling: {@link ServiceConnection#onServiceDisconnected} clears
 * state and schedules a reconnect with capped backoff. Sends issued while
 * unbound return a non-accepted result so the caller falls back to SMS.
 */
public final class ProviderTransport implements RcsTransport {
    private static final String TAG = LogUtil.BUGLE_TAG;

    /**
     * The contract version this app implements, exchanged at bind time.
     *
     * <p><b>Numbering restarts at the first published release.</b> This interface had a long
     * unpublished history before it was opened up — the version reached 64 — but none of those
     * revisions was ever visible outside the project that grew them, and a changelog of
     * transaction layouts nobody can observe is not provenance, it is noise. So this is version 1
     * of the PUBLISHED contract, and the number a reader sees means what it says.
     *
     * <p><b>This number is a LABEL, not the compatibility check.</b> It tells a log line which
     * revision each side believes it has. What decides whether a pairing is SAFE is
     * {@link RcsContractLayout}'s comparison of the two DERIVED layouts, taken at runtime from
     * each side's own generated stub. That cannot go stale, because nobody maintains it by hand —
     * and it exists because this constant once sat at 24 while the interface had reached 60, so
     * the handshake was comparing a provider's 59 against 24 and passing whatever it saw.
     *
     * <p>Bump this when the transaction layout changes. Appending a method at the END of an
     * interface does not renumber anything before it, so an older peer keeps working for
     * everything it already knew and fails only the call it does not have. Inserting or
     * reordering renumbers every method after the change and silently re-points the peer at the
     * wrong one — which is why a new method goes at the END of {@code IRcsProvider.aidl}
     * and never in the middle of it.
     */
    public static final int CONTRACT_VERSION = 1;

    /** Bind action the provider's exported service advertises. */
    static final String BIND_ACTION = RcsConstants.ACTION_BIND_RCS_PROVIDER;

    private static final long BACKOFF_MIN_MS = 2_000L;
    private static final long BACKOFF_MAX_MS = 60_000L;

    private static volatile ProviderTransport sInstance;

    private final Context mAppContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** The process-wide inbound sink shared by every transport (design §6.5).
     *  Extracted from this class to a top-level {@link RcsCallbackRouter}; this
     *  singleton passes it to {@code IRcsProvider.attach} and delegates its
     *  typing / E2EE / route-selector accessors to it. */
    private final RcsCallbackRouter mRouter;

    private final Object mLock = new Object();
    @Nullable private IRcsProvider mProvider;
    @Nullable private String mClientToken;
    private boolean mBindRequested;
    /** When {@link #mBindRequested} was last set — the watchdog's clock. */
    private long mBindRequestedAtMs;
    private long mBackoffMs = BACKOFF_MIN_MS;

    // Off-main executor for outbound typing sends (the binder call may hit the
    // network in the provider). Single-threaded so sends to one peer keep order.
    private final java.util.concurrent.ExecutorService mTypingExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // Subs whose startForSub() was requested; replayed after a (re)attach so a
    // provider crash doesn't silently drop registration.
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

    /** External wake (RcsProviderWakeReceiver): ensure we're bound to the provider so
     *  a held inbound gets drained + delivered. Idempotent — {@link #ensureBound} is a
     *  no-op when already bound/requested. Lighter than {@link #init} (no OTP-observer /
     *  one-time setup), so it's safe to call repeatedly. */
    public void wake() {
        mHandler.post(this::ensureBound);
    }

    /**
     * Kick off the initial bind. Safe to call from initializeSync; the actual
     * bind is posted so we never block app startup.
     */
    /**
     * The provider binder is not attached: report it LOUDLY and ask for a rebind.
     *
     * <p>These call sites used to {@code return null} in silence, and that silence was expensive.
     * Callers treat a null as a TRANSPORT FAILURE and log
     * "could not be sent (RETRYABLE, retry later)" for it, so an app that was simply never bound
     * to the provider produced a log line claiming the network refused us. On 2026-08-19 that cost
     * a full test cycle: a request was built correctly (3443B), "failed to send"
     * three milliseconds later, and the provider process had not been called at all. Three
     * milliseconds is not an RPC, and nothing in the log said so.
     *
     * <p>The rebind request is the other half. An unbound transport does not fix itself just
     * because a caller wanted something, and every one of these methods is a caller that wanted
     * something — so each miss is exactly the right moment to re-attach. {@code ensureBound} is
     * idempotent and posts to the handler, so calling it from many sites costs one bind attempt,
     * not many. Deliberately narrower than {@link #init()}, which also drives provisioning and
     * registers the OTP observer: a failed RPC is not a reason to re-run provisioning.
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

    public void init() {
        mHandler.post(this::ensureBound);
        // Auto-drive provisioning for the active sub. startForSub queues
        // into mPendingStarts and replays after attach, so ordering vs the bind
        // doesn't matter.
        mHandler.post(this::requestStartForActiveSub);
        // Watch the sms inbox for the Pev3 OTP and forward it to the provider.
        // The carrier delivers it as a port-196608 DATA_SMS, which the
        // DATA_SMS_RECEIVED broadcast does NOT reliably hand to RcsOtpReceiver
        // (it lands straight in the inbox DB) — so observing the DB is the robust
        // path. Without this the cold provision times out waiting for the OTP and
        // re-requests, hammering the provider's ACS.
        mHandler.post(this::registerOtpSmsObserver);
    }

    private android.database.ContentObserver mOtpObserver;
    // Only OTP SMS newer than this are submitted (set to "now" at registration
    // so we don't replay stale codes; advanced past every row we scan).
    private volatile long mLastOtpScanDate = 0L;

    /** Register the sms-inbox observer that auto-submits the Pev3 OTP. */
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
     * Read inbox SMS newer than the last scan, and submit the newest Pev3 OTP to
     * the provider. submitOtp is a cheap no-op when no Pev3 SM is awaiting an OTP,
     * so this is safe to call on every sms change. De-dups by row date (each
     * server re-send is a new row, so re-sends still get a fresh submit attempt
     * — which is what hits a freshly-armed WAITING_FOR_OTP window).
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
     * Request RCS provisioning for the device's active (default-SMS, else
     * default-data) sub.
     *
     * <p>Multi-transport framework (design §5.4): this no longer starts <i>this</i>
     * (provider) transport directly -- the registry is now the live path. It kicks
     * {@link RouteSelector#selectForActiveSub}, which ranks all transports and drives
     * {@code startForSub} on the SELECTED one. On a provider-only device
     * selection deterministically lands on this transport, so the effect is identical
     * to the pre-registry direct start; on a device where the carrier-IMS transport is
     * preferred/only, the provider is (correctly) not started. The selector runs the boot /
     * app-start trigger; if it isn't wired yet (very early startup, before the registry
     * exists) this is a safe no-op -- the boot receiver + BugleApplication re-trigger.
     */
    private void requestStartForActiveSub() {
        try {
            mRouter.getRouteSelector().selectForActiveSub("provider-attach/init");
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ProviderTransport.requestStartForActiveSub failed", t);
        }
    }

    /**
     * How long a bind request may sit unanswered before {@link #ensureBound} stops believing it.
     *
     * <p>A bind that is requested and never answered leaves {@code mBindRequested} true and
     * {@code mProvider} null, and every later {@code ensureBound()} then returns at the guard — the
     * transport is permanently SMS-only with no error anywhere. Device-observed 2026-08-19:
     * the provider held 8 inbound dispatches and poked the app for each one, the app
     * logged "ensuring bind" every time, and it never attached. Only a force-stop recovered it.
     *
     * <p>{@link #onBindingDied} now covers the known trigger (a provider reinstall), but a latch
     * whose only exit is a callback firing is a latch that sticks the first time one does not. This
     * bound makes the stuck state self-correcting whatever the cause.
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
        // Release the connection we are apparently not going to get an answer for BEFORE asking for
        // another. Rebinding while the old ServiceConnection is still registered leaks one binding
        // per attempt, and the watchdog retries for as long as the condition lasts. unbindService is
        // a framework call, so it runs outside mLock.
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
        // De-hardcoded (design §4.1): resolve the provider package by action
        // enumeration rather than a compile-time package name. This singleton
        // still binds a SINGLE provider (the first/highest-priority resolved one);
        // the full N-provider fan-out lives in ProviderRegistry.
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
            // Do NOT retry aggressively when there is simply no provider; a
            // single delayed retry covers the install-after-boot case.
            scheduleReconnect();
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder service) {
            final IRcsProvider provider = IRcsProvider.Stub.asInterface(service);
            String token = null;
            try {
                // getContractVersion() is ordinal 1 — the first method aidl numbers, and so the
                // one transaction that cannot be renumbered by a mid-interface insertion. It is
                // safe to call before the layout is verified; nothing else here is.
                final int v = provider.getContractVersion();

                // ---- THE CONTRACT-SKEW GUARD ----
                // What used to stand here was a version compare with the comment "a provider whose
                // contract is >= ours is a superset (additive bumps)". Both halves were wrong.
                // Binder transaction codes are POSITIONAL, and v59/v60 INSERTED methods into the
                // middle of IRcsProvider rather than appending them, which renumbers everything
                // after the insertion point. Device-measured 2026-09-11: one of this app's
                // calls arrived at a DESTRUCTIVE method on the provider
                // in the same millisecond, and neither side said a word. It
                // did not delete a conversation record only because that method refuses an argument
                // it cannot parse as a group id.
                //
                // So the authoritative check is the LAYOUT, derived at runtime from each side's own
                // generated stub, and it fails CLOSED: a provider that cannot answer, or that
                // answers with a layout disagreeing at any ordinal we dial, is refused rather than
                // trusted. The version ints survive only as labels in the message.
                final RcsContractLayout.Verdict contract =
                        RcsContractProbe.check(provider.asBinder(), CONTRACT_VERSION, v);
                if (!contract.compatible) {
                    LogUtil.e(TAG, "ProviderTransport: REFUSING the provider — layout="
                            + RcsContractProbe.localDigest() + " — " + contract.reason
                            + "; staying SMS-only");
                    safeUnbind();
                    return;
                }
                // The combined digest (IRcsProvider/IRcsProviderCallback) is what
                // tools/check-contract-pairing.sh compares against the provider's onBind line, so
                // a pairing can be checked without first reproducing a failure.
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
            LogUtil.i(TAG, "ProviderTransport: attached, token=" + token);
            replayPendingStarts();
            // Robustness: ALWAYS re-drive startForSub for the active sub on every
            // (re)attach -- not only replay the (possibly empty/stale) pending set
            // -- so a provider restart (reinstall/crash) deterministically
            // re-registers without waiting for a user action. Idempotent
            // provider-side (start() is guarded by `running`); pairs with the
            // provider's bind-CONNECTED self-heal so a restart is send-ready fast.
            mHandler.post(() -> requestStartForActiveSub());
            // WAVE-A / A2.6: now that the provider is bound, self-heal any RCS
            // group conversations that were created from an inbound message while
            // we were unbound (seeded sender-only, flagged needs_roster_refill).
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
         * THE BINDING IS DEAD AND WILL NOT COME BACK — and this is what a provider REINSTALL
         * delivers.
         *
         * <p>These are different events and only one of them was handled. {@code
         * onServiceDisconnected} means the process died while the binding survives, and the
         * framework re-delivers {@code onServiceConnected} by itself. {@code onBindingDied} means
         * the binding itself is gone — the package was replaced or the component vanished — and
         * the framework will NEVER reconnect it. The client must unbind and bind again.
         *
         * <p>Implementing only the first left {@code mBindRequested} true and {@code mProvider}
         * null after every provider update, so {@link #ensureBound} returned at its guard for ever
         * and the app was silently SMS-only. Device-observed 2026-08-19: the provider held 8
         * inbound dispatches and poked the app for each, the app logged "ensuring bind" each time,
         * and it never attached. Only a force-stop recovered it.
         *
         * <p>Reinstalling the provider is not an exotic case — it is what every update does, and
         * inbound that arrives in that window is HELD rather than dropped only for as long as
         * the provider process survives.
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

        /**
         * The provider returned {@code null} from {@code onBind}. No connection will ever arrive,
         * so the latch must be released here too or it sticks exactly as in {@link #onBindingDied}.
         */
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
     * Confirm that inbound messages have been durably APPLIED (contract-v44).
     *
     * <p>Until this call lands, the provider holds the message un-acknowledged and the server
     * keeps redelivering it. That is deliberate: it is the only thing that covers this app
     * receiving a callback and then dying before it could persist the result.
     *
     * <p>Call it only once the work is actually done, never on entry to a handler. Confirming
     * early re-creates exactly the bug this replaced. Idempotent, so confirming a redelivery we
     * had already applied is safe and expected.
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
            // Not fatal, and not a loss: unconfirmed means the provider still holds it and the
            // server redelivers after the re-offer window.
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
        // Per-recipient "is this destination on RCS?" lookup. MUST be off the
        // main thread (the AIDL call may hit the network via Tachyon
        // LookupRegistered in the provider; the provider also caches). We keep a
        // light read-through cache in RouteSelector so repeat sends to the same
        // recipient don't re-cross the binder. Any failure (unbound / remote /
        // bad number) collapses to CAP_UNKNOWN so the caller keeps today's
        // optimistic "try RCS, fall back to SMS" behavior.
        if (TextUtils.isEmpty(phoneE164)) {
            return IRcsProvider.CAP_UNKNOWN;
        }
        final String key = normalizeForCache(phoneE164);

        // 1) Read-through cache hit (CAP_SMS_ONLY / CAP_RCS are sticky; we never
        // cache CAP_UNKNOWN so a transient miss is always retried).
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
     * Record that a synchronous {@link #sendMessage} attempt was rejected
     * because the peer isn't on RCS ({@code REASON_PEER_NOT_RCS}). This lets
     * the route cache learn from the send backstop so the next send to the same
     * recipient skips the RCS attempt entirely. Keyed identically to
     * {@link #lookupRcsCapability} so reads and writes agree.
     */
    public void notePeerNotRcs(final String phoneE164) {
        if (TextUtils.isEmpty(phoneE164)) {
            return;
        }
        getRouteSelector().putPeerCap(normalizeForCache(phoneE164), IRcsProvider.CAP_SMS_ONLY);
    }

    /**
     * Canonicalize a recipient to an E.164-ish cache key so raw-dialed and
     * already-normalized forms of the same number collapse to one entry. Best
     * effort: if canonicalization fails we fall back to the trimmed input.
     */
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

    // ================================================================
    // FLOW4b group surface (contract-v3). All blocking binder calls; MUST be
    // invoked off the main thread (DataModel action threads). Each no-ops to a
    // null/false result when the provider is unbound so the caller can fall back
    // to MMS. groupId is the opaque 32-char lowercase-hex GROUP_ID.
    // ================================================================

    /**
     * Create a Tachygram group. The {@code desiredGroupId} is the client-minted
     * 32-char lowercase-hex GROUP_ID (UUID.randomUUID().toString().replace("-",
     * "")) which the server echoes; the returned RcsGroupInfo.groupId is that
     * same id. Returns null if unbound or on failure. Blocking; off-main only.
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
            // PROVENANCE, recorded at the moment the id is obtained.
            //
            // The Group service carries CreateGroup and JoinGroupViaLink as DISTINCT verbs, so "how
            // this client came to hold this group id" is a first-class property of that surface —
            // and only one of the two routes is one we have built. When a mutation on an id is
            // refused, the first question is which route produced it, and reconstructing that
            // afterwards is archaeology — for the ids we already hold there is no way to say how
            // either was established, which is exactly the gap this closes going forward.
            //
            // A log line rather than a column: the value is in having it at the moment of interest,
            // and a schema change to carry a debugging aid would be scope this does not justify.
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
     * Which groups the Group service associates with us — the MEMBERSHIP query (contract v49).
     *
     * <p>Distinct from {@link #getGroupInfo}'s per-id existence read.
     * Returns the ids, or null on failure or on a provider older than v49.
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
        } catch (final NoSuchMethodError preV49) {
            LogUtil.i(TAG, "ProviderTransport: the provider predates contract v49 — no "
                    + "getGroupIds. The membership half of the group probe is unavailable; "
                    + "GetGroupInfo alone still answers existence.");
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
     * Resolve an RBM agent's brand (contract-v11) for the bot
     * conversation header — name + logo + ✓ verified badge + hero/color. The
     * provider fetches+caches the public rbm.goog/bot endpoint; the returned image
     * URLs are PUBLIC, so the UI fetches the images itself. Returns null if unbound
     * or on failure (UI renders without a brand header). Blocking; off-main only.
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
     * RBM (contract-v12) outbound postback on a suggestion-chip
     * tap: send {@code jsonBody} (a {@code botsuggestion.response} payload) to the
     * bot's {@code <agent>@rbm.goog} address with {@code contentType}. Returns the
     * provider's synchronous accept/reject (null if unbound). Blocking; off-main.
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
     * RBM on/off (contract-v13). The "RCS Business Messaging" setting
     * calls this; the provider persists it and re-registers with/without the
     * chatbot capability so the provider starts/stops routing RBM. No-op if unbound (the
     * provider keeps its prior default until next attach). Blocking; off-main.
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
     * Generic E2EE state for the settings row (contract-v16). Prefers a fresh binder
     * read; falls back to the cached push or null when the provider isn't bound.
     * Scheme-agnostic — the caller renders {@link org.lineageos.rcs.provider.RcsE2eeInfo}
     * .schemeLabel + the enabled toggle. Blocking; call off-main.
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

    /** Non-blocking last-known E2EE state (cache only) — safe on the main thread for a
     *  UI refresh. null until the first getE2eeInfo / onE2eeStateChanged. */
    @Nullable
    public org.lineageos.rcs.provider.RcsE2eeInfo peekE2eeInfo() {
        return mRouter.peekE2eeInfo();
    }

    /** Set the generic E2EE user toggle (contract-v16). The provider persists + re-applies
     *  and pushes onE2eeStateChanged. Blocking; call off-main. */
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
     * Send a text body to a group. Server fans out to members. Returns a
     * synchronous accept/reject; the terminal status arrives async via
     * onMessageStatus correlated by {@code clientMessageId}. Blocking; off-main.
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
     * Send an RCS location share (geopush, contract-v15). {@code recipient} is the
     * E.164 (1-1) or the GROUP_ID (when {@code isGroup}); {@code accuracyMeters}
     * &lt;= 0 omits the radius. Blocking; off-main.
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

    /** Add members to a group (Group/AddUsers). Blocking; off-main. */
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

    /** Remove members from a group (Group/RemoveUsers). Blocking; off-main. */
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

    /** Rename a group (Group/UpdateProfile). Blocking; off-main. */
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

    // ================================================================
    // FLOW4c FT-HTTP media send — SCAFFOLD (build-green) ONLY.
    //
    // The user asked to REVIEW the media UI before full build-out, so this
    // is the minimal AIDL-client seam, NOT the attachment pick/stage/transcode
    // UI. The full path (reusing the existing MMS MessagePartData /
    // PendingAttachmentData staging + AttachmentPreviewFactory render) is
    // DEFERRED.
    //
    // What is wired here: open a picked content URI to a ParcelFileDescriptor,
    // build the RcsOutgoingFile parcelable (URI + FD, NOT inline bytes — Binder
    // 1 MiB cap), and forward to IRcsProvider.sendFile. The provider does the
    // FT-HTTP upload + kind=36 SendMessage.
    //
    // What is NOT wired (deferred for the media-UI review): the attachment
    // picker -> PendingAttachmentData staging, image resize / size clamp to
    // MaxSizeFileTransfer, the send-button branch in ComposeMessageView /
    // SendMessageAction that routes an attachment part here instead of an MMS,
    // and inbound media render. Nothing in the app calls sendFile() yet.
    // ================================================================

    /**
     * SCAFFOLD (FLOW4c): forward a picked file to the provider for FT-HTTP send.
     * Not yet invoked by any UI — kept build-green and flagged for the media-UI
     * review. Must be called off the main thread (it opens a content URI and
     * does a binder call). Returns a synchronous ACCEPTED/REJECTED; terminal
     * status follows async via the provider's onMessageStatus callback.
     *
     * @param subId       conversation self sub
     * @param messageId   client-minted UUID (correlates onMessageStatus)
     * @param toUri       E.164 recipient
     * @param contentUri  content:// URI of the picked attachment
     * @param mimeType    the media's real MIME, e.g. "image/jpeg"
     * @param fileName    original file name (optional)
     * @param size        file size in bytes if known, else -1
     * @param caption     optional caption text (may be null)
     */
    public RcsSendResult sendFile(final int subId, final String messageId,
            final String toUri, final Uri contentUri, final String mimeType,
            @Nullable final String fileName, final long size,
            @Nullable final String caption) {
        return sendFile(subId, messageId, toUri, contentUri, mimeType, fileName,
                size, caption, /*groupId=*/ null);
    }

    /** Group-aware FT send. When {@code groupId} is non-null the file
     *  is sent to the 32-hex GROUP_ID (server fans out) and {@code toUri} is
     *  null; otherwise a 1-1 send to {@code toUri}. */
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
            // The provider dup'd the FD across the binder; close our copy.
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
            LogUtil.w(TAG, "sendImdn(" + originalMessageId + "): provider not bound — receipt dropped");
            return;
        }
        try {
            provider.sendImdn(token, originalMessageId, toUri, imdnType);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, "ProviderTransport.sendImdn failed", e);
        }
    }

    /**
     * Accept an inbound FT file: ask the provider to download the
     * full blob for {@code rcsMessageId} (only the thumbnail was eagerly fetched).
     * The provider re-fires {@link RcsCallbackRouter#onIncomingMedia} with the file
     * resolved, which updates the existing row in place. No-op if unbound. Call
     * off the main thread (binder).
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
     * Contract-v9 — send an emoji reaction on a target message. {@code add=true}
     * sends message-reaction-add; {@code add=false} sends
     * message-reaction-remove (retract). A reaction rides the ordinary kind=36
     * Tachygram path with reaction headers, so there is no reaction-specific
     * IMDN. The optimistic self-row is written by the caller via
     * {@link UpdateRcsReactionAction#recordSelfReaction} so the chip appears
     * instantly; this method just dispatches the binder send.
     *
     * <p>1:1 and group share one wire: pass {@code groupId == null} for 1:1
     * (the reaction is targeted to {@code toUri}); pass the 32-hex GROUP_ID for
     * a group (the server fans out, {@code toUri} is ignored). Silent no-op when
     * unbound. Call off the main thread (binder).
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

    // ---- Typing seam (contract; implementers reference, do not edit) ----

    /**
     * Outbound typing-indicator forwarder for the compose UI. Safe to call
     * from the main thread: the actual binder send is dispatched off-main, and
     * it is a silent no-op when the provider is unbound (so the caller never
     * needs to know whether RCS is up). The {@code subId} is accepted for
     * call-site symmetry with the rest of the seam and forward-compatibility;
     * the AIDL {@code sendTyping(clientToken, toUri, active)} routes by the
     * attached client today, so subId is advisory.
     *
     * @param subId the conversation's self sub (advisory)
     * @param toUri the peer's MSISDN / tel URI
     * @param active true on first keystroke / refresh, false on idle / send
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
     * WAVE-D: outbound GROUP typing-indicator forwarder for the compose UI.
     * Mirrors {@link #sendTyping(int, String, boolean)} but targets a GROUP_ID
     * (the provider sends one im-iscomposing+xml SendMessage to the GROUP_ID;
     * the server fans out). Safe on the main thread (binder dispatched off-main),
     * silent no-op when unbound.
     *
     * @param subId   the conversation's self sub (advisory)
     * @param groupId the 32-hex GROUP_ID
     * @param active  true on first keystroke / refresh, false on idle / send
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

    /**
     * Inbound typing-indicator sink. The conversation UI implements this to
     * show a transient "&lt;name&gt; is typing…" row. Callbacks always arrive
     * on the main thread.
     */
    public interface TypingListener {
        /**
         * @param subId  the receiving sub
         * @param fromUri the peer that is (or stopped) typing
         * @param active true = started/continuing, false = stopped/idle
         */
        void onTyping(int subId, String fromUri, boolean active);
    }

    /** Register a typing listener. Idempotent. Delegates to the shared router,
     *  which owns the in-process fan-out for every transport. */
    public void registerTypingListener(final TypingListener listener) {
        mRouter.registerTypingListener(listener);
    }

    /** Unregister a typing listener. Safe even if never registered. */
    public void unregisterTypingListener(final TypingListener listener) {
        mRouter.unregisterTypingListener(listener);
    }

    // ---- WAVE-D group-typing seam (parallel to the 1:1 typing seam above) ----

    /**
     * Inbound GROUP typing sink. The conversation UI implements this to show a
     * transient multi-name "&lt;name&gt;, &lt;name&gt; are typing…" row driven
     * by a per-sender model. Callbacks always arrive on the main thread.
     */
    public interface GroupTypingListener {
        /**
         * @param subId   the receiving sub
         * @param groupId the 32-hex GROUP_ID the indicator was fanned out to
         * @param fromUri the member sender that is (or stopped) typing
         * @param active  true = started/continuing, false = stopped/idle
         */
        void onGroupTyping(int subId, String groupId, String fromUri, boolean active);
    }

    /** Register a group-typing listener. Idempotent. Delegates to the shared router. */
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
     * Feed back the user's carrier/Google ToS decision (in response to a prior
     * {@code onCarrierTosStateChanged(TOS_REQUIRED)}). accept=true sets the
     * provider's local consent latch so it can consume the already-persisted
     * config and reach CONFIGURED (no fresh ACS request); accept=false (or a
     * later withdrawal from settings) leaves/returns the sub to SMS-only. Safe
     * on the main thread (the dialog Activity / settings withdraw-confirm call
     * it): a no-op when the provider is unbound, and any RemoteException is
     * swallowed. Mirrors {@link #submitOtp}.
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

    /** Expose the router so RouteSelector can read cached reg/prov state. */
    public RouteSelector getRouteSelector() {
        return mRouter.getRouteSelector();
    }

}

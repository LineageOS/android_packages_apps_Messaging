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
package com.android.messaging.rcs.sip;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.ims.DelegateRegistrationState;
import android.telephony.ims.DelegateRequest;
import android.telephony.ims.FeatureTagState;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateConnection;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;
import android.telephony.ims.stub.DelegateConnectionMessageCallback;
import android.telephony.ims.stub.DelegateConnectionStateCallback;

import com.android.messaging.util.LogUtil;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process holder of a {@link SipDelegateConnection} for the CPM 1-1
 * messaging ride over Shannon's single-registration SipTransport.
 *
 * <p>This is the production replacement for an out-of-process probe that
 * crashed the {@link DelegateConnectionStateCallback} because its host
 * GC'd the Java callback objects. As a real system-process app
 * (messaging2 = the {@code ROLE_SMS} holder, system-signed, holding
 * {@code PERFORM_IMS_SINGLE_REGISTRATION} {@code GRANTED_BY_ROLE}), the
 * callbacks are held by this long-lived singleton and never collected.
 *
 * <p>Google Messages parity: the same delegate factory (createSipDelegate with
 * the CPM tags), state callback (onCreated / onFeatureTagStatusChanged) and
 * message callback. The send/recv JAIN-SIP↔{@link SipMessage}
 * conversion lives in {@link CpmSipMessageBuilder}.
 *
 * <p>Lifecycle: {@link #create()} requests the delegate; the framework calls
 * {@link StateCallback#onCreated} with the connection + then
 * {@link StateCallback#onFeatureTagStatusChanged} with the per-tag grant/deny.
 * {@link #destroy()} tears it down. The {@link SipDelegateConfiguration} arrives
 * via {@link StateCallback#onConfigurationChanged} and is cached for outbound
 * message construction.
 *
 * <p>Gating (must all hold or {@link #create()} fails):
 * carrier {@code ims.ims_single_registration_required_bool=true} so
 * the framework {@code SipTransportController} feature exists, Shannon set as
 * the RCS ImsService at state READY, and this app is the {@code ROLE_SMS}
 * holder. None of those are this class's job; this class assumes the framework
 * is ready and surfaces the result via logs + {@link #getStatus()}.
 */
public final class SipDelegateClient {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "SipDelegateClient";

    private static volatile SipDelegateClient sInstance;

    private final Context mAppContext;
    private final ExecutorService mExecutor;

    // All held strongly for the delegate's lifetime so the framework binder
    // proxies + our callbacks are never GC'd (the bug that killed the probe).
    private final StateCallback mStateCallback = new StateCallback();
    private final MessageCallback mMessageCallback = new MessageCallback();

    private final AtomicReference<SipDelegateManager> mManager = new AtomicReference<>();
    private final AtomicReference<SipDelegateConnection> mConnection = new AtomicReference<>();
    private final AtomicReference<SipDelegateConfiguration> mConfig = new AtomicReference<>();

    private volatile int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private volatile String mLastStatus = "idle";
    private volatile boolean mSessionTagGranted;
    private volatile boolean mMsgTagGranted;

    /**
     * Optional observer of delegate state transitions, so the {@code :ims}-process
     * {@code CarrierImsTransport} facade can map tag-grant -> REG_REGISTERED /
     * PROV_CONFIGURED and teardown -> REG_UNREGISTERED (design §6.2, §7). Additive
     * to the existing debug-driven flow; null when only the debug receiver drives
     * this client. Held volatile; callbacks fire on {@link #mExecutor}.
     */
    public interface StatusListener {
        /** Fired on every onFeatureTagStatusChanged with the current grant bits. */
        void onTagsChanged(boolean sessionGranted, boolean msgGranted);
        /** Fired on onDestroyed with the framework destroy reason. */
        void onDelegateDestroyed(int reason);
    }

    private volatile StatusListener mStatusListener;

    /** Register the (single) status observer. Pass null to clear. */
    public void setStatusListener(StatusListener l) {
        mStatusListener = l;
    }

    /**
     * Held OUTBOUND CPM SESSION engines, keyed by normalized peer {@code +E164}
     * (design §9 keep-warm; review HIGH-3 / LOW-4). A session is established once
     * per conversation ({@link CpmSessionEngine#establish}) and HELD open; each
     * send reuses it ({@link CpmSessionEngine#sendOnSession}) and it is torn down
     * on idle-timeout / delegate loss. Keyed per-peer so concurrent sends to
     * DIFFERENT peers use DIFFERENT engines (no single-ref clobber — LOW-4).
     * Inbound SipMessages (INVITE 200 OK / in-dialog) are routed to the matching
     * engine by Call-ID from the delegate message callback.
     */
    private final Map<String, HeldSession> mSessions = new ConcurrentHashMap<>();
    /** Serializes create-or-get of a {@link HeldSession} (the map insert + engine
     *  construction); the blocking establish/send runs under the per-session lock,
     *  NOT this one. */
    private final Object mSessionLock = new Object();

    /** Idle keep-warm: hold an established MSRP session this long after the last
     *  send before tearing it down (design §9 idle-teardown).
     *  RIG-VERIFY(rcs-framework): tune the exact keep-warm idle window against the
     *  carrier's MSRP session timeout / the compose-UI resend cadence on the rig. */
    private static final long SESSION_IDLE_MS = 30000;
    /** Inbound-session leak guard: a peer session that never BYEs is closed after
     *  this much inactivity (review LOW-3). Reset on each inbound frame.
     *  RIG-VERIFY(rcs-framework): confirm the TTL vs. a genuinely idle-but-open
     *  long chat on the rig. */
    private static final long INBOUND_SESSION_TTL_MS = 600000;

    /** Idle-teardown + inbound-TTL scheduler (single daemon thread). */
    private final ScheduledExecutorService mScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SipDelegateSched");
                t.setDaemon(true);
                return t;
            });

    /** One held outbound conversation session: the engine, a per-session lock that
     *  serializes establish/send/teardown for THIS peer (LOW-4: different peers
     *  use different locks -> concurrent), and an idle-timer token so a stale idle
     *  runnable that fires just after a fresh send no-ops instead of tearing the
     *  just-used session down. */
    private static final class HeldSession {
        final CpmSessionEngine engine;
        final String key;
        final Object lock = new Object();
        long idleToken; // guarded by lock

        HeldSession(final CpmSessionEngine engine, final String key) {
            this.engine = engine;
            this.key = key;
        }
    }

    /** Active INBOUND CPM sessions, keyed by SIP Call-ID. A peer INVITE on our
     *  granted {@code oma.cpm.session} tag lands here (SR receive path); ACK/BYE
     *  in that dialog is routed back to the matching engine. */
    private final Map<String, CpmIncomingSessionEngine> mInboundSessions =
            new ConcurrentHashMap<>();
    /** Pending inbound-session TTL timers, keyed by Call-ID (review LOW-3 leak). */
    private final Map<String, ScheduledFuture<?>> mInboundTtl = new ConcurrentHashMap<>();
    /** Maps a surfaced inbound messageId -&gt; its session Call-ID so {@link
     *  #rejectIncomingFile} (called with a messageId) can find the session
     *  (review LOW-3: engines were stored by Call-ID but looked up by messageId). */
    private final Map<String, String> mInboundMsgIdToCallId = new ConcurrentHashMap<>();

    /** Sink for inbound 1-1 text (SR receive path -> EVT_INCOMING_MESSAGE). */
    @androidx.annotation.Nullable
    private volatile CpmIncomingSessionEngine.InboundListener mInboundListener;

    /** Register the inbound-text sink. Pass null to clear. */
    public void setInboundListener(CpmIncomingSessionEngine.InboundListener l) {
        mInboundListener = l;
    }

    /**
     * Per-send lifecycle for a SESSION-mode send, so the {@code :ims} service can
     * report the REAL MSRP/SIP outcome (review C4) and the REAL session
     * warm/cold state (review C3) instead of an optimistic STATUS_SENT.
     */
    public interface SessionSendCallback {
        /** MSRP media leg came up for this peer (fast-accept now possible). */
        void onSessionEstablished(String toTel);
        /** Session torn down / went idle for this peer. */
        void onSessionClosed(String toTel);
        /** Terminal outcome of the send (map to STATUS_SENT / STATUS_FAILED). */
        void onSendComplete(CpmSessionEngine.Stage terminalStage);
    }

    private SipDelegateClient(Context context) {
        mAppContext = context.getApplicationContext();
        mExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SipDelegateClient");
            t.setDaemon(true);
            return t;
        });
    }

    public static SipDelegateClient getInstance(Context context) {
        SipDelegateClient local = sInstance;
        if (local == null) {
            synchronized (SipDelegateClient.class) {
                local = sInstance;
                if (local == null) {
                    local = new SipDelegateClient(context);
                    sInstance = local;
                }
            }
        }
        return local;
    }

    /** Resolve the default-SMS sub (the line we're messaging over). */
    private int resolveSubId() {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultSubscriptionId();
        }
        return subId;
    }

    /**
     * Request the SipDelegate for the CPM 1-1 tags. Idempotent: if a delegate
     * is already held this is a no-op (logs the cached status). Safe to call
     * from any thread; the framework callbacks land on {@link #mExecutor}.
     */
    public synchronized void create() {
        if (mConnection.get() != null) {
            LogUtil.i(TAG, SUBTAG + ": delegate already held (" + mLastStatus + "); skip create");
            return;
        }
        final int subId = resolveSubId();
        mSubId = subId;
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            mLastStatus = "no-valid-sub";
            LogUtil.w(TAG, SUBTAG + ": no valid sub; cannot create delegate");
            return;
        }

        final ImsManager imsManager = mAppContext.getSystemService(ImsManager.class);
        if (imsManager == null) {
            mLastStatus = "no-ImsManager";
            LogUtil.e(TAG, SUBTAG + ": ImsManager unavailable");
            return;
        }

        final SipDelegateManager manager = imsManager.getSipDelegateManager(subId);
        mManager.set(manager);

        try {
            final boolean supported = manager.isSupported();
            LogUtil.i(TAG, SUBTAG + ": subId=" + subId + " isSupported=" + supported);
            if (!supported) {
                mLastStatus = "not-supported";
                LogUtil.w(TAG, SUBTAG + ": SipDelegate not supported on subId=" + subId
                        + " (need ims_single_registration_required_bool=true + RCS ImsService READY)");
                return;
            }
        } catch (ImsException e) {
            mLastStatus = "isSupported-threw:" + e.getCode();
            LogUtil.e(TAG, SUBTAG + ": isSupported threw code=" + e.getCode()
                    + " (ImsService not available for sub " + subId + ")", e);
            return;
        } catch (SecurityException e) {
            mLastStatus = "isSupported-SecurityException";
            LogUtil.e(TAG, SUBTAG + ": isSupported SecurityException — missing"
                    + " PERFORM_IMS_SINGLE_REGISTRATION (must be ROLE_SMS holder"
                    + " AND declare the uses-permission)", e);
            return;
        }

        final Set<String> tags = CpmFeatureTags.cpmOneToOne();
        final DelegateRequest request = new DelegateRequest(tags);
        LogUtil.i(TAG, SUBTAG + ": createSipDelegate tags=" + tags);
        try {
            manager.createSipDelegate(request, mExecutor, mStateCallback, mMessageCallback);
            mLastStatus = "create-requested";
            LogUtil.i(TAG, SUBTAG + ": createSipDelegate requested; awaiting onCreated");
        } catch (ImsException e) {
            mLastStatus = "create-threw:" + e.getCode();
            LogUtil.e(TAG, SUBTAG + ": createSipDelegate threw code=" + e.getCode(), e);
        }
    }

    /** Tear down the delegate (e.g. provisioning/PDN change). */
    public synchronized void destroy() {
        // BYE + close every held conversation session while the delegate is still
        // usable (each teardown emits its session-cold so warm state is cleared).
        teardownAllSessions("delegate destroyed");
        final SipDelegateManager manager = mManager.get();
        final SipDelegateConnection conn = mConnection.get();
        if (manager != null && conn != null) {
            try {
                manager.destroySipDelegate(conn, SipDelegateManager
                        .SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP);
                LogUtil.i(TAG, SUBTAG + ": destroySipDelegate requested");
            } catch (Throwable t) {
                LogUtil.e(TAG, SUBTAG + ": destroySipDelegate failed", t);
            }
        }
        mConnection.set(null);
        mConfig.set(null);
        mSessionTagGranted = false;
        mMsgTagGranted = false;
        mLastStatus = "destroyed";
    }

    /**
     * Force the modem to do a FULL IMS re-registration via
     * {@link SipDelegateManager#triggerFullNetworkRegistration}. The delegate
     * holds the granted RCS feature tags (oma.cpm.*) but the modem does not
     * re-REGISTER on a mere capability change — activation alone never pushes a
     * wire REGISTER. This simulates a network-forced re-register (e.g. a 403),
     * on which the modem rebuilds its Contact feature-tag set — and, since the
     * production RcsFeature now owns oma.cpm.session, the fresh REGISTER carries
     * it (registeredCapabilityBitmask 0 -> N). The {@code connection} only needs
     * to be a valid SipDelegateConnection; denied tags on it don't matter.
     */
    public synchronized void triggerReregister(final int sipCode, final String sipReason) {
        final SipDelegateManager manager = mManager.get();
        final SipDelegateConnection conn = mConnection.get();
        if (manager == null || conn == null) {
            LogUtil.w(TAG, SUBTAG + ": triggerReregister no delegate (create first)");
            return;
        }
        try {
            manager.triggerFullNetworkRegistration(conn, sipCode, sipReason);
            LogUtil.i(TAG, SUBTAG + ": triggerFullNetworkRegistration(" + sipCode
                    + ", \"" + sipReason + "\") requested — expect a wire re-REGISTER"
                    + " carrying oma.cpm.session");
        } catch (Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": triggerFullNetworkRegistration failed", t);
        }
    }

    /**
     * Send a CPM 1-1 text MESSAGE over the delegate. No-op (logs) if the
     * delegate isn't created or the session tag isn't granted.
     *
     * @return the minted Call-ID if a send was issued, else {@code null}.
     */
    public synchronized String sendText(String fromTel, String toTel, String text) {
        final SipDelegateConnection conn = mConnection.get();
        final SipDelegateConfiguration cfg = mConfig.get();
        if (conn == null || cfg == null) {
            LogUtil.w(TAG, SUBTAG + ": sendText skipped — delegate not ready ("
                    + mLastStatus + ", config=" + (cfg != null) + ")");
            return null;
        }
        if (!mSessionTagGranted) {
            LogUtil.w(TAG, SUBTAG + ": sendText proceeding although oma.cpm.session"
                    + " not marked GRANTED (status=" + mLastStatus + ")");
        }
        try {
            final CpmSipMessageBuilder.Built built =
                    CpmSipMessageBuilder.buildTextMessage(cfg, fromTel, toTel, text);
            final long version = cfg.getVersion();
            LogUtil.i(TAG, SUBTAG + ": sendMessage MESSAGE to=" + toTel
                    + " callId=" + built.callId + " imdnId=" + built.imdnMessageId
                    + " configVersion=" + version
                    + " bytes=" + built.sipMessage.getContent().length);
            CpmSessionEngine.dumpWire("MESSAGE", built.sipMessage);
            conn.sendMessage(built.sipMessage, version);
            return built.callId;
        } catch (Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": sendText build/send failed", t);
            return null;
        }
    }

    /**
     * Pre-establish and HOLD an MSRP session to {@code toTel} (design §9 keep-warm;
     * review HIGH-3). Runs the INVITE&rarr;200&rarr;ACK&rarr;MSRP-connect once and
     * keeps the media leg open for reuse — NO SEND, NO BYE. On success the {@code
     * cb.onSessionEstablished} fires (the caller emits EVT_SESSION_WARM); on
     * idle-timeout / delegate loss / peer BYE the held session is torn down and
     * {@code cb.onSessionClosed} fires (EVT_SESSION_COLD). Idempotent per peer: a
     * warm to an already-held peer just refreshes the idle timer.
     */
    public void warmSession(final String fromTel, final String toTel,
            @androidx.annotation.Nullable final SessionSendCallback cb) {
        final SipDelegateConnection conn = mConnection.get();
        final SipDelegateConfiguration cfg = mConfig.get();
        if (conn == null || cfg == null) {
            LogUtil.w(TAG, SUBTAG + ": warmSession skipped — delegate not ready ("
                    + mLastStatus + ", config=" + (cfg != null) + ")");
            return;
        }
        if (!mSessionTagGranted) {
            LogUtil.w(TAG, SUBTAG + ": warmSession proceeding although oma.cpm.session"
                    + " not marked GRANTED (status=" + mLastStatus + ")");
        }
        // Blocking establish runs on a DEDICATED thread, NOT mExecutor: it waits
        // for the INVITE 200 OK which the framework delivers on mExecutor (the
        // createSipDelegate executor) — using mExecutor here would deadlock.
        final Thread t = new Thread(() -> {
            final HeldSession hs = obtainHeldSession(toTel, cb);
            synchronized (hs.lock) {
                if (!hs.engine.isHeld()) {
                    final CpmSessionEngine.Stage est = hs.engine.establish(fromTel, toTel);
                    if (est != CpmSessionEngine.Stage.ANSWERED || !hs.engine.isHeld()) {
                        LogUtil.w(TAG, SUBTAG + ": warmSession establish failed stage=" + est
                                + " peer=" + hs.key);
                        teardownSession(hs, "warm establish failed");
                        return;
                    }
                    LogUtil.i(TAG, SUBTAG + ": session WARM (held) peer=" + hs.key);
                }
            }
            scheduleIdle(hs);
        }, "CpmSessionWarm");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Send a CPM 1-1 text by REUSING the held session to {@code toTel} (design §9;
     * review HIGH-3/C4). If no session is held (idle-torn / never warmed) it
     * lazily establishes one first (emitting {@code onSessionEstablished}). The
     * session is kept warm after the send (idle timer refreshed); the terminal
     * {@link CpmSessionEngine.Stage} is reported via {@code cb.onSendComplete} so
     * the caller emits the REAL STATUS_SENT/DELIVERED/FAILED.
     */
    public void sendOnSession(final String fromTel, final String toTel, final String text,
            @androidx.annotation.Nullable final SessionSendCallback cb) {
        dispatchSend(fromTel, toTel, text, cb, /* teardownAfter= */ false);
    }

    /**
     * Debug one-shot (SIPDELEGATE_SESSION_SEND): establish, send once, then BYE —
     * does NOT keep the session warm. Distinct from the production {@link
     * #sendOnSession} keep-warm path.
     */
    public void sendSessionText(final String fromTel, final String toTel, final String text) {
        dispatchSend(fromTel, toTel, text, null, /* teardownAfter= */ true);
    }

    /**
     * Core outbound-send driver: obtain/establish the per-peer held session, run
     * one MSRP SEND on it, then either keep it warm (idle timer) or tear it down
     * ({@code teardownAfter}, the debug one-shot). Runs blocking on a dedicated
     * thread (same deadlock-avoidance as {@link #warmSession}).
     */
    private void dispatchSend(final String fromTel, final String toTel, final String text,
            @androidx.annotation.Nullable final SessionSendCallback cb,
            final boolean teardownAfter) {
        final SipDelegateConnection conn = mConnection.get();
        final SipDelegateConfiguration cfg = mConfig.get();
        if (conn == null || cfg == null) {
            LogUtil.w(TAG, SUBTAG + ": sendOnSession skipped — delegate not ready ("
                    + mLastStatus + ", config=" + (cfg != null) + ")");
            if (cb != null) {
                cb.onSendComplete(CpmSessionEngine.Stage.FAILED);
            }
            return;
        }
        if (!mSessionTagGranted) {
            LogUtil.w(TAG, SUBTAG + ": sendOnSession proceeding although oma.cpm.session"
                    + " not marked GRANTED (status=" + mLastStatus + ")");
        }
        final Thread t = new Thread(() -> {
            final HeldSession hs = obtainHeldSession(toTel, cb);
            CpmSessionEngine.Stage stage = CpmSessionEngine.Stage.FAILED;
            boolean held = false;
            try {
                synchronized (hs.lock) {
                    held = hs.engine.isHeld();
                    if (!held) {
                        // Lazy establish (never warmed, or idle-torn since warm).
                        final CpmSessionEngine.Stage est = hs.engine.establish(fromTel, toTel);
                        held = est == CpmSessionEngine.Stage.ANSWERED && hs.engine.isHeld();
                    }
                    if (held) {
                        stage = hs.engine.sendOnSession(text);
                    }
                }
                LogUtil.i(TAG, SUBTAG + ": SESSION_SEND terminal stage=" + stage
                        + " peer=" + hs.key + (teardownAfter ? " (one-shot)" : " (held)"));
            } catch (Throwable ex) {
                LogUtil.e(TAG, SUBTAG + ": session send failed", ex);
                stage = CpmSessionEngine.Stage.FAILED;
            } finally {
                if (teardownAfter || !held) {
                    teardownSession(hs, teardownAfter ? "one-shot send" : "establish failed");
                } else {
                    scheduleIdle(hs); // keep warm for the next send
                }
                if (cb != null) {
                    try {
                        cb.onSendComplete(stage);
                    } catch (Throwable ct) {
                        LogUtil.w(TAG, SUBTAG + ": SessionSendCallback.onSendComplete threw", ct);
                    }
                }
            }
        }, "CpmSessionSend");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Get the held session for {@code toTel}, creating (but not yet establishing)
     * the engine on first use. The engine's {@link CpmSessionEngine.Observer}
     * routes onEstablished/onClosed to the (first) caller's {@code cb} so the
     * caller emits EVT_SESSION_WARM/COLD, and onClosed also unregisters the
     * session. Fast (map insert + engine ctor only); the blocking establish runs
     * under {@link HeldSession#lock} by the caller.
     */
    private HeldSession obtainHeldSession(final String toTel,
            @androidx.annotation.Nullable final SessionSendCallback cb) {
        final String key = peerKey(toTel);
        synchronized (mSessionLock) {
            final HeldSession existing = mSessions.get(key);
            if (existing != null) {
                return existing;
            }
            final CpmSessionEngine engine =
                    new CpmSessionEngine(mAppContext, mConnection.get(), mConfig.get());
            final HeldSession hs = new HeldSession(engine, key);
            engine.setObserver(new CpmSessionEngine.Observer() {
                @Override
                public void onEstablished() {
                    if (cb != null) {
                        try {
                            cb.onSessionEstablished(toTel);
                        } catch (Throwable t) {
                            LogUtil.w(TAG, SUBTAG + ": onSessionEstablished threw", t);
                        }
                    }
                }

                @Override
                public void onClosed() {
                    removeSession(key);
                    if (cb != null) {
                        try {
                            cb.onSessionClosed(toTel);
                        } catch (Throwable t) {
                            LogUtil.w(TAG, SUBTAG + ": onSessionClosed threw", t);
                        }
                    }
                }
            });
            mSessions.put(key, hs);
            return hs;
        }
    }

    /** Drop the session from the map (idempotent). Called from the engine's
     *  onClosed observer and from {@link #teardownSession}. */
    private void removeSession(final String key) {
        mSessions.remove(key);
    }

    /** BYE + close the held session (the engine's onClosed unregisters it and
     *  emits session-cold); the explicit remove covers a never-established engine
     *  whose closeMsrp still fires onClosed. Lock-guarded so it can't run
     *  concurrently with an in-flight send on the same peer. */
    private void teardownSession(final HeldSession hs, final String reason) {
        synchronized (hs.lock) {
            hs.idleToken++; // invalidate any pending idle runnable for this peer
            try {
                hs.engine.teardown(reason);
            } catch (Throwable t) {
                LogUtil.w(TAG, SUBTAG + ": session teardown failed peer=" + hs.key, t);
            }
        }
        removeSession(hs.key);
    }

    /** (Re)arm the idle-teardown timer for a held session (design §9). A stale
     *  runnable that fires after a newer send no-ops via the idleToken check, so a
     *  just-used session is never torn down out from under the next send. */
    private void scheduleIdle(final HeldSession hs) {
        final long token;
        synchronized (hs.lock) {
            token = ++hs.idleToken;
        }
        try {
            mScheduler.schedule(() -> {
                synchronized (hs.lock) {
                    if (hs.idleToken != token || !hs.engine.isHeld()) {
                        return; // superseded by a newer send / already torn down
                    }
                }
                LogUtil.i(TAG, SUBTAG + ": session idle-timeout -> teardown peer=" + hs.key);
                teardownSession(hs, "idle timeout");
            }, SESSION_IDLE_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": scheduleIdle failed peer=" + hs.key, t);
        }
    }

    /** BYE + close every held outbound session (delegate teardown / loss). */
    private void teardownAllSessions(final String reason) {
        for (final HeldSession hs : mSessions.values()) {
            teardownSession(hs, reason);
        }
        mSessions.clear();
    }

    /** Normalize a peer address to a bare {@code +E164} map key ({@code tel:+1555}
     *  / {@code sip:+1555@dom;p} -&gt; {@code +1555}). */
    private static String peerKey(final String toTel) {
        if (toTel == null) {
            return "";
        }
        String s = toTel.trim();
        if (s.startsWith("tel:")) {
            s = s.substring(4);
        } else if (s.startsWith("sip:") || s.startsWith("sips:")) {
            s = s.substring(s.indexOf(':') + 1);
            final int at = s.indexOf('@');
            if (at > 0) {
                s = s.substring(0, at);
            }
        }
        final int semi = s.indexOf(';');
        if (semi >= 0) {
            s = s.substring(0, semi);
        }
        return s.trim();
    }

    /** Close + unregister an inbound session, cancel its TTL, and drop its
     *  messageId mappings (review LOW-3 leak). Idempotent. */
    private void removeInbound(@androidx.annotation.Nullable final String callId,
            final String reason) {
        if (callId == null) {
            return;
        }
        final ScheduledFuture<?> ttl = mInboundTtl.remove(callId);
        if (ttl != null) {
            ttl.cancel(false);
        }
        final CpmIncomingSessionEngine engine = mInboundSessions.remove(callId);
        if (engine != null) {
            try {
                engine.close(reason);
            } catch (Throwable t) {
                LogUtil.w(TAG, SUBTAG + ": inbound close failed callId=" + callId, t);
            }
        }
        mInboundMsgIdToCallId.values().removeIf(callId::equals);
    }

    /** (Re)arm the inbound-session leak-guard TTL for a Call-ID (review LOW-3):
     *  if the peer never BYEs and no inbound frame arrives within the TTL, the
     *  session is closed so it doesn't leak. Reset on each inbound frame. */
    private void scheduleInboundTtl(final String callId) {
        final ScheduledFuture<?> prev = mInboundTtl.remove(callId);
        if (prev != null) {
            prev.cancel(false);
        }
        try {
            mInboundTtl.put(callId, mScheduler.schedule(() -> {
                if (mInboundSessions.containsKey(callId)) {
                    LogUtil.i(TAG, SUBTAG + ": inbound session TTL expired (no BYE) callId="
                            + callId + " -> close");
                    removeInbound(callId, "inbound TTL");
                }
            }, INBOUND_SESSION_TTL_MS, TimeUnit.MILLISECONDS));
        } catch (Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": scheduleInboundTtl failed callId=" + callId, t);
        }
    }

    /**
     * Send a CPM 1-1 IMDN receipt (delivered/displayed) as a pager-mode MESSAGE
     * (spec §7.1: {@code sendImdn -> CPIM + message/imdn+xml}). Fire-and-forget.
     *
     * @param toTel             peer {@code +E164} (no scheme).
     * @param originalMessageId the CPIM Message-ID of the message being acked.
     * @param imdnType          IRcsProviderCallback.IMDN_DELIVERED(1)/DISPLAYED(2).
     */
    public synchronized void sendImdn(String fromTel, String toTel,
            String originalMessageId, int imdnType) {
        final SipDelegateConnection conn = mConnection.get();
        final SipDelegateConfiguration cfg = mConfig.get();
        if (conn == null || cfg == null) {
            LogUtil.w(TAG, SUBTAG + ": sendImdn skipped — delegate not ready");
            return;
        }
        try {
            final CpmSipMessageBuilder.Built built =
                    CpmSipMessageBuilder.buildImdn(cfg, fromTel, toTel, originalMessageId, imdnType);
            LogUtil.i(TAG, SUBTAG + ": >>> IMDN type=" + imdnType + " for=" + originalMessageId
                    + " to=" + toTel + " callId=" + built.callId);
            conn.sendMessage(built.sipMessage, cfg.getVersion());
        } catch (Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": sendImdn build/send failed", t);
        }
    }

    /**
     * Send a CPM 1-1 typing indication as a pager-mode MESSAGE (spec §7.1:
     * {@code sendTyping -> application/im-iscomposing+xml}). Best-effort.
     */
    public synchronized void sendTyping(String fromTel, String toTel, boolean active) {
        final SipDelegateConnection conn = mConnection.get();
        final SipDelegateConfiguration cfg = mConfig.get();
        if (conn == null || cfg == null) {
            LogUtil.w(TAG, SUBTAG + ": sendTyping skipped — delegate not ready");
            return;
        }
        try {
            final CpmSipMessageBuilder.Built built =
                    CpmSipMessageBuilder.buildIsComposing(cfg, fromTel, toTel, active);
            LogUtil.i(TAG, SUBTAG + ": >>> is-composing active=" + active + " to=" + toTel);
            conn.sendMessage(built.sipMessage, cfg.getVersion());
        } catch (Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": sendTyping build/send failed", t);
        }
    }

    /**
     * Decline a pending inbound MSRP file-transfer INVITE with a SIP 603 Decline
     * (spec §8.5 — MSRP FT needs an explicit decline; FT-HTTP does not). Matches
     * the pending inbound session by the surfaced messageId.
     *
     * RIG-VERIFY(rcs-framework): inbound MSRP FT (a peer INVITE carrying an
     * {@code m=message} FT session / file-selector) is not yet routed as a
     * distinct FT session here — only 1-1 chat INVITEs are. Wire the FT-INVITE
     * branch (and its 603) once MSRP file transfer crosses the {@code :ims} seam
     * (blocked on the {@code CarrierImsSeam} FD marshalling item). Until then this
     * declines any tracked inbound session for the id.
     */
    public synchronized void rejectIncomingFile(String messageId) {
        // review LOW-3: engines are stored by Call-ID; resolve the surfaced
        // messageId -> Call-ID first (the previous direct messageId lookup on
        // mInboundSessions never matched).
        final String callId = messageId != null ? mInboundMsgIdToCallId.get(messageId) : null;
        final CpmIncomingSessionEngine engine =
                callId != null ? mInboundSessions.get(callId) : null;
        if (engine == null) {
            LogUtil.i(TAG, SUBTAG + ": rejectIncomingFile — no pending inbound FT session for id="
                    + messageId + " (FT-INVITE routing is RIG-VERIFY; no-op)");
            return;
        }
        // Present for symmetry; the 603 is emitted by the engine's decline path
        // once FT INVITEs are routed (see RIG-VERIFY above). Drop the session so a
        // declined FT does not leak.
        LogUtil.i(TAG, SUBTAG + ": rejectIncomingFile id=" + messageId + " callId=" + callId);
        removeInbound(callId, "rejected FT");
    }

    /**
     * The self line as the IMS registration knows it: the {@code +E164} parsed
     * from the registered config's P-Associated-URI / public user identifier
     * (review C6). Authoritative when registered — the SIM/ISIM identity behind
     * the single registration — and preferable to the often-null
     * {@code RcsSubInfo.msisdn}. Returns null before the delegate config arrives.
     */
    @androidx.annotation.Nullable
    public String getRegisteredSelfNumber() {
        final SipDelegateConfiguration cfg = mConfig.get();
        if (cfg == null) {
            return null;
        }
        String candidate = cfgReflectString(cfg, "getSipAssociatedUriHeader");
        String e164 = e164FromUri(candidate);
        if (e164 != null) {
            return e164;
        }
        try {
            candidate = cfg.getPublicUserIdentifier();
        } catch (Throwable ignore) {
            candidate = null;
        }
        return e164FromUri(candidate);
    }

    private static String cfgReflectString(SipDelegateConfiguration cfg, String getter) {
        try {
            java.lang.reflect.Method m = cfg.getClass().getMethod(getter);
            Object v = m.invoke(cfg);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Best-effort {@code +E164} out of a tel:/sip: URI or a P-Associated-URI list. */
    private static String e164FromUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        String s = uri;
        int lt = s.indexOf('<');
        if (lt >= 0) {
            int gt = s.indexOf('>', lt);
            if (gt > lt) {
                s = s.substring(lt + 1, gt);
            }
        }
        if (s.startsWith("tel:")) {
            s = s.substring(4);
        } else if (s.startsWith("sip:") || s.startsWith("sips:")) {
            s = s.substring(s.indexOf(':') + 1);
            int at = s.indexOf('@');
            if (at > 0) s = s.substring(0, at);
        }
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+' && b.length() == 0) b.append(c);
            else if (c >= '0' && c <= '9') b.append(c);
        }
        // Require a plausible international number.
        return b.length() >= 8 && b.charAt(0) == '+' ? b.toString() : null;
    }

    public boolean isDelegateHeld() {
        return mConnection.get() != null;
    }

    public boolean isSessionTagGranted() {
        return mSessionTagGranted;
    }

    public boolean isMsgTagGranted() {
        return mMsgTagGranted;
    }

    public String getStatus() {
        return "subId=" + mSubId + " status=" + mLastStatus
                + " delegate=" + (mConnection.get() != null)
                + " config=" + (mConfig.get() != null)
                + " sessionGranted=" + mSessionTagGranted
                + " msgGranted=" + mMsgTagGranted;
    }

    // -----------------------------------------------------------------------
    // State callback. Holds the connection + config, logs grants.
    // -----------------------------------------------------------------------
    private final class StateCallback implements DelegateConnectionStateCallback {
        @Override
        public void onCreated(SipDelegateConnection c) {
            mConnection.set(c);
            mLastStatus = "created";
            LogUtil.i(TAG, SUBTAG + ": onCreated(SipDelegateConnection) " + c);
        }

        @Override
        public void onFeatureTagStatusChanged(DelegateRegistrationState registrationState,
                Set<FeatureTagState> deniedFeatureTags) {
            final Set<String> registered = registrationState.getRegisteredFeatureTags();
            final boolean session = registered.contains(CpmFeatureTags.CPM_SESSION);
            final boolean msg = registered.contains(CpmFeatureTags.CPM_MSG);
            mSessionTagGranted = session;
            mMsgTagGranted = msg;
            mLastStatus = "tags session=" + (session ? "REGISTERED" : "not")
                    + " msg=" + (msg ? "REGISTERED" : "not");

            LogUtil.i(TAG, SUBTAG + ": onFeatureTagStatusChanged");
            LogUtil.i(TAG, SUBTAG + ":   REGISTERED tags=" + registered);
            LogUtil.i(TAG, SUBTAG + ":   >>> oma.cpm.session REGISTERED=" + session
                    + "  oma.cpm.msg REGISTERED=" + msg);
            for (FeatureTagState d : deniedFeatureTags) {
                LogUtil.i(TAG, SUBTAG + ":   DENIED tag=" + d.getFeatureTag()
                        + " reason=" + denyReason(d.getState()));
            }
            for (FeatureTagState dr : registrationState.getDeregisteringFeatureTags()) {
                LogUtil.i(TAG, SUBTAG + ":   DEREGISTERING tag=" + dr.getFeatureTag()
                        + " reason=" + dr.getState());
            }
            for (FeatureTagState dd : registrationState.getDeregisteredFeatureTags()) {
                LogUtil.i(TAG, SUBTAG + ":   DEREGISTERED tag=" + dd.getFeatureTag()
                        + " reason=" + dd.getState());
            }
            final StatusListener l = mStatusListener;
            if (l != null) {
                try {
                    l.onTagsChanged(session, msg);
                } catch (Throwable t) {
                    LogUtil.w(TAG, SUBTAG + ": StatusListener.onTagsChanged threw", t);
                }
            }
        }

        @Override
        public void onConfigurationChanged(SipDelegateConfiguration registeredSipConfig) {
            mConfig.set(registeredSipConfig);
            LogUtil.i(TAG, SUBTAG + ": onConfigurationChanged version="
                    + registeredSipConfig.getVersion()
                    + " homeDomain=" + safe(registeredSipConfig::getHomeDomain)
                    + " contactUser=" + safe(registeredSipConfig::getSipContactUserParameter)
                    + " maxUdp=" + registeredSipConfig.getMaxUdpPayloadSizeBytes());
        }

        @Override
        public void onDestroyed(int reason) {
            // The transport is gone: close every held session (BYE will fail but
            // closeMsrp still fires session-cold so the facade clears warm state).
            teardownAllSessions("onDestroyed:" + reason);
            mConnection.set(null);
            mConfig.set(null);
            mSessionTagGranted = false;
            mMsgTagGranted = false;
            mLastStatus = "onDestroyed:" + reason;
            LogUtil.i(TAG, SUBTAG + ": onDestroyed reason=" + reason);
            final StatusListener l = mStatusListener;
            if (l != null) {
                try {
                    l.onDelegateDestroyed(reason);
                } catch (Throwable t) {
                    LogUtil.w(TAG, SUBTAG + ": StatusListener.onDelegateDestroyed threw", t);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Message callback. Logs inbound + acks the transaction.
    // -----------------------------------------------------------------------
    private final class MessageCallback implements DelegateConnectionMessageCallback {
        @Override
        public void onMessageReceived(SipMessage message) {
            LogUtil.i(TAG, SUBTAG + ": onMessageReceived startLine=" + message.getStartLine()
                    + " contentBytes=" + message.getContent().length);
            // Acknowledge receipt to the transport first (the framework expects
            // notifyMessageReceived for every delivered message — both our
            // responses and unrelated inbound).
            final String viaTxnId = extractViaBranch(message.getHeaderSection());
            final SipDelegateConnection conn = mConnection.get();
            if (conn != null && viaTxnId != null) {
                try {
                    conn.notifyMessageReceived(viaTxnId);
                } catch (Throwable t) {
                    LogUtil.e(TAG, SUBTAG + ": notifyMessageReceived failed", t);
                }
            }
            routeInbound(message);
        }

        /**
         * Dispatch an inbound SipMessage: responses + in-dialog traffic for our
         * OUTBOUND session go to {@link CpmSessionEngine}; a fresh peer INVITE
         * starts an INBOUND {@link CpmIncomingSessionEngine} (SR receive path);
         * in-dialog requests for an inbound session route to that engine.
         */
        private void routeInbound(SipMessage message) {
            final String startLine = message.getStartLine();
            final int status = CpmSessionSipBuilder.parseStatusCode(startLine);
            if (status >= 0) {
                // A response — route to the held outbound session it belongs to
                // (matched by Call-ID inside the engine).
                routeToHeldSession(message);
                return;
            }
            // A request. Identify method + Call-ID.
            final String method = CpmSessionSipBuilder.parseMethod(startLine);
            final CpmSessionSipBuilder.IncomingRequest req =
                    CpmSessionSipBuilder.parseRequest(message);
            if ("INVITE".equals(method)) {
                handleInboundInvite(req, message);
                return;
            }
            // In-dialog request (ACK/BYE): route to a matching inbound session,
            // else to our held outbound session.
            if (req.callId != null) {
                final CpmIncomingSessionEngine in = mInboundSessions.get(req.callId);
                if (in != null) {
                    try {
                        in.onInDialogRequest(message);
                        if ("BYE".equals(method)) {
                            removeInbound(req.callId, "peer BYE");
                        }
                    } catch (Throwable t) {
                        LogUtil.e(TAG, SUBTAG + ": inbound in-dialog route failed", t);
                    }
                    return;
                }
            }
            routeToHeldSession(message);
        }

        /** Offer an inbound SipMessage to each held outbound engine; the engine
         *  claims only messages on its own dialog Call-ID (returns true). */
        private void routeToHeldSession(SipMessage message) {
            for (final HeldSession hs : mSessions.values()) {
                try {
                    if (hs.engine.onSipMessage(message)) {
                        return;
                    }
                } catch (Throwable t) {
                    LogUtil.e(TAG, SUBTAG + ": CpmSessionEngine.onSipMessage failed", t);
                }
            }
        }

        private void handleInboundInvite(final CpmSessionSipBuilder.IncomingRequest req,
                final SipMessage message) {
            if (req.callId != null && mInboundSessions.containsKey(req.callId)) {
                // Retransmitted INVITE for a session we're already answering.
                LogUtil.i(TAG, SUBTAG + ": duplicate inbound INVITE callId=" + req.callId);
                return;
            }
            final SipDelegateConnection conn = mConnection.get();
            final SipDelegateConfiguration cfg = mConfig.get();
            if (conn == null || cfg == null) {
                LogUtil.w(TAG, SUBTAG + ": inbound INVITE but delegate not ready; ignoring");
                return;
            }
            final String callId = req.callId;
            final CpmIncomingSessionEngine engine = new CpmIncomingSessionEngine(
                    mAppContext, conn, cfg, req,
                    // Read the volatile sink at delivery time so late binding works.
                    (fromE164, body, messageId) -> {
                        // Track messageId -> Call-ID so rejectIncomingFile (keyed by
                        // messageId) can find the session (review LOW-3), and refresh
                        // the leak-guard TTL on each inbound frame (session is active).
                        if (callId != null && messageId != null) {
                            mInboundMsgIdToCallId.put(messageId, callId);
                        }
                        if (callId != null) {
                            scheduleInboundTtl(callId);
                        }
                        final CpmIncomingSessionEngine.InboundListener l = mInboundListener;
                        if (l != null) {
                            l.onIncomingText(fromE164, body, messageId);
                        } else {
                            LogUtil.w(TAG, SUBTAG + ": inbound text dropped — no InboundListener"
                                    + " (from=" + fromE164 + " id=" + messageId + ")");
                        }
                    });
            if (callId != null) {
                mInboundSessions.put(callId, engine);
                // Leak guard (review LOW-3): a peer that never BYEs is closed after
                // INBOUND_SESSION_TTL_MS of inactivity (reset on each inbound frame).
                scheduleInboundTtl(callId);
            }
            // accept() blocks through the SDP answer + MSRP connect; run it OFF
            // the delegate executor thread (same deadlock reason as the outbound
            // send — the executor is the only SIP callback thread).
            final Thread t = new Thread(() -> {
                try {
                    if (!engine.accept() && callId != null) {
                        removeInbound(callId, "accept declined");
                    }
                } catch (Throwable ex) {
                    LogUtil.e(TAG, SUBTAG + ": inbound accept failed", ex);
                    if (callId != null) {
                        removeInbound(callId, "accept failed");
                    }
                }
            }, "CpmInboundAccept");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void onMessageSent(String viaTransactionId) {
            LogUtil.i(TAG, SUBTAG + ": onMessageSent txn=" + viaTransactionId);
        }

        @Override
        public void onMessageSendFailure(String viaTransactionId, int reason) {
            LogUtil.w(TAG, SUBTAG + ": onMessageSendFailure txn=" + viaTransactionId
                    + " reason=" + reason);
        }
    }

    private static String extractViaBranch(String headerSection) {
        if (headerSection == null) {
            return null;
        }
        // First Via's branch=z9hG4bK... is the transaction id the framework
        // wants back. Find "branch=" and read to the next ; , or whitespace.
        int i = headerSection.indexOf("branch=");
        if (i < 0) {
            return null;
        }
        int start = i + "branch=".length();
        int end = start;
        while (end < headerSection.length()) {
            char ch = headerSection.charAt(end);
            if (ch == ';' || ch == ',' || ch == ' ' || ch == '\r' || ch == '\n') {
                break;
            }
            end++;
        }
        return headerSection.substring(start, end);
    }

    private interface Getter {
        String get();
    }

    private static String safe(Getter g) {
        try {
            return g.get();
        } catch (Throwable t) {
            return "<n/a>";
        }
    }

    private static String denyReason(int code) {
        switch (code) {
            case 0: return "UNKNOWN(0)";
            case 1: return "IN_USE_BY_ANOTHER_DELEGATE(1)";
            case 2: return "NOT_ALLOWED(2)";
            case 3: return "SINGLE_REGISTRATION_NOT_ALLOWED(3)";
            case 4: return "INVALID(4)";
            default: return "reason" + code;
        }
    }
}

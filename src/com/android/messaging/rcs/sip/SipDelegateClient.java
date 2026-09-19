/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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

import com.android.messaging.rcs.log.LogMask;
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
 * Holds the framework {@link SipDelegateConnection} for CPM 1:1 messaging on the SR path, and
 * the outbound and inbound sessions that ride it. A long-lived singleton, so the framework's
 * callbacks are never collected. {@link #create()} requests the delegate; the framework then
 * reports the connection, per-tag grants and the registered {@link SipDelegateConfiguration}.
 * Needs the SMS role ({@code PERFORM_IMS_SINGLE_REGISTRATION}), a carrier configured for single
 * registration, and an RCS ImsService that is ready. See docs/rcs/carrier-transport.md.
 */
public final class SipDelegateClient {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "SipDelegateClient";

    private static volatile SipDelegateClient sInstance;

    private final Context mAppContext;
    private final ExecutorService mExecutor;

    // Held for the delegate's lifetime so the framework never loses our callbacks.
    private final StateCallback mStateCallback = new StateCallback();
    private final MessageCallback mMessageCallback = new MessageCallback();

    private final AtomicReference<SipDelegateManager> mManager = new AtomicReference<>();
    private final AtomicReference<SipDelegateConnection> mConnection = new AtomicReference<>();
    private final AtomicReference<SipDelegateConfiguration> mConfig = new AtomicReference<>();

    private volatile int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private volatile String mLastStatus = "idle";
    private volatile boolean mSessionTagGranted;
    private volatile boolean mMsgTagGranted;

    /** Delegate state for {@link CarrierImsService}; called on {@link #mExecutor}. */
    public interface StatusListener {
        /** On every tag status change, with the current grants. */
        void onTagsChanged(boolean sessionGranted, boolean msgGranted);
        /** With the framework's destroy reason. */
        void onDelegateDestroyed(int reason);
    }

    private volatile StatusListener mStatusListener;

    /** One listener; null clears. */
    public void setStatusListener(StatusListener l) {
        mStatusListener = l;
    }

    /**
     * Held outbound sessions by peer {@code +E164}: established once, reused by each send, torn
     * down after {@link #SESSION_IDLE_MS} idle or on delegate loss. Per peer, so sends to different
     * peers run concurrently. Responses are routed to the engine by Call-ID.
     */
    private final Map<String, HeldSession> mSessions = new ConcurrentHashMap<>();
    /** Guards creating a {@link HeldSession}; establishing and sending use its own lock. */
    private final Object mSessionLock = new Object();

    /**
     * Idle time after the last send before a held session is torn down. TODO: tune on a network.
     */
    private static final long SESSION_IDLE_MS = 30000;
    /** An inbound session with no BYE and no frame for this long is closed; reset per frame. */
    private static final long INBOUND_SESSION_TTL_MS = 600000;

    private final ScheduledExecutorService mScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SipDelegateSched");
                t.setDaemon(true);
                return t;
            });

    /**
     * One peer's held session: the engine, a lock serialising establish, send and teardown for that
     * peer, and an idle token so a stale idle timer after a fresh send does nothing.
     */
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

    /** Inbound sessions by Call-ID; their ACK and BYE are routed back by it. */
    private final Map<String, CpmIncomingSessionEngine> mInboundSessions =
            new ConcurrentHashMap<>();
    /** Inbound-session idle timers by Call-ID. */
    private final Map<String, ScheduledFuture<?>> mInboundTtl = new ConcurrentHashMap<>();
    /** Surfaced message id to Call-ID, so {@link #rejectIncomingFile} can find the session. */
    private final Map<String, String> mInboundMsgIdToCallId = new ConcurrentHashMap<>();

    @androidx.annotation.Nullable
    private volatile CpmIncomingSessionEngine.InboundListener mInboundListener;

    /** Null clears. */
    public void setInboundListener(CpmIncomingSessionEngine.InboundListener l) {
        mInboundListener = l;
    }

    /** Per send: the session's real establishment and teardown, and the send's terminal stage. */
    public interface SessionSendCallback {
        void onSessionEstablished(String toTel);
        void onSessionClosed(String toTel);
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

    /** The default SMS subscription, else the default one. */
    private int resolveSubId() {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultSubscriptionId();
        }
        return subId;
    }

    /**
     * Requests the delegate for the CPM tags; no-op if one is held. Callbacks run on
     * {@link #mExecutor}.
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

    public synchronized void destroy() {
        // Close held sessions while the delegate can still carry the BYE; each reports cold.
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
     * Asks for a full IMS re-registration
     * ({@link SipDelegateManager#triggerFullNetworkRegistration}). The modem does not register
     * again for a capability change alone, so this is how a newly provisioned tag reaches the
     * network. Needs a delegate, whatever its grants.
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
     * A pager-mode CPM text through the delegate. Returns the Call-ID, or null if nothing was sent.
     * Sends even when the session tag is not granted, logging it.
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
            LogUtil.i(TAG, SUBTAG + ": sendMessage MESSAGE to=" + LogMask.number(toTel)
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
     * Establishes and holds a session to {@code toTel} without sending, on its own thread. The
     * callback hears of establishment and of the later close. For a peer already held it only
     * rearms the idle timer.
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
        // Its own thread, not mExecutor: establishing waits for the 200, which the framework
        // delivers on mExecutor.
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
     * Sends a text on the held session to {@code toTel}, establishing one if needed, and keeps it
     * held; the terminal stage goes to {@code cb.onSendComplete}.
     */
    public void sendOnSession(final String fromTel, final String toTel, final String text,
            @androidx.annotation.Nullable final SessionSendCallback cb) {
        dispatchSend(fromTel, toTel, text, cb, /* teardownAfter= */ false);
    }

    /** Debug: establish, send once, then BYE, without holding the session. */
    public void sendSessionText(final String fromTel, final String toTel, final String text) {
        dispatchSend(fromTel, toTel, text, null, /* teardownAfter= */ true);
    }

    /**
     * Establishes if needed, sends once, then holds (idle timer) or tears down; on its own thread,
     * as in {@link #warmSession}.
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
                        // never warmed, or closed since
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
                    scheduleIdle(hs);
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
     * The held session for {@code toTel}, creating the engine on first use without establishing it.
     * Its observer reports to the first caller's {@code cb} and forgets the session on close.
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

    /** Idempotent. */
    private void removeSession(final String key) {
        mSessions.remove(key);
    }

    /**
     * BYE and close under the peer's lock; the engine's close reports cold. Also forgets a session
     * that never established.
     */
    private void teardownSession(final HeldSession hs, final String reason) {
        synchronized (hs.lock) {
            hs.idleToken++; // cancels any pending idle timer
            try {
                hs.engine.teardown(reason);
            } catch (Throwable t) {
                LogUtil.w(TAG, SUBTAG + ": session teardown failed peer=" + hs.key, t);
            }
        }
        removeSession(hs.key);
    }

    /** Rearms the idle timer; a timer superseded by a newer send does nothing. */
    private void scheduleIdle(final HeldSession hs) {
        final long token;
        synchronized (hs.lock) {
            token = ++hs.idleToken;
        }
        try {
            mScheduler.schedule(() -> {
                synchronized (hs.lock) {
                    if (hs.idleToken != token || !hs.engine.isHeld()) {
                        return; // superseded, or already closed
                    }
                }
                LogUtil.i(TAG, SUBTAG + ": session idle-timeout -> teardown peer=" + hs.key);
                teardownSession(hs, "idle timeout");
            }, SESSION_IDLE_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": scheduleIdle failed peer=" + hs.key, t);
        }
    }

    private void teardownAllSessions(final String reason) {
        for (final HeldSession hs : mSessions.values()) {
            teardownSession(hs, reason);
        }
        mSessions.clear();
    }

    /** A peer URI reduced to its bare {@code +E164}. */
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

    /** Closes an inbound session and drops its timer and message ids. Idempotent. */
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

    /** Rearms the inbound idle timer for a Call-ID. */
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
     * A pager-mode IMDN, fire and forget. {@code imdnType} is {@code IMDN_DELIVERED} (1) or
     * {@code IMDN_DISPLAYED} (2).
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
                    CpmSipMessageBuilder.buildImdn(cfg, fromTel, toTel, originalMessageId,
                            imdnType);
            LogUtil.i(TAG, SUBTAG + ": >>> IMDN type=" + imdnType + " for=" + originalMessageId
                    + " to=" + LogMask.number(toTel) + " callId=" + built.callId);
            conn.sendMessage(built.sipMessage, cfg.getVersion());
        } catch (Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": sendImdn build/send failed", t);
        }
    }

    /** A pager-mode typing indication; best effort. */
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
            LogUtil.i(TAG, SUBTAG + ": >>> is-composing active=" + active + " to="
                    + LogMask.number(toTel));
            conn.sendMessage(built.sipMessage, cfg.getVersion());
        } catch (Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": sendTyping build/send failed", t);
        }
    }

    /**
     * Declines an inbound file session found by its surfaced message id. File-transfer invitations
     * are not routed yet, so today this only closes a tracked session. TODO: send the 603 once they
     * are.
     */
    public synchronized void rejectIncomingFile(String messageId) {
        final String callId = messageId != null ? mInboundMsgIdToCallId.get(messageId) : null;
        final CpmIncomingSessionEngine engine =
                callId != null ? mInboundSessions.get(callId) : null;
        if (engine == null) {
            LogUtil.i(TAG, SUBTAG + ": rejectIncomingFile — no pending inbound FT session for id="
                    + messageId + " (FT-INVITE routing is RIG-VERIFY; no-op)");
            return;
        }
        LogUtil.i(TAG, SUBTAG + ": rejectIncomingFile id=" + messageId + " callId=" + callId);
        removeInbound(callId, "rejected FT");
    }

    /**
     * Our {@code +E164} from the registration's P-Associated-URI, else its public user identifier;
     * null before the configuration arrives.
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

    /** {@code +E164} from a URI or a P-Associated-URI list, or null. */
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
        // require at least a plausible international number
        return b.length() >= 8 && b.charAt(0) == '+' ? b.toString() : null;
    }

    public boolean isSessionTagGranted() {
        return mSessionTagGranted;
    }

    public String getStatus() {
        return "subId=" + mSubId + " status=" + mLastStatus
                + " delegate=" + (mConnection.get() != null)
                + " config=" + (mConfig.get() != null)
                + " sessionGranted=" + mSessionTagGranted
                + " msgGranted=" + mMsgTagGranted;
    }

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
            // The delegate is gone: close every held session; the BYE fails, but each still reports
            // cold.
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

    private final class MessageCallback implements DelegateConnectionMessageCallback {
        @Override
        public void onMessageReceived(SipMessage message) {
            LogUtil.i(TAG, SUBTAG + ": onMessageReceived startLine=" + message.getStartLine()
                    + " contentBytes=" + message.getContent().length);
            // Acknowledge every delivered message to the framework, ours or not.
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
         * Responses and in-dialog requests for an outbound session go to its
         * {@link CpmSessionEngine}; a new INVITE starts a {@link CpmIncomingSessionEngine};
         * in-dialog requests for an inbound session go to that engine.
         */
        private void routeInbound(SipMessage message) {
            final String startLine = message.getStartLine();
            final int status = CpmSessionSipBuilder.parseStatusCode(startLine);
            if (status >= 0) {
                // A response: to the held session whose Call-ID it carries.
                routeToHeldSession(message);
                return;
            }
            final String method = CpmSessionSipBuilder.parseMethod(startLine);
            final CpmSessionSipBuilder.IncomingRequest req =
                    CpmSessionSipBuilder.parseRequest(message);
            if ("INVITE".equals(method)) {
                handleInboundInvite(req, message);
                return;
            }
            // ACK or BYE: an inbound session's, else a held outbound one's.
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

        /** Offers the message to each held engine; the one on that Call-ID claims it. */
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
                // a retransmission
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
                    // Read the sink at delivery time.
                    (fromE164, body, messageId) -> {
                        // Record the message id's Call-ID and rearm the idle timer.
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
                                    + " (from=" + LogMask.number(fromE164) + " id=" + messageId
                                    + ")");
                        }
                    });
            if (callId != null) {
                mInboundSessions.put(callId, engine);
                scheduleInboundTtl(callId);
            }
            // accept() blocks through the answer and MSRP connect: not on the callback thread.
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
        // The first Via branch is the transaction id the framework wants back.
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

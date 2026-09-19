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
package com.android.messaging.rcs.carrier;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProvider;
import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsIncomingMessage;
import org.lineageos.rcs.provider.RcsOutgoingMessage;
import com.android.messaging.rcs.e2ee.RcsE2eeScheme;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsSendResult;
import org.lineageos.rcs.provider.RcsSubInfo;
import org.lineageos.rcs.provider.RcsTosPrompt;

import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.util.LogUtil;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The in-process carrier-IMS (SIP/MSRP) RCS transport (design §6) -- a co-equal
 * {@link com.android.messaging.rcs.RcsTransport} implementation alongside the
 * external {@link com.android.messaging.rcs.BoundProviderTransport}s, registered
 * into {@link ProviderRegistry#registerInProcessTransport}.
 *
 * <p><b>Two-process shape (design §6.4).</b> The crash-prone JAIN-SIP/MSRP +
 * {@code SipDelegate} engine runs in an isolated {@code :ims} process
 * ({@link CarrierImsService}); THIS class is its <b>main-process facade</b>. It:
 * <ul>
 *   <li>implements the {@code RcsTransport} seam so the rest of messaging2 (send
 *       path, RouteSelector) talks to it exactly like any other transport;</li>
 *   <li>forwards seam calls to {@code :ims} over the thin {@link CarrierImsSeam}
 *       Messenger conduit; and</li>
 *   <li>receives inbound/provisioning events back from {@code :ims} and feeds the
 *       <b>same</b> {@link IRcsProviderCallback} sink (the shared {@code
 *       RcsCallbackRouter}) the AIDL path uses -- in-process, no binder round-trip
 *       (design §6.5).</li>
 * </ul>
 * The SMS-role permission is per-UID, so the {@code :ims} process still holds it
 * (design §6.1); only the fault domain is split.
 *
 * <p><b>Send latency (design §9, §6.2 pessimistic-cold).</b> The Messenger seam
 * has no synchronous return, and MSRP session setup takes seconds, so {@link
 * #sendMessage} answers locally: if a warm session to the peer is known it
 * accepts fast and forwards; otherwise it returns {@code accepted=false} so the
 * caller keeps SMS, and kicks an out-of-band keep-warm so the next attempt is
 * fast. Warmth is mirrored from {@code :ims} via {@link
 * CarrierImsSeam#EVT_SESSION_WARM}.
 */
public final class CarrierImsTransport implements com.android.messaging.rcs.RcsTransport {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CarrierImsTransport";

    /** Contract version this transport implements (mirrors the AIDL path). */
    public static final int CONTRACT_VERSION =
            com.android.messaging.rcs.BoundProviderTransport.CONTRACT_VERSION;

    /**
     * Provider-declared selection priority (design §5.3; higher = preferred, matching
     * {@code RcsProviderService.PROVIDER_PRIORITY = 100} for a bound provider). Set
     * to 50 -- below the provider's 100 -- so carrier-IMS is the fallback, not the
     * default, when both can serve a line:
     * {@link com.android.messaging.rcs.RouteSelector} picks the provider first and only
     * falls to carrier-IMS when the provider is ineligible/terminally fails.
     * RIG-VERIFY(rcs-framework): the 50-vs-100 ordering is the only thing sequencing
     * SR-carrier vs Tachyon for a dual-eligible sub; confirm the intended precedence
     * on a line both can actually serve (no such line exists on the test fleet today).
     */
    private static final int DECLARED_PRIORITY = 50;

    /**
     * Feature bitmask this transport advertises (design §8). v1 scaffold: 1-1
     * text + IMDN + typing -- the CPM messaging path the {@code rcs/sip/**} code
     * implements. FT/GROUP/REACTION/RBM/LOCATION/E2EE are NOT yet wired across
     * the seam.
     * TODO(rcs-framework): OR in FEATURE_FT once MSRP file transfer crosses the
     * seam (needs the AIDL rev for the FD -- see {@link CarrierImsSeam}), and
     * FEATURE_GROUP once conference-focus group chat lands.
     */
    private static final int FEATURE_FLAGS =
            RcsProviderCaps.FEATURE_TEXT
            | RcsProviderCaps.FEATURE_IMDN
            | RcsProviderCaps.FEATURE_TYPING;

    private static volatile CarrierImsTransport sInstance;

    private final Context mAppContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** Shared inbound sink: the app's process-wide {@code RcsCallbackRouter}
     *  (design §6.5), injected at {@link #register}. Carrier-IMS events are fed to
     *  it in-process (no binder round-trip). Nullable only for tests. */
    @Nullable private final IRcsProviderCallback mSink;

    /** Static caps, built once; {@code priority}/{@code featureFlags} drive selection. */
    private final RcsProviderCaps mCaps;

    /** Events sink the :ims process posts back to (main-process Handler). */
    private final Messenger mEventsMessenger;

    private final Object mLock = new Object();
    /** Control channel into :ims; null until the service is bound. */
    @Nullable private Messenger mControl;
    private boolean mBindRequested;
    /** Peers (subId|toUri) with a warm session -> fast-accept sends. */
    private final Set<String> mWarmPeers =
            Collections.synchronizedSet(new HashSet<String>());

    private CarrierImsTransport(final Context context, @Nullable final IRcsProviderCallback sink) {
        mAppContext = context.getApplicationContext();
        mSink = sink;
        mEventsMessenger = new Messenger(new EventHandler(Looper.getMainLooper()));
        mCaps = new RcsProviderCaps(
                "Carrier IMS (SIP/MSRP) RCS",
                CONTRACT_VERSION,
                new int[] { RcsProviderCaps.TRANSPORT_CARRIER_MSRP },
                /* canSelfProvision= */ true,
                DECLARED_PRIORITY,
                FEATURE_FLAGS);
    }

    /**
     * Construct the transport and register it into the {@link ProviderRegistry}
     * static in-process slot (design §4.3, §6). Call once, from the MAIN process
     * only (guarded by the caller). Idempotent.
     *
     * @param sink the shared process-wide {@code RcsCallbackRouter} (design §6.5;
     *             nullable only for tests).
     */
    public static synchronized CarrierImsTransport register(final Context context,
            final ProviderRegistry registry, @Nullable final IRcsProviderCallback sink) {
        if (sInstance == null) {
            sInstance = new CarrierImsTransport(context, sink);
        }
        registry.registerInProcessTransport(sInstance);
        sInstance.ensureBound();
        LogUtil.i(TAG, SUBTAG + ": registered in-process carrier-IMS transport");
        return sInstance;
    }

    @Nullable
    public static CarrierImsTransport peek() {
        return sInstance;
    }

    // ---- :ims bind lifecycle ----

    /** Bind the isolated {@code :ims} service and hand it our events Messenger. */
    public void ensureBound() {
        mMainHandler.post(this::bindLocked);
    }

    private void bindLocked() {
        synchronized (mLock) {
            if (mControl != null || mBindRequested) {
                return;
            }
            mBindRequested = true;
        }
        final Intent intent = new Intent(mAppContext, CarrierImsService.class);
        boolean ok;
        try {
            ok = mAppContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        } catch (final SecurityException e) {
            LogUtil.w(TAG, SUBTAG + ": bind :ims denied", e);
            ok = false;
        }
        if (!ok) {
            LogUtil.w(TAG, SUBTAG + ": :ims service not bindable");
            synchronized (mLock) {
                mBindRequested = false;
            }
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder service) {
            final Messenger control = new Messenger(service);
            synchronized (mLock) {
                mControl = control;
                mBindRequested = false;
            }
            // Hand :ims the events Messenger so it can call back.
            final Message reg = Message.obtain(null, CarrierImsSeam.MSG_REGISTER_EVENTS);
            reg.replyTo = mEventsMessenger;
            sendToIms(reg);
            LogUtil.i(TAG, SUBTAG + ": :ims connected");
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            LogUtil.w(TAG, SUBTAG + ": :ims disconnected");
            synchronized (mLock) {
                mControl = null;
                mBindRequested = false;
            }
            mWarmPeers.clear();
            // The :ims fault domain died (design §6.4 crash isolation): the main
            // UI process survives; rebind so a subsequent send/start can retry.
            ensureBound();
        }
    };

    /** Post a control Message to :ims; drop (log) if not yet bound. */
    private boolean sendToIms(final Message msg) {
        final Messenger control;
        synchronized (mLock) {
            control = mControl;
        }
        if (control == null) {
            LogUtil.w(TAG, SUBTAG + ": :ims not bound; dropping msg what=" + msg.what);
            ensureBound();
            return false;
        }
        try {
            control.send(msg);
            return true;
        } catch (final RemoteException e) {
            LogUtil.w(TAG, SUBTAG + ": send to :ims failed what=" + msg.what, e);
            synchronized (mLock) {
                mControl = null;
            }
            ensureBound();
            return false;
        }
    }

    private static String warmKey(final int subId, @Nullable final String toUri) {
        return subId + "|" + (toUri == null ? "" : toUri);
    }

    // ---- RcsTransport ----

    @Override
    public boolean isAttached() {
        synchronized (mLock) {
            // In-process transport: "attached" == the :ims conduit is up. Unlike a
            // bound external provider there is no attach() token ceremony
            // (design §2: isAttached is trivial for an in-process impl).
            return mControl != null;
        }
    }

    @Override
    @Nullable
    public RcsProviderCaps getProviderCaps() {
        return mCaps;
    }

    @Override
    public int canServeSub(final RcsSubInfo sub) {
        if (sub == null) {
            return IRcsProvider.LINE_UNKNOWN;
        }
        // design D5: carrier-IMS eligibility is not synchronously knowable (it
        // depends on live modem IMS state), so v1 always returns MAYBE and lets
        // startForSub be authoritative. The SR-vs-DR probe (CarrierImsMode.probe)
        // runs later, off-main, inside :ims -- NOT here on the query path.
        return IRcsProvider.LINE_MAYBE;
    }

    @Override
    public void startForSub(final RcsSubInfo sub) {
        if (sub == null) {
            return;
        }
        final Bundle b = new Bundle();
        b.putParcelable(CarrierImsSeam.KEY_SUB_INFO, sub);
        final Message m = Message.obtain(null, CarrierImsSeam.MSG_START_FOR_SUB);
        m.setData(b);
        sendToIms(m);
    }

    @Override
    public void stopForSub(final int subId) {
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        final Message m = Message.obtain(null, CarrierImsSeam.MSG_STOP_FOR_SUB);
        m.setData(b);
        sendToIms(m);
    }

    @Override
    @Nullable
    public RcsProviderCaps getCapabilitiesForSub(final int subId) {
        // TODO(rcs-framework): per-sub negotiated caps require a synchronous read
        // from :ims, which the Messenger seam can't do (see CarrierImsSeam TODO).
        // Until the AIDL rev, return the static caps once registration is up;
        // RouteSelector's REG/PROV gate is the authoritative "RCS is usable"
        // signal anyway. Returning null here == "not registered".
        return isAttached() ? mCaps : null;
    }

    @Override
    public int lookupRcsCapability(final int subId, final String phoneE164) {
        // TODO(rcs-framework): map to a SIP OPTIONS capability exchange (or a
        // presence fetch) inside :ims (spec §7.1 leak #3). No synchronous seam
        // path yet -> CAP_UNKNOWN keeps the caller's optimistic routing (which is
        // then backstopped by the pessimistic-cold sendMessage below).
        return IRcsProvider.CAP_UNKNOWN;
    }

    @Override
    public RcsSendResult sendMessage(final RcsOutgoingMessage msg) {
        if (msg == null) {
            return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR, "null msg");
        }
        if (!isAttached()) {
            return new RcsSendResult(false, RcsSendResult.REASON_NOT_REGISTERED, ":ims not bound");
        }
        // Pager-mode standalone MESSAGE (body below the MSRP switchover size) needs
        // NO warm MSRP session — it goes out immediately as a single SIP MESSAGE, so
        // fast-accept + forward. Only session-mode (large body) requires a warm MSRP
        // session first. A warm peer also fast-accepts.
        final int bodyLen = msg.body != null ? msg.body.length : 0;
        final boolean pager = !CarrierTransportBridge.shouldUseMsrpSession(bodyLen);
        final boolean warm = mWarmPeers.contains(warmKey(msg.subId, msg.toUri));
        if (pager || warm) {
            // Accept fast + forward the send to the :ims driver. Terminal status
            // arrives async via EVT_MESSAGE_STATUS (echoing msg.messageId).
            final Bundle b = new Bundle();
            b.putParcelable(CarrierImsSeam.KEY_OUT_MSG, msg);
            final Message m = Message.obtain(null, CarrierImsSeam.MSG_SEND_MESSAGE);
            m.setData(b);
            if (sendToIms(m)) {
                return new RcsSendResult(true, RcsSendResult.REASON_OK, null);
            }
            return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR, "seam send failed");
        }
        // Cold session-mode (design §9 pessimistic): large body with no warm session
        // -> reject so the caller keeps SMS (protects against multi-second MSRP setup
        // latency), and kick an out-of-band keep-warm so the next attempt is fast.
        warmPeer(msg.subId, msg.toUri);
        return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR,
                "carrier-IMS session cold; kept SMS");
    }

    /**
     * Pre-establish (keep-warm) an MSRP session to a peer so a subsequent {@link
     * #sendMessage} accepts fast (design §9 "keep it warm"). Fire-and-forget; the
     * warm state is mirrored back via {@link CarrierImsSeam#EVT_SESSION_WARM}.
     * TODO(rcs-framework): call this from the compose-UI on conversation open (the
     * natural pre-warm trigger) once the send path routes through this transport.
     */
    public void warmPeer(final int subId, @Nullable final String toUri) {
        if (TextUtils.isEmpty(toUri)) {
            return;
        }
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        b.putString(CarrierImsSeam.KEY_TO_URI, toUri);
        final Message m = Message.obtain(null, CarrierImsSeam.MSG_WARM_PEER);
        m.setData(b);
        sendToIms(m);
    }

    @Override
    public void sendImdn(final String originalMessageId, final String toUri, final int imdnType) {
        final Bundle b = new Bundle();
        b.putString(CarrierImsSeam.KEY_MESSAGE_ID, originalMessageId);
        b.putString(CarrierImsSeam.KEY_TO_URI, toUri);
        b.putInt(CarrierImsSeam.KEY_IMDN_TYPE, imdnType);
        final Message m = Message.obtain(null, CarrierImsSeam.MSG_SEND_IMDN);
        m.setData(b);
        sendToIms(m);
    }

    @Override
    public void sendTyping(final String toUri, final boolean active) {
        final Bundle b = new Bundle();
        b.putString(CarrierImsSeam.KEY_TO_URI, toUri);
        b.putBoolean(CarrierImsSeam.KEY_TYPING_ACTIVE, active);
        final Message m = Message.obtain(null, CarrierImsSeam.MSG_SEND_TYPING);
        m.setData(b);
        sendToIms(m);
    }

    /** DEBUG: drive an FT-HTTP upload of a device file in the :ims process. When
     *  {@code toUri} is non-null, also send the resulting FT file-info MESSAGE to it. */
    public void debugFtUpload(final int subId, final String path, final String toUri) {
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        b.putString(CarrierImsSeam.KEY_FT_PATH, path);
        if (toUri != null) {
            b.putString(CarrierImsSeam.KEY_TO_URI, toUri);
        }
        final Message m = Message.obtain(null, CarrierImsSeam.MSG_DEBUG_FT_UPLOAD);
        m.setData(b);
        sendToIms(m);
    }

    /** DEBUG: send a PLAIN (non-CPM) text/plain MESSAGE to {@code toUri} from the
     *  :ims SIP stack, to exercise the pure IMS terminating path. */
    public void sendPlainDebug(final int subId, final String toUri, final String text,
            final String messageId) {
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        if (toUri != null) {
            b.putString(CarrierImsSeam.KEY_TO_URI, toUri);
        }
        b.putString(CarrierImsSeam.KEY_PLAIN_TEXT, text);
        b.putString(CarrierImsSeam.KEY_MESSAGE_ID, messageId);
        final Message m = Message.obtain(null, CarrierImsSeam.MSG_DEBUG_PLAIN_MESSAGE);
        m.setData(b);
        sendToIms(m);
    }

    /**
     * Send an MLS-E2EE chat message to {@code toUri} over the carrier CPM/MSRP session.
     * The whole app-provided-MLS flow (enrol/claim/group/Welcome/encrypt) runs in the :ims process
     * co-located with the transport. DEBUG driver for the 2-device lab test.
     */
    public void sendMls(final int subId, final String toUri, final byte[] framedBody,
            final String messageId) {
        // The SAME parcelable MSG_SEND crosses with. This used to put the body in
        // KEY_PLAIN_TEXT — a String, and a DEBUG key shared with MSG_DEBUG_PLAIN_MESSAGE — which is
        // why this leg could carry nothing but UTF-8 text. RcsOutgoingMessage already had the right
        // shape and already crosses this Messenger, so the MLS send is now symmetric with the
        // plaintext one instead of being a debug shortcut with its own vocabulary.
        //
        // contentType is the OUTER type; the inner one is inside the frame. Same split the Tachyon
        // leg's RcsOutgoingMessage uses, so a reader does not have to learn two conventions.
        final Bundle b = new Bundle();
        b.putParcelable(CarrierImsSeam.KEY_OUT_MSG, new RcsOutgoingMessage(
                subId, messageId, toUri, "message/mls", framedBody,
                RcsE2eeScheme.MLS, /*groupId=*/ null));
        final Message m = Message.obtain(null, CarrierImsSeam.MSG_SEND_MLS);
        m.setData(b);
        sendToIms(m);
    }

    // ---- RcsTransport E2EE seam ----

    /**
     * The carrier-IMS transport carries app-provided MLS. "Ready" == attached: the
     * :ims conduit provisions MLS (enrol + KP publish) automatically on REGISTER
     * (CarrierRcsTransport.ensureMls), so an attached conduit is MLS-provisioned for
     * the lab. (A future EVT_MLS_PROVISIONED could make this precise per-sub.)
     */
    @Override
    public boolean isMlsReady(final int subId) {
        return isAttached();
    }

    /** Route an already-gated MLS send through the proven app-provided-MLS path. */
    @Override
    public boolean sendMlsMessage(final int subId, final String toUri, final byte[] framedBody,
            final String messageId) {
        if (!isAttached()) {
            return false;
        }
        sendMls(subId, toUri, framedBody, messageId);
        return true;
    }

    @Override
    public void submitOtp(final int subId, final String otp) {
        // design §7: carrier IMS has NO OTP path (the IMS PDN is SIM/ISIM-
        // authenticated; the SIM is the identity). Forward anyway so the seam is
        // symmetric; :ims no-ops it.
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        b.putString(CarrierImsSeam.KEY_OTP, otp);
        final Message m = Message.obtain(null, CarrierImsSeam.MSG_SUBMIT_OTP);
        m.setData(b);
        sendToIms(m);
    }

    @Override
    public void rejectIncomingFile(final int subId, final String messageId) {
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        b.putString(CarrierImsSeam.KEY_MESSAGE_ID, messageId);
        final Message m = Message.obtain(null, CarrierImsSeam.MSG_REJECT_FILE);
        m.setData(b);
        sendToIms(m);
    }

    // ---- events from :ims -> feed the shared RcsCallbackRouter (design §6.5) ----

    private final class EventHandler extends Handler {
        EventHandler(final Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(final Message msg) {
            final Bundle data = msg.getData();
            if (data != null) {
                data.setClassLoader(getClass().getClassLoader());
            }
            switch (msg.what) {
                case CarrierImsSeam.EVT_PROV_STATE:
                    dispatchProv(data);
                    break;
                case CarrierImsSeam.EVT_REG_STATE:
                    dispatchReg(data);
                    break;
                case CarrierImsSeam.EVT_INCOMING_MESSAGE:
                    dispatchIncoming(data);
                    break;
                case CarrierImsSeam.EVT_MESSAGE_STATUS:
                    dispatchStatus(data);
                    break;
                case CarrierImsSeam.EVT_SESSION_WARM:
                    if (data != null) {
                        mWarmPeers.add(warmKey(data.getInt(CarrierImsSeam.KEY_SUB_ID),
                                data.getString(CarrierImsSeam.KEY_TO_URI)));
                    }
                    break;
                case CarrierImsSeam.EVT_SESSION_COLD:
                    if (data != null) {
                        mWarmPeers.remove(warmKey(data.getInt(CarrierImsSeam.KEY_SUB_ID),
                                data.getString(CarrierImsSeam.KEY_TO_URI)));
                    }
                    break;
                case CarrierImsSeam.EVT_TOS_STATE:
                    // design §7: the :ims half (CarrierImsService.onRcsConfig)
                    // parses the autoconfig <MSG> T&C gate (KIND_CARRIER_TOS) and
                    // emits this with an optional RcsTosPrompt; surface it on the
                    // shared router exactly like an external provider would.
                    dispatchTos(data);
                    break;
                default:
                    LogUtil.w(TAG, SUBTAG + ": unknown event what=" + msg.what);
            }
        }
    }

    private void dispatchProv(@Nullable final Bundle data) {
        if (data == null || mSink == null) {
            return;
        }
        try {
            final RcsProviderCaps caps = data.getParcelable(CarrierImsSeam.KEY_CAPS);
            mSink.onProvisioningStateChanged(data.getInt(CarrierImsSeam.KEY_SUB_ID),
                    data.getInt(CarrierImsSeam.KEY_STATE), caps);
        } catch (final RemoteException e) {
            // In-process stub: RemoteException is not expected, but the interface
            // declares it. Swallow (spec §6.1 additive-callback discipline).
            LogUtil.w(TAG, SUBTAG + ": onProvisioningStateChanged failed", e);
        }
    }

    private void dispatchReg(@Nullable final Bundle data) {
        if (data == null || mSink == null) {
            return;
        }
        try {
            mSink.onRegistrationStateChanged(data.getInt(CarrierImsSeam.KEY_SUB_ID),
                    data.getInt(CarrierImsSeam.KEY_STATE),
                    data.getString(CarrierImsSeam.KEY_REASON));
        } catch (final RemoteException e) {
            LogUtil.w(TAG, SUBTAG + ": onRegistrationStateChanged failed", e);
        }
    }

    private void dispatchIncoming(@Nullable final Bundle data) {
        if (data == null || mSink == null) {
            return;
        }
        final RcsIncomingMessage in = data.getParcelable(CarrierImsSeam.KEY_IN_MSG);
        if (in == null) {
            return;
        }
        try {
            mSink.onIncomingMessage(in);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, SUBTAG + ": onIncomingMessage failed", e);
        }
    }

    private void dispatchStatus(@Nullable final Bundle data) {
        if (data == null || mSink == null) {
            return;
        }
        try {
            mSink.onMessageStatus(data.getInt(CarrierImsSeam.KEY_SUB_ID),
                    data.getString(CarrierImsSeam.KEY_MESSAGE_ID),
                    data.getInt(CarrierImsSeam.KEY_STATUS),
                    data.getString(CarrierImsSeam.KEY_REASON));
        } catch (final RemoteException e) {
            LogUtil.w(TAG, SUBTAG + ": onMessageStatus failed", e);
        }
    }

    private void dispatchTos(@Nullable final Bundle data) {
        if (data == null || mSink == null) {
            return;
        }
        try {
            final RcsTosPrompt prompt = data.getParcelable(CarrierImsSeam.KEY_TOS_PROMPT);
            mSink.onCarrierTosStateChanged(data.getInt(CarrierImsSeam.KEY_SUB_ID),
                    data.getInt(CarrierImsSeam.KEY_STATE), prompt);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, SUBTAG + ": onCarrierTosStateChanged failed", e);
        }
    }
}

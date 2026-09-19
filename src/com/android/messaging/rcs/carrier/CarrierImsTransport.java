/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
import com.android.messaging.rcs.e2ee.MlsCarrierTrust;
import com.android.messaging.rcs.e2ee.RcsE2eeScheme;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsSendResult;
import org.lineageos.rcs.provider.RcsSubInfo;
import org.lineageos.rcs.provider.RcsTosPrompt;

import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.rcs.RcsCallbackRouter;
import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.util.LogUtil;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The carrier transport's main-process facade: an in-process
 * {@link com.android.messaging.rcs.RcsTransport} registered in {@link ProviderRegistry} that
 * forwards calls to {@link CarrierImsService} in {@code :ims} over {@link CarrierImsSeam} and
 * feeds its events straight into the shared {@link IRcsProviderCallback} sink. See
 * docs/rcs/carrier-transport.md.
 */
public final class CarrierImsTransport implements com.android.messaging.rcs.RcsTransport {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CarrierImsTransport";

    /** Same as the bound-provider path. */
    public static final int CONTRACT_VERSION =
            com.android.messaging.rcs.BoundProviderTransport.CONTRACT_VERSION;

    /**
     * Below a bound provider's priority, so this transport is the fallback on a line both can
     * serve. TODO: confirm that precedence on a line both can actually serve.
     */
    private static final int DECLARED_PRIORITY = 50;

    /** TODO: add FEATURE_FT once file transfer crosses the seam, and FEATURE_GROUP with groups. */
    private static final int FEATURE_FLAGS =
            RcsProviderCaps.FEATURE_TEXT
            | RcsProviderCaps.FEATURE_IMDN
            | RcsProviderCaps.FEATURE_TYPING;

    private static volatile CarrierImsTransport sInstance;

    private final Context mAppContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** The process-wide {@code RcsCallbackRouter}; null only in tests. */
    @Nullable private final IRcsProviderCallback mSink;

    private final RcsProviderCaps mCaps;

    /** Receives events from :ims, on the main thread. */
    private final Messenger mEventsMessenger;

    private final Object mLock = new Object();
    /** Null until :ims is bound. */
    @Nullable private Messenger mControl;
    private boolean mBindRequested;
    /** {@code subId|toUri} of peers with an established session. */
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
     * Creates the transport once, registers it and binds :ims. Main process only. {@code sink} is
     * the process-wide {@code RcsCallbackRouter}, null only in tests.
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
            // :ims died; the UI process survives. Rebind so a later start or send can retry.
            ensureBound();
        }
    };

    /** Returns false, and asks for a rebind, if :ims is not bound or the send failed. */
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

    @Override
    public boolean isAttached() {
        synchronized (mLock) {
            // No attach handshake in process: attached means the :ims conduit is up.
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
        // Eligibility depends on live modem and IMS state, which only startForSub can probe.
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
        // The seam cannot answer synchronously, so the static caps stand in while :ims is bound;
        // the registration and provisioning states are what gate use. TODO: per-subscription caps.
        return isAttached() ? mCaps : null;
    }

    @Override
    public int lookupRcsCapability(final int subId, final String phoneE164) {
        // TODO: a SIP capability or presence query in :ims. Unknown keeps the caller's routing,
        // which a cold session send then backstops.
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
        // A pager-sized body needs no session, and a warm peer has one: accept and forward.
        final int bodyLen = msg.body != null ? msg.body.length : 0;
        final boolean pager = !CarrierTransportBridge.shouldUseMsrpSession(bodyLen);
        final boolean warm = mWarmPeers.contains(warmKey(msg.subId, msg.toUri));
        if (pager || warm) {
            // The terminal status arrives as EVT_MESSAGE_STATUS.
            final Bundle b = new Bundle();
            b.putParcelable(CarrierImsSeam.KEY_OUT_MSG, msg);
            final Message m = Message.obtain(null, CarrierImsSeam.MSG_SEND_MESSAGE);
            m.setData(b);
            if (sendToIms(m)) {
                return new RcsSendResult(true, RcsSendResult.REASON_OK, null);
            }
            return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR,
                    "seam send failed");
        }
        // A session-sized body with no session: decline so the caller sends SMS, since setup takes
        // seconds, and warm the peer so the next attempt is accepted.
        warmPeer(msg.subId, msg.toUri);
        return new RcsSendResult(false, RcsSendResult.REASON_INTERNAL_ERROR,
                "carrier-IMS session cold; kept SMS");
    }

    /**
     * Establishes a session to the peer ahead of a send; the result comes back as
     * {@link CarrierImsSeam#EVT_SESSION_WARM}. TODO: call on conversation open.
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

    /** Debug: uploads a device file from :ims, and sends its descriptor to {@code toUri} if set. */
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

    /** Debug: a non-CPM {@code text/plain} SIP message from the :ims stack. */
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

    /** Sends an RCC.16-framed MLS body over the carrier session; sealing happens before this. */
    public void sendMls(final int subId, final String toUri, final byte[] framedBody,
            final String messageId) {
        // The same parcelable as a plaintext send. contentType is the outer type; the inner one is
        // inside the frame.
        final Bundle b = new Bundle();
        b.putParcelable(CarrierImsSeam.KEY_OUT_MSG, new RcsOutgoingMessage(
                subId, messageId, toUri, "message/mls", framedBody,
                RcsE2eeScheme.MLS, /*groupId=*/ null));
        final Message m = Message.obtain(null, CarrierImsSeam.MSG_SEND_MLS);
        m.setData(b);
        sendToIms(m);
    }

    /**
     * Attached, and carrier-path MLS can be on: the DR stack provisions MLS when it registers, but
     * only with trust anchors, which a user build has only from the provider (see {@link
     * MlsCarrierTrust#readyInApp}). False keeps the conversation off MLS and its sends on the
     * ordinary route.
     */
    @Override
    public boolean isMlsReady(final int subId) {
        return MlsCarrierTrust.readyInApp(isAttached(), RcsDebug.isDebugBuild());
    }

    /** Forwards an already-gated MLS send. */
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
        // No OTP on this transport, since the SIM is the identity; forwarded and ignored in :ims.
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

    // Events from :ims into the shared sink.

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
            // In process this is not expected, but the interface declares it.
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
        final int subId = data.getInt(CarrierImsSeam.KEY_SUB_ID);
        final String messageId = data.getString(CarrierImsSeam.KEY_MESSAGE_ID);
        final int status = data.getInt(CarrierImsSeam.KEY_STATUS);
        final String reason = data.getString(CarrierImsSeam.KEY_REASON);
        // Named as carrier: its status must never change a scheme stamp or a conversation's
        // encryption bits, and a null scheme alone cannot say so.
        if (mSink instanceof RcsCallbackRouter) {
            ((RcsCallbackRouter) mSink).onCarrierMessageStatus(subId, messageId, status, reason);
            return;
        }
        try {
            mSink.onMessageStatus(subId, messageId, status, reason, null);
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

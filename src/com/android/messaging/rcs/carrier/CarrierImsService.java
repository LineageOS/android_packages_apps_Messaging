/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsIncomingMessage;
import org.lineageos.rcs.provider.RcsOutgoingMessage;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsSubInfo;
import org.lineageos.rcs.provider.RcsTosPrompt;

import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.rcs.sip.CpmIncomingSessionEngine;
import com.android.messaging.rcs.sip.CpmSessionEngine;
import com.android.messaging.rcs.sip.ShannonRcsConfigTrigger;
import com.android.messaging.rcs.sip.SipDelegateClient;
import com.android.messaging.util.LogUtil;

import java.nio.charset.StandardCharsets;

/**
 * The carrier transport's {@code :ims} half: runs the SIP/MSRP stack in its own process so a fault
 * there cannot take down the UI, and speaks {@link CarrierImsSeam} with
 * {@link CarrierImsTransport}. Per subscription, {@link CarrierImsMode#probe} picks SR
 * ({@link SipDelegateClient}, {@link ShannonRcsConfigTrigger}) or DR ({@link CarrierDrModeDriver}).
 * All work runs on one worker thread. See docs/rcs/carrier-transport.md.
 */
public final class CarrierImsService extends Service {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CarrierImsService";

    private static final int REG_UNREGISTERED = IRcsProviderCallback.REG_UNREGISTERED;
    private static final int REG_REGISTERING = IRcsProviderCallback.REG_REGISTERING;
    private static final int REG_REGISTERED = IRcsProviderCallback.REG_REGISTERED;
    private static final int REG_FAILED = IRcsProviderCallback.REG_FAILED;
    private static final int PROV_IN_PROGRESS = IRcsProviderCallback.PROV_IN_PROGRESS;
    private static final int PROV_CONFIGURED = IRcsProviderCallback.PROV_CONFIGURED;
    private static final int STATUS_SENT = IRcsProviderCallback.STATUS_SENT;
    private static final int STATUS_DELIVERED = IRcsProviderCallback.STATUS_DELIVERED;
    private static final int STATUS_FAILED = IRcsProviderCallback.STATUS_FAILED;
    private static final int TOS_NONE = IRcsProviderCallback.TOS_NONE;
    private static final int TOS_REQUIRED = IRcsProviderCallback.TOS_REQUIRED;

    /**
     * Re-probe delays while the probe answers UNKNOWN; the last repeats until
     * {@link #MAX_PROBE_ATTEMPTS}, then REG_FAILED lets the line fall back to SMS until a later
     * start.
     */
    private static final long[] PROBE_BACKOFF_MS = { 2000, 5000, 10000, 20000, 30000 };
    private static final int MAX_PROBE_ATTEMPTS = 6;
    private int mProbeAttempts;
    /** Bumped by every start and stop so a delayed retry from a superseded start is dropped. */
    private int mProbeGeneration;

    /** Keeps the probe, delegate creation and sends off the binder thread. */
    private HandlerThread mThread;
    private Handler mWorker;
    private Messenger mControlMessenger;

    /** Set by MSG_REGISTER_EVENTS. */
    @Nullable private volatile Messenger mEvents;

    /** The subscription being served. */
    private volatile int mSubId = -1;
    @Nullable private volatile String mMsisdn;
    @Nullable private volatile String mMccMnc;
    /** The resolved self {@code +E164}, cached per start. */
    @Nullable private volatile String mResolvedFrom;
    private volatile CarrierImsMode mMode = CarrierImsMode.UNKNOWN;
    /** Suppresses repeated identical EVT_TOS_STATE. */
    private volatile int mLastTosState = TOS_NONE;

    /** Reports REG_FAILED by itself when no usable configuration exists. */
    private CarrierDrModeDriver mDrDriver;

    @Override
    public void onCreate() {
        super.onCreate();
        mThread = new HandlerThread("CarrierImsService");
        mThread.start();
        mWorker = new Handler(mThread.getLooper());
        mControlMessenger = new Messenger(new ControlHandler(mThread.getLooper()));
        mDrDriver = new CarrierStackDrModeDriver(getApplicationContext());
        LogUtil.i(TAG, SUBTAG + ": :ims process up");
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return mControlMessenger.getBinder();
    }

    @Override
    public void onDestroy() {
        try {
            final SipDelegateClient client =
                    SipDelegateClient.getInstance(getApplicationContext());
            client.setStatusListener(null);
            client.setInboundListener(null);
        } catch (final Throwable ignore) {
        }
        try {
            ShannonRcsConfigTrigger.getInstance(getApplicationContext())
                    .setConfigListener(null);
        } catch (final Throwable ignore) {
        }
        if (mThread != null) {
            mThread.quitSafely();
        }
        super.onDestroy();
    }

    // Control, main to :ims.

    private final class ControlHandler extends Handler {
        ControlHandler(final android.os.Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(final Message msg) {
            final Bundle data = msg.getData();
            if (data != null) {
                data.setClassLoader(getClass().getClassLoader());
            }
            switch (msg.what) {
                case CarrierImsSeam.MSG_REGISTER_EVENTS:
                    mEvents = msg.replyTo;
                    LogUtil.i(TAG, SUBTAG + ": events channel registered");
                    break;
                case CarrierImsSeam.MSG_START_FOR_SUB:
                    handleStart(data);
                    break;
                case CarrierImsSeam.MSG_STOP_FOR_SUB:
                    handleStop(data);
                    break;
                case CarrierImsSeam.MSG_SEND_MESSAGE:
                    handleSend(data);
                    break;
                case CarrierImsSeam.MSG_WARM_PEER:
                    handleWarm(data);
                    break;
                case CarrierImsSeam.MSG_SEND_IMDN:
                    handleSendImdn(data);
                    break;
                case CarrierImsSeam.MSG_SEND_TYPING:
                    handleSendTyping(data);
                    break;
                case CarrierImsSeam.MSG_SUBMIT_OTP:
                    // No OTP on this transport.
                    LogUtil.i(TAG, SUBTAG + ": submitOtp ignored (no OTP path for carrier IMS)");
                    break;
                case CarrierImsSeam.MSG_REJECT_FILE:
                    handleRejectFile(data);
                    break;
                case CarrierImsSeam.MSG_DEBUG_FT_UPLOAD:
                    handleDebugFtUpload(data);
                    break;
                case CarrierImsSeam.MSG_DEBUG_PLAIN_MESSAGE:
                    handleDebugPlain(data);
                    break;
                case CarrierImsSeam.MSG_SEND_MLS:
                    handleSendMls(data);
                    break;
                default:
                    LogUtil.w(TAG, SUBTAG + ": unknown control what=" + msg.what);
            }
        }
    }

    private void handleStart(@Nullable final Bundle data) {
        if (data == null) {
            return;
        }
        final RcsSubInfo sub = data.getParcelable(CarrierImsSeam.KEY_SUB_INFO);
        if (sub == null) {
            return;
        }
        mSubId = sub.subId;
        mMsisdn = sub.msisdn;
        mMccMnc = sub.mccMnc;
        mResolvedFrom = null;
        mProbeAttempts = 0;
        mProbeGeneration++;   // supersede any pending retry
        // Watch the pulled configuration for a carrier terms gate; replays the last one.
        try {
            ShannonRcsConfigTrigger.getInstance(getApplicationContext())
                    .setConfigListener(configXml -> onRcsConfig(sub.subId, configXml));
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": setConfigListener failed", t);
        }
        // Repeating a start is safe: delegate creation is idempotent in SipDelegateClient.
        mWorker.post(() -> driveStart(sub));
    }

    private void driveStart(final RcsSubInfo sub) {
        emitProv(sub.subId, PROV_IN_PROGRESS, null);
        final CarrierImsMode mode = CarrierImsMode.probe(getApplicationContext(), sub.subId);
        LogUtil.i(TAG, SUBTAG + ": startForSub sub=" + sub.subId + " mode=" + mode
                + " probeAttempt=" + mProbeAttempts);
        switch (mode) {
            case SR:
                mMode = mode;
                startSr(sub.subId);
                break;
            case DR:
                mMode = mode;
                startDr(sub);
                break;
            case UNKNOWN:
            default:
                // The ImsService is not up yet: retryable, not ineligible. Keep the last known
                // mode.
                scheduleProbeRetry(sub);
                break;
        }
    }

    private void scheduleProbeRetry(final RcsSubInfo sub) {
        if (mProbeAttempts >= MAX_PROBE_ATTEMPTS) {
            LogUtil.w(TAG, SUBTAG + ": IMS not ready after " + mProbeAttempts
                    + " probes for sub=" + sub.subId + " -> terminal REG_FAILED (SMS fallback)");
            emitReg(sub.subId, REG_FAILED,
                    "IMS not ready after " + mProbeAttempts + " probes");
            return;
        }
        final long delay = PROBE_BACKOFF_MS[Math.min(mProbeAttempts, PROBE_BACKOFF_MS.length - 1)];
        mProbeAttempts++;
        emitReg(sub.subId, REG_REGISTERING,
                "IMS not ready; re-probe " + mProbeAttempts + "/" + MAX_PROBE_ATTEMPTS
                        + " in " + delay + "ms");
        // Only if still serving this subscription and no start or stop has intervened.
        final int gen = mProbeGeneration;
        mWorker.postDelayed(() -> {
            if (mProbeGeneration == gen && mSubId == sub.subId) {
                driveStart(sub);
            }
        }, delay);
    }

    /**
     * Pulls the modem's RCS configuration, requests the CPM delegate, and maps grants to events.
     */
    private void startSr(final int subId) {
        final SipDelegateClient client = SipDelegateClient.getInstance(getApplicationContext());
        client.setStatusListener(new SipDelegateClient.StatusListener() {
            @Override
            public void onTagsChanged(final boolean sessionGranted, final boolean msgGranted) {
                if (sessionGranted) {
                    // Silent provisioning: the registration rides the modem's.
                    emitReg(subId, REG_REGISTERED, null);
                    emitProv(subId, PROV_CONFIGURED, buildCaps());
                } else {
                    emitReg(subId, REG_REGISTERING, "awaiting oma.cpm.session grant");
                }
            }

            @Override
            public void onDelegateDestroyed(final int reason) {
                emitReg(subId, REG_UNREGISTERED, "delegate destroyed reason=" + reason);
            }
        });
        // Inbound session texts, answered by SipDelegateClient.
        client.setInboundListener((fromE164, body, messageId) ->
                emitIncoming(subId, fromE164, body, messageId));
        try {
            ShannonRcsConfigTrigger.getInstance(getApplicationContext()).trigger();
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": ShannonRcsConfigTrigger.trigger failed", t);
        }
        // The grant arrives on the StatusListener.
        try {
            client.create();
            emitReg(subId, REG_REGISTERING, "SipDelegate requested");
        } catch (final Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": SipDelegate create failed", t);
            emitReg(subId, REG_FAILED, "createSipDelegate failed: " + t.getMessage());
            return;
        }
        // A modem may register IMS before the RCS configuration is provisioned and not register
        // again afterwards, leaving the tag granted but not on the network. Force re-registration
        // until it is.
        scheduleReregisterKick(subId, client, 0);
    }

    /** Between re-registration requests; longer than a configuration pull. */
    private static final long REREGISTER_KICK_MS = 6000L;
    /** Stop after this many, so the modem's registration is not churned indefinitely. */
    private static final int MAX_REREGISTER_KICKS = 5;

    /**
     * While {@code oma.cpm.session} is not registered on the network, requests a full
     * re-registration and checks again after {@link #REREGISTER_KICK_MS}; stops once registered,
     * when superseded, or after {@link #MAX_REREGISTER_KICKS}.
     */
    private void scheduleReregisterKick(final int subId, final SipDelegateClient client,
            final int attempt) {
        final int gen = mProbeGeneration;
        mWorker.postDelayed(() -> {
            if (mProbeGeneration != gen || mSubId != subId) {
                return;  // superseded
            }
            if (client.isSessionTagGranted()) {
                return;  // onTagsChanged has reported it
            }
            if (attempt >= MAX_REREGISTER_KICKS) {
                LogUtil.w(TAG, SUBTAG + ": oma.cpm.session still not wire-registered after "
                        + MAX_REREGISTER_KICKS + " re-REGISTER kicks; giving up (RCS leg"
                        + " may need modem/provisioning attention)");
                return;
            }
            LogUtil.i(TAG, SUBTAG + ": re-REGISTER kick " + (attempt + 1) + "/"
                    + MAX_REREGISTER_KICKS + " — triggerFullNetworkRegistration to publish"
                    + " oma.cpm.session on the wire");
            // SIP code 0: not caused by a SIP failure.
            client.triggerReregister(0, "rcs-provisioned-reregister");
            scheduleReregisterKick(subId, client, attempt + 1);
        }, REREGISTER_KICK_MS);
    }

    /** Pulls the configuration through the modem for the DR driver, then starts it. */
    private void startDr(final RcsSubInfo sub) {
        // The modem only fetches the configuration; DR registers on its own over the IMS bearer.
        try {
            ShannonRcsConfigTrigger.getInstance(getApplicationContext())
                    .setConfigListener(configXml -> onRcsConfig(sub.subId, configXml));
            ShannonRcsConfigTrigger.getInstance(getApplicationContext()).trigger();
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": DR ACS config trigger failed", t);
        }
        mDrDriver.startForSub(sub.subId, sub.msisdn, sub.mccMnc,
                new CarrierDrModeDriver.Listener() {
            @Override
            public void onRegistrationState(final int subId, final int state,
                    @Nullable final String reason) {
                emitReg(subId, state, reason);
            }

            @Override
            public void onProvisioningState(final int subId, final int state) {
                emitProv(subId, state, state == PROV_CONFIGURED ? buildCaps() : null);
            }

            @Override
            public void onIncomingText(final int subId, final String fromE164,
                    final String body, final String messageId) {
                emitIncoming(subId, fromE164, body, messageId, null);
            }

            @Override
            public void onIncomingText(final int subId, final String fromE164,
                    final String body, final String messageId, final String e2eeSchemeId) {
                emitIncoming(subId, fromE164, body, messageId, e2eeSchemeId);
            }

            @Override
            public void onIncomingContent(final int subId, final String fromE164,
                    final byte[] body, final String contentType, final String messageId,
                    final String e2eeSchemeId) {
                emitIncoming(subId, fromE164, body, contentType, messageId, e2eeSchemeId);
            }

            @Override
            public void onMessageStatus(final int subId, final String messageId,
                    final int status, @Nullable final String reason) {
                emitStatus(subId, messageId, status, reason);
            }
        });
    }

    private void handleStop(@Nullable final Bundle data) {
        final int subId = data != null ? data.getInt(CarrierImsSeam.KEY_SUB_ID, mSubId) : mSubId;
        // Cancel any pending retry and forget the subscription before the teardown runs.
        mProbeGeneration++;
        mSubId = -1;
        mWorker.post(() -> {
            if (mMode == CarrierImsMode.DR) {
                mDrDriver.stopForSub(subId);
            } else {
                try {
                    SipDelegateClient.getInstance(getApplicationContext()).destroy();
                } catch (final Throwable t) {
                    LogUtil.w(TAG, SUBTAG + ": destroy failed", t);
                }
            }
            emitReg(subId, REG_UNREGISTERED, "stopForSub");
        });
    }

    private void handleSend(@Nullable final Bundle data) {
        if (data == null) {
            return;
        }
        final RcsOutgoingMessage msg = data.getParcelable(CarrierImsSeam.KEY_OUT_MSG);
        if (msg == null) {
            return;
        }
        mWorker.post(() -> driveSend(msg));
    }

    private void driveSend(final RcsOutgoingMessage msg) {
        final String from = resolveFromTel(msg.subId);
        final String to = msg.toUri;
        // The body is decoded as UTF-8 whatever its content type.
        final String body = msg.body != null ? new String(msg.body, StandardCharsets.UTF_8) : "";
        if (TextUtils.isEmpty(from) || TextUtils.isEmpty(to)) {
            emitStatus(msg.subId, msg.messageId, STATUS_FAILED, "missing from/to");
            return;
        }
        if (mMode == CarrierImsMode.DR) {
            // Accepted only means dispatched; the terminal status arrives through onMessageStatus.
            // Report FAILED at once only when the driver refuses, so the caller falls back quickly.
            final boolean accepted = mDrDriver.sendText(msg.subId, from, to, msg.messageId, body);
            if (!accepted) {
                emitStatus(msg.subId, msg.messageId, STATUS_FAILED, "DR send rejected");
            }
            return;
        }
        // Reuse the held session, establishing it first if needed. Warm and cold are reported with
        // the raw toUri so they match the facade's key.
        final String toTel = telOnly(to);
        try {
            SipDelegateClient.getInstance(getApplicationContext())
                    .sendOnSession(from, toTel, body, new SipDelegateClient.SessionSendCallback() {
                        @Override
                        public void onSessionEstablished(final String peer) {
                            emitSessionWarm(msg.subId, to);
                        }

                        @Override
                        public void onSessionClosed(final String peer) {
                            emitSessionCold(msg.subId, to);
                        }

                        @Override
                        public void onSendComplete(final CpmSessionEngine.Stage terminal) {
                            final int status = mapStageToStatus(terminal);
                            emitStatus(msg.subId, msg.messageId, status,
                                    status == STATUS_FAILED
                                            ? "SR send terminal stage=" + terminal : null);
                        }
                    });
        } catch (final Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": SR send failed", t);
            emitStatus(msg.subId, msg.messageId, STATUS_FAILED, t.getMessage());
        }
    }

    /**
     * SENT needs the MSRP 200 and DELIVERED the peer's MSRP report; anything less is FAILED so
     * the caller can fall back.
     */
    private int mapStageToStatus(final CpmSessionEngine.Stage stage) {
        if (stage == null) {
            return STATUS_FAILED;
        }
        switch (stage) {
            case DELIVERED:
                return STATUS_DELIVERED;
            case MSRP_ACKED:
                return STATUS_SENT;
            default:
                return STATUS_FAILED;
        }
    }

    private void handleWarm(@Nullable final Bundle data) {
        if (data == null) {
            return;
        }
        final int subId = data.getInt(CarrierImsSeam.KEY_SUB_ID, mSubId);
        final String toUri = data.getString(CarrierImsSeam.KEY_TO_URI);
        mWorker.post(() -> {
            if (mMode == CarrierImsMode.DR) {
                if (mDrDriver.isRegistered(subId)) {
                    emitSessionWarm(subId, toUri);
                }
                return;
            }
            // Establish and hold a session now. EVT_SESSION_WARM and _COLD come from the session's
            // real establishment and teardown, and a later send reuses it.
            final String from = resolveFromTel(subId);
            final String toTel = telOnly(toUri);
            if (TextUtils.isEmpty(from) || TextUtils.isEmpty(toTel)) {
                LogUtil.w(TAG, SUBTAG + ": warmPeer sub=" + subId + " missing from/to"
                        + " — cannot pre-establish held session");
                return;
            }
            LogUtil.i(TAG, SUBTAG + ": warmPeer sub=" + subId + " to=" + LogMask.number(toUri)
                    + " — establishing held MSRP session (EVT_SESSION_WARM on connect)");
            SipDelegateClient.getInstance(getApplicationContext())
                    .warmSession(from, toTel, new SipDelegateClient.SessionSendCallback() {
                        @Override
                        public void onSessionEstablished(final String peer) {
                            // The raw toUri, to match the facade's key.
                            emitSessionWarm(subId, toUri);
                        }

                        @Override
                        public void onSessionClosed(final String peer) {
                            emitSessionCold(subId, toUri);
                        }

                        @Override
                        public void onSendComplete(final CpmSessionEngine.Stage terminal) {
                            // nothing was sent
                        }
                    });
        });
    }

    private void handleSendImdn(@Nullable final Bundle data) {
        if (data == null) {
            return;
        }
        final int subId = data.getInt(CarrierImsSeam.KEY_SUB_ID, mSubId);
        final String toUri = telOnly(data.getString(CarrierImsSeam.KEY_TO_URI));
        final String messageId = data.getString(CarrierImsSeam.KEY_MESSAGE_ID);
        final int imdnType = data.getInt(CarrierImsSeam.KEY_IMDN_TYPE, 0);
        mWorker.post(() -> {
            final String from = resolveFromTel(subId);
            if (TextUtils.isEmpty(toUri) || messageId == null) {
                LogUtil.w(TAG, SUBTAG + ": sendImdn missing to/id — dropped");
                return;
            }
            if (mMode == CarrierImsMode.DR) {
                // Display receipts; delivered receipts are sent automatically by the receiver.
                mDrDriver.sendImdn(subId, from, toUri, messageId, imdnType);
                return;
            }
            if (TextUtils.isEmpty(from)) {
                LogUtil.w(TAG, SUBTAG + ": sendImdn missing from — dropped");
                return;
            }
            SipDelegateClient.getInstance(getApplicationContext())
                    .sendImdn(from, toUri, messageId, imdnType);
        });
    }

    private void handleSendTyping(@Nullable final Bundle data) {
        if (data == null) {
            return;
        }
        final int subId = data.getInt(CarrierImsSeam.KEY_SUB_ID, mSubId);
        final String toUri = telOnly(data.getString(CarrierImsSeam.KEY_TO_URI));
        final boolean active = data.getBoolean(CarrierImsSeam.KEY_TYPING_ACTIVE, false);
        mWorker.post(() -> {
            final String from = resolveFromTel(subId);
            if (TextUtils.isEmpty(toUri)) {
                return; // best effort
            }
            if (mMode == CarrierImsMode.DR) {
                mDrDriver.sendTyping(subId, from, toUri, active);
                return;
            }
            if (TextUtils.isEmpty(from)) {
                return;
            }
            SipDelegateClient.getInstance(getApplicationContext())
                    .sendTyping(from, toUri, active);
        });
    }

    private void handleDebugFtUpload(@Nullable final Bundle data) {
        if (data == null) {
            return;
        }
        final int subId = data.getInt(CarrierImsSeam.KEY_SUB_ID, mSubId);
        final String path = data.getString(CarrierImsSeam.KEY_FT_PATH);
        final String toUri = telOnly(data.getString(CarrierImsSeam.KEY_TO_URI));
        if (TextUtils.isEmpty(path)) {
            LogUtil.w(TAG, SUBTAG + ": debugFtUpload missing path");
            return;
        }
        // blocks on HTTP
        mWorker.post(() -> mDrDriver.debugFtUpload(subId, path, toUri));
    }

    private void handleDebugPlain(@Nullable final Bundle data) {
        if (data == null) {
            return;
        }
        final int subId = data.getInt(CarrierImsSeam.KEY_SUB_ID, mSubId);
        final String toUri = telOnly(data.getString(CarrierImsSeam.KEY_TO_URI));
        final String text = data.getString(CarrierImsSeam.KEY_PLAIN_TEXT);
        final String mid = data.getString(CarrierImsSeam.KEY_MESSAGE_ID);
        final String from = resolveFromTel(subId);
        if (TextUtils.isEmpty(toUri) || TextUtils.isEmpty(text)) {
            LogUtil.w(TAG, SUBTAG + ": debugPlain missing to/text");
            return;
        }
        mWorker.post(() -> mDrDriver.sendPlainDebug(subId, from, toUri, text, mid));
    }

    private void handleSendMls(@Nullable final Bundle data) {
        if (data == null) {
            return;
        }
        final int subId = data.getInt(CarrierImsSeam.KEY_SUB_ID, mSubId);
        final RcsOutgoingMessage out = data.getParcelable(CarrierImsSeam.KEY_OUT_MSG);
        if (out == null) {
            LogUtil.w(TAG, SUBTAG + ": sendMls missing KEY_OUT_MSG");
            return;
        }
        final String toUri = telOnly(out.toUri);
        final byte[] framedBody = out.body;
        final String mid = out.messageId;
        final String from = resolveFromTel(subId);
        // Refuse rather than fall back to text: an unframed body would be unreadable to peers.
        if (TextUtils.isEmpty(toUri) || framedBody == null || framedBody.length == 0) {
            LogUtil.w(TAG, SUBTAG + ": sendMls missing to/body (toUri="
                    + (TextUtils.isEmpty(toUri) ? "absent" : "present") + " body="
                    + (framedBody == null ? "null" : framedBody.length + "B") + ")");
            return;
        }
        mWorker.post(() -> mDrDriver.sendMls(subId, from, toUri, framedBody, mid));
    }

    private void handleRejectFile(@Nullable final Bundle data) {
        if (data == null) {
            return;
        }
        final String messageId = data.getString(CarrierImsSeam.KEY_MESSAGE_ID);
        mWorker.post(() -> {
            if (mMode == CarrierImsMode.DR) {
                LogUtil.i(TAG, SUBTAG + ": rejectIncomingFile ignored on DR mode");
                return;
            }
            // SIP 603 for a pending inbound file; a no-op until file INVITEs are routed.
            SipDelegateClient.getInstance(getApplicationContext())
                    .rejectIncomingFile(messageId);
        });
    }

    /**
     * The self {@code +E164}, cached: {@link SubscriptionManager#getPhoneNumber}, then the IMS
     * P-Associated-URI, then the start's msisdn, then {@code debug.rcs.dr.user} on a debuggable
     * build.
     */
    @Nullable
    private String resolveFromTel(final int subId) {
        final String cached = mResolvedFrom;
        if (!TextUtils.isEmpty(cached)) {
            return cached;
        }
        try {
            final SubscriptionManager sm = getSystemService(SubscriptionManager.class);
            if (sm != null && SubscriptionManager.isValidSubscriptionId(subId)) {
                final String num = sm.getPhoneNumber(subId);
                if (!TextUtils.isEmpty(num)) {
                    final String e164 = num.startsWith("+") ? num : "+"
                            + num.replaceAll("[^0-9]", "");
                    mResolvedFrom = e164;
                    LogUtil.i(TAG, SUBTAG + ": resolved self number via SubscriptionManager");
                    return e164;
                }
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": getPhoneNumber failed", t);
        }
        try {
            final String ims = SipDelegateClient.getInstance(getApplicationContext())
                    .getRegisteredSelfNumber();
            if (!TextUtils.isEmpty(ims)) {
                mResolvedFrom = ims;
                LogUtil.i(TAG, SUBTAG + ": resolved self number via IMS P-Associated-URI");
                return ims;
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": IMS self-number resolve failed", t);
        }
        if (!TextUtils.isEmpty(mMsisdn)) {
            mResolvedFrom = mMsisdn;
            return mMsisdn;
        }
        if (RcsDebug.isDebugBuild()) {
            final String dbgUser = SystemProperties.get("debug.rcs.dr.user", "");
            if (!TextUtils.isEmpty(dbgUser)) {
                final String e164 = dbgUser.startsWith("+")
                        ? dbgUser : "+" + dbgUser.replaceAll("[^0-9]", "");
                mResolvedFrom = e164;
                LogUtil.i(TAG, SUBTAG + ": resolved self number via debug.rcs.dr.user");
                return e164;
            }
        }
        LogUtil.w(TAG, SUBTAG + ": no self number for sub=" + subId
                + " (SubscriptionManager + IMS + msisdn all empty)");
        return null;
    }

    /** A bare {@code +E164} from a {@code tel:} or {@code sip:} URI. */
    @Nullable
    private static String telOnly(@Nullable final String uri) {
        if (uri == null) {
            return null;
        }
        String s = uri.trim();
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

    /**
     * Looks for a carrier terms gate, a {@code characteristic} of type {@code MSG} or a
     * terms element, and emits TOS_REQUIRED with a {@link RcsTosPrompt}, or TOS_NONE once it
     * is gone. Also hands the document to the DR driver.
     */
    private void onRcsConfig(final int subId, @Nullable final byte[] configXml) {
        if (configXml == null || configXml.length == 0) {
            return;
        }
        if (mDrDriver != null) {
            mDrDriver.setAcsConfig(configXml);
        }
        final String xml = new String(configXml, StandardCharsets.UTF_8);
        final String lower = xml.toLowerCase(java.util.Locale.ROOT);
        final boolean hasMsgGate = lower.contains("type=\"msg\"")
                || lower.contains("type='msg'") || lower.contains("<terms");
        if (!hasMsgGate) {
            if (mLastTosState != TOS_NONE) {
                mLastTosState = TOS_NONE;
                emitTos(subId, TOS_NONE, null);
            }
            return;
        }
        final String title = extractAcsValue(xml, "title");
        final String message = extractAcsValue(xml, "message");
        final String acceptLabel = firstNonNull(
                extractAcsValue(xml, "Accept_Btn"), extractAcsValue(xml, "accept"));
        final String rejectLabel = firstNonNull(
                extractAcsValue(xml, "Reject_Btn"), extractAcsValue(xml, "reject"));
        final RcsTosPrompt prompt = new RcsTosPrompt(
                subId,
                RcsTosPrompt.KIND_CARRIER_TOS,
                title,
                message,
                /* hasAccept= */ true,
                /* hasReject= */ rejectLabel != null,
                acceptLabel,
                rejectLabel,
                /* tosUrl= */ null); // a carrier prompt has no URL
        if (mLastTosState != TOS_REQUIRED) {
            mLastTosState = TOS_REQUIRED;
            LogUtil.i(TAG, SUBTAG + ": carrier <MSG> T&C gate present -> EVT_TOS_STATE(REQUIRED)");
            emitTos(subId, TOS_REQUIRED, prompt);
        }
    }

    /** A value in {@code <parm name="X" value="Y"/>} or {@code <X>Y</X>} form, case-insensitive. */
    @Nullable
    private static String extractAcsValue(final String xml, final String name) {
        final java.util.regex.Matcher parm = java.util.regex.Pattern.compile(
                "name=\"" + java.util.regex.Pattern.quote(name) + "\"\\s+value=\"([^\"]*)\"",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(xml);
        if (parm.find()) {
            return parm.group(1);
        }
        final java.util.regex.Matcher el = java.util.regex.Pattern.compile(
                "<" + java.util.regex.Pattern.quote(name) + ">([^<]*)</"
                        + java.util.regex.Pattern.quote(name) + ">",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(xml);
        if (el.find()) {
            return el.group(1);
        }
        return null;
    }

    @Nullable
    private static String firstNonNull(@Nullable final String a, @Nullable final String b) {
        return a != null ? a : b;
    }

    /** Caps published on PROV_CONFIGURED. */
    private RcsProviderCaps buildCaps() {
        return new RcsProviderCaps(
                "Carrier IMS (SIP/MSRP) RCS",
                CarrierImsTransport.CONTRACT_VERSION,
                new int[] { RcsProviderCaps.TRANSPORT_CARRIER_MSRP },
                /* canSelfProvision= */ true,
                /* priority (unused in per-sub caps) */ 0,
                RcsProviderCaps.FEATURE_TEXT
                        | RcsProviderCaps.FEATURE_IMDN
                        | RcsProviderCaps.FEATURE_TYPING);
    }

    // Events, :ims to main.

    private void emitProv(final int subId, final int state, @Nullable final RcsProviderCaps caps) {
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        b.putInt(CarrierImsSeam.KEY_STATE, state);
        if (caps != null) {
            b.putParcelable(CarrierImsSeam.KEY_CAPS, caps);
        }
        post(CarrierImsSeam.EVT_PROV_STATE, b);
    }

    private void emitReg(final int subId, final int state, @Nullable final String reason) {
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        b.putInt(CarrierImsSeam.KEY_STATE, state);
        if (reason != null) {
            b.putString(CarrierImsSeam.KEY_REASON, reason);
        }
        post(CarrierImsSeam.EVT_REG_STATE, b);
    }

    private void emitIncoming(final int subId, @Nullable final String fromE164,
            final String body, final String messageId) {
        emitIncoming(subId, fromE164, body, messageId, null);
    }

    /** For a body that is text. Anything that can be binary uses the byte overload. */
    private void emitIncoming(final int subId, @Nullable final String fromE164,
            final String body, final String messageId, @Nullable final String e2eeSchemeId) {
        emitIncoming(subId, fromE164,
                body != null ? body.getBytes(StandardCharsets.UTF_8) : null,
                "text/plain;charset=UTF-8", messageId, e2eeSchemeId);
    }

    private void emitIncoming(final int subId, @Nullable final String fromE164,
            @Nullable final byte[] body, final String contentType, final String messageId,
            @Nullable final String e2eeSchemeId) {
        // fromUri must not be null.
        final String from = !TextUtils.isEmpty(fromE164) ? fromE164 : "sip:anonymous@unknown";
        // Bodies arrive already unframed with their real type; do not parse again, since an
        // unframed image has no frame either and would be relabelled as text. e2eeSchemeId marks a
        // body decrypted from MLS.
        final RcsIncomingMessage in = new RcsIncomingMessage(
                subId,
                messageId,
                from,
                contentType,
                body,
                /* serverTimestampUsec= */ 0L,
                /* wantsDeliveredImdn= */ true,
                /* wantsDisplayedImdn= */ true,
                /* groupId= */ null,
                /* e2eeSchemeId= */ e2eeSchemeId);
        final Bundle b = new Bundle();
        b.putParcelable(CarrierImsSeam.KEY_IN_MSG, in);
        post(CarrierImsSeam.EVT_INCOMING_MESSAGE, b);
    }

    private void emitStatus(final int subId, final String messageId, final int status,
            @Nullable final String reason) {
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        b.putString(CarrierImsSeam.KEY_MESSAGE_ID, messageId);
        b.putInt(CarrierImsSeam.KEY_STATUS, status);
        if (reason != null) {
            b.putString(CarrierImsSeam.KEY_REASON, reason);
        }
        post(CarrierImsSeam.EVT_MESSAGE_STATUS, b);
    }

    private void emitSessionWarm(final int subId, @Nullable final String toUri) {
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        b.putString(CarrierImsSeam.KEY_TO_URI, toUri);
        post(CarrierImsSeam.EVT_SESSION_WARM, b);
    }

    /** Mirrors {@link #emitSessionWarm}. */
    private void emitSessionCold(final int subId, @Nullable final String toUri) {
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        b.putString(CarrierImsSeam.KEY_TO_URI, toUri);
        post(CarrierImsSeam.EVT_SESSION_COLD, b);
    }

    private void emitTos(final int subId, final int state, @Nullable final RcsTosPrompt prompt) {
        final Bundle b = new Bundle();
        b.putInt(CarrierImsSeam.KEY_SUB_ID, subId);
        b.putInt(CarrierImsSeam.KEY_STATE, state);
        if (prompt != null) {
            b.putParcelable(CarrierImsSeam.KEY_TOS_PROMPT, prompt);
        }
        post(CarrierImsSeam.EVT_TOS_STATE, b);
    }

    private void post(final int what, final Bundle data) {
        final Messenger events = mEvents;
        if (events == null) {
            LogUtil.w(TAG, SUBTAG + ": no events channel; dropping what=" + what);
            return;
        }
        final Message m = Message.obtain(null, what);
        m.setData(data);
        try {
            events.send(m);
        } catch (final RemoteException e) {
            LogUtil.w(TAG, SUBTAG + ": event send failed what=" + what, e);
            mEvents = null;
        }
    }
}

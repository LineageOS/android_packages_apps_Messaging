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

import android.app.Service;
import android.content.Intent;
import android.os.Build;
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

import com.android.messaging.rcs.sip.CpmIncomingSessionEngine;
import com.android.messaging.rcs.sip.CpmSessionEngine;
import com.android.messaging.rcs.sip.ShannonRcsConfigTrigger;
import com.android.messaging.rcs.sip.SipDelegateClient;
import com.android.messaging.util.LogUtil;

import java.nio.charset.StandardCharsets;

/**
 * The isolated {@code :ims}-process host for the carrier-IMS SIP/MSRP engine
 * (design §6.4). Declared {@code android:process=":ims"} in the manifest so a
 * JAIN-SIP/MSRP or {@code SipDelegate} fault is contained and doesn't take down
 * the UI. SMS-role + {@code PERFORM_IMS_SINGLE_REGISTRATION} are per-UID, so this
 * process still holds them (design §6.1).
 *
 * <p>It is bound by the main-process {@link CarrierImsTransport} facade and speaks
 * the {@link CarrierImsSeam} Messenger protocol: control in, events out. It owns
 * the SR path ({@link SipDelegateClient} + {@link ShannonRcsConfigTrigger}) and
 * the DR fallback ({@link CarrierDrModeDriver}); the per-sub {@link
 * CarrierImsMode#probe} picks between them (design §6.3).
 *
 * <p>Provisioning mapping (design §7): silent -&gt; CONFIGURED; no OTP; the carrier
 * autoconfig {@code <MSG>} T&amp;C gate (KIND_CARRIER_TOS) is parsed from the pulled
 * RCS config in {@link #onRcsConfig} and surfaced as EVT_TOS_STATE. On a granted
 * {@code oma.cpm.session} tag it emits REG_REGISTERED + PROV_CONFIGURED; on delegate
 * teardown, REG_UNREGISTERED.
 */
public final class CarrierImsService extends Service {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CarrierImsService";

    // IRcsProviderCallback state constants, referenced without importing the
    // whole surface into the seam. (These mirror the AIDL; kept local for clarity.)
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

    /** C7 — bounded re-probe when the SR/DR probe is UNKNOWN (ImsService not up
     *  yet) so a sub is never permanently wedged. Backoff schedule in ms; the
     *  final entry repeats until MAX_PROBE_ATTEMPTS is hit, then we surface a
     *  terminal REG_FAILED (RouteSelector falls the sub to SMS; a selection
     *  trigger — design §5.4 — re-drives startForSub later). */
    private static final long[] PROBE_BACKOFF_MS = { 2000, 5000, 10000, 20000, 30000 };
    private static final int MAX_PROBE_ATTEMPTS = 6;
    private int mProbeAttempts;
    /** Bumped on every start/stop so an in-flight {@link #scheduleProbeRetry}
     *  delayed re-drive from a superseded/stopped sub is cancelled (review LOW-2).
     *  Touched only on the single worker/control looper thread. */
    private int mProbeGeneration;

    /** Dedicated worker so the probe / delegate create / session send never run
     *  on the binder dispatch thread. */
    private HandlerThread mThread;
    private Handler mWorker;
    private Messenger mControlMessenger;

    /** The main-process events sink (set by MSG_REGISTER_EVENTS). */
    @Nullable private volatile Messenger mEvents;

    /** Latest sub we were told to serve (for from-address resolution + status routing). */
    private volatile int mSubId = -1;
    @Nullable private volatile String mMsisdn;
    @Nullable private volatile String mMccMnc;
    /** C6 — authoritatively resolved self {@code +E164} (SubscriptionManager /
     *  IMS P-Associated-URI), cached across sends. */
    @Nullable private volatile String mResolvedFrom;
    private volatile CarrierImsMode mMode = CarrierImsMode.UNKNOWN;
    /** Last carrier-ToS state emitted, to suppress duplicate EVT_TOS_STATE. */
    private volatile int mLastTosState = TOS_NONE;

    /** DR fallback driver. The REAL driver ({@link CarrierStackDrModeDriver}) is
     *  now on the classpath; it self-falls
     *  to REG_FAILED when the DR SIP config isn't available (RIG-VERIFY inside the
     *  driver), so no NoOp is needed. Initialized in {@link #onCreate} once the
     *  app context is live. */
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

    // ---- control: main -> :ims ----

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
                    // design §7: carrier IMS has no OTP path. Intentional no-op.
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
        mResolvedFrom = null; // re-resolve for the (possibly new) sub.
        mProbeAttempts = 0;   // fresh start resets the C7 re-probe budget.
        mProbeGeneration++;   // supersede any pending probe-retry from a prior sub.
        // Register the carrier T&C (<MSG>) observer so a ToS gate in the pulled
        // RCS config surfaces as EVT_TOS_STATE (spec §7). Idempotent (single
        // listener slot); replays the last config if one already arrived.
        try {
            ShannonRcsConfigTrigger.getInstance(getApplicationContext())
                    .setConfigListener(configXml -> onRcsConfig(sub.subId, configXml));
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": setConfigListener failed", t);
        }
        // Debounce is provided upstream at the RcsTransport call cadence; the
        // heavy work (probe + delegate create) is idempotent within SipDelegateClient
        // (create() no-ops if a delegate is already held), so a repeated start is safe.
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
                // C7: the ImsService isn't up for this sub yet — a transient,
                // retryable state, NOT proof of ineligibility. Re-probe with
                // bounded backoff so the sub isn't wedged; emit progress each
                // round. Do NOT flip mMode (keep the last known mode).
                scheduleProbeRetry(sub);
                break;
        }
    }

    /** C7 — bounded re-probe with backoff when the probe returns UNKNOWN. */
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
        // Guard: only re-drive if this is still the sub we're serving AND no
        // start/stop has superseded this probe cycle since (review LOW-2).
        final int gen = mProbeGeneration;
        mWorker.postDelayed(() -> {
            if (mProbeGeneration == gen && mSubId == sub.subId) {
                driveStart(sub);
            }
        }, delay);
    }

    /** SR path (design §6.2): trigger the modem RCS config PULL + request the
     *  CPM SipDelegate; map its grant callbacks to REG/PROV events. */
    private void startSr(final int subId) {
        final SipDelegateClient client = SipDelegateClient.getInstance(getApplicationContext());
        client.setStatusListener(new SipDelegateClient.StatusListener() {
            @Override
            public void onTagsChanged(final boolean sessionGranted, final boolean msgGranted) {
                if (sessionGranted) {
                    // design §7: silent provisioning -> CONFIGURED (no OTP, no ToS
                    // in the common case). The registration rides the modem's IMS
                    // single registration, surfaced here as REG_REGISTERED.
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
        // SR INBOUND: a peer INVITE on our granted CPM tag -> RcsIncomingMessage
        // -> EVT_INCOMING_MESSAGE (spec §7.1; the SipDelegateClient answers the
        // INVITE, opens MSRP, and hands us the CPIM text here).
        client.setInboundListener((fromE164, body, messageId) ->
                emitIncoming(subId, fromE164, body, messageId));
        // 1) PULL, as Google Messages does: declare our client config + trigger modem RCS
        //    reconfiguration (rides ProvisioningManager).
        try {
            ShannonRcsConfigTrigger.getInstance(getApplicationContext()).trigger();
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": ShannonRcsConfigTrigger.trigger failed", t);
        }
        // 2) Request the CPM SipDelegate. The grant arrives on the StatusListener.
        try {
            client.create();
            emitReg(subId, REG_REGISTERING, "SipDelegate requested");
        } catch (final Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": SipDelegate create failed", t);
            emitReg(subId, REG_FAILED, "createSipDelegate failed: " + t.getMessage());
            return;
        }
        // 3) Force the RCS leg onto the wire. On g5123b (Exynos) the modem brings up
        //    the IMS single registration for MMTEL BEFORE our PULL provisions
        //    oma.cpm, and it does NOT spontaneously re-REGISTER to add the tag once
        //    provisioning completes: observed getRegisteredFeatureTags() stays empty,
        //    Shannon's RcsRegistrationImpl.getRegistrationState = UNSPECIFIED, and the
        //    delegate never receives a SipDelegateConfiguration (config=false). The
        //    delegate grant alone is app-level, not a wire registration. Once the PULL
        //    has settled (getProvisionedCapabilityBitmask flips to 0x2), a
        //    triggerFullNetworkRegistration makes the ImsService de-/re-REGISTER, and
        //    that fresh REGISTER carries the now-provisioned oma.cpm.session tag. The
        //    config round-trip (ACS pull / activation) is async, so kick a few times
        //    until the tag is actually wire-registered (onFeatureTagStatusChanged ->
        //    isSessionTagGranted()), then stop.
        scheduleReregisterKick(subId, client, 0);
    }

    /** ms between successive re-REGISTER kicks while awaiting the wire grant of
     *  {@code oma.cpm.session}. Comfortably longer than a PULL round-trip. */
    private static final long REREGISTER_KICK_MS = 6000L;
    /** Give up kicking after this many attempts (~30s) to avoid churning the modem
     *  IMS registration indefinitely if the RCS leg genuinely can't come up. */
    private static final int MAX_REREGISTER_KICKS = 5;

    /**
     * Poll-and-kick loop: if {@code oma.cpm.session} is not yet wire-registered,
     * fire {@code triggerFullNetworkRegistration} to make the modem re-REGISTER with
     * the provisioned tag, then re-check after {@link #REREGISTER_KICK_MS}. Stops as
     * soon as the tag registers, when superseded by a newer start/stop cycle, or
     * after {@link #MAX_REREGISTER_KICKS}.
     */
    private void scheduleReregisterKick(final int subId, final SipDelegateClient client,
            final int attempt) {
        final int gen = mProbeGeneration;
        mWorker.postDelayed(() -> {
            if (mProbeGeneration != gen || mSubId != subId) {
                return;  // a newer start/stop superseded this cycle
            }
            if (client.isSessionTagGranted()) {
                return;  // already on the wire — onTagsChanged emitted REG_REGISTERED
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
            // sipCode 0 = proactive/no-error re-registration (not driven by a SIP
            // failure); reason is advisory telemetry only.
            client.triggerReregister(0, "rcs-provisioned-reregister");
            scheduleReregisterKick(subId, client, attempt + 1);
        }, REREGISTER_KICK_MS);
    }

    /** DR path (design §6.3): drive the OpenRCSChat carrier stack. NoOp until the
     *  DR module is on the classpath -- see {@link CarrierDrModeDriver}. */
    private void startDr(final RcsSubInfo sub) {
        // Kick the modem's RCC.07 autoconfig fetch so the real ACS config flows to
        // the DR driver (onRcsConfig -> setAcsConfig -> configFor parses it). The DR
        // REGISTER itself is our own JAIN-SIP over the IMS APN, independent of the
        // modem's single registration — we just reuse the modem as the ACS fetcher.
        try {
            ShannonRcsConfigTrigger.getInstance(getApplicationContext())
                    .setConfigListener(configXml -> onRcsConfig(sub.subId, configXml));
            ShannonRcsConfigTrigger.getInstance(getApplicationContext()).trigger();
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": DR ACS config trigger failed", t);
        }
        mDrDriver.startForSub(sub.subId, sub.msisdn, sub.mccMnc, new CarrierDrModeDriver.Listener() {
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
        // LOW-2: cancel any pending C7 probe-retry (bump the generation) and clear
        // the served sub so a stopped sub can't be re-driven by an in-flight
        // scheduleProbeRetry. Done on the looper thread, before the teardown post.
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
        // The reference provider ignores contentType and decodes body as UTF-8
        // (spec §4.1); mirror that here.
        final String body = msg.body != null ? new String(msg.body, StandardCharsets.UTF_8) : "";
        if (TextUtils.isEmpty(from) || TextUtils.isEmpty(to)) {
            emitStatus(msg.subId, msg.messageId, STATUS_FAILED, "missing from/to");
            return;
        }
        if (mMode == CarrierImsMode.DR) {
            // LOW-5: sendText()'s boolean only means "accepted for dispatch" — the
            // REAL terminal status (SIP 200 / MSRP ack -> SENT, peer REPORT ->
            // DELIVERED, else FAILED) arrives ASYNCHRONOUSLY via
            // CarrierDrModeDriver.Listener.onMessageStatus -> emitStatus (mirrors
            // the SR C4 handling). So do NOT optimistically emit STATUS_SENT here;
            // only surface an immediate FAILED when the driver rejects outright so
            // the caller keeps SMS fast.
            // RIG-VERIFY(rcs-framework): confirm the DR stack always delivers a
            // terminal onMessageStatus for an accepted send on the live carrier.
            final boolean accepted = mDrDriver.sendText(msg.subId, from, to, msg.messageId, body);
            if (!accepted) {
                emitStatus(msg.subId, msg.messageId, STATUS_FAILED, "DR send rejected");
            }
            return;
        }
        // SR path: REUSE the held per-conversation MSRP session (design §9 keep-
        // warm; review HIGH-3) — an MSRP SEND on the already-open socket, no fresh
        // INVITE/BYE per message. If no session is held yet (never warmed, or
        // idle-torn since warm) sendOnSession lazily establishes one first.
        // C4: report the REAL terminal MSRP/SIP outcome via the per-send callback
        // (STATUS_SENT on MSRP-200/ack, STATUS_DELIVERED on the MSRP REPORT,
        // STATUS_FAILED otherwise). C3: warm/cold surface the REAL session
        // establish/teardown (emitted with the RAW toUri so the facade key matches).
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

    /** Map a CPM SESSION terminal stage to an IRcsProviderCallback.STATUS_* code
     *  (review C4). SENT requires a real MSRP 200 ack; DELIVERED requires the
     *  peer MSRP REPORT; anything short of an ack is a FAILED so the client can
     *  still recover to SMS. */
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
                // INVITE_SENT/PROVISIONAL/ANSWERED/ACKED/MSRP_CONNECTED/MSRP_SENT/
                // BYE_SENT/DONE/FAILED — the chunk was not positively ack'd.
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
            // SR (design §9 keep-warm; review HIGH-3): GENUINELY establish and HOLD
            // an MSRP session to the peer now — INVITE/200/ACK/MSRP-connect — and
            // keep it open. EVT_SESSION_WARM is emitted from the REAL
            // onSessionEstablished (media leg up), EVT_SESSION_COLD from the REAL
            // teardown (idle-timeout / peer BYE / delegate loss). A subsequent
            // sendMessage REUSES this held session (MSRP SEND on the open socket)
            // instead of a fresh INVITE...BYE per message. This is what populates
            // the facade's warm-peer set so pessimistic-cold sendMessage can ever
            // accept an SR send (the previously-dead INVITE/MSRP machinery).
            final String from = resolveFromTel(subId);
            final String toTel = telOnly(toUri);
            if (TextUtils.isEmpty(from) || TextUtils.isEmpty(toTel)) {
                LogUtil.w(TAG, SUBTAG + ": warmPeer sub=" + subId + " missing from/to"
                        + " — cannot pre-establish held session");
                return;
            }
            LogUtil.i(TAG, SUBTAG + ": warmPeer sub=" + subId + " to=" + toUri
                    + " — establishing held MSRP session (EVT_SESSION_WARM on connect)");
            SipDelegateClient.getInstance(getApplicationContext())
                    .warmSession(from, toTel, new SipDelegateClient.SessionSendCallback() {
                        @Override
                        public void onSessionEstablished(final String peer) {
                            // Emit with the RAW toUri so the facade's warm-peer key
                            // (subId|msg.toUri) matches on the next sendMessage.
                            emitSessionWarm(subId, toUri);
                        }

                        @Override
                        public void onSessionClosed(final String peer) {
                            emitSessionCold(subId, toUri);
                        }

                        @Override
                        public void onSendComplete(final CpmSessionEngine.Stage terminal) {
                            // No send issued for a pure warm; nothing to report.
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
                // DR: emit the IMDN (display/read) over the JAIN-SIP pager MESSAGE
                // path. Delivery IMDNs are auto-sent by the receiver; this carries
                // the explicit display report.
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
                return; // typing is best-effort; silently drop
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
        // Upload blocks (HTTP round-trip); run off the seam thread.
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
        // REFUSE rather than degrade. This used to read KEY_PLAIN_TEXT, so the seam
        // could carry nothing but UTF-8 text. Falling back to a text key when the body is absent
        // would resurrect exactly that, silently, on whichever caller forgot to frame — and an
        // unframed body on this leg is invisible until a third-party peer tries to read it.
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
            // SR: SIP 603 Decline on the pending inbound MSRP FT INVITE. Inbound
            // FT-INVITE routing itself is a RIG-VERIFY item in SipDelegateClient
            // (only 1-1 chat INVITEs are routed today), so this is a no-op until
            // that lands; kept wired so the contract path exists end-to-end.
            SipDelegateClient.getInstance(getApplicationContext())
                    .rejectIncomingFile(messageId);
        });
    }

    /**
     * Resolve the local {@code +E164} identity for outbound (review C6).
     * Precedence: cached resolution -> {@link SubscriptionManager#getPhoneNumber}
     * (privileged, authoritative in the SMS-role process) -> the IMS-registered
     * P-Associated-URI from the SipDelegate config -> the (often-null) msisdn
     * handed in via startForSub. Caches the first non-empty answer.
     *
     * RIG-VERIFY(rcs-framework): on a multi-line eSIM the correct
     * {@code getPhoneNumber} source-priority (SOURCE_CARRIER vs SOURCE_IMS vs
     * SOURCE_UICC) can vary; confirm the carrier populates a usable number on the
     * test rig. We take the default {@code getPhoneNumber(subId)} (framework
     * source-priority) then fall back to the IMS P-Associated-URI.
     */
    @Nullable
    private String resolveFromTel(final int subId) {
        final String cached = mResolvedFrom;
        if (!TextUtils.isEmpty(cached)) {
            return cached;
        }
        // 1) Privileged SubscriptionManager.getPhoneNumber(subId).
        try {
            final SubscriptionManager sm = getSystemService(SubscriptionManager.class);
            if (sm != null && SubscriptionManager.isValidSubscriptionId(subId)) {
                final String num = sm.getPhoneNumber(subId);
                if (!TextUtils.isEmpty(num)) {
                    final String e164 = num.startsWith("+") ? num : "+" + num.replaceAll("[^0-9]", "");
                    mResolvedFrom = e164;
                    LogUtil.i(TAG, SUBTAG + ": resolved self number via SubscriptionManager");
                    return e164;
                }
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": getPhoneNumber failed", t);
        }
        // 2) IMS-registered P-Associated-URI (authoritative once registered).
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
        // 3) The value handed in via startForSub (often null — spec §4.4).
        if (!TextUtils.isEmpty(mMsisdn)) {
            mResolvedFrom = mMsisdn;
            return mMsisdn;
        }
        // 4) DEBUG lab fallback: the DR self number from debug.rcs.dr.user (the same
        //    sysprop CarrierStackDrModeDriver.configFor uses) on a debuggable build,
        //    since the lab SIM's number often isn't readable via SubscriptionManager.
        if ("eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE)) {
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

    /** Strip a URI scheme/host down to a bare {@code +E164} the CPM builders
     *  expect ({@code tel:+1555} / {@code sip:+1555@dom} -> {@code +1555}). */
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
     * Parse the pulled RCS autoconfig for a carrier T&C ({@code <MSG>}) gate and
     * emit EVT_TOS_STATE (spec §7 — carrier IMS maps to a silent CONFIGURED,
     * possibly via WAITING_FOR_TOS). RCC.14 encodes the gate as a top-level
     * {@code <characteristic type="MSG">} carrying {@code title}/{@code message}/
     * {@code Accept_Btn}/{@code Reject_Btn}. We surface a {@link RcsTosPrompt}
     * (KIND_CARRIER_TOS) when present; otherwise stay silent (TOS_NONE), matching
     * the reference provider's "silent when no gate" behaviour.
     *
     * RIG-VERIFY(rcs-framework): confirm the exact {@code <MSG>} element/attribute
     * casing your carrier emits (RCC.14 §2.x uses {@code type="MSG"} with
     * {@code title}/{@code message}; some ACS servers use {@code <TERMS>}); the
     * parse below is tolerant (case-insensitive, both spellings) but the button
     * labels/URL mapping should be spot-checked on the ACS response.
     */
    private void onRcsConfig(final int subId, @Nullable final byte[] configXml) {
        if (configXml == null || configXml.length == 0) {
            return;
        }
        // Feed the real ACS RCC.07 doc to the DR driver — its configFor parses the
        // IMS-Settings (P-CSCF/domain/identities + APPAUTH Digest creds) into the
        // RcsImsConfig, replacing the debug sysprops on the production path.
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
                /* tosUrl= */ null); // KIND_CARRIER_TOS: no external URL (spec §4.9)
        if (mLastTosState != TOS_REQUIRED) {
            mLastTosState = TOS_REQUIRED;
            LogUtil.i(TAG, SUBTAG + ": carrier <MSG> T&C gate present -> EVT_TOS_STATE(REQUIRED)");
            emitTos(subId, TOS_REQUIRED, prompt);
        }
    }

    /** Pull a value from an RCC.14 {@code <parm name="X" value="Y"/>} or
     *  {@code <X>Y</X>} shape (tolerant of both ACS encodings). */
    @Nullable
    private static String extractAcsValue(final String xml, final String name) {
        // <parm name="title" value="..."/> form.
        final java.util.regex.Matcher parm = java.util.regex.Pattern.compile(
                "name=\"" + java.util.regex.Pattern.quote(name) + "\"\\s+value=\"([^\"]*)\"",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(xml);
        if (parm.find()) {
            return parm.group(1);
        }
        // <title>...</title> element form.
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

    /** The per-transport caps this transport publishes on CONFIGURED (design §8). */
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

    // ---- events: :ims -> main ----

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

    /**
     * The TEXT overload — for a body that genuinely IS text: a plaintext CPIM {@code text/plain}
     * from the SR or DR leg. It stringified nothing that was ever binary, so it stays.
     *
     * <p>Anything that can be binary (everything decrypted from MLS) comes through the byte
     * overload below instead.
     */
    private void emitIncoming(final int subId, @Nullable final String fromE164,
            final String body, final String messageId, @Nullable final String e2eeSchemeId) {
        emitIncoming(subId, fromE164,
                body != null ? body.getBytes(StandardCharsets.UTF_8) : null,
                "text/plain;charset=UTF-8", messageId, e2eeSchemeId);
    }

    private void emitIncoming(final int subId, @Nullable final String fromE164,
            @Nullable final byte[] body, final String contentType, final String messageId,
            @Nullable final String e2eeSchemeId) {
        // fromUri is contractually non-null (spec §4.2); a peer we couldn't
        // parse a number for still yields a routable-enough placeholder rather
        // than a null that would trip the router.
        final String from = !TextUtils.isEmpty(fromE164) ? fromE164 : "sip:anonymous@unknown";
        // NOTHING IS UNFRAMED OR RE-TYPED HERE. This method is still the PRODUCER of the
        // RcsIncomingMessage, so it is still the layer that owes the router a real
        // inner type and real content — it just no longer has to DERIVE them. Both now arrive:
        // CarrierMessageReceiver.handleInboundMls runs RccMlsBody.parse at the hop that produced
        // the plaintext and hands the result down as bytes + contentType, which is the
        // same shape the Tachyon leg has always had (MlsProviderTransport.decryptInbound returns an
        // RccMlsBody.Parsed).
        //
        // Do NOT reinstate a parse here. It would be the double-parse defect over again: the body
        // reaching this method is already unframed, an unframed image has no frame either, and
        // RccMlsBody.parse returns a frameless payload verbatim as text/plain — so a second pass
        // would silently relabel every inline image as a text bubble.
        //
        // Build the neutral RcsIncomingMessage the shared router expects. The
        // e2eeSchemeId is the provenance tag threaded from the MLS
        // decrypt hop; it lights the per-message padlock and never carries
        // ciphertext (Seam-1 contract).
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

    /** C3 — a per-peer MSRP session went cold (teardown/idle). Mirrors
     *  {@link #emitSessionWarm}; the CarrierImsTransport facade already handles
     *  EVT_SESSION_COLD (this is the previously-missing emitter). */
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

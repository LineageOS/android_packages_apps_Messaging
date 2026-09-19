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
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateConnection;
import android.telephony.ims.SipMessage;

import com.android.messaging.rcs.carrier.msrp.MsrpEndFlag;
import com.android.messaging.rcs.carrier.msrp.MsrpHeaders;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;
import com.android.messaging.rcs.carrier.msrp.MsrpTransactionId;
import com.android.messaging.rcs.carrier.msrp.session.MsrpChatSession;
import com.android.messaging.rcs.carrier.msrp.session.MsrpSessionInfo;
import com.android.messaging.rcs.carrier.msrp.session.MsrpTlsConnection;
import com.android.messaging.rcs.carrier.msrp.session.SdpAnswer;
import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;
import com.android.messaging.rcs.carrier.msrp.session.SdpParseException;
import com.android.messaging.util.LogUtil;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives one CPM 1-1 SESSION-mode message send over the granted
 * {@code oma.cpm.session} SipDelegate:
 *
 * <pre>
 *   INVITE (SDP offer) ──delegate──▶ network
 *                  ◀── 100/180/183 (provisional)
 *                  ◀── 200 OK (SDP answer: relay host/port/path/fingerprint)
 *   ACK ──delegate──▶ network
 *   ── open MSRP-over-TLS socket to the relay (IMS-bound, separate from delegate)
 *   ── MSRP SEND (message/cpim wrapping text)   ◀── MSRP 200
 *                  ◀── MSRP REPORT (delivery) [optional]
 *   BYE ──delegate──▶ network
 * </pre>
 *
 * <p>SIP signaling rides {@link SipDelegateConnection#sendMessage}; the 200 OK
 * arrives asynchronously on the delegate's message callback, routed here by
 * {@link SipDelegateClient}. MSRP media rides {@link ImsMsrpSocketFactory}'s
 * IMS-bound TLS socket, the same split Google Messages uses.
 *
 * <p><b>Session model (design §9 keep-warm).</b> One engine instance = one CPM
 * conversation session, held open across multiple sends: {@link #establish} does
 * the INVITE&rarr;200&rarr;ACK&rarr;MSRP-connect once and HOLDS the media leg;
 * each {@link #sendOnSession} reuses that open MSRP connection for an MSRP SEND
 * (no fresh INVITE/BYE per message); {@link #teardown} sends the BYE + closes MSRP
 * on idle-timeout or delegate loss. Not reused after teardown.
 */
public final class CpmSessionEngine {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CpmSessionEngine";

    /** How long to wait for the INVITE 200 OK before giving up. */
    private static final long INVITE_TIMEOUT_MS = 30000;
    /** How long to wait for the MSRP 200 to our SEND. */
    private static final long MSRP_SEND_TIMEOUT_MS = 15000;

    public enum Stage {
        IDLE, INVITE_SENT, PROVISIONAL, ANSWERED, ACKED,
        MSRP_CONNECTED, MSRP_SENT, MSRP_ACKED, DELIVERED, BYE_SENT, DONE, FAILED
    }

    /**
     * Real MSRP-session lifecycle observer, so a caller can reflect true session
     * state (design §9 / review C3) instead of "the delegate exists". Fired once
     * when the media leg is up ({@link #onEstablished}) and once on teardown
     * ({@link #onClosed}). Both fire on the engine's send thread / MSRP callback
     * thread; keep the callbacks cheap and non-blocking.
     */
    public interface Observer {
        void onEstablished();
        void onClosed();
    }

    @androidx.annotation.Nullable
    private volatile Observer mObserver;
    private volatile boolean mClosedNotified;
    /** Set once {@link #teardown} (or a peer BYE) has closed the session; guards
     *  {@link #isHeld} so a torn-down engine is never reused for a SEND. */
    private volatile boolean mTornDown;

    /** Register the session lifecycle observer before {@link #establish}. */
    public void setObserver(Observer observer) {
        mObserver = observer;
    }

    private final Context mContext;
    private final SipDelegateConnection mConn;
    private final SipDelegateConfiguration mCfg;

    private volatile CpmSessionSipBuilder.Dialog mDialog;
    private volatile int mInviteCseq;
    private volatile CpmSdpFactory.Offer mOffer;
    private volatile MsrpClientCredentials mCreds;
    private volatile ImsMsrpSocketFactory mSocketFactory;
    private volatile MsrpTlsConnection mMsrpConn;
    private final MsrpChatSession mMsrpSession = new MsrpChatSession("cpm-1-1");
    private volatile String mImdnMessageId;
    private volatile String mMsrpSendTid;

    private final AtomicReference<Stage> mStage = new AtomicReference<>(Stage.IDLE);
    private final Object mInviteLatch = new Object();

    public CpmSessionEngine(Context context, SipDelegateConnection conn,
            SipDelegateConfiguration cfg) {
        this.mContext = context.getApplicationContext();
        this.mConn = conn;
        this.mCfg = cfg;
    }

    public Stage getStage() { return mStage.get(); }

    /**
     * Dump the EXACT on-wire bytes the framework will serialize (start line +
     * header section + CRLF + body) so we can byte-audit against Google Messages / Shannon
     * without the framework's redacted "Header:[***]" view. Logs the canonical
     * {@code SipMessage.toEncodedMessage()} and asserts the start line carries
     * its mandatory trailing CRLF (the 2026-06-02 code-3 root cause).
     */
    static void dumpWire(String label, SipMessage m) {
        try {
            final String startLine = m.getStartLine();
            final boolean startLineHasCrlf = startLine.endsWith("\r\n");
            final byte[] wire = m.toEncodedMessage();
            LogUtil.i(TAG, SUBTAG + ": WIRE[" + label + "] bytes=" + wire.length
                    + " startLineEndsCRLF=" + startLineHasCrlf
                    + " (if false the modem rejects code-3 'Start line INVALID')");
            // Render with visible CRLFs so the exact byte layout is auditable.
            final String rendered = new String(wire, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\r\n", "\\r\\n\n");
            LogUtil.i(TAG, SUBTAG + ": WIRE[" + label + "] >>>>>>>>>>\n" + rendered
                    + "\n<<<<<<<<<< WIRE[" + label + "]");
        } catch (Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": wire dump failed", t);
        }
    }

    /** True once the media leg is up and the session has NOT been torn down —
     *  i.e. a subsequent {@link #sendOnSession} can reuse the open MSRP socket
     *  instead of a fresh INVITE (design §9 keep-warm). */
    public boolean isHeld() {
        return !mTornDown && mMsrpConn != null;
    }

    /**
     * Establish and HOLD the CPM 1-1 SESSION to the peer: INVITE&rarr;200&rarr;
     * ACK&rarr;MSRP-connect, then stop (no SEND, no BYE). Blocks on the calling
     * thread through the INVITE 200 OK (delivered by {@link #onSipResponse} from
     * the delegate callback). Fires {@link Observer#onEstablished} once the media
     * leg is up. Returns {@link Stage#ANSWERED} when the session is held, else a
     * terminal {@link Stage#FAILED}. The held session is then reused across sends
     * until {@link #teardown}.
     */
    public Stage establish(String fromTel, String toTel) {
        try {
            return doEstablish(fromTel, toTel);
        } catch (Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": session establish failed at stage " + mStage.get(), t);
            mStage.set(Stage.FAILED);
            return Stage.FAILED;
        }
    }

    /**
     * Send one CPM 1-1 text over the ALREADY-HELD MSRP session (reuse; no fresh
     * INVITE/BYE). Blocks on the calling thread for the MSRP 200 / REPORT. Returns
     * the terminal send stage ({@link Stage#MSRP_ACKED} on the relay 200,
     * {@link Stage#DELIVERED} on the peer REPORT, else a non-ack'd stage the caller
     * maps to FAILED). Requires {@link #isHeld()}; does NOT tear the session down.
     */
    public Stage sendOnSession(String text) {
        if (!isHeld()) {
            LogUtil.w(TAG, SUBTAG + ": sendOnSession with no held MSRP session (stage="
                    + mStage.get() + ")");
            return Stage.FAILED;
        }
        try {
            return runMsrpSend(text);
        } catch (Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": session reuse send failed at stage " + mStage.get(), t);
            return Stage.FAILED;
        }
    }

    /**
     * Tear the held session down: BYE over the delegate + close the MSRP socket
     * (fires {@link Observer#onClosed} exactly once). Idempotent. Called on
     * idle-timeout or delegate loss by {@link SipDelegateClient}.
     */
    public void teardown(String reason) {
        if (mTornDown) {
            closeMsrp(reason); // still fires onClosed once if not already
            return;
        }
        mTornDown = true;
        try {
            if (mDialog != null) {
                final CpmSessionSipBuilder.Built bye = CpmSessionSipBuilder.buildBye(mDialog);
                LogUtil.i(TAG, SUBTAG + ": >>> BYE (" + reason + ") branch=" + bye.branch);
                dumpWire("BYE", bye.sipMessage);
                mConn.sendMessage(bye.sipMessage, mCfg.getVersion());
                mStage.set(Stage.BYE_SENT);
            }
        } catch (Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": BYE send failed", t);
        }
        closeMsrp(reason);
    }

    /**
     * The local host to advertise in the SDP {@code c=}/{@code o=}/{@code a=path}.
     * Prefer the registered IMS-PDN local address from the
     * {@link SipDelegateConfiguration} (the address the modem registered SIP on,
     * which is on the same IMS APN MSRP must egress from); fall back to a local
     * non-loopback address, then {@code 0.0.0.0} only as a last resort.
     */
    private String resolveLocalMsrpHost() {
        try {
            java.net.InetSocketAddress a = mCfg.getLocalAddress();
            if (a != null && a.getAddress() != null) {
                String ip = a.getAddress().getHostAddress();
                if (ip != null && !ip.isEmpty()) {
                    return ip;
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress ad = addrs.nextElement();
                    if (!ad.isLoopbackAddress() && !ad.isLinkLocalAddress()) {
                        return ad.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return "0.0.0.0";
    }

    /** Reserve a real ephemeral TCP port for the local MSRP endpoint (closed
     *  immediately; advertised in SDP). Active-setup means we connect out, but
     *  a real port is the GSMA-conformant offer (vs the old discard port 9). */
    private int reserveLocalMsrpPort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Throwable t) {
            return 2855; // GSMA default MSRP port as a fallback
        }
    }

    private Stage doEstablish(String fromTel, String toTel) throws Exception {
        // 1) TLS client credentials → SDP fingerprint.
        mCreds = MsrpClientCredentials.generate();
        final String fpHex = mCreds.fingerprintColonHex();
        LogUtil.i(TAG, SUBTAG + ": local MSRP cert fingerprint SHA-256 " + fpHex);

        // 2) SDP offer. Advertise a REAL local endpoint, not the prior
        //    0.0.0.0:9 placeholder. We offer setup:actpass; with the relay
        //    typically answering passive we connect active, but a GSMA-conformant
        //    offer still carries a routable c=/o=/m=<port>/a=path host:port.
        //    The local host = our registered IMS-PDN address (from the
        //    SipDelegateConfiguration); the port = a real ephemeral port we
        //    reserve so a passive-answer relay could connect back if it chose.
        final String localHost = resolveLocalMsrpHost();
        final int localPort = reserveLocalMsrpPort();
        LogUtil.i(TAG, SUBTAG + ": SDP local MSRP endpoint " + localHost + ":" + localPort);
        mOffer = CpmSdpFactory.build(localHost, localPort, fpHex);

        // 3) Dialog + INVITE over the delegate.
        mDialog = CpmSessionSipBuilder.newDialog(mCfg, fromTel, toTel);
        LogUtil.i(TAG, SUBTAG + ": network headers: "
                + CpmSessionSipBuilder.dumpNetworkHeaders(mDialog));
        final byte[] sdpBytes = mOffer.sdp.encode();
        final CpmSessionSipBuilder.Built invite =
                CpmSessionSipBuilder.buildInvite(mDialog, sdpBytes);
        mInviteCseq = invite.cseq;
        mStage.set(Stage.INVITE_SENT);
        LogUtil.i(TAG, SUBTAG + ": >>> INVITE to=" + toTel + " callId=" + mDialog.callId
                + " branch=" + invite.branch + " sdpBytes=" + sdpBytes.length
                + " configVersion=" + mCfg.getVersion());
        LogUtil.i(TAG, SUBTAG + ": SDP offer:\n" + new String(sdpBytes,
                java.nio.charset.StandardCharsets.UTF_8));
        dumpWire("INVITE", invite.sipMessage);
        mConn.sendMessage(invite.sipMessage, mCfg.getVersion());

        // 4) Wait for the 200 OK (delivered by onSipResponse).
        synchronized (mInviteLatch) {
            long deadline = System.currentTimeMillis() + INVITE_TIMEOUT_MS;
            while (mStage.get() != Stage.ANSWERED
                    && mStage.get() != Stage.FAILED) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) break;
                mInviteLatch.wait(wait);
            }
        }
        if (mStage.get() != Stage.ANSWERED) {
            LogUtil.w(TAG, SUBTAG + ": no 200 OK (stage=" + mStage.get()
                    + ") — INVITE path stopped here");
            mStage.compareAndSet(Stage.INVITE_SENT, Stage.FAILED);
            mStage.compareAndSet(Stage.PROVISIONAL, Stage.FAILED);
            return mStage.get();
        }

        // The 200 OK handler already parsed the answer + sent ACK + opened MSRP
        // and fired Observer.onEstablished — the session is now HELD (ANSWERED).
        // We do NOT SEND here; sends ride the held MSRP socket via sendOnSession.
        return mStage.get();
    }

    /**
     * Called by {@link SipDelegateClient} for every inbound SipMessage on the
     * delegate. We claim only responses/requests on our Call-ID.
     */
    public boolean onSipMessage(SipMessage message) {
        final String hdr = message.getHeaderSection();
        final CpmSessionSipBuilder.Dialog d = mDialog;
        if (d == null || hdr == null || !hdr.contains(d.callId)) {
            return false; // not ours
        }
        final String startLine = message.getStartLine();
        final int status = CpmSessionSipBuilder.parseStatusCode(startLine);
        if (status >= 0) {
            onSipResponse(status, message);
        } else {
            onSipRequest(startLine, message);
        }
        return true;
    }

    private void onSipResponse(int status, SipMessage message) {
        LogUtil.i(TAG, SUBTAG + ": <<< response " + status + " on our dialog");
        if (status >= 100 && status < 200) {
            mStage.compareAndSet(Stage.INVITE_SENT, Stage.PROVISIONAL);
            return;
        }
        if (status >= 200 && status < 300) {
            try {
                handle200(message);
            } catch (Throwable t) {
                LogUtil.e(TAG, SUBTAG + ": 200 OK handling failed", t);
                failAndWake();
            }
        } else {
            LogUtil.w(TAG, SUBTAG + ": INVITE rejected " + status);
            failAndWake();
        }
    }

    private void onSipRequest(String startLine, SipMessage message) {
        final String method = CpmSessionSipBuilder.parseMethod(startLine);
        LogUtil.i(TAG, SUBTAG + ": <<< in-dialog request " + method);
        if ("BYE".equals(method)) {
            // Peer tore down; close MSRP and mark the held session unusable so a
            // later sendOnSession re-establishes instead of reusing a dead socket.
            mTornDown = true;
            closeMsrp("peer BYE");
            mStage.set(Stage.DONE);
        }
        // We rely on the framework to 200 in-dialog requests it owns; for BYE
        // an explicit 200 would need a response SipMessage — out of scope for
        // the first milestone (the send completes before peer BYE normally).
    }

    private void handle200(SipMessage message) throws Exception {
        // Learn the To-tag for ACK/BYE.
        final String toTag = CpmSessionSipBuilder.parseToTag(message.getHeaderSection());
        if (toTag != null) {
            mDialog.toTag = toTag;
            LogUtil.i(TAG, SUBTAG + ": learned To-tag=" + toTag);
        }
        // Parse the SDP answer.
        final byte[] body = message.getContent();
        if (body == null || body.length == 0) {
            throw new SdpParseException("200 OK without SDP body");
        }
        LogUtil.i(TAG, SUBTAG + ": SDP answer:\n"
                + new String(body, java.nio.charset.StandardCharsets.UTF_8));
        final SdpOffer answer = SdpOffer.parse(body);
        LogUtil.i(TAG, SUBTAG + ": answer relay=" + answer.getHost() + ":" + answer.getPort()
                + " path=" + answer.getMsrpPath() + " setup=" + answer.getSetup()
                + " fp=" + (answer.hasFingerprint() ? answer.getFingerprintAlg() : "none"));

        // Our role: we offered actpass; the answer's complementary role decides.
        final SdpOffer.SetupRole localRole =
                SdpAnswer.complementarySetup(answer.getSetup());
        LogUtil.i(TAG, SUBTAG + ": local MSRP role=" + localRole);

        final MsrpSessionInfo info = MsrpSessionInfo.builder()
                .sipCallId(mDialog.callId)
                .contributionId(mDialog.contributionId)
                .conversationId(mDialog.conversationId)
                .localMsrpUri(mOffer.localMsrpUri)
                .remoteMsrpUri(answer.getMsrpPath())
                .localFingerprint(mOffer.sdp.getFingerprintAlg(), mOffer.sdp.getFingerprintHex())
                .remoteFingerprint(answer.getFingerprintAlg(), answer.getFingerprintHex())
                .acceptTypes(SdpAnswer.intersect(
                        mOffer.sdp.getAcceptTypes(), answer.getAcceptTypes()))
                .acceptWrappedTypes(SdpAnswer.intersect(
                        mOffer.sdp.getAcceptWrappedTypes(), answer.getAcceptWrappedTypes()))
                .localRole(localRole)
                .addrType(answer.getAddrType())
                .remoteHost(answer.getHost())
                .remotePort(answer.getPort())
                .build();

        // ACK the 200 over the delegate (in-dialog, reuses INVITE CSeq).
        final CpmSessionSipBuilder.Built ack =
                CpmSessionSipBuilder.buildAck(mDialog, mInviteCseq);
        LogUtil.i(TAG, SUBTAG + ": >>> ACK branch=" + ack.branch);
        dumpWire("ACK", ack.sipMessage);
        mConn.sendMessage(ack.sipMessage, mCfg.getVersion());
        mStage.set(Stage.ACKED);

        // Open the MSRP-over-TLS media socket to the relay (active connect).
        mMsrpSession.inviting();
        mMsrpSession.established(info);
        if (localRole == SdpOffer.SetupRole.ACTIVE
                || localRole == SdpOffer.SetupRole.ACTPASS) {
            mSocketFactory = new ImsMsrpSocketFactory(mContext, answer.isMsrpTls(), mCreds);
            mMsrpConn = new MsrpTlsConnection(
                    mSocketFactory,
                    new MsrpConnListener(),
                    info.getRemoteHost(), info.getRemotePort(),
                    info.getRemoteFingerprintAlg(), info.getRemoteFingerprintHex());
            LogUtil.i(TAG, SUBTAG + ": opening MSRP socket -> "
                    + info.getRemoteHost() + ":" + info.getRemotePort()
                    + " tls=" + answer.isMsrpTls());
            mMsrpConn.start();
            mStage.set(Stage.MSRP_CONNECTED);
            LogUtil.i(TAG, SUBTAG + ": MSRP socket connected (local="
                    + mSocketFactory.getLocalAddress() + ":" + mSocketFactory.getLocalPort() + ")");
        } else {
            LogUtil.w(TAG, SUBTAG + ": local role passive — inbound MSRP not wired in milestone 1");
        }
        // Session media leg is up — notify the observer the session is warm
        // (real MSRP-connected state, review C3), then wake doEstablish().
        mStage.set(Stage.ANSWERED);
        final Observer obs = mObserver;
        if (obs != null) {
            try {
                obs.onEstablished();
            } catch (Throwable t) {
                LogUtil.w(TAG, SUBTAG + ": Observer.onEstablished threw", t);
            }
        }
        synchronized (mInviteLatch) { mInviteLatch.notifyAll(); }
    }

    private Stage runMsrpSend(String text) throws Exception {
        if (mMsrpConn == null) {
            LogUtil.w(TAG, SUBTAG + ": no MSRP connection; cannot SEND");
            return Stage.FAILED;
        }
        // Fresh IMDN Message-ID per send (the held session carries many messages).
        mImdnMessageId = UUID.randomUUID().toString();
        // Build the CPIM body + MSRP SEND frame.
        final byte[] cpim = CpmSessionSipBuilder.buildCpim(
                mDialog.fromTelUri, "tel:" + mDialog.toTel, mImdnMessageId, text);
        final MsrpSessionInfo info = mMsrpSession.getSessionInfo();
        mMsrpSendTid = MsrpTransactionId.next();
        final MsrpMessage send = MsrpMessage.newSend()
                .transactionId(mMsrpSendTid)
                .toPath(info.getRemoteMsrpUri())
                .fromPath(info.getLocalMsrpUri())
                .messageId(mImdnMessageId)
                .contentType("message/cpim")
                .successReport(true)
                .failureReport(MsrpHeaders.FAILURE_REPORT_YES)
                .body(cpim)
                .endFlag(MsrpEndFlag.COMPLETE)
                .build();
        LogUtil.i(TAG, SUBTAG + ": >>> MSRP SEND tid=" + mMsrpSendTid
                + " toPath=" + info.getRemoteMsrpUri()
                + " cpimBytes=" + cpim.length);
        mMsrpConn.send(send);
        mStage.set(Stage.MSRP_SENT);

        // Wait briefly for the MSRP 200 / REPORT (driven by MsrpConnListener).
        long deadline = System.currentTimeMillis() + MSRP_SEND_TIMEOUT_MS;
        synchronized (mInviteLatch) {
            while (mStage.get() == Stage.MSRP_SENT
                    && System.currentTimeMillis() < deadline) {
                mInviteLatch.wait(500);
            }
        }
        LogUtil.i(TAG, SUBTAG + ": MSRP send stage=" + mStage.get());
        // Do NOT BYE here — the session is HELD open for the next send (design §9
        // keep-warm). Teardown is driven by the idle-timer / delegate-loss in
        // SipDelegateClient via teardown(). Report the real terminal send stage.
        final Stage terminal = (mStage.get() == Stage.MSRP_ACKED
                || mStage.get() == Stage.DELIVERED) ? mStage.get() : Stage.MSRP_SENT;
        LogUtil.i(TAG, SUBTAG + ": send done on held session, terminal stage=" + terminal);
        return terminal;
    }

    private void closeMsrp(String reason) {
        MsrpTlsConnection c = mMsrpConn;
        if (c != null) {
            c.close(MsrpChatSession.CloseReason.LOCAL_BYE, reason);
        }
        ImsMsrpSocketFactory sf = mSocketFactory;
        if (sf != null) {
            sf.release();
        }
        // Notify session-cold exactly once (review C3).
        if (!mClosedNotified) {
            mClosedNotified = true;
            final Observer obs = mObserver;
            if (obs != null) {
                try {
                    obs.onClosed();
                } catch (Throwable t) {
                    LogUtil.w(TAG, SUBTAG + ": Observer.onClosed threw", t);
                }
            }
        }
    }

    private void failAndWake() {
        mStage.set(Stage.FAILED);
        synchronized (mInviteLatch) { mInviteLatch.notifyAll(); }
    }

    // ---------------- MSRP connection listener ----------------
    private final class MsrpConnListener implements MsrpTlsConnection.Listener {
        @Override
        public void onFrame(MsrpMessage frame) {
            LogUtil.i(TAG, SUBTAG + ": <<< MSRP frame kind=" + frame.getKind()
                    + (frame.isResponse() ? " status=" + frame.getStatusCode()
                            : " method=" + frame.getMethod())
                    + " tid=" + frame.getTransactionId());
            if (frame.isResponse() && mMsrpSendTid != null
                    && mMsrpSendTid.equals(frame.getTransactionId())) {
                int code = frame.getStatusCode();
                if (code >= 200 && code < 300) {
                    LogUtil.i(TAG, SUBTAG + ": MSRP SEND 200 OK — chunk accepted by relay");
                    mStage.compareAndSet(Stage.MSRP_SENT, Stage.MSRP_ACKED);
                    synchronized (mInviteLatch) { mInviteLatch.notifyAll(); }
                } else {
                    LogUtil.w(TAG, SUBTAG + ": MSRP SEND failed status=" + code
                            + " " + frame.getStatusComment());
                    failAndWake();
                }
            } else if (frame.isRequest() && frame.getMethod() == MsrpMethod.REPORT) {
                LogUtil.i(TAG, SUBTAG + ": MSRP REPORT status=" + frame.getStatus()
                        + " msgId=" + frame.getMessageId());
                // Only the REPORT for the in-flight send advances THIS send's stage
                // (the held session carries many messages; a late REPORT from a
                // prior send must not prematurely wake the current one).
                if (mImdnMessageId != null && mImdnMessageId.equals(frame.getMessageId())) {
                    mStage.set(Stage.DELIVERED);
                    synchronized (mInviteLatch) { mInviteLatch.notifyAll(); }
                }
            }
        }

        @Override
        public void onClosed(MsrpChatSession.CloseReason reason, String detail) {
            LogUtil.i(TAG, SUBTAG + ": MSRP closed reason=" + reason + " detail=" + detail);
        }
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sip;

import android.content.Context;
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateConnection;
import android.telephony.ims.SipMessage;

import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.rcs.carrier.msrp.MsrpEndFlag;
import com.android.messaging.rcs.carrier.msrp.MsrpHeaders;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;
import com.android.messaging.rcs.carrier.msrp.MsrpReportStatus;
import com.android.messaging.rcs.carrier.msrp.MsrpTransactionId;
import com.android.messaging.rcs.carrier.msrp.session.MsrpChatSession;
import com.android.messaging.rcs.carrier.msrp.session.MsrpSessionInfo;
import com.android.messaging.rcs.carrier.msrp.session.MsrpTlsConnection;
import com.android.messaging.rcs.carrier.msrp.session.SdpAnswer;
import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;
import com.android.messaging.rcs.carrier.msrp.session.SdpParseException;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One outbound CPM 1:1 session on the SR path. {@link #establish} sends the INVITE through the
 * delegate, ACKs the 200, and connects MSRP to the relay in the answer; the session is then held,
 * each {@link #sendOnSession} is one MSRP SEND on it, and {@link #teardown} sends BYE and closes
 * it. Responses arrive through {@link SipDelegateClient}; MSRP rides an
 * {@link ImsMsrpSocketFactory} socket. Not reusable after teardown.
 */
public final class CpmSessionEngine {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CpmSessionEngine";

    private static final long INVITE_TIMEOUT_MS = 30000;
    private static final long MSRP_SEND_TIMEOUT_MS = 15000;

    public enum Stage {
        IDLE, INVITE_SENT, PROVISIONAL, ANSWERED, ACKED,
        MSRP_CONNECTED, MSRP_SENT, MSRP_ACKED, DELIVERED, BYE_SENT, DONE, FAILED
    }

    /**
     * Fired once when the media leg is up and once on close, on the establishing or MSRP thread;
     * keep the callbacks cheap.
     */
    public interface Observer {
        void onEstablished();
        void onClosed();
    }

    @androidx.annotation.Nullable
    private volatile Observer mObserver;
    private volatile boolean mClosedNotified;
    /** Set by {@link #teardown} or a peer BYE; a torn-down engine is never used for a SEND. */
    private volatile boolean mTornDown;

    /** Set before {@link #establish}. */
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

    /**
     * Logs whether the start line ends in CRLF and, on a debug build only, the encoded message with
     * visible CRLFs: it carries both parties' identities and the SDP.
     */
    static void dumpWire(String label, SipMessage m) {
        try {
            final String startLine = m.getStartLine();
            final boolean startLineHasCrlf = startLine.endsWith("\r\n");
            final byte[] wire = m.toEncodedMessage();
            LogUtil.i(TAG, SUBTAG + ": WIRE[" + label + "] bytes=" + wire.length
                    + " startLineEndsCRLF=" + startLineHasCrlf
                    + " (if false the modem rejects code-3 'Start line INVALID')");
            if (RcsDebug.isDebugBuild()) {
                final String rendered = new String(wire, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("\r\n", "\\r\\n\n");
                LogUtil.i(TAG, SUBTAG + ": WIRE[" + label + "] >>>>>>>>>>\n" + rendered
                        + "\n<<<<<<<<<< WIRE[" + label + "]");
            }
        } catch (Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": wire dump failed", t);
        }
    }

    /** The media leg is up and the session has not been torn down. */
    public boolean isHeld() {
        return !mTornDown && mMsrpConn != null;
    }

    /**
     * Establishes and holds the session, sending nothing. Blocks until the INVITE is answered or
     * times out. Returns {@link Stage#ANSWERED} when held, else {@link Stage#FAILED}.
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
     * One text on the held session; blocks for the MSRP 200 or REPORT. Returns
     * {@link Stage#MSRP_ACKED} on the 200, {@link Stage#DELIVERED} on a 2xx REPORT, otherwise
     * {@link Stage#MSRP_SENT} or {@link Stage#FAILED}, which the caller treats as failed; a failure
     * REPORT is one of those.
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

    /** Sends BYE and closes MSRP; {@link Observer#onClosed} fires once. Idempotent. */
    public void teardown(String reason) {
        if (mTornDown) {
            closeMsrp(reason);
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
     * The address to advertise in {@code c=}, {@code o=} and the path: the registered IMS address,
     * which MSRP must leave from, else any non-loopback address, else {@code 0.0.0.0}.
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

    /**
     * A real ephemeral port to advertise, closed at once; we connect out, but an offer should name
     * a usable endpoint.
     */
    private int reserveLocalMsrpPort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Throwable t) {
            return 2855; // the GSMA default MSRP port
        }
    }

    private Stage doEstablish(String fromTel, String toTel) throws Exception {
        mCreds = MsrpClientCredentials.generate();
        final String fpHex = mCreds.fingerprintColonHex();
        LogUtil.i(TAG, SUBTAG + ": local MSRP cert fingerprint SHA-256 " + fpHex);

        // We offer actpass and a relay answers passive, so we connect; the offer still names a
        // routable endpoint in case the relay connects back.
        final String localHost = resolveLocalMsrpHost();
        final int localPort = reserveLocalMsrpPort();
        LogUtil.i(TAG, SUBTAG + ": SDP local MSRP endpoint " + localHost + ":" + localPort);
        mOffer = CpmSdpFactory.build(localHost, localPort, fpHex);

        mDialog = CpmSessionSipBuilder.newDialog(mCfg, fromTel, toTel);
        if (RcsDebug.isDebugBuild()) {
            LogUtil.i(TAG, SUBTAG + ": network headers: "
                    + CpmSessionSipBuilder.dumpNetworkHeaders(mDialog));
        }
        final byte[] sdpBytes = mOffer.sdp.encode();
        final CpmSessionSipBuilder.Built invite =
                CpmSessionSipBuilder.buildInvite(mDialog, sdpBytes);
        mInviteCseq = invite.cseq;
        mStage.set(Stage.INVITE_SENT);
        LogUtil.i(TAG, SUBTAG + ": >>> INVITE to=" + LogMask.number(toTel) + " callId="
                + mDialog.callId
                + " branch=" + invite.branch + " sdpBytes=" + sdpBytes.length
                + " configVersion=" + mCfg.getVersion());
        if (RcsDebug.isDebugBuild()) {
            LogUtil.i(TAG, SUBTAG + ": SDP offer:\n" + new String(sdpBytes,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        dumpWire("INVITE", invite.sipMessage);
        mConn.sendMessage(invite.sipMessage, mCfg.getVersion());

        // onSipResponse wakes us on the answer.
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

        // The 200 handler has ACKed and connected MSRP; sends go through sendOnSession.
        return mStage.get();
    }

    /** For every inbound message on the delegate; claims those on our Call-ID. */
    public boolean onSipMessage(SipMessage message) {
        final String hdr = message.getHeaderSection();
        final CpmSessionSipBuilder.Dialog d = mDialog;
        if (d == null || hdr == null || !hdr.contains(d.callId)) {
            return false;
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
            // Answer it: the framework does not, and an unanswered BYE leaves the peer's
            // transaction to time out instead of completing.
            try {
                final CpmSessionSipBuilder.IncomingRequest bye =
                        CpmSessionSipBuilder.parseRequest(message);
                mConn.sendMessage(CpmSessionSipBuilder.buildResponse(
                        bye, 200, "OK", null, null, null, null), mCfg.getVersion());
                LogUtil.i(TAG, SUBTAG + ": >>> 200 OK to the peer's BYE");
            } catch (final Throwable t) {
                LogUtil.w(TAG, SUBTAG + ": BYE 200 failed", t);
            }
            // The session is dead; a later send must establish a new one.
            mTornDown = true;
            closeMsrp("peer BYE");
            mStage.set(Stage.DONE);
        }
    }

    private void handle200(SipMessage message) throws Exception {
        final String toTag = CpmSessionSipBuilder.parseToTag(message.getHeaderSection());
        if (toTag != null) {
            mDialog.toTag = toTag;
            LogUtil.i(TAG, SUBTAG + ": learned To-tag=" + toTag);
        }
        final byte[] body = message.getContent();
        if (body == null || body.length == 0) {
            throw new SdpParseException("200 OK without SDP body");
        }
        if (RcsDebug.isDebugBuild()) {
            LogUtil.i(TAG, SUBTAG + ": SDP answer:\n"
                    + new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }
        final SdpOffer answer = SdpOffer.parse(body);
        LogUtil.i(TAG, SUBTAG + ": answer relay=" + answer.getHost() + ":" + answer.getPort()
                + " path=" + answer.getMsrpPath() + " setup=" + answer.getSetup()
                + " fp=" + (answer.hasFingerprint() ? answer.getFingerprintAlg() : "none"));

        // We offered actpass; the answer's role decides ours.
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

        // In-dialog, with the INVITE's CSeq.
        final CpmSessionSipBuilder.Built ack =
                CpmSessionSipBuilder.buildAck(mDialog, mInviteCseq);
        LogUtil.i(TAG, SUBTAG + ": >>> ACK branch=" + ack.branch);
        dumpWire("ACK", ack.sipMessage);
        mConn.sendMessage(ack.sipMessage, mCfg.getVersion());
        mStage.set(Stage.ACKED);

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
        // A fresh Message-ID per send: the session carries many messages.
        mImdnMessageId = UUID.randomUUID().toString();
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

        long deadline = System.currentTimeMillis() + MSRP_SEND_TIMEOUT_MS;
        synchronized (mInviteLatch) {
            while (mStage.get() == Stage.MSRP_SENT
                    && System.currentTimeMillis() < deadline) {
                mInviteLatch.wait(500);
            }
        }
        LogUtil.i(TAG, SUBTAG + ": MSRP send stage=" + mStage.get());
        // No BYE: the session stays held until SipDelegateClient tears it down.
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
                // Only the REPORT for the in-flight message counts; a late one for an earlier send
                // must not complete this one. A 2xx Status is a delivery, anything else a failure.
                if (mImdnMessageId != null && mImdnMessageId.equals(frame.getMessageId())) {
                    if (MsrpReportStatus.delivered(frame.getStatus())) {
                        mStage.set(Stage.DELIVERED);
                        synchronized (mInviteLatch) { mInviteLatch.notifyAll(); }
                    } else {
                        LogUtil.w(TAG, SUBTAG + ": MSRP REPORT status=" + frame.getStatus()
                                + " is not a delivery — the send FAILED");
                        failAndWake();
                    }
                }
            }
        }

        @Override
        public void onClosed(MsrpChatSession.CloseReason reason, String detail) {
            LogUtil.i(TAG, SUBTAG + ": MSRP closed reason=" + reason + " detail=" + detail);
        }
    }
}

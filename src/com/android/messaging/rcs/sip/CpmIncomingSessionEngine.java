/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sip;

import android.content.Context;
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateConnection;
import android.telephony.ims.SipMessage;

import androidx.annotation.Nullable;

import com.android.messaging.rcs.carrier.msrp.MsrpEndFlag;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;
import com.android.messaging.rcs.carrier.msrp.MsrpTransactionId;
import com.android.messaging.rcs.carrier.msrp.session.MsrpChatSession;
import com.android.messaging.rcs.carrier.msrp.session.MsrpTlsConnection;
import com.android.messaging.rcs.carrier.msrp.session.SdpAnswer;
import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.nio.charset.StandardCharsets;

/**
 * The answering side of an inbound CPM 1:1 session on the SR path, the counterpart of
 * {@link CpmSessionEngine}: answers the INVITE's SDP offer, connects to the relay in the offer's
 * {@code a=path}, sends the bodiless first SEND, and surfaces each CPIM text as an incoming
 * message.
 *
 * <p>We answer {@code setup:active} and connect out (RFC 6135). An offer of {@code setup:active},
 * which would need us to listen on the IMS bearer, is accepted at the SIP layer but gets no MSRP.
 * TODO: add a listening socket if a network ever offers that.
 */
public final class CpmIncomingSessionEngine {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CpmIncomingSession";

    public interface InboundListener {
        /**
         * @param fromE164 the peer as {@code +E164}, or null if it could not be parsed
         * @param messageId the CPIM {@code imdn.Message-ID}, or a generated id if absent
         */
        void onIncomingText(@Nullable String fromE164, String body, String messageId);
    }

    private final Context mContext;
    private final SipDelegateConnection mConn;
    private final SipDelegateConfiguration mCfg;
    private final CpmSessionSipBuilder.IncomingRequest mInvite;
    private final InboundListener mListener;
    private final String mCallId;

    private volatile MsrpClientCredentials mCreds;
    private volatile ImsMsrpSocketFactory mSocketFactory;
    private volatile MsrpTlsConnection mMsrpConn;
    private volatile String mLocalMsrpUri;
    private volatile String mRemoteMsrpUri;
    private volatile boolean mClosed;

    public CpmIncomingSessionEngine(Context context, SipDelegateConnection conn,
            SipDelegateConfiguration cfg, CpmSessionSipBuilder.IncomingRequest invite,
            InboundListener listener) {
        this.mContext = context.getApplicationContext();
        this.mConn = conn;
        this.mCfg = cfg;
        this.mInvite = invite;
        this.mListener = listener;
        this.mCallId = invite.callId;
    }

    public String getCallId() {
        return mCallId;
    }

    /**
     * Answers 200 with SDP and opens MSRP. Blocks through the answer and connect; SENDs then arrive
     * on the MSRP read thread. Returns whether the session was accepted.
     */
    public boolean accept() {
        try {
            final byte[] offerBytes = mInvite.body;
            if (offerBytes == null || offerBytes.length == 0) {
                LogUtil.w(TAG, SUBTAG + ": INVITE without SDP; declining 488");
                sendResponse(488, "Not Acceptable Here", null);
                return false;
            }
            final SdpOffer offer = SdpOffer.parse(offerBytes);
            LogUtil.i(TAG, SUBTAG + ": inbound INVITE from=" + LogMask.number(mInvite.fromE164)
                    + " relay=" + offer.getHost() + ":" + offer.getPort()
                    + " path=" + offer.getMsrpPath() + " setup=" + offer.getSetup());
            mRemoteMsrpUri = offer.getMsrpPath();

            mCreds = MsrpClientCredentials.generate();
            final String fpHex = mCreds.fingerprintColonHex();
            final String localHost = resolveLocalMsrpHost();
            final int localPort = reserveLocalMsrpPort();
            final boolean ipv6 = localHost.indexOf(':') >= 0;
            final String pathHost = ipv6 ? "[" + localHost + "]" : localHost;
            final String msrpSessId = java.util.UUID.randomUUID().toString().replace("-", "");
            final boolean tls = offer.isMsrpTls();
            mLocalMsrpUri = (tls ? "msrps://" : "msrp://")
                    + pathHost + ":" + localPort + "/" + msrpSessId + ";tcp";

            final SdpOffer answer = SdpAnswer.build(offer, localHost, localPort, mLocalMsrpUri,
                    tls ? SdpOffer.FINGERPRINT_ALG_SHA256 : null, tls ? fpHex : null,
                    SdpOffer.upChatAcceptTypes(), SdpOffer.upChatAcceptWrappedTypes());
            final byte[] answerBytes = answer.encode();

            // The framework rewrites routing headers; we echo the dialog headers and add a To-tag
            // and Contact.
            sendResponse(200, "OK", answerBytes);

            final SdpOffer.SetupRole localRole = SdpAnswer.complementarySetup(offer.getSetup());
            if (localRole == SdpOffer.SetupRole.PASSIVE) {
                LogUtil.w(TAG, SUBTAG + ": peer setup:active (we are passive) — inbound"
                        + " listen socket not implemented; see RIG-VERIFY. Session accepted"
                        + " at SIP layer but MSRP will not bind.");
                return true;
            }
            mSocketFactory = new ImsMsrpSocketFactory(mContext, tls, mCreds);
            mMsrpConn = new MsrpTlsConnection(
                    mSocketFactory, new MsrpListener(),
                    offer.getHost(), offer.getPort(),
                    offer.getFingerprintAlg(), offer.getFingerprintHex());
            mMsrpConn.start();
            LogUtil.i(TAG, SUBTAG + ": MSRP connected -> " + offer.getHost() + ":"
                    + offer.getPort() + " tls=" + tls);
            // RFC 4975 §4.2.2: the active endpoint sends first, which binds the session on the
            // relay.
            sendBootstrap();
            return true;
        } catch (final Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": accept failed", t);
            try {
                sendResponse(500, "Server Internal Error", null);
            } catch (final Throwable ignore) {
            }
            close("accept failure");
            return false;
        }
    }

    /** An in-dialog ACK or BYE that {@link SipDelegateClient} matched to our Call-ID. */
    public void onInDialogRequest(SipMessage message) {
        final String method = CpmSessionSipBuilder.parseMethod(message.getStartLine());
        LogUtil.i(TAG, SUBTAG + ": <<< in-dialog " + method + " on " + mCallId);
        if ("BYE".equals(method)) {
            try {
                final CpmSessionSipBuilder.IncomingRequest bye =
                        CpmSessionSipBuilder.parseRequest(message);
                mConn.sendMessage(CpmSessionSipBuilder.buildResponse(
                        bye, 200, "OK", null, null, null, null), mCfg.getVersion());
            } catch (final Throwable t) {
                LogUtil.w(TAG, SUBTAG + ": BYE 200 failed", t);
            }
            close("peer BYE");
        }
        // ACK needs no answer.
    }

    private void sendResponse(int code, String reason, @Nullable byte[] sdp) {
        final String toTag = CpmSessionSipBuilder.randHex(16);
        final String contact = code / 100 == 2 ? localContact() : null;
        final SipMessage resp = CpmSessionSipBuilder.buildResponse(
                mInvite, code, reason, toTag, contact,
                sdp, sdp != null ? "application/sdp" : null);
        CpmSessionEngine.dumpWire("INBOUND-" + code, resp);
        mConn.sendMessage(resp, mCfg.getVersion());
        LogUtil.i(TAG, SUBTAG + ": >>> " + code + " " + reason
                + (sdp != null ? " (sdp " + sdp.length + "B)" : ""));
    }

    private String localContact() {
        try {
            android.net.Uri gruu = mCfg.getPublicGruuUri();
            if (gruu != null && !gruu.toString().isEmpty()) {
                return gruu.toString();
            }
        } catch (final Throwable ignore) {
        }
        return "sip:anonymous@anonymous.invalid";
    }

    private void sendBootstrap() {
        try {
            final MsrpMessage boot = MsrpMessage.newSend()
                    .transactionId(MsrpTransactionId.next())
                    .toPath(mRemoteMsrpUri)
                    .fromPath(mLocalMsrpUri)
                    .endFlag(MsrpEndFlag.COMPLETE)
                    .build();
            mMsrpConn.send(boot);
            LogUtil.i(TAG, SUBTAG + ": >>> MSRP bootstrap SEND (bodiless)");
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": bootstrap SEND failed", t);
        }
    }

    private void handleInboundSend(MsrpMessage frame) {
        final byte[] body = frame.getBody();
        // Every SEND is answered 200, whatever its Failure-Report.
        try {
            final MsrpMessage ok = MsrpMessage.newResponse(200, "OK")
                    .transactionId(frame.getTransactionId())
                    .toPath(frame.getFromPath())
                    .fromPath(frame.getToPath())
                    .build();
            mMsrpConn.send(ok);
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": MSRP 200 reply failed", t);
        }
        if (body == null || body.length == 0) {
            return; // the bootstrap, or another empty SEND
        }
        final Cpim cpim = parseCpim(body);
        if (cpim.text == null) {
            LogUtil.i(TAG, SUBTAG + ": inbound SEND had no text/plain part (ct="
                    + cpim.innerContentType + ") — not surfaced as a message");
            return;
        }
        final String messageId = cpim.imdnMessageId != null
                ? cpim.imdnMessageId : java.util.UUID.randomUUID().toString();
        LogUtil.i(TAG, SUBTAG + ": inbound text from=" + LogMask.number(mInvite.fromE164)
                + " id=" + messageId + " bytes=" + cpim.text.length());
        try {
            mListener.onIncomingText(mInvite.fromE164, cpim.text, messageId);
        } catch (final Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": InboundListener threw", t);
        }
    }

    /** Idempotent. Public so {@link SipDelegateClient} can close a session it has held too long. */
    public void close(String reason) {
        if (mClosed) {
            return;
        }
        mClosed = true;
        final MsrpTlsConnection c = mMsrpConn;
        if (c != null) {
            c.close(MsrpChatSession.CloseReason.REMOTE_BYE, reason);
        }
        final ImsMsrpSocketFactory sf = mSocketFactory;
        if (sf != null) {
            sf.release();
        }
        LogUtil.i(TAG, SUBTAG + ": inbound session closed (" + reason + ")");
    }

    private final class MsrpListener implements MsrpTlsConnection.Listener {
        @Override
        public void onFrame(MsrpMessage frame) {
            if (frame.isRequest() && frame.getMethod() == MsrpMethod.SEND) {
                handleInboundSend(frame);
            } else if (frame.isResponse()) {
                LogUtil.i(TAG, SUBTAG + ": <<< MSRP response " + frame.getStatusCode()
                        + " tid=" + frame.getTransactionId());
            } else if (frame.isRequest() && frame.getMethod() == MsrpMethod.REPORT) {
                // A REPORT has nothing to report on this receive-only leg.
                LogUtil.i(TAG, SUBTAG + ": <<< MSRP REPORT " + frame.getStatus());
            }
        }

        @Override
        public void onClosed(MsrpChatSession.CloseReason reason, String detail) {
            LogUtil.i(TAG, SUBTAG + ": MSRP closed reason=" + reason + " detail=" + detail);
        }
    }

    // Minimal CPIM (RFC 3862): the inner text/plain and imdn.Message-ID.
    private static final class Cpim {
        String imdnMessageId;
        String innerContentType;
        String text;
    }

    private static Cpim parseCpim(byte[] body) {
        final Cpim out = new Cpim();
        final String s = new String(body, StandardCharsets.UTF_8);
        // Outer headers, blank line, inner MIME headers, blank line, payload.
        final int firstBlank = indexOfBlankLine(s, 0);
        if (firstBlank < 0) {
            return out;
        }
        final String outerHeaders = s.substring(0, firstBlank);
        for (String line : outerHeaders.split("\r\n|\n")) {
            final String low = line.toLowerCase(java.util.Locale.ROOT);
            if (low.startsWith("imdn.message-id:")) {
                out.imdnMessageId = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        final int innerStart = afterBlankLine(s, firstBlank);
        final int secondBlank = indexOfBlankLine(s, innerStart);
        if (secondBlank < 0) {
            return out;
        }
        final String innerHeaders = s.substring(innerStart, secondBlank);
        for (String line : innerHeaders.split("\r\n|\n")) {
            final String low = line.toLowerCase(java.util.Locale.ROOT);
            if (low.startsWith("content-type:")) {
                out.innerContentType = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        final int payloadStart = afterBlankLine(s, secondBlank);
        final String payload = payloadStart <= s.length() ? s.substring(payloadStart) : "";
        if (out.innerContentType == null
                || out.innerContentType.toLowerCase(java.util.Locale.ROOT).startsWith(
                        "text/plain")) {
            out.text = payload;
        }
        return out;
    }

    private static int indexOfBlankLine(String s, int from) {
        int i = s.indexOf("\r\n\r\n", from);
        int j = s.indexOf("\n\n", from);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    private static int afterBlankLine(String s, int blankPos) {
        if (blankPos < 0) return s.length();
        if (s.startsWith("\r\n\r\n", blankPos)) return blankPos + 4;
        if (s.startsWith("\n\n", blankPos)) return blankPos + 2;
        return blankPos;
    }

    /**
     * The address to advertise: the registered IMS address, which MSRP must leave from, else any
     * non-loopback address. As {@link CpmSessionEngine#resolveLocalMsrpHost}.
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
        } catch (final Throwable ignore) {
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
        } catch (final Throwable ignore) {
        }
        return "0.0.0.0";
    }

    private int reserveLocalMsrpPort() {
        try (java.net.ServerSocket srv = new java.net.ServerSocket(0)) {
            return srv.getLocalPort();
        } catch (final Throwable t) {
            return 2855;
        }
    }
}

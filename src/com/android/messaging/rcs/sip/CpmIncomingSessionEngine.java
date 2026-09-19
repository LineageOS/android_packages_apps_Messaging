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

import androidx.annotation.Nullable;

import com.android.messaging.rcs.carrier.msrp.MsrpEndFlag;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;
import com.android.messaging.rcs.carrier.msrp.MsrpTransactionId;
import com.android.messaging.rcs.carrier.msrp.session.MsrpChatSession;
import com.android.messaging.rcs.carrier.msrp.session.MsrpTlsConnection;
import com.android.messaging.rcs.carrier.msrp.session.SdpAnswer;
import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;
import com.android.messaging.util.LogUtil;

import java.nio.charset.StandardCharsets;

/**
 * Drives the UAS (answerer) side of an <b>inbound</b> CPM 1-1 SESSION-mode chat:
 * a peer sent us an {@code INVITE} carrying an SDP offer on our granted
 * {@code oma.cpm.session} tag and we must accept it, open the MSRP media leg, and
 * surface the received {@code message/cpim} text as an
 * {@link org.lineageos.rcs.provider.RcsIncomingMessage} (design §6.2 + spec §7.1;
 * this is the receive-side mirror of {@link CpmSessionEngine}).
 *
 * <pre>
 *   ◀── INVITE (SDP offer)         (routed here by {@link SipDelegateClient})
 *   200 OK (SDP answer) ──delegate──▶ network
 *   ◀── ACK
 *   ── open MSRP-over-TLS to the peer relay (a=path from the offer)
 *   ── bootstrap empty MSRP SEND (RFC 4975 §4.2.2 — active endpoint sends first)
 *   ◀── MSRP SEND (message/cpim wrapping the peer's text)
 *   MSRP 200 ──▶            → parse CPIM → onIncomingText(...)
 *   ◀── BYE / idle          → BYE 200 + close MSRP
 * </pre>
 *
 * <p><b>Connection model.</b> We answer {@code setup:active} (the RFC 6135 §5.2
 * complement of the peer's {@code actpass}/{@code passive} offer) and connect
 * <i>out</i> to the MSRP switch/relay named in the peer's SDP {@code a=path} —
 * the common carrier-relay topology, and the one {@link ImsMsrpSocketFactory}
 * (active connect, IMS-bound) already implements. A true peer-to-peer offer with
 * {@code setup:active} (peer connects to us) would need us to bind + listen on the
 * IMS bearer, which the socket factory does not do today.
 *
 * RIG-VERIFY(rcs-framework): confirm on the IMS test rig that inbound CPM
 * sessions terminate on the carrier MSRP relay (answerer connects active) rather
 * than peer-to-peer; if any carrier offers {@code setup:active} to us, add a
 * bound IMS-listen ServerSocket to {@link ImsMsrpSocketFactory} for the passive
 * answer path.
 */
public final class CpmIncomingSessionEngine {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CpmIncomingSession";

    /** Surface for a received inbound 1-1 text. */
    public interface InboundListener {
        /**
         * @param fromE164   the peer ({@code +E164}; may be null if unparyseable).
         * @param body       decoded UTF-8 text/plain.
         * @param messageId  the CPIM {@code imdn.Message-ID} (echoed back in
         *                   {@code sendImdn} by the client), or a synthesized id.
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

    /** Accept the INVITE: parse the offer, answer 200 + SDP, open MSRP. Blocks
     *  only through the answer + connect; inbound SEND arrives on the MSRP
     *  listener thread. Returns true if the session was accepted. */
    public boolean accept() {
        try {
            final byte[] offerBytes = mInvite.body;
            if (offerBytes == null || offerBytes.length == 0) {
                LogUtil.w(TAG, SUBTAG + ": INVITE without SDP; declining 488");
                sendResponse(488, "Not Acceptable Here", null);
                return false;
            }
            final SdpOffer offer = SdpOffer.parse(offerBytes);
            LogUtil.i(TAG, SUBTAG + ": inbound INVITE from=" + mInvite.fromE164
                    + " relay=" + offer.getHost() + ":" + offer.getPort()
                    + " path=" + offer.getMsrpPath() + " setup=" + offer.getSetup());
            mRemoteMsrpUri = offer.getMsrpPath();

            // Local TLS creds + endpoint for the answer.
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

            // 200 OK (SDP answer). The framework/modem rewrites routing headers;
            // we echo Via/From/To/Call-ID/CSeq and add our To-tag + Contact.
            sendResponse(200, "OK", answerBytes);

            // Open the MSRP leg to the peer relay (active connect, IMS-bound).
            final SdpOffer.SetupRole localRole = SdpAnswer.complementarySetup(offer.getSetup());
            if (localRole == SdpOffer.SetupRole.PASSIVE) {
                // Peer offered setup:active => peer connects to us. Not wired.
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
            // RFC 4975 §4.2.2: the active endpoint sends the first SEND to bind
            // the session on the relay (bodiless bootstrap).
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

    /** Route an in-dialog request (ACK/BYE) that {@link SipDelegateClient}
     *  matched to our Call-ID. */
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
        // ACK is absorbed (no response); the session is already up.
    }

    // ---- internals ----

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
        // Reply MSRP 200 to the SEND (if it requested one — default yes).
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
            return; // bootstrap / empty SEND
        }
        final Cpim cpim = parseCpim(body);
        if (cpim.text == null) {
            LogUtil.i(TAG, SUBTAG + ": inbound SEND had no text/plain part (ct="
                    + cpim.innerContentType + ") — not surfaced as a message");
            return;
        }
        final String messageId = cpim.imdnMessageId != null
                ? cpim.imdnMessageId : java.util.UUID.randomUUID().toString();
        LogUtil.i(TAG, SUBTAG + ": inbound text from=" + mInvite.fromE164
                + " id=" + messageId + " bytes=" + cpim.text.length());
        try {
            mListener.onIncomingText(mInvite.fromE164, cpim.text, messageId);
        } catch (final Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": InboundListener threw", t);
        }
    }

    /** Close the MSRP leg + release the socket (idempotent). Public so {@link
     *  SipDelegateClient} can enforce the inbound-session leak-guard TTL (LOW-3). */
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
                // Inbound REPORT (peer's delivery report for something we sent) —
                // not applicable on the pure-receive leg; log only.
                LogUtil.i(TAG, SUBTAG + ": <<< MSRP REPORT " + frame.getStatus());
            }
        }

        @Override
        public void onClosed(MsrpChatSession.CloseReason reason, String detail) {
            LogUtil.i(TAG, SUBTAG + ": MSRP closed reason=" + reason + " detail=" + detail);
        }
    }

    // ---- minimal CPIM parser (RFC 3862): pull the inner text/plain + Message-ID.
    private static final class Cpim {
        String imdnMessageId;
        String innerContentType;
        String text;
    }

    private static Cpim parseCpim(byte[] body) {
        final Cpim out = new Cpim();
        final String s = new String(body, StandardCharsets.UTF_8);
        // CPIM = outer headers, blank line, inner MIME headers, blank line, payload.
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
                || out.innerContentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain")) {
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
     * The local host to advertise in the SDP answer — the registered IMS-PDN
     * address (same bearer MSRP must egress from), else a non-loopback local
     * address. Mirrors {@link CpmSessionEngine#resolveLocalMsrpHost}.
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

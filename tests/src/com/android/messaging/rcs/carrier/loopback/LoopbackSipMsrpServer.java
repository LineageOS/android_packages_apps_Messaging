/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.loopback;

import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;

import java.util.List;
import java.util.Map;

/**
 * In-process stand-in for a P-CSCF and MSRP relay on {@code 127.0.0.1}: a {@link LoopbackSipServer}
 * (Digest-challenged registration, session invitations answered with SDP pointing at the MSRP
 * server, pager messages, bye) and a {@link LoopbackMsrpServer} whose certificate fingerprint the
 * SDP answer carries. Use with try-with-resources; {@link #start} is idempotent and returns this.
 */
public final class LoopbackSipMsrpServer implements AutoCloseable {

    private final LoopbackSipServer sip;
    private final LoopbackMsrpServer msrp;
    private boolean started;

    private LoopbackSipMsrpServer(LoopbackSipServer sip, LoopbackMsrpServer msrp) {
        this.sip = sip;
        this.msrp = msrp;
    }

    public LoopbackSipMsrpServer start() {
        if (started) return this;
        msrp.start();
        sip.start();
        started = true;
        return this;
    }

    public int getSipPort()  { return sip.getPort(); }
    public int getMsrpPort() { return msrp.getPort(); }

    /** Lowercase colon-separated hex. */
    public String getMsrpFingerprintSha256() {
        return msrp.getFingerprintSha256ColonHex();
    }

    public String getMsrpFingerprintAlg() {
        return SdpOffer.FINGERPRINT_ALG_SHA256;
    }

    /** Registrations in arrival order, including the unauthenticated first attempt. */
    public List<SimpleSipMessage> getReceivedSipRegisters() {
        return sip.getReceivedRegisters();
    }

    public List<SimpleSipMessage> getReceivedSipInvites() {
        return sip.getReceivedInvites();
    }

    public List<SimpleSipMessage> getReceivedSipMessages() {
        return sip.getReceivedMessages();
    }

    public List<SimpleSipMessage> getReceivedSipByes() {
        return sip.getReceivedByes();
    }

    public List<SimpleSipMessage> getReceivedSipAcks() {
        return sip.getReceivedAcks();
    }

    /** Per-session records keyed by Call-ID, in arrival order. */
    public Map<String, LoopbackSipServer.SessionRecord> getSipSessions() {
        return sip.getSessions();
    }

    /** One entry per SEND chunk, so a chunked message appears as several. */
    public List<MsrpMessage> getReceivedMsrpSends() {
        return msrp.getReceivedSends();
    }

    public List<MsrpMessage> getReceivedMsrpReports() {
        return msrp.getReceivedReports();
    }

    /** Responses from the client; empty, since the loopback never sends a request. */
    public List<MsrpMessage> getReceivedMsrpResponses() {
        return msrp.getReceivedResponses();
    }

    /** Notified on every inbound SIP request, for tests that wait rather than poll. */
    public void setSipInboundCallback(LoopbackSipServer.InboundMessageCallback cb) {
        sip.setInboundCallback(cb);
    }

    @Override
    public void close() {
        try { sip.close(); } catch (Exception ignore) {}
        try { msrp.close(); } catch (Exception ignore) {}
    }

    public static final class Builder {
        private int sipPort = 0;
        private int msrpPort = 0;
        private String digestRealm = "loopback.test";
        private String digestUsername = "+15715551234";
        private String digestPassword = "test-secret";
        private Integer registerForcedStatus;
        private String registerForcedReason;
        private int sendFailStatus;
        private String sendFailReason;
        private int sendFailCount;
        private String certDn = "CN=loopback.test,OU=MessagingTests,O=LineageOS";
        private int rsaBits = 2048;
        private long certValiditySec = 3600;

        public Builder sipPort(int p)   { this.sipPort = p; return this; }
        public Builder msrpPort(int p)  { this.msrpPort = p; return this; }
        public Builder digestRealm(String r)    { this.digestRealm = r; return this; }
        public Builder digestUsername(String u) { this.digestUsername = u; return this; }
        public Builder digestPassword(String p) { this.digestPassword = p; return this; }

        /** Answer every registration with this status instead of the challenge. */
        public Builder forceRegisterStatus(int code, String reason) {
            this.registerForcedStatus = code;
            this.registerForcedReason = reason;
            return this;
        }

        /** Answer the next {@code count} SEND frames with this status, then 200 again. */
        public Builder msrpSendFailureForNext(int count, int status, String reason) {
            this.sendFailCount = count;
            this.sendFailStatus = status;
            this.sendFailReason = reason;
            return this;
        }

        public Builder certDn(String d)        { this.certDn = d; return this; }
        public Builder rsaBits(int b)          { this.rsaBits = b; return this; }
        public Builder certValiditySec(long s) { this.certValiditySec = s; return this; }

        public LoopbackSipMsrpServer build() throws Exception {
            LoopbackMsrpServer.Builder msrpB = new LoopbackMsrpServer.Builder()
                    .port(msrpPort)
                    .dn(certDn)
                    .rsaBits(rsaBits)
                    .validitySec(certValiditySec);
            if (sendFailCount > 0) {
                msrpB.sendStatusCodeForNextSends(sendFailStatus, sendFailReason, sendFailCount);
            }
            LoopbackMsrpServer msrp = msrpB.build();

            LoopbackSipServer.Builder sipB = new LoopbackSipServer.Builder()
                    .port(sipPort)
                    .digestRealm(digestRealm)
                    .digestUsername(digestUsername)
                    .digestPassword(digestPassword);
            if (registerForcedStatus != null) {
                sipB.forceRegisterStatus(registerForcedStatus, registerForcedReason);
            }
            LoopbackSipServer sip = sipB.build(
                    msrp.getPort(),
                    SdpOffer.FINGERPRINT_ALG_SHA256,
                    msrp.getFingerprintSha256ColonHex());
            return new LoopbackSipMsrpServer(sip, msrp);
        }
    }
}

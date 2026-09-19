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
package com.android.messaging.rcs.carrier.loopback;

import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;

import java.util.List;
import java.util.Map;

/**
 * In-process SIP + MSRP-over-TLS server stand-in for a carrier P-CSCF + MSRP
 * relay. Test scaffolding — it unblocks end-to-end integration of
 * chat-session establishment and
 * MSRP-session SEND without a viable carrier endpoint
 * (T-Mobile US is backed by a commercial RCS platform and rejects our
 * REGISTER; the Verizon test Pixel is bricked).
 *
 * <p>Composes:
 *
 * <ul>
 *   <li>A {@link LoopbackSipServer} bound to {@code 127.0.0.1:0} (or
 *       caller-supplied port). Speaks SIP-over-TCP and handles REGISTER
 *       (401-Digest-then-200), INVITE (200 OK with SDP answer pointing at
 *       our MSRP server), MESSAGE (202 Accepted), BYE (200 OK).</li>
 *   <li>A {@link LoopbackMsrpServer} bound to {@code 127.0.0.1:0} on an
 *       independent port. Speaks MSRP-over-TLS with an ephemeral self-signed
 *       cert (SHA-256 fingerprint surfaced for the SDP {@code a=fingerprint:}
 *       attribute, validated by the production
 *       {@code MsrpTlsConnection.computePeerFingerprint}).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * try (LoopbackSipMsrpServer server = new LoopbackSipMsrpServer.Builder()
 *         .sipPort(0)
 *         .msrpPort(0)
 *         .digestRealm("loopback.test")
 *         .digestUsername("+15715551234")
 *         .digestPassword("test-secret")
 *         .build().start()) {
 *     int sipPort = server.getSipPort();
 *     int msrpPort = server.getMsrpPort();
 *     String fp = server.getMsrpFingerprintSha256();
 *     // ... point an RcsImsConfig at sipPort + drive your CarrierSipRegistrar /
 *     //     CarrierMessageSender / CarrierMsrpSessionManager / etc.
 *
 *     List<MsrpMessage> received = server.getReceivedMsrpSends();
 *     assertEquals(1, received.size());
 * }
 * }</pre>
 *
 * <p>Threading: both internal servers run accept loops + per-connection reader
 * threads as daemons. {@link #close} closes the listening sockets and joins
 * the threads with a short timeout. {@link #start} is idempotent. Returns the
 * same {@code LoopbackSipMsrpServer} so the builder pattern can chain into a
 * try-with-resources.
 *
 * <p>Pure Java, no Android imports, no JAIN-SIP. Test-only — NOT shipped in
 * the production APK (lives under {@code tests/src/}).
 */
public final class LoopbackSipMsrpServer implements AutoCloseable {

    private final LoopbackSipServer sip;
    private final LoopbackMsrpServer msrp;
    private boolean started;

    private LoopbackSipMsrpServer(LoopbackSipServer sip, LoopbackMsrpServer msrp) {
        this.sip = sip;
        this.msrp = msrp;
    }

    /** Spawn the accept loops. Idempotent. Returns {@code this} for chaining. */
    public LoopbackSipMsrpServer start() {
        if (started) return this;
        msrp.start();
        sip.start();
        started = true;
        return this;
    }

    // ---------- ports / fingerprint ----------

    public int getSipPort()  { return sip.getPort(); }
    public int getMsrpPort() { return msrp.getPort(); }

    /** Colon-separated lowercase hex form per RFC 4572 §5. */
    public String getMsrpFingerprintSha256() {
        return msrp.getFingerprintSha256ColonHex();
    }

    public String getMsrpFingerprintAlg() {
        return SdpOffer.FINGERPRINT_ALG_SHA256;
    }

    // ---------- received-traffic accessors ----------

    /** All SIP REGISTER requests seen (in order), including the unauth'd
     *  first attempt that triggered our 401 challenge. */
    public List<SimpleSipMessage> getReceivedSipRegisters() {
        return sip.getReceivedRegisters();
    }

    /** All SIP INVITE requests seen (in order). */
    public List<SimpleSipMessage> getReceivedSipInvites() {
        return sip.getReceivedInvites();
    }

    /** All SIP MESSAGE requests seen (in order). */
    public List<SimpleSipMessage> getReceivedSipMessages() {
        return sip.getReceivedMessages();
    }

    /** All SIP BYE requests seen (in order). */
    public List<SimpleSipMessage> getReceivedSipByes() {
        return sip.getReceivedByes();
    }

    /** All SIP ACK requests seen (in order). */
    public List<SimpleSipMessage> getReceivedSipAcks() {
        return sip.getReceivedAcks();
    }

    /** Per-session records keyed by Call-ID, in INVITE arrival order. */
    public Map<String, LoopbackSipServer.SessionRecord> getSipSessions() {
        return sip.getSessions();
    }

    /** All MSRP SEND requests received (one per chunk, so a long message
     *  reassembled by the client appears here as multiple frames). */
    public List<MsrpMessage> getReceivedMsrpSends() {
        return msrp.getReceivedSends();
    }

    /** All MSRP REPORT requests received (delivery acks from the client). */
    public List<MsrpMessage> getReceivedMsrpReports() {
        return msrp.getReceivedReports();
    }

    /** All MSRP responses received (the client's acks to our SENDs, if we
     *  ever initiate — which the loopback never does today). */
    public List<MsrpMessage> getReceivedMsrpResponses() {
        return msrp.getReceivedResponses();
    }

    /** Install a callback notified for every inbound SIP request. Used by
     *  tests that need synchronous wakeups (rather than polling the
     *  received-* lists). */
    public void setSipInboundCallback(LoopbackSipServer.InboundMessageCallback cb) {
        sip.setInboundCallback(cb);
    }

    @Override
    public void close() {
        try { sip.close(); } catch (Exception ignore) {}
        try { msrp.close(); } catch (Exception ignore) {}
    }

    // ---------- builder ----------

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

        /** Force every REGISTER to be rejected with this status (skips the
         *  401-then-200 dance). Used for failure-path tests. */
        public Builder forceRegisterStatus(int code, String reason) {
            this.registerForcedStatus = code;
            this.registerForcedReason = reason;
            return this;
        }

        /** Make the MSRP server respond to the next {@code count} SEND frames
         *  with the supplied non-200 status, then revert to 200 OK. Useful
         *  for exercising the client's REPORT-failed path. */
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

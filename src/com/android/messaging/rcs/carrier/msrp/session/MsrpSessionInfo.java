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
package com.android.messaging.rcs.carrier.msrp.session;

import java.util.Collections;
import java.util.List;

/**
 * Immutable session-binding parameters resolved from a successful SDP
 * offer/answer round-trip. The {@link MsrpChatSession} stashes one of these
 * on transition into {@link MsrpChatSession.State#ESTABLISHED} so the MSRP
 * SEND/REPORT path can plumb {@code To-Path} / {@code From-Path} and
 * {@code Content-Type} restrictions correctly per RFC 4975 §7.1.
 *
 * <p>Pure data carrier; no I/O.
 */
public final class MsrpSessionInfo {

    /** UUIDs etc.: carrier "Conversation-ID" tying SIP and MSRP layers together. */
    private final String contributionId;
    /** The per-conversation Conversation-ID (UUID). May be null when absent. */
    private final String conversationId;

    /** SIP Call-ID for the INVITE dialog. */
    private final String sipCallId;

    /** Local MSRP URI ({@code msrps://host:port/sess-id;tcp}); the {@code From-Path} on our SENDs. */
    private final String localMsrpUri;
    /** Remote MSRP URI; the {@code To-Path} on our SENDs. */
    private final String remoteMsrpUri;

    /** Local TLS cert fingerprint we asserted in our SDP (RFC 4572). May be null for cleartext. */
    private final String localFingerprintAlg;
    private final String localFingerprintHex;

    /** Remote TLS cert fingerprint from the peer's SDP (we validate the peer cert against this). */
    private final String remoteFingerprintAlg;
    private final String remoteFingerprintHex;

    /** Negotiated content-types (intersected accept-types from offer/answer). */
    private final List<String> acceptTypes;
    private final List<String> acceptWrappedTypes;

    /** Our role for the underlying TCP/TLS connection per RFC 6135. */
    private final SdpOffer.SetupRole localRole;

    /** RFC 4566 ("IP4"/"IP6") address family for the connection. */
    private final String addrType;
    /** Remote host (from peer's c= line) for outbound TLS connect. */
    private final String remoteHost;
    /** Remote port (from peer's m= line). */
    private final int remotePort;

    private MsrpSessionInfo(Builder b) {
        this.contributionId = b.contributionId;
        this.conversationId = b.conversationId;
        this.sipCallId = b.sipCallId;
        this.localMsrpUri = b.localMsrpUri;
        this.remoteMsrpUri = b.remoteMsrpUri;
        this.localFingerprintAlg = b.localFingerprintAlg;
        this.localFingerprintHex = b.localFingerprintHex;
        this.remoteFingerprintAlg = b.remoteFingerprintAlg;
        this.remoteFingerprintHex = b.remoteFingerprintHex;
        this.acceptTypes = b.acceptTypes == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(b.acceptTypes));
        this.acceptWrappedTypes = b.acceptWrappedTypes == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(b.acceptWrappedTypes));
        this.localRole = b.localRole;
        this.addrType = b.addrType;
        this.remoteHost = b.remoteHost;
        this.remotePort = b.remotePort;
    }

    public String getContributionId()      { return contributionId; }
    public String getConversationId()      { return conversationId; }
    public String getSipCallId()           { return sipCallId; }
    public String getLocalMsrpUri()        { return localMsrpUri; }
    public String getRemoteMsrpUri()       { return remoteMsrpUri; }
    public String getLocalFingerprintAlg() { return localFingerprintAlg; }
    public String getLocalFingerprintHex() { return localFingerprintHex; }
    public String getRemoteFingerprintAlg(){ return remoteFingerprintAlg; }
    public String getRemoteFingerprintHex(){ return remoteFingerprintHex; }
    public List<String> getAcceptTypes()         { return acceptTypes; }
    public List<String> getAcceptWrappedTypes()  { return acceptWrappedTypes; }
    public SdpOffer.SetupRole getLocalRole()     { return localRole; }
    public String getAddrType()            { return addrType; }
    public String getRemoteHost()          { return remoteHost; }
    public int    getRemotePort()          { return remotePort; }

    /** True iff our local role is {@code active} — i.e. we open the TCP/TLS socket. */
    public boolean weActivelyConnect() {
        return localRole == SdpOffer.SetupRole.ACTIVE;
    }

    /** True iff a given inner CPIM content-type is on the negotiated {@code accept-wrapped-types} list. */
    public boolean acceptsWrappedType(String contentType) {
        return acceptWrappedTypes.contains(contentType);
    }

    /** True iff a given outer MSRP content-type is on the negotiated {@code accept-types} list. */
    public boolean acceptsType(String contentType) {
        return acceptTypes.contains(contentType);
    }

    @Override
    public String toString() {
        return "MsrpSessionInfo{role=" + localRole
                + " local=" + localMsrpUri
                + " remote=" + remoteMsrpUri
                + " remoteHost=" + remoteHost + ":" + remotePort
                + " contribution=" + contributionId
                + "}";
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String contributionId;
        private String conversationId;
        private String sipCallId;
        private String localMsrpUri;
        private String remoteMsrpUri;
        private String localFingerprintAlg;
        private String localFingerprintHex;
        private String remoteFingerprintAlg;
        private String remoteFingerprintHex;
        private List<String> acceptTypes;
        private List<String> acceptWrappedTypes;
        private SdpOffer.SetupRole localRole;
        private String addrType = "IP4";
        private String remoteHost;
        private int remotePort;

        public Builder contributionId(String s)   { this.contributionId = s; return this; }
        public Builder conversationId(String s)   { this.conversationId = s; return this; }
        public Builder sipCallId(String s)        { this.sipCallId = s; return this; }
        public Builder localMsrpUri(String s)     { this.localMsrpUri = s; return this; }
        public Builder remoteMsrpUri(String s)    { this.remoteMsrpUri = s; return this; }

        public Builder localFingerprint(String alg, String hex) {
            this.localFingerprintAlg = alg;
            this.localFingerprintHex = hex;
            return this;
        }

        public Builder remoteFingerprint(String alg, String hex) {
            this.remoteFingerprintAlg = alg;
            this.remoteFingerprintHex = hex;
            return this;
        }

        public Builder acceptTypes(List<String> t)         { this.acceptTypes = t; return this; }
        public Builder acceptWrappedTypes(List<String> t)  { this.acceptWrappedTypes = t; return this; }
        public Builder localRole(SdpOffer.SetupRole r)     { this.localRole = r; return this; }
        public Builder addrType(String t)         { this.addrType = t; return this; }
        public Builder remoteHost(String h)       { this.remoteHost = h; return this; }
        public Builder remotePort(int p)          { this.remotePort = p; return this; }

        public MsrpSessionInfo build() {
            if (localMsrpUri == null || remoteMsrpUri == null) {
                throw new IllegalStateException(
                        "MsrpSessionInfo: local/remote MSRP URIs required");
            }
            if (localRole == null) {
                throw new IllegalStateException("MsrpSessionInfo: localRole required");
            }
            return new MsrpSessionInfo(this);
        }
    }
}

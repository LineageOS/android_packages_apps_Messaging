/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp.session;

import java.util.Collections;
import java.util.List;

/**
 * The session parameters settled by an SDP offer and answer, held by an established
 * {@link MsrpChatSession} for its To-Path, From-Path and accepted types (RFC 4975 §7.1).
 */
public final class MsrpSessionInfo {

    /** The {@code Contribution-ID} tying the SIP and MSRP layers together. */
    private final String contributionId;
    /** The {@code Conversation-ID}, or null. */
    private final String conversationId;

    private final String sipCallId;

    /** Our MSRP URI: the {@code From-Path} of our SENDs. */
    private final String localMsrpUri;
    /** The peer's MSRP URI: the {@code To-Path} of our SENDs. */
    private final String remoteMsrpUri;

    /** The fingerprint our SDP asserted (RFC 4572), or null for cleartext. */
    private final String localFingerprintAlg;
    private final String localFingerprintHex;

    /** The peer's SDP fingerprint, which its certificate is checked against. */
    private final String remoteFingerprintAlg;
    private final String remoteFingerprintHex;

    /** The negotiated (intersected) accept lists. */
    private final List<String> acceptTypes;
    private final List<String> acceptWrappedTypes;

    /** Our connection role (RFC 6135). */
    private final SdpOffer.SetupRole localRole;

    private final String addrType;
    /** From the peer's {@code c=} line. */
    private final String remoteHost;
    /** From the peer's {@code m=} line. */
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

    public boolean weActivelyConnect() {
        return localRole == SdpOffer.SetupRole.ACTIVE;
    }

    public boolean acceptsWrappedType(String contentType) {
        return acceptWrappedTypes.contains(contentType);
    }

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
        public Builder acceptWrappedTypes(List<String> t)  { this.acceptWrappedTypes =
                t; return this; }
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

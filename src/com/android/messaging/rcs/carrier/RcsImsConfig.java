/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

/**
 * The SIP and IMS parameters of the carrier's autoconfiguration document, consumed by
 * {@link CarrierSipRegistrar}. Plain Java so it is host-testable; parsing lives in
 * {@link AcsImsConfigParser}.
 */
public final class RcsImsConfig {

    public final String pcscfAddress;
    public final int pcscfPort;           // -1: the transport's default
    public final String domain;
    public final String privateIdentity;  // the IMPI
    public final String publicIdentity;   // the IMPU, usually a tel: URI
    public final String userName;
    public final String authDigestUsername;
    public final String authDigestPassword;
    public final String authDigestRealm;
    public final String authenticationScheme;  // "Digest", "AKAv1-MD5", ...
    /**
     * {@link #authDigestPassword} holds a Digest HA1 rather than a password, used without
     * re-hashing.
     */
    public final boolean authDigestIsHa1;
    public final String psSipTransport;   // "SIPoTLS", "SIPoTCP" or "SIPoUDP"
    public final String wifiSipTransport;
    // File transfer over HTTP (RCC.07): null when not provisioned.
    public final String ftContentServerUri;   // ftHTTPCSURI
    public final String ftDownloadUri;         // ftHTTPDLURI
    public final String ftCsUser;              // ftHTTPCSUser
    public final String ftCsPassword;          // ftHTTPCSPwd
    public final long ftMaxSizeBytes;          // MaxSizeFileTr; 0 when unset
    public final String psMediaTransport;     // "MSRPoTLS" or "MSRPoTCP"
    public final String wifiMediaTransport;
    public final String phoneContext;
    public final int localSipPort;        // 0: ephemeral
    public final boolean keepAlive;
    public final int t1Ms;
    public final int t2Ms;
    public final int t4Ms;
    public final int regRetryBaseSec;
    public final int regRetryMaxSec;
    public final float q;

    private RcsImsConfig(Builder b) {
        this.pcscfAddress = b.pcscfAddress;
        this.pcscfPort = b.pcscfPort;
        this.domain = b.domain;
        this.privateIdentity = b.privateIdentity;
        this.publicIdentity = b.publicIdentity;
        this.userName = b.userName;
        this.authDigestUsername = b.authDigestUsername;
        this.authDigestPassword = b.authDigestPassword;
        this.authDigestRealm = b.authDigestRealm;
        this.authenticationScheme = b.authenticationScheme;
        this.authDigestIsHa1 = b.authDigestIsHa1;
        this.psSipTransport = b.psSipTransport;
        this.wifiSipTransport = b.wifiSipTransport;
        this.ftContentServerUri = b.ftContentServerUri;
        this.ftDownloadUri = b.ftDownloadUri;
        this.ftCsUser = b.ftCsUser;
        this.ftCsPassword = b.ftCsPassword;
        this.ftMaxSizeBytes = b.ftMaxSizeBytes;
        this.psMediaTransport = b.psMediaTransport;
        this.wifiMediaTransport = b.wifiMediaTransport;
        this.phoneContext = b.phoneContext;
        this.localSipPort = b.localSipPort;
        this.keepAlive = b.keepAlive;
        this.t1Ms = b.t1Ms;
        this.t2Ms = b.t2Ms;
        this.t4Ms = b.t4Ms;
        this.regRetryBaseSec = b.regRetryBaseSec;
        this.regRetryMaxSec = b.regRetryMaxSec;
        this.q = b.q;
    }

    /** The JAIN-SIP transport name for the Wi-Fi or cellular setting; TLS when unset or unknown. */
    public String jainSipTransport(boolean onWifi) {
        String t = onWifi ? wifiSipTransport : psSipTransport;
        if (t == null) return "tls";
        switch (t) {
            case "SIPoTLS": return "tls";
            case "SIPoTCP": return "tcp";
            case "SIPoUDP": return "udp";
            default: return "tls";
        }
    }

    /** The configured port, else 5061 for TLS and 5060 otherwise. */
    public int effectivePcscfPort(boolean onWifi) {
        if (pcscfPort > 0) return pcscfPort;
        return "tls".equals(jainSipTransport(onWifi)) ? 5061 : 5060;
    }

    @Override
    public String toString() {
        // Omits the password.
        return "RcsImsConfig{pcscf=" + pcscfAddress + ":" + pcscfPort
                + " domain=" + domain
                + " privateId=" + privateIdentity
                + " publicId=" + publicIdentity
                + " user=" + userName
                + " realm=" + authDigestRealm
                + " scheme=" + authenticationScheme
                + " psSip=" + psSipTransport
                + " psMedia=" + psMediaTransport + "}";
    }

    public static final class Builder {
        public String pcscfAddress;
        public int pcscfPort = -1;
        public String domain;
        public String privateIdentity;
        public String publicIdentity;
        public String userName;
        public boolean authDigestIsHa1;
        public String authDigestUsername;
        public String authDigestPassword;
        public String authDigestRealm;
        public String authenticationScheme = "Digest";
        public String psSipTransport = "SIPoTLS";
        public String wifiSipTransport = "SIPoTLS";
        public String ftContentServerUri;
        public String ftDownloadUri;
        public String ftCsUser;
        public String ftCsPassword;
        public long ftMaxSizeBytes;
        public String psMediaTransport = "MSRPoTLS";
        public String wifiMediaTransport = "MSRPoTLS";
        public String phoneContext;
        public int localSipPort;
        public boolean keepAlive = true;
        public int t1Ms = 500;
        public int t2Ms = 4000;
        public int t4Ms = 5000;
        public int regRetryBaseSec = 30;
        public int regRetryMaxSec = 1800;
        public float q = 0.5f;

        public RcsImsConfig build() { return new RcsImsConfig(this); }
    }
}

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

/**
 * Typed view of the {@code Configuration.mImsConfiguration} blob — the SIP /
 * IMS layer of the OMA-CP wap-provisioningdoc returned by the carrier ACS.
 *
 * <p>Field set taken from a real T-Mobile US provisioning and from Google
 * Messages' own Configuration parser.
 *
 * <p>This POJO is the bridge between {@code Pev3Client} (which fetches the
 * OMA-CP XML / JSON config from the ACS) and {@link CarrierSipRegistrar}
 * (which REGISTERs against the carrier P-CSCF). Lives in the carrier package
 * because the OTT path doesn't need it; lives in plain Java (no Android
 * deps, no org.json) so it can be unit-tested off-device. JSON parsing is
 * in the sibling {@link RcsImsConfigParser}.
 */
public final class RcsImsConfig {

    public final String pcscfAddress;
    public final int pcscfPort;           // -1 => default-by-transport
    public final String domain;
    public final String privateIdentity;  // SIP-URI form: user@homedomain
    public final String publicIdentity;   // typically tel: URI
    public final String userName;
    public final String authDigestUsername;
    public final String authDigestPassword;
    public final String authDigestRealm;
    public final String authenticationScheme;  // "Digest" / "AKAv1-MD5" / ...
    /** When true, {@link #authDigestPassword} is a PRECOMPUTED Digest HA1
     *  (MD5(username:realm:password)) rather than a cleartext password — the
     *  registrar uses it directly and does not re-hash. Set when the ACS serves
     *  AAuthType=Digest-HA1 (no cleartext secret client-side). */
    public final boolean authDigestIsHa1;
    public final String psSipTransport;   // "SIPoTLS" | "SIPoTCP" | "SIPoUDP"
    public final String wifiSipTransport;
    // FT-HTTP (RCC.07 file transfer): content-server upload/download endpoints +
    // HTTP Basic credentials, from the ACS FT characteristic. Null when the ACS
    // doesn't provision FT (FT then unavailable).
    public final String ftContentServerUri;   // ftHTTPCSURI (upload)
    public final String ftDownloadUri;         // ftHTTPDLURI (download)
    public final String ftCsUser;              // ftHTTPCSUser (Basic auth user)
    public final String ftCsPassword;          // ftHTTPCSPwd  (Basic auth pw)
    public final long ftMaxSizeBytes;          // MaxSizeFileTr (0 => unset)
    /**
     * {@code openrcs-encryption-identity-proof} — base64 SignedEncryptionIdentityProof (§7.12.1).
     *
     * <p>Carried from the ACS document to the KDS enrolment VERBATIM. Empty until the ACS emits it.
     */
    public final String acsEncryptionIdentityProof;
    /** {@code openrcs-kds-uri}, if the ACS advertised one. Empty = use the built-in lab default. */
    public final String kdsUri;
    /** {@code openrcs-trust-anchors-*} — the generation-addressed signed list and its signer. */
    public final String trustAnchorsUri;
    public final long trustAnchorsGeneration;
    public final String trustAnchorsSigner;
    public final String psMediaTransport;     // "MSRPoTLS" | "MSRPoTCP"
    public final String wifiMediaTransport;
    public final String phoneContext;
    public final int localSipPort;        // 0 => ephemeral
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
        this.acsEncryptionIdentityProof = b.acsEncryptionIdentityProof == null
                ? "" : b.acsEncryptionIdentityProof;
        this.kdsUri = b.kdsUri == null ? "" : b.kdsUri;
        this.trustAnchorsUri = b.trustAnchorsUri == null ? "" : b.trustAnchorsUri;
        this.trustAnchorsGeneration = b.trustAnchorsGeneration;
        this.trustAnchorsSigner = b.trustAnchorsSigner == null ? "" : b.trustAnchorsSigner;
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

    /**
     * JAIN-SIP transport string ("tls" / "tcp" / "udp") for the active
     * network type. {@code onWifi=true} picks {@link #wifiSipTransport},
     * else {@link #psSipTransport}.
     */
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

    /** Default P-CSCF port for the configured transport (5061 TLS, 5060 TCP/UDP). */
    public int effectivePcscfPort(boolean onWifi) {
        if (pcscfPort > 0) return pcscfPort;
        return "tls".equals(jainSipTransport(onWifi)) ? 5061 : 5060;
    }

    @Override
    public String toString() {
        // Redact password.
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

    /** Mutable builder used by the parser and by unit tests. */
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
        public String acsEncryptionIdentityProof;
        public String kdsUri;
        public String trustAnchorsUri;
        public long trustAnchorsGeneration;
        public String trustAnchorsSigner;
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

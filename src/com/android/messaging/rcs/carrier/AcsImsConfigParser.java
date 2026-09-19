/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import android.util.Xml;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Reads the SIP-registration subset of an RCC.07/RCC.14 autoconfiguration document (OMA-CP
 * {@code wap-provisioningdoc}) into {@link RcsImsConfig} for DR: P-CSCF, realm, IMPI, Digest
 * secret, public identities, file-transfer endpoints and the E2EE configuration parameters.
 * See docs/rcs/carrier-transport.md.
 */
public final class AcsImsConfigParser {

    private AcsImsConfigParser() {}

    /** Any field may be null if the document omits it. */
    public static final class ImsSettings {
        @Nullable public String pcscfAddress;      // LBO_P-CSCF_Address/Address
        @Nullable public String realm;             // APPAUTH/Realm, the home domain
        @Nullable public String userName;          // APPAUTH/UserName, the IMPI
        @Nullable public String userPwd;           // APPAUTH/UserPwd: a password or an HA1
        /** {@link #userPwd} is a precomputed HA1, not a password. */
        public boolean pwdIsHa1;
        @Nullable public String publicIdSip;       // sip: public identity
        @Nullable public String publicIdMsisdn;    // tel: public identity without the scheme
        // File transfer over HTTP.
        @Nullable public String ftCsUri;
        @Nullable public String ftDlUri;
        @Nullable public String ftCsUser;
        @Nullable public String ftCsPwd;
        public long ftMaxSize;                     // MaxSizeFileTr; 0 when unset

        // E2EE service parameters. The document is how the network names this infrastructure, so
        // none of these is hard-coded.

        /** {@code openrcs-kds-uri}: where to enrol for an MLS client certificate. */
        @Nullable public String kdsUri;
        /** {@code openrcs-trust-anchors-uri}: the generation-addressed trust-anchor list. */
        @Nullable public String trustAnchorsUri;
        /** {@code openrcs-trust-anchors-generation}: the generation to fetch. */
        public long trustAnchorsGeneration;
        /** {@code openrcs-trust-anchors-signer}: base64 P-256 SPKI of the list's signer. */
        @Nullable public String trustAnchorsSigner;
        /**
         * {@code openrcs-encryption-identity-proof}: base64 {@code SignedEncryptionIdentityProof}
         * (RCC.16 §7.12.1). Passed on verbatim, never parsed or re-encoded.
         */
        @Nullable public String encryptionIdentityProof;

        /**
         * P-CSCF, realm, IMPI and a sip: public identity are present. The password is checked
         * separately, since on AKA deployments the key stays on the SIM.
         */
        public boolean hasSipEssentials() {
            return nonEmpty(pcscfAddress) && nonEmpty(realm)
                    && nonEmpty(userName) && nonEmpty(publicIdSip);
        }

        private static boolean nonEmpty(final String s) {
            return s != null && !s.isEmpty();
        }
    }

    /** Null on a parse error. */
    @Nullable
    public static ImsSettings parse(@Nullable final byte[] configXml) {
        if (configXml == null || configXml.length == 0) {
            return null;
        }
        final ImsSettings out = new ImsSettings();
        // Enclosing characteristic types, innermost last.
        final Deque<String> scope = new ArrayDeque<>();
        try {
            final XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new ByteArrayInputStream(configXml), StandardCharsets.UTF_8.name());
            int evt = p.getEventType();
            while (evt != XmlPullParser.END_DOCUMENT) {
                if (evt == XmlPullParser.START_TAG) {
                    final String tag = p.getName();
                    if ("characteristic".equalsIgnoreCase(tag)) {
                        scope.addLast(attr(p, "type"));
                    } else if ("parm".equalsIgnoreCase(tag)) {
                        handleParm(scope, attr(p, "name"), attr(p, "value"), out);
                    }
                } else if (evt == XmlPullParser.END_TAG) {
                    if ("characteristic".equalsIgnoreCase(p.getName()) && !scope.isEmpty()) {
                        scope.removeLast();
                    }
                }
                evt = p.next();
            }
        } catch (final XmlPullParserException | IOException | RuntimeException e) {
            return null;
        }
        return out;
    }

    /**
     * Null unless the SIP essentials and a secret are available: the document's UserPwd, else
     * {@code pwFallback}, which is always treated as a password.
     */
    @Nullable
    public static RcsImsConfig toRcsImsConfig(@Nullable final ImsSettings s,
            @Nullable final String pwFallback, final String transport) {
        if (s == null || !s.hasSipEssentials()) {
            return null;
        }
        final boolean fromDoc = s.userPwd != null && !s.userPwd.isEmpty();
        final String pw = fromDoc ? s.userPwd : pwFallback;
        if (pw == null || pw.isEmpty()) {
            return null;
        }
        final boolean isHa1 = fromDoc && s.pwdIsHa1;
        final String domain = s.realm;
        final String user = userPartOf(s.publicIdMsisdn, s.publicIdSip, s.userName);
        final String impu = s.publicIdSip;

        final RcsImsConfig.Builder b = new RcsImsConfig.Builder();
        b.pcscfAddress = s.pcscfAddress;
        b.pcscfPort = -1; // the transport's default port
        b.domain = domain;
        b.privateIdentity = s.userName;
        b.publicIdentity = impu;
        b.userName = user;
        b.authDigestUsername = s.userName;
        b.authDigestPassword = pw;
        b.authDigestIsHa1 = isHa1;
        b.authDigestRealm = domain;
        b.authenticationScheme = "Digest";
        b.psSipTransport = transport;
        b.wifiSipTransport = transport;
        b.ftContentServerUri = s.ftCsUri;
        b.ftDownloadUri = s.ftDlUri;
        b.ftCsUser = s.ftCsUser;
        b.ftCsPassword = s.ftCsPwd;
        b.ftMaxSizeBytes = s.ftMaxSize;
        b.acsEncryptionIdentityProof = s.encryptionIdentityProof;
        b.kdsUri = s.kdsUri;
        b.trustAnchorsUri = s.trustAnchorsUri;
        b.trustAnchorsGeneration = s.trustAnchorsGeneration;
        b.trustAnchorsSigner = s.trustAnchorsSigner;
        return b.build();
    }

    private static void handleParm(final Deque<String> scope, @Nullable final String name,
            @Nullable final String value, final ImsSettings out) {
        if (name == null) {
            return;
        }
        // APPAUTH: AuthType, Realm, UserName and UserPwd, plus the AAuth* aliases and an AuthType
        // containing "HA1" marking the secret as an HA1. Other clients read only the four standard
        // names and a Digest AuthType. Names match case-insensitively throughout.
        if (inScope(scope, "APPAUTH")) {
            if (eq(name, "Realm") || eq(name, "AAuthRealm")) {
                out.realm = value;
            } else if (eq(name, "UserName") || eq(name, "AAuthName")) {
                out.userName = value;
            } else if (eq(name, "UserPwd") || eq(name, "AAuthSecret")) {
                out.userPwd = value;
            } else if (eq(name, "AAuthType") || eq(name, "AuthType")) {
                out.pwdIsHa1 = value != null
                        && value.toUpperCase(java.util.Locale.ROOT).contains("HA1");
            }
        } else if (inScope(scope, "LBO_P-CSCF_Address") && eq(name, "Address")) {
            out.pcscfAddress = value;
        } else if (inScope(scope, "Public_user_identity_List")
                && eq(name, "Public_user_Identity")) {
            if (value != null && value.startsWith("tel:") && out.publicIdMsisdn == null) {
                out.publicIdMsisdn = value.substring("tel:".length());
            } else if (value != null && value.startsWith("sip:") && out.publicIdSip == null) {
                out.publicIdSip = value;
            }
        }
        // These names are unique in the document, so they match in any scope.
        if (eq(name, "ftHTTPCSURI")) {
            out.ftCsUri = value;
        } else if (eq(name, "ftHTTPDLURI")) {
            out.ftDlUri = value;
        } else if (eq(name, "ftHTTPCSUser")) {
            out.ftCsUser = value;
        } else if (eq(name, "ftHTTPCSPwd")) {
            out.ftCsPwd = value;
        } else if (eq(name, "MaxSizeFileTr") && value != null) {
            try { out.ftMaxSize = Long.parseLong(value.trim()); }
            catch (final NumberFormatException ignored) { /* leave 0 */ }
        }
        // Matched by name: the parameters appear in both the nested and the flat document forms.
        if (eq(name, "openrcs-kds-uri")) {
            out.kdsUri = value;
        } else if (eq(name, "openrcs-trust-anchors-uri")) {
            out.trustAnchorsUri = value;
        } else if (eq(name, "openrcs-trust-anchors-generation") && value != null) {
            try { out.trustAnchorsGeneration = Long.parseLong(value.trim()); }
            catch (final NumberFormatException ignored) { /* leave 0 */ }
        } else if (eq(name, "openrcs-trust-anchors-signer")) {
            out.trustAnchorsSigner = value;
        } else if (eq(name, "openrcs-encryption-identity-proof")) {
            out.encryptionIdentityProof = value;
        }
        // Digest realm is the home domain when APPAUTH gives none.
        if ((eq(name, "Home_network_domain_Name") || eq(name, "Home_network_domain_name"))
                && (out.realm == null || out.realm.isEmpty())) {
            out.realm = value;
        }
    }

    private static boolean eq(@Nullable final String a, final String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    /** Case-insensitive; matches any enclosing characteristic. */
    private static boolean inScope(final Deque<String> scope, final String type) {
        for (final String s : scope) {
            if (type.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    /** The self user part: the tel: MSISDN, else the sip: user part, else the IMPI local part. */
    private static String userPartOf(@Nullable final String msisdn,
            @Nullable final String sip, @Nullable final String impi) {
        if (msisdn != null && !msisdn.isEmpty()) {
            return msisdn.startsWith("+") ? msisdn.substring(1) : msisdn;
        }
        if (sip != null && sip.startsWith("sip:")) {
            String u = sip.substring(4);
            final int at = u.indexOf('@');
            if (at > 0) u = u.substring(0, at);
            return u;
        }
        if (impi != null) {
            final int at = impi.indexOf('@');
            return at > 0 ? impi.substring(0, at) : impi;
        }
        return "";
    }

    @Nullable
    private static String attr(final XmlPullParser p, final String name) {
        final int n = p.getAttributeCount();
        for (int i = 0; i < n; i++) {
            if (name.equalsIgnoreCase(p.getAttributeName(i))) {
                return p.getAttributeValue(i);
            }
        }
        return null;
    }
}

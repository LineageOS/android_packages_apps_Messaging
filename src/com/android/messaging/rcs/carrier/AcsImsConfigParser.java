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
 * Minimal parser for a GSMA RCC.14 / RCC.07 autoconfig document — the OMA-CP
 * {@code <wap-provisioningdoc>} XML the carrier ACS returns (fetched by the modem
 * and surfaced to messaging2 via
 * {@code ShannonRcsConfigTrigger.ConfigListener#onConfig(byte[])}). It walks the
 * nested {@code <characteristic type="X">} / {@code <parm name="N" value="V"/>}
 * tree and extracts the standard IMS-Settings needed to drive the DR (JAIN-SIP)
 * SIP REGISTER into an {@link RcsImsConfig}:
 *
 * <ul>
 *   <li>{@code LBO_P-CSCF_Address/Address} → P-CSCF FQDN/IP</li>
 *   <li>{@code APPAUTH/Realm} → home domain / Digest realm</li>
 *   <li>{@code APPAUTH/UserName} → private identity (IMPI) / Digest username</li>
 *   <li>{@code APPAUTH/UserPwd} → SIP Digest password (OMA IMS MO, AuthType=Digest).
 *       ABSENT on AKA deployments (the ISIM holds the key) — the caller supplies
 *       the password from elsewhere (e.g. a lab-injected SIM Ki) in that case.</li>
 *   <li>{@code Public_user_identity_List/Public_user_Identity} → public identity
 *       (sip: URI) + the bare MSISDN (tel:)</li>
 * </ul>
 *
 * Only the SIP-registration subset is parsed here; RCS service/feature flags,
 * FT-HTTP, tokens, etc. are intentionally out of scope for the DR config seam.
 */
public final class AcsImsConfigParser {

    private AcsImsConfigParser() {}

    /** Parsed IMS-Settings. Any field may be null if the doc omits it. */
    public static final class ImsSettings {
        @Nullable public String pcscfAddress;      // LBO_P-CSCF_Address/Address
        @Nullable public String realm;             // APPAUTH/Realm (== home domain)
        @Nullable public String userName;          // APPAUTH/UserName (IMPI)
        @Nullable public String userPwd;           // APPAUTH/UserPwd (Digest pw or HA1)
        /** True iff {@link #userPwd} is a precomputed HA1 (AAuthType=Digest-HA1),
         *  not a cleartext password. */
        public boolean pwdIsHa1;
        @Nullable public String publicIdSip;       // Public_user_Identity sip:...
        @Nullable public String publicIdMsisdn;    // Public_user_Identity tel:... (bare)
        // FT-HTTP (FileTransfer characteristic)
        @Nullable public String ftCsUri;           // ftHTTPCSURI
        @Nullable public String ftDlUri;           // ftHTTPDLURI
        @Nullable public String ftCsUser;          // ftHTTPCSUser
        @Nullable public String ftCsPwd;           // ftHTTPCSPwd
        public long ftMaxSize;                     // MaxSizeFileTr (0 => unset)

        // ---- openrcs SERVICEPROVIDEREXT (the lab ACS's E2EE pointers) --------------------------
        //
        // Vendor parms in SERVICEPROVIDEREXT, emitted by the open5gs lab ACS. They are read here
        // rather than anywhere else for a reason worth stating: this is the ONLY place the RCC.07
        // document is parsed, and the document is how the network tells the device where its E2EE
        // infrastructure is. Hard-coding any of these would work right up until the lab rotates one.
        //
        // NOTE these were previously invisible: open5gs's ACS was not emitting SERVICEPROVIDEREXT
        // into the LEGACY FLAT document at all, only the nested form, so a device reading the flat
        // doc saw none of them. Fixed their side 2026-07-31.

        /** {@code openrcs-kds-uri} — where to enrol for an MLS client certificate. */
        @Nullable public String kdsUri;
        /** {@code openrcs-trust-anchors-uri} — the generation-addressed trust-anchor list. */
        @Nullable public String trustAnchorsUri;
        /** {@code openrcs-trust-anchors-generation} — the immutable generation to fetch. */
        public long trustAnchorsGeneration;
        /** {@code openrcs-trust-anchors-signer} — base64 SPKI DER (P-256) of the list signer. */
        @Nullable public String trustAnchorsSigner;
        /**
         * {@code openrcs-encryption-identity-proof} — base64 {@code SignedEncryptionIdentityProof}
         * (RCC.16 §7.12.1), the value the KDS embeds as the MANDATORY {@code .5}
         * id-acsParticipantInformation extension (A.3.8.10).
         *
         * <p>Passed to the KDS <b>verbatim</b>. We never parse or re-encode it — a client that
         * rebuilt it would be asserting a proof it cannot sign, and the scalar encoding inside its
         * TBS is not pinned yet (the spec writes {@code uint32 home_kds<V>}, which is expressible
         * as either fixed-width or a varint; unresolved as of 2026-07-31).
         */
        @Nullable public String encryptionIdentityProof;

        /** True iff the SIP-registration essentials (P-CSCF + realm + IMPI + a
         *  public identity) are present. The Digest pw is checked separately since
         *  it legitimately comes from the SIM on AKA deployments. */
        public boolean hasSipEssentials() {
            return nonEmpty(pcscfAddress) && nonEmpty(realm)
                    && nonEmpty(userName) && nonEmpty(publicIdSip);
        }

        private static boolean nonEmpty(final String s) {
            return s != null && !s.isEmpty();
        }
    }

    /** Parse the OMA-CP config document. Returns null on a parse error (the
     *  caller falls back to whatever config source it has). */
    @Nullable
    public static ImsSettings parse(@Nullable final byte[] configXml) {
        if (configXml == null || configXml.length == 0) {
            return null;
        }
        final ImsSettings out = new ImsSettings();
        // Stack of enclosing characteristic "type" values, innermost last.
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

    /** Build an {@link RcsImsConfig} from parsed settings. The Digest password is
     *  taken from the doc's UserPwd when present, else from {@code pwFallback}
     *  (e.g. the SIM Ki injected for a lab/AKA deployment). Returns null unless the
     *  SIP essentials + a password are available. */
    @Nullable
    public static RcsImsConfig toRcsImsConfig(@Nullable final ImsSettings s,
            @Nullable final String pwFallback, final String transport) {
        if (s == null || !s.hasSipEssentials()) {
            return null;
        }
        final boolean fromDoc = s.userPwd != null && !s.userPwd.isEmpty();
        final String pw = fromDoc ? s.userPwd : pwFallback;
        if (pw == null || pw.isEmpty()) {
            return null; // no Digest secret from the doc and none injected
        }
        // The doc's secret is an HA1 iff AAuthType said so; an injected fallback
        // (SIM Ki) is a cleartext password.
        final boolean isHa1 = fromDoc && s.pwdIsHa1;
        final String domain = s.realm;
        final String user = userPartOf(s.publicIdMsisdn, s.publicIdSip, s.userName);
        final String impu = s.publicIdSip;

        final RcsImsConfig.Builder b = new RcsImsConfig.Builder();
        b.pcscfAddress = s.pcscfAddress;
        b.pcscfPort = -1; // -1 => default per transport (5060 udp/tcp, 5061 tls)
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
        // FT-HTTP endpoints/creds (may be null when the doc omits FileTransfer).
        b.ftContentServerUri = s.ftCsUri;
        b.ftDownloadUri = s.ftDlUri;
        b.ftCsUser = s.ftCsUser;
        b.ftCsPassword = s.ftCsPwd;
        b.ftMaxSizeBytes = s.ftMaxSize;
        // The E2EE pointers ride through verbatim — see ImsSettings for why they are read
        // here and why the proof in particular is never parsed on the way past.
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
        // GOOGLE MESSAGES vs OUR EXTENSION: Google Messages' APPAUTH parser reads EXACTLY four
        // children —
        // AuthType (honored only when =="Digest"), Realm, UserName, UserPwd (a
        // CLEARTEXT password). There is NO AAuthName/AAuthSecret and NO "Digest-HA1"
        // AuthType anywhere in Google Messages. The AAuth* aliases and the Digest-HA1 /
        // precomputed-HA1 branch below are a REIMPL-ONLY EXTENSION of ours (lets an ACS
        // hand us an HA1 so no cleartext/Ki lives client-side). Kept behind this parser
        // and defaulting to the Google Messages UserName/UserPwd path; do NOT expect a Google Messages
        // client to consume a Digest-HA1 doc (it would see AuthType!="Digest" -> skip).
        // Case-insensitive throughout.
        if (inScope(scope, "APPAUTH")) {
            if (eq(name, "Realm") || eq(name, "AAuthRealm")) {
                out.realm = value;
            } else if (eq(name, "UserName") || eq(name, "AAuthName")) {
                out.userName = value;
            } else if (eq(name, "UserPwd") || eq(name, "AAuthSecret")) {
                out.userPwd = value;
            } else if (eq(name, "AAuthType") || eq(name, "AuthType")) {
                // "Digest-HA1" (or any AuthType containing HA1) => the secret is a
                // precomputed HA1, not a cleartext password.
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
        // FT-HTTP (FileTransfer characteristic). Names are globally unique in the doc.
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
        // openrcs SERVICEPROVIDEREXT vendor parms. Matched by NAME rather than by scope: open5gs
        // emits them in both the nested and the flat document, and the names are globally unique.
        if (eq(name, "openrcs-kds-uri")) {
            out.kdsUri = value;
        } else if (eq(name, "openrcs-trust-anchors-uri")) {
            out.trustAnchorsUri = value;
        } else if (eq(name, "openrcs-trust-anchors-generation") && value != null) {
            try { out.trustAnchorsGeneration = Long.parseLong(value.trim()); }
            catch (final NumberFormatException ignored) { /* leave 0 = unset */ }
        } else if (eq(name, "openrcs-trust-anchors-signer")) {
            out.trustAnchorsSigner = value;
        } else if (eq(name, "openrcs-encryption-identity-proof")) {
            out.encryptionIdentityProof = value;
        }
        // Home domain can appear at IMS-Settings level; use it as the realm when
        // APPAUTH didn't provide one (Digest realm == home network domain).
        if ((eq(name, "Home_network_domain_Name") || eq(name, "Home_network_domain_name"))
                && (out.realm == null || out.realm.isEmpty())) {
            out.realm = value;
        }
    }

    private static boolean eq(@Nullable final String a, final String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    /** True iff {@code type} is anywhere in the current characteristic scope
     *  (case-insensitive). */
    private static boolean inScope(final Deque<String> scope, final String type) {
        for (final String s : scope) {
            if (type.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    /** Derive the MSISDN user part for the Contact/self-number: prefer the bare
     *  tel: MSISDN, else the sip: URI user part, else the IMPI local part. */
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

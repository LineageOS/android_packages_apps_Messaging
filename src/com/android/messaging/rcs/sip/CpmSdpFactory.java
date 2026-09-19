/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sip;

import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;

import java.util.UUID;

/**
 * The SDP offer for a CPM 1:1 chat session: the {@link SdpOffer} model with the chat profile's
 * defaults, and an {@code a=path} of the form {@code msrps://host:port/session-id;tcp}.
 */
public final class CpmSdpFactory {

    private CpmSdpFactory() {}

    /**
     * @param localHost the IMS-bound socket's local address, advertised in {@code c=}, {@code o=}
     *     and the path
     * @param fingerprintHex SHA-256 fingerprint of the local TLS client certificate (RFC 4572), or
     *     null for a cleartext {@code TCP/MSRP} offer
     */
    public static Offer build(String localHost, int localPort, String fingerprintHex) {
        final String msrpSessId = randomMsrpSessionId();
        final boolean tls = fingerprintHex != null;
        final String scheme = tls ? "msrps://" : "msrp://";
        // An IPv6 host needs "IN IP6" in c= and o= (RFC 4566) and brackets in the path URI
        // (RFC 3986), or the modem rejects the body.
        final boolean ipv6 = isIpv6Literal(localHost);
        final String pathHost = ipv6 ? "[" + localHost + "]" : localHost;
        final String path = scheme + pathHost + ":" + localPort + "/" + msrpSessId + ";tcp";

        SdpOffer.Builder b = SdpOffer.builder()
                .origUser("-")
                .sessionId(Long.toString(System.currentTimeMillis() / 1000L))
                .sessionVersion(System.currentTimeMillis() / 1000L)
                .addrType(ipv6 ? "IP6" : "IP4")
                .host(localHost)
                .port(localPort)
                .mediaProto(tls ? SdpOffer.MSRP_PROTO_TLS : SdpOffer.MSRP_PROTO_TCP)
                .msrpPath(path)
                // RFC 6135: actpass lets the answerer choose; a relay answers passive, so we
                // connect.
                .setup(SdpOffer.SetupRole.ACTPASS)
                .connectionNew(true)
                .msrpCema(true)
                .direction(SdpOffer.Direction.SENDRECV)
                .acceptTypes(SdpOffer.upChatAcceptTypes())
                .acceptWrappedTypes(SdpOffer.upChatAcceptWrappedTypes());

        if (tls) {
            b.fingerprint(SdpOffer.FINGERPRINT_ALG_SHA256, fingerprintHex);
        }
        return new Offer(b.build(), path, msrpSessId);
    }

    /** RFC 4975 §6.1: an opaque, unguessable session id. */
    private static String randomMsrpSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean isIpv6Literal(String host) {
        return host != null && host.indexOf(':') >= 0;
    }

    /** The offer, the local MSRP URI it advertised, and that URI's session id. */
    public static final class Offer {
        public final SdpOffer sdp;
        public final String localMsrpUri;
        public final String msrpSessionId;

        Offer(SdpOffer sdp, String localMsrpUri, String msrpSessionId) {
            this.sdp = sdp;
            this.localMsrpUri = localMsrpUri;
            this.msrpSessionId = msrpSessionId;
        }
    }
}

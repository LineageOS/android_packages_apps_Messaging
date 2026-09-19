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

import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;

import java.util.UUID;

/**
 * Builds the {@code application/sdp} offer body for a CPM 1-1 chat session,
 * mirroring Google Messages' MSRP SDP media descriptor.
 * Reuses the shared, host-tested {@link SdpOffer} model
 * ({@code messaging-rcs-msrp-session-host}); this class just
 * carries the CPM-1-1 profile defaults (accept-types, setup role, msrp-cema)
 * and the {@code msrps://} a=path construction.
 *
 * <p>Wire shape:
 * <pre>
 *   v=0
 *   o=- {sessId} {sessVer} IN IP4 {host}
 *   s=-
 *   c=IN IP4 {host}
 *   t=0 0
 *   m=message {port} TCP/TLS/MSRP *
 *   a=path:msrps://{host}:{port}/{msrpSessId};tcp
 *   a=setup:actpass            ; we let the relay choose; we go active
 *   a=connection:new
 *   a=msrp-cema
 *   a=fingerprint:SHA-256 {fp}
 *   a=accept-types:message/cpim application/im-iscomposing+xml
 *   a=accept-wrapped-types:text/plain message/imdn+xml ...
 *   a=sendrecv
 * </pre>
 *
 * <p>Pure Java; host-testable.
 */
public final class CpmSdpFactory {

    private CpmSdpFactory() {}

    /**
     * Build the SDP offer for a 1-1 CPM chat session.
     *
     * @param localHost   the local IP we advertise on c=/o= and in a=path
     *                    (the IMS-bound socket's local address).
     * @param localPort   the local MSRP port (the bound ServerSocket / the
     *                    ephemeral source port if we connect active). For an
     *                    active-setup offer the relay ignores our port for the
     *                    connect; we still advertise a real one.
     * @param fingerprintHex the SHA-256 fingerprint (RFC 4572 colon-hex form)
     *                    of the local TLS client cert. May be null for a
     *                    cleartext (TCP/MSRP) offer — not used on TMo.
     * @return an {@link SdpOffer} plus the MSRP session-id embedded in a=path.
     */
    public static Offer build(String localHost, int localPort, String fingerprintHex) {
        final String msrpSessId = randomMsrpSessionId();
        final boolean tls = fingerprintHex != null;
        final String scheme = tls ? "msrps://" : "msrp://";
        // IMS-PDN addresses are IPv6 on TMo. The SDP c=/o= net-type MUST match
        // the literal (IN IP6 for an IPv6 host), and an IPv6 host inside the
        // a=path MSRP URI MUST be bracketed so the :port colon is unambiguous
        // (RFC 4566 + RFC 4975 §6.1 / RFC 3986 IP-literal). Emitting
        // "IN IP4 <ipv6>" + an unbracketed IPv6 path was the code-5
        // ("Body content is INVALID") SDP defect, device-observed 2026-06-02.
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
                // actpass: per RFC 6135 we let the answerer pick; for a relay
                // the answer is typically setup:passive, so we connect active.
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

    /** RFC 4975 §6.1 session-id: an opaque, unguessable token. */
    private static String randomMsrpSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** True if the host is an IPv6 literal (contains ':' and isn't a bare host). */
    private static boolean isIpv6Literal(String host) {
        return host != null && host.indexOf(':') >= 0;
    }

    /** Bundle of the SDP offer + the local MSRP URI it advertised. */
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

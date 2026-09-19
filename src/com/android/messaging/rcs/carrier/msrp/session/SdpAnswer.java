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

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 3264 offer/answer helpers for an MSRP chat session. The SDP body
 * grammar is identical between offer and answer (see {@link SdpOffer}); the
 * answer's job is to:
 *
 * <ul>
 *   <li>Echo the m= media (or refuse with port=0 per RFC 3264 §6).</li>
 *   <li>Pick a {@code a=setup:} role complementary to the offer
 *       ({@code active}/{@code passive}, or {@code passive} when the offer
 *       was {@code actpass}). RFC 6135 §5.2.</li>
 *   <li>Intersect the {@code a=accept-types} and
 *       {@code a=accept-wrapped-types} lists to the formats both sides
 *       support — RFC 4975 §8.6.</li>
 *   <li>Provide the answerer's own {@code a=path:} and (optionally)
 *       {@code a=fingerprint:}.</li>
 * </ul>
 *
 * <p>This class is a thin functional shell over {@link SdpOffer.Builder} —
 * it produces an {@link SdpOffer} instance that semantically represents
 * the answer side. The underlying wire shape is the same.
 */
public final class SdpAnswer {

    private SdpAnswer() {}

    /**
     * Build an SDP answer to the supplied offer. Defaults applied:
     * <ul>
     *   <li>{@code addrType}, {@code direction} mirror the offer's.</li>
     *   <li>{@code setup} is {@code passive} when offer was {@code active},
     *       {@code active} when offer was {@code passive}, and
     *       {@code passive} for {@code actpass} (RFC 6135 §5.2 preferred role).</li>
     *   <li>{@code msrp-cema} mirrors the offer (so the answer is consistent
     *       with the connection-mode chosen).</li>
     *   <li>{@code accept-types} and {@code accept-wrapped-types} are
     *       intersected with the answerer's local-supported sets, preserving
     *       offer order so the answerer's preference order shows through.</li>
     * </ul>
     *
     * @param offer parsed offer from the INVITE body
     * @param localHost answerer's local IP / host for c=/o=
     * @param localPort answerer's MSRP listen port
     * @param localMsrpPath answerer's {@code msrps://...} URI for a=path:
     * @param localFingerprintAlg answerer's SHA-256 alg name (may be null
     *                            for cleartext MSRP — see {@link SdpOffer#MSRP_PROTO_TCP})
     * @param localFingerprintHex answerer's TLS cert fingerprint hex
     * @param localAcceptTypes types the answerer supports (will be intersected
     *                         with offer.acceptTypes)
     * @param localAcceptWrappedTypes wrapped types the answerer supports
     */
    public static SdpOffer build(SdpOffer offer,
            String localHost, int localPort, String localMsrpPath,
            String localFingerprintAlg, String localFingerprintHex,
            List<String> localAcceptTypes,
            List<String> localAcceptWrappedTypes) {
        if (offer == null) throw new IllegalArgumentException("null offer");

        SdpOffer.SetupRole answerSetup = complementarySetup(offer.getSetup());

        List<String> isectTypes = intersect(offer.getAcceptTypes(), localAcceptTypes);
        List<String> isectWrapped = intersect(offer.getAcceptWrappedTypes(), localAcceptWrappedTypes);

        SdpOffer.Builder b = SdpOffer.builder()
                .sessionId(offer.getSessionId())
                .sessionVersion(offer.getSessionVersion())
                .addrType(offer.getAddrType())
                .host(localHost)
                .port(localPort)
                .mediaProto(offer.getMediaProto())
                .msrpPath(localMsrpPath)
                .setup(answerSetup)
                .msrpCema(offer.hasMsrpCema())
                .connectionNew(offer.hasConnectionNew())
                .direction(offer.getDirection())
                .acceptTypes(isectTypes)
                .acceptWrappedTypes(isectWrapped);

        if (localFingerprintAlg != null && localFingerprintHex != null) {
            b.fingerprint(localFingerprintAlg, localFingerprintHex);
        }
        return b.build();
    }

    /**
     * Pick the answerer's {@code a=setup:} role given the offerer's.
     * RFC 6135 §5.2. {@code actpass} from the offer resolves to
     * {@code passive} on the answer side (the convention RFC 6135 §5.2
     * recommends so the answerer terminates the TLS handshake).
     */
    public static SdpOffer.SetupRole complementarySetup(SdpOffer.SetupRole offerSetup) {
        if (offerSetup == null) {
            // Offer omitted setup: per RFC 6135 §5.2 we default to active.
            return SdpOffer.SetupRole.ACTIVE;
        }
        switch (offerSetup) {
            case ACTIVE:   return SdpOffer.SetupRole.PASSIVE;
            case PASSIVE:  return SdpOffer.SetupRole.ACTIVE;
            case ACTPASS:  return SdpOffer.SetupRole.PASSIVE;
            case HOLDCONN: return SdpOffer.SetupRole.HOLDCONN;
            default:       return SdpOffer.SetupRole.PASSIVE;
        }
    }

    /**
     * Intersect two ordered content-type lists. Preserves the order of
     * {@code preferred} (typically the offer's list).
     */
    public static List<String> intersect(List<String> preferred, List<String> available) {
        List<String> out = new ArrayList<>();
        if (preferred == null || available == null) return out;
        for (String t : preferred) {
            if (available.contains(t)) out.add(t);
        }
        return out;
    }
}

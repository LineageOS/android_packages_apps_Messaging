/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp.session;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 3264 answer for an MSRP chat session, built as an {@link SdpOffer} since the grammar is
 * the same: the complementary {@code a=setup} role (RFC 6135), accept lists intersected with
 * ours (RFC 4975 §8.6), and our own path and fingerprint.
 */
public final class SdpAnswer {

    private SdpAnswer() {}

    /**
     * Mirrors the offer's address type, direction, {@code msrp-cema} and {@code connection:new};
     * accept lists keep the offer's order. The fingerprint is set only when both alg and hex are
     * non-null.
     */
    public static SdpOffer build(SdpOffer offer,
            String localHost, int localPort, String localMsrpPath,
            String localFingerprintAlg, String localFingerprintHex,
            List<String> localAcceptTypes,
            List<String> localAcceptWrappedTypes) {
        if (offer == null) throw new IllegalArgumentException("null offer");

        SdpOffer.SetupRole answerSetup = complementarySetup(offer.getSetup());

        List<String> isectTypes = intersect(offer.getAcceptTypes(), localAcceptTypes);
        List<String> isectWrapped =
                intersect(offer.getAcceptWrappedTypes(), localAcceptWrappedTypes);

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
     * RFC 6135: {@code actpass} is answered {@code passive}; no setup in the offer gives active.
     */
    public static SdpOffer.SetupRole complementarySetup(SdpOffer.SetupRole offerSetup) {
        if (offerSetup == null) {
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
     * The members of {@code preferred} present in {@code available}, in {@code preferred} order.
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

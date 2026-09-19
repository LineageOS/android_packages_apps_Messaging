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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Host-side unit tests for {@link SdpAnswer} — RFC 3264 + RFC 6135 §5.2
 * answer construction.
 */
public class SdpAnswerTest {

    private SdpOffer offer(SdpOffer.SetupRole offerSetup) {
        return SdpOffer.builder()
                .sessionId("session-x")
                .host("203.0.113.1")
                .port(1111)
                .msrpPath("msrps://203.0.113.1:1111/session-x;tcp")
                .setup(offerSetup)
                .fingerprint("SHA-256", "AA:BB:CC")
                .acceptTypes(SdpOffer.upChatAcceptTypes())
                .acceptWrappedTypes(SdpOffer.upChatAcceptWrappedTypes())
                .msrpCema(true)
                .connectionNew(true)
                .build();
    }

    // ============================================================
    // Setup-role complement
    // ============================================================

    @Test
    public void complementarySetup_activeOfferGetsPassiveAnswer() {
        assertEquals(SdpOffer.SetupRole.PASSIVE,
                SdpAnswer.complementarySetup(SdpOffer.SetupRole.ACTIVE));
    }

    @Test
    public void complementarySetup_passiveOfferGetsActiveAnswer() {
        assertEquals(SdpOffer.SetupRole.ACTIVE,
                SdpAnswer.complementarySetup(SdpOffer.SetupRole.PASSIVE));
    }

    @Test
    public void complementarySetup_actpassResolvesToPassivePerRfc6135() {
        // RFC 6135 §5.2 recommends the answerer pick passive when the
        // offer is actpass.
        assertEquals(SdpOffer.SetupRole.PASSIVE,
                SdpAnswer.complementarySetup(SdpOffer.SetupRole.ACTPASS));
    }

    @Test
    public void complementarySetup_nullDefaultsToActive() {
        assertEquals(SdpOffer.SetupRole.ACTIVE,
                SdpAnswer.complementarySetup(null));
    }

    // ============================================================
    // Intersection
    // ============================================================

    @Test
    public void intersect_preservesOfferOrder() {
        List<String> preferred = Arrays.asList("a", "b", "c", "d");
        List<String> available = Arrays.asList("c", "a", "z");
        assertEquals(Arrays.asList("a", "c"),
                SdpAnswer.intersect(preferred, available));
    }

    @Test
    public void intersect_nullsAreEmpty() {
        assertEquals(Collections.emptyList(),
                SdpAnswer.intersect(null, Arrays.asList("x")));
        assertEquals(Collections.emptyList(),
                SdpAnswer.intersect(Arrays.asList("x"), null));
        assertEquals(Collections.emptyList(),
                SdpAnswer.intersect(null, null));
    }

    // ============================================================
    // Full answer construction
    // ============================================================

    @Test
    public void build_activeOfferYieldsPassiveAnswer() {
        SdpOffer o = offer(SdpOffer.SetupRole.ACTIVE);
        SdpOffer ans = SdpAnswer.build(o,
                "198.51.100.2", 2222, "msrps://198.51.100.2:2222/answer;tcp",
                "SHA-256", "DD:EE:FF",
                SdpOffer.upChatAcceptTypes(),
                SdpOffer.upChatAcceptWrappedTypes());

        assertEquals(SdpOffer.SetupRole.PASSIVE, ans.getSetup());
        assertEquals("198.51.100.2", ans.getHost());
        assertEquals(2222, ans.getPort());
        assertEquals("msrps://198.51.100.2:2222/answer;tcp", ans.getMsrpPath());
        assertEquals("SHA-256", ans.getFingerprintAlg());
        assertEquals("DD:EE:FF", ans.getFingerprintHex());
    }

    @Test
    public void build_intersectionMatchesOfferPreferenceOrder() {
        SdpOffer o = offer(SdpOffer.SetupRole.ACTPASS);
        // Answerer supports only message/cpim (drops im-iscomposing).
        List<String> localTypes = Arrays.asList("message/cpim");
        // Answerer also drops several wrapped types.
        List<String> localWrapped = Arrays.asList(
                "text/plain", "application/vnd.google.rcs.encrypted");

        SdpOffer ans = SdpAnswer.build(o,
                "198.51.100.2", 2222, "msrps://198.51.100.2:2222/a;tcp",
                "SHA-256", "DD:EE:FF",
                localTypes, localWrapped);

        assertEquals(Arrays.asList("message/cpim"), ans.getAcceptTypes());
        // Wrapped intersection preserves offer order.
        assertEquals(Arrays.asList("text/plain", "application/vnd.google.rcs.encrypted"),
                ans.getAcceptWrappedTypes());
    }

    @Test
    public void build_mirrorsMsrpCemaAndConnectionFromOffer() {
        SdpOffer o = offer(SdpOffer.SetupRole.ACTPASS);
        SdpOffer ans = SdpAnswer.build(o,
                "198.51.100.2", 2222, "msrps://198.51.100.2:2222/a;tcp",
                null, null,
                SdpOffer.upChatAcceptTypes(),
                SdpOffer.upChatAcceptWrappedTypes());
        assertTrue(ans.hasMsrpCema());
        assertTrue(ans.hasConnectionNew());
        assertFalse("cleartext: no fingerprint", ans.hasFingerprint());
    }

    @Test
    public void build_sessionIdMirroredFromOffer() {
        SdpOffer o = offer(SdpOffer.SetupRole.ACTIVE);
        SdpOffer ans = SdpAnswer.build(o,
                "h", 1, "msrps://h:1/a;tcp",
                "SHA-256", "00",
                SdpOffer.upChatAcceptTypes(),
                SdpOffer.upChatAcceptWrappedTypes());
        assertEquals(o.getSessionId(), ans.getSessionId());
        assertEquals(o.getSessionVersion(), ans.getSessionVersion());
    }
}

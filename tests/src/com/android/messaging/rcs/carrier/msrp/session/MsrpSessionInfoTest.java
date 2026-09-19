/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.Test;

/** {@link MsrpSessionInfo}, the immutable binding an SDP exchange produces. */
public class MsrpSessionInfoTest {

    @Test
    public void build_populatesAllFields() {
        MsrpSessionInfo info = MsrpSessionInfo.builder()
                .sipCallId("call-1")
                .contributionId("contrib-1")
                .conversationId("conv-1")
                .localMsrpUri("msrps://10.0.0.1:1000/s;tcp")
                .remoteMsrpUri("msrps://10.0.0.2:2000/r;tcp")
                .localFingerprint("SHA-256", "AA:BB")
                .remoteFingerprint("SHA-256", "CC:DD")
                .acceptTypes(Arrays.asList("message/cpim"))
                .acceptWrappedTypes(Arrays.asList("text/plain"))
                .localRole(SdpOffer.SetupRole.ACTIVE)
                .remoteHost("10.0.0.2")
                .remotePort(2000)
                .build();

        assertEquals("call-1", info.getSipCallId());
        assertEquals("contrib-1", info.getContributionId());
        assertEquals("conv-1", info.getConversationId());
        assertEquals("msrps://10.0.0.1:1000/s;tcp", info.getLocalMsrpUri());
        assertEquals("msrps://10.0.0.2:2000/r;tcp", info.getRemoteMsrpUri());
        assertEquals("SHA-256", info.getLocalFingerprintAlg());
        assertEquals("AA:BB", info.getLocalFingerprintHex());
        assertEquals("SHA-256", info.getRemoteFingerprintAlg());
        assertEquals("CC:DD", info.getRemoteFingerprintHex());
        assertEquals(Arrays.asList("message/cpim"), info.getAcceptTypes());
        assertEquals(Arrays.asList("text/plain"), info.getAcceptWrappedTypes());
        assertEquals(SdpOffer.SetupRole.ACTIVE, info.getLocalRole());
        assertEquals("10.0.0.2", info.getRemoteHost());
        assertEquals(2000, info.getRemotePort());
        assertTrue(info.weActivelyConnect());
    }

    @Test
    public void weActivelyConnect_falseForPassive() {
        MsrpSessionInfo info = MsrpSessionInfo.builder()
                .localMsrpUri("msrps://h:1/l;tcp")
                .remoteMsrpUri("msrps://h:2/r;tcp")
                .localRole(SdpOffer.SetupRole.PASSIVE)
                .build();
        assertFalse(info.weActivelyConnect());
    }

    @Test
    public void acceptsType_andWrappedType_predicates() {
        MsrpSessionInfo info = MsrpSessionInfo.builder()
                .localMsrpUri("msrps://h:1/l;tcp")
                .remoteMsrpUri("msrps://h:2/r;tcp")
                .localRole(SdpOffer.SetupRole.ACTIVE)
                .acceptTypes(Arrays.asList("message/cpim", "application/im-iscomposing+xml"))
                .acceptWrappedTypes(Arrays.asList("text/plain",
                        "application/vnd.google.rcs.encrypted"))
                .build();
        assertTrue(info.acceptsType("message/cpim"));
        assertFalse(info.acceptsType("application/sdp"));
        assertTrue(info.acceptsWrappedType("text/plain"));
        assertTrue(info.acceptsWrappedType("application/vnd.google.rcs.encrypted"));
        assertFalse(info.acceptsWrappedType("application/xml"));
    }

    @Test
    public void build_rejectsMissingUri() {
        try {
            MsrpSessionInfo.builder()
                    .localRole(SdpOffer.SetupRole.ACTIVE)
                    .remoteMsrpUri("msrp://h:1/r;tcp")
                    .build();
            fail("missing local URI");
        } catch (IllegalStateException expected) {}

        try {
            MsrpSessionInfo.builder()
                    .localRole(SdpOffer.SetupRole.ACTIVE)
                    .localMsrpUri("msrp://h:1/l;tcp")
                    .build();
            fail("missing remote URI");
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void build_rejectsMissingRole() {
        try {
            MsrpSessionInfo.builder()
                    .localMsrpUri("msrp://h:1/l;tcp")
                    .remoteMsrpUri("msrp://h:2/r;tcp")
                    .build();
            fail("missing role");
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void emptyAcceptListsAreNotNull() {
        MsrpSessionInfo info = MsrpSessionInfo.builder()
                .localMsrpUri("msrp://h:1/l;tcp")
                .remoteMsrpUri("msrp://h:2/r;tcp")
                .localRole(SdpOffer.SetupRole.ACTIVE)
                .build();
        assertNotNull(info.getAcceptTypes());
        assertEquals(0, info.getAcceptTypes().size());
        assertNotNull(info.getAcceptWrappedTypes());
        assertEquals(0, info.getAcceptWrappedTypes().size());
    }
}

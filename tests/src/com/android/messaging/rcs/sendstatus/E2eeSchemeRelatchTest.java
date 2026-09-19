/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import com.android.messaging.rcs.e2ee.E2eeSchemeGate;
import com.android.messaging.rcs.e2ee.EncryptionProtocolBits;
import com.android.messaging.rcs.e2ee.MlsCapabilities;
import com.android.messaging.rcs.e2ee.RcsE2eeScheme;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A 1:1 the MLS downgrade flow took out of MLS does not get the MLS bit back from the next send's
 * eligibility recompute. The peer still advertises MLS after a downgrade, so without this the next
 * send re-latches the bit, goes to the MLS seal, which refuses a downgraded conversation, and the
 * message fails. See docs/mls/downgrade.md.
 */
public class E2eeSchemeRelatchTest {

    private static final String CONV = "c1";

    /** A peer that advertises MLS: the eligibility tree passes for a 1:1. */
    private static List<MlsCapabilities.PeerCaps> mlsPeer() {
        final Map<String, String> tags = new HashMap<>();
        tags.put(RcsE2eeScheme.TAG_MLS_KDS, "2");
        tags.put(RcsE2eeScheme.TAG_MLS_VERSION, RcsE2eeScheme.MLS_VERSION_V1);
        return Collections.singletonList(new MlsCapabilities.PeerCaps(tags));
    }

    /** Provisioned, with a group id per conversation and a downgrade flag the test controls. */
    private static final class Provisioning implements E2eeSchemeGate.MlsProvisioning {
        boolean downgraded;

        @Override public boolean isMlsProvisioned(final int subId) { return true; }
        @Override public String mlsGroupId(final String conversationId) { return conversationId; }
        @Override public String ourLaunchIteration() { return RcsE2eeScheme.ourLaunchIteration(); }
        @Override public boolean mlsDowngraded(final String conversationId) { return downgraded; }
    }

    private static final class MemStore implements E2eeSchemeGate.BitsStore {
        private final Map<String, EncryptionProtocolBits> mMap = new HashMap<>();

        @Override public EncryptionProtocolBits load(final String id) {
            final EncryptionProtocolBits b = mMap.get(id);
            return b == null ? EncryptionProtocolBits.NONE : b;
        }

        @Override public void store(final String id, final EncryptionProtocolBits bits) {
            mMap.put(id, bits);
        }
    }

    @Test
    public void anEligibleConversationLatchesMls() {
        final E2eeSchemeGate gate = new E2eeSchemeGate(new Provisioning(), new MemStore());
        assertEquals(RcsE2eeScheme.MLS, gate.selectScheme(CONV, 1, mlsPeer(), false));
    }

    /**
     * The downgrade flow clears the bit and marks the conversation; the next send stays off MLS.
     */
    @Test
    public void aDowngradedConversationIsNotReLatchedByTheNextSend() {
        final Provisioning mls = new Provisioning();
        final MemStore store = new MemStore();
        final E2eeSchemeGate gate = new E2eeSchemeGate(mls, store);
        assertEquals(RcsE2eeScheme.MLS, gate.selectScheme(CONV, 1, mlsPeer(), false));

        // What MlsDowngradeFlow.downgradeLocally does on an eager downgrade: clear the bit, and
        // set mls_eagerly_downgraded.
        gate.downgradeMls(CONV);
        mls.downgraded = true;

        assertNotEquals("the peer still advertises MLS, and the recompute must not put the bit "
                + "back while the conversation is downgraded", RcsE2eeScheme.MLS,
                gate.selectScheme(CONV, 1, mlsPeer(), false));
        assertFalse("nor is it stored", store.load(CONV).mlsBit());
    }

    /** A re-upgrade clears the mark, and the next send latches MLS again. */
    @Test
    public void aClearedDowngradeLatchesMlsAgain() {
        final Provisioning mls = new Provisioning();
        final E2eeSchemeGate gate = new E2eeSchemeGate(mls, new MemStore());
        gate.downgradeMls(CONV);
        mls.downgraded = true;
        assertNotEquals(RcsE2eeScheme.MLS, gate.selectScheme(CONV, 1, mlsPeer(), false));
        mls.downgraded = false;
        assertEquals(RcsE2eeScheme.MLS, gate.selectScheme(CONV, 1, mlsPeer(), false));
    }
}

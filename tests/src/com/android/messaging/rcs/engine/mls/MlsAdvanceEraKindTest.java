/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The three-valued era-advance mode byte. The values cross the FFI boundary and are persisted in a
 * pending operation, so they are pinned. See docs/mls/group-lifecycle.md.
 */
public class MlsAdvanceEraKindTest {

    @Test
    public void theModeBytesAreTheReferenceClientsZeroOneTwo() {
        assertEquals(0, MlsAdvanceEraKind.NORMAL.mode);
        assertEquals(1, MlsAdvanceEraKind.REVIVAL.mode);
        assertEquals(2, MlsAdvanceEraKind.PHOENIX_DOWNGRADE.mode);
        assertEquals(3, MlsAdvanceEraKind.values().length);
    }

    /** Exactly one mode may remove {@code 0xF002}; a second means the remover grew a call site. */
    @Test
    public void onlyRevivalMayRemoveEndMls() {
        int removers = 0;
        for (final MlsAdvanceEraKind k : MlsAdvanceEraKind.values()) {
            if (k.mayRemoveEndMls()) removers++;
        }
        assertEquals(1, removers);
        assertTrue(MlsAdvanceEraKind.REVIVAL.mayRemoveEndMls());
        assertFalse(MlsAdvanceEraKind.NORMAL.mayRemoveEndMls());
        assertFalse(MlsAdvanceEraKind.PHOENIX_DOWNGRADE.mayRemoveEndMls());
    }

    /** Exactly one mode installs it, which a preserve-only carry list cannot express. */
    @Test
    public void onlyPhoenixInstallsEndMls() {
        int installers = 0;
        for (final MlsAdvanceEraKind k : MlsAdvanceEraKind.values()) {
            if (k.installsEndMls()) installers++;
        }
        assertEquals(1, installers);
        assertTrue(MlsAdvanceEraKind.PHOENIX_DOWNGRADE.installsEndMls());
    }

    /** {@link MlsAdvanceEraKind#NORMAL} touches neither and carries whatever is there. */
    @Test
    public void normalTouchesNeither() {
        assertFalse(MlsAdvanceEraKind.NORMAL.mayRemoveEndMls());
        assertFalse(MlsAdvanceEraKind.NORMAL.installsEndMls());
    }

    /**
     * An unknown mode ({@code > 2}) decodes as mode 0 and carries the extension forward, so an
     * advance never silently drops a downgrade.
     */
    @Test
    public void anUnknownModeDecodesToNormal() {
        assertEquals(MlsAdvanceEraKind.NORMAL, MlsAdvanceEraKind.fromMode(3));
        assertEquals(MlsAdvanceEraKind.NORMAL, MlsAdvanceEraKind.fromMode(255));
        assertEquals(MlsAdvanceEraKind.NORMAL, MlsAdvanceEraKind.fromMode(-1));
    }

    /** What each kind leaves in the new era, from each starting state. */
    @Test
    public void theEndMlsAfterAnAdvanceFollowsTheKind() {
        assertFalse(MlsAdvanceEraKind.NORMAL.endMlsAfter(false));
        assertTrue("a downgraded group stays downgraded",
                MlsAdvanceEraKind.NORMAL.endMlsAfter(true));
        assertFalse(MlsAdvanceEraKind.REVIVAL.endMlsAfter(true));
        assertFalse(MlsAdvanceEraKind.REVIVAL.endMlsAfter(false));
        assertTrue(MlsAdvanceEraKind.PHOENIX_DOWNGRADE.endMlsAfter(false));
        assertTrue(MlsAdvanceEraKind.PHOENIX_DOWNGRADE.endMlsAfter(true));
        for (final MlsAdvanceEraKind k : MlsAdvanceEraKind.values()) {
            assertEquals(k + " carries", k == MlsAdvanceEraKind.NORMAL, k.carriesEndMls());
        }
    }

    @Test
    public void fromModeRoundTrips() {
        for (final MlsAdvanceEraKind k : MlsAdvanceEraKind.values()) {
            assertEquals(k, MlsAdvanceEraKind.fromMode(k.mode));
        }
    }
}

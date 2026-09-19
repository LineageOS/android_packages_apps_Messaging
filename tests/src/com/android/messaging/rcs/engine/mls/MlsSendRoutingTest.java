/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.e2ee.MlsSendRouting;
import com.android.messaging.rcs.e2ee.MlsSendRouting.MlsLatch;
import com.android.messaging.rcs.e2ee.MlsSendRouting.SealCapability;
import com.android.messaging.rcs.e2ee.MlsSendRouting.Verdict;

import java.util.EnumMap;
import java.util.Map;

import org.junit.Test;

/**
 * The group-send gate's whole table. {@link #everyCombinationIsAccountedFor} walks every (engine
 * capability, app latch) pair and fails on one the inventory does not name, so a new state on
 * either axis cannot inherit an answer by omission. See docs/rcs/groups.md.
 */
public class MlsSendRoutingTest {

    /**
     * The verdict for every pair, written as data rather than derived from
     * {@link MlsSendRouting#decide}.
     */
    private static Map<SealCapability, Map<MlsLatch, Verdict>> inventory() {
        final Map<SealCapability, Map<MlsLatch, Verdict>> t = new EnumMap<>(SealCapability.class);
        t.put(SealCapability.SEALABLE, row(Verdict.SEAL, Verdict.SEAL, Verdict.SEAL));
        t.put(SealCapability.NO_MLS_STATE,
                row(Verdict.REFUSE, Verdict.PLAINTEXT, Verdict.PLAINTEXT));
        t.put(SealCapability.DOWNGRADED,
                row(Verdict.REFUSE, Verdict.PLAINTEXT, Verdict.PLAINTEXT));
        t.put(SealCapability.ENGINE_UNAVAILABLE,
                row(Verdict.REFUSE, Verdict.PLAINTEXT, Verdict.PLAINTEXT));
        t.put(SealCapability.NO_MLS_IDENTITY,
                row(Verdict.REFUSE, Verdict.PLAINTEXT, Verdict.PLAINTEXT));
        return t;
    }

    private static Map<MlsLatch, Verdict> row(final Verdict latched, final Verdict clear,
            final Verdict unreadable) {
        final Map<MlsLatch, Verdict> r = new EnumMap<>(MlsLatch.class);
        r.put(MlsLatch.LATCHED, latched);
        r.put(MlsLatch.CLEAR, clear);
        r.put(MlsLatch.UNREADABLE, unreadable);
        return r;
    }

    @Test
    public void everyCombinationIsAccountedFor() {
        final Map<SealCapability, Map<MlsLatch, Verdict>> table = inventory();
        int checked = 0;
        for (final SealCapability engine : SealCapability.values()) {
            final Map<MlsLatch, Verdict> r = table.get(engine);
            assertNotNull("no inventory row for " + engine + " — a state added to SealCapability "
                    + "must be given an answer here, not inherit one by omission", r);
            for (final MlsLatch latch : MlsLatch.values()) {
                final Verdict expected = r.get(latch);
                assertNotNull("no inventory entry for " + engine + " + " + latch, expected);
                assertEquals(engine + " + " + latch, expected,
                        MlsSendRouting.decide(engine, latch));
                checked++;
            }
        }
        assertEquals("the product of the two axes must be fully walked",
                SealCapability.values().length * MlsLatch.values().length, checked);
        assertTrue("zero rows would pass vacuously", checked > 0);
    }

    /**
     * A group latched as MLS with no MLS state behind it does not send: the server and the other
     * members still hold it as encrypted, and the latch still draws the padlock.
     */
    @Test
    public void aLatchedConversationWeCannotSealRefusesRatherThanSendingPlaintext() {
        for (final SealCapability cannotSeal : new SealCapability[] {
                SealCapability.NO_MLS_STATE, SealCapability.DOWNGRADED,
                SealCapability.ENGINE_UNAVAILABLE, SealCapability.NO_MLS_IDENTITY}) {
            assertEquals("a padlocked conversation we cannot seal must NOT go out in the clear: "
                    + cannotSeal, Verdict.REFUSE,
                    MlsSendRouting.decide(cannotSeal, MlsLatch.LATCHED));
        }
    }

    /**
     * An ordinary non-MLS group has no MLS state and no latched bit, since only an MLS message sets
     * it, so it cannot reach the refusal.
     */
    @Test
    public void anOrdinaryRcsGroupIsUntouched() {
        assertEquals(Verdict.PLAINTEXT,
                MlsSendRouting.decide(SealCapability.NO_MLS_STATE, MlsLatch.CLEAR));
        assertEquals("a device that never adopted an identity cannot refuse anything",
                Verdict.PLAINTEXT,
                MlsSendRouting.decide(SealCapability.NO_MLS_IDENTITY, MlsLatch.CLEAR));
    }

    /**
     * A group joined by Welcome that has not yet sent or received an MLS message has no latched bit
     * while its peers expect {@code message/mls}; {@link Verdict#SEAL} does not consult the latch.
     */
    @Test
    public void aJoinedGroupThatHasNeverLatchedTheBitStillSeals() {
        assertEquals(Verdict.SEAL,
                MlsSendRouting.decide(SealCapability.SEALABLE, MlsLatch.CLEAR));
        assertEquals(Verdict.SEAL,
                MlsSendRouting.decide(SealCapability.SEALABLE, MlsLatch.UNREADABLE));
    }

    /**
     * A completed downgrade sends plaintext (RCC.16 §9.1.1): {@code downgradeLocally} clears the
     * bit, so the user is no longer told the conversation is encrypted. Until the commit lands the
     * bit stays set and the send is refused.
     */
    @Test
    public void aCompletedDowngradeSendsPlaintextAndAnIncompleteOneRefuses() {
        assertEquals(Verdict.PLAINTEXT,
                MlsSendRouting.decide(SealCapability.DOWNGRADED, MlsLatch.CLEAR));
        assertEquals(Verdict.REFUSE,
                MlsSendRouting.decide(SealCapability.DOWNGRADED, MlsLatch.LATCHED));
    }

    /**
     * An unreadable bit reads as clear, as the padlock rule does, so a database error cannot refuse
     * every ordinary group send; a healthy MLS group still seals from the engine input.
     */
    @Test
    public void anUnreadableBitFallsThroughToPlaintextButNeverOverridesTheEngine() {
        assertEquals(Verdict.PLAINTEXT,
                MlsSendRouting.decide(SealCapability.NO_MLS_STATE, MlsLatch.UNREADABLE));
        assertEquals(Verdict.SEAL,
                MlsSendRouting.decide(SealCapability.SEALABLE, MlsLatch.UNREADABLE));
    }

    @Test
    public void latchOfDistinguishesClearFromUnreadable() {
        assertEquals(MlsLatch.LATCHED, MlsSendRouting.latchOf(Boolean.TRUE));
        assertEquals(MlsLatch.CLEAR, MlsSendRouting.latchOf(Boolean.FALSE));
        assertEquals("a failed read is not evidence the conversation is plaintext",
                MlsLatch.UNREADABLE, MlsSendRouting.latchOf(null));
    }

    /** A caller that cannot state its inputs gets the answer that cannot leak cleartext. */
    @Test
    public void aNullInputRefusesRatherThanDefaultingToPlaintext() {
        assertEquals(Verdict.REFUSE, MlsSendRouting.decide(null, MlsLatch.CLEAR));
        assertEquals(Verdict.REFUSE, MlsSendRouting.decide(SealCapability.SEALABLE, null));
        assertEquals(Verdict.REFUSE, MlsSendRouting.decide(null, null));
    }
}

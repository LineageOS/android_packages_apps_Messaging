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
 * The group-send gate's whole table.
 *
 * <p><b>Why the table and not a handful of cases.</b> The decision is three lines, so any test that
 * picks interesting inputs is really testing the three lines it just read. What is worth pinning is
 * the ENUMERATION: that every combination of "what the engine can do" and "what the app has told the
 * user" has a stated answer, and that adding a state to either axis cannot inherit an answer by
 * omission. {@link #everyCombinationIsAccountedFor} walks the product and fails on any pair the
 * inventory below does not name, which is the same shape the coverage guards in this package use.
 *
 * <p><b>The two rows this is actually about</b> are
 * {@code NO_MLS_STATE + LATCHED -> REFUSE} (the app is drawing a padlock on a group we hold no
 * state for — the REJOIN state, one row over) and {@code SEALABLE + CLEAR -> SEAL} (a
 * group joined by Welcome that has never latched a bit, which is the case the latch alone cannot
 * see and is why the engine is asked first).
 */
public class MlsSendRoutingTest {

    /**
     * The verdict every (engine, latch) pair must produce, written out in full.
     *
     * <p>Written as data rather than derived from {@link MlsSendRouting#decide}, which would be
     * a tautology. A row changed here is a row someone had to type.
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

    // ----------------------------------------------------------------------- the rows in question

    /**
     * THE DEFECT. A group the app has latched as MLS, with no MLS state behind it, must not send.
     *
     * <p>This is the REJOIN state one row over: the server and the other members still
     * hold the group as encrypted, we dropped both halves in a rebuild and never got back in, and
     * the latch — which is cleared only by an explicit downgrade or our own leave — is still set, so
     * the padlock is still drawn. Before this gate the reply went out as {@code text/plain}.
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
     * THE REGRESSION THIS MUST NOT CAUSE, and the reason it cannot.
     *
     * <p>An ordinary non-MLS RCS group has no MLS state and has never latched the MLS bit, because
     * nothing sets that bit but an MLS message in one direction or the other. Both inputs sit at
     * their default, so the ordinary group is not merely "expected" to keep working — it cannot
     * reach the refusal at all.
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
     * The case the latch cannot see, and the reason {@link Verdict#SEAL} does not consult it: a
     * group joined by Welcome that has neither sent nor received an MLS message has NO latched bit
     * — the bit records that a plane was IN USE, never that one was established — while its peers
     * expect {@code message/mls}.
     */
    @Test
    public void aJoinedGroupThatHasNeverLatchedTheBitStillSeals() {
        assertEquals(Verdict.SEAL,
                MlsSendRouting.decide(SealCapability.SEALABLE, MlsLatch.CLEAR));
        assertEquals(Verdict.SEAL,
                MlsSendRouting.decide(SealCapability.SEALABLE, MlsLatch.UNREADABLE));
    }

    /**
     * A completed downgrade is honest plaintext, not a refusal. RCC.16 &sect;9.1.1 forbids sending
     * encrypted on a downgraded conversation, and {@code downgradeLocally} clears the app's bit as
     * it goes — so once both halves have landed the conversation is genuinely not encrypted and the
     * user is no longer being told otherwise.
     *
     * <p>The window between them (the non-eager arm holds the bit set "until the commit lands")
     * refuses, which is the row above. That is deliberate: during it the app IS still claiming
     * encryption.
     */
    @Test
    public void aCompletedDowngradeSendsPlaintextAndAnIncompleteOneRefuses() {
        assertEquals(Verdict.PLAINTEXT,
                MlsSendRouting.decide(SealCapability.DOWNGRADED, MlsLatch.CLEAR));
        assertEquals(Verdict.REFUSE,
                MlsSendRouting.decide(SealCapability.DOWNGRADED, MlsLatch.LATCHED));
    }

    /**
     * An UNREADABLE bit is treated as CLEAR, matching the padlock rule for the reason recorded there:
     * refusing every group send on a database hiccup would land on exactly the ordinary groups this
     * must not touch. The engine input is not affected by that read, so a healthy MLS group still
     * seals — which is the row above and is what keeps this from being a hole.
     */
    @Test
    public void anUnreadableBitFallsThroughToPlaintextButNeverOverridesTheEngine() {
        assertEquals(Verdict.PLAINTEXT,
                MlsSendRouting.decide(SealCapability.NO_MLS_STATE, MlsLatch.UNREADABLE));
        assertEquals(Verdict.SEAL,
                MlsSendRouting.decide(SealCapability.SEALABLE, MlsLatch.UNREADABLE));
    }

    // ------------------------------------------------------------------ latchOf, and the trap in it

    @Test
    public void latchOfDistinguishesClearFromUnreadable() {
        assertEquals(MlsLatch.LATCHED, MlsSendRouting.latchOf(Boolean.TRUE));
        assertEquals(MlsLatch.CLEAR, MlsSendRouting.latchOf(Boolean.FALSE));
        assertEquals("a failed read is not evidence the conversation is plaintext",
                MlsLatch.UNREADABLE, MlsSendRouting.latchOf(null));
    }

    /** A caller that cannot state its inputs gets the only answer that cannot leak cleartext. */
    @Test
    public void aNullInputRefusesRatherThanDefaultingToPlaintext() {
        assertEquals(Verdict.REFUSE, MlsSendRouting.decide(null, MlsLatch.CLEAR));
        assertEquals(Verdict.REFUSE, MlsSendRouting.decide(SealCapability.SEALABLE, null));
        assertEquals(Verdict.REFUSE, MlsSendRouting.decide(null, null));
    }
}

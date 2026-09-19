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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The vocabulary for "this rebuild has no server pack".
 *
 * <p>The type exists because four situations shared one {@code null}. The way a type like that
 * quietly re-acquires the defect is a {@code switch} whose {@code default} absorbs a new constant:
 * the value is distinct and everything a person ever reads about it is not. So the property under
 * test is that <b>no two constants say the same thing</b>, checked over
 * {@link MlsServerPackOutcome#values()} rather than over a list written here.
 */
public final class MlsServerPackOutcomeTest {

    /**
     * No two constants print the same sentence.
     *
     * <p>This is the check that makes a new constant a TEST FAILURE rather than a silent alias:
     * anything added without its own {@code case} lands on {@code default} and collides with
     * {@link MlsServerPackOutcome#LOOK_FAILED}. It is the same argument {@link MlsRebuildOutcome}
     * makes for declaring its flags rather than deriving them.
     */
    @Test
    public void noTwoOutcomesSayTheSameThing() {
        final Map<String, MlsServerPackOutcome> seen = new HashMap<>();
        final List<String> collisions = new ArrayList<>();
        for (final MlsServerPackOutcome o : MlsServerPackOutcome.values()) {
            final String line = o.line();
            assertNotNull(o + " has no line at all", line);
            assertTrue(o + " prints a line too short to have said anything", line.length() > 30);
            final MlsServerPackOutcome prior = seen.put(line, o);
            if (prior != null) collisions.add(prior + " and " + o);
        }
        if (!collisions.isEmpty()) {
            fail("These MlsServerPackOutcome constants print an IDENTICAL line: " + collisions
                    + ". A constant added with no case of its own falls to default and inherits "
                    + "LOOK_FAILED's sentence — which re-creates the shared-value defect this type "
                    + "was written to end, one layer out, where only a reader can see it.");
        }
        assertEquals("the enum has stopped covering the situations enumerated here: a 1:1, a "
                + "group we hold no state for, a deliberate non-ask, our own ledger refusing, the "
                + "server having nothing, a throw, and the pack actually arriving",
                7, MlsServerPackOutcome.values().length);
    }

    /**
     * {@code spentALook()} is the fact a device fixture quotes, so it is pinned per constant.
     *
     * <p>Not derived from anything: it is the arithmetic of which arm returns above the charge, and
     * getting it wrong is what produced a wrong fixture on 2026-09-09. "Spent" means
     * {@code MlsFetchLedgerRecord.charged} ran — a refusal consults the ledger and increments
     * nothing, so {@link MlsServerPackOutcome#REFUSED_BY_LEDGER} is {@code false} and not
     * {@code true}.
     */
    @Test
    public void onlyTheArmsThatReachTheServerSpendALook() {
        assertEquals(Boolean.FALSE, MlsServerPackOutcome.NOT_A_GROUP.spentALook());
        assertEquals(Boolean.FALSE, MlsServerPackOutcome.NO_LOCAL_STATE_TO_ASK_WITH.spentALook());
        assertEquals(Boolean.FALSE, MlsServerPackOutcome.NOT_ASKED_BY_DESIGN.spentALook());
        assertEquals(Boolean.FALSE, MlsServerPackOutcome.REFUSED_BY_LEDGER.spentALook());
        assertEquals(Boolean.TRUE, MlsServerPackOutcome.SERVER_HAD_NOTHING.spentALook());
        assertEquals(Boolean.TRUE, MlsServerPackOutcome.FETCHED.spentALook());
        assertNull("a throw can come from either side of MlsFetchLedger.mayFetch, so how far it got "
                + "is NOT KNOWN — and unknown is not false",
                MlsServerPackOutcome.LOOK_FAILED.spentALook());
    }

    /**
     * Exactly one constant is UNKNOWN, and it is the one that cannot know.
     *
     * <p>Enumerated rather than asserted constant by constant, so a later constant added with a
     * {@code null} it has not earned fails here: {@code null} is the answer for a throw, not a
     * default for an author who has not worked out which side of the ledger their arm sits on.
     */
    @Test
    public void onlyAThrowIsAllowedToBeUnknown() {
        final List<String> unknown = new ArrayList<>();
        for (final MlsServerPackOutcome o : MlsServerPackOutcome.values()) {
            if (o.spentALook() == null) unknown.add(o.name());
        }
        assertEquals("UNKNOWN is for the arm that genuinely cannot tell — the look that THREW. Any "
                + "other constant answering null is an author defaulting rather than deriving, and "
                + "a fixture planner reading it gets no answer where one exists. Unknown: " + unknown,
                java.util.Collections.singletonList("LOOK_FAILED"), unknown);
    }

    /**
     * The no-carry warning names the situation and keeps the sentence the transport used to carry.
     *
     * <p>The era is passed in rather than duplicated here: {@code ERA_INITIAL} is a WIRE constant
     * and correctly transport-local (it is on the classifier's wire/ABI exempt list, not the policy
     * one), so this class must not acquire a copy of it.
     */
    @Test
    public void theNoCarryWarningNamesWhichAbsenceItIs() {
        for (final MlsServerPackOutcome o : MlsServerPackOutcome.values()) {
            final String warning = o.noCarryLine(1L);
            assertTrue(o + "'s no-carry warning does not contain its own reason, so every absence "
                    + "prints the same sentence again — which is the state this was found in",
                    warning.contains(o.line()));
            assertTrue(o + "'s no-carry warning lost the era it was handed, so a reader cannot tell "
                    + "what the re-establish will be born at", warning.contains("born at era 1,"));
            assertTrue(o + "'s no-carry warning dropped the accepted-and-discarded explanation the "
                    + "transport carried inline before this change",
                    warning.contains("accepted and discarded"));
        }
    }
}

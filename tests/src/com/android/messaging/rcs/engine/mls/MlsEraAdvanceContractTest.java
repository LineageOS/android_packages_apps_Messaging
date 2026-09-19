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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Rework item 9.6 — INV-KP, the WelcomeAction discriminator, and invariants 25/26.
 */
public class MlsEraAdvanceContractTest {

    // ---- INV-KP: key packages IFF era purpose ----

    @Test public void anEraAdvanceWithNoKeyPackagesIsRefused() {
        // The direction our preserving modes actively violated: advancing an era with none leaves a
        // new era containing only us, while every other member still sees the conversation.
        try {
            MlsAdvancePurpose.ERA_ADVANCEMENT.assertKeyPackages(0);
            fail("an era advance with zero key packages must throw");
        } catch (final IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("requires at least one KeyPackage"));
        }
    }

    @Test public void aNonEraAdvanceWithKeyPackagesIsRefused() {
        // The other direction, and it must NOT be a silent drop: a caller that got the purpose wrong
        // would otherwise look like it worked.
        for (final MlsAdvancePurpose p : new MlsAdvancePurpose[] {
                MlsAdvancePurpose.EPOCH_ADVANCEMENT, MlsAdvancePurpose.PHOENIX_MODE }) {
            try {
                p.assertKeyPackages(3);
                fail(p + " with key packages must throw");
            } catch (final IllegalStateException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().startsWith("Required 0 keypackages for " + p.name()));
            }
        }
    }

    @Test public void theLegalCombinationsPass() {
        MlsAdvancePurpose.ERA_ADVANCEMENT.assertKeyPackages(1);
        MlsAdvancePurpose.ERA_ADVANCEMENT.assertKeyPackages(7);
        MlsAdvancePurpose.EPOCH_ADVANCEMENT.assertKeyPackages(0);
        MlsAdvancePurpose.PHOENIX_MODE.assertKeyPackages(0);
    }

    @Test public void phoenixAdvancesAnEraWithoutKeyPackages() {
        // The documented exception: a phoenix advance leaves the group rather than re-establishing
        // it, so claiming a package per member would claim packages for a group nobody will join.
        assertFalse(MlsAdvancePurpose.PHOENIX_MODE.requiresKeyPackages());
        assertTrue(MlsAdvancePurpose.ERA_ADVANCEMENT.requiresKeyPackages());
    }

    // ---- the WelcomeAction accept-set ----

    @Test public void theAcceptSetIsExactlyTheThree() {
        assertTrue(MlsWelcomeAction.NEW_GROUP.acceptedAsJoin());
        assertTrue(MlsWelcomeAction.NEW_ERA_EXISTING_GROUP.acceptedAsJoin());
        assertTrue(MlsWelcomeAction.REFRESH_MEMBERSHIP_EXISTING_GROUP.acceptedAsJoin());
        assertFalse(MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP.acceptedAsJoin());
        assertFalse(MlsWelcomeAction.UNKNOWN.acceptedAsJoin());
    }

    @Test public void theWireNumberingMatchesTheReferenceClient() {
        // §9.5, five values. The first cut of this enum had four numbered 0..3, which both swapped
        // the last two AND put a real join on zero — see the next test for why zero matters.
        assertEquals(0, MlsWelcomeAction.UNKNOWN.wire);
        assertEquals(1, MlsWelcomeAction.NEW_GROUP.wire);
        assertEquals(2, MlsWelcomeAction.NEW_ERA_EXISTING_GROUP.wire);
        assertEquals(3, MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP.wire);
        assertEquals(4, MlsWelcomeAction.REFRESH_MEMBERSHIP_EXISTING_GROUP.wire);
        assertEquals(5, MlsWelcomeAction.values().length);
    }

    @Test public void zeroIsNotJoinableBecauseThatIsWhatAnAbsentFieldBecomes() {
        // welcomeAction is a proto3 field: absent and defaulted both arrive as 0. Whichever arm holds
        // zero is the one "the engine said nothing" silently becomes, so it must not be a join.
        assertEquals(MlsWelcomeAction.UNKNOWN, MlsWelcomeAction.fromWire(0));
        try {
            MlsWelcomeAction.requireJoinable(0);
            fail("wire 0 must not be joinable");
        } catch (final IllegalStateException expected) {
            assertEquals("The commit is not for advancing era, welcomeAction=0.",
                    expected.getMessage());
        }
    }

    @Test public void addingMembersIsNotAJoinAndQuotesTheRawValue() {
        // The arm that does damage if mistaken for a join: it would re-create local state for a group
        // we already hold, discarding our leaf and every message key.
        assertTrue(MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP.routesToAddMembers());
        try {
            MlsWelcomeAction.requireJoinable(MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP);
            fail("must not be joinable");
        } catch (final IllegalStateException expected) {
            assertEquals("The commit is not for advancing era, welcomeAction=3.",
                    expected.getMessage());
        }
    }

    @Test public void theAddMembersLineIsTheReferenceClientsVerbatim() {
        assertEquals("An addMembers commit needs to be sent. Welcome action: 3",
                MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP.addMembersLine());
    }

    @Test public void anUnnamedActionIsRefusedRatherThanAssumedSafe() {
        assertNull(MlsWelcomeAction.fromWire(99));
        try {
            MlsWelcomeAction.requireJoinable(99);
            fail("an unnamed action must not be joined");
        } catch (final IllegalStateException expected) {
            // The raw number is the whole point: the interesting case is the one we have no name for.
            assertEquals("The commit is not for advancing era, welcomeAction=99.",
                    expected.getMessage());
        }
    }

    @Test public void nullIsReportedAsZeroNotAsSomethingElse() {
        // null has no raw value of its own; an absent field IS zero, so calling it anything else
        // would misname it in the one message a trace diff reads.
        try {
            MlsWelcomeAction.requireJoinable((MlsWelcomeAction) null);
            fail("null must not be joined");
        } catch (final IllegalStateException expected) {
            assertEquals("The commit is not for advancing era, welcomeAction=0.",
                    expected.getMessage());
        }
    }

    @Test public void theJoinableOnesDoNotThrow() {
        MlsWelcomeAction.requireJoinable(MlsWelcomeAction.NEW_GROUP);
        MlsWelcomeAction.requireJoinable(MlsWelcomeAction.NEW_ERA_EXISTING_GROUP);
        MlsWelcomeAction.requireJoinable(MlsWelcomeAction.REFRESH_MEMBERSHIP_EXISTING_GROUP);
    }

    @Test public void wireValuesRoundTrip() {
        for (final MlsWelcomeAction a : MlsWelcomeAction.values()) {
            assertEquals(a, MlsWelcomeAction.fromWire(a.wire));
        }
    }

    // ---- invariant 25: the four hard rejections, in order ----

    private static final byte[] SOME = { 1, 2, 3 };

    @Test public void allFourPresentPasses() {
        assertEquals(MlsGroupInfoGate.Verdict.OK,
                MlsGroupInfoGate.check(SOME, SOME, SOME, SOME));
    }

    @Test public void eachMissingArtefactIsNamedSpecifically() {
        assertEquals(MlsGroupInfoGate.Verdict.EMPTY_GROUP_INFO,
                MlsGroupInfoGate.check(null, SOME, SOME, SOME));
        assertEquals(MlsGroupInfoGate.Verdict.EMPTY_RATCHET_TREE,
                MlsGroupInfoGate.check(SOME, new byte[0], SOME, SOME));
        assertEquals(MlsGroupInfoGate.Verdict.EMPTY_LATEST_EPOCH_AUTHENTICATOR,
                MlsGroupInfoGate.check(SOME, SOME, null, SOME));
        assertEquals(MlsGroupInfoGate.Verdict.EMPTY_PAGINATED_EPOCH_IDENTIFIER,
                MlsGroupInfoGate.check(SOME, SOME, SOME, new byte[0]));
    }

    @Test public void theGroupInfoIsCheckedFIRST() {
        // The other three are read out of it, so reporting a missing tree when the whole structure is
        // absent points the reader at the wrong thing.
        assertEquals(MlsGroupInfoGate.Verdict.EMPTY_GROUP_INFO,
                MlsGroupInfoGate.check(null, null, null, null));
    }

    // ---- invariant 26: prefer the server's identifier ----

    @Test public void theServersIdentifierWins() {
        final byte[] server = { 9, 9 };
        final byte[] ours = { 1, 1 };
        assertArrayEquals(server, MlsGroupInfoGate.preferredEpochIdentifier(server, ours));
        assertFalse(MlsGroupInfoGate.usedFallback(server));
    }

    @Test public void ourPairIsUsedONLYWhenTheServerNamedNone() {
        // Not a tie-break between two spellings of one fact: the identifier is what the SERVER
        // considers current, and the reason we are fetching is that it may disagree with us.
        // Resolving that with our own number answers the question with the thing in question.
        final byte[] ours = { 1, 1 };
        assertArrayEquals(ours, MlsGroupInfoGate.preferredEpochIdentifier(null, ours));
        assertArrayEquals(ours, MlsGroupInfoGate.preferredEpochIdentifier(new byte[0], ours));
        assertTrue(MlsGroupInfoGate.usedFallback(null));
        assertTrue(MlsGroupInfoGate.usedFallback(new byte[0]));
    }

    @Test public void withNeitherTheResultIsEmptyNotNull() {
        assertArrayEquals(new byte[0], MlsGroupInfoGate.preferredEpochIdentifier(null, null));
    }

    @Test public void theReturnedIdentifierIsACopy() {
        final byte[] server = { 9, 9 };
        final byte[] got = MlsGroupInfoGate.preferredEpochIdentifier(server, null);
        got[0] = 7;
        assertEquals(9, server[0]);
    }
}

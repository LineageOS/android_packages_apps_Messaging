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

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link MlsArtifactBundle} — the engine→host slot layout, and the three slots later
 * added to it.
 *
 * <p>The layout is positional across a language boundary whose two halves are compiled separately
 * (the Rust staticlib is a checked-in prebuilt), so "an older .a" and "a newer .a" are both normal
 * states of the tree. These tests pin the decode's behaviour in both.
 */
public final class MlsArtifactBundleTest {

    private static byte[] be32(final long v) {
        return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }

    private static byte[] ascii(final String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** A six-slot bundle — everything built through an era-TAKING entry point. */
    private static byte[] sixSlot() {
        return MlsArtifactBundle.joinLenPrefixed(Arrays.asList(
                ascii("welcome"), ascii("commit"), ascii("groupInfo"), ascii("tag"),
                ascii("gid"), ascii("tree")));
    }

    /**
     * THE COMPATIBILITY CASE, and the one that must never regress: a bundle from an engine that
     * does not report a plan still decodes, and reports the absence rather than inventing one.
     */
    @Test
    public void aSixSlotBundleDecodesWithNoPlanRatherThanADefaultedOne() {
        final MlsGroupArtifacts a = MlsArtifactBundle.decode(sixSlot());
        assertNotNull(a);
        assertEquals("welcome", new String(a.welcome));
        assertEquals("tree", new String(a.ratchetTree));
        assertEquals("an absent era must be -1, never 0 — 0 is a legal-looking era", -1L, a.era);
        assertNull("an absent action must be null, never UNKNOWN", a.welcomeAction);
        assertTrue(a.admittedMembers.isEmpty());
    }

    /**
     * An absent {@code welcomeAction} slot must NOT decode to wire 0.
     *
     * <p>Wire 0 is {@link MlsWelcomeAction#UNKNOWN} — a real value meaning "the engine named no
     * action" — and the two want opposite responses from the host: an old engine should fall back,
     * an engine that refused to decide should be refused in turn. Collapsing them is the whole
     * reason the enum reserves zero in the first place.
     */
    @Test
    public void anAbsentActionIsNotTheUnknownAction() {
        assertNull(MlsArtifactBundle.decode(sixSlot()).welcomeAction);
        final List<byte[]> withUnknown = new ArrayList<>(
                MlsArtifactBundle.splitLenPrefixed(sixSlot()));
        withUnknown.add(be32(7));            // era
        withUnknown.add(be32(0));            // welcomeAction = UNKNOWN, explicitly
        final MlsGroupArtifacts a =
                MlsArtifactBundle.decode(MlsArtifactBundle.joinLenPrefixed(withUnknown));
        assertEquals(MlsWelcomeAction.UNKNOWN, a.welcomeAction);
        assertEquals(7L, a.era);
    }

    /** The full eight-slot create: era and action come back as the engine's own answer. */
    @Test
    public void aPlannedCreateReportsItsEraAndItsAction() {
        final List<byte[]> p = new ArrayList<>(MlsArtifactBundle.splitLenPrefixed(sixSlot()));
        p.add(be32(1));
        p.add(be32(MlsWelcomeAction.NEW_GROUP.wire));
        p.add(new byte[0]);
        final MlsGroupArtifacts a = MlsArtifactBundle.decode(MlsArtifactBundle.joinLenPrefixed(p));
        assertEquals(1L, a.era);
        assertEquals(MlsWelcomeAction.NEW_GROUP, a.welcomeAction);
        assertTrue("a create admits nobody by this slot — the Welcome does that",
                a.admittedMembers.isEmpty());
    }

    /**
     * The add arm carries WHO, and that list is what the RCS half of the add must name.
     *
     * <p>If it named anyone else the server would see a roster change and a commit that disagree —
     * {@code mismatched-rcs-group-state}. Only the engine knows which of the requested packages were
     * new, so this slot is the answer travelling up rather than being recomputed.
     */
    @Test
    public void theAddArmCarriesTheMembersItAdmits() {
        final List<byte[]> p = new ArrayList<>(MlsArtifactBundle.splitLenPrefixed(sixSlot()));
        p.add(be32(4));
        p.add(be32(MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP.wire));
        p.add(MlsArtifactBundle.joinLenPrefixed(
                Arrays.asList(ascii("+15715550108"), ascii("+15715550104"))));
        final MlsGroupArtifacts a = MlsArtifactBundle.decode(MlsArtifactBundle.joinLenPrefixed(p));
        assertEquals(MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP, a.welcomeAction);
        assertTrue("the add arm does NOT move the era", a.era == 4L);
        assertEquals(Arrays.asList("+15715550108", "+15715550104"), a.admittedMembers);
    }

    /**
     * A slot of the wrong WIDTH reads as absent, not as a truncated number.
     *
     * <p>A two-byte era means the two builds disagree about the layout. Decoding it anyway would
     * turn a build skew into a WRONG era — which is the failure this whole class is about — instead
     * of a missing one, which the caller already refuses safely.
     */
    @Test
    public void aWrongWidthEraSlotReadsAsAbsent() {
        final List<byte[]> p = new ArrayList<>(MlsArtifactBundle.splitLenPrefixed(sixSlot()));
        p.add(new byte[] { 0x00, 0x03 });
        p.add(be32(MlsWelcomeAction.NEW_GROUP.wire));
        assertEquals(-1L, MlsArtifactBundle.decode(MlsArtifactBundle.joinLenPrefixed(p)).era);
    }

    /** A truncated tail keeps the slots already read rather than discarding a usable commit. */
    @Test
    public void aTruncatedTailKeepsWhatWasAlreadyRead() {
        final byte[] full = sixSlot();
        final byte[] cut = Arrays.copyOf(full, full.length - 3);
        final MlsGroupArtifacts a = MlsArtifactBundle.decode(cut);
        assertEquals("welcome", new String(a.welcome));
        assertEquals("gid", new String(a.groupId));
    }

    @Test
    public void nullIsAFailedOperationAndStaysNull() {
        assertNull(MlsArtifactBundle.decode(null));
    }

    /** {@code admittedMembers} is never null, so no caller has to null-check before iterating. */
    @Test
    public void admittedMembersIsNeverNull() {
        assertNotNull(MlsArtifactBundle.decode(sixSlot()).admittedMembers);
        assertNotNull(new MlsGroupArtifacts(null, null, null, null, null).admittedMembers);
    }
}

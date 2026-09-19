/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * {@link MlsArtifactBundle}: the engine-to-host slot layout. The layout is positional across a
 * language boundary whose halves are compiled separately (the Rust staticlib is a prebuilt), so a
 * bundle with fewer or more slots are both normal and both must decode. See docs/mls/rust-core.md.
 */
public final class MlsArtifactBundleTest {

    private static byte[] be32(final long v) {
        return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }

    private static byte[] ascii(final String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** A six-slot bundle, as built through an era-taking entry point. */
    private static byte[] sixSlot() {
        return MlsArtifactBundle.joinLenPrefixed(Arrays.asList(
                ascii("welcome"), ascii("commit"), ascii("groupInfo"), ascii("tag"),
                ascii("gid"), ascii("tree")));
    }

    /**
     * A bundle from an engine that does not report a plan still decodes, and reports the absence
     * rather than inventing one.
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
     * An absent {@code welcomeAction} slot does not decode to wire 0:
     * {@link MlsWelcomeAction#UNKNOWN} means "the engine named no action" and wants the opposite
     * response (an old engine falls back; an engine that refused to decide is refused).
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
     * The add arm carries the admitted members, which the RCS half of the add must name; naming
     * anyone else makes the roster change and the commit disagree
     * ({@code mismatched-rcs-group-state}).
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
     * A slot of the wrong width reads as absent, not as a truncated number, so a layout skew
     * becomes a missing era the caller refuses rather than a wrong one.
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

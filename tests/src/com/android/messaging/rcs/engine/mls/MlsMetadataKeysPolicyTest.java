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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * RCC.16 §10.5 — Recovering Group Metadata Keys, and the §7.13.4 body it travels in.
 *
 * <p>The headline is that §10.5 is <b>not</b> a v4.0 section: it exists in v3.0 as "Recovering Group
 * Subject and Icon", and we were failing that obligation. So these tests exercise BOTH revisions of
 * the rule, and the v3.0 arm is the one that matters today.
 */
public class MlsMetadataKeysPolicyTest {

    private static byte[] ascii(final String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    private static final byte[] KEY = ascii("0123456789abcdef0123456789abcdef");
    private static final byte[] TAG = ascii("hmac-tag-32-bytes-long-padding!!");

    // ---- §7.13.4 GroupMetadataKeys ------------------------------------------------------------

    @Test
    public void groupMetadataKeys_roundTripsBothFieldsIndependently() {
        final byte[] fileInfo = RccFileInfo.encode(RccFileInfo.SLOT_ICON,
                new RccFileInfo.Metadata("group_icon", "image/png", KEY, ascii("iv-16-bytes-long"),
                        TAG, RccFileInfo.ALGORITHM_AES256_CTR_HMAC_SHA256_256TAG, 4096));
        assertNotNull(fileInfo);

        final RccGroupMetadataKeys both = new RccGroupMetadataKeys(ascii("token"), fileInfo);
        final RccGroupMetadataKeys back = RccGroupMetadataKeys.parse(both.encode());
        assertNotNull(back);
        assertArrayEquals(ascii("token"), back.continuityToken);
        assertArrayEquals(fileInfo, back.subjectIconKeys);
        assertFalse(back.isEmpty());
        assertNotNull("field 2 must parse back as a §7.8.1 FileInfo", back.keys());
        assertEquals("group_icon", back.keys().metadata.fileName);
        assertEquals(4096, back.keys().metadata.fileLengthHint);

        // §8.3.1.3 sends the token alone; §9.7.1.4 sends the keys alone. Both are legal.
        final RccGroupMetadataKeys tokenOnly = new RccGroupMetadataKeys(ascii("token"), null);
        assertArrayEquals(ascii("token"),
                RccGroupMetadataKeys.parse(tokenOnly.encode()).continuityToken);
        assertNull(RccGroupMetadataKeys.parse(tokenOnly.encode()).keys());

        final RccGroupMetadataKeys keysOnly = new RccGroupMetadataKeys(null, fileInfo);
        assertEquals(0, RccGroupMetadataKeys.parse(keysOnly.encode()).continuityToken.length);
    }

    /** An empty body is a legal proto3 encoding — the caller's test is isEmpty(), not null. */
    @Test
    public void anEmptyGroupMetadataKeysParsesRatherThanFailing() {
        final RccGroupMetadataKeys empty = RccGroupMetadataKeys.parse(new byte[0]);
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
        assertEquals(0, new RccGroupMetadataKeys(null, null).encode().length);
    }

    // ---- §10.5.1 detection --------------------------------------------------------------------

    /**
     * v4.0 changed BOTH the commitment label ({@code icon_commitment} → {@code group_icon}) and the
     * committed value (key material → {@code hmac_tag}). Either change alone yields a commitment
     * that verifies against nothing, and our v3.0 values were byte-confirmed against Google Messages —
     * so both revisions are correct, each for itself.
     */
    @Test
    public void detectionUsesTheVersionCorrectLabelAndValue() {
        final byte[] v3Commitment = RccCommitment.iconCommitment(Rcc16Version.V3_0, KEY, TAG);
        final byte[] v4Commitment = RccCommitment.iconCommitment(Rcc16Version.V4_0, KEY, TAG);
        assertFalse("the two revisions must not produce the same commitment",
                Arrays.equals(v3Commitment, v4Commitment));
        assertArrayEquals(RccCommitment.commit("icon_commitment", KEY), v3Commitment);
        assertArrayEquals(RccCommitment.commit("group_icon", TAG), v4Commitment);

        assertEquals(MlsMetadataKeysPolicy.Sync.IN_SYNC,
                MlsMetadataKeysPolicy.detect(v3Commitment, KEY, Rcc16Version.V3_0, true));
        assertEquals(MlsMetadataKeysPolicy.Sync.IN_SYNC,
                MlsMetadataKeysPolicy.detect(v4Commitment, TAG, Rcc16Version.V4_0, true));
        // Cross-applying a revision's commitment to the other's rule is exactly the silent failure.
        assertEquals(MlsMetadataKeysPolicy.Sync.OUT_OF_SYNC,
                MlsMetadataKeysPolicy.detect(v3Commitment, TAG, Rcc16Version.V4_0, true));

        // The subject labels differ from the icon ones in both revisions.
        assertArrayEquals(RccCommitment.commit("subject_commitment", KEY),
                RccCommitment.subjectCommitment(Rcc16Version.V3_0, KEY, TAG));
        assertArrayEquals(RccCommitment.commit("group_subject", TAG),
                RccCommitment.subjectCommitment(Rcc16Version.V4_0, KEY, TAG));
    }

    @Test
    public void noCommitmentMeansNoEncryptedMetadata_notAFailure() {
        assertEquals(MlsMetadataKeysPolicy.Sync.NO_ENCRYPTED_METADATA,
                MlsMetadataKeysPolicy.detect(null, KEY, Rcc16Version.V3_0, true));
        assertEquals(MlsMetadataKeysPolicy.Sync.NO_ENCRYPTED_METADATA,
                MlsMetadataKeysPolicy.detect(new byte[0], KEY, Rcc16Version.V3_0, false));
    }

    /** A commitment present with no local key is the clearest out-of-sync: we cannot open it. */
    @Test
    public void aCommitmentWithNoLocalKeyIsOutOfSync() {
        final byte[] c = RccCommitment.iconCommitment(Rcc16Version.V3_0, KEY, TAG);
        assertEquals(MlsMetadataKeysPolicy.Sync.OUT_OF_SYNC,
                MlsMetadataKeysPolicy.detect(c, null, Rcc16Version.V3_0, true));
        assertEquals(MlsMetadataKeysPolicy.Sync.OUT_OF_SYNC,
                MlsMetadataKeysPolicy.detect(c, new byte[0], Rcc16Version.V3_0, true));
    }

    // ---- §10.5.2 request ----------------------------------------------------------------------

    /**
     * The request mechanism is the 0xF007 GroupContext extension, and 0xF007 <b>does not exist in
     * v3.0</b>. Asking anyway would put an extension a v3.0 peer has no handler for into the shared
     * GroupContext — so under v3.0 an out-of-sync client cannot ask, and recovery depends on the
     * sender-side rule firing.
     */
    @Test
    public void keysAreOnlyRequestedUnderV4Because0xF007DoesNotExistInV3() {
        assertTrue(MlsMetadataKeysPolicy.shouldRequestKeys(
                MlsMetadataKeysPolicy.Sync.OUT_OF_SYNC, Rcc16Version.V4_0));
        assertFalse(MlsMetadataKeysPolicy.shouldRequestKeys(
                MlsMetadataKeysPolicy.Sync.OUT_OF_SYNC, Rcc16Version.V3_0));
        assertFalse(MlsMetadataKeysPolicy.shouldRequestKeys(
                MlsMetadataKeysPolicy.Sync.IN_SYNC, Rcc16Version.V4_0));
        assertFalse(MlsMetadataKeysPolicy.shouldRequestKeys(
                MlsMetadataKeysPolicy.Sync.NO_ENCRYPTED_METADATA, Rcc16Version.V4_0));
    }

    // ---- §10.5.3 send -------------------------------------------------------------------------

    /**
     * THE v3.0 OBLIGATION WE WERE FAILING. There is no request extension in v3.0, so the trigger is
     * structural: an External Commit means somebody joined without a Welcome and therefore without
     * keys. Our whole recovery story is external-commit resync, so this fires on the path we
     * actually run — every peer that healed into one of our groups was left unable to decrypt the
     * group icon and subject.
     */
    @Test
    public void underV3AnExternalCommitAloneTriggersTheKeySend() {
        assertTrue(MlsMetadataKeysPolicy.shouldSendKeys(
                /*requested=*/ false, /*externalCommit=*/ true, /*inSync=*/ true,
                /*haveKeys=*/ true, Rcc16Version.V3_0));
        // An ordinary commit is not the v3.0 trigger.
        assertFalse(MlsMetadataKeysPolicy.shouldSendKeys(
                false, false, true, true, Rcc16Version.V3_0));
    }

    /** Under v4.0 the trigger is the ASK, not the External Commit. */
    @Test
    public void underV4TheTriggerIsTheRequestExtension() {
        assertTrue(MlsMetadataKeysPolicy.shouldSendKeys(
                /*requested=*/ true, /*externalCommit=*/ false, true, true, Rcc16Version.V4_0));
        assertFalse("an External Commit with nobody asking is not a v4.0 trigger",
                MlsMetadataKeysPolicy.shouldSendKeys(false, true, true, true, Rcc16Version.V4_0));
    }

    /**
     * Never forward key material we cannot ourselves verify. Doing so propagates a wrong key to
     * everyone who asks, and the receiver's only symptom is a tag mismatch with no way to tell who
     * introduced it.
     */
    @Test
    public void keysAreNeverForwardedWhileWeAreOurselvesOutOfSync() {
        for (final Rcc16Version v : Rcc16Version.values()) {
            assertFalse(MlsMetadataKeysPolicy.shouldSendKeys(true, true, /*inSync=*/ false, true, v));
        }
    }

    @Test
    public void nothingIsSentWhenWeHoldNoKeys() {
        for (final Rcc16Version v : Rcc16Version.values()) {
            assertFalse(MlsMetadataKeysPolicy.shouldSendKeys(true, true, true, /*haveKeys=*/ false, v));
        }
    }

    /**
     * The responder must also REMOVE the request extension in the same Commit. Leaving it means
     * every subsequent Commit or Welcome re-triggers the send — the group would broadcast its
     * metadata keys forever.
     */
    @Test
    public void respondingAlsoRemovesTheRequestExtension() {
        assertTrue(MlsMetadataKeysPolicy.shouldRemoveRequestExtension(
                /*requested=*/ true, /*sending=*/ true, Rcc16Version.V4_0));
        assertFalse(MlsMetadataKeysPolicy.shouldRemoveRequestExtension(true, false, Rcc16Version.V4_0));
        assertFalse(MlsMetadataKeysPolicy.shouldRemoveRequestExtension(false, true, Rcc16Version.V4_0));
        assertFalse("v3.0 has no such extension to remove",
                MlsMetadataKeysPolicy.shouldRemoveRequestExtension(true, true, Rcc16Version.V3_0));
    }

    /**
     * A metadata-keys body must never reach the conversation. It is binary protobuf, so rendering it
     * puts raw bytes in the thread — the exact failure class, and after
     * encryption it applies to EVERY type because the transport can no longer see inside.
     */
    @Test
    public void aGroupMetadataKeysBodyIsDroppedAsAKeyNotRendered() {
        assertEquals(RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify(RccGroupMetadataKeys.CONTENT_TYPE));
        assertEquals("a charset parameter must not defeat the match", RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify(RccGroupMetadataKeys.CONTENT_TYPE + ";charset=UTF-8"));
        assertTrue(RccContentDisposition.isDrop(
                RccContentDisposition.classify(RccGroupMetadataKeys.CONTENT_TYPE)));
        // ...and it stays distinct from the §7.8.1 FileInfo leg, which has a different consumer.
        assertEquals(RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify(RccFileInfo.CONTENT_TYPE));
    }

    // ---- §10.1.1 participant-key resync -------------------------------------------------------

    private static MlsParticipantKeyResync.Leaf leaf(final int i, final String p, final String k) {
        return new MlsParticipantKeyResync.Leaf(i, p, k);
    }

    /**
     * An External Commit may carry only ONE Remove proposal, so N stale clients need one Remove plus
     * N−1 ServerRemoves. That constraint is what makes this need a plan rather than a loop.
     */
    @Test
    public void staleClientsBecomeOneRemovePlusServerRemoves() {
        final List<MlsParticipantKeyResync.Leaf> roster = Arrays.asList(
                leaf(0, "+1", "keyNEW"),
                leaf(1, "+1", "keyOLD"),
                leaf(2, "+2", "keyOLD"),      // different participant — untouched
                leaf(3, "+1", "keyOLD"),
                leaf(4, "+1", "keyNEW"));
        final MlsParticipantKeyResync.Plan p =
                MlsParticipantKeyResync.plan(roster, "+1", "keyNEW");
        assertEquals(2, p.size());
        assertEquals("the lowest stale index becomes the single Remove", 1, p.removeLeaf);
        assertEquals(Arrays.asList(3), p.serverRemoveLeaves);
        assertFalse(p.isEmpty());
    }

    @Test
    public void aParticipantAlreadyOnTheCurrentKeyNeedsNothing() {
        final MlsParticipantKeyResync.Plan p = MlsParticipantKeyResync.plan(
                Arrays.asList(leaf(0, "+1", "keyNEW"), leaf(1, "+1", "keyNEW")), "+1", "keyNEW");
        assertTrue(p.isEmpty());
        assertEquals(-1, p.removeLeaf);
    }

    /**
     * A leaf with no recorded signing key is NOT assumed stale. Removing a member on missing
     * metadata is unrecoverable for them and, from their side, indistinguishable from being kicked —
     * an absent value means we did not look, not that it is old.
     */
    @Test
    public void aLeafWithNoRecordedKeyIsNotAssumedStale() {
        final MlsParticipantKeyResync.Plan p = MlsParticipantKeyResync.plan(
                Arrays.asList(leaf(0, "+1", ""), leaf(1, "+1", null)), "+1", "keyNEW");
        assertTrue(p.isEmpty());
    }

    /**
     * AN ABSENT {@code currentKey} PLANS NOTHING — the mirror of
     * {@link #aLeafWithNoRecordedKeyIsNotAssumedStale}, and the more dangerous half.
     *
     * <p>Without the guard, a null or empty {@code currentKey} matches no leaf, so EVERY client of
     * the participant is classified stale and the plan removes the participant from the group
     * outright. An absent value means "we have not established which key is current" — exactly
     * what {@link MlsParticipantKeyLedger} returns when a participant is split across two keys and
     * nothing distinguishes the newcomer from the survivor — and the only safe answer is to do
     * nothing. Found by pairing the ledger's refusal with the plan it produces.
     */
    @Test
    public void anAbsentCurrentKeyPlansNothingRatherThanRemovingEveryone() {
        final List<MlsParticipantKeyResync.Leaf> roster = Arrays.asList(
                leaf(0, "+1", "keyA"), leaf(1, "+1", "keyB"), leaf(2, "+1", "keyA"));
        assertTrue("empty currentKey must remove nobody",
                MlsParticipantKeyResync.plan(roster, "+1", "").isEmpty());
        assertTrue("null currentKey must remove nobody",
                MlsParticipantKeyResync.plan(roster, "+1", null).isEmpty());
        // The control: with a real currentKey the SAME roster does plan removals, so the two
        // assertions above are not passing because nothing was ever stale.
        assertEquals(2, MlsParticipantKeyResync.plan(roster, "+1", "keyB").size());
    }

    /**
     * v4.0 §7.11.9's ServerRemove body — a body we had CLOSED as unknowable across all three
     * sources. {@code uint32} in MLS is four bytes BIG-endian; a protobuf varint or an MLS vector
     * varint would both encode a small leaf index in one byte and pass every hand-written test.
     */
    @Test
    public void serverRemoveBodyIsABigEndianUint32() {
        assertArrayEquals(new byte[] {0, 0, 0, 3}, MlsParticipantKeyResync.serverRemoveBody(3));
        assertArrayEquals(new byte[] {0, 0, 1, 0}, MlsParticipantKeyResync.serverRemoveBody(256));
        assertArrayEquals(new byte[] {0x12, 0x34, 0x56, 0x78},
                MlsParticipantKeyResync.serverRemoveBody(0x12345678));
        assertNull(MlsParticipantKeyResync.serverRemoveBody(-1));

        for (final int i : new int[] {0, 1, 127, 128, 255, 256, 65535, 0x7FFFFFFF}) {
            assertEquals(i, MlsParticipantKeyResync.parseServerRemoveBody(
                    MlsParticipantKeyResync.serverRemoveBody(i)));
        }
        assertEquals(-1, MlsParticipantKeyResync.parseServerRemoveBody(new byte[] {3}));
        assertEquals(-1, MlsParticipantKeyResync.parseServerRemoveBody(null));
    }

    /**
     * The two retention caps Stage 6 moved here, at their shipping values.
     *
     * <p>{@code RETAINED_CONTENT_KEYS_PER_SLOT} is asserted to be small AND greater than one, which
     * is the whole of the reasoning: one would drop the key that arrived before the commit
     * that makes it current — a race this scheme exists to survive — while retaining everything
     * partially undoes the forward secrecy MLS gives by rotating epoch secrets.
     */
    @Test
    public void theRetentionCapIsSmallButNotOne() {
        assertEquals(4, MlsMetadataKeysPolicy.RETAINED_CONTENT_KEYS_PER_SLOT);
        assertTrue("a cap of one drops the key whose commit has not landed yet",
                MlsMetadataKeysPolicy.RETAINED_CONTENT_KEYS_PER_SLOT > 1);
        assertFalse(MlsMetadataKeysPolicy.overRetentionCap(
                MlsMetadataKeysPolicy.RETAINED_CONTENT_KEYS_PER_SLOT));
        assertTrue(MlsMetadataKeysPolicy.overRetentionCap(
                MlsMetadataKeysPolicy.RETAINED_CONTENT_KEYS_PER_SLOT + 1));
        assertEquals(MlsMetadataKeysPolicy.RETAINED_CONTENT_KEYS_PER_SLOT,
                MlsMetadataKeysPolicy.retainedContentKeysPerSlot());
    }

    /**
     * The held-subject bound counts HOLDERS, and the eighth is refused.
     *
     * <p>The caller has already cleared its own slot before asking, so the count excludes it and the
     * question is "would this make an eighth holder?" — the comparison the old per-key map's
     * {@code size()} made before the state was consolidated onto ConvState.
     */
    @Test
    public void anEighthHolderIsRefused() {
        assertEquals(8, MlsMetadataKeysPolicy.MAX_PENDING_SUBJECT);
        assertTrue(MlsMetadataKeysPolicy.mayHoldAnotherSubject(0));
        assertTrue(MlsMetadataKeysPolicy.mayHoldAnotherSubject(
                MlsMetadataKeysPolicy.MAX_PENDING_SUBJECT - 1));
        assertFalse(MlsMetadataKeysPolicy.mayHoldAnotherSubject(
                MlsMetadataKeysPolicy.MAX_PENDING_SUBJECT));
    }

    /**
     * The held-ICON bound counts BYTES, and that difference is the whole point of it existing
     * separately.
     *
     * <p>RED WHEN someone "harmonises" the two bounds by counting icon holders: eight conversations
     * holding a 256 KB icon each — reachable by peers simply changing their group icons while we
     * are behind on keys — is 2 MB of retained heap that a holder count does not see. The subject's
     * bound is safe as a count only because a held subject is a few dozen bytes.
     */
    @Test
    public void theIconBoundIsPricedInBytesNotHolders() {
        assertEquals(1024 * 1024, MlsMetadataKeysPolicy.MAX_PENDING_ICON_BYTES);
        // Empty-handed, an ordinary icon fits.
        assertTrue(MlsMetadataKeysPolicy.mayHoldAnotherIcon(0, 64 * 1024));
        // Exactly at the budget is allowed; one byte past it is not.
        assertTrue(MlsMetadataKeysPolicy.mayHoldAnotherIcon(
                MlsMetadataKeysPolicy.MAX_PENDING_ICON_BYTES - 100, 100));
        assertFalse(MlsMetadataKeysPolicy.mayHoldAnotherIcon(
                MlsMetadataKeysPolicy.MAX_PENDING_ICON_BYTES - 100, 101));
        // The count that WOULD pass a holder-style bound: eight 256 KB icons is twice the budget,
        // and the fourth is where bytes start refusing what a count of eight would wave through.
        assertTrue(MlsMetadataKeysPolicy.mayHoldAnotherIcon(3L * 256 * 1024, 256 * 1024));
        assertFalse(MlsMetadataKeysPolicy.mayHoldAnotherIcon(4L * 256 * 1024, 256 * 1024));
        // A zero-length candidate is not a hold; refusing it keeps an empty entry out of the map
        // rather than letting it occupy a slot that looks like a pending icon.
        assertFalse(MlsMetadataKeysPolicy.mayHoldAnotherIcon(0, 0));
    }

}

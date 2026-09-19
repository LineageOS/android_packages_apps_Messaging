/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * RCC.16 §10.5, recovering the group metadata keys, and the RCC.16 §7.13.4 body they travel in. The
 * section exists in v3.0 too ("Recovering Group Subject and Icon"), so both revisions are
 * exercised. See docs/mls/metadata.md.
 */
public class MlsMetadataKeysPolicyTest {

    private static byte[] ascii(final String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    private static final byte[] KEY = ascii("0123456789abcdef0123456789abcdef");
    private static final byte[] TAG = ascii("hmac-tag-32-bytes-long-padding!!");

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

        // RCC.16 §8.3.1.3 sends the token alone; §9.7.1.4 sends the keys alone. Both are legal.
        final RccGroupMetadataKeys tokenOnly = new RccGroupMetadataKeys(ascii("token"), null);
        assertArrayEquals(ascii("token"),
                RccGroupMetadataKeys.parse(tokenOnly.encode()).continuityToken);
        assertNull(RccGroupMetadataKeys.parse(tokenOnly.encode()).keys());

        final RccGroupMetadataKeys keysOnly = new RccGroupMetadataKeys(null, fileInfo);
        assertEquals(0, RccGroupMetadataKeys.parse(keysOnly.encode()).continuityToken.length);
    }

    /** An empty body is a legal proto3 encoding; the caller tests isEmpty(), not null. */
    @Test
    public void anEmptyGroupMetadataKeysParsesRatherThanFailing() {
        final RccGroupMetadataKeys empty = RccGroupMetadataKeys.parse(new byte[0]);
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
        assertEquals(0, new RccGroupMetadataKeys(null, null).encode().length);
    }

    /**
     * v4.0 changed both the commitment label ({@code icon_commitment} to {@code group_icon}) and
     * the committed value (key material to {@code hmac_tag}); each revision is correct for itself.
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
        // Cross-applying one revision's commitment to the other's rule fails silently.
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

    /** A commitment with no local key is out of sync: we cannot open it. */
    @Test
    public void aCommitmentWithNoLocalKeyIsOutOfSync() {
        final byte[] c = RccCommitment.iconCommitment(Rcc16Version.V3_0, KEY, TAG);
        assertEquals(MlsMetadataKeysPolicy.Sync.OUT_OF_SYNC,
                MlsMetadataKeysPolicy.detect(c, null, Rcc16Version.V3_0, true));
        assertEquals(MlsMetadataKeysPolicy.Sync.OUT_OF_SYNC,
                MlsMetadataKeysPolicy.detect(c, new byte[0], Rcc16Version.V3_0, true));
    }

    /**
     * The request is the 0xF007 GroupContext extension, which does not exist in v3.0: under v3.0 an
     * out-of-sync client cannot ask and relies on the sender-side rule.
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

    /**
     * v3.0's trigger is structural: an External Commit means someone joined without a Welcome and
     * so without keys. External-commit resync is our recovery path, so this fires on it.
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

    /** Under v4.0 the trigger is the request, not the External Commit. */
    @Test
    public void underV4TheTriggerIsTheRequestExtension() {
        assertTrue(MlsMetadataKeysPolicy.shouldSendKeys(
                /*requested=*/ true, /*externalCommit=*/ false, true, true, Rcc16Version.V4_0));
        assertFalse("an External Commit with nobody asking is not a v4.0 trigger",
                MlsMetadataKeysPolicy.shouldSendKeys(false, true, true, true, Rcc16Version.V4_0));
    }

    /**
     * Never forward key material we cannot verify ourselves: a wrong key would spread to everyone
     * who asks, and a tag mismatch does not say who introduced it.
     */
    @Test
    public void keysAreNeverForwardedWhileWeAreOurselvesOutOfSync() {
        for (final Rcc16Version v : Rcc16Version.values()) {
            assertFalse(
                    MlsMetadataKeysPolicy.shouldSendKeys(true, true, /*inSync=*/ false, true, v));
        }
    }

    @Test
    public void nothingIsSentWhenWeHoldNoKeys() {
        for (final Rcc16Version v : Rcc16Version.values()) {
            assertFalse(
                    MlsMetadataKeysPolicy.shouldSendKeys(true, true, true, /*haveKeys=*/ false, v));
        }
    }

    /**
     * The responder removes the request extension in the same Commit, or every later Commit or
     * Welcome re-triggers the send.
     */
    @Test
    public void respondingAlsoRemovesTheRequestExtension() {
        assertTrue(MlsMetadataKeysPolicy.shouldRemoveRequestExtension(
                /*requested=*/ true, /*sending=*/ true, Rcc16Version.V4_0));
        assertFalse(
                MlsMetadataKeysPolicy.shouldRemoveRequestExtension(true, false, Rcc16Version.V4_0));
        assertFalse(
                MlsMetadataKeysPolicy.shouldRemoveRequestExtension(false, true, Rcc16Version.V4_0));
        assertFalse("v3.0 has no such extension to remove",
                MlsMetadataKeysPolicy.shouldRemoveRequestExtension(true, true, Rcc16Version.V3_0));
    }

    /**
     * A metadata-keys body never reaches the conversation: it is binary protobuf, and after
     * encryption the transport cannot see inside any type.
     */
    @Test
    public void aGroupMetadataKeysBodyIsDroppedAsAKeyNotRendered() {
        assertEquals(RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify(RccGroupMetadataKeys.CONTENT_TYPE));
        assertEquals("a charset parameter must not defeat the match",
                RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify(RccGroupMetadataKeys.CONTENT_TYPE
                        + ";charset=UTF-8"));
        assertTrue(RccContentDisposition.isDrop(
                RccContentDisposition.classify(RccGroupMetadataKeys.CONTENT_TYPE)));
        // Distinct from the RCC.16 §7.8.1 FileInfo leg, which has a different consumer.
        assertEquals(RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify(RccFileInfo.CONTENT_TYPE));
    }

    private static MlsParticipantKeyResync.Leaf leaf(final int i, final String p, final String k) {
        return new MlsParticipantKeyResync.Leaf(i, p, k);
    }

    /**
     * An External Commit may carry only one Remove proposal, so N stale clients need one Remove
     * plus N−1 ServerRemoves (RCC.16 §10.1.1).
     */
    @Test
    public void staleClientsBecomeOneRemovePlusServerRemoves() {
        final List<MlsParticipantKeyResync.Leaf> roster = Arrays.asList(
                leaf(0, "+1", "keyNEW"),
                leaf(1, "+1", "keyOLD"),
                leaf(2, "+2", "keyOLD"),      // different participant, untouched
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
     * A leaf with no recorded signing key is not assumed stale: an absent value means we did not
     * look, and a wrong removal is unrecoverable for the member.
     */
    @Test
    public void aLeafWithNoRecordedKeyIsNotAssumedStale() {
        final MlsParticipantKeyResync.Plan p = MlsParticipantKeyResync.plan(
                Arrays.asList(leaf(0, "+1", ""), leaf(1, "+1", null)), "+1", "keyNEW");
        assertTrue(p.isEmpty());
    }

    /**
     * An absent {@code currentKey} plans nothing. It matches no leaf, so without the guard every
     * client of the participant would be classified stale and the participant removed. It is what
     * {@link MlsParticipantKeyLedger} returns when it cannot tell which key is current.
     */
    @Test
    public void anAbsentCurrentKeyPlansNothingRatherThanRemovingEveryone() {
        final List<MlsParticipantKeyResync.Leaf> roster = Arrays.asList(
                leaf(0, "+1", "keyA"), leaf(1, "+1", "keyB"), leaf(2, "+1", "keyA"));
        assertTrue("empty currentKey must remove nobody",
                MlsParticipantKeyResync.plan(roster, "+1", "").isEmpty());
        assertTrue("null currentKey must remove nobody",
                MlsParticipantKeyResync.plan(roster, "+1", null).isEmpty());
        // Control: with a real currentKey the same roster does plan removals.
        assertEquals(2, MlsParticipantKeyResync.plan(roster, "+1", "keyB").size());
    }

    /**
     * The v4.0 RCC.16 §7.11.9 ServerRemove body: {@code uint32} is four bytes big-endian; a varint
     * would encode a small leaf index in one byte and pass hand-written tests.
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
     * {@code RETAINED_CONTENT_KEYS_PER_SLOT} is small and greater than one: one would drop a key
     * that arrived before the commit making it current; many would undo the forward secrecy of
     * rotating epoch secrets.
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
     * The held-subject bound counts holders and refuses the eighth. The caller has cleared its own
     * slot before asking, so the count excludes it.
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
     * The held-icon bound counts bytes: eight 256 KB icons are 2 MB of heap a holder count would
     * not see. A held subject is a few dozen bytes, so its bound is safe as a count.
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
        // Bytes start refusing at the fourth 256 KB icon, where a holder count of eight would not.
        assertTrue(MlsMetadataKeysPolicy.mayHoldAnotherIcon(3L * 256 * 1024, 256 * 1024));
        assertFalse(MlsMetadataKeysPolicy.mayHoldAnotherIcon(4L * 256 * 1024, 256 * 1024));
        // A zero-length candidate is not a hold, so no empty entry looks like a pending icon.
        assertFalse(MlsMetadataKeysPolicy.mayHoldAnotherIcon(0, 0));
    }


    @Test
    public void slotNameNamesTheThreeMetadataSlotsAndCallsTheRestFile() {
        assertEquals("group-icon", MlsMetadataKeysPolicy.slotName(RccFileInfo.SLOT_ICON));
        assertEquals("group-subject", MlsMetadataKeysPolicy.slotName(RccFileInfo.SLOT_SUBJECT));
        assertEquals("thumbnail", MlsMetadataKeysPolicy.slotName(RccFileInfo.SLOT_THUMBNAIL));
        assertEquals("file", MlsMetadataKeysPolicy.slotName(-1));
    }


    private static MlsSession sessionWithExtTypes(final byte[] types) {
        return (MlsSession) java.lang.reflect.Proxy.newProxyInstance(
                MlsSession.class.getClassLoader(), new Class<?>[] {MlsSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("groupInfoExtTypes")) return types;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    public void metadataKeysRequestPresentReadsTheConfiguredTypeOutOfTheServerGroupInfo() {
        final MlsConfig cfg = MlsConfig.defaults();
        if (!cfg.metadataKeysExtKnown()) {
            // The extension type is unknown by default: the answer is "no", never a guess.
            assertFalse(MlsMetadataKeysPolicy.metadataKeysRequestPresent(cfg,
                    sessionWithExtTypes(new byte[] {0, 0, 0, 0}), new byte[] {1}));
            return;
        }
        final int t = cfg.metadataKeysExtType;
        final byte[] with = {(byte) (t >> 8), (byte) t, 0, 0};
        assertTrue(MlsMetadataKeysPolicy.metadataKeysRequestPresent(cfg, sessionWithExtTypes(with),
                new byte[] {1}));
        assertFalse(MlsMetadataKeysPolicy.metadataKeysRequestPresent(cfg,
                sessionWithExtTypes(new byte[] {0, 1, 0, 0}), new byte[] {1}));
        assertFalse(MlsMetadataKeysPolicy.metadataKeysRequestPresent(cfg, sessionWithExtTypes(with),
                new byte[0]));
    }
}

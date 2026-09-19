/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

/**
 * The debug inbound hold that builds a behind-member fixture: what it holds and how it reports the
 * gap. Frames are hand-built to RFC 9420 §6 so the wire walk sees every length-prefix width and
 * sender shape; decoding a 2-byte prefix as a 1-byte one returns a plausible wrong number.
 */
public class MlsInboundHoldTest {

    // Frame builders (RFC 9420 §6).

    /** MLS {@code opaque x<V>}: a 1, 2 or 4-byte length prefix selected by the top two bits. */
    private static byte[] vec(final byte[] body) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (body.length < 0x40) {
            o.write(body.length);                                   // 00xxxxxx
        } else if (body.length < 0x4000) {
            o.write(0x40 | (body.length >>> 8));                    // 01xxxxxx xxxxxxxx
            o.write(body.length & 0xFF);
        } else {
            o.write(0x80 | (body.length >>> 24));                   // 10xxxxxx x3
            o.write((body.length >>> 16) & 0xFF);
            o.write((body.length >>> 8) & 0xFF);
            o.write(body.length & 0xFF);
        }
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    private static void u64(final ByteArrayOutputStream o, final long v) {
        for (int i = 7; i >= 0; i--) o.write((int) ((v >>> (i * 8)) & 0xFF));
    }

    /** A PublicMessage whose FramedContent carries {@code contentType} at {@code epoch}. */
    private static byte[] publicMessage(final byte[] groupId, final long epoch,
            final int senderType, final int contentType, final byte[] authData) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x00); o.write(0x01);                               // version = mls10
        o.write(0x00); o.write(MlsWireScan.WF_PUBLIC_MESSAGE);
        final byte[] gid = vec(groupId);
        o.write(gid, 0, gid.length);
        u64(o, epoch);
        o.write(senderType);
        if (senderType == MlsWireScan.SENDER_MEMBER || senderType == MlsWireScan.SENDER_EXTERNAL) {
            o.write(0); o.write(0); o.write(0); o.write(2);         // uint32 leaf_index
        }
        final byte[] ad = vec(authData);
        o.write(ad, 0, ad.length);
        o.write(contentType);
        o.write(0xAA); o.write(0xBB);                               // the content itself, unread
        return o.toByteArray();
    }

    /** A PrivateMessage: {@code group_id<V>, epoch, content_type, …}, content type in the clear. */
    private static byte[] privateMessage(final byte[] groupId, final long epoch,
            final int contentType) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x00); o.write(0x01);
        o.write(0x00); o.write(MlsWireScan.WF_PRIVATE_MESSAGE);
        final byte[] gid = vec(groupId);
        o.write(gid, 0, gid.length);
        u64(o, epoch);
        o.write(contentType);
        o.write(0xCC); o.write(0xDD);
        return o.toByteArray();
    }

    private static byte[] welcome() {
        return new byte[] {0x00, 0x01, 0x00, (byte) MlsWireScan.WF_WELCOME, 0x11, 0x22, 0x33};
    }

    private static byte[] gid(final int n) {
        final byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i + 1);
        return b;
    }

    private static byte[] commitAt(final long epoch) {
        return publicMessage(gid(16), epoch, MlsWireScan.SENDER_MEMBER,
                MlsWireScan.CONTENT_COMMIT, new byte[] {1, 2, 3});
    }

    @Test public void aCommitIsReadableAsACommitWithoutDecryptingIt() {
        final byte[] c = commitAt(7);
        assertEquals(MlsWireScan.CONTENT_COMMIT, MlsWireScan.contentTypeOf(c));
        assertEquals(7L, MlsWireScan.epochOf(c));
        assertEquals(MlsInboundHold.Kind.COMMIT, MlsInboundHold.classify(c));
    }

    @Test public void aProposalIsNotACommit() {
        // Holding a commit while passing the proposal it references would fail the commit on
        // release, so HANDSHAKE mode holds both.
        final byte[] p = publicMessage(gid(16), 3, MlsWireScan.SENDER_MEMBER,
                MlsWireScan.CONTENT_PROPOSAL, new byte[0]);
        assertEquals(MlsWireScan.CONTENT_PROPOSAL, MlsWireScan.contentTypeOf(p));
        assertEquals(MlsInboundHold.Kind.PROPOSAL, MlsInboundHold.classify(p));
    }

    @Test public void theWalkSurvivesEveryShapeOfTheOptionalSenderIndex() {
        // member and external senders carry a uint32 after the sender type; the new_member_* forms
        // carry nothing. Skipping wrongly reads authenticated_data's length prefix as the content
        // type.
        for (final int st : new int[] {MlsWireScan.SENDER_MEMBER, MlsWireScan.SENDER_EXTERNAL,
                MlsWireScan.SENDER_NEW_MEMBER_PROPOSAL, MlsWireScan.SENDER_NEW_MEMBER_COMMIT}) {
            final byte[] c = publicMessage(gid(16), 4, st, MlsWireScan.CONTENT_COMMIT,
                    new byte[] {9, 9});
            assertEquals(MlsWireScan.senderTypeName(st),
                    MlsWireScan.CONTENT_COMMIT, MlsWireScan.contentTypeOf(c));
        }
    }

    @Test public void theWalkSurvivesEveryLengthPrefixWidth() {
        // 63 bytes takes the 1-byte prefix and 64 the 2-byte one; the same for authenticated_data.
        for (final int gidLen : new int[] {1, 63, 64, 300}) {
            for (final int adLen : new int[] {0, 63, 64, 500}) {
                final byte[] c = publicMessage(gid(gidLen), 11, MlsWireScan.SENDER_MEMBER,
                        MlsWireScan.CONTENT_COMMIT, new byte[adLen]);
                final String what = "gid=" + gidLen + "B ad=" + adLen + "B";
                assertEquals(what, MlsWireScan.CONTENT_COMMIT, MlsWireScan.contentTypeOf(c));
                assertEquals(what, 11L, MlsWireScan.epochOf(c));
            }
        }
    }

    @Test public void aPrivateMessageCarriesItsContentTypeInTheClearButNoSender() {
        // RFC 9420 §6.3: the content type is in the clear so the receiver can pick the key
        // schedule; the sender is not, so senderTypeOf refuses this shape.
        final byte[] m = privateMessage(gid(16), 5, MlsWireScan.CONTENT_APPLICATION);
        assertEquals(MlsWireScan.CONTENT_APPLICATION, MlsWireScan.contentTypeOf(m));
        assertEquals(5L, MlsWireScan.epochOf(m));
        assertEquals("a PrivateMessage has no Sender field at all",
                -1, MlsWireScan.senderTypeOf(m));
    }

    @Test public void anUndefinedContentTypeIsUnreadableRatherThanReported() {
        // A walk that lands on an undefined value has landed in the wrong place.
        final byte[] m = privateMessage(gid(8), 1, 47);
        assertEquals(-1, MlsWireScan.contentTypeOf(m));
    }

    @Test public void aNonMlsBlobIsUnreadableAndTruncationDoesNotThrow() {
        assertEquals(MlsInboundHold.Kind.UNREADABLE, MlsInboundHold.classify(null));
        assertEquals(MlsInboundHold.Kind.UNREADABLE, MlsInboundHold.classify(new byte[0]));
        assertEquals(MlsInboundHold.Kind.UNREADABLE,
                MlsInboundHold.classify(new byte[] {0x7B, 0x22, 0x61, 0x22}));   // {"a"
        final byte[] full = commitAt(2);
        for (int cut = 1; cut < full.length; cut++) {
            final byte[] part = java.util.Arrays.copyOf(full, cut);
            MlsInboundHold.classify(part);           // must not throw at any truncation point
            MlsWireScan.contentTypeOf(part);
            MlsWireScan.epochOf(part);
        }
    }

    @Test public void aWelcomeIsPassedUnlessTheModeIsControl() {
        // A Welcome (an era advance or a re-add) is a behind member's route back, since the server
        // does not backfill commits, so only CONTROL mode holds it.
        final byte[] w = welcome();
        assertEquals(MlsInboundHold.Kind.WELCOME, MlsInboundHold.classify(w));
        for (final MlsInboundHold.Mode m : new MlsInboundHold.Mode[] {
                MlsInboundHold.Mode.COMMIT, MlsInboundHold.Mode.HANDSHAKE}) {
            assertEquals(m.name(), MlsInboundHold.Verdict.PASS, MlsInboundHold.decide(
                    true, true, m, MlsInboundHold.Kind.WELCOME, false));
        }
        assertEquals(MlsInboundHold.Verdict.HOLD, MlsInboundHold.decide(
                true, true, MlsInboundHold.Mode.CONTROL, MlsInboundHold.Kind.WELCOME, false));
    }

    @Test public void everyModeHoldsCommitsAndOnlyHandshakeOrControlHoldsProposals() {
        for (final MlsInboundHold.Mode m : MlsInboundHold.Mode.values()) {
            assertEquals(m + " must hold commits — commits ARE the gap",
                    MlsInboundHold.Verdict.HOLD,
                    MlsInboundHold.decide(true, true, m, MlsInboundHold.Kind.COMMIT, false));
        }
        assertEquals(MlsInboundHold.Verdict.PASS, MlsInboundHold.decide(
                true, true, MlsInboundHold.Mode.COMMIT, MlsInboundHold.Kind.PROPOSAL, false));
        assertEquals(MlsInboundHold.Verdict.HOLD, MlsInboundHold.decide(
                true, true, MlsInboundHold.Mode.HANDSHAKE, MlsInboundHold.Kind.PROPOSAL, false));
        assertEquals(MlsInboundHold.Verdict.HOLD, MlsInboundHold.decide(
                true, true, MlsInboundHold.Mode.CONTROL, MlsInboundHold.Kind.PROPOSAL, false));
    }

    @Test public void nothingIsHeldWhenDisarmedOrOutOfScope() {
        for (final MlsInboundHold.Kind k : MlsInboundHold.Kind.values()) {
            assertEquals("disarmed", MlsInboundHold.Verdict.PASS, MlsInboundHold.decide(
                    false, true, MlsInboundHold.Mode.CONTROL, k, false));
            assertEquals("another conversation", MlsInboundHold.Verdict.PASS,
                    MlsInboundHold.decide(true, false, MlsInboundHold.Mode.CONTROL, k, false));
        }
    }

    @Test public void whatCannotBeClassifiedIsAlwaysPassed() {
        // A fixture that swallows what it cannot identify changes the experiment.
        for (final MlsInboundHold.Mode m : MlsInboundHold.Mode.values()) {
            assertEquals(MlsInboundHold.Verdict.PASS, MlsInboundHold.decide(
                    true, true, m, MlsInboundHold.Kind.UNREADABLE, false));
            assertEquals(MlsInboundHold.Verdict.PASS, MlsInboundHold.decide(
                    true, true, m, MlsInboundHold.Kind.OTHER, false));
        }
    }

    @Test public void atCapacityWePassRatherThanDrop() {
        // Passing a far-future commit loses nothing: the RCC.16 §10.8 pending queue parks it at its
        // own moment. Dropping it would be an unrecoverable loss.
        assertEquals(MlsInboundHold.Verdict.PASS, MlsInboundHold.decide(
                true, true, MlsInboundHold.Mode.HANDSHAKE, MlsInboundHold.Kind.COMMIT, true));
    }

    @Test public void theGapIsDerivedFromTheHeldCommitsOwnEpochs() {
        // A commit stamped N is made at epoch N and produces N+1: holding 7, 8, 9 at epoch 7 means
        // the group is at 10 and we are three behind, all read off the wire.
        final MlsInboundHold.Gap g = MlsInboundHold.measure(7, new long[] {7, 8, 9});
        assertEquals(7L, g.ourEpoch);
        assertEquals(3, g.heldCommits);
        assertEquals(10L, g.groupEpoch);
        assertEquals(3L, g.epochGap);
        assertTrue(g.contiguous);
    }

    @Test public void aHoleMeansTheLeverDidNotMakeAllOfTheGap() {
        // A missing 8 means a commit went astray by another route, so the gap is not a clean
        // fixture.
        final MlsInboundHold.Gap g = MlsInboundHold.measure(7, new long[] {7, 9});
        assertEquals(2, g.heldCommits);
        assertEquals(10L, g.groupEpoch);
        assertEquals(3L, g.epochGap);
        assertFalse("7 and 9 do not form 7..9", g.contiguous);
    }

    @Test public void aRedeliveredCommitDoesNotInflateTheGap() {
        // The same epoch held twice is not contiguous; the store also dedupes by digest before
        // this.
        assertFalse(MlsInboundHold.measure(7, new long[] {7, 8, 8}).contiguous);
    }

    @Test public void nothingHeldIsAGapOfNothingRatherThanAGapOfZero() {
        // -1 is "unknown" and must not read as "in sync".
        final MlsInboundHold.Gap g = MlsInboundHold.measure(7, new long[0]);
        assertEquals(0, g.heldCommits);
        assertEquals(-1L, g.groupEpoch);
        assertEquals(-1L, g.epochGap);
        assertFalse(g.contiguous);
    }

    @Test public void anUnreadableEpochIsCountedButNotUsedInTheArithmetic() {
        final MlsInboundHold.Gap g = MlsInboundHold.measure(4, new long[] {4, -1, 5});
        assertEquals("all three are held", 3, g.heldCommits);
        assertEquals(6L, g.groupEpoch);
        assertFalse("one epoch could not be read, so this is not a proven clean gap", g.contiguous);
    }

    @Test public void anUnknownLocalEpochYieldsAnUnknownGapNotAWrongOne() {
        final MlsInboundHold.Gap g = MlsInboundHold.measure(-1, new long[] {7, 8});
        assertEquals(9L, g.groupEpoch);
        assertEquals(-1L, g.epochGap);
        assertFalse(g.contiguous);
    }

    @Test public void theModeParsesAndAnUnknownValueFallsBackRatherThanFailing() {
        assertEquals(MlsInboundHold.Mode.COMMIT,
                MlsInboundHold.modeOf("commit", MlsInboundHold.Mode.HANDSHAKE));
        assertEquals(MlsInboundHold.Mode.CONTROL,
                MlsInboundHold.modeOf(" CONTROL ", MlsInboundHold.Mode.HANDSHAKE));
        assertEquals(MlsInboundHold.Mode.HANDSHAKE,
                MlsInboundHold.modeOf(null, MlsInboundHold.Mode.HANDSHAKE));
        assertEquals(MlsInboundHold.Mode.HANDSHAKE,
                MlsInboundHold.modeOf("nonsense", MlsInboundHold.Mode.HANDSHAKE));
    }


    @Test
    public void inboundNowParkedSaysNothingForANullRecord() {
        assertEquals("", MlsInboundHold.inboundNowParked(null));
    }
}

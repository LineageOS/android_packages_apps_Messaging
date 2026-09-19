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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

/**
 * The BEHIND fixture. These pin the two things about the lever that can be
 * silently wrong: <b>what it holds</b> and <b>what it says the gap is</b>.
 *
 * <p>Both matter more than usual because a fixture has no error channel. A hold that also swallows
 * Welcomes takes away the route a behind member has back into the group — the one that matters, since
 * the server does not backfill commits — and a status line that reports a clean N-epoch gap when the
 * held commits do not actually form one
 * makes every downstream measurement a guess. Neither shows up as a failure on a device — it shows
 * up as a recovery run that behaves oddly and gets attributed to the recovery code.
 *
 * <p>The MLS frames here are hand-built to RFC 9420 §6 rather than captured, because the property
 * being pinned is the WALK — group_id's variable-length prefix, the Sender's optional {@code uint32},
 * {@code authenticated_data} — and a captured frame exercises exactly one shape of each. The
 * one-byte and two-byte length prefixes in particular differ only in the top two bits of a byte, and
 * decoding the second as the first reads the content type out of the middle of the group id and
 * returns a plausible number rather than an error.
 */
public class MlsInboundHoldTest {

    // ---- frame builders (RFC 9420 §6) ----------------------------------------------------------

    /** MLS {@code opaque x<V>}: 1/2/4-byte length prefix selected by the top two bits. */
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
        o.write(0xAA); o.write(0xBB);                               // the content itself; unread
        return o.toByteArray();
    }

    /** A PrivateMessage: {@code group_id<V>, epoch, content_type, …} — content type in the clear. */
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

    // ---- the wire reads ------------------------------------------------------------------------

    @Test public void aCommitIsReadableAsACommitWithoutDecryptingIt() {
        final byte[] c = commitAt(7);
        assertEquals(MlsWireScan.CONTENT_COMMIT, MlsWireScan.contentTypeOf(c));
        assertEquals(7L, MlsWireScan.epochOf(c));
        assertEquals(MlsInboundHold.Kind.COMMIT, MlsInboundHold.classify(c));
    }

    @Test public void aProposalIsNotACommit() {
        // The distinction the fixture is built on: hold the commit and pass the proposal it
        // references by hash, and the commit fails validation on release against a proposal store
        // filled out of order. Kind.PROPOSAL existing is what lets Mode.HANDSHAKE freeze both.
        final byte[] p = publicMessage(gid(16), 3, MlsWireScan.SENDER_MEMBER,
                MlsWireScan.CONTENT_PROPOSAL, new byte[0]);
        assertEquals(MlsWireScan.CONTENT_PROPOSAL, MlsWireScan.contentTypeOf(p));
        assertEquals(MlsInboundHold.Kind.PROPOSAL, MlsInboundHold.classify(p));
    }

    @Test public void theWalkSurvivesEveryShapeOfTheOptionalSenderIndex() {
        // member/external carry a uint32 after the sender type; the two new_member_* forms carry
        // nothing at all. Skipping four bytes that are not there — or failing to skip four that are
        // — lands on authenticated_data's length prefix and reads THAT as the content type.
        for (final int st : new int[] {MlsWireScan.SENDER_MEMBER, MlsWireScan.SENDER_EXTERNAL,
                MlsWireScan.SENDER_NEW_MEMBER_PROPOSAL, MlsWireScan.SENDER_NEW_MEMBER_COMMIT}) {
            final byte[] c = publicMessage(gid(16), 4, st, MlsWireScan.CONTENT_COMMIT,
                    new byte[] {9, 9});
            assertEquals(MlsWireScan.senderTypeName(st),
                    MlsWireScan.CONTENT_COMMIT, MlsWireScan.contentTypeOf(c));
        }
    }

    @Test public void theWalkSurvivesEveryLengthPrefixWidth() {
        // A group id of 63 bytes uses the 1-byte prefix and one of 64 uses the 2-byte prefix, and
        // they differ only in the top two bits of one byte. Same for authenticated_data.
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
        // RFC 9420 §6.3 — {group_id, epoch, content_type, …}: the receiver has to know which key
        // schedule to use before it can decrypt, so the content type is not hidden. The SENDER is,
        // which is why senderTypeOf must refuse this shape rather than return the content type.
        final byte[] m = privateMessage(gid(16), 5, MlsWireScan.CONTENT_APPLICATION);
        assertEquals(MlsWireScan.CONTENT_APPLICATION, MlsWireScan.contentTypeOf(m));
        assertEquals(5L, MlsWireScan.epochOf(m));
        assertEquals("a PrivateMessage has no Sender field at all",
                -1, MlsWireScan.senderTypeOf(m));
    }

    @Test public void anUndefinedContentTypeIsUnreadableRatherThanReported() {
        // "we could not read a content type" is true; "the content type is 47" is not. A walk that
        // lands on an undefined value has almost certainly landed in the wrong place.
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

    // ---- the decision --------------------------------------------------------------------------

    @Test public void aWelcomeIsPassedUnlessTheModeIsControl() {
        // THE LOAD-BEARING EXCLUSION. A Welcome is the era advance or the re-add — the route back
        // for a behind member — and with no commit backfill it is the route that matters. A fixture
        // that held Welcomes by default would be built out of the thing that repairs it.
        //
        // The comment here used to add "and no external_pub on our groups".
        // RETRACTED 2026-09-08 — that came from a reader that answered ABSENT without reading
        // anything. The assertion below is unchanged, because it never depended on it.
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
        // A fixture that swallows what it cannot identify silently changes the experiment.
        for (final MlsInboundHold.Mode m : MlsInboundHold.Mode.values()) {
            assertEquals(MlsInboundHold.Verdict.PASS, MlsInboundHold.decide(
                    true, true, m, MlsInboundHold.Kind.UNREADABLE, false));
            assertEquals(MlsInboundHold.Verdict.PASS, MlsInboundHold.decide(
                    true, true, m, MlsInboundHold.Kind.OTHER, false));
        }
    }

    @Test public void atCapacityWePassRatherThanDrop() {
        // Passing a far-future commit does not close the gap — §10.8's pending queue parks it at its
        // own moment, un-capped by design — so the fixture survives and nothing is lost. Dropping
        // would be a silent unrecoverable loss committed by the instrument itself.
        assertEquals(MlsInboundHold.Verdict.PASS, MlsInboundHold.decide(
                true, true, MlsInboundHold.Mode.HANDSHAKE, MlsInboundHold.Kind.COMMIT, true));
    }

    // ---- the measured gap ----------------------------------------------------------------------

    @Test public void theGapIsDerivedFromTheHeldCommitsOwnEpochs() {
        // A commit stamped N is made AT epoch N and produces N+1. So holding the commits stamped
        // 7, 8, 9 while we sit at 7 means the group is at 10 and we are three behind — and every
        // number in that sentence was read off the wire, with no server look-up.
        final MlsInboundHold.Gap g = MlsInboundHold.measure(7, new long[] {7, 8, 9});
        assertEquals(7L, g.ourEpoch);
        assertEquals(3, g.heldCommits);
        assertEquals(10L, g.groupEpoch);
        assertEquals(3L, g.epochGap);
        assertTrue(g.contiguous);
    }

    @Test public void aHoleMeansTheLeverDidNotMakeAllOfTheGap() {
        // The assertion that keeps the instrument honest. A missing 8 means a commit went astray by
        // some route OTHER than the hold, so replaying what we hold cannot close the gap — and the
        // status line must not claim a clean 3-epoch fixture.
        final MlsInboundHold.Gap g = MlsInboundHold.measure(7, new long[] {7, 9});
        assertEquals(2, g.heldCommits);
        assertEquals(10L, g.groupEpoch);
        assertEquals(3L, g.epochGap);
        assertFalse("7 and 9 do not form 7..9", g.contiguous);
    }

    @Test public void aRedeliveredCommitDoesNotInflateTheGap() {
        // Held twice, the same epoch twice: the span no longer matches the count, so contiguous is
        // false and the reader is told rather than shown a gap of 3 that is really 2. (The store
        // dedupes by digest before it gets here; this is the arithmetic's own backstop.)
        assertFalse(MlsInboundHold.measure(7, new long[] {7, 8, 8}).contiguous);
    }

    @Test public void nothingHeldIsAGapOfNothingRatherThanAGapOfZero() {
        // -1 is "unknown", and it must not read as "in sync". An armed lever that has held nothing
        // has not yet made a fixture, and a status line saying epochGap=0 would say it had.
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
}

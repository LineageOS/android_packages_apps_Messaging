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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** The persisted per-conversation record — rework items 2.1, 2.2, 2.3, 2.4. */
public class MlsConversationRecordTest {

    private static final String ME = "+15715550104";
    private static final byte[] GID = new byte[] {0x0a, (byte) 0xbb, 0x0c};

    private static MlsConversationRecord.Builder full() {
        return MlsConversationRecord.builder()
                .identity(ME).groupId(GID).rcsGroupId("rcs-1").peerE164("+15715550103")
                .healthStatus(MlsHealthStates.ONGOINGERAADVANCEMENT)
                .selfHealKind(MlsSelfHealKind.ERA_ADVANCEMENT_FOR_PHOENIX_MODE)
                .pendingOperation(MlsPendingOperation.start(
                        MlsPendingOperation.Kind.ERA_ADVANCEMENT, 1_700_000_000_000L,
                        new Moment(3, 7L), "+15715550103"))
                .moment(new Moment(3, 7L))
                .lastHealthyMoment(new Moment(2, 91L))
                .selfHealBudget(new MlsConversationRecord.SelfHealBudget(2, 1_699_000_000_000L, true))
                .storedStatusRequest(new MlsConversationRecord.StatusRequest(
                        MlsHealthStates.ERAADVANCEMENTREQUESTED, 4))
                .expectedCommitKind(MlsConversationRecord.ExpectedCommitKind.SELF_KEY_UPDATE)
                // Era 3, matching the fixture's moment — the map is ONE era's chain.
                .putEpochAuthenticator(3, 7L, new byte[] {1, 2, 3})
                .putEpochAuthenticator(3, 6L, new byte[] {4, 5})
                .putMembership(3, 7L, new String[] {ME, "+15715550103"})
                .putMemberValidity(0, new MlsConversationRecord.MemberValidity(1000L, 99_000L))
                .putParticipantKeyUpdate("+15715550103", 1_698_000_000_000L)
                .sendsThisEpoch(42)
                // DELIBERATELY DIFFERENT FROM sendsThisEpoch. They are two independent counters
                // and equal fixture values would hide a field mix-up in either
                // direction — encode writing one twice, or decode assigning the wrong one.
                .sendsSinceLeafRotation(137)
                .selfLeftAtMs(1_701_234_567_000L)
                // §4.8 row 13. 32 bytes, because that is the only length §8.3.1.1 permits, and a
                // fixture of the wrong length would let a framing bug through unseen.
                .continuityToken(TOKEN);
    }

    /** A 32-byte stand-in for §8.3.1.1's "CSPRNG, 256 bits". */
    private static final byte[] TOKEN = token((byte) 0x5A);

    private static byte[] token(final byte seed) {
        final byte[] t = new byte[32];
        for (int i = 0; i < t.length; i++) t[i] = (byte) (i ^ seed);
        return t;
    }

    // ---- the frame -------------------------------------------------------------------------------
    //
    // encode() emits [version][varint payload length][payload]. These helpers take a record apart
    // and put it back together, so the tests can forge the blobs a real older or newer build would
    // actually write instead of hand-assembling bytes no build produces.

    private static final int VERSION_FRAMED = 2;
    private static final int VERSION_UNFRAMED = 1;

    /** The bare (field, wireType, value) stream inside a framed record. */
    private static byte[] payloadOf(final byte[] framed) {
        int p = 1;
        while ((framed[p] & 0x80) != 0) p++;          // the length varint...
        p++;                                          // ...and its last byte
        return Arrays.copyOfRange(framed, p, framed.length);
    }

    private static byte[] framed(final byte[] payload) {
        return framed(payload, payload.length);
    }

    /**
     * A framed record declaring {@code declared} payload bytes — which is the real length unless a
     * test is deliberately lying about it.
     */
    private static byte[] framed(final byte[] payload, final int declared) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream(payload.length + 8);
        o.write(VERSION_FRAMED);
        long v = declared;
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
        o.write(payload, 0, payload.length);
        return o.toByteArray();
    }

    /** The blob a pre-frame build wrote: the same payload, version byte 1, no length. */
    private static byte[] unframedV1(final byte[] payload) {
        final byte[] b = new byte[payload.length + 1];
        b[0] = VERSION_UNFRAMED;
        System.arraycopy(payload, 0, b, 1, payload.length);
        return b;
    }

    // ---- 2.1: written whole, round-trips whole -------------------------------------------------

    @Test
    public void everyFieldSurvivesARoundTrip() {
        final MlsConversationRecord in = full().build();
        final MlsConversationRecord out = MlsConversationRecord.decode(in.encode());
        assertNotNull(out);

        assertEquals(in.identity, out.identity);
        assertArrayEquals(in.groupId, out.groupId);
        assertEquals(in.rcsGroupId, out.rcsGroupId);
        assertEquals(in.peerE164, out.peerE164);
        assertEquals(in.healthStatus, out.healthStatus);
        assertEquals(in.selfHealKind, out.selfHealKind);
        assertEquals(in.moment, out.moment);
        assertEquals(in.lastHealthyMoment, out.lastHealthyMoment);
        assertEquals(in.sendsThisEpoch, out.sendsThisEpoch);
        assertEquals(in.sendsSinceLeafRotation, out.sendsSinceLeafRotation);
        assertNotEquals("the fixture must keep the two counters distinct, or this test cannot see "
                + "them being confused for each other",
                out.sendsThisEpoch, out.sendsSinceLeafRotation);
        assertEquals(in.expectedCommitKind, out.expectedCommitKind);
        assertEquals(in.selfLeftAtMs, out.selfLeftAtMs);
        assertTrue("a record with a self-leave timestamp must report selfLeft()", out.selfLeft());

        assertEquals(in.pendingOperation.kind, out.pendingOperation.kind);
        assertEquals(in.pendingOperation.attemptCount, out.pendingOperation.attemptCount);
        assertEquals(in.pendingOperation.startedAtMs, out.pendingOperation.startedAtMs);
        assertEquals(in.pendingOperation.momentBinding, out.pendingOperation.momentBinding);
        assertEquals(in.pendingOperation.context, out.pendingOperation.context);

        assertEquals(in.selfHealBudget.retryCount, out.selfHealBudget.retryCount);
        assertEquals(in.selfHealBudget.firstAttemptAtMs, out.selfHealBudget.firstAttemptAtMs);
        assertTrue(out.selfHealBudget.healedSinceClear);

        assertEquals(in.storedStatusRequest.requestedStatus, out.storedStatusRequest.requestedStatus);
        assertEquals(in.storedStatusRequest.cause, out.storedStatusRequest.cause);

        assertArrayEquals(new byte[] {1, 2, 3}, out.epochAuthenticators.get(7L));
        assertArrayEquals(new byte[] {4, 5}, out.epochAuthenticators.get(6L));
        assertArrayEquals(new String[] {ME, "+15715550103"},
                out.membershipHistory.get(3).get(7L));
        assertEquals(99_000L, out.memberValidity.get(0).notAfterSecs);
        assertEquals(Long.valueOf(1_698_000_000_000L),
                out.lastParticipantKeyUpdate.get("+15715550103"));
        assertArrayEquals("§4.8 row 13 must survive the round trip byte for byte — a token that "
                + "changes across a restart is worse than no token, because §10.5.1 compares it",
                TOKEN, out.continuityToken);
        assertTrue(out.hasContinuityToken());
    }

    /**
     * §4.8 row 13 — RCC.16 §7.11.12.1's continuity token.
     *
     * <p>The absence path is the one that has to be exactly right, because it is what every
     * conversation on the fleet reads today: a record that has never seen a token must decode to
     * <b>empty, not null</b>, and must not grow a field. That last part is what lets this ship
     * without a version bump — a record with no token is byte-identical to what the previous build
     * wrote, so a downgrade reads it unchanged.
     */
    @Test
    public void theContinuityTokenIsAbsentUntilOneArrivesAndCostsNothingWhenAbsent() {
        final MlsConversationRecord none = MlsConversationRecord.initial(ME, GID, "rcs-1", "+1555");
        assertNotNull("absent must be an empty array, never null — a null would NPE every reader",
                none.continuityToken);
        assertEquals(0, none.continuityToken.length);
        assertFalse(none.hasContinuityToken());

        final MlsConversationRecord out = MlsConversationRecord.decode(none.encode());
        assertNotNull(out);
        assertEquals(0, out.continuityToken.length);
        assertFalse(out.hasContinuityToken());

        // NO BYTES SPENT when there is no token. Compare against a record that differs ONLY by the
        // token, so the delta is attributable.
        final byte[] without = none.encode();
        final byte[] with = none.toBuilder().continuityToken(TOKEN).build().encode();
        assertTrue("a record carrying a token must be longer than one that does not, or the field "
                + "is not being written at all", with.length > without.length);

        // null and empty are the same statement, and neither may be stored as a present-but-empty
        // field: §8.3.1.3's "we hold no token" arm keys on exactly this.
        assertEquals(0, none.toBuilder().continuityToken(null).build().continuityToken.length);
        assertEquals(0, none.toBuilder().continuityToken(new byte[0]).build().continuityToken.length);
        assertArrayEquals(without, none.toBuilder().continuityToken(new byte[0]).build().encode());
    }

    /**
     * The record is IMMUTABLE, and for a secret that matters more than for a counter: a caller
     * holding the array it passed in must not be able to reach into the stored record and change
     * the token afterwards, nor mutate it by reading it back.
     */
    @Test
    public void theContinuityTokenCannotBeMutatedThroughTheCallersArray() {
        final byte[] mine = token((byte) 0x11);
        final MlsConversationRecord rec =
                MlsConversationRecord.initial(ME, GID, "rcs-1", "+1555")
                        .toBuilder().continuityToken(mine).build();
        mine[0] ^= (byte) 0xFF;
        assertNotEquals("the builder must copy — otherwise the caller still owns our token",
                mine[0], rec.continuityToken[0]);
    }

    /**
     * A record written by a build that predates the field still loads, and loads with NO token —
     * which is the truthful answer. Seeding a fake one would put us in §8.3.1.2's "HAVE token" arm
     * holding a value no peer could ever match, i.e. manufacture the mismatch the whole mechanism
     * exists to detect.
     */
    @Test
    public void aPreMigrationRecordHoldsNoContinuityToken() {
        // The blob a build predating the field would have written for this fixture, and it is that
        // blob EXACTLY rather than an approximation of one: encode() emits row 13 only when a token
        // is held, so a record with none is byte-for-byte what the older encoder produced.
        final MlsConversationRecord withToken = full().build();
        final byte[] older = full().continuityToken(null).build().encode();
        assertFalse("the fixture must actually differ, or this proves nothing",
                Arrays.equals(older, withToken.encode()));

        final MlsConversationRecord out = MlsConversationRecord.decode(older);
        assertNotNull("a record with no row 13 must still load", out);
        assertEquals("an absent token must decode as absent — seeding a fake one would put us in "
                + "§8.3.1.2's HAVE-token arm holding a value no peer could match",
                0, out.continuityToken.length);
        assertFalse(out.hasContinuityToken());
        // ...and nothing else was lost on the way.
        assertEquals(withToken.healthStatus, out.healthStatus);
        assertEquals(withToken.selfLeftAtMs, out.selfLeftAtMs);
        assertEquals(withToken.sendsSinceLeafRotation, out.sendsSinceLeafRotation);

        // The other direction: a record carrying row 13 loads on a build that does not know the
        // field, because decode() skips unknown length-delimited fields. Forge that by decoding the
        // WITH-token blob and checking every other field arrived — the skip path is shared, so a
        // field number collision would show up here as a corrupted neighbour rather than silence.
        final MlsConversationRecord newer = MlsConversationRecord.decode(withToken.encode());
        assertNotNull(newer);
        assertEquals(withToken.healthStatus, newer.healthStatus);
        assertEquals(withToken.selfLeftAtMs, newer.selfLeftAtMs);
    }

    /** The dump must name the token's LENGTH and never its value — it is a 256-bit group secret. */
    @Test
    public void theDumpNeverPrintsTheContinuityToken() {
        final String d = full().build().dump();
        assertTrue("the dump must say whether we hold one: " + d, d.contains("continuityToken=32B"));
        final StringBuilder hex = new StringBuilder();
        for (final byte b : TOKEN) hex.append(String.format("%02x", b));
        assertFalse("the dump must never carry the token's bytes: " + d,
                d.contains(hex.toString()));
        assertFalse(d.contains(android_util_base64_free(TOKEN)));
    }

    /** Base64 without pulling android.util in — the dump must not contain this either. */
    private static String android_util_base64_free(final byte[] b) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i += 3) {
            final int n = ((b[i] & 0xFF) << 16)
                    | ((i + 1 < b.length ? b[i + 1] & 0xFF : 0) << 8)
                    | (i + 2 < b.length ? b[i + 2] & 0xFF : 0);
            sb.append(A.charAt((n >> 18) & 63)).append(A.charAt((n >> 12) & 63));
            if (i + 1 < b.length) sb.append(A.charAt((n >> 6) & 63));
            if (i + 2 < b.length) sb.append(A.charAt(n & 63));
        }
        return sb.toString();
    }

    @Test
    public void anEmptyRecordRoundTrips() {
        final MlsConversationRecord in = MlsConversationRecord.initial(ME, GID, "rcs-1", "+1555");
        final MlsConversationRecord out = MlsConversationRecord.decode(in.encode());
        assertNotNull(out);
        assertEquals(MlsHealthStates.UNKNOWN, out.healthStatus);
        assertEquals(MlsSelfHealKind.NONE, out.selfHealKind);
        assertNull("nothing in flight must decode as null, not as an empty operation",
                out.pendingOperation);
        assertNull(out.storedStatusRequest);
        assertTrue(out.epochAuthenticators.isEmpty());
    }

    /**
     * A TRUNCATED record must decode to null, never to a partial one. A half-read record reads as
     * "Unknown, nothing pending", which is a lie that sends a healthy conversation back through the
     * entire recovery ladder.
     *
     * <p>EVERY prefix, stepping one byte at a time, and the assertion is now flat {@code null} —
     * not "null or at least not plausible-but-wrong". The weaker form was all
     * the old unframed format could support: a cut landing on a field boundary left a well-formed
     * shorter record that nothing could tell from one written that short on purpose, so the test
     * could only check that the surviving lie was not a specific self-contradictory one. It caught
     * the bug by accident — at cut=58, once a new field shifted the offsets its stride of 3
     * sampled. With the length frame, a short buffer is short, and there is no prefix of a record
     * that is itself a record.
     */
    @Test
    public void aTruncatedRecordIsNullNotPartial() {
        final byte[] whole = full().build().encode();
        for (int cut = 0; cut < whole.length; cut++) {
            assertNull("truncation at " + cut + " of " + whole.length + " must be refused outright, "
                            + "not decoded into a record missing everything after the cut",
                    MlsConversationRecord.decode(Arrays.copyOf(whole, cut)));
        }
        assertNotNull("...and the record itself must still load",
                MlsConversationRecord.decode(whole));
    }

    /**
     * The other half of what a length buys, and the reason it beat a trailing end-of-record marker:
     * a record that GREW is refused too.
     *
     * <p>Trailing bytes are not a newer build's field — that build's own length would cover them.
     * They are a concatenation, a padded rewrite, or the tail of a longer record partially
     * overwritten by a shorter one. A sentinel proves only that the last byte written arrived, so
     * every one of those would still parse, with the junk skipped as unknown fields.
     */
    @Test
    public void anExtendedRecordIsRefused() {
        final byte[] whole = full().build().encode();
        for (int extra = 1; extra <= 8; extra++) {
            assertNull(extra + " trailing byte(s) must be refused",
                    MlsConversationRecord.decode(Arrays.copyOf(whole, whole.length + extra)));
        }
    }

    /** A frame whose declared length disagrees with the bytes present is refused, either way. */
    @Test
    public void aFrameThatLiesAboutItsLengthIsRefused() {
        final byte[] payload = payloadOf(full().build().encode());
        assertNotNull("the honest reframing must still load",
                MlsConversationRecord.decode(framed(payload)));
        assertNull("declared shorter than present",
                MlsConversationRecord.decode(framed(payload, payload.length - 1)));
        assertNull("declared longer than present",
                MlsConversationRecord.decode(framed(payload, payload.length + 1)));
    }

    /**
     * A record written by a PRE-frame build must still load.
     *
     * <p>Version-1 blobs are live on the fleet and hold recovery-critical state. A migration that
     * orphaned them would reset every one of those conversations to Unknown — far worse than the
     * truncation bug the frame exists to fix — so the decoder reads both versions and the encoder
     * writes only the new one. The upgrade is the record's next write, and since the record is
     * always written whole, that is its next write of any kind.
     */
    @Test
    public void anUnframedVersionOneRecordStillLoads() {
        final MlsConversationRecord in = full().build();
        final MlsConversationRecord out =
                MlsConversationRecord.decode(unframedV1(payloadOf(in.encode())));
        assertNotNull("a version-1 record must not be orphaned by the frame", out);
        assertEquals(in.identity, out.identity);
        assertArrayEquals(in.groupId, out.groupId);
        assertEquals(in.healthStatus, out.healthStatus);
        assertEquals(in.selfHealKind, out.selfHealKind);
        assertEquals(in.moment, out.moment);
        assertEquals(in.sendsThisEpoch, out.sendsThisEpoch);
        assertEquals(in.sendsSinceLeafRotation, out.sendsSinceLeafRotation);
        assertNotNull(out.pendingOperation);
        assertEquals(in.pendingOperation.kind, out.pendingOperation.kind);
        assertArrayEquals(new byte[] {1, 2, 3}, out.epochAuthenticators.get(7L));

        // ...and it comes back framed, which is what makes the migration one-way.
        final byte[] rewritten = out.encode();
        assertEquals(VERSION_FRAMED, rewritten[0] & 0xFF);
        assertNotNull(MlsConversationRecord.decode(rewritten));
    }

    @Test
    public void aRecordFromAnUnknownVersionIsRefused() {
        final byte[] b = full().build().encode();
        b[0] = 99;
        assertNull(MlsConversationRecord.decode(b));
        assertNull(MlsConversationRecord.decode(null));
        assertNull(MlsConversationRecord.decode(new byte[0]));
    }

    /**
     * Forward compatibility: a record written by a NEWER build, carrying a field this one has never
     * heard of, must still load. A downgrade that discards a group's health state would send it
     * back through the recovery ladder from Unknown.
     */
    @Test
    public void unknownFieldsAreSkippedNotFatal() {
        final byte[] payload = payloadOf(full().build().encode());
        // A plausible future field: number 99, length-delimited, then number 98, scalar. Appended
        // INSIDE the frame, because that is where a newer build's encoder puts them — the length it
        // writes covers its own new fields. Bytes tacked on AFTER the frame are not a newer build,
        // they are corruption, and anExtendedRecordIsRefused() asserts they are rejected.
        final byte[] extra = new byte[] {
            99, 2, 3, 7, 7, 7,      // f99, wiretype 2, len 3, payload 07 07 07
            98, 0, 42,              // f98, wiretype 0, value 42
        };
        final byte[] grown = Arrays.copyOf(payload, payload.length + extra.length);
        System.arraycopy(extra, 0, grown, payload.length, extra.length);

        final MlsConversationRecord out = MlsConversationRecord.decode(framed(grown));
        assertNotNull("a record from a newer build must still load", out);
        assertEquals(MlsHealthStates.ONGOINGERAADVANCEMENT, out.healthStatus);
        assertEquals(42, out.sendsThisEpoch);
        assertNotNull(out.pendingOperation);
    }

    /**
     * A record written BEFORE {@code sendsSinceLeafRotation} existed must not come back with a fresh
     * full rotation budget.
     *
     * <p>The absent field decodes as 0, and 0 would mean "our leaf rotated just now" — handing every
     * already-persisted conversation a whole new {@code REKEY_AFTER_SENDS} allowance at upgrade, i.e.
     * DELAYING its next rotation. That is the one direction the fix exists to remove, so the decoder
     * seeds the missing field from {@code sendsThisEpoch} instead: never later than today's
     * behaviour, often sooner.
     *
     * <p>The seed cannot misfire on a legitimate record, and the reason is worth stating: after a real
     * rotation BOTH counters are zero (a rotation advances the epoch), and every send increments both
     * — so {@code sendsThisEpoch > 0} always implies {@code sendsSinceLeafRotation > 0}. The
     * combination this rule keys on is unreachable except for an old record.
     */
    @Test
    public void aPreMigrationRecordDoesNotGetAFreshRotationBudget() {
        final byte[] old = full().sendsThisEpoch(200).sendsSinceLeafRotation(0).build().encode();
        final MlsConversationRecord out = MlsConversationRecord.decode(old);
        assertNotNull(out);
        assertEquals("an absent rotation counter must inherit the epoch counter, not reset to 0",
                200, out.sendsSinceLeafRotation);
    }

    /**
     * A record written before {@code selfLeftAtMs} existed decodes as NOT-LEFT.
     *
     * <p>The opposite of {@code sendsSinceLeafRotation}: that field needed a migration seed because
     * its absent value (0) meant something dangerous, and this one's absent value is simply the truth
     * — no conversation written by an older build can have been left, because no older build could
     * record a departure. Pinned anyway, because the guards keyed on it are all refusals: a field
     * that decoded as "left" by accident would silently stop a real conversation sending, maintaining
     * and healing, and every one of those failures is a quiet one.
     */
    @Test
    public void aPreMigrationRecordIsNotReportedAsLeft() {
        final byte[] old = full().selfLeftAtMs(0L).build().encode();
        final MlsConversationRecord out = MlsConversationRecord.decode(old);
        assertNotNull(out);
        assertEquals(0L, out.selfLeftAtMs);
        assertFalse("a record with no departure recorded must never read as left", out.selfLeft());
    }

    /**
     * <b>The departure mark survives an overlay and is cleared only by writing zero</b>, and this
     * is the pair of properties the defect stood on.
     *
     * <p>{@code MlsProviderTransport.writeRecord} is a read-modify-write: it reads the persisted
     * record, calls {@code toBuilder()}, overlays the fields the in-memory {@code Group} knows, and
     * puts it back. It runs on EVERY {@code putGroup} — including {@code adoptGroup}'s, which is
     * what a join performs. So:
     *
     * <ul>
     *   <li><b>The overlay must preserve the mark.</b> If it did not, a departure would be undone by
     *       the next send, which is the opposite defect and a worse one — silently rejoining a group
     *       a person asked to leave.</li>
     *   <li><b>And because it preserves it, a join alone cannot clear it.</b> That is exactly
     *       the defect: a re-added member rejoins the group and {@code weLeft()} stays true, so
     *       every ED-1 guard declines on a group it is demonstrably a member of. The clear has to be
     *       an explicit write, and {@code clearSelfLeftOnRejoin} is the one place that makes it — on
     *       an ACCEPTED Welcome and nowhere else.</li>
     * </ul>
     *
     * <p>Both halves are asserted together because each is the reason the other is needed, and a
     * future reader who sees only one of them will reach for the wrong fix.
     */
    @Test
    public void theDepartureMarkSurvivesAnOverlayAndIsClearedOnlyByWritingZero() {
        final long leftAt = 1_788_965_286_026L;   // 00AU's real mark, 2026-09-09
        final MlsConversationRecord left = full().selfLeftAtMs(leftAt).build();
        assertTrue("the fixture is not marked LEFT, so neither half of this test means anything",
                left.selfLeft());

        // 1. THE OVERLAY. Exactly what writeRecord does on a join: toBuilder(), touch the fields a
        // Group carries, put it back. Nothing here names selfLeftAtMs, and it must still be there.
        final MlsConversationRecord overlaid = left.toBuilder()
                .rcsGroupId(left.rcsGroupId)
                .peerE164(left.peerE164)
                .sendsThisEpoch(left.sendsThisEpoch + 1)
                .sendsSinceLeafRotation(left.sendsSinceLeafRotation + 1)
                .build();
        assertEquals("an overlay that does not name selfLeftAtMs dropped it anyway — a departure "
                + "would then be undone by the next send, which is the reversal the drop lever "
                + "is forbidden from performing", leftAt, overlaid.selfLeftAtMs);
        assertTrue(overlaid.selfLeft());
        // ...and it survives the round trip to storage, which is where writeRecord leaves it.
        final MlsConversationRecord reloaded = MlsConversationRecord.decode(overlaid.encode());
        assertNotNull(reloaded);
        assertEquals(leftAt, reloaded.selfLeftAtMs);
        assertTrue("the mark did not survive encode/decode, so a rejoin after a process restart "
                + "would read as not-left by accident rather than by decision", reloaded.selfLeft());

        // 2. THE CLEAR, which is therefore the only exit short of forget(). One explicit write.
        final MlsConversationRecord back = overlaid.toBuilder().selfLeftAtMs(0L).build();
        assertEquals(0L, back.selfLeftAtMs);
        assertFalse("writing zero did not clear the mark, so clearSelfLeftOnRejoin cannot un-leave "
                + "us and the rejoin defect is unfixed", back.selfLeft());
        final MlsConversationRecord backReloaded = MlsConversationRecord.decode(back.encode());
        assertNotNull(backReloaded);
        assertFalse("the cleared record reads as LEFT again after a round trip through storage",
                backReloaded.selfLeft());
        // Nothing else was disturbed on the way back.
        assertEquals(overlaid.healthStatus, backReloaded.healthStatus);
        assertEquals(overlaid.rcsGroupId, backReloaded.rcsGroupId);
        assertEquals(overlaid.peerE164, backReloaded.peerE164);
    }

    // ---- 2.1: the key ---------------------------------------------------------------------------

    /**
     * The key is {@code (identity, group)}, not the conversation id. The old key could not express
     * the same RCS group under two identities — and those are two different MLS groups with two
     * different storage directories.
     */
    @Test
    public void theKeyDistinguishesIdentities() {
        assertFalse(MlsConversationRecord.key("+1555", GID)
                .equals(MlsConversationRecord.key("+1666", GID)));
        assertFalse(MlsConversationRecord.key(ME, new byte[] {1})
                .equals(MlsConversationRecord.key(ME, new byte[] {2})));
        assertEquals(MlsConversationRecord.key(ME, GID), full().build().key());
    }

    // ---- 2.2: the pending operation --------------------------------------------------------------

    /** Nothing in flight is a NULL field, which is where the "already pending" guard comes from. */
    @Test
    public void theAlreadyPendingGuardIsJustTheFieldBeingSet() {
        final MlsConversationRecord idle = MlsConversationRecord.initial(ME, GID, "r", "+1555");
        assertNull(idle.pendingOperation);

        final MlsConversationRecord busy = idle.toBuilder()
                .pendingOperation(MlsPendingOperation.start(
                        MlsPendingOperation.Kind.END_MLS, 1L, new Moment(1, 1L), ""))
                .build();
        assertNotNull(busy.pendingOperation);
        assertEquals(MlsPendingOperation.Kind.END_MLS, busy.pendingOperation.kind);
    }

    /** The retry count is PERSISTED, so a crash loop can no longer reset it to zero each pass. */
    @Test
    public void attemptCountSurvivesTheRoundTripAndIncrements() {
        MlsPendingOperation op = MlsPendingOperation.start(
                MlsPendingOperation.Kind.EPOCH_ADVANCEMENT, 500L, new Moment(1, 2L), "ctx");
        assertEquals(1, op.attemptCount);
        op = op.retried().retried();
        assertEquals(3, op.attemptCount);
        assertEquals("started_at must NOT move on a retry", 500L, op.startedAtMs);
        assertEquals("the binding must NOT move on a retry", new Moment(1, 2L), op.momentBinding);

        final MlsConversationRecord r = MlsConversationRecord.decode(
                MlsConversationRecord.initial(ME, GID, "r", "p")
                        .toBuilder().pendingOperation(op).build().encode());
        assertEquals(3, r.pendingOperation.attemptCount);
        assertTrue(r.pendingOperation.attemptsExhausted(3));
        assertFalse(r.pendingOperation.attemptsExhausted(4));
    }

    /**
     * Staleness is MOMENT-based, never timestamp-based (§7.4). An operation created a millisecond
     * ago against an era that has since advanced IS stale; one created an hour ago against the
     * current moment is NOT.
     */
    @Test
    public void stalenessIsMomentBasedNotTimeBased() {
        final MlsPendingOperation op = MlsPendingOperation.start(
                MlsPendingOperation.Kind.ERA_ADVANCEMENT, 1L, new Moment(2, 5L), "");
        assertFalse(op.isStaleAt(new Moment(2, 5L)));
        assertTrue("the epoch moved", op.isStaleAt(new Moment(2, 6L)));
        assertTrue("the era moved", op.isStaleAt(new Moment(3, 5L)));
        assertFalse("an unknown current moment cannot prove staleness", op.isStaleAt(null));
    }

    /** An operation with no binding predates the field and must not be dropped wholesale. */
    @Test
    public void anUnboundOperationIsNotStale() {
        final MlsPendingOperation legacy = new MlsPendingOperation(
                MlsPendingOperation.Kind.END_MLS, 1, 0L, null, "");
        assertFalse(legacy.isStaleAt(new Moment(9, 9L)));
    }

    @Test
    public void theTimeLimitArmFires() {
        final MlsPendingOperation op = MlsPendingOperation.start(
                MlsPendingOperation.Kind.END_MLS, 1_000L, new Moment(1, 1L), "");
        assertFalse(op.timedOut(1_500L, 1_000L));
        assertTrue(op.timedOut(2_000L, 1_000L));
        assertFalse("a zero window disables the time limit", op.timedOut(Long.MAX_VALUE, 0L));
    }

    // ---- 2.3: two fields, neither derivable from the other ---------------------------------------

    /**
     * {@code OngoingEraAdvancement} does not tell you whether an ordinary era advance or a
     * Phoenix-mode one is running, and those have different success conditions and failure ladders.
     * That is exactly why §5.1 keeps them as two fields.
     */
    @Test
    public void healthStatusDoesNotDetermineSelfHealKind() {
        final MlsConversationRecord ordinary = full()
                .healthStatus(MlsHealthStates.ONGOINGERAADVANCEMENT)
                .selfHealKind(MlsSelfHealKind.ERA_ADVANCEMENT).build();
        final MlsConversationRecord phoenix = full()
                .healthStatus(MlsHealthStates.ONGOINGERAADVANCEMENT)
                .selfHealKind(MlsSelfHealKind.ERA_ADVANCEMENT_FOR_PHOENIX_MODE).build();
        assertEquals(ordinary.healthStatus, phoenix.healthStatus);
        assertFalse("same status, different heal — the two axes are independent",
                ordinary.selfHealKind == phoenix.selfHealKind);
    }

    /** An unrecognised persisted number must degrade to NONE, not throw: records outlive builds. */
    @Test
    public void anUnknownSelfHealKindDegradesGracefully() {
        assertEquals(MlsSelfHealKind.NONE, MlsSelfHealKind.fromWire(99));
        assertEquals(MlsSelfHealKind.NONE, MlsSelfHealKind.fromWire(-1));
        assertEquals(MlsPendingOperation.Kind.UNKNOWN, MlsPendingOperation.Kind.fromWire(99));
        assertFalse(MlsSelfHealKind.NONE.isHealing());
        assertTrue(MlsSelfHealKind.END_MLS.isHealing());
    }

    // ---- the self-heal budget is a DIFFERENT counter from the retry count -------------------------

    /**
     * §4.8 rows 5 and 10 are separate on purpose: the budget is cleared by the §5.10 hooks and the
     * attempt count is NOT. Conflating them means either the self-heal limiter never resets (a
     * group that heals twice hits its limit on the second attempt) or the retry limiter is reset by
     * a state transition, which a crash loop then rides straight through.
     */
    @Test
    public void theSelfHealBudgetIsIndependentOfTheRetryCount() {
        MlsConversationRecord.SelfHealBudget b = MlsConversationRecord.SelfHealBudget.EMPTY;
        assertFalse(b.exhausted(2, 1_000L, 10_000L));
        b = b.counted(1_000L);
        assertEquals(1, b.retryCount);
        assertEquals(1_000L, b.firstAttemptAtMs);
        b = b.counted(5_000L);
        assertEquals(2, b.retryCount);
        assertEquals("first_attempt_at must not move", 1_000L, b.firstAttemptAtMs);
        assertTrue(b.exhausted(2, 5_000L, 10_000L));

        // ...and clearing it (the §5.10 hook) does not touch a pending operation's attempt count.
        final MlsPendingOperation op = new MlsPendingOperation(
                MlsPendingOperation.Kind.EPOCH_ADVANCEMENT, 4, 1L, new Moment(1, 1L), "");
        final MlsConversationRecord r = full()
                .selfHealBudget(MlsConversationRecord.SelfHealBudget.EMPTY)
                .pendingOperation(op).build();
        assertEquals(0, r.selfHealBudget.retryCount);
        assertEquals(4, r.pendingOperation.attemptCount);
    }

    /**
     * An elapsed window ROLLS the limiter; it does not retire the conversation.
     *
     * <p>This test asserted the exact opposite and the opposite was a permanent deadlock: the window
     * is the only clock in the limiter, {@code now - firstAttemptAtMs} never comes back under it, and
     * the sole reset ({@code noteForwardProgress}) needs a successful heal — the very thing being
     * refused. Any conversation that had attempted one self-heal became un-healable a day later.
     *
     * <p>Caught on 00AU ↔ 00RU against a Google Messages peer, 2026-08-03: the server refused every send with
     * {@code incorrect-epoch-authenticator}, the remedy asked to self-heal, and the limiter answered
     * {@code EXHAUSTED (time limit 86400000ms reached, reason 22, attempts=3)} — three of a permitted
     * five, refused on time.
     */
    @Test
    public void anElapsedWindowFreesTheBudgetRatherThanRetiringIt() {
        final MlsConversationRecord.SelfHealBudget b =
                new MlsConversationRecord.SelfHealBudget(1, 1_000L, true);
        assertFalse("inside the window and under the count", b.exhausted(5, 2_000L, 10_000L));
        assertFalse("an elapsed window is a NEW window, not exhaustion",
                b.exhausted(5, 12_000L, 10_000L));
        assertTrue("and it is detectable as elapsed", b.windowElapsed(12_000L, 10_000L));
        assertFalse("not yet elapsed one tick earlier", b.windowElapsed(10_999L, 10_000L));

        // The COUNT is what exhausts, and only within the window.
        final MlsConversationRecord.SelfHealBudget spent =
                new MlsConversationRecord.SelfHealBudget(5, 1_000L, true);
        assertTrue("five of five, inside the window", spent.exhausted(5, 2_000L, 10_000L));
        assertFalse("same count, window elapsed → spendable again",
                spent.exhausted(5, 12_000L, 10_000L));
    }

    // ---- retention: an unbounded map in a whole-blob record is a write-amplification bug ---------

    @Test
    public void theMapsAreBounded() {
        final MlsConversationRecord.Builder b = MlsConversationRecord.builder()
                .identity(ME).groupId(GID);
        for (long e = 0; e < 200; e++) b.putEpochAuthenticator(5, e, new byte[] {(byte) e});
        for (int era = 0; era < 40; era++) b.putMembership(era, 1L, new String[] {ME});
        final MlsConversationRecord r = b.build();
        assertTrue("epoch authenticators must be trimmed: " + r.epochAuthenticators.size(),
                r.epochAuthenticators.size() <= 32);
        assertTrue("membership eras must be trimmed: " + r.membershipHistory.size(),
                r.membershipHistory.size() <= 8);
        // ...and it is the OLDEST that go, since a past-epoch message is more likely to be recent.
        assertTrue(r.epochAuthenticators.containsKey(199L));
        assertFalse(r.epochAuthenticators.containsKey(0L));
    }

    // ---- the epoch-authenticator map is ONE ERA's chain -----------------------------------------

    /**
     * THE BUG: a new era restarts the epoch at 0, so era N+1's epochs silently OVERWROTE era N's.
     *
     * <p>The map was keyed on the epoch alone while {@code toBuilder} carried it across eras and
     * nothing ever cleared it. Era 4 epoch 0 landed on top of era 3 epoch 0 and the record then held
     * a value it would report as era 3's. It never returned a WRONG answer to the one reader we have
     * — that reader asks for the current epoch, and the current era's write is always the most
     * recent — which is exactly why it survived: the defect was in what the map COULD be used for.
     *
     * <p>This is the assertion that would have caught it, and note what it checks: not that the new
     * era's entry is present (it always was), but that the OLD era's entries are GONE rather than
     * silently shadowed. A membership test on the survivor cannot tell those two apart.
     */
    @Test
    public void aNewEraReplacesTheEpochAuthenticatorsRatherThanOverwritingThem() {
        final MlsConversationRecord.Builder b = MlsConversationRecord.builder()
                .identity(ME).groupId(GID)
                .putEpochAuthenticator(3, 0L, new byte[] {3, 0})
                .putEpochAuthenticator(3, 1L, new byte[] {3, 1})
                .putEpochAuthenticator(3, 2L, new byte[] {3, 2});
        assertEquals(3, b.build().epochAuthenticators.size());
        assertEquals(3, b.build().epochAuthEra);

        b.putEpochAuthenticator(4, 0L, new byte[] {4, 0});
        final MlsConversationRecord r = b.build();
        assertEquals("era 4's map must hold era 4 and NOTHING else — era 3's epochs 1 and 2 are "
                + "points on a chain that no longer exists", 1, r.epochAuthenticators.size());
        assertEquals(4, r.epochAuthEra);
        assertArrayEquals(new byte[] {4, 0}, r.epochAuthenticators.get(0L));
        assertFalse("era 3 epoch 1 survived an era advance", r.epochAuthenticators.containsKey(1L));
        assertFalse("era 3 epoch 2 survived an era advance", r.epochAuthenticators.containsKey(2L));
    }

    /** Within ONE era the map is real history, which is what makes the depth meaningful. */
    @Test
    public void withinOneEraTheAuthenticatorsAccumulate() {
        final MlsConversationRecord r = MlsConversationRecord.builder()
                .identity(ME).groupId(GID)
                .putEpochAuthenticator(9, 0L, new byte[] {0})
                .putEpochAuthenticator(9, 1L, new byte[] {1})
                .putEpochAuthenticator(9, 2L, new byte[] {2})
                .build();
        assertEquals(3, r.epochAuthenticators.size());
        assertArrayEquals(new byte[] {0}, r.epochAuthenticators.get(0L));
        assertArrayEquals(new byte[] {2}, r.epochAuthenticators.get(2L));
    }

    /** The era survives the encode/decode boundary, or the map means nothing after a restart. */
    @Test
    public void theEpochAuthenticatorEraRoundTrips() {
        final MlsConversationRecord r = MlsConversationRecord.builder()
                .identity(ME).groupId(GID)
                .putEpochAuthenticator(11, 4L, new byte[] {9, 9})
                .putEpochAuthenticator(11, 5L, new byte[] {8, 8})
                .build();
        final MlsConversationRecord back = MlsConversationRecord.decode(r.encode());
        assertNotNull(back);
        assertEquals(11, back.epochAuthEra);
        assertEquals(2, back.epochAuthenticators.size());
        assertArrayEquals(new byte[] {9, 9}, back.epochAuthenticators.get(4L));
        assertArrayEquals(new byte[] {8, 8}, back.epochAuthenticators.get(5L));
    }

    /**
     * DECODE MUST NOT GO THROUGH THE ERA-AWARE SETTER, and this is the assertion that pins it.
     *
     * <p>{@code putEpochAuthenticator} clears the map when the era differs, and {@code
     * F_EPOCH_AUTH_ERA} is written LAST — so a decoder using it would wipe each restored entry on
     * the next one and end with a map of size 1. Two entries surviving a round trip is the whole
     * check; a test that stored only one could not fail.
     */
    @Test
    public void decodingManyAuthenticatorsDoesNotClearThemOneByOne() {
        final MlsConversationRecord.Builder b = MlsConversationRecord.builder()
                .identity(ME).groupId(GID);
        for (long e = 0; e < 5; e++) b.putEpochAuthenticator(6, e, new byte[] {(byte) e});
        final MlsConversationRecord back = MlsConversationRecord.decode(b.build().encode());
        assertNotNull(back);
        assertEquals("the decoder is clearing the map it is filling", 5,
                back.epochAuthenticators.size());
        assertEquals(6, back.epochAuthEra);
    }

    /**
     * MIGRATION: a record written before this field KEEPS its entries at decode, and the first write
     * is what resolves the ambiguity.
     *
     * <p>Dropping them at decode would leave {@code g.epochAuth} null until the next write, and a
     * 1:1 seal with no {@code Epoch-Authenticator} header is one a Google Messages peer refuses to parse
     *. The pre-era blob is synthesised by stripping the trailing era field,
     * which is what an older build's output IS — every other byte is identical by the append
     * convention.
     */
    @Test
    public void aRecordWithNoEraFieldKeepsItsEntriesAndIsMarkedUnknown() {
        final MlsConversationRecord r = MlsConversationRecord.builder()
                .identity(ME).groupId(GID)
                .putEpochAuthenticator(7, 3L, new byte[] {1})
                .putEpochAuthenticator(7, 4L, new byte[] {2})
                .build();
        final MlsConversationRecord legacy = MlsConversationRecord.builder()
                .identity(ME).groupId(GID)
                .restoreEpochAuthenticator(3L, new byte[] {1})
                .restoreEpochAuthenticator(4L, new byte[] {2})
                .build();
        assertEquals("the restorer must not stamp an era", MlsConversationRecord.ERA_UNKNOWN,
                legacy.epochAuthEra);
        final MlsConversationRecord back = MlsConversationRecord.decode(legacy.encode());
        assertNotNull(back);
        assertEquals(MlsConversationRecord.ERA_UNKNOWN, back.epochAuthEra);
        assertEquals("a pre-era record must keep its entries — the current-epoch lookup is still "
                + "correct on it, and dropping them costs an Epoch-Authenticator header", 2,
                back.epochAuthenticators.size());
        assertEquals("...and a record that DOES know its era encodes one more field than one that "
                + "does not", true, r.encode().length > legacy.encode().length);

        // …and the FIRST WRITE clears the ambiguity rather than carrying it.
        final MlsConversationRecord after =
                back.toBuilder().putEpochAuthenticator(7, 5L, new byte[] {3}).build();
        assertEquals(7, after.epochAuthEra);
        assertEquals(1, after.epochAuthenticators.size());
        assertArrayEquals(new byte[] {3}, after.epochAuthenticators.get(5L));
    }

    /**
     * AN ERA ABOVE 2^31 IS AN ERA, NOT AN ABSENT ONE — and the setter stays idempotent for it.
     *
     * <p>{@code Moment.era} is an unsigned u32 in a signed {@code int}, so era {@code 0x80000000}
     * arrives here as {@link Integer#MIN_VALUE}. A first cut of this stored
     * {@code era < 0 ? ERA_UNKNOWN : era}, which reclassified every such era as unknown AND made
     * the setter non-idempotent: the stored value never equalled the one handed in, so the SECOND
     * write of the same era cleared the whole map. That is the assertion below, and it is the one
     * that would have failed — checking only that the entry is present could not.
     *
     * <p>Not reachable today (eras increment one at a time from 1), which is exactly why it is
     * pinned here rather than left to be noticed: the neighbours in this package are emphatic that
     * era and epoch are unsigned, and getting it wrong silently is what the
     * {@code Long.compareUnsigned} note is about one field over.
     */
    @Test
    public void anEraAboveTwoToTheThirtyFirstIsStoredVerbatimAndDoesNotReClear() {
        final int hugeEra = Integer.MIN_VALUE;           // 0x80000000 read as unsigned
        final MlsConversationRecord.Builder b = MlsConversationRecord.builder()
                .identity(ME).groupId(GID)
                .putEpochAuthenticator(hugeEra, 0L, new byte[] {1})
                .putEpochAuthenticator(hugeEra, 1L, new byte[] {2});
        final MlsConversationRecord r = b.build();
        assertEquals("a legal unsigned era must not be filed as ERA_UNKNOWN", hugeEra,
                r.epochAuthEra);
        assertNotEquals(MlsConversationRecord.ERA_UNKNOWN, r.epochAuthEra);
        assertEquals("the second write of the SAME era cleared the map — the setter is not "
                + "idempotent", 2, r.epochAuthenticators.size());
        final MlsConversationRecord back = MlsConversationRecord.decode(r.encode());
        assertNotNull(back);
        assertEquals(hugeEra, back.epochAuthEra);
        assertEquals(2, back.epochAuthenticators.size());
    }

    /**
     * THE SENTINEL COLLIDES WITH EXACTLY ONE ERA, and this test exists because the javadoc first
     * claimed it collided with none.
     *
     * <p>The claim was that {@code nextEra} can never produce {@code 0xFFFFFFFF}. It can:
     * {@code nextEra} refuses only {@code era >= 0xFFFFFFFF}, so stepping up from
     * {@code 0xFFFFFFFE} lands exactly on the sentinel's bit pattern. Pinning the REAL boundary —
     * one step refused, the step below it allowed — is what stops the comfortable version of the
     * claim coming back.
     *
     * <p>What makes {@link MlsConversationRecord#ERA_UNKNOWN} tolerable is not absence of collision
     * but that the same ambiguity already exists upstream: {@code eraFrom} answers {@code -1} for an
     * unreadable blob, so nothing in this package could tell era {@code 0xFFFFFFFF} from
     * "unreadable" before this field existed either.
     */
    @Test
    public void theUnknownSentinelCollidesWithExactlyOneUnreachableEra() {
        assertEquals(-1, MlsConversationRecord.ERA_UNKNOWN);
        assertEquals("nextEra refuses to advance FROM the sentinel's era", -1L,
                MlsAppMessage.nextEra(0xFFFFFFFFL));
        assertEquals("but it will happily advance INTO it — the collision is real, not hypothetical",
                0xFFFFFFFFL, MlsAppMessage.nextEra(0xFFFFFFFEL));
        assertEquals("and that era, narrowed to the int the record stores, IS the sentinel",
                MlsConversationRecord.ERA_UNKNOWN, (int) 0xFFFFFFFFL);
        assertEquals("the same value already means 'unreadable' at eraFrom, so this field inherits "
                + "the ambiguity rather than introducing it", -1, MlsAppMessage.eraFrom(null));
    }

    // ---- 2.4: the debug dump that exists INSTEAD of an index --------------------------------------

    @Test
    public void theDumpNamesTheThingsYouWouldOtherwiseIndexOn() {
        final String d = full().build().dump();
        assertTrue(d, d.contains(MlsHealthStates.name(MlsHealthStates.ONGOINGERAADVANCEMENT)));
        assertTrue(d, d.contains("ERA_ADVANCEMENT_FOR_PHOENIX_MODE"));
        assertTrue(d, d.contains("pending="));
        assertTrue(d, d.contains(ME));
    }

    /** The dump must be safe on a bare record — it is what you reach for when things are wrong. */
    @Test
    public void theDumpSurvivesAnEmptyRecord() {
        assertNotNull(MlsConversationRecord.initial(ME, GID, null, null).dump());
        assertNotNull(MlsConversationRecord.builder().build().dump());
    }
}

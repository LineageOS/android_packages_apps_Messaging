/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
import com.android.messaging.rcs.log.LogMask;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * {@link MlsConversationRecord}: the persisted per-conversation record, its framing and
 * compatibility rules, the pending operation, and the self-heal budget. See
 * docs/mls/health-and-recovery.md.
 */
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
                .selfHealBudget(
                        new MlsConversationRecord.SelfHealBudget(2, 1_699_000_000_000L, true))
                .storedStatusRequest(new MlsConversationRecord.StatusRequest(
                        MlsHealthStates.ERAADVANCEMENTREQUESTED, 4))
                .expectedCommitKind(MlsConversationRecord.ExpectedCommitKind.SELF_KEY_UPDATE)
                // Era 3, matching the fixture's moment: the map holds one era's chain.
                .putEpochAuthenticator(3, 7L, new byte[] {1, 2, 3})
                .putEpochAuthenticator(3, 6L, new byte[] {4, 5})
                .putMembership(3, 7L, new String[] {ME, "+15715550103"})
                .putMemberValidity(0, new MlsConversationRecord.MemberValidity(1000L, 99_000L))
                .putParticipantKeyUpdate("+15715550103", 1_698_000_000_000L)
                .sendsThisEpoch(42)
                // Different from sendsThisEpoch: equal fixture values would hide a field mix-up in
                // either direction.
                .sendsSinceLeafRotation(137)
                .selfLeftAtMs(1_701_234_567_000L)
                // 32 bytes, the only length RCC.16 §8.3.1.1 permits, so a framing bug cannot hide.
                .continuityToken(TOKEN);
    }

    /** A 32-byte stand-in for the RCC.16 §8.3.1.1 continuity token. */
    private static final byte[] TOKEN = token((byte) 0x5A);

    private static byte[] token(final byte seed) {
        final byte[] t = new byte[32];
        for (int i = 0; i < t.length; i++) t[i] = (byte) (i ^ seed);
        return t;
    }

    // encode() emits [version][varint payload length][payload]. These helpers take a record apart
    // and reassemble it, so tests forge the blobs an older or newer build would write.

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

    /** A framed record declaring {@code declared} payload bytes, which a test may set wrong. */
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

        assertEquals(in.storedStatusRequest.requestedStatus,
                out.storedStatusRequest.requestedStatus);
        assertEquals(in.storedStatusRequest.cause, out.storedStatusRequest.cause);

        assertArrayEquals(new byte[] {1, 2, 3}, out.epochAuthenticators.get(7L));
        assertArrayEquals(new byte[] {4, 5}, out.epochAuthenticators.get(6L));
        assertArrayEquals(new String[] {ME, "+15715550103"},
                out.membershipHistory.get(3).get(7L));
        assertEquals(99_000L, out.memberValidity.get(0).notAfterSecs);
        assertEquals(Long.valueOf(1_698_000_000_000L),
                out.lastParticipantKeyUpdate.get("+15715550103"));
        assertArrayEquals(
                "continuityToken must survive the round trip byte for byte — a token that "
                + "changes across a restart is worse than no token, because §10.5.1 compares it",
                TOKEN, out.continuityToken);
        assertTrue(out.hasContinuityToken());
    }

    /**
     * The RCC.16 §7.11.12.1 continuity token. A record that has never seen one decodes to empty,
     * not null, and grows no field, so it is byte-identical to what an older build wrote.
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

        // No bytes spent without a token; compared against a record that differs only by the token.
        final byte[] without = none.encode();
        final byte[] with = none.toBuilder().continuityToken(TOKEN).build().encode();
        assertTrue("a record carrying a token must be longer than one that does not, or the field "
                + "is not being written at all", with.length > without.length);

        // null and empty are the same statement and neither is stored as a present-but-empty field:
        // RCC.16 §8.3.1.3's "we hold no token" arm keys on it.
        assertEquals(0, none.toBuilder().continuityToken(null).build().continuityToken.length);
        assertEquals(0,
                none.toBuilder().continuityToken(new byte[0]).build().continuityToken.length);
        assertArrayEquals(without, none.toBuilder().continuityToken(new byte[0]).build().encode());
    }

    /**
     * The record is immutable: neither the array passed in nor the array read back can change the
     * stored token.
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
     * A record from a build that predates the field loads with no token; a seeded one would put us
     * in RCC.16 §8.3.1.2's "have token" arm holding a value no peer could match.
     */
    @Test
    public void aPreMigrationRecordHoldsNoContinuityToken() {
        // Exactly the blob an older build wrote for this fixture: encode() emits the token field
        // only when a token is held.
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

        // The other direction: decode() skips unknown length-delimited fields, so an older build
        // loads a record carrying the token. A field-number collision would show as a corrupted
        // neighbour.
        final MlsConversationRecord newer = MlsConversationRecord.decode(withToken.encode());
        assertNotNull(newer);
        assertEquals(withToken.healthStatus, newer.healthStatus);
        assertEquals(withToken.selfLeftAtMs, newer.selfLeftAtMs);
    }

    /** The dump names the token's length and never its value; it is a 256-bit group secret. */
    @Test
    public void theDumpNeverPrintsTheContinuityToken() {
        final String d = full().build().dump();
        assertTrue("the dump must say whether we hold one: " + d,
                d.contains("continuityToken=32B"));
        final StringBuilder hex = new StringBuilder();
        for (final byte b : TOKEN) hex.append(String.format("%02x", b));
        assertFalse("the dump must never carry the token's bytes: " + d,
                d.contains(hex.toString()));
        assertFalse(d.contains(android_util_base64_free(TOKEN)));
    }

    /** Base64 without android.util; the dump must not contain this either. */
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
     * A truncated record decodes to null, never to a partial one: a half-read record reads as
     * "Unknown, nothing pending" and sends a healthy conversation through the recovery ladder.
     * Every prefix is checked; with the length frame no prefix of a record is itself a record.
     */
    @Test
    public void aTruncatedRecordIsNullNotPartial() {
        final byte[] whole = full().build().encode();
        for (int cut = 0; cut < whole.length; cut++) {
            assertNull("truncation at " + cut + " of " + whole.length
                            + " must be refused outright, "
                            + "not decoded into a record missing everything after the cut",
                    MlsConversationRecord.decode(Arrays.copyOf(whole, cut)));
        }
        assertNotNull("...and the record itself must still load",
                MlsConversationRecord.decode(whole));
    }

    /**
     * A record that grew is refused too. Trailing bytes are not a newer build's field (its own
     * length would cover them) but a concatenation, padded rewrite or partial overwrite, which a
     * trailing sentinel would not catch.
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
     * A record written by a pre-frame build still loads: the decoder reads both versions, the
     * encoder writes only the framed one, and the record's next write performs the upgrade.
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

        // ...and it comes back framed, which makes the migration one-way.
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
     * A record from a newer build carrying an unknown field still loads, so a downgrade keeps the
     * group's health state.
     */
    @Test
    public void unknownFieldsAreSkippedNotFatal() {
        final byte[] payload = payloadOf(full().build().encode());
        // Future fields are appended inside the frame, where a newer encoder puts them; bytes after
        // the frame are corruption (see anExtendedRecordIsRefused).
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
     * A record written before {@code sendsSinceLeafRotation} existed does not get a fresh rotation
     * budget: the absent field is seeded from {@code sendsThisEpoch}. The seed cannot misfire,
     * because a rotation zeroes both counters and every send increments both.
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
     * A record written before {@code selfLeftAtMs} existed decodes as not left; a spurious "left"
     * would quietly stop the conversation sending, maintaining and healing.
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
     * The departure mark survives the {@code toBuilder()} overlay that {@code writeRecord} performs
     * on every {@code putGroup} (including a join), and is cleared only by an explicit write of
     * zero, which {@code clearSelfLeftOnRejoin} makes on an accepted Welcome. Without the first a
     * send would undo a leave; the second follows from it.
     */
    @Test
    public void theDepartureMarkSurvivesAnOverlayAndIsClearedOnlyByWritingZero() {
        final long leftAt = 1_788_965_286_026L;
        final MlsConversationRecord left = full().selfLeftAtMs(leftAt).build();
        assertTrue("the fixture is not marked LEFT, so neither half of this test means anything",
                left.selfLeft());

        // 1. The overlay, as writeRecord does it on a join; nothing here names selfLeftAtMs.
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
        // ...and it survives the round trip to storage.
        final MlsConversationRecord reloaded = MlsConversationRecord.decode(overlaid.encode());
        assertNotNull(reloaded);
        assertEquals(leftAt, reloaded.selfLeftAtMs);
        assertTrue("the mark did not survive encode/decode, so a rejoin after a process restart "
                + "would read as not-left by accident rather than by decision",
                reloaded.selfLeft());

        // 2. The clear, the only exit short of forget(): one explicit write.
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

    /**
     * The key is {@code (identity, group)}, not the conversation id: the same RCS group under two
     * identities is two MLS groups with two storage directories.
     */
    @Test
    public void theKeyDistinguishesIdentities() {
        assertFalse(MlsConversationRecord.key("+1555", GID)
                .equals(MlsConversationRecord.key("+1666", GID)));
        assertFalse(MlsConversationRecord.key(ME, new byte[] {1})
                .equals(MlsConversationRecord.key(ME, new byte[] {2})));
        assertEquals(MlsConversationRecord.key(ME, GID), full().build().key());
    }

    /** Nothing in flight is a null field, which is what the "already pending" guard reads. */
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

    /** The retry count is persisted, so a crash loop cannot reset it each pass. */
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
     * Staleness is moment-based, never timestamp-based: an operation created a millisecond ago
     * against an era that has since advanced is stale; one an hour old against the current moment
     * is not.
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

    /** An operation with no binding predates the field and is not dropped wholesale. */
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

    /**
     * {@code OngoingEraAdvancement} does not say whether an ordinary or a Phoenix-mode era advance
     * is running, and those have different success conditions and failure ladders, so they are two
     * fields.
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

    /**
     * An unrecognised persisted number degrades to NONE rather than throwing: records outlive
     * builds.
     */
    @Test
    public void anUnknownSelfHealKindDegradesGracefully() {
        assertEquals(MlsSelfHealKind.NONE, MlsSelfHealKind.fromWire(99));
        assertEquals(MlsSelfHealKind.NONE, MlsSelfHealKind.fromWire(-1));
        assertEquals(MlsPendingOperation.Kind.UNKNOWN, MlsPendingOperation.Kind.fromWire(99));
        assertFalse(MlsSelfHealKind.NONE.isHealing());
        assertTrue(MlsSelfHealKind.END_MLS.isHealing());
    }

    /**
     * The self-heal budget and the attempt count are separate: the budget is cleared by forward
     * progress and the attempt count is not, so neither limiter resets the other.
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

        // ...and clearing it does not touch a pending operation's attempt count.
        final MlsPendingOperation op = new MlsPendingOperation(
                MlsPendingOperation.Kind.EPOCH_ADVANCEMENT, 4, 1L, new Moment(1, 1L), "");
        final MlsConversationRecord r = full()
                .selfHealBudget(MlsConversationRecord.SelfHealBudget.EMPTY)
                .pendingOperation(op).build();
        assertEquals(0, r.selfHealBudget.retryCount);
        assertEquals(4, r.pendingOperation.attemptCount);
    }

    /**
     * An elapsed window rolls the limiter rather than retiring the conversation: the only reset
     * needs a successful heal, so a window that never rolled would make a conversation un-healable
     * for good.
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

        // The count is what exhausts, and only within the window.
        final MlsConversationRecord.SelfHealBudget spent =
                new MlsConversationRecord.SelfHealBudget(5, 1_000L, true);
        assertTrue("five of five, inside the window", spent.exhausted(5, 2_000L, 10_000L));
        assertFalse("same count, window elapsed → spendable again",
                spent.exhausted(5, 12_000L, 10_000L));
    }

    // Retention: an unbounded map in a whole-blob record amplifies every write.

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
        // ...and the oldest go, since a past-epoch message is more likely to be recent.
        assertTrue(r.epochAuthenticators.containsKey(199L));
        assertFalse(r.epochAuthenticators.containsKey(0L));
    }

    /**
     * A new era restarts the epoch at 0, so the epoch-authenticator map holds one era's chain:
     * moving to a new era clears the old entries rather than shadowing them.
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

    /** Within one era the map is real history, which makes the depth meaningful. */
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

    /** The era survives encode/decode, or the map means nothing after a restart. */
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
     * Decode does not use the era-aware setter: {@code putEpochAuthenticator} clears the map when
     * the era differs and {@code F_EPOCH_AUTH_ERA} is written last, so such a decoder would keep
     * one entry.
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
     * A record written before the era field keeps its entries at decode, and the first write
     * resolves the ambiguity; dropping them would leave {@code g.epochAuth} null, and a 1:1 seal
     * without an {@code Epoch-Authenticator} header is refused by peers. The older blob is the
     * current one with the trailing era field stripped.
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

        // ...and the first write clears the ambiguity rather than carrying it.
        final MlsConversationRecord after =
                back.toBuilder().putEpochAuthenticator(7, 5L, new byte[] {3}).build();
        assertEquals(7, after.epochAuthEra);
        assertEquals(1, after.epochAuthenticators.size());
        assertArrayEquals(new byte[] {3}, after.epochAuthenticators.get(5L));
    }

    /**
     * An era above 2^31 is an era, not an absent one, and the setter stays idempotent for it:
     * {@code Moment.era} is an unsigned u32 in a signed {@code int}, so a second write of the same
     * era must not clear the map.
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
     * {@link MlsConversationRecord#ERA_UNKNOWN} collides with exactly one era: {@code nextEra}
     * refuses only {@code era >= 0xFFFFFFFF}, so stepping from {@code 0xFFFFFFFE} lands on the
     * sentinel. The same ambiguity exists upstream, where {@code eraFrom} answers -1 for an
     * unreadable blob.
     */
    @Test
    public void theUnknownSentinelCollidesWithExactlyOneUnreachableEra() {
        assertEquals(-1, MlsConversationRecord.ERA_UNKNOWN);
        assertEquals("nextEra refuses to advance FROM the sentinel's era", -1L,
                MlsAppMessage.nextEra(0xFFFFFFFFL));
        assertEquals(
                "but it will happily advance INTO it — the collision is real, not hypothetical",
                0xFFFFFFFFL, MlsAppMessage.nextEra(0xFFFFFFFEL));
        assertEquals("and that era, narrowed to the int the record stores, IS the sentinel",
                MlsConversationRecord.ERA_UNKNOWN, (int) 0xFFFFFFFFL);
        assertEquals("the same value already means 'unreadable' at eraFrom, so this field inherits "
                + "the ambiguity rather than introducing it", -1, MlsAppMessage.eraFrom(null));
    }

    // The debug dump exists instead of an index.

    @Test
    public void theDumpNamesTheThingsYouWouldOtherwiseIndexOn() {
        final String d = full().build().dump();
        assertTrue(d, d.contains(MlsHealthStates.name(MlsHealthStates.ONGOINGERAADVANCEMENT)));
        assertTrue(d, d.contains("ERA_ADVANCEMENT_FOR_PHOENIX_MODE"));
        assertTrue(d, d.contains("pending="));
        assertTrue(d, d.contains(LogMask.number(ME)));
        // It lands in logcat through the debug receivers: every number in it is masked.
        assertFalse(d, d.matches("(?s).*\\+\\d{6,}.*"));
    }

    /** The dump is safe on a bare record. */
    @Test
    public void theDumpSurvivesAnEmptyRecord() {
        assertNotNull(MlsConversationRecord.initial(ME, GID, null, null).dump());
        assertNotNull(MlsConversationRecord.builder().build().dump());
    }
}

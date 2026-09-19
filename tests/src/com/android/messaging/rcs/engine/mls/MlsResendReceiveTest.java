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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RCC.16 §11.3a receiver ordering. The property is an order, so most assertions are about what did
 * not happen: the unwrapper that was never called, the disposition that cannot report. See
 * docs/mls/health-and-recovery.md.
 */
public class MlsResendReceiveTest {

    /** An AAD trailing slot for an ordinary (non-resend) message. */
    private static byte[] absent() {
        return new byte[] { MlsResentMessage.TAG_ABSENT };
    }

    /**
     * A present resent-message component. Its opaque is taken to be an original message id (an
     * inference, cross-checked below), not the 64-byte selector field.
     */
    private static byte[] component(final String originalMessageId) {
        return MlsResentMessage.encode(MlsResentMessage.TAG_RESENT,
                originalMessageId.getBytes(StandardCharsets.US_ASCII),
                MlsResentMessage.PrefixWidth.VARINT);
    }

    /** A present component with a default id. */
    private static byte[] component() { return component("MxaDOv6I6QTwGK2Xj6V-Ubhw"); }

    /** The 64-byte {@code key(32) || tag(32)} selector field that selects {@code target}. */
    private static byte[] selectorFor(final byte[] target) {
        final byte[] key = new byte[MlsResentMessage.HMAC_KEY_LEN];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7 + 1);
        final byte[] tag = MlsResentMessage.mac(key, target);
        final byte[] field = new byte[MlsResentMessage.HMAC_FIELD_LEN];
        System.arraycopy(key, 0, field, 0, key.length);
        System.arraycopy(tag, 0, field, key.length, tag.length);
        return field;
    }

    private static byte[] selectorFor(final String target) {
        return selectorFor(target.getBytes(StandardCharsets.US_ASCII));
    }

    /** A selector field of the wrong size. */
    private static byte[] selectorWithLen(final int len) { return new byte[len]; }

    private static Map<String, byte[]> candidatesFor(final String... names) {
        final Map<String, byte[]> c = new LinkedHashMap<>();
        for (final String n : names) c.put(n, n.getBytes(StandardCharsets.US_ASCII));
        return c;
    }

    /** Records whether, and with what, the inner-unwrap seam was invoked. */
    private static final class RecordingUnwrap implements MlsResendReceive.InnerUnwrap {
        final List<byte[]> calls = new ArrayList<>();
        final byte[] result;

        RecordingUnwrap(final byte[] result) { this.result = result; }

        @Override public byte[] unwrap(final byte[] outerPlaintext, final byte[] hmacField) {
            calls.add(hmacField);
            return result;
        }
    }

    // The ordinary path.

    /** An absent component is an ordinary message. */
    @Test public void absentComponentIsNotAResend() {
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                absent(), "body".getBytes(StandardCharsets.US_ASCII),
                selectorFor("alice"), candidatesFor("alice"), null);
        assertEquals(MlsResendReceive.Disposition.NOT_A_RESEND, o.disposition);
        assertFalse(o.isResend());
        assertTrue(MlsResendReceive.deliver(o.disposition));
        assertFalse(MlsResendReceive.reports(o.disposition));
    }

    /** An AAD that does not parse ({@code aadTrailing} null) is not evidence of a resend. */
    @Test public void unparseableAadIsNotAResend() {
        assertEquals(MlsResendReceive.Disposition.NOT_A_RESEND,
                MlsResendReceive.evaluate(null, new byte[0], selectorFor("alice"),
                        candidatesFor("alice"), null).disposition);
        assertEquals(MlsResendReceive.Disposition.NOT_A_RESEND,
                MlsResendReceive.evaluate(new byte[0], new byte[0], selectorFor("alice"),
                        candidatesFor("alice"), null).disposition);
    }

    // The partition our own evaluate produces.

    /**
     * Over three distinct candidate sets, {@code evaluate} yields one target and two not-for-me,
     * and neither of the two reports. A property of our code over inputs we chose: it says nothing
     * about how peers partition a group, and the last assertion ranges over every member.
     */
    @Test public void ourPartitionYieldsOneTargetAndTwoSilentDrops() {
        final byte[] component = component();
        final byte[] selector = selectorFor("bob");
        int forMe = 0;
        int notForMe = 0;
        for (final String member : new String[] { "alice", "bob", "carol" }) {
            final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                    component, new byte[] { 1, 2, 3 }, selector, candidatesFor(member), null);
            if (o.disposition == MlsResendReceive.Disposition.NOT_FOR_ME) {
                notForMe++;
            } else {
                forMe++;
                assertEquals(member, "bob");
            }
            // For every member: nothing this resend does puts a receipt on the wire.
            assertFalse(MlsResendReceive.reports(o.disposition));
        }
        assertEquals(1, forMe);
        assertEquals(2, notForMe);
    }

    /** A mismatch is {@code NOT_FOR_ME}: a silent drop, never delivered, never reported. */
    @Test public void aMacMismatchIsNotForMeAndIsSilent() {
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(), new byte[] { 9 }, selectorFor("bob"), candidatesFor("alice"), null);
        assertEquals(MlsResendReceive.Disposition.NOT_FOR_ME, o.disposition);
        assertTrue(MlsResendReceive.silentDrop(o.disposition));
        assertFalse(MlsResendReceive.deliver(o.disposition));
        assertFalse(MlsResendReceive.reports(o.disposition));
        assertNull(o.matchedCandidate);
        assertTrue(o.isResend());
    }

    /** No candidates at all is the same silent drop. */
    @Test public void noCandidatesIsStillNotForMe() {
        assertEquals(MlsResendReceive.Disposition.NOT_FOR_ME,
                MlsResendReceive.evaluate(component(), new byte[] { 9 }, selectorFor("bob"),
                        null, null).disposition);
    }

    // The ordering itself.

    /**
     * The unwrap is never reached for a member the resend is not addressed to; unwrapping first, or
     * letting a failed unwrap speak for a non-target, would produce a receipt from every member.
     */
    @Test public void theUnwrapIsNeverReachedWhenTheMacDoesNotMatch() {
        final RecordingUnwrap unwrap = new RecordingUnwrap(/*result=*/ null);
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(), new byte[] { 9 }, selectorFor("bob"), candidatesFor("alice"), unwrap);
        assertEquals(MlsResendReceive.Disposition.NOT_FOR_ME, o.disposition);
        assertEquals("the unwrap must not run for a non-target member", 0, unwrap.calls.size());
        assertFalse(MlsResendReceive.reports(o.disposition));
    }

    /**
     * A wrong-length HMAC field is caught before the MAC and the unwrap: the field is fixed at
     * 0x40, so 63 or 65 bytes is {@code InvalidResentMessageHmacLength}, not a mismatch.
     */
    @Test public void aWrongLengthHmacIsCaughtBeforeTheMacAndBeforeTheUnwrap() {
        for (final int len : new int[] { 1, 32, 63, 65, 128 }) {
            final RecordingUnwrap unwrap = new RecordingUnwrap(new byte[] { 42 });
            final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                    component(), new byte[] { 9 }, selectorWithLen(len), candidatesFor("alice"),
                    unwrap);
            assertEquals("len=" + len, MlsResendReceive.Disposition.INVALID_HMAC_LENGTH,
                    o.disposition);
            assertEquals("len=" + len, 0, unwrap.calls.size());
            assertTrue("len=" + len, MlsResendReceive.silentDrop(o.disposition));
            assertFalse("len=" + len, MlsResendReceive.reports(o.disposition));
        }
    }

    /** A present tag with an undecodable payload is MALFORMED: structural, and still silent. */
    @Test public void aPresentTagWithNoDecodablePayloadIsMalformed() {
        final RecordingUnwrap unwrap = new RecordingUnwrap(new byte[] { 42 });
        // Tag present, then a u8/u16/varint prefix that cannot consume the buffer exactly.
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                new byte[] { (byte) MlsResentMessage.TAG_RESENT, (byte) 0xFF, 0x01 },
                new byte[] { 9 }, selectorFor("alice"), candidatesFor("alice"), unwrap);
        assertEquals(MlsResendReceive.Disposition.MALFORMED_COMPONENT, o.disposition);
        assertEquals(0, unwrap.calls.size());
        assertTrue(MlsResendReceive.silentDrop(o.disposition));
        assertFalse(MlsResendReceive.reports(o.disposition));
    }

    /** A present tag with an empty HMAC field has its own outcome. */
    @Test public void anEmptyHmacFieldIsItsOwnOutcome() {
        final RecordingUnwrap unwrap = new RecordingUnwrap(new byte[] { 42 });
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(), new byte[] { 9 }, selectorWithLen(0), candidatesFor("alice"), unwrap);
        assertEquals(MlsResendReceive.Disposition.EMPTY_HMAC, o.disposition);
        assertEquals(0, unwrap.calls.size());
        assertTrue(MlsResendReceive.silentDrop(o.disposition));
        assertFalse(MlsResendReceive.reports(o.disposition));
    }

    // Seam 1: where the selector field lives.

    /**
     * With no selector field, a present component is our gap: silent, and says nothing about the
     * sender. This is the production state ({@code resentSelectorField} returns null).
     */
    @Test public void aPresentComponentWithNoSelectorFieldIsOurGap() {
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(), new byte[] { 9 }, /*selectorField=*/ null, candidatesFor("bob"), null);
        assertEquals(MlsResendReceive.Disposition.SELECTOR_UNAVAILABLE, o.disposition);
        assertTrue(o.isResend());
        assertTrue(MlsResendReceive.silentDrop(o.disposition));
        assertFalse(MlsResendReceive.deliver(o.disposition));
        assertFalse(MlsResendReceive.reports(o.disposition));
        assertNull(o.matchedCandidate);
        // The component itself is still handed back.
        assertNotNull(o.component);
        assertTrue(o.component.present());
    }

    /**
     * The component's opaque is never used as the selector field, even when it is a valid 64-byte
     * field that would match; both readings type-check and stay silent, so only this test tells
     * them apart.
     */
    @Test public void theComponentOpaqueIsNeverUsedAsTheSelectorField() {
        final byte[] componentWhoseOpaqueLooksLikeAField = MlsResentMessage.encode(
                MlsResentMessage.TAG_RESENT, selectorFor("bob"),
                MlsResentMessage.PrefixWidth.VARINT);
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                componentWhoseOpaqueLooksLikeAField, new byte[] { 9 }, /*selectorField=*/ null,
                candidatesFor("bob"), new RecordingUnwrap(new byte[] { 42 }));
        assertEquals(MlsResendReceive.Disposition.SELECTOR_UNAVAILABLE, o.disposition);
    }

    /**
     * An unwrapper alone changes nothing while seam 1 is open: every component stops at the
     * selector gap.
     */
    @Test public void anUnwrapperAloneDeliversNothingWhileTheSelectorIsUnknown() {
        final RecordingUnwrap unwrap = new RecordingUnwrap("the original".getBytes(
                StandardCharsets.US_ASCII));
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(), new byte[] { 9 }, /*selectorField=*/ null, candidatesFor("bob"),
                unwrap);
        assertEquals(MlsResendReceive.Disposition.SELECTOR_UNAVAILABLE, o.disposition);
        assertEquals(0, unwrap.calls.size());
        assertNull(o.inner);
    }

    /** The structural parse still runs first: an undecodable component is malformed, not a gap. */
    @Test public void aMalformedComponentIsStillMalformedWithNoSelectorField() {
        assertEquals(MlsResendReceive.Disposition.MALFORMED_COMPONENT,
                MlsResendReceive.evaluate(
                        new byte[] { (byte) MlsResentMessage.TAG_RESENT, (byte) 0xFF, 0x01 },
                        new byte[] { 9 }, /*selectorField=*/ null, candidatesFor("bob"), null)
                                .disposition);
    }

    // The Original-Message-ID cross-check.

    /** Both present and equal: the "opaque is the original message id" inference survives. */
    @Test public void theCrossCheckAgreesWhenTheOpaqueIsTheHeaderId() {
        final String id = "MxaDOv6I6QTwGK2Xj6V-Ubhw";
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(id), new byte[] { 9 }, null, candidatesFor("bob"), null);
        assertEquals(MlsResendReceive.IdCrossCheck.AGREE,
                MlsResendReceive.crossCheckOriginalMessageId(o, id));
    }

    /** Both present and different: the inference is wrong. */
    @Test public void theCrossCheckDisagreesOnADifferentId() {
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component("MxaDOv6I6QTwGK2Xj6V-Ubhw"), new byte[] { 9 }, null,
                candidatesFor("bob"), null);
        assertEquals(MlsResendReceive.IdCrossCheck.DISAGREE,
                MlsResendReceive.crossCheckOriginalMessageId(o, "MxHjYubZBiQYuA9DqODpyQiw"));
    }

    /** A non-text opaque refutes the inference outright, and is its own answer. */
    @Test public void aBinaryOpaqueIsNotAMismatch() {
        final byte[] binary = MlsResentMessage.encode(MlsResentMessage.TAG_RESENT,
                new byte[] { 0x00, 0x01, (byte) 0xFF }, MlsResentMessage.PrefixWidth.VARINT);
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                binary, new byte[] { 9 }, null, candidatesFor("bob"), null);
        assertEquals(MlsResendReceive.IdCrossCheck.PAYLOAD_NOT_TEXT,
                MlsResendReceive.crossCheckOriginalMessageId(o, "MxaDOv6I6QTwGK2Xj6V-Ubhw"));
    }

    /** "Nothing to compare" is neither "agree" nor "disagree": three distinct answers. */
    @Test public void nothingToCompareIsItsOwnAnswer() {
        final MlsResendReceive.Outcome ordinary = MlsResendReceive.evaluate(
                absent(), new byte[] { 9 }, null, candidatesFor("bob"), null);
        assertEquals(MlsResendReceive.IdCrossCheck.NO_COMPONENT,
                MlsResendReceive.crossCheckOriginalMessageId(ordinary, "MxaDOv6I6QTwGK2Xj6V-Ubhw"));

        final MlsResendReceive.Outcome resend = MlsResendReceive.evaluate(
                component(), new byte[] { 9 }, null, candidatesFor("bob"), null);
        assertEquals(MlsResendReceive.IdCrossCheck.NO_HEADER,
                MlsResendReceive.crossCheckOriginalMessageId(resend, null));
        assertEquals(MlsResendReceive.IdCrossCheck.NO_HEADER,
                MlsResendReceive.crossCheckOriginalMessageId(resend, ""));
        assertEquals(MlsResendReceive.IdCrossCheck.NO_COMPONENT,
                MlsResendReceive.crossCheckOriginalMessageId(null, "x"));
    }

    /**
     * The cross-check cannot fire on ordinary traffic: both the component and the
     * {@code Original-Message-ID} header are resend-only, so ordinary messages give
     * {@link MlsResendReceive.IdCrossCheck#NO_COMPONENT} even with a header supplied. The inputs
     * are independent (the header is parsed by the provider, the opaque by the app from the AAD).
     */
    @Test public void ordinaryReferenceClientTrafficCannotExerciseTheCrossCheck() {
        // Peers' AADs, all with an absent trailing component.
        final String[] capturedAads = {
            "0001184d78747a37584f4b2d455471656e6b3431707244796e41510000000800",
            "0001184d784c49493361625a36546d65355453744e5866357133770000000800",
            "0001184d7861444f76364936515477474b32586a36562d556268770000000600",
            "0001184d78486a5975625a4269515975413944714f4470795169770000000700",
            "0001184d78434143594778716651612d436e515a3d3558637475510000000800",
        };
        for (final String h : capturedAads) {
            final byte[] aad = new byte[h.length() / 2];
            for (int i = 0; i < aad.length; i++) {
                aad[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            }
            final byte[] tail = MlsAppMessage.aadTrailing(aad);
            assertNotNull("the peer's AAD must parse: " + h, tail);
            assertArrayEquals("a peer's ordinary trailing slot is the ABSENT component",
                    new byte[] { MlsResentMessage.TAG_ABSENT }, tail);

            final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                    tail, new byte[] { 9 }, selectorFor("alice"), candidatesFor("alice"), null);
            assertFalse("an ordinary message is not a resend", o.isResend());
            assertEquals("even WITH a header, an absent component closes the instrument",
                    MlsResendReceive.IdCrossCheck.NO_COMPONENT,
                    MlsResendReceive.crossCheckOriginalMessageId(o, "MxaDOv6I6QTwGK2Xj6V-Ubhw"));
        }
    }

    /** The log rendering shows text as text and bytes as hex. */
    @Test public void theComponentPayloadRendersForALogLine() {
        final MlsResendReceive.Outcome text = MlsResendReceive.evaluate(
                component("MxaDOv6I6QTwGK2Xj6V-Ubhw"), new byte[] { 9 }, null, null, null);
        assertEquals("'MxaDOv6I6QTwGK2Xj6V-Ubhw'",
                MlsResendReceive.componentPayloadForLog(text));

        final MlsResendReceive.Outcome binary = MlsResendReceive.evaluate(
                MlsResentMessage.encode(MlsResentMessage.TAG_RESENT,
                        new byte[] { 0x00, (byte) 0xAB }, MlsResentMessage.PrefixWidth.VARINT),
                new byte[] { 9 }, null, null, null);
        assertEquals("0x00ab", MlsResendReceive.componentPayloadForLog(binary));
        assertEquals("<none>", MlsResendReceive.componentPayloadForLog(null));
    }

    // The inner-unwrap seam.

    /**
     * With no unwrapper, a resend addressed to us is dropped silently and the matched candidate is
     * still named; reporting failed-to-decrypt would tell a healthy sender its resend is broken.
     */
    @Test public void forMeWithNoUnwrapperIsSilentButNamesTheCandidate() {
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(), new byte[] { 9 }, selectorFor("bob"),
                candidatesFor("alice", "bob", "carol"), /*unwrap=*/ null);
        assertEquals(MlsResendReceive.Disposition.FOR_ME_UNWRAP_UNAVAILABLE, o.disposition);
        assertEquals("bob", o.matchedCandidate);
        assertTrue(MlsResendReceive.silentDrop(o.disposition));
        assertFalse(MlsResendReceive.reports(o.disposition));
        assertFalse(MlsResendReceive.deliver(o.disposition));
        assertNull(o.inner);
    }

    /** Only an installed unwrapper that fails makes the one reporting arm reachable. */
    @Test public void forMeWithAFailingUnwrapperIsTheOnlyThingThatReports() {
        final RecordingUnwrap unwrap = new RecordingUnwrap(/*result=*/ null);
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(), new byte[] { 9 }, selectorFor("bob"), candidatesFor("bob"), unwrap);
        assertEquals(MlsResendReceive.Disposition.FOR_ME_UNWRAP_FAILED, o.disposition);
        assertEquals(1, unwrap.calls.size());
        assertTrue(MlsResendReceive.reports(o.disposition));
        assertFalse(MlsResendReceive.silentDrop(o.disposition));
        assertFalse(MlsResendReceive.deliver(o.disposition));
    }

    /** A working unwrapper delivers the original, not the wrapper. */
    @Test public void forMeWithAWorkingUnwrapperDeliversTheInner() {
        final byte[] original = "the original message".getBytes(StandardCharsets.US_ASCII);
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(), "the WRAPPER".getBytes(StandardCharsets.US_ASCII), selectorFor("bob"),
                candidatesFor("bob"), new RecordingUnwrap(original));
        assertEquals(MlsResendReceive.Disposition.FOR_ME, o.disposition);
        assertTrue(MlsResendReceive.deliver(o.disposition));
        assertFalse(MlsResendReceive.reports(o.disposition));
        assertArrayEquals(original, o.inner);
    }

    /** The seam is handed the full 64-byte field, key and tag. */
    @Test public void theSeamReceivesTheWholeHmacField() {
        final RecordingUnwrap unwrap = new RecordingUnwrap(new byte[] { 1 });
        MlsResendReceive.evaluate(component(), new byte[] { 9 }, selectorFor("bob"),
                candidatesFor("bob"), unwrap);
        assertEquals(1, unwrap.calls.size());
        assertEquals(MlsResentMessage.HMAC_FIELD_LEN, unwrap.calls.get(0).length);
    }

    /** A throwing unwrapper is a failed unwrap, not a crashed receive thread. */
    @Test public void aThrowingUnwrapperIsAFailedUnwrap() {
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(), new byte[] { 9 }, selectorFor("bob"), candidatesFor("bob"),
                new MlsResendReceive.InnerUnwrap() {
                    @Override public byte[] unwrap(final byte[] p, final byte[] f) {
                        throw new IllegalStateException("decoder is not finished");
                    }
                });
        assertEquals(MlsResendReceive.Disposition.FOR_ME_UNWRAP_FAILED, o.disposition);
    }

    // The disposition table.

    /** Exactly one disposition may report, pinned as a count so a second reporting one fails. */
    @Test public void exactlyOneDispositionMayEverReport() {
        int reporting = 0;
        for (final MlsResendReceive.Disposition d : MlsResendReceive.Disposition.values()) {
            if (MlsResendReceive.reports(d)) reporting++;
        }
        assertEquals(1, reporting);
        assertTrue(MlsResendReceive.reports(
                MlsResendReceive.Disposition.FOR_ME_UNWRAP_FAILED));
    }

    /**
     * The three predicates partition the enum: a disposition in none would be delivered by
     * accident, one in two would take whichever arm came first.
     */
    @Test public void theThreePredicatesPartitionTheEnum() {
        for (final MlsResendReceive.Disposition d : MlsResendReceive.Disposition.values()) {
            int in = 0;
            if (MlsResendReceive.deliver(d)) in++;
            if (MlsResendReceive.silentDrop(d)) in++;
            if (MlsResendReceive.reports(d)) in++;
            assertEquals(d + " must be in exactly one of deliver/silentDrop/reports", 1, in);
        }
    }

    /** Nothing but a resend reaches a resend-specific arm. */
    @Test public void anOrdinaryMessageIsNeverSilentDroppedOrReported() {
        assertFalse(MlsResendReceive.silentDrop(MlsResendReceive.Disposition.NOT_A_RESEND));
        assertFalse(MlsResendReceive.reports(MlsResendReceive.Disposition.NOT_A_RESEND));
        assertTrue(MlsResendReceive.deliver(MlsResendReceive.Disposition.NOT_A_RESEND));
    }

    // The markers a log scan reads.

    /** The not-for-me line carries the original message id, in other clients' wording. */
    @Test public void theNotForMeLineNamesTheOriginalMessageId() {
        final String line = MlsResendReceive.notForMeLine("mls-abc-1", "group-42");
        assertTrue(line.startsWith("Resent message not for me with original message id mls-abc-1"));
        assertTrue(line.contains("fails HMAC verification, for group: group-42"));
        // Nulls do not produce "null" in a grepped line.
        assertFalse(MlsResendReceive.notForMeLine(null, null).contains("null"));
    }

    /** The scan's needles are the emitter's constants: non-empty and distinct. */
    @Test public void theMarkersAreUsableNeedles() {
        final String[] all = {
                MlsResendReceive.MARKER_DISAGREEMENT,
                MlsResendReceive.MARKER_MALFORMED,
                MlsResendReceive.MARKER_FOR_ME,
                MlsResendReceive.MARKER_SELECTOR_UNAVAILABLE,
        };
        for (final String n : all) {
            assertNotNull(n);
            assertTrue(n.length() > 8);
        }
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertFalse(all[i] + " must not contain " + all[j], all[i].contains(all[j]));
                assertFalse(all[j] + " must not contain " + all[i], all[j].contains(all[i]));
            }
        }
    }

    /** Every marker is in the invariant catalogue. */
    @Test public void everyMarkerIsInTheInvariantCatalogue() {
        for (final String n : new String[] {
                MlsResendReceive.MARKER_DISAGREEMENT,
                MlsResendReceive.MARKER_MALFORMED,
                MlsResendReceive.MARKER_FOR_ME,
                MlsResendReceive.MARKER_SELECTOR_UNAVAILABLE }) {
            boolean found = false;
            for (final MlsInvariantScan.Marker m : MlsInvariantScan.markers()) {
                if (n.equals(m.needle)) { found = true; break; }
            }
            assertTrue("MlsInvariantScan has no marker for '" + n + "'", found);
        }
    }

    // The drop marker's round trip through the rendezvous store.

    /**
     * RESEND_NOT_FOR_ME survives framing and re-parsing and still classifies as a drop: on
     * redelivery the rendezvous row is answered from the stored bytes alone. {@code frame()}
     * appends {@code ;charset=UTF-8} and {@code classify()} uses {@code equals}; {@code parse()}
     * strips the parameter.
     */
    @Test public void theNotForMeMarkerRoundTripsThroughTheBodyFrame() {
        final byte[] framed = com.android.messaging.rcs.engine.mls.RccMlsBody.frame(
                new byte[0], RccContentDisposition.RESEND_NOT_FOR_ME, /*inline=*/ true);
        final com.android.messaging.rcs.engine.mls.RccMlsBody.Parsed back =
                com.android.messaging.rcs.engine.mls.RccMlsBody.parse(framed);
        assertEquals(RccContentDisposition.RESEND_NOT_FOR_ME, back.contentType);
        assertEquals(0, back.body.length);
        assertEquals(RccContentDisposition.DROP_CONTROL,
                RccContentDisposition.classify(back.contentType));
        assertTrue(RccContentDisposition.isDrop(
                RccContentDisposition.classify(back.contentType)));
    }

    /**
     * The marker is internal and never renders, or a dropped resend would appear as an empty
     * bubble.
     */
    @Test public void theNotForMeMarkerIsNeverRendered() {
        assertFalse(RccContentDisposition.classify(RccContentDisposition.RESEND_NOT_FOR_ME)
                == RccContentDisposition.TEXT);
    }

    /**
     * The old log line is a NEVER marker: it means a build that routes every group resend into
     * recovery.
     */
    @Test public void thePreFixGuardStringIsANeverMarker() {
        MlsInvariantScan.Marker found = null;
        for (final MlsInvariantScan.Marker m : MlsInvariantScan.markers()) {
            if (m.needle.contains("We do not implement the ResentMessage unwrap yet")) found = m;
        }
        assertNotNull("the pre-fix resend guard must be a scanned NEVER", found);
        assertEquals(MlsInvariantScan.Severity.NEVER, found.severity);
    }

    // The three-way id capture.

    /**
     * The three candidates are labelled, never printed bare: each could be called "the original
     * message id".
     */
    @Test
    public void theIdCandidateLineLabelsAllThreeAndNamesTheLayer() {
        final String line = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxCPIMheaderid0000000000",
                "MxAADmessageid0000000000");
        assertNotNull(line);
        assertTrue(line.startsWith(MlsResendReceive.MARKER_ID_CANDIDATES));
        assertTrue(line.contains("aad.message_id=MxAADmessageid0000000000"));
        assertTrue(line.contains("cpim.Original-Message-ID=MxCPIMheaderid0000000000"));
        assertTrue(line.contains("component.opaque=<absent>"));
    }

    /** A missing candidate reads N/A, never AGREE. */
    @Test
    public void anAbsentCandidateIsNotAnAgreement() {
        final String line = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, null, "MxAADmessageid0000000000");
        assertNotNull(line);
        assertTrue("absent cpim must be N/A, not AGREE", line.contains("aad-vs-cpim=N/A"));
        assertFalse(line.contains("aad-vs-cpim=AGREE"));
    }

    /** Equal values report AGREE; different values report DIFFER. */
    @Test
    public void equalAndDifferingCandidatesAreReportedDistinctly() {
        final String same = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxSame000000000000000000",
                "MxSame000000000000000000");
        assertTrue(same.contains("aad-vs-cpim=AGREE"));
        final String diff = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxOne0000000000000000000",
                "MxTwo0000000000000000000");
        assertTrue(diff.contains("aad-vs-cpim=DIFFER"));
    }

    /** Nothing anywhere: no line rather than three absent values. */
    @Test
    public void noCandidatesAnywhereProducesNoLine() {
        assertNull(MlsResendReceive.idCandidateLine((MlsResendReceive.Outcome) null, null, null));
        assertNull(MlsResendReceive.idCandidateLine((MlsResendReceive.Outcome) null, "", ""));
    }

    /** Field A is not logged: it is inside a component body we cannot yet parse correctly. */
    @Test
    public void fieldAIsNotLoggedBecauseWeCannotParseTheBody() {
        final String line = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxCPIMheaderid0000000000",
                "MxAADmessageid0000000000");
        assertFalse("field A must not appear — the body is unparseable", line.contains("fieldA="));
        assertFalse(line.contains("selector="));
        assertTrue("and the line must SAY why", line.contains("Field A not shown"));
    }

    /**
     * The line states that (1) may hold the resend's own id; on ordinary traffic the fields agree
     * under both hypotheses.
     */
    @Test
    public void theLineWarnsThatOrdinaryAgreementProvesNothing() {
        final String line = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxSame000000000000000000",
                "MxSame000000000000000000");
        assertTrue(line.contains("THREE LAYERS"));
        assertTrue(line.contains("RESEND's own id"));
    }

    /**
     * The line names which pair discriminates: only cpim-vs-component is unambiguous, because the
     * CPIM header carries the original.
     */
    @Test
    public void theLineNamesWhichPairActuallyDiscriminates() {
        final String line = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxOne0000000000000000000",
                "MxTwo0000000000000000000");
        assertTrue(line.contains("TWO readings"));
        assertTrue(line.contains("cpim-vs-component is the discriminating pair"));
    }

    /**
     * The not-for-me line says it follows an HMAC mismatch; on well-formed traffic it is
     * unreachable, since every member's HMAC verifies.
     */
    @Test
    public void theBenignLineSaysItIsDownstreamOfAnHmacMismatch() {
        final String line = MlsResendReceive.notForMeLine("MxOrig00000000000000000", "g1");
        assertTrue(line.contains("downstream of an HMAC MISMATCH"));
        assertTrue("and must not claim field A selects", line.contains("does NOT select"));
    }
}

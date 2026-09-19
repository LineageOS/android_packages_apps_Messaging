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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RCC.16 §11.3a receiver ordering.
 *
 * <p>These tests exist because the property under test is an ORDER, and an order cannot be checked
 * by reading the code that implements it: the defect this class was extracted to fix was two blocks
 * that each read as careful, twenty lines apart, in the wrong sequence. So the assertions here are
 * mostly about what did NOT happen — the unwrapper that was never called, the disposition that
 * cannot report — rather than about return values.
 */
public class MlsResendReceiveTest {

    /** An AAD trailing slot for an ordinary (non-resend) message. */
    private static byte[] absent() {
        return new byte[] { MlsResentMessage.TAG_ABSENT };
    }

    /**
     * A present resent-message component.
     *
     * <p>Its opaque is an ORIGINAL MESSAGE ID, which is the labelled inference from Google Messages'
     * engine (the opaque is handed to a lookup whose failure logs "Failed to get message content
     * for message"). <b>It is deliberately NOT the 64-byte selector field</b> — that binding was
     * this class's original mistake, and building the fixture the corrected way is what stops the
     * mistake being reintroduced by a fixture nobody reads.
     */
    private static byte[] component(final String originalMessageId) {
        return MlsResentMessage.encode(MlsResentMessage.TAG_RESENT,
                originalMessageId.getBytes(StandardCharsets.US_ASCII),
                MlsResentMessage.PrefixWidth.VARINT);
    }

    /** A present component with a default id, for tests that do not care which. */
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

    /** Records whether — and with what — the inner-unwrap seam was invoked. */
    private static final class RecordingUnwrap implements MlsResendReceive.InnerUnwrap {
        final List<byte[]> calls = new ArrayList<>();
        final byte[] result;

        RecordingUnwrap(final byte[] result) { this.result = result; }

        @Override public byte[] unwrap(final byte[] outerPlaintext, final byte[] hmacField) {
            calls.add(hmacField);
            return result;
        }
    }

    // ------------------------------------------------------------------
    // The ordinary path
    // ------------------------------------------------------------------

    /** An absent component is an ORDINARY message. This is every message we have ever seen. */
    @Test public void absentComponentIsNotAResend() {
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                absent(), "body".getBytes(StandardCharsets.US_ASCII),
                selectorFor("alice"), candidatesFor("alice"), null);
        assertEquals(MlsResendReceive.Disposition.NOT_A_RESEND, o.disposition);
        assertFalse(o.isResend());
        assertTrue(MlsResendReceive.deliver(o.disposition));
        assertFalse(MlsResendReceive.reports(o.disposition));
    }

    /**
     * An AAD we could not parse at all is NOT evidence of a resend.
     *
     * <p>{@code aadTrailing} returns null on an AAD that does not decode, and inventing a resend out
     * of that would route a healthy message into the resend path on the strength of a parse failure.
     */
    @Test public void unparseableAadIsNotAResend() {
        assertEquals(MlsResendReceive.Disposition.NOT_A_RESEND,
                MlsResendReceive.evaluate(null, new byte[0], selectorFor("alice"),
                        candidatesFor("alice"), null).disposition);
        assertEquals(MlsResendReceive.Disposition.NOT_A_RESEND,
                MlsResendReceive.evaluate(new byte[0], new byte[0], selectorFor("alice"),
                        candidatesFor("alice"), null).disposition);
    }

    // ------------------------------------------------------------------
    // THE PARTITION OUR OWN EVALUATE PRODUCES — and what it is NOT evidence of
    // ------------------------------------------------------------------

    /**
     * Over three DISTINCT candidate sets, {@code evaluate} yields exactly ONE target and TWO
     * not-for-me — and neither of the two may report anything.
     *
     * <p><b>This heading used to read "THE N-1 RULE — the reason this exists", and the javadoc
     * claimed corroboration that no longer exists</b>: the count and the
     * mechanism were both withdrawn on 2026-09-10. It said the
     * check "arrived from two directions at once", ours and a reading of Google Messages. It did not: on
     * Google Messages the MAC'd datum is recipient-invariant and the key travels in the clear, so every
     * member's MAC passes and this branch never partitions anybody. Nobody has ever counted those
     * log lines on a real group; the falsifier is unrun.
     *
     * <p><b>What is asserted here is a property of OUR code over inputs WE chose</b>, and that is
     * worth having on its own: it is what stops a re-implementation from turning a non-target into
     * a receipt. The last assertion in the loop is the load-bearing one and it ranges over EVERY
     * member, so it holds whatever the partition turns out to be.
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
            // THE INVARIANT, asserted for EVERY member: nothing this resend does to any of them may
            // put a receipt on the wire. One resend, at most one receipt, and here zero.
            assertFalse(MlsResendReceive.reports(o.disposition));
        }
        assertEquals(1, forMe);
        assertEquals(2, notForMe);
    }

    /**
     * A mismatch is {@code NOT_FOR_ME} and is a SILENT DROP — never delivered, never reported.
     *
     * <p>Renamed from {@code aMacMismatchIsASelectorNotAnError}: a test NAME is
     * an assertion with no code behind it, and that one asserted the selector model this file's
     * subject withdrew. The body never tested it — it tests the disposition,
     * which is unchanged.
     */
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

    /** No candidates at all is still benign — the same silent drop, not a failure. */
    @Test public void noCandidatesIsStillNotForMe() {
        assertEquals(MlsResendReceive.Disposition.NOT_FOR_ME,
                MlsResendReceive.evaluate(component(), new byte[] { 9 }, selectorFor("bob"),
                        null, null).disposition);
    }

    // ------------------------------------------------------------------
    // THE ORDERING ITSELF
    // ------------------------------------------------------------------

    /**
     * <b>The unwrap is NEVER reached for a member the resend is not addressed to.</b>
     *
     * <p>This is the ordering assertion: evaluating the MAC after attempting the unwrap, or letting
     * a failed unwrap speak for a non-target member, produces a receipt from every member of the
     * group. A recording unwrapper that would have reported failure proves the step never ran.
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
     * A WRONG-LENGTH HMAC field is caught before the MAC is computed, and before the unwrap.
     *
     * <p>Google Messages' ordering puts the length gate at step 3, ahead of the selector — and the field is
     * hard-coded 0x40, so a 63- or 65-byte field is its own condition (105
     * {@code InvalidResentMessageHmacLength}) rather than a mismatch.
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

    /** A present tag with an undecodable payload is MALFORMED — structural, and still silent. */
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

    /** A present tag with an EMPTY HMAC field takes Google Messages' own separate earlier path. */
    @Test public void anEmptyHmacFieldIsItsOwnOutcome() {
        final RecordingUnwrap unwrap = new RecordingUnwrap(new byte[] { 42 });
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(), new byte[] { 9 }, selectorWithLen(0), candidatesFor("alice"), unwrap);
        assertEquals(MlsResendReceive.Disposition.EMPTY_HMAC, o.disposition);
        assertEquals(0, unwrap.calls.size());
        assertTrue(MlsResendReceive.silentDrop(o.disposition));
        assertFalse(MlsResendReceive.reports(o.disposition));
    }

    // ------------------------------------------------------------------
    // SEAM 1 — WHERE THE SELECTOR FIELD LIVES
    // ------------------------------------------------------------------

    /**
     * With no selector field, a present component is <b>our gap</b> — silent, and it says nothing
     * about the sender.
     *
     * <p>This is the production state: {@code MlsProviderTransport.resentSelectorField} returns
     * null. Before 2026-09-08 the call site passed the AAD component's opaque here instead, so a
     * Google Messages 24-byte opaque came back {@code INVALID_HMAC_LENGTH} and was logged as the sender's
     * resend being "structurally broken" — a claim we had never measured.
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
        // The component itself is still handed back — those bytes are the artifact.
        assertNotNull(o.component);
        assertTrue(o.component.present());
    }

    /**
     * <b>THE REGRESSION TEST.</b> The component's opaque is never used as the selector field, even
     * when it is exactly the right size and would have matched.
     *
     * <p>Constructed adversarially: the opaque here IS a valid 64-byte field selecting "bob". If
     * anything ever wires {@code component.payload} back into the selector, this test goes
     * {@code FOR_ME_UNWRAP_UNAVAILABLE} instead of {@code SELECTOR_UNAVAILABLE} and fails. That is
     * the only mechanical guard against reintroducing the binding, because both readings type-check
     * and both stay silent on the wire.
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
     * An unwrapper alone changes nothing while seam 1 is open — the two seams are ORDERED.
     *
     * <p>Worth pinning because the obvious next move is "implement the unwrap and the
     * resend path lights up". It does not: every present component stops at the selector gap first,
     * and a recording unwrapper proves the step is never reached.
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

    /** The STRUCTURAL parse still runs first: a component we cannot decode is malformed, not a gap. */
    @Test public void aMalformedComponentIsStillMalformedWithNoSelectorField() {
        assertEquals(MlsResendReceive.Disposition.MALFORMED_COMPONENT,
                MlsResendReceive.evaluate(
                        new byte[] { (byte) MlsResentMessage.TAG_RESENT, (byte) 0xFF, 0x01 },
                        new byte[] { 9 }, /*selectorField=*/ null, candidatesFor("bob"), null)
                                .disposition);
    }

    // ------------------------------------------------------------------
    // The Original-Message-ID cross-check — the test of the one inference
    // ------------------------------------------------------------------

    /** Both present and equal: the "opaque IS the original message id" inference survives. */
    @Test public void theCrossCheckAgreesWhenTheOpaqueIsTheHeaderId() {
        final String id = "MxaDOv6I6QTwGK2Xj6V-Ubhw";
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component(id), new byte[] { 9 }, null, candidatesFor("bob"), null);
        assertEquals(MlsResendReceive.IdCrossCheck.AGREE,
                MlsResendReceive.crossCheckOriginalMessageId(o, id));
    }

    /** Both present and different: the inference is WRONG, and that is the finding. */
    @Test public void theCrossCheckDisagreesOnADifferentId() {
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                component("MxaDOv6I6QTwGK2Xj6V-Ubhw"), new byte[] { 9 }, null,
                candidatesFor("bob"), null);
        assertEquals(MlsResendReceive.IdCrossCheck.DISAGREE,
                MlsResendReceive.crossCheckOriginalMessageId(o, "MxHjYubZBiQYuA9DqODpyQiw"));
    }

    /** A non-text opaque refutes the inference outright, and is its OWN answer, not a mismatch. */
    @Test public void aBinaryOpaqueIsNotAMismatch() {
        final byte[] binary = MlsResentMessage.encode(MlsResentMessage.TAG_RESENT,
                new byte[] { 0x00, 0x01, (byte) 0xFF }, MlsResentMessage.PrefixWidth.VARINT);
        final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                binary, new byte[] { 9 }, null, candidatesFor("bob"), null);
        assertEquals(MlsResendReceive.IdCrossCheck.PAYLOAD_NOT_TEXT,
                MlsResendReceive.crossCheckOriginalMessageId(o, "MxaDOv6I6QTwGK2Xj6V-Ubhw"));
    }

    /**
     * "Nothing to compare" is NOT "they agree" and NOT "they disagree" — three distinct answers.
     *
     * <p>The house rule this pins: a reader that refuses a case must not share a return value with
     * one that measured a case. A boolean here would make every ordinary message look like an
     * agreement and every missing header look like a match.
     */
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
     * <b>THE CROSS-CHECK CANNOT FIRE ON ORDINARY TRAFFIC. Measured 2026-09-10, not argued.</b>
     *
     * <p>This test exists to kill a premise that was written down and read as established:
     * that because the component's opaque and the outer {@code Original-Message-ID} header are
     * INDEPENDENT statements, an equality between them could be obtained "from ordinary inbound
     * traffic rather than from a resend". The independence half is true — the header is parsed by
     * the PROVIDER process off the inbound CPIM triples ({@code TachyonRegistrar.parseMlsHeaders})
     * and the opaque by the APP process off {@code MLS authenticated_data} handed up from the Rust
     * core ({@code OpenMlsNative.nativeLastAad}), so the comparison is not circular. <b>The
     * obtainable half is false</b>: BOTH inputs are resend-only, so ordinary traffic supplies
     * neither and {@link MlsResendReceive#crossCheckOriginalMessageId} returns
     * {@link MlsResendReceive.IdCrossCheck#NO_COMPONENT} on every message, forever.
     *
     * <p><b>THE CENSUS.</b> Every cached inbound {@code message/mls} payload on {@code 010T} was
     * decoded — 4,925 MLS application messages, 16 distinct {@code group_id}s, 2026-07-23 to
     * 2026-09-08, spanning Google Messages and two Apple/iPhone groups. The AAD
     * trailing slot was {@code 0x00} on <b>4,925 of 4,925</b>. Not one present-form component, and
     * no AAD anomalies. Two live samples the same day carried
     * {@code Original-Message-ID=ABSENT} in the same breath.
     *
     * <p>So the instrument is WIRED and CORRECT and has never had an input. That is not a defect in
     * it — see its javadoc, which says "the first time a real resend arrives" and claims nothing
     * more. It means the resend artifact is still the blocker, and no amount of ordinary traffic
     * substitutes for it.
     *
     * <p>The header argument below is deliberately non-empty: supplying one and STILL getting
     * {@code NO_COMPONENT} is the whole point, because it shows the absent component alone closes
     * the instrument.
     */
    @Test public void ordinaryReferenceClientTrafficCannotExerciseTheCrossCheck() {
        // Real Google Messages AADs. The first two were read live off MLS-AAD-DUMP on a device while the
        // messages were being decrypted; the last three are the 2026-08-21 preserved artifacts.
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
            assertNotNull("Google Messages' AAD must parse: " + h, tail);
            assertArrayEquals("Google Messages' ordinary trailing slot is the ABSENT component",
                    new byte[] { MlsResentMessage.TAG_ABSENT }, tail);

            final MlsResendReceive.Outcome o = MlsResendReceive.evaluate(
                    tail, new byte[] { 9 }, selectorFor("alice"), candidatesFor("alice"), null);
            assertFalse("an ordinary message is not a resend", o.isResend());
            assertEquals("even WITH a header, an absent component closes the instrument",
                    MlsResendReceive.IdCrossCheck.NO_COMPONENT,
                    MlsResendReceive.crossCheckOriginalMessageId(o, "MxaDOv6I6QTwGK2Xj6V-Ubhw"));
        }
    }

    /** The log rendering shows text as text and bytes as hex, so a capture is transcribable. */
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

    // ------------------------------------------------------------------
    // THE INNER-UNWRAP SEAM
    // ------------------------------------------------------------------

    /**
     * With no unwrapper, a resend that IS ours is dropped SILENTLY — and the matched candidate is
     * still reported, because that candidate is the measurement.
     *
     * <p>Silence is the point: "we have no unwrapper" is our gap, and reporting
     * {@code resent-message-for-me-failed-to-decrypt} on it would tell a healthy sender its resend
     * is broken.
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

    /** Install an unwrapper that FAILS and the one reporting arm becomes reachable — only then. */
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

    /** Install a working unwrapper and the ORIGINAL is what gets delivered, not the wrapper. */
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

    /** The seam is handed the FULL 64-byte field — key and tag — not just the tag. */
    @Test public void theSeamReceivesTheWholeHmacField() {
        final RecordingUnwrap unwrap = new RecordingUnwrap(new byte[] { 1 });
        MlsResendReceive.evaluate(component(), new byte[] { 9 }, selectorFor("bob"),
                candidatesFor("bob"), unwrap);
        assertEquals(1, unwrap.calls.size());
        assertEquals(MlsResentMessage.HMAC_FIELD_LEN, unwrap.calls.get(0).length);
    }

    /**
     * A throwing unwrapper is a FAILED unwrap, not a crashed receive thread.
     *
     * <p>One member's broken decoder must not take down the inbound path for a message every other
     * member handled fine.
     */
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

    // ------------------------------------------------------------------
    // The disposition table itself
    // ------------------------------------------------------------------

    /**
     * <b>EXACTLY ONE disposition may report.</b>
     *
     * <p>Pinned as a count rather than as a case, so adding a disposition that also reports breaks
     * this test rather than quietly doubling the receipts a single group resend produces.
     */
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
     * The three predicates PARTITION the enum: every disposition is in exactly one.
     *
     * <p>A disposition in none of them would fall off the end of the call site's if-chain and be
     * delivered by accident; a disposition in two would take whichever arm was written first.
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

    /** Nothing but a resend can reach a resend-specific arm. */
    @Test public void anOrdinaryMessageIsNeverSilentDroppedOrReported() {
        assertFalse(MlsResendReceive.silentDrop(MlsResendReceive.Disposition.NOT_A_RESEND));
        assertFalse(MlsResendReceive.reports(MlsResendReceive.Disposition.NOT_A_RESEND));
        assertTrue(MlsResendReceive.deliver(MlsResendReceive.Disposition.NOT_A_RESEND));
    }

    // ------------------------------------------------------------------
    // The markers a log scan reads
    // ------------------------------------------------------------------

    /** The not-for-me line carries the ORIGINAL message id, matching Google Messages' own wording. */
    @Test public void theNotForMeLineNamesTheOriginalMessageId() {
        final String line = MlsResendReceive.notForMeLine("mls-abc-1", "group-42");
        assertTrue(line.startsWith("Resent message not for me with original message id mls-abc-1"));
        assertTrue(line.contains("fails HMAC verification, for group: group-42"));
        // Nulls must not produce "null" in a log line that gets grepped.
        assertFalse(MlsResendReceive.notForMeLine(null, null).contains("null"));
    }

    /** The scan's needles ARE the emitter's constants — non-empty and distinct. */
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

    /** Every marker is actually in the invariant catalogue — a needle nobody scans for is inert. */
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

    // ------------------------------------------------------------------
    // The drop marker's round trip through the rendezvous store
    // ------------------------------------------------------------------

    /**
     * The RESEND_NOT_FOR_ME marker SURVIVES being framed and re-parsed, and still classifies as a
     * drop.
     *
     * <p>Load-bearing rather than incidental. A dropped resend's rendezvous row is answered on
     * redelivery by {@code RccMlsBody.parse(storedPayload)} and nothing else is consulted, so the
     * decision has to be recoverable from the bytes alone. Storing the resend WRAPPER there instead
     * would make the second delivery of a message we correctly refused to show insert it as a
     * binary chat bubble — and Tachyon does redeliver (a Google Messages sender's ciphertext arrived twice
     * 4 ms apart, 2026-08-15).
     *
     * <p>The specific thing that could break it silently: {@code frame()} appends
     * {@code ;charset=UTF-8}, and {@code classify()} matches the marker with {@code equals}, not a
     * prefix. It works because {@code parse()} strips the parameter — a fact worth a test rather
     * than a reading.
     */
    @Test public void theNotForMeMarkerRoundTripsThroughTheBodyFrame() {
        final byte[] framed = com.android.messaging.rcs.e2ee.RccMlsBody.frame(
                new byte[0], RccContentDisposition.RESEND_NOT_FOR_ME, /*inline=*/ true);
        final com.android.messaging.rcs.e2ee.RccMlsBody.Parsed back =
                com.android.messaging.rcs.e2ee.RccMlsBody.parse(framed);
        assertEquals(RccContentDisposition.RESEND_NOT_FOR_ME, back.contentType);
        assertEquals(0, back.body.length);
        assertEquals(RccContentDisposition.DROP_CONTROL,
                RccContentDisposition.classify(back.contentType));
        assertTrue(RccContentDisposition.isDrop(
                RccContentDisposition.classify(back.contentType)));
    }

    /**
     * The marker is not on the wire and must never render.
     *
     * <p>It is an INTERNAL disposition substituted by the decrypt path; if it ever classified as
     * TEXT, a dropped resend would appear in the conversation as an empty bubble.
     */
    @Test public void theNotForMeMarkerIsNeverRendered() {
        assertFalse(RccContentDisposition.classify(RccContentDisposition.RESEND_NOT_FOR_ME)
                == RccContentDisposition.TEXT);
    }

    /**
     * The PRE-FIX log line is a NEVER in the catalogue.
     *
     * <p>Its emitter is deliberately gone; seeing it means a device is running a build whose §11.3a
     * path routes every group resend into §10 recovery plus an FTD.
     */
    @Test public void thePreFixGuardStringIsANeverMarker() {
        MlsInvariantScan.Marker found = null;
        for (final MlsInvariantScan.Marker m : MlsInvariantScan.markers()) {
            if (m.needle.contains("We do not implement the ResentMessage unwrap yet")) found = m;
        }
        assertNotNull("the pre-fix resend guard must be a scanned NEVER", found);
        assertEquals(MlsInvariantScan.Severity.NEVER, found.severity);
    }

    // ---- the three-way id capture ----------------------------------------------------------

    /**
     * The three candidates are LABELLED and never printed bare. This is the whole point of the
     * instrument: three things in three layers can each be called "the original message id", and a
     * reader who sees three unlabelled ids will conflate them — a defect just fixed
     * elsewhere, where one unnamed number was read as a different quantity.
     */
    @Test
    public void theIdCandidateLineLabelsAllThreeAndNamesTheLayer() {
        final String line = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxCPIMheaderid0000000000", "MxAADmessageid0000000000");
        assertNotNull(line);
        assertTrue(line.startsWith(MlsResendReceive.MARKER_ID_CANDIDATES));
        assertTrue(line.contains("aad.message_id=MxAADmessageid0000000000"));
        assertTrue(line.contains("cpim.Original-Message-ID=MxCPIMheaderid0000000000"));
        assertTrue(line.contains("component.opaque=<absent>"));
    }

    /**
     * "Nothing to compare" must NOT share a value with "they agree" — the same partitioning rule the
     * disposition table is built on. A missing candidate reads N/A, never AGREE.
     */
    @Test
    public void anAbsentCandidateIsNotAnAgreement() {
        final String line = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, null, "MxAADmessageid0000000000");
        assertNotNull(line);
        assertTrue("absent cpim must be N/A, not AGREE", line.contains("aad-vs-cpim=N/A"));
        assertFalse(line.contains("aad-vs-cpim=AGREE"));
    }

    /** Equal values report AGREE; different values report DIFFER. An inequality is the finding. */
    @Test
    public void equalAndDifferingCandidatesAreReportedDistinctly() {
        final String same = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxSame000000000000000000", "MxSame000000000000000000");
        assertTrue(same.contains("aad-vs-cpim=AGREE"));
        final String diff = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxOne0000000000000000000", "MxTwo0000000000000000000");
        assertTrue(diff.contains("aad-vs-cpim=DIFFER"));
    }

    /** Nothing anywhere: say nothing rather than emit a line of three <absent>s. */
    @Test
    public void noCandidatesAnywhereProducesNoLine() {
        assertNull(MlsResendReceive.idCandidateLine((MlsResendReceive.Outcome) null, null, null));
        assertNull(MlsResendReceive.idCandidateLine((MlsResendReceive.Outcome) null, "", ""));
    }

    /**
     * FIELD A IS NOT LOGGED, and this pins it. A is inside the component body, which we cannot parse
     * while the outer framing anchor is unresolved and our single-opaque model is known incomplete.
     * A value we cannot correctly extract is worse than no value, and someone will be tempted.
     */
    @Test
    public void fieldAIsNotLoggedBecauseWeCannotParseTheBody() {
        final String line = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxCPIMheaderid0000000000", "MxAADmessageid0000000000");
        assertFalse("field A must not appear — the body is unparseable", line.contains("fieldA="));
        assertFalse(line.contains("selector="));
        assertTrue("and the line must SAY why", line.contains("Field A not shown"));
    }

    /**
     * The line states that (1) may hold the RESEND's own id. Without that, a reader sees AGREE on
     * ordinary traffic and concludes the fields are the same — when on ordinary traffic they would
     * agree under BOTH hypotheses, which is precisely why ordinary traffic cannot settle it.
     */
    @Test
    public void theLineWarnsThatOrdinaryAgreementProvesNothing() {
        final String line = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxSame000000000000000000", "MxSame000000000000000000");
        assertTrue(line.contains("THREE LAYERS"));
        assertTrue(line.contains("RESEND's own id"));
    }

    /**
     * The emitted line names WHICH PAIR discriminates. Recorded before the first capture rather
     * than after someone over-reads one: an aad-vs-component DIFFER has two readings (different
     * layers, or the same layer with a §11.1 new id), so it narrows without closing. Only
     * cpim-vs-component is free of that ambiguity, because the CPIM header carries the ORIGINAL.
     */
    @Test
    public void theLineNamesWhichPairActuallyDiscriminates() {
        final String line = MlsResendReceive.idCandidateLine(
                (MlsResendReceive.Outcome) null, "MxOne0000000000000000000", "MxTwo0000000000000000000");
        assertTrue(line.contains("TWO readings"));
        assertTrue(line.contains("cpim-vs-component is the discriminating pair"));
    }

    /**
     * The benign "not for me" line must SAY it is downstream of an HMAC mismatch. Without that, a
     * reader sees it on a device and concludes a recipient test fired — when on well-formed traffic
     * this line is unreachable, because every member's HMAC verifies. Pinning the sentence because
     * three successive "X selects" models have been wrong and the log is where the next reader looks.
     */
    @Test
    public void theBenignLineSaysItIsDownstreamOfAnHmacMismatch() {
        final String line = MlsResendReceive.notForMeLine("MxOrig00000000000000000", "g1");
        assertTrue(line.contains("downstream of an HMAC MISMATCH"));
        assertTrue("and must not claim field A selects", line.contains("does NOT select"));
    }
}

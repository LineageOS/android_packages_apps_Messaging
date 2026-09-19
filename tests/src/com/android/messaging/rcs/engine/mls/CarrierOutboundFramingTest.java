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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.e2ee.RccMlsBody;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Host-side tests for the carrier MLS <b>outbound</b> framing hop — the mirror
 * of {@code CarrierInboundBinaryContentTest}, which covers the receive half.
 *
 * <p>The carrier send leg used to be String-typed from {@code InsertNewMessageAction} down to
 * {@code CarrierRcsTransport.sendMls}, which ended in {@code text.getBytes(UTF_8)}. So it could
 * carry nothing but UTF-8 text and it never framed. Two of our own clients still talked, because
 * {@link RccMlsBody#parse} returns a frameless payload verbatim as {@code text/plain} — which is
 * exactly why the gap survived: the only peer that could see it was one we did not have.
 *
 * <p>The chain's Android-coupled hops (the {@code :ims} Messenger seam, the JAIN-SIP transport) are
 * not host-testable. What decides the outcome is, and it is two pure functions composed in an order
 * that matters:
 *
 * <ol>
 *   <li>{@link RccMlsBody#frame} at the caller — the same call the two Tachyon paths already make,
 *       so all three now build one entity shape;</li>
 *   <li>{@link MlsRecoveryPolicy#stampBodyGeneration} at encrypt time — because the frame header's
 *       counter is a PLACEHOLDER that only the sender ratchet can fill.</li>
 * </ol>
 *
 * <p><b>{@link #framingWithoutStampingSendsGenerationZeroForever} is the one that would have caught
 * the near-miss, and is the reason this file exists.</b> Framing on a leg that does not stamp is
 * WORSE than not framing: the counter stays 0 while {@code sender_data.generation} walks 0,1,2,…, so
 * a peer that cross-checks them decrypts the first message of an epoch and refuses every
 * one after it. Silent, delayed, and it looks like anything but its cause.
 */
public class CarrierOutboundFramingTest {

    /** Bytes that a String round trip destroys: an embedded NUL and sequences that are not UTF-8. */
    private static byte[] binaryPayload() {
        return new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,   // JPEG SOI + APP0
            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00,                 // NULs inside the header
            (byte) 0xC3, (byte) 0x28,                             // truncated 2-byte sequence
            (byte) 0xFF, (byte) 0xFE,                             // impossible in UTF-8
        };
    }

    /** The frame header's per-message counter: bytes 4..7, big-endian. */
    private static long counterOf(final byte[] framed) {
        return ((long) (framed[4] & 0xFF) << 24) | ((framed[5] & 0xFF) << 16)
                | ((framed[6] & 0xFF) << 8) | (framed[7] & 0xFF);
    }

    // ------------------------------------------------------------------ what the caller now sends

    @Test
    public void framedTextRoundTripsThroughTheEntityTheCarrierLegNowSends() {
        final String text = "padlock hi";
        final RccMlsBody.Parsed p = RccMlsBody.parse(RccMlsBody.frameText(text));
        assertEquals("text/plain", p.contentType);
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), p.body);
    }

    /**
     * The capability the String seam could not express at all. That is the actual
     * subject: not that text was broken, but that nothing except text could be sent.
     */
    @Test
    public void aNonTextContentTypeSurvivesTheFrame_whichTheStringSeamCouldNotCarry() {
        final byte[] jpeg = binaryPayload();
        final RccMlsBody.Parsed p = RccMlsBody.parse(
                RccMlsBody.frame(jpeg, "image/jpeg", /*inline=*/ true));
        assertEquals("image/jpeg", p.contentType);
        assertArrayEquals(jpeg, p.body);
    }

    @Test
    public void binaryIsNotCorruptedTheWayAUtf8StringHopWouldCorruptIt() {
        final byte[] jpeg = binaryPayload();
        // What the old String-typed leg did to it, for contrast: bytes -> String -> bytes.
        final byte[] viaString = new String(jpeg, StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_8);
        assertNotEquals("the premise: a UTF-8 String hop is lossy for these bytes",
                jpeg.length, viaString.length);

        final RccMlsBody.Parsed p = RccMlsBody.parse(
                RccMlsBody.frame(jpeg, "image/jpeg", /*inline=*/ true));
        assertArrayEquals("the framed path must be byte-exact", jpeg, p.body);
    }

    // ------------------------------------------------------------------ the EMITTED header bytes

    /**
     * THE ONE MY OWN ROUND-TRIP TESTS COULD NOT CATCH, and the reason they could not.
     *
     * <p>{@link RccMlsBody#frame} appended {@code ;charset=UTF-8} to EVERY type, so a framed JPEG
     * went out as {@code Content-Type: image/jpeg;charset=UTF-8}. Every test above still passes,
     * because {@link RccMlsBody#parse} cuts the type at the {@code ';'} — so the defect is invisible
     * to any test that only asks "does it come back". It is visible only to a peer, and sending
     * media is the first thing that would ever have shown one a real media type.
     *
     * <p>So this asserts the BYTES ON THE WIRE, not the round trip. A round-trip test is not a wire
     * test, and reaching for one is how a whole class of interop defects stays invisible.
     */
    @Test
    public void aBinaryTypeDoesNotCarryACharsetParameterOnTheWire() {
        final String wire = new String(
                RccMlsBody.frame(binaryPayload(), "image/jpeg", /*inline=*/ true),
                StandardCharsets.ISO_8859_1);
        assertTrue("the emitted header must name the media type",
                wire.contains("Content-Type: image/jpeg\r\n"));
        assertFalse("a charset on binary is meaningless and wrong to a strict peer",
                wire.contains("image/jpeg;charset"));
    }

    /**
     * The other half of that boundary, and it is drawn on EVIDENCE rather than on RFC 2045: two
     * non-text types already ship WITH the charset — {@code message/mls-rcs-file-info} (§7.8.1 key
     * delivery, on the wire in live groups since 2026-07-29) and
     * {@code message/mls-rcs-group-metadata-keys}. Changing a working wire shape to satisfy a
     * reading of the spec is the inverse of this project's rule, so they keep it until a capture of
     * a Google Messages peer's own header block says otherwise.
     */
    @Test
    public void theShippingNonTextTypesKeepTheirCharset_becauseTheyAreAlreadyOnTheWire() {
        for (final String ct : new String[] {
                "message/mls-rcs-file-info", "message/mls-rcs-group-metadata-keys"}) {
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ false),
                    StandardCharsets.ISO_8859_1);
            assertTrue(ct + " must keep the charset it ships with today",
                    wire.contains("Content-Type: " + ct + ";charset=UTF-8\r\n"));
        }
    }

    /** text/* keeps it, which is both correct and what Google Messages sends. */
    @Test
    public void textKeepsItsCharset() {
        final String wire = new String(RccMlsBody.frameText("hi"), StandardCharsets.ISO_8859_1);
        assertTrue(wire.contains("Content-Type: text/plain;charset=UTF-8\r\n"));
    }

    // ------------------------------------------------------------------ two copies of one fact

    /**
     * <b>The framing question and the routing question are STILL two questions</b>, and this pins
     * the relation between them rather than collapsing it.
     *
     * <p>{@code frame} asks <i>is this textual, so a charset means something</i>; {@code classify}
     * asks <i>how should this be routed</i>. "Just call the classifier" is the obvious fix and it is
     * the wrong one, because the two ALREADY DISAGREE: {@code application/pdf} takes no charset and
     * classifies {@link RccContentDisposition#DROP_UNKNOWN}, so under equality {@code frame} would
     * charset it — right on the routing question, wrong on the framing one.
     *
     * <p><b>The framing predicate moved into {@link RccContentDisposition} without
     * coupling the two</b>, and the distinction survives because it is a SEPARATE function:
     * {@link RccContentDisposition#takesCharset} states the rule positively (textual takes a
     * charset, nothing else does) and {@code classify} is untouched. What moved is the fact's
     * OWNERSHIP — it used to be a private predicate inside {@code frame} that was byte-identical to
     * {@code classify}'s MEDIA arm with nothing making them agree, which is the {@code
     * MlsContentRoute} shape (two legs answering "which content types are MLS?" differently).
     *
     * <p>So the invariant pinned here is CONTAINMENT, not equality: <b>anything routed as inline
     * media must not be framed with a charset.</b> That stays true if {@code takesCharset} later
     * refuses more types, and goes red the moment {@code MEDIA} grows a type {@code frame} would
     * still put a charset on.
     */
    @Test
    public void everyTypeRoutedAsInlineMediaIsFramedWithoutACharset() {
        for (final String ct : new String[] {
                "image/jpeg", "image/png", "image/gif",
                "video/mp4", "video/3gpp",
                "audio/ogg", "audio/mpeg"}) {
            assertEquals(ct + " must classify as MEDIA for this test to mean anything",
                    RccContentDisposition.MEDIA, RccContentDisposition.classify(ct));
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ true),
                    StandardCharsets.ISO_8859_1);
            assertFalse("a type routed as inline media must not carry a charset: " + ct,
                    wire.contains(ct + ";charset"));
        }
    }

    /**
     * The near-misses, on both sides. A predicate is easy to get subtly wrong, and a test that only
     * feeds it true cases cannot tell a correct predicate from one that returns true for everything
     * — which is the same "a probe that finds nothing proves nothing until it can find something"
     * rule, applied to a positive.
     *
     * <p><b>The near-miss SET changed with the rule</b>. It used to include
     * {@code application/imagex} — "contains image but is not image/" — which was a near-miss for
     * the old NEGATIVE predicate (everything except image/video/audio takes a charset). Under the
     * positive rule that type is simply not textual and correctly takes none, so it stopped being a
     * control and became a case of the new behaviour; it is asserted as such below. A control set
     * that is not re-derived when the predicate changes is a control set that has quietly stopped
     * controlling.
     */
    @Test
    public void aTypeThatMerelyLooksLikeMediaStillGetsItsCharset() {
        for (final String ct : new String[] {
                "text/image",                // starts with text/, not image/ — textual
                "text/imagex",               // near-miss on the subtype, still text/
                "message/mls-ft",            // a transfer descriptor; message/ ships with a charset
                "application/imagex+xml"}) { // +xml is textual under application/
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ true),
                    StandardCharsets.ISO_8859_1);
            assertTrue("textual, so the charset stays: " + ct,
                    wire.contains("Content-Type: " + ct + ";charset=UTF-8\r\n"));
        }
    }

    /**
     * <b>Binary {@code application/*} carries no charset either.</b>
     *
     * <p>This is the class that fell through BOTH nets. The first fix covered image/video/audio
     * and left everything else alone; the follow-up asked about {@code message/*} and
     * {@code application/*+xml}, which did not reach {@code application/pdf} either. Measured before
     * the fix, verbatim: {@code 'application/pdf' classify=DROP_UNKNOWN emits
     * 'application/pdf;charset=UTF-8'}. A charset on a PDF is exactly as meaningless as one on a
     * JPEG.
     *
     * <p>No shipping shape changes, which is why it could be fixed on its merits: nothing can send
     * one of these today. The moment a caller can attach a PDF, this is the behaviour it gets.
     *
     * <p><b>{@code application/} is NOT a binary prefix</b> and the second loop is what stops
     * someone making it one. {@code +xml}, {@code +json}, {@code application/json} and
     * {@code application/xml} are text carried under {@code application/}, and a bare prefix rule
     * would be wrong in the other direction.
     */
    @Test
    public void binaryApplicationTypesCarryNoCharset() {
        for (final String ct : new String[] {
                "application/pdf", "application/zip", "application/octet-stream",
                "application/imagex", "application/vnd.lineageos.mls-resend-not-for-me"}) {
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ false),
                    StandardCharsets.ISO_8859_1);
            assertTrue("the type still goes out verbatim: " + ct,
                    wire.contains("Content-Type: " + ct + "\r\n"));
            assertEquals("a charset on binary is meaningless: " + ct, 0,
                    countOccurrences(wire, "charset"));
        }
        for (final String ct : new String[] {
                "application/json", "application/xml",
                "application/vnd.gsma.rcs-ft-http+xml", "application/ld+json"}) {
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ false),
                    StandardCharsets.ISO_8859_1);
            assertTrue("text carried under application/ keeps its charset: " + ct,
                    wire.contains("Content-Type: " + ct + ";charset=UTF-8\r\n"));
        }
    }

    /**
     * THE CASE MY OWN NEAR-MISS ARGUMENT COULD NOT REACH, and it was violated when I wrote it.
     *
     * <p>{@link RccMlsBody#frame} normalised nothing while {@link RccContentDisposition#classify}
     * trims and lower-cases. So containment held only for input that was ALREADY normalised —
     * {@code classify("IMAGE/JPEG")} returned {@code MEDIA} while {@code frame} emitted
     * {@code Content-Type: IMAGE/JPEG;charset=UTF-8}. Every type in the two sets above is lowercase
     * and trimmed, so no assertion there could see it.
     *
     * <p><b>That is the near-miss argument turning on itself.</b> I wrote that a predicate fed only
     * true cases cannot be told from one that returns true for everything; the same sentence says a
     * predicate fed only NORMALISED input cannot be told from one that only works on normalised
     * input. Feeding it the shapes it does not control is the whole point, and I had picked the ones
     * it did.
     *
     * <p>Same failure shape as the charset bug itself: {@code parse()} trims and {@code classify()}
     * lower-cases, so a round trip is green and <b>only a peer ever sees the malformed header</b>.
     */
    @Test
    public void containmentHoldsForInputThatIsNotAlreadyNormalised() {
        for (final String ct : new String[] {
                "IMAGE/JPEG", "Image/Jpeg", " image/jpeg ", "\tVIDEO/MP4", "AUDIO/OGG "}) {
            assertEquals(ct + " must classify as MEDIA for this case to be about framing",
                    RccContentDisposition.MEDIA, RccContentDisposition.classify(ct));
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ true),
                    StandardCharsets.ISO_8859_1);
            assertFalse("routed as inline media, so no charset — whatever the case/spacing: " + ct,
                    wire.contains(";charset"));
        }
    }

    /**
     * The emitted value keeps its CASE and loses its WHITESPACE, and the asymmetry is deliberate:
     * trimming cannot change a shape that ships (every shipping content type here is a string
     * literal), while lower-casing the emitted value would change the wire for any caller that
     * passed mixed case — and this file's whole charset boundary is drawn on "do not change a
     * shipping wire shape".
     */
    @Test
    public void theEmittedValueKeepsItsCaseButNotItsWhitespace() {
        final String wire = new String(
                RccMlsBody.frame(new byte[] {1}, "  Message/MLS-RCS-File-Info  ", /*inline=*/ false),
                StandardCharsets.ISO_8859_1);
        assertTrue("case preserved, whitespace gone",
                wire.contains("Content-Type: Message/MLS-RCS-File-Info;charset=UTF-8\r\n"));
    }

    /** A whitespace-only type is not a type; it must fall back rather than emit a blank header. */
    @Test
    public void aWhitespaceOnlyContentTypeFallsBackToTextPlain() {
        final String wire = new String(RccMlsBody.frame(new byte[] {1}, "   ", /*inline=*/ true),
                StandardCharsets.ISO_8859_1);
        assertTrue(wire.contains("Content-Type: text/plain;charset=UTF-8\r\n"));
    }

    /**
     * THE DIMENSION A REMEMBERED CHECKLIST MISSED, and the reason this test exists is the method
     * rather than the bug.
     *
     * <p>After {@code frame} was hardened for case and whitespace, the rule written down was
     * "permute case, whitespace, ordering, encoding". <b>That list is itself a chosen input set</b> —
     * the same defect one level up. Deriving the dimensions from the DOMAIN'S GRAMMAR instead of
     * from memory gives RFC 2045 §5.1 four of them: type/subtype case-insensitive, parameter NAMES
     * case-insensitive, linear whitespace permitted around {@code ;} and {@code =}, and parameter
     * values optionally quoted — which names <em>parameters already present</em>, which the
     * remembered list did not.
     *
     * <p>It found a real one: {@code "text/plain;charset=utf-8"} went out as
     * {@code text/plain;charset=utf-8;charset=UTF-8}. A duplicate parameter, not permitted by
     * RFC 2045, resolvable either way by a strict peer.
     *
     * <p>Unreachable from production today — every caller passes a bare type — so it is another
     * instance of "a defect in a path nothing can exercise reads exactly like no defect". The
     * {@code --es contenttype} debug path and the successor work (real media send, where
     * the media layer supplies the type) are what would reach it.
     */
    @Test
    public void aTypeThatAlreadyCarriesParametersDoesNotGetASecondCharset() {
        for (final String ct : new String[] {
                "text/plain;charset=utf-8",
                "text/plain; charset=UTF-8",
                "application/vnd.gsma.rcs-ft-http+xml;charset=utf-8"}) {
            final String wire = new String(RccMlsBody.frame(new byte[] {1}, ct, /*inline=*/ true),
                    StandardCharsets.ISO_8859_1);
            assertTrue("the caller's explicit parameters are kept verbatim: " + ct,
                    wire.contains("Content-Type: " + ct + "\r\n"));
            assertEquals("exactly one charset parameter", 1, countOccurrences(wire, "charset"));
        }
    }

    /** A quoted parameter value may contain a ';' — it must not be mistaken for a separator. */
    @Test
    public void aQuotedParameterValueContainingASemicolonIsNotSplit() {
        final String ct = "image/jpeg; name=\"a;b\"";
        final String wire = new String(RccMlsBody.frame(new byte[] {1}, ct, /*inline=*/ true),
                StandardCharsets.ISO_8859_1);
        assertTrue(wire.contains("Content-Type: " + ct + "\r\n"));
        assertEquals("binary, and explicit — no charset either way", 0,
                countOccurrences(wire, "charset"));
    }

    /** The three bare production types still get their charset — no shipping shape changed. */
    @Test
    public void theBareProductionTypesAreUnaffected() {
        for (final String ct : new String[] {
                "text/plain", "message/mls-rcs-file-info", "message/mls-rcs-group-metadata-keys"}) {
            final String wire = new String(RccMlsBody.frame(new byte[] {1}, ct, /*inline=*/ false),
                    StandardCharsets.ISO_8859_1);
            assertTrue(ct + " keeps the charset it ships with",
                    wire.contains("Content-Type: " + ct + ";charset=UTF-8\r\n"));
        }
    }

    private static int countOccurrences(final String haystack, final String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ the stamp, and its absence

    @Test
    public void theStampLandsInTheFrameHeaderAndLeavesTheEntityReadable() {
        final byte[] framed = RccMlsBody.frameText("three");
        final byte[] stamped = MlsRecoveryPolicy.stampBodyGeneration(framed, 3);

        assertEquals("the counter must equal the generation the message is encrypted at",
                3L, counterOf(stamped));
        final RccMlsBody.Parsed p = RccMlsBody.parse(stamped);
        assertEquals("stamping must not disturb the entity", "text/plain", p.contentType);
        assertArrayEquals("three".getBytes(StandardCharsets.UTF_8), p.body);
    }

    /**
     * THE GUARD. A leg that frames but does not stamp emits generation 0 for every message in an
     * epoch. Pinning it here means the next transport that learns to frame cannot quietly skip the
     * stamp — the failure it causes is invisible on our own clients and only shows up against a peer
     * that cross-checks, which is the hardest kind of bug to attribute.
     */
    @Test
    public void framingWithoutStampingSendsGenerationZeroForever() {
        for (final String message : new String[] {"first", "second", "third"}) {
            assertEquals("an unstamped frame always carries 0 — this is the regression, not the fix",
                    0L, counterOf(RccMlsBody.frameText(message)));
        }
        // And with the stamp the same three messages walk, which is what Google Messages does.
        assertEquals(0L, counterOf(MlsRecoveryPolicy.stampBodyGeneration(
                RccMlsBody.frameText("first"), 0)));
        assertEquals(1L, counterOf(MlsRecoveryPolicy.stampBodyGeneration(
                RccMlsBody.frameText("second"), 1)));
        assertEquals(2L, counterOf(MlsRecoveryPolicy.stampBodyGeneration(
                RccMlsBody.frameText("third"), 2)));
    }

    /**
     * Why the stamp could be added to {@code MlsCarrierTransport.encryptForSend} before the caller
     * started framing: on an unframed body it is the identity, so the two halves were safe to write
     * in either order even though they had to SHIP together.
     */
    @Test
    public void stampingAnUnframedBodyIsTheIdentity() {
        final byte[] raw = "not an RCC.16 entity".getBytes(StandardCharsets.UTF_8);
        assertSame("no copy, no change — the magic check fails and it returns its input",
                raw, MlsRecoveryPolicy.stampBodyGeneration(raw, 7));
    }

    /**
     * {@code nextAppGen} answers -1 when it cannot say. Stamping must then leave the body alone
     * rather than write -1 into the header, because a wrong counter is worse than a placeholder one:
     * the placeholder is at least what an unframed sender would have sent.
     */
    @Test
    public void anUnavailableGenerationLeavesTheFrameUntouchedRatherThanWritingGarbage() {
        final byte[] framed = RccMlsBody.frameText("no generation available");
        assertSame(framed, MlsRecoveryPolicy.stampBodyGeneration(framed, -1));
        assertEquals(0L, counterOf(framed));
    }
}

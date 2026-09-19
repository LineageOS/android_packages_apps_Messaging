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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** The CPIM reaction envelope, pinned against Google Messages' own bytes. */
public class RccCpimReactionTest {

    /**
     * A REAL Google Messages reaction, decrypted 2026-08-20 — the complete 232-byte
     * payload, captured rather than constructed. Every other test here builds its input; this one
     * is the ground truth they are all judged against.
     */
    private static final String REFERENCE_REACTION_HEX = ""
            + "000100010000000340de4e533a206e31203c687474703a2f2f7777772e67736d612e636f6d3e"
            + "0d0a4e533a206e32203c75726e3a7263733a6d6573736167653a7265616374696f6e733a3e0d"
            + "0a6e312e5265666572656e63652d49443a206d6c732d6170706d6c732d2b3135373135353530"
            + "3130362d653670332d320d0a6e312e5265666572656e63652d547970653a202b526561637469"
            + "6f6e0d0a6e322e4f726967696e2d537572666163652d547970653a20310d0a0d0a436f6e7465"
            + "6e742d547970653a20746578742f706c61696e3b20636861727365743d5554462d380d0a0d0a"
            + "f09f9882"
            ;

    private static byte[] hex(final String h) {
        final byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    // ==================== THE ONE THAT MATTERS ====================

    /** Google Messages' own reaction parses, and every field comes out. */
    @Test
    public void aCapturedReactionParsesCompletely() {
        final RccCpimReaction r = RccCpimReaction.parse(hex(REFERENCE_REACTION_HEX));
        assertNotNull("Google Messages' captured reaction must parse", r);
        assertTrue("it is a reaction", r.isReaction());
        assertTrue("and it is an ADD", r.isAdd());
        assertFalse(r.isRemove());
        assertEquals("mls-appmls-+15715550106-e6p3-2", r.reactedMessageId());
        assertEquals("😂", r.emoji());              // the captured emoji
        assertEquals("text/plain", r.contentType());
    }

    /**
     * The reactions namespace is DECLARED but the reaction indicator lives in the GSMA one. Pinning
     * both so a future reader does not "simplify" by matching on the reactions URI alone.
     */
    @Test
    public void bothNamespacesResolve() {
        final RccCpimReaction r = RccCpimReaction.parse(hex(REFERENCE_REACTION_HEX));
        assertEquals("+Reaction", r.header(RccCpimReaction.NS_GSMA, "Reference-Type"));
        assertEquals("1", r.header(RccCpimReaction.NS_REACTIONS, "Origin-Surface-Type"));
    }

    /** Header lookup is case-insensitive on the name, as CPIM/MIME require. */
    @Test
    public void headerLookupIsCaseInsensitive() {
        final RccCpimReaction r = RccCpimReaction.parse(hex(REFERENCE_REACTION_HEX));
        assertEquals("+Reaction", r.header(RccCpimReaction.NS_GSMA, "reference-TYPE"));
    }

    // ==================== prefixes are not the contract ====================

    /**
     * <b>PREFIXES ARE THE SENDER'S CHOICE.</b> n1/n2 are labels from that sender's own NS lines, so
     * a parser that matched the literal "n1." would pass against every capture we hold and break on
     * the first peer that numbers differently. Same payload, different labels, same answer.
     */
    @Test
    public void differentNamespacePrefixesParseIdentically() {
        final String cpim =
                "NS: aa <http://www.gsma.com>\r\n"
                + "NS: zz <urn:rcs:message:reactions:>\r\n"
                + "aa.Reference-ID: msg-42\r\n"
                + "aa.Reference-Type: +Reaction\r\n"
                + "zz.Origin-Surface-Type: 1\r\n"
                + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "👍";
        final RccCpimReaction r = RccCpimReaction.parse(cpim.getBytes(StandardCharsets.UTF_8));
        assertNotNull(r);
        assertTrue(r.isAdd());
        assertEquals("msg-42", r.reactedMessageId());
        assertEquals("👍", r.emoji());
    }

    /** A prefix that was never declared is IGNORED, not guessed at. */
    @Test
    public void anUndeclaredPrefixIsIgnored() {
        final String cpim =
                "NS: n1 <http://www.gsma.com>\r\n"
                + "n9.Reference-Type: +Reaction\r\n"      // n9 never declared
                + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "x";
        final RccCpimReaction r = RccCpimReaction.parse(cpim.getBytes(StandardCharsets.UTF_8));
        assertNotNull(r);
        assertFalse("an undeclared prefix must not become a reaction", r.isReaction());
        assertNull(r.header(RccCpimReaction.NS_GSMA, "Reference-Type"));
    }

    // ==================== not-a-reaction must stay not-a-reaction ====================

    /**
     * An ORDINARY message must be unaffected. This is the regression that would matter most: if
     * plain text started classifying as a reaction, every message would vanish from the thread.
     */
    @Test
    public void anOrdinaryTextPayloadIsNotAReaction() {
        assertNull("no CPIM at all -> null, and the caller keeps its old behaviour",
                RccCpimReaction.parse("hello world".getBytes(StandardCharsets.UTF_8)));
    }

    /** A CPIM message that is NOT a reaction parses, but reports itself as not one. */
    @Test
    public void aCpimMessageWithoutAReferenceTypeIsNotAReaction() {
        final String cpim =
                "NS: n1 <http://www.gsma.com>\r\n"
                + "n1.Some-Other-Header: 7\r\n"
                + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "hi";
        final RccCpimReaction r = RccCpimReaction.parse(cpim.getBytes(StandardCharsets.UTF_8));
        assertNotNull(r);
        assertFalse(r.isReaction());
        assertEquals("hi", r.emoji());
    }

    /** A Reference-Type we do not recognise is not a reaction — no sign, no verdict. */
    @Test
    public void anUnsignedReferenceTypeIsNotAReaction() {
        final String cpim =
                "NS: n1 <http://www.gsma.com>\r\n"
                + "n1.Reference-Type: Reaction\r\n"        // no + or -
                + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "x";
        final RccCpimReaction r = RccCpimReaction.parse(cpim.getBytes(StandardCharsets.UTF_8));
        assertFalse(r.isReaction());
        assertFalse(r.isAdd());
        assertFalse(r.isRemove());
    }

    /**
     * A minus sign reads as REMOVE. <b>Never captured</b> — we have only ever seen ADDs — so this
     * pins the parser's reading of the sign, NOT a claim about Google Messages' removal wire format.
     */
    @Test
    public void aMinusSignReadsAsRemoveThoughWeHaveNeverCapturedOne() {
        final String cpim =
                "NS: n1 <http://www.gsma.com>\r\n"
                + "n1.Reference-ID: msg-7\r\n"
                + "n1.Reference-Type: -Reaction\r\n"
                + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "👍";
        final RccCpimReaction r = RccCpimReaction.parse(cpim.getBytes(StandardCharsets.UTF_8));
        assertTrue(r.isReaction());
        assertTrue(r.isRemove());
        assertFalse(r.isAdd());
    }

    // ==================== framing ====================

    /** The binary framing prefix is skipped by SCANNING, not by assuming its width. */
    @Test
    public void aFramingPrefixOfAnyWidthIsSkipped() {
        final byte[] cpim = ("NS: n1 <http://www.gsma.com>\r\n"
                + "n1.Reference-Type: +Reaction\r\n\r\nContent-Type: text/plain\r\n\r\nx")
                .getBytes(StandardCharsets.UTF_8);
        for (final int width : new int[] { 0, 4, 10, 17 }) {
            final byte[] framed = new byte[width + cpim.length];
            for (int i = 0; i < width; i++) framed[i] = (byte) (0x80 | i);   // non-ASCII junk
            System.arraycopy(cpim, 0, framed, width, cpim.length);
            final RccCpimReaction r = RccCpimReaction.parse(framed);
            assertNotNull("width " + width, r);
            assertTrue("width " + width, r.isAdd());
        }
    }

    @Test
    public void nullAndEmptyAreNull() {
        assertNull(RccCpimReaction.parse(null));
        assertNull(RccCpimReaction.parse(new byte[0]));
    }
}

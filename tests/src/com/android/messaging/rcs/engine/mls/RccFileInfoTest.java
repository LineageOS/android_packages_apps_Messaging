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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * RCC.16 §7.8.1 {@code FileInfo} — the proto that carries a group icon/subject key.
 *
 * <p>These pin the FIELD NUMBERS AND WIRE TYPES the spec fixes, by decoding the bytes independently
 * rather than round-tripping our encoder against our decoder. A round-trip agrees with itself even
 * if every field number is wrong, and the failure against a real peer is a proto that parses to
 * nothing with no diagnostic.
 */
public final class RccFileInfoTest {

    /** Read a length-delimited field, asserting the tag byte is exactly what the spec implies. */
    private static byte[] expectLenDelim(final byte[] b, final int off, final int field,
            final int[] endOut) {
        assertEquals("tag for field " + field, (field << 3) | 2, b[off] & 0xFF);
        int p = off + 1;
        int len = 0, shift = 0;
        while (true) {
            final int x = b[p++] & 0xFF;
            len |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        final byte[] out = new byte[len];
        System.arraycopy(b, p, out, 0, len);
        if (endOut != null) endOut[0] = p + len;
        return out;
    }

    /** The icon rides in FileInfo field 4, and its metadata/encryption fields are 1,2,3. */
    @Test
    public void iconUsesTheSpecFieldNumbers() {
        final byte[] icon = "PNGDATA".getBytes(StandardCharsets.UTF_8);
        final RccFileInfo.Sealed s = RccFileInfo.icon(icon, "image/png");
        assertNotNull(s);

        // FileInfo.icon = field 4
        final byte[] meta = expectLenDelim(s.fileInfo, 0, RccFileInfo.SLOT_ICON, null);
        // FileMetadata.file_name = 1, content_type = 2, encryption_info = 3
        final int[] end = new int[1];
        final byte[] name = expectLenDelim(meta, 0, 1, end);
        assertEquals("group_icon", new String(name, StandardCharsets.UTF_8));
        final byte[] ctype = expectLenDelim(meta, end[0], 2, end);
        assertEquals("image/png", new String(ctype, StandardCharsets.UTF_8));
        final byte[] enc = expectLenDelim(meta, end[0], 3, null);

        // FileEncryptionInfo.key_material = 1, iv = 2, hmac_tag = 3
        final byte[] key = expectLenDelim(enc, 0, 1, end);
        assertEquals(32, key.length);
        final byte[] iv = expectLenDelim(enc, end[0], 2, end);
        assertEquals(12, iv.length);
        final byte[] tag = expectLenDelim(enc, end[0], 3, end);
        assertEquals(32, tag.length);

        // algorithm = 4, varint, AES256_CTR_HMAC_SHA256_256TAG(1)
        assertEquals((4 << 3) | 0, enc[end[0]] & 0xFF);
        assertEquals(RccFileInfo.ALGORITHM_AES256_CTR_HMAC_SHA256_256TAG, enc[end[0] + 1] & 0xFF);
    }

    /** The subject rides in field 3 and is named group_subject (§9.7.1.5). */
    @Test
    public void subjectUsesSlotThree() {
        final RccFileInfo.Sealed s =
                RccFileInfo.subject("Weekend plans".getBytes(StandardCharsets.UTF_8), "text/plain");
        final byte[] meta = expectLenDelim(s.fileInfo, 0, RccFileInfo.SLOT_SUBJECT, null);
        assertEquals("group_subject",
                new String(expectLenDelim(meta, 0, 1, null), StandardCharsets.UTF_8));
        assertEquals(RccFileInfo.SLOT_SUBJECT, RccFileInfo.parse(s.fileInfo).slot);
    }

    /**
     * {@code file_length_hint} is {@code fixedint32} — wire type 5, LITTLE-endian, 4 bytes.
     *
     * <p>The trap this catches: a varint encodes identically for values below 128, so a
     * varint implementation passes every small-payload test and breaks on the first icon over 127
     * bytes. The length is therefore checked above and below that boundary.
     */
    @Test
    public void fileLengthHintIsFixed32LittleEndian() {
        for (final int n : new int[] {5, 127, 128, 300, 70000}) {
            final byte[] payload = new byte[n];
            final RccFileInfo.Sealed s = RccFileInfo.icon(payload, "image/png");
            final byte[] meta = expectLenDelim(s.fileInfo, 0, RccFileInfo.SLOT_ICON, null);
            final int[] end = new int[1];
            expectLenDelim(meta, 0, 1, end);
            expectLenDelim(meta, end[0], 2, end);
            final byte[] enc = expectLenDelim(meta, end[0], 3, null);

            // walk to field 5
            int p = 0;
            expectLenDelim(enc, p, 1, end); p = end[0];
            expectLenDelim(enc, p, 2, end); p = end[0];
            expectLenDelim(enc, p, 3, end); p = end[0];
            assertEquals("algorithm tag", (4 << 3) | 0, enc[p] & 0xFF);
            p += 2;                                     // tag + 1-byte value
            assertEquals("file_length_hint must be wire type 5", (5 << 3) | 5, enc[p] & 0xFF);
            final int le = (enc[p + 1] & 0xFF) | ((enc[p + 2] & 0xFF) << 8)
                    | ((enc[p + 3] & 0xFF) << 16) | ((enc[p + 4] & 0xFF) << 24);
            assertEquals("little-endian length " + n, n, le);
            assertEquals(p + 5, enc.length);
            assertEquals(n, RccFileInfo.parse(s.fileInfo).metadata.fileLengthHint);
        }
    }

    /** End to end: seal an icon, ship the proto + ciphertext, open it on the other side. */
    @Test
    public void sealAndOpenRoundTrip() {
        final byte[] icon = new byte[512];
        for (int i = 0; i < icon.length; i++) icon[i] = (byte) (i * 7 + 3);
        final RccFileInfo.Sealed s = RccFileInfo.icon(icon, "image/png");
        final RccFileInfo.Parsed p = RccFileInfo.parse(s.fileInfo);
        assertNotNull(p);
        assertEquals(RccFileInfo.SLOT_ICON, p.slot);
        assertArrayEquals(icon, RccFileInfo.open(p, s.ciphertext));
    }

    /**
     * {@code file_name} is the Annex C.2 KDF {@code Info}, not a label — so a tampered name must
     * fail to decrypt rather than silently produce garbage. This is the coupling {@link
     * RccFileInfo#icon} exists to make unbreakable.
     */
    @Test
    public void fileNameIsBoundIntoTheKeyDerivation() {
        final byte[] icon = "an icon".getBytes(StandardCharsets.UTF_8);
        final RccFileInfo.Sealed s = RccFileInfo.icon(icon, "image/png");
        final RccFileInfo.Parsed p = RccFileInfo.parse(s.fileInfo);
        // Re-describe the same key/iv/tag under the WRONG filename and the payload must not open.
        final RccFileInfo.Metadata wrong = new RccFileInfo.Metadata(
                RccFileCrypto.INFO_GROUP_SUBJECT, p.metadata.contentType, p.metadata.keyMaterial,
                p.metadata.iv, p.metadata.hmacTag, p.metadata.algorithm, p.metadata.fileLengthHint);
        final byte[] reproto = RccFileInfo.encode(RccFileInfo.SLOT_ICON, wrong);
        assertNull(RccFileInfo.open(RccFileInfo.parse(reproto), s.ciphertext));
    }

    /** An unknown/unspecified algorithm must be refused, not assumed to be Annex C.2. */
    @Test
    public void refusesAnUnknownAlgorithm() {
        final byte[] icon = "x".getBytes(StandardCharsets.UTF_8);
        final RccFileInfo.Sealed s = RccFileInfo.icon(icon, "image/png");
        final RccFileInfo.Parsed p = RccFileInfo.parse(s.fileInfo);
        final RccFileInfo.Metadata unspec = new RccFileInfo.Metadata(
                p.metadata.fileName, p.metadata.contentType, p.metadata.keyMaterial, p.metadata.iv,
                p.metadata.hmacTag, RccFileInfo.ALGORITHM_UNSPECIFIED, p.metadata.fileLengthHint);
        assertNull(RccFileInfo.open(
                RccFileInfo.parse(RccFileInfo.encode(RccFileInfo.SLOT_ICON, unspec)),
                s.ciphertext));
    }

    /** Each seal must mint a fresh key — Annex C.2 requires one key per file. */
    @Test
    public void everySealUsesAFreshKey() {
        final byte[] same = "identical".getBytes(StandardCharsets.UTF_8);
        final RccFileInfo.Parsed a = RccFileInfo.parse(RccFileInfo.icon(same, "image/png").fileInfo);
        final RccFileInfo.Parsed b = RccFileInfo.parse(RccFileInfo.icon(same, "image/png").fileInfo);
        assertTrue("key reuse would repeat the AES-CTR keystream",
                !java.util.Arrays.equals(a.metadata.keyMaterial, b.metadata.keyMaterial));
    }

    @Test
    public void parseRejectsGarbageAndNulls() {
        assertNull(RccFileInfo.parse(null));
        assertNull(RccFileInfo.parse(new byte[0]));
        assertNull(RccFileInfo.parse(new byte[] {(byte) 0xFF, (byte) 0xFF}));
        assertNull(RccFileInfo.encode(RccFileInfo.SLOT_ICON, null));
        assertNull(RccFileInfo.encode(99, null));
        assertNull(RccFileInfo.icon(null, "image/png"));
        assertNull(RccFileInfo.open(null, new byte[4]));
    }

    // ------------------------------------------------------------------ the chat-attachment slots

    /**
     * <b>The file name is the KDF's {@code Info} AND the proto's {@code file_name}, from one
     * argument.</b> If they could differ, a receiver would derive a different key and see only a tag
     * mismatch — a failure with no informative symptom. {@link RccFileInfo#file} takes one name and
     * passes it to both, so they cannot drift.
     */
    @Test
    public void aChatFileNamesItselfOnceAndTheNameReachesBothTheKdfAndTheProto() {
        final byte[] plain = "hello attachment".getBytes(StandardCharsets.UTF_8);
        final RccFileInfo.Sealed sealed = RccFileInfo.file("holiday.jpg", plain, "image/jpeg");
        assertNotNull(sealed);

        final RccFileInfo.Parsed p = RccFileInfo.parse(sealed.fileInfo);
        assertNotNull(p);
        assertEquals(RccFileInfo.SLOT_FILE, p.slot);
        assertEquals("holiday.jpg", p.metadata.fileName);
        assertEquals("image/jpeg", p.metadata.contentType);

        // The receiver derives from the name the proto carries. Opening with it must round-trip.
        assertArrayEquals(plain, RccFileInfo.open(p, sealed.ciphertext));
    }

    /**
     * <b>A DIFFERENT name does not open the same ciphertext</b> — which is what makes the previous
     * test meaningful rather than a tautology. The name is a KDF input, so a receiver told the wrong
     * one derives a different key; this is that failure, made visible.
     */
    @Test
    public void aWrongFileNameDoesNotOpenTheCiphertext() {
        final byte[] plain = "hello attachment".getBytes(StandardCharsets.UTF_8);
        final RccFileInfo.Sealed sealed = RccFileInfo.file("holiday.jpg", plain, "image/jpeg");
        final RccFileInfo.Parsed good = RccFileInfo.parse(sealed.fileInfo);

        final RccFileInfo.Metadata wrongName = new RccFileInfo.Metadata(
                "encrypted_file", good.metadata.contentType, good.metadata.keyMaterial,
                good.metadata.iv, good.metadata.hmacTag, good.metadata.algorithm,
                good.metadata.fileLengthHint);
        final RccFileInfo.Parsed bad =
                RccFileInfo.parse(RccFileInfo.encode(RccFileInfo.SLOT_FILE, wrongName));

        assertNull("a KDF context derived from the wrong name must not open the file",
                RccFileInfo.open(bad, sealed.ciphertext));
    }

    /** The thumbnail is its OWN file with its OWN key — Annex C.2 is one key per file. */
    @Test
    public void aThumbnailGetsItsOwnSlotAndItsOwnKey() {
        final RccFileInfo.Sealed f =
                RccFileInfo.file("holiday.jpg", "F".getBytes(StandardCharsets.UTF_8), "image/jpeg");
        final RccFileInfo.Sealed t = RccFileInfo.thumbnail(
                "holiday-thumb.jpg", "T".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        final RccFileInfo.Parsed pf = RccFileInfo.parse(f.fileInfo);
        final RccFileInfo.Parsed pt = RccFileInfo.parse(t.fileInfo);
        assertEquals(RccFileInfo.SLOT_FILE, pf.slot);
        assertEquals(RccFileInfo.SLOT_THUMBNAIL, pt.slot);
        assertFalse("one key per file",
                java.util.Arrays.equals(pf.metadata.keyMaterial, pt.metadata.keyMaterial));
    }

    /**
     * A chat file must not be sealed under the FT-HTTP descriptor's literal. That literal names the
     * CIPHERTEXT upload in the XML; using it here would give every file the same KDF context and
     * would not match what a peer computes from the real name.
     */
    @Test
    public void theEncryptedFileLiteralIsNotTheKdfName() {
        final RccFileInfo.Sealed sealed =
                RccFileInfo.file("holiday.jpg", "x".getBytes(StandardCharsets.UTF_8), "image/jpeg");
        assertFalse(RccFileInfo.parse(sealed.fileInfo).metadata.fileName.equals("encrypted_file"));
    }

    /** An empty or missing name is refused rather than sealed under one nobody can reproduce. */
    @Test
    public void anEmptyNameIsRefused() {
        final byte[] b = "x".getBytes(StandardCharsets.UTF_8);
        assertNull(RccFileInfo.file(null, b, "image/jpeg"));
        assertNull(RccFileInfo.file("", b, "image/jpeg"));
        assertNull(RccFileInfo.thumbnail("", b, "image/jpeg"));
    }

}

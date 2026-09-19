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

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

/**
 * {@link RccMediaSeal}.
 *
 * <p>The class is thin, so most of these pin what it REFUSES to do rather than what it computes.
 * Two of them exist to fail if a later change adds a convenience that would encode an unverified
 * guess in a signature.
 */
public class RccMediaSealTest {

    private static final byte[] FILE = "the attachment bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] THUMB = "preview".getBytes(StandardCharsets.UTF_8);

    // ------------------------------------------------------------------------ what it produces

    /** The key delivery opens the ciphertext, under the name the plan reports. */
    @Test
    public void theKeyDeliveryOpensTheCiphertext() {
        final RccMediaSeal.Plan p = RccMediaSeal.seal("holiday.jpg", FILE, "image/jpeg");
        assertNotNull(p);
        assertEquals("holiday.jpg", p.plaintextFileName);

        final RccFileInfo.Parsed parsed = RccFileInfo.parse(p.keyDeliveryBody);
        assertEquals(RccFileInfo.SLOT_FILE, parsed.slot);
        assertArrayEquals(FILE, RccFileInfo.open(parsed, p.ciphertext));
    }

    /** The blob to upload is the CIPHERTEXT. A plan that handed back the plaintext would leak it. */
    @Test
    public void theBlobToUploadIsNotThePlaintext() {
        final RccMediaSeal.Plan p = RccMediaSeal.seal("holiday.jpg", FILE, "image/jpeg");
        assertFalse("the upload blob must not be the plaintext",
                Arrays.equals(FILE, p.ciphertext));
    }

    /** A preview is a SECOND file with its OWN key, not an extension of the first FileInfo. */
    @Test
    public void aPreviewIsASecondFileWithItsOwnKey() {
        final RccMediaSeal.Plan p = RccMediaSeal.seal("holiday.jpg", FILE, "image/jpeg",
                "holiday-thumb.jpg", THUMB, "image/jpeg");
        assertTrue(p.hasThumbnail());

        final RccFileInfo.Parsed f = RccFileInfo.parse(p.keyDeliveryBody);
        final RccFileInfo.Parsed t = RccFileInfo.parse(p.thumbnailKeyDeliveryBody);
        assertEquals(RccFileInfo.SLOT_FILE, f.slot);
        assertEquals(RccFileInfo.SLOT_THUMBNAIL, t.slot);
        assertFalse("Annex C.2 is one key per file",
                Arrays.equals(f.metadata.keyMaterial, t.metadata.keyMaterial));
        assertArrayEquals(THUMB, RccFileInfo.open(t, p.thumbnailCiphertext));
    }

    /** No preview, no thumbnail artefacts — and the file half is unaffected. */
    @Test
    public void noPreviewLeavesTheThumbnailSlotsNull() {
        final RccMediaSeal.Plan p = RccMediaSeal.seal("holiday.jpg", FILE, "image/jpeg");
        assertFalse(p.hasThumbnail());
        assertNull(p.thumbnailKeyDeliveryBody);
        assertNull(p.thumbnailCiphertext);
    }

    // ---------------------------------------------------------------- what it deliberately refuses

    /**
     * <b>A FILE that cannot be sealed returns null; a THUMBNAIL that cannot is dropped.</b> The
     * asymmetry is the point: sending an attachment nobody can open is worse than not sending it,
     * while a missing preview costs nothing.
     */
    @Test
    public void aFailedFileSealIsFatalAndAFailedPreviewIsNot() {
        assertNull("no name means no KDF context — refuse rather than seal unreproducibly",
                RccMediaSeal.seal(null, FILE, "image/jpeg"));
        assertNull(RccMediaSeal.seal("", FILE, "image/jpeg"));

        final RccMediaSeal.Plan p = RccMediaSeal.seal("holiday.jpg", FILE, "image/jpeg",
                /*thumbnailName=*/ "", THUMB, "image/jpeg");
        assertNotNull("a bad preview must not take the file down with it", p);
        assertFalse(p.hasThumbnail());
        assertNotNull(p.keyDeliveryBody);
    }

    /**
     * <b>THE CLASS MUST NOT OFFER A {@code <mls-file>} PAYLOAD, AND THIS TEST EXISTS TO KEEP IT
     * THAT WAY.</b>
     *
     * <p>{@code <mls-file>} carries {@code cqan.a.c.d} — the bytes half of a pair that is the send
     * step's RETURN value. Whether those bytes equal what that step SENT is <b>not established</b>;
     * it was named as exactly the inference shape that has been wrong twice, and nobody would close
     * it. A convenience accessor here would encode that guess in a signature, which is the hardest
     * place to notice one.
     *
     * <p>Keyed on the ABSENCE of such a member rather than on a comment, because a comment does not
     * fail. If the question is ever answered, delete this test in the same commit that adds the
     * accessor — and cite the read that settled it.
     */
    @Test
    public void thePlanOffersNoDescriptorPayloadAccessorWhileTheQuestionIsOpen() {
        for (final Method m : RccMediaSeal.Plan.class.getMethods()) {
            final String n = m.getName().toLowerCase(java.util.Locale.US);
            assertFalse("RccMediaSeal.Plan." + m.getName() + " looks like it answers what goes in "
                    + "<mls-file>. That is UNREAD (cqan.a.c.d vs what the send step emits); an "
                    + "accessor here would encode a guess in a signature. See the class javadoc.",
                    n.contains("mlsfile") || n.contains("descriptorpayload"));
        }
    }

    /**
     * <b>And it must not choose an id.</b> Which id goes in which slot is held — the MLS leg carries
     * two ({@code M()}, and {@code L()} blanked when equal, a client-side guard against the server's
     * dedupe on {@code message_id}) and whether the two legs share one is unread. A seal layer that
     * minted or derived an id would be making that decision where nobody would look for it.
     */
    @Test
    public void theSealLayerChoosesNoMessageId() {
        for (final Method m : RccMediaSeal.Plan.class.getMethods()) {
            final String n = m.getName().toLowerCase(java.util.Locale.US);
            assertFalse("RccMediaSeal.Plan." + m.getName() + " looks like an id. The id mapping is "
                    + "held; nothing in the seal layer may choose one.",
                    n.contains("messageid") || n.endsWith("id"));
        }
        for (final java.lang.reflect.Field f : RccMediaSeal.Plan.class.getFields()) {
            final String n = f.getName().toLowerCase(java.util.Locale.US);
            assertFalse("RccMediaSeal.Plan." + f.getName() + " looks like an id; see above.",
                    n.contains("messageid") || n.endsWith("id"));
        }
    }
}

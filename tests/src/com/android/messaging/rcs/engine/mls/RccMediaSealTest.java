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

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

/**
 * {@link RccMediaSeal}: what it produces, and what it refuses to decide. Two tests fail if a
 * convenience accessor would encode an unverified wire assumption in a signature.
 */
public class RccMediaSealTest {

    private static final byte[] FILE = "the attachment bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] THUMB = "preview".getBytes(StandardCharsets.UTF_8);

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

    /** The blob to upload is the ciphertext. */
    @Test
    public void theBlobToUploadIsNotThePlaintext() {
        final RccMediaSeal.Plan p = RccMediaSeal.seal("holiday.jpg", FILE, "image/jpeg");
        assertFalse("the upload blob must not be the plaintext",
                Arrays.equals(FILE, p.ciphertext));
    }

    /** A preview is a second file with its own key, not part of the first FileInfo. */
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

    @Test
    public void noPreviewLeavesTheThumbnailSlotsNull() {
        final RccMediaSeal.Plan p = RccMediaSeal.seal("holiday.jpg", FILE, "image/jpeg");
        assertFalse(p.hasThumbnail());
        assertNull(p.thumbnailKeyDeliveryBody);
        assertNull(p.thumbnailCiphertext);
    }

    /** A file that cannot be sealed returns null; a preview that cannot is dropped. */
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
     * What peers put in {@code <mls-file>} is not established, so the plan offers no accessor for
     * it. Remove this test in the change that adds one on established evidence.
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

    /** Which message id goes in which slot is undecided, and the seal layer must not decide it. */
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

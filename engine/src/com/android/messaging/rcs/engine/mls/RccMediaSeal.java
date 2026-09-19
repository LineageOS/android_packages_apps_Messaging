/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Seals a chat attachment for an MLS conversation, over {@link RccFileInfo}. The key delivery is
 * produced first: the file-transfer descriptor's {@code <mls-file>} carries what that send returns,
 * so the descriptor cannot be built before it. This class does not build {@code <mls-file>} or
 * choose message ids; the caller takes both from the send step.
 */
public final class RccMediaSeal {

    private RccMediaSeal() { }

    /** The artefacts of one sealed attachment, in the order they are used. */
    public static final class Plan {
        /**
         * Sent first: the RCC.16 §7.8.1 {@code FileInfo} (key, IV, tag, file name), as a
         * {@value RccFileInfo#CONTENT_TYPE} message.
         */
        public final byte[] keyDeliveryBody;

        /** Uploaded second: the Annex C.2 ciphertext, never the plaintext. */
        public final byte[] ciphertext;

        /** The thumbnail's own {@code FileInfo}, or null when there is no preview. */
        public final byte[] thumbnailKeyDeliveryBody;

        /** The thumbnail's ciphertext under its own key, or null. */
        public final byte[] thumbnailCiphertext;

        /**
         * The plaintext file name (the KDF {@code Info}). Not the descriptor's {@code <file-name>},
         * which is always {@code "encrypted_file"}.
         */
        public final String plaintextFileName;

        Plan(final byte[] keyDeliveryBody, final byte[] ciphertext,
                final byte[] thumbnailKeyDeliveryBody, final byte[] thumbnailCiphertext,
                final String plaintextFileName) {
            this.keyDeliveryBody = keyDeliveryBody;
            this.ciphertext = ciphertext;
            this.thumbnailKeyDeliveryBody = thumbnailKeyDeliveryBody;
            this.thumbnailCiphertext = thumbnailCiphertext;
            this.plaintextFileName = plaintextFileName;
        }

        /** True when this send carries a preview, i.e. two files and two keys. */
        public boolean hasThumbnail() {
            return thumbnailKeyDeliveryBody != null && thumbnailCiphertext != null;
        }
    }

    /**
     * Seals one attachment with no preview.
     *
     * @param fileName the plaintext name, never {@code "encrypted_file"}
     * @param contentType the plaintext file's own MIME type
     * @return the plan, or null if the inputs are unusable
     */
    public static Plan seal(final String fileName, final byte[] fileBytes,
            final String contentType) {
        return seal(fileName, fileBytes, contentType, null, null, null);
    }

    /**
     * Seals an attachment and its preview as two files with two keys and two {@code FileInfo}s. A
     * thumbnail that fails to seal is dropped; a file that fails returns null.
     */
    public static Plan seal(final String fileName, final byte[] fileBytes, final String contentType,
            final String thumbnailName, final byte[] thumbnailBytes,
            final String thumbnailContentType) {
        final RccFileInfo.Sealed file = RccFileInfo.file(fileName, fileBytes, contentType);
        if (file == null) {
            return null;
        }
        RccFileInfo.Sealed thumb = null;
        if (thumbnailName != null && thumbnailBytes != null && thumbnailBytes.length > 0
                && thumbnailContentType != null) {
            thumb = RccFileInfo.thumbnail(thumbnailName, thumbnailBytes, thumbnailContentType);
        }
        return new Plan(file.fileInfo, file.ciphertext,
                thumb == null ? null : thumb.fileInfo,
                thumb == null ? null : thumb.ciphertext,
                fileName);
    }
}

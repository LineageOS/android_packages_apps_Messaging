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

/**
 * Seal a chat attachment for an MLS conversation.
 *
 * <p>Thin on purpose. It calls {@link RccFileInfo} and adds no crypto of its own. <b>What it exists
 * to carry is the ORDER and the SEPARATION</b>, because both are things a reader will otherwise get
 * wrong from the shape of the problem rather than from carelessness.
 *
 * <h2>THE ORDER, and it is the opposite of the obvious one</h2>
 *
 * <p>An encrypted media send is <b>TWO wire messages</b>, and the natural reading — build the
 * FT-HTTP descriptor, then attach key material — is backwards. Measured against Google Messages:
 * the descriptor-building step <b>invokes the sealing step</b> and check-casts its RETURN value,
 * so it seals first and describes afterwards. So:
 *
 * <ol>
 *   <li><b>FIRST</b> — the sealing step: produce the §7.8.1 {@code FileInfo} and send it as an MLS
 *       message with content type {@value RccFileInfo#CONTENT_TYPE}. {@link Plan#keyDeliveryBody}
 *       is that body.</li>
 *   <li><b>THEN</b> — upload {@link Plan#ciphertext} and build the FT-HTTP descriptor, whose
 *       {@code <mls-file>} carries what step 1 RETURNED.</li>
 * </ol>
 *
 * <p><b>You cannot build the descriptor before running step 1</b>, because the blob it embeds is
 * step 1's output. A design that emits the XML first and attaches key material afterwards is
 * inverted.
 *
 * <h2>THE SEPARATION — this class deliberately does NOT tell you what goes in {@code <mls-file>}</h2>
 *
 * <p>It would be easy, and it would be an unverified guess. {@code <mls-file>} carries
 * the BYTES half of a {@code (content_type, bytes)} pair that is the sealing step's
 * return value. <b>Whether those bytes are byte-identical to what that step SENT is NOT
 * established</b> — it is exactly the shape of inference that has been wrong twice, and it is not
 * closable without reading that step's body.
 *
 * <p>So there is no {@code mlsFilePayload()} on {@link Plan} and there is not going to be one until
 * somebody reads it. Offering one would encode a guess in a signature, where it is hardest to
 * notice. <b>The caller takes the descriptor payload from whatever the send step returns</b>, and
 * the two stay separable exactly as long as the question is open.
 *
 * <h2>What this class does not do</h2>
 *
 * <p>No ids. Which id goes in which slot is held: the MLS leg carries two ({@code M()} and
 * {@code L()}, with {@code L()} blanked when it equals {@code M()} — a client-side guard against the
 * server's dedupe on {@code message_id}), and whether the two LEGS share one is unread. Nothing here
 * chooses an id, and nothing here should start to.
 */
public final class RccMediaSeal {

    private RccMediaSeal() { }

    /** The artefacts of one sealed attachment, in the order they are used. */
    public static final class Plan {
        /**
         * STEP 1's body: the §7.8.1 {@code FileInfo} to send as an MLS message with content type
         * {@value RccFileInfo#CONTENT_TYPE}. Carries the key, the IV, the tag and the file name.
         */
        public final byte[] keyDeliveryBody;

        /** STEP 2's blob: the Annex C.2 ciphertext to UPLOAD. Never the plaintext. */
        public final byte[] ciphertext;

        /** The thumbnail's own {@code FileInfo}, or null when the send carries no preview. */
        public final byte[] thumbnailKeyDeliveryBody;

        /** The thumbnail's ciphertext, or null. Its own file, its own key (Annex C.2). */
        public final byte[] thumbnailCiphertext;

        /**
         * The PLAINTEXT file name — the KDF {@code Info} and {@code FileMetadata.file_name}.
         *
         * <p>Exposed so a caller can log or assert on it, and named {@code plaintextFileName} so
         * that reaching for it when building the FT-HTTP descriptor looks wrong: that document's
         * {@code <file-name>} is the literal {@code "encrypted_file"} and describes the CIPHERTEXT.
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
     * Seal one attachment, with no preview.
     *
     * @param fileName the PLAINTEXT name — the KDF input, never {@code "encrypted_file"}
     * @param contentType the plaintext file's own MIME type
     * @return the plan, or null if the inputs are unusable
     */
    public static Plan seal(final String fileName, final byte[] fileBytes,
            final String contentType) {
        return seal(fileName, fileBytes, contentType, null, null, null);
    }

    /**
     * Seal one attachment and its preview.
     *
     * <p><b>Two files, two keys, two {@code FileInfo}s.</b> Annex C.2 is one key per file, and
     * {@link RccFileInfo#encode} carries one slot per call — so a preview does not extend the file's
     * {@code FileInfo}, it produces a second one. Both are delivered through the same MLS message
     * leg; the {@code MlsMessageList} wrapper takes k self-delimiting entries, which is what it is
     * for.
     *
     * <p>A thumbnail whose seal fails is dropped rather than failing the send: the preview is an
     * optimisation and the file is the payload. A FILE whose seal fails returns null — sending an
     * attachment nobody can open is worse than not sending it.
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

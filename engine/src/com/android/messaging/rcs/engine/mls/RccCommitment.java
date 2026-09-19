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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * RCC.16 <b>Annex C.1</b> — "Creating a Commitment for a Value".
 *
 * <pre>
 *   Commitment = Hash(HashContent)
 *   struct { opaque label&lt;V&gt;; opaque value&lt;V&gt;; } HashContent
 *   label = L;  value = V;
 * </pre>
 *
 * <p>{@code opaque x<V>} is an MLS variable-length vector, i.e. the RFC 9420 §6.2.2 varint length
 * prefix followed by the bytes — so the hashed content is
 * {@code varint(len L) ‖ L ‖ varint(len V) ‖ V} and the hash is SHA-256.
 *
 * <p>The labels are BARE ASCII — {@code "icon_commitment"} / {@code "subject_commitment"} — with no
 * "MLS 1.0 " prefix and no trailing NUL. That is not an assumption: it was byte-confirmed against
 * Google Messages. Getting the label framing
 * wrong yields a commitment that verifies against nothing, with no diagnostic beyond a mismatch.
 *
 * <p>Lives in the shared engine because both transports need it and it is spec crypto, not backend
 * framing. The varint comes from {@link MlsAppMessage} rather than a second copy — the AAD and this
 * share one implementation deliberately.
 */
public final class RccCommitment {

    private RccCommitment() { }

    /** RCC.16 <b>v3.0</b> §7.11.4.1 — the label for {@code icon_commitment} (0xF004). */
    public static final String LABEL_ICON = "icon_commitment";
    /** RCC.16 <b>v3.0</b> §7.11.6.1 — the label for {@code subject_commitment} (0xF006). */
    public static final String LABEL_SUBJECT = "subject_commitment";

    /**
     * RCC.16 <b>v4.0</b> §9.7.1.1.1 — the icon label changed to {@code "group_icon"}.
     *
     * <p><b>And so did the committed VALUE</b>: v4.0 commits to the {@code hmac_tag} field of the
     * FileEncryption metadata, where our v3.0 implementation commits to the key material. Two
     * independent changes to one hash, either of which alone produces a commitment that verifies
     * against nothing — which is why {@link #commitmentValue} exists rather than leaving callers to
     * pass whichever byte array is in scope.
     *
     * <p>The v3.0 labels are not a guess we are replacing: they were byte-confirmed against a
     * shipping Google Messages build, which is a v3.0-era client, so both are correct — each for
     * its own revision. This is the same pattern as the 0xF007 framing disagreement.
     */
    public static final String LABEL_ICON_V4 = "group_icon";
    /** RCC.16 <b>v4.0</b> §9.7.1.2.1 — the subject label. See {@link #LABEL_ICON_V4}. */
    public static final String LABEL_SUBJECT_V4 = "group_subject";

    /** The {@code icon_commitment} label for {@code version}. */
    public static String labelForIcon(final Rcc16Version version) {
        return version == Rcc16Version.V4_0 ? LABEL_ICON_V4 : LABEL_ICON;
    }

    /** The {@code subject_commitment} label for {@code version}. */
    public static String labelForSubject(final Rcc16Version version) {
        return version == Rcc16Version.V4_0 ? LABEL_SUBJECT_V4 : LABEL_SUBJECT;
    }

    /**
     * Pick the value to commit to, for {@code version}.
     *
     * <p>Takes BOTH candidates and chooses, instead of letting the caller decide, because the two
     * are both opaque byte arrays of plausible length and swapping them is undetectable until a peer
     * silently fails to verify. v3.0 commits to the key material;
     * v4.0 §9.7.1.1.1 commits to the {@code hmac_tag} of the FileEncryption metadata.
     *
     * @return the bytes to hash, or {@code null} if the one this version needs is absent
     */
    public static byte[] commitmentValue(final Rcc16Version version, final byte[] keyMaterial,
            final byte[] hmacTag) {
        return version == Rcc16Version.V4_0 ? hmacTag : keyMaterial;
    }

    /**
     * The version-correct {@code icon_commitment} (0xF004) extension_data.
     *
     * <p>Under v3.0 this is byte-identical to {@link #iconCommitment(byte[])}.
     */
    public static byte[] iconCommitment(final Rcc16Version version, final byte[] keyMaterial,
            final byte[] hmacTag) {
        return commit(labelForIcon(version), commitmentValue(version, keyMaterial, hmacTag));
    }

    /** The version-correct {@code subject_commitment} (0xF006) extension_data. */
    public static byte[] subjectCommitment(final Rcc16Version version, final byte[] keyMaterial,
            final byte[] hmacTag) {
        return commit(labelForSubject(version), commitmentValue(version, keyMaterial, hmacTag));
    }

    /**
     * Compute the Annex C.1 commitment for {@code value} under {@code label}.
     *
     * @return 32 bytes, or {@code null} if the digest is unavailable or the inputs are unusable
     */
    public static byte[] commit(final String label, final byte[] value) {
        if (label == null || value == null) return null;
        try {
            final byte[] l = label.getBytes(StandardCharsets.US_ASCII);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(
                    l.length + value.length + 8);
            out.write(MlsAppMessage.mlsVarint(l.length));
            out.write(l);
            out.write(MlsAppMessage.mlsVarint(value.length));
            out.write(value);
            return MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * The {@code icon_commitment} (0xF004) extension_data.
     *
     * <p><b>The value committed to is the icon KEY MATERIAL, not the encrypted icon.</b> That is not
     * what this API originally assumed — it was written taking "commitment over the content" at face
     * value, and callers passed ciphertext. Google Messages' native engine shows that the
     * commitment is a RefHash over the key, and that the encrypted
     * content is encrypted WITH that key: the extension is what
     * binds key and ciphertext together, which is exactly why a commitment over the ciphertext
     * leaves the ciphertext unbound and the request malformed.
     *
     * <p>The construction itself (Annex C.1) is unchanged and still host-tested; only the input is.
     */
    public static byte[] iconCommitment(final byte[] iconKeyMaterial) {
        return commit(LABEL_ICON, iconKeyMaterial);
    }

    /** The {@code subject_commitment} (0xF006) extension_data — over the subject KEY MATERIAL. */
    public static byte[] subjectCommitment(final byte[] subjectKeyMaterial) {
        return commit(LABEL_SUBJECT, subjectKeyMaterial);
    }

    /**
     * Constant-time comparison of a received commitment against one we computed.
     *
     * <p>Constant-time because a commitment is a verifier: comparing with {@code Arrays.equals} would
     * leak how many leading bytes matched.
     */
    public static boolean verify(final String label, final byte[] value, final byte[] expected) {
        final byte[] actual = commit(label, value);
        if (actual == null || expected == null || expected.length != actual.length) return false;
        int diff = 0;
        for (int i = 0; i < actual.length; i++) {
            diff |= actual[i] ^ expected[i];
        }
        return diff == 0;
    }
}

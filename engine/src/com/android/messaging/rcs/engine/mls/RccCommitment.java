/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * RCC.16 Annex C.1 commitments: {@code SHA-256(varint(len L) ‖ L ‖ varint(len V) ‖ V)} with a bare
 * ASCII label (no prefix, no NUL) and the RFC 9420 §6.2.2 varint. See docs/mls/metadata.md.
 */
public final class RccCommitment {

    private RccCommitment() { }

    /** RCC.16 v3.0 §7.11.4.1 label for {@code icon_commitment} (0xF004). */
    public static final String LABEL_ICON = "icon_commitment";
    /** RCC.16 v3.0 §7.11.6.1 label for {@code subject_commitment} (0xF006). */
    public static final String LABEL_SUBJECT = "subject_commitment";

    /**
     * RCC.16 v4.0 §9.7.1.1.1 icon label. v4.0 also commits to a different value; see
     * {@link #commitmentValue}.
     */
    public static final String LABEL_ICON_V4 = "group_icon";
    /** RCC.16 v4.0 §9.7.1.2.1 subject label. */
    public static final String LABEL_SUBJECT_V4 = "group_subject";

    public static String labelForIcon(final Rcc16Version version) {
        return version == Rcc16Version.V4_0 ? LABEL_ICON_V4 : LABEL_ICON;
    }

    public static String labelForSubject(final Rcc16Version version) {
        return version == Rcc16Version.V4_0 ? LABEL_SUBJECT_V4 : LABEL_SUBJECT;
    }

    /**
     * The value to commit to: the key material in v3.0, the FileEncryption {@code hmac_tag} in
     * v4.0. Chosen here because the two are indistinguishable byte arrays to a caller.
     *
     * @return the bytes to hash, or {@code null} if the one this version needs is absent
     */
    public static byte[] commitmentValue(final Rcc16Version version, final byte[] keyMaterial,
            final byte[] hmacTag) {
        return version == Rcc16Version.V4_0 ? hmacTag : keyMaterial;
    }

    /**
     * The {@code icon_commitment} (0xF004) extension data for {@code version}; under v3.0 identical
     * to {@link #iconCommitment(byte[])}.
     */
    public static byte[] iconCommitment(final Rcc16Version version, final byte[] keyMaterial,
            final byte[] hmacTag) {
        return commit(labelForIcon(version), commitmentValue(version, keyMaterial, hmacTag));
    }

    /** The {@code subject_commitment} (0xF006) extension data for {@code version}. */
    public static byte[] subjectCommitment(final Rcc16Version version, final byte[] keyMaterial,
            final byte[] hmacTag) {
        return commit(labelForSubject(version), commitmentValue(version, keyMaterial, hmacTag));
    }

    /** The 32-byte commitment, or {@code null} if the inputs or the digest are unusable. */
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
     * The v3.0 {@code icon_commitment} (0xF004) over the icon key material, never the ciphertext:
     * the commitment is what binds the key to the encrypted content.
     */
    public static byte[] iconCommitment(final byte[] iconKeyMaterial) {
        return commit(LABEL_ICON, iconKeyMaterial);
    }

    /** The v3.0 {@code subject_commitment} (0xF006) over the subject key material. */
    public static byte[] subjectCommitment(final byte[] subjectKeyMaterial) {
        return commit(LABEL_SUBJECT, subjectKeyMaterial);
    }

    /** Constant-time, so a mismatch does not leak how many leading bytes matched. */
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

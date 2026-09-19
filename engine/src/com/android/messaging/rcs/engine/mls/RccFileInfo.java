/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * RCC.16 §7.8.1 {@code FileInfo}, which carries a content key to the group as an ordinary encrypted
 * application message ({@value #CONTENT_TYPE}). {@code file_length_hint} is protobuf fixed32, not a
 * varint, and {@code file_name} is the Annex C.2 KDF {@code Info}, so each sealing method encrypts
 * and describes in one call. See docs/mls/metadata.md.
 */
public final class RccFileInfo {

    private RccFileInfo() { }

    /** §7.8.1 {@code Algorithm}. */
    public static final int ALGORITHM_UNSPECIFIED = 0;
    /** Annex C.2: AES-CTR with a 256-bit HMAC-SHA256 tag. */
    public static final int ALGORITHM_AES256_CTR_HMAC_SHA256_256TAG = 1;

    /** RCC.16 §7.8.1 content type of a message carrying a {@code FileInfo}. */
    public static final String CONTENT_TYPE = "message/mls-rcs-file-info";

    /**
     * The declared type of an encrypted group subject or icon. Servers validate it, and peers treat
     * it as the "already encrypted" marker. Distinct from the plaintext input rule: a subject must
     * be {@code text/plain}, {@code text/plain; charset=utf-8} or empty before encryption.
     */
    public static final String CONTENT_TYPE_ENCRYPTED = "message/mls-ft";

    /** {@code FileInfo} slots, which are also the proto field numbers. */
    public static final int SLOT_FILE = 1;
    public static final int SLOT_THUMBNAIL = 2;
    public static final int SLOT_SUBJECT = 3;
    public static final int SLOT_ICON = 4;

    /** One {@code FileMetadata} + its {@code FileEncryptionInfo}. */
    public static final class Metadata {
        public final String fileName;
        public final String contentType;
        public final byte[] keyMaterial;
        public final byte[] iv;
        public final byte[] hmacTag;
        public final int algorithm;
        public final int fileLengthHint;

        public Metadata(final String fileName, final String contentType, final byte[] keyMaterial,
                final byte[] iv, final byte[] hmacTag, final int algorithm,
                final int fileLengthHint) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.keyMaterial = keyMaterial;
            this.iv = iv;
            this.hmacTag = hmacTag;
            this.algorithm = algorithm;
            this.fileLengthHint = fileLengthHint;
        }
    }

    /** An encrypted payload plus the {@code FileInfo} that lets the group decrypt it. */
    public static final class Sealed {
        /** The binary {@code FileInfo}, the body of a {@value #CONTENT_TYPE} message. */
        public final byte[] fileInfo;
        /** The Annex C.2 ciphertext: uploaded (icon) or Base64-encoded inline (subject). */
        public final byte[] ciphertext;
        Sealed(final byte[] fileInfo, final byte[] ciphertext) {
            this.fileInfo = fileInfo;
            this.ciphertext = ciphertext;
        }
    }

    /**
     * Encrypts a group icon under a fresh key and describes it in the {@code icon} slot
     * (RCC.16 §9.7.1.1, §9.7.1.4).
     *
     * @param contentType the icon's own MIME type, e.g. {@code image/png}
     */
    public static Sealed icon(final byte[] iconBytes, final String contentType) {
        return seal(SLOT_ICON, RccFileCrypto.INFO_GROUP_ICON, iconBytes, contentType);
    }

    /** The same for the group subject (RCC.16 §9.7.1.2, §9.7.1.5). */
    public static Sealed subject(final byte[] subjectUtf8, final String contentType) {
        return seal(SLOT_SUBJECT, RccFileCrypto.INFO_GROUP_SUBJECT, subjectUtf8, contentType);
    }

    /**
     * Seals a chat attachment in the {@code file} slot. The name is the plaintext file's name: the
     * KDF input, carried inside the sealed payload. It is not the file-transfer descriptor's
     * {@code <file-name>}, which is always {@code "encrypted_file"} and describes the ciphertext
     * upload.
     *
     * @param fileName the plaintext file's name, never {@code "encrypted_file"}
     * @param contentType the plaintext file's own MIME type, e.g. {@code image/jpeg}
     */
    public static Sealed file(final String fileName, final byte[] fileBytes,
            final String contentType) {
        if (fileName == null || fileName.isEmpty()) return null;
        return seal(SLOT_FILE, fileName, fileBytes, contentType);
    }

    /**
     * Seals a thumbnail in the {@code thumbnail} slot with its own key, since it is a separate
     * file. A send with a preview therefore produces two {@code FileInfo}s.
     *
     * @param fileName the thumbnail's own name, distinct from the file's
     */
    public static Sealed thumbnail(final String fileName, final byte[] thumbBytes,
            final String contentType) {
        if (fileName == null || fileName.isEmpty()) return null;
        return seal(SLOT_THUMBNAIL, fileName, thumbBytes, contentType);
    }

    private static Sealed seal(final int slot, final String fileName, final byte[] plaintext,
            final String contentType) {
        if (plaintext == null || contentType == null) return null;
        final byte[] key = RccFileCrypto.newKey();
        final RccFileCrypto.Encrypted e = RccFileCrypto.encrypt(key, plaintext, fileName);
        if (e == null) return null;
        final byte[] info = encode(slot, new Metadata(fileName, contentType, key, e.iv, e.tag,
                ALGORITHM_AES256_CTR_HMAC_SHA256_256TAG, e.fileLengthHint));
        return (info == null) ? null : new Sealed(info, e.ciphertext);
    }

    /** Encodes a {@code FileInfo} carrying one slot. */
    public static byte[] encode(final int slot, final Metadata m) {
        if (m == null || slot < SLOT_FILE || slot > SLOT_ICON) return null;
        try {
            final ByteArrayOutputStream enc = new ByteArrayOutputStream(96);
            bytes(enc, 1, m.keyMaterial);
            bytes(enc, 2, m.iv);
            bytes(enc, 3, m.hmacTag);
            if (m.algorithm != ALGORITHM_UNSPECIFIED) varint(enc, 4, m.algorithm);
            fixed32(enc, 5, m.fileLengthHint);

            final ByteArrayOutputStream meta = new ByteArrayOutputStream(160);
            bytes(meta, 1, m.fileName.getBytes(StandardCharsets.UTF_8));
            bytes(meta, 2, m.contentType.getBytes(StandardCharsets.UTF_8));
            bytes(meta, 3, enc.toByteArray());

            final ByteArrayOutputStream out = new ByteArrayOutputStream(176);
            bytes(out, slot, meta.toByteArray());
            return out.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    /** A parsed {@code FileInfo}: whichever slot was populated, and which one it was. */
    public static final class Parsed {
        public final int slot;
        public final Metadata metadata;
        Parsed(final int slot, final Metadata metadata) {
            this.slot = slot;
            this.metadata = metadata;
        }
    }

    /**
     * Returns the first populated slot, or {@code null} if anything required is missing or
     * malformed.
     */
    public static Parsed parse(final byte[] proto) {
        if (proto == null || proto.length == 0) return null;
        try {
            for (int slot = SLOT_FILE; slot <= SLOT_ICON; slot++) {
                final byte[] meta = field(proto, slot);
                if (meta == null) continue;
                final byte[] name = field(meta, 1);
                final byte[] ctype = field(meta, 2);
                final byte[] enc = field(meta, 3);
                if (name == null || enc == null) return null;
                final byte[] key = field(enc, 1);
                final byte[] iv = field(enc, 2);
                final byte[] tag = field(enc, 3);
                if (key == null || iv == null || tag == null) return null;
                return new Parsed(slot, new Metadata(
                        new String(name, StandardCharsets.UTF_8),
                        ctype == null ? "" : new String(ctype, StandardCharsets.UTF_8),
                        key, iv, tag, varintField(enc, 4), fixed32Field(enc, 5)));
            }
            return null;
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Decrypts with the proto's own {@code file_name} as the Annex C.2 {@code Info}, checking the
     * declared length.
     */
    public static byte[] open(final Parsed p, final byte[] ciphertext) {
        if (p == null || p.metadata == null) return null;
        final Metadata m = p.metadata;
        if (m.algorithm != ALGORITHM_AES256_CTR_HMAC_SHA256_256TAG) return null;
        return RccFileCrypto.decrypt(m.keyMaterial, m.iv, ciphertext, m.hmacTag, m.fileName,
                m.fileLengthHint);
    }

    // ---- Protobuf helpers, forwarding to RccProto.

    private static void bytes(final ByteArrayOutputStream o, final int field, final byte[] v)
            throws java.io.IOException {
        RccProto.bytes(o, field, v);
    }

    private static void varint(final ByteArrayOutputStream o, final int field, final long v) {
        RccProto.varint(o, field, v);
    }

    private static void fixed32(final ByteArrayOutputStream o, final int field, final int v) {
        RccProto.fixed32(o, field, v);
    }

    private static byte[] field(final byte[] b, final int field) {
        return RccProto.field(b, field);
    }

    private static int varintField(final byte[] b, final int field) {
        return (int) RccProto.varintField(b, field);
    }

    private static int fixed32Field(final byte[] b, final int field) {
        return RccProto.fixed32Field(b, field);
    }
}

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

/**
 * RCC.16 <b>§7.8.1</b> — the {@code FileInfo} protobuf that carries a content key to the other
 * members.
 *
 * <p>This is how the group ICON and SUBJECT keys actually travel. §9.7.1.4 and §9.7.1.5 say to
 * "create an MLS PrivateMessage to transport the symmetric key ... with file_name {@code group_icon}
 * / {@code group_subject}", and §7.8.1 is the body of that message: a binary {@code FileInfo} sent as
 * an ordinary MLS application message with content type {@value #CONTENT_TYPE}. So key delivery is a
 * normal encrypted message, not a GroupContext extension — which matters, because the
 * {@code icon_key} (0xF003) / {@code subject_key} (0xF005) extensions are Welcome-only and cannot be
 * committed without leaking the key into the GroupInfo the server receives (see
 * {@code commit_icon_subject} in ffi.rs).
 *
 * <p>The proto, verbatim from §7.8.1:
 *
 * <pre>
 *   message FileEncryptionInfo {
 *     bytes key_material = 1; bytes initialization_vector = 2; bytes hmac_tag = 3;
 *     Algorithm algorithm = 4; fixedint32 file_length_hint = 5;
 *   }
 *   message FileMetadata { string file_name = 1; string content_type = 2;
 *                          FileEncryptionInfo encryption_info = 3; }
 *   message FileInfo { FileMetadata file = 1; thumbnail = 2; subject = 3; icon = 4; }
 * </pre>
 *
 * <p><b>Two details that are easy to get wrong and impossible to notice.</b>
 *
 * <ol>
 *   <li>{@code file_length_hint} is {@code fixedint32} — protobuf <b>fixed32</b> (wire type 5,
 *       little-endian), not a varint. A varint encodes identically for values under 128, so this
 *       would pass every small test and fail on the first icon larger than 127 bytes.</li>
 *   <li>{@code file_name} is not a label: Annex C.2 feeds it in as the KDF's {@code Info}. The name
 *       in this proto and the name the content was encrypted under must be the same string or the
 *       receiver derives different keys and sees only a tag mismatch. {@link #icon} and
 *       {@link #subject} therefore encrypt and describe in one call, so the two cannot drift.</li>
 * </ol>
 */
public final class RccFileInfo {

    private RccFileInfo() { }

    /** §7.8.1 {@code Algorithm}. */
    public static final int ALGORITHM_UNSPECIFIED = 0;
    /** Annex C.2 exactly: AES-CTR with an HMAC-SHA256 256-bit tag. */
    public static final int ALGORITHM_AES256_CTR_HMAC_SHA256_256TAG = 1;

    /** §7.8.1 — the content type of the MLS message carrying an encoded {@code FileInfo}. */
    public static final String CONTENT_TYPE = "message/mls-rcs-file-info";

    /**
     * The content type an ENCRYPTED group subject/icon declares — the literal {@code message/mls-ft}.
     *
     * <p>Pinned by Google Messages' own re-encryption guard: it throws
     * "subject is already encrypted somehow" when it sees exactly this string, so this value IS the
     * encrypted marker. The name is not decoration — the encrypted subject rides as an MLS
     * File-Transfer blob whose symmetric key is delivered separately over the
     * {@value #CONTENT_TYPE} leg, which is why the two content types are siblings.
     *
     * <p>An earlier guess here ({@code application/vnd.gsma.rcs-mls-encrypted}) was the whole reason
     * an otherwise correct atomic ChangeGroupProfile was refused INVALID_ARGUMENT: the server
     * validates the declared type. Nothing named {@code vnd.gsma} appears anywhere in Google Messages.
     *
     * <p>Do not confuse this with the PLAINTEXT input allow-list. Before encryption the subject must
     * be {@code text/plain}, {@code text/plain; charset=utf-8} or empty — anything else is rejected
     * as "not plain text". That list governs the input; this constant governs what goes on the wire.
     */
    public static final String CONTENT_TYPE_ENCRYPTED = "message/mls-ft";

    /** {@code FileInfo} slot numbers — also the proto field numbers. */
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
        /** The binary {@code FileInfo} — the body of the MLS message ({@value #CONTENT_TYPE}). */
        public final byte[] fileInfo;
        /** The Annex C.2 ciphertext: upload it (icon) or Base64 it into the MSRP (subject). */
        public final byte[] ciphertext;
        Sealed(final byte[] fileInfo, final byte[] ciphertext) {
            this.fileInfo = fileInfo;
            this.ciphertext = ciphertext;
        }
    }

    /**
     * §9.7.1.1 + §9.7.1.4 — encrypt a group icon and describe it in the {@code icon} slot.
     *
     * <p>Mints a fresh one-shot key (Annex C.2 requires one key per file), encrypts under the
     * {@code group_icon} filename, and emits the matching {@code FileInfo}. Doing both here is what
     * guarantees the KDF's {@code Info} and the proto's {@code file_name} agree.
     *
     * @param contentType the icon's own MIME type, e.g. {@code image/png}
     */
    public static Sealed icon(final byte[] iconBytes, final String contentType) {
        return seal(SLOT_ICON, RccFileCrypto.INFO_GROUP_ICON, iconBytes, contentType);
    }

    /** §9.7.1.2 + §9.7.1.5 — the same for the group subject, in the {@code subject} slot. */
    public static Sealed subject(final byte[] subjectUtf8, final String contentType) {
        return seal(SLOT_SUBJECT, RccFileCrypto.INFO_GROUP_SUBJECT, subjectUtf8, contentType);
    }

    /**
     * A CHAT ATTACHMENT, in the {@code file} slot.
     *
     * <p>The icon and subject above pin their file name to a spec constant because there is exactly
     * one group icon and one group subject. A chat file has a real name, and <b>that name is
     * load-bearing twice over</b>:
     *
     * <ul>
     *   <li>it is Annex C.2's {@code Info}, an input to the KDF, so the sender and receiver must use
     *       the SAME string or the receiver derives a different key and sees only a tag mismatch;</li>
     *   <li>it is {@code FileMetadata.file_name}, which is how the receiver LEARNS that string.</li>
     * </ul>
     *
     * <p>{@link #seal} passes one argument to both, which is what makes them impossible to drift
     * &mdash; the same reason {@link #icon} and {@link #subject} encrypt and describe in one call.
     *
     * <h2>⚠ DO NOT CONFUSE THIS NAME WITH THE FT-HTTP DESCRIPTOR'S {@code <file-name>}</h2>
     *
     * <p>They are two different names for two different objects and only one of them is here. This
     * one is the PLAINTEXT file's name, it is the KDF input, and it travels INSIDE the sealed
     * payload. The FT-HTTP XML's {@code <file-name>} is the literal {@code "encrypted_file"} and
     * describes the CIPHERTEXT upload &mdash; Google Messages throws on anything else there. Putting the
     * real name in the XML leaks it; putting {@code "encrypted_file"} here would make every file
     * derive the same KDF context and would not match what a peer computes.
     *
     * @param fileName the PLAINTEXT file's name &mdash; never {@code "encrypted_file"}
     * @param contentType the plaintext file's own MIME type, e.g. {@code image/jpeg}
     */
    public static Sealed file(final String fileName, final byte[] fileBytes,
            final String contentType) {
        if (fileName == null || fileName.isEmpty()) return null;
        return seal(SLOT_FILE, fileName, fileBytes, contentType);
    }

    /**
     * A chat attachment's THUMBNAIL, in the {@code thumbnail} slot.
     *
     * <p>Its own slot and its own key: Annex C.2 requires one key per file, and the thumbnail is a
     * different file that the FT-HTTP descriptor references separately. {@link #encode} carries one
     * slot per call, so a send with a preview produces TWO {@code FileInfo}s &mdash; which is what
     * the {@code MlsMessageList} wrapper's k self-delimiting entries are for.
     *
     * @param fileName the thumbnail's own name, distinct from the file's for the KDF reason above
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

    /** Encode a {@code FileInfo} carrying a single slot. */
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
     * Parse a {@code FileInfo}, returning the first populated slot. Returns {@code null} on
     * malformation — a key we cannot parse is not a key, and guessing at a partial one would hand
     * AES a wrong-length buffer.
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
     * Decrypt a payload described by a parsed {@code FileInfo}.
     *
     * <p>Uses the proto's own {@code file_name} as the Annex C.2 {@code Info}, which is the whole
     * reason the two must match, and verifies the length hint the sender declared.
     */
    public static byte[] open(final Parsed p, final byte[] ciphertext) {
        if (p == null || p.metadata == null) return null;
        final Metadata m = p.metadata;
        if (m.algorithm != ALGORITHM_AES256_CTR_HMAC_SHA256_256TAG) return null;
        return RccFileCrypto.decrypt(m.keyMaterial, m.iv, ciphertext, m.hmacTag, m.fileName,
                m.fileLengthHint);
    }

    // ---- minimal protobuf ----
    //
    // Delegated to RccProto. These were private here until §7.10/§7.13 needed the same primitives;
    // keeping thin forwarders (rather than rewriting every call site) means this file's behaviour is
    // provably unchanged by the extraction, which is what its existing tests then verify.

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

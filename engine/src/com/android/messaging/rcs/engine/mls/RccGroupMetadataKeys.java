/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;

/**
 * RCC.16 §7.13.4 {@code GroupMetadataKeys}: {@code continuity_token = 1} (bytes) and
 * {@code group_subject_icon_keys = 2} (a §7.8.1 {@code FileInfo}; RCC.16 §10.5.4 calls it
 * {@code subject_and_icon_keys}, same field). Either field may be absent. It travels as an
 * encrypted application message, so the server never sees the keys. See docs/mls/metadata.md.
 */
public final class RccGroupMetadataKeys {

    /**
     * Not yet confirmed against a peer; it follows the {@code message/mls-rcs-file-info} naming,
     * and inbound dispatch drops an unrecognised non-text type rather than rendering it.
     */
    public static final String CONTENT_TYPE = "message/mls-rcs-group-metadata-keys";

    /** Field 1, or empty. */
    public final byte[] continuityToken;
    /** Field 2, a serialised {@code FileInfo}, or empty. */
    public final byte[] subjectIconKeys;

    public RccGroupMetadataKeys(final byte[] continuityToken, final byte[] subjectIconKeys) {
        this.continuityToken = continuityToken == null ? new byte[0] : continuityToken.clone();
        this.subjectIconKeys = subjectIconKeys == null ? new byte[0] : subjectIconKeys.clone();
    }

    /** True iff this carries nothing — there is no reason to send it. */
    public boolean isEmpty() {
        return continuityToken.length == 0 && subjectIconKeys.length == 0;
    }

    public byte[] encode() {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(128);
            if (continuityToken.length > 0) RccProto.bytes(out, 1, continuityToken);
            if (subjectIconKeys.length > 0) RccProto.bytes(out, 2, subjectIconKeys);
            return out.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * An empty input parses to an empty message, not {@code null}; test usability with
     * {@link #isEmpty}.
     */
    public static RccGroupMetadataKeys parse(final byte[] proto) {
        if (proto == null) return null;
        try {
            return new RccGroupMetadataKeys(RccProto.field(proto, 1), RccProto.field(proto, 2));
        } catch (final Throwable t) {
            return null;
        }
    }

    /** The parsed {@code FileInfo} in field 2, or {@code null} if absent/unparseable. */
    public RccFileInfo.Parsed keys() {
        return subjectIconKeys.length == 0 ? null : RccFileInfo.parse(subjectIconKeys);
    }
}

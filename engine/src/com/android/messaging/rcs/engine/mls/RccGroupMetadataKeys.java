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

/**
 * RCC.16 <b>§7.13.4</b> — {@code GroupMetadataKeys}, the body that carries group secrets to a peer.
 *
 * <pre>
 *   message GroupMetadataKeys {
 *     bytes    continuity_token        = 1;
 *     FileInfo group_subject_icon_keys = 2;
 *   }
 * </pre>
 *
 * <p><b>§10.5.4 calls field 2 {@code subject_and_icon_keys}</b> while §7.13.4 calls it
 * {@code group_subject_icon_keys}. That is a naming inconsistency in the published spec, not a
 * second field — the number is 2 either way, and the number is what goes on the wire. Recorded here
 * because a reader comparing this class against §10.5.4 will otherwise think a field is missing.
 *
 * <h2>Why this is the primitive that unblocks two separate features</h2>
 *
 * This message is how BOTH the continuity token (§8.3.1.3, requested when absent) and the group
 * icon/subject keys (§9.7.1.4/.5, after the v4.0 rewrite) reach a peer. It travels as an ordinary
 * MLS PrivateMessage — encrypted to the group, invisible to the server — which is the whole point:
 * v3.0 put the icon/subject key in a GroupContext extension, where the server building the GroupInfo
 * could read it. Moving key delivery into a PrivateMessage is the fix to that confidentiality
 * defect, and it is transport-independent, so it applies to us on Tachyon even though the rest of
 * §7.13 is carrier/CPM surface.
 *
 * <p>Note the two fields are independently optional. §8.3.1.3 sends the token alone; §9.7.1.4 sends
 * the keys alone; §10.5.3 may send both. A message with neither is meaningless and
 * {@link #encode} returns an empty body for it rather than inventing a field.
 */
public final class RccGroupMetadataKeys {

    /**
     * The content type of the PrivateMessage carrying this body.
     *
     * <p>v4.0 §7.5.1.2 makes {@code SecretPayload.payload} type-dependent and names
     * {@code group_metadata_keys} as one of the variants. Until a capture pins the exact token, this
     * follows the sibling naming that §7.8.1 already establishes for the FileInfo leg
     * ({@code message/mls-rcs-file-info}) — see {@link RccFileInfo#CONTENT_TYPE}.
     *
     * <p><b>This string is a reasoned guess and is marked as one.</b> It is safe to hold because we
     * only ever SEND it to peers running our own build today, and because inbound dispatch
     * ({@link RccContentDisposition}) drops an unrecognised non-text type rather than rendering it.
     * A capture of Google Messages sending group metadata keys settles it.
     */
    public static final String CONTENT_TYPE = "message/mls-rcs-group-metadata-keys";

    /** §7.13.4 field 1 — the continuity token, or empty. */
    public final byte[] continuityToken;
    /** §7.13.4 field 2 — a serialised §7.8.1 {@code FileInfo}, or empty. */
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
     * Parse. An empty input yields an empty message rather than {@code null}: a
     * {@code GroupMetadataKeys} with no fields set is a legal proto3 encoding, and the caller's
     * "did I get anything usable" test is {@link #isEmpty}, not a null check.
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

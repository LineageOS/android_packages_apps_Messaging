/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RCC.16 §7.10.5 {@code MlsEnhancedGroupInfo}, the response to the enhanced GroupInfo pull:
 * {@code latest_epoch_identifier = 2}, {@code mls_group_info = 3}, repeated
 * {@code committed_control_messages = 4} ({@code rcs_message_id = 1},
 * {@code server_mls_rcs_message = 2}), {@code paginated_epoch_identifier = 5}; field 1 is reserved.
 * Replaying the control messages (RCC.16 §10.1.2) catches up without an external commit. The client
 * only decodes this; {@link #encode} is for tests and servers.
 */
public final class RccEnhancedGroupInfo {

    /** One entry of {@code committed_control_messages}. */
    public static final class ControlMessage {
        /** For IMDN correlation; may be empty. */
        public final String rcsMessageId;
        /** Raw bytes, parsed by the MLS router. */
        public final byte[] serverMlsRcsMessage;

        public ControlMessage(final String rcsMessageId, final byte[] serverMlsRcsMessage) {
            this.rcsMessageId = rcsMessageId == null ? "" : rcsMessageId;
            this.serverMlsRcsMessage =
                    serverMlsRcsMessage == null ? new byte[0] : serverMlsRcsMessage.clone();
        }
    }

    /** Where the server says the group is. */
    public final RccEpochIdentifier latestEpoch;
    /** The ordinary GroupInfo, for the external-commit fallback. */
    public final byte[] mlsGroupInfo;
    /** In wire order, which is the order RCC.16 §10.1.2 applies them in. */
    public final List<ControlMessage> committedControlMessages;
    /**
     * The epoch reached after the last commit in the list; {@code null} when absent. Absence means
     * abandon the enhanced path and fall back to an external commit (RCC.16 §10.1.2).
     */
    public final RccEpochIdentifier paginatedEpoch;

    public RccEnhancedGroupInfo(final RccEpochIdentifier latestEpoch, final byte[] mlsGroupInfo,
            final List<ControlMessage> committedControlMessages,
            final RccEpochIdentifier paginatedEpoch) {
        this.latestEpoch = latestEpoch;
        this.mlsGroupInfo = mlsGroupInfo == null ? new byte[0] : mlsGroupInfo.clone();
        this.committedControlMessages = Collections.unmodifiableList(new ArrayList<>(
                committedControlMessages == null ? new ArrayList<>() : committedControlMessages));
        this.paginatedEpoch = paginatedEpoch;
    }

    /** Parses the message; a reserved field 1 is skipped, not rejected. */
    public static RccEnhancedGroupInfo parse(final byte[] proto) {
        if (proto == null || proto.length == 0) return null;
        try {
            final byte[] latest = RccProto.field(proto, 2);
            final byte[] gi = RccProto.field(proto, 3);
            final byte[] paginated = RccProto.field(proto, 5);

            final List<ControlMessage> msgs = new ArrayList<>();
            // Every entry: the list is applied in sequence, so the first alone never catches up.
            for (final byte[] cm : RccProto.repeatedField(proto, 4)) {
                final byte[] id = RccProto.field(cm, 1);
                final byte[] body = RccProto.field(cm, 2);
                msgs.add(new ControlMessage(
                        id == null ? "" : new String(id, java.nio.charset.StandardCharsets.UTF_8),
                        body));
            }
            return new RccEnhancedGroupInfo(
                    latest == null ? null : RccEpochIdentifier.parse(latest),
                    gi,
                    msgs,
                    // Absent stays null, distinct from present-but-empty.
                    paginated == null ? null : RccEpochIdentifier.parse(paginated));
        } catch (final Throwable t) {
            return null;
        }
    }

    /** For round-trip tests and servers; the client never sends this. */
    public byte[] encode() {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            if (latestEpoch != null) RccProto.bytes(out, 2, latestEpoch.encode());
            if (mlsGroupInfo.length > 0) RccProto.bytes(out, 3, mlsGroupInfo);
            for (final ControlMessage cm : committedControlMessages) {
                final ByteArrayOutputStream e = new ByteArrayOutputStream(128);
                if (!cm.rcsMessageId.isEmpty()) {
                    RccProto.bytes(e, 1,
                            cm.rcsMessageId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                if (cm.serverMlsRcsMessage.length > 0) RccProto.bytes(e, 2, cm.serverMlsRcsMessage);
                RccProto.bytes(out, 4, e.toByteArray());
            }
            if (paginatedEpoch != null) RccProto.bytes(out, 5, paginatedEpoch.encode());
            return out.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }
}

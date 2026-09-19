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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RCC.16 <b>§7.10.5</b> — {@code MlsEnhancedGroupInfo}, the response to the Enhanced GroupInfo pull.
 *
 * <pre>
 *   message MlsEnhancedGroupInfo {
 *     reserved 1;
 *     EpochIdentifier         latest_epoch_identifier    = 2;
 *     MlsGroupInfo            mls_group_info             = 3;
 *     repeated EpochControlMessages committed_control_messages = 4;
 *     EpochIdentifier         paginated_epoch_identifier = 5;
 *   }
 *   message EpochControlMessages {
 *     string              rcs_message_id        = 1;
 *     ServerMlsRcsMessage server_mls_rcs_message = 2;
 *   }
 * </pre>
 *
 * <h2>What this buys, in this project's terms</h2>
 *
 * Our entire recovery story is external-commit resync, which is precisely what §10.1.2 makes the
 * <em>fallback</em>. Replaying the committed control messages catches a diverged client up
 * <b>without</b> consuming an External Commit against the quota, without a new leaf, and without the
 * roster churn that has repeatedly cost us {@code mismatched-rcs-group-state} refusals.
 *
 * <h2>The shape was already known, before we had the spec</h2>
 *
 * {@link MlsGroupInfoGate} gates a fetched GroupInfo on {@code paginatedEpochIdentifier} and
 * implements "prefer {@code latest_epoch_identifier}, fall back to the flat {@code (era, epoch)}
 * pair" — both of them §7.10.5 semantics, read off Google Messages. So this proto is not a
 * v4.0 import into a codebase that knew nothing about it; it is the name for a shape we had already
 * measured. That is also why the backfill-field reading was retracted: backfill was never in the
 * ORDINARY pull, and we were asking the wrong endpoint.
 *
 * <p>Decode-only for now. We are the client: we send an {@link RccEpochIdentifier} and read one of
 * these back. {@link #encode} exists for round-trip testing and for the lab server, and is the only
 * honest way to test a parser against a message no live server has yet sent us.
 */
public final class RccEnhancedGroupInfo {

    /** One entry of {@code committed_control_messages}. */
    public static final class ControlMessage {
        /** {@code rcs_message_id} — the RCS message id, for IMDN correlation. May be empty. */
        public final String rcsMessageId;
        /** {@code server_mls_rcs_message} — the raw bytes; parsing it belongs to the MLS router. */
        public final byte[] serverMlsRcsMessage;

        public ControlMessage(final String rcsMessageId, final byte[] serverMlsRcsMessage) {
            this.rcsMessageId = rcsMessageId == null ? "" : rcsMessageId;
            this.serverMlsRcsMessage =
                    serverMlsRcsMessage == null ? new byte[0] : serverMlsRcsMessage.clone();
        }
    }

    /** {@code latest_epoch_identifier} — where the server says the group actually is. */
    public final RccEpochIdentifier latestEpoch;
    /** {@code mls_group_info} — the ordinary GroupInfo, for the external-commit fallback. */
    public final byte[] mlsGroupInfo;
    /** {@code committed_control_messages}, IN WIRE ORDER. §10.1.2 applies them sequentially. */
    public final List<ControlMessage> committedControlMessages;
    /**
     * {@code paginated_epoch_identifier} — the epoch the client reaches after applying the last
     * Commit in the list. <b>Its ABSENCE is a control signal</b>, not a missing optional: §10.1.2
     * says that if it is not provided, abandon the enhanced path and fall back to §6.3.1 plus an
     * External Commit. {@code null} here means absent.
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

    /**
     * Parse a {@code MlsEnhancedGroupInfo}.
     *
     * <p>Field 1 is {@code reserved} and is skipped rather than rejected — a reserved field that
     * turns up on the wire is a server we do not fully understand, not a message to discard.
     */
    public static RccEnhancedGroupInfo parse(final byte[] proto) {
        if (proto == null || proto.length == 0) return null;
        try {
            final byte[] latest = RccProto.field(proto, 2);
            final byte[] gi = RccProto.field(proto, 3);
            final byte[] paginated = RccProto.field(proto, 5);

            final List<ControlMessage> msgs = new ArrayList<>();
            // repeatedField, NOT field: the contract is "apply in sequential order", so taking only
            // the first would look like a self-heal that runs and never catches up.
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
                    // Absent stays null — §10.1.2 branches on absence, so an empty-but-present
                    // identifier and a missing one must not collapse to the same value.
                    paginated == null ? null : RccEpochIdentifier.parse(paginated));
        } catch (final Throwable t) {
            return null;
        }
    }

    /** Serialise — for round-trip tests and the lab server, not for the client's own sending. */
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

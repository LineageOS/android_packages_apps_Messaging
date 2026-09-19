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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The 9-field {@code MessageContent} the {@link MlsPorts.MessageAccessor} returns.
 *
 * <p>Rework item 1.4. Wire name
 * {@code communication_synapse_security_zinnia_v2_proto.MessageContent}; field numbers and wire
 * types are established, the names below field 5 are inferred from use — no field-name string survived,
 * so {@code f2}–{@code f5} keep their numbers as names rather than inventing meanings we would then
 * reason from.
 *
 * <pre>
 *   optional bytes  content                        = 1;
 *   optional uint64 f2                             = 2;   inferred: a timestamp (ms)
 *   optional uint32 f3                             = 3;   inferred: a state/kind discriminant
 *   optional uint64 f4                             = 4;   inferred: a second timestamp
 *   optional uint32 f5                             = 5;   inferred: a count
 *   repeated ClientFtdRetryCount ftd_retry_counts   = 6;
 *   optional string client_id                      = 7;   name recovered
 *   optional MessageContent original               = 8;   SELF-RECURSIVE
 *   optional string group_id                       = 9;   name recovered
 * </pre>
 *
 * <p><b>Field 8 is the message, not a detail.</b> It is self-recursive — the original or sibling
 * message — and it is why the engine is not a pure function of its arguments: it reaches into the
 * application's message ledger, mid-decision, for resend bookkeeping it does not itself store. An
 * earlier draft put the nested child at field 10; that was invented and is refuted. The message has
 * exactly nine fields.
 *
 * <p>The type lives in the engine module and the QUERY lives in the app layer, which is the split
 * item 1.4 calls for: the shape is a protocol fact, the join is an application fact.
 */
public final class MlsMessageContent {

    /** Per-client FTD retry count — {@code {uint32 count = 1, string client_id = 2}}. */
    public static final class ClientFtdRetryCount {
        public final int count;
        public final String clientId;

        public ClientFtdRetryCount(final int count, final String clientId) {
            this.count = count;
            this.clientId = clientId == null ? "" : clientId;
        }
    }

    /**
     * Per-participant delivery status, as an IMDN state.
     *
     * <p>The ladder is Google Messages', and its order is load-bearing — a row carrying a decrypt failure
     * reports FAILED_TO_DECRYPT even if it also has a read timestamp, because the decrypt failure is
     * the thing the peer needs to act on:
     *
     * <pre>
     *   no message row OR no participant row  -> UNAVAILABLE
     *   row carries a decrypt failure         -> FAILED_TO_DECRYPT
     *   read timestamp      != 0              -> READ
     *   delivered timestamp != 0              -> DELIVERED
     *   otherwise                             -> UNAVAILABLE
     * </pre>
     */
    public enum ImdnState {
        UNSPECIFIED, UNAVAILABLE, DELIVERED, READ, FAILED_TO_DECRYPT;

        /** The ladder above, in one place, so the order cannot drift between call sites. */
        public static ImdnState of(final boolean hasRow, final boolean decryptFailed,
                final long readTs, final long deliveredTs) {
            if (!hasRow) return UNAVAILABLE;
            if (decryptFailed) return FAILED_TO_DECRYPT;
            if (readTs != 0L) return READ;
            if (deliveredTs != 0L) return DELIVERED;
            return UNAVAILABLE;
        }
    }

    public final byte[] content;
    public final long f2;
    public final int f3;
    public final long f4;
    public final int f5;
    public final List<ClientFtdRetryCount> ftdRetryCounts;
    public final String clientId;
    /** Field 8, self-recursive: the original / sibling message. Null when there is none. */
    public final MlsMessageContent original;
    public final String groupId;

    private MlsMessageContent(final Builder b) {
        this.content = b.content == null ? new byte[0] : b.content;
        this.f2 = b.f2;
        this.f3 = b.f3;
        this.f4 = b.f4;
        this.f5 = b.f5;
        this.ftdRetryCounts = Collections.unmodifiableList(
                new ArrayList<>(b.ftdRetryCounts));
        this.clientId = b.clientId == null ? "" : b.clientId;
        this.original = b.original;
        this.groupId = b.groupId == null ? "" : b.groupId;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private byte[] content;
        private long f2;
        private int f3;
        private long f4;
        private int f5;
        private final List<ClientFtdRetryCount> ftdRetryCounts = new ArrayList<>();
        private String clientId;
        private MlsMessageContent original;
        private String groupId;

        public Builder content(final byte[] v) { content = v; return this; }
        public Builder f2(final long v) { f2 = v; return this; }
        public Builder f3(final int v) { f3 = v; return this; }
        public Builder f4(final long v) { f4 = v; return this; }
        public Builder f5(final int v) { f5 = v; return this; }
        public Builder addFtdRetryCount(final int count, final String clientId) {
            ftdRetryCounts.add(new ClientFtdRetryCount(count, clientId));
            return this;
        }
        public Builder clientId(final String v) { clientId = v; return this; }
        public Builder original(final MlsMessageContent v) { original = v; return this; }
        public Builder groupId(final String v) { groupId = v; return this; }

        public MlsMessageContent build() { return new MlsMessageContent(this); }
    }

    // ---- wire encoding -------------------------------------------------------------------------
    //
    // Hand-rolled rather than generated, for the same reason the rest of this module is: the engine
    // library deliberately has no protobuf runtime dependency, and these nine fields are the whole
    // surface. "Optional with presence" is honoured by omitting a field whose value is its default,
    // which is what makes an absent f2 distinguishable from f2=0 on the wire.

    private static final int T_CONTENT = 1, T_F2 = 2, T_F3 = 3, T_F4 = 4, T_F5 = 5,
            T_FTD = 6, T_CLIENT_ID = 7, T_ORIGINAL = 8, T_GROUP_ID = 9;

    public byte[] encode() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (content.length > 0) writeBytes(out, T_CONTENT, content);
        if (f2 != 0L) writeVarintField(out, T_F2, f2);
        if (f3 != 0) writeVarintField(out, T_F3, f3 & 0xFFFFFFFFL);
        if (f4 != 0L) writeVarintField(out, T_F4, f4);
        if (f5 != 0) writeVarintField(out, T_F5, f5 & 0xFFFFFFFFL);
        for (final ClientFtdRetryCount r : ftdRetryCounts) {
            final ByteArrayOutputStream sub = new ByteArrayOutputStream();
            if (r.count != 0) writeVarintField(sub, 1, r.count & 0xFFFFFFFFL);
            if (!r.clientId.isEmpty()) {
                writeBytes(sub, 2, r.clientId.getBytes(StandardCharsets.UTF_8));
            }
            writeBytes(out, T_FTD, sub.toByteArray());
        }
        if (!clientId.isEmpty()) {
            writeBytes(out, T_CLIENT_ID, clientId.getBytes(StandardCharsets.UTF_8));
        }
        // Field 8, recursively. Depth is bounded by the caller building it, not by us: a cycle here
        // would be a cycle in the app's own message ledger.
        if (original != null) writeBytes(out, T_ORIGINAL, original.encode());
        if (!groupId.isEmpty()) {
            writeBytes(out, T_GROUP_ID, groupId.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static void writeVarintField(final ByteArrayOutputStream o, final int field,
            final long value) {
        writeVarint(o, ((long) field << 3));            // wire type 0
        writeVarint(o, value);
    }

    private static void writeBytes(final ByteArrayOutputStream o, final int field,
            final byte[] value) {
        writeVarint(o, ((long) field << 3) | 2L);       // wire type 2
        writeVarint(o, value.length);
        o.write(value, 0, value.length);
    }

    private static void writeVarint(final ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The nine-field {@code MessageContent} the {@link MlsPorts.MessageAccessor} returns. Fields 2-5
 * keep their numbers as names because their meanings are inferred, not known. Field 8 is
 * self-recursive (the original or sibling message), which is why the engine reads the app's
 * message ledger mid-decision.
 *
 * <pre>
 *   optional bytes  content                        = 1;
 *   optional uint64 f2                             = 2;   inferred: a timestamp (ms)
 *   optional uint32 f3                             = 3;   inferred: a state/kind discriminant
 *   optional uint64 f4                             = 4;   inferred: a second timestamp
 *   optional uint32 f5                             = 5;   inferred: a count
 *   repeated ClientFtdRetryCount ftd_retry_counts   = 6;
 *   optional string client_id                      = 7;
 *   optional MessageContent original               = 8;   self-recursive
 *   optional string group_id                       = 9;
 * </pre>
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
     * Per-participant delivery status as an IMDN state. The order is load-bearing: a decrypt
     * failure wins over a read timestamp, because it is what the peer must act on.
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

        /** The ladder above, in one place. */
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
    // Hand-rolled: the engine has no protobuf runtime. A field at its default value is omitted.

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
        // Depth is bounded by the caller; a cycle here would be a cycle in the app's ledger.
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

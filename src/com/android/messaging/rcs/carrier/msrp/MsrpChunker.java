/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a message body into MSRP SEND chunks (RFC 4975 §5.1). Each chunk copies the prototype's
 * headers, gets its own transaction id and a {@code Byte-Range}, and ends {@code +} except the
 * last, which ends {@code $}. An empty body is one zero-byte SEND.
 */
public final class MsrpChunker {

    public static final int DEFAULT_MAX_CHUNK_BYTES = 2048;

    private final int maxChunkBytes;

    public MsrpChunker() {
        this(DEFAULT_MAX_CHUNK_BYTES);
    }

    public MsrpChunker(int maxChunkBytes) {
        if (maxChunkBytes <= 0) {
            throw new IllegalArgumentException("maxChunkBytes must be positive");
        }
        this.maxChunkBytes = maxChunkBytes;
    }

    public int getMaxChunkBytes() {
        return maxChunkBytes;
    }

    /**
     * Chunks a SEND prototype, in order. Headers other than {@code Byte-Range} are copied verbatim
     * in their insertion order.
     */
    public List<MsrpMessage> chunk(MsrpMessage prototype) {
        if (prototype.getKind() != MsrpMessage.Kind.REQUEST
                || prototype.getMethod() != MsrpMethod.SEND) {
            throw new IllegalArgumentException(
                    "MsrpChunker only chunks SEND requests");
        }
        byte[] body = prototype.getBody();
        int total = body == null ? 0 : body.length;

        List<MsrpMessage> out = new ArrayList<>();
        if (total == 0) {
            MsrpMessage.Builder b = prototype.toBuilder();
            // The prototype's transaction id is a placeholder.
            b.transactionId(MsrpTransactionId.next());
            b.body(null);
            b.byteRange(buildByteRange(1, 0, 0));
            b.endFlag(MsrpEndFlag.COMPLETE);
            out.add(b.buildUnchecked());
            return out;
        }

        int offset = 0;
        while (offset < total) {
            int end = Math.min(offset + maxChunkBytes, total);
            byte[] chunkBody = new byte[end - offset];
            System.arraycopy(body, offset, chunkBody, 0, end - offset);

            MsrpMessage.Builder b = prototype.toBuilder();
            b.transactionId(MsrpTransactionId.next());
            b.body(chunkBody);
            // RFC 4975 §5.1: ranges are 1-based and inclusive.
            b.byteRange(buildByteRange(offset + 1, end, total));
            b.endFlag(end == total ? MsrpEndFlag.COMPLETE : MsrpEndFlag.CONTINUATION);
            out.add(b.buildUnchecked());
            offset = end;
        }
        return out;
    }

    private static String buildByteRange(long start, long end, long total) {
        return String.format(Locale.ROOT, "%d-%d/%d", start, end, total);
    }
}

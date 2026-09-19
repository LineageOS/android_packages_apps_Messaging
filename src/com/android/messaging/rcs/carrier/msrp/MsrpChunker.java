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
package com.android.messaging.rcs.carrier.msrp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a logical MSRP message body into one-or-more wire SEND chunks per
 * RFC 4975 §5.1.
 *
 * <p>Each output chunk:
 * <ul>
 *   <li>shares the same Message-ID, To-Path, From-Path, Content-Type, and
 *       Success-Report / Failure-Report settings as the input prototype;</li>
 *   <li>gets a fresh per-chunk transaction-id (RFC 4975 §7.1.1 — each chunk
 *       is its own MSRP transaction);</li>
 *   <li>gets a {@code Byte-Range: start-end/total} header naming the
 *       byte-range this chunk covers;</li>
 *   <li>ends with {@code +} for every chunk except the last, which ends
 *       with {@code $}.</li>
 * </ul>
 *
 * <p>The threshold is configurable: Google Messages adapts to peer pushback (the 413
 * Request Entity Too Large response per RFC 4975 §7.4 / §13). Our default
 * 2048-byte threshold matches what Google Messages was observed to use, as a
 * reasonable starting point; the transport will tune via 413 retry once it's
 * wired up.
 *
 * <p>Empty bodies are emitted as a single zero-byte SEND — this is the
 * spec-compliant keepalive (RFC 4975 §6.4 / §11) and Google Messages'
 * "initial empty SEND" handshake.
 */
public final class MsrpChunker {

    /** Default chunk threshold in bytes: 2048. Encoder splits bodies > this. */
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
     * Split the body of {@code prototype} into one-or-more SEND chunks. The
     * prototype must be a SEND request; its body is the logical message
     * payload (may be null/empty). Headers other than {@code Byte-Range} are
     * copied to every chunk verbatim, in their original insertion order.
     *
     * <p>The returned list preserves chunk order; element 0 is the first
     * chunk (offset 1), the last element carries the {@code $} end-flag.
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
            // Empty SEND (keepalive / handshake).
            MsrpMessage.Builder b = prototype.toBuilder();
            // Force a fresh per-chunk transaction-id (the prototype's tid is
            // a placeholder — callers usually don't fill it in).
            b.transactionId(MsrpTransactionId.next());
            b.body(null);
            b.byteRange(buildByteRange(1, 0, 0)); // RFC 4975 §5.1: "0-0/0"-style
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
            // RFC 4975 §5.1: ranges are 1-indexed, end is inclusive.
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

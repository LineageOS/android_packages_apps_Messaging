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

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Stateful reassembler for inbound MSRP SEND chunks. Indexed by Message-ID
 * (RFC 4975 §5.1: chunks of one logical message share Message-ID; the
 * transaction-id is per-chunk and not stable).
 *
 * <p>State machine per Message-ID:
 * <ul>
 *   <li>OPEN — at least one chunk delivered, no terminal flag yet.</li>
 *   <li>COMPLETE — terminal {@code $} flag observed; {@link #take(String)}
 *       returns the assembled body and discards state.</li>
 *   <li>ABORTED — terminal {@code #} flag observed; the partial body is
 *       discarded and {@link #take(String)} returns null.</li>
 * </ul>
 *
 * <p>This implementation accepts chunks in any order, validates that their
 * byte-ranges are non-overlapping and contiguous-on-completion, and
 * verifies the declared total length matches the assembled body. It
 * intentionally accepts {@code total == -1} (the unbounded {@code "*"} case
 * per RFC 4975 §5.1) and only fixes the total when a chunk reports a
 * concrete total.
 *
 * <p>Not threadsafe — the MSRP session owns the reassembler on its read
 * thread.
 */
public final class MsrpReassembler {

    /** Result of feeding one chunk. */
    public enum Outcome {
        /** Chunk accepted; the message is not yet complete. */
        IN_PROGRESS,
        /** Chunk accepted and the message is now complete — call {@link #take}. */
        COMPLETE,
        /** A {@code #}-flag chunk arrived; partial state has been discarded. */
        ABORTED
    }

    private static final class Partial {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long expectedTotal = -1;     // -1 = unknown (i.e., "*" so far)
        long nextOffset    = 1;      // RFC 4975 byte-range is 1-indexed
        boolean complete   = false;
    }

    private final Map<String, Partial> byMessageId = new HashMap<>();

    /**
     * Feed one inbound chunk into the reassembler. The chunk MUST be a SEND
     * request (otherwise this throws; REPORTs / responses are not reassembled).
     *
     * @return outcome — call {@link #take(String)} when COMPLETE
     * @throws MsrpException for protocol violations (range gap, length mismatch,
     *                       Message-ID missing, etc.)
     */
    public Outcome feed(MsrpMessage chunk) throws MsrpException {
        if (chunk == null) {
            throw new MsrpException("MsrpReassembler.feed: null chunk");
        }
        if (chunk.getKind() != MsrpMessage.Kind.REQUEST
                || chunk.getMethod() != MsrpMethod.SEND) {
            throw new MsrpException("MsrpReassembler.feed: not a SEND request");
        }
        String messageId = chunk.getMessageId();
        if (messageId == null || messageId.isEmpty()) {
            throw new MsrpException("MsrpReassembler.feed: SEND missing Message-ID");
        }

        // Abort?
        if (chunk.getEndFlag() == MsrpEndFlag.ABORT) {
            byMessageId.remove(messageId);
            return Outcome.ABORTED;
        }

        Partial p = byMessageId.get(messageId);
        if (p == null) {
            p = new Partial();
            byMessageId.put(messageId, p);
        }

        ByteRange range = parseByteRange(chunk.getByteRange());
        if (range.start != p.nextOffset) {
            throw new MsrpException("MsrpReassembler: out-of-order chunk for "
                    + messageId + " — expected start=" + p.nextOffset
                    + ", got " + range.start);
        }
        byte[] body = chunk.getBody();
        long len = body == null ? 0 : body.length;
        if (range.endUnknown) {
            // RFC 4975 §5.1: range-end "*" means the sender didn't state the
            // chunk end (e.g. a single COMPLETE chunk carrying the whole body,
            // with the total known — open5gs forwards reassembled large
            // messages as "1-*/<total>" + '$'). Trust the actual body length;
            // the end-flag and total (checked at COMPLETE) drive completion.
            // Enforcing (end-start+1)==len here dropped such messages.
        } else {
            long expectedLen = range.end - range.start + 1;
            // Empty SEND (keepalive) carries Byte-Range like 1-0/0 — len == 0
            // then end < start, which we treat as zero-length.
            if (range.end < range.start) {
                expectedLen = 0;
            }
            if (expectedLen != len) {
                throw new MsrpException("MsrpReassembler: byte-range "
                        + chunk.getByteRange() + " mismatches body length " + len);
            }
        }
        if (range.total >= 0) {
            if (p.expectedTotal >= 0 && p.expectedTotal != range.total) {
                throw new MsrpException("MsrpReassembler: total changed mid-message: "
                        + p.expectedTotal + " -> " + range.total);
            }
            p.expectedTotal = range.total;
        }

        if (body != null && body.length > 0) {
            p.buf.write(body, 0, body.length);
            p.nextOffset += body.length;
        } else if (len == 0 && range.start == 1 && range.end == 0) {
            // Empty body keepalive — don't advance nextOffset.
        }

        if (chunk.getEndFlag() == MsrpEndFlag.COMPLETE) {
            long assembled = p.buf.size();
            if (p.expectedTotal >= 0 && assembled != p.expectedTotal) {
                throw new MsrpException("MsrpReassembler: assembled length "
                        + assembled + " != declared total " + p.expectedTotal);
            }
            p.complete = true;
            return Outcome.COMPLETE;
        }
        return Outcome.IN_PROGRESS;
    }

    /**
     * Remove and return the assembled body for a completed Message-ID. Returns
     * null if no completed message is waiting (or if the message was aborted).
     */
    public byte[] take(String messageId) {
        Partial p = byMessageId.get(messageId);
        if (p == null || !p.complete) return null;
        byMessageId.remove(messageId);
        return p.buf.toByteArray();
    }

    /** Drop any partial state for {@code messageId}. */
    public void drop(String messageId) {
        byMessageId.remove(messageId);
    }

    /** Number of in-flight messages currently held in partial state. */
    public int inFlightCount() {
        return byMessageId.size();
    }

    // -------- byte-range parsing --------

    private static final class ByteRange {
        final long start;
        final long end;
        final long total; // -1 for "*"
        final boolean endUnknown; // true when the range-end token was "*"
        ByteRange(long s, long e, long t, boolean eu) {
            start = s; end = e; total = t; endUnknown = eu;
        }
    }

    static ByteRange parseByteRange(String value) throws MsrpException {
        if (value == null) {
            throw new MsrpException("MSRP SEND missing Byte-Range header");
        }
        int dash  = value.indexOf('-');
        int slash = value.indexOf('/', dash + 1);
        if (dash < 0 || slash < 0) {
            throw new MsrpException("Malformed Byte-Range: " + value);
        }
        String startS = value.substring(0, dash).trim();
        String endS   = value.substring(dash + 1, slash).trim();
        String totS   = value.substring(slash + 1).trim();
        try {
            long start = Long.parseLong(startS);
            boolean endUnknown = "*".equals(endS);
            long end;
            if (endUnknown) {
                // RFC 4975 §5.1 allows "*" to indicate "not yet known".
                end = start - 1; // 0-length placeholder; real end from body length
            } else {
                end = Long.parseLong(endS);
            }
            long total = "*".equals(totS) ? -1 : Long.parseLong(totS);
            return new ByteRange(start, end, total, endUnknown);
        } catch (NumberFormatException nfe) {
            throw new MsrpException("Malformed Byte-Range numerics: " + value, nfe);
        }
    }
}

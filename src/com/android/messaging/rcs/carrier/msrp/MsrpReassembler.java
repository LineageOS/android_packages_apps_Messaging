/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Reassembles inbound MSRP SEND chunks by Message-ID (RFC 4975 §5.1; the transaction id is per
 * chunk). Chunks must arrive in order, and the assembled length must match a declared total; a
 * {@code #} end-flag discards the message. Not thread-safe: owned by the session's read thread.
 */
public final class MsrpReassembler {

    public enum Outcome {
        IN_PROGRESS,
        /** The message is complete; call {@link #take}. */
        COMPLETE,
        /** Partial state was discarded. */
        ABORTED
    }

    private static final class Partial {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long expectedTotal = -1;     // -1: "*" so far
        long nextOffset    = 1;      // 1-based
        boolean complete   = false;
    }

    private final Map<String, Partial> byMessageId = new HashMap<>();

    /**
     * Feeds one SEND chunk.
     *
     * @throws MsrpException on a range gap, a length mismatch, a missing Message-ID or a non-SEND
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
            // RFC 4975 §5.1: an end of "*" is unstated, as in a whole body sent as "1-*/<total>"
            // with '$'. Trust the body length; the end-flag and total decide completion.
        } else {
            long expectedLen = range.end - range.start + 1;
            // An empty keepalive SEND carries a range such as 1-0/0.
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

    /** Removes and returns a completed body; null if none is complete or it was aborted. */
    public byte[] take(String messageId) {
        Partial p = byMessageId.get(messageId);
        if (p == null || !p.complete) return null;
        byMessageId.remove(messageId);
        return p.buf.toByteArray();
    }

    public void drop(String messageId) {
        byMessageId.remove(messageId);
    }

    public int inFlightCount() {
        return byMessageId.size();
    }

    private static final class ByteRange {
        final long start;
        final long end;
        final long total; // -1 for "*"
        final boolean endUnknown;
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
                end = start - 1; // the real end comes from the body length
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

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming MSRP framer: returns each message once its end-line
 * ({@code -------<tid><flag>}, RFC 4975 §5.1) has arrived. Not thread-safe.
 */
public final class MsrpFrameReader {

    private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

    /** Appends socket bytes and returns the frames completed so far, possibly none. */
    public List<MsrpMessage> append(byte[] data, int off, int len) throws MsrpException {
        if (data != null && len > 0) {
            buf.write(data, off, len);
        }
        return drain();
    }

    public List<MsrpMessage> append(byte[] data) throws MsrpException {
        return append(data, 0, data == null ? 0 : data.length);
    }

    private List<MsrpMessage> drain() throws MsrpException {
        List<MsrpMessage> out = new ArrayList<>();
        byte[] all = buf.toByteArray();
        int p = 0;
        while (true) {
            int next = scanFrameEnd(all, p, all.length);
            if (next < 0) break;
            byte[] slice = new byte[next - p];
            System.arraycopy(all, p, slice, 0, slice.length);
            out.add(MsrpMessage.parse(slice));
            p = next;
        }
        if (p > 0) {
            buf.reset();
            if (p < all.length) {
                buf.write(all, p, all.length - p);
            }
        }
        return out;
    }

    /** Offset just past the first complete frame's end-line, or -1 if none is complete yet. */
    private static int scanFrameEnd(byte[] bytes, int off, int end) throws MsrpException {
        int slEnd = findLineEnd(bytes, off, end);
        if (slEnd < 0) return -1;
        String startLine = new String(bytes, off, slEnd - off, MsrpMessage.UTF_8);
        if (!startLine.startsWith("MSRP ")) {
            throw new MsrpException("MsrpFrameReader: not an MSRP start-line: " + startLine);
        }
        int firstSp  = startLine.indexOf(' ');
        int secondSp = startLine.indexOf(' ', firstSp + 1);
        if (secondSp < 0) {
            throw new MsrpException("MsrpFrameReader: bad start-line: " + startLine);
        }
        String tid = startLine.substring(firstSp + 1, secondSp);

        byte[] needle = (MsrpMessage.DASHES + tid).getBytes(MsrpMessage.UTF_8);
        int p = advancePastLineEnd(bytes, slEnd, end);
        while (p < end) {
            int lineEnd = findLineEnd(bytes, p, end);
            if (lineEnd < 0) return -1;
            int lineLen = lineEnd - p;
            if (lineLen == needle.length + 1) {
                boolean ok = true;
                for (int i = 0; i < needle.length; i++) {
                    if (bytes[p + i] != needle[i]) { ok = false; break; }
                }
                if (ok) {
                    byte flag = bytes[p + needle.length];
                    if (flag == '$' || flag == '+' || flag == '#') {
                        return advancePastLineEnd(bytes, lineEnd, end);
                    }
                }
            }
            p = advancePastLineEnd(bytes, lineEnd, end);
        }
        return -1;
    }

    private static int findLineEnd(byte[] bytes, int off, int end) {
        for (int i = off; i < end; i++) {
            byte v = bytes[i];
            if (v == '\r' && i + 1 < end && bytes[i + 1] == '\n') return i;
            if (v == '\n') return i;
        }
        return -1;
    }

    private static int advancePastLineEnd(byte[] bytes, int lineEnd, int end) {
        if (lineEnd < end && bytes[lineEnd] == '\r'
                && lineEnd + 1 < end && bytes[lineEnd + 1] == '\n') {
            return lineEnd + 2;
        }
        return lineEnd + 1;
    }
}

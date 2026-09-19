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

/**
 * Streaming framer: feed it byte chunks as they arrive off the TCP/TLS
 * socket; it returns whole MSRP messages as soon as each frame's end-line
 * is observed.
 *
 * <p>The contract is identical to a length-prefixed framer's, but MSRP isn't
 * length-prefixed — frames are delimited by the per-transaction end-line
 * ({@code "-------<tid><flag>\r\n"}). We must therefore parse the start-line
 * to learn the tid, then scan forward for the matching end-line.
 *
 * <p>Not threadsafe.
 */
public final class MsrpFrameReader {

    private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

    /**
     * Append more bytes from the socket; returns whatever complete frames are
     * available now (may be empty). Caller must continue calling until socket
     * close.
     */
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
        // Compact buf to what's left unconsumed.
        if (p > 0) {
            buf.reset();
            if (p < all.length) {
                buf.write(all, p, all.length - p);
            }
        }
        return out;
    }

    /**
     * Locate the byte just past the end-line of the first complete frame
     * starting at {@code off}. Returns -1 if the buffer doesn't yet contain
     * a complete frame.
     */
    private static int scanFrameEnd(byte[] bytes, int off, int end) throws MsrpException {
        // Find start-line CRLF/LF to extract the tid.
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

        // Scan for the line that IS "-------<tid><flag>" — that's the end-line.
        byte[] needle = (MsrpMessage.DASHES + tid).getBytes(MsrpMessage.UTF_8);
        int p = advancePastLineEnd(bytes, slEnd, end);
        while (p < end) {
            int lineEnd = findLineEnd(bytes, p, end);
            if (lineEnd < 0) return -1; // not yet — need more data
            int lineLen = lineEnd - p;
            if (lineLen == needle.length + 1) {
                // Maybe the end-line?
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

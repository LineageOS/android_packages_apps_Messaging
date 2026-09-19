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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One decoded result from a repeated-result engine call, and the wire format they arrive in
 * (rework item 6.6, §10.5).
 *
 * <h2>The format, and why it is written down here rather than in the Rust</h2>
 *
 * <p>The engine encodes this in Rust and the host decodes it in Java, so the definition exists twice
 * whatever we do. Putting the normative statement on the side that can be host-tested means the
 * decoder is checkable without a device, and a mismatch shows up as a failing test rather than as an
 * empty list on a phone.
 *
 * <pre>
 * list    := len_prefixed(record)*          u32 big-endian lengths, as everywhere else in this FFI
 * record0 := [ FORMAT_VERSION ]             exactly one byte
 * recordN := len_prefixed([ hdr, ctx, gid, aux, payload ])
 *   hdr     := [ status ]                   the MlsProcStatus wire value
 *   ctx     := the context id, UTF-8        which operation this result belongs to
 *   gid     := the MLS group id             empty when the result names none
 *   aux     := proposal type, u16 BE        present only for status 2 (PROPOSAL)
 *   payload := decrypted plaintext          present only for status 0 (APPLICATION)
 * </pre>
 *
 * <h2>Why a version byte on a purely internal format</h2>
 *
 * <p>Both APKs embed the {@code .so}, and they are installed separately — so a device can and does
 * run a Java side and a native side built at different times. That skew is not hypothetical here:
 * {@code MlsInvariantScan} already carries a {@code NEVER} marker for it (<i>"UNHANDLED MLS RESULT …
 * a .so/Java version skew"</i>). Without a version byte the skew presents as a garbage decode, which
 * looks like a corrupt message rather than a mismatched build.
 *
 * <p><b>An unknown version is a THROW, not a best-effort parse.</b> Guessing at a format we do not
 * know produces results attributed to the wrong context, and acting on someone else's result is
 * strictly worse than not acting.
 */
public final class MlsEngineResult {

    /** Bump when the record layout changes. The decoder refuses anything else. */
    public static final int FORMAT_VERSION = 1;

    /** Which operation this result belongs to. Empty is legal and means "the caller's". */
    public final String contextId;
    /** The {@link MlsProcStatus} wire value. */
    public final int status;
    /** For {@code status == 2}, the RFC 9420 proposal type; {@code -1} otherwise. */
    public final int proposalType;
    private final byte[] mGroupId;
    private final byte[] mPayload;

    public MlsEngineResult(final String contextId, final int status, final int proposalType,
            final byte[] groupId, final byte[] payload) {
        this.contextId = contextId == null ? "" : contextId;
        this.status = status;
        this.proposalType = proposalType;
        mGroupId = copy(groupId);
        mPayload = copy(payload);
    }

    public byte[] groupId() { return copy(mGroupId); }

    /** The decrypted plaintext for an application message; empty otherwise. */
    public byte[] payload() { return copy(mPayload); }

    /**
     * Decode a repeated-result list.
     *
     * @return the results in engine order; never {@code null}
     * @throws IllegalStateException on an unknown format version, or on a record the layout cannot
     *         account for — both mean the two sides disagree, and a partial parse would attribute
     *         results to the wrong context
     */
    public static List<MlsEngineResult> decodeList(final byte[] blob) {
        final List<byte[]> records = split(blob);
        if (records.isEmpty()) {
            throw new IllegalStateException("MlsEngineResult: empty result blob — the engine "
                    + "returned nothing at all, not even a version byte");
        }
        final byte[] header = records.get(0);
        if (header.length != 1) {
            throw new IllegalStateException("MlsEngineResult: malformed version record ("
                    + header.length + " bytes, expected 1) — this is a .so/Java build skew, not a "
                    + "corrupt message");
        }
        final int version = header[0] & 0xFF;
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException("MlsEngineResult: unknown format version " + version
                    + " (this build reads " + FORMAT_VERSION + ") — the .so and the Java side were "
                    + "built at different times. Refusing to guess: a wrong parse attributes results "
                    + "to the wrong context.");
        }
        final List<MlsEngineResult> out = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            final List<byte[]> f = split(records.get(i));
            if (f.size() < 5) {
                throw new IllegalStateException("MlsEngineResult: record " + i + " has " + f.size()
                        + " fields, expected 5 — version " + version + " says otherwise, so the "
                        + "encoder and this decoder disagree");
            }
            final byte[] hdr = f.get(0);
            if (hdr.length != 1) {
                throw new IllegalStateException("MlsEngineResult: record " + i
                        + " has a " + hdr.length + "-byte header, expected 1");
            }
            final byte[] aux = f.get(3);
            final int proposalType = aux.length >= 2
                    ? ((aux[0] & 0xFF) << 8) | (aux[1] & 0xFF)
                    : -1;
            out.add(new MlsEngineResult(new String(f.get(1), StandardCharsets.UTF_8),
                    hdr[0] & 0xFF, proposalType, f.get(2), f.get(4)));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Encode a list in the same format the engine emits.
     *
     * <p>Exists so the decoder can be tested against bytes built by something other than itself —
     * a decoder tested only against its own encoder proves the two agree, not that either is right,
     * so the fixtures in the test are hand-written bytes and this is used for the round-trip half.
     */
    public static byte[] encodeList(final List<MlsEngineResult> results) {
        final List<byte[]> records = new ArrayList<>();
        records.add(new byte[] { (byte) FORMAT_VERSION });
        if (results != null) {
            for (final MlsEngineResult r : results) {
                if (r == null) continue;
                final byte[] aux = r.proposalType < 0 ? new byte[0]
                        : new byte[] { (byte) (r.proposalType >> 8), (byte) r.proposalType };
                records.add(join(new byte[][] {
                        { (byte) r.status },
                        r.contextId.getBytes(StandardCharsets.UTF_8),
                        r.groupId(),
                        aux,
                        r.payload(),
                }));
            }
        }
        return join(records.toArray(new byte[0][]));
    }

    @Override public String toString() {
        return "engineResult{ctx=" + contextId + " status=" + status
                + (proposalType < 0 ? "" : " prop=0x" + Integer.toHexString(proposalType))
                + " gid=" + mGroupId.length + "B payload=" + mPayload.length + "B}";
    }

    // ---- the same u32-big-endian framing the rest of this FFI uses ----

    private static List<byte[]> split(final byte[] b) {
        final List<byte[]> out = new ArrayList<>();
        if (b == null) return out;
        int i = 0;
        while (b.length - i >= 4) {
            final long n = ((long) (b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16)
                    | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (n > b.length - i - 4L) break;
            final byte[] part = new byte[(int) n];
            System.arraycopy(b, i + 4, part, 0, (int) n);
            out.add(part);
            i += 4 + (int) n;
        }
        return out;
    }

    private static byte[] join(final byte[][] parts) {
        int total = 0;
        for (final byte[] p : parts) total += 4 + p.length;
        final byte[] out = new byte[total];
        int i = 0;
        for (final byte[] p : parts) {
            out[i] = (byte) (p.length >> 24);
            out[i + 1] = (byte) (p.length >> 16);
            out[i + 2] = (byte) (p.length >> 8);
            out[i + 3] = (byte) p.length;
            System.arraycopy(p, 0, out, i + 4, p.length);
            i += 4 + p.length;
        }
        return out;
    }

    private static byte[] copy(final byte[] b) {
        if (b == null) return new byte[0];
        final byte[] out = new byte[b.length];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }
}

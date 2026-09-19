/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.List;

/**
 * The artifact bundle the native engine returns for each group operation, a flat list of
 * length-prefixed records:
 *
 * <pre>
 *   [0] welcome        [1] commit      [2] groupInfo   [3] tag (32B epoch_authenticator)
 *   [4] groupId        [5] ratchetTree
 *   [6] era            u32 big-endian, the era the engine built at
 *   [7] welcomeAction  u32 big-endian, what the engine decided the operation was
 *   [8] admitted       nested len-prefixed ASCII MSISDNs, arm 3 only
 * </pre>
 *
 * <p>Slots are append-only (Java and native build separately). A short bundle decodes absent slots
 * to {@code era = -1} and {@code welcomeAction = null}, never 0, which is a real value.
 * See docs/mls/rust-core.md.
 */
public final class MlsArtifactBundle {

    public static final int SLOT_ERA = 6;
    public static final int SLOT_WELCOME_ACTION = 7;
    public static final int SLOT_ADMITTED = 8;

    private MlsArtifactBundle() {}

    /** Tolerates any length; null when {@code bundle} is null, the engine's failure report. */
    public static MlsGroupArtifacts decode(final byte[] bundle) {
        if (bundle == null) return null;
        final List<byte[]> p = splitLenPrefixed(bundle);
        return new MlsGroupArtifacts(at(p, 0), at(p, 1), at(p, 2), at(p, 3), at(p, 4), at(p, 5),
                beU32(p, SLOT_ERA),
                p.size() > SLOT_WELCOME_ACTION
                        ? MlsWelcomeAction.fromWire((int) beU32(p, SLOT_WELCOME_ACTION)) : null,
                p.size() > SLOT_ADMITTED ? asciiList(p.get(SLOT_ADMITTED)) : null);
    }

    public static byte[] at(final List<byte[]> l, final int i) {
        return i < l.size() ? l.get(i) : new byte[0];
    }

    /** A 4-byte big-endian slot, or -1 when absent or of the wrong width. */
    public static long beU32(final List<byte[]> p, final int i) {
        if (i >= p.size()) return -1L;
        final byte[] b = p.get(i);
        if (b == null || b.length != 4) return -1L;
        return ((long) (b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    public static List<String> asciiList(final byte[] blob) {
        final List<String> out = new ArrayList<>();
        for (final byte[] b : splitLenPrefixed(blob)) {
            if (b != null && b.length > 0) {
                out.add(new String(b, java.nio.charset.StandardCharsets.US_ASCII));
            }
        }
        return out;
    }

    /** Splits {@code [u32 BE len][bytes]…}; a truncated tail stops the scan, keeping the rest. */
    public static List<byte[]> splitLenPrefixed(final byte[] b) {
        final List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (b != null && i + 4 <= b.length) {
            final int n = ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16)
                    | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
            if (n < 0 || i + 4 + n > b.length) break;
            final byte[] rec = new byte[n];
            System.arraycopy(b, i + 4, rec, 0, n);
            out.add(rec);
            i += 4 + n;
        }
        return out;
    }

    public static byte[] joinLenPrefixed(final List<byte[]> parts) {
        int total = 0;
        for (final byte[] p : parts) total += 4 + (p == null ? 0 : p.length);
        final byte[] out = new byte[total];
        int i = 0;
        for (final byte[] p : parts) {
            final int n = p == null ? 0 : p.length;
            out[i] = (byte) (n >>> 24); out[i + 1] = (byte) (n >>> 16);
            out[i + 2] = (byte) (n >>> 8); out[i + 3] = (byte) n;
            if (n > 0) System.arraycopy(p, 0, out, i + 4, n);
            i += 4 + n;
        }
        return out;
    }
}

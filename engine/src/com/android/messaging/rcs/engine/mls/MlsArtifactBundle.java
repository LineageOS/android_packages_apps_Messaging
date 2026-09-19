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

import java.util.ArrayList;
import java.util.List;

/**
 * The engine→host artifact bundle's SLOT LAYOUT, in one place and with no native dependency.
 *
 * <p>Every group operation the native engine performs comes back as a flat list of length-prefixed
 * records. The first six have been stable since the transport was written; slots 6 onward were added
 * when the engine started deciding the era instead of being told one.
 *
 * <pre>
 *   [0] welcome        [1] commit      [2] groupInfo   [3] tag (32B epoch_authenticator)
 *   [4] groupId        [5] ratchetTree
 *   [6] era            u32 big-endian — what the engine ACTUALLY built at
 *   [7] welcomeAction  u32 big-endian — what the engine decided the op WAS
 *   [8] admitted       nested len-prefixed ASCII MSISDNs, arm 3 only
 * </pre>
 *
 * <h2>Why slots are appended and never reordered</h2>
 *
 * <p>The layout is positional and both ends are compiled separately — the Rust staticlib is a
 * checked-in prebuilt, so a Java build and a native build can be out of step by design. Appending
 * keeps every existing index byte-stable, which is how the ratchet tree arrived at slot 5 and how
 * these three arrived after it. A short bundle is therefore normal and not an error: it means the
 * operation was built through an entry point that does not report these, and it decodes to
 * {@code era = -1} / {@code welcomeAction = null}.
 *
 * <p><b>An absent slot must never decode to zero.</b> {@code welcomeAction} 0 is {@code UNKNOWN} — a
 * real value meaning "the engine named no action" — so defaulting a missing slot to it would make an
 * un-migrated call site indistinguishable from an engine that refused to decide. The two want
 * opposite responses, so they get different values.
 */
public final class MlsArtifactBundle {

    /** The era slot's index. */
    public static final int SLOT_ERA = 6;
    /** The welcomeAction slot's index. */
    public static final int SLOT_WELCOME_ACTION = 7;
    /** The admitted-members slot's index. */
    public static final int SLOT_ADMITTED = 8;

    private MlsArtifactBundle() {}

    /**
     * Decode a bundle into artifacts, tolerating any length from zero slots upward.
     *
     * @return null when {@code bundle} is null — the engine reporting a failed operation
     */
    public static MlsGroupArtifacts decode(final byte[] bundle) {
        if (bundle == null) return null;
        final List<byte[]> p = splitLenPrefixed(bundle);
        return new MlsGroupArtifacts(at(p, 0), at(p, 1), at(p, 2), at(p, 3), at(p, 4), at(p, 5),
                beU32(p, SLOT_ERA),
                p.size() > SLOT_WELCOME_ACTION
                        ? MlsWelcomeAction.fromWire((int) beU32(p, SLOT_WELCOME_ACTION)) : null,
                p.size() > SLOT_ADMITTED ? asciiList(p.get(SLOT_ADMITTED)) : null);
    }

    /** A slot's bytes, or empty when the bundle is shorter than that. */
    public static byte[] at(final List<byte[]> l, final int i) {
        return i < l.size() ? l.get(i) : new byte[0];
    }

    /**
     * A 4-byte big-endian slot, or {@code -1} when the bundle does not carry it.
     *
     * <p>A slot of the wrong WIDTH also reads as {@code -1}, deliberately: a two-byte era is a
     * layout disagreement between the two builds, and guessing at its value would turn a build skew
     * into a wrong era rather than an absent one.
     */
    public static long beU32(final List<byte[]> p, final int i) {
        if (i >= p.size()) return -1L;
        final byte[] b = p.get(i);
        if (b == null || b.length != 4) return -1L;
        return ((long) (b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    /** Slot 8: a len-prefixed list of ASCII MSISDNs nested inside one bundle slot. */
    public static List<String> asciiList(final byte[] blob) {
        final List<String> out = new ArrayList<>();
        for (final byte[] b : splitLenPrefixed(blob)) {
            if (b != null && b.length > 0) {
                out.add(new String(b, java.nio.charset.StandardCharsets.US_ASCII));
            }
        }
        return out;
    }

    /**
     * Split a flat {@code [u32 BE len][bytes]…} blob.
     *
     * <p>A truncated tail STOPS the scan rather than throwing: the slots already read are real, and
     * losing them to a malformed trailer would discard a usable commit over a reporting field.
     */
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

    /** Join records back into the flat form, the inverse of {@link #splitLenPrefixed}. */
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

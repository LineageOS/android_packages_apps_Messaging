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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * {@link MlsAppMessage#buildAuthenticatedData} against REAL CAPTURED WIRE BYTES.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>RCC.16 says AuthenticatedData is <code>{version, message_id, optional resent-message}</code> —
 * <b>with no era</b>. Ours emits a {@code uint32} era between the message id and the trailing
 * component. Read the spec and our builder side by side and the obvious conclusion is that ours is
 * wrong, so the obvious change is to delete the era field. <b>That change would break interop with
 * every Google Messages peer</b>, and it would surface as {@code KEY_GENERATION_MISMATCH} — a symptom that
 * points nowhere near the AAD.
 *
 * <p>The vectors below are the counter-evidence, taken off the wire from a shipping Google
 * Messages build. They exist so that anyone making that "obvious" correction has to delete a failing test that
 * says, in bytes, why they are wrong.
 *
 * <h2>Provenance — and why four samples rather than one</h2>
 *
 * <p>The era-4 vector was captured 2026-07-31 and has been recorded in the builder's javadoc since.
 * The other three were captured on
 * 2026-08-21 (same Google Messages peer, same {@code group_id 6b38bff5-…}).
 *
 * <p>The extra three are not redundancy. From a SINGLE era-4 sample, "the {@code uint32} is the era"
 * is an inference that a hard-coded constant would have fitted exactly as well — and a constant is a
 * perfectly plausible thing for a protocol to carry there. Four samples at four different values,
 * each matching that message's own {@code Era-ID} envelope header, is what actually rules the
 * constant out. {@link #theUint32IsTheEraAndNotAConstant} is that argument as a test.
 *
 * <p>All four are ORDINARY messages, so all four carry the resent component ABSENT (a single trailing
 * {@code 0x00}). The PRESENT form is still uncaptured. Nothing here should be read
 * as evidence about it.
 *
 * <p>Recovered without decrypting anything: MLS {@code authenticated_data} is authenticated but
 * <b>not encrypted</b> (RFC 9420 {@code PrivateMessage}), which is the only reason these vectors are
 * obtainable at all — the artifacts' eras are long gone and they can never be decrypted again.
 */
public class MlsAuthenticatedDataWireVectorTest {

    /** 2026-07-31 capture, recorded in {@link MlsAppMessage}'s javadoc. */
    private static final String ID_E4 = "Mxg7aPBHhvSLSqseYQHUenLA";

    /** The three 2026-08-21 artifacts, in era order. */
    private static final String ID_E6 = "MxaDOv6I6QTwGK2Xj6V-Ubhw";
    private static final String ID_E7 = "MxHjYubZBiQYuA9DqODpyQiw";
    private static final String ID_E8 = "MxCACYGxqfQa-CnQZ=5XctuQ";

    /**
     * The two 2026-09-10 samples, read LIVE off {@code MLS-AAD-DUMP} on {@code 010T} while the
     * messages were being decrypted — not recovered from a dead artifact. Same Google Messages peer
     * (00RU, +15715550106), same {@code group_id 6b38bff5-…}, era 8 both.
     */
    private static final String ID_L1 = "Mxtz7XOK-ETqenk41prDynAQ";
    private static final String ID_L2 = "MxLII3abZ6Tme5TStNXf5q3w";

    private static byte[] hex(final String s) {
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ==================== THE VECTORS ====================

    /**
     * <b>The whole point of the file.</b> Google Messages' exact wire bytes against ours, byte-for-byte.
     *
     * <p>These three hex strings were read directly out of the preserved artifacts' MLS
     * {@code PrivateMessage.authenticated_data}. They are a RECORDING OF DEPLOYED TRAFFIC — if this
     * fails, do not "fix" the expectation; something changed in the builder.
     *
     * <p>The era-4 sample is deliberately NOT asserted here: the 2026-07-31 note records it
     * abbreviated ({@code 0001 18 4d78..4c41 00000004 00}) rather than in full, so a byte vector for
     * it would be me inventing the middle. It is exercised structurally in
     * {@link #eachFieldSitsWhereTheReferenceClientPutIt} instead, which is all the recorded evidence supports.
     */
    @Test
    public void ourAadIsByteIdenticalToTheCapturedWireBytes() {
        assertArrayEquals("era 6 — captured from Google Messages",
                hex("0001184d7861444f76364936515477474b32586a36562d556268770000000600"),
                MlsAppMessage.buildAuthenticatedData(ID_E6, 6));
        assertArrayEquals("era 7 — captured from Google Messages",
                hex("0001184d78486a5975625a4269515975413944714f4470795169770000000700"),
                MlsAppMessage.buildAuthenticatedData(ID_E7, 7));
        assertArrayEquals("era 8 — captured from Google Messages",
                hex("0001184d78434143594778716651612d436e515a3d3558637475510000000800"),
                MlsAppMessage.buildAuthenticatedData(ID_E8, 8));
    }

    /**
     * The three artifacts, expressed as the STRUCTURE rather than as one opaque hex blob, so a
     * failure says which field moved instead of just "bytes differ".
     */
    @Test
    public void eachFieldSitsWhereTheReferenceClientPutIt() {
        for (final Object[] v : new Object[][] {
                {ID_E6, 6}, {ID_E7, 7}, {ID_E8, 8}, {ID_E4, 4}}) {
            final String id = (String) v[0];
            final int era = (Integer) v[1];
            final byte[] aad = MlsAppMessage.buildAuthenticatedData(id, era);

            final String why = "era " + era + " (" + id + ")";
            // [00 01] version
            assertEquals(why + ": aad version high byte", 0x00, aad[0] & 0xff);
            assertEquals(why + ": aad version low byte", 0x01, aad[1] & 0xff);
            // [len] mls_varint, form 0 — every real id observed is 24 chars
            assertEquals(why + ": message-id length prefix", id.length(), aad[2] & 0xff);
            // [message_id ASCII]
            final byte[] idBytes = new byte[id.length()];
            System.arraycopy(aad, 3, idBytes, 0, id.length());
            assertEquals(why + ": message id", id, new String(idBytes, java.nio.charset.StandardCharsets.US_ASCII));
            // [uint32 era BE]
            final int o = 3 + id.length();
            final long got = ((long) (aad[o] & 0xff) << 24) | ((aad[o + 1] & 0xff) << 16)
                    | ((aad[o + 2] & 0xff) << 8) | (aad[o + 3] & 0xff);
            assertEquals(why + ": uint32 era, big-endian, right after the id", era, got);
            // [00] resent component ABSENT
            assertEquals(why + ": trailing resent component must be ABSENT", 0x00,
                    aad[o + 4] & 0xff);
            assertEquals(why + ": nothing after the trailing byte", o + 5, aad.length);
        }
    }

    /**
     * <b>CROSS-VENDOR: an APPLE RCS AAD, byte-for-byte</b>, from a real iPhone external commit
     * (a {@code kind=47} PublicMessage
     * Commit with {@code sender_type=4 new_member_commit}).
     *
     * <p>This is worth more than another Google sample: it is a <b>second independent
     * implementation</b> of the same format. The two vendors differ only in message-id length —
     * Google's {@code Mx}-form ids are 24 chars so its AAD is 32 bytes, Apple's UUIDs are 36 so its
     * AAD is 44 — and agree on every field and its order.
     *
     * <p><b>And it settles something our own vectors cannot.</b> On our side the era and the MLS
     * epoch tend to move together, so a Google-only corpus cannot prove the {@code uint32} is the
     * ERA rather than the EPOCH. In this Apple capture three commits carry MLS epochs 1, 2 and 3
     * while the AAD {@code uint32} stays <b>1</b> throughout — the RCC.16 era. <b>The field is the
     * era.</b> Do not "fix" it to the epoch.
     */
    @Test
    public void ourBuilderReproducesAnAppleRcsAadByteForByte() {
        final String appleMid = "5FF9708B-07D8-48D4-AB27-8167531C35AA";
        final byte[] apple = hex("0001243546463937303842" + "2d303744382d343844342d414232372d38313637353331433335414100000001" + "00");
        assertEquals("Apple ids are 36 chars, so its AAD is 44B not 32B", 44, apple.length);
        assertArrayEquals("our builder must reproduce Apple's AAD byte-for-byte",
                apple, MlsAppMessage.buildAuthenticatedData(appleMid, 1));
    }

    /**
     * <b>THE ERA IS THE ERA, NOT A CONSTANT.</b> This is the assertion a single captured sample
     * could never support, and it is the reason three more artifacts were worth recovering: with one
     * era-4 vector, a hard-coded {@code 0x00000004} fits the evidence perfectly.
     */
    @Test
    public void theUint32IsTheEraAndNotAConstant() {
        final byte[] a4 = MlsAppMessage.buildAuthenticatedData(ID_E8, 4);
        final byte[] a6 = MlsAppMessage.buildAuthenticatedData(ID_E8, 6);
        final byte[] a7 = MlsAppMessage.buildAuthenticatedData(ID_E8, 7);
        final byte[] a8 = MlsAppMessage.buildAuthenticatedData(ID_E8, 8);
        // Same id, four eras — the ONLY difference must be the uint32, and it must track.
        assertNotEquals("a constant would make these identical", java.util.Arrays.hashCode(a4),
                java.util.Arrays.hashCode(a6));
        final int o = 3 + ID_E8.length();
        assertEquals(4, a4[o + 3] & 0xff);
        assertEquals(6, a6[o + 3] & 0xff);
        assertEquals(7, a7[o + 3] & 0xff);
        assertEquals(8, a8[o + 3] & 0xff);
        // and nothing else moved
        for (int i = 0; i < o + 3; i++) {
            assertEquals("byte " + i + " must not depend on the era", a4[i], a8[i]);
        }
    }

    /**
     * <b>TWO MORE VECTORS, CAPTURED LIVE ON A DECRYPTING DEVICE (2026-09-10).</b>
     *
     * <p>Every other vector in this file came from a preserved artifact whose era is long gone. These
     * two were read off {@code MLS-AAD-DUMP} on a test device at 13:52:27 and 13:53:24 while the
     * messages were actually being decrypted and inserted, so the AAD, the {@code Era-ID} envelope
     * header and the successful decrypt are all attested by the same log lines:
     *
     * <pre>
     * MLS-AAD-DUMP from=+15715550106 msgId=Mxtz7XOK-ETqenk41prDynAQ len=32
     *   hex=0001184d78747a37584f4b2d455471656e6b3431707244796e41510000000800
     * MLS-AAD-DUMP from=+15715550106 msgId=MxLII3abZ6Tme5TStNXf5q3w len=32
     *   hex=0001184d784c49493361625a36546d65355453744e5866357133770000000800
     * </pre>
     *
     * <p>They add nothing to the era argument (both are era 8). What they add is CURRENCY: the
     * format is unchanged on live Google Messages traffic six weeks after the 2026-07-31 sample, which is
     * the thing a purely archival vector set cannot say.
     */
    @Test
    public void ourAadMatchesTheReferenceClientOnTrafficCapturedLiveToday() {
        assertArrayEquals("era 8 — Google Messages, live capture 2026-09-10 13:52:27",
                hex("0001184d78747a37584f4b2d455471656e6b3431707244796e41510000000800"),
                MlsAppMessage.buildAuthenticatedData(ID_L1, 8));
        assertArrayEquals("era 8 — Google Messages, live capture 2026-09-10 13:53:24",
                hex("0001184d784c49493361625a36546d65355453744e5866357133770000000800"),
                MlsAppMessage.buildAuthenticatedData(ID_L2, 8));
    }

    /**
     * The era is a {@code uint32} BIG-ENDIAN, so it must survive past a byte. A little-endian or
     * single-byte encoding passes every vector above (all four eras are < 256) and breaks the first
     * time a conversation reaches era 256 — which is a bug that would surface months later as an
     * unexplained {@code KEY_GENERATION_MISMATCH} on one long-lived thread.
     */
    @Test
    public void theEraIsFourBytesBigEndianAndNotOne() {
        final byte[] aad = MlsAppMessage.buildAuthenticatedData(ID_E8, 0x01020304);
        final int o = 3 + ID_E8.length();
        assertEquals(0x01, aad[o] & 0xff);
        assertEquals(0x02, aad[o + 1] & 0xff);
        assertEquals(0x03, aad[o + 2] & 0xff);
        assertEquals(0x04, aad[o + 3] & 0xff);
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * {@link MlsAppMessage#buildAuthenticatedData} against wire bytes from a shipping peer.
 *
 * <p>RCC.16 describes AuthenticatedData as {@code {version, message_id, optional resent-message}}
 * with no era, but deployed peers put a {@code uint32} era between the message id and the trailing
 * component; removing it breaks interop and surfaces as {@code KEY_GENERATION_MISMATCH}. Samples at
 * several eras, each matching the message's {@code Era-ID} header, rule out a constant. All samples
 * are ordinary messages with the resent component absent; the present form is not covered.
 * {@code authenticated_data} is authenticated but not encrypted (RFC 9420 §6.3), so the vectors are
 * readable without the group's keys. See docs/mls/rcc16-map.md.
 */
public class MlsAuthenticatedDataWireVectorTest {

    /** The sample recorded in {@link MlsAppMessage}'s javadoc. */
    private static final String ID_E4 = "Mxg7aPBHhvSLSqseYQHUenLA";

    /** Three samples from one peer and group, in era order. */
    private static final String ID_E6 = "MxaDOv6I6QTwGK2Xj6V-Ubhw";
    private static final String ID_E7 = "MxHjYubZBiQYuA9DqODpyQiw";
    private static final String ID_E8 = "MxCACYGxqfQa-CnQZ=5XctuQ";

    /** Two samples read while the messages were being decrypted; same peer and group, era 8. */
    private static final String ID_L1 = "Mxtz7XOK-ETqenk41prDynAQ";
    private static final String ID_L2 = "MxLII3abZ6Tme5TStNXf5q3w";

    private static byte[] hex(final String s) {
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * The peer's exact wire bytes against ours. These record deployed traffic: if this fails, the
     * builder changed; do not change the expectation. The era-4 sample is recorded only
     * abbreviated, so it is checked structurally in
     * {@link #eachFieldSitsWhereTheReferenceClientPutIt}.
     */
    @Test
    public void ourAadIsByteIdenticalToTheCapturedWireBytes() {
        assertArrayEquals("era 6 — peer wire bytes",
                hex("0001184d7861444f76364936515477474b32586a36562d556268770000000600"),
                MlsAppMessage.buildAuthenticatedData(ID_E6, 6));
        assertArrayEquals("era 7 — peer wire bytes",
                hex("0001184d78486a5975625a4269515975413944714f4470795169770000000700"),
                MlsAppMessage.buildAuthenticatedData(ID_E7, 7));
        assertArrayEquals("era 8 — peer wire bytes",
                hex("0001184d78434143594778716651612d436e515a3d3558637475510000000800"),
                MlsAppMessage.buildAuthenticatedData(ID_E8, 8));
    }

    /** The same samples as structure, so a failure names the field that moved. */
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
            // [len] mls_varint, form 0; real ids are 24 chars
            assertEquals(why + ": message-id length prefix", id.length(), aad[2] & 0xff);
            // [message_id ASCII]
            final byte[] idBytes = new byte[id.length()];
            System.arraycopy(aad, 3, idBytes, 0, id.length());
            assertEquals(why + ": message id", id,
                    new String(idBytes, java.nio.charset.StandardCharsets.US_ASCII));
            // [uint32 era BE]
            final int o = 3 + id.length();
            final long got = ((long) (aad[o] & 0xff) << 24) | ((aad[o + 1] & 0xff) << 16)
                    | ((aad[o + 2] & 0xff) << 8) | (aad[o + 3] & 0xff);
            assertEquals(why + ": uint32 era, big-endian, right after the id", era, got);
            // [00] resent component absent
            assertEquals(why + ": trailing resent component must be ABSENT", 0x00,
                    aad[o + 4] & 0xff);
            assertEquals(why + ": nothing after the trailing byte", o + 5, aad.length);
        }
    }

    /**
     * An AAD from a second, independent implementation (an external commit, {@code kind=47}
     * PublicMessage with {@code sender_type=4 new_member_commit}). It differs only in message-id
     * length (36-char UUID, 44-byte AAD), and across commits at epochs 1, 2 and 3 its
     * {@code uint32} stays 1: the field is the RCC.16 era, not the MLS epoch.
     */
    @Test
    public void ourBuilderReproducesAnAppleRcsAadByteForByte() {
        final String appleMid = "5FF9708B-07D8-48D4-AB27-8167531C35AA";
        final byte[] apple = hex("0001243546463937303842"
                + "2d303744382d343844342d414232372d38313637353331433335414100000001" + "00");
        assertEquals("Apple ids are 36 chars, so its AAD is 44B not 32B", 44, apple.length);
        assertArrayEquals("our builder must reproduce Apple's AAD byte-for-byte",
                apple, MlsAppMessage.buildAuthenticatedData(appleMid, 1));
    }

    /** The {@code uint32} tracks the era rather than being a constant. */
    @Test
    public void theUint32IsTheEraAndNotAConstant() {
        final byte[] a4 = MlsAppMessage.buildAuthenticatedData(ID_E8, 4);
        final byte[] a6 = MlsAppMessage.buildAuthenticatedData(ID_E8, 6);
        final byte[] a7 = MlsAppMessage.buildAuthenticatedData(ID_E8, 7);
        final byte[] a8 = MlsAppMessage.buildAuthenticatedData(ID_E8, 8);
        // Same id, four eras: only the uint32 differs, and it tracks the era.
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
     * Two samples read while decrypting, so the AAD, the {@code Era-ID} header and a successful
     * decrypt are attested together; they show the format is current on live traffic.
     */
    @Test
    public void ourAadMatchesTheReferenceClientOnTrafficCapturedLiveToday() {
        assertArrayEquals("era 8 — peer wire bytes, first live sample",
                hex("0001184d78747a37584f4b2d455471656e6b3431707244796e41510000000800"),
                MlsAppMessage.buildAuthenticatedData(ID_L1, 8));
        assertArrayEquals("era 8 — peer wire bytes, second live sample",
                hex("0001184d784c49493361625a36546d65355453744e5866357133770000000800"),
                MlsAppMessage.buildAuthenticatedData(ID_L2, 8));
    }

    /**
     * The era is a big-endian {@code uint32}, so it survives past a byte; every captured era is
     * below 256, so a little-endian or single-byte encoding would pass the vectors above.
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

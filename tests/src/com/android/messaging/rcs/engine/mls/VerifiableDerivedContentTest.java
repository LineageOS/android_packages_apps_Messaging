/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * The {@code VerifiableDerivedContent} encoding (RCC.16 §7.6.3), pinned byte by byte: a round trip
 * passes with a wrong layout, and a peer would only reject our signatures.
 */
public final class VerifiableDerivedContentTest {

    @Test
    public void deliveryImdnMatchesTheSpecLayout() {
        // version u16 | status u16 | opaque message_id<V> | failure_reason u16
        final byte[] a = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_DELIVERED, "abc",
                VerifiableDerivedContent.FAILURE_UNSET);
        assertArrayEquals(new byte[] {
                0x00, 0x01,             // version = v1
                0x00, 0x01,             // delivered(1)
                0x03, 'a', 'b', 'c',    // opaque<V>: 1-byte varint len 3
                0x00, 0x00,             // failure = unset(0)
        }, a);
    }

    @Test
    public void displayImdnHasNoFailureField() {
        final byte[] a = VerifiableDerivedContent.displayImdn(
                VerifiableDerivedContent.DISPLAY_DISPLAYED, "abc");
        assertArrayEquals(new byte[] {
                0x00, 0x01,             // version = v1
                0x00, 0x01,             // displayed(1)
                0x03, 'a', 'b', 'c',
        }, a);
        // Two bytes shorter than the delivery form: the failure field.
        assertEquals(a.length + 2, VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_DELIVERED, "abc",
                VerifiableDerivedContent.FAILURE_UNSET).length);
    }

    /** A negative delivery IMDN uses the same struct with its own status and reason. */
    @Test
    public void negativeDeliveryUsesTheSameStruct() {
        final byte[] neg = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_FAILED, "m1",
                VerifiableDerivedContent.FAILURE_FAILURE_TO_DECRYPT);
        final VerifiableDerivedContent.Delivery p =
                VerifiableDerivedContent.parseDelivery(neg);
        assertNotNull(p);
        assertEquals(VerifiableDerivedContent.DELIVERY_FAILED, p.status);
        assertEquals(VerifiableDerivedContent.FAILURE_FAILURE_TO_DECRYPT, p.failureReason);
        assertEquals("m1", p.messageId);
        assertEquals(VerifiableDerivedContent.VERSION_V1, p.version);
    }

    /** {@code message_id} is UTF-8, prefixed with its byte length, not its char count. */
    @Test
    public void messageIdIsUtf8ByteLengthPrefixed() {
        final String id = "café";                       // 5 UTF-8 bytes, 4 chars
        final byte[] a = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_DELIVERED, id,
                VerifiableDerivedContent.FAILURE_UNSET);
        assertEquals(5, a[4]);                          // varint length byte
        assertEquals(id, VerifiableDerivedContent.parseDelivery(a).messageId);
    }

    /** Peers' message ids are 24-char base64url, under the 1-byte varint boundary. */
    @Test
    public void handlesARealisticMessageId() {
        final String id = "MxOqsaj-SISUWicyqxytpy2g";
        final byte[] a = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_DELIVERED, id,
                VerifiableDerivedContent.FAILURE_UNSET);
        assertEquals(id, VerifiableDerivedContent.parseDelivery(a).messageId);
    }

    /** A message id past 63 bytes crosses into the 2-byte varint form. */
    @Test
    public void crossesTheVarintBoundary() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('x');
        final byte[] a = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_DELIVERED, sb.toString(),
                VerifiableDerivedContent.FAILURE_UNSET);
        assertEquals(0x40, a[4] & 0xC0);                // 2-byte varint prefix
        assertEquals(sb.toString(), VerifiableDerivedContent.parseDelivery(a).messageId);
    }

    @Test
    public void videoChatWrapsTheProtoOpaquely() {
        final byte[] proto = new byte[] {9, 8, 7};
        assertArrayEquals(new byte[] {0x00, 0x01, 0x03, 9, 8, 7},
                VerifiableDerivedContent.videoChat(proto));
    }

    /** Trailing bytes mean it is not this struct. */
    @Test
    public void parseRejectsTrailingBytes() {
        final byte[] good = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_DELIVERED, "m",
                VerifiableDerivedContent.FAILURE_UNSET);
        final byte[] extra = java.util.Arrays.copyOf(good, good.length + 1);
        assertNull(VerifiableDerivedContent.parseDelivery(extra));
    }

    @Test
    public void parseRejectsTruncation() {
        final byte[] good = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_DELIVERED, "m",
                VerifiableDerivedContent.FAILURE_UNSET);
        assertNull(VerifiableDerivedContent.parseDelivery(
                java.util.Arrays.copyOf(good, good.length - 1)));
        assertNull(VerifiableDerivedContent.parseDelivery(new byte[0]));
    }

    @Test
    public void nullsDoNotThrow() {
        assertNull(VerifiableDerivedContent.deliveryImdn(1, null, 0));
        assertNull(VerifiableDerivedContent.displayImdn(1, null));
        assertNull(VerifiableDerivedContent.videoChat(null));
        assertNull(VerifiableDerivedContent.parseDelivery(null));
    }

    @Test
    public void signedImdnContentTrailingDefaultsToAbsent0x00() {
        // Both forms end in the absent RCC.16 §10.3 component: a single 0x00.
        final byte[] inner = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_FAILED, "m1",
                VerifiableDerivedContent.FAILURE_FAILURE_TO_DECRYPT);
        final byte[] four = VerifiableDerivedContent.signedImdnContent(
                "own", 5, VerifiableDerivedContent.TYPE_DELIVERY, inner);
        final byte[] fiveNull = VerifiableDerivedContent.signedImdnContent(
                "own", 5, VerifiableDerivedContent.TYPE_DELIVERY, inner, null);
        assertArrayEquals(four, fiveNull);
        assertEquals(0x00, four[four.length - 1]);              // absent component tag
    }

    @Test
    public void signedImdnContentAppendsPresentComponentInsteadOf0x00() {
        // A present RCC.16 §10.3 component replaces the trailing 0x00 rather than following it.
        final byte[] inner = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_FAILED, "m1",
                VerifiableDerivedContent.FAILURE_FAILURE_TO_DECRYPT);
        final byte[] absent = VerifiableDerivedContent.signedImdnContent(
                "own", 5, VerifiableDerivedContent.TYPE_DELIVERY, inner, null);
        final byte[] hmacField = new byte[MlsResentMessage.HMAC_FIELD_LEN];
        final byte[] component = MlsResentMessage.encode(
                MlsResentMessage.TAG_RESENT, hmacField, MlsResentMessage.PrefixWidth.VARINT);
        final byte[] present = VerifiableDerivedContent.signedImdnContent(
                "own", 5, VerifiableDerivedContent.TYPE_DELIVERY, inner, component);
        final byte[] prefix = java.util.Arrays.copyOf(absent, absent.length - 1);
        final byte[] expected = new byte[prefix.length + component.length];
        System.arraycopy(prefix, 0, expected, 0, prefix.length);
        System.arraycopy(component, 0, expected, prefix.length, component.length);
        assertArrayEquals(expected, present);
        // The appended component parses (tag 0x02, 64-byte field).
        final byte[] tail = java.util.Arrays.copyOfRange(present, prefix.length, present.length);
        final MlsResentMessage.Parsed p = MlsResentMessage.parse(tail);
        assertNotNull(p);
        assertEquals(MlsResentMessage.TAG_RESENT, p.tag);
        assertEquals(MlsResentMessage.HMAC_FIELD_LEN, p.payload.length);
    }

    @Test
    public void negativeInnerCarriesField4OptionNoneAndPositiveDoesNot() {
        // Peers read a u16 Option after failure_reason on the negative form and reject its
        // absence; 0x0000 is None. The positive form carries no such field.
        final byte[] neg = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_FAILED, "m1",
                VerifiableDerivedContent.FAILURE_FAILURE_TO_DECRYPT);
        assertArrayEquals(new byte[] {
                0x00, 0x01,             // version
                0x00, 0x02,             // status = failed(2)
                0x02, 'm', '1',         // opaque reported_id<V>
                0x00, 0x04,             // failure_reason = 4
                0x00, 0x00,             // field 4 = Option None
        }, neg);
        final byte[] pos = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_DELIVERED, "m1",
                VerifiableDerivedContent.FAILURE_UNSET);
        assertArrayEquals(new byte[] {
                0x00, 0x01, 0x00, 0x01, 0x02, 'm', '1', 0x00, 0x00,
        }, pos);                        // no field 4 on the positive form
        // Our own negative form still parses.
        final VerifiableDerivedContent.Delivery p = VerifiableDerivedContent.parseDelivery(neg);
        assertNotNull(p);
        assertEquals(VerifiableDerivedContent.DELIVERY_FAILED, p.status);
        assertEquals(VerifiableDerivedContent.FAILURE_FAILURE_TO_DECRYPT, p.failureReason);
        assertEquals("m1", p.messageId);
        // A trailing byte on the positive form is still rejected.
        final byte[] posPlus = java.util.Arrays.copyOf(pos, pos.length + 2);
        assertNull(VerifiableDerivedContent.parseDelivery(posPlus));
    }

    @Test
    public void deliveryImdnRawInsertPlacesBytesEitherSideOfFailureReason() {
        // Raw bytes go verbatim before or after failure_reason.
        final byte[] one = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_FAILED, "m", 4);
        // Before failure_reason, right after reported_id.
        final byte[] before = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_FAILED, "m", 4,
                new byte[] { (byte) 0xAA, (byte) 0xBB }, true);
        assertArrayEquals(new byte[] {
                0x00, 0x01,             // version
                0x00, 0x02,             // status = failed(2)
                0x01, 'm',              // opaque reported_id<V>
                (byte) 0xAA, (byte) 0xBB,   // raw insert
                0x00, 0x04,             // failure_reason = 4
        }, before);
        // After failure_reason.
        final byte[] after = VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_FAILED, "m", 4,
                new byte[] { (byte) 0xAA }, false);
        assertArrayEquals(new byte[] {
                0x00, 0x01, 0x00, 0x02, 0x01, 'm', 0x00, 0x04, (byte) 0xAA,
        }, after);
        // A null or empty insert gives the same bytes as the call without one.
        assertArrayEquals(one, VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_FAILED, "m", 4, null, true));
        assertArrayEquals(one, VerifiableDerivedContent.deliveryImdn(
                VerifiableDerivedContent.DELIVERY_FAILED, "m", 4, new byte[0], false));
    }
}

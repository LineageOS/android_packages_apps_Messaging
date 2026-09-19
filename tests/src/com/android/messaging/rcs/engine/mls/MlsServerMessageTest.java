/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@code ServerMlsRcsMessage}'s outer oneof. Frames are hand-built so the test pins the wire's
 * field numbers; a round trip through our own encoder would pass with every number wrong.
 */
public class MlsServerMessageTest {

    /** A length-delimited field: tag = (field &lt;&lt; 3) | 2, then a length, then the bytes. */
    private static byte[] lenField(final int field, final byte[] body) {
        final byte[] out = new byte[2 + body.length];
        out[0] = (byte) ((field << 3) | 2);
        out[1] = (byte) body.length;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    @Test
    public void armOneIsTheRawBytesArm() {
        final MlsServerMessage.Parsed p =
                MlsServerMessage.parse(lenField(1, new byte[] { 9, 9, 9 }));
        assertEquals(MlsServerMessage.Arm.RAW, p.arm);
        assertEquals(1, p.arm.field);
        assertArrayEquals(new byte[] { 9, 9, 9 }, p.payload);
        assertFalse(p.isAccepted());
    }

    @Test
    public void armFourIsTheAcceptedMessage() {
        final MlsServerMessage.Parsed p =
                MlsServerMessage.parse(lenField(4, new byte[] { 1, 2 }));
        assertEquals(MlsServerMessage.Arm.ACCEPTED, p.arm);
        assertEquals(4, p.arm.field);
        assertTrue("§10.6 requires the host to test THIS arm", p.isAccepted());
    }

    /**
     * {@code ServerCommitBundle} and {@code MlsGroupInfo} are structurally identical, so which arm
     * is which cannot be determined statically; dispatch by arm number needs only that they differ.
     */
    @Test
    public void armsTwoAndThreeAreDistinctButUnnamed() {
        final MlsServerMessage.Parsed two = MlsServerMessage.parse(lenField(2, new byte[] { 7 }));
        final MlsServerMessage.Parsed three = MlsServerMessage.parse(lenField(3, new byte[] { 7 }));
        assertEquals(MlsServerMessage.Arm.SERVER_COMMIT_BUNDLE, two.arm);
        assertEquals(MlsServerMessage.Arm.MLS_GROUP_INFO, three.arm);
        assertEquals(2, two.arm.field);
        assertEquals(3, three.arm.field);
        assertFalse(two.arm == three.arm);
        assertFalse(two.isAccepted());
        assertFalse(three.isAccepted());
    }

    @Test
    public void theArmNumbersAreOneToFour() {
        assertEquals(MlsServerMessage.Arm.RAW, MlsServerMessage.Arm.forField(1));
        assertEquals(MlsServerMessage.Arm.SERVER_COMMIT_BUNDLE, MlsServerMessage.Arm.forField(2));
        assertEquals(MlsServerMessage.Arm.MLS_GROUP_INFO, MlsServerMessage.Arm.forField(3));
        assertEquals(MlsServerMessage.Arm.ACCEPTED, MlsServerMessage.Arm.forField(4));
        assertEquals(MlsServerMessage.Arm.NONE, MlsServerMessage.Arm.forField(5));
        assertEquals(MlsServerMessage.Arm.NONE, MlsServerMessage.Arm.forField(0));
    }

    /** The enclosing request's oneof is {@code 2/5/8/10}; this inner one is 1-4. */
    @Test
    public void theseAreTheInnerOneofNumbersNotTheOuterOnes() {
        assertEquals(MlsServerMessage.Arm.NONE, MlsServerMessage.parse(
                lenField(5, new byte[] { 1 })).arm);
        assertEquals(MlsServerMessage.Arm.NONE, MlsServerMessage.parse(
                lenField(10, new byte[] { 1 })).arm);
    }

    @Test
    public void acceptedMessageIdIsFieldOneAsAString() {
        final byte[] id = "m-123".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("m-123", MlsServerMessage.acceptedMessageId(lenField(1, id)));
    }

    @Test
    public void acceptedMessageIdSkipsFieldTwo() {
        final byte[] id = "m-9".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] f2 = lenField(2, new byte[] { 4, 4, 4 });
        final byte[] both = new byte[f2.length + lenField(1, id).length];
        // Field 2 first, so the reader has to skip past it.
        System.arraycopy(f2, 0, both, 0, f2.length);
        System.arraycopy(lenField(1, id), 0, both, f2.length, lenField(1, id).length);
        assertEquals("m-9", MlsServerMessage.acceptedMessageId(both));
    }

    @Test
    public void acceptedMessageIdIsNullWhenAbsent() {
        assertNull(MlsServerMessage.acceptedMessageId(new byte[0]));
        assertNull(MlsServerMessage.acceptedMessageId(null));
        assertNull("only field 1 is the id",
                MlsServerMessage.acceptedMessageId(lenField(2, new byte[] { 1, 2 })));
    }

    @Test
    public void anUnknownFieldIsSkippedRatherThanEndingTheParse() {
        final byte[] unknown = lenField(7, new byte[] { 1, 1 });
        final byte[] accepted = lenField(4, new byte[] { 2 });
        final byte[] both = new byte[unknown.length + accepted.length];
        System.arraycopy(unknown, 0, both, 0, unknown.length);
        System.arraycopy(accepted, 0, both, unknown.length, accepted.length);
        assertEquals(MlsServerMessage.Arm.ACCEPTED, MlsServerMessage.parse(both).arm);
    }

    @Test
    public void emptyAndNullAndGarbageYieldNone() {
        assertEquals(MlsServerMessage.Arm.NONE, MlsServerMessage.parse(null).arm);
        assertEquals(MlsServerMessage.Arm.NONE, MlsServerMessage.parse(new byte[0]).arm);
        // A truncated length prefix must not run off the end.
        assertEquals(MlsServerMessage.Arm.NONE,
                MlsServerMessage.parse(new byte[] { (byte) ((4 << 3) | 2), 99 }).arm);
    }

    @Test
    public void parseNeverReturnsANullPayload() {
        assertEquals(0, MlsServerMessage.parse(null).payload.length);
        assertEquals(0, MlsServerMessage.parse(new byte[0]).payload.length);
    }


    @Test
    public void groupIdFromChangeRejectReadsTheServersOwnStatement() {
        assertEquals("abc", MlsServerMessage.groupIdFromChangeReject(
                "FAILED_PRECONDITION: Group ID changed from abc to def"));
        assertNull(MlsServerMessage.groupIdFromChangeReject("Group ID changed from  to def"));
        assertNull(MlsServerMessage.groupIdFromChangeReject("Era changed from 1 to 2"));
        assertNull(MlsServerMessage.groupIdFromChangeReject(null));
    }
}

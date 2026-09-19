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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@code ServerMlsRcsMessage}'s outer oneof — rework {@code 6.8}, §10.6.
 *
 * <p>The frames are hand-built rather than round-tripped through our own writer, because the point
 * is to pin the FIELD NUMBERS observed on the wire rather than to check we can read what we
 * wrote. A round-trip against our own encoder would pass with every number wrong.
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

    // ---- the four arms -------------------------------------------------------------------------

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
     * Arms 2 and 3 are DISTINCT but UNNAMED.
     *
     * <p>{@code ServerCommitBundle} and {@code MlsGroupInfo} are structurally identical — same field
     * count, numbering, types and schema string — so which is which is not statically determinable.
     * Naming one would not fail loudly if wrong: both parse against either interpretation, so a
     * swap would mis-route commit bundles into the GroupInfo re-drive path <i>and look like it
     * worked</i>.
     *
     * <p>What this test pins is the property that actually matters: they are told APART, which is
     * what lets arm-number dispatch be correct without the names.
     */
    @Test
    public void armsTwoAndThreeAreDistinctButUnnamed() {
        final MlsServerMessage.Parsed two = MlsServerMessage.parse(lenField(2, new byte[] { 7 }));
        final MlsServerMessage.Parsed three = MlsServerMessage.parse(lenField(3, new byte[] { 7 }));
        assertEquals(MlsServerMessage.Arm.SERVER_COMMIT_BUNDLE, two.arm);
        assertEquals(MlsServerMessage.Arm.MLS_GROUP_INFO, three.arm);
        assertEquals(2, two.arm.field);
        assertEquals(3, three.arm.field);
        // Distinct — the whole point. If these ever collapse, arm dispatch is broken.
        assertFalse(two.arm == three.arm);
        // ...and neither is the accepted arm, so they are not lumped into "not accepted".
        assertFalse(two.isAccepted());
        assertFalse(three.isAccepted());
    }

    /** Every arm number maps to exactly one enum value, and nothing else does. */
    @Test
    public void theArmNumbersAreOneToFour() {
        assertEquals(MlsServerMessage.Arm.RAW, MlsServerMessage.Arm.forField(1));
        assertEquals(MlsServerMessage.Arm.SERVER_COMMIT_BUNDLE, MlsServerMessage.Arm.forField(2));
        assertEquals(MlsServerMessage.Arm.MLS_GROUP_INFO, MlsServerMessage.Arm.forField(3));
        assertEquals(MlsServerMessage.Arm.ACCEPTED, MlsServerMessage.Arm.forField(4));
        assertEquals(MlsServerMessage.Arm.NONE, MlsServerMessage.Arm.forField(5));
        assertEquals(MlsServerMessage.Arm.NONE, MlsServerMessage.Arm.forField(0));
    }

    /**
     * <b>These are NOT ProcessMessageRequest's numbers.</b> That outer oneof is
     * {@code 2 raw / 5 server / 8 keys / 10 groupInfo}; this inner one is 1-4. Conflating the two
     * nested oneofs is the first mistake available, so it is asserted.
     */
    @Test
    public void theseAreTheInnerOneofNumbersNotTheOuterOnes() {
        // Field 5 is ProcessMessageRequest's SERVER arm — meaningless inside ServerMlsRcsMessage.
        assertEquals(MlsServerMessage.Arm.NONE, MlsServerMessage.parse(
                lenField(5, new byte[] { 1 })).arm);
        assertEquals(MlsServerMessage.Arm.NONE, MlsServerMessage.parse(
                lenField(10, new byte[] { 1 })).arm);
    }

    // ---- AcceptedMlsRcsMessage.message_id ------------------------------------------------------

    @Test
    public void acceptedMessageIdIsFieldOneAsAString() {
        final byte[] id = "m-123".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("m-123", MlsServerMessage.acceptedMessageId(lenField(1, id)));
    }

    /** Field 2 is {@code fkap} and is skipped, not decoded — we have no use for it. */
    @Test
    public void acceptedMessageIdSkipsFieldTwo() {
        final byte[] id = "m-9".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] f2 = lenField(2, new byte[] { 4, 4, 4 });
        final byte[] both = new byte[f2.length + lenField(1, id).length];
        // field 2 FIRST, so the reader has to skip past it to find field 1.
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

    // ---- robustness ----------------------------------------------------------------------------

    @Test
    public void anUnknownFieldIsSkippedRatherThanEndingTheParse() {
        // field 7 (unknown) then field 4 (accepted) — the accepted arm must still be found.
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
}

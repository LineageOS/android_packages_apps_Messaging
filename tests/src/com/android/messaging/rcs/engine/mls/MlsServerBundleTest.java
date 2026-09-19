/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * What is in a relayed {@code ServerMlsRcsMessage} bundle. Fixtures are hand-built at the nesting
 * peers send (metadata commit at {@code f2.f1.f2.{f1,f3}}, create push at {@code f1.f2.{f2,f4}})
 * rather than round-tripped through our own encoder, which would pass with every depth wrong.
 */
public class MlsServerBundleTest {

    /** A protobuf length-delimited field, with a multi-byte length varint. */
    private static byte[] lenField(final int field, final byte[] body) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        varint(o, (field << 3) | 2);
        varint(o, body.length);
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    private static void varint(final ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    /** A protobuf varint field: the GroupEvent's f1 op and the ApplyMlsControlMessage's f3 era. */
    private static byte[] varintField(final int field, final long value) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        varint(o, (field << 3));
        varint(o, value);
        return o.toByteArray();
    }

    private static byte[] cat(final byte[]... parts) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (final byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    /** A bare MLSMessage: {@code [00 01][00 wf]} then filler. */
    private static byte[] mls(final int wireFormat, final int bodyLen) {
        final byte[] b = new byte[4 + bodyLen];
        b[0] = 0x00; b[1] = 0x01; b[2] = 0x00; b[3] = (byte) wireFormat;
        for (int i = 4; i < b.length; i++) b[i] = (byte) (0x40 + (i % 30));
        return b;
    }

    /** The {@code mls_varint}-length-prefixed form a bundle field carries. */
    private static byte[] wrapped(final byte[] artifact) {
        return cat(MlsAppMessage.mlsVarint(artifact.length), artifact);
    }

    private static final byte[] COMMIT = mls(MlsWireScan.WF_PUBLIC_MESSAGE, 3669);
    private static final byte[] KEY_DELIVERY = mls(MlsWireScan.WF_PRIVATE_MESSAGE, 458);
    private static final byte[] WELCOME = mls(MlsWireScan.WF_WELCOME, 405);
    private static final byte[] GROUP_INFO = mls(MlsWireScan.WF_GROUP_INFO, 900);
    private static final byte[] TREE = new byte[5921];
    private static final byte[] EPOCH_AUTH = new byte[32];

    /** Metadata-commit bundle: {@code f2.f1.f2.{f1,f3}} wrapped, and a 32-byte {@code f4}. */
    private static byte[] metadataCommitBundle(final int arm) {
        final byte[] artifacts =
                cat(lenField(1, wrapped(COMMIT)), lenField(3, wrapped(KEY_DELIVERY)));
        final byte[] inner = lenField(2, artifacts);
        final byte[] bundle = cat(lenField(1, inner), lenField(4, EPOCH_AUTH));
        return lenField(arm, bundle);
    }

    /** Create push: the Welcome at {@code f1.f2.f2}, the out-of-band tree at {@code f1.f2.f4}. */
    private static byte[] welcomeBundle(final int arm) {
        final byte[] artifacts = cat(lenField(2, WELCOME), lenField(4, TREE));
        final byte[] bundle = lenField(1, lenField(2, artifacts));
        return lenField(arm, bundle);
    }

    @Test
    public void acceptedArmIsNotMlsAndCarriesNoMessages() {
        final byte[] wire = lenField(4, lenField(1, "m-42".getBytes()));
        final MlsServerBundle.Recognised r = MlsServerBundle.recognise(wire);

        assertTrue(r.isAccepted());
        assertEquals(MlsServerMessage.Arm.ACCEPTED, r.arm);
        assertEquals(MlsServerBundle.Variant.ACCEPTED, r.variant);
        assertEquals("m-42", r.acceptedMessageId);
        assertTrue("arm 4 carries no MLSMessage at all", r.messages.isEmpty());
        assertTrue("nothing may be forwarded to the engine", r.delivery.isEmpty());
        // Forwarding a server ACK to the engine would report a failure for an accepted message.
        assertFalse(r.forwardWholeBlob);
    }

    @Test
    public void armOneIsTakenVerbatimAndTheDescentIsSkipped() {
        final MlsServerBundle.Recognised r = MlsServerBundle.recognise(lenField(1, COMMIT));

        assertTrue("a bytes arm carries the MLSMessage directly", r.takenVerbatim);
        assertEquals(1, r.messages.size());
        assertArrayEquals(COMMIT, r.messages.get(0));
        assertEquals(MlsServerBundle.Variant.COMMIT, r.variant);
        assertFalse(r.forwardWholeBlob);
        assertEquals("delivery is the extracted list when the blob need not go whole",
                1, r.delivery.size());
        assertArrayEquals(COMMIT, r.delivery.get(0));
    }

    @Test
    public void armOneAcceptsTheVarintWrappedForm() {
        final MlsServerBundle.Recognised r =
                MlsServerBundle.recognise(lenField(1, wrapped(COMMIT)));

        assertTrue(r.takenVerbatim);
        assertEquals(1, r.messages.size());
        assertArrayEquals("the prefix must be stripped, not handed to the engine",
                COMMIT, r.messages.get(0));
    }

    /** An arm-1 payload that is not an MLSMessage falls through to the descent, not the floor. */
    @Test
    public void armOnePayloadThatIsNotAnMlsMessageFallsThroughRatherThanBeingDropped() {
        final byte[] artifacts = lenField(1, wrapped(COMMIT));
        final MlsServerBundle.Recognised r = MlsServerBundle.recognise(lenField(1, artifacts));

        assertFalse("did not qualify for the verbatim shortcut", r.takenVerbatim);
        assertEquals("but the descent still found it", 1, r.messages.size());
        assertArrayEquals(COMMIT, r.messages.get(0));
    }

    @Test
    public void bothMessagesOfAMetadataCommitAreFoundInApplyOrder() {
        final MlsServerBundle.Recognised r =
                MlsServerBundle.recognise(metadataCommitBundle(/*arm=*/ 2));

        assertEquals(
                "the commit AND the key delivery — taking only the first loses the subject key",
                2, r.messages.size());
        // The key delivery is encrypted at the post-commit epoch, so this order is the contract.
        assertArrayEquals(COMMIT, r.messages.get(0));
        assertArrayEquals(KEY_DELIVERY, r.messages.get(1));
        assertEquals(MlsServerBundle.Variant.COMMIT, r.variant);
        assertFalse(r.forwardWholeBlob);
    }

    @Test
    public void theSameArtifactReachedTwiceIsDeliveredOnce() {
        // f1 and f2 of the same container hold the same commit.
        final byte[] artifacts = cat(lenField(1, wrapped(COMMIT)), lenField(2, wrapped(COMMIT)));
        final MlsServerBundle.Recognised r =
                MlsServerBundle.recognise(lenField(2, lenField(1, artifacts)));

        assertEquals("applying one commit twice is a failure, not a duplicate log line",
                1, r.messages.size());
        assertArrayEquals(COMMIT, r.messages.get(0));
    }

    @Test
    public void aWelcomeForcesTheWholeBlobBecauseTheTreeRidesAlongside() {
        final byte[] wire = welcomeBundle(/*arm=*/ 2);
        final MlsServerBundle.Recognised r = MlsServerBundle.recognise(wire);

        assertEquals(MlsServerBundle.Variant.WELCOME, r.variant);
        assertTrue(r.forwardWholeBlob);
        assertEquals(1, r.delivery.size());
        assertArrayEquals("forwarding only the Welcome strips the ratchet_tree and drops the join",
                wire, r.delivery.get(0));
        // The extraction still reports what it found; only the delivery differs.
        assertFalse(r.messages.isEmpty());
        assertArrayEquals(WELCOME, r.messages.get(0));
    }

    @Test
    public void groupInfoIsRecognisedAsItsOwnVariant() {
        final byte[] wire = lenField(3, lenField(1, wrapped(GROUP_INFO)));
        final MlsServerBundle.Recognised r = MlsServerBundle.recognise(wire);

        assertEquals(MlsServerBundle.Variant.GROUP_INFO, r.variant);
        assertFalse("a GroupInfo is not a Welcome — no whole-blob rule applies",
                r.forwardWholeBlob);
    }

    @Test
    public void onlyPrivateMessagesReadAsApplication() {
        final MlsServerBundle.Recognised r =
                MlsServerBundle.recognise(lenField(1, KEY_DELIVERY));
        assertEquals(MlsServerBundle.Variant.APPLICATION, r.variant);
    }

    @Test
    public void anUnrecognisedBlobIsForwardedWholeRatherThanDropped() {
        final byte[] wire = lenField(2, new byte[] { 7, 7, 7, 7, 7, 7, 7, 7 });
        final MlsServerBundle.Recognised r = MlsServerBundle.recognise(wire);

        assertEquals(MlsServerBundle.Variant.UNRECOGNISED, r.variant);
        assertTrue(r.messages.isEmpty());
        assertTrue(r.forwardWholeBlob);
        assertArrayEquals(wire, r.delivery.get(0));
    }

    @Test
    public void aBareMlsMessageWithNoEnvelopeIsClassifiedFromItself() {
        // Nothing to descend into: the variant is classified from the blob itself.
        final MlsServerBundle.Recognised r = MlsServerBundle.recognise(WELCOME);

        assertEquals(MlsServerMessage.Arm.NONE, r.arm);
        assertEquals(MlsServerBundle.Variant.WELCOME, r.variant);
        assertTrue(r.forwardWholeBlob);
        assertArrayEquals(WELCOME, r.delivery.get(0));
    }

    @Test
    public void nullAndEmptyAreRecognisedRatherThanThrown() {
        for (final byte[] b : new byte[][] { null, new byte[0] }) {
            final MlsServerBundle.Recognised r = MlsServerBundle.recognise(b);
            assertEquals(MlsServerMessage.Arm.NONE, r.arm);
            assertEquals(MlsServerBundle.Variant.UNRECOGNISED, r.variant);
            assertTrue(r.messages.isEmpty());
            assertEquals(1, r.delivery.size());
        }
    }

    @Test
    public void theBundlesThirtyTwoByteF4IsReadOnArmsTwoAndThree() {
        assertNotNull("f4 is the GroupInfo bundle's epoch authenticator",
                MlsServerBundle.recognise(metadataCommitBundle(2)).groupInfoEpochAuthenticator);
        assertEquals(32,
                MlsServerBundle.recognise(metadataCommitBundle(3))
                        .groupInfoEpochAuthenticator.length);
    }

    @Test
    public void theF4ReadIsNotAttemptedOnArmsThatAreNotBundles() {
        // Arm 1's payload is a bare MLSMessage; a "field 4" read of it often succeeds on noise.
        assertNull(MlsServerBundle.recognise(lenField(1, COMMIT)).groupInfoEpochAuthenticator);
        assertNull(MlsServerBundle.recognise(lenField(4, lenField(1, "m-1".getBytes())))
                .groupInfoEpochAuthenticator);
    }

    @Test
    public void anF4OfTheWrongLengthIsNotReportedAsAnEpochAuthenticator() {
        final byte[] bundle = cat(lenField(1, lenField(2, lenField(1, wrapped(COMMIT)))),
                lenField(4, new byte[31]));
        assertNull("31 bytes is not an epoch authenticator",
                MlsServerBundle.recognise(lenField(2, bundle)).groupInfoEpochAuthenticator);
    }

    @Test
    public void onlyTheArmAssignedByEliminationGetsAnEvidenceLine() {
        assertNotNull("arm 2 is ServerCommitBundle BY ELIMINATION",
                MlsServerBundle.recognise(metadataCommitBundle(2)).armEvidenceLine());
        assertNull("arm 3 is settled by the field-4 agreement",
                MlsServerBundle.recognise(metadataCommitBundle(3)).armEvidenceLine());
        assertNull(MlsServerBundle.recognise(lenField(1, COMMIT)).armEvidenceLine());
        assertNull(MlsServerBundle.recognise(lenField(4, lenField(1, "m".getBytes())))
                .armEvidenceLine());
    }

    /** The evidence line carries the content reading, which the arm number cannot supply. */
    @Test
    public void theEvidenceLineNamesTheContentVariantBesideTheArm() {
        final String welcomeArm2 = MlsServerBundle.recognise(welcomeBundle(2)).armEvidenceLine();
        assertTrue("a 9.4KB arm-2 payload whose CONTENT is a Welcome says so on its own line",
                welcomeArm2.contains("variant=WELCOME"));
        assertTrue(welcomeArm2.contains("arm=SERVER_COMMIT_BUNDLE(2)"));
    }

    /** The invariant-scan needle must match the line the code emits, not merely be non-empty. */
    @Test
    public void theInvariantScanActuallyFiresOnTheLineTheCodeEmits() {
        final String line = "01-01 00:00:00.000  I MlsMessageTransport: "
                + MlsServerBundle.recognise(metadataCommitBundle(2)).armEvidenceLine();
        final List<MlsInvariantScan.Violation> hits =
                MlsInvariantScan.scan(Collections.singletonList(line));

        assertEquals("the needle and the emitter must be the same string", 1, hits.size());
        assertEquals(MlsInvariantScan.Severity.SUSPICIOUS, hits.get(0).marker.severity);
        assertEquals(MlsServerBundle.ARM_UNPROVEN_MARKER, hits.get(0).marker.needle);
    }

    @Test
    public void theProvenArmProducesNoScanHit() {
        final String line = "I MlsMessageTransport: routeOpen[control] "
                + MlsServerBundle.recognise(metadataCommitBundle(3)).logLine();
        assertTrue("a proven arm is not a discovery",
                MlsInvariantScan.scan(Collections.singletonList(line)).isEmpty());
    }

    @Test
    public void theLogLineCarriesTheArmAndTheContentTogether() {
        final String line = MlsServerBundle.recognise(metadataCommitBundle(2)).logLine();

        assertTrue(line.contains("arm=SERVER_COMMIT_BUNDLE(2)"));
        assertTrue(line.contains("variant=COMMIT"));
        assertTrue(line.contains("messages=2"));
        assertTrue("the wire formats, named", line.contains("PublicMessage/"));
        assertTrue(line.contains("PrivateMessage/"));
        assertTrue("the f4 the arm question turns on", line.contains("f4=32B"));
    }

    @Test
    public void theWholeBlobDecisionSaysWhichCaseFired() {
        assertTrue(MlsServerBundle.recognise(welcomeBundle(2)).logLine()
                .contains("forwardWholeBlob(Welcome"));
        assertTrue(MlsServerBundle.recognise(lenField(2, new byte[] { 7, 7, 7, 7 })).logLine()
                .contains("forwardWholeBlob(nothing parsed)"));
    }

    /**
     * Input contract: callers pass the located {@code ServerMlsRcsMessage}. Fed the
     * {@code ApplyMlsControlMessage} envelope ({@code {f1: arm, f2: epoch_auth, f3: era}}), the arm
     * walk reports the wrapper's field number as the arm.
     */
    @Test
    public void anApplyMlsControlMessageReadsAsArmOneWhateverTheRealArmWas() {
        final byte[] realArm = lenField(4, lenField(1, "m-99".getBytes()));   // really ACCEPTED
        final byte[] apply = cat(lenField(1, realArm), lenField(2, EPOCH_AUTH), varintField(3, 7));

        assertEquals("the f1 wrapper masquerades as the bytes arm",
                MlsServerMessage.Arm.RAW, MlsServerBundle.recognise(apply).arm);
        assertEquals("fed the located arm oneof, it is what it always was",
                MlsServerMessage.Arm.ACCEPTED, MlsServerBundle.recognise(realArm).arm);
    }

    /** A GroupEvent ({@code {f1: varint op, f2: ApplyMlsControlMessage}}) reads as arm 2. */
    @Test
    public void aGroupEventReadsAsArmTwoWhateverTheRealArmWas() {
        final byte[] realArm = lenField(1, COMMIT);                          // really RAW
        final byte[] apply = cat(lenField(1, realArm), lenField(2, EPOCH_AUTH), varintField(3, 7));
        final byte[] groupEvent =
                cat(varintField(1, 6), lenField(2, apply), lenField(106, new byte[64]));

        assertEquals("the GroupEvent's f2 masquerades as the commit-bundle arm",
                MlsServerMessage.Arm.SERVER_COMMIT_BUNDLE,
                MlsServerBundle.recognise(groupEvent).arm);
        assertEquals(MlsServerMessage.Arm.RAW, MlsServerBundle.recognise(realArm).arm);
    }

    /** Fed an envelope, {@code isAccepted()} can never fire. */
    @Test
    public void anAckIsInvisibleWhenTheEnvelopeIsFedInstead() {
        final byte[] realArm = lenField(4, lenField(1, "m-99".getBytes()));
        final byte[] apply = cat(lenField(1, realArm), lenField(2, EPOCH_AUTH), varintField(3, 7));
        final byte[] groupEvent = cat(varintField(1, 6), lenField(2, apply));

        assertFalse(MlsServerBundle.recognise(apply).isAccepted());
        assertFalse(MlsServerBundle.recognise(groupEvent).isAccepted());
        assertTrue("and the whole GroupEvent would have gone to the engine as a bundle",
                MlsServerBundle.recognise(groupEvent).forwardWholeBlob);
        assertTrue(MlsServerBundle.recognise(realArm).isAccepted());
    }

    /** The content read is independent of the arm read: extra envelopes are more containers. */
    @Test
    public void theContentReadIsUnaffectedByBeingFedTheEnvelope() {
        final byte[] artifacts =
                cat(lenField(1, wrapped(COMMIT)), lenField(3, wrapped(KEY_DELIVERY)));
        final byte[] realArm = lenField(2, lenField(1, lenField(2, artifacts)));
        final byte[] apply = cat(lenField(1, realArm), lenField(2, EPOCH_AUTH), varintField(3, 7));
        final byte[] groupEvent = cat(varintField(1, 6), lenField(2, apply));

        for (final byte[] level : new byte[][] { realArm, apply, groupEvent }) {
            final MlsServerBundle.Recognised r = MlsServerBundle.recognise(level);
            assertEquals(MlsServerBundle.Variant.COMMIT, r.variant);
            assertEquals(2, r.messages.size());
            assertArrayEquals(COMMIT, r.messages.get(0));
            assertArrayEquals(KEY_DELIVERY, r.messages.get(1));
        }
    }

    /** The descent stops at depth 6, so a hostile payload cannot make the walk expensive. */
    @Test
    public void theDescentIsBoundedAtSixLevels() {
        byte[] deep = lenField(1, wrapped(COMMIT));
        for (int i = 0; i < 4; i++) deep = lenField(1, deep);
        assertEquals("four wrappers in: still reachable",
                1, MlsServerBundle.recognise(lenField(2, deep)).messages.size());

        byte[] deeper = lenField(1, wrapped(COMMIT));
        for (int i = 0; i < 8; i++) deeper = lenField(1, deeper);
        assertTrue("past the bound: reported as nothing found, and forwarded whole",
                MlsServerBundle.recognise(lenField(2, deeper)).forwardWholeBlob);
    }
}

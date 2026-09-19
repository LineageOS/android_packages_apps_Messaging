/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ImdnCheck;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import org.junit.Test;

public final class MlsImdnSignerSplitTest {


    private static FakeShellPort signer(final FakeSysProps knobs, final byte[] signature) {
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("getGroup", grp())
                .returns("sysprops", knobs);
        f.returns("session",
                f.stub(MlsSession.class, "eraEpoch", new byte[12], "rcsSign", signature));
        return f;
    }

    @Test
    public void aDisplayReceiptIsSignedAndCarriedAsBase64() {
        assertEquals(java.util.Base64.getEncoder().encodeToString(new byte[] {1,
                2}), MlsImdnSigner.signImdn(
                signer(new FakeSysProps(), new byte[] {1, 2}).port(), MlsLogSink.NONE, "grp", "+2",
                "r1", "m1", true, 0, VerifiableDerivedContent.FAILURE_UNSET));
        assertNull("the engine would not sign", MlsImdnSigner.signImdn(
                signer(new FakeSysProps(), null).port(),
                MlsLogSink.NONE, "grp", "+2", "r1", "m1", true, 0,
                VerifiableDerivedContent.FAILURE_UNSET));
    }

    @Test
    public void aNegativeReceiptIsUnsignedWhenTheKnobSaysSo() {
        assertNull(MlsImdnSigner.signImdn(signer(
                new FakeSysProps().set("debug.rcs.mls_sign_negative", 0),
                new byte[] {1, 2}).port(), MlsLogSink.NONE, "grp", "+2", "r1", "m1", false,
                VerifiableDerivedContent.DELIVERY_DELIVERED + 1,
                VerifiableDerivedContent.FAILURE_UNSET));
    }


    private static byte[] leafThen(final int leaf, final byte[] content) {
        final byte[] out = new byte[4 + content.length];
        out[3] = (byte) leaf;
        System.arraycopy(content, 0, out, 4, content.length);
        return out;
    }

    /** A port whose engine verifies any signature to {@code verified}. */
    static FakeShellPort imdnPort(final byte[] verified) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .on("base64Decode", a -> java.util.Base64.getDecoder().decode((String) a[0]));
        f.returns("session", f.stub(MlsSession.class, "rcsVerify", verified));
        return f;
    }

    @Test
    public void aSignatureCountsOnlyIfItSignsTheReceiptWeGot() {
        final byte[] shown = VerifiableDerivedContent.displayImdn(
                VerifiableDerivedContent.DISPLAY_DISPLAYED, "m1");
        final ImdnCheck ok = MlsImdnSigner.verifyImdn(imdnPort(leafThen(3, shown)).port(),
                MlsLogSink.NONE,
                "grp", "+2", "AAAA", "m1", true, VerifiableDerivedContent.DISPLAY_DISPLAYED,
                VerifiableDerivedContent.FAILURE_UNSET);
        assertTrue(ok.ok());
        assertEquals(3, ok.leafIndex);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        final ImdnCheck other = MlsImdnSigner.verifyImdn(
                imdnPort(leafThen(3, new byte[] {9})).port(), log,
                "grp", "+2", "AAAA", "m1", true, VerifiableDerivedContent.DISPLAY_DISPLAYED,
                VerifiableDerivedContent.FAILURE_UNSET);
        assertTrue(other.signatureValid);
        assertFalse(other.contentMatches);
        assertTrue(log.said("W", "signs a DIFFERENT statement"));
        assertFalse(MlsImdnSigner.verifyImdn(imdnPort(null).port(), MlsLogSink.NONE, "grp", "+2",
                "AAAA", "m1", true, 1, 0).signatureValid);
    }


    @Test
    public void aDecryptedDisplayReceiptIsParsedAndVerifiedAndAnUnsignedOneIsNot() {
        final byte[] shown = VerifiableDerivedContent.displayImdn(
                VerifiableDerivedContent.DISPLAY_DISPLAYED, "m1");
        final String doc =
                "MLS-Derived-Content-Signature: AAAA\r\n\r\n<imdn><message-id>m1</message-id>"
                + "<display-notification><status><displayed/></status></display-notification></imdn>";
        final ImdnCheck r = MlsImdnSigner.verifyDecryptedImdn(imdnPort(leafThen(2, shown)).port(),
                MlsLogSink.NONE, "grp", "+2",
                doc.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(r.ok());
        assertNull(MlsImdnSigner.verifyDecryptedImdn(imdnPort(null).port(), MlsLogSink.NONE, "grp",
                "+2", "Content-Type: x\r\n\r\n<imdn/>".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)));
    }


    @Test
    public void aGroupReceiptWithNoGroupGetsNoStampsRatherThanTheOneToOnes() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("getGroup", null)
                .returns("groups", new java.util.HashMap<String, MlsTransportTypes.Group>());
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsImdnSigner.imdnStampsFor(f.port(), log, "+2", "grp", "m1", false));
        assertTrue(log.said("W", "NOT falling back"));
        assertNull(MlsImdnSigner.imdnStampsFor(
                new FakeShellPort().returns("ensureSession", false).port(),
                MlsLogSink.NONE, "+2", "grp", "m1", false));
    }


    @Test
    public void theThreeArgumentStampsAreForAOneToOne() {
        assertNull(MlsImdnSigner.imdnStampsFor(
                new FakeShellPort().returns("ensureSession", false).port(),
                MlsLogSink.NONE, "+2", "m1", false));
    }
}

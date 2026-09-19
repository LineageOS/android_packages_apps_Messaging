/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsGroupMetadataTest {


    private static java.util.Map<String, ConvState> held(final byte[]... subjects) {
        final java.util.Map<String, ConvState> m = new java.util.LinkedHashMap<>();
        int i = 0;
        for (final byte[] s : subjects) {
            final ConvState c = new ConvState();
            c.pendingSubject = s;
            c.pendingIcon = s;
            m.put("k" + i++, c);
        }
        return m;
    }

    @Test
    public void conversationsHoldingASubjectCountsOnlyHeldOnes() {
        assertEquals(2, MlsGroupMetadata.conversationsHoldingASubject(new FakeShellPort()
                .returns("convStates", held(new byte[1], null, new byte[3])).port()));
        assertEquals(0, MlsGroupMetadata.conversationsHoldingASubject(new FakeShellPort()
                .returns("convStates", held()).port()));
    }


    @Test
    public void bytesHoldingAnIconIsPricedInBytesNotHolders() {
        assertEquals(4L, MlsGroupMetadata.bytesHoldingAnIcon(new FakeShellPort()
                .returns("convStates", held(new byte[1], null, new byte[3])).port()));
    }


    @Test
    public void currentCommitmentReadsTheSlotsOwnExtensionAndTreatsEmptyAsNone() {
        final Group g = new Group();
        g.groupId = new byte[] {7};
        final FakeShellPort f =
                new FakeShellPort().returns("ensureSession", true).returns("getGroup", g);
        f.returns("session", f.stub(MlsSession.class, "groupExt",
                (java.util.function.Function<Object[], Object>) a ->
                        ((Integer) a[1]) == MlsSession.EXT_ICON_COMMITMENT ? new byte[] {9}
                                : new byte[0]));
        assertArrayEquals(new byte[] {9}, MlsGroupMetadata.currentCommitment(f.port(),
                MlsLogSink.NONE, "g:x", RccFileInfo.SLOT_ICON));
        assertNull(MlsGroupMetadata.currentCommitment(f.port(), MlsLogSink.NONE, "g:x",
                RccFileInfo.SLOT_SUBJECT));
        assertNull(MlsGroupMetadata.currentCommitment(new FakeShellPort()
                .returns("ensureSession", false).port(), MlsLogSink.NONE, "g:x",
                RccFileInfo.SLOT_ICON));
    }
}

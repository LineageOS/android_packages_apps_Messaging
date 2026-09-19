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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.GroupMembershipRouting;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.GroupPlane;
import java.util.concurrent.atomic.AtomicBoolean;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.MlsAdoptionUndo;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.AnchorProbe;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.DeferredResend;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerPack;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsGroupMetadataSplitTest {


    @Test
    public void anEncryptedSubjectOpensNowOrIsHeldForItsKey() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = new FakeShellPort().returns("openStoredIconSubject", null)
                .returns("conv", cs);
        assertNull(MlsGroupMetadata.onEncryptedSubject(f.port(), MlsLogSink.NONE, "grp", "+1",
                new byte[] {7}));
        assertArrayEquals(new byte[] {7}, cs.pendingSubject);
        assertArrayEquals(new byte[] {8}, MlsGroupMetadata.onEncryptedSubject(new FakeShellPort()
                .returns("openStoredIconSubject", new byte[] {8}).port(), MlsLogSink.NONE, "grp",
                "+1", new byte[] {7}));
        assertNull(MlsGroupMetadata.onEncryptedSubject(new FakeShellPort().port(), MlsLogSink.NONE,
                "grp", "+1", new byte[0]));
    }


    @Test
    public void anEncryptedIconIsHeldWithinTheByteBudgetAndDroppedPastIt() {
        final ConvState cs = new ConvState();
        final java.util.Map<String, ConvState> all = new java.util.HashMap<>();
        all.put("g:grp", cs);
        final FakeShellPort f = new FakeShellPort().returns("openStoredIconSubject", null)
                .returns("convStates", all).returns("conv", cs);
        assertNull(MlsGroupMetadata.onEncryptedIcon(f.port(), MlsLogSink.NONE, "grp", "+1",
                new byte[] {1, 2}));
        assertArrayEquals(new byte[] {1, 2}, cs.pendingIcon);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        final byte[] huge = new byte[(int) MlsMetadataKeysPolicy.MAX_PENDING_ICON_BYTES + 1];
        assertNull(MlsGroupMetadata.onEncryptedIcon(f.port(), log, "grp", "+1", huge));
        assertNull("the icon is dropped, not held", cs.pendingIcon);
        assertTrue(log.said("W", "pending-icon budget"));
    }


    @Test
    public void healBeforeMetadataChangeHealsWhenBehindAndRefusesIfItCannot() {
        final Group g = new Group();
        g.groupId = new byte[] {1};
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("getGroup", g)
                .on("lock", a -> null).on("unlock", a -> null)
                .returns("lookServerEpochAuthenticator", Look.asked(new byte[] {9}));
        f.returns("session", f.stub(MlsSession.class, "epochAuth", new byte[] {1}));
        f.returns("selfHeal", -1);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(
                MlsGroupMetadata.healBeforeMetadataChange(f.port(), log, "grp", null, "subject"));
        assertTrue(log.said("E", "refusing the subject change"));
        f.returns("selfHeal", 0);
        assertTrue(MlsGroupMetadata.healBeforeMetadataChange(f.port(), MlsLogSink.NONE, "grp", null,
                "subject"));
        f.returns("lookServerEpochAuthenticator", Look.refusedByLedger("no"));
        final FakeShellPort.Log skip = new FakeShellPort.Log();
        assertTrue("a refused look sends unverified, it does not refuse the change",
                MlsGroupMetadata.healBeforeMetadataChange(f.port(), skip, "grp", null, "subject"));
        assertTrue(skip.said("I", "SKIPPED"));
    }


    @Test
    public void unparseableGroupMetadataKeysAreDroppedOutLoud() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsGroupMetadata.onGroupMetadataKeys(new FakeShellPort().port(), log, "grp",
                "+2", new byte[] {(byte) 0xff}));
        assertTrue(log.said("W", "unparseable/empty GroupMetadataKeys"));
    }


    @Test
    public void theFileInfoKeyWithoutACommitmentNamesTheSlotAndConversation() {
        assertEquals("fileinfo_1_g:grp", MlsGroupMetadata.fileInfoPrefKey("g:grp", 1));
    }


    @Test
    public void aCommitmentSuffixesTheKeyWithItsFirstEightBytes() {
        assertEquals("fileinfo_2_g:grp", MlsGroupMetadata.fileInfoPrefKey("g:grp", 2, null));
        assertEquals("fileinfo_2_g:grp", MlsGroupMetadata.fileInfoPrefKey("g:grp", 2, new byte[0]));
        assertEquals("fileinfo_2_g:grp_0aff000102030405", MlsGroupMetadata.fileInfoPrefKey("g:grp",
                2, new byte[] {0x0a, (byte) 0xff, 0, 1, 2, 3, 4, 5, 6}));
    }


    /** A port for conversation {@code grp}: a live session whose group extension is {@code ext}. */
    private static FakeShellPort iconPort(final byte[] ext) {
        final FakeShellPort f =
                new FakeShellPort().returns("ensureSession", true).returns("getGroup", grp());
        f.returns("session", f.stub(MlsSession.class, "groupExt", ext));
        return f;
    }

    @Test
    public void aCommitmentVerifiesAgainstTheGroupExtensionOrTheOneTheCallerSupplies() {
        final byte[] key = {9, 8, 7};
        final byte[] icon = RccCommitment.commit(RccCommitment.LABEL_ICON, key);
        assertTrue(MlsGroupMetadata.verifyIconSubject(iconPort(icon).port(), MlsLogSink.NONE, "grp",
                "+2", key, true, null));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse("an icon commitment does not verify a subject",
                MlsGroupMetadata.verifyIconSubject(iconPort(icon).port(), log, "grp", "+2", key,
                        false, null));
        assertTrue(log.said("I", "MISMATCH"));
        assertTrue("a supplied commitment wins over the group's",
                MlsGroupMetadata.verifyIconSubject(iconPort(new byte[] {1}).port(), MlsLogSink.NONE,
                        "grp", "+2", key, true, icon));
    }

    @Test
    public void nothingCommittedOrNoSessionVerifiesNothing() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsGroupMetadata.verifyIconSubject(iconPort(null).port(), log, "grp", "+2",
                new byte[] {1}, true, null));
        assertTrue(log.said("I", "nothing to verify"));
        assertFalse(MlsGroupMetadata.verifyIconSubject(
                iconPort(null).returns("ensureSession", false).port(),
                MlsLogSink.NONE, "grp", "+2", new byte[] {1}, true, new byte[] {1}));
    }


    @Test
    public void anIconOpensOnlyUnderItsCommitment() {
        final byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        final RccFileInfo.Sealed s = RccFileInfo.icon(png, "image/png");
        final byte[] committed = RccCommitment.commit(RccCommitment.LABEL_ICON,
                RccFileInfo.parse(s.fileInfo).metadata.keyMaterial);
        assertArrayEquals(png, MlsGroupMetadata.openIconSubject(iconPort(committed).port(),
                MlsLogSink.NONE, "grp", "+2", s.fileInfo, s.ciphertext, null));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsGroupMetadata.openIconSubject(iconPort(new byte[] {1, 2}).port(), log,
                "grp", "+2", s.fileInfo, s.ciphertext, null));
        assertTrue(log.said("W", "refusing to decrypt or display"));
        final FakeShellPort.Log bad = new FakeShellPort.Log();
        assertNull(MlsGroupMetadata.openIconSubject(iconPort(committed).port(), bad, "grp", "+2",
                new byte[] {0x7f}, s.ciphertext, null));
        assertTrue(bad.said("W", "unparseable FileInfo"));
    }


    @Test
    public void retainedContentKeysAreEvictedOldestFirstPastTheCap() {
        final FakePrefs p = new FakePrefs();
        final FakeShellPort f = new FakeShellPort().returns("prefs", p);
        final int cap = MlsMetadataKeysPolicy.retainedContentKeysPerSlot();
        final FakeShellPort.Log log = new FakeShellPort.Log();
        for (int i = 0; i <= cap; i++) {
            final byte[] c = {(byte) i};
            p.values.put(MlsGroupMetadata.fileInfoPrefKey("g:grp", 1, c), "key" + i);
            MlsGroupMetadata.pruneRetainedContentKeys(f.port(), log, "g:grp", 1, c);
        }
        assertFalse("the oldest is gone",
                p.values.containsKey(MlsGroupMetadata.fileInfoPrefKey("g:grp", 1, new byte[] {0})));
        assertTrue(p.values.containsKey(
                MlsGroupMetadata.fileInfoPrefKey("g:grp", 1, new byte[] {(byte) cap})));
        assertTrue(log.said("I", "evicted a retained"));
    }


    @Test
    public void metadataKeysAreFramedAndSentOnlyWhenThereIsSomethingToSend() {
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("sendFramedToGroup", true);
        assertTrue(MlsGroupMetadata.sendGroupMetadataKeys(f.port(), MlsLogSink.NONE, "grp",
                new byte[] {2}, new byte[] {1}));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("sendFramedToGroup(grp")));
        final FakeShellPort empty = new FakeShellPort().returns("ensureSession", true);
        assertFalse(MlsGroupMetadata.sendGroupMetadataKeys(empty.port(), MlsLogSink.NONE, "grp",
                null, null));
        assertFalse(empty.calls.stream().anyMatch(c -> c.startsWith("sendFramedToGroup")));
    }


    private static FakeShellPort iconChangePort(final MlsProviderRpc.ControlResult r,
            final boolean[] restored) {
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("resolveInbound", "g:grp")
                .returns("getGroup", grp()).on("putGroup", a -> null);
        f.returns("session", f.stub(MlsSession.class, "exportGroupSnapshot", new byte[] {5},
                "epochAuth", new byte[] {1}, "eraEpoch", new byte[12], "commitGroupMetadata",
                new MlsGroupArtifacts(null, new byte[] {9}, new byte[] {8}, new byte[] {7}, GID),
                "encryptWithAad", new byte[] {6},
                "restoreGroupSnapshot",
                (Function<Object[], Object>) a -> { restored[0] = true; return true; }));
        f.returns("rpc",
                f.stub(MlsProviderRpc.class, "changeGroupIconMls", r, "changeGroupSubjectMls", r));
        f.returns("applyGroupIcon", true).returns("applyGroupSubject", true);
        return f;
    }

    /**
     * The sender cannot decrypt its own subject or icon, and the group's copy of its own control
     * is not re-processed, so the change is shown to it when the provider accepts it, whichever
     * caller asked; a refused change is not shown.
     */
    @Test
    public void anAcceptedSubjectOrIconIsShownToTheSenderAndARefusedOneIsNot() {
        final boolean[] restored = {false};
        final MlsProviderRpc.ControlResult accepted = new MlsProviderRpc.ControlResult(
                MlsProviderRpc.ControlResult.VERDICT_OK, null, null);
        final FakeShellPort subject = iconChangePort(accepted, restored);
        assertNotNull(MlsGroupMetadata.changeIconOrSubject(subject.port(), MlsLogSink.NONE, "grp",
                "+2", "new name".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain",
                false));
        assertTrue("the sender's own conversation keeps the old name after an accepted subject",
                subject.calls.contains("applyGroupSubject(grp, new name)"));
        assertFalse(subject.calls.stream().anyMatch(c -> c.startsWith("applyGroupIcon(")));
        assertTrue("the name is shown only after the provider accepted the commit",
                subject.calls.indexOf("MlsProviderRpc.changeGroupSubjectMls")
                        < subject.calls.indexOf("applyGroupSubject(grp, new name)"));

        final FakeShellPort icon = iconChangePort(accepted, restored);
        assertNotNull(MlsGroupMetadata.changeIconOrSubject(icon.port(), MlsLogSink.NONE, "grp",
                "+2", new byte[] {1, 2, 3}, "image/png", true));
        assertTrue("the sender's own conversation keeps the old icon after an accepted change",
                icon.calls.contains("applyGroupIcon(grp, 010203)"));

        for (final int refusal : new int[] {MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                MlsProviderRpc.ControlResult.VERDICT_TRANSPORT_FAILED}) {
            final FakeShellPort no = iconChangePort(
                    new MlsProviderRpc.ControlResult(refusal, null, "no"), restored);
            assertNull(MlsGroupMetadata.changeIconOrSubject(no.port(), MlsLogSink.NONE, "grp",
                    "+2", "new name".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "text/plain", false));
            assertFalse("a subject the provider did not accept was shown (verdict " + refusal + ")",
                    no.calls.stream().anyMatch(c -> c.startsWith("applyGroup")));
        }
    }

    @Test
    public void anIconChangeIsCommittedAndItsCiphertextReturnedOrRolledBackWhenRefused() {
        final boolean[] restored = {false};
        final FakeShellPort ok = iconChangePort(new MlsProviderRpc.ControlResult(
                MlsProviderRpc.ControlResult.VERDICT_OK, null, null), restored);
        assertNotNull(MlsGroupMetadata.changeIconOrSubject(ok.port(), MlsLogSink.NONE, "grp", "+2",
                new byte[] {1, 2, 3}, "image/png", true));
        assertTrue(ok.calls.contains("MlsProviderRpc.changeGroupIconMls"));
        assertFalse(restored[0]);
        final FakeShellPort no = iconChangePort(new MlsProviderRpc.ControlResult(
                MlsProviderRpc.ControlResult.VERDICT_REJECTED, null, "no"), restored);
        assertNull(MlsGroupMetadata.changeIconOrSubject(no.port(), MlsLogSink.NONE, "grp", "+2",
                "s".getBytes(), "text/plain", false));
        assertTrue(no.calls.contains("MlsProviderRpc.changeGroupSubjectMls"));
        assertTrue(restored[0]);
    }


    /**
     * A port whose engine builds an icon/subject commit and whose server answers {@code verdict}.
     */
    private static FakeShellPort publishPort(final int verdict) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("resolveInbound", "g:grp")
                .on("putGroup", a -> null).returns("convIfAny", null);
        f.returns("session", f.stub(MlsSession.class, "exportGroupSnapshot", new byte[] {5},
                "epochAuth", new byte[] {1}, "eraEpoch", new byte[12], "restoreGroupSnapshot", true,
                "commitIconSubject", new MlsGroupArtifacts(null, new byte[] {9}, new byte[] {8},
                        new byte[] {7}, GID)));
        f.on("rpc", a -> f.stub(MlsProviderRpc.class, "applyMlsControl",
                new MlsProviderRpc.ControlResult(verdict, null, "test")));
        return f;
    }

    @Test
    public void theNegativeControlCommitsOnlyWhatItWasGivenAndAppliesAnAcceptance() {
        final FakeShellPort f = publishPort(MlsProviderRpc.ControlResult.VERDICT_OK);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(0, MlsGroupMetadata.publishIconSubject(f.port(), log, "grp", "+2",
                new byte[] {3}, null));
        assertTrue(log.said("W", "NEGATIVE CONTROL"));
        assertTrue(log.said("I", "ACCEPTED"));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("putGroup(")));
    }

    @Test
    public void nothingToCommitOrARefusalRollsBackAndReturnsMinusOne() {
        final FakeShellPort.Log empty = new FakeShellPort.Log();
        final FakeShellPort none = publishPort(MlsProviderRpc.ControlResult.VERDICT_OK);
        assertEquals(-1, MlsGroupMetadata.publishIconSubject(none.port(), empty, "grp", "+2", null,
                new byte[0]));
        assertTrue(empty.said("W", "nothing to commit"));
        assertFalse(none.calls.contains("MlsSession.commitIconSubject"));
        final FakeShellPort refused = publishPort(MlsProviderRpc.ControlResult.VERDICT_REJECTED);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(-1, MlsGroupMetadata.publishIconSubject(refused.port(), log, "grp", "+2", null,
                new byte[] {4}));
        assertTrue(refused.calls.contains("MlsSession.restoreGroupSnapshot"));
        assertTrue(log.said("W", "icon/subject REFUSED"));
        assertFalse(refused.calls.stream().anyMatch(c -> c.startsWith("putGroup(")));
    }


    @Test
    public void aStoredKeyIsOpenedOnlyAgainstTheCurrentCommitment() {
        assertNull(MlsGroupMetadata.openStoredIconSubject(new FakeShellPort().port(),
                MlsLogSink.NONE, null, null, true, new byte[] {1}));
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b));
        f.returns("session", f.stub(MlsSession.class, "groupExt", null));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsGroupMetadata.openStoredIconSubject(f.port(), log, "grp", null, true,
                new byte[] {1}));
        assertTrue(log.said("I", "cannot read the current"));
    }


    /**
     * A port holding {@code cs} for the group, whose engine has no current commitment to open
     * against.
     */
    private static FakeShellPort heldPort(final ConvState cs) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("convIfAny", cs)
                .returns("convStates", new java.util.HashMap<String, ConvState>());
        f.returns("session", f.stub(MlsSession.class, "groupExt", null));
        return f;
    }

    @Test
    public void aHeldIconThatCannotOpenYetIsHeldAgainAndNothingIsApplied() {
        final ConvState cs = new ConvState();
        final byte[] icon = {1, 2, 3};
        cs.pendingIcon = icon;
        final FakeShellPort f = heldPort(cs);
        MlsGroupMetadata.openPendingIcon(f.port(), MlsLogSink.NONE, KEY, "grp", "+2");
        assertSame(icon, cs.pendingIcon);
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("applyGroupIcon(")));
    }


    @Test
    public void aHeldSubjectThatCannotOpenYetIsHeldAgainAndNothingIsApplied() {
        final ConvState cs = new ConvState();
        final byte[] subject = {4, 5};
        cs.pendingSubject = subject;
        final FakeShellPort f = heldPort(cs);
        MlsGroupMetadata.openPendingSubject(f.port(), MlsLogSink.NONE, KEY, "grp", "+2");
        assertSame(subject, cs.pendingSubject);
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("applyGroupSubject(")));
    }


    @Test
    public void anUnparseableFileInfoIsNotAKey() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsGroupMetadata.onFileInfo(new FakeShellPort().port(), log, "grp", "+2",
                new byte[] {(byte) 0xff}));
        assertTrue(log.said("W", "unparseable FileInfo"));
    }


    /** No session, so the currency check cannot pass, and the heal it falls back to fails. */
    private static FakeShellPort unhealablePort() {
        return new FakeShellPort().returns("ensureSession", false).returns("selfHeal", -1);
    }

    @Test
    public void anIconChangeWeCannotHealBeforeIsRefusedBeforeAnyUpload() {
        final FakeShellPort f = unhealablePort();
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsGroupMetadata.changeGroupIcon(f.port(), log, "grp", null, new byte[] {1},
                "image/png"));
        assertTrue(log.said("E", "refusing the icon change"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("rpc(")));
    }


    @Test
    public void aSubjectChangeWeCannotHealBeforeIsRefused() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsGroupMetadata.changeGroupSubject(unhealablePort().port(), log, "grp", null,
                new byte[] {1}, "text/plain"));
        assertTrue(log.said("E", "refusing the subject change"));
    }
}

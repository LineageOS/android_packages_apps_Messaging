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
package org.lineageos.rcs.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The contract-skew guard's decision, pinned against THREE REAL LAYOUTS recovered from real builds
 *.
 *
 * <p><b>These fixtures are not invented.</b> {@link #PRE_V59} was read out of the compiled artifact
 * — {@code javap -constants} over the {@code TRANSACTION_*} fields of {@code IRcsProvider$Stub} in
 * {@code out/target/common/obj/JAVA_LIBRARIES/messaging-rcs-contract-aidl_intermediates/classes.jar},
 * built 2026-09-08 14:47. {@link #V59} and {@link #TREE_V60} were derived from the {@code .aidl} at
 * commits {@code 067e6256} and {@code d7d2a6a3} by a parser first VALIDATED by reproducing
 * {@code PRE_V59} from the matching revision ({@code f9b55018}) byte-for-byte against that compiled
 * artifact. So the ordering below is what aidl actually numbered, not what the source looks like it
 * should number.
 *
 * <p><b>What would make these tests fail.</b> The headline case, {@link
 * #skew_reproducesTheMeasuredMisDispatch}, is the 2026-09-11 incident itself: an app on the v59
 * layout dialling ordinal 18 for {@code uploadKeyPackages} while the provider on the v60 layout has
 * {@code mlsForgetGroupConversation} there. If {@code compare} were stubbed to return compatible it
 * fails; if it were stubbed to return incompatible, {@link #identicalLayouts_compatible} and {@link
 * #trulyAdditive_compatible} fail. Both directions are covered on purpose — a guard that refuses
 * everything is as useless as one that refuses nothing, and only one of those is obvious in a log.
 */
public class RcsContractLayoutTest {

    /**
     * The layout of the build installed as messaging2 before v59 — 60 methods. Recovered from the
     * compiled {@code classes.jar} (see the class doc), which is why it is the reference the parser
     * that produced the other two was validated against.
     */
    private static final String[] PRE_V59 = {
            "getContractVersion", "getProviderCaps", "attach", "detach", "startForSub",
            "stopForSub", "getCapabilitiesForSub", "lookupRcsCapability", "lookupPeerMlsCaps",
            "isMlsReady", "createMlsConversation", "applyMlsControl", "getMlsGroupInfo",
            "claimPeerKeyPackage", "claimPeerKeyPackages", "mlsForgetConversation",
            "uploadKeyPackages", "getMlsServerEraEpoch", "exportMlsIdentity",
            "getMlsTransportProfile", "sendMlsCiphertext", "sendGroupMlsCiphertext",
            "getMlsGroupIdForPeer", "sendMessage", "sendImdn", "sendReconciliationReceipt",
            "sendTyping", "submitOtp", "submitTosConsent", "getTosState", "createGroup",
            "getGroupInfo", "getGroupIds", "addGroupUsers", "addGroupUsersMls", "removeGroupUsers",
            "removeGroupUsersMls", "selfLeaveGroupMls", "renameGroup", "sendGroupMessage",
            "sendLocation", "sendFile", "fetchMissedCommits", "fetchServerEpochAuthenticator",
            "changeGroupSubjectMls", "sendMlsNegativeDeliveryImdn", "acceptIncomingFile",
            "sendGroupTyping", "sendReaction", "getBotBrand", "sendBotPostback",
            "setChatbotEnabled", "getE2eeInfo", "setE2eeEnabled", "canServeSub",
            "rejectIncomingFile", "ackInboundMessages", "sendMlsImdn", "sendMlsGroupImdn",
            "getMlsGroupInfoForGroup"
    };

    /**
     * Contract v59 ({@code 067e6256}, 2026-09-08): {@code mlsForgetGroupConversation} INSERTED after
     * {@code mlsForgetConversation} at ordinal 17. 61 methods. This is a layout an
     * already-installed app can still be on.
     */
    private static final String[] V59 = {
            "getContractVersion", "getProviderCaps", "attach", "detach", "startForSub",
            "stopForSub", "getCapabilitiesForSub", "lookupRcsCapability", "lookupPeerMlsCaps",
            "isMlsReady", "createMlsConversation", "applyMlsControl", "getMlsGroupInfo",
            "claimPeerKeyPackage", "claimPeerKeyPackages", "mlsForgetConversation",
            "mlsForgetGroupConversation", "uploadKeyPackages", "getMlsServerEraEpoch",
            "exportMlsIdentity", "getMlsTransportProfile", "sendMlsCiphertext",
            "sendGroupMlsCiphertext", "getMlsGroupIdForPeer", "sendMessage", "sendImdn",
            "sendReconciliationReceipt", "sendTyping", "submitOtp", "submitTosConsent",
            "getTosState", "createGroup", "getGroupInfo", "getGroupIds", "addGroupUsers",
            "addGroupUsersMls", "removeGroupUsers", "removeGroupUsersMls", "selfLeaveGroupMls",
            "renameGroup", "sendGroupMessage", "sendLocation", "sendFile", "fetchMissedCommits",
            "fetchServerEpochAuthenticator", "changeGroupSubjectMls",
            "sendMlsNegativeDeliveryImdn", "acceptIncomingFile", "sendGroupTyping", "sendReaction",
            "getBotBrand", "sendBotPostback", "setChatbotEnabled", "getE2eeInfo", "setE2eeEnabled",
            "canServeSub", "rejectIncomingFile", "ackInboundMessages", "sendMlsImdn",
            "sendMlsGroupImdn", "getMlsGroupInfoForGroup"
    };

    /**
     * Contract v60 ({@code 17073fde}, 2026-09-10): {@code claimPeerKeyPackagesWithOutcome} INSERTED
     * after {@code claimPeerKeyPackages} at ordinal 16, pushing everything below it down one more.
     * 62 methods. This is the layout the provider in this tree was built from.
     */
    private static final String[] TREE_V60 = {
            "getContractVersion", "getProviderCaps", "attach", "detach", "startForSub",
            "stopForSub", "getCapabilitiesForSub", "lookupRcsCapability", "lookupPeerMlsCaps",
            "isMlsReady", "createMlsConversation", "applyMlsControl", "getMlsGroupInfo",
            "claimPeerKeyPackage", "claimPeerKeyPackages", "claimPeerKeyPackagesWithOutcome",
            "mlsForgetConversation", "mlsForgetGroupConversation", "uploadKeyPackages",
            "getMlsServerEraEpoch", "exportMlsIdentity", "getMlsTransportProfile",
            "sendMlsCiphertext", "sendGroupMlsCiphertext", "getMlsGroupIdForPeer", "sendMessage",
            "sendImdn", "sendReconciliationReceipt", "sendTyping", "submitOtp", "submitTosConsent",
            "getTosState", "createGroup", "getGroupInfo", "getGroupIds", "addGroupUsers",
            "addGroupUsersMls", "removeGroupUsers", "removeGroupUsersMls", "selfLeaveGroupMls",
            "renameGroup", "sendGroupMessage", "sendLocation", "sendFile", "fetchMissedCommits",
            "fetchServerEpochAuthenticator", "changeGroupSubjectMls",
            "sendMlsNegativeDeliveryImdn", "acceptIncomingFile", "sendGroupTyping", "sendReaction",
            "getBotBrand", "sendBotPostback", "setChatbotEnabled", "getE2eeInfo", "setE2eeEnabled",
            "canServeSub", "rejectIncomingFile", "ackInboundMessages", "sendMlsImdn",
            "sendMlsGroupImdn", "getMlsGroupInfoForGroup"
    };

    // -----------------------------------------------------------------
    // The incident
    // -----------------------------------------------------------------

    /**
     * The 2026-09-11 measurement, reconstructed from the two layouts alone.
     *
     * <p>Device log, same millisecond, deviceC:
     * <pre>
     *   MlsProviderTransport: publishKeyPackages(11) ... -&gt; REJECTED
     *   RcsProviderService: mlsForgetGroupConversation: no group id — refusing ...
     * </pre>
     * The ordinal skew was recorded as inferred ("I did not diff the generated stubs to confirm
     * WHICH ordinal moved"). This is that diff: the app's {@code uploadKeyPackages} and the
     * provider's {@code mlsForgetGroupConversation} are the SAME transaction code, 18.
     */
    @Test
    public void skew_reproducesTheMeasuredMisDispatch() {
        final int appOrdinal = ordinalOf(V59, "uploadKeyPackages");
        assertEquals("the app dialled transaction 18", 18, appOrdinal);
        assertEquals("which is what the provider answers there",
                "mlsForgetGroupConversation", TREE_V60[appOrdinal - RcsContractLayout.FIRST_ORDINAL]);
    }

    /** And the guard refuses that pairing, naming the ordinal and both method names. */
    @Test
    public void skew_v59AppAgainstV60Provider_refusedAndNamed() {
        final RcsContractLayout.Verdict v = cmp(V59, 24, TREE_V60, 59);
        assertFalse(v.reason, v.compatible);
        // First divergence is ordinal 16, where v60 inserted.
        assertTrue(v.reason, v.reason.contains("ORDINAL SKEW at transaction 16"));
        assertTrue(v.reason, v.reason.contains("the app calls 'mlsForgetConversation'"));
        assertTrue(v.reason, v.reason.contains("'claimPeerKeyPackagesWithOutcome'"));
        // And it spells out a concrete mis-dispatch rather than only "mismatch".
        assertTrue(v.reason, v.reason.contains("uploadKeyPackages -> mlsForgetGroupConversation"));
    }

    /**
     * Both versions must appear, because a reader who sees only "mismatch" cannot tell which side
     * to rebuild. Uses the real stale pair: the app said 24 while the provider said 59.
     */
    @Test
    public void skew_messageNamesBothSides() {
        final RcsContractLayout.Verdict v = cmp(V59, 24, TREE_V60, 59);
        assertTrue(v.reason, v.reason.contains("app v24"));
        assertTrue(v.reason, v.reason.contains("provider v59"));
        assertTrue(v.reason, v.reason.contains("61 methods"));
        assertTrue(v.reason, v.reason.contains("62 methods"));
    }

    /**
     * The version ints could not have caught it, which is the whole reason the layout check exists.
     * Provider 59 against app 24 passes {@code provider >= app} — the old guard's entire test.
     */
    @Test
    public void theOldVersionCheckWouldHavePassedThisSkew() {
        final int appSaid = 24;
        final int providerSaid = 59;
        assertFalse("provider < app (the old refusal) was never true here", providerSaid < appSaid);
        assertFalse("yet the layouts are incompatible",
                cmp(V59, appSaid, TREE_V60, providerSaid).compatible);
    }

    /** The other real pair on the fleet: the 2026-09-08 build against the tree, skewed at 16 too. */
    @Test
    public void skew_preV59AppAgainstV60Provider_refused() {
        final RcsContractLayout.Verdict v = cmp(PRE_V59, 24, TREE_V60, 61);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("ORDINAL SKEW at transaction 16"));
    }

    // -----------------------------------------------------------------
    // The guard must still pass what is genuinely safe
    // -----------------------------------------------------------------

    @Test
    public void identicalLayouts_compatible() {
        assertTrue(cmp(TREE_V60, 61, TREE_V60, 61).compatible);
        assertTrue(cmp(V59, 59, V59, 59).compatible);
    }

    /**
     * A truly ADDITIVE bump — the method APPENDED, not inserted — stays compatible. This is the
     * case the old {@code >=} check was written for; it was simply not the case that occurred.
     */
    @Test
    public void trulyAdditive_compatible() {
        final String[] appended = new String[TREE_V60.length + 1];
        System.arraycopy(TREE_V60, 0, appended, 0, TREE_V60.length);
        appended[TREE_V60.length] = "someMethodAddedLater";
        final RcsContractLayout.Verdict v = cmp(TREE_V60, 61, appended, 62);
        assertTrue(v.reason, v.compatible);
    }

    /** One method inserted anywhere renumbers the tail, and that is the failure. */
    @Test
    public void singleInsertion_anywhere_refused() {
        for (int at = 1; at < TREE_V60.length; at++) {
            final String[] inserted = new String[TREE_V60.length + 1];
            System.arraycopy(TREE_V60, 0, inserted, 0, at);
            inserted[at] = "insertedHere";
            System.arraycopy(TREE_V60, at, inserted, at + 1, TREE_V60.length - at);
            assertFalse("insertion at index " + at + " must be refused",
                    cmp(TREE_V60, 61, inserted, 62).compatible);
        }
    }

    // -----------------------------------------------------------------
    // Fail closed
    // -----------------------------------------------------------------

    /** A provider too old to answer the probe reports no layout. That is a refusal, not a pass. */
    @Test
    public void providerDidNotAnswer_refused() {
        assertFalse(cmp(TREE_V60, 61, null, 59).compatible);
        assertFalse(cmp(TREE_V60, 61, new String[0], 59).compatible);
    }

    /** If OUR OWN derivation failed (R8 stripped the fields), we refuse rather than assume. */
    @Test
    public void ourOwnDerivationFailed_refused() {
        final RcsContractLayout.Verdict v = cmp(null, 61, TREE_V60, 61);
        assertFalse(v.compatible);
        assertTrue(v.reason, v.reason.contains("could not derive the app transaction layout"));
    }

    /** A layout not anchored at getContractVersion means we are reading the wrong thing. */
    @Test
    public void wrongAnchor_refused() {
        final String[] shifted = TREE_V60.clone();
        shifted[0] = "somethingElse";
        assertFalse(cmp(shifted, 61, TREE_V60, 61).compatible);
        assertFalse(cmp(TREE_V60, 61, shifted, 61).compatible);
    }

    /** A provider with fewer methods than we call: our top ordinals hit nothing. Refuse. */
    @Test
    public void providerShorterThanUs_refused() {
        final String[] truncated = new String[TREE_V60.length - 1];
        System.arraycopy(TREE_V60, 0, truncated, 0, truncated.length);
        final RcsContractLayout.Verdict v = cmp(TREE_V60, 61, truncated, 60);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("FEWER methods"));
    }

    // -----------------------------------------------------------------
    // Digest
    // -----------------------------------------------------------------

    @Test
    public void digest_isStableAndDistinguishesEveryRealLayout() {
        assertEquals(RcsContractLayout.digest(TREE_V60), RcsContractLayout.digest(TREE_V60.clone()));
        assertNotEquals(RcsContractLayout.digest(PRE_V59), RcsContractLayout.digest(V59));
        assertNotEquals(RcsContractLayout.digest(V59), RcsContractLayout.digest(TREE_V60));
        assertEquals(12, RcsContractLayout.digest(TREE_V60).length());
    }

    /** Order is part of the identity: a pure REORDER must change the digest. */
    @Test
    public void digest_coversOrderNotJustMembership() {
        final String[] swapped = TREE_V60.clone();
        final String t = swapped[20];
        swapped[20] = swapped[21];
        swapped[21] = t;
        assertNotEquals(RcsContractLayout.digest(TREE_V60), RcsContractLayout.digest(swapped));
    }

    /** An underivable layout must not digest to something a real layout could also produce. */
    @Test
    public void digest_ofNothingIsNotADigest() {
        assertEquals("none", RcsContractLayout.digest(null));
        assertEquals("none", RcsContractLayout.digest(new String[0]));
    }

    // -----------------------------------------------------------------
    // The fixtures themselves
    // -----------------------------------------------------------------

    /** Each recorded layout is anchored where aidl anchors it, and names no method twice. */
    @Test
    public void fixtures_areWellFormed() {
        for (final String[] layout : new String[][] { PRE_V59, V59, TREE_V60 }) {
            assertEquals(RcsContractLayout.ANCHOR_METHOD, layout[0]);
            final java.util.Set<String> seen = new java.util.HashSet<>();
            for (final String m : layout) {
                assertTrue("duplicate method " + m, seen.add(m));
            }
        }
        assertEquals(60, PRE_V59.length);
        assertEquals(61, V59.length);
        assertEquals(62, TREE_V60.length);
    }


    // -----------------------------------------------------------------
    // The OTHER direction: IRcsProviderCallback
    // -----------------------------------------------------------------
    //
    // The provider dials these and the APP dispatches them, so a skew here mis-delivers INBOUND
    // work — a message, a receipt, an MLS control frame — onto the wrong handler. The guard covers
    // it because the history says it must: this interface has taken FOUR mid-interface insertions
    // (33380aba, 4a6c9040, d966d46f, 82a2edb9), each shifting everything from onGroupTyping down.

    /** IRcsProviderCallback at d966d46f — 22 methods, before 82a2edb9 inserted onMlsNegativeDelivery. */
    private static final String[] CB_22 = {
            "onIncomingMessage", "onMessageStatus", "onImdnReceipt", "onImdnReceiptForPeer",
            "onRegistrationStateChanged", "onProvisioningStateChanged", "onOtpRequired",
            "onTyping", "onCarrierTosStateChanged", "onGroupEvent", "onEncryptedGroupSubject",
            "onEncryptedGroupIcon", "onMlsControlBundle", "onGroupTyping", "onGroupImdnReceipt",
            "onIncomingMedia", "onIncomingReaction", "onIncomingBotMessage", "onE2eeStateChanged",
            "onMlsControl", "onMlsCiphertext", "onMlsIdentityChanged"
    };

    /** IRcsProviderCallback in the tree — 23 methods. */
    private static final String[] CB_23 = {
            "onIncomingMessage", "onMessageStatus", "onImdnReceipt", "onImdnReceiptForPeer",
            "onRegistrationStateChanged", "onProvisioningStateChanged", "onOtpRequired",
            "onTyping", "onCarrierTosStateChanged", "onGroupEvent", "onEncryptedGroupSubject",
            "onEncryptedGroupIcon", "onMlsControlBundle", "onMlsNegativeDelivery", "onGroupTyping",
            "onGroupImdnReceipt", "onIncomingMedia", "onIncomingReaction", "onIncomingBotMessage",
            "onE2eeStateChanged", "onMlsControl", "onMlsCiphertext", "onMlsIdentityChanged"
    };

    /** The INBOUND direction: the provider dials, the app dispatches. */
    private static RcsContractLayout.Verdict cmpCb(final String[] provider,
            final int providerVersion, final String[] app, final int appVersion) {
        return RcsContractLayout.compare("IRcsProviderCallback",
                RcsContractLayout.CALLBACK_ANCHOR_METHOD,
                "provider", provider, providerVersion, "app", app, appVersion);
    }

    /**
     * 82a2edb9 INSERTED onMlsNegativeDelivery at ordinal 14, which is why this is not a theoretical
     * direction: a newer provider dialling onGroupTyping reaches the older app's onMlsNegativeDelivery.
     */
    @Test
    public void callback_insertionIsRealAndRefused() {
        assertEquals("onMlsNegativeDelivery", CB_23[13]);
        assertEquals("onGroupTyping", CB_22[13]);
        final RcsContractLayout.Verdict v = cmpCb(CB_23, 61, CB_22, 59);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("IRcsProviderCallback"));
        assertTrue(v.reason, v.reason.contains("ORDINAL SKEW at transaction 14"));
        // Named in the direction it is actually dialled, not always "app -> provider".
        assertTrue(v.reason, v.reason.contains("the provider calls 'onMlsNegativeDelivery'"));
        assertTrue(v.reason, v.reason.contains("the app has 'onGroupTyping'"));
    }

    /** The callback anchor is its own; using the IRcsProvider one would refuse every real layout. */
    @Test
    public void callback_hasItsOwnAnchor() {
        assertEquals(RcsContractLayout.CALLBACK_ANCHOR_METHOD, CB_23[0]);
        assertNotEquals(RcsContractLayout.ANCHOR_METHOD, RcsContractLayout.CALLBACK_ANCHOR_METHOD);
        assertTrue(cmpCb(CB_23, 61, CB_23, 61).compatible);
    }

    /** An app older than the provider is short by a method it will never be dialled on. Refuse. */
    @Test
    public void callback_appShorterThanProvider_refused() {
        final RcsContractLayout.Verdict v = cmpCb(CB_23, 61, CB_22, 59);
        assertFalse(v.reason, v.compatible);
    }

    /** A skew in EITHER interface is a skew; the two layouts are independent identities. */
    @Test
    public void callback_digestIsIndependentOfTheProviderInterface() {
        assertNotEquals(RcsContractLayout.digest(CB_23), RcsContractLayout.digest(TREE_V60));
        assertNotEquals(RcsContractLayout.digest(CB_22), RcsContractLayout.digest(CB_23));
    }

    /**
     * The OUTBOUND direction — the app dials {@code IRcsProvider}, the provider dispatches. Stated
     * once here so each test reads as a fact about the layouts rather than as argument plumbing.
     */
    private static RcsContractLayout.Verdict cmp(final String[] app, final int appVersion,
            final String[] provider, final int providerVersion) {
        return RcsContractLayout.compare("IRcsProvider", RcsContractLayout.ANCHOR_METHOD,
                "app", app, appVersion, "provider", provider, providerVersion);
    }

    private static int ordinalOf(final String[] layout, final String method) {
        for (int i = 0; i < layout.length; i++) {
            if (layout[i].equals(method)) {
                return i + RcsContractLayout.FIRST_ORDINAL;
            }
        }
        throw new AssertionError("no such method in layout: " + method);
    }
}

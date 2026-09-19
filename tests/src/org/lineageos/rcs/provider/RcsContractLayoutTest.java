/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link RcsContractLayout}'s decision against transaction layouts taken from real builds of the
 * contract, in the order aidl numbered them. Skewed pairs must be refused and safe ones accepted,
 * so a verdict stubbed either way fails. See docs/rcs/provider-contract.md.
 */
public class RcsContractLayoutTest {

    /**
     * The layout before v59, 60 methods, read from the compiled stub's {@code TRANSACTION_*}
     * fields.
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

    /** Contract v59, 61 methods: {@code mlsForgetGroupConversation} inserted at ordinal 17. */
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

    /** Contract v60, 62 methods: {@code claimPeerKeyPackagesWithOutcome} inserted at ordinal 16. */
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

    // ---- Skew

    /**
     * A v59 app's {@code uploadKeyPackages} and a v60 provider's {@code mlsForgetGroupConversation}
     * share transaction 18.
     */
    @Test
    public void skew_reproducesTheMeasuredMisDispatch() {
        final int appOrdinal = ordinalOf(V59, "uploadKeyPackages");
        assertEquals("the app dialled transaction 18", 18, appOrdinal);
        assertEquals("which is what the provider answers there",
                "mlsForgetGroupConversation",
                TREE_V60[appOrdinal - RcsContractLayout.FIRST_ORDINAL]);
    }

    /** And the guard refuses that pairing, naming the ordinal and both method names. */
    @Test
    public void skew_v59AppAgainstV60Provider_refusedAndNamed() {
        final RcsContractLayout.Verdict v = cmp(V59, 24, TREE_V60, 59);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("ORDINAL SKEW at transaction 16"));
        assertTrue(v.reason, v.reason.contains("the app calls 'mlsForgetConversation'"));
        assertTrue(v.reason, v.reason.contains("'claimPeerKeyPackagesWithOutcome'"));
        assertTrue(v.reason, v.reason.contains("uploadKeyPackages -> mlsForgetGroupConversation"));
    }

    /** Both versions appear, so a reader can tell which side to rebuild. */
    @Test
    public void skew_messageNamesBothSides() {
        final RcsContractLayout.Verdict v = cmp(V59, 24, TREE_V60, 59);
        assertTrue(v.reason, v.reason.contains("app v24"));
        assertTrue(v.reason, v.reason.contains("provider v59"));
        assertTrue(v.reason, v.reason.contains("61 methods"));
        assertTrue(v.reason, v.reason.contains("62 methods"));
    }

    /**
     * A {@code provider >= app} version check passes this skewed pair; only the layout catches it.
     */
    @Test
    public void theOldVersionCheckWouldHavePassedThisSkew() {
        final int appSaid = 24;
        final int providerSaid = 59;
        assertFalse("provider < app (the old refusal) was never true here", providerSaid < appSaid);
        assertFalse("yet the layouts are incompatible",
                cmp(V59, appSaid, TREE_V60, providerSaid).compatible);
    }

    /** The pre-v59 layout against v60, skewed at 16 as well. */
    @Test
    public void skew_preV59AppAgainstV60Provider_refused() {
        final RcsContractLayout.Verdict v = cmp(PRE_V59, 24, TREE_V60, 61);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("ORDINAL SKEW at transaction 16"));
    }

    // ---- The guard must still pass what is genuinely safe

    @Test
    public void identicalLayouts_compatible() {
        assertTrue(cmp(TREE_V60, 61, TREE_V60, 61).compatible);
        assertTrue(cmp(V59, 59, V59, 59).compatible);
    }

    /** A method appended rather than inserted stays compatible. */
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

    // ---- Fail closed

    /** A provider too old to answer the probe reports no layout, which is refused. */
    @Test
    public void providerDidNotAnswer_refused() {
        assertFalse(cmp(TREE_V60, 61, null, 59).compatible);
        assertFalse(cmp(TREE_V60, 61, new String[0], 59).compatible);
    }

    /** When the app cannot derive its own layout (R8 removed the fields), it refuses. */
    @Test
    public void ourOwnDerivationFailed_refused() {
        final RcsContractLayout.Verdict v = cmp(null, 61, TREE_V60, 61);
        assertFalse(v.compatible);
        assertTrue(v.reason, v.reason.contains("could not derive the app transaction layout"));
    }

    /** A layout not anchored at getContractVersion was read from the wrong place. */
    @Test
    public void wrongAnchor_refused() {
        final String[] shifted = TREE_V60.clone();
        shifted[0] = "somethingElse";
        assertFalse(cmp(shifted, 61, TREE_V60, 61).compatible);
        assertFalse(cmp(TREE_V60, 61, shifted, 61).compatible);
    }

    /** A provider with fewer methods than the app calls is refused. */
    @Test
    public void providerShorterThanUs_refused() {
        final String[] truncated = new String[TREE_V60.length - 1];
        System.arraycopy(TREE_V60, 0, truncated, 0, truncated.length);
        final RcsContractLayout.Verdict v = cmp(TREE_V60, 61, truncated, 60);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("FEWER methods"));
    }

    // ---- Digest

    @Test
    public void digest_isStableAndDistinguishesEveryRealLayout() {
        assertEquals(RcsContractLayout.digest(TREE_V60),
                RcsContractLayout.digest(TREE_V60.clone()));
        assertNotEquals(RcsContractLayout.digest(PRE_V59), RcsContractLayout.digest(V59));
        assertNotEquals(RcsContractLayout.digest(V59), RcsContractLayout.digest(TREE_V60));
        assertEquals(12, RcsContractLayout.digest(TREE_V60).length());
    }

    /** A reorder alone changes the digest. */
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

    // ---- The fixtures themselves

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

    // ---- IRcsProviderCallback: the provider calls, the app dispatches, so a skew misroutes
    // inbound work.

    /** IRcsProviderCallback with 22 methods, before onMlsNegativeDelivery was inserted. */
    private static final String[] CB_22 = {
            "onIncomingMessage", "onMessageStatus", "onImdnReceipt", "onImdnReceiptForPeer",
            "onRegistrationStateChanged", "onProvisioningStateChanged", "onOtpRequired",
            "onTyping", "onCarrierTosStateChanged", "onGroupEvent", "onEncryptedGroupSubject",
            "onEncryptedGroupIcon", "onMlsControlBundle", "onGroupTyping", "onGroupImdnReceipt",
            "onIncomingMedia", "onIncomingReaction", "onIncomingBotMessage", "onE2eeStateChanged",
            "onMlsControl", "onMlsCiphertext", "onMlsIdentityChanged"
    };

    /** IRcsProviderCallback with 23 methods. */
    private static final String[] CB_23 = {
            "onIncomingMessage", "onMessageStatus", "onImdnReceipt", "onImdnReceiptForPeer",
            "onRegistrationStateChanged", "onProvisioningStateChanged", "onOtpRequired",
            "onTyping", "onCarrierTosStateChanged", "onGroupEvent", "onEncryptedGroupSubject",
            "onEncryptedGroupIcon", "onMlsControlBundle", "onMlsNegativeDelivery", "onGroupTyping",
            "onGroupImdnReceipt", "onIncomingMedia", "onIncomingReaction", "onIncomingBotMessage",
            "onE2eeStateChanged", "onMlsControl", "onMlsCiphertext", "onMlsIdentityChanged"
    };

    private static RcsContractLayout.Verdict cmpCb(final String[] provider,
            final int providerVersion, final String[] app, final int appVersion) {
        return RcsContractLayout.compare("IRcsProviderCallback",
                RcsContractLayout.CALLBACK_ANCHOR_METHOD,
                "provider", provider, providerVersion, "app", app, appVersion);
    }

    /** onMlsNegativeDelivery was inserted at ordinal 14, where an older app has onGroupTyping. */
    @Test
    public void callback_insertionIsRealAndRefused() {
        assertEquals("onMlsNegativeDelivery", CB_23[13]);
        assertEquals("onGroupTyping", CB_22[13]);
        final RcsContractLayout.Verdict v = cmpCb(CB_23, 61, CB_22, 59);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("IRcsProviderCallback"));
        assertTrue(v.reason, v.reason.contains("ORDINAL SKEW at transaction 14"));
        // Named in the direction of the call.
        assertTrue(v.reason, v.reason.contains("the provider calls 'onMlsNegativeDelivery'"));
        assertTrue(v.reason, v.reason.contains("the app has 'onGroupTyping'"));
    }

    /** The callback interface has its own anchor. */
    @Test
    public void callback_hasItsOwnAnchor() {
        assertEquals(RcsContractLayout.CALLBACK_ANCHOR_METHOD, CB_23[0]);
        assertNotEquals(RcsContractLayout.ANCHOR_METHOD, RcsContractLayout.CALLBACK_ANCHOR_METHOD);
        assertTrue(cmpCb(CB_23, 61, CB_23, 61).compatible);
    }

    /** An app older than the provider lacks a method the provider calls. */
    @Test
    public void callback_appShorterThanProvider_refused() {
        final RcsContractLayout.Verdict v = cmpCb(CB_23, 61, CB_22, 59);
        assertFalse(v.reason, v.compatible);
    }

    /**
     * A callback appended after the last one pairs one way only: an older provider never dials
     * the newer app's extra method, but a newer provider would dial an ordinal the older app lacks.
     * A callback inserted before others is refused both ways; see RcsContractSignatureTest.
     */
    @Test
    public void callback_trailingAddition_pairsOnlyAnOlderProviderWithANewerApp() {
        final String[] longer = java.util.Arrays.copyOf(CB_23, CB_23.length + 1);
        longer[CB_23.length] = "onAppendedCallback";
        assertTrue(cmpCb(CB_23, 61, longer, 62).compatible);
        final RcsContractLayout.Verdict v = cmpCb(longer, 62, CB_23, 61);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("FEWER methods"));
    }

    /** The two interfaces have independent digests. */
    @Test
    public void callback_digestIsIndependentOfTheProviderInterface() {
        assertNotEquals(RcsContractLayout.digest(CB_23), RcsContractLayout.digest(TREE_V60));
        assertNotEquals(RcsContractLayout.digest(CB_22), RcsContractLayout.digest(CB_23));
    }

    /** {@code IRcsProvider}: the app calls, the provider dispatches. */
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

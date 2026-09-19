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
 * {@link RcsContractLayout}'s decision against the contract's transaction layout, in the order aidl
 * numbers it, and against an older build that lacks the last method and a later one that inserted a
 * method instead of appending it. Skewed pairs must be refused and safe ones accepted, so a verdict
 * stubbed either way fails. See docs/rcs/provider-contract.md.
 */
public class RcsContractLayoutTest {

    /** {@code IRcsProvider}, 34 methods, in declaration order. */
    private static final String[] CURRENT = {
            "getContractVersion", "getProviderCaps", "attach", "detach", "startForSub",
            "stopForSub", "getCapabilitiesForSub", "lookupRcsCapability", "sendMessage", "sendImdn",
            "sendTyping", "submitOtp", "submitTosConsent", "getTosState", "createGroup",
            "getGroupInfo", "getGroupIds", "addGroupUsers", "removeGroupUsers", "renameGroup",
            "sendGroupMessage", "sendLocation", "sendFile", "acceptIncomingFile", "sendGroupTyping",
            "sendReaction", "getBotBrand", "sendBotPostback", "setChatbotEnabled", "getE2eeInfo",
            "setE2eeEnabled", "canServeSub", "rejectIncomingFile", "ackInboundMessages"
    };

    /** An older build, 33 methods: {@link #CURRENT} before {@code ackInboundMessages}. */
    private static final String[] OLDER = java.util.Arrays.copyOf(CURRENT, CURRENT.length - 1);

    /** A later build, 35 methods: {@code insertedMethod} inserted at ordinal 9. */
    private static final String[] INSERTED = insert(CURRENT, 8, "insertedMethod");

    // ---- Skew

    /** The current app dials {@code sendImdn} at 10, where the later build has sendMessage. */
    @Test
    public void skew_appOrdinalLandsOnAnotherMethod() {
        final int appOrdinal = ordinalOf(CURRENT, "sendImdn");
        assertEquals("the app dialled transaction 10", 10, appOrdinal);
        assertEquals("which is what the provider answers there",
                "sendMessage", INSERTED[appOrdinal - RcsContractLayout.FIRST_ORDINAL]);
    }

    /** And the guard refuses that pairing, naming the ordinal and both method names. */
    @Test
    public void skew_currentAppAgainstInsertedProvider_refusedAndNamed() {
        final RcsContractLayout.Verdict v = cmp(CURRENT, 1, INSERTED, 2);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("ORDINAL SKEW at transaction 9"));
        assertTrue(v.reason, v.reason.contains("the app calls 'sendMessage'"));
        assertTrue(v.reason, v.reason.contains("'insertedMethod'"));
        assertTrue(v.reason, v.reason.contains("sendImdn -> sendMessage"));
    }

    /** Both versions appear, so a reader can tell which side to rebuild. */
    @Test
    public void skew_messageNamesBothSides() {
        final RcsContractLayout.Verdict v = cmp(CURRENT, 1, INSERTED, 2);
        assertTrue(v.reason, v.reason.contains("app v1"));
        assertTrue(v.reason, v.reason.contains("provider v2"));
        assertTrue(v.reason, v.reason.contains("34 methods"));
        assertTrue(v.reason, v.reason.contains("35 methods"));
    }

    /**
     * A {@code provider >= app} version check passes this skewed pair; only the layout catches it.
     */
    @Test
    public void aVersionCheckWouldPassThisSkew() {
        final int appSaid = 1;
        final int providerSaid = 2;
        assertFalse("provider < app is not true here", providerSaid < appSaid);
        assertFalse("yet the layouts are incompatible",
                cmp(CURRENT, appSaid, INSERTED, providerSaid).compatible);
    }

    /** The older layout against the inserted one, skewed at 9 as well. */
    @Test
    public void skew_olderAppAgainstInsertedProvider_refused() {
        final RcsContractLayout.Verdict v = cmp(OLDER, 1, INSERTED, 2);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("ORDINAL SKEW at transaction 9"));
    }

    // ---- The guard must still pass what is genuinely safe

    @Test
    public void identicalLayouts_compatible() {
        assertTrue(cmp(CURRENT, 1, CURRENT, 1).compatible);
        assertTrue(cmp(INSERTED, 2, INSERTED, 2).compatible);
    }

    /** A method appended rather than inserted stays compatible. */
    @Test
    public void trulyAdditive_compatible() {
        final String[] appended = new String[CURRENT.length + 1];
        System.arraycopy(CURRENT, 0, appended, 0, CURRENT.length);
        appended[CURRENT.length] = "someMethodAddedLater";
        final RcsContractLayout.Verdict v = cmp(CURRENT, 1, appended, 2);
        assertTrue(v.reason, v.compatible);
        final RcsContractLayout.Verdict older = cmp(OLDER, 1, CURRENT, 1);
        assertTrue(older.reason, older.compatible);
    }

    /** One method inserted anywhere renumbers the tail, and that is the failure. */
    @Test
    public void singleInsertion_anywhere_refused() {
        for (int at = 1; at < CURRENT.length; at++) {
            assertFalse("insertion at index " + at + " must be refused",
                    cmp(CURRENT, 1, insert(CURRENT, at, "insertedHere"), 2).compatible);
        }
    }

    // ---- Fail closed

    /** A provider too old to answer the probe reports no layout, which is refused. */
    @Test
    public void providerDidNotAnswer_refused() {
        assertFalse(cmp(CURRENT, 1, null, 1).compatible);
        assertFalse(cmp(CURRENT, 1, new String[0], 1).compatible);
    }

    /** When the app cannot derive its own layout (R8 removed the fields), it refuses. */
    @Test
    public void ourOwnDerivationFailed_refused() {
        final RcsContractLayout.Verdict v = cmp(null, 1, CURRENT, 1);
        assertFalse(v.compatible);
        assertTrue(v.reason, v.reason.contains("could not derive the app transaction layout"));
    }

    /** A layout not anchored at getContractVersion was read from the wrong place. */
    @Test
    public void wrongAnchor_refused() {
        final String[] shifted = CURRENT.clone();
        shifted[0] = "somethingElse";
        assertFalse(cmp(shifted, 1, CURRENT, 1).compatible);
        assertFalse(cmp(CURRENT, 1, shifted, 1).compatible);
    }

    /** A provider with fewer methods than the app calls is refused. */
    @Test
    public void providerShorterThanUs_refused() {
        final RcsContractLayout.Verdict v = cmp(CURRENT, 1, OLDER, 1);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("FEWER methods"));
    }

    // ---- Digest

    @Test
    public void digest_isStableAndDistinguishesEveryLayout() {
        assertEquals(RcsContractLayout.digest(CURRENT), RcsContractLayout.digest(CURRENT.clone()));
        assertNotEquals(RcsContractLayout.digest(OLDER), RcsContractLayout.digest(CURRENT));
        assertNotEquals(RcsContractLayout.digest(CURRENT), RcsContractLayout.digest(INSERTED));
        assertEquals(12, RcsContractLayout.digest(CURRENT).length());
    }

    /** A reorder alone changes the digest. */
    @Test
    public void digest_coversOrderNotJustMembership() {
        final String[] swapped = CURRENT.clone();
        final String t = swapped[20];
        swapped[20] = swapped[21];
        swapped[21] = t;
        assertNotEquals(RcsContractLayout.digest(CURRENT), RcsContractLayout.digest(swapped));
    }

    /** An underivable layout must not digest to something a real layout could also produce. */
    @Test
    public void digest_ofNothingIsNotADigest() {
        assertEquals("none", RcsContractLayout.digest(null));
        assertEquals("none", RcsContractLayout.digest(new String[0]));
    }

    // ---- The fixtures themselves

    /** Each layout is anchored where aidl anchors it, and names no method twice. */
    @Test
    public void fixtures_areWellFormed() {
        for (final String[] layout : new String[][] { OLDER, CURRENT, INSERTED, CB_CURRENT,
                CB_INSERTED }) {
            assertTrue(layout[0].equals(RcsContractLayout.ANCHOR_METHOD)
                    || layout[0].equals(RcsContractLayout.CALLBACK_ANCHOR_METHOD));
            final java.util.Set<String> seen = new java.util.HashSet<>();
            for (final String m : layout) {
                assertTrue("duplicate method " + m, seen.add(m));
            }
        }
        assertEquals(RcsContractLayout.ANCHOR_METHOD, CURRENT[0]);
        assertEquals(33, OLDER.length);
        assertEquals(34, CURRENT.length);
        assertEquals(35, INSERTED.length);
        assertEquals(16, CB_CURRENT.length);
        assertEquals(17, CB_INSERTED.length);
    }

    // ---- IRcsProviderCallback: the provider calls, the app dispatches, so a skew misroutes
    // inbound work.

    /** {@code IRcsProviderCallback}, 16 methods, in declaration order. */
    private static final String[] CB_CURRENT = {
            "onIncomingMessage", "onMessageStatus", "onImdnReceipt", "onImdnReceiptForPeer",
            "onRegistrationStateChanged", "onProvisioningStateChanged", "onOtpRequired",
            "onTyping", "onCarrierTosStateChanged", "onGroupEvent", "onGroupTyping",
            "onGroupImdnReceipt", "onIncomingMedia", "onIncomingReaction", "onIncomingBotMessage",
            "onE2eeStateChanged"
    };

    /** A later build, 17 methods: {@code onInsertedCallback} inserted at ordinal 11. */
    private static final String[] CB_INSERTED = insert(CB_CURRENT, 10, "onInsertedCallback");

    private static RcsContractLayout.Verdict cmpCb(final String[] provider,
            final int providerVersion, final String[] app, final int appVersion) {
        return RcsContractLayout.compare("IRcsProviderCallback",
                RcsContractLayout.CALLBACK_ANCHOR_METHOD,
                "provider", provider, providerVersion, "app", app, appVersion);
    }

    /** onInsertedCallback sits at ordinal 11, where the current app has onGroupTyping. */
    @Test
    public void callback_insertionIsRefused() {
        assertEquals("onInsertedCallback", CB_INSERTED[10]);
        assertEquals("onGroupTyping", CB_CURRENT[10]);
        final RcsContractLayout.Verdict v = cmpCb(CB_INSERTED, 2, CB_CURRENT, 1);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("IRcsProviderCallback"));
        assertTrue(v.reason, v.reason.contains("ORDINAL SKEW at transaction 11"));
        // Named in the direction of the call.
        assertTrue(v.reason, v.reason.contains("the provider calls 'onInsertedCallback'"));
        assertTrue(v.reason, v.reason.contains("the app has 'onGroupTyping'"));
    }

    /** The callback interface has its own anchor. */
    @Test
    public void callback_hasItsOwnAnchor() {
        assertEquals(RcsContractLayout.CALLBACK_ANCHOR_METHOD, CB_CURRENT[0]);
        assertNotEquals(RcsContractLayout.ANCHOR_METHOD, RcsContractLayout.CALLBACK_ANCHOR_METHOD);
        assertTrue(cmpCb(CB_CURRENT, 1, CB_CURRENT, 1).compatible);
    }

    /** An app older than the provider lacks a method the provider calls. */
    @Test
    public void callback_appShorterThanProvider_refused() {
        final String[] appended = java.util.Arrays.copyOf(CB_CURRENT, CB_CURRENT.length + 1);
        appended[CB_CURRENT.length] = "onAppendedCallback";
        final RcsContractLayout.Verdict v = cmpCb(appended, 2, CB_CURRENT, 1);
        assertFalse(v.reason, v.compatible);
        assertTrue(v.reason, v.reason.contains("FEWER methods"));
    }

    /** The two interfaces have independent digests. */
    @Test
    public void callback_digestIsIndependentOfTheProviderInterface() {
        assertNotEquals(RcsContractLayout.digest(CB_CURRENT), RcsContractLayout.digest(CURRENT));
        assertNotEquals(RcsContractLayout.digest(CB_CURRENT),
                RcsContractLayout.digest(CB_INSERTED));
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

    /** {@code layout} with {@code name} inserted at index {@code at}. */
    private static String[] insert(final String[] layout, final int at, final String name) {
        final String[] out = new String[layout.length + 1];
        System.arraycopy(layout, 0, out, 0, at);
        out[at] = name;
        System.arraycopy(layout, at, out, at + 1, layout.length - at);
        return out;
    }
}

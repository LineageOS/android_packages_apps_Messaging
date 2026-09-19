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

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * RCC.16 §7.11.12 and §8.3.1: the continuity token construction and its policy. The policy's
 * failure mode is downgrading a conversation out of encryption. See docs/mls/downgrade.md.
 */
public class MlsContinuityTokenTest {

    private static byte[] ascii(final String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    /** §8.3.1.1: 256 bits from a cryptographically secure generator. */
    @Test
    public void mint_is256BitsAndNotRepeating() {
        final byte[] a = MlsContinuityToken.mint();
        final byte[] b = MlsContinuityToken.mint();
        assertEquals(32, a.length);
        assertEquals(32, b.length);
        assertFalse("two mints must not collide", Arrays.equals(a, b));
        // An all-zero token is what an uninitialised buffer looks like.
        assertFalse(Arrays.equals(a, new byte[32]));
    }

    /**
     * The RefHash label carries the {@code "MLS 1.0 "} prefix (RFC 9420 §5.2) while Annex C.1's
     * commitment labels are bare; the expected digest is computed independently here.
     */
    @Test
    public void refHash_prefixesTheLabelAndFramesBothFieldsAsOpaqueV() throws Exception {
        final byte[] value = ascii("value-bytes");
        final byte[] got = MlsContinuityToken.refHash("Some Label", value);

        final byte[] label = ascii("MLS 1.0 Some Label");
        final java.io.ByteArrayOutputStream expect = new java.io.ByteArrayOutputStream();
        expect.write(MlsAppMessage.mlsVarint(label.length));
        expect.write(label);
        expect.write(MlsAppMessage.mlsVarint(value.length));
        expect.write(value);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expect.toByteArray()), got);

        // It differs from the bare-label Annex C.1 construction.
        assertFalse("RefHash must not equal the bare-label Annex C.1 commitment",
                Arrays.equals(got, RccCommitment.commit("Some Label", value)));
    }

    /** §7.11.12.2 — TokenCommitment is two opaque<V> fields, in order, then RefHash'd. */
    @Test
    public void tokenCommitmentStruct_isTwoOpaqueVFieldsInOrder() {
        final byte[] token = ascii("tok");
        final byte[] auth = ascii("auth!");
        final byte[] s = MlsContinuityToken.tokenCommitmentStruct(token, auth);
        assertArrayEquals(new byte[] {3, 't', 'o', 'k', 5, 'a', 'u', 't', 'h', '!'}, s);

        assertArrayEquals(MlsContinuityToken.refHash(MlsContinuityToken.COMMITMENT_LABEL, s),
                MlsContinuityToken.commitment(token, auth));

        // Order matters: swapping the fields changes the commitment.
        assertFalse(Arrays.equals(MlsContinuityToken.commitment(token, auth),
                MlsContinuityToken.commitment(auth, token)));
    }

    @Test
    public void commitment_changesWithTheEpochAuthenticator() {
        final byte[] token = MlsContinuityToken.mint();
        final byte[] c1 = MlsContinuityToken.commitment(token, ascii("epoch-1"));
        final byte[] c2 = MlsContinuityToken.commitment(token, ascii("epoch-2"));
        assertEquals(32, c1.length);
        assertFalse("the commitment binds the epoch, not just the token", Arrays.equals(c1, c2));
        assertNull(MlsContinuityToken.commitment(null, ascii("e")));
        assertNull(MlsContinuityToken.commitment(token, null));
    }

    @Test
    public void commitmentMatches_rejectsEmptyAndMismatchedLengths() {
        final byte[] a = MlsContinuityToken.mint();
        assertTrue(MlsContinuityToken.commitmentMatches(a, a.clone()));
        assertFalse(MlsContinuityToken.commitmentMatches(a, new byte[31]));
        assertFalse(MlsContinuityToken.commitmentMatches(null, a));
        assertFalse("empty must never compare equal — that would make 'no token' match 'no token'",
                MlsContinuityToken.commitmentMatches(new byte[0], new byte[0]));
    }

    private static final List<String> ABC = Arrays.asList("+1", "+2", "+3");
    private static final List<String> AB = Arrays.asList("+1", "+2");

    /**
     * A token mismatch on its own does not act: §8.3.1.2 is
     * {@code (mismatch AND fetched-has-strangers) OR rcs-has-strangers}, so an ordinary re-mint or
     * a peer that lost its token does not downgrade.
     */
    @Test
    public void aBareTokenMismatchDoesNotTriggerTheDowngradeArm() {
        final byte[] ours = MlsContinuityToken.mint();
        final byte[] theirs = MlsContinuityToken.mint();
        // Mismatch, but the fetched group holds nobody we do not already hold, and RCS agrees.
        assertEquals(MlsContinuityPolicy.Action.MINT_TOKEN,
                MlsContinuityPolicy.evaluate(ours, theirs, AB, AB, ABC));
    }

    @Test
    public void mismatchPlusUnknownFetchedMembersIsTheDowngradeArm() {
        final byte[] ours = MlsContinuityToken.mint();
        final byte[] theirs = MlsContinuityToken.mint();
        // fetched has +3, which we do not hold locally, so the AND arm is satisfied.
        assertEquals(MlsContinuityPolicy.Action.MINT_TOKEN_AND_MAY_DOWNGRADE,
                MlsContinuityPolicy.evaluate(ours, theirs, AB, ABC, AB));
    }

    @Test
    public void rcsParticipantsNotInTheFetchedGroupTriggerItOnTheirOwn() {
        final byte[] ours = MlsContinuityToken.mint();
        // Tokens agree, but the RCS chat names +3 and the fetched MLS group does not: the OR term
        // fires without any token disagreement.
        assertEquals(MlsContinuityPolicy.Action.MINT_TOKEN_AND_MAY_DOWNGRADE,
                MlsContinuityPolicy.evaluate(ours, ours, ABC, AB, AB));
    }

    @Test
    public void matchingTokenAndConsistentMembershipCarriesTheToken() {
        final byte[] ours = MlsContinuityToken.mint();
        assertEquals(MlsContinuityPolicy.Action.CARRY_TOKEN,
                MlsContinuityPolicy.evaluate(ours, ours, AB, AB, AB));
    }

    /** §8.3.1.3: absence is recoverable when membership is consistent, unlike a substitution. */
    @Test
    public void noLocalTokenRequestsItWhenMembershipIsConsistent() {
        assertEquals(MlsContinuityPolicy.Action.REQUEST_TOKEN,
                MlsContinuityPolicy.evaluate(null, MlsContinuityToken.mint(), AB, ABC, AB));
        assertEquals(MlsContinuityPolicy.Action.REQUEST_TOKEN,
                MlsContinuityPolicy.evaluate(new byte[0], null, AB, AB, AB));
        // ...but mint rather than ask when the RCS side names people the fetched group lacks.
        assertEquals(MlsContinuityPolicy.Action.MINT_TOKEN,
                MlsContinuityPolicy.evaluate(null, null, ABC, AB, AB));
    }

    /** A fetched GroupInfo carrying no token does not read as agreement with the one we hold. */
    @Test
    public void anAbsentFetchedTokenIsAMismatchNotAMatch() {
        final byte[] ours = MlsContinuityToken.mint();
        assertEquals(MlsContinuityPolicy.Action.MINT_TOKEN,
                MlsContinuityPolicy.evaluate(ours, null, AB, AB, AB));
    }

    /**
     * The downgrade is a MAY and is declined until a server is seen doing continuity: if no peer
     * mints, every fetched GroupInfo mismatches and acting on it would downgrade every group.
     */
    @Test
    public void theDowngradeIsWithheldUntilTheServerIsObservedDoingContinuity() {
        final MlsContinuityPolicy.Action act =
                MlsContinuityPolicy.Action.MINT_TOKEN_AND_MAY_DOWNGRADE;
        assertFalse("no observation yet -> never downgrade",
                MlsContinuityPolicy.downgradeIsPermitted(act, false));
        assertTrue(MlsContinuityPolicy.downgradeIsPermitted(act, true));
        // The non-downgrade actions never permit it.
        assertFalse(MlsContinuityPolicy.downgradeIsPermitted(
                MlsContinuityPolicy.Action.CARRY_TOKEN, true));
        assertFalse(MlsContinuityPolicy.downgradeIsPermitted(
                MlsContinuityPolicy.Action.MINT_TOKEN, true));
    }

    /** Reason 6 names "we never had one"; reason 5 names "ours disagreed". */
    @Test
    public void theDowngradeReasonDistinguishesAbsenceFromDisagreement() {
        assertEquals(6, MlsContinuityPolicy.downgradeReason(null));
        assertEquals(6, MlsContinuityPolicy.downgradeReason(new byte[0]));
        assertEquals(5, MlsContinuityPolicy.downgradeReason(MlsContinuityToken.mint()));
    }

    /**
     * The receiver's bound is greater than {@link MlsContinuityToken#TOKEN_BYTES}, so an unexpected
     * framing is kept rather than dropped (a token cannot be requested again), and small, because
     * the token is written into a record that is rewritten whole. Asserted as a relationship, not a
     * literal.
     */
    @Test
    public void theStoredTokenBoundAdmitsARealTokenAndStaysSmall() {
        assertTrue("the bound must admit a spec-length token, or we refuse every real one",
                MlsContinuityToken.MAX_STORED_TOKEN_BYTES >= MlsContinuityToken.TOKEN_BYTES);
        assertTrue("it must leave room for a framing we have not seen — a token dropped because "
                        + "its length surprised us is the failure this bound exists to remove",
                MlsContinuityToken.MAX_STORED_TOKEN_BYTES > MlsContinuityToken.TOKEN_BYTES);
        assertTrue("...and it must stay SMALL: the record is rewritten whole, so this is a "
                        + "per-write multiplier on every later write to that conversation",
                MlsContinuityToken.MAX_STORED_TOKEN_BYTES <= 16 * MlsContinuityToken.TOKEN_BYTES);
        // The 33-byte wire form (0x20 || 32) fits.
        assertTrue(MlsContinuityToken.MAX_STORED_TOKEN_BYTES > MlsContinuityToken.TOKEN_BYTES + 1);
    }
}

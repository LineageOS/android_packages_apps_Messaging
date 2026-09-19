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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * RCC.16 §7.11.12 / §8.3.1 — the continuity token construction and its policy.
 *
 * <p>Host-tested because the construction has no free parameters (so it can be checked against the
 * spec text alone, with no peer to ask) and because the policy's failure mode is DOWNGRADING a
 * conversation out of encryption, which is not something to discover on a phone.
 */
public class MlsContinuityTokenTest {

    private static byte[] ascii(final String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    /** §8.3.1.1 — "CSPRNG, 256 bits". */
    @Test
    public void mint_is256BitsAndNotRepeating() {
        final byte[] a = MlsContinuityToken.mint();
        final byte[] b = MlsContinuityToken.mint();
        assertEquals(32, a.length);
        assertEquals(32, b.length);
        assertFalse("two mints must not collide", Arrays.equals(a, b));
        // An all-zero token would be what an uninitialised buffer looks like.
        assertFalse(Arrays.equals(a, new byte[32]));
    }

    /**
     * The RefHash label carries the {@code "MLS 1.0 "} prefix (RFC 9420 §5.2) while Annex C.1's
     * commitment labels are BARE. Two hash constructions that differ only by an eight-byte prefix is
     * exactly the detail that yields a commitment verifying against nothing, so it is pinned by
     * computing the expected digest independently here rather than by calling the same helper.
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

        // And it is genuinely different from the bare-label Annex C.1 construction.
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

        // Order matters — swapping the fields must not produce the same commitment.
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

    // ---- §8.3.1.2 / §8.3.1.3 policy ----------------------------------------------------------

    private static final List<String> ABC = Arrays.asList("+1", "+2", "+3");
    private static final List<String> AB = Arrays.asList("+1", "+2");

    /**
     * THE RULE THAT IS EASIEST TO GET WRONG: a token mismatch <em>on its own</em> does not act.
     *
     * <p>§8.3.1.2 is {@code (mismatch AND fetched-has-strangers) OR rcs-has-strangers}. Implementing
     * the mismatch alone would downgrade a conversation on the benign case — an ordinary re-mint, or
     * a peer that lost its token — which is precisely what the standing "do not mint one" position
     * was protecting against.
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
        // fetched has +3, which we do not hold locally -> the AND arm is satisfied.
        assertEquals(MlsContinuityPolicy.Action.MINT_TOKEN_AND_MAY_DOWNGRADE,
                MlsContinuityPolicy.evaluate(ours, theirs, AB, ABC, AB));
    }

    @Test
    public void rcsParticipantsNotInTheFetchedGroupTriggerItOnTheirOwn() {
        final byte[] ours = MlsContinuityToken.mint();
        // Tokens AGREE, but the RCS chat names +3 and the fetched MLS group does not have them.
        // That is the OR term: it fires without any token disagreement at all.
        assertEquals(MlsContinuityPolicy.Action.MINT_TOKEN_AND_MAY_DOWNGRADE,
                MlsContinuityPolicy.evaluate(ours, ours, ABC, AB, AB));
    }

    @Test
    public void matchingTokenAndConsistentMembershipCarriesTheToken() {
        final byte[] ours = MlsContinuityToken.mint();
        assertEquals(MlsContinuityPolicy.Action.CARRY_TOKEN,
                MlsContinuityPolicy.evaluate(ours, ours, AB, AB, AB));
    }

    /** §8.3.1.3 — absence is RECOVERABLE when membership is consistent, unlike a substitution. */
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

    /** A fetched GroupInfo carrying NO token must not read as agreement with the one we hold. */
    @Test
    public void anAbsentFetchedTokenIsAMismatchNotAMatch() {
        final byte[] ours = MlsContinuityToken.mint();
        assertEquals(MlsContinuityPolicy.Action.MINT_TOKEN,
                MlsContinuityPolicy.evaluate(ours, null, AB, AB, AB));
    }

    /**
     * The downgrade is a MAY, and we decline it until a server has actually been seen doing
     * continuity. If no peer ever mints, every fetched GroupInfo mismatches — a client acting on MAY
     * would downgrade every group it has, and the user could not tell that from encryption breaking.
     */
    @Test
    public void theDowngradeIsWithheldUntilTheServerIsObservedDoingContinuity() {
        final MlsContinuityPolicy.Action act = MlsContinuityPolicy.Action.MINT_TOKEN_AND_MAY_DOWNGRADE;
        assertFalse("no observation yet -> never downgrade",
                MlsContinuityPolicy.downgradeIsPermitted(act, false));
        assertTrue(MlsContinuityPolicy.downgradeIsPermitted(act, true));
        // And the non-downgrade actions never permit it, however the measurement came out.
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
     * The bound a RECEIVER applies, and its relationship to the spec's length.
     *
     * <p>Both halves matter and they pull opposite ways. It must be GREATER than
     * {@link MlsContinuityToken#TOKEN_BYTES}, because refusing an unexpected framing is the wrong
     * default for a value we can never ask for again — a token dropped because its length surprised
     * us is the exact failure this removes. And it must be SMALL, because a received token is
     * written into a record that is rewritten whole, so an attacker-chosen length amplifies every
     * later write to that conversation rather than only its own.
     *
     * <p>Asserted as a RELATIONSHIP rather than as the literal 256: the literal is one edit away
     * from being changed for a reason nobody records, and what must not change is that the bound
     * admits every real token and stays within one record's worth of slack.
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
        // The 33-byte wire form Google Messages was measured sending (0x20 || 32) fits with room to spare.
        assertTrue(MlsContinuityToken.MAX_STORED_TOKEN_BYTES > MlsContinuityToken.TOKEN_BYTES + 1);
    }
}

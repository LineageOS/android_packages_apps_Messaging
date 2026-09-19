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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * RCC.16 <b>§7.11.12</b> + <b>§8.3.1.1–.3</b> — the continuity token and its commitment.
 *
 * <p>The token is a secret associated with an MLS group that is <b>continuous across Epochs AND
 * Eras</b>. That is the whole point of it: an Era advance builds a brand-new MLS group, so nothing
 * in the MLS state links a successor era to its predecessor. The token is the link, and the
 * commitment is what lets a peer check the link without the server learning the token.
 *
 * <h2>Why this is not "v4.0-only" work</h2>
 *
 * The code points 0xF010/0xF011 do not appear in RCC.16 v3.0 at all. But shipping Google Messages
 * builds — v3.0-era clients, and the ones we interoperate with — carry code for both. So
 * Google Messages implements continuity while speaking v3.0, and the reading was that era chaining
 * runs through the token — which is why "the server accepts an era advance and never moves the era"
 * points here.
 *
 * <h2>CORRECTED — the {@code 0xF011} "GroupInfo builder" was not a builder</h2>
 *
 * This paragraph used to cite a {@code 0xF011} <i>builder</i> as the evidence
 * that Google Messages does continuity. A sweep of the whole binary for the immediate
 * {@code 0xF011} found four sites, every one of which takes {@code (extension_list, type)} and
 * returns a <b>bool</b>. <b>A builder
 * would have to pass a VALUE. None does.</b> No flag gates it either: the only continuity flags are
 * {@code bugle.enable_zinnia_populate_continuity_token} and its telemetry sibling, both
 * gone from newer builds, neither a commitment flag. The construction code is ABSENT, not dormant.
 *
 * <p>So Google Messages implements §7.11.12.2's <b>receiver half and not its sender half</b>: it can
 * verify a peer's commitment and never emits its own. That is what the four ABSENT server GroupInfos
 * we have measured predict, and those negatives stand.
 *
 * <h2>THE TWO CODE POINTS DO NOT RESOLVE THE SAME WAY — do not read "continuity" as one decision</h2>
 *
 * <ul>
 *   <li><b>{@link MlsContinuityCodePoints#TOKEN} (0xF010) — Google Messages DOES mint one.</b>
 *       A Welcome a Google Messages peer BUILT FOR US carried {@code 0xF010=33B} in
 *       its decrypted GroupInfo — {@code 0x20 ‖ 32}, a well-formed 256-bit token. That overturned
 *       the flat "do not mint one" prohibition this class used to carry.</li>
 *   <li><b>{@link MlsContinuityCodePoints#COMMITMENT} (0xF011) — nobody has been seen building
 *       one</b>, by three independent routes: six-plus measured GroupInfos with BOTH extension lists
 *       searched, no write site in the binary, and no gating flag.</li>
 * </ul>
 *
 * <p>Whether WE emit either is a separate decision, and it is <b>HOLD</b>. Nothing in this
 * class emits anything; it computes.
 *
 * <h2>Where a token we RECEIVE goes</h2>
 *
 * <p>Receiving is not gated and never was. A token arrives either in the encrypted GroupInfo of a
 * Welcome (§7.11.12.1, the only route ever measured) or in a §10.5.4 {@code GroupMetadataKeys}, and
 * both now land in §4.8 row 13 of {@code MlsConversationRecord}. Until recently neither had a
 * consumer: the Welcome-borne token reached a log-only instrument and the §10.5.4 token was written
 * to a preference nothing read. {@link MlsContinuityPolicy}'s §8.3.1.2 "we HAVE a token" arm was
 * therefore unreachable no matter what a peer sent us.
 *
 * <h2>The construction, which has no free parameters</h2>
 *
 * <pre>
 *   struct { opaque continuity_token&lt;V&gt;; opaque epoch_authenticator&lt;V&gt;; } TokenCommitment
 *   token_commitment = RefHash("Continuity Token GroupInfo Commitment", TokenCommitment)
 * </pre>
 *
 * <p>{@code RefHash} is RFC 9420 §5.2:
 *
 * <pre>
 *   RefHash(label, value) = Hash(RefHashInput)
 *   struct { opaque label&lt;V&gt;; opaque value&lt;V&gt;; } RefHashInput
 * </pre>
 *
 * <p>and §5.2 prefixes the label with the literal {@code "MLS 1.0 "}. <b>That prefix is the one
 * thing here that differs from {@link RccCommitment}</b>, whose Annex C.1 labels are BARE (no
 * prefix — byte-confirmed against Google Messages). Two hash constructions that look identical and differ
 * by an eight-byte prefix is exactly the kind of detail that produces a commitment verifying
 * against nothing, so they are separate classes rather than one parameterised helper.
 *
 * <p>Hash is SHA-256 (the P256_AES128 suite's hash).
 *
 * <h2>What this class deliberately does NOT decide</h2>
 *
 * Whether to EMIT a token or a commitment. That is a wire question, it is version-gated, and the
 * standing position is <b>HOLD</b> — because v4.0 makes this a VALIDATION
 * mechanism whose failure mode is downgrading the conversation (§8.3.1.2,
 * {@code END_MLS_REASON_CONTINUITY_TOKEN_MISMATCH}). For the COMMITMENT the reason is now measured
 * rather than cautious: emitting {@code 0xF011} is the act that ARMS §10.5.1 on every peer, and the
 * verifier is compiled into Google Messages with one of its call sites in {@code advance_era.rs} — §8.3.1.2's
 * exact trigger, and the path that ends in the §11.2 downgrade. See {@link MlsContinuityPolicy}.
 *
 * <p><b>FALSIFIER — what would reopen the commitment question:</b> an implementation observed
 * BUILDING a {@code 0xF011}, which in practice means one present in a GroupInfo we read. Probe:
 * {@code --ez groupexts true}, grep {@code CONTINUITY}. Still unfalsified: the four-group
 * measurement of 2026-08-06, plus a group we created and a 1:1 at era 8 read on
 * 2026-09-11 — all ABSENT, with the reader searching BOTH extension lists each time.
 *
 * <p><b>Note what that makes of {@link MlsContinuityPolicy#downgradeIsPermitted}</b>, which requires
 * {@code serverDoesContinuity} — defined as "a server GroupInfo has been seen carrying 0xF011". On
 * this evidence it will never become true against a Google Messages peer, so the §8.3.1.2 downgrade is not
 * merely unexercised but unreachable-true, and no test of it has ever been a live test.
 *
 * <p><b>The falsifier is 0xF011 and NOT 0xF010</b>, which is the part worth getting right: the token
 * is <b>Welcome-only</b>, so its absence from a GroupInfo proves nothing and can never overturn
 * anything. Looking for the wrong one of the two would produce a negative that feels like evidence
 * and is not: reading a Welcome is the only way to see 0xF010 at all.
 *
 * <p><b>ATTRIBUTE THE ABSENCE TO THE RIGHT ACTOR.</b> Continuity is a <i>client</i> feature —
 * clients mint the token and build the commitment, and the server only carries what it was given. So
 * an absent 0xF011 means "no client that built a GroupInfo in this group is doing continuity", NOT
 * "the server does not support it". The latter is a claim the measurement cannot make, and it sends
 * the next reader to the wrong place to change the outcome.
 */
public final class MlsContinuityToken {

    private MlsContinuityToken() { }

    /** §8.3.1.1 — "CSPRNG, 256 bits". */
    public static final int TOKEN_BYTES = 32;

    /**
     * The largest token a receiver will STORE.
     *
     * <h2>Why a bound exists at all</h2>
     *
     * <p>A received token is written into §4.8 row 13 of {@code MlsConversationRecord}, and that
     * record is rewritten WHOLE on every update. So the length of one inbound extension is not a
     * one-off cost: an attacker-chosen 0xF010 would amplify <b>every later write</b> to that
     * conversation, not only the write that stored it. Every other unbounded field in §4.8 is capped
     * for the same reason.
     *
     * <h2>Why it is not simply {@link #TOKEN_BYTES}</h2>
     *
     * <p>Because refusing an unexpected length is the wrong default for a value we can never ask for
     * again. §8.3.1.1 fixes 32 and everything measured is 32, but "decode tolerantly, encode
     * strictly" applies here with real force: a token dropped because its framing surprised us is
     * the exact failure this store exists to remove. 8x leaves room for every plausible framing of
     * a real token and for nothing that could be mistaken for one.
     *
     * <p><b>This number is OURS, not the spec's.</b> That is what makes it policy rather than a wire
     * constant, and it is why it lives here — beside the length the spec does fix — rather than in
     * the transport that enforces it.
     */
    public static final int MAX_STORED_TOKEN_BYTES = 8 * TOKEN_BYTES;

    /**
     * The RefHash label, verbatim from §7.11.12.2. <b>Without</b> the {@code "MLS 1.0 "} prefix —
     * {@link #refHash} adds it, because RFC 9420 §5.2 owns that part of the construction.
     */
    public static final String COMMITMENT_LABEL = "Continuity Token GroupInfo Commitment";

    /** RFC 9420 §5.2 — the prefix every RefHash label carries. */
    private static final String MLS_LABEL_PREFIX = "MLS 1.0 ";

    /**
     * Mint a fresh token — §8.3.1.1.
     *
     * <p>{@link SecureRandom} with no seed argument, so it draws from the platform CSPRNG. Seeding
     * it would REDUCE entropy on Android, which is the opposite of what a "CSPRNG, 256 bits"
     * requirement is asking for.
     */
    public static byte[] mint() {
        final byte[] t = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(t);
        return t;
    }

    /**
     * The §7.11.12.2 {@code token_commitment} over {@code (token, epochAuthenticator)}.
     *
     * @return 32 bytes, or {@code null} if either input is missing or the digest is unavailable
     */
    public static byte[] commitment(final byte[] token, final byte[] epochAuthenticator) {
        if (token == null || epochAuthenticator == null) return null;
        final byte[] inner = tokenCommitmentStruct(token, epochAuthenticator);
        if (inner == null) return null;
        return refHash(COMMITMENT_LABEL, inner);
    }

    /**
     * The serialised {@code TokenCommitment} struct — two {@code opaque<V>} fields, in order.
     *
     * <p>Exposed because §7.11.12.2 describes the extension_data as "a serialised
     * {@code token_commitment}" in one place and as the hash in another; keeping the struct
     * reachable means a reader can check which one a capture actually carries without re-deriving
     * the encoding.
     */
    public static byte[] tokenCommitmentStruct(final byte[] token, final byte[] epochAuthenticator) {
        if (token == null || epochAuthenticator == null) return null;
        try {
            final ByteArrayOutputStream out =
                    new ByteArrayOutputStream(token.length + epochAuthenticator.length + 8);
            out.write(MlsAppMessage.mlsVarint(token.length));
            out.write(token);
            out.write(MlsAppMessage.mlsVarint(epochAuthenticator.length));
            out.write(epochAuthenticator);
            return out.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * RFC 9420 §5.2 {@code RefHash(label, value)}, with the mandatory {@code "MLS 1.0 "} prefix.
     *
     * @return 32 bytes, or {@code null} if the inputs are unusable or SHA-256 is unavailable
     */
    public static byte[] refHash(final String label, final byte[] value) {
        if (label == null || value == null) return null;
        try {
            final byte[] l = (MLS_LABEL_PREFIX + label).getBytes(StandardCharsets.US_ASCII);
            final ByteArrayOutputStream out =
                    new ByteArrayOutputStream(l.length + value.length + 8);
            out.write(MlsAppMessage.mlsVarint(l.length));
            out.write(l);
            out.write(MlsAppMessage.mlsVarint(value.length));
            out.write(value);
            return MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Constant-time comparison of a received commitment against one recomputed locally.
     *
     * <p>Constant-time because a commitment is a secret-derived value and this comparison runs on
     * every GroupInfo we validate; {@link Arrays#equals} would leak the matching-prefix length. The
     * practical risk is low, but the cost of getting it right is one method.
     */
    public static boolean commitmentMatches(final byte[] a, final byte[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
        return diff == 0;
    }
}

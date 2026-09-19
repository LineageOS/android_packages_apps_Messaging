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

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>On the carrier leg, the id bound into the AAD must be the id on the envelope</b>.
 *
 * <h2>The rule, and what breaking it costs</h2>
 *
 * <p>RCC.16 §7.5.3.1: a Google Messages peer cross-checks the {@code AuthenticatedData} message id against
 * the envelope's and drops a mismatch. {@code MlsProviderTransport} states this at its own seal
 * site and satisfies it by surfacing the sealed id in a header. The carrier leg broke it in both
 * directions at once: it sealed against a synthesised {@code "mls-<peer>-<era>"} while
 * {@code CarrierRcsTransport.sendMls} put the app's row UUID on the wire.
 *
 * <h2>Why it survived, which is NOT the reason that was assumed</h2>
 *
 * <p>The natural hypothesis — and the one this was first investigated under — is that our own receive
 * side re-synthesises the same string, so two of our clients are self-consistent and the defect is
 * invisible A&lt;-&gt;B. <b>That is not what is happening.</b> {@code MlsCarrierTransport.onInboundCpim}
 * calls the NO-AAD {@code process()} entry, and under RFC 9420 the AAD travels inside the
 * {@code PrivateMessage} and the receiver READS it rather than recomputing it — decryption cannot
 * fail on a mismatch here however wrong the id is.
 *
 * <p><b>The binding check is simply absent on this leg.</b> The host-side check
 * ({@code MlsAppMessage.aadMessageIdMatches}) is called only by the provider. The engine-side one
 * is armed by {@code MlsSession.setRequestMessageId}, which has <b>no production caller anywhere
 * in the tree</b> — and the Rust side fail-opens on an unset id. Nothing was agreeing; nothing was
 * looking. That distinction matters for severity: a self-consistent pair is latent, an unchecked
 * mismatch is live the moment a Google Messages peer is on the other end.
 *
 * <h2>What is pinned</h2>
 *
 * <ul>
 *   <li><b>The invariant itself</b> — {@code sendMls} passes the SAME identifier to the seal and
 *       to the envelope. This is the assertion worth having; the other two support it.</li>
 *   <li>The seal accepts a caller id at all (the 3-arg form), so a revert to the no-id overload is
 *       caught even if the envelope call is untouched.</li>
 *   <li>The synthesised FALLBACK carries an epoch, not era alone. Era alone is not an identifier:
 *       it was a per-(peer, era) constant shared by every message sealed in that era. The provider
 *       leg paid for this twice on device — on 2026-08-08 two of three messages in a controlled
 *       experiment vanished with no error anywhere, because the transport deduped reissued ids.
 *       Production never takes this arm, which is exactly why nobody would notice it rotting.</li>
 * </ul>
 *
 * <p><b>Identifiers are DERIVED from the source, never hardcoded.</b> A sibling guard in this
 * package had needles that could not match — the mutations it was written to catch passed clean,
 * and only running them found it. Everything below reads the names out of the call it is checking.
 */
public class MlsCarrierAadIdGuardTest {

    private static final String CARRIER_TRANSPORT =
            "src/com/android/messaging/rcs/e2ee/MlsCarrierTransport.java";
    private static final String CARRIER_RCS =
            "src/com/android/messaging/rcs/carrier/CarrierRcsTransport.java";

    /**
     * <b>THE INVARIANT.</b> One identifier reaches both the seal and the envelope, so they cannot
     * disagree. Asserted by comparing the arguments rather than by looking for a literal, because
     * the property is that the two calls AGREE — not that either mentions a particular name.
     */
    @Test
    public void theSealAndTheEnvelopeCarryTheSameId() throws IOException {
        final String body = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(CARRIER_RCS)), "sendMls");
        assertTrue("CarrierRcsTransport.sendMls not found in " + CARRIER_RCS
                + " — a guard that cannot find its subject passes on nothing", body.length() > 200);

        final List<String> seal = argsAt(body, "encryptForSend(");
        final List<String> envelope = argsAt(body, "sendMlsBody(");
        assertFalse("no encryptForSend( call in sendMls — this guard is scanning nothing",
                seal.isEmpty());
        assertFalse("no sendMlsBody( call in sendMls — this guard is scanning nothing",
                envelope.isEmpty());

        assertEquals("the seal must be handed the caller's message id — the 3-arg form. With the "
                        + "2-arg overload it binds a SYNTHESISED id while the envelope carries the "
                        + "app's row UUID, which is the RCC.16 §7.5.3.1 mismatch a Google Messages peer "
                        + "drops. Its arguments read: " + seal,
                3, seal.size());

        final String sealedId = seal.get(2);
        final String envelopeId = envelope.get(envelope.size() - 1);
        assertTrue("the id handed to the seal is empty — read the source rather than relaxing this",
                !sealedId.isEmpty() && !envelopeId.isEmpty());
        assertEquals("THE ENVELOPE ID AND THE AAD ID DISAGREE. sendMls seals with `" + sealedId
                        + "` and puts `" + envelopeId + "` on the wire. RCC.16 §7.5.3.1 requires "
                        + "them to be the same value and a Google Messages peer drops a mismatch — and "
                        + "nothing on this leg would tell you, because the carrier receive path "
                        + "calls the no-AAD process() entry and never checks.",
                envelopeId, sealedId);
    }

    /**
     * The seal's own half: it must accept an id and prefer it, and its fallback must not be the
     * era-only constant that started this.
     */
    @Test
    public void theCarrierSealPrefersTheCallerIdAndItsFallbackCarriesAnEpoch() throws IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(CARRIER_TRANSPORT));

        // TWO DECLARATIONS: the interface's 2-arg form, which now delegates, and the id-carrying
        // one. Counted rather than located by body, because the delegator is a one-liner and a
        // length check on it would fail on correct code — which is exactly how the first version
        // of this assertion failed, on the change it was written to bless.
        assertEquals("MlsCarrierTransport must have exactly two encryptForSend declarations — the "
                        + "E2eeConversationTransport 2-arg form and the one that takes the "
                        + "caller's message id. Without the latter, CarrierRcsTransport cannot "
                        + "bind the envelope's id into the AAD and every message this leg sends "
                        + "carries two different ids.",
                2, SourceScan.count(src, "Payload encryptForSend("));
        assertTrue("the id-carrying overload no longer names a caller id parameter",
                SourceScan.count(src, "rcsMessageId") >= 1);

        // The synthesised fallback must carry an epoch. Era alone made the id a per-(peer, era)
        // CONSTANT — the same id on every message in that era.
        assertTrue("the synthesised fallback id no longer carries an epoch. Era alone is not an "
                        + "identifier: every message this leg seals in one era would share it, "
                        + "which is how the provider leg silently lost two of three messages in a "
                        + "controlled experiment on 2026-08-08. Production never takes this arm, "
                        + "so nothing else would ever notice it rotting.",
                SourceScan.count(src, "epochFrom(") >= 1);
        assertTrue("the synthesised fallback id no longer carries a generation — the counter that "
                        + "distinguishes messages WITHIN one epoch",
                SourceScan.count(src, "nextGen") >= 1);
    }

    /**
     * <b>The receive side must not start recomputing the AAD.</b> This is a NEGATIVE assertion and
     * it exists because the natural "fix" for a mismatch is to make both ends synthesise the same
     * string — which would re-create a self-consistent pair that works between our clients and
     * fails against every Google Messages peer, i.e. the harder-to-find version of this bug.
     *
     * <p>Under RFC 9420 the AAD travels in the message and the receiver reads it. There is nothing
     * for the receive path to build.
     */
    @Test
    public void theReceivePathStillDoesNotRecomputeTheAad() throws IOException {
        final String body = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(CARRIER_TRANSPORT)), "onInboundCpim");
        assertTrue("MlsCarrierTransport.onInboundCpim not found — this guard is scanning nothing",
                body.length() > 200);
        assertEquals("the carrier receive path now builds an AAD. Do not make both ends synthesise "
                        + "the same id: that produces a pair of OUR clients that agree with each "
                        + "other and with nobody else, which is strictly harder to find than the "
                        + "mismatch it replaces. The AAD travels inside the PrivateMessage (RFC "
                        + "9420) and the receiver reads it — there is nothing to recompute.",
                0, SourceScan.count(body, "buildAuthenticatedData("));
        assertEquals("the carrier receive path now calls the AAD-carrying decrypt entry. See above "
                        + "— if a binding CHECK is what is wanted here, use "
                        + "MlsAppMessage.aadMessageIdMatches against the envelope id, the way "
                        + "MlsProviderTransport does; do not rebuild the expected AAD.",
                0, SourceScan.count(body, "decryptWithAad("));
    }

    /** Top-level arguments of the first call to {@code needle} in {@code body}, parens balanced. */
    private static List<String> argsAt(final String body, final String needle) {
        final List<String> out = new ArrayList<>();
        final int at = body.indexOf(needle);
        if (at < 0) return out;
        final int open = at + needle.length() - 1;
        int depth = 0;
        int start = open + 1;
        for (int i = open; i < body.length(); i++) {
            final char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth == 0) {
                    out.add(body.substring(start, i).trim());
                    return out;
                }
            } else if (c == ',' && depth == 1) {
                out.add(body.substring(start, i).trim());
                start = i + 1;
            }
        }
        return out;
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * On the carrier leg, the message id bound into the AAD is the id on the envelope (RCC.16 §7.5.3.1:
 * a peer cross-checks the two and drops a mismatch). Under RFC 9420 the AAD travels inside the
 * {@code PrivateMessage} and the receiver reads it, so our own receive path cannot detect a
 * mismatch; only a peer can. Identifiers are read out of the call being checked, never hardcoded.
 * See docs/rcs/carrier-transport.md.
 */
public class MlsCarrierAadIdGuardTest {

    private static final String CARRIER_TRANSPORT =
            "src/com/android/messaging/rcs/e2ee/MlsCarrierTransport.java";
    private static final String CARRIER_RCS =
            "src/com/android/messaging/rcs/carrier/CarrierRcsTransport.java";

    /**
     * One identifier reaches both the seal and the envelope. Asserted by comparing the arguments,
     * because the property is that the two calls agree, not that either mentions a name.
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
                        + "app's row UUID, which is the RCC.16 §7.5.3.1 mismatch some peers "
                        + "drop. Its arguments read: " + seal,
                3, seal.size());

        final String sealedId = seal.get(2);
        final String envelopeId = envelope.get(envelope.size() - 1);
        assertTrue("the id handed to the seal is empty — read the source rather than relaxing this",
                !sealedId.isEmpty() && !envelopeId.isEmpty());
        assertEquals("THE ENVELOPE ID AND THE AAD ID DISAGREE. sendMls seals with `" + sealedId
                        + "` and puts `" + envelopeId + "` on the wire. RCC.16 §7.5.3.1 requires "
                        + "them to be the same value, and a peer drops a mismatch: our own "
                        + "carrier receive path does, through MlsEngineIdCheck in "
                        + "MlsCarrierTransport.onInboundCpim. Nothing on the sending side would "
                        + "tell you.",
                envelopeId, sealedId);
    }

    /**
     * The seal accepts an id and prefers it, and its synthesised fallback carries an epoch: an
     * era-only id is a per-(peer, era) constant, and the transport dedupes reissued ids.
     */
    @Test
    public void theCarrierSealPrefersTheCallerIdAndItsFallbackCarriesAnEpoch() throws IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(CARRIER_TRANSPORT));

        // One declaration, the id-carrying one. Counted, so a returning 2-arg overload, which
        // could only bind a synthesised id, fails here as well.
        assertEquals("MlsCarrierTransport must have exactly one encryptForSend declaration, the "
                        + "one that takes the caller's message id. Without it, "
                        + "CarrierRcsTransport cannot bind the envelope's id into the AAD and "
                        + "every message this leg sends carries two different ids.",
                1, SourceScan.count(src, "Payload encryptForSend("));
        assertTrue("the id-carrying overload no longer names a caller id parameter",
                SourceScan.count(src, "rcsMessageId") >= 1);

        // Era alone made the id the same on every message in that era.
        assertTrue("the synthesised fallback id no longer carries an epoch. Era alone is not an "
                        + "identifier: every message this leg seals in one era would share it, "
                        + "and the provider leg would silently lose all but one "
                        + "of them. Production never takes this arm, "
                        + "so nothing else would ever notice it rotting.",
                SourceScan.count(src, "epochFrom(") >= 1);
        assertTrue("the synthesised fallback id no longer carries a generation — the counter that "
                        + "distinguishes messages WITHIN one epoch",
                SourceScan.count(src, "nextGen") >= 1);
    }

    /**
     * The receive side does not recompute the AAD: the receiver reads it from the message, and
     * making both ends synthesise the same string would work between our clients and fail against
     * others.
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
                        + "— the binding CHECK is the engine's, armed with the envelope id by "
                        + "MlsEngineIdCheck.process as on the provider leg; do not rebuild the "
                        + "expected AAD.",
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

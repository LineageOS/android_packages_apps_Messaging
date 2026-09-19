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
package com.android.messaging.rcs;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.RcsOutgoingMessage;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsSendResult;
import org.lineageos.rcs.provider.RcsSubInfo;

/**
 * The main app's view of "an RCS backend". This is the seam the rest of
 * Messaging talks to; the only implementation in v1 is {@link
 * ProviderTransport}, which forwards every call across the {@code IRcsProvider}
 * AIDL boundary to the RCS provider APK. Keeping an interface here (rather
 * than calling the binder proxy directly) means:
 *
 * <ul>
 *   <li>the send path / OTP catcher depend on a clean main-app type, not on
 *       the generated AIDL stub, so they stay testable; and
 *   <li>a future in-process transport (or a no-op stub when no provider is
 *       installed) can be dropped in without touching call sites.
 * </ul>
 *
 * All methods are safe to call before the provider is bound: implementations
 * must no-op / return a not-registered result rather than throw, so the
 * caller can always fall back to SMS. None of these run on the main thread
 * unless noted (they are invoked from DataModel Actions).
 *
 * <p>Multi-transport framework (design §2, §5): several implementations coexist,
 * managed by {@link ProviderRegistry} -- the external AIDL bridge
 * ({@link BoundProviderTransport}, one per discovered provider APK) and the
 * in-process carrier-IMS transport (a later phase). {@link RouteSelector} picks
 * one transport per subId using {@link #canServeSub} (pre-flight eligibility)
 * and {@link #getProviderCaps}'s declared priority.
 */
public interface RcsTransport {

    /** True once attach() has succeeded and a clientToken is held. */
    boolean isAttached();

    /**
     * Static (SIM-independent) capability description this transport publishes:
     * provider label, contract version, supported transport tags, declared
     * selection {@code priority}, and the {@code featureFlags} bitmask. Used by
     * {@link RouteSelector} to rank eligible transports. Returns null when not
     * yet known (e.g. an external provider that isn't bound). Non-blocking.
     */
    @Nullable
    RcsProviderCaps getProviderCaps();

    /**
     * Pre-flight, NON-side-effecting per-line eligibility check (design §5.2).
     * Fast + synchronous; distinct from {@link #startForSub} (which commits to a
     * provisioning run). Returns one of {@code IRcsProvider.LINE_INELIGIBLE /
     * LINE_ELIGIBLE / LINE_MAYBE / LINE_UNKNOWN}. A transport whose eligibility
     * isn't synchronously knowable (carrier-IMS) returns {@code LINE_MAYBE} and
     * lets {@code startForSub} be authoritative. Returns {@code LINE_UNKNOWN}
     * when not attached.
     */
    int canServeSub(RcsSubInfo sub);

    /**
     * Begin (or resume) provisioning + registration for a sub. Idempotent;
     * a no-op if not attached.
     */
    void startForSub(RcsSubInfo sub);

    /** Tear down registration for a sub. Idempotent. */
    void stopForSub(int subId);

    /**
     * Latest negotiated per-sub capabilities, or null if unknown / not
     * started / not attached.
     */
    RcsProviderCaps getCapabilitiesForSub(int subId);

    /**
     * Synchronous per-recipient RCS capability lookup. MUST be called off the
     * main thread (it can hit the network via a Tachyon LookupRegistered RPC
     * in the provider; the provider caches). Returns one of
     * {@code IRcsProvider.CAP_UNKNOWN / CAP_SMS_ONLY / CAP_RCS}. Returns
     * CAP_UNKNOWN when not attached so the caller keeps optimistic routing.
     */
    int lookupRcsCapability(int subId, String phoneE164);

    /**
     * Try to send a 1-1 text over RCS. Returns a synchronous accept/reject so
     * the caller can fall back to SMS immediately on reject. Never throws;
     * a binder failure is reported as a non-accepted INTERNAL_ERROR result.
     */
    RcsSendResult sendMessage(RcsOutgoingMessage msg);

    /**
     * E2EE: true iff this transport can carry <b>app-provided MLS</b>
     * ({@link com.android.messaging.rcs.e2ee.RcsE2eeScheme#MLS}) for the sub right
     * now — i.e. the MLS session is provisioned (KDS cert + uploaded KeyPackages)
     * and the transport is attached/registered. The Etouffee plane is owned by the
     * provider and is NOT signalled here. Default {@code false} (transport carries
     * no MLS); only the carrier-IMS transport overrides it. Non-blocking.
     */
    default boolean isMlsReady(int subId) {
        return false;
    }

    /**
     * E2EE: send an already-gated MLS-E2EE 1-1 message. The transport
     * owns the whole app-provided-MLS flow (enrol/claim/group/Welcome/encrypt) and
     * reports terminal status async via the same {@code EVT_MESSAGE_STATUS} path a
     * plaintext RCS send uses (correlated by {@code messageId}). Returns {@code true}
     * if the transport accepted ownership of the send (dispatched), {@code false} if
     * it cannot carry MLS (caller falls back per the gate). No-op default.
     *
     * <p><b>Takes a FRAMED BODY, not text.</b> {@code framedBody} is an RCC.16 MIME
     * entity from {@code RccMlsBody.frame} / {@code frameText}: the inner content type is inside the
     * frame, which is what lets this path carry media and interoperate with a third-party peer. The
     * old {@code String text} signature could express neither.
     *
     * @param toUri {@code tel:+E164} of the peer
     * @param framedBody the RCC.16-framed application entity to encrypt and send
     */
    default boolean sendMlsMessage(int subId, String toUri, byte[] framedBody, String messageId) {
        return false;
    }

    /**
     * Fire an IMDN delivered/displayed receipt for an inbound message.
     * imdnType is one of {@code IRcsProviderCallback.IMDN_DELIVERED /
     * IMDN_DISPLAYED}. No-op if not attached.
     */
    void sendImdn(String originalMessageId, String toUri, int imdnType);

    /**
     * As {@link #sendImdn}, naming the RCS GROUP the original message arrived in.
     *
     * <p>On an MLS conversation the group id is what selects the conversation the receipt is stamped
     * from. Without it the stamping resolves by peer, and for a group message that finds the 1:1 with
     * the same peer — a different MLS group at a different era, which Google Messages rejects as
     * {@code ZINNIA_FAILURE_GROUP_ID_MISMATCH}.
     *
     * <p>Defaulted rather than added to the interface method so the carrier transports, which have no
     * MLS stamping and no group routing to get wrong, need no change.
     */
    default void sendImdn(String originalMessageId, String toUri, int imdnType,
            String rcsGroupId) {
        sendImdn(originalMessageId, toUri, imdnType);
    }

    /** Typing indicator. Best-effort; no-op if not attached. */
    void sendTyping(String toUri, boolean active);

    /**
     * Feed an OTP captured by the main app (default-SMS role) back to the
     * provider's verify flow. No-op if not attached.
     */
    void submitOtp(int subId, String otp);

    /**
     * Decline an inbound file the user chose NOT to download (contract-v17,
     * design §8). The counterpart to {@code acceptIncomingFile}: a
     * session-oriented transport (MSRP FT) must send an actual SIP 603 Decline
     * rather than let the INVITE time out; an FT-HTTP transport may simply drop
     * the pending descriptor. Idempotent; no-op if not attached / unknown id.
     */
    void rejectIncomingFile(int subId, String messageId);
}

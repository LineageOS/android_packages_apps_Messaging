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

/**
 * Which revision of GSMA RCC.16 the TRANSPORT on the other side speaks.
 *
 * <p>GSMA published RCC.16 v4.0 in 2026-08 and it changes shapes we have already device-proven.
 * The clearest is {@code end_mls} (0xF002): v3.0 puts the 7-byte ASCII literal {@code "end_mls"} in
 * {@code extension_data}, v4.0 puts a serialised {@code EndMlsMetadata} protobuf there. Both cannot
 * be on the wire at once — and the two transports we speak to are not on the same revision:
 *
 * <ul>
 *   <li><b>Google Tachyon is v3.0.</b> Our v3.0-form {@code end_mls} was ACCEPTED by the server and
 *       a Google Messages peer decrypted the result, in both directions. No v4.0 shape has ever been
 *       observed from it.</li>
 *   <li><b>The lab (openrcs) can move to v4.0</b> on request — their CPM AS is already an RCC.16
 *       Conversation Focus and they develop against the spec.</li>
 * </ul>
 *
 * <p>So the engine has to serve both AT ONCE, and it cannot discover which is which: the spec
 * revision is a property of the DEPLOYMENT, not of MLS. It is <b>announced</b> by the transport,
 * exactly like the capability-advertisement override and the KDS behaviour profile before it.
 *
 * <p><b>Never infer it</b> from a capability advertisement, a probe, or the shape of something that
 * arrived. Nothing Tachyon sends states its RCC.16 version, and a detection heuristic here is the
 * silent-failure class — the same trap as guessing a code point (where a guessed
 * 0xF007 drove two behaviours for months). The rule at every divergent site is <b>decode tolerantly,
 * encode strictly</b>: accepting both forms inbound is cheap and protects us against a server that
 * upgrades before we notice; what we PRODUCE follows the announced revision exactly.
 *
 * <h2>NOTHING ON THE WIRE ANNOUNCES AN RCC.16 REVISION — checked 2026-08-07</h2>
 *
 * Worth stating because "announced by the transport" invites the reading that a peer tells us, and
 * no peer does. The full inventory of what is actually exchanged:
 *
 * <ul>
 *   <li>{@code +g.gsma.rcs.mls.mls-version} is the RCS capability tag, and its value is the fixed
 *       constant {@code "v1"} ({@code RcsE2eeScheme.MLS_VERSION_V1}). It says "I do MLS", not which
 *       RCC.16 revision. Google Messages sends the same {@code v1}.</li>
 *   <li>{@code +g.gsma.rcs.mls.mls-kds} is a KDS selector ({@code "2"}), not a spec revision.</li>
 *   <li>The MLS capability lists (extension types, proposal types) are an implicit, per-code-point
 *       claim — the closest thing to a revision signal, and it is not one: Google Messages advertises the
 *       voided 0xF001 while implementing v4.0 payloads.</li>
 * </ul>
 *
 * <p>So this value is a LOCAL configuration read from {@code MlsConfig.KEY_RCC16_VERSION}, and
 * "announced by the transport" means <b>the transport tells the ENGINE</b>, not the peer. There is no
 * negotiation and no handshake to lose.
 *
 * <p><b>Which is why we build both revisions and pick by sysprop.</b> A wire that carried a revision
 * would let us serve whatever a peer asked for; without one, every divergent site must either be
 * revision-stable or chosen from evidence about the DEPLOYMENT. That is the whole reason the rule
 * here is <i>decode tolerantly, encode strictly</i> — tolerance covers the peer we cannot interrogate.
 *
 * <p><b>Worked example, and the reason this section exists:</b> the {@code gsma_rcs_e2ee_feature}
 * custom-proposal framing (0xF002/0xF004 bare vs length-prefixed) has NO revision switch upstream —
 * one encoding, no version input. If v3.0 and v4.0 disagreed there, no library could serve both and
 * nothing on the wire would tell us which was wanted. We resolved it by evidence about the
 * deployment rather than by reading the spec: Google Messages demonstrably compiles that feature ON,
 * and it is the peer our v3.0 transport actually talks to, so bare is right for this deployment whatever
 * the nominal revision.
 *
 * <p>This enum is deliberately its own type rather than a member of {@code OpenMlsEngine}: it is a
 * spec fact with no Android and no JNI behind it, which is what lets the host tests pin the default.
 *
 * <h2>ONE VALUE IS A LOSSY COMPRESSION OF TWO INDEPENDENT FACTS — a known approximation</h2>
 *
 * This announces a single revision per transport. That is the right granularity for <b>wire
 * shapes</b> and it is <b>not</b> the right granularity for <b>capability lists</b>, and Google Messages is
 * the proof: it is simultaneously AHEAD of v3.0 on payloads ({@code EndMlsMetadata} at 0xF002,
 * {@code key_generation} in SecretPayload) and BEHIND v4.0 on its capability list (it still
 * advertises the 0xF001 {@code end_mls} proposal that v4.0 §7.11.2.1 voided). The summary to
 * hold: <i>substantially a v4.0 implementation, but not cleanly one — a v4.0 feature set
 * carried on a v3.0-derived capability list.</i>
 *
 * <p><b>Why they diverge, which is the part that predicts the future:</b> a payload encoding is
 * decided by whoever writes the bytes; a capability list is decided by whoever maintains the
 * registry. In a large codebase those are different owners on different schedules, so there is no
 * mechanism keeping them in step. <b>Expect them to diverge again, and expect the CAPABILITY LIST to
 * be the laggard — a registry entry has no failing test attached to it.</b>
 *
 * <p><b>THE FALSIFIER:</b> if Google Messages is ever found AHEAD on its capability list and BEHIND on a
 * payload — the divergence running the other way — this mechanism is wrong and should be
 * <b>discarded rather than patched</b>. An explanation with no failure condition is decoration, and
 * the whole value of this one is that it predicts a direction.
 *
 * <p>Deliberately NOT split into two knobs. The two agree for every transport we ship, and a second
 * mechanism with nothing to drive it is worse than one documented approximation — the same reasoning
 * applied to {@code ext_encode}'s framing gate. Split it when a deployment actually needs them apart,
 * and let that deployment supply the evidence for both values.
 *
 * <p>Sources: our own v3-to-v4 delta and v4 conformance assessment; the payload /
 * capability split read off the shipping client.
 */
public enum Rcc16Version {
    /** RCC.16 v3.0 — what Google Tachyon speaks, device-proven in both directions. */
    V3_0(30),
    /** RCC.16 v4.0, published 2026-08. No production transport of ours speaks it yet. */
    V4_0(40);

    /** The value the engine's C ABI takes ({@code rcs_mls_set_rcc16_version}). */
    public final int wire;

    Rcc16Version(final int wire) { this.wire = wire; }

    /** True iff {@code wire} names a revision this build knows. */
    public static boolean isKnownWire(final int wire) {
        for (final Rcc16Version v : values()) { if (v.wire == wire) return true; }
        return false;
    }

    /**
     * Resolve a wire value ({@code 30}/{@code 40}), typically from
     * {@link MlsConfig#KEY_RCC16_VERSION}.
     *
     * <p>Anything unrecognised resolves to {@link #V3_0} — a mistyped sysprop must land on the
     * revision we have evidence for, never on the one that would silently change what we put on the
     * wire. Callers that want to report the mistake ask {@link #isKnownWire} first; this class stays
     * free of logging so it can be tested on the host.
     */
    public static Rcc16Version fromWire(final int wire) {
        for (final Rcc16Version v : values()) { if (v.wire == wire) return v; }
        return V3_0;
    }
}

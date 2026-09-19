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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The "should never happen" scan — assert by ABSENCE (rework item 13.5).
 *
 * <p>Every one of our host tests is a positive-path assertion on a leaf function. None of them can
 * fail because something bad appeared in a run, and several of this project's most expensive bugs
 * were exactly that shape: a condition that logged and was never read.
 *
 * <p>This is the other half. Feed it a device log and it tells you whether anything in the
 * never-expected set fired. It is deliberately a pure function over lines so the same catalogue runs
 * in a host test, in CI, and against a capture — the alternative is a shell pipeline that lives in
 * one script and rots.
 *
 * <h2>Why absence is worth asserting on, concretely</h2>
 *
 * <p>Four of the markers below correspond to bugs found this project, and in every case the symptom
 * was reachable in a log nobody was grepping for:
 *
 * <ul>
 *   <li>an inbound message with <b>no AAD</b> — the §7.5.3.1 binding check had been fail-opening on
 *       every Google Messages message for the whole life of the check;</li>
 *   <li>the drive loop hitting its <b>iteration cap</b> — means a pass reports work without making
 *       progress;</li>
 *   <li>an <b>unhandled engine result</b> — the host has no arm for something the engine did;</li>
 *   <li>a <b>self-heal era advance failing</b> — the last rung of the recovery ladder not holding.</li>
 * </ul>
 *
 * <h2>The catalogue is the artefact, not the matcher</h2>
 *
 * <p>Matching is a substring test on purpose. A regex catalogue invites clever patterns that drift
 * from the log lines they are meant to track, and the log lines are already written to be
 * distinctive. If a marker stops matching because a message was reworded, that is a real signal —
 * the message and its check should move together, which is why {@link Marker#emittedBy} names where
 * the line comes from.
 */
public final class MlsInvariantScan {

    /** How bad it is that this fired at all. */
    public enum Severity {
        /** Should be impossible. A CI run seeing this must fail. */
        NEVER,
        /** Expected to be flat at zero in a healthy fleet, but a peer can provoke it. */
        SUSPICIOUS
    }

    /** One never-expected condition. */
    public static final class Marker {
        /** Substring to look for. */
        public final String needle;
        public final Severity severity;
        /** The invariant or item it guards. */
        public final String guards;
        /** Where the line is emitted, so a reword moves both together. */
        public final String emittedBy;

        Marker(final String needle, final Severity severity, final String guards,
                final String emittedBy) {
            this.needle = needle;
            this.severity = severity;
            this.guards = guards;
            this.emittedBy = emittedBy;
        }
    }

    /** One hit. */
    public static final class Violation {
        public final Marker marker;
        /** The offending line, verbatim. */
        public final String line;
        /** 1-based index in the scanned stream, so a hit can be found again. */
        public final int lineNumber;

        Violation(final Marker marker, final String line, final int lineNumber) {
            this.marker = marker;
            this.line = line;
            this.lineNumber = lineNumber;
        }

        @Override public String toString() {
            return "[" + marker.severity + "] line " + lineNumber + " — " + marker.guards
                    + "\n    " + line.trim();
        }
    }

    private static final List<Marker> MARKERS;

    static {
        final List<Marker> m = new ArrayList<>();
        m.add(new Marker("MaxLoopReached", Severity.NEVER,
                "rework 4.3: the bounded drive loop hit its cap, i.e. a pass kept reporting more "
                + "work without making progress. This counter is expected FLAT AT ZERO forever.",
                "MlsDriveLoop.drive / MlsMetrics.MAX_LOOP_REACHED"));
        m.add(new Marker("hit the iteration cap", Severity.NEVER,
                "rework 4.3: same condition as MaxLoopReached, via the log rather than the counter.",
                "MlsDriveLoop.Result.logLine"));
        m.add(new Marker("reported work without progressing", Severity.NEVER,
                "two consecutive drive passes returned an indistinguishable action, so "
                + "the loop stopped rather than spending another GetMlsGroupInfo on an answer it "
                + "already had. Same fault as MaxLoopReached, caught eight fetches earlier — and it "
                + "fires WITHOUT the iteration cap being reached, so scanning only for the cap line "
                + "would miss every instance from here on.",
                "MlsDriveLoop.Result.logLine"));
        m.add(new Marker("UNHANDLED MLS RESULT", Severity.NEVER,
                "rework 4.1d: the host had no arm for an engine status. A .so/Java version skew, or "
                + "a status added on the Rust side without a matching arm.",
                "MlsProviderTransport.applyInboundControl"));
        m.add(new Marker("cannot handle action", Severity.NEVER,
                "rework 4.2: a flow was handed a host action it does not handle — rejected by name.",
                "MlsHostAction.rejectUnhandled"));
        m.add(new Marker("Failed requirement", Severity.NEVER,
                "rework 5.5: notification-driven work reached a transport entry point by a route "
                + "that should not exist.",
                "MlsSchedulingType.requireTransportAllowed"));
        m.add(new Marker("carries NO AuthenticatedData", Severity.SUSPICIOUS,
                "the §7.5.3.1 message-id binding check cannot assert anything and passes by "
                + "default. A Google Messages peer always binds one, so this is a non-binding peer or our "
                + "AAD plumbing regressed — the exact condition that hid a live defect for the "
                + "whole life of the check.",
                "MlsProviderTransport.decryptInbound"));
        m.add(new Marker("TAKING OVER the era advance", Severity.SUSPICIOUS,
                "a member stopped yielding to the designated era advancer and advanced itself "
                + "(see MlsAdvancerElection). Legitimate and necessary — an "
                + "advancer that never connects would otherwise hold every other member's "
                + "self-heal hostage — but it is also the one recovery step that can FORK a group "
                + "if the election was wrong, so it is worth reading every time. TWO of these for "
                + "the same group inside one run is the shape to worry about: the rank stagger "
                + "exists so the first takeover moves the group and stands the rest down, and two "
                + "means either it did not move the group or two members disagreed about the "
                + "roster they were electing from.",
                "MlsProviderTransport.eraYieldExhausted"));
        m.add(new Marker("era advance FAILED", Severity.SUSPICIOUS,
                "the last rung of the recovery ladder did not hold. Not destructive (state is "
                + "rolled back), but ERA_GAP -> ERA_ADVANCE_REQUIRED is the arm real conversations "
                + "reach.",
                "MlsProviderTransport.selfHeal"));
        m.add(new Marker("did NOT take", Severity.SUSPICIOUS,
                "an era advance the server accepted without moving the era. EXPLAINED as of "
                + "2026-09-10: A CREATE THAT REUSES A CONTEXTID THE SERVER ALREADY "
                + "HOLDS DOES NOT TAKE. An era advance re-creates the conversation and, by "
                + "default, sends the STORED contextId — so it asks for the one thing that does not "
                + "take, and nothing says so: gRPC OK, no trailer, the era simply does not move "
                + "(the old wording here — 'verdict=0, a fresh serverGroupId' — was withdrawn "
                + "later: the create response has one wire field, a two-long header, so it "
                + "carries no verdict to be 0, and the id in it is a response id, not a group "
                + "id). Measured on "
                + "BOTH shapes with the era read back every time: 1:1 four "
                + "alternating trials on one conversation, and GROUP four trials across two "
                + "purpose-built groups with ordinal position controlled — a freshly minted "
                + "contextId is GRANTED, the stored one is REFUSED, same group and same target "
                + "era minutes apart. Lever: debug.rcs.mls_advance_fresh_ctxid. "
                + "SUPERSEDES the 2026-08-06 reading recorded here, which was 'the era advance is "
                + "RATE-LIMITED PER CONVERSATION, window >1h44m and unmeasured'. That was already "
                + "dead on the later evidence and it MISDIRECTS: it tells a reader to wait "
                + "out a window that does not exist. Do not restore it. It stays SUSPICIOUS "
                + "rather than NEVER because the line is still what a real problem looks like "
                + "and because a deliberate control arm produces it on purpose; the discriminator "
                + "is the contextId on the create above it, not elapsed time. Our repair "
                + "primitive IS the era advance, so a run with several of these is a conversation "
                + "whose repair budget is being spent invisibly.",
                "MlsProviderTransport.eraAdvanceLocked"));
        m.add(new Marker("ACCEPTED AND DISCARDED", Severity.SUSPICIOUS,
                "a CreateMlsConversation the server accepted and did not apply. Expected when the "
                + "server already holds the conversation — Google Messages calls that reason "
                + "'THE GROUP ALREADY EXISTS ON THE SERVER' and no era can help, because you "
                + "cannot be at the right era for a group you are asking to CREATE ("
                + "four eras measured, all discarded). The remedy is a re-Welcome from a member "
                + "that is current, NOT another create. Suspicious rather than never because a "
                + "create that hits this on a conversation the server should NOT hold is a real "
                + "finding.",
                "MlsProviderTransport.establishGroup"));
        m.add(new Marker("RE-WELCOME REQUEST", Severity.SUSPICIOUS,
                "we asked to be re-admitted because we hold no state for a group the server does. "
                + "Correct behaviour and the only move available to a member with nothing to "
                + "advance from — but it means this device cannot participate until some CURRENT "
                + "member advances the era. If it repeats without a Welcome arriving, no member is "
                + "acting and the group needs one driven by hand.",
                "MlsProviderTransport.requestReWelcome"));
        m.add(new Marker("LEGACY SCALAR LOAD", Severity.SUSPICIOUS,
                "a conversation loaded from the pre-record .gid scalars, meaning the record store "
                + "did not have it. Not a fault — the drain is by design and it re-writes as a "
                + "record on its next putGroup — but it is the ONE fact the scalar-block deletion is "
                + "waiting on: the mls_provider_conv scalar block may be deleted only when this "
                + "stops appearing across a representative period. Seeing it AFTER the deletion "
                + "would mean state was lost.",
                "MlsProviderTransport.loadPersisted"));
        m.add(new Marker("is OUR OWN, fanned back", Severity.SUSPICIOUS,
                "our own group control message returned to us by the server's fan-out. Entirely "
                + "normal — every member receives it and we are a member — and listed only "
                + "because it USED to be reported as 'not a valid MLSMessage (MALFORMED)', a claim "
                + "about a peer. If a scan shows MALFORMED for our own number again, that "
                + "regression is back.",
                "MlsProviderTransport.applyInboundControl"));
        m.add(new Marker("is STRANDED", Severity.SUSPICIOUS,
                "consecutive future-epoch controls with none applying: we missed a commit and MLS "
                + "cannot skip one.",
                "MlsProviderTransport.applyInboundControl"));
        // NEVER -> SUSPICIOUS, and the demotion is the POINT rather than a
        // relaxation. This row used to read "Defer-until-ACK should make this unreachable", which
        // was already false — a rollback that FAILS lands here, which is what
        // quarantineIfAheadOfServer exists for, and a test fixture produces it on purpose — and is
        // now false by design: when an Apply's response is SILENT about whether the server took the
        // commit, keepUnacknowledgedCommit HOLDS it rather than discarding it, and a hold the server
        // did not take is exactly this state. It is reached deliberately, it has an automatic repair
        // (reconcileAction's AHEAD arm rebuilds), and the alternative it was chosen over — discarding
        // a commit the server applied — lands in LOWER_EPOCH_CHAIN_UNKNOWN, which nothing repairs.
        // A NEVER row that a designed path reaches trains a reader to dismiss the catalogue.
        m.add(new Marker("local state is poisoned", Severity.SUSPICIOUS,
                "we are AHEAD of the server — we hold a commit the server did not take. REACHABLE "
                + "BY DESIGN: a commit whose response was silent is held rather "
                + "than discarded, because being AHEAD is repairable (this arm rebuilds) and being "
                + "BEHIND an epoch we signed is not. Expect it to be RARE and to clear itself; a "
                + "conversation that stays here across rebuilds is the real fault.",
                "MlsProviderTransport.reconcileAction"));
        m.add(new Marker("the delete-on-chat-row-insert", Severity.NEVER,
                "rework 6.4: the rendezvous table hit its cap, so the release path has stopped "
                + "running and duplicate deliveries will re-run a ratchet-advancing decrypt.",
                "MlsRendezvousStore.put"));
        m.add(new Marker("A retry of it will re-encrypt", Severity.NEVER,
                "rework 7.1: the ciphertext cache hit its cap, so retries resume burning "
                + "generations — invariant 62 is no longer being enforced.",
                "MlsCiphertextCache.put"));
        m.add(new Marker("NOT sending it. The escalation ladder needs the row", Severity.NEVER,
                "rework 7.4: a resend could not be recorded, so it was not sent. An unrecorded "
                + "resend carries no count and the escalation ladder never advances — a diverged "
                + "peer would be resent to forever. Means the register is unwritable.",
                "MlsProviderTransport.resendOriginal"));
        m.add(new Marker("LEGACY 1:1 WIRE id", Severity.SUSPICIOUS,
                "a peer reported an id from the pre-unification namespace, so it was "
                + "never a row id and the message cannot be recovered. Expected only from messages "
                + "sent before the fix; a NEW one means a send path is still synthesising its own "
                + "wire id — sendFramedToGroup was exactly that until 7.4.",
                "MlsProviderTransport.resendOriginal"));
        m.add(new Marker("could not state WHO SIGNED it", Severity.SUSPICIOUS,
                "the sender-identity binding cannot assert anything, so an inbound "
                + "message is being attributed to whoever the TRANSPORT ENVELOPE named — a field "
                + "the sender chooses. Same shape as the AAD check that fail-opened for its whole "
                + "life, and the same cost if it goes unnoticed.",
                "MlsProviderTransport.decryptInbound"));
        // THE NEEDLE IS THE EMITTER'S OWN CONSTANT (see the note further down about the three
        // characters that made an earlier hand-typed needle undetectable). The wording predates
        // MlsInboundRefusal and captures already grep for it, so the constant carries it verbatim.
        m.add(new Marker(MlsInboundRefusal.MARKER_IMPERSONATION, Severity.NEVER,
                "a group member sent a message under another member's name. The decrypt "
                + "SUCCEEDED, so this is not a crypto failure and not noise — it is an authenticated "
                + "member of the group impersonating a peer. The response is a "
                + "SILENT REFUSAL (no FTD, no self-heal, no rebuild credit); before it, each one of "
                + "these cost the group a repair and told the IMPERSONATED peer we could not decrypt "
                + "a message they never sent, so a run of them on an older build is an amplified "
                + "attack rather than a record of one.",
                "MlsProviderTransport.decryptInbound / MlsInboundRefusal.impersonationLine"));
        m.add(new Marker("WELCOME-EXT probe", Severity.NEVER,
                "this probe is DELETED and must never come back. It looped "
                + "0xF010..0xF018 through groupExt(), which reads the GROUPCONTEXT extension list, "
                + "and on finding nothing logged that it was 'the only read that could have found' "
                + "a continuity token — which lives in GroupInfo.extensions and was therefore "
                + "invisible to it by construction. It produced two wrong answers to the same "
                + "question; the second was caught only because the correct instrument printed the "
                + "opposite verdict two lines below it in the same log (a Google Messages peer's "
                + "Welcome carried a 33-byte 0xF010). If this string appears, either the probe was "
                + "reinstated or you are reading a log from a build that predates its removal — "
                + "check which before believing "
                + "anything it says about 0xF010. The read that works is WELCOME-GI-EXT.",
                "MlsProviderTransport.joinFromWelcome (removed)"));
        m.add(new Marker("continuity DROPPED", Severity.NEVER,
                "a §7.11.12.1 continuity token reached us and was not persisted. The "
                + "point is that this used to happen SILENTLY on both arrival routes — the "
                + "Welcome-borne token was handed to a log-only instrument, and the §10.5.4 token "
                + "was written to a preference nothing read — so the repair is not only 'store it' "
                + "but 'say so when we cannot'. A hit names its own cause in the line: no self "
                + "identity, no MLS group under the key, an unreadable record we refused to "
                + "overwrite, or a failed write. None of those is a peer behaviour, so this is "
                + "NEVER rather than SUSPICIOUS: a peer can decide not to send a token, but it "
                + "cannot make us fail to key one it did send. "
                + "NOT the same as 'no 0xF010 in the decrypted GroupInfo', which is the ordinary "
                + "engine line for a Welcome whose builder minted none and is not a marker. Nor the "
                + "same as 'continuity OVERSIZE', which is a PEER offering an absurd extension "
                + "length and is deliberately spelled differently so it cannot fire this one — a "
                + "NEVER marker that a peer can provoke stops meaning anything.",
                "MlsProviderTransport.noteContinuityToken"));
        m.add(new Marker("the chain has been orphaned", Severity.SUSPICIOUS,
                "a §10.3 report named an id that resolves to itself and has no outgoing "
                + "chat row. A resend's id is a bare UUID whose ONLY resolvable link is its ledger "
                + "row, so this is the signature of a chain whose rows were deleted under it — the "
                + "body still in the store, one link away, unreachable. Since the fix only a "
                + "DELIVERY forgets a chain, so a permanent failure can no longer cause it. "
                + "SUSPICIOUS rather than NEVER because a peer can also reach this line honestly, "
                + "by reporting an id we never sent (a foreign or non-originator id) — so read the "
                + "id: a bare UUID we DID resend is the bug, anything else is the peer.",
                "MlsProviderTransport.resendOriginal"));
        m.add(new Marker("is a GROUP wire id", Severity.SUSPICIOUS,
                "rework 7.4: a group FTD resolved its GROUP but had no stored body to resend, "
                + "because the send that produced it had no chat row to bind an id to. Expected "
                + "from the debug group-send hook; from a UI send it means a real group message "
                + "cannot be resent.",
                "MlsProviderTransport.resendOriginal"));
        // A BROAD NEEDLE, and it always was — "REFUSING" is emitted by six sites (the two inbound
        // refusals below, two negative-delivery-report refusals, and two receipt-signing refusals).
        // Kept because it is the cheapest single grep for "we declined to accept something", and
        // because captures already use it; the two markers after it are the precise ones. A hit on
        // this alone, with neither of those on the line, is one of the report/signing refusals.
        m.add(new Marker("REFUSING", Severity.SUSPICIOUS,
                "we declined to accept or emit something. Catch-all across six emitters — read the "
                + "line. The two INBOUND refusals have their own markers below; anything else is a "
                + "negative-delivery report or a receipt signature we would not put our name to.",
                "MlsProviderTransport (6 sites)"));
        // ---- the two inbound REFUSALS --------------------------------------------
        //
        // These fire on a message that DECRYPTED. That is what makes them worth their own markers:
        // a decrypt failure is a state problem and lands in §10 recovery, while a refusal is a
        // statement about the message itself and now puts NOTHING on the wire. Both used to take
        // `return null`, which RcsCallbackRouter reads as "did not decrypt" — so an older
        // build answered each of these with a self-heal AND a §7.7.2.2 FTD, and three in a
        // row REBUILT the conversation. Any capture showing them from an older build has to be read
        // as "the group repaired itself because it was lied to", not as a record of the refusal.
        m.add(new Marker(MlsInboundRefusal.MARKER_MISBINDING, Severity.SUSPICIOUS,
                "RCC.16 §7.5.3.1 fired: an inbound message's AAD named a different message than the "
                + "envelope carrying it, and we hold no stored result for the id it named. A peer "
                + "with a framing bug reaches this honestly, hence SUSPICIOUS — but the id in the "
                + "line is worth reading, because the same check with EVIDENCE reports as a REPLAY "
                + "instead (next marker) and the absence of that evidence proves nothing.",
                "MlsProviderTransport.decryptInbound / MlsInboundRefusal.misbindingLine"));
        m.add(new Marker(MlsInboundRefusal.MARKER_REPLAY, Severity.NEVER,
                "RCC.16 §7.5.3.1 fired AND the id the AAD names is one we have PROVABLY already "
                + "processed from this same peer — i.e. a ciphertext is being re-presented to us "
                + "under a new transport message id. This is the condition the binding check exists "
                + "for: without it the replayed bytes would be attributed to the new id and every "
                + "ledger keyed on message id (receipts, FTD correlation, resend) would point at the "
                + "wrong message. Keep the capture.",
                "MlsProviderTransport.decryptInbound / MlsInboundRefusal.misbindingLine"));
        // ---- discoveries, not failures -------------------------------------------------------
        //
        // Two markers below fire on something we have NEVER SEEN and would very much like to. The
        // scan is an assert-by-absence instrument, and these invert it: the absence is the finding
        // so far, and the moment either fires, a routine scan hands over a number we have been
        // unable to obtain any other way. SUSPICIOUS rather than NEVER, deliberately — neither is a
        // defect, and failing CI on a discovery would teach people to stop running the scan.
        m.add(new Marker("MLS-EXT-UNKNOWN", Severity.SUSPICIOUS,
                "a SERVER GroupInfo carried a GroupInfo extension type we cannot name. "
                + "This is very likely group_metadata_keys_requested, whose code point is the ONE "
                + "thing blocking the proactive maintenance refresh's add arm — it is not "
                + "recoverable from strings (extension types are integer constants in "
                + "code) and narrows only to the 0xF007-0xF00F region. RECORD THE NUMBER on "
                + "the line, then set debug.rcs.mls_metadata_keys_ext.",
                "MlsProviderTransport.reportUnknownServerExtTypes"));
        // THE NEEDLE IS THE EMITTER'S OWN CONSTANT, and it has to be. This marker read
        // "6.8 ARM 2/3 OBSERVED" while the code emitted "6.8 ARM 2 OBSERVED", so the scan built to
        // catch this discovery WITHOUT anyone watching a logcat could never have fired on it — and
        // nothing said so, because the catalogue's only test asserts that needles are non-empty.
        // Arm 2 was then seen 57 times on live traffic and recorded by a human reading the log.
        m.add(new Marker(MlsServerBundle.ARM_UNPROVEN_MARKER, Severity.SUSPICIOUS,
                "a ServerMlsRcsMessage arm whose TYPE assignment is by "
                + "ELIMINATION appeared on real traffic. ServerCommitBundle and MlsGroupInfo are "
                + "STRUCTURALLY IDENTICAL ({1:bytes,2:bytes,3:bytes,4:bytes}), so which is which "
                + "cannot be settled statically and a wrong assignment would not fail loudly — it "
                + "would mis-route commit bundles into the GroupInfo re-drive path and look like it "
                + "worked. The line carries the CONTENT-derived variant beside the arm number: the "
                + "two readings are independent, so WELCOME/COMMIT corroborates the elimination and "
                + "GROUP_INFO contradicts it. Size alone never settled this — arm-2 payloads span "
                + "143B to 9454B and the ~9.4KB band is exactly where a GroupInfo and a "
                + "Welcome-bearing bundle are indistinguishable by length.",
                "MlsServerBundle.Recognised.armEvidenceLine"));
        // ---- §11.3a resend receive ----
        //
        // THIS ONE IS A LITERAL AND CANNOT BE THE EMITTER'S CONSTANT, because the emitter is GONE —
        // that is the whole point. Until 2026-09-08 decryptInbound carried a guard that saw any
        // present resent-message component, logged this, and returned "did not decrypt", which
        // RcsCallbackRouter turns into onDecryptFailure(): a self-heal AND a §7.7.2.2 FTD, from
        // EVERY non-target member of the group. The comment above it promised "no FTD, no receipt,
        // no state change" and delivered all three, and it made the CORRECT selector twenty lines
        // below it dead code.
        //
        // So this needle means one thing: the device is running a build from before that fix, and
        // any group resend it saw produced a receipt storm and then a self-heal storm. Any capture
        // showing it must be re-read with that in mind rather than trusted.
        m.add(new Marker("We do not implement the ResentMessage unwrap yet", Severity.NEVER,
                "a pre-fix build. Its §11.3a path routes EVERY resend carrying a present "
                + "AAD component into §10 recovery plus an FTD, from every member of the group — "
                + "the receipt storm and self-heal storm the current code exists to prevent. Not a peer "
                + "problem: reflash before drawing any conclusion from this run.",
                "MlsProviderTransport.decryptInbound (REMOVED — this is the pre-fix string)"));
        m.add(new Marker(MlsResendReceive.MARKER_DISAGREEMENT, Severity.SUSPICIOUS,
                "the Original-Message-ID header and the AAD resent-message component "
                + "disagree about whether a message is a resend. Two INDEPENDENT statements about "
                + "one fact, so a disagreement is evidence rather than noise — and it points at the "
                + "specific unknown. Header says RESEND + component reads ABSENT means our inferred "
                + "component TAG byte (0x02) is wrong, which is the first open question and is "
                + "answerable from this one line instead of from a capture we cannot provoke.",
                "MlsProviderTransport.decryptInbound"));
        m.add(new Marker(MlsResendReceive.MARKER_MALFORMED, Severity.SUSPICIOUS,
                "a resent-message component that is PRESENT but decodes under no "
                + "candidate length-prefix width. On Google Messages this is the PARSE-error arm, which is "
                + "how the width question gets settled — a wrong TAG gives 'does not contain a "
                + "resent message' instead. Capture the trailing bytes on the neighbouring "
                + "MLS-AAD-DUMP line.",
                "MlsProviderTransport.decryptInbound"));
        // NOT A FAULT EITHER, and it is the FIRST of the two artifacts to become reachable: it
        // needs only a present-form component, not a MAC match.
        m.add(new Marker(MlsResendReceive.MARKER_SELECTOR_UNAVAILABLE, Severity.SUSPICIOUS,
                "A PRESENT-FORM §10.3 RESENT-MESSAGE COMPONENT WAS OBSERVED, and "
                + "the line carries its bytes. This is the artifact, not a defect. We dropped the "
                + "resend silently because we do not know where the 64-byte recipient-selector "
                + "field lives — that is OUR gap and the line claims nothing about "
                + "the sender. Keep the capture: the opaque's value settles whether it is the "
                + "original message id, and the trailing bytes on the neighbouring MLS-AAD-DUMP "
                + "line settle the TAG byte and the length-prefix width.",
                "MlsProviderTransport.decryptInbound"));
        // NOT A FAULT. Scanned for because it is the EVENT WE HAVE BEEN TRYING TO PRODUCE, and the
        // scan is the only thing that reads a log nobody is grepping. Four device probes and two
        // capture campaigns failed to obtain it; if it ever appears, the run that produced it is
        // the most valuable artifact we have on this question.
        m.add(new Marker(MlsResendReceive.MARKER_FOR_ME, Severity.SUSPICIOUS,
                "A RESEND'S SELECTOR MAC MATCHED ONE OF OUR CANDIDATE INPUTS. "
                + "This is the MEASUREMENT, not a defect — the line names WHICH candidate, and that "
                + "candidate IS the recipient-specific MAC input, the one input static analysis "
                + "cannot reach and the thing blocking correctly-targeted resend EMISSION. Keep "
                + "the capture.",
                "MlsProviderTransport.decryptInbound"));
        MARKERS = Collections.unmodifiableList(m);
    }

    /** The catalogue. Exposed so a report can list what was checked, not just what fired. */
    public static List<Marker> markers() { return MARKERS; }

    /**
     * Scan a log stream.
     *
     * @param lines the log, in order; {@code null} entries are skipped
     * @return every hit, in order. Empty is the expected result.
     */
    public static List<Violation> scan(final Iterable<String> lines) {
        final List<Violation> out = new ArrayList<>();
        if (lines == null) return out;
        int n = 0;
        for (final String line : lines) {
            n++;
            if (line == null) continue;
            for (final Marker m : MARKERS) {
                if (line.contains(m.needle)) out.add(new Violation(m, line, n));
            }
        }
        return out;
    }

    /** Whether anything at {@link Severity#NEVER} fired — the CI-failing condition. */
    public static boolean hasFatal(final List<Violation> violations) {
        if (violations == null) return false;
        for (final Violation v : violations) {
            if (v.marker.severity == Severity.NEVER) return true;
        }
        return false;
    }

    /**
     * A human-readable report.
     *
     * <p>States how many markers were CHECKED even when nothing fired, because "no output" and "the
     * scan did not run" look identical otherwise — which is the same confusion this class exists to
     * end.
     */
    public static String report(final List<Violation> violations) {
        final int checked = MARKERS.size();
        if (violations == null || violations.isEmpty()) {
            return "MLS invariant scan: CLEAN — " + checked + " never-expected conditions checked, "
                    + "none fired.";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("MLS invariant scan: ").append(violations.size()).append(" hit(s) across ")
          .append(checked).append(" checked conditions")
          .append(hasFatal(violations) ? " — INCLUDING FATAL:" : ":");
        for (final Violation v : violations) sb.append('\n').append(v);
        return sb.toString();
    }

    private MlsInvariantScan() {}
}

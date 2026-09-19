/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The "should never happen" scan: a pure function over log lines that reports whether anything in
 * the never-expected catalogue fired, so the same catalogue runs in host tests, CI and against a
 * capture. Matching is a plain substring test; {@link Marker#emittedBy} names the emitter so a
 * reworded line and its needle move together. See docs/testing.md.
 */
public final class MlsInvariantScan {

    /** How bad it is that this fired at all. */
    public enum Severity {
        /** Should be impossible; a CI run seeing this fails. */
        NEVER,
        /** Expected to be flat at zero in a healthy fleet, but a peer can provoke it. */
        SUSPICIOUS
    }

    /** One never-expected condition. */
    public static final class Marker {
        /** Substring to look for. */
        public final String needle;
        public final Severity severity;
        /** What it guards, in words. */
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
                "MlsCommitApplication.applyInboundControl"));
        m.add(new Marker("cannot handle action", Severity.NEVER,
                "rework 4.2: a flow was handed a host action it does not handle — rejected by name.",
                "MlsHostAction.rejectUnhandled"));
        m.add(new Marker("Failed requirement", Severity.NEVER,
                "rework 5.5: notification-driven work reached a transport entry point by a route "
                + "that should not exist.",
                "MlsSchedulingType.requireTransportAllowed"));
        m.add(new Marker("carries NO AuthenticatedData", Severity.SUSPICIOUS,
                "the §7.5.3.1 message-id binding check cannot assert anything and passes by "
                + "default. Most peers always bind one, so this is a non-binding peer or our "
                + "AAD plumbing regressed, which nothing else reports "
                + "because the check still passes.",
                "MlsInboundDecrypt.decryptInbound"));
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
                "MlsAdvancerElection.eraYieldExhausted"));
        m.add(new Marker("era advance FAILED", Severity.SUSPICIOUS,
                "the last rung of the recovery ladder did not hold. Not destructive (state is "
                + "rolled back), but ERA_GAP -> ERA_ADVANCE_REQUIRED is the arm real conversations "
                + "reach.",
                "MlsSelfHeal.selfHeal"));
        m.add(new Marker("did NOT take", Severity.SUSPICIOUS,
                "an era advance the server accepted without moving "
                + "the era. A create that reuses a contextId the "
                + "server already holds does not take. An era advance "
                + "re-creates the conversation and, by default, sends "
                + "the STORED contextId — so it asks for the one "
                + "thing that does not take, and nothing says so: "
                + "gRPC OK, no trailer, the era simply does not move. "
                + "The create response has one wire field, a two-long "
                + "header, so it carries no verdict, and the id in it "
                + "is a response id, not a group id. This holds for "
                + "1:1 and group conversations alike: a freshly "
                + "minted contextId is GRANTED and the stored one is "
                + "REFUSED, for the same group and the same target "
                + "era. Lever: debug.rcs.mls_advance_fresh_ctxid. It "
                + "is not a per-conversation rate limit, so do not "
                + "wait out a window. It stays SUSPICIOUS rather than "
                + "NEVER because the line is still what a real "
                + "problem looks like and because a deliberate "
                + "control arm produces it on purpose; the "
                + "discriminator is the contextId on the create above "
                + "it, not elapsed time. Our repair primitive IS the "
                + "era advance, so a run with several of these is a "
                + "conversation whose repair budget is being spent invisibly.",
                "MlsEraAdvance.eraAdvanceLocked"));
        m.add(new Marker("ACCEPTED AND DISCARDED", Severity.SUSPICIOUS,
                "a CreateMlsConversation the server accepted and did not apply. Expected when the "
                + "server already holds the conversation — some peers call that reason "
                + "'THE GROUP ALREADY EXISTS ON THE SERVER' and no era can help, because you "
                + "cannot be at the right era for a group you are asking to CREATE ("
                + "any era is discarded). The remedy is a re-Welcome from a member "
                + "that is current, NOT another create. Suspicious rather than never because a "
                + "create that hits this on a conversation the server should NOT hold is a real "
                + "finding.",
                "MlsGroupEstablish.establishGroup"));
        m.add(new Marker("RE-WELCOME REQUEST", Severity.SUSPICIOUS,
                "we asked to be re-admitted because we hold no state for a group the server does. "
                + "Correct behaviour and the only move available to a member with nothing to "
                + "advance from — but it means this device cannot participate until some CURRENT "
                + "member advances the era. If it repeats without a Welcome arriving, no member is "
                + "acting and the group needs one driven by hand.",
                "MlsRecoveryPolicy.requestReWelcome"));
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
                "MlsCommitApplication.applyInboundControl"));
        m.add(new Marker("is STRANDED", Severity.SUSPICIOUS,
                "consecutive future-epoch controls with none applying: we missed a commit and MLS "
                + "cannot skip one.",
                "MlsCommitApplication.applyInboundControl"));
        // Suspicious, not never: holding an unacknowledged commit reaches this by design, and it
        // has an automatic repair.
        m.add(new Marker("local state is poisoned", Severity.SUSPICIOUS,
                "we are AHEAD of the server — we hold a commit the server did not take. REACHABLE "
                + "BY DESIGN: a commit whose response was silent is held rather "
                + "than discarded, because being AHEAD is repairable (this arm rebuilds) and being "
                + "BEHIND an epoch we signed is not. Expect it to be RARE and to clear itself; a "
                + "conversation that stays here across rebuilds is the real fault.",
                "MlsConversationRebuild.reconcileAction"));
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
                "MlsResend.resendOriginal"));
        m.add(new Marker("LEGACY 1:1 WIRE id", Severity.SUSPICIOUS,
                "a peer reported an id from the pre-unification namespace, so it was "
                + "never a row id and the message cannot be recovered. Expected only from messages "
                + "sent before the fix; a NEW one means a send path is still synthesising its own "
                + "wire id — sendFramedToGroup was exactly that until 7.4.",
                "MlsResend.resendOriginal"));
        m.add(new Marker("could not state WHO SIGNED it", Severity.SUSPICIOUS,
                "the sender-identity binding cannot assert anything, so an inbound "
                + "message is being attributed to whoever the TRANSPORT ENVELOPE named — a field "
                + "the sender chooses. Same shape as the AAD check that fail-opened for its whole "
                + "life, and the same cost if it goes unnoticed.",
                "MlsInboundDecrypt.decryptInbound"));
        // Needles use the emitter's own constant where one exists, so they cannot drift apart.
        m.add(new Marker(MlsInboundRefusal.MARKER_IMPERSONATION, Severity.NEVER,
                "a group member sent a message under another member's name. The decrypt "
                + "SUCCEEDED, so this is not a crypto failure and not noise — it is an authenticated "
                + "member of the group impersonating a peer. The response is a "
                + "SILENT REFUSAL (no FTD, no self-heal, no rebuild credit); before it, each one of "
                + "these cost the group a repair and told the IMPERSONATED peer we could not decrypt "
                + "a message they never sent, so a run of them on an older build is an amplified "
                + "attack rather than a record of one.",
                "MlsInboundDecrypt.decryptInbound / MlsInboundRefusal.impersonationLine"));
        m.add(new Marker("WELCOME-EXT probe", Severity.NEVER,
                "this probe is DELETED and must never come back. It "
                + "looped 0xF010..0xF018 through groupExt(), which reads "
                + "the GROUPCONTEXT extension list, and on finding nothing "
                + "logged that it was 'the only read that could have found' "
                + "a continuity token — which lives in GroupInfo.extensions "
                + "and is invisible to it by construction, so its answer is "
                + "wrong whenever a peer's Welcome carries a 33-byte "
                + "0xF010. If this string appears, either the probe was "
                + "reinstated or the log is from a build that predates its "
                + "removal — check which before believing anything it says "
                + "about 0xF010. The read that works is WELCOME-GI-EXT.",
                "MlsWelcomeAdmission.joinFromWelcome (removed)"));
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
                "MlsContinuityToken.noteContinuityToken"));
        m.add(new Marker("the chain has been orphaned", Severity.SUSPICIOUS,
                "a §10.3 report named an id that resolves to itself and has no outgoing "
                + "chat row. A resend's id is a bare UUID whose ONLY resolvable link is its ledger "
                + "row, so this is the signature of a chain whose rows were deleted under it — the "
                + "body still in the store, one link away, unreachable. Since the fix only a "
                + "DELIVERY forgets a chain, so a permanent failure can no longer cause it. "
                + "SUSPICIOUS rather than NEVER because a peer can also reach this line honestly, "
                + "by reporting an id we never sent (a foreign or non-originator id) — so read the "
                + "id: a bare UUID we DID resend is the bug, anything else is the peer.",
                "MlsResend.resendOriginal"));
        m.add(new Marker("is a GROUP wire id", Severity.SUSPICIOUS,
                "rework 7.4: a group FTD resolved its GROUP but had no stored body to resend, "
                + "because the send that produced it had no chat row to bind an id to. Expected "
                + "from the debug group-send hook; from a UI send it means a real group message "
                + "cannot be resent.",
                "MlsResend.resendOriginal"));
        // Broad: six sites emit "REFUSING". The two inbound refusals have precise markers below.
        m.add(new Marker("REFUSING", Severity.SUSPICIOUS,
                "we declined to accept or emit something. Catch-all across six emitters — read the "
                + "line. The two INBOUND refusals have their own markers below; anything else is a "
                + "negative-delivery report or a receipt signature we would not put our name to.",
                "MlsProviderTransport (6 sites)"));
        // The two inbound refusals fire on a message that decrypted and put nothing on the wire.
        m.add(new Marker(MlsInboundRefusal.MARKER_MISBINDING, Severity.SUSPICIOUS,
                "RCC.16 §7.5.3.1 fired: an inbound message's AAD named a different message than the "
                + "envelope carrying it, and we hold no stored result for the id it named. A peer "
                + "with a framing bug reaches this honestly, hence SUSPICIOUS — but the id in the "
                + "line is worth reading, because the same check with EVIDENCE reports as a REPLAY "
                + "instead (next marker) and the absence of that evidence proves nothing.",
                "MlsInboundDecrypt.decryptInbound / MlsInboundRefusal.misbindingLine"));
        m.add(new Marker(MlsInboundRefusal.MARKER_REPLAY, Severity.NEVER,
                "RCC.16 §7.5.3.1 fired AND the id the AAD names is one we have PROVABLY already "
                + "processed from this same peer — i.e. a ciphertext is being re-presented to us "
                + "under a new transport message id. This is the condition the binding check exists "
                + "for: without it the replayed bytes would be attributed to the new id and every "
                + "ledger keyed on message id (receipts, FTD correlation, resend) would point at the "
                + "wrong message. Keep the capture.",
                "MlsInboundDecrypt.decryptInbound / MlsInboundRefusal.misbindingLine"));
        // Discoveries, not failures: the markers below record something not yet observed.
        // Suspicious rather than never, so a discovery does not fail CI.
        m.add(new Marker("MLS-EXT-UNKNOWN", Severity.SUSPICIOUS,
                "a SERVER GroupInfo carried a GroupInfo extension type we cannot name. "
                + "This is very likely group_metadata_keys_requested, whose code point is the ONE "
                + "thing blocking the proactive maintenance refresh's add arm — it is not "
                + "recoverable from strings (extension types are integer constants in "
                + "code) and narrows only to the 0xF007-0xF00F region. RECORD THE NUMBER on "
                + "the line, then set debug.rcs.mls_metadata_keys_ext on a debug build.",
                "MlsTransportDiagnostics.reportUnknownServerExtTypes"));
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
        // A literal, since the emitter was removed: it identifies an older build whose resend guard
        // turned every present resend component into recovery plus an FTD.
        m.add(new Marker("We do not implement the ResentMessage unwrap yet", Severity.NEVER,
                "a pre-fix build. Its §11.3a path routes EVERY resend carrying a present "
                + "AAD component into §10 recovery plus an FTD, from every member of the group — "
                + "the receipt storm and self-heal storm the current code exists to prevent. Not a peer "
                + "problem: reflash before drawing any conclusion from this run.",
                "MlsInboundDecrypt.decryptInbound (REMOVED — this is the pre-fix string)"));
        m.add(new Marker(MlsResendReceive.MARKER_DISAGREEMENT, Severity.SUSPICIOUS,
                "the Original-Message-ID header and the AAD resent-message component "
                + "disagree about whether a message is a resend. Two INDEPENDENT statements about "
                + "one fact, so a disagreement is evidence rather than noise — and it points at the "
                + "specific unknown. Header says RESEND + component reads ABSENT means our inferred "
                + "component TAG byte (0x02) is wrong, which is the first open question and is "
                + "answerable from this one line instead of from a capture we cannot provoke.",
                "MlsInboundDecrypt.decryptInbound"));
        m.add(new Marker(MlsResendReceive.MARKER_MALFORMED, Severity.SUSPICIOUS,
                "a resent-message component that is PRESENT but decodes under no "
                + "candidate length-prefix width. On some peers this is the PARSE-error arm, which is "
                + "how the width question gets settled — a wrong TAG gives 'does not contain a "
                + "resent message' instead. Capture the trailing bytes on the neighbouring "
                + "MLS-AAD-DUMP line.",
                "MlsInboundDecrypt.decryptInbound"));
        m.add(new Marker(MlsResendReceive.MARKER_SELECTOR_UNAVAILABLE, Severity.SUSPICIOUS,
                "A PRESENT-FORM §10.3 RESENT-MESSAGE COMPONENT WAS OBSERVED, and "
                + "the line carries its bytes. This is the artifact, not a defect. We dropped the "
                + "resend silently because we do not know where the 64-byte recipient-selector "
                + "field lives — that is OUR gap and the line claims nothing about "
                + "the sender. Keep the capture: the opaque's value settles whether it is the "
                + "original message id, and the trailing bytes on the neighbouring MLS-AAD-DUMP "
                + "line settle the TAG byte and the length-prefix width.",
                "MlsInboundDecrypt.decryptInbound"));
        m.add(new Marker(MlsResendReceive.MARKER_FOR_ME, Severity.SUSPICIOUS,
                "A RESEND'S SELECTOR MAC MATCHED ONE OF OUR CANDIDATE INPUTS. "
                + "This is the MEASUREMENT, not a defect — the line names WHICH candidate, and that "
                + "candidate IS the recipient-specific MAC input, the one input static analysis "
                + "cannot reach and the thing blocking correctly-targeted resend EMISSION. Keep "
                + "the capture.",
                "MlsInboundDecrypt.decryptInbound"));
        MARKERS = Collections.unmodifiableList(m);
    }

    /** The catalogue, so a report can list what was checked. */
    public static List<Marker> markers() { return MARKERS; }

    /**
     * Scans a log stream.
     *
     * @param lines the log, in order; {@code null} entries are skipped
     * @return every hit, in order; empty is the expected result
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

    /** Whether anything at {@link Severity#NEVER} fired, the CI-failing condition. */
    public static boolean hasFatal(final List<Violation> violations) {
        if (violations == null) return false;
        for (final Violation v : violations) {
            if (v.marker.severity == Severity.NEVER) return true;
        }
        return false;
    }

    /**
     * A human-readable report that states how many markers were checked even when none fired, so a
     * clean scan is distinguishable from one that did not run.
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

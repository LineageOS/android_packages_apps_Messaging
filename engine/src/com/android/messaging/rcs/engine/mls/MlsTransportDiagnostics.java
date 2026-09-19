/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import java.util.Map;
import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.AnchorProbe;
import com.android.messaging.rcs.log.LogMask;
/**
 * The transport's instruments: probes, dumps and deliberate test sends driven from a debug lever
 * and read in a log. Nothing here decides anything the production path acts on.
 */
public final class MlsTransportDiagnostics {
    private MlsTransportDiagnostics() {}

    /**
     * Log the three candidate homes for "the original message id" side by side: the AAD's
     * {@code message_id}, the CPIM {@code Original-Message-ID} header, and the resent component's
     * opaque. On a real resend an inequality shows which layer carries the original. Capture only.
     */
    public static void logIdCandidates(final MlsLogSink log, final MlsResendReceive.Outcome resend,
            final String originalMessageId, final byte[] inboundAad) {
        final String line = MlsResendReceive.idCandidateLine(
                resend, originalMessageId, MlsAppMessage.aadMessageId(inboundAad));
        if (line != null) log.i("MlsTransportDiagnostics: " + line);
    }

    /**
     * Read-only divergence probe: ask the server for its epoch anchor and log it beside ours. Equal
     * era and epoch can still be different groups; only the epoch authenticator answers that.
     */
    public static AnchorProbe probeAnchor(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return AnchorProbe.NO_LOCAL_STATE;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return AnchorProbe.NO_LOCAL_STATE;
        // Read engine state under the lock; make the RPC without it.
        final int era;
        final long epoch;
        final byte[] auth;
        shell.lock(key);
        try {
            final Group g = shell.getGroup(key);
            if (g == null || g.groupId == null) return AnchorProbe.NO_LOCAL_STATE;
            final byte[] eraEpoch = shell.session().eraEpoch(g.groupId);
            era = MlsAppMessage.eraFrom(eraEpoch);
            epoch = MlsAppMessage.epochFrom(eraEpoch);
            auth = shell.session().epochAuth(g.groupId);
        } finally { shell.unlock(key); }
        // Epoch and authenticator on one line: an earlier epoch with a different authenticator is
        // behind on the same chain (commits can still land); an equal epoch with a different
        // authenticator is a fork (no commit will apply). The two need opposite remedies.
        final StringBuilder a = new StringBuilder();
        if (auth == null) {
            a.append("none");
        } else {
            for (int i = 0; i < Math.min(8, auth.length); i++) {
                a.append(String.format("%02x", auth[i]));
            }
            a.append('/').append(auth.length).append('B');
        }
        log.i("MlsTransportDiagnostics: anchor probe " + rcsGroupId + " ours=era=" + era
                + " epoch=" + epoch + " auth=" + a);
        // Its own fetch ration, outside the shared ceiling: starving the reader that makes health
        // legible escalates recovery to a heavier remedy than needed.
        final Look<byte[]> look = shell.lookMissedCommits(MlsFetchLedger.Caller.HEALTH_PROBE, key,
                peerE164, rcsGroupId, era, auth);
        if (look.refused()) {
            // Not a false: we never asked the server, which is a fact about our budget, not the
            // group.
            return AnchorProbe.REFUSED_BY_LEDGER;
        }
        return AnchorProbe.PROBED;
    }

    /**
     * Test seam: seed the usage counters so the next send crosses the rekey threshold, exercising
     * the production increment, comparison and rekey call. Deliberately not
     * {@code debug.rcs.mls_rekey_limit}, which must not trigger commits.
     */
    public static boolean seedUsageCounter(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return false;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return false;
        shell.lock(key);
        try {
        final Group g = shell.getGroup(key);
        if (g == null) return false;
        // The rotation counter is what the threshold reads; seeding only sendsThisEpoch would be
        // inert.
        g.sendsSinceLeafRotation = MlsRekeyPolicy.seedForImminentRotation();
        g.sendsThisEpoch =
                Math.max(g.sendsThisEpoch, MlsRekeyPolicy.seedForImminentRotation());
        shell.putGroup(key, g);
        log.i("MlsTransportDiagnostics: usage counter seeded to " + g.sendsSinceLeafRotation
                + " sends-since-leaf-rotation on " + MlsConversationKey.forLog(key)
                + " — the next send should trigger a rekey");
        return true;
        } finally { shell.unlock(key); }
    }

    /**
     * Drive one wire blob through the repeated-result engine path and demux it, crossing Java, JNI,
     * Rust, {@link MlsEngineResult#decodeList} and {@link MlsResultBundle}. Garbage input is fine:
     * the engine answers malformed and the whole chain still runs.
     *
     * @return a one-line summary for the log; never throws
     */
    public static String probeProcessResults(final MlsShellPort shell, final String rcsGroupId,
            final String peerE164, final byte[] wire, final String contextId) {
        if (!shell.ensureSession()) return "no session";
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        final byte[] gid = (g == null || g.groupId == null) ? new byte[0] : g.groupId;
        try {
            final java.util.List<MlsEngineResult> raw =
                    shell.session().processResults(gid, wire, contextId);
            final java.util.List<MlsResultBundle.Result> results = new java.util.ArrayList<>();
            for (final MlsEngineResult r : raw) {
                results.add(new MlsResultBundle.Result(r.contextId,
                        MlsHostAction.of(MlsProcStatus.toActionKind(r.status),
                                "engine status " + MlsProcStatus.nameOf(r.status)),
                        r.groupId()));
            }
            final java.util.Map<String, java.util.List<MlsHostAction>> byContext =
                    MlsResultBundle.demux(results);
            final java.util.List<MlsHostAction> ours =
                    MlsResultBundle.forContext(byContext, contextId);
            final MlsHostAction governing =
                    MlsResultBundle.governing(MlsResultBundle.applyPoisonRule(ours));
            return "raw=" + raw.size() + " contexts=" + byContext.keySet()
                    + " ours=" + ours.size()
                    + " others=" + MlsResultBundle.otherContexts(byContext, contextId).keySet()
                    + " governing=" + (governing == null ? "none"
                            : governing.kind + "/" + governing.status);
        } catch (final RuntimeException guardFired) {
            // The demux guards throw by design; report which one fired.
            return "GUARD: " + guardFired.getMessage();
        }
    }

    /**
     * Every member's certificate window for one conversation, the expired count, the floor report
     * and our own leaf status. Reads only; issues no refresh. Unreadable members are counted apart
     * from expired ones so an unparseable leaf is never a refresh trigger.
     */
    public static String dumpMemberValidity(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return "no session";
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        final Group g = key == null ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) return "no group for " + MlsConversationKey.forLog(key);
        if (shell.session() == null) return "not the OpenMLS path";
        final java.util.Map<Integer, long[]> v =
                shell.session().memberValidity(g.groupId);
        final long now = System.currentTimeMillis() / 1000L;
        int expired = 0;
        int unreadable = 0;
        final StringBuilder sb = new StringBuilder();
        for (final java.util.Map.Entry<Integer, long[]> e : v.entrySet()) {
            final long nb = e.getValue()[0];
            final long na = e.getValue()[1];
            final boolean bad = nb == 0 && na == 0;
            if (bad) unreadable++;
            else if (na <= now) expired++;
            sb.append(" leaf=").append(e.getKey());
            if (bad) {
                sb.append(" UNREADABLE");
            } else {
                // The group-stored leaf's window: not the certificate the device holds nor the leaf
                // its published pool serves, which can all differ.
                sb.append(" notBefore=").append(nb).append(" notAfter=").append(na)
                  .append(" groupLeafDays=").append((na - now) / 86400L);
            }
        }
        // "Expired" misses members inside the 30-day floor and does not identify our leaf, so the
        // floor report and self-leaf status are appended.
        final MlsCredentialFloor.Report floor =
                MlsServerBundle.rosterFloorReport(cfg, shell.session(), log, g);
        final MlsSelfLeafStatus self = shell.session().selfLeafStatus(g.groupId);
        sb.append(" | floor=").append(floor == null ? "UNREADABLE" : floor.toString())
          .append(" | selfLeaf=").append(self == null ? "UNREADABLE" : self.toString());
        if (self != null) {
            sb.append(" groupRemainingDays=").append(self.groupRemainingDays(now))
              .append(" clientRemainingDays=").append(self.clientRemainingDays(now));
        }
        return "members=" + v.size() + " expired=" + expired + " unreadable=" + unreadable + sb;
    }

    /**
     * Print the advancer election's inputs and verdict without changing anything or fetching. An
     * empty presence ledger (every member UNKNOWN, base budget) and a member never heard from
     * (NEVER_HEARD, reduced budget) look alike until looks have been spent; this tells them apart
     * beforehand.
     */
    public static String dumpAdvancerElection(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return "no session";
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        if (key == null) return "no conversation for rcsgid=" + rcsGroupId + " peer="
                + LogMask.number(peerE164);
        final Group g = shell.getGroup(key);
        final java.util.List<String> roster = (g == null) ? null
                : MlsServerBundle.ourRoster(shell, log, key, g);
        if (roster == null || roster.isEmpty()) {
            return MlsConversationKey.forLog(key)
                    + ": no stored roster (membershipHistory has no row for our current era) — "
                    + "the election cannot be evaluated without one";
        }
        final ConvState s = shell.conv(key);
        final java.util.Map<String, Long> heard;
        final EraYield held;
        final long seq;
        synchronized (s) {
            heard = new java.util.LinkedHashMap<>(s.heardAtSeq);
            held = s.eraYield;
            seq = s.heardSeq;
        }
        final long startedAt = (held == null) ? seq : held.heardSeqAtStart;
        final java.util.List<String> electorate = new java.util.ArrayList<>(roster);
        if (!electorate.contains(shell.selfE164())) electorate.add(shell.selfE164());
        final MlsAdvancerElection.Decision d = MlsAdvancerElection.decide(
                shell.selfE164(), electorate, heard, startedAt,
                MlsAdvancerElection.eraYieldMaxObservations(cfg));
        final StringBuilder sb = new StringBuilder();
        sb.append(MlsConversationKey.forLog(key)).append(": self=")
          .append(LogMask.number(shell.selfE164()))
          .append(" order=").append(LogMask.numbers(MlsAdvancerElection.order(electorate)))
          .append(" heardSeq=").append(seq)
          .append(" ledger=").append(heard.isEmpty() ? "EMPTY (nothing inbound yet — every member "
                  + "reads UNKNOWN, so the budget is the unchanged base)" : heard.toString())
          .append(" yield=").append(held == null ? "none"
                  : "at " + held.at + " looks=" + held.observations
                          + " startedAtSeq=" + held.heardSeqAtStart)
          .append(" → ").append(d);
        for (int i = 0; i < d.rank && i < electorate.size(); i++) {
            final String m = MlsAdvancerElection.order(electorate).get(i);
            sb.append("\n    ahead[").append(i).append("] ").append(LogMask.number(m))
              .append(" = ")
              .append(MlsAdvancerElection.classify(m, heard, startedAt));
        }
        return sb.toString();
    }

    /**
     * Debug only ({@code --ez continuity}): inject a continuity token through the production write
     * path, or with no token report what we hold and drain the engine's Welcome hand-off (which
     * exercises the JNI binding). Writes local state only; sends nothing.
     */
    public static String debugInjectContinuityToken(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final byte[] token) {
        // ensureSession first and outside any lock: it populates selfE164() (a debug broadcast can
        // arrive before any MLS operation) and it does transport I/O.
        if (!shell.ensureSession()) return "continuity: no MLS session — nothing to read or write";
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (token == null || token.length == 0) {
            final byte[] held = shell.continuityTokenFor(rcsGroupId, peerE164);
            String engine = "n/a (no group)";
            final Group g = (key == null) ? null : shell.getGroup(key);
            if (g != null && g.groupId != null && shell.session() != null) {
                try {
                    engine = shell.session().takeWelcomeContinuityToken(g.groupId).length
                            + "B pending in the engine";
                } catch (final Throwable t) {
                    engine = "ENGINE CALL FAILED: " + t;
                }
            }
            return "continuity " + MlsConversationKey.forLog(key) + ": " + (held.length == 0
                    ? "we hold NO token" : "we hold a " + held.length + "B token")
                    + "; welcome hand-off = " + engine;
        }
        final boolean wrote = MlsContinuityToken.noteContinuityToken(shell, log, key, token,
                "--ez continuity (debug inject)");
        final byte[] back = shell.continuityTokenFor(rcsGroupId, peerE164);
        return "continuity " + MlsConversationKey.forLog(key) + ": wrote=" + wrote + " readback="
                + back.length + "B match=" + java.util.Arrays.equals(token, back);
    }

    // The proactive maintenance pass is separate from recovery: it computes a membership delta and
    // never revives or creates, so no era advance is reachable through it. Recovery is gated on
    // state and health instead; gating it on a membership delta would disable it, since a diverged
    // conversation usually has a correct roster.

    /**
     * Every GroupInfo extension type we can name, so {@code reportUnknownServerExtTypes} can report
     * the rest. The {@code group_metadata_keys_requested} code point is still unknown and must be
     * read off a GroupInfo that carries it.
     */
    public static final int[] KNOWN_GROUP_INFO_EXT_TYPES = {
        0x0001, 0x0002, 0x0003, 0x000A,
        0xF001, 0xF002, 0xF003, 0xF004, 0xF005, 0xF006, 0xF007,
        0xF010, 0xF011, 0xF012, 0xE000,
    };

    public static boolean isKnownExtType(final int t) {
        for (final int k : KNOWN_GROUP_INFO_EXT_TYPES) if (k == t) return true;
        return false;
    }

    /** The RCC.16 era GroupContext extension; bare, not varint-framed. */
    public static final int ERA_EXT_TYPE = 0xF001;

    /**
     * Log which RCC.16 GroupContext extensions a group carries, ours and the server's, plus the
     * continuity commitment, external-join viability and the era value on both sides.
     */
    public static void dumpGroupExtensions(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return;
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) {
            log.w("MlsTransportDiagnostics: group-ext dump — no group for "
                    + LogMask.number(peerE164));
            return;
        }
        // RCC.16 §7.11 types plus the headroom: era, end_mls, icon/subject keys and commitments,
        // last_resort, 0xF007, and the continuity pair 0xF010/0xF011.
        final int[] types = {
            0xF001, 0xF002, 0xF003, 0xF004, 0xF005, 0xF006, 0xF007,
            0xF010, 0xF011, 0xF012, 0x000A, 0xE000,
        };
        final StringBuilder ours = new StringBuilder();
        for (final int t : types) {
            final byte[] v = shell.session().groupExt(g.groupId, t);
            if (v != null && v.length > 0) {
                ours.append(String.format(" 0x%04X=%dB", t, v.length));
            }
        }
        // Ours is the decoded value; the server's is raw extension_data. A varint-framed type reads
        // one byte larger on the server side, a bare one (0xF001, 0xF002, 0xF007) equal. Framed
        // types matching means the inner varint was lost.
        log.i("GROUP-EXT " + MlsConversationKey.forLog(key) + " OURS (decoded value):"
                + (ours.length() == 0 ? " (none)" : ours));
        // A debug arm, exempt from the fetch ledger; describeExemption logs that and what has been
        // spent.
        final Look<byte[]> dump =
                shell.fetchServerPack(MlsFetchLedger.Caller.DEBUG_DUMP, rcsGroupId, peerE164, g);
        final byte[] packed = dump.orNull();
        final byte[] serverGi = (packed == null) ? null : MlsWireScan.firstPacked(packed, 0);
        if (serverGi == null || serverGi.length == 0) {
            log.w("GROUP-EXT " + MlsConversationKey.forLog(key) + " SERVER: no GroupInfo returned");
            return;
        }
        final byte[] srvTypes = shell.session().groupInfoExtTypes(serverGi);
        final StringBuilder srv = new StringBuilder();
        for (int i = 0; i + 3 < srvTypes.length; i += 4) {
            final int t = ((srvTypes[i] & 0xFF) << 8) | (srvTypes[i + 1] & 0xFF);
            final int n = ((srvTypes[i + 2] & 0xFF) << 8) | (srvTypes[i + 3] & 0xFF);
            srv.append(String.format(" 0x%04X=%dB", t, n));
        }
        log.i("GROUP-EXT " + MlsConversationKey.forLog(key)
                + " SERVER GroupInfo (raw extension_data) " + serverGi.length
                + "B:" + (srv.length() == 0 ? " (none parsed)" : srv)
                // RCC.16 §7.11.12: 0xF010 is the token (Welcome-only, never expected here), 0xF011
                // its commitment (required in every GroupInfo), so only the commitment's absence is
                // a negative.
                + "  [0xF010 = the continuity token (Welcome-only; NOT expected in a server "
                + "GroupInfo); 0xF011 = its commitment, which v4.0 requires in EVERY GroupInfo — "
                + "that one being absent is the real negative]");
        // Continuity is a client feature: an absent commitment means no client in this group is
        // doing continuity, not that the server lacks support. The token half cannot be seen here.
        final byte[] srvCommitment =
                shell.session().groupInfoContinuity(serverGi, MlsContinuityCodePoints.COMMITMENT);
        log.i("GROUP-EXT " + MlsConversationKey.forLog(key)
                + " CONTINUITY — server GroupInfo 0xF011(commitment)="
                + (srvCommitment == null || srvCommitment.length == 0
                        ? "ABSENT -> NO CLIENT in this group is doing continuity (v4.0 requires "
                          + "the commitment in EVERY GroupInfo a client builds); do not mint a token "
                          + "and do not act on the §8.3.1.2 downgrade. NB this reaches only the "
                          + "COMMITMENT half — 0xF010 is Welcome-only and cannot be seen from here"
                        : srvCommitment.length + "B PRESENT -> a client in this group IS doing "
                          + "continuity; the gate is satisfied and minting/validation may "
                          + "be enabled"));
        // RFC 9420 §12.4.3.2: an external commit needs external_pub (GroupInfo extension 0x0004).
        // It is a GroupInfo extension, so the GroupContext dump above can never show it; read it
        // from the GroupInfo's own list. groupInfoContinuity is used here as a generic
        // GroupInfo-extension reader.
        final byte[] srvExternalPub = shell.session().groupInfoContinuity(serverGi, 0x0004);
        final byte[] srvExtSenders  = shell.session().groupInfoContinuity(serverGi, 0x0005);
        log.i("GROUP-EXT " + MlsConversationKey.forLog(key)
                + " EXTERNAL-JOIN VIABILITY — GroupInfo(own exts):"
                + " external_pub(0x0004)=" + (srvExternalPub == null || srvExternalPub.length == 0
                        ? "ABSENT" : srvExternalPub.length + "B")
                + " external_senders(0x0005)=" + (srvExtSenders == null || srvExtSenders.length == 0
                        ? "ABSENT" : srvExtSenders.length + "B")
                + " -> external commit is " + (srvExternalPub == null || srvExternalPub.length == 0
                        ? "IMPOSSIBLE from this GroupInfo (expect MissingExternalPubExtension); it "
                          + "is a state SUMMARY for the divergence check, not a joinable GroupInfo"
                        : "VIABLE — feed {GroupInfo, ratchet_tree} to externalJoin"));
        // The era's value on both sides, signed and hex (a wrong-endianness read looks plausible).
        // The create response carries no verdict, so this is how to tell whether the server
        // recorded an advance.
        log.i("GROUP-EXT " + MlsConversationKey.forLog(key) + " ERA(0xF001) value — OURS="
                + MlsAdvancerElection.eraExtOf(shell.session().groupExt(
                        g.groupId, ERA_EXT_TYPE))
                + " SERVER="
                + MlsAdvancerElection.eraExtOf(shell.session().groupInfoExt(serverGi, ERA_EXT_TYPE))
                + "  (local record says era=" + g.era + ")");
    }

    /**
     * Log any extension type in a server GroupInfo that we cannot name. Runs on every maintenance
     * pass to capture the {@code group_metadata_keys_requested} code point; types are decoded, not
     * scanned.
     *
     * @return the unknown types found
     */
    public static java.util.List<Integer> reportUnknownServerExtTypes(final MlsSession session,
            final MlsLogSink log, final String key, final byte[] serverGroupInfo) {
        final java.util.List<Integer> unknown = new java.util.ArrayList<>();
        if (serverGroupInfo == null || serverGroupInfo.length == 0) return unknown;
        final byte[] types = session.groupInfoExtTypes(serverGroupInfo);
        if (types == null) return unknown;
        for (int i = 0; i + 3 < types.length; i += 4) {
            final int t = ((types[i] & 0xFF) << 8) | (types[i + 1] & 0xFF);
            final int len = ((types[i + 2] & 0xFF) << 8) | (types[i + 3] & 0xFF);
            if (MlsTransportDiagnostics.isKnownExtType(t)) continue;
            unknown.add(t);
            log.w("MLS-EXT-UNKNOWN " + MlsConversationKey.forLog(key)
                    + " the server GroupInfo carries extension type "
                    + String.format("0x%04X", t) + " (" + len + "B) which we cannot name. If this "
                    + "group has metadata (a subject or icon) and members were recently added, this "
                    + "is very likely group_metadata_keys_requested — the code point the metadata "
                    + "path is blocked on. Record it, then set " + MlsConfig.KEY_METADATA_KEYS_EXT
                    + " on a debug build.");
        }
        return unknown;
    }

    /**
     * Every leaf's certificate window in the server's copy of the tree: the instrument for judging
     * a credential repair, since the local group can differ from the server's in either direction.
     * Reports identities first (which leaves, whether ours is there, who signed the current
     * GroupInfo), then windows, then our leaf against the certificate we hold. A refused fetch, an
     * unparseable tree and a missing leaf are reported as different results. Charged as the exempt
     * {@link MlsFetchLedger.Caller#DEBUG_DUMP}.
     */
    public static String dumpServerValidity(final MlsConfig cfg, final MlsShellPort shell,
            final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return "no session";
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return "no conversation key for " + rcsGroupId + "/" + peerE164;
        if (shell.session() == null) return "not the OpenMLS path";
        final Group g = shell.getGroup(key);
        // Without a local group there is no anchor for the fetch, and that is the state this probe
        // is most wanted in; say so and name the remedy (a re-Welcome from a member).
        if (g == null || g.groupId == null) {
            return "NO LOCAL GROUP for " + MlsConversationKey.forLog(key)
                    + " — nothing to anchor a GetMlsGroupInfo at, so the "
                    + "server's tree cannot be read from this device. This is a STATE, not a "
                    + "failure of the fetch: we hold no group while the server may well count us a "
                    + "member. The remedy is a re-Welcome from "
                    + "a member; run this probe from a device that still holds the group to see "
                    + "whether our leaf is in the server's tree.";
        }

        final Look<byte[]> look =
                shell.fetchServerPack(MlsFetchLedger.Caller.DEBUG_DUMP, rcsGroupId, peerE164, g);
        if (look.refused()) {
            // Nothing was asked of the server; this says nothing about the tree.
            return "FETCH REFUSED by the ledger (" + look.why() + ") — NOTHING was asked of the "
                    + "server, so this is not evidence about the roster";
        }
        final byte[] packed = look.orNull();
        if (packed == null) {
            return "FETCH FAILED — the server returned nothing. NOT 'the group is empty'";
        }
        final byte[] tree = MlsWireScan.firstPacked(packed, 1);
        int rosterCount = 0;
        while (MlsWireScan.firstPacked(packed, 2 + rosterCount) != null) rosterCount++;
        final StringBuilder sb = new StringBuilder();
        sb.append("g=").append(MlsTrace.groupId(g == null ? null : g.groupId))
          .append(" rcsGid=").append(rcsGroupId == null ? "" : rcsGroupId)
          .append(" tree=").append(tree == null ? "ABSENT" : tree.length + "B")
          .append(" rcsRoster=").append(rosterCount);
        if (tree == null) {
            // The pack carried no tree: not an empty roster.
            return sb + " | NO RATCHET TREE IN THE PACK — the fetch worked and slot 1 was empty. "
                    + "That is a different fact from an empty roster and must not be read as one.";
        }
        final java.util.List<MlsTreeLeaf> leaves =
                shell.session().treeMemberValidity(tree);
        if (leaves.isEmpty()) {
            // Bytes arrived and the engine could not read them.
            return sb + " | TREE WOULD NOT PARSE (" + tree.length + "B) — we could not look. NOT "
                    + "'the server holds no members'.";
        }

        // Who committed the server's current epoch (RFC 9420 GroupInfo.signer): on a fork, the most
        // attributive datum. Certificate windows identify a certificate, not a member, and
        // batch-minted lines share them.
        final byte[] serverGi = MlsWireScan.firstPacked(packed, 0);
        final long[] signed = shell.session().groupInfoSigner(serverGi);
        final int signerIdx = (signed == null) ? -1 : (int) signed[0];
        final long giEpoch = (signed == null) ? -1L : signed[1];
        // The fetch is anchored at our own epoch, and at that epoch the returned GroupInfo may be
        // our own anchor; compare before attributing.
        final long ourEpoch = MlsAppMessage.epochFrom(shell.session().eraEpoch(g.groupId));

        // Identity first.
        final long now = System.currentTimeMillis() / 1000L;
        final StringBuilder who = new StringBuilder();
        boolean ourLeafPresent = false;
        int ourLeafIndex = -1;
        long groupNotAfterForUs = 0L;
        for (final MlsTreeLeaf l : leaves) {
            who.append("\n    leaf=").append(l.leafIndex).append(' ')
               .append(l.msisdn.isEmpty() ? "(no readable SAN)" : LogMask.number(l.msisdn));
            if (l.isIdentity(shell.selfE164Raw())) {
                ourLeafPresent = true;
                ourLeafIndex = l.leafIndex;
                groupNotAfterForUs = l.notAfterSecs;
            }
        }
        sb.append(" treeLeaves=").append(leaves.size());
        // The RCS roster and the MLS tree answer different questions; a member can be in one only.
        sb.append("\n  IDENTITIES IN THE SERVER'S TREE:").append(who);
        sb.append("\n  OUR LEAF (").append(shell.selfE164Raw() == null ? "?"
                : LogMask.number(shell.selfE164Raw())).append("): ");
        if (ourLeafPresent) {
            sb.append("PRESENT at leaf=").append(ourLeafIndex);
        } else {
            sb.append("*** ABSENT FROM THE SERVER'S CURRENT TREE ***");
            if (rosterCount > 0) {
                sb.append(" while the RCS roster carries ").append(rosterCount)
                  .append(" member(s) — a roster row without a leaf is what a re-Welcome that "
                          + "never arrived leaves behind, and no self-heal or era advance fixes "
                          + "it: the remedy is a re-add by a member");
            }
            // Only the tree at the server's current epoch: a leaf removed by a commit we never
            // processed looks the same.
            sb.append(". This is the server's tree AT ITS CURRENT EPOCH, not our whole history: a "
                    + "leaf removed by a Commit we never processed looks identical from here");
        }

        sb.append("\n  GroupInfo EPOCH: ");
        // The epoch comparison is decided in MlsAnchorProvenance, where every outcome is
        // host-tested.
        final MlsAnchorProvenance prov =
                MlsAnchorProvenance.of(signed != null, giEpoch, ourEpoch);
        if (prov != MlsAnchorProvenance.UNREADABLE) {
            sb.append(giEpoch).append(" (we hold ").append(ourEpoch).append(") — ");
        }
        sb.append(prov.line());
        sb.append("\n  SIGNER OF THAT GroupInfo (who signed it): ");
        if (serverGi == null || serverGi.length == 0) {
            sb.append("no GroupInfo in the pack — not asked, not unknown");
        } else if (signerIdx < 0) {
            // Never rendered as leaf 0, which is a real index (the creator's).
            sb.append("UNREADABLE from ").append(serverGi.length)
              .append("B of GroupInfo — we could not tell, which is NOT leaf 0");
        } else {
            String signerWho = null;
            boolean signerIsUs = false;
            for (final MlsTreeLeaf l : leaves) {
                if (l.leafIndex == signerIdx) {
                    signerWho = l.msisdn;
                    // isIdentity, not equals: the leaf MSISDN has no '+', ours does.
                    signerIsUs = l.isIdentity(shell.selfE164Raw());
                    break;
                }
            }
            sb.append("leaf=").append(signerIdx).append(' ');
            if (signerWho == null) {
                sb.append("*** NOT IN THIS TREE *** — the signer index is outside the roster we "
                        + "just parsed, which is a stronger finding than an unknown name");
            } else if (signerWho.isEmpty()) {
                sb.append("(leaf present, no readable SAN)");
            } else {
                sb.append(signerWho);
                if (signerIsUs) {
                    sb.append(" — OURSELVES: the server's current epoch descends from OUR commit");
                } else {
                    sb.append(" — NOT us: the server's current epoch descends from THEIR commit, "
                            + "so our state at a lower epoch was not on this chain unless a commit "
                            + "we never saw carried it");
                }
            }
        }

        // Windows second.
        sb.append("\n  WINDOWS (serverLeafDays = the SERVER'S copy of that member's credential, "
                + "which is neither the certificate the device holds nor the leaf its published "
                + "pool serves):");
        final java.util.Map<Integer, long[]> asValidity = new java.util.LinkedHashMap<>();
        final java.util.Map<Integer, String> names = new java.util.LinkedHashMap<>();
        for (final MlsTreeLeaf l : leaves) {
            sb.append("\n    leaf=").append(l.leafIndex).append(' ')
              .append(l.msisdn.isEmpty() ? "(no readable SAN)" : LogMask.number(l.msisdn));
            if (l.unreadable()) {
                // An unreadable leaf is not a leaf about to expire.
                sb.append(" UNREADABLE — we could not date this leaf, which is NOT 'expired'");
            } else {
                sb.append(" notBefore=").append(l.notBeforeSecs)
                  .append(" notAfter=").append(l.notAfterSecs)
                  .append(" serverLeafDays=").append(l.remainingDays(now));
            }
            asValidity.put(Integer.valueOf(l.leafIndex),
                    new long[] { l.notBeforeSecs, l.notAfterSecs });
            names.put(Integer.valueOf(l.leafIndex), l.msisdn);
        }
        sb.append("\n  floor=").append(MlsCredentialFloor.classify(
                asValidity, names, now, cfg.kpMinRemainingDays));

        // The comparison a credential repair is judged on.
        final MlsSelfLeafStatus self =
                (g == null || g.groupId == null) ? null
                        : shell.session().selfLeafStatus(g.groupId);
        sb.append("\n  vs THE CERTIFICATE WE HOLD: ");
        if (self == null) {
            // Not a probe failure: with no local group there is nothing to compare.
            sb.append("no local group here, so the client certificate is not readable at this "
                    + "site — the server-side windows above still stand on their own");
        } else if (!ourLeafPresent) {
            sb.append("not comparable — our leaf is not in the server's tree");
        } else if (self.clientNotAfter == 0L) {
            sb.append("the client certificate is UNREADABLE — not compared");
        } else if (self.clientNotAfter == groupNotAfterForUs) {
            sb.append("SAME (notAfter=").append(groupNotAfterForUs)
              .append(") — the server holds the credential we hold");
        } else {
            sb.append("DIFFERS: the SERVER holds notAfter=").append(groupNotAfterForUs)
              .append(" while we hold notAfter=").append(self.clientNotAfter)
              .append(" — a §9.5.3 certificate update has not reached the server's copy");
        }
        return sb.toString();
    }

    /**
     * Per-leaf {@code (index, MSISDN, participant key)} for a group, as text: the input
     * {@link MlsParticipantKeyResync#plan} would act on. A dump, not an action, since that plan
     * removes members.
     */
    public static String dumpMemberParticipantKeys(final MlsShellPort shell,
            final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return "no session";
        final MlsSession eng = shell.openMlsSession();
        if (eng == null) return "engine is not OpenMLS";
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return "no conversation key";
        final byte[] gid;
        shell.lock(key);
        try {
            final Group g = shell.getGroup(key);
            if (g == null || g.groupId == null) return "no group";
            gid = g.groupId;
        } finally { shell.unlock(key); }
        final java.util.Map<Integer, MlsParticipantKeyResync.Leaf> leaves =
                eng.memberParticipantKeys(gid);
        if (leaves.isEmpty()) return "no leaves";
        final StringBuilder sb = new StringBuilder();
        sb.append(leaves.size()).append(" leaf/leaves");
        for (final java.util.Map.Entry<Integer, MlsParticipantKeyResync.Leaf> e :
                leaves.entrySet()) {
            final MlsParticipantKeyResync.Leaf l = e.getValue();
            final String k = l.signedByParticipantKey;
            sb.append("\n    [").append(l.index).append("] ")
              .append(l.participant.isEmpty() ? "<no SAN msisdn>" : l.participant)
              .append("  participantKey=")
              // Head and tail: every P-256 SPKI starts with the same header, so a prefix makes
              // distinct keys look identical.
              .append(k.isEmpty() ? "<UNREADABLE — plan() will NOT treat this leaf as stale>"
                                  : (k.substring(0, Math.min(8, k.length())) + "…"
                                     + k.substring(Math.max(0, k.length() - 16))
                                     + " (" + (k.length() / 2) + "B)"));
        }
        return sb.toString();
    }

    /**
     * The {@code --ez serverera} era/epoch read, exempt from the fetch ledger.
     *
     * @return the server's {@code [era, epoch]}, or null if the server had none
     */
    public static long[] debugServerEraEpoch(final MlsShellPort shell, final String rcsGroupId,
            final String peerE164) {
        return shell.lookServerEraEpoch(MlsFetchLedger.Caller.DEBUG_HEALTH,
                MlsConversationKey.canonicalKey(rcsGroupId, peerE164), peerE164,
                rcsGroupId).orNull();
    }

    /**
     * Test instrument: ask the rebuild-episode and re-establish cooldown stamps whether they would
     * allow their operation now, stamping if so, through the same {@link MlsPeerGuard} calls
     * production uses. Checks the stamps survive a process restart without a diverged conversation.
     * Transmits nothing; the probe's stamp suppresses the real operation for the window until the
     * cooldowns are reset.
     */
    public static String debugProbeDurableCooldowns(final MlsShellPort shell,
            final String rcsGroupId, final String peerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        final StringBuilder sb = new StringBuilder();
        if (key == null) {
            sb.append("episode=NO_KEY");
        } else {
            final long ageBefore = shell.peerGuard().rebuildEpisodeAgeMs(key);
            final boolean allowed = shell.peerGuard().claimRebuildEpisode(key);
            sb.append("episode{key=").append(MlsConversationKey.forLog(key))
              .append(" ageBeforeMs=").append(ageBefore == Long.MAX_VALUE ? "NONE"
                      : ageBefore < 0L ? "UNREADABLE" : Long.toString(ageBefore))
              .append(" verdict=").append(allowed ? "ALLOWED_AND_STAMPED" : "SUPPRESSED")
              .append(" windowMs=").append(shell.peerGuard().rebuildEpisodeWindowMs()).append('}');
        }
        // The log line says the probe spent what it reported as allowed.
        sb.append(
                " [this probe SPENT any bound it reported as allowed; cooldownsreset undoes it] ");
        if (peerE164 == null || peerE164.isEmpty()) {
            sb.append("reestablish=NO_PEER");
        } else {
            final boolean allowed = shell.peerGuard().claimReestablishAttempt(peerE164);
            sb.append("reestablish{verdict=").append(allowed ? "ALLOWED_AND_STAMPED" : "COOLING")
              .append(" windowMs=").append(shell.peerGuard().reestablishCooldownMs()).append('}');
        }
        return sb.toString();
    }
}

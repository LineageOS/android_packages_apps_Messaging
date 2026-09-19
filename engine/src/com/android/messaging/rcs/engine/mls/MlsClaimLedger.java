/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PeerClaim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ClaimOutcomeSink;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.log.LogMask;
/**
 * Per-peer ledger for KeyPackage claims, charged before the claim is sent: a claim consumes the
 * peer's pool, which we cannot give back. Each {@link Caller} has a ration under a shared per-peer
 * ceiling; unrefusable callers are never refused but always charged. See docs/mls/budgets.md.
 */
public final class MlsClaimLedger {

    private MlsClaimLedger() {}

    /** One constant: both AIDL spellings reach the same claim. */
    public enum Primitive {
        CLAIM_KEY_PACKAGES("claimKeyPackages");

        public final String dialName;

        Primitive(final String dialName) {
            this.dialName = dialName;
        }
    }

    /** The needles for the source-scan guard over the transport. */
    public static final String[] AIDL_SPELLINGS = {
        "claimPeerKeyPackagesWithOutcome",
        "claimPeerKeyPackages",
    };

    /** Must appear zero times in the transport; disjoint from {@link #AIDL_SPELLINGS}. */
    public static final String[] RETIRED_AIDL_SPELLINGS = {
        "claimPeerKeyPackage",
    };

    /** Never refused; unlike an exempt fetch-ledger caller, it still charges. */
    public static final int UNREFUSABLE = -1;

    /** {@code MlsUpgradeClaim.count()} when the ledger refused; distinct from 0, "none had one". */
    public static final int CLAIM_REFUSED = -1;

    /** One constant per method that spends; rations are per peer per {@link #WINDOW_MS}. */
    public enum Caller {
        /** The 1:1 create and one retry. "Try again" must not clear this ledger. */
        ENSURE_READY(2),
        /** After the rebuild's forget: a refusal would leave no group at all. */
        REBUILD_RECREATE(UNREFUSABLE),
        GROUP_ESTABLISH(2),
        /** Reached on every conversation open; bounds an upgrade that keeps failing elsewhere. */
        UPGRADE_PROBE(1),
        ADD_MEMBER(2),
        ERA_ADVANCE(2),
        DEBUG_KP_COUNT(UNREFUSABLE),
        DEBUG_CLAIM_KP(UNREFUSABLE),
        DEBUG_CTRL(UNREFUSABLE);

        public final int ration;

        Caller(final int ration) {
            this.ration = ration;
        }

        public boolean isUnrefusable() {
            return ration == UNREFUSABLE;
        }
    }

    /** Equal to the re-establish cooldown but a separate constant: they bound different things. */
    public static final long WINDOW_MS = 10L * 60L * 1000L;

    /** Claims all callers together may spend against one peer in one window. */
    public static final int SHARED_CEILING = 4;

    public enum Verdict {
        SPEND,
        SPEND_UNREFUSABLE,
        DENIED_CALLER_RATION,
        DENIED_SHARED_CEILING;

        public boolean permitted() {
            return this == SPEND || this == SPEND_UNREFUSABLE;
        }

        /** Identical to {@link #permitted()}: an uncounted claim would overstate the pool. */
        public boolean charges() {
            return permitted();
        }
    }

    /**
     * A null {@code caller} is refused, never defaulted; negative counts read as none, and
     * {@code sharedCeiling} is clamped to at least 1.
     */
    public static Verdict mayClaim(final Caller caller, final int spentByThisCaller,
            final int spentAgainstCeiling, final int sharedCeiling) {
        if (caller == null) {
            return Verdict.DENIED_CALLER_RATION;
        }
        if (caller.isUnrefusable()) return Verdict.SPEND_UNREFUSABLE;
        final int mine = Math.max(0, spentByThisCaller);
        if (mine >= Math.max(1, caller.ration)) return Verdict.DENIED_CALLER_RATION;
        final int shared = Math.max(0, spentAgainstCeiling);
        if (shared >= Math.max(1, sharedCeiling)) return Verdict.DENIED_SHARED_CEILING;
        return Verdict.SPEND;
    }

    public static Verdict mayClaim(final Caller caller, final int spentByThisCaller,
            final int spentAgainstCeiling) {
        return mayClaim(caller, spentByThisCaller, spentAgainstCeiling, SHARED_CEILING);
    }

    public static String describeRefusal(final Caller caller, final Verdict verdict,
            final int spentByThisCaller, final int spentAgainstCeiling, final int sharedCeiling,
            final String peer) {
        final String who = "MLS claim ledger REFUSED a KeyPackage claim for " + name(caller)
                + " on " + safe(peer) + ": ";
        final String common = " This is OUR bound, and the pool is THEIRS — a claim consumes every "
                + "device's published KeyPackage for that peer, and a peer with none left cannot be "
                + "added, re-Welcomed or brought into a rebuilt group. NOTHING WAS CLAIMED, so this "
                + "says nothing about how many packages they have; it must not be read as the peer "
                + "having none. The work is still owed and is retried from a fresh trigger after "
                + "the " + (WINDOW_MS / 60000L) + "-minute window.";
        if (verdict == Verdict.DENIED_SHARED_CEILING) {
            return who + "this peer's shared allowance of " + Math.max(1, sharedCeiling)
                    + " claim(s) per " + (WINDOW_MS / 60000L) + " min is gone ("
                    + Math.max(0, spentAgainstCeiling) + " spent, of which "
                    + Math.max(0, spentByThisCaller) + " by this caller). OTHER CALLERS SPENT MOST "
                    + "OF IT — this one is not necessarily looping." + common;
        }
        if (caller == null) {
            return who + "no caller was declared, so no ration could be consulted and the claim is "
                    + "refused rather than defaulted — defaulting would hand a new call site a free "
                    + "allowance by omission." + common;
        }
        return who + "it has spent its own " + Math.max(1, caller.ration) + "-claim allowance ("
                + Math.max(0, spentByThisCaller) + " spent per " + (WINDOW_MS / 60000L)
                + " min) while this peer's shared allowance still has room ("
                + Math.max(0, spentAgainstCeiling) + " of " + Math.max(1, sharedCeiling)
                + "). THIS CALLER is the one repeating itself." + common;
    }

    public static String describeUnrefusable(final Caller caller, final String peer,
            final int spentAgainstCeilingAfter, final int sharedCeiling) {
        return "MLS claim ledger UNREFUSABLE: " + name(caller) + "'s KeyPackage claim on "
                + safe(peer) + " cannot be refused — " + reasonUnrefusable(caller)
                + " — but it IS CHARGED, because the packages are really gone: "
                + LogMask.number(peer) + " is now "
                + "at " + Math.max(0, spentAgainstCeilingAfter) + " of "
                + Math.max(1, sharedCeiling)
                + " claim(s) spent in the last " + (WINDOW_MS / 60000L) + " min. A claim that "
                + "happened and was not counted would leave the next caller told this pool is "
                + "fuller than it is.";
    }

    private static String reasonUnrefusable(final Caller caller) {
        if (caller == Caller.REBUILD_RECREATE) {
            return "a rebuild has already dropped both halves of our state, and unlike a refused "
                    + "LOOK there is nothing to proceed without: the claim's product is the input "
                    + "the re-create is made of, so declining would leave this conversation with "
                    + "nothing at all";
        }
        return "an operator asked it directly, and an answer withheld for budget is useless at the "
                + "one moment it was worth having";
    }

    public static String describeCharge(final Caller caller, final String peer,
            final int spentByThisCallerAfter, final int spentAgainstCeilingAfter,
            final int sharedCeiling) {
        return "MLS claim ledger charged a KeyPackage claim to " + name(caller) + " on "
                + safe(peer) + " — " + Math.max(0, spentByThisCallerAfter) + " of "
                + Math.max(1, caller.ration) + " for this caller, "
                + Math.max(0, spentAgainstCeilingAfter) + " of " + Math.max(1, sharedCeiling)
                + " for this peer, per " + (WINDOW_MS / 60000L) + " min.";
    }

    public static String describeEmptyClaim(final Caller caller, final String peer,
            final int spentAgainstCeilingBefore) {
        return describeEmptyClaim(caller, peer, spentAgainstCeilingBefore, Attribution.UNKNOWN,
                null);
    }

    /** Whom an empty claim is evidence about, reduced from the provider's outcome. */
    public enum Attribution {
        /** The server answered with nothing for this peer; still not proof their pool is empty. */
        PEER_HAS_NONE,
        NOT_ABOUT_THE_PEER,
        UNKNOWN,
    }

    /**
     * Even {@link Attribution#PEER_HAS_NONE} does not blame the peer: an intact pool is withheld
     * inside the credential's 30-day floor (RCC.16 A.4.2.2).
     *
     * @param detail for the log only; never parsed
     */
    public static String describeEmptyClaim(final Caller caller, final String peer,
            final int spentAgainstCeilingBefore, final Attribution attribution,
            final String detail) {
        final String tail;
        switch (attribution == null ? Attribution.UNKNOWN : attribution) {
            case PEER_HAS_NONE:
                tail = "The KDS ANSWERED and returned nothing FOR THIS PEER — which is more than a "
                        + "refusal tells you, and still NOT a statement about what is in their "
                        + "pool. Three causes produce it and only two are theirs: (1) they have "
                        + "published none; (2) their pool is drained, last-resort included; "
                        + "(3) their pool is INTACT and the KDS is WITHHOLDING it because the "
                        + "credential in those KeyPackages is inside RCC.16's "
                        + MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS + "-day floor (A.4.2.2, "
                        + "which reaches a CLIENT's claim via §5.3 and not via A.4.2's own scope "
                        + "list) — a certificate REFRESH failure, which on a fleet running one "
                        + "implementation is as often ours as theirs. "
                        + "READ THE CERTIFICATE WINDOW before blaming this peer. Either way nothing "
                        + "was claimed for this member, so the upgrade is blocked while they are "
                        + "in it.";
                break;
            case NOT_ABOUT_THE_PEER:
                tail = "THIS IS NOT A FACT ABOUT THE PEER. The provider reports that the claim did "
                        + "not get an answer about them at all — we were refused, the request was "
                        + "refused, nothing came back, or it was never sent. Their pool may be "
                        + "full. Look at OUR OWN state first. What IS true either way "
                        + "is that nothing was claimed for this member, so the upgrade is blocked "
                        + "while they are in it.";
                break;
            default:
                tail = "Unlike a refusal, the KDS WAS ASKED — but what it answered is not visible "
                        + "here: this is EITHER their pool (published none, or drained) OR a claim "
                        + "that never got an answer (our own token dead, or the wrong KDS dialled "
                        + "for this peer). DO NOT conclude the peer is at fault without reading "
                        + "MlsProvider.KdsClient for the gRPC status; a grpc-16 UNAUTHENTICATED on "
                        + "OUR side looks exactly like this. Either way nothing was "
                        + "claimed for this member, so the upgrade is blocked while they are in "
                        + "it.";
                break;
        }
        return "MLS claim ledger: " + name(caller) + "'s KeyPackage claim on " + safe(peer)
                + " was MADE and came back EMPTY after " + Math.max(0, spentAgainstCeilingBefore)
                + " claim(s) against this peer in the last " + (WINDOW_MS / 60000L) + " min. "
                + tail + (detail == null || detail.isEmpty() ? ""
                        : " [provider detail, do not parse: " + detail + "]");
    }

    /**
     * A claim the provider never sent, its charge already refunded.
     *
     * @param spentAgainstCeilingAfterRefund or {@link #NO_COUNT}, which claims no refund
     */
    public static String describeNotAttempted(final Caller caller, final String peer,
            final int spentAgainstCeilingAfterRefund, final int sharedCeiling,
            final String detail) {
        final String accounting = spentAgainstCeilingAfterRefund < 0
                ? "This peer's stored ledger is UNREADABLE, so the charge this ledger takes BEFORE "
                        + "it asks could NOT be accounted for and no count can be stated here — "
                        + "read the ledger's own lines above for which of the two it is. Anything "
                        + "stuck against " + safe(peer) + " ages out of the "
                        + (WINDOW_MS / 60000L) + "-minute window on its own."
                : "The charge this ledger takes BEFORE it asks has been REFUNDED — THE CHARGE LINE "
                        + "LOGGED FOR THIS CLAIM A MOMENT AGO IS SUPERSEDED BY THIS ONE — so "
                        + safe(peer) + " is back at " + spentAgainstCeilingAfterRefund + " of "
                        + Math.max(1, sharedCeiling) + " claim(s) spent in the last "
                        + (WINDOW_MS / 60000L) + " min.";
        return "MLS claim ledger: " + name(caller) + "'s KeyPackage claim on " + safe(peer)
                + " was NOT ATTEMPTED. No dial was spent and NO KeyPackage left their pool, so this "
                + "is not an empty answer and must not be read as one. THIS IS NOT A FACT ABOUT THE "
                + "PEER — nobody was asked about them. It is a fact about OUR OWN side: the RCS "
                + "provider was not bound, or could not take the call at all; the provider detail "
                + "at the end of this line says which. " + accounting + " A claim nobody made must "
                + "not spend a peer's allowance, and four that did would make this ledger refuse a "
                + "real one just as the provider came back. Nothing was claimed for "
                + "this member, so whatever needed the KeyPackage is still blocked."
                + (detail == null || detail.isEmpty() ? ""
                        : " [provider detail, do not parse: " + detail + "]");
    }

    /** The ledger is unreadable, so no count can be stated. */
    public static final int NO_COUNT = -1;

    public static String rosterClaimBlockedLine(final String peer, final Attribution attribution) {
        return blockedByEmptyClaim(peer, attribution,
                "era advance ABORTED — no KeyPackage came back for " + safe(peer) + ", and the new "
                        + "era would be short a member the server expects, so this builds NO roster "
                        + "rather than a partial one.");
    }

    public static String oneToOneCreateBlockedLine(final String peer,
            final Attribution attribution) {
        return blockedByEmptyClaim(peer, attribution, "NOT creating the 1:1 with " + safe(peer)
                + " — no KeyPackage came back, and the group is built FROM it, so there is no "
                + "weaker create to fall back to.");
    }

    /** {@link Attribution#UNKNOWN} also covers a member a pre-claim carried nothing for. */
    public static String establishGroupBlockedLine(final String peer,
            final Attribution attribution) {
        return blockedByEmptyClaim(peer, attribution, "NOT creating the group — no KeyPackage came "
                + "back for " + safe(peer) + ", and a group that omits a member the server expects "
                + "is not a weaker create, it is a wrong one.");
    }

    public static String addMemberBlockedLine(final String peer, final Attribution attribution) {
        return blockedByEmptyClaim(peer, attribution, "NOT adding " + safe(peer)
                + " — no KeyPackage came back, and an Add is built FROM it.");
    }

    /**
     * The attribution clause lives only here, so call sites cannot disagree about whom to blame.
     *
     * @param consequence what the caller did about it, as a complete sentence
     */
    public static String blockedByEmptyClaim(final String peer, final Attribution attribution,
            final String consequence) {
        final String who;
        switch (attribution == null ? Attribution.UNKNOWN : attribution) {
            case PEER_HAS_NONE:
                who = "The KDS ANSWERED about them and returned nothing, so the block is on THEIR "
                        + "side of the claim — see the ledger line directly above for the three "
                        + "causes, only two of which are theirs.";
                break;
            case NOT_ABOUT_THE_PEER:
                who = "THIS IS NOT A FACT ABOUT " + safe(peer) + ". The provider reports we never "
                        + "got an answer ABOUT them — we were refused, the request was, nothing "
                        + "came back, or it was never sent. DO NOT go and look at their device: "
                        + "look at OUR OWN side first — our KDS credentials, or whether we reached "
                        + "a KDS at all.";
                break;
            default:
                who = "WHOSE BLOCK THIS IS, IS UNKNOWN — this provider did not report an outcome, "
                        + "so their pool and our own credentials are equally consistent with it. "
                        + "Read the ledger line above and MlsProvider.KdsClient before attributing "
                        + "it to this member.";
                break;
        }
        return (consequence == null || consequence.isEmpty()
                ? "no KeyPackage came back for " + safe(peer) + "." : consequence) + " " + who;
    }

    private static String name(final Caller c) {
        return c == null ? "<no caller declared>" : c.name();
    }

    private static String safe(final String s) {
        return (s == null || s.isEmpty()) ? "<no peer>" : LogMask.number(s);
    }

    public static String claimLedgerPrefKey(final String peer) {
        return "mls_claim_ledger_" + (peer == null || peer.isEmpty() ? "<none>" : peer);
    }

    /**
     * Null when unreadable, which callers treat as fully spent; {@link #spendOneClaim} refuses once
     * and then discards the record ({@link #discardUnreadable}).
     */
    public static MlsClaimLedgerRecord claimLedgerFor(final MlsShellPort shell,
            final MlsLogSink log, final String peer) {
        final String raw;
        try {
            raw = shell.prefs()
                    .getString(MlsClaimLedger.claimLedgerPrefKey(peer), null);
        } catch (final Throwable t) {
            log.w("MlsClaimLedger: could not READ the claim ledger for " + LogMask.number(peer)
                    + " — treating the peer as having spent its shared allowance, which is the "
                    + "strict direction for a pool that is not ours.", t);
            return null;
        }
        final MlsClaimLedgerRecord r = MlsClaimLedgerRecord.decode(raw);
        if (r == null) {
            log.w("MlsClaimLedger: the claim ledger for " + LogMask.number(peer) + " is STORED and "
                    + "UNREADABLE (" + (raw == null ? 0 : raw.length()) + " chars). Treating the "
                    + "peer as having spent its shared allowance rather than as unspent — an "
                    + "unreadable record read as 'no charges' hands back a full allowance on the "
                    + "strength of a parse failure.");
        }
        return r;
    }

    /**
     * Removes a stored record that does not parse, after it has refused one claim: leaving it would
     * refuse every later claim, since nothing else rewrites it. Re-reads first, so a record another
     * caller wrote since is kept; a store that cannot be read is left alone.
     *
     * @return whether the record was removed
     */
    static boolean discardUnreadable(final MlsShellPort shell, final MlsLogSink log,
            final String peer) {
        try {
            final MlsPrefs p = shell.prefs();
            final String k = MlsClaimLedger.claimLedgerPrefKey(peer);
            final String raw = p.getString(k, null);
            if (raw == null || MlsClaimLedgerRecord.decode(raw) != null) return false;
            return p.edit().remove(k).commit();
        } catch (final Throwable t) {
            log.w("MlsClaimLedger: could not DISCARD the unreadable claim ledger for "
                    + LogMask.number(peer), t);
            return false;
        }
    }

    /**
     * Writes with {@code commit()}: the charge is on disk before the claim it pays for, since a
     * claim spends the peer's KeyPackage whether or not this process survives to record it.
     */
    public static void storeClaimLedger(final MlsShellPort shell, final MlsLogSink log,
            final String peer, final MlsClaimLedgerRecord r) {
        if (r == null) return;
        try {
            if (!shell.prefs().edit()
                    .putString(MlsClaimLedger.claimLedgerPrefKey(peer), r.encode()).commit()) {
                log.w("MlsClaimLedger: commit() FAILED writing the claim ledger for "
                        + LogMask.number(peer) + " — this charge may not survive a restart.");
            }
        } catch (final Throwable t) {
            log.w("MlsClaimLedger: could not WRITE the claim ledger for " + LogMask.number(peer)
                    + " — this charge will not be counted against the next caller.", t);
        }
    }

    /** Refunds a claim never sent; re-reads the record so interleaved charges survive. */
    public static <T> Claim<T> notAttemptedAfterCharge(final MlsShellPort shell,
            final MlsLogSink log, final MlsClaimLedger.Caller caller,
            final String peer, final long now, final int ceiling, final ClaimOutcomeSink outcome) {
        final MlsClaimLedgerRecord current = MlsClaimLedger.claimLedgerFor(shell, log, peer);
        final int spentAfterRefund;
        if (current == null) {
            log.w("MlsClaimLedger: the claim ledger for " + LogMask.number(peer) + " became "
                    + "UNREADABLE between the charge and the refund, so a claim that was NEVER SENT "
                    + "stays charged against this peer. Writing our own view over a record we "
                    + "cannot read would release every OTHER caller's charge too, which is the "
                    + "direction claimLedgerFor refuses on purpose, so nothing is written here. "
                    + "The next claim on this peer refuses once and discards the record.");
            spentAfterRefund = MlsClaimLedger.NO_COUNT;
        } else {
            final MlsClaimLedgerRecord refunded = current.refunded(caller, now);
            MlsClaimLedger.storeClaimLedger(shell, log, peer, refunded);
            spentAfterRefund = refunded.spentAgainstCeiling(now);
        }
        final String why = MlsClaimLedger.describeNotAttempted(
                caller, peer, spentAfterRefund, ceiling, outcome.detail);
        log.w("MlsClaimLedger: " + why);
        return Claim.notAttempted(outcome.attribution, why);
    }

    /** Read live, so an override applies without a restart. */
    public static int claimLedgerCeiling(final MlsShellPort shell) {
        return shell.sysprops().getInt(
                "debug.rcs.mls_claim_ceiling", MlsClaimLedger.SHARED_CEILING);
    }

    /** As below, with a sink the supplier fills with the provider's outcome. */
    public static <T> Claim<T> spendOneClaim(final MlsShellPort shell, final MlsLogSink log,
            final MlsClaimLedger.Caller caller, final String peer,
            final ClaimOutcomeSink outcome, final PeerClaim<T> doIt) {
        final long now = shell.elapsedRealtime();
        final int ceiling = MlsClaimLedger.claimLedgerCeiling(shell);
        final MlsClaimLedgerRecord before = MlsClaimLedger.claimLedgerFor(shell, log, peer);
        if (before == null) {
            if (caller != null && caller.isUnrefusable()) {
                log.w("MlsClaimLedger: " + MlsClaimLedger.describeUnrefusable(
                        caller, peer, 0, ceiling) + " THE LEDGER IS UNREADABLE, so this charge "
                        + "could not be recorded and the count above is short by at least one.");
                final T unrefusedAnswer = doIt.claim();
                if (outcome.notAttempted) {
                    final String why = MlsClaimLedger.describeNotAttempted(caller, peer,
                            MlsClaimLedger.NO_COUNT, ceiling, outcome.detail);
                    log.w("MlsClaimLedger: " + why);
                    return Claim.notAttempted(outcome.attribution, why);
                }
                return Claim.asked(unrefusedAnswer, outcome.attribution);
            }
            // Refuse once, then discard, so a format skew costs one claim rather than all of them.
            final boolean discarded = MlsClaimLedger.discardUnreadable(shell, log, peer);
            final String why = "MLS claim ledger REFUSED a KeyPackage claim for " + caller + " on "
                    + LogMask.number(peer)
                    + ": this peer's ledger is stored and UNREADABLE, and an unreadable "
                    + "ledger is read as SPENT rather than as unspent. NOTHING WAS CLAIMED — this "
                    + "is not the peer having no key packages. "
                    + (discarded ? "The record is now DISCARDED, so the next claim starts from an "
                            + "empty ledger."
                            : "The record could NOT be discarded, so later claims are refused too "
                            + "until it is replaced.");
            log.w("MlsClaimLedger: " + why);
            return Claim.refusedByLedger(why);
        }
        final int mine = before.spentBy(caller, now);
        final int shared = before.spentAgainstCeiling(now);
        final MlsClaimLedger.Verdict v = MlsClaimLedger.mayClaim(caller, mine, shared, ceiling);
        if (!v.permitted()) {
            final String why =
                    MlsClaimLedger.describeRefusal(caller, v, mine, shared, ceiling, peer);
            log.w("MlsClaimLedger: " + why);
            return Claim.refusedByLedger(why);
        }
        final MlsClaimLedgerRecord after = before.charged(caller, now);
        MlsClaimLedger.storeClaimLedger(shell, log, peer, after);
        final int sharedAfter = after.spentAgainstCeiling(now);
        if (v == MlsClaimLedger.Verdict.SPEND_UNREFUSABLE) {
            log.i("MlsClaimLedger: " + MlsClaimLedger.describeUnrefusable(
                    caller, peer, sharedAfter, ceiling));
        } else {
            log.i("MlsClaimLedger: " + MlsClaimLedger.describeCharge(
                    caller, peer, after.spentBy(caller, now), sharedAfter, ceiling));
        }
        final T answer = doIt.claim();
        if (outcome.notAttempted) {
            return MlsClaimLedger.notAttemptedAfterCharge(shell, log, caller, peer, now, ceiling,
                    outcome);
        }
        if (answer == null || (answer instanceof java.util.List
                && ((java.util.List<?>) answer).isEmpty())) {
            log.w("MlsClaimLedger: " + MlsClaimLedger.describeEmptyClaim(
                    caller, peer, shared, outcome.attribution, outcome.detail));
        }
        return Claim.asked(answer, outcome.attribution);
    }

    /** The charge point; a refusal cannot be mistaken for an empty pool. */
    public static <T> Claim<T> spendOneClaim(final MlsShellPort shell, final MlsLogSink log,
            final MlsClaimLedger.Caller caller, final String peer, final PeerClaim<T> doIt) {
        return MlsClaimLedger.spendOneClaim(shell, log, caller, peer, new ClaimOutcomeSink(), doIt);
    }

    /** An empty {@code OUTCOME_SERVED} is a fault on our side, so not about the peer. */
    public static MlsClaimLedger.Attribution attributionOf(final int outcome) {
        return outcome == MlsProviderRpc.ClaimResult.OUTCOME_PEER_HAS_NONE
                ? MlsClaimLedger.Attribution.PEER_HAS_NONE
                : MlsClaimLedger.Attribution.NOT_ABOUT_THE_PEER;
    }

    // The two AIDL spellings are one spend point: both claim every device's package, and the
    // singular form discards the rest afterwards. The claim ledger outlives the conversation: a
    // peer does not get its packages back because we dropped a conversation, and a rebuild claims
    // again immediately. See docs/mls/budgets.md.

    /**
     * {@code claimPeerKeyPackage}, charged: one package back, every device's package spent (the
     * provider discards the rest).
     */
    public static Claim<byte[]> claimOne(final MlsShellPort shell, final MlsLogSink log,
            final MlsClaimLedger.Caller caller, final String peer) {
        // Ask for the outcome as well as the bytes, so "the peer published nothing" and "the KDS
        // would not talk to us" differ. The provider maps its transport status to spec-level terms
        // first; `detail` is for logs only and must not be parsed.
        final ClaimOutcomeSink outcome = new ClaimOutcomeSink();
        return MlsClaimLedger.spendOneClaim(shell, log, caller, peer, outcome, () -> {
            final MlsProviderRpc.ClaimResult r = shell.rpc("claimPeerKeyPackagesWithOutcome")
                    .claimPeerKeyPackagesWithOutcome(peer);
            outcome.attribution = MlsClaimLedger.attributionOf(r.outcome);
            outcome.detail = r.detail;
            // Refund only the outcome that asserts nothing was dialled (unbound provider, null
            // reply). An empty reply is not that: a NOT_AUTHORIZED also returns
            // empty and did dial.
            outcome.notAttempted = r.outcome == MlsProviderRpc.ClaimResult.OUTCOME_NOT_ATTEMPTED;
            return MlsClaimLedger.firstClaimedPackage(r.keyPackages);
        });
    }

    /** The first KeyPackage of the {@code [u32 BE len][bytes]…} reply, or null. */
    static byte[] firstClaimedPackage(final byte[] packed) {
        final List<byte[]> all = MlsArtifactBundle.splitLenPrefixed(packed);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * {@code claimPeerKeyPackages}, charged: every device's package back, at the same cost as
     * {@link #claimOne}.
     */
    public static Claim<List<byte[]>> claimAll(final MlsShellPort shell, final MlsLogSink log,
            final MlsClaimLedger.Caller caller, final String peer) {
        final ClaimOutcomeSink outcome = new ClaimOutcomeSink();
        return MlsClaimLedger.spendOneClaim(shell, log, caller, peer, outcome, () -> {
            // Pre-filled with a non-outcome: the shim writes the sink only when an outcome is known
            // and leaves it untouched when the provider cannot say. Never pass an unwritten
            // sink to attributionOf, whose 0 would read as OUTCOME_SERVED.
            final int[] sink = { NO_CLAIM_OUTCOME };
            final List<byte[]> kps =
                    shell.rpc("claimPeerKeyPackages").claimPeerKeyPackages(peer, sink);
            if (sink[0] != NO_CLAIM_OUTCOME) {
                outcome.attribution = MlsClaimLedger.attributionOf(sink[0]);
            }
            // Through the plural shim NOT_ATTEMPTED means only an unbound provider; other paths
            // fall back to an older spelling that does dial, so no refund.
            outcome.notAttempted = sink[0] == MlsProviderRpc.ClaimResult.OUTCOME_NOT_ATTEMPTED;
            return kps;
        });
    }

    /**
     * Pre-fill for the outcome sink, meaning "the provider has not said"; outside the claim
     * outcomes' 0..5 so it cannot collide with {@code OUTCOME_SERVED}.
     */
    static final int NO_CLAIM_OUTCOME = -1;
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

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
}

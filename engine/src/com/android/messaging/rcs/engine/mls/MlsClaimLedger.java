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
 * The LEDGER for one scarce resource that <b>belongs to somebody else</b> — a claim against a peer's
 * published KeyPackage pool — kept <b>per peer</b> and charged at the dial.
 *
 * <h2>What the resource is, established by walking to the dial and not by reading a javadoc</h2>
 *
 * <p>Two provider AIDL methods appear to spend it. <b>They are ONE spend point.</b> Both call the
 * identical {@code MlsKeyPackageClaimer.claim(ctx, singletonList(peer), -1)}, which issues
 * {@code claimOnce} and dials {@code KdsClient.claimKeyPackages}:
 *
 * <pre>
 *   MlsProviderTransport -> ProviderTransport (binder shim) -> RcsProviderService
 *         claimPeerKeyPackage  :831  --+
 *         claimPeerKeyPackages :920  --+--> MlsKeyPackageClaimer.claim   :137
 *                                            -> claimOnce               :277
 *                                                 -> KdsClient.claimKeyPackages   THE DIAL
 * </pre>
 *
 * <p><b>The claim consumes EVERY device's package</b> — one per device — and the singular form then
 * discards everything past index 0, <i>after</i> the claim has already happened. So the singular
 * form does not cost less than the plural; it costs the same and throws most of it away. The
 * contract-v21 javadoc that says "claim one KeyPackage for a peer" describes what the method
 * RETURNS, not what it SPENDS, and Stage 0's own first draft of this row believed it. Both forms
 * therefore charge the same primitive here, and {@link Primitive} has exactly one constant so that
 * a future third spelling cannot arrive looking like a different resource.
 *
 * <h2>Why this is keyed on the PEER where {@link MlsFetchLedger} is keyed on the conversation</h2>
 *
 * <p>Not a stylistic difference — the key is dictated by what is scarce. {@code MlsFetchLedger}
 * bounds a rate window that Tachyon enforces per conversation, so a per-conversation ledger models
 * it exactly. <b>This bounds a pool, and a peer has ONE pool.</b> A peer in three group
 * conversations would get three independent allowances from a per-conversation ledger, and the door
 * the door this was written about — {@link Caller#UPGRADE_PROBE}, one claim per participant on every
 * conversation OPEN — would drain that one pool from whichever conversation the person happened to
 * tap. Everything else is Stage 1's method unchanged: enumerate from the primitive, charge at the
 * wrappers, per-caller rations under a shared ceiling, a refusal arm at every door, a source-scan
 * guard.
 *
 * <h2>Why a refused claim matters more than a refused look</h2>
 *
 * <p>A peer with no claimable KeyPackage cannot be added, cannot be re-Welcomed, and cannot be
 * brought into a rebuilt group — {@code dumpKeyPackageCount}'s own output says <i>"THIS MEMBER ALONE
 * BLOCKS THE WHOLE UPGRADE"</i>. Draining a peer's pool is a way to make a conversation
 * unrepairable from our side, and it is the same class of harm as draining any other device's
 * spent something belonging to somebody else's device, at a rate nothing counted.
 *
 * <h2>The one place this deliberately departs from {@link MlsFetchLedger}: debug arms</h2>
 *
 * <p>There, a debug arm is EXEMPT and charges nothing. Half of that reasoning transfers and half of
 * it does not.
 *
 * <p>It transfers that a debug arm must never be REFUSED: the operator ran it precisely because
 * something is already wrong, and an answer withheld for budget is useless at the only moment it was
 * worth having. So the arms here are unrefusable, exactly as there.
 *
 * <p>It does <b>not</b> transfer that they charge nothing. {@code GetMlsGroupInfo} is a read against
 * a rate window; a KeyPackage claim removes a consumable from a third party's device. <b>A debug
 * claim that does not charge makes this ledger wrong about the world</b> — the packages really are
 * gone, and the next production caller would be told a pool is fuller than it is. So the verdict is
 * {@link Verdict#SPEND_UNREFUSABLE}: always permitted, always counted, and the log line says both.
 * Every permitted claim charges; see {@link Verdict#charges()}, which is pinned by the guard because
 * it is the design decision rather than an implementation detail.
 *
 * <h2>It reads no clock and holds no state</h2>
 *
 * <p>All methods are total, take explicit inputs, and read nothing. The counters and the window live
 * in {@link MlsClaimLedgerRecord}, which the host stores; the clock is supplied.
 */
public final class MlsClaimLedger {

    private MlsClaimLedger() {}

    /**
     * The resource, named once. <b>One constant, deliberately</b>, because the two AIDL spellings
     * are one spend point (see this class's header) and a scan that treated them as two primitives
     * would double-count the doors while a scan that picked either name alone would miss half.
     *
     * <p>Enumerated here rather than in the source-scan test so the two cannot drift: the test reads
     * the AIDL spellings out of this enum. A third form added provider-side must appear in
     * {@link #AIDL_SPELLINGS}, and the scan fails until it does.
     */
    public enum Primitive {
        /**
         * One {@code ClaimKeyPackages} round-trip, consuming every device's published package for
         * one peer.
         *
         * <p>Named for the DIAL rather than for either AIDL method, because the dial is what spends
         * and the AIDL names are two ways of asking for it.
         */
        CLAIM_KEY_PACKAGES("claimKeyPackages");

        /** The KDS dial this bottoms out in, for the log line. */
        public final String dialName;

        Primitive(final String dialName) {
            this.dialName = dialName;
        }
    }

    /**
     * The two app-side AIDL spellings of {@link Primitive#CLAIM_KEY_PACKAGES}, which are ONE spend
     * point.
     *
     * <p><b>The needle for this row's source scan, and it is not the dial's name.</b> The
     * underlying primitive is {@code KdsClient.claimKeyPackages}, a PROVIDER-side name in a
     * different repository. A scan keyed on it would search the provider, find the one call it
     * expects, and go green while
     * every app-side door sat untouched in {@code MlsProviderTransport}. The needle would be
     * well-formed, resolvable, and aimed at the wrong module — every other instance of that defect
     * class is a wrong key in the right place, and this is
     * a plausible key in the wrong place.
     */
    public static final String[] AIDL_SPELLINGS = {
        "claimPeerKeyPackagesWithOutcome",
        "claimPeerKeyPackages",
    };

    /**
     * Spellings that were LIVE DOORS and are not any more — they must appear <b>zero</b> times in
     * {@code MlsProviderTransport}, and the guard fails if one comes back.
     *
     * <p><b>Why retire rather than delete.</b> {@code claimOne} moved to
     * {@code claimPeerKeyPackagesWithOutcome} at contract v60, so the singular form is no longer
     * called from the transport. Simply dropping it from {@link #AIDL_SPELLINGS} would have been the
     * one-line change — and it would have silently removed the tripwire: a future direct
     * {@code pt(…).claimPeerKeyPackage(…)} would then spend a peer's pool with the guard green,
     * because nothing would be watching that name. The scan's whole argument is that a needle which
     * stops matching its subject certifies a file it cannot see; a needle deleted on purpose is the
     * same hole entered deliberately.
     *
     * <p>So it stays named, with the opposite expectation. Note the two lists must not overlap, and
     * the guard's "exactly one invocation per spelling" rule applies only to {@link #AIDL_SPELLINGS}
     * — a retired name is asserted at zero.
     *
     * <p>The singular is still reachable INSIDE {@code ProviderTransport} as the pre-v48 fallback of
     * {@code claimPeerKeyPackages}. That is a different file, behind an already-charged wrapper, and
     * it is not a door this ledger is accounting for.
     */
    public static final String[] RETIRED_AIDL_SPELLINGS = {
        "claimPeerKeyPackage",
    };

    /**
     * A caller that may never be refused. It still charges; see this class's header for why that
     * differs from {@link MlsFetchLedger#EXEMPT}.
     */
    public static final int UNREFUSABLE = -1;

    /**
     * The count {@code MlsUpgradeClaim.count()} reports when the ledger refused and we therefore
     * <b>never asked</b>.
     *
     * <p><b>Distinct from zero, and that is the whole point.</b> Zero already means "we claimed for
     * every participant and nobody had a package" — a statement about the PEERS. Returning zero for
     * a refusal would put that statement in front of a reader on the strength of our own rate
     * ledger, and {@link MlsUpgradePolicy} would answer {@code NOT_ENOUGH_KEY_PACKAGES} and log
     * Google Messages' verbatim line naming counts nobody measured. That is the defect Stage 1 met when a
     * refused look was routed into {@code Health.UNKNOWN} and a drive reported "the look came back
     * unreadable" for a look never made.
     */
    public static final int CLAIM_REFUSED = -1;

    /**
     * <b>The doors.</b> One constant per method that decides to spend, with its own allowance
     * against one peer inside one {@link #WINDOW_MS}.
     *
     * <p><b>There is no "outside the ceiling" flag here, and its absence is a decision.</b>
     * {@link MlsFetchLedger} holds its health readers outside the shared ceiling because starving a
     * diagnostic makes health unreadable, which escalates the ladder to a heavier remedy than the
     * fault needed. The equivalent reader here is the operator's {@link #DEBUG_KP_COUNT}, and it is
     * already {@link #UNREFUSABLE} — it cannot be starved by anything. The only other reader is
     * {@link #UPGRADE_PROBE}, which is the door this was written about. So every caller charges
     * the ceiling, and a field that was always true would be an assertion about nothing.
     */
    public enum Caller {
        /**
         * {@code ensureReady} — the 1:1 initiator create, {@code MlsProviderTransport:868}.
         *
         * <p>Two: one create, and one retry after a create the server discarded.
         *
         * <h3>An OUTER bound sits on one of this caller's paths, and it is tighter</h3>
         *
         * <p>{@code reestablishOutbound} reaches {@code ensureReady} (:6555) behind
         * {@code MlsPeerGuard.claimReestablishAttempt(peer, REESTABLISH_COOLDOWN_MS)} — one attempt
         * per peer per ten minutes, and durable. That is <b>one</b> where this ration
         * is <b>two</b>, so on THAT path this ledger can never be what refuses. It is nesting, not
         * starvation, and the distinction matters: {@code ensureReady}'s other callers — the
         * ordinary 1:1 send, and the rebuild through {@link #REBUILD_RECREATE} — do not pass the
         * cooldown at all, so this ration is what bounds them and is not a dead constant.
         *
         * <p><b>The outer bound stamps for attempts that claim nothing.</b> It is taken before the
         * off-thread runnable, and two arms inside return without ever reaching the create: a
         * refused {@code getMlsServerEraEpoch}, and "the server still holds this conversation". So
         * its javadoc's stated resource — <i>"each attempt claims a peer KeyPackage"</i> — is not
         * what it actually meters. The error is conservative, so the verdict is right; recorded
         * because a right verdict resting on a wrong reason is the one shape no test fails on.
         *
         * <h3>Try again must NOT clear this ledger, and that asymmetry is the design</h3>
         *
         * <p>The stalled-conversation notification's <i>Try again</i> reaches
         * {@code MlsPeerGuard.resetReestablishCooldown} and {@code resetRebuildRateBound}. It
         * touches nothing here, and a later change to "complete" the reset would be wrong:
         * <b>those bounds are ours to forgive and a peer's pool is not.</b> Handing back a claim
         * allowance would hand back packages that are actually gone, which is the whole error this
         * class exists to prevent, arriving through a helpful-looking reset.
         */
        ENSURE_READY(2),
        /**
         * {@code ensureReady} reached from {@code rebuildConversation} with
         * {@code stateAlreadyDestroyed=true} — {@code MlsProviderTransport:18673}.
         *
         * <p><b>UNREFUSABLE, and it is the opposite reason from the debug arms'.</b> Carrying Stage
         * 1's H1 lesson literally: {@code rebuildConversation} hoists its guards ABOVE its two
         * forgets, because a refusal arriving after the forget leaves the conversation with no
         * provider record, no engine state and no group — strictly worse than the churn the refusal
         * prevents. This charge sits BELOW the forget and cannot be hoisted, because the create it
         * feeds is what the rebuild exists to perform.
         *
         * <p>Stage 1's fix there was to FAIL OPEN — proceed without the server's era. <b>That
         * remedy is unavailable here</b>, and the difference is worth stating because it is the
         * difference between a look and a claim: a refused look costs a fact we can proceed without,
         * while a claim's product is REQUIRED INPUT — there is no group to build without the peer's
         * KeyPackage. Nothing to fail open TO. So the ledger does not refuse this caller at all,
         * which is the same trade {@code rebuildConversation} already makes one layer up.
         *
         * <p>It still charges, so a rebuild loop is visible in the ledger even though it is never
         * stopped by it. What stops a rebuild loop is {@code MlsRebuildLimiter} and
         * {@code MlsPeerGuard}'s era budget, which are outside this ledger and tighter.
         */
        REBUILD_RECREATE(UNREFUSABLE),
        /**
         * {@code establishGroup} — the create, once per member, {@code MlsProviderTransport:7901}.
         *
         * <p>This is the only door that uses the PLURAL spelling for a reason that matters: it needs
         * every device's package, because claiming one per participant gave a two-device member ONE
         * leaf and their second device could not decrypt anything. The cost is identical either way
         * (see the header); what differs is how much of the claim is thrown away.
         */
        GROUP_ESTABLISH(2),
        /**
         * <b>{@code claimForUpgrade} — the UI door</b>, reached from
         * {@code MlsConversationOpenListener.upgrade} whenever a conversation is opened.
         *
         * <p><b>It used to be a counter that consumed what it counts</b> — {@code
         * claimableKeyPackageCount}, whose own javadoc said so. It was made
         * the create's claim: the packages are kept and handed to {@code establishGroup}, so the
         * upgrade spends ONE round trip per participant rather than two. Its only bound
         * before this ledger was an ordering — {@code cheapGuardsPass = online && !alreadyMls &&
         * metadataAvailable && !participants.isEmpty()} — and there is deliberately no cooldown,
         * because the listener's throttle is state-based and Google Messages' is too ("§8.7's throttle is
         * state-based", "Build no timer", a verified exhaustive negative).
         *
         * <p><b>That reasoning is sound and it does not bound the failing case.</b>
         * {@code !alreadyMls} is false forever once the upgrade SUCCEEDS. A group whose upgrade is
         * refused for any reason other than key packages stays {@code !alreadyMls}, and every open
         * claims one package per participant again — at the rate a person taps a conversation list.
         *
         * <p><b>ONE, and it is the tightest ration here.</b> A successful upgrade needs exactly ONE
         * charge against a given peer — this claim, which the create then
         * consumes — where it used to need two (this probe, then {@link #GROUP_ESTABLISH}). The
         * ration bounds REPEATED opens rather than the upgrade itself, and it now does so with a
         * whole charge more headroom than it was set with.
         * The second open inside the window is refused, which is exactly the case the state-based
         * throttle cannot see.
         */
        UPGRADE_PROBE(1),
        /**
         * {@code addMember} — {@code MlsProviderTransport:8711}.
         *
         * <p>Two, because this one is reached from a person's action and a single retry must work.
         * It is refusable all the same: the resource is the NEW MEMBER'S pool, the add cannot
         * proceed without their package under any policy, and a refusal here is retriable in a way
         * a drained pool is not.
         */
        ADD_MEMBER(2),
        /**
         * {@code eraAdvanceLocked} — the era advance, once per member of the new roster,
         * {@code MlsProviderTransport:13348}.
         *
         * <p><b>Almost never the bound that binds</b>, and the reason is the interlock
         * {@code arms-on-device} measured for the fetch ledger: {@code MlsPeerGuard}'s era budget
         * (2/hour, 5/day) refuses an era advance long before this ration could. Recorded so a future
         * reader does not raise it looking for an effect it cannot have — and so that "unreachable
         * from a fixture" is never read as "cannot happen", since organic traffic reaches the deep
         * path through budgets charged by attempts that SUCCEEDED.
         */
        ERA_ADVANCE(2),
        /**
         * {@code dumpKeyPackageCount} — the {@code --ez kpcount} operator probe,
         * {@code MlsProviderTransport:11271}.
         *
         * <p>UNREFUSABLE and CHARGED. The operator ran it because something is already wrong, so
         * refusing it withholds the answer at the one moment it is worth having; and it really does
         * consume the pool it reports on, so not counting it would leave the next production caller
         * told the pool is fuller than it is.
         */
        DEBUG_KP_COUNT(UNREFUSABLE),
        /**
         * {@code RcsDebugSendReceiver}'s {@code --es claimkp} arm — the device-count probe.
         *
         * <p>UNREFUSABLE and CHARGED, as {@link #DEBUG_KP_COUNT}. Listed separately so the log says
         * WHICH arm spent it: this one and the probe above ask different questions and a shared
         * constant would make two operator actions indistinguishable in the ledger.
         */
        DEBUG_CLAIM_KP(UNREFUSABLE),
        /**
         * {@code RcsDebugSendReceiver}'s {@code --ez ctrl} arm — the v21 control-plane smoke test.
         *
         * <p>UNREFUSABLE and CHARGED. This arm is the one an earlier sweep walked past: its
         * {@code getMlsGroupInfo} half was routed through the transport and given an exempt caller,
         * while its claim half two lines above went on calling {@code ProviderTransport} directly.
         * A door outside the class that owns the resource, in a file that had just been edited for
         * exactly that reason.
         */
        DEBUG_CTRL(UNREFUSABLE);

        /** Claims this caller may spend against ONE peer within one {@link #WINDOW_MS}. */
        public final int ration;

        Caller(final int ration) {
            this.ration = ration;
        }

        /** Whether this caller may always claim. It still charges — see the class header. */
        public boolean isUnrefusable() {
            return ration == UNREFUSABLE;
        }
    }

    /**
     * The window the counters roll on: <b>ten minutes</b>.
     *
     * <p><b>Where it comes from, and what it is not.</b> Unlike {@link MlsFetchLedger#WINDOW_MS}
     * there is no measured server throttle behind this number — nothing has ever drawn a
     * {@code RESOURCE_EXHAUSTED} from {@code ClaimKeyPackages}, and this class must not pretend
     * otherwise. What exists is a bound somebody already chose for THIS resource:
     * {@code MlsProviderTransport.REESTABLISH_COOLDOWN_MS = 10 min}, whose javadoc names this
     * resource outright — <i>"{@code ensureReady} CLAIMS A PEER KEYPACKAGE, and a peer's one-time
     * pool is small and not replenished by us"</i>. It was the only rate bound
     * on this resource anywhere in the tree, and it covered one of the nine doors.
     *
     * <p><b>That quotation was wrong here until it was opened.</b> This paragraph read <i>"each
     * attempt claims one of their KeyPackages"</i> and attributed it to that javadoc, which does not
     * contain the sentence — it is a paraphrase, copied from an issue note into a
     * source file as though it were the file's own words. The distinction is not pedantry: the real
     * javadoc attributes the claim to {@code ensureReady}, which is exactly right, whereas the
     * paraphrase attributes it to <i>each attempt</i>, which is false — the cooldown stamps before
     * an off-thread run whose fetch-ledger refusal and "the server still holds this conversation"
     * arms both return without reaching a claim. So the paraphrase would have made a correct javadoc
     * look like the one that needed correcting, and it was nearly reworded on the strength
     * of this quotation.
     *
     * <p><b>The two are deliberately NOT tied to one constant</b>, though they carry the same value
     * today. They bound different things — that one is a per-peer re-establish cooldown, this is a
     * claim window across every door — and that coupling has been refused before in as many
     * words: tying them is how raising one silently raises the other.
     */
    public static final long WINDOW_MS = 10L * 60L * 1000L;

    /**
     * Claims all callers TOGETHER may spend against one peer in one window.
     *
     * <p><b>Four, and the derivation has an honest hole in it that is stated rather than dressed
     * up.</b> {@link MlsFetchLedger#SHARED_CEILING} is anchored from both sides: strictly below the
     * ten fetches that drew {@code grpcStatus=8}, strictly above the sequence the fixture runs.
     * <b>This one has no measured upper anchor</b>, because no claim rate has ever been observed to
     * fail. Both anchors are therefore arguments, and they are given here so a later measurement can
     * replace them rather than merely disagree with them:
     *
     * <ul>
     *   <li><b>Above the legitimate sequence.</b> The heaviest correct run against ONE peer is an
     *       upgrade: {@link Caller#UPGRADE_PROBE} (1) then {@link Caller#GROUP_ESTABLISH} (1) on the
     *       same open, back to back. Four leaves two claims of headroom, which is what stopped
     *       {@code MlsFetchLedger}'s first cut finishing at 7 of 7 — at the bound rather than under
     *       it, so the next ordinary operation on a healthy conversation would have been refused.</li>
     *   <li><b>Below our own idea of a drained pool.</b> {@code KP_REPUBLISH_MS} is 24 h and
     *       {@code KP_REPLENISH_AT} is 3 — the depth at which we consider OUR pool to need repair.
     *       Four claims per ten minutes says we will not, unaided, take a peer from a healthy pool
     *       to under our own replenish threshold inside one window. <b>That is our constant applied
     *       to their pool</b>, which is an argument and not a measurement; a peer running Google Messages
     *       may replenish on a different cadence entirely.</li>
     * </ul>
     *
     * <p>What would settle it: {@code noteKeyPackageConsumed}'s "~N left" line read from a peer we
     * control, across a window in which a known number of claims were made.
     */
    public static final int SHARED_CEILING = 4;

    /** Whether a claim may be spent, and if not, which bound refused it. */
    public enum Verdict {
        /** Spend it, and charge it. */
        SPEND,
        /**
         * Spend it, charge it, and say it could not have been refused — a debug arm, or the
         * re-create inside a rebuild that has already dropped our state.
         */
        SPEND_UNREFUSABLE,
        /** This caller has spent its own allowance against this peer in this window. */
        DENIED_CALLER_RATION,
        /** The peer's shared allowance is gone, spent by other callers too. */
        DENIED_SHARED_CEILING;

        /** Whether the claim happens. */
        public boolean permitted() {
            return this == SPEND || this == SPEND_UNREFUSABLE;
        }

        /**
         * Whether the ledger records a charge for it.
         *
         * <p><b>Identical to {@link #permitted()}, and that identity is the design</b> rather than a
         * coincidence to be tidied away: this ledger models a pool that really was emptied, so a
         * claim that happened and was not counted would make every later answer wrong. It is pinned
         * by {@code MlsKeyPackageClaimLedgerGuardTest} so a fourth verdict cannot quietly break it.
         */
        public boolean charges() {
            return permitted();
        }
    }

    /**
     * May {@code caller} spend one claim against this peer?
     *
     * @param caller             the door; never null
     * @param spentByThisCaller  this caller's charges against this peer in the current window;
     *                           negative reads as none
     * @param spentAgainstCeiling charges by every caller in the current window, this caller's
     *                           included; negative reads as none
     * @param sharedCeiling      {@link #SHARED_CEILING}, or an operator override; anything below 1
     *                           is clamped to 1, because a ceiling of zero would refuse a peer we
     *                           were never allowed to ask about — a self-inflicted outage rather
     *                           than a tight bound
     */
    public static Verdict mayClaim(final Caller caller, final int spentByThisCaller,
            final int spentAgainstCeiling, final int sharedCeiling) {
        if (caller == null) {
            // A charge with no declared caller is what this ledger exists to make impossible, so it
            // is refused rather than defaulted. Defaulting would give a new call site a free ration
            // by omission, which is the shape of the defect being closed.
            return Verdict.DENIED_CALLER_RATION;
        }
        if (caller.isUnrefusable()) return Verdict.SPEND_UNREFUSABLE;
        final int mine = Math.max(0, spentByThisCaller);
        if (mine >= Math.max(1, caller.ration)) return Verdict.DENIED_CALLER_RATION;
        final int shared = Math.max(0, spentAgainstCeiling);
        if (shared >= Math.max(1, sharedCeiling)) return Verdict.DENIED_SHARED_CEILING;
        return Verdict.SPEND;
    }

    /** {@link #mayClaim} against {@link #SHARED_CEILING}. */
    public static Verdict mayClaim(final Caller caller, final int spentByThisCaller,
            final int spentAgainstCeiling) {
        return mayClaim(caller, spentByThisCaller, spentAgainstCeiling, SHARED_CEILING);
    }

    // -- what each outcome says, and it must say what IT measured ----------------------------------

    /**
     * The refusal line. It has to say the quota is OURS, that the pool is THEIRS, and which of our
     * two bounds refused.
     *
     * <p>The person reading it is looking at an operation that stopped, and the available wrong
     * conclusions are that the peer has no key packages (nothing was asked, so nothing is known
     * about their pool) and that this caller is looping (the ceiling is spent by other callers too).
     */
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
            // A charge with no declared caller is refused as DENIED_CALLER_RATION, so this line runs
            // for it. It has no ration to name, and a log line that throws inside a refusal turns a
            // bounded refusal into a crash.
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

    /**
     * The line an unrefusable caller logs. It is charged like any other, and the line says both
     * halves — that it could not have been stopped, and that it cost the peer all the same.
     *
     * <p>"Unrefusable" alone reads as "free" and it is not: this is where {@link MlsFetchLedger}'s
     * exempt arms and these part company, because there the resource was a rate window and here it
     * is somebody's key material.
     */
    public static String describeUnrefusable(final Caller caller, final String peer,
            final int spentAgainstCeilingAfter, final int sharedCeiling) {
        return "MLS claim ledger UNREFUSABLE: " + name(caller) + "'s KeyPackage claim on "
                + safe(peer) + " cannot be refused — " + reasonUnrefusable(caller)
                + " — but it IS CHARGED, because the packages are really gone: " + peer + " is now "
                + "at " + Math.max(0, spentAgainstCeilingAfter) + " of " + Math.max(1, sharedCeiling)
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

    /** The accounting line for a charge that happened. */
    public static String describeCharge(final Caller caller, final String peer,
            final int spentByThisCallerAfter, final int spentAgainstCeilingAfter,
            final int sharedCeiling) {
        return "MLS claim ledger charged a KeyPackage claim to " + name(caller) + " on "
                + safe(peer) + " — " + Math.max(0, spentByThisCallerAfter) + " of "
                + Math.max(1, caller.ration) + " for this caller, "
                + Math.max(0, spentAgainstCeilingAfter) + " of " + Math.max(1, sharedCeiling)
                + " for this peer, per " + (WINDOW_MS / 60000L) + " min.";
    }

    /**
     * The line for a claim that WAS spent and came back with nothing.
     *
     * <h2>It is NOT a fact about the peer's pool, and it used to say that it was</h2>
     *
     * <p>The distinction this line was written for is real and is kept: our ledger REFUSING a claim
     * (nothing was asked) is different from a claim that was MADE (the KDS was asked).
     * <b>What it got wrong is everything after "MADE".</b> An empty result reaches the
     * app as bytes-or-null, so it cannot distinguish:
     *
     * <ul>
     *   <li><b>SERVED_NONE</b> — the peer really has published nothing, or has been drained.</li>
     *   <li><b>UNAUTHENTICATED</b> — OUR register token is dead and the KDS refused to talk to us.
     *       Device-measured: {@code grpcStatus=16}, while another device
     *       claimed from the SAME peer successfully 53 seconds later. The old wording named that
     *       peer and told the reader it alone blocked the upgrade. It was innocent and its pool was
     *       full.</li>
     *   <li><b>WRONG_KDS</b> — {@code mls-kds=-1 (fallback — no advertised kds)}, then
     *       {@code grpc-13} and {@code grpc-5 NOT_FOUND}. Same shape, different cause.</li>
     * </ul>
     *
     * <p>A fourth case does not reach here at all and is worth knowing about: a pool that answers
     * with a five-week-old identity. That claim SUCCEEDS, so nothing in this
     * class fires — only {@code kp_inspect}'s certificate window sees it.
     *
     * <p><b>This overload is the pre-contract-v60 wording and is still correct for it</b> — a
     * provider that cannot say which of the above happened leaves the reader with all of them. The
     * fix landed as contract v60: see {@link #describeEmptyClaim(Caller, String, int, Attribution,
     * String)}.
     *
     * <p>It did <b>not</b> plumb {@code KdsClient.UploadResult.statusCode} across the AIDL, which
     * is what this javadoc used to promise. It must not: that boundary is a VOCABULARY FIREWALL
     * (see {@code RcsMlsControlResult}) and a gRPC status crossing it would make a carrier
     * CPM/MSRP provider unable to report the same outcomes. The provider translates first and the
     * app branches on the MEANING.
     */
    public static String describeEmptyClaim(final Caller caller, final String peer,
            final int spentAgainstCeilingBefore) {
        return describeEmptyClaim(caller, peer, spentAgainstCeilingBefore, Attribution.UNKNOWN, null);
    }

    /**
     * WHO the empty claim is evidence about — contract v60's outcome, reduced to the only question
     * this line has to answer.
     *
     * <p>The provider translates its transport status before it crosses the AIDL boundary (a
     * VOCABULARY FIREWALL: no gRPC status, no {@code tachyonerror}, no Google error string), so this
     * enum is deliberately about ATTRIBUTION rather than about a protocol.
     */
    public enum Attribution {
        /**
         * The KDS <b>answered</b> and returned nothing for this peer. The only value that is
         * evidence about the CLAIM ON them — and still not a statement about what is IN their pool:
         * see {@link #describeEmptyClaim} for the third cause, which is a certificate-lifecycle
         * failure rather than anything the peer did.
         */
        PEER_HAS_NONE,
        /** The KDS refused US, refused the request, never answered, or was never asked. */
        NOT_ABOUT_THE_PEER,
        /** No outcome was available — an older provider, or a caller that did not ask for one. */
        UNKNOWN,
    }

    /**
     * The line for a claim that WAS spent and came back with nothing, with WHOSE fault it is when
     * the provider could say.
     *
     * <h2>Why even {@code PEER_HAS_NONE} does not blame the peer</h2>
     *
     * <p>The first version of this arm read "<i>this IS a fact about their pool — they have
     * published none, or one has been drained</i>". It names two causes and <b>there are three</b>,
     * and the third is not the peer's: RCC.16 <b>A.4.2.2</b> forbids the KDS to
     * RETURN a KeyPackage whose credential is inside
     * {@link MlsCredentialFloor#RCC16_MIN_REMAINING_DAYS}'s window, and a peer's one-time packages
     * and its last-resort embed the SAME leaf certificate — a device holds exactly one, so there is
     * no second credential for the two roles to differ by. A peer whose certificate has
     * aged past the floor has its WHOLE pool withheld while a sticky
     * {@code hasUploadedKeyPackages} keeps it advertising {@code mls-kds}: the right KDS is
     * dialled, it answers, the answer is empty, and the peer is innocent because a certificate
     * REFRESH failed. Devices have been measured
     * sitting at 25 days remaining for weeks, so it is the state a broken fleet is normally in —
     * exactly when this line would be read.
     *
     * <p><b>THE CITATION, traced to the spec text and corrected here.</b> It had been
     * <i>"A.4.2.1 §1(a) / A.4.2.2"</i> everywhere in this tree,
     * sourced to {@code MlsCertPolicy}'s own paraphrase rather than to RCC.16, and one half of it
     * does not say what it was cited for. <b>A.4.2.2</b> (v4.0 p.111 / v3.0 p.86) is the whole of
     * the withholding rule — <i>"KDSs must not return KeyPackages to a query where the credential
     * has less than 30 days before expiry"</i>, with a rationale that names the CLIENT's Add, so
     * it binds on a client claim. <b>A.4.2.1 §1(a)</b> only says <i>"Verify that the client
     * certificate has a remaining lifetime of at least 30 days"</i>, and A.4.2's scope list is
     * just <i>"KeyPackage update"</i> and <i>"Query receipt from a peer KDS"</i> — <b>a client's
     * claim is not in it</b>. What puts it in scope is <b>§5.3</b> (p.19, identical in v3.0 and
     * v4.0): <i>"The Home KDS shall validate the Client Credentials in the KeyPackages according
     * to Annex A.4.2."</i> Cite §5.3 alongside, or a reader checking A.4.2 will find the client
     * path absent and retract a conclusion that is correct.
     *
     * <p>Whether the KDS also withholds the LAST-RESORT is an inference and is marked as one:
     * the roles cannot differ by credential, but the wire CAN tell them apart (RFC
     * 9420 extension {@code 0x000A}), so a role-selective KDS is available and is not assumed away.
     * The wording above is safe under either answer because it stops at what the outcome supports.
     *
     * @param attribution what the provider's outcome reduces to; {@link Attribution#UNKNOWN} keeps
     *                    the honest-but-vague wording the contract-v60-less path must use
     * @param detail      the provider's human diagnostic, for the log only. NEVER parsed.
     */
    public static String describeEmptyClaim(final Caller caller, final String peer,
            final int spentAgainstCeilingBefore, final Attribution attribution,
            final String detail) {
        final String tail;
        switch (attribution == null ? Attribution.UNKNOWN : attribution) {
            case PEER_HAS_NONE:
                // WHAT THE OUTCOME SUPPORTS, AND NOT ONE WORD MORE. This arm used to
                // read "this IS a fact about their pool — they have published none, or one has
                // been drained", which is the SAME over-assertion this class exists to stop, one arm
                // over: it enumerates two causes when there are three, and the third is a
                // certificate-lifecycle failure on the fleet running this code. See the javadoc.
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
                + " claim(s) against this peer in the last " + (WINDOW_MS / 60000L) + " min. " + tail
                + (detail == null || detail.isEmpty() ? ""
                        : " [provider detail, do not parse: " + detail + "]");
    }

    /**
     * The line for a claim that was <b>NEVER ATTEMPTED</b> — contract v60's
     * {@code OUTCOME_NOT_ATTEMPTED}, the one outcome that asserts no dial was spent.
     *
     * <h2>Why this is a third frame and not a fourth arm of {@link #describeEmptyClaim}</h2>
     *
     * <p>This ledger had exactly two words for what became of a claim: <b>REFUSED</b> — by us,
     * before asking ({@link #describeRefusal}) — and <b>MADE</b> — we asked
     * ({@link #describeEmptyClaim}, whose frame hard-codes <i>"was MADE and came back EMPTY"</i>).
     * {@code NOT_ATTEMPTED} is neither, and for want of a third word it printed as the second:
     * device-measured, a claim that spent no dial and no KeyPackage was logged as
     * one that <i>was MADE</i>.
     *
     * <p>The {@link Attribution} is NOT the thing that was wrong and is not what this fixes. A
     * never-sent claim really is {@link Attribution#NOT_ABOUT_THE_PEER} — that arm's tail already
     * ends <i>"…or it was never sent"</i> — so the reduction was honest and the FRAME asserted an
     * action that did not happen. Which is the same defect class with the subject
     * changed: there a line over-stated what was known about a PEER, here it over-states what WE
     * did. Keeping it out of the attribution also keeps the vocabulary firewall's three values
     * intact, which matters because {@code NOT_ATTEMPTED} is not a report ABOUT anything the
     * provider observed — it is a fact about our own stack, synthesised on this side of the binder.
     *
     * <h2>It says the charge came back, because the charge came back</h2>
     *
     * <p>{@code MlsProviderTransport.spendOneClaim} charges BEFORE it asks, and that ordering is
     * right (a claim that happened and went uncounted leaves the next caller told
     * the pool is fuller than it is). So by the time this line is written the phantom charge has
     * already been taken and is REVERSED by {@link MlsClaimLedgerRecord#refunded}. The count printed
     * here is therefore the count AFTER the refund, and the line says so — a reader comparing two
     * ledger lines a minute apart must be able to tell a refund from a claim that never showed up.
     *
     * <h2>{@link #NO_COUNT} exists so the refund cannot be CLAIMED without being made</h2>
     *
     * <p>A peer whose stored ledger is unreadable has no count to state and no charge that can be
     * accounted for — and the first draft of this method printed {@code Math.max(0, …)} for it,
     * which reads as "back at 0 of 4": a refund asserted on a record nobody could read. That is this
     * same defect inside its own fix, so the two states get visibly different sentences and
     * the unreadable one never says the word REFUNDED.
     *
     * @param spentAgainstCeilingAfterRefund this peer's window count with the charge given back, or
     *                                       {@link #NO_COUNT} when this peer's ledger is UNREADABLE
     *                                       and neither the charge nor the count can be stated
     * @param detail                         the provider's human diagnostic, for the log only.
     *                                       NEVER parsed.
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

    /**
     * "This peer's ledger is unreadable, so no count can be stated" — the one non-count
     * {@link #describeNotAttempted} accepts, and negative so it can never collide with a real
     * window count.
     *
     * <p>Named rather than spelled {@code -1} at the call sites for the same reason that gave the
     * claim sink its own sentinel: a magic number in a position that otherwise holds a count is read
     * as a count by the next person to touch it.
     */
    public static final int NO_COUNT = -1;

    /**
     * The ERA ADVANCE's line for a member it could not claim a KeyPackage for.
     *
     * <h2>Why this is separate from {@link #describeEmptyClaim}</h2>
     *
     * <p>That line is the LEDGER's: it is emitted first, and it says everything there is to say
     * about the CLAIM. This one is the ADVANCE's, and it says the CONSEQUENCE — the new era would be
     * short a member the server expects, so the advance stops rather than building a partial roster.
     * Two lines, two subjects.
     *
     * <p>It deliberately does NOT restate the ledger's three causes. Two lines explaining the same
     * thing in different words is exactly how the two drift apart, and the ledger's wording is the
     * one under test.
     *
     * <h2>The defect this closes</h2>
     *
     * <p>The consequence sentence used to be UNCONDITIONAL and to name the member: <i>"no KeyPackage
     * for +1…; the new era would be missing a member the server expects"</i>. On a device whose own
     * KDS credentials are dead that reads as an accusation against a peer whose pool is full — one
     * device returned {@code UNAUTHENTICATED} on every claim while another claimed from the same
     * peers seconds later, so the advance would have named an innocent device and sent an operator
     * to go and look at it. Contract v60 fixed the ledger's wording; this call site
     * did not inherit it.
     *
     * <p><b>{@link Attribution#UNKNOWN} keeps the pre-v60 posture</b> and must: a provider that
     * cannot say which happened leaves the reader with both, and inventing a verdict for it here
     * would be the same defect pointing the other way.
     *
     * @param peer        the member no package came back for
     * @param attribution what the provider's outcome reduces to, from the same claim
     */
    public static String rosterClaimBlockedLine(final String peer, final Attribution attribution) {
        return blockedByEmptyClaim(peer, attribution,
                "era advance ABORTED — no KeyPackage came back for " + safe(peer) + ", and the new "
                        + "era would be short a member the server expects, so this builds NO roster "
                        + "rather than a partial one.");
    }

    /**
     * The 1:1 CREATE's line — {@code ensureReady}.
     *
     * <p>A named wrapper rather than a call site assembling the sentence, for a reason specific to
     * this file: {@code ensureReady} is in DoD-5's MIXED bucket and that guard is a strict two-way
     * ratchet, so the wording has to live somewhere it can grow without moving the transport's line
     * count. Here it is also under test, which the call site's string literal never was.
     */
    public static String oneToOneCreateBlockedLine(final String peer,
            final Attribution attribution) {
        return blockedByEmptyClaim(peer, attribution, "NOT creating the 1:1 with " + safe(peer)
                + " — no KeyPackage came back, and the group is built FROM it, so there is no "
                + "weaker create to fall back to.");
    }

    /**
     * The GROUP CREATE's line — {@code gatherInitialKeyPackages}.
     *
     * <p><b>This is the site the whole split was for.</b> It came through the PLURAL claim, which
     * until contract v60's outcome sink could not report anything, so the create path printed the
     * ledger's UNKNOWN wording — <i>"DO NOT conclude the peer is at fault without reading
     * MlsProvider.KdsClient for the gRPC status"</i> — and then named the peer two lines later and
     * refused to create a group that would omit them. The two lines did not merely differ in
     * confidence, they instructed the reader in OPPOSITE DIRECTIONS, and the confident one was the
     * one with a phone number in it. Device-measured by {@code claim-attrib}: same peer, same cause,
     * 62 seconds apart, {@code outcome=2 (NOT_AUTHORIZED)} logged for both.
     *
     * <p>{@link Attribution#UNKNOWN} is also the honest answer on a path this class did not create:
     * a member covered by a PRE-CLAIM that carried nothing for them was never claimed for live, so
     * no outcome about them exists.
     */
    public static String establishGroupBlockedLine(final String peer,
            final Attribution attribution) {
        return blockedByEmptyClaim(peer, attribution, "NOT creating the group — no KeyPackage came "
                + "back for " + safe(peer) + ", and a group that omits a member the server expects "
                + "is not a weaker create, it is a wrong one.");
    }

    /** The ADD's line — {@code addMember}. */
    public static String addMemberBlockedLine(final String peer, final Attribution attribution) {
        return blockedByEmptyClaim(peer, attribution, "NOT adding " + safe(peer)
                + " — no KeyPackage came back, and an Add is built FROM it.");
    }

    /**
     * <b>One empty-claim line for any caller: a CONSEQUENCE the caller states, and an ATTRIBUTION
     * clause that lives here and nowhere else.</b>
     *
     * <h2>Why the split, rather than a sibling per call site</h2>
     *
     * <p>Four places in the transport refuse an operation because no KeyPackage came back, and their
     * CONSEQUENCES genuinely differ — an era advance builds no roster, a 1:1 create makes no
     * conversation, an add does not happen, a group create would omit a member. What must NOT differ
     * is who we say the block belongs to. A sibling method per caller is four copies of that
     * judgement, and a wording that exists in four places is one that will disagree with itself: the
     * defect these lines were written against is precisely a sentence asserting more than
     * the outcome supports, and it only had to be written once to do the damage.
     *
     * <p>So the caller owns what it did, and this owns what that means about the peer.
     *
     * <h2>What each arm may say</h2>
     *
     * <p>{@link Attribution#PEER_HAS_NONE} is the only outcome that is evidence about them, and even
     * it DEFERS to {@link #describeEmptyClaim}'s three causes rather than restating them — every
     * caller of this logs that line immediately before, so the causes are already on screen and a
     * second telling is a second thing to get wrong. {@link Attribution#NOT_ABOUT_THE_PEER} sends the
     * reader to OUR state and says so in as many words. {@link Attribution#UNKNOWN} blames nobody:
     * inventing a verdict for the pre-v60 case would be the same defect pointing the other way.
     *
     * @param peer        the member no package came back for
     * @param attribution what the provider's outcome reduces to, from the same claim
     * @param consequence what the CALLER did about it — a complete sentence, ending in a full stop
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
                // "OUR OWN KDS CREDENTIALS" WAS TOO SPECIFIC, and a later fix made the gap
                // reachable: a claim that never reached a server at all (grpcStatus=-1 TRANSPORT,
                // net::ERR_NAME_NOT_RESOLVED) now reports TRANSPORT_FAILED honestly instead of
                // REFUSED, and it reduces to THIS arm. Sending that operator to check tokens is
                // the same defect one layer over — a line pointing confidently at the wrong place.
                // The outcome cannot distinguish them here by design (the vocabulary firewall
                // keeps this to three values), so the line names both and the provider's detail
                // string in the LEDGER line directly above says which.
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
        return (s == null || s.isEmpty()) ? "<no peer>" : s;
    }
}

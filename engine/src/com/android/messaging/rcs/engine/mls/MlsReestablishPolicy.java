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
 * Is this re-establish a FIRST create, or a RE-creation every member must re-join by Welcome?
 *
 * <h2>Why this decision needed a class of its own</h2>
 *
 * <p>{@code MlsPeerGuard.allowEraAdvance} — guardrail G2 — bounds the operation
 * that wedged a real person's phone for a month: the era advance, which re-creates the group and
 * makes <b>every member re-join via a Welcome</b>. It was called from exactly one place, the
 * {@code eraAdvance} funnel. But {@code rebuildConversation} reaches the same peer-facing cost by a
 * different door: it drops both halves of our state and re-establishes, which by design lands above
 * the era the server holds. Same cost, no charge.
 *
 * <p>So the guard needs a predicate for "will this re-establish make peers re-join", and the
 * predicate has to be right about the cases that are easy to get wrong.
 *
 * <h2>The obvious predicate is wrong, and this class exists to say why</h2>
 *
 * <p>The natural test is <b>"is a GroupInfo carry present"</b> — the carry is what makes
 * {@code plan_group} take ARM 2 and derive {@code era = server_era + 1} instead of being born at
 * {@link #ERA_INITIAL}. It is wrong twice over:
 *
 * <ul>
 *   <li><b>A 1:1 rebuild never has a carry</b> and re-creates anyway. The group form fetches the
 *       server's GroupInfo and hands it down as bytes; the 1:1 form has no such fetch — it reaches
 *       {@code server_era + 1} through {@code ensureReady}'s reclaim arm, driven by the
 *       server naming its own group id in a refusal. Carry absent, re-join certain. That is the
 *       incident's own shape.</li>
 *   <li><b>A group in the REJOIN state has no carry either</b>: the pack is fetched with our local
 *       group as the question, and REJOIN means we hold none. The server still holds the
 *       conversation, so the create lands on top of it.</li>
 * </ul>
 *
 * <p><b>What actually decides it is whether the SERVER holds this conversation.</b> That is one
 * read, it is the same question {@code reestablishOutbound} already asks before driving a create
 * ("the server still holds this conversation … a create would not re-join it"), and it is a
 * measurement rather than an inference from a parameter that happens to correlate.
 *
 * <h2>An unreadable answer is charged, and it is a distinct verdict</h2>
 *
 * <p>"The server did not answer" is not "the server holds nothing" — they must never share a return
 * value, because the cheap outcome would then be the one a network blip selects. It gets its own
 * verdict, it is charged, and the log says the charge was made on an answer we did not get.
 */
public final class MlsReestablishPolicy {

    /** The era a group with nothing to inherit is born at. Mirrors the transport's constant. */
    public static final long ERA_INITIAL = 1L;

    /**
     * How long before we will try to re-establish MLS with the same peer again.
     *
     * <p><b>Not a politeness delay.</b> A re-establish CLAIMS A PEER KEYPACKAGE, and a peer's
     * one-time pool is small and not replenished by us. A burst of undecryptable inbound — which is
     * exactly the situation this fires in — would otherwise drain that pool in seconds and leave the
     * peer serving last-resort, which is a state we detect separately.
     *
     * <p>Ten minutes. Declared here because this class already answers what
     * a re-establish COSTS A PEER, and how often we may impose that cost is the same question with a
     * clock on it. The durable stamp stays in {@code MlsPeerGuard} — the window
     * is arithmetic, the stamp is storage, and only the storage needs a {@code Context}.
     */
    public static final long REESTABLISH_COOLDOWN_MS = 10L * 60L * 1000L;

    /** Whether the peers of this conversation must RE-join for the re-establish to take. */
    public enum ReJoin {
        /** Measured: the server holds this conversation, so its members are in a group already. */
        YES,
        /** Measured: the server holds nothing here, so every member joins for the first time. */
        NO,
        /** Not measured — the server did not answer. */
        UNKNOWN
    }

    /**
     * What a re-establish of this conversation would be.
     *
     * <p>The two flags are declared independently on purpose. {@code chargesEraBudget} is not
     * derived from {@code reJoin} in code, so a constant added later with the two out of step is a
     * TEST FAILURE rather than a silently uncharged door — which is exactly the defect that was
     * found here once (the rebuild path re-created groups for months while G2 counted nothing).
     */
    public enum Verdict {
        /** Nothing server-side to re-create. Not an era advance and must not be charged as one. */
        FIRST_CREATE(ReJoin.NO, /*chargesEraBudget=*/ false),
        /** The server holds it: re-establishing lands above its era and every member re-joins. */
        RECREATES_EXISTING(ReJoin.YES, /*chargesEraBudget=*/ true),
        /** We could not read the server. Charged — an unclassifiable rebuild is not the cheap one. */
        SERVER_UNREADABLE(ReJoin.UNKNOWN, /*chargesEraBudget=*/ true);

        private final ReJoin mReJoin;
        private final boolean mChargesEraBudget;

        Verdict(final ReJoin reJoin, final boolean chargesEraBudget) {
            mReJoin = reJoin;
            mChargesEraBudget = chargesEraBudget;
        }

        /** Whether the peers must re-join — including that we could not tell. */
        public ReJoin reJoin() {
            return mReJoin;
        }

        /**
         * Whether this re-establish must be charged to the era-advance budget (G2) before it runs.
         *
         * <p>Only a measured {@link ReJoin#NO} is free.
         */
        public boolean chargesEraBudget() {
            return mChargesEraBudget;
        }
    }

    private MlsReestablishPolicy() {}

    /**
     * Classify a re-establish from the server's own answer.
     *
     * @param serverAnswered whether the server-era read returned anything at all
     * @param serverEra      the era it reported; meaningless unless {@code serverAnswered}
     */
    public static Verdict classify(final boolean serverAnswered, final long serverEra) {
        if (!serverAnswered) return Verdict.SERVER_UNREADABLE;
        return serverEra >= ERA_INITIAL ? Verdict.RECREATES_EXISTING : Verdict.FIRST_CREATE;
    }

    /**
     * Would this re-establish be BORN AT {@link #ERA_INITIAL} <em>on top of</em> a group the server
     * already holds — i.e. a FORK rather than a repair?
     *
     * <h2>The gap between this class's two halves</h2>
     *
     * <p>{@link Verdict#RECREATES_EXISTING}'s javadoc says "re-establishing lands above its era",
     * and the class javadoc above says the carry "is what makes {@code plan_group} take ARM 2 and
     * derive {@code era = server_era + 1} instead of being born at {@link #ERA_INITIAL}". Both are
     * true separately and they cannot both be true at once when the carry is ABSENT: with nothing
     * local (the rebuild has just forgotten it) and nothing carried, {@code plan_group} takes ARM 1
     * and the new group is born at {@link #ERA_INITIAL}. The landing is asserted by one half and
     * refuted by the other, and nothing read the pair together.
     *
     * <p>{@link #classify} is deliberately unchanged and must stay so: the CHARGE does not depend on
     * the carry, which is the whole of that finding and is pinned by its own test. The
     * charge asks "will peers re-join"; this asks "will the thing we are about to build be the
     * server's group or a second one beside it". Different questions, and only the second one needs
     * the carry.
     *
     * <h2>What happens when it is true, measured</h2>
     *
     * <p>Device-established on {@code deviceC}, group {@code b1189d9c…}, 2026-09-08
     * 17:49:39 (rebuild charged) → 17:49:43 (engine group written): the server held the group at
     * era 1, the pack carried no GroupInfo, the re-establish was born at era 1, and the transport's
     * post-create check compares {@code serverEra != targetEra} — {@code 1 != 1} is false, so the
     * create was ADOPTED. The device has held a complete three-member era-1 group the server has no
     * trace of ever since, with itself at leaf 0 of its own tree while the server's tree puts it at
     * leaf 2.
     *
     * <p>And when the server's era is ABOVE {@link #ERA_INITIAL} the same build is rolled back
     * instead — so the carry-less arm either forks or wastes a rebuild that destroyed both halves of
     * our state first. There is no era at which it repairs anything.
     *
     * <h2>Why only a measured yes</h2>
     *
     * <p>{@link Verdict#SERVER_UNREADABLE} is deliberately NOT included. It is charged like a
     * re-creation because the outcome we cannot measure must not be the cheap one — but refusing on
     * it would block the FIRST encryption of a group whose server read merely blipped, which is a
     * worse trade than the fork it would sometimes avoid. Widen this only with a measurement.
     *
     * @param isGroup    whether this is a group conversation; a 1:1 never has a carry and
     *                   re-establishes through a different path entirely (see the class javadoc)
     * @param verdict    what {@link #classify} made of the server's own answer
     * @param haveCarry  whether a server GroupInfo is in hand to hand to the engine
     */
    public static boolean forksAtEraInitial(final boolean isGroup, final Verdict verdict,
            final boolean haveCarry) {
        return isGroup && !haveCarry && verdict == Verdict.RECREATES_EXISTING;
    }

    /**
     * Would this re-establish reach the wire as a create the server <b>does not apply</b>, so that
     * it cannot converge no matter what era it asks for?
     *
     * <h2>The half of the rebuild arm {@link #forksAtEraInitial} does not cover</h2>
     *
     * <p>{@code forksAtEraInitial} refuses the CARRY-LESS group rebuild, on the argument that with
     * nothing local and nothing carried {@code plan_group} takes ARM 1 and is born at
     * {@link #ERA_INITIAL} beside the server's group. Its sibling assertion — that WITH a carry the
     * engine derives {@code server_era + 1} — is true and was read as "so the with-carry arm is a
     * repair". <b>Nobody had measured the with-carry arm.</b> The era the engine derives is not what
     * decides whether the server applies the create.
     *
     * <h2>What decides it, measured 8/8</h2>
     *
     * <p><b>A {@code CreateMlsConversation} that reuses a {@code contextId} the
     * server already holds does not move the server's era</b>, and says nothing — gRPC OK, no
     * trailer, and the era re-read from the server is unchanged. Four alternating 1:1 trials
     * and four GROUP trials across two purpose-built groups, with ordinal
     * position, era value and group identity each controlled out. Minted is granted, stored is not
     * applied. ("Refuses", "de-duplicates" and "creates something
     * else" all fit the measurement and only "does not apply" is measured; this predicate needs no
     * more than that.)
     *
     * <h2>Why a GROUP rebuild always reuses one and a 1:1 rebuild never does</h2>
     *
     * <p>Both rebuild shapes drop the provider's record first. That is what makes the 1:1 work: the
     * provider's contextId resolver finds no stored record and MINTS a fresh one, which is
     * the 1:1 recovery working <em>by accident</em>.
     * A GROUP never reaches that branch — the resolver's group arm returns the ENGINE's reported id,
     * which for us is the ASCII RCS group id, and it is chosen BEFORE the store is consulted at all.
     * The RCS group id does not change with the era, so <b>every group re-create REACHED FROM A
     * REBUILD reuses the contextId the server already holds, by construction</b>.
     *
     * <p>The qualifier is not decoration. {@code MlsProviderTransport.freshContextIdRetry}
     * re-dials a create with a MINTED id and is the one path for which the
     * unqualified sentence is false — it is scoped to {@code eraAdvanceLocked} and does not reach a
     * rebuild. Dropping the qualifier is how the next reader concludes a group can never get a
     * fresh contextId and stops looking for the cheap fix.
     *
     * <p><b>Three arms out-rank the group one, and none of them is reachable from a rebuild.</b>
     * Re-derived against the fresh-contextId retry, which re-ordered the resolver while this was
     * being written; the conclusion is unchanged and the reasons are worth having written down:
     *
     * <ul>
     *   <li>{@code debug.rcs.mls_advance_fresh_ctxid} is evaluated FIRST — it does not sit below
     *       the group arm — but it is INERT here for a different reason: it fires only when a
     *       provider record still exists under this MLS group id, and a rebuild's first act is to
     *       delete that record. No setting of it reaches this path.</li>
     *   <li>An explicit Google Messages-shaped {@code contextId} from the caller out-ranks the group arm
     *. The rebuild's caller passes the RCS group id, which is not that shape —
     *       and <b>even if some RCS group id ever were 24 base64url characters, the value returned
     *       would still BE the RCS group id</b>, so the answer does not depend on the shape test.
     *       A future caller that passed a MINTED id would change this, and would then have to
     *       change this predicate with it.</li>
     *   <li>{@code debug.rcs.mls_ctxid_group_engine_id=0} turns the group arm off. That is the one
     *       client-side lever that can change the answer today, which is why it is this predicate's
     *       third argument rather than a constant.</li>
     * </ul>
     *
     * <h2>What it costs when it runs anyway — device-observed, twice</h2>
     *
     * <p>Measured on a device: DIVERGED detected
     * correctly, both halves dropped, a KeyPackage claim and an era-budget charge spent, the engine
     * built era 2 — and the server, read again 46 seconds later, still held <b>era 1 epoch 7</b>,
     * unchanged. The create did not take. The conversation ended in {@code REJOIN}, needing a
     * Welcome only a peer can send, with its local state gone. The charge is <b>per attempt</b>: a
     * second attempt two minutes later logged the identical era-budget line. The same outcome had
     * already been recorded three times before ("on a GROUP that rung historically
     * returns {@code rebuilt=false} and leaves the conversation in REJOIN. Three fixtures were
     * destroyed that way") — it was refused at the floor-rebuild site only, and the automatic
     * recovery arms kept the behaviour on the argument that the destructive rebuild "has actually
     * been proven to work end to end". <b>That proof was a 1:1, and only a 1:1.</b>
     *
     * <h2>Scope, stated so it is not widened by accident</h2>
     *
     * <p>{@code contextIdIsReused} must be a fact the caller READ, not an assumption: passing
     * {@code false} means "the group arm is off, so the resolver may mint", which is not the same as
     * "a fresh contextId is guaranteed" — with the group arm off the resolver still consults the
     * stored record for the first member. Refusing only the CERTAIN case is deliberate: a predicate
     * that answers true too often blocks the first encryption of a conversation, which is the trade
     * {@link #forksAtEraInitial} states for {@link Verdict#SERVER_UNREADABLE} and it applies here
     * unchanged. A 1:1 is never refused: its rebuild is the one that mints, and it is the only
     * recovery a 1:1 that lost its group has.
     *
     * @param isGroup          whether this is a group conversation
     * @param verdict          what {@link #classify} made of the server's own answer
     * @param contextIdIsReused whether the create this rebuild drives will go out under the
     *                          contextId the server already holds for this conversation
     */
    public static boolean reCreateWouldNotTake(final boolean isGroup, final Verdict verdict,
            final boolean contextIdIsReused) {
        return isGroup && contextIdIsReused && verdict == Verdict.RECREATES_EXISTING;
    }

    /** One line for the log, saying what was measured rather than what was assumed. */
    public static String wouldNotTakeLine(final long serverEra) {
        return "the server holds this group at era " + serverEra + " and the re-create would go out "
                + "under the contextId it ALREADY HOLDS — for a group that id is the RCS group id "
                + "and it does not change with the era, so there is no era at which this create "
                + "takes. That rule was measured 8/8 with the server era read back every "
                + "trial, and this arm has been watched spending a KeyPackage claim and an era-budget "
                + "charge, drop BOTH halves of our state, and leave the conversation in REJOIN with "
                + "the server still at the era it started from. A GroupInfo carry does not change "
                + "it: the carry decides the era the ENGINE derives, not whether the SERVER applies "
                + "the create. Refused BEFORE anything is dropped or charged. The exit "
                + "is a member who IS current re-admitting us — asked for on the §7.7.2.2 channel "
                + "at this refusal — or an operator setting debug.rcs.mls_ctxid_group_engine_id=0 "
                + "so the re-create can mint a fresh contextId.";
    }

    /** One line for the log, saying what was measured rather than what was assumed. */
    public static String forkLine(final long serverEra) {
        return "the server holds this group at era " + serverEra + " and we have NO GroupInfo to "
                + "carry, so the re-establish would be born at era " + ERA_INITIAL
                + " — a second group beside the server's, not a repair of it. At THAT era the "
                + "create is adopted (the transport compares era NUMBERS and they match) and the "
                + "fork is permanent; above it the create is rolled back and both halves of our "
                + "state were destroyed for nothing. Refused BEFORE anything is dropped.";
    }

    /** One line for the log, saying what was measured rather than what was assumed. */
    public static String line(final Verdict v, final long serverEra) {
        switch (v) {
            case FIRST_CREATE:
                return "the server holds no group for this conversation, so this create makes "
                        + "nobody RE-join — not charged to the era budget";
            case RECREATES_EXISTING:
                return "the SERVER holds this conversation at era " + serverEra + ", so "
                        + "re-establishing re-creates it and every member must re-join by Welcome "
                        + "— charged to the era budget, the same budget an era advance charges";
            case SERVER_UNREADABLE:
            default:
                return "the server did not answer when asked whether it holds this conversation, "
                        + "so we cannot tell a first create from a re-creation — charged to the era "
                        + "budget, because the outcome we cannot measure must not be the cheap one";
        }
    }
}

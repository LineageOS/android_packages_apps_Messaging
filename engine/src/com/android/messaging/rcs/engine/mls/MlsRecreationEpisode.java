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
 * When one recovery episode charges G2 <b>again</b>.
 *
 * <h2>The question</h2>
 *
 * <p>{@link MlsReestablishPolicy} answers <i>"is this re-establish a re-creation at all?"</i>. This
 * class answers the one after it: <i>"a charge is already spent in this episode — does THIS door
 * need its own?"</i>. They are separate on purpose. The first is a measurement about the server; the
 * second is bookkeeping about what we have already done in the last few hundred milliseconds, and
 * conflating them is how the same re-creation gets charged twice by two methods that each believe
 * they are the only one counting.
 *
 * <h2>Four doors reach G2 inside one recovery episode, and until this class three of them were
 * reconciled and the fourth was not</h2>
 *
 * <table>
 *   <caption>The interaction catalogue</caption>
 *   <tr><th>edge</th><th>how it was reconciled before</th><th>{@link PriorCharge}</th></tr>
 *   <tr><td>{@code rebuildConversation} → {@code ensureReady}</td>
 *       <td>the {@code recreateAlreadyCharged} boolean parameter</td>
 *       <td>{@link PriorCharge#SPENT_AND_UNCONSUMED} — the rebuild charged on the CLASSIFICATION,
 *           before any create was put to the server</td></tr>
 *   <tr><td>{@code ensureReady}'s era-bump arm → its reclaim arm</td>
 *       <td>the {@code recreateCharged} local</td>
 *       <td>{@link PriorCharge#SPENT_AND_UNCONSUMED} — the reclaim arm is reachable ONLY through the
 *           bump's create being REFUSED (that refusal is what names the group id), so nobody
 *           re-joined on it</td></tr>
 *   <tr><td>{@code eraAdvance} → the rebuild fallback</td>
 *       <td><b>nothing</b> — this is the case</td>
 *       <td>{@link PriorCharge#SPENT_AND_CONSUMED} — see below</td></tr>
 *   <tr><td>G2 × {@code MlsRebuildLimiter}</td>
 *       <td>{@code MlsReestablishPolicy.classify(…).chargesEraBudget()} — a different question, and
 *           already right</td>
 *       <td>n/a</td></tr>
 * </table>
 *
 * <h2>The fallback edge charges twice, and that is CORRECT</h2>
 *
 * <p>The premise was that the fallback arm is the one where nothing was inflicted on the peer
 * — the server "silently refused" the advance, so the first charge paid for an operation the peer
 * never saw. <b>That premise does not survive reading the arm.</b> To reach it,
 * {@code eraAdvanceLocked} must have:
 *
 * <ul>
 *   <li>claimed one KeyPackage per member from the server's roster and checked each is usable;</li>
 *   <li>built a real group through {@code createGroupPlanned} — the primitive invariant I2
 *       enumerates from — with a real Welcome for every member;</li>
 *   <li>had {@code createMlsConversation} return <b>OK</b>, carrying that Welcome; and</li>
 *   <li>then read the server's era and found it unmoved.</li>
 * </ul>
 *
 * <p>So the peer-facing resources G2 exists to bound were spent. What was measured is that the era
 * did not move; whether a member re-joined on that accepted RPC is <b>not measured and cannot be
 * measured from here</b>. An outcome we cannot measure must not be the cheap one — the same rule
 * {@link MlsReestablishPolicy.Verdict#SERVER_UNREADABLE} already applies one question over. The
 * rebuild that follows is then a genuinely second re-creation attempt, with its own KeyPackage claims
 * and its own Welcomes, so it pays again.
 *
 * <p><b>The behaviour is therefore unchanged by this class.</b> What changes is that the answer is
 * now a named predicate with the same shape as its three neighbours, instead of the one edge in the
 * event with nothing said about it at all.
 */
public final class MlsRecreationEpisode {

    private MlsRecreationEpisode() {}

    /** What G2 has already been charged in the episode reaching this door. */
    public enum PriorCharge {
        /** Nothing charged yet. This door pays. */
        NONE,
        /**
         * A charge is spent, and it has <b>not</b> yet bought a re-creation the server took: either
         * no create has been put to the server at all, or the one that was is known to have been
         * refused. The peer cannot have re-joined on it, so the spent charge still covers this door.
         */
        SPENT_AND_UNCONSUMED,
        /**
         * A charge is spent and the server <b>accepted</b> the re-creation it paid for. That charge
         * is used up; a further re-creation in the same episode is a second peer-facing event.
         *
         * <p>"Accepted" means the create RPC returned ok. It deliberately does <b>not</b> mean the
         * repair worked — the fallback arm is precisely the case where it was accepted and the
         * server's era did not move.
         */
        SPENT_AND_CONSUMED,
    }

    /**
     * Classify what the episode has already charged.
     *
     * @param spent whether G2 has been charged at all in this episode — in production this is
     *     {@code MlsReestablishPolicy.Verdict.chargesEraBudget()}, the {@code recreateCharged} local,
     *     or the fact that {@code eraAdvance}'s funnel charge succeeded
     * @param consumedByAnAcceptedRecreation whether the create that charge paid for was ACCEPTED by
     *     the server ({@code RcsMlsControlResult.ok()}). Meaningless, and ignored, when nothing was
     *     spent — the same shape as {@link MlsReestablishPolicy#classify(boolean, long)}'s
     *     {@code serverEra}
     */
    public static PriorCharge priorCharge(final boolean spent,
            final boolean consumedByAnAcceptedRecreation) {
        if (!spent) return PriorCharge.NONE;
        return consumedByAnAcceptedRecreation
                ? PriorCharge.SPENT_AND_CONSUMED : PriorCharge.SPENT_AND_UNCONSUMED;
    }

    /**
     * <b>The reconciliation predicate.</b> Must this door charge G2 itself?
     *
     * <p>Stated over the enum rather than over the two booleans so a state added later has to answer
     * the question explicitly instead of falling into whichever arm an {@code if} happens to reach.
     */
    public static boolean needsItsOwnCharge(final PriorCharge prior) {
        switch (prior) {
            case NONE:
            case SPENT_AND_CONSUMED:
                return true;
            case SPENT_AND_UNCONSUMED:
            default:
                return false;
        }
    }

    /** One line for the log, saying what was already paid and why this door does or does not pay. */
    public static String line(final PriorCharge prior) {
        switch (prior) {
            case NONE:
                return "nothing has been charged to the era budget in this recovery episode, so this "
                        + "re-creation pays for itself";
            case SPENT_AND_UNCONSUMED:
                return "the era budget was already charged in this episode and the server has not "
                        + "taken a re-creation on it — no member can have re-joined yet, so this is "
                        + "the SAME re-creation reached by a second door and it is not charged again";
            case SPENT_AND_CONSUMED:
            default:
                return "the era budget was already charged in this episode AND the server ACCEPTED "
                        + "the re-creation it paid for — a Welcome was minted per member and a "
                        + "KeyPackage claimed for each, whatever the server then did with the era. "
                        + "This is a SECOND peer-facing re-creation and it is charged again";
        }
    }
}

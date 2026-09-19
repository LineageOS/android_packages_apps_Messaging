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

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>DoD-5 — no decision in the transport is unreachable from a host test.</b> Decoupling plan
 * §7, Stage 7.
 *
 * <p>The DoD row reads: <i>"re-run {@code tools/mls/transport-classify.py}. The mixed bucket must
 * reach 0, or every remaining member must appear in Stage 0's exemption list naming the source-scan
 * test that covers it."</i> This test re-runs it — the actual checked-in tool, in a subprocess, over
 * the actual working-tree source — rather than re-implementing the classifier in Java.
 *
 * <h2>Why it shells out instead of reimplementing</h2>
 *
 * <p>Two instruments measuring one property is how they come to disagree, and this instrument's
 * own history is exactly that story told about its two halves: {@code transport-classify.py}'s brace
 * matcher read comment-stripped source while its tagger read raw, so a {@code &#64;link} in a
 * javadoc counted as a reference and three methods sat in the MIXED bucket on the strength of prose.
 * A Java reimplementation here would be a second tagger, and the first divergence between them would
 * be invisible until someone re-derived both. So the tool stays the instrument, this test stays the
 * enforcement, and {@link #theClassifierRanAndClassifiedTheWholeFile} fails loudly if the tool could
 * not be run at all — never skips.
 *
 * <h2>The baseline is deliberately the published one</h2>
 *
 * <p>DoD-5 is asserted against <b>46 methods / 5,802 body lines</b>, measured at {@code 1e50f59c}
 * with the instrument as published. Three defects were then fixed in that instrument
 * and the plan records the before/after rather than absorbing it, because moving the instrument and
 * the target in one step makes the work unfalsifiable. What this test ratchets is therefore today's
 * number from today's tool, with the published baseline kept beside it in the failure message.
 *
 * <h2>The exemption list is a set of assertions, not a set of names</h2>
 *
 * <p>Stage 0's §5.1 and §5.3 exempt eight methods. Each row here carries a COVERING CLAIM that this
 * test checks against the method's brace-matched body — a fixture guard the debug arms must keep, or
 * the policy constant that leaves under DoD-1. An exemption is only worth what a
 * reader can check, and an exemption for a method that no longer exists is inherited silently by the
 * next method to take that name.
 */
public final class MlsTransportDecisionCoverageGuardTest {

    private static final String TOOL = "tools/mls/transport-classify.py";

    /** As published, at {@code 1e50f59c}. Kept for the failure message; not the ratchet. */
    private static final int PUBLISHED_BASELINE_METHODS = 46;
    private static final int PUBLISHED_BASELINE_LINES = 5802;

    /**
     * Today's measurement with the corrected instrument, 2026-09-09.
     *
     * <p><b>33 / 4921 -> 32 / 4930, and it moved in BOTH directions.</b> One
     * method left the bucket and the line count rose by nine. Stated that way rather than as an
     * improvement, because it is not one on the measure this constant actually ratchets.
     *
     * <p><b>The method that left is {@code addMember}</b>, and it is worth naming: it was
     * {@code NET+GUARD} at 57 lines — it dialled the provider ({@code
     * pt("claimPeerKeyPackage").claimPeerKeyPackage(...)}) AND carried a policy decision, which is
     * exactly what this bucket is. A later change routed that claim through
     * {@code MlsProviderTransport.claimOne}, so {@code addMember} no longer reaches the provider at
     * all. That is the mechanism DoD-5 is asking for rather than a coincidence: putting an effect
     * behind a wrapper takes the effect out of every method that used to perform it, and a ledger's
     * charge point IS a wrapper.
     *
     * <p><b>The nine lines are the price</b>, and they are the same change's other half: three
     * in-scope methods gained a refusal arm ({@code ensureReady} 476 -> 499, {@code establishGroup},
     * {@code eraAdvanceLocked}), which is +66 against {@code addMember}'s -57. A refusal arm is a
     * DECISION written into a method that already had an effect, so it lands in this bucket by
     * construction. The way to get it back is to move those arms behind the wrapper too, which is
     * possible for the two recovery callers and not for {@code ensureReady}, whose arm reads
     * {@code stateAlreadyDestroyed}.
     *
     * <p><b>How this was measured, because I got it wrong once.</b> Four tree states, each run
     * through this test: before the change, with only this change's hunks, with a concurrent
     * edit nearby, and the full tree. The first value written here was <b>4912</b>, taken from
     * a mirror in which the {@code ensureReady} arm had been reproduced in abbreviated form — a
     * number measured on a proxy for the change rather than on the change. 4930 is the real one.
     *
     * <p><b>4332 -> 4331 and 25 -> 25.</b> Three lines, and they are worth naming
     * because that change ADDED a decision to a mixed method and this number still fell. Giving
     * {@code MlsAdvancerElection.takeoverReachableWithin} the production call site it never had put
     * a dozen lines of reconciliation-and-log into {@code advanceOrYield}, which is in this bucket:
     * measured at +31, taking the number to 4363, and the ratchet said so. Two things paid for it,
     * both of which are this work's own direction rather than accounting. The report moved into
     * {@code reportTakeoverReachability}, whose body has no Android effect at all — it is a decision
     * and a log line — so it classifies as decision-only and {@code advanceOrYield} grew by two. And
     * the grep that found the wrapper found a SECOND method in the same class with no production
     * caller: {@code MlsSelfHealPass.look}, the single arithmetic both yield paths were written to
     * share, which production had never called — {@code relookLiveYield} and
     * {@code eraYieldExhausted} each re-derived {@code observations + 1} and the exhaustion test
     * themselves, reconciled by a comment. Routing both through it took {@code relookLiveYield} from
     * 42 to 39. <b>The ratchet was not raised to accommodate the new lines</b>, which is the rule,
     * and it is the ratchet that forced the better placement rather than review noticing it.
     *
     * <p><b>4332 -> 4332 and 25 -> 25, Stage 6 slice 6 — and the flat number is the finding.</b>
     * The last three constants left the transport and the mixed bucket did not move at all, because
     * all three were in methods this list ALREADY exempted or that were never mixed: DoD-1 and DoD-5
     * measure different things, and a stage can finish one without touching the other. The three
     * exemptions written "the constant leaves under DoD-1" are now deleted, which is the only thing
     * that changed here.
     *
     * <p><b>4404 -> 4332 and 27 -> 25 methods, Stage 6 slice 5.</b> The KeyPackage pool
     * cadence moved to {@code MlsKeyPackagePolicy}, and both of its remaining callers —
     * {@code maybePublishKeyPackages} and {@code republishPoolAfterUnopenableWelcome} — are now
     * preference reads and an upload with the decision asked of the engine.
     *
     * <p><b>4464 -> 4404 and 28 -> 27 methods, Stage 6 slice 4.</b> The leaf-rotation cadence
     * moved to {@code MlsRekeyPolicy}, taking {@code dispatchPendingKeyUpdate} out of the bucket: its
     * only decision was the refusal backoff, which is now one call to {@code counterAfterRefusal()}.
     *
     * <p><b>4807 -> 4464 and 31 -> 28 methods, Stage 6 slice 3.</b> Three more methods left,
     * and one of them is the clean case this work is arguing for: {@code maybeRefreshIdentity} was
     * EXEMPT here — "storage whose only decision is a DoD-1 constant" — and with
     * {@code IDENTITY_REFRESH_MS} resolved through {@code MlsConfig} it has no decision left at all,
     * so its exemption is deleted rather than re-pointed. {@code flushFtdReports} also lost the
     * {@code SystemProperties.getInt} that made it unreachable from a host test by construction.
     *
     * <p><b>4927 -> 4807 and 32 -> 31 methods, Stage 6 slice 2.</b> The method that LEFT is
     * {@code reestablishOutbound}: its only decision was {@code REESTABLISH_COOLDOWN_MS}, and with
     * the window moved to {@link MlsReestablishPolicy} and {@code MlsPeerGuard} grown a
     * one-argument claim, the method is a NET effect and nothing else. That is the mechanism DoD-5
     * asks for and not an instrument artefact — {@code MlsPeerGuard} is still in the classifier's
     * GUARD set, so a method that CONSULTS the guard stays tagged; this one stopped naming the
     * threshold, which is a different thing from hiding it.
     *
     * <p><b>4930 -> 4927, Stage 6 slice 1.</b> Three lines, and the mechanism is worth naming
     * because it is the one Stage 6 has to offer DoD-5: a policy constant leaving the transport
     * takes its LOG LINE with it. {@code deferGatedResend} refused with five lines that spelled
     * {@code MAX_GATED_RESENDS} into a sentence; it now calls
     * {@code MlsRecoveryPolicy.gatedResendRefusalLine}, which is two, and the sentence became
     * host-assertable in the bargain. The bucket's MEMBERSHIP did not move — all three methods still
     * reach {@code MlsRecoveryPolicy}, which is in the classifier's POLICY set, so none of them left
     * by having its decision hidden rather than moved.
     *
     * <p><b>25 → 26, and it is a RE-BASELINE rather than a stage — nothing was
     * extracted for it.</b> The method that ENTERED is {@code freshContextIdRetry},
     * {@code [NET, SYSPROP]} at 53 body lines, added by {@code 5f1a8aca} (one
     * fresh-{@code contextId} re-dial before the destructive rebuild).
     *
     * <p><b>It is not an exemption case and the row is deliberately not written.</b> §5.1 exempts
     * {@code Build.TYPE}-gated fixtures and this is production on every build; §5.3 exempts storage
     * whose one decision is a DoD-1 constant and this decides nothing it owns. Its DECISION tag is
     * the two {@code SystemProperties} reads that FETCH the operator's knobs — the decision itself
     * is already {@code MlsFreshContextRetry.decide}, in the engine and host-reachable, which is
     * the mechanism DoD-5 asks for. The method still counts because the tag measures the Android
     * READ that makes the body unreachable from a host test, and that is true of it. Splitting the
     * read out is a separate change with its own argument, and is NOT taken here.
     */
    private static final int MIXED_IN_SCOPE_METHODS = 26;
    /**
     * <b>4,268 → 4,281 — NOT A CHANGE ANYONE MADE ON PURPOSE, and it means this ratchet was RED at
     * {@code 625db112} before the change described below touched anything.</b> THIRTEEN commits have landed
     * in the transport since {@code 15902213}, the last commit to write this constant, and none of
     * them re-measured it: {@code 625db112 4678fa9e 7c56966c 4e7c75ea 4233864c d5b4d07c 17b51900
     * f431c70f ecde152c c25dbe3e 9709013e f90051c2 b424e22e}. Recorded rather than absorbed into the
     * number below it, because a movement folded into someone else's justification is a movement
     * nobody can audit — and because the interesting fact is about the RATCHET rather than the code:
     * a two-sided bound that goes stale unnoticed for thirteen commits is measuring the constant's
     * age, not the file's shape. Both ends re-measured with the checked-in classifier
     * ({@code tools/mls/transport-classify.py --json}, EFFECT ∩ DECISION tags, minus {@link #EXEMPT}):
     * {@code 15902213} reads 25 / 4,268 exactly, {@code 625db112} reads 25 / 4,281.
     *
     * <p><b>AND THE DRIFT IS ONE METHOD, WHICH IS THE FACT A READER ACTUALLY NEEDS</b> — found
     * while this was being written, and it is a better answer than the commit
     * list above it: naming thirteen commits tells you where to look, naming the method tells you
     * what moved. Diffed per-method over the in-scope bucket, {@code 15902213 -> } this commit moves
     * EXACTLY TWO entries and nothing else:
     *
     * <pre>
     *   eraAdvanceLocked      431 -> 444   (+13)   era-advance DIAGNOSTICS — NOT this change
     *   rebuildConversation   245 -> 257   (+12)   see below
     * </pre>
     *
     * <p><b>THE +13 IS COMMENTS AND ONE LOG LINE, AND THE NAME AGAINST IT HAS BEEN WRONG ONCE —
     * this is the THIRD correction of this paragraph, which is itself the finding.</b> It was
     * attributed here to "the held-commit rollback" on an attribution made by TAG
     * FREQUENCY over a whole-file diff. Re-derived by HUNK LOCATION against the method's own line
     * range, which is the only thing that can answer it:
     *
     * <pre>
     *   4233864c  +9   two hunks inside 15239-15669: 6 lines tagged "" adding
     *                  eraAdvanceLeverDiagnostic() to the not-applied warning, and 3 lines
     *                  rewording the refusal-evidence comment
     *   4e7c75ea  +3   the same reachable-negative comment
     *   7c56966c  +1   the same comment, re-cited
     * </pre>
     *
     * <p>So it is ONE log line and a comment thread about refusal evidence — no logic at all — and
     * that rollback's own commit {@code 625db112} leaves this method at {@code 444}, i.e. contributes
     * NOTHING. Per-revision, verified with the classifier: {@code d5b4d07c} 431, {@code 4233864c}
     * 440, {@code 4e7c75ea} 443, {@code 7c56966c} 444, and 444 unchanged thereafter.
     *
     * <p>The lesson is the one the instrument paragraph below states in a different organ: TAG
     * FREQUENCY IS NOT HUNK LOCATION, exactly as a raw newline count is not the classifier's line
     * span. Both times the measurement was of something ADJACENT to the claim. When you attribute a
     * method's growth, map the hunk offsets onto that method's range and read the lines; a tag
     * counted across a whole file names whoever was busiest, not whoever moved it.
     *
     * and {@code 13 + 12 = 25 = 4,293 - 4,268}, so the two movements account for the whole delta
     * with nothing unattributed.
     *
     * <p><b>AND THE OTHER MOVERS ARE EXCLUDED STRUCTURALLY, NOT BY ABSENCE FROM A LIST.</b> Twelve
     * more methods moved over the same span — {@code resyncViaExternalCommit} by +85,
     * {@code maybeUpdateGroupCredential} by +142 — and a reader who diffs ALL methods will think the
     * paragraph above is refuted. It is not, and the reason is stronger than "they are not in the
     * bucket": every one of them carries an EFFECT tag or a DECISION tag but <b>not both</b>, so
     * none of them can enter the MIXED bucket at any size ({@code resyncViaExternalCommit} is
     * {@code [NET]}, {@code maybeUpdateGroupCredential} is {@code [PERSIST]},
     * {@code eraAdvanceLeverDiagnostic} is {@code [SYSPROP]}, the rest carry neither). Sharpened
     * from a membership claim, which is the weaker form this project
     * keeps catching: a membership test is a snapshot and goes stale as the file grows, while the
     * tag rule stays true. Re-derive it the same way if this ever looks wrong — the classifier
     * prints {@code tags} per method and the bucket is {@code EFFECT ∩ DECISION}.
     *
     * <p><b>TWO INSTRUMENTS DISAGREED BY A CONSTANT 3 AND THE DELTA WAS IMMUNE TO IT — this is the
     * generalisable half.</b> A second reader measured the same {@code +13} over the same
     * revision pair at absolute {@code 428 -> 441}; this javadoc reads {@code 431 -> 444}. The cause
     * is not the checkout, not the revisions and not the classifier's own version (that moved at
     * {@code 25aa7723} and both versions read 431/444 over both revisions). It is that the two
     * numbers came from DIFFERENT INSTRUMENTS, and the one used for the attribution was a
     * throwaway brace-matcher rather than the classifier the guard runs on:
     *
     * <pre>
     *   d5b4d07c   classifier 431  (start 15239, end 15669)   brace-matcher 428
     *   beaaf32b   classifier 444  (start 15693, end 16136)   brace-matcher 441
     * </pre>
     *
     * The classifier reports the INCLUSIVE span from the signature's first line to the closing
     * brace; the proxy counts newlines strictly inside the braces, dropping the signature lines and
     * the final newline. {@code 15669 - 15239 + 1 = 431} and {@code 16136 - 15693 + 1 = 444}, and
     * the offset is +3 at BOTH endpoints — which is exactly why the delta agreed and the absolutes
     * never could.
     *
     * <p><b>So the rule is a consequence rather than a heuristic: a constant definitional offset
     * CANCELS in a delta and does not in an absolute.</b> Two instruments that disagree about what
     * a "line of a method" is will agree about CHANGE forever. Trust the delta when the two
     * disagree; quote an absolute only from the instrument the guard itself runs, which is
     * {@code tools/mls/transport-classify.py} and nothing else. And say WHICH tool produced a
     * number when you quote it — the omission is what made this take two wrong explanations to
     * find. (Measuring the subject from {@code git show <rev>:} rather than the checkout is a
     * separate rule, independently right, and it was not the cause here.)
     *
     * <p>That is also why this reconciliation is the thing to REPEAT next time the number moves — a
     * per-method diff of the bucket says whether a jump is one change or several, and a bare total
     * never can. The ATTRIBUTION is delta-shaped, so it survives the instrument disagreement
     * entirely: "the whole drift is {@code eraAdvanceLocked}" is true under both readings.
     *
     * <p><b>rebuildConversation 257 → 276, i.e. +19 — AND THE CONSTANT BELOW IS
     * DELIBERATELY NOT MOVED FOR IT. Read this before bumping it.</b>
     *
     * <p>WHAT IT BOUGHT: {@code rebuildConversation} now REFUSES a GROUP rebuild whose re-create the
     * server will not apply, instead of running it. The refused arm is the one that, on
     * {@code deviceB} on 2026-09-11, dropped both halves of a conversation's state, claimed a
     * peer KeyPackage and charged the era budget, built era 2 — and left the server at the era 1 it
     * started from, the conversation in REJOIN. {@link MlsReestablishPolicy#forksAtEraInitial} could
     * not catch it: a GroupInfo carry was in hand and that predicate answers false whenever there
     * is one. The +19 is the call, its refusal log, the §7.7.2.2 ask that gives the refusal an exit,
     * and five lines saying where the argument lives — the argument itself, both device runs and the
     * measurement are in {@link MlsReestablishPolicy#reCreateWouldNotTake}'s javadoc, on the
     * host classpath, for the reason given below.
     *
     * <p><b>TWO NUMBERS, because two forms of this arm exist in the tree and both are real.</b> The
     * first cut carried the reasoning inline and measures {@code rebuildConversation} at 284 (+27);
     * the trimmed form measures 276 (+19). The inline form is what commit {@code 286d5656} happens
     * to carry — it swept this file mid-edit in a shared checkout, which is a process fact rather
     * than a decision about the shape. Whichever is in front of you, the delta attributable to this
     * change is the one whose baseline is 257.
     *
     * <p>WHY THE NUMBER IS UNCHANGED, measured 2026-09-12 rather than assumed: the in-scope bucket
     * read <b>26 methods / 4,373 lines</b> over the working tree BEFORE this change and <b>26 /
     * 4,392</b> after it. So the ratchet was already red by one method and 85 lines when this was
     * written, from concurrent uncommitted work in the same file by another agent. Bumping the
     * constant to 4,392 would fold that movement into this change's account, which is precisely the
     * laundering this ratchet exists to prevent — an unexplained delta must not become explained by
     * sitting next to one that is. The +19 is on the record here; the remaining +85 / +1 belongs to
     * whoever made it, and the constant moves when they account for it. Reproduce by running this
     * test: it measures the bucket itself and prints both numbers in its failure message. (The
     * command-line instrument that produced the published baseline lives in the out-of-tree RCS
     * provider's repository and is not needed here.)
     *
     * <p><b>4,281 → 4,293, and this one IS deliberate.</b>
     *
     * <p>WHAT IT BOUGHT: {@code rebuildConversation} now REFUSES a carry-less group re-establish
     * over a group the server measurably holds, instead of warning about it and proceeding. That arm
     * is how {@code deviceC} came to hold a complete three-member era-1 copy of
     * {@code b1189d9c…} the server has no trace of — its rebuild allowance charged at 2026-09-08
     * 17:49:39.407, its engine group written at 17:49:43.580, and never written since. With no carry
     * {@code plan_group} takes ARM 1 and is born at {@code ERA_INITIAL}; at server era 1 the
     * transport's post-create check compares era NUMBERS, they match, and the fork is ADOPTED.
     *
     * <p>WHY IT IS +12 AND NOT +48: the decision is {@code MlsReestablishPolicy.forksAtEraInitial}
     * and the evidence is its javadoc — both in the engine, both host-reachable, which is what DoD-5
     * is asking for. What the transport gains is the call, its refusal log, and six lines saying
     * where the argument lives. The first cut carried the argument inline and measured 4,329; the
     * shape was not chosen to flatter the counter, but moving it is what DoD-5 wanted anyway.
     */
    /**
     * <b>4,267 → 4,268, and this is the first time this number has gone UP.</b>
     * Recorded rather than absorbed, because a ratchet whose movements are only ever reported in the
     * favourable direction stops being evidence.
     *
     * <p>WHAT IT BOUGHT: {@code logIdCandidates}, the only instrument that can settle which of three
     * LAYERS holds "the original message id" — the AAD's own {@code message_id}, the CPIM
     * {@code Original-Message-ID} header, or the component's opaque. All three are 24-byte
     * {@code Mx}-form so length cannot separate them, and on ordinary traffic they agree under both
     * hypotheses; the first real resend is what answers it. It runs on traffic we already receive.
     *
     * <p>WHY IT IS +1 AND NOT +12: the capture is a named private method, so what
     * {@code decryptInbound} gains is the call (528 → 529 body lines). That is better code on its
     * own terms — a twelve-line block whose entire purpose is one log line does not belong in a
     * 528-line method that already mixes effect with decision — so the shape was not chosen to
     * flatter the counter. Had it been inlined as proposed, this would read 4,279.
     *
     * <p>NET FOR THE DAY IS STILL DOWN, 4,287 → 4,268, and the 4,267 below was one hour old and
     * never shipped. Both movements are listed so a reader can audit either.
     *
     * <p>4,287 → 4,267, <b>by the same mechanism as the change below and on the
     * same method shape.</b> {@code eraAdvanceLocked}'s per-member KeyPackage loop moved to
     * {@code claimRosterForAdvance}, a private helper that claims, applies the A.4.1.2 floor and
     * dates each package's leaf certificate. It reaches no guard, no policy constant and no class in
     * the classifier's NARROW policy set. The bucket's MEMBERSHIP is unchanged — {@code
     * eraAdvanceLocked} is still mixed through {@code MlsRecreationEpisode} and the create — so this
     * is EFFECT leaving a mixed body rather than a method leaving the bucket by being hidden.
     *
     * <p>NET 20 LINES AND NOT 90, and the difference is worth stating because a bare "-20" looks
     * like a rounding error next to what moved: the same change ADDED roughly seventy lines to that
     * loop (the certificate pre-flight and its refusal), so what the extraction removed is closer to
     * ninety. The ratchet sees only the net, which is the correct thing for it to bound; the reason
     * it did not go backwards is the extraction, not restraint.
     *
     * <p>{@code MlsFloorRebuild}, the class the helper consults, is on the classifier's
     * POLICY_WIDE_CLASSES list rather than the narrow one — the same treatment as
     * {@code MlsGroupInfoGate} and for the reason that list documents, that the narrow regex is what
     * this baseline is asserted against. Under {@code --wide-policy}, {@code floorRebuild} is MIXED,
     * which is the honest reading of a method that consults a policy class and writes
     * {@code ConvState}.
     *
     * <p>4,331 → 4,287. {@code establishGroup}'s per-member KeyPackage loop moved
     * to {@code gatherInitialKeyPackages}, a private helper with no decision in it: it claims, applies
     * the RCC.16 A.4.1.2 floor and refuses the create outright, and reaches no guard, constant or
     * policy class. The bucket's MEMBERSHIP is unchanged — {@code establishGroup} is still mixed,
     * still through {@code allowedToJoinAll}'s {@code MlsPeerGuard} arm — so this is 44 lines of
     * EFFECT leaving a mixed body, not a method leaving the bucket by being hidden.
     *
     * <p>4,293 → 4,288, and the NET is what this records rather than what moved.
     * {@code establishGroup} 396 → 391. The fix itself ADDED about 24 lines to that body — the
     * epoch-authenticator check moved above the adoption, a snapshot, an adoption and a rollback —
     * and then its {@code DIFFERS} arm left for {@code healOntoServerGroupOrUnadopt}, 29 lines of
     * EFFECT out of a body that already mixes effect with decision. Same mechanism as the two
     * changes above, and the same test of it: the bucket's METHOD COUNT is unchanged at 25,
     * so the new helper did not enter the bucket and {@code establishGroup} did not leave it by
     * being hidden. The helper reaches no guard, no policy constant and no provider RPC.
     *
     * <p>Stated because a bare "-5" understates both halves: had the arm not been extracted this
     * would read 4,317 and the ratchet would have been the thing that noticed.
     *
     * <p><b>4,288 → 4,402. A RE-BASELINE. The whole +114 is accounted for below
     * so the bump is auditable rather than a number moved to silence a red guard.</b> HEAD was red
     * against its own ratchet with a working tree byte-identical to {@code git show HEAD:}, which is
     * read by the next agent as THEIR regression — that, and not the size of the number, is why it
     * was P1. Measured with this guard's own compiled instrument over every revision that touched
     * the transport; {@code 37ef57f0} is the last one reading 25 / 4,288 exactly.
     *
     * <pre>
     *   37ef57f0   25 / 4288
     *   286d5656   25 / 4347   eraAdvanceLocked 444-&gt;476, rebuildConversation 257-&gt;284
     *   07b1eb35   25 / 4347
     *   5f1a8aca   26 / 4400   freshContextIdRetry ENTERS at 53
     *   db45c412   26 / 4392   rebuildConversation 284-&gt;276              (trimmed)
     *   b7d34f0d   26 / 4402   ensureReady 499-&gt;509                      (docs)
     *   e51f639a   26 / 4402   HEAD
     * </pre>
     *
     * <p>Per method, {@code 37ef57f0} to HEAD, with the SPAN this ratchet counts beside a CODE-ONLY
     * count — the same brace-matched body with blank and comment lines dropped:
     *
     * <pre>
     *   freshContextIdRetry     0 -&gt;  53   (+53)     code    0 -&gt; 48   (+48)
     *   eraAdvanceLocked      444 -&gt; 476   (+32)     code  204 -&gt; 216  (+12)
     *   rebuildConversation   257 -&gt; 276   (+19)     code  119 -&gt; 129  (+10)
     *   ensureReady           499 -&gt; 509   (+10)     code  257 -&gt; 257  ( +0)
     *                                      ----                        ----
     *                                      +114                         +70
     * </pre>
     *
     * <p><b>44 OF THE 114 LINES ARE COMMENTS AND BLANKS, AND {@code ensureReady}'s ENTIRE +10 IS.</b>
     * {@code b7d34f0d} is the clean instance and it is worth naming precisely: a commit titled
     * {@code docs(mls):}, 13 inserted lines and 3 deleted, EVERY ONE of them a {@code //} comment
     * inside {@code ensureReady}, and it moved this ratchet by ten. What DoD-5 measures is code that
     * mixes an Android effect with a policy decision and is therefore unreachable from a host test;
     * a comment is never unreachable from a host test. A ratchet that a comment can trip gets
     * re-based for reasons that have nothing to do with its subject, and each re-base erodes what a
     * red means. The mechanism: the classifier's {@code lines} is the INCLUSIVE span from the
     * signature to the closing brace ({@code j - i + 1}), and comments are blanked by
     * {@code strip_comments_and_strings} for TAGGING (instrument defect 2) while still being
     * counted for LENGTH.
     *
     * <p><b>The fix is a code-only count, and it is deliberately NOT taken in this commit</b> — for
     * the reason this class's own header gives about moving the instrument and the target in one
     * step. The whole published lineage (46 / 5,802 at {@code 1e50f59c} and every movement recorded
     * above) is asserted against the SPAN, so switching the definition silently re-scales all of it.
     * The shape that does not: have {@code transport-classify.py} emit {@code code_lines} ALONGSIDE
     * {@code lines}, ratchet on the new field, and RE-DERIVE the baseline from scratch at a named
     * commit keeping both numbers — never adjust this one into it. At HEAD the in-scope code-only
     * total is <b>2,265</b> ({@code 37ef57f0} reads 2,195), so the re-derivation is already paid for
     * by whoever takes it.
     *
     * <p><b>AND THE FIRST COMMIT TO MOVE IT IS NOT THE DOCS COMMIT ITS LABEL SAYS IT IS.</b>
     * {@code 286d5656} is also {@code docs(mls):} and its own message says <i>"Comments and javadoc
     * only; no bytes change"</i>. It carries +24 CODE lines inside this bucket
     * ({@code eraAdvanceLocked} +12, {@code rebuildConversation} +12) — an {@code EraReconcile}
     * change, a parked-verdict clear and a
     * {@code groupReCreateReusesTheServersContextId} change, swept up mid-edit from a shared
     * checkout. So
     * "a docs commit moved a code ratchet" is true of {@code b7d34f0d} and true of {@code 286d5656}'s
     * LABEL ONLY. Two different organs: the comment sensitivity above is this instrument's, the
     * sweep is the shared checkout's, and reading them as one finding loses both.
     *
     * <p><b>4,402 → 4,375.</b> {@code establishGroup} 391 → 364:
     * {@code establishGroup}'s addMembers arm moved into {@code addMembersToExistingGroup}, which
     * ships a provider RPC and makes no policy decision of its own — so it is EFFECT-ONLY and does
     * not join the bucket, and the method count stays 26. The fix itself ADDED about 40 lines (the
     * arm's missing epoch-authenticator verification, the same ordering one arm over); they landed in
     * the new body rather than in {@code establishGroup}, which is the placement this ratchet is for
     * — the same trade made with {@code healOntoServerGroupOrUnadopt}, and the ratchet
     * was NOT raised to accommodate any of it.
     *
     * <p><b>4,375 → 4,378, and it is worth saying what the three lines ARE
     * because a rise in this number is supposed to mean a decision arrived in the layer.</b> They
     * are a three-line COMMENT in {@code encryptForSend} (210 lines, unchanged in the method count)
     * saying why the sealed-cache entry now carries two clocks — a wall stamp for the dump and an
     * {@code elapsedRealtime} stamp for the retention sweep. No branch, no new call, no decision:
     * the instrument counts body lines and does not strip comments, which its own javadoc above
     * records as a known sensitivity ({@code b7d34f0d}). Raised rather than worked around, because
     * shortening a comment to hold a number is how a ratchet starts teaching people to write worse
     * code.
     *
     * <p><b>4,378 → 4,424, and it is the comment sensitivity above for the third
     * time — measured rather than assumed.</b> {@code decryptInbound} 529 → 575. The same
     * brace-matched body counted CODE-ONLY (blank and comment lines dropped) moves
     * <b>253 → 255</b>, so 44 of the 46 are comments and blanks; the remaining <b>two</b> are string
     * continuations of an existing {@code LogUtil.e} call whose text grew. No branch, no new call,
     * no decision arrived in the layer.
     *
     * <p>What the 44 comment lines SAY is the reason the change exists: the §11.3a resend block
     * justified its silent drop with <i>"in an N-member group, N-1 members fail it by
     * construction"</i> — a count that was never measured by anybody, inherited its "proven"
     * label by proximity, and was withdrawn on 2026-09-10. That site
     * survived the sweep. The replacement states the corrected mechanism, the three things the
     * disposition ACTUALLY rests on, and names the falsifier that has not been run.
     *
     * <p>Raised rather than trimmed, following the entry above and for its stated reason. The
     * alternative here would be worse than usual: the edit exists to stop a retracted claim being
     * quoted as a justification, and shortening the correction to fit a line budget is how the next
     * reader ends up with the retraction but not the reason.
     *
     * <p><b>4,424 → 4,443. A RE-BASELINE for the publication cleanup, and the +19 is entirely
     * comment text.</b> Nothing moved in or out of the mixed bucket: {@link #MIXED_IN_SCOPE_METHODS}
     * is unchanged at 26 and the member list is identical. The rewrite that replaced internal
     * references with prose a public reader can follow made several of those methods' comments
     * longer.
     *
     * <p>That this is comment growth is CHECKED, not assumed. {@code transport-classify.py} counts
     * a method as {@code j - i + 1} — the span from its declaration to its closing brace — over an
     * array that {@code strip_comments_and_strings} has blanked IN PLACE. Measured: 25,822 lines in,
     * 25,822 out. So the span includes comment lines, and a comment-only edit moves this number
     * while moving nothing the metric is about. Had the count been comment-stripped, +19 would have
     * meant real code arrived in the mixed bucket and this constant must NOT have been raised.
     */
    private static final int MIXED_IN_SCOPE_LINES = 4443;

    /**
     * The mixed bucket in the provider {@code e2ee} package OUTSIDE the transport. Measured
     * 2026-09-09; see {@link #theProviderLayerOutsideTheTransportHasItsOwnRatchet}.
     *
     * <p><b>7 → 10</b>, and unlike the transport's +3 above these ARE decisions
     * arriving in the layer, so the rise is honest rather than incidental. The three new members are
     * {@code MlsPendingBodyStore.oldestKey} (13), {@code MlsCiphertextCache.sweepExpired} (54) and
     * {@code MlsCiphertextCache.evictOldestPreReboot} (33) — the first two were already in these
     * files and are counted now because they gained a decision, the third is new.
     *
     * <p>Each holds a judgement over a {@code SharedPreferences} scan that this layer cannot hand to
     * the engine as it stands: "which entry has the largest PROVABLE age", "is this row from an
     * older FORMAT (adopt it) or merely old (drop it)", and "is this row provably from a previous
     * boot". The arithmetic behind all three is already in the engine and host-tested
     * ({@code MlsMonotonicAge}, {@code MlsSendRetentionPolicy.expiredMonotonic}); what stays here is
     * the ENUMERATION, which needs a {@code Context}. Moving the rest would mean giving the engine a
     * store-iterator port, which is the decoupling plan's shape of work and not this change's.
     */
    private static final int SIBLING_MIXED_METHODS = 10;
    /**
     * 422 → 420. {@code MlsConversationOpenListener.upgrade} 128 → 126: the
     * discarded key-package probe and its eleven lines of justification left, and what replaced them
     * — the free guards, one claim, the claim handed to the create — is shorter because the argument
     * moved into {@code MlsUpgradeClaim} and {@code MlsUpgradePolicy} rather than being restated at
     * the call site. Nothing was cut out of this layer into a new sibling class, which is the
     * evasion this ratchet exists for.
     *
     * <p>420 → 557, alongside the method rise documented on
     * {@link #SIBLING_MIXED_METHODS}: {@code MlsCiphertextCache.put} 46 → 61 and
     * {@code MlsPendingBodyStore.put} + {@code .sweepExpired} 76 → 98, plus the three new members.
     * Nothing was cut out of this layer into a new sibling class, which is the evasion this ratchet
     * exists for — the transport's own number ROSE in the same change, which is the signature of a
     * real addition rather than a file split.
     */
    private static final int SIBLING_MIXED_LINES = 557;

    /** One exempted method, and the claim that earns the exemption. */
    private static final class Exempt {
        final String method;
        final String claim;      // a needle that must be present in the brace-matched body
        final String why;
        Exempt(final String method, final String claim, final String why) {
            this.method = method;
            this.claim = claim;
            this.why = why;
        }
    }

    /**
     * Stage 0's §5.1 (debug/fixture arms) and §5.3 (storage whose only decision is a DoD-1 constant).
     *
     * <p>§5.2's two rows are NOT here: they were the instrument's own extractor bug, since
     * fixed, and both left the bucket. Listing them would be an exemption for a problem that no
     * longer exists.
     */
    private static final Exempt[] EXEMPT = {
        // §5.1 — Build.TYPE-gated instruments. Their "decision" IS the guard that stops them running
        // on a user build; moving that into the engine would make a fixture into a policy.
        new Exempt("armInboundHold", "buildAllowsFixtures(",
                "a fixture arm; the guard that exempts it is the one it must keep"),
        new Exempt("armOutboundHold", "buildAllowsFixtures(",
                "ditto"),
        new Exempt("dumpAdvancerElection", "MlsAdvancerElection",
                "a read-only reporter over MlsAdvancerElection.decide — it reports a decision the "
                + "engine already owns and host-tests"),

        // §5.3 — the effect IS the method. Its one decision is a policy CONSTANT, which leaves under
        // DoD-1/Stage 6; listing them here stops Stage 6 and DoD-5 double-counting the same work.
        // pruneRetainedContentKeys and openPendingSubject left the bucket in Stage 6 slice 6, which
        // is what "the constant leaves under DoD-1" was written in anticipation of. Both rows are
        // deleted rather than re-pointed: their retention caps are MlsMetadataKeysPolicy's now, and
        // what is left in each method is a preference edit and a log line.
        // maybeRefreshIdentity LEFT the bucket in Stage 6 and its row is deleted rather than
        // re-pointed: with IDENTITY_REFRESH_MS resolved through MlsConfig the method reads a
        // preference, compares two longs and calls the identity store — a net effect with no policy
        // left in it. An exemption for a method that no longer needs one is the shape this list must
        // not acquire.
        // noteKeyPackageConsumed left the bucket in Stage 6 slice 5, same shape as
        // maybeRefreshIdentity: with KP_REPLENISH_AT in MlsKeyPackagePolicy the method reads a
        // preference, decrements it and asks the policy. Row deleted rather than re-pointed.
        new Exempt("sweepExpiredSendMaterial", "MlsSendRetentionPolicy",
                "its decision is ALREADY an engine policy call — nothing to extract"),
    };

    // ---- the checks ----------------------------------------------------------------------------

    /**
     * The classifier ran, over the real file, and classified a plausible number of methods.
     *
     * <p>The source-scan rule in its subprocess form: a tool that could not be found, could not be
     * run, or printed nothing must FAIL. A DoD row satisfied by an instrument that did not execute
     * is the strongest form of a guard reporting green.
     */
    @Test
    public void theClassifierRanAndClassifiedTheWholeFile() throws Exception {
        final List<Map<String, Object>> rows = classify();
        assertTrue("tools/mls/transport-classify.py classified " + rows.size() + " methods — the "
                + "transport has ~300 and anything near zero means the tool did not run against "
                + "the file this test thinks it did", rows.size() >= 250);
        int tagged = 0;
        for (final Map<String, Object> r : rows) {
            if (!((List<?>) r.get("tags")).isEmpty()) tagged++;
        }
        assertTrue("no method carried a single tag — the tagger's regexes matched nothing, so every "
                + "bucket is empty and DoD-5 would read as met", tagged >= 50);
    }

    /**
     * Every exempted method still exists and still earns its exemption.
     *
     * <p>The claim is checked against the method's BRACE-MATCHED body, over comment-stripped source,
     * and keyed on an invoked method name or an engine type — never on a log label or a comment.
     * A debug arm that quietly lost its {@code buildAllowsFixtures()} guard would otherwise keep an
     * exemption granted on the strength of having one.
     */
    @Test
    public void everyExemptionNamesAMethodThatStillEarnsIt() throws IOException {
        final String code = SourceScan.transport();
        final List<String> broken = new ArrayList<>();
        for (final Exempt e : EXEMPT) {
            final String body = SourceScan.bodyOf(code, e.method);
            if (body.isEmpty()) {
                broken.add(e.method + " — no such class-level method; delete the exemption, or it "
                        + "will be inherited by the next method to take the name");
            } else if (!body.contains(e.claim)) {
                broken.add(e.method + " — exempt because \"" + e.why + "\", and its body no longer "
                        + "contains " + e.claim);
            }
        }
        if (!broken.isEmpty()) fail("DoD-5 exemptions that no longer hold: " + broken);
    }

    /**
     * Every mixed method is either exempt or in scope, and the in-scope count only falls.
     *
     * <p>This is DoD-5's ratchet. An exemption that has LEFT the mixed bucket is reported as an
     * improvement to remove rather than tolerated, because a name on an exemption list that
     * exempts nothing is the same defect as a stale needle: it reads as accounted-for.
     */
    @Test
    public void theMixedBucketOnlyShrinksAndEveryMemberIsExemptOrInScope() throws Exception {
        final Map<String, Integer> mixed = mixedBucket();
        final Set<String> exempt = new LinkedHashSet<>();
        for (final Exempt e : EXEMPT) exempt.add(e.method);

        final List<String> exemptButNotMixed = new ArrayList<>();
        for (final String name : exempt) {
            if (!mixed.containsKey(name)) exemptButNotMixed.add(name);
        }

        int methods = 0;
        int lines = 0;
        final List<String> inScope = new ArrayList<>();
        for (final Map.Entry<String, Integer> e : mixed.entrySet()) {
            if (exempt.contains(e.getKey())) continue;
            methods++;
            lines += e.getValue().intValue();
            inScope.add(e.getKey() + "(" + e.getValue() + ")");
        }

        if (!exemptButNotMixed.isEmpty()) {
            fail("These methods are on the DoD-5 exemption list and are no longer in the MIXED "
                    + "bucket: " + exemptButNotMixed + ". That is progress — remove the row so the "
                    + "list keeps meaning \"exempt AND mixed\". (In scope now: " + methods
                    + " methods / " + lines + " lines.)");
        }
        if (methods > MIXED_IN_SCOPE_METHODS || lines > MIXED_IN_SCOPE_LINES) {
            Collections.sort(inScope);
            fail("DoD-5 went BACKWARDS: " + methods + " methods / " + lines + " body lines mix an "
                    + "Android effect with a policy decision and are not exempt, up from "
                    + MIXED_IN_SCOPE_METHODS + " / " + MIXED_IN_SCOPE_LINES + ". (The published "
                    + "baseline DoD-5 is written against is " + PUBLISHED_BASELINE_METHODS + " / "
                    + PUBLISHED_BASELINE_LINES + ", measured at 1e50f59c with the uncorrected "
                    + "instrument.) In scope: " + inScope);
        }
        if (methods < MIXED_IN_SCOPE_METHODS || lines < MIXED_IN_SCOPE_LINES) {
            fail("DoD-5 IMPROVED — " + methods + " methods / " + lines + " lines in scope, down "
                    + "from " + MIXED_IN_SCOPE_METHODS + " / " + MIXED_IN_SCOPE_LINES + ". Lower "
                    + "both constants in this test in the SAME commit, so the next regression is "
                    + "caught against what the stage actually achieved.");
        }
    }

    /**
     * A method may not leave the mixed bucket by being deleted from the classifier's view.
     *
     * <p>Instrument defect (1): nine class-level methods were INVISIBLE to the extractor, so
     * the bucket shrank without anything moving. {@code sendBlockedByGate} — the send gate both
     * {@code encryptForSend} and {@code resendFramed} consult — was one of them. This pins the
     * total number of class-level methods the tool can see: if it falls while the file grows, the
     * instrument has gone blind again and DoD-5's number is measuring less of the file.
     *
     * <p><b>Its slack, stated.</b> The two scanners do not agree exactly — the tool's signature
     * regex is looser than {@link SourceScan}'s, so it sees 328 where the scan sees 325 — and
     * this check only fires when the tool falls BELOW the scan. So up to three methods could go
     * invisible without it noticing. At the magnitude that defect actually had (308 seen against
     * ~325 declared) it would have fired, which is the claim being made and not a stronger one.
     */
    @Test
    public void theClassifierStillSeesEveryClassLevelMethodTheSourceDeclares() throws Exception {
        final int seenByTool = classify().size();
        final int seenBySourceScan =
                SourceScan.declarations(SourceScan.transport()).size();
        assertTrue("ZERO HITS MUST FAIL: the transport's class-level declarations is EMPTY, so the loop below never runs "
                        + "and this assertion certifies green having examined nothing. A zero-match "
                        + "scan is a broken scan, not a clean tree.",
                seenBySourceScan > 0);
        final int missing = seenBySourceScan - seenByTool;
        if (missing > 0) {
            fail("The classifier sees " + seenByTool + " class-level methods; an independent scan "
                    + "of the same file finds " + seenBySourceScan + ". " + missing + " method(s) "
                    + "are invisible to the DoD-5 instrument, which is instrument defect (1) "
                    + "returning: the bucket shrinks without anything having moved.");
        }
    }

    /**
     * <b>A method may not leave the mixed bucket by leaving the FILE.</b>
     *
     * <p>DoD-5's subject is {@code MlsProviderTransport.java}, and that is the blindness DoD-3
     * nearly shipped with. Measured on a mirror, 2026-09-09: cutting {@code onPeerReportedFailure}
     * (644 lines) and {@code decryptInbound} (528) out of the transport into
     * {@code MlsProviderTransportInbound} — <b>same package, same layer, not one decision moved to
     * the engine and nothing made host-testable</b> — and this class reported
     * <i>"DoD-5 IMPROVED — 24 methods / 3688 lines in scope, down from 25 / 4332. Lower both
     * constants in this test in the SAME commit."</i> The guard does not merely miss a file split;
     * it instructs the next stage to ratchet down for work that did not happen.
     * {@link #theClassifierStillSeesEveryClassLevelMethodTheSourceDeclares} cannot see it either —
     * both scanners read the same shrunken file, so they fall together.
     *
     * <p>So the layer outside the transport gets its own ratchet, from the same instrument over
     * every provider {@code e2ee} source. The transport's number keeps its published lineage (46 /
     * 5,802 at {@code 1e50f59c}) and stays the row DoD-5 is written against; this one is what makes
     * a split visible, because a split lowers the first and leaves the second exactly where it was.
     *
     * <p><b>Measured 2026-09-09: 7 methods / 422 lines</b>, 420 after that split —
     * {@code MlsCiphertextCache.put} (46),
     * {@code MlsConversationOpenListener.upgrade} (126), {@code MlsPendingBodyStore.put} and
     * {@code .sweepExpired} (76), {@code MlsRetryWorker.enqueue} and {@code .doWork} (77),
     * {@code MlsStalledActionReceiver.onReceive} (95). Both directions fail, as everywhere else
     * here: a rise is a decision arriving in the layer, and a fall must be recorded so the next
     * regression is caught against what a stage actually achieved.
     *
     * <p><b>10 methods / 557 lines</b> (2026-09-13) —
     * {@code MlsCiphertextCache.put} (61), {@code .sweepExpired} (54) and
     * {@code .evictOldestPreReboot} (33); {@code MlsConversationOpenListener.upgrade} (126);
     * {@code MlsPendingBodyStore.put} (54), {@code .sweepExpired} (44) and {@code .oldestKey} (13);
     * {@code MlsRetryWorker.enqueue} (29) and {@code .doWork} (48);
     * {@code MlsStalledActionReceiver.onReceive} (95). The reason the three new members are a real
     * rise rather than a split is on {@link #SIBLING_MIXED_METHODS}.
     */
    @Test
    /**
     * <b>WHERE THIS RATCHET'S SUBJECT STILL ENDS, and why it is left there — measured,
     * Stage 7.</b>
     *
     * <p>{@link #mixedBucketOutsideTheTransport} walks {@code src/…/rcs/e2ee} recursively. So the
     * evasion this ratchet exists for — <i>cut a mixed method out of the transport and the DoD-5
     * number falls for a file split</i> — is still available ONE DIRECTORY OVER: a method cut into a
     * new provider class outside {@code e2ee} lowers the transport's number and moves neither of
     * these two. That is the same location-defined subject that was closed for DoD-3, and
     * it is NOT closed here.
     *
     * <p><b>Deliberately, and the measurement is the argument.</b> Closing it means taking DoD-3's
     * union — {@code e2ee} plus every provider source importing the engine MLS package — and that
     * union adds <b>2 mixed methods / 1,633 lines</b>, of which <b>1,606 lines are ONE method</b> in
     * {@code RcsDebugSendReceiver}, a debug surface with nothing to do with this work. A
     * ratchet that reds on any edit to a debug receiver is a guard someone switches off, which is
     * the failure mode {@code MlsGateCounterDurabilityGuardTest} names in its own javadoc about not
     * widening a literal scan. The narrow subject is kept and the hole is stated rather than
     * papered over.
     *
     * <p>What still catches the evasion partially: a class holding transport work would import the
     * engine, and any policy TUNABLE it declared would be caught by DoD-3's widened subject. What
     * would not be caught is the LINE COUNT. Filed rather than absorbed.
     */
    public void theProviderLayerOutsideTheTransportHasItsOwnRatchet() throws Exception {
        final Map<String, Integer> mixed = mixedBucketOutsideTheTransport();
        int lines = 0;
        for (final Integer n : mixed.values()) lines += n.intValue();
        final List<String> members = new ArrayList<>(mixed.keySet());
        Collections.sort(members);
        if (mixed.size() > SIBLING_MIXED_METHODS || lines > SIBLING_MIXED_LINES) {
            fail("The provider layer OUTSIDE MlsProviderTransport gained mixed methods: "
                    + mixed.size() + " / " + lines + " lines, up from " + SIBLING_MIXED_METHODS
                    + " / " + SIBLING_MIXED_LINES + ". If the transport's number fell in the same "
                    + "change, that is a FILE SPLIT and not a stage — a method that moves to a "
                    + "sibling in the same package has not left the layer and is no more reachable "
                    + "from a host test than it was. " + members);
        }
        if (mixed.size() < SIBLING_MIXED_METHODS || lines < SIBLING_MIXED_LINES) {
            fail("The provider layer outside the transport SHRANK — " + mixed.size() + " / " + lines
                    + " lines, down from " + SIBLING_MIXED_METHODS + " / " + SIBLING_MIXED_LINES
                    + ". Lower both constants in this test in the SAME commit. " + members);
        }
    }

    // ---- the instrument ------------------------------------------------------------------------

    /** {@code name -> body lines} for every method in the MIXED bucket. */
    private static Map<String, Integer> mixedBucket() throws Exception {
        final Set<String> effect = new HashSet<>(Arrays.asList("NET", "PERSIST", "SCHED"));
        final Set<String> decision =
                new HashSet<>(Arrays.asList("CONST", "GUARD", "POLICY", "SYSPROP"));
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final Map<String, Object> r : classify()) {
            boolean hasEffect = false;
            boolean hasDecision = false;
            for (final Object t : (List<?>) r.get("tags")) {
                if (effect.contains(t)) hasEffect = true;
                if (decision.contains(t)) hasDecision = true;
            }
            if (hasEffect && hasDecision) {
                out.put((String) r.get("name"), (Integer) r.get("lines"));
            }
        }
        return out;
    }

    /** {@code Class.method -> body lines} for every MIXED method in e2ee OUTSIDE the transport. */
    private static Map<String, Integer> mixedBucketOutsideTheTransport() throws Exception {
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final File f : SourceScan.javaSourcesUnder("src/com/android/messaging/rcs/e2ee")) {
            if (f.getName().equals("MlsProviderTransport.java")) continue;
            final String simple = f.getName().substring(0, f.getName().length() - ".java".length());
            for (final Map<String, Object> r : classifyFile(f.getPath())) {
                if (isMixed(r)) {
                    out.put(simple + "." + r.get("name") + "(" + r.get("lines") + ")",
                            (Integer) r.get("lines"));
                }
            }
        }
        return out;
    }

    private static boolean isMixed(final Map<String, Object> row) {
        final Set<String> effect = new HashSet<>(Arrays.asList("NET", "PERSIST", "SCHED"));
        final Set<String> decision =
                new HashSet<>(Arrays.asList("CONST", "GUARD", "POLICY", "SYSPROP"));
        boolean hasEffect = false;
        boolean hasDecision = false;
        for (final Object t : (List<?>) row.get("tags")) {
            if (effect.contains(t)) hasEffect = true;
            if (decision.contains(t)) hasDecision = true;
        }
        return hasEffect && hasDecision;
    }

    private static List<Map<String, Object>> cached;



    /** Run the checked-in classifier over the working-tree transport and parse its {@code --json}. */
    private static synchronized List<Map<String, Object>> classify() throws Exception {
        if (cached != null) return cached;
        cached = classifyFile(locate(SourceScan.TRANSPORT, "", "packages/apps/Messaging/",
                "../"));
        return cached;
    }

    /** The same instrument, over any one file. Never re-implemented in Java — see the class doc. */
    private static List<Map<String, Object>> classifyFile(final String target) throws Exception {
        final String tool = locate(TOOL, "", "packages/apps/Messaging/");
        final ProcessBuilder pb = new ProcessBuilder("python3", tool, target, "--json");
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        final String stdout;
        try (InputStream in = p.getInputStream()) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] chunk = new byte[8192];
            for (int n = in.read(chunk); n > 0; n = in.read(chunk)) buf.write(chunk, 0, n);
            stdout = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
        if (p.waitFor() != 0) {
            throw new IllegalStateException("transport-classify.py exited " + p.exitValue()
                    + " over " + target + " — DoD-5's instrument did not run, so its number means "
                    + "nothing:\n" + stdout);
        }
        final int at = stdout.indexOf("[{\"name\"");
        if (at < 0 && stdout.trim().endsWith("[]")) return new ArrayList<>();   // a file with no
                                                                               // class-level method
        if (at < 0) {
            throw new IllegalStateException("transport-classify.py --json printed no row array. "
                    + "Its output shape changed and this guard cannot read it; that must fail "
                    + "rather than degrade to zero rows. Output was:\n" + stdout);
        }
        return parseRows(stdout.substring(at));
    }

    private static String locate(final String rel, final String... prefixes) throws IOException {
        for (final String prefix : prefixes) {
            final File f = new File(prefix + rel);
            if (f.isFile()) return f.getPath();
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — DoD-5's instrument or its subject is missing");
    }

    /**
     * A parser for exactly the shape {@code --json} emits: a flat array of objects whose values are
     * strings, integers, or arrays of strings. Deliberately not a general JSON reader — the host
     * classpath has no JSON library and a hand-rolled general one is a bug farm.
     */
    private static List<Map<String, Object>> parseRows(final String json) {
        final List<Map<String, Object>> out = new ArrayList<>();
        int i = json.indexOf('[') + 1;
        while (i < json.length()) {
            while (i < json.length() && json.charAt(i) != '{' && json.charAt(i) != ']') i++;
            if (i >= json.length() || json.charAt(i) == ']') break;
            final Map<String, Object> row = new LinkedHashMap<>();
            i++;
            while (i < json.length() && json.charAt(i) != '}') {
                final int keyStart = json.indexOf('"', i) + 1;
                final int keyEnd = json.indexOf('"', keyStart);
                final String key = json.substring(keyStart, keyEnd);
                int v = json.indexOf(':', keyEnd) + 1;
                while (v < json.length() && json.charAt(v) == ' ') v++;
                if (json.charAt(v) == '"') {
                    final int end = json.indexOf('"', v + 1);
                    row.put(key, json.substring(v + 1, end));
                    i = end + 1;
                } else if (json.charAt(v) == '[') {
                    final int end = json.indexOf(']', v);
                    final List<String> items = new ArrayList<>();
                    for (final String s : json.substring(v + 1, end).split(",")) {
                        final String t = s.trim().replace("\"", "");
                        if (!t.isEmpty()) items.add(t);
                    }
                    row.put(key, items);
                    i = end + 1;
                } else {
                    int end = v;
                    while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) end++;
                    row.put(key, Integer.valueOf(json.substring(v, end)));
                    i = end;
                }
                while (i < json.length() && (json.charAt(i) == ',' || json.charAt(i) == ' ')) i++;
            }
            out.add(row);
            i++;
        }
        return out;
    }
}

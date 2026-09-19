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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Every path that makes a peer join or re-join by Welcome is charged to a budget.</b>
 *
 * <h2>Why this is a SOURCE guard and why it is asserted this way</h2>
 *
 * <p>{@code MlsPeerGuard} and {@code MlsProviderTransport} are Android-dependent and cannot run in
 * the host suite, so the alternative to this test is a worked example on a device — and a worked
 * example is exactly what did not catch the defect. G2, the era-advance circuit breaker written
 * after ~17 re-creations wedged a real person's phone for a month, was called
 * from ONE place and was correct there. The hole was a second door: {@code rebuildConversation}
 * dropped both halves of our state and re-established over a conversation the server still held,
 * which re-Welcomes every member, and charged nothing. A test of the era-advance path passes while
 * that door stands open.
 *
 * <p>So the property is asserted over the SOURCE, from the ground truth: the engine calls that MINT
 * A WELCOME. Whatever method contains one of those calls is a path that makes a peer (re-)join, and
 * it must appear in {@link #CHARGED_BY} naming the guard it charges. A new call site — a new
 * recovery arm, a new create path — fails this test until someone says which budget pays for it.
 * That is the question the last three of these defects turned on, and none of them could be asked
 * of a behavioural test.
 *
 * <h2>What the needle rule changed here, and why it was P1</h2>
 *
 * <p>This guard used to key on RECEIVER-QUALIFIED needles — {@code "mSelf.addMember("} and its two
 * siblings. That was correct at the revision it was written against and unsound as a rule: the
 * needle encoded how the call is currently SPELLED, and the property is about what the code DOES.
 * {@code openMls()} hands back a second live reference to the same session and four methods already
 * hold one in a local, so {@code eng.addMember(…)} evaded all three needles — and a source scan that
 * misses its target does not fail, it REPORTS GREEN. Three rules now hold instead:
 *
 * <ol>
 *   <li><b>Key on the INVOKED METHOD NAME, never on a receiver or a label.</b> No needle below
 *       names a receiver, so no alias of the session can hide a call from it. A method
 *       <i>reference</i> ({@code mSelf::addMember}) is the one non-call spelling that still mints,
 *       so it is matched too.</li>
 *   <li><b>The door list is derived from ground truth, not written by hand.</b>
 *       {@link #theDoorListIsEveryWelcomeMintingEngineCall} reads {@code MlsSession.java} and fails
 *       if any method returning {@code MlsGroupArtifacts} is neither listed as minting a Welcome nor
 *       carries a recorded reason it does not. A hand list of three names could not notice
 *       {@code createGroupMulti} or {@code addMembers}, both of which mint one and neither of which
 *       it named.</li>
 *   <li><b>A scan that finds none of its own subject FAILS.</b> Each door carries its own minimum
 *       call-site count, so renaming one goes red instead of quietly enumerating nothing. A total
 *       across all doors cannot do this: four {@code addMember} sites would cover for a
 *       {@code createGroupPlanned} that had been renamed out from under the needle.</li>
 *   <li><b>Never a character distance.</b> {@link #aRefusedRebuildStops} read 1,200 raw characters
 *       forward from the charge to find the refusal. That is the same failure's fourth instance on a
 *       different axis — a window encodes how far apart the code currently SITS — and these arms
 *       open with a paragraph of comment. It brace-matches the block now.</li>
 * </ol>
 *
 * <p>Every scan here reads COMMENT- AND STRING-BLANKED source ({@link #codeOnly}), except the one
 * whose needle is itself a literal. The transport documents these paths heavily and names
 * {@code MlsPeerGuard.allowEraAdvance} in prose; reading the raw text would let a comment satisfy a
 * charge assertion, which is the same disease pointing the other way.
 *
 * <p>If this test fails you have added a way to Welcome a peer. Charge it, then add the row.
 */
public final class MlsPeerReJoinBudgetGuardTest {

    /**
     * The engine calls that MINT A WELCOME, each with the minimum number of call sites the transport
     * is expected to hold and why.
     *
     * <p>A peer cannot be made to join or re-join a group by any other means, so this is the
     * complete set of doors — and {@link #theDoorListIsEveryWelcomeMintingEngineCall} is what makes
     * "complete" a checked claim rather than an assertion by the author.
     *
     * <p><b>The minimum is per door, and zero is a declaration.</b> A door with no call site today is
     * still watched: if one appears it must be in {@link #CHARGED_BY} like any other. What the
     * number stops is the silent case — {@code addMember} renamed, its four sites no longer matched,
     * and the guard reporting green on a file it can no longer see into.
     */
    private static final String[][] WELCOME_MINTING_CALLS = {
        // {invoked method, minimum call sites, why that minimum}
        {"createGroupPlanned", "3", "the era-free create entry point: ensureReady's 1:1 "
                + "create, establishGroup's group create, and eraAdvanceLocked's advance"},
        {"createGroupWithId", "2", "ensureReady's two revive arms — the era bump and the id "
                + "reclaim, both re-using the id the server named"},
        {"addMember", "4", "the 2-arg delegate, the MLS arm in mlsMembershipChange, the plaintext "
                + "arm through commitAndSend, and changeGroupMembership's ADD route"},
        {"createGroup", "0", "no site today — createGroupPlanned is the entry point every create "
                + "should use, and this is the era-naming overload it replaced"},
        {"createGroupMulti", "0", "no site today — createGroupPlanned reaches it inside the engine. "
                + "It builds an N-member group in ONE commit, so one Welcome covers everyone, and a "
                + "transport-side call to it would be an unbudgeted mass re-Welcome"},
        {"addMembers", "0", "no site today — establishGroup ships an addMembers commit that the "
                + "engine builds. N packages means N DEVICES, so a direct call is a Welcome per "
                + "device with nothing counting them"},
    };

    /**
     * Every {@code MlsSession} method returning {@code MlsGroupArtifacts}, and whether it mints a
     * Welcome. {@code MINTS} means it must be a door above; anything else must say why it is not.
     *
     * <p>This list is the bridge between "what the guard looks for" and "what the engine can do".
     * Without it the door list is a hand inventory, which is the instrument this whole test exists
     * to replace — and hand inventories of exactly this set have been wrong twice already.
     */
    private static final String MINTS = "MINTS";
    private static final String[][] ARTIFACT_RETURNING = {
        {"createGroup", MINTS},
        {"createGroupWithId", MINTS},
        {"createGroupMulti", MINTS},
        {"createGroupPlanned", MINTS},
        {"addMember", MINTS},
        {"addMembers", MINTS},
        {"removeMember", "a Remove commit — its own javadoc says 'no welcome'; nobody is admitted"},
        {"removeMemberByMsisdn", "as removeMember, for every leaf of one participant"},
        {"selfLeave", "a by-reference SelfRemoveProposal; the artifacts carry welcome(empty) and it "
                + "does not even advance the epoch"},
        {"selfUpdate", "a self Update commit — the roster is unchanged, so there is nobody to "
                + "Welcome"},
        {"selfUpdateExtPub", "as selfUpdate, republishing our own extension key"},
        {"commitGroupMetadata", "a GroupContextExtensions commit (RCC.16 §9.7.1.4/.5)"},
        {"commitIconSubject", "a GroupContextExtensions commit (RCC.16 §7.11.4/.6)"},
        {"commitEndMls", "a GroupContextExtensions commit (0xF002)"},
        // CLASSIFIED FROM THE ENGINE, NOT FROM ITS JAVADOC — and the two disagree about what this
        // method is. The javadoc describes a membership-preserving advance whose artifacts carry no
        // Welcome. That describes a path that CANNOT RUN: commit_era_advance in
        // rust/rcs_mls_ffi/src/ffi.rs returns Err unconditionally, because the Era (0xF001) is
        // immutable within an MLS group instance, so eraAdvancePreserving always answers
        // BUILD_FAILED and every advance falls through to the create. It mints nothing because it
        // returns nothing.
        //
        // The row's verdict was right and its REASON was corroboration rather than verification: I
        // read the javadoc and repeated it. This table's whole value is that a later reader can
        // check the reasons against the engine, so a reason that describes dead behaviour is a
        // defect in it. Flagged in review and verified at the FFI here.
        {"commitEraAdvance", "mints nothing because it returns nothing: commit_era_advance in "
                + "rust/rcs_mls_ffi/src/ffi.rs refuses unconditionally (the Era is immutable "
                + "within a group instance), so every era advance falls through to the CREATE path "
                + "— which is why createGroupPlanned carries eraAdvanceLocked's site"},
    };

    /**
     * {@code enclosing method}, {@code charging method}, {@code declaration fragment}, {@code guard}.
     *
     * <p>The charging method is often the enclosing one, but not always: {@code eraAdvanceLocked}
     * runs inside a lock and its guard sits at the {@code eraAdvance} funnel every overload reaches,
     * and {@code mlsMembershipChange}'s add arm is only reachable through {@code addMember}. Naming
     * both makes the indirection a claim a reader can check rather than an omission.
     *
     * <p>The DECLARATION FRAGMENT picks the overload. Every one of these funnels has a delegating
     * sibling of the same name whose body is a single {@code return}, and matching on the name alone
     * finds that one and reports the guard missing — which is what the first run of this guard did.
     * An empty fragment means the name is unambiguous.
     */
    private static final String[][] CHARGED_BY = {
        // The 1:1 create. Its FIRST create is deliberately free — nobody re-joins a group that does
        // not exist — and its two re-create arms (the era bump and the id reclaim, both driven by
        // the server naming its own state in a refusal) charge the era budget.
        // The fragment deliberately stops at the parameter NAME. It used to carry the closing paren
        // — "final boolean recreateAlreadyCharged)" — which made it a claim about how many
        // parameters the overload has, and a sixth parameter (stateAlreadyDestroyed) turned every
        // ensureReady Welcome-mint site red for a change that was fine. A declaration fragment is a
        // spelling too; keep it to the least of the signature that picks the overload.
        {"ensureReady", "ensureReady", "final boolean recreateAlreadyCharged",
                "MlsPeerGuard.allowEraAdvance("},
        // The group create. Welcomes every member, so every member passes the joining gate. Reached
        // WITHOUT a debug arm from MlsConversationOpenListener's upgrade-on-open.
        // The gate is reached through allowedToJoinAll because rebuildConversation must ask the same
        // question BEFORE it destroys anything; theGuardHelpersReachMlsPeerGuard pins the second
        // link of that chain, so naming the helper here does not weaken the check.
        // THE FRAGMENT STOPS AT THE PARAMETER NAME, as ensureReady's above and for the same reason
        // written there: it used to carry the closing paren — "final byte[] carryGroupInfo)" — which
        // made it a claim about how many parameters the overload has, and a fourth
        // parameter (preClaimed) turned this row red for a change that was fine. The lesson was
        // already recorded above this table when it happened to ensureReady; this row had not been
        // brought in line with it.
        {"establishGroup", "establishGroup", "final MlsUpgradeClaim preClaimed",
                "allowedToJoinAll("},
        // The Add, MLS-conversation arm. Only reachable through addMember, which carries the gate
        //.
        {"mlsMembershipChange", "addMember", "final String rcsGroupId, final String peerE164,",
                "MlsPeerGuard.allowJoiningPeer("},
        // The Add, PLAINTEXT-conversation arm — the same Welcome by a different route, and the one
        // this guard found that a hand-written inventory had missed twice. commitAndSend is generic
        // over the op; Op.ADD reaches it from addMember and from nowhere else, so the same gate
        // covers it.
        {"commitAndSend", "addMember", "final String rcsGroupId, final String peerE164,",
                "MlsPeerGuard.allowJoiningPeer("},
        // The era advance itself — G2's original and only call site.
        //
        // NO CLOSING PAREN in the selector, for the reason the establishGroup row above records:
        // carrying one makes the needle a claim about how many parameters the overload has, and it
        // then turns red for a change that is fine. It happened again on 2026-09-10 —
        // requireRebuildableRoster is a fifth parameter on this same funnel — which is twice now,
        // so the rule is stated here rather than only two rows up.
        {"eraAdvanceLocked", "eraAdvance", "final MlsAdvanceEraKind kind",
                "MlsPeerGuard.allowEraAdvance("},
        // The two-argument DELEGATE. Invisible to the old receiver-qualified needles, because the
        // call it makes is a bare `addMember(null, …)` with no receiver at all — and a needle that
        // required one could not see the plainest spelling in the file. It reaches the same gated
        // three-argument overload, which is why the charger is that overload rather than itself.
        {"addMember", "addMember", "final String rcsGroupId, final String peerE164,",
                "MlsPeerGuard.allowJoiningPeer("},
        // The AIDL membership route: changeGroupMembership picks the commit-carrying RPC for a
        // single-member ADD and calls addMember with the group id. Same gate, third caller.
        {"changeGroupMembership", "addMember", "final String rcsGroupId, final String peerE164,",
                "MlsPeerGuard.allowJoiningPeer("},
    };

    /** A method declaration at class level: 4-space indent, a visibility modifier, no initialiser. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    /** {@code MlsGroupArtifacts foo(} in an interface or class declaration. */
    private static final Pattern ARTIFACT_DECL = Pattern.compile(
            "\\bMlsGroupArtifacts\\s+(\\w+)\\s*\\(");

    @Test
    public void everyWelcomeMintingCallSiteChargesABudget() throws IOException {
        // CODE ONLY, offsets preserved. The transport's prose names these calls repeatedly — the
        // comment above rebuildConversation says "so it never goes near createGroupPlanned" — and a
        // needle that is no longer receiver-qualified reads every one of those as a call site.
        final String src = codeOnly(readTransport());
        final List<int[]> decls = declarations(src);
        assertTrue("no method declarations matched — the pattern has gone stale and this guard is "
                + "silently passing, which is worse than failing", decls.size() > 20);

        final List<String> unaccounted = new ArrayList<>();
        final List<String> uncharged = new ArrayList<>();
        final List<String> unselectable = new ArrayList<>();
        final List<String> vanished = new ArrayList<>();
        for (final String[] door : WELCOME_MINTING_CALLS) {
            final String call = door[0];
            int sites = 0;
            for (final int at : invocationsOf(src, call)) {
                if (isDeclaration(decls, at)) continue;     // the method itself, not a call to it
                sites++;
                final String enclosing = enclosingMethod(src, decls, at);
                final String[] row = rowFor(enclosing);
                if (row == null) {
                    unaccounted.add(call + "() in " + enclosing + "()");
                } else {
                    final String charger = bodyOf(src, row[1], row[2]);
                    if (charger.isEmpty()) {
                        // A DIFFERENT FAILURE, and it used to wear the other one's message. The
                        // declaration fragment picks an overload, so it is a spelling of the
                        // signature — rename a parameter or add one and the fragment selects
                        // nothing, which is not the same fact as "the guard has moved".
                        unselectable.add(row[1] + "(" + row[2] + " …) from " + enclosing + "()");
                    } else if (!charger.contains(row[3])) {
                        uncharged.add(enclosing + "() → " + row[1] + "("
                                + (row[2].isEmpty() ? "" : row[2] + " …") + ") must call " + row[3]);
                    }
                }
            }
            final int min = Integer.parseInt(door[1]);
            if (sites < min) {
                vanished.add(call + "(): " + sites + " call sites, expected at least " + min
                        + " — " + door[2]);
            }
        }

        if (!vanished.isEmpty()) {
            fail("This guard ENUMERATES DOORS; it does not police names. Fewer call sites matched "
                    + "than are declared to exist, which means either the engine call was RENAMED — "
                    + "in which case every site behind the new name is now invisible here and this "
                    + "test would have gone on reporting green — or the path really was removed, in "
                    + "which case lower the number in WELCOME_MINTING_CALLS and say why: " + vanished
                    + ". Do not delete the door: a door with zero sites is still watched, and that "
                    + "is what the zeros in that table mean.");
        }
        if (!unaccounted.isEmpty()) {
            fail("These calls MINT A WELCOME from a method no budget accounts for, so a peer can be "
                    + "made to join or re-join a group with nothing counting it — the exact hole "
                    + "this guard closed in rebuildConversation: " + unaccounted + ". Charge the "
                    + "operation (MlsPeerGuard.allowEraAdvance for a re-creation, allowJoiningPeer "
                    + "for bringing a peer in) and add a row to CHARGED_BY.");
        }
        if (!unselectable.isEmpty()) {
            fail("The declaration fragment in CHARGED_BY no longer picks any overload of the "
                    + "charging method: " + unselectable + ". This is NOT a missing charge — it is "
                    + "the guard failing to find the method to look in, which is the same disease "
                    + "the needles had: the fragment encodes a SPELLING of the "
                    + "signature. Trim it to the least that distinguishes the overload from its "
                    + "delegating sibling, or update it to the new one.");
        }
        if (!uncharged.isEmpty()) {
            fail("These paths mint a Welcome but the method declared as charging for them no longer "
                    + "does: " + uncharged + ". A guard that has moved out from under its call site "
                    + "is indistinguishable from one that was never there.");
        }
    }

    /**
     * The door list is EVERY Welcome-minting engine call, checked against the engine's own API.
     *
     * <p>{@link #WELCOME_MINTING_CALLS} named three of the six methods that mint
     * a Welcome. The three it missed have no transport call site today, so nothing was wrong at
     * runtime — and nothing would have gone red on the day one appeared, which is the failure this
     * whole file is about, occurring in its own ground truth.
     *
     * <p>A Welcome is minted by admitting somebody, and every admitting call answers
     * {@code MlsGroupArtifacts}. So the enumerable set is the artifact-returning methods of
     * {@code MlsSession}, and each must be classified: a door, or a recorded reason it admits
     * nobody. Adding one to the engine fails this test until someone says which.
     */
    @Test
    public void theDoorListIsEveryWelcomeMintingEngineCall() throws IOException {
        final String session = codeOnly(read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsSession.java"));
        final Set<String> declared = new LinkedHashSet<>();
        final Matcher m = ARTIFACT_DECL.matcher(session);
        while (m.find()) declared.add(m.group(1));
        assertTrue("no MlsGroupArtifacts-returning methods found in MlsSession — either the engine's "
                + "artifact type has been renamed or this scan has gone stale, and a stale scan here "
                + "makes the door list unfalsifiable again", declared.size() > 5);

        final Set<String> classified = new LinkedHashSet<>();
        final List<String> unreasoned = new ArrayList<>();
        for (final String[] row : ARTIFACT_RETURNING) {
            classified.add(row[0]);
            if (!MINTS.equals(row[1]) && row[1].trim().isEmpty()) unreasoned.add(row[0]);
        }
        if (!unreasoned.isEmpty()) {
            fail("These are classified as not minting a Welcome with no reason given: " + unreasoned
                    + ". The reason is the whole value of the row — it is what a later reader checks "
                    + "against the engine, and 'somebody decided this once' is not checkable.");
        }

        final List<String> unclassified = new ArrayList<>();
        for (final String name : declared) {
            if (!classified.contains(name)) unclassified.add(name);
        }
        if (!unclassified.isEmpty()) {
            fail("MlsSession has grown method(s) returning MlsGroupArtifacts that this guard has "
                    + "never been told about: " + unclassified + ". Every artifact-returning call "
                    + "either ADMITS somebody — in which case it mints a Welcome, add it to "
                    + "ARTIFACT_RETURNING as MINTS and give it a row in WELCOME_MINTING_CALLS — or "
                    + "it does not, in which case say so with the reason. Leaving it out is how a "
                    + "door stays invisible: createGroupMulti and addMembers both mint one and "
                    + "neither was named until this guard was widened.");
        }

        final List<String> stale = new ArrayList<>();
        for (final String[] row : ARTIFACT_RETURNING) {
            if (!declared.contains(row[0])) stale.add(row[0]);
        }
        if (!stale.isEmpty()) {
            fail("ARTIFACT_RETURNING names engine methods that no longer exist: " + stale
                    + ". A classification for a method that is gone excuses nothing and misleads the "
                    + "next reader; delete the row, and the door with it if it had one.");
        }

        final Set<String> doors = new LinkedHashSet<>();
        for (final String[] d : WELCOME_MINTING_CALLS) doors.add(d[0]);
        final List<String> unwatched = new ArrayList<>();
        for (final String[] row : ARTIFACT_RETURNING) {
            if (MINTS.equals(row[1]) && !doors.contains(row[0])) unwatched.add(row[0]);
        }
        if (!unwatched.isEmpty()) {
            fail("These engine calls mint a Welcome and no needle looks for them: " + unwatched
                    + ". Add each to WELCOME_MINTING_CALLS — with a minimum of 0 if the transport "
                    + "does not call it today, which still watches the door.");
        }
        final List<String> phantom = new ArrayList<>();
        for (final String[] d : WELCOME_MINTING_CALLS) {
            boolean minting = false;
            for (final String[] row : ARTIFACT_RETURNING) {
                if (row[0].equals(d[0]) && MINTS.equals(row[1])) minting = true;
            }
            if (!minting) phantom.add(d[0]);
        }
        if (!phantom.isEmpty()) {
            fail("WELCOME_MINTING_CALLS watches names that are not classified as minting a Welcome: "
                    + phantom + ". A needle for a door that does not exist is an assertion about "
                    + "nothing, and it inflates the count that is supposed to prove the scan works.");
        }
    }

    /**
     * The rebuild must charge BEFORE it destroys anything, and a refusal must STOP it.
     *
     * <p>Separate test because the failure modes read differently. "Nothing charges it" is the hole;
     * "it charges after the forget" is a guard that has already done the damage by the time it
     * speaks — the conversation would be left with no provider record, no engine state and no group,
     * which is strictly worse than the divergence the rebuild came to repair.
     */
    @Test
    public void theRebuildChargesBeforeItDestroysAnything() throws IOException {
        final String body = bodyOfFunnel(codeOnly(readTransport()), "rebuildConversation");
        assertTrue("rebuildConversation not found — it has been renamed, and it is the second door "
                + "to the era budget", body.length() > 0);

        final int charge = body.indexOf("MlsPeerGuard.allowEraAdvance(");
        final int joinGate = body.indexOf("allowedToJoinAll(");
        final int destroy = indexOfFirst(body, "mlsForgetGroupConversation(", "mlsForgetConversation(");
        assertTrue("rebuildConversation does not charge the era budget at all. It re-creates a "
                + "conversation the server holds, which makes EVERY member re-join by Welcome — the "
                + "same cost an era advance imposes and the one ~17 of wedged a peer for a month "
                + ". MlsRebuildLimiter bounds our side, not the peer's.", charge >= 0);
        assertTrue("rebuildConversation does not check whether its roster may be brought into an "
                + "MLS group", joinGate >= 0);
        assertTrue("rebuildConversation does not drop the provider half — the recipe has changed and "
                + "this ordering check no longer means anything", destroy >= 0);
        assertTrue("rebuildConversation charges the era budget AFTER it starts destroying state (at "
                + charge + " vs " + destroy + "). A refusal "
                + "would then leave the conversation with nothing at all, which is worse than the "
                + "churn the refusal prevents.", charge < destroy);
        assertTrue("the joining gate must also run before the forget, for the same reason",
                joinGate < destroy);
    }

    /**
     * A refusal must stop the rebuild rather than let it run uncharged.
     *
     * <p>Pinned as a source shape because the alternative — charging and ignoring the answer — reads
     * as a guard in a review and is a no-op at runtime. That is the failure named
     * explicitly: "have a refusal there STOP the rebuild rather than let it proceed uncharged."
     */
    @Test
    public void aRefusedRebuildStops() throws IOException {
        final String body = bodyOfFunnel(codeOnly(readTransport()), "rebuildConversation");
        final int charge = body.indexOf("MlsPeerGuard.allowEraAdvance(");
        assertTrue("rebuildConversation does not charge the era budget", charge >= 0);
        // THE BLOCK THE REFUSAL OPENS, brace-matched — not a fixed number of characters after the
        // charge. It used to read 1,200 raw characters forward, which is the FOURTH
        // instance of the needle failure: a window is an encoding of how far apart the code currently SITS, and these
        // arms open with a paragraph of comment. Stage 1 hit exactly this with a 120-character
        // window and found it only by injecting the regression. Same class, same fix.
        final String refusalArm = blockAfter(body, charge);
        assertTrue("the era-budget charge in rebuildConversation is not followed by a block, so "
                + "this guard cannot see what happens on a refusal at all", refusalArm.length() > 0);
        // The RETURN VALUE changed shape later — rebuildConversation answers an
        // MlsRebuildOutcome now, because nine situations used to share one `false` and the arms
        // above it printed a disjunction they had not measured. What this test is about is
        // unchanged: the refusal must STOP, and it must stop as a REFUSAL rather than as any of the
        // outcomes that are re-driven silently.
        assertTrue("the era-budget check in rebuildConversation does not return on refusal — a "
                + "budget whose answer is discarded is worse than no budget, because the next "
                + "reader believes it. The refusal arm reads: "
                + refusalArm.substring(0, Math.min(200, refusalArm.length())),
                refusalArm.contains("return MlsRebuildOutcome.REFUSED_BY_GUARD"));
    }

    /**
     * The era budget must be countable for a 1:1, which it was not originally.
     *
     * <p>{@code allowEraAdvance} used to answer "allowed" without charging whenever the group id was
     * empty, i.e. for every 1:1 — so the one era advance we know we forced at the wedged peer, on
     * 2026-08-25, was not counted by the guard written because of that peer.
     */
    @Test
    public void theEraBudgetHasAKeyForAOneToOne() throws IOException {
        // NO LONGER A SOURCE SCAN, and that is an upgrade rather than a relocation (Stage 2,
        // decision D1(a) — "stop proving these properties by grep"). The derivation
        // moved to MlsStateChangeGate, which is pure and on this test's classpath, so the question
        // this test asks can be asked of a real call instead of of the text of one. It used to read
        // MlsPeerGuard's source for a "peer:" literal, which was the only thing reachable while the
        // derivation sat behind a class that needs a Context.
        final MlsStateChangeGate.BudgetKey oneToOne =
                MlsStateChangeGate.eraBudgetKey(/*groupId=*/ null, "+15715550100");
        assertEquals("a 1:1 does not fall back to the PEER, so it has no budget key and its era "
                + "advances are uncounted — the one advance we know we forced at the "
                + "wedged peer, on 2026-08-25, was not counted by the guard written because of it",
                MlsStateChangeGate.KeySource.PEER, oneToOne.source());
        assertEquals("peer:5715550100", oneToOne.key());

        // And the wiring, which no host test can reach: the guard must use THAT derivation rather
        // than a second one of its own.
        final String guard = codeOnly(readGuard());
        final String decide = bodyOf(guard, "decide", "final Tier tier");
        assertTrue("MlsPeerGuard.decide is gone", decide.length() > 0);
        assertTrue("MlsPeerGuard no longer derives its budget key from "
                + "MlsStateChangeGate.eraBudgetKey. A second derivation is how the 1:1 fell through "
                + "the guard in the first place.",
                decide.contains("MlsStateChangeGate.eraBudgetKey("));
    }

    /**
     * A guard named in {@link #CHARGED_BY} that is not itself an {@code MlsPeerGuard} call must be a
     * transport helper that makes one.
     *
     * <p>Without this, a row could be satisfied by any method whose name happens to appear in the
     * charging body — including one that decided nothing. It is the same failure the {@code
     * mConvAlias} teardown had: present, well-formed and matching nothing.
     */
    @Test
    public void theGuardHelpersReachMlsPeerGuard() throws IOException {
        final String src = codeOnly(readTransport());
        for (final String[] row : CHARGED_BY) {
            final String g = row[3];
            if (g.startsWith("MlsPeerGuard.")) continue;
            final String helper = bodyOf(src, g.substring(0, g.length() - 1));
            assertTrue(g + " is named as a guard in CHARGED_BY but is not a method of "
                    + "MlsProviderTransport", helper.length() > 0);
            assertTrue(g + " is named as a guard in CHARGED_BY but never asks MlsPeerGuard "
                    + "anything, so the paths that rely on it are ungated",
                    helper.contains("MlsPeerGuard."));
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Every offset at which {@code name} is INVOKED, by any receiver or none.
     *
     * <p>Two spellings reach a method: a call, and a method reference handed to something that will
     * call it. Neither names a receiver here, which is the point — {@code mSelf.addMember(…)},
     * {@code openMls().addMember(…)}, {@code s.addMember(…)} and a bare {@code addMember(…)} are the
     * same door, and the old needles could see only the first.
     */
    private static List<Integer> invocationsOf(final String src, final String name) {
        final List<Integer> out = new ArrayList<>();
        final Matcher m = Pattern.compile(
                "\\b" + Pattern.quote(name) + "\\s*\\(|::\\s*" + Pattern.quote(name) + "\\b")
                .matcher(src);
        while (m.find()) out.add(src.indexOf(name, m.start()));
        return out;
    }

    private static String[] rowFor(final String method) {
        for (final String[] row : CHARGED_BY) {
            if (row[0].equals(method)) return row;
        }
        return null;
    }

    /**
     * The FUNNEL overload of {@code name}: the one that is not a delegate, brace-matched.
     *
     * <p>{@link #bodyOf} answers the first declaration it meets, and every funnel in this class has
     * a delegating sibling of the same name whose body is one {@code return}. Naming the real one by
     * a declaration fragment works and is a spelling of the signature, and
     * it is how {@code CHARGED_BY}'s {@code ensureReady} row went red on a parameter addition.
     *
     * <p>So the delegate is identified STRUCTURALLY instead: a body whose only statement is a
     * {@code return} of a call to its own name. That is what a delegate IS, it survives a
     * reformat and a parameter change, and it is exactly the shape
     * that was added to {@code rebuildConversation} while this guard was being written —
     * which turned all three of its rebuild assertions red until this method existed.
     *
     * <p>Fails to "" if every overload is a delegate or none is, both of which mean the caller's
     * assumption about this method has stopped holding.
     */
    private static String bodyOfFunnel(final String src, final String name) {
        String best = "";
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            final String body = blockAfter(src, open);
            if (body.isEmpty() || isDelegateTo(body, name)) continue;
            if (body.length() > best.length()) best = body;
        }
        return best;
    }

    /** A body whose only statement is {@code return name(…);} — a delegating overload. */
    private static boolean isDelegateTo(final String body, final String name) {
        final String flat = body.replaceAll("\\s+", "");
        return flat.matches("\\{return" + Pattern.quote(name) + "\\(.*\\);\\}");
    }

    /**
     * The brace-matched block opened by the arm at {@code at}, or "" if that arm has no block.
     *
     * <p><b>It must be THAT arm's block.</b> Taking "the next {@code &#123;} after the offset" is the
     * same defect as a character window one step removed: a BRACELESS arm
     * ({@code if (refused) log(…);}) has no block of its own, so the search runs on and hands back a
     * NEIGHBOUR's — and the assertion then reports on code the arm does not contain. Measured before
     * this guard existed: make the refusal arm braceless and non-returning, leave a
     * {@code REFUSED_BY_GUARD} in the arm below it, and the check returned PASS on a rebuild that
     * charges the budget, is refused, and rebuilds anyway.
     *
     * <p>So a {@code ;} before the {@code &#123;} ends the search: a statement has intervened, the arm
     * is braceless, and there is no block to read. That is the third time something written AFTER
     * naming the defect class contained it — the same shape turned up in another per-arm lookup on
     * the same day.
     */
    private static String blockAfter(final String src, final int at) {
        final int open = src.indexOf('{', at);
        if (open < 0) return "";
        final int stop = src.indexOf(';', at);
        if (stop >= 0 && stop < open) return "";        // braceless arm: that brace is not its own
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    private static int indexOfFirst(final String body, final String... needles) {
        int best = -1;
        for (final String n : needles) {
            final int at = body.indexOf(n);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    /** Start offset and name of every class-level method declaration, in source order. */
    private static List<int[]> declarations(final String src) {
        final List<int[]> out = new ArrayList<>();
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) out.add(new int[] {m.start(), m.start(1), m.end(1)});
        return out;
    }

    /** True if {@code at} is where a class-level method DECLARES the name rather than calls it. */
    private static boolean isDeclaration(final List<int[]> decls, final int at) {
        for (final int[] d : decls) {
            if (d[1] == at) return true;
            if (d[0] > at) break;
        }
        return false;
    }

    private static String enclosingMethod(final String src, final List<int[]> decls, final int at) {
        int[] best = null;
        for (final int[] d : decls) {
            if (d[0] > at) break;
            best = d;
        }
        return best == null ? "<file scope>" : src.substring(best[1], best[2]);
    }

    /**
     * The brace-matched body of the first method whose declaration line contains {@code name}, or
     * "" if it is absent. Matches on the declaration rather than a full signature so a parameter
     * list can be reformatted without silently disabling the check.
     */
    private static String bodyOf(final String src, final String name) {
        return bodyOf(src, name, "");
    }

    private static String bodyOf(final String src, final String name, final String declFragment) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            // The DECLARATION only — up to the opening brace, never into the body. Reading a fixed
            // number of characters past the signature reaches the first statements, so a method
            // that merely CALLS the one you asked for answers as though it were it. That produced a
            // failure on the first run of this guard: allowEraAdvance matched a request for
            // eraBudgetKey because it calls it on its second line.
            // EXACT name, never a substring. "eraAdvance" is a prefix of "eraAdvanceWithKind" and
            // of "eraAdvanceLocked", both of which are one-line delegates, so a contains() match
            // finds a body with no guard in it and reports the funnel unguarded — the second false
            // failure this guard produced on itself before it was right.
            final String decl = src.substring(m.start(), open);
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            if (!declFragment.isEmpty() && !decl.contains(declFragment)) continue;
            int depth = 0;
            for (int i = open; i < src.length(); i++) {
                final char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
            }
            return "";
        }
        return "";
    }

    /**
     * Comments AND string-literal contents blanked, character for character.
     *
     * <p>Blanked rather than removed so every offset still points at the same place in the real
     * file: {@link #theRebuildChargesBeforeItDestroysAnything} compares positions, and a scan that
     * reported the right defect at the wrong line would be worse than one that missed it. Same
     * helper as {@code MlsGuardPersistenceTest.codeOnly}.
     */
    private static String codeOnly(final String src) {
        return blank(src, /*alsoStringContents=*/ true);
    }

    /** Comments blanked, string literals intact — for the one scan whose needle is a literal. */
    private static String withoutComments(final String src) {
        return blank(src, /*alsoStringContents=*/ false);
    }

    private static String blank(final String src, final boolean alsoStringContents) {
        final char[] out = src.toCharArray();
        final int n = out.length;
        int i = 0;
        while (i < n) {
            final char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
                if (i < n) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                final char quote = c;
                i++;                                    // the quotes themselves are code
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) {
                        if (alsoStringContents) out[i] = ' ';
                        i++;
                        if (alsoStringContents && out[i] != '\n') out[i] = ' ';
                        i++;
                        continue;
                    }
                    if (alsoStringContents && out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static String readTransport() throws IOException {
        return read("src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java");
    }

    private static String readGuard() throws IOException {
        return read("src/com/android/messaging/rcs/e2ee/MlsPeerGuard.java");
    }

    /**
     * Locate a source file relative to this module.
     *
     * <p>Resolved from the working directory rather than a build variable so the guard runs the same
     * way from the module dir and from the tree root — same locator as
     * {@code MlsConversationTeardownGuardTest}.
     */
    private static String read(final String rel) throws IOException {
        final String[] candidates = {rel, "packages/apps/Messaging/" + rel, "../" + rel};
        for (final String c : candidates) {
            final File f = new File(c);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — tried " + String.join(", ", candidates));
    }
}

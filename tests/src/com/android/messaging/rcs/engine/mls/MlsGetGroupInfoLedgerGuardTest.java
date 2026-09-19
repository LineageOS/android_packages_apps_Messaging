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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Every path that spends a {@code GetMlsGroupInfo} is charged to ONE ledger</b> — invariant I2,
 * generalised from the Welcome budget to a second resource (Stage 1).
 *
 * <h2>Why this test exists, and what it is NOT allowed to enumerate from</h2>
 *
 * <p>{@code MlsPeerReJoinBudgetGuardTest} is the model, and its lesson is precise: it enumerated
 * from the ENGINE CALLS THAT MINT A WELCOME rather than from a hand inventory, and it found a sixth
 * call site that <b>two hand inventories in the same session had missed</b>.
 *
 * <p>Stage 1 met the same failure one level up. The I2 resource table names the
 * primitive for this resource as {@code pt("fetchMissedCommits").fetchMissedCommits}, and eleven
 * doors were enumerated from it — correctly, for that call. But the resource is <b>one
 * {@code GetMlsGroupInfo} RPC</b>, and FIVE provider AIDL methods each dial
 * {@code MlsControlClient.getGroupInfo} exactly once. Enumerating from one of a resource's five
 * spellings is itself the hand inventory the invariant exists to replace, and it under-counted the
 * call sites 3 to 17 — including the one the reconcile drive actually spends on.
 *
 * <p>So the ground truth here is {@link MlsFetchLedger.Primitive}, and the enum is read at runtime
 * rather than restated. A sixth spelling added provider-side must appear there, and the moment it
 * does this test starts looking for its call sites.
 *
 * <h2>The three properties</h2>
 *
 * <ol>
 *   <li><b>Every primitive call site is inside a charged wrapper.</b> {@code pt("<primitive>")} may
 *       appear only in {@code MlsProviderTransport}'s {@code look*} methods, and each of those must
 *       reach {@code spendOneLook}. A sixth site anywhere else is a door with no ledger.</li>
 *   <li><b>Every caller of a wrapper or a funnel DECLARES its ledger.</b> The declaration is an
 *       argument — {@code MlsFetchLedger.Caller.SOMETHING} — so a new recovery arm cannot inherit a
 *       ration by omission. This is the half that stops door twelve.</li>
 *   <li><b>No path reaches the provider's primitives around the transport.</b> The {@code --ez ctrl}
 *       debug arm did exactly that until it was fixed: it called {@code ProviderTransport} directly,
 *       so {@code getMlsGroupInfo}'s only caller sat outside the class every inventory of these
 *       doors has looked in.</li>
 * </ol>
 *
 * <p>If this test fails you have added a way to spend a {@code GetMlsGroupInfo}. Charge it, then the
 * failure names what to do.
 *
 * <h2>The exemptions, which are assertions</h2>
 *
 * <p>Three of this guard's own claims were exemptions carrying a REASON, and a reason in an
 * exemption table is an assertion nothing tests — a point made after the
 * found one in their own table. <b>A wrong reason with a right verdict never fails a test</b>, so it
 * is the one shape no negative control on the other checks can catch. Audited:
 *
 * <ul>
 *   <li>The files permitted to invoke a primitive were a HAND LIST of three names — true the day it
 *       was written, blind to the fourth file ever added, which is the hand-inventory defect this
 *       whole guard exists to replace, sitting inside it. Enumerated now.</li>
 *   <li>"Only debug arms hold {@code EXEMPT}" was true and unenforced. It is a check now, and the
 *       exempt SET is pinned: exemption is the strongest thing that enum can say, and a third
 *       constant needs its own argument rather than a widened list.</li>
 *   <li>{@code QUARANTINE_CHECK}'s stated reason was <b>false</b>. It said the check "runs after an
 *       era advance or a membership change has already spent theirs"; {@code mlsMembershipChange}
 *       reaches it having spent nothing, and {@code eraAdvancePreserving} cannot run at all. Verdict
 *       right, reason describing a path that does not exist. Corrected at the constant.</li>
 * </ul>
 *
 * <h2>The negative controls, and why they are written down here</h2>
 *
 * <p><b>Every source-scanning check below was injected, observed RED, and reverted — and the
 * injection must be the COMMENT-OUT form, not a deletion.</b> Twice now a control here has passed on
 * a regression the check was written to catch:
 *
 * <ul>
 *   <li>{@code aLedgerRefusalAfterTheForgetFailsOpen}'s per-arm check read 120 raw characters
 *       forward from the needle, and the arms it inspects open with a paragraph of comment, so the
 *       {@code return false} sat outside the window.</li>
 *   <li>{@code anUnverifiedRebuildIsNotReportedAsAFailedOne} took 1400 characters forward from its
 *       marker — <b>a fixed distance, in a test written after I had flagged fixed distances as a
 *       defect class.</b> It reads the arm's brace-matched block now. A distance fails in both
 *       directions: red when the arm grows, green when the property moves into a neighbour.</li>
 *   <li>Six of these scans read the transport RAW, so <b>a charge deleted and left behind in a
 *       comment satisfied them</b>. Measured against
 *       {@code everyWrapperReachesTheChargePoint}: replace {@code lookGroupInfo}'s body with
 *       {@code // The charge used to be here: spendOneLook(…)} plus a bare RPC, and the wrapper
 *       spends a {@code GetMlsGroupInfo} with nothing counting it while this test reports green.
 *       They read {@link #codeOnly} now.</li>
 * </ul>
 *
 * <p><b>My own controls missed the second one</b>, because I injected by DELETING the charge rather
 * than commenting it out — testing the regression I had thought of instead of the class of
 * regression. That is the lesson worth more than the fix: a control that deletes proves less than a
 * control that comments out, because a real change is far more likely to leave the old text behind.
 *
 * <p>Recorded as a table rather than in a commit message because the next person to change one of
 * these checks needs to know what it was proven against, and a control nobody can name is a claim.
 *
 * <table>
 *   <caption>What was injected, and which check went red</caption>
 *   <tr><th>check</th><th>injected regression</th></tr>
 *   <tr><td>{@code everyPrimitiveCallSiteIsInsideAChargedWrapper}</td>
 *       <td>a stray {@code pt("fetchServerEpochAuthenticator")} outside a wrapper; separately, a
 *           wrapper re-pointed at another primitive (one to zero, another to two)</td></tr>
 *   <tr><td>{@code everyWrapperReachesTheChargePoint}</td>
 *       <td>a wrapper returning {@code Look.asked(pt(…))} directly; and the charge COMMENTED OUT
 *           rather than deleted — the form that passed before these scans read {@code codeOnly}</td></tr>
 *   <tr><td>{@code aRefusedLookDoesNotReachTheServer}</td>
 *       <td>the RPC moved above the refusal, so the resource is spent before the bound applies.
 *           <b>And the hoist case</b> — {@code final T early = doIt.look();} HOISTED ABOVE
 *           {@code mayFetch(}, which the {@code indexOf}-from-the-ask form could not see at all:
 *           it is the exact regression the check is named for, and it passed. <b>Re-measured on
 *           THIS file rather than inherited from a sibling's copy</b>: the pre-fix guard was
 *           compiled from {@code HEAD} and run against a transport whose charged {@code doIt.look()}
 *           had been hoisted above {@code mayFetch(} — it reported {@code OK (15 tests)}. The
 *           post-fix guard reds on the same tree ("performs a look at 1488 before it asks the ledger
 *           (1672)"). Two forms were run, because they fail differently: a FOURTH
 *           {@code doIt.look()} trips the occurrence count, and a hoist that leaves the count at
 *           three trips the ordering. The false-red direction was run too — padding the exempt arm
 *           with twelve comment lines stays GREEN — because a control that only proves the red
 *           direction has not shown the fix is usable</td></tr>
 *   <tr><td>{@code aLedgerRefusalBeforeTheRebuildIsNotReportedAsAGuardRefusal}</td>
 *       <td>the opening look's refusal arm reverted to {@code REFUSED_BY_GUARD} — the state this
 *           state it was found in; and separately the correct return MOVED to a neighbouring arm, which
 *           the method-wide form of this check accepted</td></tr>
 *   <tr><td>{@code theFourWaysToHaveNoServerPackDoNotShareAValue}</td>
 *       <td>the no-local-state arm reverted to a bare {@code return null;} — the state it
 *           was found in (reds this AND the enumeration below, on different
 *           properties); the pack fetch HOISTED above that arm with all four constants left in
 *           place, which is the hoist shape and the reason the ordering assertion searches
 *           from the START of the body ("arm at 719, fetch at 232"); and the refusal arm re-pointed
 *           at the neighbouring constant, i.e. the two situations sharing a value again. The
 *           false-red direction was run too — fourteen comment lines padded in at TWO points,
 *           moving every landmark apart, stays GREEN — because a control that only proves the red
 *           direction has not shown the check is usable</td></tr>
 *   <tr><td>{@code everyServerPackOutcomeIsProducedSomewhereInTheTransport}</td>
 *       <td>{@code NOT_ASKED_BY_DESIGN}'s ONLY site ({@code resetPeerHealth}) re-pointed at a
 *           neighbour: a vocabulary word for a case the code cannot be in, which reads as
 *           accounted-for while covering nothing</td></tr>
 *   <tr><td>{@code MlsServerPackOutcomeTest#noTwoOutcomesSayTheSameThing}</td>
 *       <td>a constant added with NO {@code case} of its own, so it falls to {@code default} and
 *           inherits {@code LOOK_FAILED}'s sentence. Compiled and run rather than reasoned about;
 *           exactly one check reds, and the two neighbouring checks correctly stay green</td></tr>
 *   <tr><td>{@code everyCallerDeclaresTheLedgerItCharges}</td>
 *       <td>a new method calling {@code fetchServerPack} with no {@code Caller}; separately, a
 *           primitive invoked through a held {@code final ProviderTransport pt = pt(…)} local —
 *           the spelling that hid five doors from the first version of this guard</td></tr>
 *   <tr><td>{@code everyCallerConstantIsActuallyUsed}</td>
 *       <td>a constant losing its only call site; and the same constant left only in a
 *           {@code /* … *}{@code /} comment</td></tr>
 *   <tr><td>{@code theFailOpenCallerChargesAndSaysSo}</td>
 *       <td>{@code reporterIsAMember} returning false instead of failing open; and
 *           {@code describeFailOpen} commented out</td></tr>
 *   <tr><td>{@code theDebugArmIsExemptAndSaysSo}</td>
 *       <td>both {@code describeExemption} sites replaced — one site is not enough, the
 *           unreadable-ledger arm carries the second — and again in commented-out form</td></tr>
 *   <tr><td>{@code aLedgerRefusalAfterTheForgetFailsOpen}</td>
 *       <td>{@code stateAlreadyDestroyed} reverted to false at the rebuild call site; separately, a
 *           bare {@code if (bump.refused())} decline — <b>the one that initially did not fail</b></td></tr>
 *   <tr><td>{@code anUnverifiedRebuildIsNotReportedAsAFailedOne}</td>
 *       <td>{@code RAN_BUT_UNVERIFIED} collapsed back into the {@code NOT_CONVERGED} fall-through;
 *           and the return MOVED INTO A NEIGHBOURING ARM, which a fixed window forward from the
 *           marker accepted. Padding the arm past that window is the control for the other
 *           direction — it must NOT go red, because the property is still where it belongs</td></tr>
 *   <tr><td>{@code nothingReachesTheProviderPrimitivesAroundTheLedger}</td>
 *       <td>the debug receiver calling {@code pt.getMlsServerEraEpoch} directly</td></tr>
 *   <tr><td>{@code nothingReachesTheProviderPrimitivesAroundTheLedger} (enumerated)</td>
 *       <td>a FOURTH file calling {@code pt.getMlsServerEraEpoch} — invisible to the hand-list of
 *           three this replaced</td></tr>
 *   <tr><td>{@code anExemptCallerIsPassedOnlyFromADebugArm}</td>
 *       <td>{@code refreshStallNotification} taking {@code DEBUG_HEALTH}: a production path
 *           spending without bound while the ledger reports the arm as correctly exempt.
 *           <b>And mutation B4</b> — a method named {@code dumpAndRepair} that spells
 *           everything correctly and rebuilds a conversation, exempt purely by name. That is the
 *           direction where a spelling PERMITS rather than MISSES, which authors rarely test
 *           because the predicate reads as obviously right</td></tr>
 *   <tr><td>{@code nothingReachesTheProviderPrimitivesAroundTheLedger} (method reference)</td>
 *       <td><b>Mutation A1</b> — {@code return pt::getMlsGroupInfo;} in a third file. A
 *           door handed out to be called later, with no parens for the needle to match</td></tr>
 *   <tr><td>{@code noConsumerTestsEqualityAgainstTheBareMinusOne}</td>
 *       <td>{@code advanced == -1} in place of the named constant — the obvious way to write it,
 *           and the one that silently excludes the {@code -2} sentinel</td></tr>
 *   <tr><td>{@link #bodyOf} itself, via {@link #isDelegateTo}</td>
 *       <td>a DELEGATING {@code rebuildConversation} overload inserted above the real one. With
 *           {@code isDelegateTo} neutered to its pre-fix behaviour this reds TWO checks; with the
 *           rule in place it reds none. Both halves were run — a control that only passes after the
 *           fix has not shown the fix does anything</td></tr>
 * </table>
 *
 * <p><b>Inject against a COPY, not the shared tree.</b> These scans read
 * {@code MlsProviderTransport.java} as text, so a control is a write to a file other agents are
 * editing — and running them on the live tree once produced two spurious failures from a read/write
 * race and risked clobbering another agent's in-flight work. Copy the file out, inject there, point
 * the working directory at the copy.
 */
public final class MlsGetGroupInfoLedgerGuardTest {

    /**
     * The methods that are ALLOWED to contain a {@code pt("<primitive>")} call, and the charge point
     * each must reach.
     *
     * <p>Deliberately short. The point of funnelling five primitives through five one-line wrappers
     * is that the set of places a charge can be forgotten is small enough to write down.
     */
    private static final String[][] CHARGED_WRAPPERS = {
        {"lookMissedCommits", "FETCH_MISSED_COMMITS"},
        {"lookServerEpochAuthenticator", "FETCH_SERVER_EPOCH_AUTHENTICATOR"},
        {"lookGroupInfoForGroup", "GET_MLS_GROUP_INFO_FOR_GROUP"},
        {"lookServerEraEpoch", "GET_MLS_SERVER_ERA_EPOCH"},
        {"lookGroupInfo", "GET_MLS_GROUP_INFO"},
    };

    /** The one method that actually asks the ledger. Every wrapper must go through it. */
    private static final String CHARGE_POINT = "spendOneLook(";

    /**
     * Methods whose FIRST argument must be an {@code MlsFetchLedger.Caller} — the wrappers plus the
     * funnels that pass one down.
     *
     * <p>A funnel has no ledger of its own on purpose: {@code fetchServerPack} is reached from seven
     * methods and {@code serverStateCheck} from six, and those are thirteen different decisions to
     * spend. Attributing the cost to the funnel would put them all under one ration and hide exactly
     * the competition the ledger exists to separate.
     */
    private static final String[] MUST_DECLARE_A_CALLER = {
        "lookMissedCommits",
        "lookServerEpochAuthenticator",
        "lookGroupInfoForGroup",
        "lookServerEraEpoch",
        "lookGroupInfo",
        "fetchServerPack",
        "serverStateCheck",
        // The same funnel asked about a group the host has NOT adopted. It is a separate
        // name and not an overload of the one above because the regex below matches
        // `serverStateCheck\s*\(` — `serverStateCheckFor(` does not match it, so without this row the
        // create path's authenticator check would spend a look with no funnel rule over it at all.
        "serverStateCheckFor",
        "detectHealth",
        // detectHealth's own comparison, extracted so runMaintenanceOnce can reach it instead of
        // growing a second copy. It is a funnel by the same rule as the others: it
        // spends a fetchServerEpochAuthenticator on its equal-era-and-epoch arm and has two callers
        // with two different reasons to spend — the reconcile drive's, and the maintenance pass's
        // MAINTENANCE_IDENTITY ration.
        "healthAgainstServer",
    };

    /**
     * The ONLY two files permitted to invoke a primitive, each for a stated reason.
     *
     * <p>{@code MlsProviderTransport} holds the five charged wrappers. {@code ProviderTransport} is
     * the binder shim and necessarily declares and forwards them — it is the layer the wrappers call
     * THROUGH, so a hit there is the ledger working, not a bypass.
     *
     * <p>Everything else under {@code src/} is enumerated and must name none. This replaced a
     * hand-list of three file names, which was true when written and blind to the fourth.
     */
    /**
     * The transport methods allowed to pass an EXEMPT caller — named, not matched by prefix.
     *
     * <p>This was {@code name.startsWith("debug") || startsWith("dump")}, and
     * Mutation B4 showed what that permits: a method called
     * {@code dumpAndRepair} that spells everything correctly, is exempt from the ledger purely
     * because of its name, and then <b>rebuilds a conversation</b>. Not a diagnostic; exempt anyway.
     *
     * <p>The direction matters. My own control tested where a spelling MISSES a defect; this is
     * where a spelling PERMITS one, and authors rarely test that direction because the predicate
     * reads as obviously right. A prefix rule is a hand inventory written as a predicate — the same
     * defect as the file list this class already replaced, one field over.
     *
     * <p>A further entry is then a deliberate act with an argument attached, rather than a naming
     * coincidence. Each of these is an operator diagnostic that returns what it read and
     * changes nothing.
     */
    private static final java.util.Set<String> MAY_PASS_AN_EXEMPT_CALLER =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "dumpGroupExtensions",    // --ez groupexts: reads the server's GroupInfo, logs it
                    "debugServerEraEpoch",    // --ez serverera: reads [era, epoch], logs it
                    "debugGroupInfo",         // --ez ctrl: reads the 1:1 GroupInfo, logs it
                    // --ez servervalidity: parses the ratchet tree the SAME fetch
                    // already returns and logs the leaf identities and certificate windows. Reads
                    // only — it does not join, resync or commit, and the tree it takes is the one
                    // the other three arms here leave unread. Exempt for the same reason they are:
                    // an operator runs it because a group is already wedged, and a diagnostic
                    // refused for budget at that moment answers nothing.
                    "dumpServerValidity"));

    private static final String TRANSPORT = "e2ee/MlsProviderTransport.java";
    private static final String PROVIDER_SHIM = "rcs/ProviderTransport.java";

    /** A method declaration at class level: 4-space indent, a visibility modifier. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    /**
     * <b>THE NEEDLE RULE, and it is load-bearing: enumerate on the INVOKED METHOD NAME, never on the
     * {@code pt("…")} argument.</b>
     *
     * <p>{@code MlsProviderTransport.pt(String what)} is a LOCK-ASSERTION probe: it checks whether a
     * group lock is held across transport I/O and interpolates {@code what} into the exception
     * message. The string exists to name an offending operation in a lock-ordering diagnostic. It
     * identifies nothing, nothing keeps it in step with the method about to be called, and 10 of the
     * 29 distinct tags in the file are not AIDL method names at all.
     *
     * <p>The first version of this guard keyed on {@code pt("<aidlName>")} and enumerated <b>17 of
     * 22</b> doors while reporting green. Two spellings defeat it, both silently:
     *
     * <ul>
     *   <li><b>Held reference</b> — {@code final ProviderTransport pt = pt("establishGroup")} and
     *       then {@code pt.getMlsServerEraEpoch(…)}. The literal exists, at the local's declaration,
     *       naming a different string. Eight such locals exist in the file, and two of them carry
     *       KeyPackage-claim spends, so this is not confined to this resource.</li>
     *   <li><b>Caller-named tag</b> — {@code pt("quarantineIfAheadOfServer").getMlsServerEraEpoch(…)}.
     *       The literal is right there and it names the ENCLOSING METHOD. This is the one that
     *       generalises: the other four are not a special case, the tag is simply free-form.</li>
     * </ul>
     *
     * <p>Keying on the invocation closes both, because a call through a local is still
     * {@code .getMlsServerEraEpoch(} — it is a structural fact rather than a naming convention.
     * <b>The same corollary applies to {@link MlsFetchLedger.Caller}:</b> the tag is unusable as a
     * caller identity (free-form, not unique per site, and one site's tag names something else), so
     * the {@code Caller} is passed as a typed argument and is never derived from it.
     */
    // ---- 1. every primitive call site is inside a charged wrapper ---------------------------------

    @Test
    public void everyPrimitiveCallSiteIsInsideAChargedWrapper() throws IOException {
        // CODE ONLY. The primitive names appear in prose all over this file — "fetchMissedCommits
        // says so in as many words" is a comment, and one log line names a method inside a string —
        // and a scan that reads prose as a call site reports doors that do not exist. Now that the
        // needle is the INVOCATION rather than the pt("…") literal, the literals can go too.
        // Blanked in place, so offsets still line up with the real source.
        final String src = codeOnly(readTransport());
        final List<int[]> decls = declarations(src);
        assertTrue("no method declarations matched — the pattern has gone stale and this guard is "
                + "silently passing, which is worse than failing", decls.size() > 20);

        int sites = 0;
        final List<String> stray = new ArrayList<>();
        final List<String> vanished = new ArrayList<>();
        final List<String> duplicated = new ArrayList<>();
        for (final MlsFetchLedger.Primitive p : MlsFetchLedger.Primitive.values()) {
            int perPrimitive = 0;
            // THE INVOCATION, NOT THE LABEL. The first version of this guard keyed on
            // pt("<aidlName>") and MISSED FIVE SITES, because pt(String) at MlsProviderTransport:565
            // takes a LOCK-ASSERTION LABEL, not an AIDL method name — so the literal is a naming
            // CONVENTION, and four of the five reached the transport through a local
            // `final ProviderTransport pt = pt("establishGroup")` while the fifth was
            // pt("quarantineIfAheadOfServer").getMlsServerEraEpoch(…), where the label names the
            // caller rather than the callee. Found by the concurrent Stage 0 catalogue and
            // confirmed on re-measurement here. A guard keyed on a convention is a guard that fails
            // silently the first time someone does not follow it, which is worse than none.
            final String needle = "." + p.aidlName + "(";
            int at = src.indexOf(needle);
            while (at >= 0) {
                sites++;
                final String enclosing = enclosingMethod(src, decls, at);
                if (wrapperRowFor(enclosing) == null) {
                    stray.add(p.aidlName + " in " + enclosing + "()");
                }
                perPrimitive++;
                at = src.indexOf(needle, at + 1);
            }
            // ZERO MUST FAIL, NOT PASS. A source scan that misses its own subject
            // degrades to "found fewer than exist", and the limit of that is "found none, certified
            // the file". Every primitive has exactly ONE invocation — the one inside its wrapper —
            // so the per-primitive count is a bound this test can state exactly rather than a
            // minimum it hopes for, and a total-only assertion would still pass if one primitive
            // dropped to zero while another gained a site.
            if (perPrimitive == 0) {
                vanished.add(p.aidlName);
            } else if (perPrimitive != 1) {
                duplicated.add(p.aidlName + " x" + perPrimitive);
            }
        }
        if (!vanished.isEmpty()) {
            fail("This guard found NO invocation of " + vanished + " in MlsProviderTransport, so it "
                    + "is no longer watching those doors — and a scan that misses its subject "
                    + "passes silently, which is how an I2 guard fails in exactly the manner the "
                    + "invariant exists to prevent. This is NOT a complaint about naming: the guard "
                    + "enumerates DOORS TO A SCARCE SERVER RESOURCE, and it has just certified a "
                    + "transport it can no longer see. Either the wrapper was renamed or removed "
                    + "(re-point CHARGED_WRAPPERS), the primitive was renamed provider-side (update "
                    + "MlsFetchLedger.Primitive.aidlName), or the file moved (fix read()).");
        }
        if (!duplicated.isEmpty()) {
            fail("These primitives are invoked more than once in MlsProviderTransport: " + duplicated
                    + ". Exactly one invocation each is the whole architecture — the wrapper is the "
                    + "single charge point, and a second invocation is a second door whether or not "
                    + "it happens to sit inside a wrapper too. DoD-2 is one ledger per resource "
                    + "enumerated from its primitive; two wrappers over one primitive is two "
                    + "ledgers wearing one name.");
        }
        assertEquals("the number of primitive INVOCATIONS no longer matches the number of wrappers. "
                + "Each wrapper contains exactly one, so any other count means either a wrapper lost "
                + "its call or a site appeared outside one — including one reached through a local "
                + "ProviderTransport variable, which is how five sites hid from the first version of "
                + "this guard.",
                MlsFetchLedger.Primitive.values().length, sites);
        if (!stray.isEmpty()) {
            fail("These calls spend a GetMlsGroupInfo from a method that is not a charged wrapper, "
                    + "so the RPC happens with nothing counting it: " + stray + ". Do NOT add a new "
                    + "pt(\"…\") site — route the call through the matching look*() wrapper and pass "
                    + "the MlsFetchLedger.Caller that owns the decision. If the primitive is new, "
                    + "add it to MlsFetchLedger.Primitive and give it a wrapper.");
        }
    }

    @Test
    public void everyWrapperReachesTheChargePoint() throws IOException {
        // CODE ONLY. Read raw, a DELETED charge left behind in a comment satisfies every assertion
        // below — measured against this exact method: replace lookGroupInfo's
        // body with `// The charge used to be here: spendOneLook(…)` plus a bare RPC, and the
        // wrapper spends a GetMlsGroupInfo with nothing counting it while this test reports green.
        final String src = codeOnly(readTransport());
        for (final String[] row : CHARGED_WRAPPERS) {
            final String body = bodyOf(src, row[0]);
            assertTrue(row[0] + "() is gone — the wrapper for " + row[1] + " has been renamed or "
                    + "removed, and this guard can no longer see whether its primitive is charged",
                    body.length() > 0);
            assertTrue(row[0] + "() no longer calls " + CHARGE_POINT + " — it makes the RPC without "
                    + "asking the ledger, which is a budget whose answer is discarded. That reads as "
                    + "a guard in review and is a no-op at runtime.",
                    body.contains(CHARGE_POINT));
            assertTrue(row[0] + "() no longer names MlsFetchLedger.Primitive." + row[1]
                    + ", so the ledger is charging the wrong resource or none",
                    body.contains("MlsFetchLedger.Primitive." + row[1]));
            final String aidl = MlsFetchLedger.Primitive.valueOf(row[1]).aidlName;
            assertTrue(row[0] + "() no longer invokes ." + aidl + "(), so it declares a primitive it "
                    + "does not spend — the ledger would be charging a resource nobody is using and "
                    + "the real spend would be somewhere this guard is not looking",
                    body.contains("." + aidl + "("));
        }
    }

    /**
     * The charge point must actually consult {@link MlsFetchLedger#mayFetch} and must REFUSE on a
     * non-permitted verdict.
     *
     * <p>Separate test because the failure modes read differently: "it never asks" is the hole, and
     * "it asks and ignores the answer" is worse, because the next reader believes it. That is the
     * failure {@code MlsPeerReJoinBudgetGuardTest.aRefusedRebuildStops} pins for the era budget.
     */
    @Test
    public void aRefusedLookDoesNotReachTheServer() throws IOException {
        // CODE ONLY, and it must be the BLANKING form: this test compares OFFSETS (refusal before
        // look), so a stripping pass would move the very positions it asserts about. codeOnly blanks
        // in place for exactly that reason.
        final String body = bodyOf(codeOnly(readTransport()), "spendOneLook");
        assertTrue("spendOneLook() is gone — every wrapper's charge went with it", body.length() > 0);
        assertTrue("spendOneLook() does not ask MlsFetchLedger.mayFetch at all",
                body.contains("MlsFetchLedger.mayFetch("));
        final int ask = body.indexOf("MlsFetchLedger.mayFetch(");
        final int refuse = body.indexOf("Look.refusedByLedger(", ask);
        assertTrue("spendOneLook() asks the ledger and has no arm that returns a refusal — a budget "
                + "whose answer is discarded is worse than no budget", refuse >= 0);

        // EVERY OCCURRENCE, NOT THE ONE AFTER THE ASK — found by copying this
        // shape into a new guard, injecting the regression as a control, and watching NOTHING
        // HAPPEN. The form this replaces was
        //     doIt = body.indexOf("doIt.look()", ask);  assertTrue(refuse < doIt);
        // and it is BLIND TO A LOOK HOISTED ABOVE THE ASK, which is precisely the regression the
        // check is named for: indexOf-from-the-ask cannot see anything before the ask, so a
        // `final T early = doIt.look();` inserted above mayFetch() passes untouched.
        //
        // Searching from zero instead would go red on CORRECT code, because the unreadable-ledger
        // arm legitimately looks before the ask — it is permitted by isExempt() rather than by a
        // verdict. THAT ACCOMMODATION IS HOW THE HOLE WAS MADE: a correct local fix whose scope
        // nobody re-derived, which is a FIFTH source-scan axis (a search ORIGIN chosen to dodge
        // a false positive, silently narrowing what the check can see). So the property is pinned
        // PER OCCURRENCE instead, and each one's justification is named.
        final List<Integer> looks = new ArrayList<>();
        int at = body.indexOf("doIt.look()");
        while (at >= 0) {
            looks.add(at);
            at = body.indexOf("doIt.look()", at + 1);
        }
        assertEquals("spendOneLook() has " + looks.size() + " call(s) to doIt.look() and this guard "
                + "knows the context of exactly three: the unreadable-ledger arm (permitted by "
                + "isExempt() rather than by a verdict), the SPEND_EXEMPT arm, and the charged look. "
                + "A FOURTH is a look whose permission this check cannot account for — and the "
                + "failure mode is a GetMlsGroupInfo made before the bound applies, which we "
                + "already measured drawing grpcStatus=8 RESOURCE_EXHAUSTED at ten on one "
                + "conversation.",
                3, looks.size());

        final int first = looks.get(0);
        assertTrue("the FIRST doIt.look() in spendOneLook() is not inside an arm whose condition "
                + "names isExempt(). It runs before the ledger is consulted, so its only possible "
                + "justification is that this caller can never be refused — and nothing there says "
                + "so. Condition read: " + conditionGoverning(body, first),
                conditionGoverning(body, first).contains("isExempt()"));
        assertTrue("the first doIt.look() is at " + first + " and the ask at " + ask + " — the "
                + "exempt arm must come BEFORE the ask, or it is not the arm this guard permits",
                first < ask);

        for (int i = 1; i < looks.size(); i++) {
            final int later = looks.get(i);
            assertTrue("spendOneLook() performs a look at " + later + " before it asks the ledger ("
                    + ask + "). Only the exempt arm may look without asking.", ask < later);
            assertTrue("spendOneLook() performs a look at " + later + " before it can refuse "
                    + "(refusal at " + refuse + "). A refusal after the RPC has been made bounds "
                    + "nothing: the resource is already spent.", refuse < later);
        }
    }

    /**
     * The condition of the {@code if} that governs the statement at {@code at} — the text between
     * {@code if (} and the block it opens.
     *
     * <p>Read instead of a window of characters before the statement, for {@link #armContaining}'s
     * reason: a distance acquires whatever is inserted between the landmarks, and a comment
     * paragraph above an arm is exactly what gets inserted.
     */
    private static String conditionGoverning(final String body, final int at) {
        final int ifAt = body.lastIndexOf("if (", at);
        if (ifAt < 0) return "";
        final int open = body.indexOf('{', ifAt);
        if (open < 0 || open > at) return "";
        return body.substring(ifAt, open);
    }

    // ---- 2. every caller declares its ledger ------------------------------------------------------

    @Test
    public void everyCallerDeclaresTheLedgerItCharges() throws IOException {
        // CODE ONLY. The transport documents these funnels heavily — "Note serverStateCheck() would
        // ALSO have to reach the server to answer" is a comment, and one of its log lines names the
        // method inside a string — and a scan that reads prose as a call site reports three doors
        // that do not exist. Offsets are preserved (matches are blanked, not deleted) so the
        // enclosing-method attribution below still lines up with the real source.
        final String src = codeOnly(readTransport());
        final List<int[]> decls = declarations(src);
        final List<String> undeclared = new ArrayList<>();
        int calls = 0;
        for (final String funnel : MUST_DECLARE_A_CALLER) {
            final Matcher m = Pattern.compile("\\b" + Pattern.quote(funnel) + "\\s*\\(")
                    .matcher(src);
            while (m.find()) {
                // Skip the declaration itself; only CALLS carry an argument list to inspect.
                if (isDeclaration(src, decls, m.start())) continue;
                calls++;
                final String args = argumentPrefix(src, m.end());
                if (!args.contains("MlsFetchLedger.Caller.")
                        && !args.startsWith("caller")) {
                    undeclared.add(funnel + "() in " + enclosingMethod(src, decls, m.start())
                            + "() — first argument was: " + args.trim());
                }
            }
        }
        assertTrue("no calls to any charged wrapper or funnel matched — the names have changed and "
                + "this guard is silently passing", calls >= MUST_DECLARE_A_CALLER.length);
        if (!undeclared.isEmpty()) {
            fail("These calls spend a GetMlsGroupInfo without naming the ledger they charge, so a "
                    + "new path would inherit a ration by omission — the exact shape of the defect "
                    + "this guard closed: " + undeclared + ". Pass the MlsFetchLedger.Caller that "
                    + "owns the decision (or the enclosing method's own `caller` parameter if it is "
                    + "itself a funnel), and add a constant to MlsFetchLedger.Caller if none fits.");
        }
    }

    /**
     * Every {@link MlsFetchLedger.Caller} that is not RETIRED must be USED at a call site — and
     * every constant that IS retired must be used at none.
     *
     * <p>A constant nobody passes is a ration nobody spends, which reads in review as a door that is
     * bounded and is in fact a door that was renamed or deleted while its constant stayed. That is
     * the same shape as {@code MlsAdvancerElection.takeoverReachableWithin} having no production
     * call site (finding 3 of the plan) — an assertion about nothing.
     *
     * <h2>Why RETIRED exists, and why its advice used to be actively harmful</h2>
     *
     * <p>This failed with <i>"Delete it or wire it"</i> when {@code reWelcomeDivergedPeers} was
     * deleted and left {@code RE_WELCOME_SWEEP} charged by nobody.
     * <b>Both branches of that instruction were wrong for this constant.</b> Wiring it would have
     * restored a blind era advance across every conversation on the device; deleting it would have
     * been worse and silent, because {@link MlsFetchLedgerRecord} persists the caller BY ORDINAL
     * ({@code who[n] = caller.ordinal()}, read back by comparing ordinals), so removing a constant
     * shifts every later one and reattributes ledger rows already on disk to the wrong caller.
     * Reclaiming the slot is a migration, not a deletion.
     *
     * <p>So a red test whose message prescribes the harmful action is worse than no test: the next
     * reader does what it says. The remedy is a third state rather than an exemption list here,
     * because the reason lives at the constant and this file should not carry a second copy of it.
     *
     * <p><b>RETIRED FLIPS THE ASSERTION, IT DOES NOT SKIP IT.</b> A retired constant must have NO
     * call site, so the word cannot be used to silence a live door that someone quietly stopped
     * charging — which is the exact defect this test was written to catch, and would be the obvious
     * way to defeat it.
     */
    @Test
    public void everyCallerConstantIsActuallyUsed() throws IOException {
        // CODE ONLY: a constant named in a comment — "we used to charge STALL_REFRESH here" —
        // would otherwise satisfy this and report a ration nobody spends as a live door.
        final String src = codeOnly(readTransport()) + codeOnly(readDebugReceiver());
        // RAW, not codeOnly: the RETIRED marker lives in the constant's JAVADOC, and codeOnly()
        // blanks comments. Reading the declaration through codeOnly would find no marker on any
        // constant and quietly restore the old behaviour — a guard that cannot see its own exemption.
        final String ledger = read("engine/src/com/android/messaging/rcs/engine/mls/"
                + "MlsFetchLedger.java");
        final List<String> unusedAndLive = new ArrayList<>();
        final List<String> retiredButStillCharged = new ArrayList<>();
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            final boolean used = src.contains("MlsFetchLedger.Caller." + c.name());
            if (isRetired(ledger, c.name())) {
                if (used) retiredButStillCharged.add(c.name());
            } else if (!used) {
                unusedAndLive.add(c.name());
            }
        }
        if (!unusedAndLive.isEmpty()) {
            fail("These MlsFetchLedger.Caller constants are passed at no call site: " + unusedAndLive
                    + ". A ration nobody spends looks like a bounded door and is not one — either "
                    + "the door was renamed and its constant left behind, or the constant was added "
                    + "for a caller that never landed. Wire it — or, if its caller was deliberately "
                    + "DELETED, mark the constant RETIRED in its MlsFetchLedger javadoc and say what "
                    + "replaced it. DO NOT DELETE THE CONSTANT: MlsFetchLedgerRecord persists the "
                    + "caller by ORDINAL, so removing one shifts every later constant and silently "
                    + "reattributes ledger rows already on disk.");
        }
        if (!retiredButStillCharged.isEmpty()) {
            fail("These MlsFetchLedger.Caller constants are marked RETIRED in their javadoc but ARE "
                    + "still passed at a call site: " + retiredButStillCharged + ". RETIRED means "
                    + "the door is gone and the ordinal is only reserved; a live charge against one "
                    + "is either a caller that came back without the javadoc being updated, or the "
                    + "word being used to silence this test for a door that is still open.");
        }
    }

    /**
     * Does {@code name}'s declaration in {@code MlsFetchLedger} carry a RETIRED marker in the
     * javadoc immediately above it?
     *
     * <p>Anchored on the DECLARATION (<code>NAME(</code> at enum indent) and searching BACKWARDS to
     * the end of the previous one, so a constant cannot inherit its neighbour's marker — which is
     * what a plain "RETIRED appears somewhere near NAME" test would do, and it would exempt whichever
     * constant happened to be declared next.
     */
    private static boolean isRetired(final String ledgerSrc, final String name) {
        final Matcher m = Pattern.compile("(?m)^        " + Pattern.quote(name) + "\\s*\\(")
                .matcher(ledgerSrc);
        if (!m.find()) return false;
        final int declAt = m.start();
        // THE ATTACHED JAVADOC BLOCK, EXACTLY — not "the text since the previous constant".
        // The loose window matched RETIRED_AIDL_SPELLINGS, an unrelated CLASS-level constant 60
        // lines above, and reported the first enum constant as retired. Measured; it is why this
        // reads a delimited block instead of a range.
        final int close = ledgerSrc.lastIndexOf("*/", declAt);
        if (close < 0) return false;
        // ATTACHED means only whitespace between the javadoc's close and the declaration. Without
        // this a constant with NO javadoc of its own silently inherits its predecessor's.
        if (!ledgerSrc.substring(close + 2, declAt).trim().isEmpty()) return false;
        final int open = ledgerSrc.lastIndexOf("/**", close);
        if (open < 0) return false;
        return ledgerSrc.substring(open, close).contains("RETIRED");
    }

    /** Exactly one caller may fail open, and it must be the one whose reasoning is written down. */
    @Test
    public void onlyThePeerReportVerificationFailsOpen() {
        final List<String> failOpen = new ArrayList<>();
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            if (c.failsOpenOnRefusal()) failOpen.add(c.name());
        }
        assertEquals("fail-open is a deliberate, single exception (decision D3): the cost of acting "
                + "on a real report we could not verify is a redundant repair, while the cost of "
                + "dropping one is a peer that stays diverged forever. Any OTHER caller failing "
                + "open would be spending the resource and then ignoring the refusal, which is a "
                + "budget in name only.",
                java.util.Collections.singletonList("PEER_REPORT_VERIFY"), failOpen);
    }

    /**
     * The fail-open caller must still CHARGE, and its site must say what it did.
     *
     * <p>The asymmetry is only safe if both halves hold. Charging without proceeding would drop real
     * reports; proceeding without charging would leave a peer-driven spend uncounted, which is the
     * sharpest of the eleven doors named above.
     */
    @Test
    public void theFailOpenCallerChargesAndSaysSo() throws IOException {
        final String body = codeOnly(bodyOf(readTransport(), "reporterIsAMember"));
        assertTrue("reporterIsAMember() is gone — the fail-open exception has moved and this guard "
                + "no longer watches it", body.length() > 0);
        assertTrue("reporterIsAMember() no longer charges the ledger. Its rate is driven by a PEER "
                + "rather than by a loop of ours (onPeerReportedFailure calls it once per report, "
                + "and PEER_FTD_ESCALATE_AT bounds the escalation, not this fetch), so an uncounted "
                + "spend here is the one most likely to surprise the next recovery.",
                body.contains("MlsFetchLedger.Caller.PEER_REPORT_VERIFY"));
        assertTrue("reporterIsAMember() no longer returns true on a refusal — it has stopped failing "
                + "open, and our own rate ledger is now acting as evidence against a peer. Dropping "
                + "a real report leaves that peer diverged forever.",
                body.contains("describeFailOpen("));
    }

    /** A debug arm must be exempt AND say so, because either half alone is a lie. */
    @Test
    public void theDebugArmIsExemptAndSaysSo() throws IOException {
        assertTrue("DEBUG_DUMP is no longer exempt — a debug arm refused for budget is useless, "
                + "because the operator ran it precisely when something is already wrong",
                MlsFetchLedger.Caller.DEBUG_DUMP.isExempt());
        assertTrue("DEBUG_HEALTH is no longer exempt", MlsFetchLedger.Caller.DEBUG_HEALTH.isExempt());
        final String body = codeOnly(bodyOf(readTransport(), "spendOneLook"));
        assertTrue("spendOneLook() no longer logs describeExemption for an exempt caller. A debug "
                + "arm that silently spent a recovery look would be lying about what the operator's "
                + "next recovery has left.", body.contains("describeExemption("));
    }

    /**
     * <b>A ledger refusal must not arrive after the rebuild has destroyed our state.</b>
     *
     * <p>{@code rebuildConversation} hoists {@code allowedToJoinAll} and
     * {@code MlsPeerGuard.allowEraAdvance} ABOVE its two forgets, for a reason it states itself: a
     * refusal that arrived after the forget would leave the conversation with no provider record, no
     * engine state and no group — strictly worse than the churn the refusal prevents.
     *
     * <p>The fetch ledger's charge points are BELOW the forget and, unlike a guard, cannot be
     * hoisted: {@code ensureReady}'s two era reads happen in RESPONSE to the server refusing a create
     * that has already been attempted. So the rebuild passes {@code stateAlreadyDestroyed=true} and
     * those arms fail OPEN. Found after the first cut shipped them declining.
     *
     * <p>Pinned as a source shape because the failure is invisible at runtime until it happens to a
     * real conversation, and by then the state is gone.
     */
    @Test
    public void aLedgerRefusalAfterTheForgetFailsOpen() throws IOException {
        // TWO READINGS, one per assertion, because this method asserts two different kinds of fact.
        //
        // The ORDERING (forget before ensureReady) is a fact about CODE, so it reads codeOnly — a
        // comment mentioning mlsForgetConversation() would otherwise move the offsets it compares.
        //
        // The flag is carried by a NAMED-ARGUMENT COMMENT (`/*stateAlreadyDestroyed=*/ true`) — the
        // code either side is a bare `true` and says nothing — so that one assertion, and only that
        // one, reads raw. Using raw for BOTH was the bug: it let a commented-out forget or
        // ensureReady call move the ordering check.
        final String raw = readTransport();
        final String src = codeOnly(raw);
        final String rebuild = bodyOf(src, "rebuildConversation");
        final String rebuildRaw = bodyOf(raw, "rebuildConversation");
        assertTrue("rebuildConversation not found — it has been renamed, and it is the one caller "
                + "that reaches ensureReady with our state already dropped", rebuild.length() > 0);
        final int destroy = indexOfFirst(rebuild,
                "mlsForgetGroupConversation(", "mlsForgetConversation(");
        final int ensure = rebuild.indexOf("ensureReady(");
        assertTrue("rebuildConversation no longer drops the provider half — the ordering this test "
                + "is about has changed and it no longer means anything", destroy >= 0);
        assertTrue("rebuildConversation no longer reaches ensureReady", ensure >= 0);
        assertTrue("ensureReady is now called BEFORE the forget (" + ensure + " vs " + destroy
                + "). If that is deliberate the fail-open flag may no longer be needed — but check, "
                + "because this test was written on the opposite ordering.", ensure > destroy);
        assertTrue("rebuildConversation reaches ensureReady AFTER dropping both halves of our state "
                + "and does not pass stateAlreadyDestroyed=true. A fetch-ledger refusal inside "
                + "ensureReady would then DECLINE, leaving the conversation with no provider "
                + "record, no engine state and no group — the exact outcome rebuildConversation "
                + "hoists its guards above the forget to prevent.",
                rebuildRaw.contains("stateAlreadyDestroyed=*/ true"));

        // CODE ONLY here, and a BRACE-MATCHED block rather than a character window. The first cut
        // read 120 raw characters forward from `.refused()`, and these arms open with a paragraph of
        // comment — so the `return false` sat outside the window and an unguarded decline passed the
        // check. A guard that cannot see the statement it is about is the shape this whole stage is
        // against; it was caught by injecting the regression and watching nothing happen.
        final String ready = bodyOf(src, "ensureReady", "final boolean stateAlreadyDestroyed)");
        assertTrue("ensureReady's stateAlreadyDestroyed overload is gone", ready.length() > 0);
        int at = ready.indexOf(".refused()");
        int arms = 0;
        while (at >= 0) {
            arms++;
            final int ifAt = ready.lastIndexOf("if (", at);
            assertTrue("a .refused() in ensureReady is not inside an if — this guard cannot read the "
                    + "arm and must not certify it", ifAt >= 0);
            final int open = ready.indexOf('{', at);
            assertTrue("a refusal arm in ensureReady has no block", open >= 0);
            // The brace must open the arm, not belong to something after a braceless statement —
            // otherwise the "condition" swallows real code and the block is a neighbour's.
            final int semi = ready.indexOf(';', at);
            assertTrue("the refusal arm at " + at + " has a statement before its block, so this "
                    + "guard would read a NEIGHBOUR's braces as the arm — a span bounded by "
                    + "whatever comes next is not an arm", semi < 0 || open < semi);
            final String condition = ready.substring(ifAt, open);
            final String block = bracedBlock(ready, open);
            if (block.contains("return false")) {
                assertTrue("A REFUSAL ARM IN ensureReady DECLINES WITHOUT CHECKING "
                        + "stateAlreadyDestroyed: " + condition.trim() + ". Reached from "
                        + "rebuildConversation both halves of our state are already gone, so this "
                        + "return leaves the conversation with no provider record, no engine state "
                        + "and no group — strictly worse than the churn the refusal prevents, and "
                        + "the exact outcome rebuildConversation hoists its guards above the forget "
                        + "to avoid.",
                        condition.contains("!stateAlreadyDestroyed"));
            }
            at = ready.indexOf(".refused()", at + 1);
        }
        assertTrue("no ledger refusal arms found in ensureReady — either the era reads stopped being "
                + "charged (which would be a hole) or they were renamed, and either way this guard "
                + "is no longer watching anything", arms >= 2);
    }

    /**
     * A rebuild we could not verify must not be reported as one that failed.
     *
     * <p>{@code !repaired()} is what {@code offerTheStallChoiceOffThread} raises the
     * stalled-conversation notification on, so collapsing a refused verification into
     * {@code NOT_CONVERGED} puts a stall alert in front of a person for a conversation that may be
     * perfectly repaired, offering a Try again that cannot shorten our own window.
     */
    @Test
    public void anUnverifiedRebuildIsNotReportedAsAFailedOne() throws IOException {
        final String rebuild = bodyOf(codeOnly(readTransport()), "rebuildConversation");
        final int refused = rebuild.indexOf("ServerState.REFUSED_BY_LEDGER");
        assertTrue("rebuildConversation no longer distinguishes a refused confirming look at all — "
                + "it is reporting an unverified rebuild as a measured outcome", refused >= 0);
        // THE ARM'S OWN BLOCK, brace-matched — NOT a fixed window forward from the marker. This
        // read `refused + 1400` until these guards were swept for the character-window
        // shape I had flagged; mine still had one, in a test written after I flagged it. A distance
        // acquires whatever is inserted between the landmarks: pad the arm past 1400 characters and
        // this goes red on correct code, and any later mention of the constant satisfies it.
        final String after = armContaining(rebuild, refused);
        assertTrue("rebuildConversation detects the refused verification and does not return "
                + "RAN_BUT_UNVERIFIED from THAT ARM, so it falls through to NOT_CONVERGED — which "
                + "raises a stall alert to a person about a rebuild nobody measured. "
                + "A return somewhere else in the method does not count: this must be the arm the "
                + "refusal takes.",
                after.contains("MlsRebuildOutcome.RAN_BUT_UNVERIFIED"));
        assertFalse("RAN_BUT_UNVERIFIED must not reach a person: its exit is our own window passing, "
                + "which no Try again shortens",
                MlsRebuildOutcome.RAN_BUT_UNVERIFIED.needsAPerson());
    }

    /**
     * A rebuild our OWN ledger deferred must not be reported as one a peer-protecting guard refused
     * — the sibling of the check above at the OTHER end of the rebuild.
     *
     * <p>{@code rebuildConversation}'s opening look asks whether the server still holds the
     * conversation, which is what decides whether the rebuild is charged to the era budget.
     * Refusing the rebuild when that look is refused is CORRECT — proceeding would
     * classify an unasked question as a first create and charge nothing. Returning
     * {@link MlsRebuildOutcome#REFUSED_BY_GUARD} for it was not: that value's {@code line()} asserts
     * the joining allowlist, the era budget, the peer-health streak or the kill switch refused,
     * <b>none of which ran</b>, and its {@code needsAPerson()} then raised the stalled-conversation
     * notification carrying that text — offering a Try again that resets six bounds, not one of
     * which is the fetch ledger.
     *
     * <p>Pinned on the ARM rather than on the method, for {@code anUnverifiedRebuildIsNotReportedAsA
     * FailedOne}'s reason: a correct return somewhere else in a 230-line method is not this arm
     * behaving.
     */
    @Test
    public void aLedgerRefusalBeforeTheRebuildIsNotReportedAsAGuardRefusal() throws IOException {
        final String rebuild = bodyOf(codeOnly(readTransport()), "rebuildConversation");
        final int opening = rebuild.indexOf("MlsFetchLedger.Caller.REBUILD");
        assertTrue("rebuildConversation no longer charges an opening look at all — the question that "
                + "decides whether this rebuild is billed to the era budget is not being asked, "
                + "which re-opens the second door", opening >= 0);
        final int refusalArm = rebuild.indexOf(".refused()", opening);
        assertTrue("rebuildConversation charges the opening look and never tests whether it was "
                + "refused — a ledger whose answer is discarded is worse than no ledger",
                refusalArm >= 0);
        final String arm = armContaining(rebuild, refusalArm);
        assertTrue("the opening look's refusal arm does not return DEFERRED_BY_OUR_OWN_LEDGER. If it "
                + "returns REFUSED_BY_GUARD it is claiming a peer-protecting guard refused when none "
                + "ran, and raising a stall alert whose Try again cannot reach the fetch ledger "
                + ". Arm read: " + arm.trim(),
                arm.contains("MlsRebuildOutcome.DEFERRED_BY_OUR_OWN_LEDGER"));
        assertFalse("that arm must NOT still return REFUSED_BY_GUARD",
                arm.contains("MlsRebuildOutcome.REFUSED_BY_GUARD"));
        assertFalse("DEFERRED_BY_OUR_OWN_LEDGER must not reach a person: Try again resets the "
                + "self-heal budget, the rebuild rate bound and its episode suppressor, the era "
                + "budget, the peer-health streak, the re-establish cooldown and the external-commit "
                + "allowance — and NOT the fetch ledger, whose only exit is its own window passing",
                MlsRebuildOutcome.DEFERRED_BY_OUR_OWN_LEDGER.needsAPerson());
    }

    /**
     * <b>The FOUR ways a rebuild can have no server pack must not share a value</b> — the third
     * of this family after the two above.
     *
     * <p>{@code serverPackForRebuild} answered {@code byte[]} and {@code null} covered four
     * situations: a 1:1, a group we hold no state for, our own ledger refusing, and the look coming
     * back with nothing or throwing. Unlike the two checks above <b>no caller behaviour differed
     * between them</b> — every arm degrades the rebuild to the recorded membership, deliberately
     * — so what this pins is the DIAGNOSTIC, and the diagnostic is what bit: a
     * device fixture was derived twice from "that method charges a third {@code REBUILD} look per
     * attempt", and it is false for the arm {@code --ez forgetgroup} actually produces, which
     * returns above the charge.
     *
     * <p><b>Per occurrence, not per ordering</b> — the lesson from the check three
     * rows up. Every {@code return} in the method is taken and required to name a
     * {@link MlsServerPackOutcome}; a fifth arm added later with a bare {@code null} fails on its own
     * occurrence rather than on a landmark that some other arm can satisfy for it. The one ordering
     * assertion here searches for the fetch from the START of the body, so a fetch HOISTED above the
     * no-local-state arm is visible — which is precisely the form that was found invisible.
     *
     * <p>The behavioural half is asserted on the enum itself in the same test, because the fixture
     * fact and the arm that produces it are the pair that has to stay in step: pinning where the
     * arm lives while {@code spentALook()} drifts would certify the misreading rather than the code.
     */
    @Test
    public void theFourWaysToHaveNoServerPackDoNotShareAValue() throws IOException {
        final String body = bodyOf(codeOnly(readTransport()), "serverPackForRebuild");
        assertFalse("serverPackForRebuild is not in MlsProviderTransport under that name — this "
                + "check is scanning nothing, which is the strongest form of a guard reporting "
                + "green", body.isEmpty());

        // PER OCCURRENCE. Each return is taken to its own semicolon — a bound the code supplies,
        // not a character distance — and must name the situation it is in.
        final List<String> bare = new ArrayList<>();
        int returns = 0;
        for (int at = body.indexOf("return"); at >= 0; at = body.indexOf("return", at + 1)) {
            final int semi = body.indexOf(';', at);
            if (semi < 0) continue;
            returns++;
            final String stmt = body.substring(at, semi + 1);
            if (!stmt.contains("ServerPack.")) bare.add(stmt.trim());
        }
        assertTrue("serverPackForRebuild has fewer than four returns, so it can no longer be "
                + "distinguishing four situations at all", returns >= 4);
        if (!bare.isEmpty()) {
            fail("serverPackForRebuild returns something that cannot say WHICH of the four "
                    + "situations it is in: " + bare + ". A bare null here is the state it was "
                    + "found in — a ledger refusal sharing a value with \"there was nothing to "
                    + "ask with\" — and it produced a wrong device fixture before it produced "
                    + "anything else.");
        }

        // THE ARM THAT RETURNS ABOVE THE CHARGE, which is the one the fixture turns on.
        final int noState = body.indexOf("MlsServerPackOutcome.NO_LOCAL_STATE_TO_ASK_WITH");
        assertTrue("serverPackForRebuild no longer distinguishes the group-we-hold-no-state-for "
                + "arm. That arm is the REJOIN shape --ez forgetgroup produces and it returns "
                + "BEFORE the fetch ledger is consulted, so a fixture reaching the rebuild rung "
                + "that way exercises TWO of REBUILD's three sites, not three.",
                noState >= 0);
        final int fetch = body.indexOf("fetchServerPack(");
        assertTrue("serverPackForRebuild never calls fetchServerPack — the pack look is gone, and "
                + "with it the third REBUILD site this check is about", fetch >= 0);
        assertTrue("the no-local-state arm now sits BELOW the fetch (arm at " + noState + ", fetch "
                + "at " + fetch + "). Searched from the start of the body precisely so a hoisted "
                + "fetch is visible: if the fetch runs first then this arm DOES charge the ledger, "
                + "and MlsServerPackOutcome.NO_LOCAL_STATE_TO_ASK_WITH.spentALook() is now lying to "
                + "the next person planning a device fixture.", noState < fetch);

        // THE LEDGER REFUSAL, brace-matched to its own arm rather than found anywhere in the method.
        final int refusal = body.indexOf(".refused()");
        assertTrue("serverPackForRebuild charges a look and never tests whether it was refused — a "
                + "ledger whose answer is discarded is worse than no ledger", refusal >= 0);
        final String arm = armContaining(body, refusal);
        assertTrue("the pack look's refusal arm does not return REFUSED_BY_LEDGER. If it returns "
                + "the same thing as the arms above, a fall-back caused by OUR OWN bound is "
                + "indistinguishable from one caused by having nothing to ask with — which is the "
                + "whole point. Arm read: " + arm.trim(),
                arm.contains("MlsServerPackOutcome.REFUSED_BY_LEDGER"));

        // AND THE FACT THE FIXTURE QUOTES, pinned beside the arm that produces it.
        assertEquals("NO_LOCAL_STATE_TO_ASK_WITH returns above the charge, so it spends nothing",
                Boolean.FALSE, MlsServerPackOutcome.NO_LOCAL_STATE_TO_ASK_WITH.spentALook());
        assertEquals("NOT_A_GROUP never reaches the ledger either — a 1:1 fetches no pack",
                Boolean.FALSE, MlsServerPackOutcome.NOT_A_GROUP.spentALook());
        assertEquals("a REFUSAL IS NOT A SPEND: spendOneLook calls MlsFetchLedgerRecord.charged only "
                + "on the permitted, non-exempt path, so a refused look consults the ledger and "
                + "increments nothing",
                Boolean.FALSE, MlsServerPackOutcome.REFUSED_BY_LEDGER.spentALook());
        assertEquals("SERVER_HAD_NOTHING is the one absence that DID charge — the look was taken",
                Boolean.TRUE, MlsServerPackOutcome.SERVER_HAD_NOTHING.spentALook());
        assertEquals("LOOK_FAILED must stay UNKNOWN. The throw can come from either side of "
                + "MlsFetchLedger.mayFetch, and answering false there is this type's own defect "
                + "committed inside this type.",
                null, MlsServerPackOutcome.LOOK_FAILED.spentALook());
    }

    /**
     * Every {@link MlsServerPackOutcome} is actually produced by the transport.
     *
     * <p>Enumerated from the enum at runtime, per constant, and a constant with ZERO sites FAILS.
     * A value nobody produces reads as accounted-for while covering nothing, and
     * this family has already shipped that defect once in an exemption table. It also means a
     * constant added later arrives as an unanswered question rather than as a blank, which is what
     * {@link MlsFetchLedgerCoverage} does for the ledger's callers one layer up.
     */
    @Test
    public void everyServerPackOutcomeIsProducedSomewhereInTheTransport() throws IOException {
        final String src = codeOnly(readTransport());
        final List<String> unproduced = new ArrayList<>();
        for (final MlsServerPackOutcome o : MlsServerPackOutcome.values()) {
            final Matcher m = Pattern.compile(
                    "MlsServerPackOutcome\\s*\\.\\s*" + Pattern.quote(o.name()) + "\\b").matcher(src);
            if (!m.find()) unproduced.add(o.name());
        }
        if (!unproduced.isEmpty()) {
            fail("These MlsServerPackOutcome constants are named by nothing in MlsProviderTransport: "
                    + unproduced + ". Either an arm stopped producing one — in which case the "
                    + "situation it names is back to sharing a value with its neighbour — or a "
                    + "constant was added with no site, which is a vocabulary word for a case the "
                    + "code cannot be in. Both are the original defect returning.");
        }
    }

    /**
     * An EXEMPT caller may be passed only from a debug arm.
     *
     * <p>The exemption's stated reason is that only debug arms hold it — <b>and a reason in an
     * exemption table is an assertion</b>. Nothing enforced it: a
     * production path could have taken {@code DEBUG_HEALTH} and spent unbounded look-ups with the
     * ledger reporting the arm as correctly exempt. That is the one shape no negative control on the
     * other checks can catch, because a wrong reason with a right verdict never fails a test.
     *
     * <p>"Debug arm" is asserted structurally: the call is in {@code RcsDebugSendReceiver}, or in a
     * transport method whose name begins {@code debug} or {@code dump}.
     */
    @Test
    public void anExemptCallerIsPassedOnlyFromADebugArm() throws IOException {
        final String transport = codeOnly(readTransport());
        final List<int[]> decls = declarations(transport);
        final List<String> production = new ArrayList<>();
        int sites = 0;
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            if (!c.isExempt()) continue;
            final String needle = "MlsFetchLedger.Caller." + c.name();
            int at = transport.indexOf(needle);
            while (at >= 0) {
                sites++;
                final String enclosing = enclosingMethod(transport, decls, at);
                if (!MAY_PASS_AN_EXEMPT_CALLER.contains(enclosing)) {
                    production.add(c.name() + " passed from " + enclosing + "()");
                }
                at = transport.indexOf(needle, at + 1);
            }
        }
        assertTrue("no exempt caller is passed anywhere in the transport — either the debug arms "
                + "stopped charging or the constants were renamed, and this check is watching "
                + "nothing", sites > 0);
        if (!production.isEmpty()) {
            fail("An EXEMPT caller is passed from a PRODUCTION method: " + production + ". Exempt "
                    + "means never refused and never counted, which is only defensible for an "
                    + "operator's diagnostic — a production path holding it spends GetMlsGroupInfo "
                    + "without bound while the ledger reports the arm as correctly exempt. Give the "
                    + "path its own Caller with a ration, or move the call into a debug arm.");
        }
        // And the exemption must not have quietly spread beyond the two arms it was written for.
        assertEquals("the set of EXEMPT callers has changed. Exemption is the strongest thing this "
                + "enum can say — never refused, never counted — and it was argued for exactly two "
                + "debug arms. A third needs its own argument, not this list widened.",
                java.util.Arrays.asList("DEBUG_DUMP", "DEBUG_HEALTH"), exemptNames());
    }

    private static List<String> exemptNames() {
        final List<String> out = new ArrayList<>();
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            if (c.isExempt()) out.add(c.name());
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * <b>No consumer may test equality against the bare literal {@code -1}.</b>
     *
     * <p>Two returns in this transport carry a SECOND negative sentinel beside {@code -1}:
     * {@code HEAL_LOOK_UNAVAILABLE} and {@code ERA_ADVANCE_LOOK_UNAVAILABLE}, both {@code -2}, both
     * meaning "our own ledger refused a look" as against "the operation was attempted and failed".
     * Their whole safety argument is that they are STILL NEGATIVE, so every consumer testing a
     * RELATION ({@code < 0}, {@code >= 0}, {@code > 0}) keeps treating them as failure and no
     * behaviour changes when one is introduced.
     *
     * <p><b>That argument holds only while no consumer tests EQUALITY.</b> A single
     * {@code advanced == -1} would silently exclude {@code -2} — turning a refusal into a
     * non-match at that site, which is the exact defect the sentinel was added to fix,
     * reintroduced by the fix. A device run verified by hand that all five
     * {@code eraAdvance} consumers test relations; nothing kept that true, and a future caller
     * written the obvious way would break it silently.
     *
     * <p>So the property is pinned rather than re-verified. Comparing against the NAMED constants is
     * fine and expected — that is a caller deliberately telling the two apart.
     */
    @Test
    public void noConsumerTestsEqualityAgainstTheBareMinusOne() throws IOException {
        final String src = codeOnly(readTransport());
        final Matcher m = Pattern.compile("[=!]=\\s*-1\\b").matcher(src);
        final List<int[]> decls = declarations(src);
        assertTrue("ZERO HITS MUST FAIL: the transport's class-level declarations is EMPTY, so the check below is satisfied "
                        + "by having read nothing. A zero-match scan is a broken scan, not a clean "
                        + "tree.",
                decls.size() > 50);
        final List<String> bare = new ArrayList<>();
        while (m.find()) {
            bare.add(enclosingMethod(src, decls, m.start()) + "(): "
                    + src.substring(Math.max(0, m.start() - 40), m.end()).replace('\n', ' ').trim());
        }
        if (!bare.isEmpty()) {
            fail("These compare against the BARE LITERAL -1: " + bare + ". Two returns here carry a "
                    + "second negative sentinel (HEAL_LOOK_UNAVAILABLE, "
                    + "ERA_ADVANCE_LOOK_UNAVAILABLE, both -2) whose entire safety argument is that "
                    + "consumers test a RELATION and so keep treating them as failure. An equality "
                    + "test silently EXCLUDES -2, turning a ledger refusal into a non-match — the "
                    + "defect those sentinels exist to fix, reintroduced by the fix. Use `< 0` for "
                    + "'did not succeed', or compare against the NAMED constant to tell them apart.");
        }
    }

    // ---- 3. nothing reaches the provider's primitives around the transport ------------------------

    @Test
    public void nothingReachesTheProviderPrimitivesAroundTheLedger() throws IOException {
        // ENUMERATED, NOT HAND-LISTED. This took a list of three file names — correct on the day it
        // was written and blind to the fourth file ever added, which is the same hand-inventory
        // defect the whole guard exists to replace, sitting in the guard's own exemption table
        // (a reason in an exemption table is an assertion). It walks every
        // .java under src/ now, and the allow-list is TWO files with a stated reason each.
        final List<String> around = new ArrayList<>();
        final List<File> all = javaSourcesUnderSrc();
        assertTrue("found no Java sources under src/ — the locator has gone stale and this check is "
                + "certifying a tree it cannot see", all.size() > 50);
        // ...AND THAT THEY HAVE CONTENT. The assertion above counts FILES, which are still there
        // when a scan is reading nothing: measured with every source truncated to
        // zero bytes this check stayed GREEN because the locator still found >50 files. A corpus
        // assertion on the wrong quantity is itself a check that cannot fail.
        int withContent = 0;
        for (final File f : all) if (f.length() > 0) withContent++;
        assertTrue("the locator found " + all.size() + " Java sources under src/ and " + withContent
                + " of them have any content — this check would be certifying a tree it cannot "
                + "read", withContent > 50);
        for (final File f : all) {
            final String rel = f.getPath();
            if (rel.endsWith(TRANSPORT) || rel.endsWith(PROVIDER_SHIM)) continue;
            final String src = codeOnly(new String(
                    Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (final MlsFetchLedger.Primitive p : MlsFetchLedger.Primitive.values()) {
                // BOTH SPELLINGS. A METHOD REFERENCE has no parens, so `\.NAME\s*\(` misses
                // `return pt::getMlsGroupInfo;` — a door handed out to be called later, in a file
                // this enumeration already reads. Found by mutation A1; the same
                // alternation is what MlsPeerReJoinBudgetGuardTest runs for `mSelf::addMember`.
                final String q = Pattern.quote(p.aidlName);
                final Matcher m = Pattern.compile("\\.\\s*" + q + "\\s*\\(|::\\s*" + q + "\\b")
                        .matcher(src);
                if (m.find()) around.add(rel + " calls " + p.aidlName + "()");
            }
        }
        if (!around.isEmpty()) {
            fail("These call a GetMlsGroupInfo primitive without going through MlsProviderTransport, "
                    + "so they are doors to the resource OUTSIDE the class that owns it — invisible "
                    + "to any enumeration of MlsProviderTransport, which is where every inventory of "
                    + "these doors has looked: " + around + ". That is how getMlsGroupInfo's only "
                    + "caller went unnoticed. Route it through the transport with an "
                    + "MlsFetchLedger.Caller (an exempt one, if it is a debug arm).");
        }
    }

    /**
     * The ledger's window must be the measured one, not a number of its own.
     *
     * <p>Two constants for one datum drift, and the drift is silent: re-measuring the throttle would
     * move {@code MlsFetchBudget}'s retry scheduling and leave the ledger counting on the old window,
     * with nothing failing.
     */
    @Test
    public void theWindowIsTheOneMeasuredDatum() {
        assertEquals("MlsFetchLedger.WINDOW_MS has stopped being MlsFetchBudget's measured cooldown. "
                + "There is ONE datum here — 200s, after which the identical single fetch succeeded "
                + "on deviceA, 2026-09-08 — and it must have one home.",
                MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS, MlsFetchLedger.WINDOW_MS);
    }

    /**
     * The shared ceiling must sit strictly below the burst that was measured exhausting the quota,
     * and strictly above the sequence the device fixture runs.
     *
     * <p>A bound set at the value that failed is a bound that fails. A bound below the sequence we
     * deliberately exercise is a bound that breaks the test that would prove it.
     */
    @Test
    public void theCeilingClearsTheFixtureAndStopsTheBurst() {
        assertTrue("the shared ceiling (" + MlsFetchLedger.SHARED_CEILING + ") is not below the ten "
                + "fetches that drew grpcStatus=8 RESOURCE_EXHAUSTED. A bound set at the value that "
                + "failed is a bound that fails.", MlsFetchLedger.SHARED_CEILING < 10);
        final int fixture = MlsFetchLedger.Caller.RECONCILE_DRIVE.ration
                + MlsFetchLedger.Caller.SELF_HEAL.ration;
        // STRICTLY ABOVE, not at. Measured on both test devices 2026-09-08: the drive-then-self-heal
        // sequence also carries one MAINTENANCE charge on the same conversation from session
        // bring-up, and with the ceiling at exactly `fixture` the run finished at 7 of 7 — at the
        // bound, so the next ordinary look on a HEALTHY conversation would have been refused. A
        // ceiling that binds on the healthy case is not the bound this class is for.
        assertTrue("the shared ceiling (" + MlsFetchLedger.SHARED_CEILING + ") leaves no room above "
                + "the drive + self-heal sequence the device verification runs back to back ("
                + fixture + "). The real sequence carries a MAINTENANCE charge too, so a ceiling at "
                + "the fixture's own size refuses the healthy case.",
                MlsFetchLedger.SHARED_CEILING > fixture);
    }

    /**
     * The health readers must stay outside the shared ceiling.
     *
     * <p>This is the whole of D3's argument against one global ration, and it is a property of the
     * enum rather than of any call site — so it is asserted here rather than left to a reader
     * noticing the {@code false} in a constructor argument.
     */
    @Test
    public void theHealthReadersDoNotCompeteWithRecovery() {
        assertTrue("HEALTH_PROBE now charges the shared ceiling, so recovery can starve the DIVERGED "
                + "health test. We already showed an unreadable health escalates the ladder to a "
                + "heavier remedy than the fault needed — which is why D3 rejected one global "
                + "ration.", !MlsFetchLedger.Caller.HEALTH_PROBE.chargesTheSharedCeiling);
        assertTrue("STALL_REFRESH now charges the shared ceiling. A person has just pressed Try "
                + "again and is watching the alert; refusing that read because recovery spent the "
                + "allowance leaves them looking at a notification that reflects nothing.",
                !MlsFetchLedger.Caller.STALL_REFRESH.chargesTheSharedCeiling);
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            if (c.isExempt()) {
                assertTrue(c + " is exempt but still charges the shared ceiling — it would refuse "
                        + "recovery without ever being refused itself, which is the starvation D3 "
                        + "rejected wearing the opposite hat", !c.chargesTheSharedCeiling);
            } else {
                assertTrue(c + " has a ration of " + c.ration + ". A non-exempt caller needs a "
                        + "positive allowance; zero is a self-inflicted outage rather than a tight "
                        + "bound.", c.ration >= 1);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * The source with every comment and string literal replaced by spaces, character for character.
     *
     * <p>Blanked rather than removed so that every offset — and therefore
     * {@link #enclosingMethod} — still refers to the same place in the real file. A scan that
     * reported the right defect at the wrong line would be worse than one that missed it.
     *
     * <p>The transport documents these funnels heavily: {@code serverStateCheck()} appears in a
     * comment ("Note serverStateCheck() would ALSO have to reach the server to answer") and inside a
     * log line's string. Both read as call sites to a naive scan, and the first run of this guard
     * reported three doors on exactly that.
     */
    private static String codeOnly(final String src) {
        return blank(src, /*alsoStringContents=*/ true);
    }

    /**
     * The source with comments blanked but string literals intact — for the one scan whose needle is
     * itself a literal, {@code pt("<primitive>")}.
     */
    private static String withoutComments(final String src) {
        return blank(src, /*alsoStringContents=*/ false);
    }

    private static String blank(final String src, final boolean alsoStringContents) {
        final char[] out = src.toCharArray();
        int i = 0;
        final int n = out.length;
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
                i++;                                  // the opening quote itself always stays
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

    /**
     * The brace-matched block of the {@code if} arm containing {@code at}, or the rest of the body
     * if {@code at} is not inside one.
     *
     * <p>Used instead of a fixed window forward from a marker. A distance acquires whatever is
     * inserted between the landmarks — it goes red when the arm grows and green when the property
     * moves into a neighbour — which is the same defect as a needle keyed on a spelling, wearing a
     * different set of clothes (axes 4 and 6).
     */
    private static String armContaining(final String body, final int at) {
        final int ifAt = body.lastIndexOf("if (", at);
        if (ifAt < 0) return body.substring(at);
        final int open = body.indexOf('{', at);
        if (open < 0) return body.substring(at);
        final int semi = body.indexOf(';', at);
        if (semi >= 0 && semi < open) return body.substring(at);   // braceless arm; no block to take
        final String block = bracedBlock(body, open);
        return block.isEmpty() ? body.substring(at) : block;
    }

    /** The brace-matched block starting at {@code open}, or "" if it does not close. */
    private static String bracedBlock(final String src, final int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    /** Every {@code .java} under the module's {@code src/}, wherever the runner's cwd happens to be. */
    private static List<File> javaSourcesUnderSrc() throws IOException {
        File root = null;
        for (final String c : new String[] {"src", "packages/apps/Messaging/src", "../src"}) {
            final File f = new File(c);
            if (f.isDirectory()) { root = f; break; }
        }
        if (root == null) {
            throw new IOException("src/ not found from " + new File(".").getAbsolutePath());
        }
        final List<File> out = new ArrayList<>();
        final java.util.Deque<File> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            final File[] kids = stack.pop().listFiles();
            if (kids == null) continue;
            for (final File k : kids) {
                if (k.isDirectory()) stack.push(k);
                else if (k.getName().endsWith(".java")) out.add(k);
            }
        }
        return out;
    }

    private static int indexOfFirst(final String body, final String... needles) {
        int best = -1;
        for (final String n : needles) {
            final int at = body.indexOf(n);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    private static String bodyOf(final String src, final String name, final String declFragment) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            // The DECLARATION only, never into the body: a method that merely CALLS the one you
            // asked for would otherwise answer as though it were it.
            if (!declFragment.isEmpty() && !src.substring(m.start(), open).contains(declFragment)) {
                continue;
            }
            final String body = bracedBlock(src, open);
            if (body.isEmpty() || isDelegateTo(body, name)) continue;
            return body;
        }
        return "";
    }

    private static String[] wrapperRowFor(final String method) {
        for (final String[] row : CHARGED_WRAPPERS) {
            if (row[0].equals(method)) return row;
        }
        return null;
    }

    /** The text from an opening paren up to the first top-level comma or the closing paren. */
    private static String argumentPrefix(final String src, final int afterOpenParen) {
        int depth = 1;
        final StringBuilder b = new StringBuilder();
        for (int i = afterOpenParen; i < src.length() && b.length() < 200; i++) {
            final char c = src.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (--depth == 0) break;
            } else if (c == ',' && depth == 1) break;
            b.append(c);
        }
        return b.toString();
    }

    private static boolean isDeclaration(final String src, final List<int[]> decls, final int at) {
        for (final int[] d : decls) {
            if (d[1] == at) return true;
            if (d[0] > at) break;
        }
        return false;
    }

    /** Start offset and name bounds of every class-level method declaration, in source order. */
    private static List<int[]> declarations(final String src) {
        final List<int[]> out = new ArrayList<>();
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) out.add(new int[] {m.start(), m.start(1), m.end(1)});
        return out;
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
     * The brace-matched body of the first method whose name matches EXACTLY.
     *
     * <p>Exact, never a substring: {@code lookGroupInfo} is a prefix of
     * {@code lookGroupInfoForGroup}, and a {@code contains()} match would answer with the wrong
     * wrapper's body and report the right one unguarded — the same false failure
     * {@code MlsPeerReJoinBudgetGuardTest} produced on itself before it was right.
     */
    private static String bodyOf(final String src, final String name) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            final String body = bracedBlock(src, open);
            if (body.isEmpty() || isDelegateTo(body, name)) continue;   // see isDelegateTo
            return body;
        }
        return "";
    }

    /**
     * Whether {@code body} is a DELEGATING overload — its only statement is a return of a call to
     * its own name.
     *
     * <p>{@link #bodyOf} matches by name and answers the FIRST declaration, so a delegate added
     * above the real one hands every check a one-line body and reports every property missing.
     *
     * <p>This is not hypothetical and it is not caught by review: a concurrent change added a
     * delegating {@code rebuildConversation} overload, and it turned two
     * assertions here red on correct code. They removed the overload rather than have me add a
     * declaration fragment — <b>so this guard was passing because of a choice someone else made in
     * another change</b>, which is exactly the kind of dependency that ages badly. The rule is
     * from {@code b18f048b}, and it is structural: a delegate is
     * recognisable by shape, not by a fragment of its parameter list.
     */
    private static boolean isDelegateTo(final String body, final String name) {
        final String inner = codeOnly(body).trim();
        if (!inner.startsWith("{") || !inner.endsWith("}")) return false;
        final String stmt = inner.substring(1, inner.length() - 1).trim();
        return stmt.startsWith("return " + name + "(") && stmt.indexOf(';') == stmt.length() - 1;
    }

    private static String readTransport() throws IOException {
        return read("src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java");
    }

    private static String readDebugReceiver() throws IOException {
        return read("src/com/android/messaging/rcs/RcsDebugSendReceiver.java");
    }

    /** Same locator as {@code MlsPeerReJoinBudgetGuardTest}: works from the module dir or the root. */
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

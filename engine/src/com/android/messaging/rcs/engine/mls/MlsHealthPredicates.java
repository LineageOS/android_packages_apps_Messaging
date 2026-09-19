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
 * The four decoded health-status membership tests — §9.7b. Rework item {@code 11.1a}.
 *
 * <h2>Why this class exists at all, stated as the correction it is</h2>
 *
 * <p><b>{@code end_mls} exists in two independent places, and almost every operational guard in
 * Google Messages reads the SECOND one.</b>
 *
 * <table>
 *   <tr><th></th><th>representation</th><th>where</th><th>who reads it</th></tr>
 *   <tr><td><b>A</b></td><td>the GroupContext extension {@code 0xF002}</td>
 *       <td>inside {@code GroupContext.extensions} — cryptographically bound into the group state,
 *           travelling with every commit and every GroupInfo</td>
 *       <td>commit building; self-heal's reconciliation against a <i>fetched server</i> GroupInfo;
 *           the revive precondition; the inbound proposal scan</td></tr>
 *   <tr><td><b>B</b></td><td>the persisted {@code MlsHealthStatus}</td>
 *       <td>{@link MlsConversationRecord#healthStatus}</td>
 *       <td><b>every operational guard</b></td></tr>
 * </table>
 *
 * <p>Our implementation had exactly the shape §9.7b forbids in as many words: {@code hasEndMls()}
 * <i>was</i> {@code group.context().extensions().get(0xF002).is_some()}, and it was THE operational
 * guard at six call sites. §9.7b's implementer's rule is literal — <i>"Do NOT implement
 * {@code hasEndMls()} as 'does the GroupContext carry {@code 0xF002}'"</i> — and the two answers
 * differ in <b>five</b> states:
 *
 * <table>
 *   <tr><th>status</th><th>{@code 0xF002} in the context?</th><th>{@code hasEndMls()}</th></tr>
 *   <tr><td>{@code EndMlsRequested(8)}</td><td>no — not committed yet</td><td><b>false</b></td></tr>
 *   <tr><td>{@code OngoingEndMls(9)}</td><td>no — commit in flight</td><td><b>false</b></td></tr>
 *   <tr><td>{@code DoneEndMls(10)}</td><td>yes</td><td>true</td></tr>
 *   <tr><td>{@code OngoingReviveMls(11)}</td><td><b>yes</b>, until the revive commit lands</td><td>true</td></tr>
 *   <tr><td>{@code OngoingEraAdvancementForRevive(12)}</td><td><b>yes</b> in the old era; the new
 *       era's context omits it</td><td>true</td></tr>
 *   <tr><td>{@code CannotHealDuringEndMls(15)}</td><td><b>maybe</b> — reachable when end-mls is
 *       <i>unstable</i></td><td>true</td></tr>
 *   <tr><td>{@code OngoingPhoenixMode(16)}</td><td><b>not yet</b> — Phoenix <i>installs</i> it in the
 *       new era</td><td>true</td></tr>
 *   <tr><td>{@code PhoenixModeRequested(17)}</td><td>no</td>
 *       <td><b>false</b> — but {@link #isDowngraded} is true</td></tr>
 * </table>
 *
 * <p>Read {@link #hasEndMls}'s set carefully, because both ends of it are counter-intuitive: it
 * <b>excludes</b> 8 and 9, so a downgrade that has been requested or whose commit is in flight
 * reports {@code false}; and it <b>includes</b> the two revive states, so a group actively coming
 * back reports {@code true} right up until it lands in {@code Healthy}.
 *
 * <p><b>Why keying off the extension alone is not merely different but strictly weaker.</b> §9.7m
 * lists four independent enforcements of INVARIANT ED-1, and the fourth is the status guards: <i>"even
 * if a context somehow lost {@code 0xF002}, the health status would still suppress the machinery that
 * could re-encrypt the group."</i> Google Messages' real defence is STATE-based and the extension is the
 * durable, cryptographically-bound <i>record</i> of that state. Keying everything off the extension
 * loses defence 4 entirely — which was our posture until this class landed.
 *
 * <p><b>Where the masks come from.</b> All four are read off Google Messages' own engine (§9.7b):
 * each predicate loads the health status and tests it against a compile-time bitmask, not a chain
 * of comparisons. The native numbering is pinned to the wire numbering by the FFI debug
 * setter's own refusal string, which names {@code PhoenixModeRequested} for 17 and "not healthy" for
 * anything other than 13.
 *
 * <p>Pure, static, no state: these are questions about a number.
 */
public final class MlsHealthPredicates {

    private MlsHealthPredicates() {}

    /**
     * {@code has_end_mls} — mask {@code 0x00019C00}, states {10,11,12,15,16}.
     *
     * <p>Google Messages' own decode range-tests {@code status < 17} first and then shifts the mask,
     * so 17 is excluded by the range test rather than by the
     * mask. We reproduce the effect, not the instruction sequence.
     *
     * <p>This is the predicate behind <i>"MLS is already done. Returning no-op."</i> (downgrade entry
     * guard 1, §9.7c) and behind the {@code MISMATCHED_RCS_GROUP_STATE} absorber (§9.7h path C).
     * <b>17 call sites in Google Messages</b> — a pervasive engine predicate, not a niche one.
     */
    public static boolean hasEndMls(final int status) {
        return status == MlsHealthStates.DONEENDMLS                       // 10
                || status == MlsHealthStates.ONGOINGREVIVEMLS             // 11
                || status == MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE  // 12
                || status == MlsHealthStates.CANNOTHEALDURINGENDMLS       // 15
                || status == MlsHealthStates.ONGOINGPHOENIXMODE;          // 16
    }

    /**
     * {@code is_downgraded} — states {8,9,10,11,12,15,16,17}.
     *
     * <p>Google Messages computes it as {@code (status - 8) & ~9 == 0} (giving {8,9,16,17}) unioned with
     * {@code has_end_mls}'s {@code 0x19C00}. <b>This is the same function that produces §10.8's
     * inbound-buffering exclusion set — not a coincidence.</b>
     *
     * <p>Gates the three hard stops of §9.7f: no new pending operation, no key refresh, no self-heal.
     */
    public static boolean isDowngraded(final int status) {
        return hasEndMls(status)
                || status == MlsHealthStates.ENDMLSREQUESTED              // 8
                || status == MlsHealthStates.ONGOINGENDMLS                // 9
                || status == MlsHealthStates.PHOENIXMODEREQUESTED;        // 17
    }

    /**
     * {@code is_phoenix_ongoing} — {@code (status & ~1) == 0x10}, i.e. {16,17}.
     *
     * <p><b>Covers REQUESTED as well as ONGOING:</b> a Phoenix that has been asked for but not started
     * still blocks a new downgrade (entry guard 3). Google Messages has <b>exactly one</b> call site for this
     * predicate, and it is that guard.
     *
     * <p><b>That count is a CALL-GRAPH enumeration, and it is only as complete as the search</b>
     *. It is a structural claim, so structure can settle it — but the same
     * instrument has already failed us once: an earlier key-package census was direct-call-only and
     * could not see dispatch through a trait object. FALSIFIER: a second reader of the
     * {@code {16,17}} predicate reached indirectly — through a function pointer, a generic
     * monomorphisation, or a dispatch table — rather than by a direct call.
     * Note what does NOT follow from the count either way: "one call site" bounds where the
     * predicate is READ, not how often the guard RUNS, and nothing here needs the second.
     */
    public static boolean isPhoenixOngoing(final int status) {
        return status == MlsHealthStates.ONGOINGPHOENIXMODE               // 16
                || status == MlsHealthStates.PHOENIXMODEREQUESTED;        // 17
    }

    /**
     * {@code is_healing} — {@code status < 13 && bit(status) in 0x10D6},
     * i.e. {1,2,4,6,7,12}.
     *
     * <p><b>State 12 is deliberately in BOTH {@link #isDowngraded} and this set.</b> An era advance
     * for revival is simultaneously a downgraded group and an in-progress heal, and a decoder that
     * "tidies" the overlap away breaks one of the two.
     */
    public static boolean isHealing(final int status) {
        return status == MlsHealthStates.EPOCHADVANCEMENTREQUESTED        // 1
                || status == MlsHealthStates.ONGOINGEPOCHADVANCEMENT      // 2
                || status == MlsHealthStates.ONGOINGERAADVANCEMENT        // 4
                || status == MlsHealthStates.SELFHEALFAILED               // 6
                || status == MlsHealthStates.ERAADVANCEMENTREQUESTED      // 7
                || status == MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE;  // 12
    }

    /**
     * §10.8's <b>G1</b> pre-decrypt busy-group lock — mask {@code 0x35096}, states
     * {1,2,4,7,12,14,16,17}.
     *
     * <p>Read as one sentence: <i>buffer inbound while the group is mid-transition to a new epoch or a
     * new era (including revive and phoenix), or while it is still initialising; do not buffer while
     * healthy, while self-heal has already failed, or anywhere in the end-MLS family.</i>
     *
     * <p><b>This is NOT {@code !isDowngraded}, and the difference is the whole point.</b> 12, 16 and
     * 17 are all downgraded AND all buffer; 8, 9, 10, 11 and 15 are downgraded and do NOT. Deriving
     * one from the other — in either direction — gets five states wrong. §10.8's G3 is the predicate
     * that genuinely uses {@code is_downgraded} as an exclusion; G1 uses this separate mask.
     */
    public static boolean buffersInbound(final int status) {
        return status == MlsHealthStates.EPOCHADVANCEMENTREQUESTED        // 1
                || status == MlsHealthStates.ONGOINGEPOCHADVANCEMENT      // 2
                || status == MlsHealthStates.ONGOINGERAADVANCEMENT        // 4
                || status == MlsHealthStates.ERAADVANCEMENTREQUESTED      // 7
                || status == MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE  // 12
                || status == MlsHealthStates.INITIALIZING                 // 14
                || status == MlsHealthStates.ONGOINGPHOENIXMODE           // 16
                || status == MlsHealthStates.PHOENIXMODEREQUESTED;        // 17
    }

    /**
     * The §9.7i revive precondition, in the order the engine evaluates it (invariant 104).
     *
     * <p>{@code (hasExtension(0xF002) || status == CannotHealDuringEndMls(15))} <b>and then</b>
     * {@code (isDowngraded(status) || status == OngoingReviveMls(11))}.
     *
     * <p>The second disjunct is statically redundant — 11 is already inside {@link #isDowngraded}'s
     * set — but it is in the shipped binary and is a checkable ordering fact, so it is spelled out
     * rather than simplified away.
     *
     * <p><b>This is the one place the EXTENSION is legitimately consulted for a decision</b>, which is
     * why it is a parameter rather than something this class could compute: revive is the only
     * deliberate removal of {@code end_mls}, and it must be able to run on a group whose status and
     * whose context disagree — that disagreement is precisely the condition it repairs.
     */
    public static boolean canRevive(final int status, final boolean extensionPresent) {
        final boolean clause1 = extensionPresent || status == MlsHealthStates.CANNOTHEALDURINGENDMLS;
        if (!clause1) return false;
        return isDowngraded(status) || status == MlsHealthStates.ONGOINGREVIVEMLS;
    }

    /**
     * Whether failing {@link #canRevive}'s FIRST clause should additionally emit Google Messages'
     * self-declared "bug in the engine" diagnostic — §9.7i, invariant 104.
     *
     * <p>Failing clause 1 logs <i>"MLS is already active, no need to revive"</i> and then, <b>on the
     * same straight-line path</b>, RE-TESTS {@code is_downgraded}; if that is nevertheless true,
     * Google Messages additionally logs the 207-byte diagnostic. That is the literal meaning of the message:
     * <i>unhealthy, but no extension and no pending op</i>.
     *
     * <p>Failing the SECOND clause takes a separate path with <b>no log line at all</b>. An earlier
     * draft of the design doc placed the diagnostic on the second-clause failure; it is on the first.
     */
    public static boolean reviveRefusalIsZinniaBug(final int status, final boolean extensionPresent) {
        final boolean clause1 = extensionPresent || status == MlsHealthStates.CANNOTHEALDURINGENDMLS;
        return !clause1 && isDowngraded(status);
    }
}

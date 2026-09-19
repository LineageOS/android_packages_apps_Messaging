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
 * Every MLS behavioural knob, resolved ONCE and carried per instance.
 *
 * <p>Rework item 14.4. Google Messages' flags are process-global statics; ours were worse — device-global
 * system properties re-read at the call site, inside the very class the state machine is moving
 * into. Three consequences, and the third is the one that blocks the rework:
 *
 * <ol>
 *   <li>A knob could change <em>mid-operation</em>. {@code eraAdvanceMode} was read on every
 *       advance, so flipping it between building a commit and sending it produced a combination no
 *       code path was written for.</li>
 *   <li>Two tests cannot run with different flags in one JVM, because the flag state is the
 *       device's, not the object's.</li>
 *   <li>{@code android.os.SystemProperties} is an Android class, so <em>any</em> behaviour gated on
 *       a knob is unreachable from a host test by construction. That is the concrete reason the
 *       state machine cannot land in {@code MlsProviderTransport} as it stands.</li>
 * </ol>
 *
 * <p>So: read through a {@link Source} the host chooses — {@code SystemProperties} on device, a
 * literal in tests — exactly once, at construction. The fields are final and public; there is
 * nothing to invalidate and no read path to get wrong.
 *
 * <p><b>Changing a knob now requires a restart of the owning object.</b> That is a deliberate
 * narrowing: `setprop` then re-run the operation used to be enough. It is worth stating alongside
 * each key, and in practice every one of these is set before a test
 * run rather than during one.
 */
public final class MlsConfig {

    /** Where the values come from. Deliberately three primitives — no Android, no Context. */
    public interface Source {
        boolean getBoolean(String key, boolean def);
        int getInt(String key, int def);
        long getLong(String key, long def);
    }

    // -- keys ------------------------------------------------------------------------------------

    public static final String KEY_ERA_YIELD_LOOKS = "debug.rcs.mls_era_yield_looks";
    public static final String KEY_DUMP_KP = "debug.rcs.mls_dump_kp";
    public static final String KEY_DUMP_AAD = "debug.rcs.mls_dump_aad";
    public static final String KEY_ERA_ADVANCE_MODE = "debug.rcs.mls_era_advance_mode";
    public static final String KEY_SAN_IDENTITY_CHECK = "debug.rcs.mls_san_identity_check";
    /**
     * Publish a GroupInfo carrying {@code external_pub} on our own commits (default OFF).
     *
     * <p>{@code external_pub} is COMMITTER-produced — the committer builds the GroupInfo and the
     * delivery service only stores and serves it. Every commit we make publishes
     * {@code group_info_message(false)}, which omits it, so the GroupInfo the server holds for OUR
     * groups cannot support a resync EXTERNAL COMMIT from anyone: the joiner has no key to derive
     * the init secret from and the commit is unconstructible ({@code MissingExternalPubExtension},
     * measured 2026-08-16).
     *
     * <p>Default OFF because the earlier attempt at this FAILED in a specific, recorded way: an
     * external_pub-carrying GroupInfo made the server demand the ratchet_tree
     * ({@code field1003{7}} tree-not-found). The variant behind this flag pairs it the way the CREATE
     * path and the resync already do — GroupInfo tree-LESS, post-commit tree shipped as a separate
     * field — so it is a different experiment from the one that failed, not a retry of it. Prove it
     * on a disposable group before considering it anywhere near a default.
     */
    public static final String KEY_PUBLISH_EXTERNAL_PUB = "debug.rcs.mls_publish_external_pub";

    /**
     * {@code debug.rcs.mls_plaintext_reconcile_imdn} — emit the PLAINTEXT reconciliation delivery
     * IMDN when an MLS message arrives for a conversation we hold no group for. <b>DEFAULT OFF, and
     * the default is the important part.</b>
     *
     * <p>It was introduced on the reasoning that a group-less peer cannot sign
     * an MLS FTD — true — so a plaintext receipt is the only channel left, and that Google Messages would
     * "clear its stale encryption_protocol belief and re-Welcome on its next thread-open".
     *
     * <p><b>Captured on both sides 2026-08-20: the first half happens and the
     * second does not.</b> Google Messages clears the belief by DEMOTING the destination one rung, and never
     * re-establishes. One receipt took the conversation MLS -> Scytale; the next took it Scytale ->
     * no encryption at all, with a tombstone. The whole ladder collapsed in 3.5 minutes, and a third
     * send would have gone out in the clear. There was no re-Welcome, no create_group, no backoff
     * line — Google Messages never attempted MLS again, because the destination record simply said SCYTALE.
     *
     * <p>So the receipt does not reconcile anything; it trades an undelivered message for a silent,
     * irreversible downgrade of the entire conversation. Not sending it leaves Google Messages showing the
     * message as undelivered, which is <em>true</em> — we could not decrypt it — and leaves the
     * conversation on MLS so that re-establishment stays possible from our side.
     *
     * <p>Kept as a flag rather than deleted because the qk9x trap it was written for is real: with it
     * off, a peer whose group we have lost has no inbound-driven recovery at all, and recovery must
     * be driven outbound by us. Turn it on only to reproduce the downgrade.
     */
    public static final String KEY_PLAINTEXT_RECONCILE_IMDN =
            "debug.rcs.mls_plaintext_reconcile_imdn";
    /**
     * Act on the repair-exhausted terminal by DOWNGRADING out of MLS, rather than only recording the
     * intent. Google Messages' {@code bugle.downgrade_mls_if_failure_during_era_advancement}.
     *
     * <p><b>Default ON since 2026-08-16.</b> It defaulted OFF for as long as Google Messages' predicate was
     * unread, because dropping a conversation out of encryption on an INFERRED condition is
     * invisible to the user until it matters. The predicate is no longer inferred — Google
     * Messages carries a per-server-reason remedy switch, and the row for this reason is
     *
     * <pre>ERA_ADVANCEMENT_QUOTA_REACHED -> b(allowRetry=true, MLS_HEALTH_STATUS_END_MLS_REQUESTED)</pre>
     *
     * i.e. a group that has burned its era-advancement budget should, by Google Messages' own policy, stop
     * being an MLS group. Matching that is now the evidenced behaviour and diverging from it is what
     * would need justifying — a conversation that cannot repair and cannot advance otherwise stalls
     * forever, delivering nothing, which is worse for the user than an honest downgrade.
     *
     * <p>Set {@code 0} to restore report-only, in which the terminal is recorded and the user keeps
     * the stall choice. Note the downgrade is still not the FIRST resort: the ladder tries self-heal,
     * then the epoch advance, then the era advance, and only lands here when the era quota itself is
     * spent. Once the resync external commit exists it should be attempted
     * BEFORE this terminal — it carries a separate 50/group/day budget and can repair a group the
     * era quota has stranded.
     */
    public static final String KEY_DOWNGRADE_ON_REPAIR_EXHAUSTED =
            "debug.rcs.mls_downgrade_on_repair_exhausted";
    /**
     * Run the §8.7 maintenance pass when a conversation is OPENED. Default ON.
     *
     * <p>Off is an escape hatch, not a tuning knob: the pass performs network I/O (a server group-
     * state fetch) on the open path, and it is the trigger a capture or an A/B may need to hold
     * still. Turning it off disables the PROACTIVE refresh only — recovery is a different path and
     * is structurally exempt, so nothing here can stop a conversation being repaired.
     */
    public static final String KEY_MAINTENANCE_ON_OPEN = "debug.rcs.mls_maintenance_on_open";
    /**
     * Run the §8.7 maintenance pass over the groups NOBODY OPENED — the
     * {@code RefreshMlsGroups} continuation sweep. Default ON.
     *
     * <p>Sibling of {@link #KEY_MAINTENANCE_ON_OPEN} and off for the same reason: the sweep performs
     * network I/O (one server group-state fetch per group it walks), so it is background traffic a
     * capture or an A/B may need to hold still. It disables the PROACTIVE refresh only — recovery is
     * a structurally separate path and is exempt, so nothing here can stop a group being repaired.
     *
     * <p>This is NOT a timer knob and there is no interval to tune: §8.7 closes with <i>Build no
     * timer</i>, and the sweep is armed by state changes (a session bring-up, an identity change)
     * and disarms when it has walked the set once.
     */
    public static final String KEY_GROUP_SWEEP = "debug.rcs.mls_group_sweep";
    public static final String KEY_KP_MIN_DAYS = "debug.rcs.mls_kp_min_remaining_days";
    /**
     * Refuse a membership Commit LOCALLY when a member's credential is inside RCC.16's remaining-
     * lifetime floor. <b>ON by default</b> ({@code 0} to disable).
     *
     * <p>The server refuses it anyway ("Time-related validation error … with MSISDN …", A.4.3.1
     * §1(a)), so this changes nothing about what is possible; it changes what the failure SAYS. The
     * measured cost of not having it was a {@code PERMISSION_DENIED}
     * whose remedy re-minted OUR identity because nothing read the MSISDN in the refusal.
     *
     * <p>It gates ADD and REMOVE only. A Self-Update must never be gated by it: A.4.3.2 §3's
     * carve-out makes that the one Commit a stale member is allowed to send, and it is the repair
     * a member inside the floor depends on.
     *
     * <p>Turn it OFF if a server is ever observed ACCEPTING a membership Commit on a roster inside
     * the floor — that would make this a false refusal rather than an early one.
     */
    public static final String KEY_FLOOR_PRECHECK = "debug.rcs.mls_floor_precheck";
    /**
     * REFUSE a claimed peer KeyPackage whose leaf CERTIFICATE is inside the remaining-lifetime
     * floor. <b>OFF by default</b> ({@code 1} to enable); the measurement is
     * always logged either way.
     *
     * <h3>Why the measurement is not in question and the refusal is</h3>
     *
     * <p>{@link #KEY_KP_MIN_DAYS}'s floor was applied to the LeafNode's §7.2 {@code Lifetime}, which
     * on the Tachyon profile the engine itself mints as {@code cert.notBefore + 365d}. It therefore
     * cannot fall inside a 30-day floor until the certificate is ~335 days old, and a Tachyon
     * certificate lives ~75 — so the gate has never refused anything and structurally never can,
     * while logging a PASS that reads as a statement about the certificate. Measuring the
     * certificate too is uncontroversial and unconditional.
     *
     * <p>REFUSING on it is not, and this sysprop is why it is not simply switched on:
     *
     * <ul>
     *   <li><b>For it.</b> The server measures the CERTIFICATE. It validates every credential in the
     *       post-Commit roster at {@code now + 30d} (A.4.3.1 §1(a), Invariant 17 — "all certificates
     *       in the group") and refuses quoting the certificate's own {@code Validity}. An Add whose
     *       new member is inside the floor is therefore predicted to be refused — and the roster
     *       pre-check ({@link #KEY_FLOOR_PRECHECK}) cannot see it, because the member being added is
     *       not in the roster yet. This is the ONLY site where that certificate can be measured. A
     *       refusal here also saves a one-time KeyPackage that a doomed Commit would consume.</li>
     *   <li><b>Against it — WEAKENED 2026-09-11, not refuted.</b> Kept rather than deleted, because
     *       it was the reason this flag was ever off. The argument was: a Tachyon certificate is ~75
     *       days, so a peer is inside the floor for the last ~40% of its life, and refusing makes it
     *       unreachable for weeks. The population is much SMALLER than that, and no in-floor package
     *       has ever been observed here — but it is not empty, and the reasoning below does not show
     *       that it is (see the correction after this list). We only claim from a peer advertising
     *       {@code +g.gsma.rcs.mls.mls-kds}, and no conformant peer advertises inside the floor:
     *       Google Messages stops publishing at {@code earliestExpiry - 30d} (Mendel
     *       {@code enable_buffer_before_stopping_caps_publishing_due_to_expiry}; plus a 31-day max
     *       age and a 10-day re-mint cadence), and we stop at 30 days of certificate AGE, which
     *       on the measured 74.96-day issued window is ~45 days remaining — tighter than Google
     *       Messages, though only by arithmetic on that window rather than by stating the
     *       rule.</li>
     * </ul>
     *
     * <p><b>SO WHY IS IT STILL OFF? Because no in-floor package has ever been OBSERVED here — an
     * empirical reason, not a structural one.</b> Every claim line we have (~40, 2026-09-08..10,
     * five peers) converts to a certificate of 42-74 days remaining, and not one in-floor KeyPackage
     * has reached this site. On that evidence, flipping it today would refuse nothing.
     *
     * <p><b>⚠ THE STRUCTURAL VERSION OF THAT CLAIM IS WRONG, and it is the stronger-sounding one.</b>
     * "No conformant peer can present an in-floor certificate" does not follow from the advertise
     * gate, because the two clauses are about DIFFERENT ARTEFACTS:
     *
     * <pre>
     *   advertises mls-kds  ⇒  the peer's CURRENT certificate is above the floor      ✓
     *                       ⇒  the CLAIMED KEYPACKAGE's certificate is above the floor ✗
     * </pre>
     *
     * A claimed package carries whatever certificate its POOL was minted under, and those were
     * measured diverging on one device, 2026-09-10: the provider's
     * certificate had ~74 days while the pool it published served a leaf with <b>41.7</b>. The app's
     * identity refresh is a WEEKLY cadence ({@link #KEY_IDENTITY_REFRESH_DAYS}), so the divergence
     * persists rather than closing, and {@code --ez publishkp true} republishes the stale leaf
     * without refreshing. So a peer that is entirely conformant in its ADVERTISEMENT can still serve
     * an in-floor package — which is a better-evidenced route to this arm than the two exotic ones
     * named below, and a P1 bug on our own fleet rather than a hypothetical.
     *
     * <p>That same measurement puts a sample BELOW the minimum quoted above: 41.7 days, read by
     * DER off the wire rather than by the conversion. A stated minimum that sits below itself means
     * the ~40-line sweep has a known omission, so <b>42 is not a floor to quote</b> — and the margin
     * to the 30-day line is ~11 days, not the ~15 that "we stop at ~45 days remaining" implies.
     *
     * <p>That conversion is worth keeping, because it makes every older one-clock log line
     * readable after the fact: on the Tachyon profile {@code Lifetime = cert.notBefore + 365d} and
     * the issued certificate window is ~75 days, so {@code cert_remaining ~= leafLifetime_remaining
     * - 290d}. Verified against all four two-clock lines that print both (363->73, 364->74, 358->68,
     * 357->67) and independently against a second device's DER-parsed leaf.
     *
     * <p><b>The arm IS reachable, which is exactly what distinguishes it from the Lifetime gate this
     * flag was originally about.</b> A stale pool (above — the likeliest route), a peer whose
     * own advertise gate is broken, or a capability record we cached
     * before the peer stopped advertising, can still present an in-floor certificate. On that traffic
     * the refusal is more likely right than wrong: all three device samples fit exactly one rule,
     * "the post-Commit roster is validated at now+30d", and the member being ADDED is in that roster.
     * But "more likely right" is not an observation, so the default does not move on it.
     *
     * <p><b>Still unobserved, and the only thing left:</b> the server's verdict on an Add naming an
     * in-floor ADDED member. All three samples are Commits over an EXISTING
     * roster — the Remove was of {@code +15715550109} while the
     * server named a SURVIVING {@code +15715550104}, and the Add claimed {@code +15715550103} at
     * ~42 days, above the floor. The log line collects it on every claim.
     */
    public static final String KEY_KP_CERT_FLOOR = "debug.rcs.mls_kp_cert_floor";
    /**
     * Let the maintenance pass REBUILD a group that RCC.16's floor has wedged, by era-advancing it
     * around freshly claimed KeyPackages. <b>OFF by default</b> ({@code 1} to
     * enable). The per-conversation debug lever works whether or not this is set.
     *
     * <h3>The mechanism is not in question. Doing it unattended is.</h3>
     *
     * <p>What it repairs is real and nothing else repairs it: once a SECOND member's credential is
     * inside the floor, Invariant 17 refuses every Commit on that group including each member's own
     * §9.5.3 Self-Update (observed on a device), and the group is permanently
     * uncommittable. An era advance re-creates the group from the members' PUBLISHED pools, which
     * are a different artefact with a different clock, and {@link MlsFloorRebuild} refuses to start
     * one it cannot finish.
     *
     * <p><b>The default is OFF for a reason that is specific rather than cautious.</b> A rebuild
     * re-Welcomes every member — including third parties who did not ask for it, and who see it. On
     * the bench today, switching this on fleet-wide would re-Welcome the owner's personal iPhone in
     * two conversations ({@code p:+12025550101} and {@code g:5B8905CD}), and wedging a real third
     * party's phone by repeated era advances is the P0 this fleet already had (
     * ~17 advances on one group, 2026-08-04..08-25). The right granularity for "re-Welcome everyone
     * in THIS conversation" is a decision per conversation, which is what the lever is.
     *
     * <p>Every advance it can issue still goes through {@code MlsPeerGuard.allowEraAdvance} (G2,
     * 2/hour and 5/day) and INVARIANT ED-1, so this is not the only thing standing between the
     * fleet and an era storm — it is the thing that decides whether anyone is asked.
     *
     * <p>What would justify flipping the default: a fleet whose members all re-mint reliably
     *, so that a rebuild's pre-flight passes on the first attempt rather than
     * refusing and re-arming — plus a run of the lever across the wedged set with no surprises.
     */
    public static final String KEY_FLOOR_REBUILD = "debug.rcs.mls_floor_rebuild";
    public static final String KEY_KP_COUNT = "debug.rcs.mls_kp_count";
    public static final String KEY_SELF_HEAL_RETRY_LIMIT = "debug.rcs.mls_self_heal_retries";
    public static final String KEY_SELF_HEAL_WINDOW_S = "debug.rcs.mls_self_heal_window_s";
    public static final String KEY_REUPGRADE_BASE_S = "debug.rcs.mls_reupgrade_base_s";
    public static final String KEY_REUPGRADE_MAX_SHIFT = "debug.rcs.mls_reupgrade_max_shift";
    public static final String KEY_REUPGRADE_STABILITY_S = "debug.rcs.mls_reupgrade_stability_s";
    /**
     * The {@code group_metadata_keys_requested} GroupInfo extension type — <b>0xF007, and armed by
     * default since 2026-08-06</b>.
     *
     * <p>It was unset for months, and the reason it was unset is worth keeping: the value was OUR
     * GUESS. The extension's NAME and its whole behavioural surface were recoverable, but a
     * type number is an integer constant in code rather than a string literal, so no amount of
     * further string extraction produces it — that narrowed it to 0xF007–0xF00F without naming
     * one. A guessed value fails silently in BOTH directions: too low and we never
     * see the gate (and never refresh), too high and we read some other extension AS the gate.
     *
     * <p><b>GSMA RCC.16 v4.0 §7.11.10.1 publishes it.</b> The number is now a spec fact, so
     * withholding it would be refusing evidence rather than refusing a guess. The answer was in a
     * document that had not been published yet — not in an analysis anyone could have run harder.
     *
     * <p>The knob remains, so a capture that contradicts v4.0 can win without a build. Setting it to
     * {@code 0} restores the un-evaluable behaviour: the maintenance add-arm then reports itself
     * un-evaluable rather than letting "no refresh needed" stand as a measurement.
     */
    public static final String KEY_METADATA_KEYS_EXT = "debug.rcs.mls_metadata_keys_ext";

    /**
     * The RCC.16 spec revision this transport speaks: {@code 30} = v3.0 (default), {@code 40} = v4.0.
     *
     * <p>An OVERRIDE, not the mechanism. The version is announced in code by the transport that
     * knows which deployment it is talking to (see {@code OpenMlsEngine.Rcc16Version}); this key
     * exists so the lab can be flipped to v4.0 on a device without a rebuild, and so a v4.0 shape
     * can be forced on for a one-off interop test. An unrecognised value is refused by the engine
     * and v3.0 kept — a spec revision must never be selected by a typo.
     *
     * <p>Setting this to 40 against <b>Tachyon</b> will emit shapes no Google server has ever been
     * seen to accept, and will break {@code end_mls} in particular. That is what the knob is for;
     * it is not a thing to leave set.
     */
    public static final String KEY_RCC16_VERSION = "debug.rcs.mls_rcc16_version";

    /**
     * {@code debug.rcs.mls_ftd_max_attempts} — raise §10.3's five-attempt FTD chain cap.
     *
     * <p>Not new: it has gated {@code flushFtdReports} since the reason-token sweep
     * (13 FTDs on one real message). What changed later is WHERE it is read.
     * It was an {@code android.os.SystemProperties.getInt} inside the flush, which is the one shape
     * that makes a method unreachable from a host test by construction — the concrete reason this
     * class exists. It is now resolved once, here, against {@link MlsFtdEscalation#MAX_FTD_ATTEMPTS}.
     *
     * <p><b>That narrows it, and the narrowing is the documented trade:</b> a {@code setprop} now
     * takes effect at the next process start rather than at the next flush. See this class's
     * javadoc — every knob here works that way.
     */
    public static final String KEY_FTD_MAX_ATTEMPTS = "debug.rcs.mls_ftd_max_attempts";

    /**
     * {@code debug.rcs.mls_identity_refresh_days} — how often the enrolment identity is re-fetched
     * from the provider so a certificate refresh is picked up. Default 7 days.
     *
     * <p>It is a knob rather than a bare constant for one
     * concrete reason: verifying the refresh otherwise costs a week of wall clock. There is no
     * policy sibling to sit beside — this is a lone cadence over a {@code SharedPreferences} stamp,
     * which is exactly the case {@code MlsConfig} exists for.
     */
    public static final String KEY_IDENTITY_REFRESH_DAYS = "debug.rcs.mls_identity_refresh_days";

    // -- defaults --------------------------------------------------------------------------------

    /**
     * How many times we may look and find the group unmoved before advancing anyway. 0 = yield
     * forever (rework 5.3).
     *
     * <p>Replaced {@code DEF_ERA_YIELD_MS = 120_000L}. The unit is LOOKS, not milliseconds, and the
     * change is the point of item 5.3: "has the peer advanced?" is a question about group state, so
     * the success test is the group's moment moving. A bound is still needed — the moment says
     * whether the peer acted, never how long we waited — but a count only advances when we actually
     * look, so a device asleep for a week wakes having burned none of it. The wall-clock version
     * woke with its yield already expired and advanced an era nobody needed.
     */
    public static final int DEF_ERA_YIELD_LOOKS = 3;
    /** Legacy era-advance shape: rebuild from KeyPackages. The other two are §9.2-illegal. */
    public static final int DEF_ERA_ADVANCE_MODE = 0;
    /**
     * RCC.16 A.4.1.2 / A.4.2.2: refuse to consume a KeyPackage with less than this left on its
     * certificate.
     *
     * <p>Not tidiness. A member added on a nearly-expired leaf takes the whole group down when that
     * leaf lapses, and MLS gives the other members no way to notice in advance — the group simply
     * stops validating.
     */
    public static final long DEF_KP_MIN_REMAINING_DAYS = 30L;
    /**
     * Google Messages' TOTAL pool size — claimable plus last-resort.
     *
     * <p>Total, not claimable: we mint the claimable pool and the last-resort package with separate
     * calls and once published {@code 11 + 1 = 12}, one claimable more than Google Messages. We mint
     * {@code count - 1} claimable.
     */
    public static final int DEF_KP_POOL_TOTAL = 11;
    /** One claimable + one last-resort is the smallest pool that is not degenerate. */
    public static final int MIN_KP_POOL_TOTAL = 2;
    /**
     * Self-heal attempts before the budget is exhausted and the ladder ESCALATES.
     *
     * <p>A knob rather than a constant because Google Messages' is one too — its attempt count is compared
     * against a runtime config word, not a baked number. The value is ours; only the shape is
     * Google Messages'.
     */
    public static final int DEF_SELF_HEAL_RETRY_LIMIT = 5;
    /**
     * The budget's TIME arm: 86400 seconds, which is Google Messages' constant.
     *
     * <p>The unit is INFERRED (§22.1-51) — the number 86400 is decoded, "seconds" is the reading that
     * makes it a day. Recorded as inference rather than asserted, because if it turns out to be
     * milliseconds the behaviour changes by a factor of a thousand and this comment is where someone
     * will look.
     */
    public static final long DEF_SELF_HEAL_WINDOW_S = 86_400L;
    /**
     * {@code group_metadata_keys_requested} = <b>0xF007</b>, per RCC.16 v4.0 §7.11.10.1.
     *
     * <p>{@code 0} still means UNKNOWN and is still handled by every consumer — it is not a valid
     * MLS extension type, so it cannot collide with a real answer — but it is no longer the default.
     * See {@link #KEY_METADATA_KEYS_EXT} for why it was, and what changed.
     *
     * <p><b>Arming the number does not arm its FRAMING.</b> v4.0 says {@code opaque<V>} (varint-
     * framed); Google Messages bare-frames a closed list of three extensions that includes
     * this one. We have no capture, so the engine resolves neither: it decodes both forms (they
     * differ in length — 29 bytes bare, 30 framed) and REFUSES to encode. Nothing produces this
     * extension, so refusing costs nothing.
     */
    public static final int DEF_METADATA_KEYS_EXT = 0xF007;

    /**
     * v3.0 — the only RCC.16 revision with wire evidence behind it, so the only safe default.
     *
     * <p>Zero would have been wrong here in a way {@link #DEF_METADATA_KEYS_EXT} is not: an unknown
     * extension type can be reported un-evaluable and skipped, but there is no "unknown" spec
     * version to fall back to — every encode has to emit SOMETHING, so the default must be the
     * revision we have proven rather than a sentinel.
     */
    public static final int DEF_RCC16_VERSION = 30;

    /**
     * A week. Long enough that it is not traffic, short enough that a certificate refreshed on the
     * provider side is picked up before anything expires.
     */
    public static final long DEF_IDENTITY_REFRESH_DAYS = 7L;

    // -- resolved values -------------------------------------------------------------------------

    public final int eraYieldLooks;
    public final boolean dumpKeyPackages;
    /**
     * Dump every INBOUND AuthenticatedData as hex (rework 6.3 / 7.5 capture support).
     *
     * <p>Off by default and diagnostic-only. The AAD is authenticated but NOT encrypted, so it is
     * readable on receive without touching the peer — which is what makes a Google Messages peer's AAD
     * capturable from OUR side, with no instrumentation of the Google Messages device at all.
     *
     * <p>Safe to log: it carries a version, our own message id and an optional resent-message
     * component. No key material and no plaintext.
     */
    public final boolean dumpAad;
    public final int eraAdvanceMode;
    /**
     * RCC.16 A.4.1 SAN↔MSISDN identity equality. Default ENFORCE — this is a security control, and
     * the failure it prevents is silently talking to a certificate for someone else's number.
     *
     * <p>Set {@code 0} only to distinguish "our E.164 normalisation disagrees with the KDS" from a
     * real identity mismatch during bring-up: the refusal is logged either way, so turning it off
     * shows whether the group would otherwise form. Not a supported operating configuration.
     */
    public final boolean sanIdentityCheck;
    /** @see #KEY_PUBLISH_EXTERNAL_PUB */
    public final boolean publishExternalPub;
    /** @see #KEY_DOWNGRADE_ON_REPAIR_EXHAUSTED */
    public final boolean downgradeOnRepairExhausted;
    /** @see #KEY_PLAINTEXT_RECONCILE_IMDN */
    public final boolean plaintextReconcileImdn;
    /** @see #KEY_MAINTENANCE_ON_OPEN */
    public final boolean maintenanceOnOpen;
    /** @see #KEY_GROUP_SWEEP */
    public final boolean groupSweep;
    public final long kpMinRemainingDays;
    /** @see #KEY_FLOOR_PRECHECK */
    public final boolean floorPrecheck;
    /** @see #KEY_KP_CERT_FLOOR */
    public final boolean kpCertFloor;
    /** @see #KEY_FLOOR_REBUILD */
    public final boolean floorRebuild;
    public final int kpPoolCount;
    /** §9.3e self-heal budget: attempts before escalation. */
    public final int selfHealRetryLimit;
    /** §9.3e self-heal budget: the window since the first attempt, in ms. */
    public final long selfHealWindowMs;
    /**
     * §9.7l re-upgrade loop 1: the backoff base, in seconds.
     *
     * <p>This and the two below stand in for Google Messages' Phenotype longs 45692099 / 45692100 / 45692391,
     * which are <b>server-delivered and not present in the APK</b> (NEEDS-CAPTURE §22.1-59). The
     * defaults are {@link MlsReupgradeState}'s and are OURS — do not record them as Google Messages'.
     */
    public final long reupgradeBackoffBaseS;
    /** §9.7l re-upgrade loop 1: the maximum shift the attempt count is clamped to. */
    public final int reupgradeBackoffMaxShift;
    /** §9.7l: the downgrade stability window, in seconds — the attempt-counter reset rule. */
    public final long reupgradeStabilityWindowS;
    /**
     * The {@code group_metadata_keys_requested} extension type, or {@link #METADATA_KEYS_EXT_UNKNOWN}
     * (0) meaning UNKNOWN.
     *
     * <p><b>This comment used to name the wrong constant and state the opposite fact</b> — "or
     * {@code DEF_METADATA_KEYS_EXT} (0) meaning UNKNOWN … there is no default value". Both halves
     * went stale when {@link #DEF_METADATA_KEYS_EXT} became {@code 0xF007} (RCC.16 v4.0 §7.11.10.1):
     * there IS a default, it is not 0, and the sentinel is the separate constant below. Left
     * standing, it reads as "the add arm is inert because we do not know the code point", which is
     * exactly backwards — the arm is ARMED by default. Measured before correcting: the shipped
     * default is {@code 0xF007} and {@code debug.rcs.mls_metadata_keys_ext} is unset on every
     * test device, so production reads {@code 0xF007}.
     */
    public final int metadataKeysExtType;

    /**
     * "We do not know which GroupInfo extension is the metadata-keys request."
     *
     * <p>Zero, because 0 is not a valid MLS extension type and so cannot collide with a real answer.
     * Its own constant rather than a reuse of {@link #DEF_METADATA_KEYS_EXT}: those two were the same
     * number for as long as the default WAS unknown, and {@link #metadataKeysExtKnown()} silently
     * meant "differs from the default" — so arming the default made every value read as unknown,
     * including the armed one. A sentinel and a default are different facts and must not share a name.
     */
    public static final int METADATA_KEYS_EXT_UNKNOWN = 0;

    /** Do we know which GroupInfo extension is the metadata-keys request? */
    public boolean metadataKeysExtKnown() {
        return metadataKeysExtType != METADATA_KEYS_EXT_UNKNOWN;
    }

    /**
     * The announced RCC.16 revision as the engine's wire value ({@code 30}/{@code 40}). See
     * {@link #KEY_RCC16_VERSION}.
     */
    public final int rcc16Version;

    /**
     * §10.3's FTD chain cap, {@link MlsFtdEscalation#MAX_FTD_ATTEMPTS} unless overridden.
     *
     * <p>Read at a gate site in the transport, so DoD-3 enumerates it — see
     * {@code MlsGateCounterDurabilityGuardTest}. The counter it bounds is
     * {@code MlsConversationRecord.ftdResendCounts}, which is durable.
     */
    public final int ftdMaxAttempts;

    /** How long between enrolment-identity refreshes, in ms. @see #KEY_IDENTITY_REFRESH_DAYS */
    public final long identityRefreshMs;

    /**
     * How long a FAILED enrolment-identity read suppresses the next attempt.
     * <b>Seconds, not days, and it is NOT a second copy of
     * {@link #identityRefreshMs}.</b>
     *
     * <p>That one is the window between SUCCESSFUL refreshes. This one bounds a BURST of failures:
     * on a device with no adopted identity the read never succeeds, so the success stamp is never
     * written, the weekly guard never engages, and every {@code sealCapability} call made a fresh
     * binder round-trip &mdash; a throttle that records only successes cannot rate-limit a failing
     * call.
     *
     * <p><b>They must stay two numbers.</b> Stamping the weekly pref on a failed ATTEMPT would let
     * the next call re-write the stamp {@code onIdentityChanged} had just cleared to force an
     * immediate re-read after a racing refresh, stranding the identity for {@code identityRefreshMs}
     * &mdash; a week by default. A burst traded for a week-long outage.
     *
     * <p>No sysprop: it bounds a burst rather than scheduling anything, so there is nothing a device
     * run would want to tune. Declared here rather than in the transport because a tunable in the
     * provider layer is a policy decision DoD-1 cannot see — and as an INSTANCE FIELD read through
     * {@code mCfg}, exactly like {@link #identityRefreshMs}, because a {@code public static final}
     * on this class is still referenced by its SCREAMING_CASE name from the transport and the
     * DoD-1 guard counts that as the decision not having left.
     */
    public final long identityRetryBackoffMs;

    /**
     * The source is RETAINED, so the DIAGNOSTIC knobs can be re-read live.
     *
     * <p>Every field below is resolved once, in this constructor, which runs from
     * {@code MlsProviderTransport}'s own constructor. For POLICY that is right — a pool size or a
     * retry window changing mid-flight is a hazard, not a feature. For DIAGNOSTICS it was a trap
     * with a cost: {@code setprop debug.rcs.mls_dump_aad true} appears to work, changes nothing,
     * and the operator concludes the instrument is broken or the path is not being hit. It has cost
     * time at least twice on this project. Diagnostics are now read through {@link #dumpAadLive()}
     * and {@link #dumpKeyPackagesLive()} instead; they sit on paths that already do far more
     * expensive work than a property read, so there is no performance argument for caching them.
     */
    private final Source mSource;

    private MlsConfig(final Source s) {
        mSource = s;
        eraYieldLooks = s.getInt(KEY_ERA_YIELD_LOOKS, DEF_ERA_YIELD_LOOKS);
        dumpKeyPackages = s.getBoolean(KEY_DUMP_KP, false);
        dumpAad = s.getBoolean(KEY_DUMP_AAD, false);
        eraAdvanceMode = s.getInt(KEY_ERA_ADVANCE_MODE, DEF_ERA_ADVANCE_MODE);
        sanIdentityCheck = s.getInt(KEY_SAN_IDENTITY_CHECK, 1) != 0;
        // OFF by default — see the key's doc. This one acts on a predicate we have NOT read.
        downgradeOnRepairExhausted = s.getInt(KEY_DOWNGRADE_ON_REPAIR_EXHAUSTED, 1) != 0;
        publishExternalPub = s.getInt(KEY_PUBLISH_EXTERNAL_PUB, 0) != 0;
        // OFF by default — device-proven HARMFUL against Google Messages. See the key's doc.
        plaintextReconcileImdn = s.getInt(KEY_PLAINTEXT_RECONCILE_IMDN, 0) != 0;
        maintenanceOnOpen = s.getInt(KEY_MAINTENANCE_ON_OPEN, 1) != 0;
        groupSweep = s.getInt(KEY_GROUP_SWEEP, 1) != 0;
        kpMinRemainingDays = s.getLong(KEY_KP_MIN_DAYS, DEF_KP_MIN_REMAINING_DAYS);
        floorPrecheck = s.getInt(KEY_FLOOR_PRECHECK, 1) != 0;
        // OFF by default — see the key's doc. The MEASUREMENT is unconditional; only the refusal is
        // behind this, because no device sample yet covers an ADD of a member inside the floor.
        kpCertFloor = s.getInt(KEY_KP_CERT_FLOOR, 0) != 0;
        // OFF by default — see the key's doc. The MECHANISM is not gated by this and the
        // per-conversation lever is always available; what this decides is whether a rebuild, which
        // re-Welcomes every member including third parties, may happen with nobody asked.
        floorRebuild = s.getInt(KEY_FLOOR_REBUILD, 0) != 0;
        kpPoolCount = Math.max(MIN_KP_POOL_TOTAL, s.getInt(KEY_KP_COUNT, DEF_KP_POOL_TOTAL));
        selfHealRetryLimit = s.getInt(KEY_SELF_HEAL_RETRY_LIMIT, DEF_SELF_HEAL_RETRY_LIMIT);
        selfHealWindowMs = s.getLong(KEY_SELF_HEAL_WINDOW_S, DEF_SELF_HEAL_WINDOW_S) * 1000L;
        reupgradeBackoffBaseS =
                s.getLong(KEY_REUPGRADE_BASE_S, MlsReupgradeState.DEF_BACKOFF_BASE_S);
        reupgradeBackoffMaxShift =
                s.getInt(KEY_REUPGRADE_MAX_SHIFT, MlsReupgradeState.DEF_BACKOFF_MAX_SHIFT);
        reupgradeStabilityWindowS =
                s.getLong(KEY_REUPGRADE_STABILITY_S, MlsReupgradeState.DEF_STABILITY_WINDOW_S);
        metadataKeysExtType = s.getInt(KEY_METADATA_KEYS_EXT, DEF_METADATA_KEYS_EXT);
        rcc16Version = s.getInt(KEY_RCC16_VERSION, DEF_RCC16_VERSION);
        ftdMaxAttempts = s.getInt(KEY_FTD_MAX_ATTEMPTS, MlsFtdEscalation.MAX_FTD_ATTEMPTS);
        identityRefreshMs =
                s.getLong(KEY_IDENTITY_REFRESH_DAYS, DEF_IDENTITY_REFRESH_DAYS) * 24L * 3600_000L;
        identityRetryBackoffMs = DEF_IDENTITY_RETRY_BACKOFF_MS;
    }

    /** @see #identityRetryBackoffMs */
    private static final long DEF_IDENTITY_RETRY_BACKOFF_MS = 30_000L;

    /** Resolve every knob against {@code s}. Call once, at construction. */
    /**
     * {@code debug.rcs.mls_dump_aad}, read LIVE — no app restart required.
     *
     * <p>Prefer this over the cached {@link #dumpAad} field at every call site. The field is kept
     * so the construction log line can show what was set at startup.
     */
    public boolean dumpAadLive() {
        return mSource.getBoolean(KEY_DUMP_AAD, false);
    }

    /** {@code debug.rcs.mls_dump_kp}, read LIVE — no app restart required. */
    public boolean dumpKeyPackagesLive() {
        return mSource.getBoolean(KEY_DUMP_KP, false);
    }

    public static MlsConfig from(final Source s) {
        return new MlsConfig(s);
    }

    /** Every knob at its default — the shipped behaviour, and the baseline for host tests. */
    public static MlsConfig defaults() {
        return new MlsConfig(new Source() {
            @Override public boolean getBoolean(final String k, final boolean d) { return d; }
            @Override public int getInt(final String k, final int d) { return d; }
            @Override public long getLong(final String k, final long d) { return d; }
        });
    }

    @Override public String toString() {
        return "MlsConfig{eraYieldLooks=" + eraYieldLooks
                + " dumpKp=" + dumpKeyPackages
                + " dumpAad=" + dumpAad
                + " eraAdvanceMode=" + eraAdvanceMode
                + " sanIdentityCheck=" + sanIdentityCheck
                + " downgradeOnRepairExhausted=" + downgradeOnRepairExhausted
                + " plaintextReconcileImdn=" + plaintextReconcileImdn
                + " publishExternalPub=" + publishExternalPub
                + " maintenanceOnOpen=" + maintenanceOnOpen
                + " groupSweep=" + groupSweep
                + " kpMinDays=" + kpMinRemainingDays
                + " floorPrecheck=" + floorPrecheck
                + " kpCertFloor=" + kpCertFloor
                + " floorRebuild=" + floorRebuild
                + " kpPool=" + kpPoolCount
                + " selfHealRetries=" + selfHealRetryLimit
                + " selfHealWindowMs=" + selfHealWindowMs
                + " reupgradeBaseS=" + reupgradeBackoffBaseS
                + " reupgradeMaxShift=" + reupgradeBackoffMaxShift
                + " reupgradeStabilityS=" + reupgradeStabilityWindowS
                // The two knobs added 2026-08-06. Present here because this line is how a DEVICE
                // test confirms which spec revision is in effect and whether 0xF007 is armed —
                // both of which change behaviour and neither of which is visible anywhere else.
                + " ftdMaxAttempts=" + ftdMaxAttempts
                + " identityRefreshMs=" + identityRefreshMs
                + " rcc16=" + rcc16Version
                + " metadataKeysExt=" + (metadataKeysExtKnown()
                        ? String.format("0x%04X", metadataKeysExtType) : "UNKNOWN")
                + "}";
    }
}

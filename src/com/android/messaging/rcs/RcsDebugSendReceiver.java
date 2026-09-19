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
package com.android.messaging.rcs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import com.android.messaging.datamodel.action.ReceiveRcsMessageAction;
import org.lineageos.rcs.provider.RcsIncomingMessage;
import org.lineageos.rcs.provider.RcsOutgoingMessage;
import org.lineageos.rcs.provider.RcsSendResult;

import com.android.messaging.rcs.e2ee.E2eeSendGate;
import com.android.messaging.rcs.e2ee.E2eeConversationTransport;
import com.android.messaging.rcs.engine.mls.VerifiableDerivedContent;
import com.android.messaging.rcs.e2ee.MlsProviderTransport;
import com.android.messaging.rcs.engine.mls.MlsAdvanceEraKind;
import com.android.messaging.rcs.engine.mls.MlsPayloadCorruptor;
import com.android.messaging.rcs.engine.mls.RccFileCrypto;
import com.android.messaging.rcs.e2ee.RcsE2eeScheme;
import com.android.messaging.rcs.e2ee.RccMlsBody;
import com.android.messaging.rcs.e2ee.MlsPeerGuard;
import com.android.messaging.util.LogUtil;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Debug-only trigger used by the E2E harness to force an outgoing RCS send
 * straight through the provider seam, bypassing the normal compose UI and the
 * SMS-fallback RouteSelector logic.
 *
 * <p>Exported (so {@code adb shell am broadcast} can reach it) but inert on a
 * user build: {@link #onReceive} short-circuits unless the app is flagged
 * {@link ApplicationInfo#FLAG_DEBUGGABLE}. On a debuggable build it builds an
 * {@link RcsOutgoingMessage} from the {@code to} / {@code body} string extras
 * (subId = default-SMS sub) and calls
 * {@link ProviderTransport#sendMessage(RcsOutgoingMessage)} directly.
 *
 * <p>Exercise from adb:
 * <pre>
 *   adb shell am broadcast \
 *     -a com.android.messaging.debug.SEND_TEST_RCS \
 *     -n com.android.messaging/.rcs.RcsDebugSendReceiver \
 *     --es to "+15555550123" --es body "hello from e2e"
 * </pre>
 * Grep logcat for the "DEBUG SEND_TEST_RCS" lines (tag {@code MessagingApp}).
 */
public final class RcsDebugSendReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    static final String ACTION_SEND_TEST_RCS = "com.android.messaging.debug.SEND_TEST_RCS";
    private static final String EXTRA_TO = "to";
    private static final String EXTRA_BODY = "body";
    /** {@code --ez e2ee true} → send through the production RCC.16-framed MLS path. */
    private static final String EXTRA_E2EE = "e2ee";
    private static final String CONTENT_TYPE = "text/plain;charset=UTF-8";

    /**
     * The boolean arms {@link #armOf} matches first, in dispatch order. Hoisted out of armOf so the
     * gate vocabulary below can be checked against armOf's OWN literals rather than a hand-copied
     * second list — a duplicate would drift exactly the way the one described below did.
     */
    private static final String[] BOOL_ARMS = {
            "appmls", "selfheal", "records", "groupexts", "ftdfail", "ftdreport", "forgetgroup",
            "servera", "eraadvance", "floorrebuild", "groupsend", "health", "procresults",
            "advancer", "continuity",
    };

    /**
     * The second block of boolean arms, matched after {@code establishgroup} and {@code membership}.
     *
     * <p>{@code resync} sits between {@code publishkp} and {@code dropprops} to mirror its dispatch
     * position (the external-commit arm), NOT beside {@code eraadvance}. It was MISSING here
     * entirely at first: {@code --ez resync true} fell through every branch to
     * {@code "send"}, so the {@link #STATE_CHANGING} lab-peer gate never ran for the one arm that
     * builds a commit against the SERVER's GroupInfo with {@code forcePastProfile=true}.
     */
    private static final String[] MORE_ARMS = {
            "servremove", "imdnsig", "iconsubj", "endmls", "phoenix", "seedusage", "publishkp",
            "resync", "dropprops", "kpvet",
    };

    /**
     * The remaining names {@link #armOf} can return: the String-extra arms, the two collapsing
     * branches ({@code membership}, {@code commit}) and the terminal fallthrough {@code send}.
     *
     * <p>This one IS hand-maintained, because those arms are individual {@code if}s rather than a
     * table. Adding a return to armOf means adding it here — and {@link #assertArmVocabulary()}
     * turns a mistake into a loud refusal instead of a gate that silently never fires.
     */
    private static final String[] OTHER_ARMS = {
            "establishgroup", "membership", "mlshold", "mlsahead", "claimkp", "replayapp",
            "replayctrl", "removemember", "changesubj", "plainrename", "commit", "ctrl", "gate",
            "send",
    };

    /** Every string {@link #armOf} can return. */
    private static final java.util.Set<String> ARM_NAMES;
    static {
        final java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Collections.addAll(names, BOOL_ARMS);
        java.util.Collections.addAll(names, MORE_ARMS);
        java.util.Collections.addAll(names, OTHER_ARMS);
        ARM_NAMES = java.util.Collections.unmodifiableSet(names);
    }

    /**
     * The arms that act on a named peer's conversation and cannot do anything sensible without one.
     *
     * <p>Deliberately NOT "every arm that reads {@code to}": several pass it through as an optional
     * hint ({@code servera}, {@code servremove}, {@code procresults}) and behave correctly with
     * null. Listing those here would re-create the defect described at the dispatch site in a
     * narrower form — a required extra that the arm does not actually require.
     */
    private static final java.util.Set<String> NEEDS_TO =
            java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(
                    "appmls", "selfheal", "groupexts", "ftdfail", "ftdreport", "forgetgroup",
                    "eraadvance", "floorrebuild", "health", "membership", "imdnsig", "iconsubj",
                    "endmls", "continuity",
                    "phoenix", "seedusage", "dropprops", "removemember", "changesubj",
                    "commit", "ctrl", "gate")));

    /**
     * Arms that CHANGE MLS STATE ON A PEER, and are therefore gated by the lab allowlist
     * ({@link com.android.messaging.rcs.e2ee.MlsPeerGuard}).
     *
     * <p>These are the arms that wedged a real person's iPhone for a month: era advances, creates,
     * adds/removes, rekeys, self-heals and end_mls, driven by hand dozens of times against a number
     * that was never a lab device. The same declarative-list reasoning as {@link #NEEDS_TO} applies —
     * one list checked once beats the same guard copied into twenty arms.
     *
     * <p>DELIBERATELY EXCLUDED: {@code forgetgroup} and the other purely LOCAL cleanup arms. When a
     * peer is already wedged, our ability to stop talking to it must not be gated — that is the
     * recovery path. Also excluded: read-only arms ({@code health}, {@code servera},
     * {@code groupexts}, {@code pkeys}, {@code records}, {@code kpvet}), which transmit no state.
     *
     * <p>NOT LISTED, DELIBERATELY: {@code rekey}. {@code --ez rekey true} IS gated — but under the
     * name {@code commit}, because {@link #armOf} maps both it and {@code --es addmember} to
     * "commit". Listing "rekey" here as well read as a second, independent guarantee and was in
     * fact dead: armOf can never return it. Every name in this set must be one armOf can produce,
     * which {@link #assertArmVocabulary()} now enforces.
     */
    private static final java.util.Set<String> STATE_CHANGING =
            java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(
                    "eraadvance", "floorrebuild", "establishgroup", "commit", "removemember",
                    "membership",
                    "selfheal", "endmls", "changesubj", "iconsubj", "servremove",
                    "phoenix", "resync", "plainrename")));

    /**
     * Every peer a state-changing arm would touch — `to` plus the arm-specific member extras.
     *
     * <p>Returns an EMPTY list only when the arm names no peer at all, in which case there is
     * nothing to gate (a group-id-only operation resolves its members from local state, which is
     * itself derived from peers that were gated when they were added).
     */
    private static java.util.List<String> debugArmPeers(final Intent i, final String to) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        if (!TextUtils.isEmpty(to)) out.add(to);
        // Arms that carry their own recipient list rather than using `to`.
        for (final String key : new String[] {"establishgroup", "addmember", "removemember",
                "members"}) {
            final String v = i.getStringExtra(key);
            if (TextUtils.isEmpty(v)) continue;
            for (final String part : v.split(",")) {
                final String p = part.trim();
                // Only things that look like a peer — these extras also carry non-E.164 values
                // (removemember can take a signature pubkey), and gating a non-peer string would
                // reproduce the false refusal this method exists to fix.
                if (p.startsWith("+") && p.length() >= 8) out.add(p);
            }
        }
        return out;
    }

    /**
     * Which arm this broadcast selects, for the one-line trace at the top of {@link #onReceive}.
     *
     * <p><b>Ordering mirrors the if-chain below and is for LOGGING ONLY</b> — dispatch remains the
     * chain itself, so a drift here misnames a line but cannot misroute a broadcast. Keep it in
     * step anyway: a trace that names the wrong arm is worse than no trace.
     *
     * <p>Note {@code eraadvance} is matched BEFORE {@code health}, matching the chain. So
     * {@code --ez health true --ez eraadvance true} runs the era-advance arm, and the health arm's
     * own {@code eraadvance} modifier is unreachable in that combination.
     */
    /**
     * The two gate lists must only name arms {@link #armOf} can actually return.
     *
     * <p>THIS GENERALISES A REAL DEFECT. {@link #STATE_CHANGING} named {@code resync},
     * armOf could not produce it, and so the lab-peer gate — the one guardrail between a debug
     * broadcast and an MLS state change on a phone we do not own — never ran for that arm. Nothing
     * said so: a set entry that cannot match reports green forever, and the entry's own presence is
     * what makes the gate read as covered.
     *
     * <p>It catches ONE direction only. The opposite — an arm armOf can produce that belongs in a
     * gate list and is missing from it — is equally silent, and is not mechanically decidable:
     * whether a new arm changes peer state is a judgement, so that half stays a review question.
     *
     * @return the offending names, empty when the vocabulary is consistent.
     */
    private static java.util.Set<String> assertArmVocabulary() {
        final java.util.Set<String> bad = new java.util.TreeSet<>();
        for (final String n : STATE_CHANGING) {
            if (!ARM_NAMES.contains(n)) {
                bad.add("STATE_CHANGING:" + n);
            }
        }
        for (final String n : NEEDS_TO) {
            if (!ARM_NAMES.contains(n)) {
                bad.add("NEEDS_TO:" + n);
            }
        }
        return bad;
    }

    private static String armOf(final Intent i) {
        for (final String b : BOOL_ARMS) {
            if (i.getBooleanExtra(b, false)) return b;
        }
        if (i.getStringExtra("establishgroup") != null) return "establishgroup";
        if (i.getBooleanExtra("leave", false) || i.getBooleanExtra("rmmember", false)
                || i.getBooleanExtra("forget", false)) {
            return "membership";
        }
        for (final String b : MORE_ARMS) {
            if (i.getBooleanExtra(b, false)) return b;
        }
        // NOT in STATE_CHANGING, and not in NEEDS_TO. The gate for this one is verb-dependent — only
        // `arm` is gated, and it is gated on the whole ROSTER rather than on `to` — so it lives in
        // MlsProviderTransport.armInboundHold where the roster is readable. See the comment there.
        if (i.getStringExtra("mlshold") != null) return "mlshold";
        // Same shape and the same reason as mlshold: only `arm` is gated, on the whole ROSTER
        // rather than on `to`, so the gate lives in MlsProviderTransport.armOutboundHold where the
        // roster is readable. Its javadoc argues why an AHEAD hold needs the allowlist MORE than the
        // BEHIND one, not less — our reconcile ladder answers AHEAD with the REBUILD.
        if (i.getStringExtra("mlsahead") != null) return "mlsahead";
        if (i.getStringExtra("recvct") != null) return "recvct";
        if (i.getStringExtra("claimkp") != null) return "claimkp";
        if (i.getStringExtra("replayapp") != null) return "replayapp";
        if (i.getStringExtra("replayctrl") != null) return "replayctrl";
        if (i.getStringExtra("removemember") != null) return "removemember";
        if (i.getStringExtra("changesubj") != null) return "changesubj";
        if (i.getStringExtra("plainrename") != null) return "plainrename";
        if (i.getBooleanExtra("rekey", false) || i.getStringExtra("addmember") != null) {
            return "commit";
        }
        if (i.getBooleanExtra("ctrl", false)) return "ctrl";
        if (i.getBooleanExtra("gate", false)) return "gate";
        return "send";
    }

    /** Even-length hex to bytes, or null. Used by the {@code continuity} arm's optional token. */
    private static byte[] unhex(final String hex) {
        if (hex == null || hex.length() % 2 != 0) return null;
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = Character.digit(hex.charAt(2 * i), 16);
            final int lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION_SEND_TEST_RCS.equals(intent.getAction())) {
            return;
        }

        // Gate: inert on a user (non-debuggable) build even though exported. Use
        // the build TYPE (eng/userdebug), NOT ApplicationInfo.FLAG_DEBUGGABLE —
        // the latter reflects the manifest android:debuggable attr and is false
        // for system apps even on a userdebug image.
        final boolean debuggable =
                "eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE);
        if (!debuggable) {
            LogUtil.w(TAG, "DEBUG SEND_TEST_RCS ignored: build is not debuggable");
            return;
        }

        // FAIL-CLOSED on an inconsistent gate vocabulary. If a gate list names an arm
        // armOf cannot produce, that gate is DEAD, and we cannot know from here which peer it
        // would have protected — so refuse every arm rather than dispatch past a guard that is not
        // running. Loud and total beats the silent green this replaces.
        final java.util.Set<String> badArms = assertArmVocabulary();
        if (!badArms.isEmpty()) {
            LogUtil.e(TAG, "DEBUG SEND_TEST_RCS REFUSED: gate lists name arms armOf can never "
                    + "return " + badArms + " — that gate silently never fires. Fix armOf or the "
                    + "list before using the debug receiver.");
            return;
        }

        final String to = intent.getStringExtra(EXTRA_TO);
        final String body = intent.getStringExtra(EXTRA_BODY);

        // NO to/body GATE HERE.
        //
        // This used to require BOTH extras before ANY arm dispatched, which is wrong: the guard
        // exists only for the DEFAULT send path at the bottom of this method, and every arm below
        // returns long before reaching it. Arms that need neither extra (publishkp, kpvet, records,
        // establishgroup, replayctrl, plainrename) were therefore unreachable from adb, and arms
        // needing only `to` (forget, leave, eraadvance, health, …) were unreachable without also
        // passing a meaningless `body`.
        //
        // That cost a full device session: `--ez publishkp true` is the ONLY direct lever for
        // republishing a stale KeyPackage pool, and the pool republish was the single blocker on the
        // whole device-verification queue. It returned "Broadcast completed: result=0"
        // and did nothing — the most expensive shape a debug hook can fail in, because it reads as
        // "ran, no effect" rather than "never ran".
        //
        // The gate now sits immediately before the default send path, which is the only code here
        // that actually consumes both extras. Arms that genuinely need `to` are named in NEEDS_TO
        // and checked once, below — one declarative list beats the same guard copied into 20 arms.
        final String arm = armOf(intent);
        LogUtil.i(TAG, "DEBUG SEND_TEST_RCS arm=" + arm
                + " to=" + (to == null ? "<unset>" : to)
                + " body=" + (body == null ? "<unset>" : (body.length() + "ch"))
                + " rcsgid=" + intent.getStringExtra("rcsgid"));
        if (NEEDS_TO.contains(arm) && TextUtils.isEmpty(to)) {
            LogUtil.w(TAG, "DEBUG SEND_TEST_RCS arm=" + arm + " needs --es to <e164>; ignoring. "
                    + "(It names the peer whose conversation this arm acts on.)");
            return;
        }
        // THE LAB-PEER GATE. A debug-driven MLS state change may only ever target a
        // device we own. Checked here, once, for every arm — see STATE_CHANGING for why this list
        // and not the others.
        //
        // CHECK EVERY PEER THE ARM TARGETS, NOT JUST `to`. The first cut checked only `to` and
        // refused `establishgroup` outright, because that arm takes its members as a CSV in
        // --es establishgroup and legitimately has no `to` at all (it is named in the NEEDS_TO
        // comment above as one of the arms needing neither extra). A safety gate that blocks valid
        // lab work gets switched off, so getting this right matters as much as the refusal itself.
        if (STATE_CHANGING.contains(arm)) {
            for (final String peer : debugArmPeers(intent, to)) {
                if (!MlsPeerGuard.allowDebugStateChange(arm, peer)) {
                    LogUtil.e(TAG, "DEBUG SEND_TEST_RCS arm=" + arm + " REFUSED by MlsPeerGuard — "
                            + "see the MlsPeerGuard line above for which rule fired.");
                    return;
                }
            }
        }

        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultDataSubscriptionId();
        }

        final String messageId = UUID.randomUUID().toString();
        LogUtil.i(TAG, "DEBUG SEND_TEST_RCS to=" + to + " body=" + body
                + " subId=" + subId + " messageId=" + messageId
                + " -> ProviderTransport.sendMessage");

        // --ez e2ee true reproduces the PRODUCTION send shape (InsertNewMessageAction path 2):
        // frame the RCC.16 body HERE (the generic MLS layer) and tag the scheme, so the provider
        // receives a framed payload and MLS-encrypts it verbatim via sendMlsRaw.
        //
        // Without this the harness called sendMessage() with a bare text body and NO scheme, which
        // lands on the provider's plaintext path and silently bypasses messaging2's E2EE layer
        // entirely. That blind spot is why a hardcoded RCC.16 body counter survived in production
        // while provider-direct testing (debug.rcs.mls_provider_framing=true, a DIFFERENT framing
        // implementation) reported everything green. Default stays false so existing plain-path
        // testing is unchanged.
        final boolean e2ee = intent.getBooleanExtra(EXTRA_E2EE, false);


        // --ez appmls true → THE OWNERSHIP FLIP end-to-end: this app owns the engine,
        // frames the RCC.16 body, stamps the generation counter, binds the AAD and SEALS the message;
        // the provider only carries the envelope (sendMlsCiphertext, contract v23).
        //
        // This is the migration's actual acceptance test. Everything before it proved a piece — the
        // engine is shared, the state migrated and LOADS, capabilities and control traffic cross the
        // binder. This proves the whole chain runs with MLS owned by the app.
        if (intent.getBooleanExtra("appmls", false)) {
            final Context appCtx3 = context.getApplicationContext();
            final int aSub = subId;
            final String aTo = to;
            final String aBody = body;
            new Thread(new Runnable() {
                @Override public void run() {
                    // get(), NOT new — the lever must exercise the instance production uses.
                    //
                    // This built a FRESH MlsProviderTransport per broadcast, and three pieces of
                    // per-instance state made that a materially different code path from a real
                    // send (device-observed 2026-08-08):
                    //
                    //   1. THE SENDER-RATCHET CACHE (vc790, the KEY_GEN root fix) keeps the group in
                    //      memory across encrypts. A fresh instance has an empty cache, so every
                    //      lever send re-loaded the group — i.e. every experiment run through here
                    //      was measuring the behaviour that fix removed.
                    //   2. mLocks is per-instance, so concurrent lever sends serialised against
                    //      nothing. Two of them sealed the SAME generation with the same message id
                    //      and put two different plaintexts on the wire under one id.
                    //   3. the sealed-ciphertext cache (invariant 62) likewise starts empty.
                    //
                    // None of that is reachable in production, which only ever calls get(). So the
                    // lever was not a weaker version of the real path, it was a different one — and
                    // it is the path most of this project's send-side measurements were taken on.
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtx3, aSub);
                    // THE LEVER MINTS ITS OWN CONVERSATION, AND THAT IS A TRAP WORTH SEEING.
                    //
                    // "appmls-<peer>" is a key this debug arm invented; it is NOT the conversation
                    // the app uses for that peer. So this device can end up holding TWO MLS
                    // conversations with one peer, drifting independently — device-observed
                    // 2026-08-08, where the real 1:1 with that peer was Healthy while
                    // THIS pseudo-conversation had reached DoneEndMls, so every appmls send refused
                    // with "is DOWNGRADED ... not sealing" and the obvious reading ("our 1:1 with
                    // Google Messages is broken") was wrong.
                    //
                    // Deliberately NOT changed to resolve the real conversation here: this peer
                    // showed THREE key forms in one session (p:<peer>, appmls-<peer>, and an
                    // rcsGid-keyed record), and silently re-pointing a send path is how a misroute
                    // ships. Naming the key costs nothing and makes the
                    // ambiguity visible to whoever is reading the log; the routing fix belongs in
                    // its own change, with the resolution rule settled first.
                    final String convId = "appmls-" + aTo;
                    // AND REPORT THE REAL CONVERSATION'S HEALTH ALONGSIDE.
                    //
                    // The routing is deliberately left alone above. What cost a session was not the
                    // pseudo-key itself but having to go and find the real conversation by hand
                    // afterwards: on 2026-08-08 this lever's conversation was DoneEndMls while the
                    // peer's REAL 1:1 was Healthy, and the obvious reading of the refusal ("our 1:1
                    // with Google Messages is broken") was wrong. Printing both side by side makes that
                    // impossible to misread, and costs one detectHealth call with no routing risk.
                    final MlsProviderTransport.Health realHealth = t.detectHealth(
                            com.android.messaging.rcs.engine.mls.MlsFetchLedger.Caller.DEBUG_HEALTH,
                            null, aTo);
                    LogUtil.i(TAG, "DEBUG APP-MLS the peer's REAL 1:1 conversation health is "
                            + realHealth + " — compare against this lever's own conversation below. "
                            + "If they disagree, the lever is telling you about ITS conversation, "
                            + "not the app's.");
                    LogUtil.i(TAG, "DEBUG APP-MLS using conversation key '" + convId + "' — this is "
                            + "the LEVER'S OWN key, not necessarily the conversation the app uses "
                            + "for " + aTo + ". If this refuses as DOWNGRADED, check the peer's REAL "
                            + "conversation health printed above before concluding the 1:1 is broken.");
                    final boolean ready = t.ensureReady(convId, aSub,
                            java.util.Collections.singletonList(aTo));
                    LogUtil.i(TAG, "DEBUG APP-MLS ensureReady → " + ready
                            + " profile=" + t.profile());
                    if (!ready) return;
                    // Frame HERE (RccMlsBody) — the app owns framing now, and the counter it stamps
                    // must match the generation the app's own ratchet seals at.
                    final byte[] framed = RccMlsBody.frameText(aBody);
                    // --es msgid <id> binds a caller-supplied rcs_message_id as the wire + AAD id
                    //. Omit it to exercise the legacy synthesised-id fallback, so both
                    // arms are testable from here rather than only the one production takes.
                    final String aMsgId = intent.getStringExtra("msgid");
                    // --ez corruptct true → flip a byte in the sealed ct of this 1:1 send so the
                    // peer's AEAD fails (recoverable decrypt-miss) and our resend is readable.
                    if (intent.getBooleanExtra("corruptct", false)) {
                        MlsProviderTransport.setCorruptNextCt();
                        LogUtil.w(TAG, "DEBUG APP-MLS: arming POST-SEAL CT CORRUPTION (capstone)");
                    }
                    final E2eeConversationTransport.Payload p =
                            t.encryptForSend(convId, framed, aMsgId);
                    if (p == null) {
                        LogUtil.w(TAG, "DEBUG APP-MLS encryptForSend → null (see refusal above)");
                        return;
                    }
                    LogUtil.i(TAG, "DEBUG APP-MLS sealed ct=" + p.body.length
                            + "B eraHdr=" + (p.cpimHeaders == null ? "?" : p.cpimHeaders.get("Era-ID"))
                            + " wireMsgId=" + (p.cpimHeaders == null ? "?"
                                    : p.cpimHeaders.get(MlsProviderTransport.HDR_SEALED_MESSAGE_ID))
                            + " (requested=" + aMsgId + ")");
                    // Send VERBATIM, with the id/era/epoch-auth from the SAME seal.
                    LogUtil.i(TAG, "DEBUG APP-MLS sendSealed → " + t.sendSealed(aTo, p));
                }
            }, "app-mls-send").start();
            return;
        }

        // --ez advancer true --es rcsgid <id> [--es to <peer>] → print the advancer election's
        // inputs and verdict WITHOUT fetching and without changing anything.
        //
        // Read this BEFORE --ez selfheal on a fixture. "ahead=NEVER_HEARD" and "ahead=UNKNOWN" lead
        // to very different budgets (the floor vs the unchanged base) and are indistinguishable
        // from the self-heal log until three looks have already been spent — at which point the
        // second reads as "the fix did nothing" when it is in fact the documented no-evidence arm.
        if (intent.getBooleanExtra("advancer", false)) {
            final Context appCtxA = context.getApplicationContext();
            final int aSubId = subId;
            final String aGid = intent.getStringExtra("rcsgid");
            final String aTo = to;
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS ADVANCER "
                            + MlsProviderTransport.get(appCtxA, aSubId)
                                    .dumpAdvancerElection(aGid, aTo));
                }
            }, "mls-advancer").start();
            return;
        }

        // --ez selfheal true --es rcsgid <id> --es to <peer> → enhanced self-heal: fetch the commits
        // we missed and replay them, instead of waiting for a peer to re-Welcome us.
        if (intent.getBooleanExtra("selfheal", false)) {
            final Context appCtxH = context.getApplicationContext();
            final int hSubId = subId;
            final String hGid = intent.getStringExtra("rcsgid");
            final String hTo = to;
            new Thread(new Runnable() {
                @Override public void run() {
                    final int n = MlsProviderTransport.get(appCtxH, hSubId).selfHeal(hGid, hTo);
                    LogUtil.i(TAG, "DEBUG MLS SELFHEAL(" + hGid + ") → applied=" + n);
                }
            }, "mls-self-heal").start();
            return;
        }

        // --ez records true → dump every persisted per-conversation record (rework 2.4).
        //
        // This exists INSTEAD of a query. §4.8 shape-rule 2 forbids indexing the record on anything,
        // health_status included, so "which groups are unhealthy?" is answered by reading a dump —
        // not by growing an accessor that becomes a second source of truth.
        // --ez membervalidity true --es to <E164> [--es rcsgid <gid>]
        // Prints every member's certificate window for one conversation: the input
        // section 9.7's expiry refresh needs, and the row (16) that nothing has ever written.
        if (intent.getBooleanExtra("membervalidity", false)) {
            final Context appCtxV = context.getApplicationContext();
            final int vSubId = subId;
            final String vTo = to;
            final String vGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS MEMBERVALIDITY(" + vTo + ") → "
                            + MlsProviderTransport.get(appCtxV, vSubId).dumpMemberValidity(vGid, vTo));
                }
            }, "mls-membervalidity").start();
            return;
        }

        // --ez servervalidity true --es to <E164> [--es rcsgid <gid>]
        //
        // The SERVER's copy of the roster. --ez membervalidity prints the LOCAL
        // engine's group; this prints the copy the server holds and validates against, which is the
        // only one a credential repair can be judged on — success is measured on the group.
        //
        // READ-ONLY. It parses the ratchet tree GetMlsGroupInfo already returns; it does not join,
        // resync or commit. It charges the fetch ledger as DEBUG_DUMP, which is exempt from the
        // ceiling and recorded.
        //
        // IT PRINTS IDENTITIES BEFORE WINDOWS, which is not a formatting choice: on a forked group
        // the leaf identities are the only thing that says whose state the server descends from,
        // and whether OUR leaf is in the tree at all — a member can sit in the RCS roster with no
        // MLS leaf, which is what a re-Welcome that never arrived leaves behind.
        if (intent.getBooleanExtra("servervalidity", false)) {
            final Context appCtxSv = context.getApplicationContext();
            final int svSubId = subId;
            final String svTo = to;
            final String svGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS SERVERVALIDITY(" + svTo + ") -> "
                            + MlsProviderTransport.get(appCtxSv, svSubId)
                                    .dumpServerValidity(svGid, svTo));
                }
            }, "mls-servervalidity").start();
            return;
        }

        // --ez kpcount true --es rcsgid <gid> [--es members <csv>]
        //
        // The roster-wide key-package COUNT — the comparison UpgradeToMlsOperation makes
        // before it will turn a conversation MLS:
        //     "Skip conversation update because keyPackage count %d is less than remote
        //      participants count %d"
        // STRICT and ALL-OR-NOTHING: one member without a claimable key package holds the entire
        // group on Etouffee, silently. So the probe names the member rather than only counting.
        //
        // NOT --ez kpvet, which inspects OUR OWN key package and reports "KeyPackage for <our own
        // number> accepted" even when pointed at a peer — the reason the earlier check came back
        // inconclusive instead of settled.
        //
        // CONSUMES one key package per member, exactly as the operation it models does. Cheap
        // (pools are eleven deep and refill) but not idempotent: do not loop it.
        // --ez failnextdecrypt true  → arm the one-shot receive-side decrypt failure.
        // Pairs with --ez corruptct true, which is the SEND-side twin. This one is the only way to
        // draw an FTD for a message a Google Messages peer originated, which is what makes it perform
        // the §10.3 resend we need to capture.
        if (intent.getBooleanExtra("failnextdecrypt", false)) {
            MlsProviderTransport.setFailNextDecrypt(context.getApplicationContext());
            LogUtil.w(TAG, "DEBUG MLS: armed a ONE-SHOT forced decrypt failure for the next inbound "
                    + "message — the next thing we receive will be reported as undecryptable even "
                    + "though it is intact.");
            return;
        }

        // --es mlshold <arm|status|disarm|release|drop>  → THE BEHIND FIXTURE.
        //
        // Holds the inbound HANDSHAKE plane for ONE named conversation until it is disarmed, so the
        // group advances and this device does not. That produces an arbitrary, controllable epoch
        // gap — the state on which the designated-advancer election and its yield/takeover, the
        // drive loop, the rebuild rung and the era-advance fallback are the ONLY reachable code, and
        // which we previously had no dependable way to produce.
        //
        // WHY NOT --ez failnextdecrypt, WHICH ALREADY EXISTS: that one is one-shot and untargeted,
        // so it races everything else on the wire. Aiming it at a specific commit failed because
        // unrelated inbound consumed the arm first and the device stayed IN_SYNC. This one is
        // scoped to a conversation, is armed until disarmed, and counts what it did.
        //
        // WHY IT HOLDS RATHER THAN DROPS: there is no commit backfill — the provider's
        // fetchMissedCommits says so in as many words — so a dropped commit has no replay source
        // anywhere and the fixture would destroy the group it was measuring. The held bytes are the
        // only copy that exists, and `release`
        // hands them back. `--ez discard true` opts into the destructive version for the
        // rebuild-rung case, loudly.
        //
        // This comment also cited "and our groups' server GroupInfo carries no external_pub".
        // RETRACTED 2026-09-08: that reading came from an extension reader that answered ABSENT for
        // any non-continuity code point without calling native, so it never measured the server;
        // external_pub is group-dependent and measures 67B on 2 of the 3 groups we hold. The
        // hold-not-drop decision never rested on it — no backfill is sufficient on its own.
        //
        //   adb shell am broadcast -a com.android.messaging.debug.SEND_TEST_RCS \
        //     -n com.android.messaging/.rcs.RcsDebugSendReceiver \
        //     --es mlshold arm --es rcsgid <gid> [--es holdmode commit|handshake|control] \
        //     [--ez discard true] [--es members "+1…,+1…"]
        //   … --es mlshold status
        //   … --es mlshold disarm     (stop holding, KEEP the bytes — leaves the gap standing)
        //   … --es mlshold release    (stop holding and replay the bytes in arrival order)
        //   … --es mlshold drop       (destroy the bytes — the gap becomes unrecoverable)
        //
        // Grep logcat for "DEBUG MLS HOLD". A 1:1 conversation takes --es to <e164> instead of
        // --es rcsgid.
        if (intent.getStringExtra("mlshold") != null) {
            final Context appCtxH = context.getApplicationContext();
            final int hSub = subId;
            final String verb = intent.getStringExtra("mlshold").trim().toLowerCase(
                    java.util.Locale.US);
            final String hGid = intent.getStringExtra("rcsgid");
            final String hTo = to;
            final com.android.messaging.rcs.engine.mls.MlsInboundHold.Mode hMode =
                    com.android.messaging.rcs.engine.mls.MlsInboundHold.modeOf(
                            intent.getStringExtra("holdmode"),
                            com.android.messaging.rcs.engine.mls.MlsInboundHold.Mode.HANDSHAKE);
            final boolean hDiscard = intent.getBooleanExtra("discard", false);
            final String hMembers = intent.getStringExtra("members");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtxH, hSub);
                    final String out;
                    if ("arm".equals(verb)) {
                        out = t.armInboundHold(hGid, hTo, hMode, hDiscard, hMembers);
                    } else if ("release".equals(verb)) {
                        out = t.releaseInboundHold();
                    } else if ("disarm".equals(verb)) {
                        out = t.disarmInboundHold();
                    } else if ("drop".equals(verb)) {
                        out = t.dropInboundHold();
                    } else if ("status".equals(verb)) {
                        out = t.inboundHoldStatus();
                    } else {
                        out = "unknown verb '" + verb + "' — one of arm|status|disarm|release|drop";
                    }
                    LogUtil.i(TAG, "DEBUG MLS HOLD(" + verb + ") → " + out);
                    // ALWAYS print the status after a mutating verb, measured rather than assumed.
                    // The arm's own return value says what was REQUESTED; this says what IS.
                    if (!"status".equals(verb)) {
                        LogUtil.i(TAG, "DEBUG MLS HOLD status → " + t.inboundHoldStatus());
                    }
                }
            }, "mls-hold").start();
            return;
        }

        // --es mlsahead <arm|status|disarm|release|publish|drop>  → THE AHEAD FIXTURE.
        //
        // The MIRROR of --es mlshold, and deliberately a separate lever rather than a mode of it.
        // mlshold holds INBOUND handshake so the group advances and we do not (BEHIND); this one
        // withholds an OUTBOUND rekey commit the engine has ALREADY applied locally, so we advance
        // and the group does not (AHEAD). Health.AHEAD has its own reconcile arm and was just as
        // unreachable on demand.
        //
        // A DIFFERENT KIND OF DIVERGENCE, which is why the release is not the same shape. The
        // inbound hold is recoverable because this device keeps the only copy of what it withheld.
        // An unpublished commit is a rung the group will NEVER have — nothing can hand it to them
        // after the fact — so the undo is purely local: restoreGroupSnapshot of the state captured
        // before the commit was applied. It needs nothing from the server and nothing from a peer,
        // and for exactly that reason nothing else notices if it silently fails, so `release`
        // MEASURES it: era and epoch read back and compared against the snapshot's own values, and
        // it reports RESTORED only when they agree.
        //
        // REKEY ONLY. A rekey is the minimal epoch-advancing operation — no membership change, no
        // RCS half to fall out of step — and on an MLS GROUP the add and remove do not pass through
        // this choke point at all (they ride addGroupUsersMls / removeGroupUsersMls).
        //
        //   adb shell am broadcast -a com.android.messaging.debug.SEND_TEST_RCS \
        //     -n com.android.messaging/.rcs.RcsDebugSendReceiver \
        //     --es mlsahead arm --es rcsgid <gid> [--es members "+1…,+1…"]
        //   … --ez rekey true --es rcsgid <gid> --es to <e164>   (each one withholds a commit)
        //   … --es mlsahead status
        //   … --es mlsahead disarm    (stop withholding, KEEP the lead AND the undo)
        //   … --es mlsahead release   (UNDO: restore the snapshot, and prove the epoch went back)
        //   … --es mlsahead publish   (send the withheld commits late — the server catches up)
        //   … --es mlsahead drop      (throw the undo away — the lead becomes unrecoverable)
        //
        // Grep logcat for "DEBUG MLS AHEAD". A 1:1 takes --es to <e164> instead of --es rcsgid.
        if (intent.getStringExtra("mlsahead") != null) {
            final Context appCtxA = context.getApplicationContext();
            final int aSub = subId;
            final String verb = com.android.messaging.rcs.engine.mls.MlsOutboundHold.verbOf(
                    intent.getStringExtra("mlsahead"));
            final String aGid = intent.getStringExtra("rcsgid");
            final String aTo = to;
            final String aMembers = intent.getStringExtra("members");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtxA, aSub);
                    final String out;
                    if ("arm".equals(verb)) {
                        out = t.armOutboundHold(aGid, aTo, aMembers);
                    } else if ("release".equals(verb)) {
                        out = t.releaseOutboundHold();
                    } else if ("publish".equals(verb)) {
                        out = t.publishOutboundHold();
                    } else if ("disarm".equals(verb)) {
                        out = t.disarmOutboundHold();
                    } else if ("drop".equals(verb)) {
                        out = t.dropOutboundHold();
                    } else if ("status".equals(verb)) {
                        out = t.outboundHoldStatus();
                    } else {
                        out = "unknown verb '" + intent.getStringExtra("mlsahead")
                                + "' — one of arm|status|disarm|release|publish|drop";
                    }
                    LogUtil.i(TAG, "DEBUG MLS AHEAD(" + verb + ") → " + out);
                    // ALWAYS print the status after a mutating verb, measured rather than assumed:
                    // the verb's return value says what was REQUESTED, this says what IS.
                    if (!"status".equals(verb)) {
                        LogUtil.i(TAG, "DEBUG MLS AHEAD status → " + t.outboundHoldStatus());
                    }
                }
            }, "mls-ahead").start();
            return;
        }

        if (intent.getBooleanExtra("kpcount", false)) {
            final Context appCtxK = context.getApplicationContext();
            final int kSubId = subId;
            final String kGid = intent.getStringExtra("rcsgid");
            final String kMembers = intent.getStringExtra("members");
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS KPCOUNT(" + kGid + ") → "
                            + MlsProviderTransport.get(appCtxK, kSubId)
                                    .dumpKeyPackageCount(kGid, kMembers));
                }
            }, "mls-kpcount").start();
            return;
        }

        // --ez maintenance true --es to <E164> [--es rcsgid <gid>]
        //
        // Run the PROACTIVE maintenance pass once, by hand: Google Messages'
        // ZinniaMaintenanceSynclet -> maybeRefresh. Reads the server's view, computes the membership
        // delta and the expired-certificate count, and issues an era advance only if
        // MlsMaintenancePolicy says one is warranted.
        //
        // NOT A RECOVERY LEVER, and that distinction is the whole point: recovery issues
        // through different classes and is gated on state/health, never on a membership delta.
        // Reaching for this to fix a diverged conversation will do nothing, correctly.
        //
        // It is also the instrument for reading an unknown extension off the wire — the pass logs
        // any GroupInfo
        // extension type it cannot name (MLS-EXT-UNKNOWN), which is how the
        // group_metadata_keys_requested code point will be read off real traffic rather than guessed.
        if (intent.getBooleanExtra("maintenance", false)) {
            final Context appCtxM = context.getApplicationContext();
            final int mSubIdM = subId;
            final String mTo = to;
            final String mGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS MAINTENANCE(" + mTo + ") → "
                            + MlsProviderTransport.get(appCtxM, mSubIdM)
                                    .runMaintenance(mGid, mTo, "debug lever"));
                }
            }, "mls-maintenance").start();
            return;
        }
        if (intent.getBooleanExtra("records", false)) {
            final Context appCtxR = context.getApplicationContext();
            final int rSubId = subId;
            new Thread(new Runnable() {
                @Override public void run() {
                    for (final String line : MlsProviderTransport.get(appCtxR, rSubId)
                            .dumpRecords().split("\n")) {
                        LogUtil.i(TAG, "MLS-RECORDS " + line);
                    }
                }
            }, "mls-record-dump").start();
            return;
        }

        // --ez sendkeys true --es rcsgid <id> → RCC.16 §10.5.3 / the v3.0 one-shot: send this
        // group's metadata keys (and, if we hold one, the continuity token) as an encrypted
        // PrivateMessage.
        //
        // Exists as a probe because the AUTOMATIC triggers differ by revision and neither is easy
        // to provoke on demand: under v3.0 the trigger is a peer external-committing into the group,
        // under v4.0 it is a peer setting 0xF007. This drives the send directly so the wire shape
        // and the receiver's handling can be checked without staging a self-heal.
        if (intent.getBooleanExtra("sendkeys", false)) {
            final Context appCtxK = context.getApplicationContext();
            final int kSubId = subId;
            final String kGid = intent.getStringExtra("rcsgid");
            // --es token <hex> supplies a SYNTHETIC continuity token so the wire path can be
            // exercised end to end. Without it the call correctly refuses (nothing to send), which
            // proves the guard but not the transport — and the transport is the half that can only
            // be checked against a real peer.
            final String kTokenHex = intent.getStringExtra("token");
            final byte[] kToken = hexToBytes(kTokenHex);
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean sent = MlsProviderTransport.get(appCtxK, kSubId)
                            .sendGroupMetadataKeys(kGid, null, kToken);
                    LogUtil.i(TAG, "MLS-SENDKEYS rcsgid=" + kGid + " token="
                            + (kToken == null ? 0 : kToken.length) + "B sent=" + sent
                            + " (false with no key material is CORRECT — an empty "
                            + "GroupMetadataKeys is not worth a message)");
                }
            }, "mls-sendkeys").start();
            return;
        }

        // --ez groupexts true --es rcsgid <id> --es to <peer> → dump which RCC.16 GroupContext
        // extensions this group actually carries, LOCALLY and on the SERVER's GroupInfo.
        //
        // Exists to answer one question with data instead of theory: do we ever RECEIVE a continuity
        // token? (Written as "0xF011"; v4.0 §7.11.12 assigns 0xF010 to the token and 0xF011 to its
        // commitment — the pair was reversed.) One reading is that a successor era is chained to its
        // predecessor through that token and that we drop it, which would explain an era advance the server
        // accepts without moving the era. But carrying a token we are never sent is not a fix, so
        // the presence check comes first.
        // --ez continuity true --es rcsgid <id> --es to <peer> [--es token <hex>]
        // → RCC.16 §7.11.12.1 / §4.8 row 13. Without `token`: READ — do we hold a
        // continuity token for this conversation, and how long is it. With `token`: inject those
        // bytes through the SAME write path both real arrival routes use, then read them back.
        //
        // WHY AN INJECT EXISTS AT ALL, since a debug arm that manufactures its own input is usually
        // a way to prove nothing. The route a peer actually uses cannot be driven from a device we
        // own: a Welcome carrying 0xF010 has to be BUILT by a client that mints a token, we mint
        // none, and whether we ever should is still an open question. So the CAPTURE half is
        // proven end to end in the Rust host tests — which play the peer through mls-rs's
        // set_group_info_ext — and this proves the half that only real hardware can: that the token
        // reaches durable storage and is still there after the process dies.
        //
        // LOCAL ONLY. It transmits nothing and changes no byte on any wire, which is why it is not
        // in STATE_CHANGING. The value is echoed back as a LENGTH and a match/no-match, never as
        // bytes — the token is a 256-bit group secret and this lands in logcat.
        if (intent.getBooleanExtra("continuity", false)) {
            final Context appCtxC = context.getApplicationContext();
            final int cSubId = subId;
            final String cGid = intent.getStringExtra("rcsgid");
            final String cTo = to;
            final String cHex = intent.getStringExtra("token");
            new Thread(new Runnable() {
                @Override public void run() {
                    byte[] tok = null;
                    if (cHex != null && !cHex.isEmpty()) {
                        tok = unhex(cHex);
                        if (tok == null) {
                            LogUtil.e(TAG, "MLS-CONTINUITY: --es token must be an even number of "
                                    + "hex digits; got " + cHex.length() + " character(s)");
                            return;
                        }
                    }
                    LogUtil.i(TAG, "MLS-CONTINUITY " + MlsProviderTransport.get(appCtxC, cSubId)
                            .debugInjectContinuityToken(cGid, cTo, tok));
                }
            }, "mls-continuity").start();
            return;
        }

        if (intent.getBooleanExtra("groupexts", false)) {
            final Context appCtxX = context.getApplicationContext();
            final int xSubId = subId;
            final String xGid = intent.getStringExtra("rcsgid");
            final String xTo = to;
            new Thread(new Runnable() {
                @Override public void run() {
                    MlsProviderTransport.get(appCtxX, xSubId).dumpGroupExtensions(xGid, xTo);
                }
            }, "mls-group-exts").start();
            return;
        }

        // --ez ftdfail true --es rcsgid <id> --es to <peer> [--es mid <messageId>] → exercise the
        // RCC.16 §10 EMIT half: pretend a message from <peer> failed to decrypt. Runs the real
        // recovery (self-heal, convergence check, then the §7.7.2.2 negative-delivery IMDN if the
        // failure persists), so a real report goes out on the wire to <peer>.
        //
        // Exists because the natural trigger — an actual undecryptable message — needs a genuinely
        // diverged pair, which is slow to arrange and hard to arrange REPEATABLY. This makes both
        // halves of the flow testable independently of how the divergence was caused.
        // --ez badpayload true --es rcsgid <id> [--es mode over|under|trunc|container] [--es body <t>]
        // → send a group message whose INNER custom payload is deliberately malformed while the MLS
        // layer stays perfectly valid. This is the recipe for making a Google Messages peer EMIT a
        // negative delivery receipt: a clean decrypt whose inner payload will not deserialise,
        // with no health status, takes the FAIL_NO_RETRY branch that IS the emit gate.
        //
        // We have never once seen a real negative receipt, so our entire §7.7.2.2 receive half is
        // tested only against our own emitter — against our own reading of the spec. This is the
        // probe that would give us the reference artefact.
        //
        // Aim it at a group we own on both ends: it sends a message the peer cannot read.
        // --ez forgetresends true --es rcsgid <id> --es to <peer>
        // → drop the resend-ledger rows for one conversation.
        //
        // The ledger is DURABLE by design: counting rows rather than holding a number is what stops
        // a diverged peer getting a fresh resend budget on every restart. The cost of that is that a
        // burst of junk rows suppresses legitimate resends in that conversation FOREVER — which is
        // not hypothetical: an uncapped resend loop (fixed same day) wrote 82 rows against one peer
        // in about forty seconds, and every real resend in that conversation was capped afterwards.
        //
        // This is an INSTRUMENT for clearing that residue, not a remedy. Production drops rows on
        // the two terminal outcomes it should: delivered (forgetChain) and escalation-repaired
        // (forgetConversation).
        if (intent.getBooleanExtra("forgetresends", false)) {
            final Context appCtxF = context.getApplicationContext();
            final int fSubId = subId;
            final String fGid = intent.getStringExtra("rcsgid");
            final String fTo = to;
            new Thread(new Runnable() {
                @Override public void run() {
                    final int n = MlsProviderTransport.get(appCtxF, fSubId)
                            .forgetResendHistory(fGid, fTo);
                    LogUtil.i(TAG, "DEBUG MLS FORGETRESENDS: dropped " + n + " resend row(s) for "
                            + (fGid != null ? fGid : fTo));
                }
            }, "mls-forget-resends").start();
            return;
        }

        // --es displayreceipt-mid <messageId> --es to <peer> [--es rcsgid <group>]
        // → emit a DISPLAYED IMDN (read receipt) for <messageId> to <peer>. The app normally
        // sends this on read; this lets a test send it on demand BEFORE forcing an FTD, to probe
        // whether a positive-DISPLAYED signal is the persist-trigger Google Messages' resend-prep needs.
        if (intent.getStringExtra("displayreceipt-mid") != null) {
            final Context appCtxDr = context.getApplicationContext();
            final String drMid = intent.getStringExtra("displayreceipt-mid");
            final String drTo = to;
            final String drGid = intent.getStringExtra("rcsgid"); // null for 1:1
            if (drTo == null) {
                LogUtil.w(TAG, "DEBUG MLS DISPLAYRECEIPT needs --es to <peer>");
                return;
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    ProviderTransport.getInstance(appCtxDr).sendImdn(drMid, drTo,
                            org.lineageos.rcs.provider.IRcsProviderCallback.IMDN_DISPLAYED, drGid);
                    LogUtil.i(TAG, "DEBUG MLS DISPLAYRECEIPT: sent DISPLAYED for " + drMid
                            + " to " + drTo + (drGid != null ? " grp=" + drGid : ""));
                }
            }, "mls-display-receipt").start();
            return;
        }

        if (intent.getBooleanExtra("badpayload", false)) {
            final Context appCtxB = context.getApplicationContext();
            final int bSubId = subId;
            final String bGid = intent.getStringExtra("rcsgid");
            final String bBody = intent.getStringExtra("body") != null
                    ? intent.getStringExtra("body") : "malformed-payload-probe";
            final MlsPayloadCorruptor.Mode bMode =
                    MlsPayloadCorruptor.modeOf(intent.getStringExtra("mode"));
            if (bGid == null) {
                LogUtil.w(TAG, "DEBUG MLS BADPAYLOAD needs --es rcsgid <group>");
                return;
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean ok = MlsProviderTransport.get(appCtxB, bSubId)
                            .sendMalformedPayloadToGroup(bGid, bBody, bMode);
                    LogUtil.i(TAG, "DEBUG MLS BADPAYLOAD (" + bMode + ") sent=" + ok
                            + " — now watch the PEER for a negative delivery receipt");
                }
            }, "mls-bad-payload").start();
            return;
        }

        if (intent.getBooleanExtra("ftdfail", false)) {
            final Context appCtxD = context.getApplicationContext();
            final int dSubId = subId;
            final String dGid = intent.getStringExtra("rcsgid");
            final String dTo = to;
            final String dMid = intent.getStringExtra("mid") != null
                    ? intent.getStringExtra("mid")
                    : "debug-ftd-" + System.currentTimeMillis();
            // --es reason N → report N instead of 4 (failed-to-decrypt), for THIS report only.
            //
            // A one-message probe. The reading of Google Messages this tests is that the
            // UNABLE_TO_DESERIALIZE_CUSTOM_PAYLOAD(51) we keep drawing is reached only on reason 4,
            // because 4 is what starts resend-preparation and the deserialise that fails is of
            // Google Messages' OWN stored state. Same receipt, reason 1, tells us which: 51 gone means our
            // bytes were never the problem; 51 still there means the theory is dead.
            final int dReason = intent.getStringExtra("reason") == null
                    ? 0 : Integer.parseInt(intent.getStringExtra("reason"));
            new Thread(new Runnable() {
                @Override public void run() {
                    if (dReason > 0) {
                        MlsProviderTransport.setNextFtdReason(dReason);
                        LogUtil.w(TAG, "DEBUG MLS FTDFAIL: reason OVERRIDDEN to " + dReason
                                + " for this report only — the receipt will not describe what "
                                + "actually happened. Experiment use only.");
                    }
                    LogUtil.i(TAG, "DEBUG MLS FTDFAIL: simulating a decrypt failure of " + dMid
                            + " from " + dTo + " in group " + dGid);
                    // --ez noheal true → emit the report WITHOUT running recovery first.
                    //
                    // For that discriminating experiment only: it needs the SAME message id
                    // reported 3x a minute apart, and each production report drags a full self-heal
                    // with it. On 2026-07-31 that self-heal era-advanced a working Google Messages-peer
                    // conversation, FAILED, and dropped local state — so three of them would destroy
                    // the conversation the experiment needs AND confound the repetition variable
                    // with three recovery attempts.
                    if (intent.getBooleanExtra("noheal", false)) {
                        MlsProviderTransport.get(appCtxD, dSubId)
                                .reportFtdWithoutHealing(dGid, dTo, dMid);
                    } else {
                        MlsProviderTransport.get(appCtxD, dSubId).onDecryptFailure(dGid, dTo, dMid);
                    }
                    LogUtil.i(TAG, "DEBUG MLS FTDFAIL done — see the §10 recovery lines above");
                }
            }, "mls-ftd-fail").start();
            return;
        }

        // --ez ftdreport true --es rcsgid <id> --es to <peer> --es mid <messageId> [--es reason N]
        // → exercise the RCC.16 §10 HANDLE half: pretend <peer> told us OUR message <messageId>
        // failed on its side. Runs the real remedy, so this actually rekeys/resends or era-advances.
        //
        // reason is the §7.6.3.2 code (default 4 = failed-to-decrypt): 1 message-from-non-member,
        // 2 invalid-credential, 3 invalid-commit, 4 failed-to-decrypt, 5 commit-in-privatemessage.
        // Each takes a DIFFERENT branch, so pass it explicitly to test escalation vs resend.
        if (intent.getBooleanExtra("ftdreport", false)) {
            final Context appCtxR = context.getApplicationContext();
            final int rSubId = subId;
            final String rGid = intent.getStringExtra("rcsgid");
            final String rTo = to;
            final String rMid = intent.getStringExtra("mid");
            final int rReason = intent.getStringExtra("reason") == null
                    ? 4 : Integer.parseInt(intent.getStringExtra("reason"));
            if (rMid == null) {
                LogUtil.w(TAG, "DEBUG MLS FTDREPORT needs --es mid <messageId> (the message of OURS "
                        + "the peer claims failed); without it there is nothing to resend");
                return;
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS FTDREPORT: simulating " + rTo + " reporting our "
                            + rMid + " failed, reason=" + rReason);
                    MlsProviderTransport.get(appCtxR, rSubId)
                            .onPeerReportedFailure(rGid, rTo, rMid, rReason);
                    LogUtil.i(TAG, "DEBUG MLS FTDREPORT done — see the remedy lines above");
                }
            }, "mls-ftd-report").start();
            return;
        }

        // --ez forgetgroup true --es rcsgid <id> --es to <peer> → drop ALL local MLS state for a
        // conversation so the next Welcome is taken as a fresh join. Recovery lever for a device
        // whose engine state has diverged from the server past what a re-join guard will accept.
        if (intent.getBooleanExtra("forgetgroup", false)) {
            final Context appCtxF = context.getApplicationContext();
            final int fSubId = subId;
            final String fGid = intent.getStringExtra("rcsgid");
            final String fTo = to;
            // --ez deep true ALSO drops the PROVIDER's peer->group record (contract v55
            // mlsForgetConversation). Without it forget() clears the app half only, so the next send
            // RE-ESTABLISHES the same group id via an era advance instead of starting fresh — which
            // is useless when the reason you are forgetting is that the server's era is somewhere our
            // side can no longer reach. Device-proven 2026-08-09: after an app-only forget, the
            // device rebuilt at era=1 while the server held era=7, and every send stayed
            // INVALID_ARGUMENT.
            final boolean fDeep = intent.getBooleanExtra("deep", false);
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean ok = MlsProviderTransport.get(appCtxF, fSubId).forget(fGid, fTo);
                    boolean provOk = false;
                    if (fDeep) {
                        provOk = ProviderTransport.getInstance(appCtxF)
                                .mlsForgetConversation(fSubId, fTo);
                    }
                    LogUtil.i(TAG, "DEBUG MLS FORGETGROUP(" + fGid + ") → app=" + ok
                            + (fDeep ? (" provider=" + provOk + " (DEEP: both halves dropped)")
                                     : " (app half only — pass --ez deep true for the provider half)"));
                }
            }, "mls-forget-group").start();
            return;
        }

        // --ez servera true --es rcsgid <id> [--es to <peer>] → ask the SERVER what era/epoch
        // it holds for this conversation, and print it next to ours. The reconciliation we never do
        // automatically: drift is otherwise only discovered by a send being refused.
        // --ez pkeys true --es rcsgid <id> --es to <peer> → dump per-leaf (index, MSISDN,
        // participant-key) for the group. READ-ONLY: it is the input MlsParticipantKeyResync.plan()
        // needs, printed so the data can be inspected before any policy acts on it.
        // Removing a member on this metadata is unrecoverable for them, so it gets looked at first.
        if (intent.getBooleanExtra("pkeys", false)) {
            final Context appCtxPk = context.getApplicationContext();
            final int pkSubId = subId;
            final String pkGid = intent.getStringExtra("rcsgid");
            final String pkTo = to;
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS PKEYS(" + pkGid + ") → "
                            + MlsProviderTransport.get(appCtxPk, pkSubId)
                                    .dumpMemberParticipantKeys(pkGid, pkTo));
                }
            }, "mls-pkeys").start();
            return;
        }

        if (intent.getBooleanExtra("servera", false)) {
            final Context appCtxS2 = context.getApplicationContext();
            final int sSubId2 = subId;
            final String sGid2 = intent.getStringExtra("rcsgid");
            final String sTo2 = to;
            new Thread(new Runnable() {
                @Override public void run() {
                    // THROUGH MlsProviderTransport, not around it. This used to reach
                    // ProviderTransport directly, which put a door to the GetMlsGroupInfo resource
                    // OUTSIDE the class that owns the resource — invisible to any enumeration of
                    // MlsProviderTransport, which is where every inventory of these doors has
                    // looked. It is still exempt from the ledger; it is no longer unaccounted.
                    final long[] srv = MlsProviderTransport.get(appCtxS2, sSubId2)
                            .debugServerEraEpoch(sGid2, sTo2);
                    final int ours = MlsProviderTransport.get(appCtxS2, sSubId2)
                            .localEra(sGid2, sTo2);
                    // READ-ONLY probe: fetchMissedCommits logs "server anchor auth vs ours", which
                    // is the only comparison that tests STATE rather than era/epoch NUMBERS —
                    // today proved matching numbers do NOT mean matching state.
                    final MlsProviderTransport.AnchorProbe probe =
                            MlsProviderTransport.get(appCtxS2, sSubId2).probeAnchor(sGid2, sTo2);
                    LogUtil.i(TAG, "DEBUG MLS SERVERERA(" + sGid2 + ") server="
                            + (srv == null ? "null" : ("era=" + srv[0] + " epoch=" + srv[1]))
                            + " ours=era=" + ours + " anchorProbe=" + probe);
                }
            }, "mls-server-era").start();
            return;
        }

        // --ez eraadvance true --es rcsgid <id> --es to <peer> → advance the era: re-create the group
        // at era+1 and Welcome every member. This is the ONLY recovery for a member that missed a
        // Welcome, because an era gap cannot be crossed by external commit (Tachyon refuses it).
        //
        // --ez requirefresh true adds a ROSTER PRE-FLIGHT: claim one KeyPackage from every
        // member, measure the certificate of the exact package that would become that member's leaf,
        // and REFUSE naming anyone whose published pool has not turned over — instead of burning an
        // era to rebuild a group around stale leaves.
        //
        // WHY IT IS A FLAG AND NOT THE DEFAULT HERE. `floorRebuild` passes it always, because it
        // only runs on a group the floor has already wedged. This lever is also the repair for a
        // group that is merely FORKED — no member inside the floor, nothing to pre-flight — and
        // there the claims cost one package per member for an answer nobody needed. Ask for it when
        // the point of the advance is the CERTIFICATES rather than the epoch.
        if (intent.getBooleanExtra("eraadvance", false)) {
            final Context appCtxA = context.getApplicationContext();
            final int aSubId2 = subId;
            final String aGid2 = intent.getStringExtra("rcsgid");
            final String aTo2 = to;
            final boolean aFresh = intent.getBooleanExtra("requirefresh", false);
            new Thread(new Runnable() {
                @Override public void run() {
                    final int era = aFresh
                            ? MlsProviderTransport.get(appCtxA, aSubId2).eraAdvance(aGid2, aTo2,
                                    /*carryGroupInfo=*/ null, MlsAdvanceEraKind.NORMAL,
                                    /*requireRebuildableRoster=*/ true)
                            : MlsProviderTransport.get(appCtxA, aSubId2).eraAdvance(aGid2, aTo2);
                    LogUtil.i(TAG, "DEBUG MLS ERAADVANCE(" + aGid2 + ")"
                            + (aFresh ? " requireFreshRoster" : "") + " → era=" + era
                            + (era == MlsProviderTransport.ERA_ADVANCE_ROSTER_NOT_READY
                                    ? " (a member's published KeyPackage is still stale — see the "
                                            + "named list above; nothing was advanced)"
                                    : ""));
                }
            }, "mls-era-advance").start();
            return;
        }

        // --ez floorrebuild true --es to <E164> [--es rcsgid <gid>] → REPAIR a group that RCC.16's
        // 30-day remaining-lifetime floor has wedged, by era-advancing it around freshly claimed
        // KeyPackages.
        //
        // THIS IS THE LEVER, AND THE LEVER IS THE PRODUCT. The automatic arm is behind
        // debug.rcs.mls_floor_rebuild and OFF by default, because a rebuild re-Welcomes every
        // member — third parties included — and that is a decision per conversation rather than a
        // fleet default. State-changing and NEEDS_TO, so it passes the same lab-peer gate every
        // other era advance does.
        //
        // It REFUSES and names the members whose published KeyPackage still carries a stale
        // certificate, rather than burning an era on a rebuild that would re-create the wedge. Grep
        // the log for "FLOOR REBUILD"; unlike the automatic arm this ignores the once-per-mint
        // marker, so an operator who knows a peer has just republished can retry immediately.
        if (intent.getBooleanExtra("floorrebuild", false)) {
            final Context appCtxFr = context.getApplicationContext();
            final int frSubId = subId;
            final String frGid = intent.getStringExtra("rcsgid");
            final String frTo = to;
            new Thread(new Runnable() {
                @Override public void run() {
                    final int era = MlsProviderTransport.get(appCtxFr, frSubId)
                            .debugFloorRebuild(frGid, frTo);
                    LogUtil.i(TAG, "DEBUG MLS FLOORREBUILD(" + frGid + ") → era=" + era
                            + (era == MlsProviderTransport.ERA_ADVANCE_ROSTER_NOT_READY
                                    ? " (a member has not republished — see the named list above)"
                                    : ""));
                }
            }, "mls-floor-rebuild").start();
            return;
        }

        // --es establishgroup <csv of members> --es rcsgid <id> → CREATE the MLS group for an RCS
        // group we own (the counterpart to the join path). The RCS group must already exist with
        // those members — this only builds the MLS half.
        if (intent.getStringExtra("establishgroup") != null) {
            final Context appCtxE = context.getApplicationContext();
            final int eSubId = subId;
            final String eGid = intent.getStringExtra("rcsgid");
            final String eCsv = intent.getStringExtra("establishgroup");
            new Thread(new Runnable() {
                @Override public void run() {
                    final java.util.List<String> mem = new java.util.ArrayList<>();
                    for (final String m : eCsv.split(",")) {
                        final String t = m.trim();
                        if (!t.isEmpty()) mem.add(t);
                    }
                    final int era = MlsProviderTransport.get(appCtxE, eSubId)
                            .establishGroup(eGid, mem);
                    LogUtil.i(TAG, "DEBUG MLS ESTABLISHGROUP(" + eGid + ") members=" + mem
                            + " → era=" + era);
                }
            }, "mls-establish-group").start();
            return;
        }

        // --ez groupsend true --es rcsgid <id> --es body <text> → app-sealed MLS into an RCS group.
        // Add --ez corruptct true to flip a byte in the SEALED ciphertext of this one send: the
        // peer's AEAD then FAILS (a recoverable decrypt-miss) while our stored plaintext body stays
        // intact, so the peer FTDs it and our RESEND is readable — the reverse-resend capstone.
        if (intent.getBooleanExtra("groupsend", false)) {
            final Context appCtx8 = context.getApplicationContext();
            final int gSubId = subId;
            final String gGid = intent.getStringExtra("rcsgid");
            final String gBody = body;
            final boolean gCorrupt = intent.getBooleanExtra("corruptct", false);
            final String gMsgId = intent.getStringExtra("msgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    if (gCorrupt) {
                        MlsProviderTransport.setCorruptNextCt();
                        LogUtil.w(TAG, "DEBUG MLS GROUPSEND: arming POST-SEAL CT CORRUPTION for this "
                                + "send — peer AEAD will fail, our resend will be readable (capstone)");
                    }
                    // --es msgid threads a REAL rcs_message_id through, which is what makes the
                    // send cacheable (invariant 62). Without it sendToGroup synthesises a
                    // per-call id and the sealed-ciphertext cache is deliberately bypassed, so a
                    // group RESEND/replay could not be exercised from adb at all.
                    final boolean ok = MlsProviderTransport.get(appCtx8, gSubId)
                            .sendToGroup(gGid, gBody, gMsgId);
                    LogUtil.i(TAG, "DEBUG MLS GROUPSEND(" + gGid + ") → " + ok
                            + (gMsgId == null ? " (no msgid: not cacheable)" : " msgid=" + gMsgId));
                }
            }, "mls-group-send").start();
            return;
        }

        // --ez health true → detect (and optionally reconcile) local-vs-server divergence.
        if (intent.getBooleanExtra("health", false)) {
            final Context appCtx7 = context.getApplicationContext();
            final int hSubId = subId;
            final String hTo = to;
            final String hGid = intent.getStringExtra("rcsgid");
            final boolean fix = intent.getBooleanExtra("fix", false);
            final boolean intent2Advance = intent.getBooleanExtra("eraadvance", false);
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtx7, hSubId);
                    final String extra = fix ? (" reconcile=" + t.reconcile(hGid, hTo)) : "";
                    final String adv = intent2Advance ? (" eraAdvance=" + t.eraAdvance(hGid, hTo)) : "";
                    // --ez drive true → run the bounded fixed point (rework 4.3) over the SAME
                    // entry point onControlRefused uses, so what this prints is the production
                    // loop's behaviour and not a debug re-implementation of it. Prints the typed
                    // outcome, the pass count and whether the cap was reached — the last of which
                    // should be false forever.
                    String driven = "";
                    if (intent.getBooleanExtra("drive", false)) {
                        final com.android.messaging.rcs.engine.mls.MlsDriveLoop.Result r =
                                t.driveReconcile(hGid, hTo);
                        // noprogress/inert are printed alongside capped because they are the two
                        // ways the loop now stops EARLY, and telling them apart is the whole point
                        // of the distinction: noprogress=true is the fault (a pass reported work while
                        // nothing moved), inert=true is the fix working (a pass said it changed
                        // nothing and was believed). capped=true should now be unreachable from
                        // here — if it is ever true again, a producer found a way to alternate.
                        driven = " drive={status=" + r.status() + " passes=" + r.iterations
                                + " capped=" + r.cappedOut
                                + " noprogress=" + r.stoppedWithoutProgress
                                + " inert=" + r.stoppedDeclaredInert
                                + " redrive=" + r.action.redrive
                                + " action=" + r.action.kind
                                + " reason=\"" + r.action.reason + "\"}";
                    }
                    LogUtil.i(TAG, "DEBUG MLS HEALTH(" + hTo + ") → " + t.detectHealth(
                            com.android.messaging.rcs.engine.mls.MlsFetchLedger.Caller.DEBUG_HEALTH,
                            hGid, hTo)
                            + extra + adv + driven);
                }
            }, "mls-health").start();
            return;
        }

        // --ez procresults true [--es rcsgid <id>] [--es to <peer>] [--es ctx <id>] [--es hex <bytes>]
        // → drive one wire blob through the REPEATED-RESULT engine path and demux it (rework 6.6).
        // With no --es hex it feeds deliberate garbage, which the engine answers MALFORMED — that
        // still crosses Java → JNI → Rust → decode → demux, so it proves the whole chain with no
        // peer, no group and no live conversation.
        if (intent.getBooleanExtra("procresults", false)) {
            final Context appCtxP = context.getApplicationContext();
            final int pSubId = subId;
            final String pGid = intent.getStringExtra("rcsgid");
            final String pTo = to;
            final String pCtx = intent.getStringExtra("ctx") == null
                    ? "probe-ctx" : intent.getStringExtra("ctx");
            final String pHex = intent.getStringExtra("hex");
            new Thread(new Runnable() {
                @Override public void run() {
                    byte[] wire = new byte[] { (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF };
                    if (pHex != null && pHex.length() >= 2 && pHex.length() % 2 == 0) {
                        wire = new byte[pHex.length() / 2];
                        for (int i = 0; i < wire.length; i++) {
                            wire[i] = (byte) Integer.parseInt(
                                    pHex.substring(i * 2, i * 2 + 2), 16);
                        }
                    }
                    LogUtil.i(TAG, "DEBUG MLS PROCRESULTS(" + wire.length + "B ctx=" + pCtx + ") → "
                            + MlsProviderTransport.get(appCtxP, pSubId)
                                    .probeProcessResults(pGid, pTo, wire, pCtx));
                }
            }, "mls-procresults").start();
            return;
        }

        // --ez leave true | --ez rmmember true | --ez forget true → membership ops.
        if (intent.getBooleanExtra("leave", false) || intent.getBooleanExtra("rmmember", false)
                || intent.getBooleanExtra("forget", false)) {
            final Context appCtx6 = context.getApplicationContext();
            final int mSubId2 = subId;
            final String mTo = to;
            final String rcsgid = intent.getStringExtra("rcsgid");
            final boolean doLeave = intent.getBooleanExtra("leave", false);
            final boolean doForget = intent.getBooleanExtra("forget", false);
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtx6, mSubId2);
                    final String r;
                    if (doForget)      r = "forget=" + t.forget(rcsgid, mTo);
                    else if (doLeave)  r = "leave=" + t.leave(rcsgid, mTo);
                    else               r = "removeMember era=" + t.removeMember(rcsgid, mTo, new byte[0]);
                    LogUtil.i(TAG, "DEBUG MLS MEMBERSHIP(" + mTo + ") → " + r);
                }
            }, "mls-membership").start();
            return;
        }

        // --ez servremove true [--es rcsgid <id>] → the client action RCC.16 requires on a server
        // NOTIFY: commit the removal of the named participant's clients.
        if (intent.getBooleanExtra("servremove", false)) {
            final Context appCtxB = context.getApplicationContext();
            final int sSubId = subId;
            final String sTo = to;
            final String sGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS SERVREMOVE(" + sTo + ") → era="
                            + MlsProviderTransport.get(appCtxB, sSubId)
                                    .removeOnServerNotify(sGid, sTo, new byte[0]));
                }
            }, "mls-server-remove").start();
            return;
        }

        // --ez imdnsig true [--es rcsgid <id>] → sign an IMDN, verify it back, and prove a
        // tampered statement is rejected even though the signature is valid.
        if (intent.getBooleanExtra("imdnsig", false)) {
            final Context appCtxD = context.getApplicationContext();
            final int dSubId = subId;
            final String dTo = to;
            final String dGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtxD, dSubId);
                    final String mid = "MxImdnTest" + (System.currentTimeMillis() % 100000);
                    final String sig = t.signImdn(dGid, dTo,
                            java.util.UUID.randomUUID().toString(), mid, /*displayed=*/ false,
                            VerifiableDerivedContent.DELIVERY_DELIVERED,
                            VerifiableDerivedContent.FAILURE_UNSET);
                    LogUtil.i(TAG, "DEBUG MLS IMDNSIG sign → " + (sig == null ? "null"
                            : sig.length() + " b64 chars"));
                    if (sig == null) return;
                    LogUtil.i(TAG, "DEBUG MLS IMDNSIG verify(correct) → " + t.verifyImdn(dGid, dTo,
                            sig, mid, false, VerifiableDerivedContent.DELIVERY_DELIVERED,
                            VerifiableDerivedContent.FAILURE_UNSET));
                    // Same signature, different claimed statement: signature valid, content must NOT match.
                    LogUtil.i(TAG, "DEBUG MLS IMDNSIG verify(tampered-status) → " + t.verifyImdn(dGid,
                            dTo, sig, mid, false, VerifiableDerivedContent.DELIVERY_FAILED,
                            VerifiableDerivedContent.FAILURE_UNSET));
                    LogUtil.i(TAG, "DEBUG MLS IMDNSIG verify(tampered-msgid) → " + t.verifyImdn(dGid,
                            dTo, sig, mid + "X", false, VerifiableDerivedContent.DELIVERY_DELIVERED,
                            VerifiableDerivedContent.FAILURE_UNSET));
                }
            }, "mls-imdn-sig").start();
            return;
        }

        // --ez iconsubj true [--es icon <text>] [--es subject <text>] [--es rcsgid <id>]
        // → commit Annex C.1 commitments, then verify them back.
        if (intent.getBooleanExtra("iconsubj", false)) {
            final Context appCtxC = context.getApplicationContext();
            final int iSubId = subId;
            final String iTo = to;
            final String iGid = intent.getStringExtra("rcsgid");
            final String iconTxt = intent.getStringExtra("icon");
            final String subjTxt = intent.getStringExtra("subject");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtxC, iSubId);
                    // KEY MATERIAL, not the text. publishIconSubject commits over the KEY, the
                    // same preimage verifyIconSubject checks — it used to take the
                    // ciphertext, and this hook used to hand it the plaintext, so the commitment
                    // it published could never validate anywhere.
                    //
                    // THE MISMATCH WAS ALREADY DOCUMENTED ON THIS LINE and worked around instead
                    // of fixed: the note that stood here said the verify parameter "is now the KEY
                    // MATERIAL (the sender commits to the key, not the ciphertext)" and dropped
                    // the verify calls for that reason — stating the correct rule immediately
                    // above a call that broke it. The rule was right; the publisher was wrong.
                    //
                    // A fresh random key per arm. The point of this hook is the SERVER's verdict
                    // on a commitment published with no content and no key delivery, which does
                    // not depend on the key's value — and minting one keeps the commitment
                    // well-formed, so an acceptance would be useless rather than harmful.
                    final byte[] ic = iconTxt == null ? null : RccFileCrypto.newKey();
                    final byte[] sc = subjTxt == null ? null : RccFileCrypto.newKey();
                    final int era = t.publishIconSubject(iGid, iTo, ic, sc);
                    // The verify half is exercised for real on the receive side (openIconSubject),
                    // where the key arrives in the §7.8.1 FileInfo. It cannot be exercised here:
                    // this hook publishes a commitment to a key it then throws away, which is the
                    // whole point of a negative control.
                    LogUtil.i(TAG, "DEBUG MLS ICONSUBJ (negative control: commitment only, no "
                            + "content, no key delivery — expect a refusal) → era=" + era);
                }
            }, "mls-icon-subject").start();
            return;
        }

        // --ez endmls true [--ez resume true] [--es rcsgid <id>] [--es reason <NAME>] → move a
        // conversation unencrypted (RCC.16 §7.11.2.2), or back to encrypted with resume.
        //
        // `reason` names an MlsDowngradeReason (default DEBUG_MENU). It is not decoration: the
        // reason's seven columns decide whether a commit is generated at all (y), whether the app's
        // own bit is cleared eagerly (z), and — via w — whether this conversation is ever eligible
        // to come back. Passing a reason is how the four §9.7h arms are told apart in a trace diff.
        if (intent.getBooleanExtra("endmls", false)) {
            final Context appCtxA = context.getApplicationContext();
            final int eSubId = subId;
            final String eTo = to;
            final String eGid = intent.getStringExtra("rcsgid");
            final boolean resume = intent.getBooleanExtra("resume", false);
            final String reasonName = intent.getStringExtra("reason");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtxA, eSubId);
                    com.android.messaging.rcs.engine.mls.MlsDowngradeReason reason =
                            com.android.messaging.rcs.engine.mls.MlsDowngradeReason.DEBUG_MENU;
                    if (reasonName != null && !reasonName.isEmpty()) {
                        try {
                            reason = com.android.messaging.rcs.engine.mls.MlsDowngradeReason
                                    .valueOf(reasonName);
                        } catch (final IllegalArgumentException bad) {
                            LogUtil.w(TAG, "DEBUG MLS ENDMLS: no such downgrade reason '"
                                    + reasonName + "' — using DEBUG_MENU. Valid names are the 21 in "
                                    + "MlsDowngradeReason (§9.7a).");
                        }
                    }
                    final int era = t.endMls(eGid, eTo, resume, reason);
                    LogUtil.i(TAG, "DEBUG MLS " + (resume ? "RESUME" : "ENDMLS") + "(" + eTo
                            + ", reason=" + reason + ") → era=" + era
                            + " endMlsNow=" + t.isEndMls(eGid, eTo));
                }
            }, "mls-end-mls").start();
            return;
        }

        // --ez phoenix true [--es rcsgid <id>] → initiate PHOENIX MODE (§9.7g): an era advance whose
        // new era is BORN DOWNGRADED. The way out of a wedged encrypted state when the ordinary
        // end_mls commit cannot be made to land.
        //
        // Google Messages treats a Phoenix request as a BUG SIGNAL, not a feature — it raises the
        // user-facing "please file feedback" prompt unconditionally on this arm. Do not reach for
        // this probe before the ordinary --ez endmls path has been shown to fail.
        if (intent.getBooleanExtra("phoenix", false)) {
            final Context appCtxP = context.getApplicationContext();
            final int pSubId = subId;
            final String pTo = to;
            final String pGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtxP, pSubId);
                    final int era = t.initiatePhoenixMode(pGid, pTo, "debug probe");
                    LogUtil.i(TAG, "DEBUG MLS PHOENIX(" + pTo + ") → era=" + era
                            + " endMlsNow=" + t.isEndMls(pGid, pTo));
                }
            }, "mls-phoenix").start();
            return;
        }

        // --ez seedusage true [--es rcsgid <id>] → seed the usage counter so the NEXT send rekeys.
        if (intent.getBooleanExtra("seedusage", false)) {
            final Context appCtx9 = context.getApplicationContext();
            final int uSubId = subId;
            final String uTo = to;
            final String uGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS SEEDUSAGE → " + MlsProviderTransport
                            .get(appCtx9, uSubId).seedUsageCounter(uGid, uTo));
                }
            }, "mls-seed-usage").start();
            return;
        }

        // --es claimkp <e164> → claim a PEER's KeyPackages and report HOW MANY came back.
        //
        // The contract-v48 plural claim returns one KeyPackage PER DEVICE, and
        // "a two-device participant must get TWO leaves" cannot be checked without seeing the count.
        // The only production caller is establishGroup, which needs the members to already be RCS
        // participants of the group — so measuring the count that way costs a whole group setup and
        // conflates a claim result with a membership failure. This asks the one question directly.
        //
        // It CONSUMES one-time KeyPackages from the peer's pool, which is why it names the peer
        // explicitly and is not wired into anything automatic. Against a Google Messages capture line that is
        // a read of their pool, not a write to their device — nothing is installed and no group is
        // created.
        if (intent.getStringExtra("claimkp") != null) {
            final Context appCtxCk = context.getApplicationContext();
            final int ckSub = subId;
            final String peer = intent.getStringExtra("claimkp");
            new Thread(new Runnable() {
                @Override public void run() {
                    // THROUGH MlsProviderTransport, not ProviderTransport (and the
                    // same reason as --ez serverera and --ez ctrl's GroupInfo half):
                    // reaching the shim directly put a door to a PEER'S POOL outside the class that
                    // owns the resource, invisible to any enumeration of the transport. Unrefusable
                    // — an operator asked — and CHARGED, because the packages really are gone.
                    final java.util.List<byte[]> kps = MlsProviderTransport
                            .get(appCtxCk, ckSub).debugClaimAllKeyPackages(peer);
                    if (kps == null || kps.isEmpty()) {
                        LogUtil.w(TAG, "DEBUG CLAIMKP " + peer + " → NONE (null or empty). That is "
                                + "not the same as 'one device' — it is no claimable KeyPackage at "
                                + "all, which is a pool or capability problem, not a device count.");
                        return;
                    }
                    final StringBuilder sizes = new StringBuilder();
                    for (final byte[] k : kps) {
                        if (sizes.length() > 0) sizes.append(',');
                        sizes.append(k == null ? 0 : k.length);
                    }
                    LogUtil.i(TAG, "DEBUG CLAIMKP " + peer + " → " + kps.size()
                            + " KeyPackage(s), sizes=[" + sizes + "]B. "
                            + (kps.size() > 1
                                ? "MULTI-DEVICE: each of these gets its own leaf in the commit."
                                : "Single device (or the server only ever hands back one)."));
                }
            }, "mls-claim-kp").start();
            return;
        }

        // --ez publishkp true [--es kpcount N] → app-generated KeyPackages, published via the KDS.
        if (intent.getBooleanExtra("publishkp", false)) {
            final Context appCtx5 = context.getApplicationContext();
            final int pSubId = subId;
            int n = 11;
            try { n = Integer.parseInt(intent.getStringExtra("kpcount")); } catch (final Throwable ig) { }
            final int count = n;
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean ok = MlsProviderTransport.get(appCtx5, pSubId)
                            .publishKeyPackages(count);
                    LogUtil.i(TAG, "DEBUG MLS PUBLISHKP(" + count + ") → " + ok);
                }
            }, "mls-publish-kp").start();
            return;
        }

        // --ez dropprops true [--es rcsgid <id>] → drop the cached by-reference proposals on a
        // conversation. Doubles as the field recovery lever for the underlying failure:
        // a proposal we cannot honour blocks EVERY send until it is committed, so a wedged
        // conversation is un-wedged by dropping it. Safe when nothing is pending — it clears an
        // empty cache.
        // --ez resync true --es rcsgid <gid> [--es to <peer>]  → drive a RESYNC EXTERNAL COMMIT
        // directly. The production path reaches this from the BEHIND arm and from the
        // era-quota terminal; this is how you exercise it on a known-broken fixture on demand.
        if (intent.getBooleanExtra("resync", false)) {
            final Context appCtxRS = context.getApplicationContext();
            final int rsSubId = subId;
            final String rsTo = to;
            final String rsGid = intent.getStringExtra("rcsgid");
            // --ei resyncleaf -1 asks for the PLAIN (no self-remove) flavor — Google Messages'
            // epoch-advancement shape — instead of the RESYNC(self_remove) rejoin shape.
            final long rsLeaf = intent.getIntExtra("resyncleaf", 0);
            // --ez dryrun true asks ONLY whether the rejoin could work: it fetches the UNANCHORED
            // GroupInfo, reports whether it carries external_pub, and stops. Nothing is built, sent
            // or deleted and the §11.2.2 budget is not charged.
            //
            // IT EXISTS FOR THE GROUPS WE MUST NOT DISTURB. The real lever re-enters the group and
            // every member sees the commit, so pointing it at a conversation carrying a Google Messages
            // capture line or a third party to find out whether it would have worked is exactly the
            // move that produced this fleet's P0. Ask first, then decide.
            final boolean rsDry = intent.getBooleanExtra("dryrun", false);
            new Thread(new Runnable() {
                @Override public void run() {
                    final int r = MlsProviderTransport.get(appCtxRS, rsSubId)
                            .resyncViaExternalCommit(rsGid, rsTo, "operator request",
                                    /*forcePastProfile=*/ true, rsLeaf, rsDry);
                    LogUtil.i(TAG, "DEBUG MLS RESYNC(" + rsGid + "/" + rsTo + " leaf=" + rsLeaf
                            + (rsDry ? " DRY-RUN" : "") + ") → " + r
                            + (r == MlsProviderTransport.RESYNC_DRY_RUN_VIABLE
                                    ? " (DRY RUN: VIABLE — external_pub present; nothing sent)"
                                    : r == MlsProviderTransport.RESYNC_DRY_RUN_NOT_VIABLE
                                            ? " (DRY RUN: NOT VIABLE — no external_pub; nothing sent)"
                                            : ""));
                }
            }, "mls-resync").start();
            return;
        }

        // --ez cooldowns true [--es rcsgid <gid>] [--es to <e164>]  → ask the two DURABLE stamps
        // whether they would allow their operation now, and stamp if they would.
        //
        // THE SHIPPING GATES, NOT A RE-IMPLEMENTATION. It calls MlsPeerGuard.claimRebuildEpisode and
        // MlsPeerGuard.claimReestablishAttempt with the same arguments production passes — the
        // transport's own REBUILD_EPISODE_MS and REESTABLISH_COOLDOWN_MS, read back through the
        // transport rather than retyped — so what this prints is the real verdict against the real
        // record. Only the TRIGGER is synthetic, which is seedUsageCounter's precedent: verifying
        // the shipping path is worth a debug entry point, verifying a parallel one is not.
        //
        // WHY IT NEEDS TO EXIST AT ALL. The property at stake is that these stamps survive a
        // process restart, and the operations they gate — a rebuild, a 1:1 re-establish — need a
        // genuinely diverged conversation to reach. This asks the gate directly so a force-stop and
        // relaunch is a two-minute check rather than a two-device setup.
        //
        // TRANSMITS NOTHING, so it is deliberately NOT in STATE_CHANGING: both calls only read and
        // write our own preferences file. The visible side effect is that a probe STAMPS, which
        // suppresses the real operation for the rest of the window — `--ez cooldownsreset true`
        // undoes both, through the same levers Try again uses.
        if (intent.getBooleanExtra("cooldowns", false)) {
            final Context appCtxCD = context.getApplicationContext();
            final int cdSubId = subId;
            final String cdTo = to;
            final String cdGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtxCD, cdSubId);
                    LogUtil.i(TAG, "DEBUG MLS COOLDOWNS(" + cdGid + "/" + cdTo + ") → "
                            + t.debugProbeDurableCooldowns(cdGid, cdTo));
                }
            }, "mls-cooldowns").start();
            return;
        }

        // --ez cooldownsreset true [--es rcsgid <gid>] [--es to <e164>]  → clear both, through the
        // same levers the stalled-conversation notification's Try again pulls.
        if (intent.getBooleanExtra("cooldownsreset", false)) {
            final Context appCtxCR = context.getApplicationContext();
            final int crSubId = subId;
            final String crTo = to;
            final String crGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    // Through the transport, so the canonical key comes from canonicalKey() rather
                    // than being derived a second time here. A second derivation of that key is
                    // exactly how a 1:1 once fell through a guard.
                    LogUtil.i(TAG, "DEBUG MLS COOLDOWNSRESET(" + crGid + "/" + crTo + ") → "
                            + MlsProviderTransport.get(appCtxCR, crSubId)
                                    .debugResetDurableCooldowns(crGid, crTo));
                }
            }, "mls-cooldowns-reset").start();
            return;
        }

        // --ez rebuildreset true --es rcsgid <gid>  → hand a conversation its rebuild allowance
        // back. TEST ONLY: a poisoned fixture spends all three rebuilds in the session that broke
        // it and is then unrepairable for the rest of the 6h window, which is not a test cycle.
        if (intent.getBooleanExtra("rebuildreset", false)) {
            final Context appCtxRR = context.getApplicationContext();
            final int rrSubId = subId;
            final String rrTo = to;
            final String rrGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    MlsProviderTransport.get(appCtxRR, rrSubId)
                            .debugResetRebuildAllowance(rrGid, rrTo);
                }
            }, "mls-rebuild-reset").start();
            return;
        }

        if (intent.getBooleanExtra("dropprops", false)) {
            final Context appCtx11 = context.getApplicationContext();
            final int dSubId = subId;
            final String dTo = to;
            final String dGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS DROPPROPS(" + dTo + ") → " + MlsProviderTransport
                            .get(appCtx11, dSubId).dropPendingProposals(dGid, dTo));
                }
            }, "mls-drop-props").start();
            return;
        }

        // --ez kpvet true → exercise the RCC.16 A.4.1.2 consume-side KeyPackage gate
        // against a locally minted KeyPackage. Verifies the whole new native path — kp_inspect over
        // JNI, the lifetime read, and the floor arithmetic — WITHOUT claiming a peer's package,
        // which would consume one of theirs and, on the paths that claim, commit a group change.
        // Raise debug.rcs.mls_kp_min_remaining_days above the minted lifetime to see it REFUSE.
        if (intent.getBooleanExtra("kpvet", false)) {
            final Context appCtx10 = context.getApplicationContext();
            final int vSubId = subId;
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS KPVET → " + MlsProviderTransport
                            .get(appCtx10, vSubId).vetSelfKeyPackage());
                }
            }, "mls-kp-vet").start();
            return;
        }

        // --es replayctrl <file> --es from <e164> [--es rcsgid <id>] → replay a captured inbound
        // control payload through the app's engine. The provider dumps every inbound to its cache
        // (dumpInboundRaw); copy one to /data/local/tmp and replay it here instead of asking a human
        // to re-trigger a group add on a real phone every time the join path changes.
        // --es replayapp <file> --es from <e164> [--es rcsgid <id>] [--es era N] [--es mid <id>]
        //   → feed a captured APPLICATION ciphertext through the real inbound decrypt path.
        //
        // The app-plane sibling of replayctrl, and it exists for the half of §10.8 that the control
        // replay cannot reach: PARK-vs-REPORT. That decision is made on an application ciphertext by
        // comparing its moment against the group's, so exercising it needs a ciphertext whose era we
        // CHOOSE — not one the wire happened to produce.
        //
        // Why a lever rather than "arrange it on two phones": the natural triggers keep resolving in
        // the happy direction. An era advance carries its Welcome, so the peer is already at the new
        // era before the message lands and the from-the-future arm is never entered (device-observed
        // 2026-08-07 — the OUTCOME was right and the MECHANISM stayed untested).
        // A test that cannot make the interesting case happen is not a test, and this is exactly the
        // "instrument that cannot discriminate" trap we have now hit three times in a week.
        //
        // Capture an input with: the provider dumps every inbound to
        // /data/user/0/com.google.android.apps.messaging/cache/inbound-raw/.
        // --es recvct <content-type> [--es from <e164>] [--es recvfile <path>] [--es body <text>]
        //   [--es rcsgid <id>]
        //   → drive ONE inbound message through the real receive path with a content type WE
        //     choose, and render it.
        //
        // WHY A LEVER RATHER THAN TWO PHONES. The defect is a peer sending a
        // conforming-but-uppercase MIME — "IMAGE/JPEG" is legal per RFC 2045 §5.1 — and neither of
        // our own two producers can emit one: Google Messages sends lowercase, and our own send path
        // frames whatever the media layer hands it, which on Android is lowercase by convention.
        // So the interesting case is exactly the one no natural trigger produces, which is the
        // "instrument that cannot discriminate" trap this receiver already carries two other levers
        // for (replayapp's PARK-vs-REPORT, mlsahead's AHEAD hold).
        //
        // WHERE IT ENTERS, stated so nobody over-reads the proof: at RcsCallbackRouter's inbound
        // funnel, whose whole body is `new ReceiveRcsMessageAction(msg).start()`. That covers
        // classify(), the MEDIA arm, staging, the part row and every renderer — i.e. the whole
        // chain from the point the content type and bytes are already unframed. It does NOT cover
        // RccMlsBody.parse or the decrypt, which is where the type comes from; those are the same
        // for any casing and are covered by the host suite.
        //
        // LOCAL ONLY: no network, no MLS state, no peer's key material — it inserts one row.
        if (intent.getStringExtra("recvct") != null) {
            final Context appCtxR = context.getApplicationContext();
            final int rcSubId = subId;
            final String rcType = intent.getStringExtra("recvct");
            final String rcFrom = to != null ? to : intent.getStringExtra("from");
            final String rcFile = intent.getStringExtra("recvfile");
            final String rcGid = intent.getStringExtra("rcsgid");
            final String rcBody = body;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        byte[] bytes;
                        if (rcFile != null) {
                            final java.io.File f = new java.io.File(rcFile);
                            bytes = new byte[(int) f.length()];
                            final java.io.FileInputStream in = new java.io.FileInputStream(f);
                            int off = 0, n;
                            while (off < bytes.length
                                    && (n = in.read(bytes, off, bytes.length - off)) > 0) {
                                off += n;
                            }
                            in.close();
                        } else {
                            bytes = (rcBody == null ? "" : rcBody)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        }
                        if (rcFrom == null || rcFrom.isEmpty()) {
                            LogUtil.w(TAG, "DEBUG RECVCT needs --es to <e164> (or --es from)");
                            return;
                        }
                        final String rcMid = "recvct-" + UUID.randomUUID();
                        LogUtil.i(TAG, "DEBUG RECVCT ct=<<" + rcType + ">> from=" + rcFrom
                                + " body=" + bytes.length + "B mid=" + rcMid
                                + " rcsgid=" + rcGid + " — the type is passed VERBATIM, which is "
                                + "the whole point: a conforming peer may send it in any case");
                        // The funnel RcsCallbackRouter.onIncomingMessage uses, verbatim — its body
                        // is this one line, and going through the Action directly keeps the lever
                        // from depending on a router instance it would have to invent.
                        new ReceiveRcsMessageAction(new RcsIncomingMessage(rcSubId, rcMid, rcFrom,
                                rcType, bytes, /*serverTimestampUsec=*/ 0L,
                                /*wantsDeliveredImdn=*/ false, /*wantsDisplayedImdn=*/ false,
                                rcGid, null)).start();
                        LogUtil.i(TAG, "DEBUG RECVCT handed to ReceiveRcsMessageAction — watch for "
                                + "the classify line and the part row's media mime");
                    } catch (final Throwable e) {
                        LogUtil.w(TAG, "DEBUG RECVCT failed", e);
                    }
                }
            }, "debug-recvct").start();
            return;
        }

        if (intent.getStringExtra("replayapp") != null) {
            final Context appCtx9 = context.getApplicationContext();
            final int aSubId9 = subId;
            final String path = intent.getStringExtra("replayapp");
            final String aFrom = intent.getStringExtra("from");
            final String aGid = intent.getStringExtra("rcsgid");
            final String eraStr = intent.getStringExtra("era");
            final String midStr = intent.getStringExtra("mid");
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        final java.io.File f = new java.io.File(path);
                        final byte[] buf = new byte[(int) f.length()];
                        final java.io.FileInputStream in = new java.io.FileInputStream(f);
                        int off = 0, n;
                        while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                            off += n;
                        }
                        in.close();
                        final long era = eraStr == null ? -1L : Long.parseLong(eraStr.trim());
                        final String mid = midStr != null ? midStr : "replay-" + f.getName();
                        LogUtil.i(TAG, "DEBUG MLS REPLAYAPP " + path + " (" + buf.length
                                + "B) from=" + aFrom + " rcsgid=" + aGid + " era=" + era
                                + " mid=" + mid);
                        final MlsProviderTransport t =
                                MlsProviderTransport.get(appCtx9, aSubId9);
                        final RccMlsBody.Parsed parsed =
                                t.decryptInbound(aFrom, mid, buf, aGid);
                        if (parsed != null && parsed.body != null) {
                            LogUtil.i(TAG, "DEBUG MLS REPLAYAPP → DECRYPTED " + parsed.body.length
                                    + "B ct=" + parsed.contentType + " (it was readable at our "
                                    + "current moment; to exercise the park path replay one whose "
                                    + "era is AHEAD of us)");
                            return;
                        }
                        // The decision under test. onDecryptFailure parks a strictly-from-the-future
                        // ciphertext and takes the §10/FTD path for everything else — the era we pass
                        // is what lets it tell those apart.
                        LogUtil.i(TAG, "DEBUG MLS REPLAYAPP → did not decrypt; handing to "
                                + "onDecryptFailure (era=" + era + ") — watch for PARKED vs §10");
                        t.onDecryptFailure(aGid, aFrom, mid, buf, era);
                    } catch (final Throwable t) {
                        LogUtil.w(TAG, "DEBUG MLS REPLAYAPP failed", t);
                    }
                }
            }, "mls-replay-app").start();
            return;
        }

        if (intent.getStringExtra("replayctrl") != null) {
            final Context appCtx4 = context.getApplicationContext();
            final int rSubId = subId;
            final String path = intent.getStringExtra("replayctrl");
            final String rFrom = intent.getStringExtra("from");
            final String rGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        final java.io.File f = new java.io.File(path);
                        final byte[] buf = new byte[(int) f.length()];
                        final java.io.FileInputStream in = new java.io.FileInputStream(f);
                        int off = 0, n;
                        while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                            off += n;
                        }
                        in.close();
                        LogUtil.i(TAG, "DEBUG MLS REPLAYCTRL " + path + " (" + buf.length
                                + "B) from=" + rFrom + " rcsgid=" + rGid);
                        final boolean ok = MlsProviderTransport.get(appCtx4, rSubId)
                                .applyInboundControl(rFrom, "replay-" + f.getName(), buf,
                                        /*convergenceAck=*/ false, rGid);
                        LogUtil.i(TAG, "DEBUG MLS REPLAYCTRL → applied=" + ok);
                    } catch (final Throwable t) {
                        LogUtil.w(TAG, "DEBUG MLS REPLAYCTRL failed", t);
                    }
                }
            }, "mls-replay-ctrl").start();
            return;
        }

        // --es removemember <e164> [--es rcsgid <id>] → RCS participant removal FIRST, then the
        // MLS remove commit. A REAL group change.
        if (intent.getStringExtra("removemember") != null) {
            final Context appCtxR = context.getApplicationContext();
            final int rSubId = subId;
            final String rTo = to;
            final String rWho = intent.getStringExtra("removemember");
            final String rGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    final int era = MlsProviderTransport.get(appCtxR, rSubId)
                            .removeMember(rGid, rTo, /*memberSigPub=*/ null, rWho);
                    LogUtil.i(TAG, "DEBUG MLS REMOVEMEMBER " + rWho + " → era=" + era);
                }
            }, "mls-remove-member").start();
            return;
        }

        // --es changesubj <text> [--es rcsgid <id>] → the FULL RCC.16 §9.7.1.5 subject flow:
        // encrypt (Annex C.2) -> set the RCS group name to the ciphertext -> commit the
        // subject_commitment -> send the key as a §7.8.1 FileInfo. A REAL group change.
        if (intent.getStringExtra("changesubj") != null) {
            final Context appCtxS = context.getApplicationContext();
            final int sSubId = subId;
            final String sTo = to;
            final String sGid = intent.getStringExtra("rcsgid");
            final String subjTxt = intent.getStringExtra("changesubj");
            new Thread(new Runnable() {
                @Override public void run() {
                    final byte[] ct = MlsProviderTransport.get(appCtxS, sSubId).changeGroupSubject(
                            sGid, sTo, subjTxt.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "text/plain");
                    LogUtil.i(TAG, "DEBUG MLS CHANGESUBJ '" + subjTxt + "' → "
                            + (ct == null ? "FAILED" : "OK ciphertext=" + ct.length + "B"));
                }
            }, "mls-change-subject").start();
            return;
        }

        // --es changeicon <file> [--es iconmime <mime>] [--es rcsgid <id>] → the FULL RCC.16
        // §9.7.1.4 icon flow: encrypt (Annex C.2) -> the provider uploads the CIPHERTEXT to the FT
        // server -> one ChangeGroupProfile carrying the icon reference + the commit + the key
        // delivery.
        //
        // THIS IS THE ONLY CALLER OF changeGroupIcon ANYWHERE (2026-09-13). There is no
        // OP_CHANGE_ICON in ManageRcsGroupAction and no icon row in PeopleAndOptionsFragment, so
        // without this arm the send path that landed today is unreachable and therefore untestable.
        // That is the point of adding it: "landed" must not be allowed to read as "shipped", and an
        // unreachable path is one nobody can tell is broken.
        //
        // ⚠ READ THIS BEFORE PLANNING A DEVICE RUN — WHAT A RUN OF THIS CAN AND CANNOT SHOW.
        // The ONLY positive signal available today is the SERVER'S verdict: ChangeGroupProfile+MLS
        // accepted, our epoch advanced. Nothing else is observable, in either direction:
        //   - NOT on the sender. There is no GroupIconApplier and no local mirror to
        //     ConversationColumns.ICON, so our own device shows nothing. (MlsSubjectApplier's
        //     absence was a known defect for the subject, and the icon has never had one.)
        //   - NOT on any peer, ours or Google Messages. RcsCallbackRouter.onEncryptedGroupIcon logs the
        //     reference and stores nothing — there is no download path yet.
        // So a green run proves the REQUEST is well-formed and nothing more. Do not let "the icon
        // change succeeded" be recorded from it: that mistake has already been made once, when
        // "ACCEPTED -> era=1" was read as the subject working cross-vendor. Ordering and the UI row
        // wait on the receive half, and the
        // reason they wait is exactly this: a row shipped now would be a control that spends a
        // real upload and a real era and changes nothing anyone can see.
        //
        //   adb shell am broadcast -a <action> --es changeicon /sdcard/icon.jpg \
        //       --es iconmime image/jpeg --es rcsgid <32-hex>
        if (intent.getStringExtra("changeicon") != null) {
            final Context appCtxI = context.getApplicationContext();
            final int iSubId = subId;
            final String iTo = to;
            final String iGid = intent.getStringExtra("rcsgid");
            final String iPath = intent.getStringExtra("changeicon");
            final String iMime = intent.getStringExtra("iconmime") == null
                    ? "image/jpeg" : intent.getStringExtra("iconmime");
            new Thread(new Runnable() {
                @Override public void run() {
                    final byte[] raw;
                    try {
                        raw = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(iPath));
                    } catch (final Throwable t) {
                        // Say WHICH failure this is. A missing file and a refused change both end
                        // with no icon on the group, and only one of them is about the MLS path.
                        LogUtil.w(TAG, "DEBUG MLS CHANGEICON: could not read " + iPath
                                + " — this is the FILE, not the icon flow", t);
                        return;
                    }
                    // The mime we pass is the IMAGE's own, and it travels in the §7.8.1 FileInfo.
                    // It is NOT what lands in the reference's field 1 — that is the literal message/mls-ft,
                    // which the provider writes. Passing image/jpeg here and expecting to see it on
                    // the wire would be reading the wrong field.
                    final byte[] ct = MlsProviderTransport.get(appCtxI, iSubId).changeGroupIcon(
                            iGid, iTo, raw, iMime);
                    LogUtil.i(TAG, "DEBUG MLS CHANGEICON " + iPath + " (" + raw.length + "B "
                            + iMime + ") → " + (ct == null ? "FAILED"
                                    + " — read the MlsProviderTransport lines above for which half"
                                    : "OK ciphertext=" + ct.length + "B uploaded + committed"));
                }
            }, "mls-change-icon").start();
            return;
        }

        // --es plainrename <text> [--es rcsgid <id>] → set the RCS group name in the clear. Not a
        // spec flow; the way back from an encrypted-subject test so the group is readable again.
        if (intent.getStringExtra("plainrename") != null) {
            final Context appCtxN = context.getApplicationContext();
            final int nSubId = subId;
            final String nGid = intent.getStringExtra("rcsgid");
            final String nName = intent.getStringExtra("plainrename");
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG RCS PLAINRENAME '" + nName + "' → "
                            + com.android.messaging.rcs.ProviderTransport.getInstance(appCtxN)
                                    .renameGroup(nSubId, nGid, nName));
                }
            }, "rcs-plain-rename").start();
            return;
        }

        // --ez rekey true → app-built selfUpdate commit (era advance) sent through the provider.
        // --es addmember <e164> → app-built addMember commit. Both are REAL group changes, not probes:
        // they are also the only way an era advances once step 6 removes the provider's state machine.
        if (intent.getBooleanExtra("rekey", false)
                || intent.getStringExtra("addmember") != null) {
            final Context appCtx3 = context.getApplicationContext();
            final int cSubId = subId;
            final String cTo = to;
            final String addWho = intent.getStringExtra("addmember");
            final String intent2Gid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtx3, cSubId);
                    final String cGid = intent2Gid;
                    // Use the GROUP-aware rekey overload when --es rcsgid names one. The 1:1
                    // overload resolves whatever 1:1 exists with that peer, so probing a group
                    // member rekeyed a DIFFERENT conversation — which is exactly the trap the
                    // overload's own javadoc documents.
                    final int era = (addWho != null) ? t.addMember(cGid, cTo, addWho)
                            : (cGid == null || cGid.isEmpty()) ? t.rekey(cTo)
                            : t.rekey(cGid, cTo);
                    LogUtil.i(TAG, "DEBUG MLS " + (addWho == null ? "REKEY" : "ADDMEMBER " + addWho)
                            + "(" + cTo + ") → era=" + era);
                }
            }, "mls-commit-probe").start();
            return;
        }

        // --ez ctrl true → exercise the v21 MLS control plane over the binder (read-only calls:
        // claim a peer KeyPackage from the KDS, and fetch the server's GroupInfo). Proves the app can
        // drive MLS control traffic through the provider without owning any Tachyon vocabulary.
        if (intent.getBooleanExtra("ctrl", false)) {
            final Context appCtx2 = context.getApplicationContext();
            final int ctrlSubId = subId;
            final String ctrlTo = to;
            new Thread(new Runnable() {
                @Override public void run() {
                    final ProviderTransport pt = ProviderTransport.getInstance(appCtx2);
                    // THROUGH MlsProviderTransport. An earlier change routed this arm's
                    // getMlsGroupInfo half below through the transport for exactly this reason and
                    // walked past the claim two lines above it — the claim was still a door to a
                    // peer's pool sitting outside the class that owns the resource, in a file that
                    // had just been edited to close the same shape one line over.
                    final byte[] kp = MlsProviderTransport.get(appCtx2, ctrlSubId)
                            .debugClaimOneKeyPackage(ctrlTo);
                    LogUtil.i(TAG, "DEBUG MLS CTRL claimPeerKeyPackage(" + ctrlTo + ") → "
                            + (kp == null ? "null" : kp.length + "B"));
                    // Through MlsProviderTransport for the same reason as --ez serverera above
                    //: this was getMlsGroupInfo's ONLY caller, and it lived outside the
                    // class that owns the resource. Exempt, and the ledger line says so.
                    final org.lineageos.rcs.provider.RcsMlsControlResult gi =
                            MlsProviderTransport.get(appCtx2, ctrlSubId).debugGroupInfo(ctrlTo);
                    LogUtil.i(TAG, "DEBUG MLS CTRL getMlsGroupInfo(" + ctrlTo + ") → " + gi);
                    LogUtil.i(TAG, "DEBUG MLS CTRL profile → "
                            + pt.getMlsTransportProfile(ctrlSubId));
                }
            }, "mls-ctrl-probe").start();
            return;
        }

        // --ez gate true → run the REAL eligibility gate (E2eeSendGate.resolveForSend) and log its
        // verdict.
        //
        // WHY THIS EXISTS, and why --ez e2ee true is NOT a substitute: the e2ee path above HARDCODES
        // RcsE2eeScheme.MLS. It reproduces the production send SHAPE but never calls the gate, so it
        // can never observe an eligibility decision — which is exactly how the gate refusing
        // Google Messages peers in 1:1 stayed invisible. That makes this the FIFTH bug in this
        // subsystem hidden by a test that did not exercise the shipping path, and the second inside a
        // harness added to fix the previous one. Read-only: resolves and logs, sends nothing.
        if (intent.getBooleanExtra("gate", false)) {
            final String scheme = E2eeSendGate.get()
                    .resolveForSend(/*conversationId=*/ "debug-gate-probe", subId, to,
                            /*isGroup=*/ false);
            LogUtil.i(TAG, "DEBUG GATE PROBE to=" + to + " subId=" + subId
                    + " → selected scheme=" + scheme
                    + " (MLS=" + RcsE2eeScheme.MLS + "); ourLaunchIteration="
                    + RcsE2eeScheme.ourLaunchIteration());
            return;
        }

        // THE DEFAULT SEND PATH — the only code in this receiver that consumes BOTH extras, and so
        // the only place the to/body requirement belongs (it used to gate every arm).
        if (TextUtils.isEmpty(to) || body == null) {
            LogUtil.w(TAG, "DEBUG SEND_TEST_RCS: no arm extra matched, so this is the plain send "
                    + "path, which needs --es to <e164> --es body <text>. Nothing sent.");
            return;
        }

        // Resolve the scheme through the REAL gate rather than asserting it.
        //
        // This used to hardcode RcsE2eeScheme.MLS, which reproduced the production send SHAPE while
        // skipping the production DECISION — so the harness could not observe eligibility at all.
        // That blind spot hid two defects simultaneously — no peer-capability source, and a tree
        // that refused Google peers — each masking the other. Now --ez e2ee true asks E2eeSendGate
        // exactly as
        // InsertNewMessageAction does, and reports what it chose: a downgrade to plaintext becomes a
        // visible test result instead of a silent pass.
        String scheme = null;
        if (e2ee) {
            scheme = E2eeSendGate.get()
                    .resolveForSend(/*conversationId=*/ "debug-" + to, subId, to, /*isGroup=*/ false);
            LogUtil.i(TAG, "DEBUG SEND_TEST_RCS gate resolved scheme=" + scheme
                    + (RcsE2eeScheme.MLS.equals(scheme)
                            ? " (MLS — production path)"
                            : " (NOT MLS — the gate declined; this send will NOT be E2EE)"));
        }
        final boolean useMls = RcsE2eeScheme.MLS.equals(scheme);
        final byte[] outBody = useMls
                ? RccMlsBody.frameText(body)
                : body.getBytes(StandardCharsets.UTF_8);
        final RcsOutgoingMessage msg = useMls
                ? new RcsOutgoingMessage(subId, messageId, to, "message/mls", outBody,
                        RcsE2eeScheme.MLS, /*groupId=*/ null)
                : new RcsOutgoingMessage(subId, messageId, to, CONTENT_TYPE, outBody);
        LogUtil.i(TAG, "DEBUG SEND_TEST_RCS e2ee=" + e2ee + " useMls=" + useMls
                + (useMls ? " (RCC.16-framed " + outBody.length + "B, scheme=" + RcsE2eeScheme.MLS
                        + " — PRODUCTION path, gate-selected)" : " (plaintext path)"));

        final ProviderTransport transport = ProviderTransport.getInstance(context);
        final RcsSendResult result = transport.sendMessage(msg);
        LogUtil.i(TAG, "DEBUG SEND_TEST_RCS result accepted=" + result.accepted
                + " reasonCode=" + result.reasonCode + " reason=" + result.reason
                + " messageId=" + messageId);
    }

    /** Parse an even-length hex string into bytes; null/blank/odd/non-hex yields null. */
    private static byte[] hexToBytes(final String hex) {
        if (hex == null) return null;
        final String h = hex.trim();
        if (h.isEmpty() || (h.length() % 2) != 0) return null;
        final byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int v = Character.digit(h.charAt(i * 2), 16);
            final int w = Character.digit(h.charAt(i * 2 + 1), 16);
            if (v < 0 || w < 0) return null;
            out[i] = (byte) ((v << 4) | w);
        }
        return out;
    }
}

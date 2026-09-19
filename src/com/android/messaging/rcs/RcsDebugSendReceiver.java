/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
import com.android.messaging.rcs.engine.mls.RccMlsBody;
import com.android.messaging.rcs.e2ee.MlsPeerGuard;
import com.android.messaging.util.LogUtil;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes;

/**
 * Debug-only driver for RCS and MLS operations, bypassing the compose UI and route selection.
 * Ignored unless {@link RcsDebug#isDebugBuild()} (a system app is never marked
 * debuggable). With no arm extra it sends {@code body} to {@code to} through
 * {@link ProviderTransport#sendMessage(RcsOutgoingMessage)}; each arm extra below selects another
 * operation.
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.SEND_TEST_RCS \
 *     -n com.android.messaging/.rcs.RcsDebugSendReceiver --es to &lt;e164&gt; --es body hi
 * </pre>
 */
public final class RcsDebugSendReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    static final String ACTION_SEND_TEST_RCS = "com.android.messaging.debug.SEND_TEST_RCS";
    private static final String EXTRA_TO = "to";
    private static final String EXTRA_BODY = "body";
    /** {@code --ez e2ee true}: resolve the scheme through the send gate and use RCC.16 framing. */
    private static final String EXTRA_E2EE = "e2ee";
    private static final String CONTENT_TYPE = "text/plain;charset=UTF-8";

    /**
     * The boolean arms {@link #armOf} matches first, in order. Shared with armOf so the gate lists
     * below are checked against armOf's own literals.
     */
    private static final String[] BOOL_ARMS = {
            "appmls", "selfheal", "records", "groupexts", "ftdfail", "ftdreport", "forgetgroup",
            "servera", "eraadvance", "floorrebuild", "groupsend", "health", "procresults",
            "advancer", "continuity",
    };

    /**
     * The second block of boolean arms, matched after {@code establishgroup} and {@code
     * membership}.
     */
    private static final String[] MORE_ARMS = {
            "servremove", "imdnsig", "iconsubj", "endmls", "phoenix", "seedusage", "publishkp",
            "resync", "dropprops", "kpvet",
    };

    /**
     * The remaining names {@link #armOf} can return: the string-extra arms, the two collapsing
     * branches ({@code membership}, {@code commit}) and the fallthrough {@code send}.
     * Hand-maintained; {@link #assertArmVocabulary()} refuses every arm if a gate list names one
     * that is missing.
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
     * Arms that act on a named peer's conversation and need {@code to}. Arms that take {@code to}
     * as an optional hint ({@code servera}, {@code servremove}, {@code procresults}) are not
     * listed.
     */
    private static final java.util.Set<String> NEEDS_TO =
            java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(
                    "appmls", "selfheal", "groupexts", "ftdfail", "ftdreport", "forgetgroup",
                    "eraadvance", "floorrebuild", "health", "membership", "imdnsig", "iconsubj",
                    "endmls", "continuity",
                    "phoenix", "seedusage", "dropprops", "removemember", "changesubj",
                    "commit", "ctrl", "gate")));

    /**
     * Arms that change MLS state on a peer, gated by the peer allowlist
     * ({@link com.android.messaging.rcs.e2ee.MlsPeerGuard}). Local cleanup arms such as
     * {@code forgetgroup} and read-only arms are excluded: stopping talking to a wedged peer must
     * never be gated. {@code --ez rekey} is gated under the name {@code commit}, which is what
     * {@link #armOf} returns for it.
     */
    private static final java.util.Set<String> STATE_CHANGING =
            java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(
                    "eraadvance", "floorrebuild", "establishgroup", "commit", "removemember",
                    "membership",
                    "selfheal", "endmls", "changesubj", "iconsubj", "servremove",
                    "phoenix", "resync", "plainrename")));

    /**
     * Every peer a state-changing arm would touch: {@code to} plus the arm's member extras. Empty
     * only when the arm names no peer; a group-id-only operation resolves members from local state.
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
                // Only E.164-looking values: these extras can also carry non-peer values
                // (removemember accepts a signature public key).
                if (p.startsWith("+") && p.length() >= 8) out.add(p);
            }
        }
        return out;
    }

    /**
     * Names in the gate lists that {@link #armOf} cannot return; such a gate would never fire.
     * Checks one direction only: whether a new arm belongs in a gate list is a review question.
     *
     * @return the offending names, empty when the vocabulary is consistent
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

    /**
     * Which arm this broadcast selects, for the trace line and the gates. The order mirrors the
     * dispatch chain in {@link #onReceive}, which remains the actual dispatch.
     */
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
        // Not in either gate list: only the `arm` verb is gated, on the whole roster, inside
        // MlsProviderTransport.armInboundHold.
        if (i.getStringExtra("mlshold") != null) return "mlshold";
        // As mlshold: gated on the whole roster inside MlsProviderTransport.armOutboundHold.
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

        // Inert on a non-debuggable build, although exported. ro.debuggable rather than
        // FLAG_DEBUGGABLE, which is false for a system app even on a userdebug image.
        final boolean debuggable = RcsDebug.isDebugBuild();
        if (!debuggable) {
            LogUtil.w(TAG, "DEBUG SEND_TEST_RCS ignored: build is not debuggable");
            return;
        }

        // Fail closed: a gate list naming an arm armOf cannot return is a dead gate, so refuse
        // every arm.
        final java.util.Set<String> badArms = assertArmVocabulary();
        if (!badArms.isEmpty()) {
            LogUtil.e(TAG, "DEBUG SEND_TEST_RCS REFUSED: gate lists name arms armOf can never "
                    + "return " + badArms + " — that gate silently never fires. Fix armOf or the "
                    + "list before using the debug receiver.");
            return;
        }

        final String to = intent.getStringExtra(EXTRA_TO);
        final String body = intent.getStringExtra(EXTRA_BODY);

        // No to/body requirement here: only the default send path at the bottom needs both. Arms
        // that need `to` are listed in NEEDS_TO and checked once.
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
        // Peer allowlist gate for every state-changing arm, checked against every peer the arm
        // targets, not only `to` (establishgroup takes its members as a CSV and has no `to`).
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
        LogUtil.i(TAG, "DEBUG SEND_TEST_RCS to=" + to
                + " body=" + (body == null ? "<unset>" : (body.length() + "ch"))
                + " subId=" + subId + " messageId=" + messageId
                + " -> ProviderTransport.sendMessage");

        // --ez e2ee true reproduces the production send: the gate picks the scheme, and an MLS body
        // is RCC.16-framed here and carried by the provider.
        final boolean e2ee = intent.getBooleanExtra(EXTRA_E2EE, false);


        // --ez appmls true: app-sealed MLS 1:1 send. The app frames, seals and binds the AAD; the
        // provider only carries the envelope (sendMlsCiphertext).
        if (intent.getBooleanExtra("appmls", false)) {
            final Context appCtx3 = context.getApplicationContext();
            final int aSub = subId;
            final String aTo = to;
            final String aBody = body;
            new Thread(new Runnable() {
                @Override public void run() {
                    // get(), not a new instance: the per-instance ratchet cache, locks and
                    // sealed-ciphertext cache must be the ones production uses.
                    final MlsProviderTransport t = MlsProviderTransport.get(appCtx3, aSub);
                    // This arm uses its own conversation key, "appmls-<peer>", not the one the app
                    // uses for that peer, so the two can drift apart. The key is logged to make
                    // that visible.
                    final String convId = "appmls-" + aTo;
                    // Log the real conversation's health next to this arm's own.
                    final MlsTransportTypes.Health realHealth = t.detectHealth(
                            com.android.messaging.rcs.engine.mls.MlsFetchLedger.Caller.DEBUG_HEALTH,
                            null, aTo);
                    LogUtil.i(TAG, "DEBUG APP-MLS the peer's REAL 1:1 conversation health is "
                            + realHealth
                            + " — compare against this lever's own conversation below. "
                            + "If they disagree, the lever is telling you about ITS conversation, "
                            + "not the app's.");
                    LogUtil.i(TAG, "DEBUG APP-MLS using conversation key '" + convId
                            + "' — this is "
                            + "the LEVER'S OWN key, not necessarily the conversation the app uses "
                            + "for " + aTo
                            + ". If this refuses as DOWNGRADED, check the peer's REAL "
                            + "conversation health printed above before concluding the 1:1 is broken.");
                    final boolean ready = t.ensureReady(convId, aSub,
                            java.util.Collections.singletonList(aTo));
                    LogUtil.i(TAG, "DEBUG APP-MLS ensureReady → " + ready
                            + " profile=" + t.profile());
                    if (!ready) return;
                    // The app frames; the counter it stamps must match the generation its ratchet
                    // seals at.
                    final byte[] framed = RccMlsBody.frameText(aBody);
                    // --es msgid <id> binds a caller-supplied rcs_message_id as the wire and AAD
                    // id; omit it to use a synthesised id.
                    final String aMsgId = intent.getStringExtra("msgid");
                    // --ez corruptct true flips a byte in the sealed ciphertext so the peer's
                    // decrypt fails and our resend is readable.
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
                            + "B eraHdr="
                            + (p.cpimHeaders == null ? "?" : p.cpimHeaders.get("Era-ID"))
                            + " wireMsgId=" + (p.cpimHeaders == null ? "?"
                                    : p.cpimHeaders.get(MlsProviderTransport.HDR_SEALED_MESSAGE_ID))
                            + " (requested=" + aMsgId + ")");
                    // Send verbatim, with the id, era and epoch authenticator from the same seal.
                    LogUtil.i(TAG, "DEBUG APP-MLS sendSealed → " + t.sendSealed(aTo, p));
                }
            }, "app-mls-send").start();
            return;
        }

        // --ez advancer true --es rcsgid <id> [--es to <peer>]: print the advancer election's
        // inputs and verdict without fetching or changing anything. Read it before --ez selfheal:
        // the never-heard and unknown presences give very different look budgets.
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

        // --ez selfheal true --es rcsgid <id> --es to <peer>: fetch the missed commits and replay
        // them.
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

        // --ez membervalidity true --es to <e164> [--es rcsgid <gid>]: print every member's
        // certificate window in the local group.
        if (intent.getBooleanExtra("membervalidity", false)) {
            final Context appCtxV = context.getApplicationContext();
            final int vSubId = subId;
            final String vTo = to;
            final String vGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    LogUtil.i(TAG, "DEBUG MLS MEMBERVALIDITY(" + vTo + ") → "
                            + MlsProviderTransport.get(appCtxV, vSubId).dumpMemberValidity(vGid,
                                    vTo));
                }
            }, "mls-membervalidity").start();
            return;
        }

        // --ez servervalidity true --es to <e164> [--es rcsgid <gid>]: the same for the server's
        // copy of the roster, identities first (they show whose state the server descends from, and
        // whether our leaf is in the tree). Read-only; charged to the fetch ledger as DEBUG_DUMP.
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

        // --ez failnextdecrypt true: make the next inbound decrypt fail once (the receive-side twin
        // of --ez corruptct), to draw a failure report and resend from a peer.
        if (intent.getBooleanExtra("failnextdecrypt", false)) {
            MlsProviderTransport.setFailNextDecrypt(context.getApplicationContext());
            LogUtil.w(TAG,
                    "DEBUG MLS: armed a ONE-SHOT forced decrypt failure for the next inbound "
                    + "message — the next thing we receive will be reported as undecryptable even "
                    + "though it is intact.");
            return;
        }

        // --es mlshold <arm|status|disarm|release|drop>: hold the inbound handshake plane for one
        // conversation, so the group advances and this device does not (a controllable behind
        // state). Held, not dropped: there is no commit backfill, so the held bytes are the only
        // copy; `release` replays them in arrival order and `drop` makes the gap permanent. --ez
        // discard true opts into dropping as they arrive.
        //
        //   --es mlshold arm --es rcsgid <gid> [--es holdmode commit|handshake|control]
        //       [--ez discard true] [--es members <e164,...>]      (a 1:1 takes --es to instead)
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
                    // Print the resulting status after a mutating verb.
                    if (!"status".equals(verb)) {
                        LogUtil.i(TAG, "DEBUG MLS HOLD status → " + t.inboundHoldStatus());
                    }
                }
            }, "mls-hold").start();
            return;
        }

        // --es mlsahead <arm|status|disarm|release|publish|drop>: withhold outbound rekey commits
        // that were already applied locally, so this device is ahead of the group. `release`
        // restores the snapshot taken before the commit and reports "restored" only if era and
        // epoch read back equal to it; `publish` sends the withheld commits late; `drop` discards
        // the undo. Rekey only.
        //
        //   --es mlsahead arm --es rcsgid <gid> [--es members <e164,...>]   (a 1:1 takes --es to)
        //   then --ez rekey true --es rcsgid <gid> --es to <e164>           (each withholds a
        //   commit)
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
                    // Print the resulting status after a mutating verb.
                    if (!"status".equals(verb)) {
                        LogUtil.i(TAG, "DEBUG MLS AHEAD status → " + t.outboundHoldStatus());
                    }
                }
            }, "mls-ahead").start();
            return;
        }

        // --ez kpcount true --es rcsgid <gid> [--es members <e164,...>]: claim one KeyPackage per
        // member and name any member without one; an upgrade to MLS needs one for every member.
        // Consumes one package per member, so do not loop it.
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

        // --ez maintenance true --es to <e164> [--es rcsgid <gid>]: run the proactive maintenance
        // pass once. It era-advances only if MlsMaintenancePolicy says so; it is not a recovery
        // lever. It also logs any GroupInfo extension type it cannot name.
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
        // --ez records true: dump every persisted per-conversation record.
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

        // --ez sendkeys true --es rcsgid <id> [--es token <hex>]: send the group's metadata keys
        // (and a continuity token, if held) as an encrypted PrivateMessage (RCC.16 §10.5).
        if (intent.getBooleanExtra("sendkeys", false)) {
            final Context appCtxK = context.getApplicationContext();
            final int kSubId = subId;
            final String kGid = intent.getStringExtra("rcsgid");
            // --es token <hex> supplies a synthetic continuity token so the send path can be
            // exercised; without key material the call refuses.
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

        // --ez continuity true --es rcsgid <id> --es to <peer> [--es token <hex>]: read whether a
        // continuity token (RCC.16 §7.11.12.1) is held, or inject one through the same write path
        // real arrivals use and read it back. Local only; logs a length and a match, never the
        // bytes.
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

        // --ez groupexts true --es rcsgid <id> --es to <peer>: dump the RCC.16 GroupContext
        // extensions the group carries, locally and in the server's GroupInfo.
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

        // --ez forgetresends true --es rcsgid <id> --es to <peer>: drop one conversation's
        // resend-ledger rows. The ledger is durable by design, so junk rows would suppress real
        // resends for good; this clears them. Not a remedy.
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

        // --es displayreceipt-mid <messageId> --es to <peer> [--es rcsgid <group>]: send a
        // displayed receipt on demand.
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

        // --ez badpayload true --es rcsgid <id> [--es mode over|under|trunc|container] [--es body
        // <t>]: send a group message whose MLS layer is valid but whose inner payload is malformed,
        // to draw a negative delivery receipt from a peer. Aim it at a group whose members are all
        // ours.
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

        // --ez ftdfail true --es rcsgid <id> --es to <peer> [--es mid <messageId>]: simulate a
        // decrypt failure of a message from <peer> and run the real recovery, which sends a real
        // RCC.16 §7.7.2.2 report if the failure persists.
        if (intent.getBooleanExtra("ftdfail", false)) {
            final Context appCtxD = context.getApplicationContext();
            final int dSubId = subId;
            final String dGid = intent.getStringExtra("rcsgid");
            final String dTo = to;
            final String dMid = intent.getStringExtra("mid") != null
                    ? intent.getStringExtra("mid")
                    : "debug-ftd-" + System.currentTimeMillis();
            // --es reason N reports N instead of 4 (failed-to-decrypt), for this report only.
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
                    // --ez noheal true emits the report without running recovery first, so one
                    // message id can be reported repeatedly without a self-heal each time.
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

        // --ez ftdreport true --es rcsgid <id> --es to <peer> --es mid <messageId> [--es reason N]:
        // simulate a peer reporting that our message failed, and run the real remedy. reason is the
        // RCC.16 §7.6.3.2 code (default 4, failed-to-decrypt); each takes a different branch.
        if (intent.getBooleanExtra("ftdreport", false)) {
            final Context appCtxR = context.getApplicationContext();
            final int rSubId = subId;
            final String rGid = intent.getStringExtra("rcsgid");
            final String rTo = to;
            final String rMid = intent.getStringExtra("mid");
            final int rReason = intent.getStringExtra("reason") == null
                    ? 4 : Integer.parseInt(intent.getStringExtra("reason"));
            if (rMid == null) {
                LogUtil.w(TAG,
                        "DEBUG MLS FTDREPORT needs --es mid <messageId> (the message of OURS "
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

        // --ez forgetgroup true --es rcsgid <id> --es to <peer>: drop all local MLS state for a
        // conversation, so the next Welcome is a fresh join.
        if (intent.getBooleanExtra("forgetgroup", false)) {
            final Context appCtxF = context.getApplicationContext();
            final int fSubId = subId;
            final String fGid = intent.getStringExtra("rcsgid");
            final String fTo = to;
            // --ez deep true also drops the provider's peer-to-group record; without it the next
            // send re-establishes the same group id through an era advance.
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

        // --ez pkeys true --es rcsgid <id> --es to <peer>: dump each leaf's index, MSISDN and
        // participant key. Read-only.
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

        // --ez servera true --es rcsgid <id> [--es to <peer>]: print the server's era and epoch
        // next to ours.
        if (intent.getBooleanExtra("servera", false)) {
            final Context appCtxS2 = context.getApplicationContext();
            final int sSubId2 = subId;
            final String sGid2 = intent.getStringExtra("rcsgid");
            final String sTo2 = to;
            new Thread(new Runnable() {
                @Override public void run() {
                    // Through MlsProviderTransport, which owns the GroupInfo fetch; exempt from the
                    // ledger.
                    final long[] srv = MlsProviderTransport.get(appCtxS2, sSubId2)
                            .debugServerEraEpoch(sGid2, sTo2);
                    final int ours = MlsProviderTransport.get(appCtxS2, sSubId2)
                            .localEra(sGid2, sTo2);
                    // The anchor probe compares epoch authenticators, which tests state rather than
                    // era and epoch numbers.
                    final MlsTransportTypes.AnchorProbe probe =
                            MlsProviderTransport.get(appCtxS2, sSubId2).probeAnchor(sGid2, sTo2);
                    LogUtil.i(TAG, "DEBUG MLS SERVERERA(" + sGid2 + ") server="
                            + (srv == null ? "null" : ("era=" + srv[0] + " epoch=" + srv[1]))
                            + " ours=era=" + ours + " anchorProbe=" + probe);
                }
            }, "mls-server-era").start();
            return;
        }

        // --ez eraadvance true --es rcsgid <id> --es to <peer>: re-create the group at era+1 and
        // Welcome every member; the only recovery for a member that missed a Welcome. --ez
        // requirefresh true claims one KeyPackage per member first and refuses if any member's
        // published certificate is stale; use it when the certificates, not the epoch, are the
        // reason.
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

        // --ez floorrebuild true --es to <e164> [--es rcsgid <gid>]: era-advance a group wedged by
        // the RCC.16 30-day lifetime floor around freshly claimed KeyPackages. Refuses and names
        // members whose published KeyPackage is still stale. Unlike the automatic path
        // (debug.rcs.mls_floor_rebuild, off by default) it ignores the once-per-mint marker.
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

        // --es establishgroup <e164,...> --es rcsgid <id>: create the MLS group for an existing RCS
        // group with those members.
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

        // --ez groupsend true --es rcsgid <id> --es body <text> [--es msgid <id>] [--ez corruptct
        // true]: app-sealed group send; corruptct makes the peers' decrypt fail so our resend can
        // be observed.
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
                        LogUtil.w(TAG,
                                "DEBUG MLS GROUPSEND: arming POST-SEAL CT CORRUPTION for this "
                                + "send — peer AEAD will fail, our resend will be readable (capstone)");
                    }
                    // --es msgid makes the send cacheable, so a group resend can be exercised.
                    final boolean ok = MlsProviderTransport.get(appCtx8, gSubId)
                            .sendToGroup(gGid, gBody, gMsgId);
                    LogUtil.i(TAG, "DEBUG MLS GROUPSEND(" + gGid + ") → " + ok
                            + (gMsgId == null ? " (no msgid: not cacheable)" : " msgid=" + gMsgId));
                }
            }, "mls-group-send").start();
            return;
        }

        // --ez health true [--ez fix true] [--ez eraadvance true] [--ez drive true]: detect, and
        // optionally reconcile, local-versus-server divergence.
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
                    final String adv = intent2Advance ? (" eraAdvance=" + t.eraAdvance(hGid, hTo))
                            : "";
                    // --ez drive true runs the bounded drive loop through the entry point
                    // production uses and prints its outcome.
                    String driven = "";
                    if (intent.getBooleanExtra("drive", false)) {
                        final com.android.messaging.rcs.engine.mls.MlsDriveLoop.Result r =
                                t.driveReconcile(hGid, hTo);
                        // noprogress and inert are the two early stops: noprogress is a fault (a
                        // pass reported work while nothing moved), inert is a pass that changed
                        // nothing. capped should never be true.
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

        // --ez procresults true [--es rcsgid <id>] [--es to <peer>] [--es ctx <id>] [--es hex
        // <bytes>]: run one wire blob through the repeated-result engine path. With no hex it feeds
        // garbage, which still crosses Java, JNI and Rust and comes back as malformed.
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
                    else               r = "removeMember era="
                            + t.removeMember(rcsgid, mTo, new byte[0]);
                    LogUtil.i(TAG, "DEBUG MLS MEMBERSHIP(" + mTo + ") → " + r);
                }
            }, "mls-membership").start();
            return;
        }

        // --ez servremove true [--es rcsgid <id>]: commit the removal of the named participant's
        // clients, as RCC.16 requires on a server notify.
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

        // --ez imdnsig true [--es rcsgid <id>]: sign an IMDN, verify it, and check that a tampered
        // statement fails.
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
                    // Same signature, different statement: the content must not match.
                    LogUtil.i(TAG, "DEBUG MLS IMDNSIG verify(tampered-status) → " + t.verifyImdn(
                            dGid, dTo, sig, mid, false, VerifiableDerivedContent.DELIVERY_FAILED,
                            VerifiableDerivedContent.FAILURE_UNSET));
                    LogUtil.i(TAG, "DEBUG MLS IMDNSIG verify(tampered-msgid) → " + t.verifyImdn(
                            dGid,
                            dTo, sig, mid + "X", false, VerifiableDerivedContent.DELIVERY_DELIVERED,
                            VerifiableDerivedContent.FAILURE_UNSET));
                }
            }, "mls-imdn-sig").start();
            return;
        }

        // --ez iconsubj true [--es icon <text>] [--es subject <text>] [--es rcsgid <id>]: publish
        // icon and subject commitments alone, as a negative control; a refusal is expected.
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
                    // Commitments are over key material; a fresh random key keeps them well formed.
                    final byte[] ic = iconTxt == null ? null : RccFileCrypto.newKey();
                    final byte[] sc = subjTxt == null ? null : RccFileCrypto.newKey();
                    final int era = t.publishIconSubject(iGid, iTo, ic, sc);
                    // The verify half runs on the receive side, where the key arrives in the RCC.16
                    // §7.8.1 FileInfo.
                    LogUtil.i(TAG, "DEBUG MLS ICONSUBJ (negative control: commitment only, no "
                            + "content, no key delivery — expect a refusal) → era=" + era);
                }
            }, "mls-icon-subject").start();
            return;
        }

        // --ez endmls true [--ez resume true] [--es rcsgid <id>] [--es reason <NAME>]: move a
        // conversation to unencrypted (RCC.16 §7.11.2.2), or back with resume. reason names an
        // MlsDowngradeReason (default DEBUG_MENU), which decides whether a commit is sent and
        // whether the conversation may come back. See docs/mls/downgrade.md.
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
                                    + reasonName
                                    + "' — using DEBUG_MENU. Valid names are the 21 in "
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

        // --ez phoenix true [--es rcsgid <id>]: an era advance whose new era starts downgraded; the
        // way out when the end_mls commit cannot land. Try --ez endmls first.
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

        // --ez seedusage true [--es rcsgid <id>] → seed the usage counter so the next send rekeys.
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

        // --es claimkp <e164>: claim all of a peer's KeyPackages and report how many came back (one
        // per device). Consumes the peer's one-time packages.
        if (intent.getStringExtra("claimkp") != null) {
            final Context appCtxCk = context.getApplicationContext();
            final int ckSub = subId;
            final String peer = intent.getStringExtra("claimkp");
            new Thread(new Runnable() {
                @Override public void run() {
                    // Through MlsProviderTransport, which owns the peer pools, and charged to the
                    // ledger.
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

        // --ez publishkp true [--es kpcount N] → app-generated KeyPackages, published via the
        // provider.
        if (intent.getBooleanExtra("publishkp", false)) {
            final Context appCtx5 = context.getApplicationContext();
            final int pSubId = subId;
            int n = 11;
            try { n = Integer.parseInt(intent.getStringExtra("kpcount")); } catch (
                    final Throwable ig) { }
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

        // --ez resync true --es rcsgid <gid> [--es to <peer>]: drive a resync external commit
        // directly.
        if (intent.getBooleanExtra("resync", false)) {
            final Context appCtxRS = context.getApplicationContext();
            final int rsSubId = subId;
            final String rsTo = to;
            final String rsGid = intent.getStringExtra("rcsgid");
            // --ei resyncleaf -1 asks for the plain epoch-advancing external commit instead of the
            // self-remove rejoin.
            final long rsLeaf = intent.getIntExtra("resyncleaf", 0);
            // --ez dryrun true only fetches the GroupInfo and reports whether it carries
            // external_pub; nothing is built, sent or charged. Use it on groups that must not be
            // disturbed.
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

        // --ez cooldowns true [--es rcsgid <gid>] [--es to <e164>]: ask the two durable cooldown
        // stamps (MlsPeerGuard.claimRebuildEpisode and claimReestablishAttempt) for their verdict
        // with the production arguments, stamping if allowed. Transmits nothing; --ez
        // cooldownsreset true undoes the stamps.
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

        // --ez cooldownsreset true [--es rcsgid <gid>] [--es to <e164>]: clear both, as Try again
        // does.
        if (intent.getBooleanExtra("cooldownsreset", false)) {
            final Context appCtxCR = context.getApplicationContext();
            final int crSubId = subId;
            final String crTo = to;
            final String crGid = intent.getStringExtra("rcsgid");
            new Thread(new Runnable() {
                @Override public void run() {
                    // Through the transport, so the canonical key is derived in one place.
                    LogUtil.i(TAG, "DEBUG MLS COOLDOWNSRESET(" + crGid + "/" + crTo + ") → "
                            + MlsProviderTransport.get(appCtxCR, crSubId)
                                    .debugResetDurableCooldowns(crGid, crTo));
                }
            }, "mls-cooldowns-reset").start();
            return;
        }

        // --ez rebuildreset true --es rcsgid <gid>: restore a conversation's rebuild allowance.
        // Test only.
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

        // --ez dropprops true [--es rcsgid <id>]: drop the cached by-reference proposals. A
        // proposal we cannot honour blocks every send until committed, so this also un-wedges a
        // conversation.
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

        // --ez kpvet true: run the RCC.16 A.4.1.2 KeyPackage lifetime check against a locally
        // minted KeyPackage, without claiming a peer's. Raise debug.rcs.mls_kp_min_remaining_days
        // above the minted lifetime to see it refuse.
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

        // --es recvct <content-type> [--es from <e164>] [--es recvfile <path>] [--es body <text>]
        // [--es rcsgid <id>]: run one inbound message with a chosen content type through
        // ReceiveRcsMessageAction, for example an uppercase MIME type (RFC 2045 §5.1). Starts after
        // unframing and decryption. Local only: inserts one row.
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
                        // The same entry RcsCallbackRouter.onIncomingMessage uses.
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

        // --es replayapp <file> --es from <e164> [--es rcsgid <id>] [--es era N] [--es mid <id>]:
        // feed a captured application ciphertext through the inbound decrypt path, with a chosen
        // era, to exercise the park-versus-report decision.
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
                        // onDecryptFailure parks a ciphertext strictly from the future and takes
                        // the RCC.16 §10 path for everything else; the era passed in is what
                        // decides.
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

        // --es replayctrl <file> --es from <e164> [--es rcsgid <id>]: replay a captured inbound
        // control payload through the engine.
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

        // --es removemember <e164> [--es rcsgid <id>]: remove the RCS participant, then commit the
        // MLS removal. A real group change.
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

        // --es changesubj <text> [--es rcsgid <id>]: the RCC.16 §9.7.1.5 subject flow (encrypt, set
        // the group name to the ciphertext, commit the subject commitment, send the key in a
        // FileInfo). A real group change.
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
                    LogUtil.i(TAG, "DEBUG MLS CHANGESUBJ " + subjTxt.length() + "ch → "
                            + (ct == null ? "FAILED" : "OK ciphertext=" + ct.length + "B"));
                }
            }, "mls-change-subject").start();
            return;
        }

        // --es changeicon <file> [--es iconmime <mime>] [--es rcsgid <id>]: the RCC.16 §9.7.1.4
        // icon flow (encrypt, the provider uploads the ciphertext, one profile change carries the
        // reference, the commit and the key delivery). A real group change.
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
                        // A missing file is reported as such, not as an icon-flow failure.
                        LogUtil.w(TAG, "DEBUG MLS CHANGEICON: could not read " + iPath
                                + " — this is the FILE, not the icon flow", t);
                        return;
                    }
                    // The image MIME travels in the RCC.16 §7.8.1 FileInfo; the reference's own
                    // content type is set by the provider.
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

        // --es plainrename <text> [--es rcsgid <id>]: set the group name in the clear, to make a
        // group readable again after an encrypted-subject test.
        if (intent.getStringExtra("plainrename") != null) {
            final Context appCtxN = context.getApplicationContext();
            final int nSubId = subId;
            final String nGid = intent.getStringExtra("rcsgid");
            final String nName = intent.getStringExtra("plainrename");
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean renamed = com.android.messaging.rcs.ProviderTransport
                            .getInstance(appCtxN).renameGroup(nSubId, nGid, nName);
                    LogUtil.i(TAG, "DEBUG RCS PLAINRENAME " + nName.length() + "ch → " + renamed);
                }
            }, "rcs-plain-rename").start();
            return;
        }

        // --ez rekey true: app-built self-update commit. --es addmember <e164>: app-built add
        // commit. Both are real group changes.
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
                    // With --es rcsgid use the group rekey; the 1:1 overload would rekey the peer's
                    // 1:1 instead.
                    final int era = (addWho != null) ? t.addMember(cGid, cTo, addWho)
                            : (cGid == null || cGid.isEmpty()) ? t.rekey(cTo)
                            : t.rekey(cGid, cTo);
                    LogUtil.i(TAG, "DEBUG MLS " + (addWho == null ? "REKEY" : "ADDMEMBER " + addWho)
                            + "(" + cTo + ") → era=" + era);
                }
            }, "mls-commit-probe").start();
            return;
        }

        // --ez ctrl true: read-only MLS control calls through the provider (claim one peer
        // KeyPackage, fetch the server's GroupInfo, print the transport profile).
        if (intent.getBooleanExtra("ctrl", false)) {
            final Context appCtx2 = context.getApplicationContext();
            final int ctrlSubId = subId;
            final String ctrlTo = to;
            new Thread(new Runnable() {
                @Override public void run() {
                    final ProviderTransport pt = ProviderTransport.getInstance(appCtx2);
                    // Through MlsProviderTransport, which owns the peer pools.
                    final byte[] kp = MlsProviderTransport.get(appCtx2, ctrlSubId)
                            .debugClaimOneKeyPackage(ctrlTo);
                    LogUtil.i(TAG, "DEBUG MLS CTRL claimPeerKeyPackage(" + ctrlTo + ") → "
                            + (kp == null ? "null" : kp.length + "B"));
                    // Through MlsProviderTransport, which owns the GroupInfo fetch; exempt from the
                    // ledger.
                    final org.lineageos.rcs.provider.RcsMlsControlResult gi =
                            MlsProviderTransport.get(appCtx2, ctrlSubId).debugGroupInfo(ctrlTo);
                    LogUtil.i(TAG, "DEBUG MLS CTRL getMlsGroupInfo(" + ctrlTo + ") → " + gi);
                    LogUtil.i(TAG, "DEBUG MLS CTRL profile → "
                            + pt.getMlsTransportProfile(ctrlSubId));
                }
            }, "mls-ctrl-probe").start();
            return;
        }

        // --ez gate true: run the real eligibility gate (E2eeSendGate.resolveForSend) and log its
        // verdict. Sends nothing.
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

        // Default send path: the only code here that needs both `to` and `body`.
        if (TextUtils.isEmpty(to) || body == null) {
            LogUtil.w(TAG, "DEBUG SEND_TEST_RCS: no arm extra matched, so this is the plain send "
                    + "path, which needs --es to <e164> --es body <text>. Nothing sent.");
            return;
        }

        // Resolve the scheme through the real gate, as InsertNewMessageAction does, so a downgrade
        // to plaintext is visible in the log.
        String scheme = null;
        if (e2ee) {
            scheme = E2eeSendGate.get()
                    .resolveForSend(/*conversationId=*/ "debug-" + to, subId, to,
                            /*isGroup=*/ false);
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

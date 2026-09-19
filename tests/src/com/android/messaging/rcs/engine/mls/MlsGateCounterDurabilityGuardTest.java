/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every gate counter in the MLS subsystem is durable or declares why not. A gate is a {@code static
 * final} scalar (or an {@code MlsConfig} field) used as an operand of a relational or equality
 * operator, or inside the paren-matched arguments of a method some engine class declares returning
 * {@code boolean}; every such threshold needs an {@link #INVENTORY} row, and its site count is
 * pinned per constant. See docs/testing.md and docs/mls/budgets.md.
 */
public final class MlsGateCounterDurabilityGuardTest {

    private static final String TOOL = "tools/mls/transport-classify.py";
    private static final String E2EE = "src/com/android/messaging/rcs/e2ee/";
    /**
     * The self-test harness is its own APK, outside {@code src/}; its thresholds are still covered.
     */
    private static final String SELFTEST_E2EE = "selftest/src/com/android/messaging/rcs/e2ee/";
    private static final String ENGINE_DIR = "engine/src/com/android/messaging/rcs/engine/mls";
    /** The provider MLS layer's root, walked recursively. See {@link #providerLayerOwners}. */
    private static final String PROVIDER_MLS_DIR = "src/com/android/messaging/rcs/e2ee";
    private static final String TRANSPORT = "MlsProviderTransport";

    private enum Durability { DURABLE_RECORD, DURABLE_PREF, TRANSIENT_DECLARED,
        TRANSIENT_UNDECLARED, NOT_A_GATE, WIRE }

    /**
     * One threshold: where it is declared, how many code sites it has, and what backs its counter.
     */
    private static final class Row {
        final String owner;          // the declaring class
        final String constant;
        final int sites;             // code (not javadoc) reference sites
        final Durability durability;
        final String counter;        // what the threshold bounds
        final String evidence;       // a record class, a Class.PREF_ key, or the reason
        final String restartTest;    // DURABLE_RECORD only: the host test that proves it survives

        Row(final String owner, final String constant, final int sites,
                final Durability durability, final String counter, final String evidence,
                final String restartTest) {
            this.owner = owner;
            this.constant = constant;
            this.sites = sites;
            this.durability = durability;
            this.counter = counter;
            this.evidence = evidence;
            this.restartTest = restartTest;
        }

        String key() { return owner + "." + constant; }
    }

    private static Row durableRecord(final String owner, final String c, final int n,
            final String counter, final String record, final String test) {
        return new Row(owner, c, n, Durability.DURABLE_RECORD, counter, record, test);
    }

    private static Row durablePref(final String owner, final String c, final int n,
            final String counter, final String prefKey) {
        return new Row(owner, c, n, Durability.DURABLE_PREF, counter, prefKey, "");
    }

    private static Row transientUndeclared(final String owner, final String c, final int n,
            final String counter, final String whatItLeavesUnbounded) {
        return new Row(owner, c, n, Durability.TRANSIENT_UNDECLARED, counter,
                whatItLeavesUnbounded, "");
    }

    private static Row transientDeclared(final String owner, final String c, final int n,
            final String counter, final String whyItIsCorrectlyInMemory) {
        return new Row(owner, c, n, Durability.TRANSIENT_DECLARED, counter,
                whyItIsCorrectlyInMemory, "");
    }

    private static Row notAGate(final String owner, final String c, final int n,
            final String reason) {
        return new Row(owner, c, n, Durability.NOT_A_GATE, "—", reason, "");
    }

    /**
     * A value fixed outside us (a spec, a peer, a platform ABI). Such a value may sit at a gate
     * site as an equality check, so it is not {@code NOT_A_GATE}; its attack is {@link
     * #nothingClassifiedWireIsOrderCompared}. Whether a number is ours or the spec's is not in the
     * source, so this verdict is recorded, not derived.
     */
    private static Row wire(final String owner, final String c, final int n,
            final String whoFixesTheValue) {
        return new Row(owner, c, n, Durability.WIRE, "—", whoFixesTheValue, "");
    }

    /** Every threshold in the subsystem, grouped by verdict. */
    private static final Row[] INVENTORY = {
        // Durable in an engine record, restart-tested. A bound lives with the counter it bounds.
        durableRecord("MlsPendingOperation", "PENDING_OP_MAX_ATTEMPTS", 3,
                "MlsPendingOperation.attemptCount", "MlsPendingOperation",
                "MlsConversationRecordTest"),
        durableRecord("MlsPendingOperation", "PENDING_OP_MAX_AGE_MS", 2,
                "MlsPendingOperation.claimedAtMs", "MlsPendingOperation",
                "MlsConversationRecordTest"),
        // RCC.16 §10.3's chain cap is the default of mCfg.ftdMaxAttempts, which has its own row;
        // both rows are kept so a default nothing reads and a gate reading something else cannot
        // hide each other. Zero sites: its only reader is MlsConfig's constructor, and MlsConfig is
        // not a threshold owner.
        durableRecord("MlsFtdEscalation", "MAX_FTD_ATTEMPTS", 0,
                "MlsConversationRecord.ftdResendCounts", "MlsConversationRecord",
                "MlsConversationRecordTest"),
        durableRecord("MlsFtdEscalation", "ESCALATE_AT", 4,
                "MlsResendLedger repeat-resend rows for (peer, conversation) in WINDOW_MS — a SQL "
                + "table, durable by construction because the count is derived from rows",
                "MlsResendRecord", "MlsResendRecordTest"),
        notAGate("MlsFtdEscalation", "WINDOW_MS", 1,
                "a window LENGTH subtracted in windowStart(); the rows it windows are the durable "
                + "ledger's, and ESCALATE_AT is the bound over them"),
        durableRecord("MlsPeerGuard", "MAX_ERA_ADVANCES_PER_HOUR", 1,
                "MlsEraBudgetRecord.countWithin(HOUR_MS)", "MlsEraBudgetRecord",
                "MlsEraBudgetRecordTest"),
        durableRecord("MlsPeerGuard", "MAX_ERA_ADVANCES_PER_DAY", 1,
                "MlsEraBudgetRecord.countWithin(DAY_MS)", "MlsEraBudgetRecord",
                "MlsEraBudgetRecordTest"),
        durableRecord("MlsPeerGuard", "MAX_CONSECUTIVE_PEER_FAILURES", 1,
                "MlsPeerHealthRecord consecutive-failure count", "MlsPeerHealthRecord",
                "MlsPeerHealthRecordTest"),
        // The two window budgets' thresholds live in the engine policy both adapters delegate to;
        // MlsWindowBudgetTest asserts the property through the shipped policy at the shipped
        // numbers.
        durableRecord("MlsWindowBudget", "REBUILD_MAX_PER_WINDOW", 1,
                "MlsRebuildWindowRecord.count()", "MlsRebuildWindowRecord",
                "MlsWindowBudgetTest"),
        durableRecord("MlsWindowBudget", "EXTERNAL_COMMIT_MAX_PER_DAY", 1,
                "MlsRebuildWindowRecord.count()", "MlsRebuildWindowRecord",
                "MlsWindowBudgetTest"),

        // Durable in SharedPreferences.

        // The arithmetic is in MlsKeyPackagePolicy; the stamps are preferences the transport
        // writes.
        durablePref("MlsKeyPackagePolicy", "KP_REPUBLISH_MS", 1, "the last KeyPackage publish",
                "MlsKeyPackagePool.PREF_LAST_KP_PUBLISH"),
        durablePref("MlsKeyPackagePolicy", "KP_REPLENISH_AT", 1, "our remaining published pool",
                "MlsKeyPackagePool.PREF_KP_REMAINING"),
        durablePref("MlsKeyPackagePolicy", "POOL_REPAIR_MIN_INTERVAL_MS", 2,
                "the last pool repair", "MlsKeyPackagePool.PREF_LAST_POOL_REPAIR"),
        durablePref("MlsMetadataKeysPolicy", "RETAINED_CONTENT_KEYS_PER_SLOT", 2,
                "the per-slot retention order list", "MlsProviderTransport.PREFS"),

        // Declared transient rows follow a directional rule: a bound whose loss lets a loop run
        // more (a spent allowance refilled, a suppressor reset) must outlive the process; one whose
        // loss makes us more patient, or whose counter dies with the thing it counts, must not.
        //
        // REKEY_AFTER_SENDS is durable: noteSendAndMaybeRekey calls putGroup, which writes both
        // send counters into MlsConversationRecord with commit(), and loadFromRecord reads them
        // back.
        durableRecord("MlsRekeyPolicy", "REKEY_AFTER_SENDS", 6, "Group.sendsSinceLeafRotation",
                "MlsConversationRecord", "MlsConversationRecordTest"),
        // The durable cooldown windows are spent through MlsPeerGuard's claim; the debug probe
        // calls the same claim and is a real door, so it reads the window through an accessor.
        durableRecord("MlsWindowBudget", "REBUILD_EPISODE_MS", 1,
                "the rebuild-episode stamp, MlsPeerGuard K_EPISODE/<conversation key>",
                "MlsCooldownRecord", "MlsCooldownRecordTest"),
        durableRecord("MlsReestablishPolicy", "REESTABLISH_COOLDOWN_MS", 1,
                "the re-establish stamp, MlsPeerGuard K_REESTABLISH/<peer>",
                "MlsCooldownRecord", "MlsCooldownRecordTest"),

        // In memory on purpose, with the reason recorded here.
        transientDeclared("MlsRecoveryPolicy", "UNCONVERGED_BEFORE_REBUILD", 2,
                "ConvState.consecutiveUnconverged",
                "it drives TOWARD a repair, so a restart makes us MORE patient. Losing it costs a "
                + "few more messages before the rebuild, and can never buy an extra attempt: what "
                + "must survive is the RATE BOUND on the repair itself, and both of those are "
                + "durable (G2's era budget and MlsRebuildLimiter's window). The direction "
                + "argument, on the counter that carries it"),
        transientDeclared("MlsSelfHealPass", "PARKS_BEFORE_UNSTICKING_A_HEAL", 1,
                "ConvState.consecutiveParksAtSameMoment",
                "same direction. Reaching it KILLS a stuck self-heal, which is a purely local act "
                + "that lets the honest FTD path run — so a restart resetting it defers that, never "
                + "repeats it. Nothing peer-facing is downstream of the counter itself; the FTD and "
                + "the rebuild past it have their own durable bounds. And the moment it is counted "
                + "against (lastParkGroupMoment) plus the parked messages themselves die with the "
                + "same process, so a persisted count would be a run of parks at a moment nothing "
                + "remembers"),
        transientDeclared("MlsRecoveryPolicy", "GATE_DEADLINE_MS", 1, "ConvState.gateOpenedAt",
                "the counter BLOCKS our own sends rather than authorising anything, so losing it "
                + "RELEASES the gate — which is precisely what the deadline itself does on expiry, "
                + "for the reason sendBlockedByGate states: 'a generation the peer ignores is "
                + "strictly better than a conversation that never sends again'. A restart can only "
                + "make us send sooner, and it spends no allowance on anyone. Persisting it would "
                + "make a restart able to hold a conversation shut, which is the outcome this "
                + "deadline exists to prevent"),
        transientDeclared("MlsRecoveryPolicy", "MAX_GATED_RESENDS", 2, "ConvState.gatedResends",
                "the counter IS the collection: it bounds the depth of an in-memory ArrayDeque on "
                + "the same object. A restart empties the queue and the count together, so nothing "
                + "is handed back — there is no allowance to spend against a queue that no longer "
                + "exists. Persisting the bound without persisting the parked resends would bound a "
                + "queue that is not there, which is worse than not bounding it"),
        transientDeclared("MlsMetadataKeysPolicy", "MAX_PENDING_SUBJECT", 1,
                "ConvState.pendingSubject across mConv",
                "the same shape one level up: conversationsHoldingASubject() counts the mConv "
                + "entries holding an in-memory byte[], and a restart drops every one of them, so "
                + "the count and the thing counted die together. What it bounds is app-level "
                + "metadata whose key may never arrive — dropping one costs a subject line, and "
                + "nothing escalates on overflow"),
        // Two sites, one gate: the second is the refusal log line printing the budget.
        transientDeclared("MlsMetadataKeysPolicy", "MAX_PENDING_ICON_BYTES", 2,
                "ConvState.pendingIcon across mConv, summed in BYTES",
                "the icon twin of MAX_PENDING_SUBJECT and transient for the same reason — "
                + "bytesHoldingAnIcon() sums in-memory byte[]s that a restart drops along with the "
                + "count. What differs is the UNIT, and that is the whole reason it is a separate "
                + "constant rather than a second user of the subject's: a held subject is dozens of "
                + "bytes so a holder count bounds the memory implicitly, while an icon is four or "
                + "five orders of magnitude larger and eight holders would be megabytes a count "
                + "cannot see. Overflow drops the NEWCOMER rather than evicting a hold, because the "
                + "older one is likelier to be about to open, and nothing escalates either way"),

        // Not a gate.
        notAGate("MlsRekeyPolicy", "REKEY_REFUSED_BACKOFF_SENDS", 1,
                "an arithmetic offset subtracted from REKEY_AFTER_SENDS to back a refused rekey "
                + "off; it bounds no counter of its own"),
        // A gate, but transient on purpose: the counter is a per-pass local while the sweep
        // position is durable.
        new Row("MlsMaintenancePolicy", "SWEEP_PAGE", 1, Durability.TRANSIENT_DECLARED,
                "the per-pass local `maintained`",
                "a page SIZE, so the counter is born and dies inside one maintenance pass. What "
                + "must survive a restart is the POSITION, and PREF_SWEEP_CURSOR is durable — a "
                + "restart re-reads the cursor and continues, it does not re-sweep from zero", ""),
        notAGate("MlsPeerGuard", "HOUR_MS", 2,
                "a window LENGTH handed to MlsEraBudgetRecord.countWithin; the counter it windows "
                + "is the durable record"),
        notAGate("MlsPeerGuard", "DAY_MS", 4, "ditto"),
        notAGate("MlsWindowBudget", "REBUILD_WINDOW_MS", 1,
                "a window LENGTH handed to MlsRebuildWindowRecord.rolled"),
        notAGate("MlsWindowBudget", "EXTERNAL_COMMIT_WINDOW_MS", 1, "ditto"),

        // Reading its tunable from MlsConfig.
        durableRecord(TRANSPORT, "mCfg.selfHealRetryLimit", 1,
                "MlsConversationRecord.selfHealBudget.retryCount", "MlsConversationRecord",
                "MlsConversationRecordTest"),
        durableRecord(TRANSPORT, "mCfg.selfHealWindowMs", 4,
                "MlsConversationRecord.selfHealBudget window start", "MlsConversationRecord",
                "MlsConversationRecordTest"),
        // RccMlsBody's framing constants are compared for equality (a version byte and a
        // payload-type discriminator), so they are WIRE, not NOT_A_GATE; neither bounds a counter.
        wire("RccMlsBody", "HEADER_VERSION_1", 1,
                "the RCC.16 secret-payload framing: the 2-byte header version this parser accepts"),
        wire("RccMlsBody", "SECRET_PAYLOAD_TYPE_TEXT", 1,
                "the RCC.16 secret-payload framing: the payload-type discriminator for text"),
        // selfHeal's return sentinel, compared for equality; it separates "the heal could not read
        // the server" from a bare -1.
        wire("MlsRecoveryPolicy", "HEAL_LOOK_UNAVAILABLE", 6,
                "selfHeal's return value space: a sentinel compared for equality, not a threshold"),
        wire(TRANSPORT, "mCfg.metadataKeysExtType", 1,
                "RCC.16's metadata-keys GroupContext extension TYPE. The value is the spec's; we "
                + "compare it for equality to recognise the extension, which is a protocol check "
                + "and not a bound on anything we count"),
        // IDENTITY_REFRESH_MS is a knob so the refresh can be verified without a week of wall
        // clock.
        durablePref(TRANSPORT, "mCfg.identityRefreshMs", 1, "the last identity refresh",
                "MlsIdentityRefresh.PREF_LAST_IDENTITY_REFRESH"),
        // The failure-side sibling: the weekly window records successes, so it cannot rate-limit a
        // call that always fails. A separate key, because onIdentityChanged clears the success
        // stamp to force a re-read, and stamping that key on attempt would re-write what was just
        // cleared.
        durablePref(TRANSPORT, "mCfg.identityRetryBackoffMs", 1, "the last FAILED identity attempt",
                "MlsIdentityRefresh.PREF_LAST_IDENTITY_ATTEMPT"),
        durableRecord(TRANSPORT, "mCfg.ftdMaxAttempts", 1, "MlsConversationRecord.ftdResendCounts",
                "MlsConversationRecord", "MlsConversationRecordTest"),
        // The RCC.16 remaining-lifetime floor (A.4.1.2, A.4.1.3, A.4.2.2, A.4.3.1 §1(a)): a spec
        // value the server also enforces, bounding no counter of ours.
        //
        // Site count maintained by hand: everyThresholdHasExactlyTheSitesItIsRecordedWith skips
        // mCfg. rows, so no test contradicts this number. Per method: reportCredentialRefusal 3,
        // logFloorReport 3, membershipChangeAllowedByFloor 2, dumpKeyPackageCount 1,
        // rosterFloorReport 1, dumpServerValidity 1, floorRebuild 1, maybeFloorRebuild 1,
        // claimRosterForAdvance 1. The consume-side gate is MlsKeyPackagePolicy.keyPackageUsable,
        // which this row does not scan.
        wire(TRANSPORT, "mCfg.kpMinRemainingDays", 14,
                "RCC.16 v4.0 — A.4.1.2/A.4.1.3 (the client tier), A.4.2.2 (the KDS must not return "
                + "a KeyPackage inside it) and A.4.3.1 §1(a) (the RCS SPN, on every Commit). The "
                + "sysprop exists to test either side of the line, not to choose the number"),

        // The rest of the provider MLS layer: constants reachable from none of the terms above.

        // A store's own preference entry count; the counter is the file, so it is durable by
        // construction.
        durablePref("MlsCiphertextCache", "MAX_ENTRIES", 3,
                "the sealed-ciphertext store's own entry count, p.getAll().size()",
                "MlsCiphertextCache.PREFS"),
        durablePref("MlsPendingBodyStore", "MAX_ENTRIES", 4,
                "the pending-body store's own entry count, p.getAll().size()",
                "MlsPendingBodyStore.PREFS"),
        durablePref("MlsRendezvousStore", "MAX_ROWS", 2,
                "the rendezvous store's own row count, p.getAll().size()",
                "MlsRendezvousStore.PREFS"),

        // Fixed outside us.
        wire("MlsCarrierTransport", "ERA_INITIAL", 3,
                "RCC.16 §7.11.1.1: the Era ordinal of a fresh group is 1. The same value the "
                + "transport declares under the same name and the tool's WIRE_CONSTANTS exempts "
                + "there — this row exists because that exemption is now scoped to the transport, "
                + "so a policy tunable cannot inherit a wire name's pass by being declared next to "
                + "one"),
        wire("MlsEnrollDebugReceiver", "ERA", 1,
                "the RCC.16 Era GroupContext extension TYPE, 0xF001 — "
                + "MlsTransportDiagnostics.ERA_EXT_TYPE under another name, handed to createGroup "
                + "as the extension to write"),
        wire("MlsSelfTestReceiver", "ERA", 1, "ditto, on the self-test path"),
        wire("RcsE2eeScheme", "MLS_CIPHERSUITE_P256_AES128", 1,
                "the MLS ciphersuite identifier. Equality-compared twice in the transport against "
                + "info.cipherSuite, which arrives in a peer's KeyPackage — the peer picks the "
                + "number and RFC 9420 §7.2 makes the comparison mandatory"),
        wire("MlsCredential", "GOOGLE_VENDOR_ID", 4,
                "the vendorId written into the GSMA participant-info certificate extension "
                + "(2.23.146.2.1.4) and into the TBS the KDS signs. A value the verifier reads, "
                + "not one we choose. 5 -> 4 when the BouncyCastle minter was replaced: generateCa "
                + "and issueIntermediate folded into one mintCa, so the CA path reads it once "
                + "instead of twice. Same bound, one fewer door"),
        // The two lifetime caps rest on the RCC.16 citation in their javadoc; the validator that
        // enforces them is in the Rust core, so they are not machine-checkable here.
        wire("MlsCredential", "ROOT_LIFETIME_S", 1,
                "RCC.16 A.1.5's root-CA lifetime cap. NOT machine-checkable in this tree: the "
                + "validator that enforces it is in the Rust core, so this row rests on the spec "
                + "citation in the constant's own javadoc"),
        wire("MlsCredential", "ICA_LIFETIME_S", 1,
                "RCC.16 A.2.5's intermediate-CA lifetime cap, and the same caveat — 1825 rather "
                + "than the permitted 1827 to keep the 1-hour backdate inside the allowance, which "
                + "a device run found and this tree cannot re-derive"),

        // Bounds nothing we count.
        notAGate("RcsKdsClient", "TIMEOUT_MS", 2,
                "a socket timeout handed to HttpURLConnection.setConnectTimeout/setReadTimeout. "
                + "The clock it bounds begins and ends inside one call, so there is no counter to "
                + "survive a restart — a process that dies mid-request loses the request too"),
        notAGate("MlsCarrierTransport", "KP_POOL", 2,
                "a generation COUNT handed to generateKeyPackages(n) — how many KeyPackages one "
                + "call mints, not a bound on how many exist. The pool's actual replenishment gate "
                + "is MlsKeyPackagePolicy.KP_REPLENISH_AT, which has its own row above"),
        notAGate("MlsCredential", "LEAF_LIFETIME_S", 2,
                "an X.509 notAfter offset. The SPEC bounds it — RCC.16 A.4.1.2/A.4.2.2 refuse a "
                + "KeyPackage with under 30 days left, which MlsSession states at its own claim-time "
                + "check — but 60 days inside that window is ours, and it gates no counter: the "
                + "certificate carries its own expiry and the certificate is what persists"),
        notAGate("MlsStalledNotifier", "ID_BASE", 1,
                "a notification-id NAMESPACE, OR'd with a conversation-key hash so this class's "
                + "notifications cannot collide with another feature's. An identifier, not a bound"),

        // Found by term (b) of providerLayerOwners and by scoping the wire-name exemption to the
        // transport.

        // RcsCallbackRouter is the provider callback surface, outside e2ee, and declares two
        // tunables on the MLS inbound path.
        transientDeclared("RcsCallbackRouter", "NEGATIVE_DELIVERY_DEDUPE_MS", 1,
                "RcsCallbackRouter.mNegativeDeliverySeen, an in-memory LinkedHashMap capped at 512",
                "the same DIRECTIONAL rule the rows above use, and it is worth stating carefully "
                + "because what this gate protects is expensive: the remedy behind it is "
                + "onPeerReportedFailure, the §10.3 resend, and a duplicate one can make "
                + "a working resend look like a failing one and escalate to an era advance that "
                + "tears down a healthy conversation. What a restart hands back is at "
                + "most ONE extra remedy, and the duplicate this suppresses is the provider "
                + "dispatching the same report twice on two threads MILLISECONDS apart — a process "
                + "that dies between them loses both dispatches rather than duplicating one. Every "
                + "scarce thing downstream is separately and DURABLY bounded: the repeat-resend "
                + "count is MlsResendLedger's SQL rows, its escalation is MlsFtdEscalation."
                + "ESCALATE_AT over those rows, and the era advance a duplicate could reach is "
                + "refused by MlsPeerGuard's durable era budget. So persisting this would buy "
                + "nothing the era budget does not already refuse. NOT verified on device: that "
                + "the provider cannot re-dispatch a report across our process restart"),
        notAGate("RcsCallbackRouter", "JOIN_RACE_WINDOW_MS", 1,
                "a bounded WAIT handed to awaitJoinAndRetryDecrypt — how long a failed decrypt "
                + "waits for an in-flight join before it becomes an FTD (observed race 19 ms). It "
                + "bounds no counter. It IS an invariant-I1 subject rather than an I3 one: the "
                + "question for a bounded wait is whether its bound is reachable inside the budget "
                + "that charges its iterations, and this one is charged by nothing — it delays a "
                + "report on a message that has already failed"),
        transientDeclared("RcsCallbackRouter", "MAX_INBOUND_FILE_BYTES", 2,
                "the byte count of ONE inbound file copy into scratch storage, a local of "
                + "ingestFdToScratch",
                "the count is born and dies inside one call and bounds that copy only: a restart "
                + "abandons the copy it was counting, and the redelivered file starts a new one "
                + "that the same cap bounds again. Nothing accumulates across calls, so there is "
                + "nothing to persist"),

        // The name ERA_INITIAL is in the tool's WIRE_CONSTANTS, which is scoped to the transport,
        // so an engine class declaring it needs its own row.
        wire("MlsReestablishPolicy", "ERA_INITIAL", 2,
                "RCC.16's initial Era ordinal, mirroring the transport's constant of the same "
                + "name. serverEra >= ERA_INITIAL asks whether the server reported an era at or "
                + "above the first legal one, i.e. whether the server holds this conversation at "
                + "all — a protocol validity test, not a bound on anything we count. It is also "
                + "the COUNTEREXAMPLE nothingClassifiedWireIsOrderCompared is pinned against. "
                + "1 → 2: forkLine names the era a carry-less re-establish would be "
                + "BORN at, which is this same ordinal used as a LABEL in a log line rather than "
                + "as a test — no comparison, no counter, so the durability answer is unchanged. "
                + "The DECISION beside it (forksAtEraInitial) deliberately does not read the "
                + "constant at all: it asks the Verdict, so a second order-comparison against this "
                + "ordinal was not added and the pin above still describes the only one"),
    };

    /**
     * A constant the layer reads but does not declare: a {@code Class.CONSTANT} reference in an
     * owner's code to a provider class outside the layer. Granularity is the constant, not the
     * class, so an unrelated class's other constants stay out of the inventory; one never read in
     * the layer gates nothing and is correctly invisible.
     */
    private static final class OutsideRef {
        final String owner;
        final String constant;
        final int refs;              // qualified references from inside the layer
        final Durability durability;
        final String reason;

        OutsideRef(final String owner, final String constant, final int refs,
                final Durability durability, final String reason) {
            this.owner = owner;
            this.constant = constant;
            this.refs = refs;
            this.durability = durability;
            this.reason = reason;
        }

        String key() { return owner + "." + constant; }
    }

    /** Every one is an identifier or a query bound. */
    private static final OutsideRef[] OUTSIDE_LAYER_REFERENCES = {
        new OutsideRef("BugleNotifications", "UPDATE_ALL", 1, Durability.NOT_A_GATE,
                "a bitmask of which notification surfaces to refresh, handed to "
                + "BugleNotifications.update. A flag word, not a bound"),
        new OutsideRef("ParticipantData", "DEFAULT_SELF_SUB_ID", 1, Durability.NOT_A_GATE,
                "the sentinel sub id meaning 'the default SIM'. An identifier"),
        new OutsideRef("RcsIosTapback", "SEARCH_LIMIT", 1, Durability.NOT_A_GATE,
                "how many recent rows the tapback matcher scans backwards. It bounds one query's "
                + "result set, which is born and dies inside that query — nothing counts across "
                + "calls, so there is no counter to survive a restart"),
        new OutsideRef("UpdateRcsMessageStatusAction", "KIND_GROUP_IMDN", 1, Durability.WIRE,
                "a message-kind discriminator on the action's own contract, chosen by that class "
                + "and read by us. Not a value we may vary"),
        new OutsideRef("UpdateRcsMessageStatusAction", "KIND_IMDN", 1, Durability.WIRE, "ditto"),
    };

    /**
     * Per item and in both directions: an unclassified reference fails, a row the layer stopped
     * reading fails, and a row that gains a reference fails.
     */
    @Test
    public void everyConstantTheLayerReadsFromOutsideItIsClassified() throws IOException {
        final Map<String, Integer> found = constantsReadFromOutsideTheLayer();
        assertTrue("the layer reads NO static-final scalar from any provider class outside it, and "
                + "it reads six. The qualified-reference scan has gone stale and term (c) is "
                + "silently enumerating nothing.", found.size() >= 4);

        final Map<String, Integer> rows = new LinkedHashMap<>();
        for (final OutsideRef r : OUTSIDE_LAYER_REFERENCES) rows.put(r.key(),
                Integer.valueOf(r.refs));

        final List<String> unclassified = new ArrayList<>();
        final List<String> moved = new ArrayList<>();
        for (final Map.Entry<String, Integer> e : found.entrySet()) {
            final Integer pinned = rows.get(e.getKey());
            if (pinned == null) {
                unclassified.add(e.getKey() + " (" + e.getValue() + " references from the layer)");
            } else if (!pinned.equals(e.getValue())) {
                moved.add(e.getKey() + ": " + pinned + " → " + e.getValue());
            }
        }
        final List<String> stale = new ArrayList<>();
        for (final String k : rows.keySet()) {
            if (!found.containsKey(k)) stale.add(k);
        }
        if (!unclassified.isEmpty()) {
            fail("The MLS provider layer reads these constants from classes OUTSIDE it, and none "
                    + "has a row: " + unclassified + ". Say which counter each bounds and whether "
                    + "that counter survives a restart — a tunable is not out of scope because it "
                    + "is declared in somebody else's file.");
        }
        if (!moved.isEmpty()) {
            fail("These outside-the-layer constants gained or lost doors into the layer: " + moved
                    + ". A new reference is a new gate on a bound this guard does not own the "
                    + "declaration of; re-answer it and update the pin in the same commit.");
        }
        if (!stale.isEmpty()) {
            fail("OUTSIDE_LAYER_REFERENCES rows for constants the layer no longer reads: " + stale
                    + ". Delete the row.");
        }
    }

    /** {@code Class.CONSTANT -> qualified references from the layer}, for provider non-owners. */
    private static Map<String, Integer> constantsReadFromOutsideTheLayer() throws IOException {
        final Set<String> owners = new HashSet<>(providerLayerOwners());
        final Pattern ref = Pattern.compile("\\b([A-Z]\\w*)\\.([A-Z][A-Z0-9_]{2,})\\b");
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final String owner : providerLayerOwners()) {
            final Matcher m = ref.matcher(codeOf(owner));
            while (m.find()) {
                final String cls = m.group(1);
                final String member = m.group(2);
                if (owners.contains(cls)) continue;                 // already a declaration subject
                if (SourceScan.engineClass(cls) != null) continue;   // engine, and host-tested
                final String src = SourceScan.providerClassSource(cls);
                // JDK, android, or a nested type
                if (src == null) continue;
                if (!SourceScan.scalarConstantsDeclaredIn(src).contains(member)) continue;
                final String key = cls + "." + member;
                final Integer n = out.get(key);
                out.put(key, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
            }
        }
        return out;
    }

    /**
     * Capacity bounds written as a bare literal, which no named-constant enumeration can see. Found
     * by {@link #everyEvictionCapInTheTransportIsClassified} through the JDK contract they
     * override.
     */
    private static final class LiteralCap {
        final int value;
        final String counter;
        final String reason;

        LiteralCap(final int value, final String counter, final String reason) {
            this.value = value;
            this.counter = counter;
            this.reason = reason;
        }
    }

    private static final LiteralCap[] LITERAL_CAPS = {
        new LiteralCap(256, "ConvState.heardAtSeq — the advancer-liveness ledger",
                "TRANSIENT_DECLARED, and it must stay that way: an empty ledger "
                + "classifies every member UNKNOWN and draws the unchanged base budget, so losing "
                + "it across a restart makes us MORE patient and a restart can never buy an early "
                + "takeover. The cap itself bounds MEMORY, not an operation — the keys come off the "
                + "wire (fromE164 on an inbound the server routed to us), so the map is not bounded "
                + "by the roster. 256 is far above any RCS group size, so eviction is unreachable "
                + "for a real conversation; if it ever did evict, the evicted member reads as "
                + "NEVER_HEARD and draws a SHORTER wait, which is why the cap is set where a real "
                + "group cannot reach it rather than tuned tight"),
        new LiteralCap(64, "ConvState.decryptFailedAt — the moment each failed application "
                + "decrypt saw, taken once by the park decision",
                "TRANSIENT_DECLARED: it is not a gate and counts nothing. An entry lives from a "
                + "failed decrypt to the park decision on the same inbound, milliseconds later. "
                + "Losing it (a restart, or eviction by 64 failures in between) makes the decision "
                + "compare against the group's current moment, which is what it did before the "
                + "map existed: a message a commit made readable in that window takes the FTD "
                + "path and is resent, never lost. The keys come off the wire, so the cap bounds "
                + "memory"),
    };

    /** Gates with an in-memory counter and no declared reason. This number may only fall. */
    private static final int TRANSIENT_UNDECLARED_TODAY = 0;

    /** Every enumeration this class rests on must find something. */
    @Test
    public void theSubjectsOfThisGuardAreAllNonEmpty() throws IOException {
        // What remains in the transport is the wire/ABI list, pinned name by name by
        // MlsTransportPolicyConstantGuardTest.
        assertTrue("no constants declared in " + TRANSPORT + " — the declaration shape changed",
                declaredIn(TRANSPORT).size() >= 6);
        final List<String> guards = guardClasses();
        assertTrue("no guard classes parsed out of " + TOOL
                + "'s GUARD regex — the inventory would then "
                + "silently stop watching MlsPeerGuard, MlsRebuildLimiter and "
                + "MlsExternalCommitBudget, which are the three classes this work is named after",
                guards.size() >= 3);
        for (final String g : guards) {
            if (!declaredIn(g).isEmpty()) continue;
            // A store adapter declares no thresholds of its own; that is acceptable only while it
            // names an engine policy that does, derived from its source.
            final List<String> behind = enginePoliciesBehind(g);
            assertFalse(g + " declares no threshold and names no engine policy that declares one — "
                    + "either it stopped bounding anything, or its bound moved somewhere the inventory is "
                    + "not watching, or the locator is wrong. All three must fail.",
                    behind.isEmpty());
        }
        assertTrue("MlsConfig exposes no scalar fields by reflection — where tunables live is "
                + "invisible to this guard", configFields().size() >= 10);
        assertTrue("no engine class declares a boolean method — rule (2) is dead and four "
                + "thresholds would silently read as 'not a gate'",
                booleanMethodNames().size() > 20);
        // Each term of the provider layer has its own floor, so one cannot go to zero unnoticed.
        assertTrue("no .java found under " + PROVIDER_MLS_DIR + " — the provider half of this "
                + "guard's subject is empty and a tunable declared anywhere in the layer is "
                + "invisible again",
                SourceScan.javaSourcesUnder(PROVIDER_MLS_DIR).size() >= 30);
        assertTrue("no provider source imports com.android.messaging.rcs.engine.mls — term (b) of "
                + "providerLayerOwners finds nothing, so a class that leaves e2ee leaves this "
                + "guard's subject with it",
                providerClassesImportingTheEngine().size() >= 20);
        assertTrue("the provider layer enumerates fewer than 30 classes — allOwners() has fallen "
                + "back to the guard set and the layer-wide hole is open again",
                providerLayerOwners().size() >= 30);
    }

    /** A threshold with no row is a bound whose counter nobody has asked about. */
    @Test
    public void everyThresholdInTheSubsystemIsClassified() throws IOException {
        final Set<String> known = new HashSet<>();
        for (final Row r : INVENTORY) known.add(r.key());

        // The wire/ABI constants come from the same checked-in list
        // MlsTransportPolicyConstantGuardTest uses, so the two guards cannot disagree about which
        // constants are policy.
        final Set<String> wire = new HashSet<>(
                MlsTransportPolicyConstantGuardTest.wireConstants());
        assertTrue("WIRE_CONSTANTS is empty or unparseable in " + TOOL + " — every wire value "
                + "would read as an unclassified threshold", wire.size() >= 6);

        // allOwners() is never empty, so the zero-hit check is over the constants they declare.
        int scannedConstants = 0;
        for (final String owner : allOwners()) scannedConstants += declaredIn(owner).size();
        assertTrue("ZERO HITS MUST FAIL: the owners declare NO constants between them, "
                        + "so the classification loop below never runs and this assertion certifies "
                        + "green having examined nothing. A zero-match scan is a broken scan, not a "
                        + "clean tree.",
                scannedConstants > 0);
        final List<String> unclassified = new ArrayList<>();
        for (final String owner : allOwners()) {
            for (final String c : declaredIn(owner)) {
                // WIRE_CONSTANTS is the transport's exemption list, matched by name, so it applies
                // to the transport only.
                if (TRANSPORT.equals(owner) && wire.contains(c)) continue;
                // A pure alias defers the durability question to where the value is declared;
                // everyAliasPointsAtAConstantThatResolves keeps this skip honest.
                if (isResolvedAlias(owner, c)) continue;
                if (!known.contains(owner + "." + c)) unclassified.add(owner + "." + c);
            }
        }
        for (final String field : configFieldsAtAGateSite()) {
            if (!known.contains(TRANSPORT + ".mCfg." + field)) {
                unclassified.add(TRANSPORT + ".mCfg." + field);
            }
        }
        Collections.sort(unclassified);
        if (!unclassified.isEmpty()) {
            fail("These thresholds have no row in this test's INVENTORY: " + unclassified
                    + ". Add one saying which counter each bounds and whether that counter is "
                    + "DURABLE_RECORD / DURABLE_PREF / TRANSIENT_DECLARED / TRANSIENT_UNDECLARED / "
                    + "NOT_A_GATE. A bound whose counter nobody asked about is the original defect, "
                    + "and enumerating it is invariant I3(b).");
        }
    }

    /**
     * Starts from the tool's {@code POLICY_CONSTANTS} list, which does not shrink when a constant
     * moves, and finds where each is declared today in the engine and {@code e2ee} packages. A name
     * declared nowhere needs no row: {@code MlsTransportPolicyConstantGuardTest} requires it to
     * have zero references in the transport, so it cannot have been replaced by a literal.
     */
    @Test
    public void everyPolicyConstantTheToolNamesHasARow() throws IOException {
        final List<String> policy = MlsTransportPolicyConstantGuardTest.policyConstants();
        assertTrue("POLICY_CONSTANTS is empty or unparseable in " + TOOL + " — this check would "
                + "then walk nothing and pass", policy.size() >= 15);

        final Map<String, String> rows = new LinkedHashMap<>();
        for (final Row r : INVENTORY) {
            if (!r.constant.startsWith("mCfg.")) rows.put(r.constant, r.owner);
        }

        final List<String> missing = new ArrayList<>();
        final List<String> misfiled = new ArrayList<>();
        int located = 0;
        for (final String c : policy) {
            final String owner = declaringOwnerOf(c);
            if (owner == null) continue;              // deleted outright; see the javadoc
            located++;
            final String rowOwner = rows.get(c);
            if (rowOwner == null) {
                missing.add(c + " (now declared in " + owner + ")");
            } else if (!rowOwner.equals(owner)) {
                misfiled.add(c + ": row says " + rowOwner + ", declared in " + owner);
            }
        }
        assertTrue("not one of the " + policy.size() + " policy constants " + TOOL + " names is "
                + "declared anywhere under the engine or e2ee packages — the locator is wrong, not "
                + "the code, and this check is passing on an empty set", located > 0);
        if (!missing.isEmpty()) {
            fail("These policy constants have no INVENTORY row: " + missing + ". A threshold that "
                    + "moves out of the transport must be re-classified where it landed — its "
                    + "counter's durability question does not move with it.");
        }
        if (!misfiled.isEmpty()) {
            fail("These INVENTORY rows name the wrong owner: " + misfiled
                    + ". Point the row at the "
                    + "class that declares the constant today, in the same commit as the move.");
        }
    }

    /**
     * The engine or {@code e2ee} class that declares scalar constant {@code name} today, or null.
     */
    private static String declaringOwnerOf(final String name) throws IOException {
        for (final String dir : new String[] {ENGINE_DIR, E2EE}) {
            for (final String simple : javaFilesIn(dir)) {
                if (declaredIn(simple).contains(name)) return simple;
            }
        }
        return null;
    }

    /** The simple class names of the {@code .java} files in {@code dir}, or empty. */
    private static List<String> javaFilesIn(final String dir) {
        final List<String> out = new ArrayList<>();
        for (final String c : new String[] {dir, "packages/apps/Messaging/" + dir, "../" + dir}) {
            final File d = new File(c);
            if (!d.isDirectory()) continue;
            final String[] names = d.list();
            if (names == null) continue;
            for (final String n : names) {
                if (n.endsWith(".java")) out.add(n.substring(0, n.length() - ".java".length()));
            }
            Collections.sort(out);
            return out;
        }
        return out;
    }

    /** A row for a threshold that is gone would be inherited by the next constant of that name. */
    @Test
    public void everyRowNamesAThresholdThatStillExists() throws IOException {
        final Map<String, Set<String>> declared = new LinkedHashMap<>();
        for (final String owner : allOwners()) declared.put(owner,
                new HashSet<>(declaredIn(owner)));
        final Set<String> configGates = new HashSet<>(configFieldsAtAGateSite());

        final List<String> stale = new ArrayList<>();
        for (final Row r : INVENTORY) {
            if (r.constant.startsWith("mCfg.")) {
                if (!configGates.contains(r.constant.substring("mCfg.".length()))) {
                    stale.add(r.key() + " (no longer at a gate site in the transport)");
                }
                continue;
            }
            final Set<String> d = declared.get(r.owner);
            if (d == null || !d.contains(r.constant)) stale.add(r.key());
        }
        if (!stale.isEmpty()) {
            fail("INVENTORY rows name thresholds that no longer exist: " + stale
                    + ". Delete the row — a classification for a name that is gone is inherited "
                    + "silently by the next constant to take that name.");
        }
    }

    /**
     * Pinned per constant rather than as a total, which could stay put while one gate loses a site
     * and another gains one. A new site is a new door onto the same bound.
     */
    @Test
    public void everyThresholdHasExactlyTheSitesItIsRecordedWith() throws IOException {
        final List<String> moved = new ArrayList<>();
        for (final Row r : INVENTORY) {
            if (r.constant.startsWith("mCfg.")) continue;    // counted by its gate sites, below
            final int n = sitesOf(r);
            if (n != r.sites) moved.add(r.key() + ": " + r.sites + " → " + n);
        }
        if (!moved.isEmpty()) {
            fail("These thresholds gained or lost code reference sites: " + moved
                    + ". A new site is a new door onto the same bound — re-answer the durability "
                    + "question for it, then update the row's site count in the same commit.");
        }
    }

    /**
     * By reflection, so a record that no longer compiles or a deleted restart test cannot leave a
     * row reading as durable on the strength of a string.
     */
    @Test
    public void everyDurableRecordRowResolvesItsRecordAndItsRestartTest() {
        final List<String> broken = new ArrayList<>();
        int checked = 0;
        for (final Row r : INVENTORY) {
            if (r.durability != Durability.DURABLE_RECORD) continue;
            checked++;
            if (SourceScan.engineClass(r.evidence) == null) {
                broken.add(r.key() + " names record " + r.evidence + ", which does not resolve");
            }
            final Class<?> t = SourceScan.engineClass(r.restartTest);
            if (t == null) {
                broken.add(r.key() + " names restart test " + r.restartTest + ", which does not "
                        + "resolve");
            } else if (SourceScan.testMethodCount(t) == 0) {
                broken.add(r.key() + "'s restart test " + r.restartTest + " declares no @Test");
            }
        }
        assertTrue("no DURABLE_RECORD rows — the inventory's whole durable half is gone",
                checked >= 5);
        if (!broken.isEmpty()) fail("Durability evidence that does not exist: " + broken);
    }

    /**
     * Evidence is a dotted {@code Class.MEMBER} that must resolve in that class: a bare key could
     * be satisfied by another class declaring the same name (the transport and the stores both
     * declare {@code PREFS}).
     */
    @Test
    public void everyDurablePrefRowNamesAKeyThatResolvesInTheClassItNames() throws IOException {
        final List<String> broken = new ArrayList<>();
        int checked = 0;
        for (final Row r : INVENTORY) {
            if (r.durability != Durability.DURABLE_PREF) continue;
            checked++;
            final int dot = r.evidence.lastIndexOf('.');
            if (dot < 0) {
                broken.add(r.key() + " names durability evidence '" + r.evidence + "' with no "
                        + "declaring class. Write it as Class.MEMBER — a bare key is answerable by "
                        + "any class that happens to declare that name.");
                continue;
            }
            final String owner = r.evidence.substring(0, dot);
            final String member = r.evidence.substring(dot + 1);
            final String code;
            try {
                code = codeOf(owner);
            } catch (final IOException e) {
                broken.add(r.key() + " names durability evidence in " + owner + ", which is in "
                        + "neither the engine nor the provider");
                continue;
            }
            if (!Pattern.compile("static final String " + Pattern.quote(member) + "\\s*=")
                    .matcher(code).find()) {
                broken.add(r.key() + " claims durability through " + r.evidence + ", and " + owner
                        + " declares no such preference key");
            }
        }
        assertTrue("no DURABLE_PREF rows left", checked >= 3);
        if (!broken.isEmpty()) fail("Preference keys named as durability evidence that do not "
                + "exist: " + broken);
    }

    /**
     * {@code NOT_A_GATE} is the only verdict that ends the question, so it is checked against the
     * structural rule rather than believed.
     */
    @Test
    public void nothingClassifiedNotAGateIsUsedAtAGateSite() throws IOException {
        final Set<String> booleanMethods = booleanMethodNames();
        assertTrue(
                        "ZERO HITS MUST FAIL: booleanMethodNames() scanned from source is EMPTY, so the check below is satisfied "
                        + "by having read nothing. A zero-match scan is a broken scan, not a clean "
                        + "tree.",
                !booleanMethods.isEmpty());
        final List<String> wrong = new ArrayList<>();
        for (final Row r : INVENTORY) {
            if (r.durability != Durability.NOT_A_GATE) continue;
            for (final String[] where : new String[][] {
                    {r.owner, r.constant},
                    {TRANSPORT, r.owner + "." + r.constant} }) {
                if (TRANSPORT.equals(r.owner) && !where[0].equals(r.owner)) continue;
                final String code = codeOf(where[0]);
                for (final Integer at : SourceScan.usesOf(code, where[1])) {
                    final String why = gateSiteKind(code, at.intValue(), where[1], booleanMethods);
                    if (why != null) {
                        wrong.add(r.key() + " is classified NOT_A_GATE and is used at a gate site "
                                + "in " + where[0] + " (" + why + "): "
                                + lineAt(code, at.intValue()));
                    }
                }
            }
        }
        if (!wrong.isEmpty()) {
            fail("These thresholds are recorded as bounding nothing and are being compared or "
                    + "handed to a predicate: " + wrong + ". Re-classify each — the reason on the "
                    + "row is what a reader trusts instead of re-deriving it.");
        }
    }

    /**
     * A value fixed by a peer or spec is matched for equality, so ordering over a {@code WIRE}
     * constant suggests it bounds something. The rule is not absolute (a spec ordinal can be a
     * floor test), so the order-compared rows are pinned per item and the check fails in both
     * directions. It can refute a {@code WIRE} claim, never confirm one.
     */
    @Test
    public void nothingClassifiedWireIsOrderCompared() throws IOException {
        final Pattern ordering = Pattern.compile("(<=|>=|<|>)");
        final Set<String> ordered = new HashSet<>();
        final List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (final Row r : INVENTORY) {
            if (r.durability != Durability.WIRE) continue;
            checked++;
            final String bare = r.constant.startsWith("mCfg.")
                    ? r.constant : r.owner + "." + r.constant;
            for (final String owner : allOwners()) {
                final String code = codeOf(owner);
                final List<Integer> at = new ArrayList<>();
                at.addAll(SourceScan.usesOf(code, bare));
                if (owner.equals(r.owner)) at.addAll(SourceScan.usesOf(code, r.constant));
                for (final Integer i : at) {
                    final int end = i.intValue() + (code.startsWith(bare, i.intValue())
                            ? bare.length() : r.constant.length());
                    if (ordering.matcher(subExpressionBefore(code, i.intValue())).find()
                            || ordering.matcher(subExpressionAfter(code, end)).find()) {
                        ordered.add(r.key());
                        if (!WIRE_ROWS_ORDER_COMPARED.contains(r.key())) {
                            wrong.add(r.key() + " is classified WIRE and is ORDER-compared in "
                                    + owner + ": " + lineAt(code, i.intValue()));
                        }
                    }
                }
            }
        }
        assertTrue("no WIRE rows in the inventory — either the verdict has left the vocabulary or "
                + "the wire values of the provider layer have stopped being classified, and this "
                + "check is passing on an empty set", checked >= 5);
        if (!wrong.isEmpty()) {
            fail("These constants are recorded as values fixed outside us and are NEWLY being "
                    + "order-compared: " + new HashSet<>(wrong) + ". A protocol value is normally "
                    + "matched for equality and a threshold is ordered, so say which it is: if it "
                    + "bounds a counter, re-classify it and answer the durability question; if it "
                    + "is a spec ordinal used as a floor test, add it to WIRE_ROWS_ORDER_COMPARED "
                    + "with the reason, in the same commit.");
        }
        final List<String> gone = new ArrayList<>();
        for (final String pinned : WIRE_ROWS_ORDER_COMPARED) {
            if (!ordered.contains(pinned)) gone.add(pinned);
        }
        if (!gone.isEmpty()) {
            fail("These WIRE rows are pinned as order-compared and no longer are: " + gone
                    + ". Delete the pin — an exemption for a comparison that is gone is inherited "
                    + "silently by the next one to appear at that name.");
        }
    }

    /**
     * {@code WIRE} rows that are order-compared, and why each is not a threshold.
     *
     * <ul>
     * <li>{@code MlsReestablishPolicy.ERA_INITIAL}: {@code serverEra >= ERA_INITIAL} in
     * {@code classify} asks whether the server holds the conversation at all; the other operand is
     * the server's era, not a counter of ours.</li>
     * </ul>
     */
    private static final Set<String> WIRE_ROWS_ORDER_COMPARED =
            new HashSet<>(java.util.Arrays.asList("MlsReestablishPolicy.ERA_INITIAL"));

    /** The failure message lists each open gate with what it leaves unbounded across a restart. */
    @Test
    public void theNumberOfUndeclaredTransientGatesOnlyFalls() {
        final List<String> open = new ArrayList<>();
        for (final Row r : INVENTORY) {
            if (r.durability == Durability.TRANSIENT_UNDECLARED) {
                open.add(r.key() + " → " + r.counter + " (" + r.evidence + ")");
            }
        }
        if (open.size() > TRANSIENT_UNDECLARED_TODAY) {
            fail("the inventory went BACKWARDS: " + open.size()
                    + " gates have an in-memory counter with "
                    + "no declared reason, up from " + TRANSIENT_UNDECLARED_TODAY + ". " + open);
        }
        if (open.size() < TRANSIENT_UNDECLARED_TODAY) {
            fail("the inventory IMPROVED — " + open.size()
                    + " undeclared transient gates remain, down "
                    + "from " + TRANSIENT_UNDECLARED_TODAY + ". Lower TRANSIENT_UNDECLARED_TODAY "
                    + "to " + open.size() + " in the SAME commit. Remaining: " + open);
        }
    }

    /**
     * A capacity bound written as a literal in a {@code LinkedHashMap} is named nowhere, so
     * eviction hooks are found by the method the JDK declares, resolved by reflection. Zero
     * overrides fails.
     */
    @Test
    public void everyEvictionCapInTheTransportIsClassified() throws IOException {
        final String hook = evictionHookName();
        assertTrue("java.util.LinkedHashMap declares no boolean(Map.Entry) eviction hook, so this "
                + "check has no subject and would silently pass over an unbounded map fed from the "
                + "wire", !hook.isEmpty());

        // The liveness ledger's cap is inside ConvState, in MlsTransportTypes.
        final String code = SourceScan.transportAndMoved();
        final List<Integer> overrides = SourceScan.usesOf(code, hook);
        assertTrue("no override of " + hook + " in the transport. Either the liveness ledger's cap "
                + "is gone — in which case a map keyed by an E.164 that arrives on the wire is now "
                + "unbounded — or this locator has gone stale. Both must fail.",
                !overrides.isEmpty());

        final List<String> found = new ArrayList<>();
        for (final Integer at : overrides) {
            final int open = code.indexOf('{', at.intValue());
            if (open < 0) continue;
            final String body = braceMatched(code, open);
            final Matcher m = Pattern.compile("(?:<=|>=|<|>)\\s*(\\d[\\d_]*)").matcher(body);
            while (m.find()) found.add(m.group(1).replace("_", ""));
        }
        assertTrue("the " + hook + " override(s) compare against no literal at all — the cap is "
                + "being read from somewhere this check cannot see, so classify it as a threshold "
                + "in INVENTORY instead", !found.isEmpty());

        final Set<String> declared = new HashSet<>();
        for (final LiteralCap c : LITERAL_CAPS) declared.add(Integer.toString(c.value));
        final List<String> unclassified = new ArrayList<>();
        for (final String v : found) {
            if (!declared.contains(v)) unclassified.add(v);
        }
        final Set<String> foundSet = new HashSet<>(found);
        final List<String> stale = new ArrayList<>();
        for (final String v : declared) {
            if (!foundSet.contains(v)) stale.add(v);
        }
        if (!unclassified.isEmpty()) {
            fail("These eviction caps have no LITERAL_CAPS row: " + unclassified + ". A capacity "
                    + "bound written as a literal is invisible to all four of this guard's "
                    + "enumerations — say which counter it bounds and why that counter may or may "
                    + "not die with the process.");
        }
        if (!stale.isEmpty()) {
            fail("LITERAL_CAPS rows for caps that no longer exist: " + stale + ". Delete the row — "
                    + "a classification for a value that is gone is inherited silently by the next "
                    + "cap to take it.");
        }
    }

    /**
     * The name of {@code LinkedHashMap}'s eviction hook, from the JDK rather than from a string.
     */
    private static String evictionHookName() {
        for (final java.lang.reflect.Method m : LinkedHashMap.class.getDeclaredMethods()) {
            if (m.getReturnType() == boolean.class && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Map.Entry.class) {
                return m.getName();
            }
        }
        return "";
    }

    /** The brace-matched block opened at {@code open}, brackets included, or "". */
    private static String braceMatched(final String src, final int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    /**
     * The transport, every guard class named by the tool's {@code GUARD} regex, and every engine
     * policy a guard class delegates its thresholds to, derived rather than listed.
     */
    private static List<String> allOwners() throws IOException {
        final List<String> out = new ArrayList<>();
        out.add(TRANSPORT);
        final List<String> guards = guardClasses();
        out.addAll(guards);
        for (final String g : guards) {
            if (!declaredIn(g).isEmpty()) continue;
            for (final String policy : enginePoliciesBehind(g)) {
                if (!out.contains(policy)) out.add(policy);
            }
        }
        // Engine policies named by an INVENTORY row. Not self-serving: a row whose owner no longer
        // declares its constant, a policy constant with no row, and a constant read nowhere each
        // fail another check.
        for (final Row r : INVENTORY) {
            if (r.constant.startsWith("mCfg.")) continue;
            if (out.contains(r.owner)) continue;
            if (SourceScan.engineClass(r.owner) == null) continue;
            if (!new File(locate(ENGINE_DIR + "/" + r.owner + ".java")).isFile()) continue;
            out.add(r.owner);
        }
        // The whole provider MLS layer; see providerLayerOwners().
        for (final String c : providerLayerOwners()) {
            if (!out.contains(c)) out.add(c);
        }
        return out;
    }

    /**
     * The provider MLS layer: (a) every {@code .java} under {@link #PROVIDER_MLS_DIR}, recursively,
     * and (b) every provider source that imports the engine MLS package. Each covers the other's
     * evasion, and (b) alone misses classes such as {@code MlsStalledNotifier} and
     * {@code RcsE2eeScheme} that import no engine class.
     */
    private static List<String> providerLayerOwners() throws IOException {
        final List<String> out = new ArrayList<>();
        for (final File f : SourceScan.javaSourcesUnder(PROVIDER_MLS_DIR)) {
            out.add(f.getName().substring(0, f.getName().length() - ".java".length()));
        }
        for (final File f : providerClassesImportingTheEngine()) {
            final String simple = f.getName().substring(0, f.getName().length() - ".java".length());
            if (!out.contains(simple)) out.add(simple);
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Term (b), over {@code src} and {@code selftest/src}: an owner this cannot see has its
     * declarations read as deleted, so a file move could retire a row.
     */
    private static List<File> providerClassesImportingTheEngine() throws IOException {
        final List<File> out = new ArrayList<>();
        final List<File> sources = new ArrayList<>(
                SourceScan.javaSourcesUnder(SourceScan.PROVIDER_SRC));
        sources.addAll(SourceScan.javaSourcesUnder("selftest/src"));
        for (final File f : sources) {
            final String code = SourceScan.codeOnly(
                    new String(java.nio.file.Files.readAllBytes(f.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8));
            // An import of RccMlsBody or MlsTransportTypes alone does not enrol a file: both types
            // moved into the engine package, and the files naming them had that dependency before.
            final String engineImports = code.replace(
                    "import com.android.messaging.rcs.engine.mls.RccMlsBody;", "").replace(
                    "import com.android.messaging.rcs.engine.mls.MlsTransportTypes;", "");
            if (engineImports.contains("import com.android.messaging.rcs.engine.mls.")) out.add(f);
        }
        return out;
    }

    /**
     * The engine classes {@code guardClass} references in code that declare a threshold; a name
     * that does not resolve through {@link SourceScan#engineClass} does not count as delegation.
     */
    private static List<String> enginePoliciesBehind(final String guardClass) throws IOException {
        final List<String> out = new ArrayList<>();
        final Matcher m = Pattern.compile("\\bMls[A-Za-z]\\w*\\b").matcher(codeOf(guardClass));
        while (m.find()) {
            final String name = m.group();
            if (out.contains(name) || name.equals(guardClass)) continue;
            if (SourceScan.engineClass(name) == null) continue;
            if (!new File(locate(ENGINE_DIR + "/" + name + ".java")).isFile()) continue;
            if (declaredIn(name).isEmpty()) continue;
            out.add(name);
        }
        return out;
    }

    /** The guard classes, read from {@code transport-classify.py}'s {@code GUARD} pattern. */
    private static List<String> guardClasses() throws IOException {
        final String py = SourceScan.read(TOOL);
        final int at = py.indexOf("GUARD = re.compile(");
        if (at < 0) return new ArrayList<>();
        final String pattern = py.substring(at, Math.min(py.length(), at + 800));
        final Set<String> seen = new HashSet<>();
        final List<String> out = new ArrayList<>();
        final Matcher m = Pattern.compile("\\bMls[A-Za-z]+\\b").matcher(pattern);
        while (m.find()) {
            if (seen.add(m.group()) && new File(locate(E2EE + m.group() + ".java")).isFile()) {
                out.add(m.group());
            }
        }
        return out;
    }

    private static String locate(final String rel) {
        for (final String c : new String[] {rel, "packages/apps/Messaging/" + rel, "../" + rel}) {
            if (new File(c).isFile()) return c;
        }
        return rel;
    }

    /** A threshold owner's source, comment- and string-stripped: provider first, then engine. */
    private static String codeOf(final String simpleName) throws IOException {
        final String hit = CODE_CACHE.get(simpleName);
        if (hit != null) return hit;
        final String provider = E2EE + simpleName + ".java";
        final String code;
        if (new File(locate(provider)).isFile()) {
            code = SourceScan.codeOnly(SourceScan.read(provider));
        } else if (new File(locate(ENGINE_DIR + "/" + simpleName + ".java")).isFile()) {
            code = SourceScan.codeOnly(SourceScan.read(ENGINE_DIR + "/" + simpleName + ".java"));
        } else if (new File(locate(SELFTEST_E2EE + simpleName + ".java")).isFile()) {
            code = SourceScan.codeOnly(SourceScan.read(SELFTEST_E2EE + simpleName + ".java"));
        } else {
            // A provider class outside e2ee, term (b) of providerLayerOwners.
            final String anywhere = SourceScan.providerClassSource(simpleName);
            if (anywhere == null) {
                throw new IOException(simpleName + ".java found in neither " + E2EE + ", "
                        + ENGINE_DIR + ", " + SELFTEST_E2EE + " nor anywhere under "
                        + SourceScan.PROVIDER_SRC);
            }
            code = anywhere;
        }
        CODE_CACHE.put(simpleName, code);
        return code;
    }

    /**
     * {@code simpleName -> code-only source}; the widened subject reads the same files many times.
     */
    private static final Map<String, String> CODE_CACHE = new LinkedHashMap<>();

    private static List<String> declaredIn(final String simpleName) throws IOException {
        return SourceScan.scalarConstantsDeclaredIn(codeOf(simpleName));
    }

    /**
     * Sites everywhere in the subsystem, not only in the owner: the owner's own uses plus every
     * qualified reference from another owner, so a second door opened in another class moves the
     * count.
     */
    private static int sitesOf(final Row r) throws IOException {
        int n = SourceScan.usesOf(codeOf(r.owner), r.constant).size();
        final List<String> owners = allOwners();
        for (final String other : owners) {
            if (other.equals(r.owner)) continue;
            n += SourceScan.usesOf(codeOf(other), r.owner + "." + r.constant).size();
        }
        // Also wherever the transport split moved code: scanned, not made owners, since an owner
        // must classify every constant it declares.
        for (final String p : SourceScan.transportDestinations()) {
            final String dest = p.substring(p.lastIndexOf('/') + 1, p.length() - ".java".length());
            if (owners.contains(dest) || dest.equals(r.owner)) continue;
            n += SourceScan.usesOf(codeOf(dest), r.owner + "." + r.constant).size();
        }
        return n;
    }

    /** {@code MlsConfig}'s scalar fields, by reflection. */
    private static List<String> configFields() {
        final List<String> out = new ArrayList<>();
        final Class<?> cfg = SourceScan.engineClass("MlsConfig");
        if (cfg == null) return out;
        for (final java.lang.reflect.Field f : cfg.getDeclaredFields()) {
            final Class<?> t = f.getType();
            if (t == int.class || t == long.class || t == boolean.class || t == short.class
                    || t == byte.class || t == double.class || t == float.class) {
                out.add(f.getName());
            }
        }
        return out;
    }

    /**
     * Config fields used at a gate site in the transport ({@code mCfg.X}) or in the classes the
     * split moved its code into ({@code cfg.X}), from {@link SourceScan#TRANSPORT_MOVED}.
     */
    private static List<String> configFieldsAtAGateSite() throws IOException {
        final Set<String> booleanMethods = booleanMethodNames();
        final Map<String, String> scanned = new LinkedHashMap<>();   // code -> the field prefix
        scanned.put(SourceScan.transport(), "mCfg.");
        for (final String dest : splitDestinations()) scanned.put(codeOf(dest), "cfg.");
        final List<String> out = new ArrayList<>();
        for (final String f : configFields()) {
            boolean found = false;
            for (final Map.Entry<String, String> e : scanned.entrySet()) {
                final String code = e.getKey();
                final String needle = e.getValue() + f;
                for (int i = code.indexOf(needle); i >= 0
                        && !found; i = code.indexOf(needle, i + 1)) {
                    if (i > 0 && Character.isJavaIdentifierPart(code.charAt(i - 1))) continue;
                    if (!Character.isJavaIdentifierPart(code.charAt(i + needle.length()))
                            && gateSiteKind(code, i, needle, booleanMethods) != null) {
                        found = true;
                    }
                }
                if (found) break;
            }
            if (found) out.add(f);
        }
        return out;
    }

    /** Engine classes the transport split has moved code into, from its manifest. */
    private static Set<String> splitDestinations() throws IOException {
        final Set<String> out = new java.util.TreeSet<>();
        for (final String[] tm : SourceScan.transportMoved()) out.add(tm[0]);
        assertTrue("no split destination parsed from " + SourceScan.TRANSPORT_MOVED
                + " — this scan is now blind", out.size() > 0);
        return out;
    }

    /**
     * The {@code Class.MEMBER} this constant is a pure alias of, or {@code null}: the initialiser,
     * from {@code =} to the next {@code ;}, is nothing but a qualified reference. {@code
     * MlsPeerGuard}'s five constants are all aliases. An initialiser that derives a value ({@code
     * X.Y * 2}) is a declaration and needs a row.
     */
    private static String aliasTargetOf(final String owner, final String constant)
            throws IOException {
        final String code = codeOf(owner);
        final Matcher m = Pattern.compile(
                "(?m)^[ \\t]+(?:(?:public|private|protected|static|final)[ \\t]+)*"
                + "(?:int|long|short|byte|double|float|boolean)[ \\t]+"
                + Pattern.quote(constant) + "[ \\t]*=([^;]*);").matcher(code);
        if (!m.find()) return null;
        final String init = m.group(1).trim().replaceAll("\\s+", " ");
        return Pattern.matches("[A-Z]\\w*(?:\\.[A-Z]\\w*)*\\.\\w+", init) ? init : null;
    }

    /** Is this constant a pure alias whose target resolves? Aliases carry no row. */
    private static boolean isResolvedAlias(final String owner, final String constant)
            throws IOException {
        return aliasTargetOf(owner, constant) != null
                && aliasFault(owner, constant, aliasTargetOf(owner, constant)) == null;
    }

    /**
     * Why {@code target} is not a usable alias target, or {@code null}. An engine target must be a
     * static final field by reflection; a provider target must be declared and carry its own row,
     * so an alias chain cannot walk a constant out of the inventory.
     */
    private static String aliasFault(final String owner, final String constant,
            final String target) throws IOException {
        final int dot = target.lastIndexOf('.');
        final String targetClass = target.substring(0, dot);
        final String member = target.substring(dot + 1);
        final Class<?> engine = SourceScan.engineClass(targetClass);
        if (engine != null) {
            for (final java.lang.reflect.Field f : engine.getDeclaredFields()) {
                if (!f.getName().equals(member)) continue;
                final int mod = f.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(mod)
                        || !java.lang.reflect.Modifier.isFinal(mod)) {
                    return owner + "." + constant + " aliases " + target + ", which " + targetClass
                            + " declares but not as a static final field";
                }
                return null;
            }
            return owner + "." + constant + " aliases " + target + ", and " + targetClass
                    + " resolves but declares no field " + member;
        }
        final String provider = SourceScan.providerClassSource(targetClass);
        if (provider == null) {
            return owner + "." + constant + " aliases " + target + ", and " + targetClass
                    + " is in neither the engine nor the provider — the alias points at nothing "
                    + "this guard can see, which is indistinguishable from a tunable hiding behind "
                    + "a qualified name";
        }
        if (!SourceScan.scalarConstantsDeclaredIn(provider).contains(member)) {
            return owner + "." + constant + " aliases " + target + ", and provider class "
                    + targetClass + " declares no static-final scalar " + member;
        }
        for (final Row r : INVENTORY) {
            if (r.owner.equals(targetClass) && r.constant.equals(member)) return null;
        }
        return owner + "." + constant + " aliases the PROVIDER constant " + target + ", which has "
                + "no INVENTORY row of its own — an alias may only defer the durability question to "
                + "a place that answers it";
    }

    /**
     * The skip in {@link #everyThresholdInTheSubsystemIsClassified} is sound only while each alias
     * is one. Zero aliases fails, since five exist.
     */
    @Test
    public void everyAliasPointsAtAConstantThatResolves() throws IOException {
        final List<String> aliases = new ArrayList<>();
        final List<String> broken = new ArrayList<>();
        for (final String owner : allOwners()) {
            for (final String c : declaredIn(owner)) {
                final String target = aliasTargetOf(owner, c);
                if (target == null) continue;
                aliases.add(owner + "." + c + " -> " + target);
                final String fault = aliasFault(owner, c, target);
                if (fault != null) broken.add(fault);
            }
        }
        assertTrue("no static-final scalar in the subsystem is an alias of another class's "
                + "constant, and five are (MlsPeerGuard's, every one of them). The initialiser "
                + "matcher has gone stale, and everyThresholdInTheSubsystemIsClassified is now "
                + "skipping nothing — or, worse, has stopped skipping and the five are about to be "
                + "reported as unclassified tunables.", aliases.size() >= 5);
        if (!broken.isEmpty()) {
            fail("These aliases do not resolve: " + broken + ". An unresolvable alias is not an "
                    + "alias — it is a declaration this guard is failing to ask about. (" 
                    + aliases.size() + " aliases seen: " + aliases + ")");
        }
    }

    /** Every method name some engine class declares returning {@code boolean}. */
    private static Set<String> booleanMethodNames() throws IOException {
        final Set<String> out = new HashSet<>();
        final File dir = new File(locate(ENGINE_DIR + "/MlsConfig.java")).getParentFile();
        final String[] names = dir == null ? null : dir.list();
        if (names == null) return out;
        final Pattern p = Pattern.compile("\\bboolean\\s+(\\w+)\\s*\\(");
        for (final String n : names) {
            if (!n.endsWith(".java")) continue;
            final Matcher m = p.matcher(SourceScan.read(ENGINE_DIR + "/" + n));
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    /**
     * Why the occurrence of {@code name} at {@code at} is a gate site, or {@code null}: an operator
     * in the same sub-expression, or a place inside the paren-matched arguments of a boolean
     * method.
     */
    private static String gateSiteKind(final String code, final int at, final String name,
            final Set<String> booleanMethods) {
        final Pattern op = Pattern.compile("(<=|>=|==|!=|<|>)");
        if (op.matcher(subExpressionBefore(code, at)).find()) return "compared, operator before it";
        if (op.matcher(subExpressionAfter(code, at + name.length())).find()) {
            return "compared, operator after it";
        }

        final int stmt = statementStart(code, at);
        final Matcher call = Pattern.compile("(\\w+)\\s*\\(").matcher(code.substring(stmt, at));
        while (call.find()) {
            final String method = call.group(1);
            if (!booleanMethods.contains(method)) continue;
            final int open = stmt + call.end() - 1;
            final String args = SourceScan.argumentListAt(code, open);
            if (open + 1 + args.length() >= at) return "argument to boolean " + method + "(…)";
        }
        return null;
    }

    /**
     * The text from the nearest sub-expression boundary back to {@code at}. Not the whole
     * statement: in {@code rec == null ? -1 : rec.countWithin(DAY_MS, now)} the {@code ==} is not
     * DAY_MS's.
     */
    private static String subExpressionBefore(final String code, final int at) {
        for (int i = at; i > 0; i--) {
            final char c = code.charAt(i - 1);
            if (c == ';' || c == '{' || c == '}' || c == '(' || c == ')' || c == ',' || c == '?'
                    || c == ':' || c == '&' || c == '|' || c == '!') {
                return code.substring(i, at);
            }
        }
        return code.substring(0, at);
    }

    /** The mirror of {@link #subExpressionBefore}, forwards. */
    private static String subExpressionAfter(final String code, final int from) {
        for (int i = from; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == ';' || c == '{' || c == '}' || c == '(' || c == ')' || c == ',' || c == '?'
                    || c == ':' || c == '&' || c == '|') {
                return code.substring(from, i);
            }
        }
        return code.substring(from);
    }

    /** The start of the statement containing {@code at} — a brace, a semicolon, or the file. */
    private static int statementStart(final String code, final int at) {
        for (int i = at; i > 0; i--) {
            final char c = code.charAt(i - 1);
            if (c == ';' || c == '{' || c == '}') return i;
        }
        return 0;
    }

    private static String lineAt(final String code, final int at) {
        final int a = code.lastIndexOf('\n', at) + 1;
        int b = code.indexOf('\n', at);
        if (b < 0) b = code.length();
        return "line " + (1 + count(code.substring(0, at), '\n')) + " — "
                + code.substring(a, b).trim();
    }

    private static int count(final String s, final char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    static {
        // A duplicate key would let one row silently answer for two thresholds.
        final Set<String> seen = new HashSet<>();
        for (final Row r : INVENTORY) {
            if (!seen.add(r.key())) throw new IllegalStateException("duplicate INVENTORY row "
                    + r.key());
        }
    }
}

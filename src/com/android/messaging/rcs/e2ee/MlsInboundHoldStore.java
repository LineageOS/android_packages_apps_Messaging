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
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import com.android.messaging.rcs.engine.mls.MlsInboundHold;
import com.android.messaging.rcs.engine.mls.MlsWireScan;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>TEST FIXTURE.</b> The durable half of the inbound-control hold — what is
 * held, in what order, and how to give it back. The decision half is
 * {@link MlsInboundHold}, in the engine, where it is host-testable.
 *
 * <h2>HOLD, do not DROP — and this is the whole design</h2>
 *
 * <p>This was first proposed as "a debug arm that drops inbound commits for a named group until
 * disarmed". Dropping is what makes the gap unrecoverable, and it is worth spelling out why:
 *
 * <ul>
 *   <li><b>The server does not backfill commits.</b> {@code GetMlsGroupInfo} returns a GroupInfo +
 *       ratchet tree, never the commits between our epoch and the group's — measured, and the
 *       provider says so
 *       in as many words at {@code RcsProviderService.fetchMissedCommits}: <i>"no commit backfill
 *       (expected)"</i>. So a dropped commit has no replay source anywhere.</li>
 *   <li><b>External commit does not rescue it either, but for a CLIENT reason.</b> This transport's
 *       profile refuses ExternalInit from an existing member ({@code acceptsMemberExternalCommit
 *       = false}), so we do not attempt one whatever the server offers.
 *
 *       <p><b>CORRECTED — do not restore the sentence this replaced.</b> It read "the
 *       server's GroupInfo for our groups carries {@code external_pub}(0x0004) and
 *       {@code external_senders}(0x0005) ABSENT, measured with {@code --ez groupexts}".
 *       That was never a measurement of the server:
 *       {@code OpenMlsSession.groupInfoContinuity} returned an empty array for any non-continuity
 *       code point <i>without calling native</i>, and every caller logs empty as ABSENT — so
 *       {@code external_pub} read ABSENT unconditionally whatever the server sent. With the reader
 *       fixed it measures 67 B on 2 of 3 groups we hold, including the Google Messages-created one, and is
 *       group-dependent. That claim is retracted; see the long note at
 *       {@code MlsProviderTransport}'s resync external-commit guard for the re-measurement.</li>
 *   <li><b>Which leaves another member re-Welcoming us</b>, i.e. a device we do not control choosing
 *       to act, and nothing on this side can make that happen.</li>
 * </ul>
 *
 * <p><b>The decision does not depend on the corrected fact.</b> The first bullet carries it on its
 * own: with no backfill, the bytes this device withholds are the only copy that exists anywhere, so
 * a lever that discards them is unrecoverable no matter what else is true about external commit.
 *
 * <p>A lever that drops therefore creates exactly the unrecoverable state it exists to study, on a
 * group it then consumes — which is the failure this was written for. So the held bytes are kept,
 * and {@code release} replays them in arrival order through the ordinary inbound path. Reversibility
 * is a property of this class holding the only copy, NOT a hope about the server.
 *
 * <p>{@code --ez discard true} still exists, because a deliberately unrecoverable gap is the right
 * fixture for the rebuild rung. It is opt-in and it announces itself.
 *
 * <h2>Persistence, and why prefs rather than a static</h2>
 *
 * <p>Same reason {@code fail_next_decrypt} is persisted: this app restarts constantly (provider
 * wakes, installs, low-memory kills) and the fixture has to survive minutes of a group committing
 * while nobody is watching. A process-static flag would silently disarm itself before most runs. Its
 * own prefs file, separate from the transport's {@code mls_provider_conv}, so that clearing the
 * fixture can never touch conversation state and so an operator can see it in one place.
 *
 * <p>Inert on a non-debuggable build at BOTH ends — the debug arm refuses to set it and this class
 * refuses to act on it. Belt and braces on purpose: the gate sits on the production inbound path, and
 * the one thing that must be impossible is a restored data directory arming it on a user build.
 */
public final class MlsInboundHoldStore {

    private static final String TAG = LogUtil.BUGLE_TAG;

    private static final String PREFS = "mls_inbound_hold";

    private static final String K_ARMED = "armed";
    private static final String K_SCOPE = "scope";
    private static final String K_LABEL = "label";
    private static final String K_MODE = "mode";
    private static final String K_DISCARD = "discard";
    private static final String K_ARM_ERA = "arm_era";
    private static final String K_ARM_EPOCH = "arm_epoch";
    private static final String K_ARM_AT = "arm_at";
    private static final String K_COUNT = "held_count";
    private static final String K_HELD = "held_";
    private static final String K_PASSED = "passed_total";
    private static final String K_DISCARDED = "discarded_total";
    private static final String K_DUPES = "dupes_total";
    private static final String K_OVERFLOW = "overflow_total";

    private static volatile MlsInboundHoldStore sInstance;

    private final Context mCtx;

    private MlsInboundHoldStore(final Context ctx) {
        mCtx = ctx.getApplicationContext();
    }

    public static MlsInboundHoldStore get(final Context ctx) {
        MlsInboundHoldStore s = sInstance;
        if (s == null) {
            synchronized (MlsInboundHoldStore.class) {
                s = sInstance;
                if (s == null) {
                    s = new MlsInboundHoldStore(ctx);
                    sInstance = s;
                }
            }
        }
        return s;
    }

    /**
     * eng/userdebug only — the same test as {@code RcsDebugSendReceiver}, and for the same reason
     * recorded there: {@code ApplicationInfo.FLAG_DEBUGGABLE} reflects the manifest attribute and is
     * false for a system app even on a userdebug image, so it is the wrong test.
     */
    public static boolean buildAllowsFixtures() {
        return "eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE);
    }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** One held control payload, with everything needed to replay it and to measure the gap. */
    public static final class Held {
        public final String fromE164;
        public final String messageId;
        public final MlsInboundHold.Kind kind;
        /** The epoch stamped on the wire, or -1 if it could not be read. */
        public final long epoch;
        public final long heldAtMs;
        public final byte[] blob;

        Held(final String from, final String msgId, final MlsInboundHold.Kind kind,
                final long epoch, final long heldAtMs, final byte[] blob) {
            this.fromE164 = from;
            this.messageId = msgId;
            this.kind = kind;
            this.epoch = epoch;
            this.heldAtMs = heldAtMs;
            this.blob = blob;
        }
    }

    // ---- ARM STATE ----------------------------------------------------------------------------

    public boolean isArmed() {
        return buildAllowsFixtures() && prefs().getBoolean(K_ARMED, false);
    }

    /** The canonical conversation key the lever names ({@code g:<gid>} or {@code p:<e164>}). */
    public String scopeKey() {
        return prefs().getString(K_SCOPE, null);
    }

    public MlsInboundHold.Mode mode() {
        return MlsInboundHold.modeOf(prefs().getString(K_MODE, null),
                MlsInboundHold.Mode.HANDSHAKE);
    }

    public boolean discarding() {
        return prefs().getBoolean(K_DISCARD, false);
    }

    public int armEra() {
        return prefs().getInt(K_ARM_ERA, -1);
    }

    public long armEpoch() {
        return prefs().getLong(K_ARM_EPOCH, -1L);
    }

    /**
     * Arm the lever on one conversation.
     *
     * <p>{@code armEra}/{@code armEpoch} are recorded so {@code release} can say whether the ground
     * moved underneath the fixture. An era change while held means a Welcome was applied (we pass
     * those unless {@link MlsInboundHold.Mode#CONTROL}), which makes every held commit stale — held
     * bytes from era N cannot apply to a group that is now at era N+1. Recording it is what lets the
     * release REPORT that rather than silently replay nothing and read as a failure of the ladder.
     */
    public synchronized void arm(final String scopeKey, final String label,
            final MlsInboundHold.Mode mode, final boolean discard, final int armEra,
            final long armEpoch) {
        prefs().edit()
                .putBoolean(K_ARMED, true)
                .putString(K_SCOPE, scopeKey)
                .putString(K_LABEL, label)
                .putString(K_MODE, mode.name())
                .putBoolean(K_DISCARD, discard)
                .putInt(K_ARM_ERA, armEra)
                .putLong(K_ARM_EPOCH, armEpoch)
                .putLong(K_ARM_AT, System.currentTimeMillis())
                .commit();
    }

    /**
     * Stop holding. Held payloads are KEPT — disarming and releasing are separate acts.
     *
     * <p>Separate because they answer different questions. Disarm-and-keep leaves the gap standing
     * with the bytes still in hand, which is the state every recovery test wants: the ladder now runs
     * against a real divergence, and if it fails to converge the fixture can still be handed back
     * afterwards. Release-and-replay is the check that the lever itself is reversible.
     */
    public synchronized void disarm() {
        prefs().edit().putBoolean(K_ARMED, false).commit();
    }

    // ---- THE GATE -----------------------------------------------------------------------------

    /**
     * Offer one inbound control payload to the fixture.
     *
     * @return true if the fixture took it and the caller must NOT apply it
     */
    public synchronized boolean offer(final String conversationKey, final String fromE164,
            final String messageId, final byte[] mlsBytes) {
        if (!isArmed()) return false;
        final String scope = scopeKey();
        final boolean scopeMatches = scope != null && scope.equals(conversationKey);
        final MlsInboundHold.Kind kind = MlsInboundHold.classify(mlsBytes);
        final int count = prefs().getInt(K_COUNT, 0);
        final boolean atCapacity = !discarding() && count >= MlsInboundHold.CAPACITY;
        final MlsInboundHold.Verdict v =
                MlsInboundHold.decide(true, scopeMatches, mode(), kind, atCapacity);
        if (v != MlsInboundHold.Verdict.HOLD) {
            if (scopeMatches) {
                bump(K_PASSED);
                if (atCapacity) {
                    bump(K_OVERFLOW);
                    LogUtil.w(TAG, "MlsInboundHoldStore: AT CAPACITY (" + MlsInboundHold.CAPACITY
                            + " held) — passing this " + kind + " through to the normal path rather "
                            + "than dropping it. §10.8's pending queue will park it if it is from "
                            + "the future, so nothing is lost; the gap simply stops growing here.");
                }
            }
            return false;
        }
        final long epoch = MlsWireScan.epochOf(mlsBytes);
        if (discarding()) {
            bump(K_DISCARDED);
            // SAYS ONLY WHAT IT MEASURES. This line used to add "and no external_pub", which was
            // repeating a retracted fact (see the class doc) — and worse, asserting something about
            // THIS group that it had not read. What it can state is what it just did and the one
            // thing that follows from it: the bytes are gone and there is no backfill, so REPLAY is
            // not a route home. Whether any OTHER route exists for this group is a separate
            // question with a per-group answer, and this line is not the place it gets decided.
            LogUtil.w(TAG, "MlsInboundHoldStore: DISCARDED a " + kind + " at epoch " + epoch
                    + " for " + conversationKey + " from " + fromE164 + " (msgId=" + messageId
                    + ", " + mlsBytes.length + "B). --ez discard was set at arm time, so this "
                    + "payload is GONE and nothing can hand it back — the server does not backfill "
                    + "commits, so REPLAY is not a route home for this conversation. Whether a "
                    + "re-Welcome or an external commit is depends on the group; check "
                    + "--ez groupexts. Deliberate.");
            return true;
        }
        final String digest = sha256(mlsBytes);
        for (final Held h : heldLocked()) {
            if (digest.equals(sha256(h.blob))) {
                bump(K_DUPES);
                LogUtil.i(TAG, "MlsInboundHoldStore: already holding this exact payload (digest "
                        + digest + ") — a redelivery, not a new commit. Held count unchanged at "
                        + count + ", so the measured gap stays honest.");
                return true;
            }
        }
        final String row = enc(fromE164) + '|' + enc(messageId) + '|' + kind.name() + '|' + epoch
                + '|' + System.currentTimeMillis() + '|'
                + Base64.encodeToString(mlsBytes, Base64.NO_WRAP);
        prefs().edit().putString(K_HELD + count, row).putInt(K_COUNT, count + 1).commit();
        LogUtil.w(TAG, "MlsInboundHoldStore: HELD a " + kind + " at epoch " + epoch + " for "
                + conversationKey + " from " + fromE164 + " (msgId=" + messageId + ", "
                + mlsBytes.length + "B). Now holding " + (count + 1) + "/"
                + MlsInboundHold.CAPACITY + ". This device is a test fixture right now — it is "
                + "BEHIND on this conversation BY CONSTRUCTION, not by a fault.");
        return true;
    }

    // ---- READ / RELEASE -----------------------------------------------------------------------

    /** Everything held, in arrival order. */
    public synchronized List<Held> held() {
        return heldLocked();
    }

    private List<Held> heldLocked() {
        final SharedPreferences p = prefs();
        final int n = p.getInt(K_COUNT, 0);
        final List<Held> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final String row = p.getString(K_HELD + i, null);
            if (row == null) continue;
            final String[] f = row.split("\\|", 6);
            if (f.length < 6) continue;
            try {
                out.add(new Held(dec(f[0]), dec(f[1]), MlsInboundHold.Kind.valueOf(f[2]),
                        Long.parseLong(f[3]), Long.parseLong(f[4]),
                        Base64.decode(f[5], Base64.NO_WRAP)));
            } catch (final RuntimeException bad) {
                LogUtil.w(TAG, "MlsInboundHoldStore: held row " + i + " is unreadable and is being "
                        + "skipped — the replay will be INCOMPLETE and the gap will not close: "
                        + bad);
            }
        }
        return out;
    }

    /** The epoch stamped on each held COMMIT, in arrival order — the input to {@code measure}. */
    public synchronized long[] heldCommitEpochs() {
        final List<Held> all = heldLocked();
        int n = 0;
        for (final Held h : all) if (h.kind == MlsInboundHold.Kind.COMMIT) n++;
        final long[] out = new long[n];
        int i = 0;
        for (final Held h : all) if (h.kind == MlsInboundHold.Kind.COMMIT) out[i++] = h.epoch;
        return out;
    }

    /**
     * Take everything held and clear the store, so a replay cannot double-apply.
     *
     * <p>Cleared BEFORE the replay runs rather than after: a replay that crashes half way through
     * would otherwise leave rows that the next release re-applies on top of commits the engine has
     * already taken. The bytes are in the returned list either way, and a crashed replay is visible
     * in the log.
     */
    public synchronized List<Held> takeAll() {
        final List<Held> all = heldLocked();
        clearHeldLocked();
        return all;
    }

    /** Throw the held payloads away without replaying them. Announced, never silent. */
    public synchronized int dropHeld() {
        final int n = prefs().getInt(K_COUNT, 0);
        clearHeldLocked();
        return n;
    }

    private void clearHeldLocked() {
        final SharedPreferences.Editor e = prefs().edit();
        final int n = prefs().getInt(K_COUNT, 0);
        for (int i = 0; i < n; i++) e.remove(K_HELD + i);
        e.putInt(K_COUNT, 0).commit();
    }

    // ---- STATUS -------------------------------------------------------------------------------

    /**
     * A one-line status. Every number in it is measured — the engine's epoch is read from the engine
     * and the group's is derived from the epochs the held commits carry on the wire — so a test can
     * assert the fixture instead of assuming it. Costs no server look-up; see
     * {@link MlsInboundHold#measure}.
     */
    public synchronized String describe(final int ourEra, final long ourEpoch) {
        final SharedPreferences p = prefs();
        if (!buildAllowsFixtures()) {
            return "inbound-hold UNAVAILABLE (Build.TYPE=" + Build.TYPE + ", not eng/userdebug)";
        }
        final MlsInboundHold.Gap gap = MlsInboundHold.measure(ourEpoch, heldCommitEpochs());
        final List<Held> all = heldLocked();
        int commits = 0, proposals = 0, welcomes = 0;
        for (final Held h : all) {
            switch (h.kind) {
                case COMMIT: commits++; break;
                case PROPOSAL: proposals++; break;
                case WELCOME: welcomes++; break;
                default: break;
            }
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(p.getBoolean(K_ARMED, false) ? "ARMED" : "disarmed")
                .append(" scope=").append(p.getString(K_SCOPE, "<none>"))
                .append(" label=").append(p.getString(K_LABEL, "<none>"))
                .append(" mode=").append(p.getString(K_MODE, "<none>"))
                .append(discarding() ? " DISCARDING" : "")
                .append(" armedAt=(era=").append(p.getInt(K_ARM_ERA, -1))
                .append(" epoch=").append(p.getLong(K_ARM_EPOCH, -1L)).append(')')
                .append(" now=(era=").append(ourEra).append(" epoch=").append(ourEpoch).append(')')
                .append(" held=").append(all.size())
                .append("(commits=").append(commits)
                .append(" proposals=").append(proposals)
                .append(" welcomes=").append(welcomes).append(')')
                .append(" passed=").append(p.getInt(K_PASSED, 0))
                .append(" dupes=").append(p.getInt(K_DUPES, 0))
                .append(" discarded=").append(p.getInt(K_DISCARDED, 0))
                .append(" overflowed=").append(p.getInt(K_OVERFLOW, 0))
                .append(" · ").append(gap);
        if (gap.heldCommits > 0 && !gap.contiguous) {
            // The house rule, applied to the instrument itself: this line may not claim the fixture
            // is a clean N-epoch gap unless the held epochs actually form one.
            sb.append(" · NOT CONTIGUOUS — the held commits do not run ").append(ourEpoch)
                    .append("..").append(gap.highestCommitEpoch).append(" without holes, so some "
                            + "commit went missing by a route OTHER than this lever. The gap is "
                            + "real but replaying what we hold will NOT close it.");
        }
        return sb.toString();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void bump(final String key) {
        prefs().edit().putInt(key, prefs().getInt(key, 0) + 1).commit();
    }

    /** {@code |} is the row separator, so it may not survive inside a field. */
    private static String enc(final String s) {
        return s == null ? "" : s.replace("|", "%7C");
    }

    private static String dec(final String s) {
        return s == null || s.isEmpty() ? null : s.replace("%7C", "|");
    }

    private static String sha256(final byte[] b) {
        if (b == null) return "none";
        try {
            final byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b);
            final StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (final java.security.NoSuchAlgorithmException impossible) {
            return "nohash/" + b.length;
        }
    }
}

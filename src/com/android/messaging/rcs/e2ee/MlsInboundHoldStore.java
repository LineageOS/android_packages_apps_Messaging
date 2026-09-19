/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsInboundHold;
import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.MlsWireScan;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Test fixture, debug builds only: the durable half of the inbound-control hold, which withholds
 * inbound control messages so a test can put this device behind its group on purpose. The decision
 * half is {@link MlsInboundHold} in the engine.
 *
 * <p>It holds rather than drops: the server does not backfill commits, so the held bytes are the
 * only copy, and {@code release} replays them in arrival order through the ordinary inbound path.
 * Dropping would create an unrecoverable gap on the group under test. A discarding mode exists for
 * the rebuild rung's fixture, opt-in and logged.
 *
 * <p>Persisted in its own preferences file so it survives the app's frequent restarts and clearing
 * it cannot touch conversation state. Inert on a user build at both ends: the debug arm refuses to
 * set it and this class refuses to act on it.
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
     * eng or userdebug only. {@code ApplicationInfo.FLAG_DEBUGGABLE} is false for a system app even
     * on userdebug, so it is the wrong test.
     */
    public static boolean buildAllowsFixtures() {
        return RcsDebug.isDebugBuild();
    }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** One held control payload, with what is needed to replay it and to measure the gap. */
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
     * Arm the lever on one conversation. {@code armEra} and {@code armEpoch} let a release report
     * that the era moved while held (a Welcome was applied), which makes every held commit stale.
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
     * Stop holding. Held payloads are kept: disarm-and-keep leaves the gap standing for a recovery
     * test, and release-and-replay checks that the lever is reversible.
     */
    public synchronized void disarm() {
        prefs().edit().putBoolean(K_ARMED, false).commit();
    }

    /**
     * Offer one inbound control payload to the fixture.
     *
     * @return true if the fixture took it and the caller must not apply it
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
                            + " held) — passing this " + kind
                            + " through to the normal path rather "
                            + "than dropping it. §10.8's pending queue will park it if it is from "
                            + "the future, so nothing is lost; the gap simply stops growing here.");
                }
            }
            return false;
        }
        final long epoch = MlsWireScan.epochOf(mlsBytes);
        if (discarding()) {
            bump(K_DISCARDED);
            LogUtil.w(TAG, "MlsInboundHoldStore: DISCARDED a " + kind + " at epoch " + epoch
                    + " for " + MlsConversationKey.forLog(conversationKey) + " from "
                    + LogMask.number(fromE164) + " (msgId="
                    + MlsMessageId.forLog(messageId)
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
                        + count + ", so the reported gap stays accurate.");
                return true;
            }
        }
        final String row = enc(fromE164) + '|' + enc(messageId) + '|' + kind.name() + '|' + epoch
                + '|' + System.currentTimeMillis() + '|'
                + Base64.encodeToString(mlsBytes, Base64.NO_WRAP);
        prefs().edit().putString(K_HELD + count, row).putInt(K_COUNT, count + 1).commit();
        LogUtil.w(TAG, "MlsInboundHoldStore: HELD a " + kind + " at epoch " + epoch + " for "
                + MlsConversationKey.forLog(conversationKey) + " from " + LogMask.number(fromE164)
                + " (msgId=" + MlsMessageId.forLog(messageId)
                + ", "
                + mlsBytes.length + "B). Now holding " + (count + 1) + "/"
                + MlsInboundHold.CAPACITY + ". This device is a test fixture right now — it is "
                + "BEHIND on this conversation BY CONSTRUCTION, not by a fault.");
        return true;
    }

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

    /** The epoch on each held commit, in arrival order: the input to {@code measure}. */
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
     * Take everything held and clear the store before the replay runs, so a replay that crashes
     * half way cannot be re-applied on top of commits the engine already took.
     */
    public synchronized List<Held> takeAll() {
        final List<Held> all = heldLocked();
        clearHeldLocked();
        return all;
    }

    /** Throw the held payloads away without replaying them. */
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

    /**
     * A one-line status. Our epoch is read from the engine and the group's is derived from the
     * epochs the held commits carry, so a test can assert the fixture. No server look-up; see
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
                .append(" scope=").append(MlsConversationKey.forLog(p.getString(K_SCOPE, "<none>")))
                .append(" label=").append(MlsConversationKey.forLog(p.getString(K_LABEL, "<none>")))
                .append(" mode=").append(MlsConversationKey.forLog(p.getString(K_MODE, "<none>")))
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
            // Claim a clean N-epoch gap only if the held epochs actually form one.
            sb.append(" · NOT CONTIGUOUS — the held commits do not run ").append(ourEpoch)
                    .append("..").append(gap.highestCommitEpoch).append(" without holes, so some "
                            + "commit went missing by a route OTHER than this lever. The gap is "
                            + "real but replaying what we hold will NOT close it.");
        }
        return sb.toString();
    }

    private void bump(final String key) {
        prefs().edit().putInt(key, prefs().getInt(key, 0) + 1).commit();
    }

    /** {@code |} separates fields, so it is escaped inside one. */
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

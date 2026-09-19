/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The KeyPackages one upgrade-on-open claimed, carried on the stack from the count to the create so
 * a successful upgrade claims once per participant. Nothing is stored. {@link #take} consumes,
 * since a KeyPackage is single-use (RFC 9420 §10). Refused ({@link #count()} is
 * {@link MlsClaimLedger#CLAIM_REFUSED}), claimed-but-empty (an empty list) and not covered
 * ({@code null}) never share a value. Not thread-safe. See docs/mls/budgets.md.
 */
public final class MlsUpgradeClaim {

    private final boolean mRefused;
    private final String mWhy;
    private final Map<String, List<byte[]>> mByPeer;
    private final int mClaimedFor;

    private MlsUpgradeClaim(final boolean refused, final String why,
            final Map<String, List<byte[]>> byPeer) {
        mRefused = refused;
        mWhy = why;
        mByPeer = byPeer;
        mClaimedFor = (byPeer == null) ? 0 : byPeer.size();
    }

    /**
     * Our own claim ledger refused, so nothing was claimed.
     *
     * @param why the ledger's explanation, for the caller's log line
     */
    public static MlsUpgradeClaim refusedByLedger(final String why) {
        return new MlsUpgradeClaim(/*refused=*/ true, why, Collections.<String,
                List<byte[]>>emptyMap());
    }

    /**
     * A claim that reached the KDS for every participant; one served nothing is kept as an empty
     * list.
     */
    public static MlsUpgradeClaim claimed(final LinkedHashMap<String, List<byte[]>> byPeer) {
        final LinkedHashMap<String, List<byte[]>> copy = new LinkedHashMap<>();
        if (byPeer != null) {
            for (final Map.Entry<String, List<byte[]>> e : byPeer.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty()) continue;
                copy.put(e.getKey(), (e.getValue() == null)
                        ? new ArrayList<byte[]>() : new ArrayList<>(e.getValue()));
            }
        }
        return new MlsUpgradeClaim(/*refused=*/ false, null, copy);
    }

    /** {@code true} when the ledger refused. Never means "the peers had nothing". */
    public boolean refused() {
        return mRefused;
    }

    /** The ledger's explanation when {@link #refused()}, else {@code null}. */
    public String why() {
        return mWhy;
    }

    /**
     * Participants we hold at least one package for, counted over what was claimed rather than what
     * is left; {@link MlsClaimLedger#CLAIM_REFUSED} when refused.
     */
    public int count() {
        if (mRefused) return MlsClaimLedger.CLAIM_REFUSED;
        int n = 0;
        for (final List<byte[]> pkgs : mByPeer.values()) {
            if (hasAny(pkgs)) n++;
        }
        return n;
    }

    /** How many participants this claim was made for, including any the KDS served nothing for. */
    public int claimedFor() {
        return mClaimedFor;
    }

    /** Whether this claim carries an answer of any kind about {@code peer}. */
    public boolean covers(final String peer) {
        return peer != null && mByPeer.containsKey(peer);
    }

    /**
     * Hand this peer's packages over, once.
     *
     * @return every device's package for {@code peer} (empty if the KDS served none), or
     * {@code null} when this claim has no answer about them, including when already taken
     */
    public List<byte[]> take(final String peer) {
        if (peer == null) return null;
        return mByPeer.remove(peer);
    }

    /** Peers whose packages have not yet been taken. Zero after a create has consumed the claim. */
    public int remaining() {
        return mByPeer.size();
    }

    /** One log line: what was claimed, and how much is still in hand. */
    public String describe() {
        if (mRefused) return "REFUSED by our own claim ledger — nothing was claimed. " + mWhy;
        return count() + " of " + mClaimedFor + " participant(s) served a KeyPackage, "
                + remaining() + " still in hand";
    }

    private static boolean hasAny(final List<byte[]> pkgs) {
        if (pkgs == null) return false;
        for (final byte[] k : pkgs) {
            if (k != null && k.length > 0) return true;
        }
        return false;
    }
}

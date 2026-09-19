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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The KeyPackages one upgrade-on-open claimed, carried from the count to the create.</b>
 *
 * <h2>What this replaces</h2>
 *
 * <p>{@code claimableKeyPackageCount} was a counter that CONSUMED what it counted: it claimed one
 * package per participant, read the byte array only to increment an {@code int}, and dropped it.
 * {@code MlsUpgradePolicy} then said PROCEED and {@code establishGroup} claimed <b>again</b>, per
 * member. So a successful group upgrade cost two {@code ClaimKeyPackages} round trips per
 * participant and the first one's product was thrown away.
 *
 * <p>The resource is spent AT CLAIM TIME — measured on a device, three claims
 * returning three different package sizes, i.e. three distinct packages really removed — so the
 * discarded claim was not a wasted millisecond. It was a KeyPackage taken off somebody else's
 * device to answer a question, once per participant, every time a person tapped a conversation.
 *
 * <p>This type is what makes the second dial unnecessary: the probe <b>becomes</b> the establish's
 * claim. One claim per participant, the packages kept, counted, and handed to the create.
 *
 * <h2>Why this is a carrier and not a cache</h2>
 *
 * <p>The provider-side {@code ClaimedKeyStore} was deleted rather than wired to a
 * reader into it, and its three arguments apply to any cache put back in its place: a cache under
 * the AIDL boundary is invisible to {@link MlsClaimLedger}, which charges above it and would then
 * charge for claims that never happened; the deleted reader never DELETED what it read, so a second
 * establish inside the TTL would have handed one KeyPackage to two Welcomes; and a KeyPackage is a
 * consumable with a {@code >=}30-day remaining-lifetime floor, not a record
 * worth persisting until a consumer appears.
 *
 * <p>None of that applies here, because <b>nothing is stored</b>. This object lives on one stack
 * inside {@code MlsConversationOpenListener.upgrade}: claimed at the top, consumed by
 * {@code establishGroup} a few lines later, unreachable after. There is no TTL because there is no
 * ageing, no staleness policy because there is no second reader, and no coherence problem because
 * there is no second copy.
 *
 * <h2>Three states, and none of them shares a value with another</h2>
 *
 * <ul>
 *   <li><b>Refused</b> — {@link MlsClaimLedger} said no and <b>nothing was claimed</b>.
 *       {@link #count()} answers {@link MlsClaimLedger#CLAIM_REFUSED}, never a count, because zero
 *       already means something else.</li>
 *   <li><b>Claimed, peer had none</b> — we asked the KDS about that peer and it served nothing.
 *       {@link #take} returns an EMPTY list. This is the only state that is evidence about a peer's
 *       pool.</li>
 *   <li><b>Not covered</b> — this claim says nothing at all about that peer, either because it was
 *       never claimed for or because its packages have already been taken. {@link #take} returns
 *       {@code null}.</li>
 * </ul>
 *
 * <p>The middle and the last must not share a return value, and that is not a style preference: a
 * caller that reads "not covered" as "the peer has no key packages" refuses an upgrade and blames a
 * participant for our own bookkeeping — the same laundering {@link MlsClaimLedger#CLAIM_REFUSED}
 * exists to prevent one level up.
 *
 * <h2>{@link #take} consumes</h2>
 *
 * <p>A KeyPackage is one-time (RFC 9420 §10): the peer drops the private half of its init key when a
 * Welcome consumes it, and our own engine does the same ({@code KeyPackageStorage::delete} is a hard
 * file delete). So a package handed out twice is a package one of the two recipients cannot open.
 * {@link #take} REMOVES the entry, which is why {@link #covers} is asked first and why a second
 * {@code take} for the same peer answers {@code null} rather than the same bytes again.
 *
 * <p>Not thread-safe, and deliberately not: it is created and consumed on one thread, and making it
 * safe to share would invite the shape this class exists to avoid.
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
     * Our own claim ledger refused, so <b>nothing was claimed</b> and this carries no packages.
     *
     * @param why the ledger's own explanation, for the line the caller logs
     */
    public static MlsUpgradeClaim refusedByLedger(final String why) {
        return new MlsUpgradeClaim(/*refused=*/ true, why, Collections.<String, List<byte[]>>emptyMap());
    }

    /**
     * A claim that reached the KDS for every participant.
     *
     * <p>A participant the KDS served nothing for is present with an EMPTY list — that is the state
     * that means "they have none", and it must be recorded rather than omitted, or it becomes
     * indistinguishable from a participant nobody asked about.
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
     * The count {@link MlsUpgradePolicy} compares against the participant total — participants we
     * hold at least one usable-looking package for.
     *
     * <p>{@link MlsClaimLedger#CLAIM_REFUSED} when refused, and the policy tests for that sentinel
     * BEFORE the shortfall comparison precisely so a refusal cannot be read as a shortfall.
     *
     * <p><b>Counted over what was CLAIMED, not over what is left.</b> {@link #take} empties this
     * object as {@code establishGroup} consumes it, and a count that fell as packages were handed
     * out would report the roster as short at exactly the moment the upgrade was succeeding.
     */
    public int count() {
        if (mRefused) return MlsClaimLedger.CLAIM_REFUSED;
        int n = 0;
        for (final List<byte[]> pkgs : mByPeer.values()) {
            if (hasAny(pkgs)) n++;
        }
        return n;
    }

    /** How many participants this claim was made for — including any the KDS served nothing for. */
    public int claimedFor() {
        return mClaimedFor;
    }

    /** Does this claim carry an answer — of any kind — about {@code peer}? */
    public boolean covers(final String peer) {
        return peer != null && mByPeer.containsKey(peer);
    }

    /**
     * Hand this peer's packages over, ONCE.
     *
     * @return every device's package for {@code peer} — possibly an EMPTY list, meaning we asked and
     *     the KDS served nothing — or {@code null} when this claim carries no answer about them at
     *     all, including when they have already been taken. The two are different facts and a caller
     *     that collapses them blames a participant for our own bookkeeping.
     */
    public List<byte[]> take(final String peer) {
        if (peer == null) return null;
        return mByPeer.remove(peer);
    }

    /** Peers whose packages have not yet been taken. Zero after a create has consumed the claim. */
    public int remaining() {
        return mByPeer.size();
    }

    /** One line for a log: what was claimed, and how much of it is still in hand. */
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

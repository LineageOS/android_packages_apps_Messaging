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
 * How long a sent message's replay material must be kept — the §10.3 resend precondition.
 *
 * <h2>WHY THIS EXISTS: a positive delivery receipt is NOT terminal for a GROUP</h2>
 *
 * <p>Both send-side stores (the sealed-ciphertext cache and the pending-body store) were released on
 * the first {@code IMDN_DELIVERED}, on the reasoning written at that call site: "the peer has it, so
 * there is nothing left to resend". That reasoning is sound for a 1:1 and <b>wrong for a group</b>,
 * where a message has N recipients and one member's success says nothing about the other N-1.
 *
 * <p>Device-observed 2026-08-08 on the {@code resendlab} group: a group send stored its body, the
 * store held it for ~1.2s across twelve polls, then a positive receipt from ONE member released it —
 * so when a different member reported a failure-to-decrypt afterwards, §10.3 had nothing to resend
 * and logged "no stored text ... this message stays lost for them". The receive half, the routing
 * and the remedy selection were all correct; the bytes had simply been thrown away by an unrelated
 * member's success. A group resend was therefore structurally impossible, which is most of what a
 * resend is for.
 *
 * <p><b>What we cannot do yet, stated plainly.</b> The correct release for a group is "when EVERY
 * member has confirmed", and we cannot compute that: {@code onImdnReceipt} carries no sender, so a
 * receipt cannot be attributed to a member and deliveries cannot be counted. Adding the sender is an
 * AIDL contract change; until it lands, a group send keeps its material until it
 * ages out or fails permanently. That is deliberately conservative — holding bytes slightly too long
 * costs one of 256 slots, while dropping them too early costs a message that can never be recovered.
 */
public final class MlsSendRetentionPolicy {

    private MlsSendRetentionPolicy() {}

    /**
     * The retention window for send-side replay material.
     *
     * <p>24h. There is no §10.3 deadline to derive this from — the spec says nothing about how long
     * after a message a failure report may arrive — so this is a judgement call, sized against the
     * two costs it sits between: too short silently re-opens the bug this class fixes (an FTD
     * arrives and the bytes are gone), too long fills a 256-entry cap after which the stores DECLINE
     * to store and retries resume burning a generation each. A day comfortably covers a peer that
     * was offline overnight, and 256 messages per day per device is far above anything observed.
     *
     * <h2>THIS WINDOW NOW PROTECTS A USER-VISIBLE AFFORDANCE, not only a
     * peer-driven FTD — and that is a stronger reason to hold 24h than anything above</h2>
     *
     * <p>The paragraph above weighs this window entirely against a report from the PEER: an FTD
     * arrives, and either the bytes are here or they are not. That was the whole of it while a
     * stranded RCS row was invisible. It is not any more.
     *
     * <p>{@code FixupMessageStatusOnStartupAction} now sweeps {@code TRANSPORT_RCS} rows left at
     * {@code YET_TO_SEND(4)} or {@code AWAITING_RETRY(7)} to {@code OUTGOING_FAILED} at cold start —
     * measured in HEAD, in that action's RCS backstop arm, and it is the fix for a message that used
     * to sit at "Sending…" forever. So a user can now be shown <b>"Not sent"</b> for a message whose
     * replay material this window has already deleted: a visible, actionable failure with nothing
     * left to resend from. Shortening this window does not merely lose a peer's recovery path any
     * more; it puts a retry affordance in front of a person and then cannot honour it.
     *
     * <p>It is recorded HERE rather than next to the sweep because the
     * person it has to reach is the one reaching for this constant.
     *
     * <p><b>And do NOT unify this with an ACK deadline</b>, if one is ever built. They are different
     * quantities: this is a RETENTION window sized so a peer that was offline overnight can still
     * ask for a resend, while a deadline that moves a row off "sending" wants to be short enough to
     * be useful to a person. A deadline that fires at 24h would fire at the same moment the material
     * it would resend is being deleted. (No such deadline was built, on the
     * ground that a missing IMDN is not evidence of non-delivery — a peer that emits no IMDNs
     * produces one on every message — so the question is open rather than settled.)
     */
    public static final long DEFAULT_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    /**
     * Whether a positive delivery receipt may release a message's replay material.
     *
     * @param isGroupMessage whether the message was sent to a group rather than a single peer
     * @return {@code true} only for a 1:1, where the sole recipient confirming IS the terminal state
     */
    public static boolean releaseOnPositiveReceipt(final boolean isGroupMessage) {
        return !isGroupMessage;
    }

    /**
     * Whether a permanent send failure may release it. Always — nothing will resend a message the
     * provider has stopped trying to send, for a group or otherwise.
     *
     * <p>Read this together with {@link #forgetChainOnTerminal}: "may release it" is about the
     * attempt that failed, and says nothing about the rest of its resend chain. Scoping the two
     * questions to one answer is the bug the next method records.
     */
    public static boolean releaseOnPermanentFailure() {
        return true;
    }

    /**
     * Whether a terminal event may DELETE the resend chain's LEDGER ROWS.
     *
     * <h2>Only a delivery ends a chain</h2>
     *
     * <p>Forgetting the rows on a DELIVERY is right and is why the call exists (rework 7.4): the
     * message arrived, and rows recorded because it kept failing are no longer evidence about
     * anything — keeping them would leave the ladder at its top rung and turn the next ordinary
     * decrypt failure straight into an era advance.
     *
     * <p>On a permanent FAILURE the same call is destructive, and destructive in a way that is
     * invisible at the moment it happens. The rows are the ONLY thing that maps a resend's id back
     * to its root: a resend is minted as a bare UUID and never gets a chat row of its own, so once
     * its row is gone {@code MlsResendLedger.rootOf} returns it unchanged, the body lookup misses,
     * the chat-row fallback misses, and §10.3 reports "no stored text for &lt;uuid&gt; — cannot
     * resend it" about a message whose body is sitting in the store one link away. That is the
     * rung-1 stop this guards against, and the failed message stays lost.
     *
     * <p><b>This is not "stop releasing on failure".</b> The failed attempt's own bytes are still
     * released — see {@link #releaseOnPermanentFailure} and
     * {@link MlsResendRecord#materialToRelease} — so nothing is pinned that the release existed to
     * unpin. What survives is the bookkeeping, which is bounded by
     * {@code MlsResendBudget.WINDOW_MS} on the read side and cleared wholesale by
     * {@code forgetConversation} when the group is actually repaired.
     *
     * @param delivered {@code true} for a positive delivery receipt, {@code false} for a permanent
     *                  send failure
     */
    public static boolean forgetChainOnTerminal(final boolean delivered) {
        return delivered;
    }

    /**
     * How a store spells "this entry carries no elapsed stamp".
     *
     * <p>Negative, so it can never collide with a real {@code SystemClock.elapsedRealtime()}
     * reading — {@code 0} cannot be used for this, because zero is a legal reading in the first
     * millisecond after boot and "written at boot" must not be spelled the same way as "unknown".
     * That collision is what the wall-clock form had: it read {@code storedAtMs <= 0} as untimed.
     */
    public static final long UNSTAMPED = -1L;

    /**
     * Whether material stamped {@code storedElapsedMs} has aged out.
     *
     * <h2>Why this does not take the wall clock, and what the wall-clock form did</h2>
     *
     * <p>This class used to expose {@code expired(nowMs, storedAtMs, maxAgeMs)} over
     * {@code System.currentTimeMillis()}, and both send-side stores aged their entries with it. The
     * arithmetic runs the WRONG WAY for a retention store: a jump FORWARD makes a stored item look
     * old and DELETES it, and an NTP correction after a boot with a dead RTC is a forward jump of
     * arbitrary size. One pass could empty both stores, with the log saying only that entries had
     * aged out. The budget classes are the opposite case, where the same jump REFILLS an allowance;
     * these are the mirror image, and the loss is our own message's last resend material.
     *
     * <p>So the age comes from {@link MlsMonotonicAge}, whose every branch answers the largest age
     * it can PROVE. An entry can therefore be RETAINED longer than its window — after a reboot, for
     * as long as it takes the uptime to reach {@code maxAgeMs} — and can never be dropped EARLY.
     * That is the retention direction: over-retention is recoverable, a clock that moved must
     * never expire something early. Here the asymmetry is stark, because the two outcomes are not
     * comparable: holding bytes too long costs one of a few hundred slots, dropping them too early
     * costs a message that can never be recovered.
     *
     * <p><b>The price this charges, and where it is paid.</b> Nothing pre-reboot can be proven old
     * until the device has been up for the whole window, so a store sitting at its ceiling when the
     * device reboots cannot be relieved by this sweep for up to {@code maxAgeMs} of uptime. That is
     * real and is measured on the caller, not hand-waved here: {@code MlsPendingBodyStore} evicts
     * the oldest entry at its cap and self-heals, while {@code MlsCiphertextCache} declines and
     * needed a ceiling arm that evicts a PROVABLY pre-reboot entry — see
     * {@code MlsCiphertextCache.evictOldestPreReboot}.
     *
     * <p><b>{@link #UNSTAMPED} is expired, not immortal.</b> An entry we cannot age at all is
     * exactly the one that would pin a slot forever, and neither store's ceiling eviction can reach
     * one either (both order by age). This is NOT the answer for an entry written in an older
     * FORMAT — that one has a stamp, in the wrong clock, and its callers adopt it at the current
     * reading rather than judging it. Deciding those two cases the same way is how a format upgrade
     * silently becomes a deletion.
     */
    public static boolean expiredMonotonic(final long nowElapsedMs, final long storedElapsedMs,
            final long maxAgeMs) {
        if (storedElapsedMs < 0L) return true;
        return MlsMonotonicAge.ageMs(storedElapsedMs, nowElapsedMs) >= maxAgeMs;
    }
}

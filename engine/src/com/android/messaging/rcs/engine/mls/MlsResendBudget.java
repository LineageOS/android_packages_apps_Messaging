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
 * How many §10.3 resends one peer may draw, and over what period — <b>a count WITHIN A WINDOW</b>.
 *
 * <h2>WHY THE WINDOW EXISTS: a count-only cap poisons the conversation permanently</h2>
 *
 * <p>The ladder's counter is durable and row-backed, which is correct — counting rows is what stops
 * a diverged peer being handed a fresh budget by every restart. But a count with no time bound
 * <b>accumulates forever</b>, so one bad burst disables resends in that conversation for good.
 *
 * <p>Not hypothetical. On 2026-08-08 an uncapped arm resent 99 times in about forty seconds against
 * a real peer; once stopped, the 82 rows it left behind meant every subsequent <i>legitimate</i>
 * resend in that conversation was refused, and the only way back was an operator lever that deleted
 * the rows by hand. A bug that lasts forty seconds should not leave a conversation degraded until
 * someone notices and intervenes.
 *
 * <p><b>Google Messages bounds this on two independent limits</b>, and the pairing is the part worth copying
 * (from the engine's own reason enum): {@code ZINNIA_FAILURE_FTD_RETRY_LIMIT_EXCEEDED(12)} is a
 * COUNT bound and {@code ZINNIA_FAILURE_FTD_TIME_LIMIT_EXCEEDED(26)} is a TIME-WINDOW bound, with
 * the same count/time pair again for epoch self-heal ({@code 6}/{@code 22}). A burst ages out on its
 * own; nothing has to be cleared by hand.
 */
public final class MlsResendBudget {

    private MlsResendBudget() {}

    /**
     * Resends allowed to one peer, per conversation, within {@link #WINDOW_MS}.
     *
     * <p>Small on purpose. A resend that does not work is unlikely to work on the third attempt, and
     * the escalation ladder — repair the group — is the better answer past this point. The value
     * matches the escalation threshold the reason-4 path already used, so this change adds the
     * window without quietly loosening the count.
     */
    public static final int MAX_PER_WINDOW = 2;

    /**
     * The window. One hour.
     *
     * <p>Long enough that a genuinely broken peer cannot spin through budgets — at this count that is
     * at most a couple of resends an hour, which no peer can turn into a storm — and short enough
     * that a transient burst clears itself well before anyone would investigate it. There is no
     * spec deadline to derive this from; §10.3 says nothing about resend pacing, so it is a judgement
     * sized against those two costs.
     */
    public static final long WINDOW_MS = 60L * 60L * 1000L;

    /** The oldest timestamp still inside the window ending at {@code nowMs}. */
    public static long windowStart(final long nowMs) {
        return nowMs - WINDOW_MS;
    }

    /**
     * Whether the budget is spent.
     *
     * @param resendsInWindow resends already recorded for this peer+conversation within the window
     * @return true if the caller must NOT resend again
     */
    public static boolean spent(final int resendsInWindow) {
        return resendsInWindow >= MAX_PER_WINDOW;
    }
}

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

import android.text.TextUtils;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.action.ReceiveRcsGroupEventAction;
import com.android.messaging.util.LogUtil;
import org.lineageos.rcs.provider.IRcsProviderCallback;

import java.util.ArrayList;
import java.util.Collections;

/**
 * A member left over MLS: make the CONVERSATION say so (RCC.16 §9.4).
 *
 * <h2>The half that was missing</h2>
 *
 * <p>An inbound {@code self_remove} (0xF003) was already honoured in MLS terms — cached by
 * reference, swept into our next commit, proposer's leaf dropped. What did not happen is anything
 * the user could see: the departed participant stayed in the thread and nothing said they had left.
 * This is the same shape as {@link MlsSubjectApplier}, whose case was the decrypted subject that
 * went to logcat and no further.
 *
 * <h2>Why it dispatches the ordinary group-event Action instead of writing the tables</h2>
 *
 * <p>{@link ReceiveRcsGroupEventAction} already does every part of this — removes the participant,
 * writes the "X left" status line, refreshes the avatar and participant count, and posts the
 * notification and content-provider changes. Doing it again here would be a second mechanism for
 * one fact, and the two would drift.
 *
 * <p>It also buys the DE-DUP, which is the reason this can be safely redundant. When the leaver is
 * a client that also announces its departure over RCS — ours does, a {@code KickGroupUsers} naming
 * itself with the proposal on the KickGroupUsers request — the server fans a
 * {@code KickGroupUsersPush}
 * to the remaining members and the same Action runs from the same op with {@code requester} and
 * {@code affected} both set to the leaver. The event signature is {@code op + group + name + actor
 * + sorted affected}, so the synthesised event and the wire echo produce the IDENTICAL key and
 * collapse to ONE status line. Passing the leaver as the requester is therefore not cosmetic: it is
 * what makes the two agree, and it is also what makes the line read "X left" rather than "someone
 * removed X" ({@code samePhone(requester, affected)} selects that string).
 *
 * <h2>Why it is not always redundant</h2>
 *
 * <p>{@code KickGroupUsersPush} carries no MLS field — the {@code mls_control_message} at field 9
 * exists on the REQUEST only — so the RCS event and the MLS proposal are independent deliveries. A
 * by-reference {@code self_remove} can reach us with no group event behind it: the inbound
 * {@code proposal_list} arm exists because something that is not a Google client sends one, and
 * nothing obliges that peer to drive {@code KickGroupUsers} as well. When the RCS plane stays
 * silent, this is the only thing that tells the conversation a member is gone.
 *
 * <h2>The leaver is sometimes US</h2>
 *
 * <p>{@code MlsProviderTransport.leave} calls this with our OWN number, and for that direction there
 * is nothing to be redundant with: the server fans {@code KickGroupUsersPush} to the members who
 * REMAIN, so no echo ever reaches the leaver. Everything above holds unchanged — the same Action,
 * the same {@code requester == affected}, the same signature — with two things worth knowing:
 *
 * <ul>
 *   <li>the status line reads <b>"You left"</b>, which took reordering the two arms of
 *       {@code buildStatusText}'s KICK case: for the leaver both "they left on their own" and
 *       "someone removed you" are true, and only the first is right;</li>
 *   <li>removing the affected member from the participants table is a NO-OP for us, because
 *       {@code removeGroupParticipants} skips the self participant — which is what we want. Leaving
 *       a group must not delete our own identity from the conversation.</li>
 * </ul>
 */
public final class GroupDepartureApplier {

    private static final String TAG = LogUtil.BUGLE_TAG;

    private GroupDepartureApplier() {}

    /**
     * Record that {@code departedE164} is no longer in the group {@code rcsGroupId}.
     *
     * <p>{@code departedE164} may be our OWN number: that is the outbound self-leave, and it is the
     * one case where nothing else will ever report the departure.
     *
     * <p>Must not run on the main thread — the caller is already on the inbound control thread.
     *
     * @return true if the departure was dispatched to the conversation
     */
    public static boolean apply(final int subId, final String rcsGroupId,
            final String departedE164) {
        if (TextUtils.isEmpty(rcsGroupId) || TextUtils.isEmpty(departedE164)) return false;
        try {
            // RESOLVE WITHOUT CREATING, and bail if we hold no conversation. The Action's own
            // getOrCreateGroupConversation would happily create one — and a departure is the worst
            // possible reason to invent a conversation, since it would be created empty (this path
            // supplies no roster) and immediately have a member removed from it. MlsSubjectApplier
            // draws the same line for the same reason.
            final DatabaseWrapper db = DataModel.get().getDatabase();
            final String conversationId =
                    BugleDatabaseOperations.getExistingGroupConversation(db, rcsGroupId);
            if (TextUtils.isEmpty(conversationId)) {
                LogUtil.w(TAG, "GroupDepartureApplier: " + departedE164 + " left MLS group "
                        + rcsGroupId + ", which we hold no conversation for — nothing to update");
                return false;
            }
            // REQUESTER == AFFECTED. That is literally what a self_remove is, it is what the wire
            // echo carries for a self-leave, and it is what selects the "X left" string over
            // "someone removed X". An empty member list is correct and safe: for a KICK op the
            // Action does not backfill the roster, and on an EXISTING conversation
            // getOrCreateGroupConversation only ADDS members it is given — it never removes, so
            // passing none cannot shorten the participant list.
            new ReceiveRcsGroupEventAction(subId,
                    IRcsProviderCallback.GROUP_OP_KICK_USERS,
                    rcsGroupId,
                    /*name=*/ null,
                    /*conferenceUri=*/ null,
                    /*requester=*/ departedE164,
                    /*members=*/ new ArrayList<String>(),
                    new ArrayList<>(Collections.singletonList(departedE164)))
                    .start();
            LogUtil.i(TAG, "GroupDepartureApplier: " + departedE164 + " left MLS group " + rcsGroupId
                    + " (conversation " + conversationId + ") — dispatched as a KICK group event so "
                    + "the participant list, the status line and the de-dup are the same ones the "
                    + "RCS echo would use");
            return true;
        } catch (final Throwable t) {
            // Never let conversation bookkeeping take down the inbound control path. The MLS commit
            // that removed this member has already landed and is not in question here.
            LogUtil.w(TAG, "GroupDepartureApplier: failed to apply the departure of " + departedE164
                    + " from " + rcsGroupId, t);
            return false;
        }
    }
}

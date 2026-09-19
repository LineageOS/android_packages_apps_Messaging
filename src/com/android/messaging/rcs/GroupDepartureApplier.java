/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.text.TextUtils;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.action.ReceiveRcsGroupEventAction;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;
import org.lineageos.rcs.provider.IRcsProviderCallback;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Records a member's departure (our own leave) by dispatching a KICK group event with requester ==
 * affected == the leaver, so it de-duplicates against the server's echo. See docs/rcs/groups.md.
 */
public final class GroupDepartureApplier {

    private static final String TAG = LogUtil.BUGLE_TAG;

    private GroupDepartureApplier() {}

    /**
     * Records that {@code departedE164} (possibly our own number) left {@code rcsGroupId}. Not on
     * the main thread.
     *
     * @return true if the departure was dispatched to the conversation
     */
    public static boolean apply(final int subId, final String rcsGroupId,
            final String departedE164) {
        if (TextUtils.isEmpty(rcsGroupId) || TextUtils.isEmpty(departedE164)) return false;
        try {
            // Resolve without creating: a departure is never a reason to invent a conversation.
            final DatabaseWrapper db = DataModel.get().getDatabase();
            final String conversationId =
                    BugleDatabaseOperations.getExistingGroupConversation(db, rcsGroupId);
            if (TextUtils.isEmpty(conversationId)) {
                LogUtil.w(TAG, "GroupDepartureApplier: " + LogMask.number(departedE164)
                        + " left group "
                        + rcsGroupId + ", which we hold no conversation for — nothing to update");
                return false;
            }
            // requester == affected selects "X left"; the empty roster cannot remove anyone.
            new ReceiveRcsGroupEventAction(subId,
                    IRcsProviderCallback.GROUP_OP_KICK_USERS,
                    rcsGroupId,
                    /*name=*/ null,
                    /*conferenceUri=*/ null,
                    /*requester=*/ departedE164,
                    /*members=*/ new ArrayList<String>(),
                    new ArrayList<>(Collections.singletonList(departedE164)))
                    .start();
            LogUtil.i(TAG, "GroupDepartureApplier: " + LogMask.number(departedE164) + " left group "
                    + rcsGroupId
                    + " (conversation " + conversationId
                    + ") — dispatched as a KICK group event so "
                    + "the participant list, the status line and the de-dup are the same ones the "
                    + "RCS echo would use");
            return true;
        } catch (final Throwable t) {
            // Never throw into the caller; the leave has already been accepted.
            LogUtil.w(TAG, "GroupDepartureApplier: failed to apply the departure of "
                    + LogMask.number(departedE164)
                    + " from " + rcsGroupId, t);
            return false;
        }
    }
}

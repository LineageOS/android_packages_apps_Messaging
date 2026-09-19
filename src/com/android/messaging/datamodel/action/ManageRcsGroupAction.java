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
package com.android.messaging.datamodel.action;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsTransport;
import com.android.messaging.rcs.GroupDepartureApplier;
import com.android.messaging.rcs.e2ee.MlsProviderTransport;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * WAVE-C: member-management for an RCS group, fired from the People &amp; options
 * GROUP section (rename / add people / remove member).
 *
 * <p>The provider methods ({@code ProviderTransport.renameGroup /
 * addGroupUsers / removeGroupUsers}) are blocking binder calls, so all the work
 * runs off the main thread inside {@link #executeAction}. Each op:
 * <ol>
 *   <li>ASKS {@link MlsProviderTransport} which plane the conversation is on, and takes the MLS arm
 *       or refuses outright when it is encrypted;</li>
 *   <li>fires the provider RPC. On a {@code false}/failed result the caller is
 *       notified via {@link ManageRcsGroupListener#onManageFailed} so the UI can
 *       toast;</li>
 *   <li>and ONLY THEN mirrors the change into messaging.db (rename the
 *       conversation / add or remove the local participants), dropping a
 *       centered "You ..." SYSTEM status line — using the EXACT same signature
 *       ({@link ReceiveRcsGroupEventAction#buildEventSignature}) and insert path
 *       ({@link ReceiveRcsGroupEventAction#insertStatusMessage}) the inbound
 *       {@code onGroupEvent} echo uses, so when the server echoes the local
 *       user's action back it de-dups to ONE line.</li>
 * </ol>
 *
 * <p><b>Nothing here is optimistic any more, and this list is the thing a reader checks the
 * ordering against.</b> It described the reverse order — mirror first, unconditionally, never
 * rolled back — until the write moved behind the result on the plaintext arm, as had already
 * happened on the MLS arm. It also said the rename payload was "scaffolded
 * provider-side", which stopped being true once {@code renameGroup} became a real
 * GroupPropertiesUpdate the server validates and echoes.
 *
 * <p>The optimistic-self signature pins {@code requester == selfE164} so it
 * matches the echo (whose {@code requester} is the local user's MSISDN when the
 * local user initiated the action).
 */
public class ManageRcsGroupAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    /** Main-thread result callback so the UI can toast on a failed RPC. */
    public interface ManageRcsGroupListener {
        void onManageFailed(int op);
    }

    public static final int OP_RENAME = IRcsProviderCallback.GROUP_OP_CHANGE_PROFILE;
    public static final int OP_ADD = IRcsProviderCallback.GROUP_OP_ADD_USERS;
    public static final int OP_REMOVE = IRcsProviderCallback.GROUP_OP_KICK_USERS;
    /**
     * Leave this group.
     *
     * <p><b>Deliberately NOT a {@code GROUP_OP_*} value.</b> Those are the wire op codes (7-12) and
     * this one never reaches the wire or an event signature: on the MLS arm the transport writes the
     * whole local mirror itself, and on the plaintext arm the departure is dispatched as a KICK by
     * {@link GroupDepartureApplier}, which supplies {@code GROUP_OP_KICK_USERS} for the signature. The
     * value is out of that range so a future reader cannot mistake it for one, and
     * {@link #applyLocalMirror} and {@link #buildSelfStatusText} both fall through their defaults for
     * it — which is correct rather than an omission; see the arm in {@link #executeAction}.
     */
    public static final int OP_LEAVE = 1000;

    /**
     * Change the group's ICON — RCC.16 §9.7.1.4.
     *
     * <p>A LOCAL op number like {@link #OP_LEAVE}, not a {@code GROUP_OP_*}: there is no inbound
     * group event for "someone set an icon" to collide with. An icon arrives as an encrypted
     * reference on a profile push and is handled by {@code RcsCallbackRouter}, not here.
     *
     * <p>Numbered 1001 so it cannot be confused with {@link #OP_LEAVE} in a log line, and it falls
     * through {@code applyLocalMirror}'s and {@code buildSelfStatusText}'s defaults nowhere — both
     * have an explicit case, which is the difference from {@code OP_LEAVE}.
     */
    public static final int OP_CHANGE_ICON = 1001;

    private static final String KEY_OP = "op";
    private static final String KEY_CONVERSATION_ID = "conversation_id";
    private static final String KEY_GROUP_ID = "group_id";
    private static final String KEY_NAME = "name";
    private static final String KEY_MEMBERS = "members";
    /** The SCALED image bytes for {@link #OP_CHANGE_ICON}; see {@link #changeIcon}. */
    private static final String KEY_ICON = "icon";
    /** The picked image's own MIME, which travels in the §7.8.1 FileInfo, not on the wire field. */
    private static final String KEY_ICON_MIME = "icon_mime";

    /**
     * Queue a rename. {@code newName} is the user-entered group name; the
     * conversation is renamed locally immediately and the provider RPC fired.
     */
    public static void renameGroup(final String conversationId, final String groupId,
            final String newName, final ManageRcsGroupListener listener) {
        final ManageRcsGroupAction action = new ManageRcsGroupAction(OP_RENAME, conversationId,
                groupId, newName, null);
        action.startWithListener(listener);
    }

    /**
     * Queue a group ICON change.
     *
     * <p><b>{@code iconBytes} must already be SCALED, and the bound is the CALLER's job for a
     * reason.</b> The receive side caps an inbound icon at 256 KB before it crosses the Binder
     * ({@code debug.rcs.mls_icon_max_bytes}) — so a sender that ignores the bound produces
     * something its own peers drop, and an unscaled camera photo would also be several megabytes
     * travelling twice through a Binder transaction whose limit is about one. The UI scales because
     * it is the only layer that knows the picked image; this javadoc is where that obligation is
     * written down, since nothing below can check it.
     *
     * @param iconBytes   the scaled image
     * @param iconMime    the scaled image's MIME — carried in the §7.8.1 FileInfo, not on the wire
     */
    public static void changeIcon(final String conversationId, final String groupId,
            final byte[] iconBytes, final String iconMime,
            final ManageRcsGroupListener listener) {
        final ManageRcsGroupAction action = new ManageRcsGroupAction(OP_CHANGE_ICON, conversationId,
                groupId, null, null);
        action.actionParameters.putByteArray(KEY_ICON, iconBytes);
        action.actionParameters.putString(KEY_ICON_MIME, iconMime);
        action.startWithListener(listener);
    }

    /** Queue an add of {@code memberE164s} (already E.164-canonicalized). */
    public static void addUsers(final String conversationId, final String groupId,
            final ArrayList<String> memberE164s, final ManageRcsGroupListener listener) {
        final ManageRcsGroupAction action = new ManageRcsGroupAction(OP_ADD, conversationId,
                groupId, null, memberE164s);
        action.startWithListener(listener);
    }

    /** Queue removal of a single member (passed as a 1-element E.164 list). */
    public static void removeUsers(final String conversationId, final String groupId,
            final ArrayList<String> memberE164s, final ManageRcsGroupListener listener) {
        final ManageRcsGroupAction action = new ManageRcsGroupAction(OP_REMOVE, conversationId,
                groupId, null, memberE164s);
        action.startWithListener(listener);
    }

    /**
     * Queue OUR OWN departure from {@code groupId}.
     *
     * <p>No member list: the only participant is us, and the transport resolves the conversation
     * from the group id alone.
     */
    public static void leaveGroup(final String conversationId, final String groupId,
            final ManageRcsGroupListener listener) {
        final ManageRcsGroupAction action = new ManageRcsGroupAction(OP_LEAVE, conversationId,
                groupId, null, null);
        action.startWithListener(listener);
    }

    private ManageRcsGroupAction(final int op, final String conversationId, final String groupId,
            @Nullable final String name, @Nullable final ArrayList<String> memberE164s) {
        actionParameters.putInt(KEY_OP, op);
        actionParameters.putString(KEY_CONVERSATION_ID, conversationId);
        actionParameters.putString(KEY_GROUP_ID, groupId);
        actionParameters.putString(KEY_NAME, name);
        actionParameters.putStringArrayList(KEY_MEMBERS,
                memberE164s == null ? new ArrayList<>() : memberE164s);
    }

    private void startWithListener(final ManageRcsGroupListener listener) {
        final ManageRcsGroupActionMonitor monitor =
                new ManageRcsGroupActionMonitor(actionParameters.getInt(KEY_OP), listener);
        // Re-create the action carrying the monitor's action key so the
        // completion routes back to the listener.
        final ManageRcsGroupAction keyed = new ManageRcsGroupAction(this, monitor.getActionKey());
        keyed.start(monitor);
    }

    private ManageRcsGroupAction(final ManageRcsGroupAction src, final String actionKey) {
        super(actionKey);
        actionParameters.putAll(src.actionParameters);
    }

    /** @return Boolean RPC result (true == provider accepted), never null. */
    @Override
    protected Object executeAction() {
        if (OsUtil.isSecondaryUser()) {
            return Boolean.TRUE;
        }
        final int op = actionParameters.getInt(KEY_OP);
        final String conversationId = actionParameters.getString(KEY_CONVERSATION_ID);
        final String groupId = actionParameters.getString(KEY_GROUP_ID);
        final String name = actionParameters.getString(KEY_NAME);
        final ArrayList<String> members = actionParameters.getStringArrayList(KEY_MEMBERS);

        if (TextUtils.isEmpty(conversationId) || TextUtils.isEmpty(groupId)) {
            LogUtil.w(TAG, "ManageRcsGroupAction: missing conversationId/groupId; op=" + op);
            return Boolean.FALSE;
        }

        final Context context = Factory.get().getApplicationContext();
        final int subId = defaultSubId();
        final DatabaseWrapper db = DataModel.get().getDatabase();

        final String selfE164 = selfE164(subId);

        // 0) MLS ROUTING — BEFORE THE OPTIMISTIC WRITE.
        //
        // On an ENCRYPTED group the bare RPCs below are refused by the server: proven by
        // flipping one group and re-testing two minutes later with identical request bytes. Both
        // rows were still offered — isManageableRcsGroup() gates on rcs_group_id and group-RCS
        // availability and says nothing about encryption — so every Add/Remove on an MLS group was a
        // shipped affordance that could not work, AND the optimistic mirror below ran anyway and was
        // never rolled back, leaving messaging.db's roster permanently ahead of the server's.
        //
        // So this is deliberately not just a re-route: on the MLS arm the local mirror is written
        // only AFTER the change is accepted. Ordering it the other way is what turned a refusal into
        // silent divergence, and it is a lie the moment the guard refuses. The plaintext arm keeps
        // the optimistic ordering exactly as it was — there the RPC succeeds and the mirror is what
        // makes the change visible at once.
        //
        // RENAME IS ROUTED TOO, at arm 0c below — and the account that used to stand here was
        // wrong on both of its facts (corrected 2026-09-13):
        //
        //  - "ChangeGroupProfile's own control-message slot has never been built or measured". It IS
        //    built and IS device-measured: TachyonRegistrar.changeGroupProfileWithMls sets
        //    mls_control_message = 8, and it is the shipped encrypted-subject path (contract
        //    v36→v39). What is missing is not a wire shape.
        //  - "there is no renameGroupMls" was true and is the wrong thing to want. Google Messages'
        //    own MLS group-request modifier never reads the display NAME at all — it transforms
        //    the SUBJECT slot and nothing else. The
        //    encrypted-subject flow IS the MLS rename, it is already built as
        //    MlsProviderTransport.changeGroupSubject, and the UI is simply not wired to it.
        if (op == OP_ADD || op == OP_REMOVE) {
            final MlsProviderTransport.GroupMembershipRouting routed =
                    routeThroughMls(context, subId, groupId, members, op == OP_ADD);
            if (routed != MlsProviderTransport.GroupMembershipRouting.PLAINTEXT) {
                final boolean applied =
                        routed == MlsProviderTransport.GroupMembershipRouting.APPLIED;
                if (applied) {
                    applyLocalMirror(db, conversationId, groupId, subId, op, name, members,
                            selfE164);
                    MessagingContentProvider.notifyMessagesChanged(conversationId);
                    MessagingContentProvider.notifyConversationListChanged();
                }
                // THE FAILURE HALF SAYS ONLY WHAT IT OBSERVED. REFUSED is the transport's verdict
                // for the request as a whole; it is NOT a claim that the server is unchanged. If the
                // group were forgotten between our branch and addMember's own re-derivation of it,
                // addMember's plaintext arm would have made the bare RCS roster change before its
                // commit failed. That race is narrow — but "unchanged on both sides" would be an
                // assertion this line cannot see, and the local half is all it can.
                LogUtil.i(TAG, "ManageRcsGroupAction: op=" + op + " groupId=" + groupId
                        + " went through MLS → " + routed
                        + (applied ? "" : " — nothing written to messaging.db"));
                return Boolean.valueOf(applied);
            }
        }

        // 0b) LEAVE — routed the same way, and it does NOT rejoin the arms below.
        //
        // It was originally argued that a leave is "NOT another arm of that action", on the grounds
        // that the other three fire a bare RPC and optimistically mirror it. That is no longer
        // what add and remove do — they ask the transport first and mirror only on acceptance — so a
        // leave is now the same shape as its two neighbours rather than a different one, and giving
        // it the action framework's off-main execution, monitor and failure listener a second time
        // is how the two copies drift.
        //
        // WHAT IS GENUINELY DIFFERENT is the local write, and it is different in BOTH directions:
        //  - on the MLS arm there must be NONE. leave() has already written the terminal mark, the
        //    §4.8 row-14 clear, the pending-slot release, the encryption bit and the "You left" line
        //    (recordSelfDeparture + announceSelfDeparture). A second optimistic write
        //    would share the de-dup signature and collapse into it — but only while it used the
        //    identical requester/affected, which is a coincidence to depend on, not a design.
        //  - on the plaintext arm there must be one, and applyLocalMirror cannot supply it:
        //    buildSelfStatusText would render "You removed You", and removeGroupParticipants skips
        //    the self participant by design. GroupDepartureApplier produces the right string ("You
        //    left", selected by requester == affected) and the right signature, which is the same
        //    one the wire echo would carry if the server ever fanned one back to the leaver. It
        //    does not — the push goes to the members who REMAIN — so this write is the only thing
        //    that tells the thread, exactly as it is on the MLS arm.
        //
        // Despite its package, nothing in GroupDepartureApplier is MLS-specific: it dispatches the
        // ordinary ReceiveRcsGroupEventAction. Using it here is one mechanism for one fact.
        if (op == OP_LEAVE) {
            final MlsProviderTransport.GroupMembershipRouting routed;
            try {
                routed = MlsProviderTransport.get(context, subId).leaveGroup(groupId);
            } catch (final Throwable t) {
                // REFUSED, not PLAINTEXT — routeThroughMls's rule, and for the same reason: a bare
                // KickGroupUsers naming ourselves on a conversation we just failed to classify is
                // the request an MLS group refuses, and leaving is irreversible, so a guess in that
                // direction cannot be undone.
                LogUtil.e(TAG, "ManageRcsGroupAction: the MLS transport could not be asked whether "
                        + groupId + " is encrypted — refusing the leave rather than sending the "
                        + "bare RPC an MLS group rejects", t);
                return Boolean.FALSE;
            }
            if (routed != MlsProviderTransport.GroupMembershipRouting.PLAINTEXT) {
                final boolean applied =
                        routed == MlsProviderTransport.GroupMembershipRouting.APPLIED;
                LogUtil.i(TAG, "ManageRcsGroupAction: leave groupId=" + groupId
                        + " went through MLS → " + routed
                        + (applied ? " — the transport wrote its own local state" : ""));
                return Boolean.valueOf(applied);
            }
            final ProviderTransport plain = resolveTransport(context, subId);
            if (plain == null) return Boolean.FALSE;
            boolean leftOk;
            try {
                leftOk = !TextUtils.isEmpty(selfE164)
                        && plain.removeGroupUsers(subId, groupId,
                                new ArrayList<>(java.util.Collections.singletonList(selfE164)));
            } catch (final Throwable t) {
                LogUtil.w(TAG, "ManageRcsGroupAction: plaintext leave threw; groupId=" + groupId, t);
                leftOk = false;
            }
            if (leftOk) {
                // AFTER the RPC, not before. The MLS arm writes its mirror only on acceptance;
                // a plaintext leave that mirrored first would be the same silent
                // divergence one row over, and this one cannot be undone by tapping again.
                GroupDepartureApplier.apply(subId, groupId, selfE164);
            }
            LogUtil.i(TAG, "ManageRcsGroupAction: leave groupId=" + groupId
                    + " took the PLAINTEXT path (removeGroupUsers naming ourselves) → " + leftOk
                    + (TextUtils.isEmpty(selfE164)
                            ? " — REFUSED: our own E.164 is unknown, so there is nobody to remove"
                            : ""));
            return Boolean.valueOf(leftOk);
        }

        // 0c) RENAME — ROUTED to the RCC.16 §9.7.1.5 ENCRYPTED SUBJECT on an MLS conversation
        //     (this arm previously REFUSED here instead).
        //
        //     THE BARE RPC PUTS THE GROUP NAME ON THE WIRE IN CLEARTEXT. On an MLS conversation the
        //     human-visible title is the encrypted subject — that is what MlsSubjectApplier writes
        //     into conversations.name when a decrypted subject arrives — so sending the same string
        //     unencrypted hands the server the one value §9.7.1.5 exists to hide, on a thread the
        //     app draws with a padlock. Refusing rather than finding that out by leaking was the
        //     right thing to land without a device and was never the fix.
        //
        //     WHAT IS STILL NOT CLAIMED: that the server would have refused the bare rename. Nobody
        //     has measured one on an MLS group; only the bare ADD and KICK were measured. Routing
        //     does not block that measurement either — `--es plainrename` in RcsDebugSendReceiver
        //     fires ProviderTransport.renameGroup directly and never comes through here — and the
        //     answer still decides an open question: a §9.7.1.5-only rename leaves the cleartext
        //     name slot at whatever it was, so a Google Messages peer may see the OLD name or none.
        //
        //     THE DECISION IS NOT MADE HERE. MlsProviderTransport.renameGroupRouting owns it and
        //     asks groupPlane — the ONE evaluation of "is this encrypted", already carrying the
        //     MLS_LOCKED_OUT case. This site only obeys it. Note the APPLIED arm below writes a
        //     local mirror the other arms treat as
        //     optional: the sender cannot decrypt its own subject, so nothing else will ever show
        //     it the new name.
        // THE ICON ARM — the rename's twin, and deliberately adjacent so the two are read
        // together. Same single evaluation via groupPlane, same obey-do-not-re-derive rule, same
        // mirror-on-acceptance-only.
        //
        // The ONE asymmetry: there is no plaintext fall-through below. iconChangeRouting never
        // returns PLAINTEXT for a real group, because this provider has no plaintext group-icon
        // path at all — so unlike the rename, an unencrypted conversation gets REFUSED rather than
        // dropping to a bare RPC. If a plaintext icon verb is ever added, this arm and that method
        // change together.
        if (op == OP_CHANGE_ICON) {
            final byte[] icon = actionParameters.getByteArray(KEY_ICON);
            final String iconMime = actionParameters.getString(KEY_ICON_MIME);
            final MlsProviderTransport.GroupMembershipRouting routed;
            try {
                routed = MlsProviderTransport.get(context, subId)
                        .iconChangeRouting(groupId, icon, iconMime);
            } catch (final Throwable t) {
                LogUtil.e(TAG, "ManageRcsGroupAction: the MLS transport could not be asked whether "
                        + groupId + " is encrypted — refusing the icon change", t);
                return Boolean.FALSE;
            }
            final boolean applied =
                    routed == MlsProviderTransport.GroupMembershipRouting.APPLIED;
            if (applied) {
                // THE MIRROR IS LOAD-BEARING HERE FOR THE SAME REASON AS THE RENAME, and it is the
                // reason the UI row was not shipped before the receive half existed. The
                // key rides in an MLS private message and MLS gives you no way to decrypt your own,
                // so GroupIconApplier never fires for the person who SET the icon — add and remove
                // get an inbound echo, an icon-setter gets nothing. This write is the ONLY thing
                // that shows them their own new photo, and without it a change that genuinely
                // succeeded looks exactly like one that failed. A sender showing the old SUBJECT
                // already cost one wrong diagnosis.
                //
                // ON ACCEPTANCE ONLY, like every other arm.
                applyLocalMirror(db, conversationId, groupId, subId, op, name, members, selfE164);
                MessagingContentProvider.notifyMessagesChanged(conversationId);
                MessagingContentProvider.notifyConversationListChanged();
            }
            LogUtil.i(TAG, "ManageRcsGroupAction: op=" + op + " (CHANGE_ICON) groupId=" + groupId
                    + " went through MLS → " + routed + " (" + (icon == null ? 0 : icon.length)
                    + "B " + iconMime + ")"
                    + (applied ? " — §9.7.1.4 encrypted icon sent, photo mirrored locally"
                               : " — nothing sent and nothing written to messaging.db"));
            return Boolean.valueOf(applied);
        }

        if (op == OP_RENAME) {
            final MlsProviderTransport.GroupMembershipRouting routed;
            try {
                routed = MlsProviderTransport.get(context, subId)
                        .renameGroupRouting(groupId, name);
            } catch (final Throwable t) {
                // REFUSED, not PLAINTEXT — routeThroughMls's rule. A conversation we just failed to
                // classify may be one we are drawing a padlock on, and the bare rename would put
                // its name on the server in the clear.
                LogUtil.e(TAG, "ManageRcsGroupAction: the MLS transport could not be asked whether "
                        + groupId + " is encrypted — refusing the rename rather than sending the "
                        + "group name in the clear on a conversation that may be encrypted", t);
                return Boolean.FALSE;
            }
            if (routed != MlsProviderTransport.GroupMembershipRouting.PLAINTEXT) {
                final boolean applied =
                        routed == MlsProviderTransport.GroupMembershipRouting.APPLIED;
                if (applied) {
                    // THE MIRROR IS LOAD-BEARING HERE IN A WAY IT IS NOT ON THE OTHER ARMS, and
                    // that asymmetry is a trap (see renameGroupRouting's
                    // javadoc). Add and remove get an inbound echo as well as this write; a RENAMER
                    // gets NOTHING. MlsSubjectApplier is what turns a decrypted §9.7.1.5 subject
                    // into conversations.name, and it can never fire for the sender — the key rides
                    // in an MLS private message and MLS gives you no way to decrypt your own. So
                    // this write is the ONLY thing that ever shows the new title to the person who
                    // typed it, and dropping it would leave them looking at the old name on a
                    // rename that genuinely succeeded. That exact appearance cost a wrong diagnosis
                    // once already.
                    //
                    // It writes the PLAINTEXT name into messaging.db, which is right and is not a
                    // leak: applyLocalMirror calls BugleDatabaseOperations.renameGroupConversation,
                    // the SAME function MlsSubjectApplier uses for a decrypted inbound subject. The
                    // local database holds readable titles on both paths; only the wire carries
                    // ciphertext.
                    //
                    // ON ACCEPTANCE ONLY, like every other arm.
                    applyLocalMirror(db, conversationId, groupId, subId, op, name, members,
                            selfE164);
                    MessagingContentProvider.notifyMessagesChanged(conversationId);
                    MessagingContentProvider.notifyConversationListChanged();
                }
                LogUtil.i(TAG, "ManageRcsGroupAction: op=" + op + " (RENAME) groupId=" + groupId
                        + " went through MLS → " + routed
                        + (applied ? " — §9.7.1.5 encrypted subject sent, name mirrored locally"
                                   : " — nothing sent and nothing written to messaging.db"));
                return Boolean.valueOf(applied);
            }
        }

        // 1) Fire the blocking provider RPC. Multi-transport framework (design §2,
        //    §5.4): route the rich provider-only group-management surface through the
        //    sub's selected transport when it IS a ProviderTransport, else fall back to
        //    the legacy singleton (byte-identical on a provider-only device).
        //
        //    THE RPC RUNS BEFORE THE LOCAL MIRROR, AND THAT ORDER IS THE POINT.
        //
        //    This arm used to mirror first, unconditionally, and never roll back — the helper's own
        //    javadoc said "the optimistic local state is left in place — best-effort". The ordering
        //    was fixed on the MLS arm first, because changing the plaintext ordering was out of
        //    that change's scope. Device-measured 2026-09-10 as the control for it:
        //    removing a member from plaintext conversation 33 returned FAILED_PRECONDITION
        //    tachyonerror=-1, and messaging.db had dropped the member anyway. The local roster ends
        //    up permanently ahead of the server's, every tap widens it, nothing reconciles it.
        //
        //    WHY (a) — MIRROR AFTER — AND NOT (b) ROLL BACK OR (c) A PENDING ROW.
        //
        //    The objection to (a) is latency: the optimistic write exists so the UI updates at once
        //    on a slow network. THAT BENEFIT WAS NOT OBSERVED ON THE SCREEN WHERE THE ACTION IS
        //    TAKEN. The same measurement recorded that People & options went on rendering the
        //    removed member's row — a stale view, not a second source of truth — so the user saw
        //    nothing change either way while the roster underneath had already diverged. An
        //    optimistic write whose visible benefit is not visible is paying the divergence cost for
        //    nothing. And executeAction already runs off the main thread, so nothing blocks.
        //
        //    (b) needs the mirror to have an inverse, and it has none that can be written honestly:
        //    removeGroupParticipants destroys the participant rows rather than hiding them, and the
        //    OP_ADD reconcile adds "whatever was missing", so undoing it means snapshotting the
        //    prior roster first. Every line of that rollback runs only on failure, which is the
        //    least-exercised code in the file guarding the case that matters most.
        //    (c) needs a pending state the schema and the UI do not have.
        //
        //    And (a) is what the two neighbouring arms in THIS METHOD already do — the MLS add/
        //    remove and the plaintext leave. Three arms with two orderings was
        //    the two-copies-of-one-fact shape; there is now one.
        //
        //    THE STATUS LINE IS ORDER-INDEPENDENT, so moving it costs nothing there:
        //    insertStatusMessage de-dups on the event signature and the inbound echo computes the
        //    same one, so whichever of the two arrives second is dropped, exactly as before.
        final ProviderTransport transport = resolveTransport(context, subId);
        if (transport == null) {
            return Boolean.FALSE;
        }

        boolean ok;
        try {
            switch (op) {
                case OP_RENAME:
                    ok = transport.renameGroup(subId, groupId, name);
                    break;
                case OP_ADD:
                    ok = transport.addGroupUsers(subId, groupId, members);
                    break;
                case OP_REMOVE:
                    ok = transport.removeGroupUsers(subId, groupId, members);
                    break;
                default:
                    LogUtil.w(TAG, "ManageRcsGroupAction: unknown op=" + op);
                    ok = false;
                    break;
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ManageRcsGroupAction: provider call threw; op=" + op, t);
            ok = false;
        }

        // 2) Mirror it locally — ONLY if the server took it. A refused change writes
        //    nothing, which is the whole point: messaging.db must not claim a roster the server
        //    does not hold.
        if (ok) {
            applyLocalMirror(db, conversationId, groupId, subId, op, name, members, selfE164);
            MessagingContentProvider.notifyMessagesChanged(conversationId);
            MessagingContentProvider.notifyConversationListChanged();
        }

        LogUtil.i(TAG, "ManageRcsGroupAction: op=" + op + " groupId=" + groupId
                + " result=" + ok + (ok ? "" : " — nothing written to messaging.db"));
        return Boolean.valueOf(ok);
    }

    /**
     * The sub's selected {@link ProviderTransport}, or the legacy singleton — null when neither is
     * reachable. Extracted so the leave arm above and the three ops below resolve it identically
     *; byte-identical to what the ops did inline before.
     */
    @Nullable
    private static ProviderTransport resolveTransport(final Context context, final int subId) {
        try {
            final ProviderRegistry registry = ProviderRegistry.peek();
            final RcsTransport selected = (registry != null)
                    ? registry.getActiveTransport(subId) : null;
            return (selected instanceof ProviderTransport)
                    ? (ProviderTransport) selected : ProviderTransport.getInstance(context);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ManageRcsGroupAction: RCS transport unavailable", t);
            return null;
        }
    }

    /**
     * Ask the MLS layer to make this membership change, or to say it is not its conversation.
     *
     * <p>The branch itself is NOT made here — {@link MlsProviderTransport#changeGroupMembership}
     * owns it, because it owns the state the answer comes from. This method only carries the
     * question across and turns an unreachable transport into an answer that is not a lie.
     *
     * <p><b>A transport we cannot reach is REFUSED, not PLAINTEXT.</b> Returning PLAINTEXT would let
     * the bare RPC fire on a conversation we just failed to classify, and the optimistic mirror
     * would follow it — which is precisely the silent divergence this routing exists to end. The
     * cost is that an MLS-layer defect makes a plain-group membership change toast instead of
     * working; that is the direction to fail in, and the log names the throw.
     */
    private static MlsProviderTransport.GroupMembershipRouting routeThroughMls(final Context context,
            final int subId, final String groupId, final List<String> members, final boolean add) {
        try {
            return MlsProviderTransport.get(context, subId)
                    .changeGroupMembership(groupId, members, add);
        } catch (final Throwable t) {
            LogUtil.e(TAG, "ManageRcsGroupAction: the MLS transport could not be asked whether "
                    + groupId + " is encrypted — refusing the " + (add ? "add" : "remove") + " "
                    + "rather than sending the bare RPC an MLS group rejects and mirroring it "
                    + "locally as though it had worked", t);
            return MlsProviderTransport.GroupMembershipRouting.REFUSED;
        }
    }

    /**
     * Mirror the action into messaging.db and drop the matching SYSTEM status line. Rename ->
     * rename conversation; add -> reconcile the roster (adds the new members); remove -> drop the
     * participants. The status line is written with requester == self so it renders "You ..." and
     * shares the de-dup signature with the eventual inbound echo.
     *
     * <p><b>Was {@code applyOptimistic}, and the rename is part of the fix.</b>
     * It ran unconditionally BEFORE the RPC on every arm and was never rolled back.
     * The MLS arm now calls it only on {@code APPLIED}; the plaintext arm calls it
     * only when the RPC returned true, and the leave arm does not call it at all. No caller is
     * optimistic any more, so a name that says otherwise describes a design this file no longer
     * follows — and it is the name a reader checks the ordering against.
     */
    private void applyLocalMirror(final DatabaseWrapper db, final String conversationId,
            final String groupId, final int subId, final int op, @Nullable final String name,
            @NonNull final List<String> members, @Nullable final String selfE164) {
        switch (op) {
            case OP_RENAME:
                if (!TextUtils.isEmpty(name)) {
                    BugleDatabaseOperations.renameGroupConversation(db, conversationId, name);
                }
                break;
            case OP_ADD:
                if (!members.isEmpty()) {
                    // Reuse the roster-reconcile path: adds members missing
                    // locally, leaves a server-owned name intact (groupName=null).
                    BugleDatabaseOperations.getOrCreateGroupConversation(db, groupId,
                            null /* groupName */, subId, members, false /* rosterIncomplete */);
                }
                break;
            case OP_REMOVE:
                if (!members.isEmpty()) {
                    BugleDatabaseOperations.removeGroupParticipants(db, conversationId, subId,
                            members);
                }
                break;
            case OP_CHANGE_ICON:
                // THE SAME WRITER THE INBOUND PATH USES. GroupIconApplier owns
                // ConversationColumns.ICON — it writes the durable file and points the column at
                // it — and that ownership is explicit, so the derived-avatar path no
                // longer overwrites it on a roster refresh. Going around the applier here would
                // create a second writer for that column, and would put a value in the
                // column that the ownership predicate does not recognise as owned.
                final byte[] mirrorIcon = actionParameters.getByteArray(KEY_ICON);
                if (mirrorIcon != null && mirrorIcon.length > 0) {
                    com.android.messaging.rcs.GroupIconApplier.apply(
                            Factory.get().getApplicationContext(), groupId, mirrorIcon);
                }
                break;
            default:
                break;
        }

        final String statusText = buildSelfStatusText(subId, op, name, members);
        if (TextUtils.isEmpty(statusText)) {
            return;
        }
        // affected = the changed members (sorted inside buildEventSignature);
        // name only for rename. requester = selfE164 so the signature collides
        // with the inbound echo for the local user's own action.
        final ArrayList<String> affected = (op == OP_RENAME)
                ? null : new ArrayList<>(members);
        final String signature = ReceiveRcsGroupEventAction.buildEventSignature(
                subId, op, groupId, (op == OP_RENAME) ? name : null, selfE164, affected);
        ReceiveRcsGroupEventAction.insertStatusMessage(db, conversationId, subId,
                selfE164, statusText, signature);
    }

    /** Self-attributed status text ("You renamed ...", "You added ...", "You removed ..."). */
    @Nullable
    private String buildSelfStatusText(final int subId, final int op,
            @Nullable final String name, @NonNull final List<String> members) {
        final Context context = Factory.get().getApplicationContext();
        final String you = context.getString(R.string.rcs_group_event_self);
        switch (op) {
            case OP_RENAME:
                if (TextUtils.isEmpty(name)) {
                    return null;
                }
                return context.getString(R.string.rcs_group_event_renamed_by, you, name);
            case OP_CHANGE_ICON:
                return context.getString(R.string.rcs_group_event_icon_changed_by, you);
            case OP_ADD:
                if (members.isEmpty()) {
                    return context.getString(R.string.rcs_group_event_added_generic, you);
                }
                return context.getString(R.string.rcs_group_event_added, you,
                        joinDisplayNames(subId, members));
            case OP_REMOVE:
                if (members.isEmpty()) {
                    return context.getString(R.string.rcs_group_event_removed_generic, you);
                }
                return context.getString(R.string.rcs_group_event_removed, you,
                        joinDisplayNames(subId, members));
            default:
                return null;
        }
    }

    /** Comma-joined display names for the affected members. */
    private String joinDisplayNames(final int subId, final List<String> members) {
        final ArrayList<String> names = new ArrayList<>(members.size());
        for (final String m : members) {
            if (TextUtils.isEmpty(m)) {
                continue;
            }
            names.add(ParticipantData.getFromRawPhoneBySimLocale(m, subId)
                    .getDisplayName(true /* preferFullName */));
        }
        return TextUtils.join(", ", names);
    }

    @Nullable
    private String selfE164(final int subId) {
        try {
            return PhoneUtils.get(subId).getCanonicalForSelf(true /* allowOverride */);
        } catch (final Throwable t) {
            return null;
        }
    }

    private static int defaultSubId() {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultDataSubscriptionId();
        }
        return subId;
    }

    /** Routes the RPC result back to the listener so a failure can toast. */
    public static class ManageRcsGroupActionMonitor extends ActionMonitor
            implements ActionMonitor.ActionCompletedListener {
        private final ManageRcsGroupListener mListener;
        private final int mOp;

        ManageRcsGroupActionMonitor(final int op, final ManageRcsGroupListener listener) {
            super(STATE_CREATED, generateUniqueActionKey("ManageRcsGroupAction"), null);
            setCompletedListener(this);
            mOp = op;
            mListener = listener;
        }

        @Override
        public void onActionSucceeded(final ActionMonitor monitor, final Action action,
                final Object data, final Object result) {
            if (mListener != null && Boolean.FALSE.equals(result)) {
                mListener.onManageFailed(mOp);
            }
        }

        @Override
        public void onActionFailed(final ActionMonitor monitor, final Action action,
                final Object data, final Object result) {
            if (mListener != null) {
                mListener.onManageFailed(mOp);
            }
        }
    }

    private ManageRcsGroupAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<ManageRcsGroupAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public ManageRcsGroupAction createFromParcel(final Parcel in) {
            return new ManageRcsGroupAction(in);
        }

        @Override
        public ManageRcsGroupAction[] newArray(final int size) {
            return new ManageRcsGroupAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}

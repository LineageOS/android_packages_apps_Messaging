/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.android.messaging.util.LogUtil;

/**
 * Confirms a stored inbound callback to the provider that made it, so the provider acknowledges the
 * message upstream only once it is stored. See "Delivery confirmation" in
 * docs/rcs/provider-contract.md.
 *
 * <p>The router takes a {@link Ticket} on the binder thread, where the caller is known, and hands
 * it to the receive action; the action sends it with {@link #afterStore} once its store has
 * returned. A handler that throws sends nothing, so the provider offers the message again.
 */
public final class RcsInboundConfirmation {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static final String KEY_SUB_ID = "inbound_confirm_sub_id";
    private static final String KEY_ID = "inbound_confirm_id";
    private static final String KEY_UID = "inbound_confirm_uid";

    private RcsInboundConfirmation() {}

    /** A provider that can take a confirmation: {@link ProviderTransport}, or a bound provider. */
    public interface Upstream {
        /** Whether the provider this transport is bound to runs as {@code uid}. */
        boolean deliversFromUid(int uid);

        /** Forwards {@code IRcsProvider.ackInboundMessages}. */
        void ackInboundMessages(int subId, String... ids);
    }

    /** What one stored callback confirms, and to whom. */
    public static final class Ticket {
        final int subId;
        final String id;
        final int uid;

        Ticket(final int subId, final String id, final int uid) {
            this.subId = subId;
            this.id = id;
            this.uid = uid;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * The ticket for the callback running on this binder thread, or null when there is nothing to
     * confirm: no id, or a call from this process (the in-process carrier transport, or a message
     * the router decoded itself and confirms on its own path).
     */
    @Nullable
    public static Ticket forBinderCall(final int subId, @Nullable final String id) {
        if (TextUtils.isEmpty(id)) return null;
        final int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return null;
        return new Ticket(subId, id, uid);
    }

    /** Stores {@code ticket} with an action's parameters; a null ticket stores nothing. */
    public static void attach(@Nullable final Ticket ticket, final Bundle params) {
        if (ticket == null) return;
        params.putInt(KEY_SUB_ID, ticket.subId);
        params.putString(KEY_ID, ticket.id);
        params.putInt(KEY_UID, ticket.uid);
    }

    /**
     * Moves the ticket from one action's parameters to another's, when the first hands its store
     * to the second: the second confirms once it has stored.
     */
    public static void moveTo(final Bundle from, final Bundle to) {
        final Ticket t = read(from);
        if (t == null) return;
        from.remove(KEY_SUB_ID);
        from.remove(KEY_ID);
        from.remove(KEY_UID);
        attach(t, to);
    }

    /**
     * Sends the confirmation an action carries. Call only after the action's store has returned,
     * on every path that is a final answer: stored, already stored, or deliberately not stored.
     */
    public static void afterStore(final Bundle params, final String what) {
        final Ticket t = read(params);
        if (t != null) send(t, what);
    }

    /** Sends {@code ticket} to the provider running as its caller's uid. */
    public static void send(final Ticket ticket, final String what) {
        final Upstream up = upstreamFor(ticket.uid);
        if (up == null) {
            // Not a loss: the provider still holds the message and offers it again.
            LogUtil.w(TAG, what + ": stored " + ticket.id + " but no bound provider runs as its "
                    + "caller — not confirmed; the provider offers it again");
            return;
        }
        up.ackInboundMessages(ticket.subId, ticket.id);
        LogUtil.i(TAG, what + ": confirmed " + ticket.id + " to the provider after the store");
    }

    /** The uid {@code pkg} runs as, or -1. */
    public static int uidOf(final android.content.Context context, final String pkg) {
        try {
            return context.getPackageManager().getPackageUid(pkg, 0);
        } catch (final android.content.pm.PackageManager.NameNotFoundException e) {
            LogUtil.w(TAG, "RcsInboundConfirmation: provider package not found; confirmations "
                    + "to it are not sent");
            return -1;
        }
    }

    @Nullable
    private static Ticket read(final Bundle params) {
        final String id = params.getString(KEY_ID);
        if (TextUtils.isEmpty(id)) return null;
        return new Ticket(params.getInt(KEY_SUB_ID), id, params.getInt(KEY_UID, -1));
    }

    @Nullable
    private static Upstream upstreamFor(final int uid) {
        final ProviderTransport legacy = ProviderTransport.peekInstance();
        if (legacy != null && legacy.deliversFromUid(uid)) return legacy;
        final ProviderRegistry registry = ProviderRegistry.peek();
        if (registry == null) return null;
        for (final RcsTransport t : registry.getTransports()) {
            if (t instanceof Upstream && ((Upstream) t).deliversFromUid(uid)) {
                return (Upstream) t;
            }
        }
        return null;
    }
}

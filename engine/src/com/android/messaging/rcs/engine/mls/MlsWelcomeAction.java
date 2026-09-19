/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * What an inbound or outbound Welcome means, as the engine tells the host. Wire values match other
 * clients' numbering so log lines quoting the raw value read the same; zero is {@link #UNKNOWN}
 * because an absent proto3 field decodes to it. See docs/mls/group-lifecycle.md.
 */
public enum MlsWelcomeAction {

    /** The engine named no action, or the field was absent. Not joinable. */
    UNKNOWN(0, false),

    /** A group that did not exist before. The ordinary create. */
    NEW_GROUP(1, true),

    /** A new era of a group we are in: same RCS group id, new MLS group. */
    NEW_ERA_EXISTING_GROUP(2, true),

    /**
     * Members are being added to a group we already hold. Not a join: handling it as one would
     * discard our leaf and message keys. Routes to {@code addMembers}.
     */
    NEW_MEMBERSHIP_EXISTING_GROUP(3, false),

    /** The membership of a group we are in was rebuilt at the same era and re-Welcomed. */
    REFRESH_MEMBERSHIP_EXISTING_GROUP(4, true);

    public final int wire;
    private final boolean mAccepted;

    MlsWelcomeAction(final int wire, final boolean accepted) {
        this.wire = wire;
        mAccepted = accepted;
    }

    /** Whether a Welcome carrying this action may be processed as a join. */
    public boolean acceptedAsJoin() { return mAccepted; }

    /** Whether this action means "commit an addMembers", not "join". */
    public boolean routesToAddMembers() { return this == NEW_MEMBERSHIP_EXISTING_GROUP; }

    /** @return the action for a wire value, or {@code null} when it names none */
    public static MlsWelcomeAction fromWire(final int wire) {
        for (final MlsWelcomeAction a : values()) if (a.wire == wire) return a;
        return null;
    }

    /**
     * Reject an action outside the accept-set, including a value this build cannot name, quoting
     * the raw wire value. The message matches other clients' text verbatim.
     *
     * @throws IllegalStateException when the action may not be processed as a join
     */
    public static void requireJoinable(final int rawWire) {
        final MlsWelcomeAction action = fromWire(rawWire);
        if (action == null || !action.acceptedAsJoin()) {
            throw new IllegalStateException(
                    "The commit is not for advancing era, welcomeAction=" + rawWire + ".");
        }
    }

    /** Overload for a typed action; {@code null} is reported as {@link #UNKNOWN}. */
    public static void requireJoinable(final MlsWelcomeAction action) {
        requireJoinable(action == null ? UNKNOWN.wire : action.wire);
    }

    /**
     * The log line for {@link #NEW_MEMBERSHIP_EXISTING_GROUP}: a routing instruction, not a
     * failure.
     */
    public String addMembersLine() {
        return "An addMembers commit needs to be sent. Welcome action: " + wire;
    }
}

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
 * What a Welcome MEANS — the typed engine→host discriminator (rework item 9.6, §9.2).
 *
 * <h2>Why a type rather than the call site</h2>
 *
 * <p>Today the host tells a create from an era advance by <em>which method it is standing in</em>.
 * That works for Welcomes we produce and not at all for Welcomes we receive, where the same bytes
 * can mean four different things and the only difference is what the sender intended. Without a
 * discriminator there is nowhere to enforce an accept-set and nowhere to route the one arm that is
 * not a join at all.
 *
 * <p>The consequences are concrete. A {@link #NEW_MEMBERSHIP_EXISTING_GROUP} Welcome handled as a
 * join re-creates local state for a group we are already in — dropping our leaf and every message
 * key with it — when what it actually asks for is an {@code addMembers} commit. And a Welcome whose
 * action is not in the accept-set is currently indistinguishable from one that is.
 *
 * <h2>The wire numbering is Google Messages', and it is load-bearing</h2>
 *
 * <p>{@code 0 UNKNOWN, 1 NEW_GROUP, 2 NEW_ERA_EXISTING_GROUP, 3 NEW_MEMBERSHIP_EXISTING_GROUP,
 * 4 REFRESH_MEMBERSHIP_EXISTING_GROUP} — design doc §9.5, five values including {@code UNKNOWN}.
 *
 * <p>This enum first shipped with four values numbered {@code 0..3}, which put {@code NEW_GROUP} on
 * zero and swapped the last two. Two things were wrong with that. The swap is a straight misread. The
 * zero is worse: {@code welcomeAction} is a proto3 field, so an absent or defaulted one arrives as
 * zero, and an arm that means "create a group" sitting on zero turns "the engine named no action"
 * into a silent, accepted join. Google Messages reserves zero for {@code UNKNOWN} for exactly this reason,
 * and {@code UNKNOWN} is not joinable.
 *
 * <p>The action is NOT marshalled onto the wire — it is a pure engine→host instruction — so these
 * numbers are not an interop constraint. They are a <em>diff</em> constraint: Google Messages quotes the raw
 * value in {@code "The commit is not for advancing era, welcomeAction=…"} and in
 * {@code "An addMembers commit needs to be sent. Welcome action: %d"}, and a trace compared against
 * Google Messages is only readable if our numbers mean what its numbers mean.
 */
public enum MlsWelcomeAction {

    /**
     * The engine named no action — wire value {@code 0}, which is also the proto3 default.
     *
     * <p>It is deliberately FIRST and deliberately not joinable. An absent or defaulted field
     * deserialises to zero, so whichever arm holds zero is the one a missing value silently becomes;
     * putting a real join there would make "the engine said nothing" indistinguishable from "the
     * engine said create a group".
     */
    UNKNOWN(0, false),

    /** A group that did not exist before. The ordinary create. */
    NEW_GROUP(1, true),

    /**
     * A new ERA of a group we are already in — same RCS group id, new MLS group.
     *
     * <p>The era-advance re-join. Accepting it is what lets a peer's advance carry us along; the
     * local state it replaces is deliberately discarded, because the new era shares no keys with the
     * old one.
     */
    NEW_ERA_EXISTING_GROUP(2, true),

    /**
     * Members are being ADDED to a group that already exists and that we are already in.
     *
     * <p><b>Not a join, and this is the arm that does damage if mistaken for one.</b> Treating it as
     * a Welcome re-creates local state for a group we already hold, discarding our leaf and every
     * message key. It routes to {@code addMembers} instead.
     */
    NEW_MEMBERSHIP_EXISTING_GROUP(3, false),

    /**
     * The membership of a group we are in has been REFRESHED — same era, re-Welcomed.
     *
     * <p>Accepted for the same reason as an era advance: the sender rebuilt the group state and we
     * are being handed the result.
     */
    REFRESH_MEMBERSHIP_EXISTING_GROUP(4, true);

    public final int wire;
    private final boolean mAccepted;

    MlsWelcomeAction(final int wire, final boolean accepted) {
        this.wire = wire;
        mAccepted = accepted;
    }

    /**
     * Whether a Welcome carrying this action may be processed as a JOIN.
     *
     * <p>The accept-set is {@code {NEW_GROUP, NEW_ERA_EXISTING_GROUP,
     * REFRESH_MEMBERSHIP_EXISTING_GROUP}} — everything that genuinely hands us a group to adopt.
     */
    public boolean acceptedAsJoin() { return mAccepted; }

    /** Whether this action means "commit an addMembers", not "join". */
    public boolean routesToAddMembers() { return this == NEW_MEMBERSHIP_EXISTING_GROUP; }

    /**
     * @return the action for a wire value, or {@code null} when it names none
     */
    public static MlsWelcomeAction fromWire(final int wire) {
        for (final MlsWelcomeAction a : values()) if (a.wire == wire) return a;
        return null;
    }

    /**
     * Reject an action that is not in the accept-set, quoting the RAW wire value.
     *
     * <p>Unnamed values are rejected too, and deliberately: a value this build cannot name is one it
     * does not understand, and guessing that an unrecognised action is safe to join is how a future
     * arm gets silently handled as a create. The raw number is what goes in the message precisely
     * because the interesting case is the one we have no name for.
     *
     * <p>The message is Google Messages', <b>verbatim</b>, so a trace diff matches it as a literal — the
     * reasoning above lives in this javadoc rather than in the string for that reason.
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

    /**
     * Overload for a call site that already holds a typed action.
     *
     * <p>{@code null} is treated as {@link #UNKNOWN} — wire {@code 0} — because that is what an
     * absent field actually deserialises to; reporting it as anything else would misname it.
     */
    public static void requireJoinable(final MlsWelcomeAction action) {
        requireJoinable(action == null ? UNKNOWN.wire : action.wire);
    }

    /**
     * Google Messages' verbatim line for the arm that is not a join at all (§11.3).
     *
     * <p>This is a ROUTING instruction, not a failure: the commit still needs to be sent, as an
     * {@code addMembers} rather than as a create.
     */
    public String addMembersLine() {
        return "An addMembers commit needs to be sent. Welcome action: " + wire;
    }
}

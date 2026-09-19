/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Group-creation output: the RFC 9420 artifacts a create or add request carries, plus the group id
 * the engine chose, which the transport keys local storage by.
 */
public final class MlsGroupArtifacts {
    public final byte[] welcome;
    public final byte[] commit;
    public final byte[] groupInfo;
    public final byte[] tag;       // 32-byte epoch_authenticator of the post-Add epoch
    public final byte[] groupId;
    /** Serialized ratchet tree of the post-Add epoch, or null if the engine exported none. */
    public final byte[] ratchetTree;

    /**
     * The era the engine built this at (GroupContext extension {@code 0xF001} in
     * {@link #groupInfo}), or {@code -1} from an era-taking entry point. It is an output only and
     * never echoes a caller-supplied era, so an unmigrated call site stays distinguishable.
     */
    public final long era;

    /**
     * What the engine decided this operation is, or null from an era-taking entry point.
     * {@link MlsWelcomeAction#NEW_MEMBERSHIP_EXISTING_GROUP} routes the bundle to the add request;
     * the join-accepting actions mean a create.
     */
    public final MlsWelcomeAction welcomeAction;

    /**
     * The MSISDNs the commit admits, for {@link MlsWelcomeAction#NEW_MEMBERSHIP_EXISTING_GROUP}
     * only; empty otherwise, never null. The RCS half of an add must name exactly these, or the
     * server answers {@code mismatched-rcs-group-state}.
     */
    public final java.util.List<String> admittedMembers;

    public MlsGroupArtifacts(byte[] welcome, byte[] commit, byte[] groupInfo, byte[] tag,
            byte[] groupId) {
        this(welcome, commit, groupInfo, tag, groupId, null);
    }

    public MlsGroupArtifacts(byte[] welcome, byte[] commit, byte[] groupInfo, byte[] tag,
            byte[] groupId, byte[] ratchetTree) {
        this(welcome, commit, groupInfo, tag, groupId, ratchetTree, -1L, null, null);
    }

    public MlsGroupArtifacts(byte[] welcome, byte[] commit, byte[] groupInfo, byte[] tag,
            byte[] groupId, byte[] ratchetTree, long era, MlsWelcomeAction welcomeAction,
            java.util.List<String> admittedMembers) {
        this.welcome = welcome; this.commit = commit; this.groupInfo = groupInfo;
        this.tag = tag; this.groupId = groupId; this.ratchetTree = ratchetTree;
        this.era = era; this.welcomeAction = welcomeAction;
        this.admittedMembers = (admittedMembers == null)
                ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(
                        new java.util.ArrayList<String>(admittedMembers));
    }
}

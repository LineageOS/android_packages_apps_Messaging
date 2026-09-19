/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Why an advance is happening. Key packages are supplied if and only if the purpose is
 * {@link #ERA_ADVANCEMENT}. See docs/mls/group-lifecycle.md.
 */
public enum MlsAdvancePurpose {

    /** A new group under the same RCS group id, every member re-Welcomed. */
    ERA_ADVANCEMENT(true),

    /** A commit inside the existing group; no membership change. */
    EPOCH_ADVANCEMENT(false),

    /** An era advance into a downgraded era, which nobody needs to join. */
    PHOENIX_MODE(false);

    private final boolean mRequiresKeyPackages;

    MlsAdvancePurpose(final boolean requiresKeyPackages) {
        mRequiresKeyPackages = requiresKeyPackages;
    }

    public boolean requiresKeyPackages() { return mRequiresKeyPackages; }

    /** @throws IllegalStateException if an era advance has none, or any other purpose has some */
    public void assertKeyPackages(final int count) {
        if (mRequiresKeyPackages && count <= 0) {
            throw new IllegalStateException(name()
                    + " requires at least one KeyPackage: it builds a "
                    + "NEW group and re-Welcomes every member, so advancing with none leaves an era "
                    + "containing only us while every other member still sees the conversation.");
        }
        if (!mRequiresKeyPackages && count != 0) {
            // Same wording as other clients, so logs line up.
            throw new IllegalStateException("Required 0 keypackages for " + name() + ", got "
                    + count
                    + ". This purpose does not change membership, and adding members as a side "
                    + "effect of recovery is a change nobody asked for.");
        }
    }
}

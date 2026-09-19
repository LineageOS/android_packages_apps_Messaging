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
 * Why an advance is happening, and the key-package rule that follows from it — <b>INV-KP</b>
 * (rework item 9.6, §9.2).
 *
 * <h2>The rule is an IF AND ONLY IF, and both directions bite</h2>
 *
 * <p>Key packages are supplied <b>iff</b> the purpose is {@link #ERA_ADVANCEMENT}. Stated as a
 * biconditional because the two failures are different and each is reachable on its own:
 *
 * <ul>
 *   <li><b>Era purpose with NO key packages.</b> An era advance builds a NEW MLS group and
 *       re-Welcomes every member from a fresh key package each. With none, the new era has no
 *       members but us — the advance "succeeds" and everyone else is silently locked out of a
 *       conversation they can still see.</li>
 *   <li><b>Non-era purpose WITH key packages.</b> An epoch advance is a commit inside the existing
 *       group; handing it key packages asks it to add members as a side effect of recovery, which is
 *       a membership change nobody requested. Google Messages throws {@code Required 0 keypackages} rather
 *       than ignoring them, and throwing is the right shape: quietly dropping them would make a
 *       caller that got the purpose wrong look like it worked.</li>
 * </ul>
 *
 * <p>Our preserving modes actively violated the first direction — they advanced an era with zero key
 * packages — which is why this is an assertion rather than documentation.
 */
public enum MlsAdvancePurpose {

    /**
     * A new era: a NEW group under the same RCS group id, every member re-Welcomed.
     *
     * <p>The only purpose that takes key packages, and it requires at least one.
     */
    ERA_ADVANCEMENT(true),

    /**
     * A new epoch inside the existing group — an ordinary commit. No membership change, so no key
     * packages.
     */
    EPOCH_ADVANCEMENT(false),

    /**
     * Era advancement used as a DOWNGRADE mechanism (§9.2's phoenix mode).
     *
     * <p>Takes no key packages despite advancing an era, and that is the exception the flag exists
     * for: the point of a phoenix advance is to leave the group without re-establishing it, so
     * claiming a key package per member would be claiming packages for a group nobody will join.
     */
    PHOENIX_MODE(false);

    private final boolean mRequiresKeyPackages;

    MlsAdvancePurpose(final boolean requiresKeyPackages) {
        mRequiresKeyPackages = requiresKeyPackages;
    }

    /** Whether this purpose supplies key packages. The left-hand side of INV-KP. */
    public boolean requiresKeyPackages() { return mRequiresKeyPackages; }

    /**
     * Enforce INV-KP in both directions.
     *
     * <p>Throws rather than returning a verdict. A caller that has the purpose and the count wrong
     * has a bug in the step it is about to take, and the cheapest place to find that is here — the
     * alternative is a group that advances into an empty membership and reports success.
     *
     * @param count how many key packages the caller is about to supply
     * @throws IllegalStateException on either direction of the violation
     */
    public void assertKeyPackages(final int count) {
        if (mRequiresKeyPackages && count <= 0) {
            throw new IllegalStateException(name() + " requires at least one KeyPackage: it builds a "
                    + "NEW group and re-Welcomes every member, so advancing with none leaves an era "
                    + "containing only us while every other member still sees the conversation.");
        }
        if (!mRequiresKeyPackages && count != 0) {
            // Google Messages' wording, because a log diff should line up.
            throw new IllegalStateException("Required 0 keypackages for " + name() + ", got " + count
                    + ". This purpose does not change membership, and adding members as a side "
                    + "effect of recovery is a change nobody asked for.");
        }
    }
}

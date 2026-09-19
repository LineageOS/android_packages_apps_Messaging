/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The ordinal layout of the provider contract's interfaces, and the check that decides whether two
 * builds of it can talk. Transaction codes are positional, so a skewed pair mis-dispatches
 * silently; layouts are derived from each side's generated stub ({@code RcsContractProbe}) and
 * every undeterminable case is refused. Pure JDK so it can be host-tested.
 * See docs/rcs/provider-contract.md.
 */
public final class RcsContractLayout {

    /**
     * {@code IBinder.FIRST_CALL_TRANSACTION}, duplicated to keep this class JDK-only;
     * {@code RcsContractProbe} checks the two agree.
     */
    public static final int FIRST_ORDINAL = 1;

    /**
     * The first method of {@link IRcsProvider}. A derived layout that does not start here is
     * refused as a failed derivation.
     */
    public static final String ANCHOR_METHOD = "getContractVersion";

    /** The same anchor for {@link IRcsProviderCallback}, which the provider dials. */
    public static final String CALLBACK_ANCHOR_METHOD = "onIncomingMessage";

    private RcsContractLayout() {}

    /** The outcome of comparing two layouts. Carries the sentence to log. */
    public static final class Verdict {
        /** True only when every ordinal the caller knows resolves to the same method remotely. */
        public final boolean compatible;
        /** Human-readable, naming both sides. Never null. */
        public final String reason;

        Verdict(final boolean compatible, final String reason) {
            this.compatible = compatible;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return (compatible ? "COMPATIBLE: " : "SKEW: ") + reason;
        }
    }

    /**
     * Fingerprint of a layout for logs: SHA-256 over {@code "<ordinal>:<name>\n"} per method, so
     * any insertion, removal or reorder changes it.
     *
     * @return 12 lowercase hex chars, or {@code "none"} for a null/empty layout.
     */
    public static String digest(final String[] names) {
        if (names == null || names.length == 0) {
            return "none";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            sb.append(i + FIRST_ORDINAL).append(':').append(names[i]).append('\n');
        }
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] h = md.digest(sb.toString().getBytes("UTF-8"));
            final StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(Character.forDigit((h[i] >> 4) & 0xf, 16));
                hex.append(Character.forDigit(h[i] & 0xf, 16));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // Unreachable; a value no real digest can match keeps a digest comparison failing.
            return "undigestible";
        }
    }

    /**
     * Whether a caller holding {@code local} may dial a binder implementing {@code remote}: every
     * ordinal the caller knows must name the same method on the callee. Extra trailing methods on
     * the callee are fine. Compare each interface in its own direction.
     *
     * @param iface         the interface being compared, for the message
     * @param anchor        the method that must sit at {@link #FIRST_ORDINAL} on both sides
     * @param callerLabel   the side that dials ("app" / "provider")
     * @param local         the caller's layout, index 0 == ordinal {@link #FIRST_ORDINAL}
     * @param localVersion  the caller's contract version, for the message only
     * @param calleeLabel   the side that dispatches
     * @param remote        the callee's layout
     * @param remoteVersion the callee's contract version, for the message only
     */
    public static Verdict compare(final String iface, final String anchor,
            final String callerLabel, final String[] local, final int localVersion,
            final String calleeLabel, final String[] remote, final int remoteVersion) {
        final String tag = iface + ": " + callerLabel + " v" + localVersion + " ("
                + count(local) + " methods, " + digest(local) + ") vs " + calleeLabel + " v"
                + remoteVersion + " (" + count(remote) + " methods, " + digest(remote) + ")";

        if (local == null || local.length == 0) {
            return new Verdict(false, "could not derive the " + callerLabel + " transaction layout "
                    + "— refusing rather than dialling ordinals nobody can verify; " + tag);
        }
        if (remote == null || remote.length == 0) {
            return new Verdict(false, "the " + calleeLabel + " did not report a transaction layout "
                    + "(too old to answer the contract probe, or the probe failed) — refusing; "
                    + tag);
        }
        if (!anchor.equals(local[0])) {
            return new Verdict(false, "the " + callerLabel + " layout does not start at " + anchor
                    + " (got '" + local[0] + "') — the derivation is wrong, refusing; " + tag);
        }
        if (!anchor.equals(remote[0])) {
            return new Verdict(false, "the " + calleeLabel + " layout does not start at " + anchor
                    + " (got '" + remote[0] + "') — refusing; " + tag);
        }
        // Divergence before length: it names the insertion. Length still catches pure truncation.
        final int common = Math.min(local.length, remote.length);
        for (int i = 0; i < common; i++) {
            if (!local[i].equals(remote[i])) {
                return new Verdict(false, "ORDINAL SKEW at transaction " + (i + FIRST_ORDINAL)
                        + ": the " + callerLabel + " calls '" + local[i] + "' where the "
                        + calleeLabel + " has '" + remote[i] + "'. Binder ordinals are positional, "
                        + "so this MIS-DISPATCHES SILENTLY — the call succeeds against the wrong "
                        + "method. Examples of what those calls would land on: "
                        + examples(local, remote, i)
                        + ". Rebuild and install BOTH sides from one tree; " + tag);
            }
        }
        if (remote.length < local.length) {
            return new Verdict(false, "the " + calleeLabel + " implements FEWER methods than the "
                    + callerLabel + " calls (" + remote.length + " < " + local.length + "), so the "
                    + "highest ordinals dispatch to nothing at all — rebuild and install BOTH "
                    + "sides from one tree; " + tag);
        }
        return new Verdict(true, tag);
    }

    /** Up to three "we dial X, it runs Y" pairs from the first divergence. */
    private static String examples(final String[] local, final String[] remote, final int from) {
        final StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = from; i < local.length && shown < 3; i++) {
            final String theirs = i < remote.length ? remote[i] : "<nothing>";
            if (local[i].equals(theirs)) {
                continue;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append('#').append(i + FIRST_ORDINAL).append(' ').append(local[i])
                    .append(" -> ").append(theirs);
            shown++;
        }
        return sb.length() == 0 ? "<none>" : sb.toString();
    }

    private static int count(final String[] a) {
        return a == null ? 0 : a.length;
    }
}

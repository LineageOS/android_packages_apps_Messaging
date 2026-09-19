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
package org.lineageos.rcs.provider;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The provider/app AIDL contract's ORDINAL LAYOUT, and the comparison that decides whether two
 * builds of it can safely talk.
 *
 * <p><b>Why this exists.</b> Binder transaction codes are POSITIONAL: aidl
 * numbers each method {@code FIRST_CALL_TRANSACTION + n} in DECLARATION ORDER. So inserting a
 * method in the middle of {@link IRcsProvider} renumbers every method after it, and a caller built
 * against the old declaration does not fail — it <em>lands on a different method</em>. Measured on
 * a device running a newer provider against an older app: two log lines in the SAME millisecond,
 * one from the app dialling an ordinal and one from the provider refusing an entirely different
 * method on the argument it was handed. A key-upload call had arrived at a record-deletion method,
 * because a contract revision inserted a new method mid-interface and pushed everything after it
 * down by one. The only reason the upload did not delete a conversation record is that the method
 * it landed on refuses an argument that does not parse as a group id — a guard written for an
 * unrelated reason, and the entire margin.
 *
 * <p><b>Why the contract version int was not enough, twice over.</b> {@link
 * IRcsProvider#getContractVersion()} already existed and is already exchanged on bind. It did not
 * catch this because:
 * <ol>
 *   <li>Both constants were STALE. At the time of the measurement the aidl was at v60, the provider
 *       reported 59, and the app's {@code ProviderTransport.CONTRACT_VERSION} was still <b>24</b> —
 *       36 bumps behind. The check was {@code provider &lt; app}, i.e. {@code 59 &lt; 24}, which is
 *       false, so it passed. A hand-maintained number fails open the moment somebody forgets it,
 *       and both sides had forgotten it.</li>
 *   <li>The check's PREMISE was false. Its comment read "additive bumps ... a provider whose
 *       contract is &gt;= ours is a superset". That is only true if methods are APPENDED, and the
 *       two revisions either side of the incident both INSERTED a method mid-interface. So even a
 *       perfectly maintained {@code &gt;=} check would have passed this skew.</li>
 * </ol>
 *
 * <p>So the number is kept as a LABEL — useful in a log line — and the authoritative check is the
 * layout itself, DERIVED at runtime from each side's own generated stub (see {@code
 * RcsContractProbe}) rather than spelled out by hand. A derived check cannot go stale, and a new
 * method is covered the moment it is declared with nobody updating anything.
 *
 * <p><b>Fail closed.</b> Every "we could not determine this" path in {@link #compare} returns
 * INCOMPATIBLE, not compatible. A guard that cannot see the skew must refuse.
 *
 * <p>Pure JDK on purpose (no {@code android.*}): the decision is the part worth testing, and it is
 * tested off-device against layouts recovered from real builds — see {@code RcsContractLayoutTest}.
 */
public final class RcsContractLayout {

    /**
     * Mirrors {@code android.os.IBinder.FIRST_CALL_TRANSACTION}. Duplicated as a plain int so this
     * class stays JDK-only; {@code RcsContractProbe} asserts the two agree on device.
     */
    public static final int FIRST_ORDINAL = 1;

    /**
     * The method aidl numbers FIRST, and which is therefore the one ordinal that cannot move as
     * long as nobody declares something above it in {@link IRcsProvider}. Used as an anchor: if a
     * derived layout does not start here, the derivation is wrong (R8 renamed things, the interface
     * was reordered) and we refuse rather than compare garbage.
     */
    public static final String ANCHOR_METHOD = "getContractVersion";

    /**
     * The same anchor for {@link IRcsProviderCallback}, whose ordinals are numbered independently
     * and which carries the identical hazard in the OTHER direction: there the PROVIDER dials and
     * the APP dispatches, so a skew mis-delivers inbound work — a message, a receipt, a group
     * event — onto the wrong handler. Not hypothetical: that interface has taken FOUR
     * mid-interface insertions ({@code 33380aba}, {@code 4a6c9040}, {@code d966d46f},
     * {@code 82a2edb9}), each of which shifted everything from {@code onGroupTyping} down.
     */
    public static final String CALLBACK_ANCHOR_METHOD = "onIncomingMessage";

    private RcsContractLayout() {}

    /** The outcome of comparing two layouts. Carries the sentence to log. */
    public static final class Verdict {
        /** True only when every ordinal the LOCAL side knows resolves to the same method remotely. */
        public final boolean compatible;
        /** Human-readable, and always names BOTH sides. Never null. */
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
     * Short stable fingerprint of a layout, for logging and for the install-time pairing check.
     * Covers the names AND their order, so any insertion, removal or reorder changes it.
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
            // Neither is reachable on a JDK or on Android. Fail CLOSED: return a value that cannot
            // match any real digest, so a caller comparing digests refuses rather than passes.
            return "undigestible";
        }
    }

    /**
     * Decide whether a caller holding {@code local} may safely make calls to a binder implementing
     * {@code remote}.
     *
     * <p>The property checked is exactly the one that was violated: <b>every ordinal the caller
     * knows must resolve to the same method name on the callee.</b> Trailing methods the callee has
     * and the caller does not are fine — that is what a genuinely ADDITIVE bump looks like, and the
     * caller never dials them. Anything else refuses.
     *
     * <p><b>Direction matters, and it is not always app-to-provider.</b> {@code IRcsProvider} is
     * dialled BY the app; {@code IRcsProviderCallback} is dialled BY the provider. The caller is
     * whichever side holds the ordinals being sent, so both interfaces must be compared, each in
     * its own direction, before a pairing can be called safe.
     *
     * @param iface         the interface being compared, for the message
     * @param anchor        the method that must sit at {@link #FIRST_ORDINAL} on both sides
     * @param callerLabel   which side DIALS ("app" / "provider")
     * @param local         the CALLER's layout, index 0 == ordinal {@link #FIRST_ORDINAL}
     * @param localVersion  the caller's declared contract version (a label, for the message)
     * @param calleeLabel   which side DISPATCHES
     * @param remote        the CALLEE's layout
     * @param remoteVersion the callee's declared contract version (a label, for the message)
     */
    public static Verdict compare(final String iface, final String anchor,
            final String callerLabel, final String[] local, final int localVersion,
            final String calleeLabel, final String[] remote, final int remoteVersion) {
        final String tag = iface + ": " + callerLabel + " v" + localVersion + " ("
                + count(local) + " methods, " + digest(local) + ") vs " + calleeLabel + " v"
                + remoteVersion + " (" + count(remote) + " methods, " + digest(remote) + ")";

        // --- Fail closed: anything we cannot establish is a refusal, not a pass. ---
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
        // Divergence BEFORE length, deliberately. A callee that is both shorter and renumbered
        // trips both conditions, and "ORDINAL SKEW at transaction 14: the provider calls X where
        // the app has Y" is the sentence somebody can act on; "implements fewer methods" is true
        // but sends the reader counting instead of looking at the insertion. The length check below
        // still catches the pure-truncation case, where the common prefix agrees.
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

    /**
     * Up to three concrete "we dial X, it runs Y" pairs starting at the first divergence.
     *
     * <p>Worth the extra text: "contract mismatch" tells a reader nothing they can act on, whereas
     * {@code uploadKeyPackages -> mlsForgetGroupConversation} is the line that makes somebody go
     * and look at which side inserted a method. It is also the shape the incident actually took.
     */
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

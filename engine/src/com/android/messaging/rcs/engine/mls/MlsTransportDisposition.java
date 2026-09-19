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
 * Whether a transport failure is worth trying again — §17.3's classifier, and the resolution of
 * rework item 5.7's DECIDE.
 *
 * <h2>The decision, written down because the item requires it to be</h2>
 *
 * <p>Google Messages does <b>not</b> pre-check connectivity. A typed exception propagates out of the
 * peek/retry call path and is caught separately at each of the two call sites, which is why two
 * distinct log lines exist for what looks like one condition. §17.3's {@code isLive()} is the doc
 * authors' recommendation, explicitly NOT a real method.
 *
 * <p>We cannot copy that shape end to end, and the reason is structural rather than preference:
 * bind-stream liveness is produced in the <b>provider</b> and the drain runs in <b>Messaging</b>,
 * so the signal crosses Binder — where a typed Java exception does not survive except as
 * {@code ServiceSpecificException}.
 *
 * <p><b>So the split is: value across Binder, exception host-side.</b> The provider keeps returning
 * a {@code VERDICT_*} on {@code RcsMlsControlResult} (it already does), and the first host-side
 * wrapper classifies it here and throws. That preserves the two properties the item actually cares
 * about — a mid-call loss is reported (a pre-check cannot cover the mid-call case at all, which is
 * the case that actually happens), and each call site catches separately so its log line stays
 * distinguishable, without which the §20.3 logcat diff stops working.
 *
 * <p>What we were missing was never the signal. {@code VERDICT_TRANSPORT_FAILED} has existed all
 * along as a flat value with <b>no retryability semantics and nothing acting on it</b>. This is that
 * missing half.
 *
 * <p>Pairs with {@link MlsResultStatus}: {@link #RETRYABLE} is exactly what
 * {@link MlsResultStatus#FAIL_RETRY} was built to carry.
 */
public enum MlsTransportDisposition {

    /** No failure. */
    OK,

    /**
     * Transient — the bind stream is down or we are not registered right now. Try again later.
     *
     * <p>Note what this is NOT: IP reachability. Google Messages' source is Tachyon <b>bind-stream</b>
     * liveness, and we own that stream, so the same signal comes from the same place. A device with
     * a working data connection and a dead bind stream is exactly the case a ping-style check calls
     * healthy and MLS cannot use.
     */
    RETRYABLE,

    /** The server refused on the merits. Retrying sends the same thing and gets the same answer. */
    PERMANENT,

    /**
     * The group is not there.
     *
     * <p>Separate from {@link #PERMANENT} because the remedies differ: a refusal means stop, an
     * absence means the group must be re-established before anything else can succeed.
     */
    NOT_FOUND;

    /** Whether a drive loop may re-drive on this. */
    public boolean mayRetry() { return this == RETRYABLE; }

    /** The {@link MlsResultStatus} this disposition maps onto. */
    public MlsResultStatus toResultStatus() {
        switch (this) {
            case OK:        return MlsResultStatus.SUCCESS;
            case RETRYABLE: return MlsResultStatus.FAIL_RETRY;
            case NOT_FOUND:
            case PERMANENT:
            default:        return MlsResultStatus.FAIL_NO_RETRY;
        }
    }

    // The provider's VERDICT_* wire values (RcsMlsControlResult). Duplicated as plain ints rather
    // than imported because this module is Android-free and must not depend on the AIDL parcelable;
    // the values are part of a versioned cross-process contract, so they do not drift silently.
    public static final int VERDICT_OK = 0;
    public static final int VERDICT_ERA_GAP = 1;
    public static final int VERDICT_EXTERNAL_COMMIT_REFUSED = 2;
    public static final int VERDICT_GROUP_ID_CHANGED = 3;
    public static final int VERDICT_NOT_REGISTERED = 4;
    public static final int VERDICT_TRANSPORT_FAILED = 5;
    public static final int VERDICT_REJECTED = 6;
    /** The server does not count this line as a member of the group (TachyonError 36). */
    public static final int VERDICT_NOT_IN_GROUP = 7;

    /**
     * Classify a provider verdict.
     *
     * <p>The two divergence verdicts ({@code ERA_GAP}, {@code GROUP_ID_CHANGED}) are <b>not</b>
     * transport failures and are deliberately {@link #OK} here: they mean the call reached the
     * server and the server disagreed about where we are, which is recovery's business, not the
     * retry queue's. Classifying them as retryable would have the queue re-sending a control the
     * server has already told us is at the wrong era.
     *
     * <p>An unrecognised verdict is {@link #RETRYABLE} — the safe direction, matching
     * {@link MlsResultStatus#fromWire}'s reasoning: one wasted retry beats treating a new failure
     * mode as permanent and abandoning a conversation.
     */
    public static MlsTransportDisposition ofVerdict(final int verdict) {
        switch (verdict) {
            case VERDICT_OK:
            case VERDICT_ERA_GAP:
            case VERDICT_GROUP_ID_CHANGED:
                return OK;
            case VERDICT_NOT_REGISTERED:
            case VERDICT_TRANSPORT_FAILED:
                return RETRYABLE;
            case VERDICT_REJECTED:
            case VERDICT_EXTERNAL_COMMIT_REFUSED:
                return PERMANENT;
            // NOT_IN_GROUP IS PERMANENT, AND IT IS THE ONE CASE WHERE `default: RETRYABLE` WAS
            // ACTIVELY WRONG RATHER THAN MERELY UNINFORMED. The default is a
            // fail-safe — an unknown mode is better retried than treated as permanent, because
            // abandoning a live conversation is the worse error. But here we KNOW the reason: the
            // server does not count this line as a member, so the identical request cannot
            // succeed however many times it is sent. Retrying is not caution, it is a loop.
            //
            // "Permanent" is about THIS request, not about the conversation. The repair exists —
            // retire the stale local membership (Google Messages' REMOVE_SELF_FROM_GROUP) — it is simply
            // not a retry, and the app cannot reach for it while this says "try again".
            case VERDICT_NOT_IN_GROUP:
                return PERMANENT;
            default:
                return RETRYABLE;
        }
    }

    /** Whether a verdict means connectivity was lost, i.e. should raise the typed exception. */
    public static boolean isConnectivityLoss(final int verdict) {
        return verdict == VERDICT_NOT_REGISTERED || verdict == VERDICT_TRANSPORT_FAILED;
    }

    /**
     * The server refused <b>where we stood</b>, not <b>what we sent</b>.
     *
     * <p>This is the question {@code MlsCredentialUpdateSeal} actually asks and could not express:
     * "did the server EVALUATE the commit, as opposed to refusing our position or never seeing
     * it?". A verdict in this set says nothing about the artefact we offered, so spending the
     * §9.5.3 once-per-certificate marker on it burns the one attempt that certificate gets on an
     * answer that was never about the certificate.
     *
     * <p><b>Why not just widen {@link #isConnectivityLoss}.</b> That predicate raises
     * {@link ConnectivityLost}, and {@code NOT_IN_GROUP} is not a connectivity condition — the
     * request arrived, the server read it and declined. Widening it would have made the seal
     * correct by making the exception a lie, and the next reader would have inherited a predicate
     * whose name disagreed with its members. Two predicates that overlap on
     * {@code VERDICT_NOT_REGISTERED} is the honest shape: that verdict genuinely is both.
     */
    public static boolean refusedOurPosition(final int verdict) {
        return verdict == VERDICT_NOT_REGISTERED || verdict == VERDICT_NOT_IN_GROUP;
    }

    /**
     * Connectivity was lost during a call — the typed exception Google Messages propagates.
     *
     * <p>Unchecked so it travels out of the drive-loop pass without every intermediate signature
     * declaring it, which is the whole point of using an exception rather than another return value:
     * the intermediate frames have nothing useful to do with it.
     */
    public static final class ConnectivityLost extends RuntimeException {
        /** The provider verdict that produced it. */
        public final int verdict;

        public ConnectivityLost(final String where, final int verdict) {
            super("Connectivity lost during " + where + " (verdict " + verdict + ")");
            this.verdict = verdict;
        }
    }

    /**
     * Throw if {@code verdict} means connectivity was lost.
     *
     * @param where the call site, which MUST be distinct per site — the §20.3 logcat diff relies on
     *              the two lines being distinguishable
     */
    public static void requireLive(final String where, final int verdict) {
        if (isConnectivityLoss(verdict)) {
            throw new ConnectivityLost(where, verdict);
        }
    }
}

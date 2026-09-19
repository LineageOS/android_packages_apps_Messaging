/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Map;
import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.log.LogMask;
/**
 * The RCC.16 §9.5.3 credential Self-Update: noticing that the group still certifies a certificate
 * we have since replaced, and carrying the new one in with an empty Commit. {@link
 * MlsCredentialUpdateSeal} is the policy; this is the flow. See docs/mls/credentials.md.
 */
public final class MlsCredentialUpdate {
    private MlsCredentialUpdate() {}

    /**
     * Carry our current certificate into this group with an empty Commit whose UpdatePath holds the
     * new leaf (RCC.16 §9.5.3). A rekey, not an era advance. Declines a downgraded or left group,
     * an un-evaluable leaf status, a new certificate inside the floor, a peer inside the floor, and
     * a certificate already attempted at this position. Success is judged on the group's leaf.
     *
     * @return true if a Commit was issued and accepted
     */
    public static boolean maybeUpdateGroupCredential(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key, final Group g,
            final String rcsGroupId, final String peerE164, final String cause) {
        // What this invocation took, so the catch can restore it; null means nothing was taken.
        ConvState takenOn = null;
        long takenFor = 0L;
        long markerBefore = 0L;
        MlsAppMessage.Moment refusedAtBefore = null;
        try {
            if (shell.session() == null || g == null || g.groupId == null) return false;
            // Downgraded or left: never committed into.
            if (MlsRecordState.isDowngradedStatus(shell, log, key)) {
                log.i("MlsCredentialUpdate: §9.5.3 credential update (" + cause + ") for "
                        + MlsConversationKey.forLog(key)
                        + " — DECLINED, the conversation is downgraded (INVARIANT ED-1)");
                return false;
            }
            final MlsSelfLeafStatus st = shell.session().selfLeafStatus(g.groupId);
            if (st == null) {
                // "No update needed" and "could not look" must not share the same silence.
                log.w("MlsCredentialUpdate: §9.5.3 credential update (" + cause + ") for "
                        + MlsConversationKey.forLog(key)
                        + " — UN-EVALUABLE: the engine did not report our own leaf status. "
                        + "NOT concluding that the group holds our current certificate.");
                return false;
            }
            if (!st.stale) return false;                 // the ordinary case, silent
            final long now = System.currentTimeMillis() / 1000L;
            final long clientLeft = st.clientRemainingDays(now);
            final long groupLeft = st.groupRemainingDays(now);
            // RCC.16 A.4.1.5 §5: no Self-Update with a new credential inside the floor. MIN_VALUE
            // means its window would not parse, which gets the same answer.
            if (clientLeft == Long.MIN_VALUE
                    || clientLeft < MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS) {
                log.e("MlsCredentialUpdate: §9.5.3 credential update (" + cause + ") for "
                        + MlsConversationKey.forLog(key)
                        + " is NEEDED — the group holds a certificate with " + groupLeft
                        + "d left and we hold one with " + clientLeft + "d — but RCC.16 A.4.1.5 §5 "
                        + "forbids a Self-Update whose NEW credential is inside the "
                        + MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS + "-day floor. The remedy is "
                        + "a fresh KDS mint, not a Commit; issuing one now would be refused for the "
                        + "same reason the stale leaf is.");
                return false;
            }
            // RCC.16 A.4.3.2 §3 exempts only the committer's own leaf, so a peer inside the floor
            // makes this Commit impossible. Our own leaf is excluded here: it is the one being
            // replaced.
            final MlsCredentialFloor.Report roster =
                    MlsServerBundle.rosterFloorReport(cfg, shell.session(), log, g);
            if (roster != null) {
                final java.util.List<String> blocking = new java.util.ArrayList<>();
                for (final java.util.Map.Entry<Integer, MlsCredentialFloor.Standing> e
                        : roster.standings.entrySet()) {
                    if (e.getKey() != null && e.getKey().intValue() == st.leafIndex) continue;
                    if (e.getValue() == MlsCredentialFloor.Standing.INSIDE_FLOOR
                            || e.getValue() == MlsCredentialFloor.Standing.EXPIRED
                            || e.getValue() == MlsCredentialFloor.Standing.NOT_YET_VALID) {
                        blocking.add("leaf=" + e.getKey() + "(" + e.getValue() + ")");
                    }
                }
                if (!blocking.isEmpty()) {
                    // The marker is not taken: the roster can clear without a new certificate.
                    log.e("MlsCredentialUpdate: §9.5.3 credential update (" + cause
                            + ") for " + MlsConversationKey.forLog(key)
                            + " is NEEDED (the group holds " + groupLeft + "d, we "
                            + "hold " + clientLeft + "d) but CANNOT BE SENT: " + blocking
                            + " " + roster.below + " are inside RCC.16's "
                            + MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS
                            + "-day floor. A.4.3.2 §3 "
                            + "exempts only the COMMITTER'S OWN leaf from the expiry check, and "
                            + "Invariant 17 is evaluated on the whole roster: a "
                            + "Self-Update here is refused naming the PEER's credential, not ours. "
                            + "Nothing we can send fixes this group until those members update "
                            + "themselves; NOT burning a Commit and an epoch on it.");
                    return false;
                }
            }
            // One attempt per certificate at one position. The position is read before the Commit,
            // because a refused Commit rolls back and a later read could not tell "not moved" from
            // "moved and came back".
            final MlsAppMessage.Moment positionNow =
                    MlsAppMessage.Moment.from(shell.session().eraEpoch(g.groupId));
            final ConvState cs = shell.conv(key);
            synchronized (cs) {
                if (cs.credentialUpdateAttemptedFor == st.clientNotAfter
                        && MlsCredentialUpdateSeal.stillStands(
                                cs.credentialUpdateRefusedAt, positionNow)) {
                    log.i("MlsCredentialUpdate: §9.5.3 credential update (" + cause
                            + ") for " + MlsConversationKey.forLog(key)
                            + " — already attempted for this certificate (notAfter="
                            + st.clientNotAfter + ") and the group still holds the old one. NOT "
                            + "re-offering the same bytes; re-armed by the next MINT, by a process "
                            + "restart, or"
                            + (cs.credentialUpdateRefusedAt == null
                                    ? " — NOT by moving, because the server judged these BYTES."
                                    : " by this conversation leaving "
                                            + cs.credentialUpdateRefusedAt + ", where the server "
                                            + "refused our POSITION rather than our certificate."));
                    return false;
                }
                // Taken before the Commit, position-scoped, and released below if no server judged
                // the bytes; a null position stores the wide seal.
                markerBefore = cs.credentialUpdateAttemptedFor;
                refusedAtBefore = cs.credentialUpdateRefusedAt;
                cs.credentialUpdateAttemptedFor = st.clientNotAfter;
                cs.credentialUpdateRefusedAt = positionNow;
                takenOn = cs;
                takenFor = st.clientNotAfter;
                // Clear the parked verdict under the same lock: commitAndSend can return before its
                // own clear, and a previous operation's verdict must not be read as this attempt's.
                cs.lastControlVerdict = -1;
                cs.lastControlDetail = null;
            }
            log.w("MlsCredentialUpdate: §9.5.3 CERTIFICATE UPDATE (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — the group's copy of OUR credential expires in " + groupLeft
                    + "d while the one we hold has " + clientLeft + "d (" + st + "). Sending the "
                    + "empty Commit with an UpdatePath carrying the new leaf. Without this the "
                    + "group ages on the ORIGINAL mint's clock however often we re-mint, and the "
                    + "server refuses every membership Commit once the whole roster is inside the "
                    + "floor (Invariant 17).");
            final int era = shell.rekey(rcsGroupId, peerE164);
            final int verdict;
            final String verdictDetail;
            synchronized (cs) {
                verdict = cs.lastControlVerdict;
                verdictDetail = cs.lastControlDetail;
            }
            // No server verdict (never asked, connectivity loss, or a refusal of our position) says
            // nothing about the certificate, so the attempt is released. Checked on every outcome,
            // not only era < 0: a commit withheld by a test fixture returns the post-commit era
            // with the sentinel still set.
            final boolean neverAsked = MlsCredentialUpdateSeal.serverWasNeverAsked(verdict);
            if (neverAsked || MlsTransportDisposition.isConnectivityLoss(verdict)
                    || MlsTransportDisposition.refusedOurPosition(verdict)) {
                synchronized (cs) {
                    if (cs.credentialUpdateAttemptedFor == st.clientNotAfter) {
                        cs.credentialUpdateAttemptedFor = markerBefore;
                        cs.credentialUpdateRefusedAt = refusedAtBefore;
                    }
                }
                log.e("MlsCredentialUpdate: §9.5.3 credential update for "
                        + MlsConversationKey.forLog(key)
                        + (!neverAsked
                                ? " NEVER REACHED A SERVER VERDICT (transport verdict=" + verdict
                                        + ") — the request went out and nobody answered it."
                                : era >= 0
                                        ? " WAS WITHHELD BY THE AHEAD FIXTURE "
                                                + "— the engine applied the Commit locally and "
                                                + "reported era=" + era + ", but applyMlsControl "
                                                + "was never called, so no server has seen this "
                                                + "certificate. Release or disarm the fixture on "
                                                + MlsConversationKey.forLog(key)
                                                + " to let the update out."
                                        : " NEVER REACHED THE SERVER AT ALL (no verdict was "
                                                + "recorded for this attempt) — the commit "
                                                + "artefact was not built, or the AHEAD fixture "
                                                + "refused it at capacity, so applyMlsControl was "
                                                + "never called.")
                        + " The group still holds the superseded certificate and we are NOT "
                        + "spending this certificate's one attempt on it. The next maintenance "
                        + "pass re-offers the same bytes."
                        + (neverAsked ? ""
                                : " If this is UNAUTHENTICATED, the line's provider registration "
                                        + "is refused and NO commit of ours can land until it "
                                        + "recovers."));
                return false;
            }
            if (era < 0) {
                // Which member's credential the server refused decides whether this certificate's
                // attempt is spent. A refusal of our position, or of someone else's credential,
                // keeps a position-scoped seal: catching up, an era advance or a peer's commit
                // re-arms it.
                final boolean judgedOurs =
                        MlsCredentialUpdateSeal.judgedOurOwnCredential(verdictDetail,
                                shell.selfE164());
                if (!MlsCredentialUpdateSeal.isAboutTheBytes(verdict) || !judgedOurs) {
                    log.w("MlsCredentialUpdate: §9.5.3 credential update for "
                            + MlsConversationKey.forLog(key)
                            + " was refused "
                            + (judgedOurs ? "for our POSITION, not for this certificate"
                                    : "ON A CREDENTIAL THAT IS NOT OURS — the server named "
                                            + "someone else's number, or none we could read")
                            + " (verdict=" + verdict + ", "
                            + MlsTransportDisposition.ofVerdict(verdict) + "). The "
                            + "group still holds the superseded certificate. NOT re-offering the "
                            + "same bytes while we are still at " + positionNow + " — but this is "
                            + "sealed to that moment, so catching up, an era advance or a peer's "
                            + "commit re-arms it WITHOUT waiting for a new mint"
                            + (judgedOurs ? "." : ", and a blocking member's OWN update advances "
                                    + "this group's epoch, which is exactly that move."));
                    return false;
                }
                // Refused on the merits: widen the seal so only a new certificate re-arms it.
                synchronized (cs) {
                    if (cs.credentialUpdateAttemptedFor == st.clientNotAfter) {
                        cs.credentialUpdateRefusedAt = null;
                    }
                }
                log.w("MlsCredentialUpdate: §9.5.3 credential update for "
                        + MlsConversationKey.forLog(key)
                        + " was REFUSED by the server ON THE MERITS (verdict=" + verdict
                        + "). The group still holds the superseded certificate; the next MINT "
                        + "re-arms this, and neither a re-offer of the same certificate nor a move "
                        + "to another epoch will.");
                return false;
            }
            // Judged on the group, not on the certificate store.
            final MlsSelfLeafStatus after = shell.session().selfLeafStatus(g.groupId);
            if (after != null && after.stale) {
                log.e("MlsCredentialUpdate: the §9.5.3 Commit for " + MlsConversationKey.forLog(key)
                        + " was ACCEPTED "
                        + "(era=" + era + ") but our leaf STILL certifies the old certificate ("
                        + after + "). That is the original defect exactly — the rekey rotated the "
                        + "HPKE key without carrying the credential.");
                // Performed and ineffective, and it moved the position: widen the seal so it is not
                // re-offered once per epoch.
                synchronized (cs) {
                    if (cs.credentialUpdateAttemptedFor == st.clientNotAfter) {
                        cs.credentialUpdateRefusedAt = null;
                    }
                }
                return false;
            }
            log.i("MlsCredentialUpdate: §9.5.3 credential update for "
                    + MlsConversationKey.forLog(key)
                    + " ACCEPTED → era=" + era + "; the group now holds the certificate we hold"
                    + (after == null ? " (post-check un-evaluable)" : " (" + after + ")"));
            return true;
        } catch (final Throwable t) {
            // A throw carries no verdict about the bytes, so the attempt is restored, not
            // classified: the parked verdict may still be a previous operation's.
            if (takenOn != null) {
                synchronized (takenOn) {
                    if (takenOn.credentialUpdateAttemptedFor == takenFor) {
                        takenOn.credentialUpdateAttemptedFor = markerBefore;
                        takenOn.credentialUpdateRefusedAt = refusedAtBefore;
                    }
                }
            }
            log.w("MlsCredentialUpdate: the §9.5.3 credential update threw for "
                    + MlsConversationKey.forLog(key)
                    + (takenOn == null ? " before it took this certificate's attempt"
                            : " AFTER taking this certificate's attempt, which is NOT spent on it: "
                                    + "a throw carries no verdict about the bytes, so the next "
                                    + "maintenance pass re-offers them"), t);
            return false;
        }
    }

    /**
     * Read the MSISDN out of a time-validation refusal and route by whose credential it names: ours
     * gets a §9.5.3 Self-Update, a peer's or an unreadable one gets a roster floor report. Our
     * identity is never re-minted here. Never throws.
     */
    public static void reportCredentialRefusal(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key, final Group g, final String rcsGroupId,
            final String peerE164, final MlsProviderRpc.ControlResult r, final String what) {
        try {
            if (r == null) return;
            final MlsTimeValidationRefusal ref = MlsTimeValidationRefusal.parse(r.detail);
            if (ref == null) return;
            final long now = System.currentTimeMillis() / 1000L;
            // Log whether the server's validation instant equals device time plus the floor.
            final long skew = ref.validationSkewSecs(now);
            final String skewLine = ref.validatedAtSecs == 0L ? "no validation instant stated"
                    : "the server validated at device-now+" + skew + "s"
                            + (ref.skewMatchesFloor(now, cfg.kpMinRemainingDays)
                                    ? " — EXACTLY the " + cfg.kpMinRemainingDays + "-day floor, "
                                            + "so it enforces remaining lifetime by shifting its "
                                            + "clock"
                                    : " — NOT the " + cfg.kpMinRemainingDays + "-day floor; the "
                                            + "device clock or the rule may have moved");
            final String self = shell.selfE164();
            if (ref.namesUs(self)) {
                log.e("MlsCredentialUpdate: " + what + " on " + MlsConversationKey.forLog(key)
                        + " was refused for "
                        + "OUR OWN credential (" + ref + "); " + skewLine
                        + ". The repair is RCC.16 "
                        + "§9.5.3 — carry our CURRENT certificate into the group — not a re-mint: "
                        + "the server is refusing the group's COPY of our leaf, which re-minting "
                        + "does not touch.");
                MlsCredentialUpdate.maybeUpdateGroupCredential(cfg, shell, log, key, g, rcsGroupId,
                        peerE164, "the server refused our own credential");
                return;
            }
            if (!ref.msisdn.isEmpty()) {
                log.e("MlsCredentialUpdate: " + what + " on " + MlsConversationKey.forLog(key)
                        + " was refused for A "
                        + "PEER'S credential — " + ref + " (ours is " + LogMask.number(self) + "); "
                        + skewLine
                        + ". NOT re-minting our identity: it is not ours that was refused, and "
                        + "nothing we can send fixes theirs. A Remove of that member gets no expiry "
                        + "carve-out (A.4.3.2 exempts only the committer's own leaf) and A.4.2.2 "
                        + "stops the KDS returning their KeyPackage, so ONLY their own Self-Update "
                        + "clears this group.");
                MlsFloorRebuild.logFloorReport(cfg, log, key, MlsServerBundle.rosterFloorReport(cfg,
                        shell.session(), log, g), "after a peer credential refusal");
                return;
            }
            log.e("MlsCredentialUpdate: " + what + " on " + MlsConversationKey.forLog(key)
                    + " was refused on "
                    + "credential VALIDITY but the refusal named no MSISDN we could read (" + ref
                    + "); " + skewLine + ". Reporting the roster instead so the candidates are at "
                    + "least named.");
            MlsFloorRebuild.logFloorReport(cfg, log, key, MlsServerBundle.rosterFloorReport(cfg,
                    shell.session(), log, g), "after an unattributed credential refusal");
        } catch (final Throwable t) {
            log.w("MlsCredentialUpdate: could not read the credential refusal for "
                    + MlsConversationKey.forLog(key),
                    t);
        }
    }

    /**
     * The {@code <expired-credential/>} remedy on the receipt plane, which carries no MSISDN: a
     * stale group copy of our leaf gets a Self-Update, our own certificate inside the floor gets a
     * new identity, anything else is a peer's and is only reported. An unreadable group falls back
     * to {@code onIdentityChanged}.
     */
    public static void expiredCredentialRemedy(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key, final String rcsGroupId,
            final String peerE164, final String messageId) {
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null || shell.session() == null) {
            log.w("MlsCredentialUpdate: the SERVER says a credential expired for "
                    + MlsMessageId.forLog(messageId)
                    + " and we cannot read the group to tell WHOSE — falling back to "
                    + "refreshing our own identity, which is what this arm did unconditionally "
                    + "before this remedy existed.");
            MlsIdentityRefresh.onIdentityChanged(cfg, shell, log,
                    "server reported expired-credential");
            return;
        }
        final MlsSelfLeafStatus st = shell.session().selfLeafStatus(g.groupId);
        final long now = System.currentTimeMillis() / 1000L;
        if (st != null && st.stale) {
            log.w("MlsCredentialUpdate: the SERVER says a credential expired for "
                    + MlsMessageId.forLog(messageId) + " on " + MlsConversationKey.forLog(key)
                    + ", and THE GROUP'S COPY OF OURS IS STALE (" + st
                    + ") — the remedy is RCC.16 §9.5.3, a Self-Update carrying the certificate we "
                    + "already hold. Re-minting again would not touch the group's copy.");
            MlsCredentialUpdate.maybeUpdateGroupCredential(cfg, shell, log, key, g, rcsGroupId,
                    peerE164, "server reported expired-credential");
            return;
        }
        final long ours = st == null ? Long.MIN_VALUE : st.clientRemainingDays(now);
        if (ours != Long.MIN_VALUE && ours < MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS) {
            log.w("MlsCredentialUpdate: the SERVER says a credential expired for "
                    + MlsMessageId.forLog(messageId) + " on " + MlsConversationKey.forLog(key)
                    + " and OUR OWN certificate has " + ours
                    + "d left, inside the " + MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS
                    + "-day floor — a fresh identity/KeyPackage is needed, not a resend");
            MlsIdentityRefresh.onIdentityChanged(cfg, shell, log,
                    "server reported expired-credential");
            return;
        }
        log.e("MlsCredentialUpdate: the SERVER says a credential expired for "
                + MlsMessageId.forLog(messageId)
                + " on " + MlsConversationKey.forLog(key)
                + ", but OURS IS NOT THE STALE ONE — the group holds the certificate "
                + "we hold and it has " + (ours == Long.MIN_VALUE ? "an unreadable window" : ours
                + "d") + " left. NOT re-minting: that would replace a healthy credential, rotate "
                + "the published pool and leave the refusal exactly where it was (this is what made "
                + "the remedy miss on device). The stale credential is a PEER's, and only their own "
                + "Self-Update can clear it — A.4.3.2's carve-out is the committer's own leaf.");
        MlsFloorRebuild.logFloorReport(cfg, log, key, MlsServerBundle.rosterFloorReport(cfg,
                shell.session(), log, g), "server reported expired-credential");
    }
}

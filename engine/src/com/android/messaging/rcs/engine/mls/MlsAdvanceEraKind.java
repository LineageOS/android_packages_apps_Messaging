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
 * The three-valued era-advance MODE — invariant 103, §9.7g, §9.7m. Rework items {@code 11.1c} and
 * {@code 11.2c}.
 *
 * <h2>Why a mode and not a guard</h2>
 *
 * <p>§9.7m's verdict on a defence built from {@code {selfHeal guard, eraAdvance guard, 0xF002 in the
 * carry list}} is worth quoting, because our era-advance half was exactly the shape it names:
 * <i>"CONFIRMED — but Google Messages' is a MODE SELECTOR, not a guard … Google Messages makes clearing a distinct
 * mode that the ordinary advance never selects. That is strictly stronger: <b>there is no code path in
 * which a plain advance COULD clear it and be prevented</b>."</i>
 *
 * <p>A guard prevents a bad path from completing. A mode means the bad path does not exist. The
 * difference shows up the first time someone adds a fourth caller to the advance: with a guard they
 * must remember it, with a mode the compiler asks them which kind they meant.
 *
 * <p>What this REPLACED was a blanket refusal — <i>"if the group carries end_mls, refuse to advance"</i>
 * — and removing it was not a relaxation. That refusal made
 * {@code StartedEraAdvancementForRevival(12)} <b>structurally unreachable</b> even though §5.3 reaches
 * it from twelve states including {@code DoneEndMls(10)}, {@code OngoingEndMls(9)} and
 * {@code CannotHealDuringEndMls(15)}, and it made Phoenix impossible by construction. It forbade the
 * two operations whose entire purpose is to act on a downgraded group.
 *
 * <h2>The byte-level provenance</h2>
 *
 * <p>Read off the branch structure of Google Messages' own era advance, the single most important
 * undocumented control value in this domain:
 *
 * <pre>
 * 0127bbb0  cmp w8, #1 ; b.eq  -&gt;  mode 1: remove_extension(list, EndMls)
 * 0127bbb8  cmp w8, #2 ; b.ne  -&gt;  mode 0 (or &gt;2): touch NEITHER
 *                              -&gt;  mode 2: set_extension(list, EndMls)
 * </pre>
 *
 * <p>The <i>names</i> of the three values are inferred from the surrounding strings; the numbers and
 * the behaviour are proven. The era is set FIRST in all three cases (invariant 102), before the
 * removal, the installation, the continuity check and the commitment reconcile — the mode only decides
 * what happens to {@code 0xF002} afterwards.
 *
 * <p><b>Mode 0 genuinely carries everything forward</b>, because the era-advance path clones the
 * extension list before editing it. That is the structural half of ED-1 (§9.7m mechanism 1): nothing
 * is rebuilt, so nothing can be dropped by omission.
 */
public enum MlsAdvanceEraKind {

    /**
     * Mode 0 — a plain era advance. {@code 0xF002} is left <b>exactly as it is</b>.
     *
     * <p>This is the mode every ordinary caller wants: self-heal, era-advancement-on-divergence,
     * quota recovery. If the group was downgraded it stays downgraded; if it was not, it does not
     * become so. Carrying rather than dropping is the whole point: an era advance re-creates
     * the group, so dropping {@code end_mls} here would silently erase a peer's downgrade and
     * re-encrypt a conversation that is deliberately plaintext.
     */
    NORMAL(0),

    /**
     * Mode 1 — <b>REVIVAL</b>: era+1 <i>and drop</i> {@code 0xF002}.
     *
     * <p>One of exactly <b>two</b> deliberate removal sites permitted by INVARIANT ED-1 (the other is
     * the in-place revive commit). A whole-{@code .text} sweep of Google Messages' {@code ExtensionList::remove}
     * finds seven call sites; exactly two pass {@code 0xF002} and both are the deliberate revive path.
     * <b>There is no third site</b>, and there must be none here either.
     */
    REVIVAL(1),

    /**
     * Mode 2 — <b>PHOENIX</b>: era+1 <i>and install</i> {@code 0xF002}.
     *
     * <p>Phoenix mode is an era advancement whose new era's GroupContext carries {@code end_mls} — the
     * engine's way out of a wedged encrypted state when the ordinary end-mls commit cannot be made to
     * land. Instead of committing "turn encryption off" into the current group, it creates a new era
     * that is <b>born already downgraded</b>: no key packages required, no cooperation from the
     * existing tree required.
     *
     * <p>That is also why §9.5's {@code INV-KP} says "phoenix flag ⇒ zero key packages" — you do not
     * need anybody's key packages to advance into an era whose group is not encrypted.
     *
     * <p><b>A carry list that can only PRESERVE {@code end_mls} cannot express this</b>, which is the
     * second half of invariant 103 and the gap this enum closes. Our previous carry could inherit
     * {@code 0xF002} but never originate it, so "the new era is born downgraded" was inexpressible.
     */
    PHOENIX_DOWNGRADE(2);

    /** The mode byte handed to the engine. Persisted and on the FFI boundary — never renumber. */
    public final int mode;

    MlsAdvanceEraKind(final int mode) { this.mode = mode; }

    public static MlsAdvanceEraKind fromMode(final int mode) {
        for (final MlsAdvanceEraKind k : values()) {
            if (k.mode == mode) return k;
        }
        // Google Messages' own decode treats ">2" as mode 0 (the `b.ne` falls through to touching neither),
        // so an unknown mode is NORMAL rather than an error. Copy that: an advance that carries the
        // extension forward is always the safe answer to "I do not know what you meant".
        return NORMAL;
    }

    /** Whether this mode is permitted to REMOVE {@code 0xF002} — true for {@link #REVIVAL} alone. */
    public boolean mayRemoveEndMls() { return this == REVIVAL; }

    /** Whether this mode INSTALLS {@code 0xF002} — true for {@link #PHOENIX_DOWNGRADE} alone. */
    public boolean installsEndMls() { return this == PHOENIX_DOWNGRADE; }
}

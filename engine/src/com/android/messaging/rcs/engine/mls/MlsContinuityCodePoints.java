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
 * RCC.16 v4.0 §7.11.12 — the continuity code points, and which way round they go.
 *
 * <p>Its own tiny class because <b>we had this pair REVERSED</b> in four places, and the reversal was
 * not a typo: we measured 33 bytes at 0xF011 and named it "the continuity token". A {@code RefHash}
 * is a 32-byte hash, and a 32-byte hash written as {@code opaque<V>} is {@code 0x20 ‖ hash} = 33
 * bytes — so the bytes were always the COMMITMENT and the length said so on its own. The
 * earlier reading was right about the bytes and wrong about which name went with them.
 *
 * <p>The distinction is load-bearing, not cosmetic:
 *
 * <ul>
 *   <li>{@link #TOKEN} is a 256-bit group SECRET, carried <b>only</b> in the encrypted GroupInfo
 *       inside a Welcome. Putting it in a server-bound GroupInfo hands the server the secret.</li>
 *   <li>{@link #COMMITMENT} is a hash and is required in <b>every</b> GroupInfo and Welcome. It is
 *       the one whose absence is meaningful evidence.</li>
 * </ul>
 */
public final class MlsContinuityCodePoints {

    private MlsContinuityCodePoints() { }

    /** §7.11.12.1 {@code continuity_token} — Welcome-only, a secret. */
    public static final int TOKEN = 0xF010;

    /** §7.11.12.2 {@code continuity_token_commitment} — in ALL GroupInfo and Welcome messages. */
    public static final int COMMITMENT = 0xF011;

    /** True iff {@code ty} is one of the two. */
    public static boolean isContinuity(final int ty) { return ty == TOKEN || ty == COMMITMENT; }
}

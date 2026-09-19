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

package com.android.messaging.rcs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The R8 keep rule for the provider contract must name the package the contract is ACTUALLY in.
 *
 * <p><b>This exists because the rule silently stopped matching and nothing noticed.</b> The
 * contract package was renamed and {@code proguard.flags} kept naming the old one, so the rule
 * matched nothing, R8 obfuscated {@code IRcsProvider$Stub} and inlined away every
 * {@code TRANSACTION_*} field, and {@link RcsContractProbe}'s layout derivation returned null —
 * which every caller turns into a refusal to attach. The app shipped unable to bind ANY provider
 * and fell back to SMS. Measured on two devices 2026-09-19: 43 classes under the contract package
 * in the APK, every one renamed to a single letter.
 *
 * <p><b>Why a host test and not a build check:</b> a stale keep rule is not a build error. It
 * compiles, it links, the APK is produced, and 2,051 host tests pass — because the failure is in
 * what R8 did to the bytecode, which only a device (or a DEX scan) can see. The cheapest thing
 * that CAN see it from source is this: the two strings must agree, and they live in different
 * files, which is exactly the shape that rots.
 *
 * <p>This asserts the rule names the contract package. It deliberately does not assert the rule's
 * body or flags — those are R8's business, and pinning them here would fail on a harmless
 * reformat while catching nothing the derivation cares about.
 */
public class RcsContractKeepRuleGuardTest {

    private static final String FLAGS = "proguard.flags";
    private static final String CONTRACT_AIDL =
            "aidl/src/aidl/org/lineageos/rcs/provider/IRcsProvider.aidl";

    /** The package the contract interface actually declares. */
    private static String contractPackage() throws IOException {
        final Matcher m = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;")
                .matcher(SourceScan.read(CONTRACT_AIDL));
        assertTrue("no package declaration in " + CONTRACT_AIDL
                + " — this guard has gone stale, not green", m.find());
        return m.group(1);
    }

    @Test
    public void theKeepRuleNamesThePackageTheContractIsActuallyIn() throws IOException {
        final String pkg = contractPackage();
        final String flags = SourceScan.read(FLAGS);

        // Every package a `-keep class <pkg>.**` rule names.
        final Matcher m = Pattern.compile("(?m)^\\s*-keep\\s+class\\s+([a-zA-Z0-9_.]+)\\.\\*\\*")
                .matcher(flags);
        boolean kept = false;
        final StringBuilder seen = new StringBuilder();
        while (m.find()) {
            seen.append(m.group(1)).append(' ');
            if (pkg.equals(m.group(1))) {
                kept = true;
            }
        }
        assertTrue("no `-keep class ...**` rules found in " + FLAGS
                + " — this guard has gone stale, not green", seen.length() > 0);
        assertTrue(FLAGS + " has no `-keep class " + pkg + ".**` rule, so R8 is free to rename "
                + "IRcsProvider$Stub and inline away its TRANSACTION_* fields. The app then cannot "
                + "derive its own transaction layout and REFUSES every provider, falling back to "
                + "SMS — silently, from the build's point of view. Packages currently kept: "
                + seen.toString().trim(), kept);
    }

    /**
     * And the rule must not name a package that does not exist. A renamed contract leaves the old
     * rule behind matching nothing, which is how this broke: the failure is not a missing rule but
     * a rule pointing somewhere empty.
     */
    @Test
    public void noKeepRuleNamesADeadContractPackage() throws IOException {
        final String flags = SourceScan.read(FLAGS);
        final Matcher m = Pattern.compile(
                "(?m)^\\s*-keep\\s+class\\s+([a-zA-Z0-9_.]*\\.rcs\\.provider)\\.\\*\\*")
                .matcher(flags);
        final StringBuilder dead = new StringBuilder();
        final String pkg = contractPackage();
        while (m.find()) {
            if (!pkg.equals(m.group(1))) {
                dead.append(m.group(1)).append(' ');
            }
        }
        assertEquals("proguard.flags keeps a *.rcs.provider package that the contract is not in. "
                + "A rule naming a package nothing is in matches nothing, which reads as protection "
                + "while providing none", "", dead.toString().trim());
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.messaging.rcs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The R8 keep rule for the provider contract names the package the contract is in. A stale rule
 * still builds, but R8 then inlines the stub's {@code TRANSACTION_*} fields and
 * {@link RcsContractProbe} cannot derive a layout, so every provider is refused. Only the package
 * is pinned, not the rule's flags. See docs/rcs/provider-contract.md.
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

    /** A renamed contract leaves the old rule behind, matching nothing. */
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

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Text accessors over a decrypted CPIM / IMDN document. Deliberately naive: they read documents
 * this client produced or the engine has already authenticated, not arbitrary input.
 */
public final class MlsCpimDoc {
    private MlsCpimDoc() {}

    /** First value of a CPIM/MIME header, or null. */
    public static String header(final String doc, final String name) {
        for (final String line : doc.split("\r?\n")) {
            final int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) {
                return line.substring(c + 1).trim();
            }
            if (line.isEmpty()) break;      // headers end at the blank line
        }
        return null;
    }

    /** Text of the first {@code <name>…</name>} element, or null. */
    public static String element(final String doc, final String name) {
        final int a = doc.indexOf("<" + name + ">");
        if (a < 0) return null;
        final int b = doc.indexOf("</" + name + ">", a);
        return (b < 0) ? null : doc.substring(a + name.length() + 2, b).trim();
    }
}

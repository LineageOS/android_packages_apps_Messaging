/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared source-reading helpers for the source-scan guards. Not a test class; compiled by path into
 * the engine, send-status and database-schema suites, hence {@code public}. Guards built on it key
 * on invoked method names and fail on zero hits. See docs/testing.md.
 */
public final class SourceScan {

    private SourceScan() {}

    /**
     * A method declaration at class level: 4-space indent, a visibility modifier, no initialiser.
     */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    public static final String TRANSPORT =
            "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";

    /** The transport, comments and string contents blanked. See {@link #codeOnly}. */
    public static String transport() throws IOException {
        return codeOnly(read(TRANSPORT));
    }

    /** A source file relative to the module, from the module dir, the tree root or one below. */
    public static String read(final String rel) throws IOException {
        final String[] candidates = {rel, "packages/apps/Messaging/" + rel, "../" + rel};
        for (final String c : candidates) {
            final File f = new File(c);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — tried " + String.join(", ", candidates));
    }

    /** Comments and string-literal contents blanked, offsets and line breaks preserved. */
    public static String codeOnly(final String src) {
        final char[] out = src.toCharArray();
        int i = 0;
        while (i < out.length) {
            final char c = out[i];
            final char next = (i + 1 < out.length) ? out[i + 1] : '\0';
            if (c == '/' && next == '/') {
                while (i < out.length && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && next == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < out.length
                        && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < out.length) out[i++] = ' ';
                if (i < out.length) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                i++;                                    // the quotes themselves are kept
                while (i < out.length && out[i] != c) {
                    if (out[i] == '\\') {
                        out[i++] = ' ';
                        if (i < out.length) out[i++] = ' ';
                        continue;
                    }
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                i++;            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** The brace-matched body of the class-level method named {@code name}. Empty if absent. */
    public static String bodyOf(final String src, final String name) {
        return bodyOf(src, name, "");
    }

    /**
     * As {@link #bodyOf(String, String)}, for the overload whose declaration has {@code
     * declFragment}.
     */
    public static String bodyOf(final String src, final String name, final String declFragment) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            // Match the declaration only, by exact name, so a caller of the method does not answer.
            final String decl = src.substring(m.start(), open);
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            if (!declFragment.isEmpty() && !decl.contains(declFragment)) continue;
            int depth = 0;
            for (int i = open; i < src.length(); i++) {
                final char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
            }
            return "";
        }
        return "";
    }

    /**
     * The body of the declaration beginning with the exact text {@code declAnchor}, for
     * package-private methods {@link #bodyOf} cannot see. Empty when the anchor is absent or not
     * unique.
     */
    public static String bodyOfDeclaredAs(final String src, final String declAnchor) {
        final int at = src.indexOf(declAnchor);
        if (at < 0 || src.indexOf(declAnchor, at + 1) >= 0) return "";
        final int open = src.indexOf('{', at + declAnchor.length() - 1);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    /** Every class-level method declaration as {@code [start, bodyOpenBrace]}, in file order. */
    public static List<int[]> declarations(final String src) {
        final List<int[]> out = new ArrayList<>();
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            final int open = src.indexOf('{', m.end() - 1);
            if (open >= 0) out.add(new int[] {m.start(), open, m.start(1), m.end(1)});
        }
        return out;
    }

    /** The name of the class-level method containing {@code at}, or {@code "<class level>"}. */
    public static String enclosingMethod(final String src, final List<int[]> decls, final int at) {
        String name = "<class level>";
        for (final int[] d : decls) {
            if (d[0] > at) break;
            name = src.substring(d[2], d[3]);
        }
        return name;
    }

    /** How many times {@code needle} appears in {@code haystack}. */
    public static int count(final String haystack, final String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    /** Every index at which {@code needle} appears, so a caller can assert the count. */
    public static List<Integer> indicesOf(final String haystack, final String needle) {
        final List<Integer> out = new ArrayList<>();
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            out.add(Integer.valueOf(i));
        }
        return out;
    }

    /**
     * Every index of {@code needle} outside a class-level declaration header. The needle is a
     * substring, not an identifier ({@code "sendMessage("} matches {@code resendMessage(}); a
     * caller that means a method name checks the preceding character itself.
     */
    public static List<Integer> invocationsOf(final String src, final List<int[]> decls,
            final String needle) {
        final List<Integer> out = new ArrayList<>();
        for (final Integer at : indicesOf(src, needle)) {
            if (!inDeclarationHeader(decls, at.intValue())) out.add(at);
        }
        return out;
    }

    /** Is {@code at} inside a class-level method declaration, before its opening brace? */
    private static boolean inDeclarationHeader(final List<int[]> decls, final int at) {
        for (final int[] d : decls) {
            if (d[0] > at) break;
            if (at <= d[1]) return true;
        }
        return false;
    }

    /** A {@code static final} scalar declaration, modifiers in any order, at any nesting depth. */
    private static final Pattern SCALAR_DECL = Pattern.compile(
            "(?m)^[ \\t]+(?:(?:public|private|protected|static|final)[ \\t]+)*"
            + "(?:int|long|short|byte|double|float|boolean)[ \\t]+(\\w+)[ \\t]*=");

    /**
     * Every {@code static final} scalar constant declared in {@link #codeOnly} {@code src}, in
     * order.
     */
    public static List<String> scalarConstantsDeclaredIn(final String src) {
        final List<String> out = new ArrayList<>();
        final Matcher m = SCALAR_DECL.matcher(src);
        while (m.find()) {
            final String decl = src.substring(m.start(), m.end());
            if (decl.contains("static") && decl.contains("final")) out.add(m.group(1));
        }
        return out;
    }

    /**
     * Every word-bounded use of {@code name} in {@link #codeOnly} {@code src} other than its
     * declaration.
     */
    public static List<Integer> usesOf(final String src, final String name) {
        final List<int[]> declSpans = new ArrayList<>();
        final Matcher d = SCALAR_DECL.matcher(src);
        while (d.find()) {
            if (src.substring(d.start(), d.end()).contains("static")) {
                declSpans.add(new int[] {d.start(), d.end()});
            }
        }
        final List<Integer> out = new ArrayList<>();
        final Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(src);
        while (m.find()) {
            boolean isDeclaration = false;
            for (final int[] span : declSpans) {
                if (m.start() >= span[0] && m.end() <= span[1]) { isDeclaration = true; break; }
            }
            if (!isDeclaration) out.add(Integer.valueOf(m.start()));
        }
        return out;
    }

    /**
     * The paren-matched argument list at the first {@code (} at or after {@code from}, without its
     * brackets. Empty when the parentheses do not close.
     */
    public static String argumentListAt(final String src, final int from) {
        final int open = src.indexOf('(', from);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return src.substring(open + 1, i);
        }
        return "";
    }

    /** Split a paren-matched argument list on its top-level commas. */
    public static List<String> topLevelArguments(final String args) {
        final List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < args.length(); i++) {
            final char c = args.charAt(i);
            if (c == '(' || c == '[' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '>') depth--;
            else if (c == ',' && depth == 0) {
                out.add(args.substring(start, i).trim());
                start = i + 1;
            }
        }
        final String last = args.substring(start).trim();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    /**
     * Every row of every markdown table in {@code md} whose header cells are exactly {@code
     * header}, as {@code {1-based line, trimmed cells}}. A table that gains a column stops
     * matching.
     */
    public static List<Object[]> tableRows(final String md, final String... header) {
        final List<Object[]> out = new ArrayList<>();
        final String[] lines = md.split("\n", -1);
        boolean inTable = false;
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            if (!line.startsWith("|")) { inTable = false; continue; }
            final String[] cells = splitRow(line);
            if (matchesHeader(cells, header)) { inTable = true; continue; }
            if (!inTable) continue;
            if (line.replaceAll("[\\s|:\\-]", "").isEmpty()) continue;   // the ---|--- separator
            out.add(new Object[] {Integer.valueOf(i + 1), cells});
        }
        return out;
    }

    private static boolean matchesHeader(final String[] cells, final String[] header) {
        if (cells.length != header.length) return false;
        for (int i = 0; i < header.length; i++) {
            if (!cells[i].equalsIgnoreCase(header[i])) return false;
        }
        return true;
    }

    private static String[] splitRow(final String line) {
        String s = line.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        final String[] cells = s.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim();
        return cells;
    }

    /** Every {@code `name`} backtick-quoted token in {@code cell} matching {@code pattern}. */
    public static List<String> backtickedMatching(final String cell, final Pattern pattern) {
        final List<String> out = new ArrayList<>();
        final Matcher b = Pattern.compile("`([^`]+)`").matcher(cell);
        while (b.find()) {
            final Matcher m = pattern.matcher(b.group(1));
            if (m.matches()) out.add(b.group(1));
        }
        return out;
    }

    /** The quoted entries of the python list literal assigned to {@code name}, comments ignored. */
    public static List<String> pythonListLiteral(final String py, final String name) {
        final Matcher m =
                Pattern.compile("(?m)^" + Pattern.quote(name) + "\\s*=\\s*\\[").matcher(py);
        if (!m.find()) return new ArrayList<>();
        final int open = py.indexOf('[', m.start());
        int depth = 0;
        int close = -1;
        for (int i = open; i < py.length(); i++) {
            final char c = py.charAt(i);
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) { close = i; break; }
        }
        if (close < 0) return new ArrayList<>();
        final List<String> out = new ArrayList<>();
        final Matcher e = Pattern.compile("'([^']+)'|\"([^\"]+)\"")
                .matcher(withoutPythonComments(py.substring(open, close)));
        while (e.find()) out.add(e.group(1) != null ? e.group(1) : e.group(2));
        return out;
    }

    /**
     * Python source with {@code #} comments blanked, quote-aware, offsets kept, so an apostrophe in
     * a comment cannot open a quote in {@link #pythonListLiteral}.
     */
    public static String withoutPythonComments(final String py) {
        final char[] out = py.toCharArray();
        char quote = 0;
        for (int i = 0; i < out.length; i++) {
            final char c = out[i];
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '#') {
                while (i < out.length && out[i] != '\n') out[i++] = ' ';
            }
        }
        return new String(out);
    }

    /** The engine class of that simple name if it is on the host classpath, else {@code null}. */
    public static Class<?> engineClass(final String simpleName) {
        try {
            return Class.forName("com.android.messaging.rcs.engine.mls." + simpleName);
        } catch (final ClassNotFoundException e) {
            return null;
        }
    }

    /** Does {@code owner} declare {@code member} as a method, a field or a nested type? */
    public static boolean declaresMember(final Class<?> owner, final String member) {
        for (final java.lang.reflect.Method m : owner.getDeclaredMethods()) {
            if (m.getName().equals(member)) return true;
        }
        for (final java.lang.reflect.Field f : owner.getDeclaredFields()) {
            if (f.getName().equals(member)) return true;
        }
        for (final Class<?> c : owner.getDeclaredClasses()) {
            if (c.getSimpleName().equals(member)) return true;
        }
        for (final Object o : owner.isEnum() ? owner.getEnumConstants() : new Object[0]) {
            if (String.valueOf(o).equals(member)) return true;
        }
        return false;
    }

    /**
     * The app sources, the {@code "PROVIDER"} half of the two-package locator: engine classes
     * resolve by reflection, app classes by source under this directory, since they import {@code
     * android}. A symbol missing from one half must be looked up in the other, never skipped.
     */
    public static final String PROVIDER_SRC = "src";

    /** {@code simpleName -> code-only source}, for the provider classes already read. */
    private static final java.util.Map<String, String> PROVIDER_SOURCES =
            new java.util.HashMap<>();

    /**
     * Every {@code .java} under a module-relative directory, recursively; throws when there are
     * none.
     */
    public static List<File> javaSourcesUnder(final String relDir) throws IOException {
        File root = null;
        for (final String c
                : new String[] {relDir, "packages/apps/Messaging/" + relDir, "../" + relDir}) {
            final File f = new File(c);
            if (f.isDirectory()) { root = f; break; }
        }
        if (root == null) {
            throw new IOException(relDir + " not found from " + new File(".").getAbsolutePath());
        }
        final List<File> out = new ArrayList<>();
        final java.util.Deque<File> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            final File[] kids = stack.pop().listFiles();
            if (kids == null) continue;
            for (final File k : kids) {
                if (k.isDirectory()) stack.push(k);
                else if (k.getName().endsWith(".java")) out.add(k);
            }
        }
        java.util.Collections.sort(out);
        if (out.isEmpty()) throw new IOException("no .java sources under " + root.getPath());
        return out;
    }

    /**
     * The {@link #codeOnly} source of the app class of that simple name, searched recursively, or
     * {@code null}.
     */
    public static synchronized String providerClassSource(final String simpleName) throws IOException {
        if (PROVIDER_SOURCES.isEmpty()) {
            for (final File f : javaSourcesUnder(PROVIDER_SRC)) {
                final String n = f.getName();
                PROVIDER_SOURCES.put(n.substring(0, n.length() - ".java".length()),
                        codeOnly(new String(Files.readAllBytes(f.toPath()),
                                StandardCharsets.UTF_8)));
            }
        }
        return PROVIDER_SOURCES.get(simpleName);
    }

    /** {@code "ENGINE"}, {@code "PROVIDER"} or {@code null} when the name is in neither. */
    public static String ownerPackageOf(final String simpleName) throws IOException {
        if (engineClass(simpleName) != null) return "ENGINE";
        return providerClassSource(simpleName) != null ? "PROVIDER" : null;
    }

    /**
     * Whether {@code simpleName} declares {@code member}; {@code null} when the class is in neither
     * half.
     */
    public static Boolean declaresMemberAnywhere(final String simpleName, final String member)
            throws IOException {
        final Class<?> engine = engineClass(simpleName);
        if (engine != null) return Boolean.valueOf(declaresMember(engine, member));
        final String provider = providerClassSource(simpleName);
        if (provider == null) return null;
        return Boolean.valueOf(sourceDeclaresMember(provider, member));
    }

    /**
     * Whether {@link #codeOnly} source declares {@code member} as a method, field, enum constant or
     * type.
     */
    public static boolean sourceDeclaresMember(final String code, final String member) {
        final String m = Pattern.quote(member);
        // A modifier-prefixed declaration: `private final long fooMs =`, `public enum Primitive {`.
        if (Pattern.compile("(?m)^[ \\t]+(?:(?:public|private|protected|static|final|abstract"
                + "|synchronized|native|volatile|transient|default|strictfp)[ \\t]+)+"
                + "[\\w.<>\\[\\], ?&]*\\b" + m + "[ \\t]*[(=;{]").matcher(code).find()) {
            return true;
        }
        // An enum constant: an upper-case name starting its line, followed by , ; ( or {.
        return member.equals(member.toUpperCase(java.util.Locale.ROOT))
                && Pattern.compile("(?m)^[ \\t]+" + m + "[ \\t]*[,;({]").matcher(code).find();
    }

    /**
     * The class declaring the {@code static final} scalar {@code name}, searched in both halves, as
     * {@code {simpleName, "ENGINE"|"PROVIDER"}}, or {@code null}.
     */
    public static String[] declaringOwnerOfScalar(final String name) throws IOException {
        for (final File f : javaSourcesUnder("engine/src/com/android/messaging/rcs/engine/mls")) {
            final String simple = f.getName().substring(0, f.getName().length() - 5);
            if (scalarConstantsDeclaredIn(codeOnly(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)))
                    .contains(name)) {
                return new String[] {simple, "ENGINE"};
            }
        }
        for (final File f : javaSourcesUnder(PROVIDER_SRC)) {
            final String simple = f.getName().substring(0, f.getName().length() - 5);
            if (scalarConstantsDeclaredIn(codeOnly(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)))
                    .contains(name)) {
                return new String[] {simple, "PROVIDER"};
            }
        }
        return null;
    }

    /** How many {@code @Test} methods {@code testClass} declares. */
    public static int testMethodCount(final Class<?> testClass) {
        int n = 0;
        for (final java.lang.reflect.Method m : testClass.getDeclaredMethods()) {
            for (final java.lang.annotation.Annotation a : m.getAnnotations()) {
                if (a.annotationType().getName().equals("org.junit.Test")) { n++; break; }
            }
        }
        return n;
    }
}

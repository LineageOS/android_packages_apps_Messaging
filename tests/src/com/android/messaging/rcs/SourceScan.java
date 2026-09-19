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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared source-reading machinery for the guards that assert over {@code MlsProviderTransport.java}
 * and its siblings.
 *
 * <p>{@code MlsPeerReJoinBudgetGuardTest} and {@code MlsGuardPersistenceTest} each carry their own
 * copy of {@code read} / {@code codeOnly} / {@code bodyOf}. Those two are left alone deliberately —
 * they are working guards and rewriting them to prove a point about duplication is how a green
 * suite becomes a changed one — but a third and fourth copy is where the drift starts, so the new
 * guards share this.
 *
 * <p><b>Not a {@code Test} class</b>, so the host-test jar enumeration does not
 * pick it up; it compiles into the same target as the guards that use it.
 *
 * <p><b>Compiled into TWO targets.</b> {@code messaging2-mls-engine-host-tests} globs it;
 * {@code messaging2-rcs-send-status-host-tests} lists it by path. The class and
 * the five members that second target uses — {@link #read}, {@link #codeOnly}, {@link #bodyOf},
 * {@link #count}, {@link #indicesOf} — are {@code public} for that reason and no other. Sharing one
 * copy was chosen over a fourth hand-rolled scanner for exactly the reason stated above: the third
 * and fourth copies are where the drift starts.
 *
 * <h2>The standing caution these helpers exist under</h2>
 *
 * <p>A source-scan guard encodes a SPELLING, not the property. That is a real cost and it is worth
 * paying only when the property is otherwise unreachable — {@code MlsProviderTransport} needs a
 * {@code Context} and a bound provider, so it has no host test at all. Two rules follow, and every
 * guard built on this class states how it meets them:
 *
 * <ul>
 *   <li>Key on the INVOKED METHOD NAME, never on a receiver, a variable name or a log label. A
 *       method that is renamed and re-linked keeps working; a label that is reworded does not.</li>
 *   <li><b>Zero hits must FAIL.</b> A scan whose pattern has gone stale reports success for the
 *       fraction it happens to still match, and a silently-passing guard is worse than no guard.
 *       Every entry point here returns something a caller can count, and every caller counts it.</li>
 * </ul>
 */
public final class SourceScan {

    private SourceScan() {}

    /** A method declaration at class level: 4-space indent, a visibility modifier, no initialiser. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    public static final String TRANSPORT = "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";

    /** The transport, comments and string contents blanked. See {@link #codeOnly}. */
    public static String transport() throws IOException {
        return codeOnly(read(TRANSPORT));
    }

    /**
     * Read a source file relative to the module. Works from the module dir or from the tree root,
     * the same locator {@code MlsPeerReJoinBudgetGuardTest} uses.
     */
    public static String read(final String rel) throws IOException {
        final String[] candidates = {rel, "packages/apps/Messaging/" + rel, "../" + rel};
        for (final String c : candidates) {
            final File f = new File(c);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — tried " + String.join(", ", candidates));
    }

    /**
     * The EXECUTABLE source: comments and string-literal contents blanked, offsets and line breaks
     * preserved.
     *
     * <p>Every assertion built on this is about what the code DOES. Searching the raw text would let
     * a guard PASS because a comment mentioned the call it was checking for — and would make a class
     * fail its own guard for documenting the thing it forbids.
     */
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
                while (i < out.length && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < out.length) out[i++] = ' ';
                if (i < out.length) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                i++;                                    // keep the opening quote: it is code
                while (i < out.length && out[i] != c) {
                    if (out[i] == '\\') {
                        out[i++] = ' ';
                        if (i < out.length) out[i++] = ' ';
                        continue;
                    }
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                i++;                                    // and the closing quote
            } else {
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
     * The brace-matched body of the class-level method named {@code name} whose DECLARATION contains
     * {@code declFragment} — the overload selector. Matching on the name alone finds a delegating
     * sibling whose body is a single {@code return} and reports the property missing.
     */
    public static String bodyOf(final String src, final String name, final String declFragment) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            // The DECLARATION only, never into the body: a method that merely CALLS the one asked
            // for would otherwise answer as though it were it. Exact name, never a substring.
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
     * The brace-matched body of the declaration whose header begins with the EXACT text
     * {@code declAnchor} — for methods {@link #bodyOf} structurally cannot see.
     *
     * <p>{@link #METHOD_DECL} requires a visibility modifier, and a PACKAGE-PRIVATE method has none
     * to require: {@code String adoptGroup(…)} is invisible to every guard built on {@code bodyOf},
     * which returns the empty string for it — indistinguishable, to a careless caller, from a method
     * whose body holds nothing. {@code MlsVerifyBeforeAdoptGuardTest} hit exactly that.
     *
     * <p>Deliberately NOT a widening of {@code METHOD_DECL}. That pattern also feeds
     * {@link #declarations} and {@link #enclosingMethod}, so broadening it would silently move the
     * attribution every other guard reports. This adds a second, narrower reader instead.
     *
     * <p>Anchored on an exact literal the caller writes out in full, and {@code ""} when the anchor
     * is absent OR appears more than once — an ambiguous anchor must fail the caller's length
     * assertion rather than pick one and look decisive.
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

    /**
     * Every index at which {@code needle} appears — so a caller can assert the COUNT as well as the
     * presence, which is what makes "zero hits fail" checkable.
     */
    public static List<Integer> indicesOf(final String haystack, final String needle) {
        final List<Integer> out = new ArrayList<>();
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            out.add(Integer.valueOf(i));
        }
        return out;
    }

    /**
     * Every index at which {@code needle} appears as an INVOCATION — occurrences inside a
     * class-level method's own declaration header are dropped.
     *
     * <p>{@code "foo("} matches {@code private int foo(final String a)} as readily as {@code foo(a)},
     * so a guard counting call sites without this reports one more than exist and attributes the
     * declaration to itself as its own enclosing method. That is not hypothetical: it is what the
     * first cut of {@code MlsSelfHealChargeGuardTest} did.
     *
     * <p><b>AND THE HALF IT DOES NOT DO: THE NEEDLE IS A SUBSTRING, NOT AN IDENTIFIER.</b> The
     * search underneath is {@link #indicesOf}, i.e. {@code String.indexOf}, so
     * {@code "sendMessage("} matches inside {@code getShowResendMessage(},
     * {@code resendMessage(} and {@code canRedownloadMessage(}. The first cut of
     * {@code MlsUngatedOutboundSendGuardTest} reported TWELVE call sites that do not exist for
     * exactly that reason.
     *
     * <p>It is deliberately NOT fixed here: existing callers pass needles like
     * {@code "SealCapability."} and {@code ".load("} that are not identifiers at all, and narrowing
     * this method would silently change what those guards count. A caller that means "a call to a
     * method of this NAME" must add the boundary check itself — assert the character before the
     * match is not {@code [A-Za-z0-9_$]} — which is four lines and belongs at the call site that
     * knows it wants an identifier.
     *
     * <p>The failure is worth its own paragraph because of WHERE it lands. A phantom hit in a guard
     * that enumerates a surface does not merely add noise: each one has to be written up as an
     * exemption, and an exemption list padded with fictions is how the real row stops being read.
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

    // ---- Stage 7 additions ------------------------------------------------------------
    //
    // A reader of this class in an earlier revision will remember a reader here that reached into a
    // SIBLING repository — the out-of-tree RCS provider — so that a guard could enumerate its
    // subject from a ground truth checked in over there. It is gone, with every check that used it.
    // This repository does not contain that repository, so those checks could only report that they
    // could not find their subject, and a scan that cannot find its subject certifies nothing. Do
    // not re-add one: a guard belongs in the repository that holds the code it guards.

    /** A {@code static final} scalar declaration, modifiers in any order, at any nesting depth. */
    private static final Pattern SCALAR_DECL = Pattern.compile(
            "(?m)^[ \\t]+(?:(?:public|private|protected|static|final)[ \\t]+)*"
            + "(?:int|long|short|byte|double|float|boolean)[ \\t]+(\\w+)[ \\t]*=");

    /**
     * Every {@code static final} scalar constant declared in {@code src}, in file order.
     *
     * <p>Structural: a declaration is a declaration because of its SHAPE, not because of its name.
     * Pass {@link #codeOnly} output — a constant named in a javadoc is not declared by it.
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
     * Every use of the identifier {@code name} in {@code src} that is NOT its own declaration.
     *
     * <p>Word-bounded, so {@code FOO} does not match {@code FOO_BAR}. Pass {@link #codeOnly}
     * output: a constant mentioned in prose is not used by it, and counting prose is defect (2)
     * of the instrument.
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
     * The paren-matched argument list starting at the {@code (} at or after {@code from}, without
     * its brackets. Empty when the parentheses do not close.
     *
     * <p>Paren-MATCHED rather than "up to the next comma or close bracket": instance 4 of the
     * needle failure is a window that encoded how far apart the code happened to sit, and an argument
     * list containing a nested call is the same shape one level down.
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

    /** Split a paren-matched argument list on its TOP-LEVEL commas. */
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
     * Every row of every markdown table in {@code md} whose header cells are exactly
     * {@code header}, as trimmed cell arrays, each paired with its 1-based line number.
     *
     * <p>The header match is what makes this an enumeration rather than a guess: a table that gains
     * a column stops matching and the caller's "zero rows" assertion fires, which is the loud
     * direction. A table matched on "starts with a pipe" would silently acquire the file's other
     * tables instead.
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

    /**
     * A python list literal assigned to {@code name} in a checked-in tool, as its quoted entries.
     *
     * <p>{@code tools/mls/transport-classify.py} is the DoD-5 instrument and it already carries the
     * DoD-1 classification. Reading it here rather than re-typing the names is what stops the two
     * halves of the same work disagreeing about which constants are policy.
     */
    public static List<String> pythonListLiteral(final String py, final String name) {
        final Matcher m = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\s*=\\s*\\[").matcher(py);
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
     * Blank out {@code #} comments, so a list's PROSE cannot be read as its CONTENTS.
     *
     * <p><b>Why this exists rather than a warning.</b> {@link #pythonListLiteral} scans quoted tokens
     * across the whole bracket span, comments included — so an apostrophe in a comment inside the
     * list opens a quote that pairs with the next one and mis-reads every real name after it. The
     * failure is not a parse error: the guard reports PHANTOM CONSTANTS assembled out of English
     * ("this file", "s own criterion") and a reader sees a list that has apparently changed.
     *
     * <p>{@code transport-classify.py}'s {@code WIRE_CONSTANTS} block carried a note telling editors
     * to keep quote characters out of its comments. That note was on ONE of the two lists this
     * method parses, and it was advice where a fix was available: the hazard belongs to the parser,
     * not to the document, and an instruction that must be re-read by every future editor of either
     * list is the weaker of the two.
     *
     * <p>Quote-aware, so a {@code #} inside a name is not treated as a comment. Comments are BLANKED
     * rather than deleted, keeping offsets and line breaks intact for anything that reports a
     * position. Reported by a reviewer who hit it twice — once with an apostrophe and again
     * with the double quotes in the replacement comment.
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

    /**
     * The engine class of that simple name if it is on the HOST CLASSPATH, else {@code null}.
     *
     * <p>This is the strongest key a guard over a checked-in artefact can have: a class that
     * {@link Class#forName} resolves is a class that COMPILED, and the member checks below are
     * about the real signature rather than about how a document spelled it. Recommendation (d) in
     * the form it can actually take for a document: where a claim names a
     * production symbol, resolve the symbol.
     */
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

    // ---- the two-package locator (the DoD-3 blindness in DoD-1/2/4) -----------------

    /**
     * <b>Where a class lives is not what a check may be keyed on.</b>
     *
     * <p>{@link #engineClass} resolves only {@code com.android.messaging.rcs.engine.mls}, and every
     * guard built on it wrote {@code if (owner == null) continue;} — so a symbol that moved to the
     * PROVIDER, or was always there, is not checked and never was. Measured rather than argued, on
     * two guards that have since been removed for an unrelated reason: corrupting five named
     * predicates at once in the table one of them enumerated from left it GREEN, and repointing a
     * resource ledger at a nonexistent member of a provider class left the other GREEN. That is the
     * familiar shape — <i>a check whose subject is defined by WHERE a thing lives rather than by
     * WHAT it is</i> — and the measurement is recorded here because the LOCATOR below is what fixed
     * it and is still used by the guards that remain.
     *
     * <p>So: resolve the ENGINE half by reflection (a class that {@link Class#forName} answers is a
     * class that compiled) and the PROVIDER half by source, recursively under {@code src/}, because
     * a provider class imports {@code android} and can never be on a host classpath. The two
     * together are the subsystem; neither alone is.
     */
    public static final String PROVIDER_SRC = "src";

    /** {@code simpleName -> code-only source}, for the provider classes already read. */
    private static final java.util.Map<String, String> PROVIDER_SOURCES =
            new java.util.HashMap<>();

    /** Every {@code .java} under a module-relative directory, RECURSIVELY. Never empty silently. */
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
     * The {@link #codeOnly} source of the PROVIDER class of that simple name, or {@code null}.
     *
     * <p>Recursive, so a class that moves into a subpackage of {@code e2ee} is still found — the
     * non-recursive {@code dir.list()} form is what made moving {@code MlsPeerGuard.java} one
     * directory down read as "the predicate lost all four of its production call sites".
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
     * Does {@code simpleName} declare {@code member}? {@code null} when the class is in neither
     * package — which is a THIRD answer and must never collapse into "yes".
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
     * A DECLARATION of {@code member} in {@link #codeOnly} source — a method, a field, an enum
     * constant or a nested type. Never a bare mention: the same rule, one level down.
     */
    public static boolean sourceDeclaresMember(final String code, final String member) {
        final String m = Pattern.quote(member);
        // A modifier/type-prefixed declaration: `public static boolean allowEraAdvance(`,
        // `private final long fooMs =`, `public enum Primitive {`, `static final int X;`.
        if (Pattern.compile("(?m)^[ \\t]+(?:(?:public|private|protected|static|final|abstract"
                + "|synchronized|native|volatile|transient|default|strictfp)[ \\t]+)+"
                + "[\\w.<>\\[\\], ?&]*\\b" + m + "[ \\t]*[(=;{]").matcher(code).find()) {
            return true;
        }
        // An enum constant: a SHOUTING name alone on its line, followed by , ; ( or {.
        return member.equals(member.toUpperCase(java.util.Locale.ROOT))
                && Pattern.compile("(?m)^[ \\t]+" + m + "[ \\t]*[,;({]").matcher(code).find();
    }

    /**
     * The class that DECLARES the {@code static final} scalar {@code name}, as
     * {@code {simpleName, "ENGINE"|"PROVIDER"}}, or {@code null} when nothing declares it.
     *
     * <p>By declaration SHAPE over both trees, so a constant cannot hide by moving between them.
     * This is DoD-3's {@code declaringOwnerOf} generalised — recursive, and it answers WHICH LAYER,
     * which is the half DoD-1 needs: a policy constant that leaves {@code MlsProviderTransport.java}
     * for a sibling in the same package has not left the layer this work is emptying.
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

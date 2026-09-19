/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * No method in the transport mixes an Android effect with a policy decision unless it is exempt.
 * The checked-in classifier is run in a subprocess rather than reimplemented, so one instrument
 * measures the property, and a classifier that did not run fails. Each exemption carries a claim
 * checked against the method's body. See docs/mls/transport-and-port.md.
 */
public final class MlsTransportDecisionCoverageGuardTest {

    private static final String TOOL = "tools/mls/transport-classify.py";

    /** The original baseline, quoted in the failure message; not the ratchet. */
    private static final int PUBLISHED_BASELINE_METHODS = 46;
    private static final int PUBLISHED_BASELINE_LINES = 5802;

    /** Mixed methods in the transport outside {@link #EXEMPT}. */
    private static final int MIXED_IN_SCOPE_METHODS = 0;
    /** Their non-blank code lines, signature to closing brace; comments do not count. */
    private static final int MIXED_IN_SCOPE_LINES = 0;

    /**
     * Mixed methods in the provider {@code e2ee} package outside the transport. Each judges a
     * {@code SharedPreferences} scan whose enumeration needs a {@code Context}.
     */
    private static final int SIBLING_MIXED_METHODS = 10;
    /** Their non-blank code lines. */
    private static final int SIBLING_MIXED_LINES = 367;

    /** One exempted method, and the claim that earns the exemption. */
    private static final class Exempt {
        final String method;
        final String claim;      // a needle that must be present in the brace-matched body
        final String why;
        Exempt(final String method, final String claim, final String why) {
            this.method = method;
            this.claim = claim;
            this.why = why;
        }
    }

    /** Methods whose only decision is the fixture guard that stops them on a user build. */
    private static final Exempt[] EXEMPT = {
        // Moving the build guard into the engine would make a fixture into a policy.
        new Exempt("armInboundHold", "buildAllowsFixtures(",
                "a fixture arm; the guard that exempts it is the one it must keep"),
        new Exempt("armOutboundHold", "buildAllowsFixtures(",
                "ditto"),
    };

    /** A classifier that could not be found, could not run, or printed nothing fails. */
    @Test
    public void theClassifierRanAndClassifiedTheWholeFile() throws Exception {
        final List<Map<String, Object>> rows = classify();
        assertTrue("tools/mls/transport-classify.py classified " + rows.size() + " methods — the "
                + "transport has ~200 and anything near zero means the tool did not run against "
                + "the file this test thinks it did", rows.size() >= 150);
        int tagged = 0;
        for (final Map<String, Object> r : rows) {
            if (!((List<?>) r.get("tags")).isEmpty()) tagged++;
        }
        assertTrue(
                "no method carried a single tag — the tagger's regexes matched nothing, so every "
                + "bucket is empty and the ratchet would read as met", tagged >= 50);
    }

    /** Each exemption's claim is checked against the method's code-only body. */
    @Test
    public void everyExemptionNamesAMethodThatStillEarnsIt() throws IOException {
        final String code = SourceScan.transport();
        final List<String> broken = new ArrayList<>();
        for (final Exempt e : EXEMPT) {
            final String body = SourceScan.bodyOf(code, e.method);
            if (body.isEmpty()) {
                broken.add(e.method + " — no such class-level method; delete the exemption, or it "
                        + "will be inherited by the next method to take the name");
            } else if (!body.contains(e.claim)) {
                broken.add(e.method + " — exempt because \"" + e.why + "\", and its body no longer "
                        + "contains " + e.claim);
            }
        }
        if (!broken.isEmpty()) fail("ratchet exemptions that no longer hold: " + broken);
    }

    /**
     * The ratchet, two-sided: a rise fails, and a fall fails until the constants are lowered. An
     * exemption that has left the mixed bucket must be removed.
     */
    @Test
    public void theMixedBucketOnlyShrinksAndEveryMemberIsExemptOrInScope() throws Exception {
        final Map<String, Integer> mixed = mixedBucket();
        final Set<String> exempt = new LinkedHashSet<>();
        for (final Exempt e : EXEMPT) exempt.add(e.method);

        final List<String> exemptButNotMixed = new ArrayList<>();
        for (final String name : exempt) {
            if (!mixed.containsKey(name)) exemptButNotMixed.add(name);
        }

        int methods = 0;
        int lines = 0;
        final List<String> inScope = new ArrayList<>();
        for (final Map.Entry<String, Integer> e : mixed.entrySet()) {
            if (exempt.contains(e.getKey())) continue;
            methods++;
            lines += e.getValue().intValue();
            inScope.add(e.getKey() + "(" + e.getValue() + ")");
        }

        if (!exemptButNotMixed.isEmpty()) {
            fail("These methods are on the exemption list and are no longer in the MIXED "
                    + "bucket: " + exemptButNotMixed + ". That is progress — remove the row so the "
                    + "list keeps meaning \"exempt AND mixed\". (In scope now: " + methods
                    + " methods / " + lines + " lines.)");
        }
        if (methods > MIXED_IN_SCOPE_METHODS || lines > MIXED_IN_SCOPE_LINES) {
            Collections.sort(inScope);
            fail("the ratchet went BACKWARDS: " + methods + " methods / " + lines
                    + " body lines mix an "
                    + "Android effect with a policy decision and are not exempt, up from "
                    + MIXED_IN_SCOPE_METHODS + " / " + MIXED_IN_SCOPE_LINES + ". (The published "
                    + "baseline this ratchet is written against is " + PUBLISHED_BASELINE_METHODS
                    + " / " + PUBLISHED_BASELINE_LINES + ", counted with the uncorrected "
                    + "instrument.) In scope: " + inScope);
        }
        if (methods < MIXED_IN_SCOPE_METHODS || lines < MIXED_IN_SCOPE_LINES) {
            fail("the ratchet IMPROVED — " + methods + " methods / " + lines
                    + " lines in scope, down "
                    + "from " + MIXED_IN_SCOPE_METHODS + " / " + MIXED_IN_SCOPE_LINES + ". Lower "
                    + "both constants in this test in the SAME commit, so the next regression is "
                    + "caught against the new floor.");
        }
    }

    /**
     * A method may not leave the bucket by becoming invisible to the classifier. The tool's
     * signature regex is looser than {@link SourceScan}'s, so this fires only when it sees fewer.
     */
    @Test
    public void theClassifierStillSeesEveryClassLevelMethodTheSourceDeclares() throws Exception {
        final int seenByTool = classify().size();
        final int seenBySourceScan =
                SourceScan.declarations(SourceScan.transport()).size();
        assertTrue(
                        "ZERO HITS MUST FAIL: the transport's class-level declarations is EMPTY, so the loop below never runs "
                        + "and this assertion certifies green having examined nothing. A zero-match "
                        + "scan is a broken scan, not a clean tree.",
                seenBySourceScan > 0);
        final int missing = seenBySourceScan - seenByTool;
        if (missing > 0) {
            fail("The classifier sees " + seenByTool + " class-level methods; an independent scan "
                    + "of the same file finds " + seenBySourceScan + ". " + missing + " method(s) "
                    + "are invisible to the classifier: "
                    + "the bucket shrinks without anything having moved.");
        }
    }

    /**
     * A method may not leave the bucket by moving to a sibling file: the rest of the {@code e2ee}
     * package has its own ratchet, which a file split leaves unchanged. Provider classes outside
     * {@code e2ee} are not covered; widening the subject would pull in a large debug receiver.
     */
    @Test
    public void theProviderLayerOutsideTheTransportHasItsOwnRatchet() throws Exception {
        final Map<String, Integer> mixed = mixedBucketOutsideTheTransport();
        int lines = 0;
        for (final Integer n : mixed.values()) lines += n.intValue();
        final List<String> members = new ArrayList<>(mixed.keySet());
        Collections.sort(members);
        if (mixed.size() > SIBLING_MIXED_METHODS || lines > SIBLING_MIXED_LINES) {
            fail("The provider layer OUTSIDE MlsProviderTransport gained mixed methods: "
                    + mixed.size() + " / " + lines + " lines, up from " + SIBLING_MIXED_METHODS
                    + " / " + SIBLING_MIXED_LINES + ". If the transport's number fell in the same "
                    + "change, that is a FILE SPLIT and not a stage — a method that moves to a "
                    + "sibling in the same package has not left the layer and is no more reachable "
                    + "from a host test than it was. " + members);
        }
        if (mixed.size() < SIBLING_MIXED_METHODS || lines < SIBLING_MIXED_LINES) {
            fail("The provider layer outside the transport SHRANK — " + mixed.size() + " / " + lines
                    + " lines, down from " + SIBLING_MIXED_METHODS + " / " + SIBLING_MIXED_LINES
                    + ". Lower both constants in this test in the SAME commit. " + members);
        }
    }

    /** {@code name -> body lines} for every method in the mixed bucket. */
    private static Map<String, Integer> mixedBucket() throws Exception {
        final Set<String> effect = new HashSet<>(Arrays.asList("NET", "PERSIST", "SCHED"));
        final Set<String> decision =
                new HashSet<>(Arrays.asList("CONST", "GUARD", "POLICY", "SYSPROP"));
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final Map<String, Object> r : classify()) {
            boolean hasEffect = false;
            boolean hasDecision = false;
            for (final Object t : (List<?>) r.get("tags")) {
                if (effect.contains(t)) hasEffect = true;
                if (decision.contains(t)) hasDecision = true;
            }
            if (hasEffect && hasDecision) {
                out.put((String) r.get("name"), (Integer) r.get("lines"));
            }
        }
        return out;
    }

    /** {@code Class.method -> body lines} for every mixed method in e2ee outside the transport. */
    private static Map<String, Integer> mixedBucketOutsideTheTransport() throws Exception {
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final File f : SourceScan.javaSourcesUnder("src/com/android/messaging/rcs/e2ee")) {
            if (f.getName().equals("MlsProviderTransport.java")) continue;
            final String simple = f.getName().substring(0, f.getName().length() - ".java".length());
            for (final Map<String, Object> r : classifyFile(f.getPath())) {
                if (isMixed(r)) {
                    out.put(simple + "." + r.get("name") + "(" + r.get("lines") + ")",
                            (Integer) r.get("lines"));
                }
            }
        }
        return out;
    }

    private static boolean isMixed(final Map<String, Object> row) {
        final Set<String> effect = new HashSet<>(Arrays.asList("NET", "PERSIST", "SCHED"));
        final Set<String> decision =
                new HashSet<>(Arrays.asList("CONST", "GUARD", "POLICY", "SYSPROP"));
        boolean hasEffect = false;
        boolean hasDecision = false;
        for (final Object t : (List<?>) row.get("tags")) {
            if (effect.contains(t)) hasEffect = true;
            if (decision.contains(t)) hasDecision = true;
        }
        return hasEffect && hasDecision;
    }

    private static List<Map<String, Object>> cached;



    /** Runs the checked-in classifier over the transport and parses its {@code --json}. */
    private static synchronized List<Map<String, Object>> classify() throws Exception {
        if (cached != null) return cached;
        cached = classifyFile(locate(SourceScan.TRANSPORT, "", "packages/apps/Messaging/",
                "../"));
        return cached;
    }

    /** The same instrument, over any one file. */
    private static List<Map<String, Object>> classifyFile(final String target) throws Exception {
        final String tool = locate(TOOL, "", "packages/apps/Messaging/");
        final ProcessBuilder pb = new ProcessBuilder("python3", tool, target, "--json");
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        final String stdout;
        try (InputStream in = p.getInputStream()) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] chunk = new byte[8192];
            for (int n = in.read(chunk); n > 0; n = in.read(chunk)) buf.write(chunk, 0, n);
            stdout = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
        if (p.waitFor() != 0) {
            throw new IllegalStateException("transport-classify.py exited " + p.exitValue()
                    + " over " + target + " — the classifier did not run, so its number means "
                    + "nothing:\n" + stdout);
        }
        final int at = stdout.indexOf("[{\"name\"");
        if (at < 0 && stdout.trim().endsWith("[]")) return new ArrayList<>();   // a file with no
                                                                               // class-level method
        if (at < 0) {
            throw new IllegalStateException("transport-classify.py --json printed no row array. "
                    + "Its output shape changed and this guard cannot read it; that must fail "
                    + "rather than degrade to zero rows. Output was:\n" + stdout);
        }
        return parseRows(stdout.substring(at));
    }

    private static String locate(final String rel, final String... prefixes) throws IOException {
        for (final String prefix : prefixes) {
            final File f = new File(prefix + rel);
            if (f.isFile()) return f.getPath();
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — the classifier or its subject is missing");
    }

    /**
     * A parser for exactly the shape {@code --json} emits: a flat array of objects whose values are
     * strings, integers or arrays of strings. The host classpath has no JSON library.
     */
    private static List<Map<String, Object>> parseRows(final String json) {
        final List<Map<String, Object>> out = new ArrayList<>();
        int i = json.indexOf('[') + 1;
        while (i < json.length()) {
            while (i < json.length() && json.charAt(i) != '{' && json.charAt(i) != ']') i++;
            if (i >= json.length() || json.charAt(i) == ']') break;
            final Map<String, Object> row = new LinkedHashMap<>();
            i++;
            while (i < json.length() && json.charAt(i) != '}') {
                final int keyStart = json.indexOf('"', i) + 1;
                final int keyEnd = json.indexOf('"', keyStart);
                final String key = json.substring(keyStart, keyEnd);
                int v = json.indexOf(':', keyEnd) + 1;
                while (v < json.length() && json.charAt(v) == ' ') v++;
                if (json.charAt(v) == '"') {
                    final int end = json.indexOf('"', v + 1);
                    row.put(key, json.substring(v + 1, end));
                    i = end + 1;
                } else if (json.charAt(v) == '[') {
                    final int end = json.indexOf(']', v);
                    final List<String> items = new ArrayList<>();
                    for (final String s : json.substring(v + 1, end).split(",")) {
                        final String t = s.trim().replace("\"", "");
                        if (!t.isEmpty()) items.add(t);
                    }
                    row.put(key, items);
                    i = end + 1;
                } else {
                    int end = v;
                    while (end < json.length() && "-0123456789".indexOf(json.charAt(end))
                            >= 0) end++;
                    row.put(key, Integer.valueOf(json.substring(v, end)));
                    i = end;
                }
                while (i < json.length() && (json.charAt(i) == ',' || json.charAt(i) == ' ')) i++;
            }
            out.add(row);
            i++;
        }
        return out;
    }
}

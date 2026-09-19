/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A host-test {@link MlsShellPort}: only the members a test stubs answer; every other call throws
 * naming the member, so a test cannot pass by touching an effect it never declared. Proxy-based
 * because the port grows one member per moved method.
 */
public final class FakeShellPort {
    private final Map<String, Function<Object[], Object>> mStubs = new HashMap<>();
    /** Every call made, as {@code name(arg, arg)}, in order — for asserting effects. */
    public final List<String> calls = new ArrayList<>();

    public FakeShellPort on(final String member, final Function<Object[], Object> answer) {
        mStubs.put(member, answer);
        return this;
    }

    public FakeShellPort returns(final String member, final Object value) {
        return on(member, args -> value);
    }

    private final java.util.Set<String> mReal = new java.util.HashSet<>();

    /**
     * Runs the port's own default body for {@code member} (an engine seam such as {@code park}),
     * so a test exercises the real method behind it; the call is still recorded.
     */
    public FakeShellPort real(final String member) {
        mReal.add(member);
        return this;
    }

    public MlsShellPort port() {
        return (MlsShellPort) Proxy.newProxyInstance(MlsShellPort.class.getClassLoader(),
                new Class<?>[] {MlsShellPort.class}, (proxy, method, args) -> {
                    final StringBuilder sb = new StringBuilder(method.getName()).append('(');
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(args[i] instanceof byte[]
                                    ? MlsHex.hex((byte[]) args[i]) : String.valueOf(args[i]));
                        }
                    }
                    calls.add(sb.append(')').toString());
                    if (method.isDefault() && mReal.contains(method.getName())) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    final Function<Object[], Object> f = mStubs.get(method.getName());
                    if (f == null) {
                        throw new UnsupportedOperationException("unstubbed MlsShellPort."
                                + method.getName() + " — stub it, or the method under test "
                                + "reached an effect the test did not expect");
                    }
                    return f.apply(args == null ? new Object[0] : args);
                });
    }

    /**
     * Any other interface the method under test takes (MlsSession, MlsTelemetry, ...), stubbed the
     * same way: {@code name, answer} pairs, where an answer is a value or a
     * {@code Function<Object[], Object>}. Calls are recorded into {@link #calls} too.
     */
    @SuppressWarnings("unchecked")
    public <T> T stub(final Class<T> iface, final Object... nameAnswer) {
        final Map<String, Object> answers = new HashMap<>();
        for (int i = 0; i < nameAnswer.length; i += 2) answers.put((String) nameAnswer[i],
                nameAnswer[i + 1]);
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface},
                (proxy, method, args) -> {
                    calls.add(iface.getSimpleName() + "." + method.getName());
                    if (!answers.containsKey(method.getName())) {
                        throw new UnsupportedOperationException("unstubbed " + iface.getSimpleName()
                                + "." + method.getName());
                    }
                    final Object a = answers.get(method.getName());
                    return a instanceof Function
                            ? ((Function<Object[], Object>) a)
                            .apply(args == null ? new Object[0] : args) : a;
                });
    }

    /** A recording log sink, for asserting what a moved decision logged. */
    public static final class Log implements MlsLogSink {
        public final List<String> lines = new ArrayList<>();
        @Override public void d(final String m) { lines.add("D " + m); }
        @Override public void i(final String m) { lines.add("I " + m); }
        @Override public void w(final String m) { lines.add("W " + m); }
        @Override public void w(final String m, final Throwable t) { lines.add("W " + m); }
        @Override public void e(final String m) { lines.add("E " + m); }
        @Override public void e(final String m, final Throwable t) { lines.add("E " + m); }

        public boolean said(final String level, final String fragment) {
            for (final String l : lines) {
                if (l.startsWith(level + " ") && l.contains(fragment)) return true;
            }
            return false;
        }
    }
}

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
package com.android.messaging.rcs.rbm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free JSON reader (RFC 8259 subset) for the RBM bot-message
 * parser. PURE JAVA — deliberately NOT {@code org.json}, so the parser stays
 * host-testable (the OpenRCSChat host test classpath is org.json-free) and usable
 * in both the provider and the main app.
 *
 * <p>Parses to plain objects: {@code Map<String,Object>} (object, insertion-
 * ordered), {@code List<Object>} (array), {@code String}, {@code Double}
 * (number), {@code Boolean}, and {@code null}. Read-only; supports the full
 * string escape set incl. {@code \\uXXXX}. Throws {@link JsonException} on
 * malformed input.
 */
public final class Json {

    /** Thrown on malformed JSON. */
    public static final class JsonException extends Exception {
        public JsonException(String msg) { super(msg); }
    }

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    /** Parse a JSON document into a plain object tree. */
    public static Object parse(String text) throws JsonException {
        if (text == null) throw new JsonException("null input");
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw new JsonException("trailing data at " + p.i);
        return v;
    }

    /** Convenience: parse and require a top-level object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new JsonException("top-level value is not an object");
        return (Map<String, Object>) v;
    }

    private Object value() throws JsonException {
        if (i >= s.length()) throw new JsonException("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': case 'f': return bool();
            case 'n': literal("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw new JsonException("unexpected char '" + c + "' at " + i);
        }
    }

    private Map<String, Object> object() throws JsonException {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw new JsonException("expected key string at " + i);
            String key = string();
            ws();
            if (peek() != ':') throw new JsonException("expected ':' at " + i);
            i++;
            ws();
            m.put(key, value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; break; }
            throw new JsonException("expected ',' or '}' at " + i);
        }
        return m;
    }

    private List<Object> array() throws JsonException {
        List<Object> a = new ArrayList<>();
        i++; // [
        ws();
        if (peek() == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; break; }
            throw new JsonException("expected ',' or ']' at " + i);
        }
        return a;
    }

    private String string() throws JsonException {
        StringBuilder b = new StringBuilder();
        i++; // opening quote
        while (true) {
            if (i >= s.length()) throw new JsonException("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                if (i >= s.length()) throw new JsonException("bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new JsonException("bad \\u escape");
                        try {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException nfe) {
                            throw new JsonException("bad \\u hex at " + i);
                        }
                        i += 4;
                        break;
                    default: throw new JsonException("bad escape '\\" + e + "'");
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private Double number() throws JsonException {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                    || c == '+' || c == '-') {
                i++;
            } else {
                break;
            }
        }
        try {
            return Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException nfe) {
            throw new JsonException("bad number at " + start);
        }
    }

    private Boolean bool() throws JsonException {
        if (s.charAt(i) == 't') { literal("true"); return Boolean.TRUE; }
        literal("false");
        return Boolean.FALSE;
    }

    private void literal(String lit) throws JsonException {
        if (!s.regionMatches(i, lit, 0, lit.length())) {
            throw new JsonException("expected '" + lit + "' at " + i);
        }
        i += lit.length();
    }

    private char peek() throws JsonException {
        if (i >= s.length()) throw new JsonException("unexpected end");
        return s.charAt(i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }
}

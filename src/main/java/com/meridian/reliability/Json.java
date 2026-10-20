package com.meridian.reliability;

import java.util.*;

/** Minimal JSON parser (objects, arrays, strings, numbers, booleans, null). Keeps the project dependency-free. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("Trailing content");
        return v;
    }

    private Object value() {
        if (i >= s.length()) throw err("Unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws(); need(':'); ws();
            m.put(k, value());
            ws();
            if (peek(',')) { i++; continue; }
            need('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (peek(']')) { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (peek(',')) { i++; continue; }
            need(']');
            return l;
        }
    }

    private String string() {
        need('"');
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);
                }
            } else sb.append(c);
        }
        throw err("Unterminated string");
    }

    private Number number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw err("Unexpected character '" + s.charAt(i) + "'");
        String t = s.substring(st, i);
        return (t.contains(".") || t.contains("e") || t.contains("E")) ? (Number) Double.valueOf(t) : (Number) Long.valueOf(t);
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
    private void need(char c) { if (!peek(c)) throw err("Expected '" + c + "'"); i++; }
    private void expect(String w) { if (!s.startsWith(w, i)) throw err("Expected " + w); i += w.length(); }
    private IllegalArgumentException err(String m) { return new IllegalArgumentException(m + " at offset " + i); }
}

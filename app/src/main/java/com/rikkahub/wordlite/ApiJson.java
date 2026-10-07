package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JSON/path support, usable in JVM transport tests without Android stubs. */
public final class ApiJson {
    private final String input;
    private int at;
    private ApiJson(String input) { this.input = input; }
    public static Object parse(String text) {
        if (text == null || text.length() > 8 * 1024 * 1024) throw new IllegalArgumentException("响应过大");
        ApiJson parser = new ApiJson(text); Object value = parser.value(0); parser.space();
        if (parser.at != text.length()) throw new IllegalArgumentException("JSON格式无效"); return value;
    }
    private void space() { while (at < input.length() && Character.isWhitespace(input.charAt(at))) at++; }
    private Object value(int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON层级过深"); space();
        if (at >= input.length()) throw new IllegalArgumentException("JSON格式无效");
        char c = input.charAt(at);
        if (c == '"') return string();
        if (c == '{') {
            at++; LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>(); space();
            if (take('}')) return map;
            do { space(); String key = string(); space(); if (!take(':')) throw new IllegalArgumentException("JSON格式无效");
                map.put(key, value(depth + 1)); space(); if (take('}')) return map;
            } while (take(','));
            throw new IllegalArgumentException("JSON格式无效");
        }
        if (c == '[') {
            at++; ArrayList<Object> array = new ArrayList<Object>(); space(); if (take(']')) return array;
            do { array.add(value(depth + 1)); space(); if (take(']')) return array; } while (take(','));
            throw new IllegalArgumentException("JSON格式无效");
        }
        if (input.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
        if (input.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
        if (input.startsWith("null", at)) { at += 4; return null; }
        int start = at;
        while (at < input.length() && "-+0123456789.eE".indexOf(input.charAt(at)) >= 0) at++;
        String number = input.substring(start, at);
        if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("JSON数字无效");
        Double result = Double.valueOf(number); if ((Double.isNaN(result) || Double.isInfinite(result))) throw new IllegalArgumentException("JSON数字无效"); return result;
    }
    private boolean take(char c) { if (at < input.length() && input.charAt(at) == c) { at++; return true; } return false; }
    private String string() {
        if (!take('"')) throw new IllegalArgumentException("JSON字符串无效"); StringBuilder out = new StringBuilder();
        while (at < input.length()) {
            char c = input.charAt(at++); if (c == '"') return out.toString();
            if (c < 32) throw new IllegalArgumentException("JSON字符串无效");
            if (c != '\\') { out.append(c); continue; }
            if (at >= input.length()) throw new IllegalArgumentException("JSON转义无效"); c = input.charAt(at++);
            switch (c) {
                case '"': case '\\': case '/': out.append(c); break;
                case 'n': out.append('\n'); break; case 'r': out.append('\r'); break; case 't': out.append('\t'); break;
                case 'b': out.append('\b'); break; case 'f': out.append('\f'); break;
                case 'u':
                    if (at + 4 > input.length()) throw new IllegalArgumentException("JSON转义无效");
                    try { out.append((char) Integer.parseInt(input.substring(at, at + 4), 16)); } catch (NumberFormatException error) { throw new IllegalArgumentException("JSON转义无效"); }
                    at += 4; break;
                default: throw new IllegalArgumentException("JSON转义无效");
            }
        }
        throw new IllegalArgumentException("JSON字符串无效");
    }
    public static Object path(Object root, String path) {
        if (path == null || path.isEmpty() || path.equals("$")) return root;
        String key = path.startsWith("$.") ? path.substring(2) : path;
        key = key.replace('[', '.').replace("]", "");
        Object current = root;
        for (String part : key.split("\\.")) {
            if (part.isEmpty()) continue;
            if (current instanceof Map) current = ((Map<?, ?>) current).get(part);
            else if (current instanceof List) {
                try { int index = Integer.parseInt(part); current = index >= 0 && index < ((List<?>) current).size() ? ((List<?>) current).get(index) : null; }
                catch (NumberFormatException error) { return null; }
            } else return null;
        }
        return current;
    }
    public static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return quote((String) value);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        StringBuilder result = new StringBuilder();
        if (value instanceof Map) {
            result.append('{'); boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) result.append(','); first = false;
                result.append(quote(String.valueOf(entry.getKey()))).append(':').append(stringify(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof List) {
            result.append('['); boolean first = true;
            for (Object item : (List<?>) value) { if (!first) result.append(','); first = false; result.append(stringify(item)); }
            return result.append(']').toString();
        }
        throw new IllegalArgumentException("JSON类型无效");
    }
    public static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n"); else if (c == '\r') out.append("\\r"); else if (c == '\t') out.append("\\t");
            else if (c < 32) out.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}

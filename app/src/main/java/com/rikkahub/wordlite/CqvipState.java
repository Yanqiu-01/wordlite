package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * 维普检索页的题名不在 HTML 里：服务端把 {@code <a class="title">} 印成空标签，真题名由浏览器脚本从
 * {@code window.__NUXT__} 这份前端状态里填（实测一次检索 20 条记录 = 20 个空题名标签，摘要与作者反倒是印出来的）。
 * 2026-10-08 量过的四条替代路都不通，于是只解这一页：
 * ① 前端真正调的 {@code POST https://apiv3.cqvip.com/search}（带 cqvip-sign/appId/signature）对本机整站 503；
 * ② qikan.cqvip.com 的检索页与文献页 412，回的是 {@code $_ts} 那段 JS 挑战；
 * ③ {@code www.cqvip.com/doc/<id>} 不带 sign 时 302 到 /?serve=...&appCode=101，落地页 {@code <title>} 就是"维普网"；
 * ④ 检索页里那条带 sign 的 URL 是 200，但详情页状态里的 abstr 与检索页逐字相同（452 字对 452 字），
 *    多花一次请求换不到一个字，而 sign 每次检索都换、expireTime 两年，存进报告迟早点不开。
 *
 * <p>那份状态是 Nuxt 的 {@code (function(a,b,c,...){ return {...} }(v1,v2,...))}：字符串整个堆在末尾的实参里，
 * 函数体内只剩一个字母变量名。所以先按顶层逗号把实参切开、建"变量名 → 字面量"表，再逐条读
 * listData.records 里的题名、出处与关键词。任何一步对不上就交回空表，题名照旧署成"《刊名》 年 期"，
 * 检索本身不受影响。
 */
final class CqvipState {
    /** 一条记录里本机用得上的三项：真题名、出处（刊名或培养单位）、关键词。 */
    static final class Record {
        String title = "", venue = "", keywords = "";
    }

    private static final String STATE = "window.__NUXT__=", RECORDS = "records:[";
    /** 一次检索最多 50 条（Limits.perEngine 的上限），多出来的对象不解。 */
    private static final int MAX_RECORDS = 50;
    /** 实测一篇 3~6 个关键词，取到八个为止。 */
    private static final int MAX_KEYWORDS = 8;

    private CqvipState() { }

    /** 键是记录自己的文献号（如 {@code 7105328027}），与 HTML 里 {@code /doc/journal/<号>} 的尾段是同一个东西。 */
    static LinkedHashMap<String, Record> parse(String html) {
        LinkedHashMap<String, Record> out = new LinkedHashMap<String, Record>();
        int at = html == null ? -1 : html.indexOf(STATE);
        int params = at < 0 ? -1 : html.indexOf("function(", at);
        int paramsEnd = params < 0 ? -1 : html.indexOf(')', params);
        int body = paramsEnd < 0 ? -1 : html.indexOf('{', paramsEnd);
        int bodyEnd = body < 0 ? -1 : matching(html, body, '{', '}');
        int args = bodyEnd < 0 ? -1 : html.indexOf('(', bodyEnd);
        int argsEnd = args < 0 ? -1 : matching(html, args, '(', ')');
        int records = argsEnd < 0 ? -1 : html.indexOf(RECORDS, body);
        if (records < 0) return out;
        HashMap<String, String> vars = literals(html.substring(params + 9, paramsEnd),
                html.substring(args + 1, argsEnd));
        for (String item : objects(html, records + RECORDS.length())) {
            Record record = new Record();
            record.title = value(vars, item, "title");
            if (record.title.isEmpty()) record.title = value(vars, item, "name");
            record.venue = venue(vars, item);
            record.keywords = keywords(vars, item);
            String id = value(vars, item, "id");
            if (id.isEmpty()) id = value(vars, item, "lngid");
            if (!id.isEmpty() && !(record.title.isEmpty() && record.keywords.isEmpty())) out.put(id, record);
        }
        return out;
    }

    /** HTML 侧的文献号带着 journal/ 或 degree/ 前缀，状态里没有，所以取尾段再问。 */
    static Record find(LinkedHashMap<String, Record> state, String document) {
        if (state == null || state.isEmpty() || document == null || document.isEmpty()) return null;
        int slash = document.lastIndexOf('/');
        return state.get(slash < 0 ? document : document.substring(slash + 1));
    }

    /** 出处：期刊取刊名；学位论文没有期刊链接，退到培养单位，与 HTML 那一路同样的取舍。 */
    private static String venue(HashMap<String, String> vars, String item) {
        String journal = value(vars, body(raw(item, "journalInfo")), "name");
        if (!journal.isEmpty()) return journal;
        for (String organ : objects(raw(item, "organInfo"), 0)) {
            String name = value(vars, organ, "name");
            if (!name.isEmpty()) return name;
        }
        return "";
    }

    private static String keywords(HashMap<String, String> vars, String item) {
        StringBuilder out = new StringBuilder();
        int taken = 0;
        for (String word : objects(raw(item, "keywordInfo"), 0)) {
            String name = value(vars, word, "name");
            if (name.isEmpty() || out.indexOf(name) >= 0) continue;
            if (out.length() > 0) out.append("; ");
            out.append(name);
            if (++taken >= MAX_KEYWORDS) break;
        }
        return out.toString();
    }

    /** 形参名按位置对上末尾实参。只留字符串与数字：{} 与 Array(6) 这类实参本来就没有可比对内容。 */
    private static HashMap<String, String> literals(String names, String values) {
        HashMap<String, String> out = new HashMap<String, String>();
        String[] formals = names.split(",");
        ArrayList<String> actuals = splitTop(values);
        for (int i = 0; i < formals.length && i < actuals.size(); i++) {
            String name = formals[i].trim(), value = actuals.get(i);
            if (!name.isEmpty() && !value.isEmpty() && (value.charAt(0) == '"' || digit(value.charAt(0))))
                out.put(name, value);
        }
        return out;
    }

    /** 某个键的字面量取值：字符串解转义，字母变量回查实参表，对象/数组/null 一律算空。 */
    private static String value(HashMap<String, String> vars, String item, String key) {
        String token = raw(item, key);
        if (token == null || token.isEmpty()) return "";
        char first = token.charAt(0);
        if (first == '"') return unescape(token);
        return isIdentifier(token) ? unescape(vars.get(token)) : "";
    }

    /** 嵌套的值原样带着花括号，要读它里面的键得先把这一层剥掉。 */
    private static String body(String object) {
        String text = object == null ? "" : object.trim();
        return text.length() > 1 && text.charAt(0) == '{' && text.charAt(text.length() - 1) == '}'
                ? text.substring(1, text.length() - 1) : text;
    }

    /** 顶层（不钻嵌套）某个键的值原文；同名的内层键在 depth > 0 处，天然被跳过。 */
    private static String raw(String item, String key) {
        if (item == null || item.isEmpty()) return null;
        int depth = 0;
        for (int i = 0; i < item.length(); i++) {
            char c = item.charAt(i);
            if (c == '"') { i = stringEnd(item, i); continue; }
            if (c == '(' || c == '[' || c == '{') { depth++; continue; }
            if (c == ')' || c == ']' || c == '}') { depth--; continue; }
            if (depth != 0 || !Character.isJavaIdentifierStart(c)) continue;
            int name = i;
            while (i < item.length() && Character.isJavaIdentifierPart(item.charAt(i))) i++;
            int colon = i;
            while (colon < item.length() && item.charAt(colon) == ' ') colon++;
            if (colon >= item.length() || item.charAt(colon) != ':') { i--; continue; }
            if (item.substring(name, i).equals(key)) return trim(item.substring(colon + 1, topEnd(item, colon + 1)));
            i = topEnd(item, colon + 1) - 1;
        }
        return null;
    }

    /** 从 {@code records:[} 之后逐个取出对象体，遇到数组收尾就停。 */
    private static ArrayList<String> objects(String text, int from) {
        ArrayList<String> out = new ArrayList<String>();
        if (text == null) return out;
        int i = Math.max(0, from);
        while (i < text.length() && out.size() < MAX_RECORDS) {
            char c = text.charAt(i);
            if (c == ']') break;
            if (c == '{') {
                int end = matching(text, i, '{', '}');
                if (end < 0) break;
                out.add(text.substring(i + 1, end));
                i = end + 1;
                continue;
            }
            i++;
        }
        return out;
    }

    private static ArrayList<String> splitTop(String text) {
        ArrayList<String> out = new ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { i = stringEnd(text, i); continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) { out.add(text.substring(start, i).trim()); start = i + 1; }
        }
        out.add(text.substring(start).trim());
        return out;
    }

    /** 值的尽头：值可以是对象或数组，所以要跳到配平之后的第一个顶层逗号。 */
    private static int topEnd(String text, int from) {
        int depth = 0;
        for (int i = Math.max(0, from); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { i = stringEnd(text, i); continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return text.length();
    }

    private static int matching(String text, int open, char left, char right) {
        int depth = 0;
        for (int i = Math.max(0, open); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { i = stringEnd(text, i); continue; }
            if (c == left) depth++;
            else if (c == right && --depth == 0) return i;
        }
        return -1;
    }

    private static int stringEnd(String text, int open) {
        for (int i = open + 1; i < text.length(); i++) {
            if (text.charAt(i) == '\\') i++;
            else if (text.charAt(i) == '"') return i;
        }
        return text.length() - 1;
    }

    /** 状态里的字符串带着 \u002F 这类转义，题名里的斜杠与引号靠它还原。 */
    private static String unescape(String token) {
        if (token == null || token.length() < 2 || token.charAt(0) != '"') return "";
        int end = token.length() - 1;
        if (token.charAt(end) != '"') return "";
        StringBuilder out = new StringBuilder(end - 1);
        for (int i = 1; i < end; i++) {
            char c = token.charAt(i);
            if (c != '\\' || i + 1 >= end) { out.append(c); continue; }
            char next = token.charAt(++i);
            if (next == 'u' && i + 4 < end) {
                try {
                    out.append((char) Integer.parseInt(token.substring(i + 1, i + 5), 16));
                    i += 4;
                    continue;
                } catch (NumberFormatException ignored) { out.append(next); continue; }
            }
            out.append(next == 'n' ? ' ' : next);
        }
        return trim(out.toString());
    }

    private static boolean isIdentifier(String token) {
        if (token.isEmpty() || !Character.isJavaIdentifierStart(token.charAt(0))) return false;
        for (int i = 1; i < token.length(); i++) if (!Character.isJavaIdentifierPart(token.charAt(i))) return false;
        return true;
    }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    /** 首尾空白与不换行空格都得去掉：题名里留着这些，标题指纹就对不上别的库了。 */
    private static String trim(String value) {
        return value.replace('\u00a0', ' ').replaceAll("(?s)^\\s+|\\s+$", "");
    }
}

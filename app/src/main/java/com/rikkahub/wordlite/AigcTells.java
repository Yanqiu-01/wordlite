package com.rikkahub.wordlite;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外部"AI 味"规则表（{@code assets/aigc/ai-tells.tsv}，humanizer-zh-academic@MIT (c) 2025
 * redbaronyyyyy-eng）的读取与执行层。这张表是别人给的判据与改法，不是我们自己凑的，
 * 所以它的本职有两件：<b>列证据</b>与<b>给降重工单</b>（cap 硬上限 + hint 改法）。
 *
 * <p><b>它不参与打分。</b>2026-10-12 这一版在带标注语料上逐条量过（复现
 * {@code py tools/aigc-style-rules.py audit}），随包那 18 条里 {@code verified} 全为 0；
 * 更关键的是套话类判据在<b>论文正文</b>里两侧都是 0 命中，只在<b>人类已发表论文摘要</b>里密集出现
 * （真人摘要 T03 45.9 次/千字）。也就是说这条轴量的是语域，不是来源：拿它当分数会冤枉真人摘要、
 * 放过机器正文。所以这里读到的每一条都只进证据列表与降重工单，不进句分、不进档位。
 * 界面上必须跟着说一句"真人正式摘要里也常见，不能当判决"（{@link #CAVEAT}）。
 *
 * <p>表里没有就什么都不画：{@link #current()} 允许是 null（主机回归不装表、老包缺文件），
 * 那种场合本机检测照样出指数，只是少了套话那几行证据与工单。
 */
public final class AigcTells {
    /** 随包文件。 */
    public static final String FILE = "aigc/ai-tells.tsv";
    public static final int MAX_ROWS = 512;
    /** 单条判据作用范围内截断到的字符数，与 Python 侧 {@code u[:2000]} 同值。 */
    static final int UNIT_LIMIT = 2000;
    /** 词表类证据必须跟着的那一句。 */
    public static final String CAVEAT = "上面这些是套话与词表类线索：真人正式摘要里也常见，只作改写线索，不能当判决。";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static volatile AigcTells active;
    private static boolean loadAttempted;

    /** 表里的一行。读法与 {@code tools/aigc-style-rules.py} 导出的列一一对应。 */
    public static final class Rule {
        public String id = "", name = "", scope = "", kind = "", spec = "", cap = "", hint = "";
        public String mode = "", note = "", source = "";
        public double humanRate = Double.NaN, machineRate = Double.NaN, ratio = Double.NaN;
        public double gain = Double.NaN, loss = Double.NaN, p = Double.NaN;
        public boolean verified;
        Pattern pattern;

        /** 词表类条目一律不计分：只有清单里 verified=1 的才有资格，今天 18 条一条都不是。 */
        public boolean scores() {
            return verified;
        }

        /** 上限那一栏的空值。 */
        public String capOr(String fallback) {
            return cap.trim().length() == 0 ? fallback : cap.trim();
        }
    }

    /** 一条命中：落在正文哪个区间、是哪条判据、该怎么改。 */
    public static final class Hit {
        public String ruleId = "", name = "", mode = "", cap = "", hint = "";
        public int start, end;
        public boolean paragraphScope;

        /** 工单上"最多保留几处"那一栏，空值给个说法。 */
        public String capOr(String fallback) {
            return cap == null || cap.trim().length() == 0 ? fallback : cap.trim();
        }

        public Hit() { }

        public Hit(Rule rule, int start, int end) {
            this.ruleId = rule.id;
            this.name = rule.name;
            this.mode = rule.mode;
            this.cap = rule.cap;
            this.hint = rule.hint;
            this.start = start;
            this.end = end;
            this.paragraphScope = "para".equals(rule.scope) || "doc".equals(rule.scope);
        }
    }

    private final ArrayList<Rule> rules = new ArrayList<Rule>();

    private AigcTells() { }

    public static AigcTells current() {
        return active;
    }

    /** 装载一次（app 侧从 assets 开流，主机回归从文件开流，两边同一个入口）。 */
    public static AigcTells install(InputStream table) throws IOException {
        AigcTells loaded = new AigcTells();
        loaded.read(table);
        active = loaded;
        return loaded;
    }

    /** 读不到只当"没有这张表"：检测不许因为一张证据表缺失而中断。 */
    public static void loadFromAssets(android.content.Context context) {
        if (loadAttempted || active != null) return;
        loadAttempted = true;
        try {
            install(context.getAssets().open(FILE));
        } catch (Exception missing) {
            active = null;
            android.util.Log.w("WordliteAigc", "ai-tells table unreadable: " + missing);
        }
    }

    /** 主机回归用：让下一次 loadFromAssets 真的重读。 */
    public static void resetLoadAttempt() {
        loadAttempted = false;
    }

    public static void uninstall() {
        active = null;
    }

    public ArrayList<Rule> rules() {
        return rules;
    }

    /** 这张表有没有参与打分的条目（今天恒 false）。 */
    public boolean anyScoring() {
        for (int i = 0; i < rules.size(); i++) if (rules.get(i).scores()) return true;
        return false;
    }

    /** 按 id 找一行；取实测密度用（出处行要读 P05 那两个人数）。 */
    public Rule byId(String id) {
        for (int i = 0; i < rules.size(); i++) if (rules.get(i).id.equals(id)) return rules.get(i);
        return null;
    }

    private void read(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
        String line;
        String[] header = null;
        int rows = 0;
        while ((line = r.readLine()) != null) {
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            if (++rows > MAX_ROWS) throw new IOException("规则表行数超过上限 " + MAX_ROWS);
            String[] parts = line.split("\t", -1);
            if (header == null) {
                header = parts;
                continue;
            }
            Rule rule = new Rule();
            for (int i = 0; i < parts.length && i < header.length; i++) bind(rule, header[i], parts[i]);
            if (rule.id.length() == 0) continue;
            if ("regex".equals(rule.kind) && rule.spec.length() > 0) {
                // 判据是按原稿写的（全角标点），跑在 normalize 之后的文本上，所以规格字符串也过一遍
                // normalize：全角折半角、"，、"变 ","、"。"变 "."，与待匹配文本同一把尺。
                try {
                    rule.pattern = Pattern.compile(TextCorpus.normalize(rule.spec));
                } catch (RuntimeException bad) {
                    rule.pattern = null;      // 翻译不过来的表达式就不执行，只当说明留着
                }
            }
            rules.add(rule);
        }
        if (rules.isEmpty()) throw new IOException("规则表一行都没读到");
    }

    private static void bind(Rule rule, String column, String value) {
        String v = value == null ? "" : value.trim();
        if ("id".equals(column)) rule.id = v;
        else if ("name".equals(column)) rule.name = v;
        else if ("scope".equals(column)) rule.scope = v;
        else if ("kind".equals(column)) rule.kind = v;
        else if ("spec".equals(column)) rule.spec = value == null ? "" : value;
        else if ("cap".equals(column)) rule.cap = v;
        else if ("hint".equals(column)) rule.hint = v;
        else if ("mode".equals(column)) rule.mode = v;
        else if ("note".equals(column)) rule.note = v;
        else if ("human_rate".equals(column)) rule.humanRate = num(v);
        else if ("machine_rate".equals(column)) rule.machineRate = num(v);
        else if ("ratio".equals(column)) rule.ratio = num(v);
        else if ("gain".equals(column)) rule.gain = num(v);
        else if ("loss".equals(column)) rule.loss = num(v);
        else if ("p".equals(column)) rule.p = num(v);
        else if ("verified".equals(column)) rule.verified = "1".equals(v) || "true".equals(v);
        else if ("source".equals(column)) rule.source = v;
    }

    private static double num(String v) {
        if (v.length() == 0 || "nan".equalsIgnoreCase(v) || "None".equals(v)) return Double.NaN;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    // ---------------------------------------------------------------- 执行（只出证据）

    /** 一句里的命中。unit 用 normalize 之后的原文区间，偏移原样带回。 */
    public ArrayList<Hit> sentenceHits(String norm, int from, int to) {
        return scan(norm, from, to, false);
    }

    /** 一个自然段里的命中（scope=para 的那些条）。 */
    public ArrayList<Hit> paragraphHits(String norm, int from, int to) {
        return scan(norm, from, to, true);
    }

    private ArrayList<Hit> scan(String norm, int from, int to, boolean paragraphs) {
        ArrayList<Hit> out = new ArrayList<Hit>();
        if (norm == null || to <= from) return out;
        int stop = Math.min(to, from + UNIT_LIMIT);
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            boolean paraScope = "para".equals(rule.scope) || "doc".equals(rule.scope);
            if (paraScope != paragraphs) continue;
            if (matches(rule, norm, from, stop)) out.add(new Hit(rule, from, Math.min(to, stop)));
        }
        return out;
    }

    private static boolean matches(Rule rule, String norm, int from, int to) {
        if ("regex".equals(rule.kind)) {
            if (rule.pattern == null) return false;
            Matcher m = rule.pattern.matcher(norm.subSequence(from, to));
            return m.find();
        }
        if ("struct".equals(rule.kind)) return struct(rule.id, norm, from, to);
        return false;
    }

    /**
     * 结构类判据的 Java 实现。逐条对齐 Python 侧 {@code tools/aigc-style-rules.py} 的 STRUCT 表；
     * 差别只有一处：Python 跑在原稿上，这里跑在 normalize 之后的文本上，所以全角标点在这一侧是半角。
     * 表里没有的 id 一律 false——宁可少列一条证据，也不自己发明判据。
     */
    static boolean struct(String id, String norm, int from, int to) {
        if ("T06".equals(id)) return countWords(norm, from, to,
                new String[]{"可能", "在一定程度上", "一定程度上", "潜在", "某种程度", "或许", "大概"}) >= 2;
        if ("T10".equals(id)) return threeParallel(norm, from, to);
        if ("T12".equals(id)) return chainedLe(norm, from, to);
        if ("P03".equals(id)) return count(norm, from, to, ":") >= 3;        // 「：」normalize 之后是 ':'
        if ("P04".equals(id)) return count(norm, from, to, "--") >= 3;       // 「——」normalize 之后是 "--"
        return false;
    }

    private static int countWords(String norm, int from, int to, String[] words) {
        String text = norm.substring(from, to);
        int hits = 0;
        for (int i = 0; i < words.length; i++) {
            int at = 0;
            while ((at = text.indexOf(words[i], at)) >= 0) {
                hits++;
                at += words[i].length();
            }
        }
        return hits;
    }

    private static int count(String text, int from, int to, String token) {
        int hits = 0;
        int at = from;
        while (token.length() > 0 && (at = text.indexOf(token, at)) >= 0 && at < to) {
            hits++;
            at += token.length();
        }
        return hits;
    }

    /** 三个连续并列项等长等重（末字或前两字相同），与 Python 的 st_three_parallel 同式。 */
    static boolean threeParallel(String norm, int from, int to) {
        String text = norm.substring(from, to);
        String[] separators = {"、", ","};      // normalize 之后「、」已经并成 ','，两个都留着以防口径再变
        for (int s = 0; s < separators.length; s++) {
            ArrayList<String> parts = new ArrayList<String>();
            int cursor = 0;
            String sep = separators[s];
            while (true) {
                int at = text.indexOf(sep, cursor);
                String piece = (at < 0 ? text.substring(cursor) : text.substring(cursor, at)).trim();
                if (piece.length() >= 2) parts.add(piece);
                if (at < 0) break;
                cursor = at + sep.length();
            }
            for (int i = 0; i + 2 < parts.size(); i++) {
                int a = parts.get(i).length(), b = parts.get(i + 1).length(), c = parts.get(i + 2).length();
                int max = Math.max(a, Math.max(b, c));
                int min = Math.min(a, Math.min(b, c));
                if (max > 1.3 * Math.max(2, min) || max > 14) continue;
                String x = parts.get(i), y = parts.get(i + 1), z = parts.get(i + 2);
                boolean sameTail = x.charAt(x.length() - 1) == y.charAt(y.length() - 1)
                        && y.charAt(y.length() - 1) == z.charAt(z.length() - 1);
                boolean sameHead = x.regionMatches(0, y, 0, 2) && y.regionMatches(0, z, 0, 2)
                        && x.length() >= 2 && y.length() >= 2 && z.length() >= 2;
                if (sameTail || sameHead) return true;
            }
        }
        return false;
    }

    /** 三处以上连续的"……了"短句串，与 Python 的 st_chained_le 同式。 */
    static boolean chainedLe(String norm, int from, int to) {
        String text = norm.substring(from, to);
        String[] pieces = text.split("[,，、;；]");
        int run = 0, best = 0;
        for (int i = 0; i < pieces.length; i++) {
            String piece = pieces[i].trim();
            if (piece.endsWith("了") && piece.length() >= 2 && piece.length() <= 10) {
                run++;
                if (run > best) best = run;
            } else {
                run = 0;
            }
        }
        return best >= 3;
    }

    // ---------------------------------------------------------------- 降重工单与出处

    /** 把命中按判据归并成工单行：同一条只出一行，带上命中处数、硬上限与改法。 */
    public static ArrayList<Hit> workOrder(ArrayList<Hit> hits) {
        ArrayList<Hit> out = new ArrayList<Hit>();
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            int known = -1;
            for (int k = 0; k < out.size(); k++)
                if (out.get(k).ruleId.equals(hit.ruleId)) known = k;
            if (known < 0) out.add(hit);
        }
        return out;
    }

    /** 出处行里给这张表的一句话：哪一版、多少条、几条参与打分。 */
    public static String describe() {
        AigcTells table = active;
        if (table == null) return "外部套话规则表未装载";
        int scoring = 0;
        for (int i = 0; i < table.rules.size(); i++) if (table.rules.get(i).scores()) scoring++;
        return "外部套话规则表 " + table.rules.size() + " 条（humanizer-zh-academic@MIT），本机实测后 "
                + scoring + " 条够格计分，其余只列证据与改法";
    }
}

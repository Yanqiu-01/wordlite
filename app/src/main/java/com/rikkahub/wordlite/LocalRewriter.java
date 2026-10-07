package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic offline rewrite rules; mask islands, numbers, units and terms always survive verbatim. */
public final class LocalRewriter {
    public static final class Option {
        public String text = "";
        public String strategy = "";
    }

    /** One private-use character stands for one protected span so no rule can split or reorder it. */
    private static final char PH_BASE = '\uE000';
    private static final int PH_CAPACITY = 0x800;
    private static final Pattern TOKENS = Pattern.compile("\u27e6WL_[^\u27e7\\n]*\u27e7");
    /** Numbers with units, alphanumeric model codes, citation brackets and greek symbols never move. */
    private static final Pattern ISLANDS = Pattern.compile(
            "[+-]?[0-9\uFF10-\uFF19]+(?:[.\uFF0E][0-9\uFF10-\uFF19]+)?"
                    + "(?:\\s*(?:%|\uFF05|\u2103|\u00B0[CFK]|K|MPa|GPa|kPa|Pa|N\u00B7m|N/m|mm|cm|nm|\u03BCm|um|dm|km|kg|mg|Hz|kHz|MHz|GHz|kV|mV|V|mA|uA|A|kW|mW|Wh|J|kJ|eV|mol|mmol|mL|uL|L|g|ms|us|ns|min|h|d|s|rad)?(?![0-9A-Za-z%]))"
                    + "|[0-9\uFF10-\uFF19]+(?:[.\uFF0E][0-9\uFF10-\uFF19]+)?(?:\\s*[-\u2013~\uFF5E]\\s*[0-9\uFF10-\uFF19]+(?:[.\uFF0E][0-9\uFF10-\uFF19]+)?)?"
                    + "(?:\\s*(?:%|\uFF05|\u2103|\u00B0[CFK]|MPa|GPa|kPa|Pa|mm|cm|nm|\u03BCm|um|kg|mg|Hz|kHz|MHz|V|A|mL|min|h|s))?(?![0-9A-Za-z%])"
                    + "|[A-Za-z]{1,10}(?:-[0-9]+)+[A-Za-z0-9-]*"
                    + "|[A-Za-z]+[0-9]+[A-Za-z0-9]*(?:-[A-Za-z0-9]+)*"
                    + "|[0-9]+[A-Za-z]+[A-Za-z0-9-]*"
                    + "|\\[[0-9\uFF10-\uFF19]+(?:\\s*[-\u2013\u2014,\uFF0C\u3001;\uFF1B]\\s*[0-9\uFF10-\uFF19]+)*\\]"
                    + "|[\\p{InGreek}][A-Za-z0-9]*");
    /** Object span: no sentence punctuation, no negation or tense adverbs, no second voice marker. */
    private static final String SPAN_BAN = "\u3002\uFF01\uFF1F\uFF1B\uFF0C\u3001\uFF1A\\n\\t \u3000"
            + "0-9A-Za-z\u27e6\u27e7"
            + "\u4e0d\u6ca1\u672a\u66fe\u5c06\u8981\u4f1a\u80fd\u53ef\u884c\u8fdb\u88ab\u628a\u6240\u800c\u5374\u4f46\u5e76\u4e14\u4e5f\u5f88\u66f4\u4e8e";
    private static final String HEAD_BAN = "\u534a\u51e0\u591a\u4e24\u4e09\u56db\u6570\u6bcf\u5404\u4e4b\u8fd9\u90a3\u5176\u5df2\u4e00\u4e8c\u8be5\u672c\u6b64\u5c31\u90fd\u4f1a\u6700\u8fdc\u7565\u8f83\u7a0d\u663e\u660e\u6781\u4ea6\u5982\u679c\u56e0\u4e3a";
    private static final String SPAN_CHAR = "[^" + SPAN_BAN + "]";
    private static final String SPAN = SPAN_CHAR + "{1,10}?";
    private static final String HEAD_SPAN = "[^" + SPAN_BAN + HEAD_BAN + "]" + SPAN_CHAR + "{0,11}?";
    private static final String CLAUSE_HEAD = "\n\r\u3002\uFF01\uFF1F\uFF1B\uFF1A\uFF0C\u3001\t\uFF08\u201C\u2018\u3010\u300A[";

    private LocalRewriter() { }

    private interface Rule {
        String apply(String source);
    }

    private static final class Entry {
        final String strategy;
        final Rule rule;
        Entry(String strategy, Rule rule) { this.strategy = strategy; this.rule = rule; }
    }

    /** One rule, one match point: the first legal match is rewritten, everything else stays put. */
    private static final class Regex implements Rule {
        private final Pattern pattern;
        private final String template;
        private final boolean clauseStart;
        private final Pattern rejects;
        Regex(Pattern pattern, String template, boolean clauseStart, String rejects) {
            this.pattern = pattern;
            this.template = template;
            this.clauseStart = clauseStart;
            this.rejects = rejects.isEmpty() ? null
                    : Pattern.compile("(?i)\\b(?:" + rejects + ")\\b");
        }
        Regex(String regex, String template) { this(Pattern.compile(regex), template, false, ""); }
        Regex(String regex, String template, boolean clauseStart) { this(Pattern.compile(regex), template, clauseStart, ""); }
        Regex(String regex, String template, boolean clauseStart, String rejects) { this(Pattern.compile(regex), template, clauseStart, rejects); }
        public String apply(String source) {
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) {
                if (clauseStart && !atClauseStart(source, matcher.start())) continue;
                String replacement = expand(matcher, template);
                if (replacement == null || !sameIslands(matcher.group(), replacement)) return null;
                if (rejected(matcher.group())) return null;
                return source.substring(0, matcher.start()) + replacement + source.substring(matcher.end());
            }
            return null;
        }
        private boolean rejected(String matched) {
            return rejects != null && rejects.matcher(matched).find();
        }
    }

    private static boolean atClauseStart(String text, int at) {
        return at == 0 || CLAUSE_HEAD.indexOf(text.charAt(at - 1)) >= 0;
    }

    private static String marks(String text) {
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= PH_BASE && c < PH_BASE + PH_CAPACITY) marks.append(c);
        }
        return marks.toString();
    }

    private static boolean sameIslands(String before, String after) {
        return marks(before).equals(marks(after));
    }

    /** Templates use $n, $un (first letter upper case) and $ln (first letter lower case). */
    private static String expand(Matcher matcher, String template) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c != '$') {
                out.append(c);
                continue;
            }
            int at = i + 1;
            boolean upper = false;
            boolean lower = false;
            if (at < template.length() && (template.charAt(at) == 'u' || template.charAt(at) == 'l')) {
                upper = template.charAt(at) == 'u';
                lower = !upper;
                at++;
            }
            if (at >= template.length() || template.charAt(at) < '0' || template.charAt(at) > '9') {
                out.append(c);
                continue;
            }
            int index = template.charAt(at) - '0';
            if (index > matcher.groupCount()) return null;
            String group = matcher.group(index);
            if (group == null) return null;
            if (upper) group = capitalize(group, true);
            if (lower) group = capitalize(group, false);
            out.append(group);
            i = at;
        }
        return out.toString();
    }

    private static String capitalize(String text, boolean upper) {
        if (text.isEmpty()) return text;
        char first = upper ? Character.toUpperCase(text.charAt(0)) : Character.toLowerCase(text.charAt(0));
        return first + text.substring(1);
    }

    private static String alternate(String[] words) {
        String[] sorted = words.clone();
        Arrays.sort(sorted, new Comparator<String>() {
            public int compare(String a, String b) { return b.length() - a.length(); }
        });
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sorted.length; i++) {
            if (i > 0) out.append('|');
            out.append(sorted[i]);
        }
        return out.toString();
    }

    private static String literal(String from, String banBefore, String banAfter) {
        StringBuilder out = new StringBuilder();
        if (!banBefore.isEmpty()) out.append("(?<![").append(banBefore).append("])");
        out.append(Pattern.quote(from));
        if (!banAfter.isEmpty()) out.append("(?![").append(banAfter).append("])");
        return out.toString();
    }

    /** English keyword with hard letter/digit edges so no inflected form is cut in half. */
    private static String phrase(String words) {
        String[] parts = words.split(" ");
        StringBuilder out = new StringBuilder("(?<![A-Za-z0-9])");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append("\\s+");
            out.append(Pattern.quote(parts[i]));
        }
        return out.append("(?![A-Za-z0-9])").toString();
    }
    /** Verbs that read naturally as "对X进行了V". */
    private static final String[] DUI_VERBS = {
        "加热", "保温", "冷却", "观察", "分析", "检测", "测试", "测量", "测定", "表征", "对比", "比较",
        "优化", "改善", "调整", "调节", "控制", "评估", "评价", "验证", "仿真", "模拟", "计算", "讨论",
        "总结", "清洗", "搅拌", "研磨", "抛光", "腐蚀", "干燥", "称量", "加载", "淬火", "退火", "时效",
        "焊接", "钎焊", "喷涂", "改性", "筛选", "统计", "拟合", "说明", "制备", "合成", "去除", "消除", "处理",
    };
    /** Verbs that also carry an object in "V了X". */
    private static final String[] LE_VERBS = {
        "加热", "保温", "冷却", "观察", "分析", "检测", "测试", "测量", "测定", "表征", "对比", "比较",
        "优化", "改善", "调整", "调节", "控制", "评估", "评价", "验证", "仿真", "模拟", "计算", "讨论",
        "总结", "清洗", "搅拌", "研磨", "抛光", "腐蚀", "干燥", "称量", "加载", "淬火", "退火", "时效",
        "焊接", "钎焊", "喷涂", "改性", "筛选", "统计", "拟合", "说明", "制备", "合成", "去除", "消除", "处理",
    };
    /** Verbs allowed after a 把/将 object. */
    private static final String[] BA_VERBS = {
        "加热", "保温", "冷却", "观察", "分析", "检测", "测试", "测量", "测定", "表征", "对比", "比较",
        "优化", "改善", "调整", "调节", "控制", "评估", "评价", "验证", "仿真", "模拟", "计算", "讨论",
        "清洗", "搅拌", "研磨", "抛光", "腐蚀", "干燥", "称量", "加载", "淬火", "退火", "时效", "焊接",
        "钎焊", "喷涂", "改性", "筛选", "统计", "拟合", "制备", "合成", "去除", "消除", "处理", "提高",
        "降低", "提升", "增强", "保持", "维持", "固定", "放入", "取出", "置于", "置入", "分成", "分为",
        "看作", "视为", "划分",
    };
    /** Verbs that keep sense when 被 is dropped for a 将 clause. */
    /** Only deliberate lab operations: "将X加热" keeps a passive reading, "将X氧化" would not. */
    private static final String[] BEI_VERBS = {
        "加热", "保温", "冷却", "清洗", "搅拌", "研磨", "抛光", "腐蚀", "干燥", "称量", "加载", "淬火",
        "退火", "时效", "焊接", "钎焊", "喷涂", "改性", "制备", "合成", "去除", "消除", "处理", "观察",
        "检测", "测试", "测量", "测定", "表征", "模拟", "仿真", "计算", "统计", "筛选", "控制", "调节", "优化",
    };
    /** Verb heads usable after an adverb or after "通过X来". */
    private static final String[] VERB_ANY = {
        "加热", "保温", "冷却", "观察", "分析", "检测", "测试", "测量", "测定", "表征", "对比", "比较",
        "优化", "改善", "调整", "调节", "控制", "评估", "验证", "仿真", "模拟", "计算", "讨论", "制备",
        "合成", "处理", "提高", "降低", "提升", "增强", "抑制", "促进", "细化", "实现", "完成", "减少",
        "增加", "扩展", "发展", "应用", "保持", "维持", "搅拌", "研磨", "抛光", "干燥", "统计", "说明",
    };
    /** Adverbs that stay grammatical when 地 is dropped. */
    private static final String[] ADVERBS = {
        "显著", "明显", "大幅", "有效", "缓慢", "快速", "均匀", "充分", "仔细", "精确", "顺利", "稳定",
        "逐渐", "持续", "分别", "依次", "同步", "单独", "迅速", "直接", "间接", "有力", "可靠", "灵活",
        "系统", "全面", "深入",
    };
    /** Directional comparatives: swapping the two sides and the marker keeps the meaning exact. */
    private static final String[][] COMPARATIVES = {
        { "高于", "低于" }, { "大于", "小于" }, { "强于", "弱于" }, { "多于", "少于" },
        { "快于", "慢于" }, { "长于", "短于" }, { "优于", "劣于" }, { "早于", "晚于" },
    };
    /** strategy, from, to, banned char before, banned char after. */
    private static final String[][] LEXICAL = {
        { "连接词", "由于", "因为", "", "" },
        { "连接词", "因为", "由于", "所", "" },
        { "连接词", "鉴于", "由于", "", "" },
        { "连接词", "因此", "所以", "", "" },
        { "连接词", "所以", "因而", "", "，,。；;" },
        { "连接词", "因而", "因此", "", "" },
        { "连接词", "并且", "同时", "", "" },
        { "连接词", "同时", "并且", "应将能可须要会也都还并不没未", "，,。" },
        { "连接词", "此外", "另外", "", "" },
        { "连接词", "另外", "此外", "", "" },
        { "连接词", "而且", "并且", "", "，," },
        { "连接词", "但是", "然而", "", "" },
        { "连接词", "然而", "但是", "", "" },
        { "连接词", "不仅", "不只", "", "" },
        { "连接词", "然后", "随后", "", "" },
        { "语气", "可以", "能够", "", "" },
        { "语气", "能够", "可以", "", "" },
        { "语气", "能够", "能", "", "" },
        { "语气", "需要", "需", "", "" },
        { "语气", "应当", "应该", "", "" },
        { "语气", "大约", "约", "", "" },
        { "语气", "约", "大约", "大约", "束定会" },
        { "词汇", "使用", "采用", "", "者法说手寿命期率量次价" },
        { "词汇", "采用", "使用", "", "率" },
        { "词汇", "运用", "使用", "", "" },
        { "词汇", "利用", "使用", "", "率体" },
        { "词汇", "方法", "方式", "", "学" },
        { "词汇", "方式", "方法", "", "" },
        { "词汇", "提高", "提升", "", "" },
        { "词汇", "提升", "提高", "", "" },
        { "词汇", "表明", "显示", "", "" },
        { "词汇", "显示", "表明", "", "屏器" },
        { "词汇", "显著", "明显", "", "性" },
        { "词汇", "明显", "显著", "", "" },
        { "词汇", "优点", "优势", "", "" },
        { "词汇", "缺点", "不足", "", "" },
        { "词汇", "分为", "划分为", "部", "" },
        { "词汇", "步骤", "流程", "", "" },
    };
    /** English pairs; a capitalized twin of every pair is generated too. */
    private static final String[][] EN_WORDS = {
        { "连接词", "however", "nevertheless" },
        { "连接词", "nevertheless", "however" },
        { "连接词", "moreover", "furthermore" },
        { "连接词", "furthermore", "moreover" },
        { "连接词", "therefore", "thus" },
        { "连接词", "thus", "therefore" },
        { "连接词", "consequently", "accordingly" },
        { "连接词", "additionally", "moreover" },
        { "词汇", "significantly", "considerably" },
        { "词汇", "considerably", "significantly" },
        { "词汇", "significant", "considerable" },
        { "词汇", "considerable", "significant" },
        { "词汇", "numerous", "many" },
        { "词汇", "utilize", "employ" },
        { "词汇", "utilizes", "employs" },
        { "词汇", "utilized", "employed" },
        { "词汇", "utilizing", "employing" },
        { "词汇", "obtain", "acquire" },
        { "词汇", "obtained", "acquired" },
        { "词汇", "demonstrate", "show" },
        { "词汇", "demonstrated", "showed" },
        { "词汇", "suitable", "appropriate" },
        { "词汇", "appropriate", "suitable" },
    };
    /** Multi-word English frames; also generated with a capitalized first word. */
    private static final String[][] EN_PHRASES = {
        { "连接词", "due to", "because of" },
        { "连接词", "because of", "owing to" },
        { "连接词", "owing to", "due to" },
        { "结构", "by using", "using" },
        { "结构", "in order to", "to" },
    };

    private static final String CLAUSE_END = "(?=[\u3002\uFF0C\uFF01\uFF1F\uFF1B\u3001\uFF1A\\n\\r]|$)";
    /** An object may not end on a temporal tail: a trailing 后 would break the rewritten clause. */
    private static final String TAIL_GUARD = "(?<![后前时内中上下间际左右来去之部])";
    /** Comma tolerant span for the 通过X可以Y frame; sentence enders stay forbidden. */
    private static final String SOFT_CHAR = "[^。！？；\n\t 　0-9A-Za-z⟦⟧不没未曾将要会能可行进被把所而却但并且也很更于]";

    private static final Pattern DUI_JINXING = Pattern.compile(
            "(?<![针相伏绝])对(" + HEAD_SPAN + ")" + TAIL_GUARD + "(?:进行了|进行)(" + alternate(DUI_VERBS) + ")" + CLAUSE_END);
    private static final Pattern LE_OBJ = Pattern.compile(
            "(" + alternate(LE_VERBS) + ")了(" + HEAD_SPAN + ")" + TAIL_GUARD + CLAUSE_END);
    private static final Pattern BEI_ACTIVE = Pattern.compile(
            "(" + HEAD_SPAN + ")" + TAIL_GUARD + "被(" + alternate(BEI_VERBS) + ")(了?)");
    private static final Pattern BEI_ADVERB = Pattern.compile(
            "被(?=广泛|大量|普遍|严重|完全|部分|直接|间接|有效|明显|迅速|长期|大幅)");
    private static final Pattern BA_JIANG = Pattern.compile(
            "(?<![一两几车枪刀门话])把(" + SPAN + ")(" + alternate(BA_VERBS) + ")");
    private static final Pattern TONGGUO_KEYI = Pattern.compile(
            "(通过)(" + SOFT_CHAR + "{2,20}?)(可以|能够|可(?![以能]))");
    private static final Pattern TONGGUO_LAI = Pattern.compile(
            "(通过)(" + SOFT_CHAR + "{2,18}?)来(" + alternate(VERB_ANY) + ")");
    private static final Pattern DI_DELETE = Pattern.compile(
            "(" + alternate(ADVERBS) + ")地(" + alternate(VERB_ANY) + ")");
    /** Past passive with a bounded agent: simple past needs no number agreement when the sides swap. */
    private static final Pattern EN_PASSIVE = Pattern.compile(
            "(?:(?<=\\n)|(?<=\\. )|(?<=\u3002)|(?<=\uFF1B)|^)"
                    + "(the|The|a|A|an|An|this|This|these|These)\\s+([A-Za-z][A-Za-z0-9()\\- ]{0,40}?)\\s+(?:was|were)\\s+([A-Za-z]{3,}ed)\\s+by\\s+"
                    + "((?:the\\s+|a\\s+|an\\s+|The\\s+|A\\s+|An\\s+)?[A-Za-z][A-Za-z0-9()\\- ]{0,40}?)"
                    + "(?=[.,;:!?\\n\\r]|\\z)");
    private static final Pattern EN_REDUCED = Pattern.compile(
            "(?<![,.])\\s+(?:which|that)\\s+(?:is|are|was|were)\\s+([A-Za-z]{4,}ed)\\b");

    private static Entry[] buildRules() {
        ArrayList<Entry> rules = new ArrayList<Entry>();
        rules.add(new Entry("结构", new Regex(DUI_JINXING, "$2了$1", false, "")));
        rules.add(new Entry("结构", new Regex(LE_OBJ, "对$2进行了$1", true, "")));
        rules.add(new Entry("结构", new Regex(BEI_ACTIVE, "将$1$2$3", true, "")));
        rules.add(new Entry("结构", new Regex(BEI_ADVERB, "", false, "")));
        rules.add(new Entry("结构", new Regex(BA_JIANG, "将$1$2", false, "")));
        rules.add(new Entry("结构", new Regex(TONGGUO_KEYI, "借助$2$3", true, "")));
        rules.add(new Entry("结构", new Regex(TONGGUO_LAI, "借助$2$3", true, "")));
        rules.add(new Entry("结构", new Regex(DI_DELETE, "$1$2", false, "")));
        rules.add(new Entry("结构", new Regex(EN_REDUCED, " $1", false, "not|never")));
        rules.add(new Entry("词序", new Regex(EN_PASSIVE, "$u4 $3 $l1 $2", false,
                "that|which|who|whom|whose|and|but|because|when|where|while|not|have|has|used")));
        for (String[] pair : COMPARATIVES) {
            rules.add(new Entry("词序", new Regex("(" + HEAD_SPAN + ")" + TAIL_GUARD + pair[0] + "(" + SPAN_CHAR
                    + "{1,12}?)" + CLAUSE_END, "$2" + pair[1] + "$1", true, "")));
            rules.add(new Entry("词序", new Regex("(" + HEAD_SPAN + ")" + TAIL_GUARD + pair[1] + "(" + SPAN_CHAR
                    + "{1,12}?)" + CLAUSE_END, "$2" + pair[0] + "$1", true, "")));
        }
        for (String[] row : LEXICAL)
            rules.add(new Entry(row[0], new Regex(literal(row[1], row[3], row[4]), row[2], false, "")));
        for (String[] row : EN_WORDS) {
            addEnglish(rules, row[0], row[1], row[2]);
            addEnglish(rules, row[0], capitalize(row[1], true), capitalize(row[2], true));
        }
        for (String[] row : EN_PHRASES) {
            addEnglish(rules, row[0], row[1], row[2]);
            addEnglish(rules, row[0], capitalize(row[1], true), capitalize(row[2], true));
        }
        return rules.toArray(new Entry[rules.size()]);
    }

    private static void addEnglish(ArrayList<Entry> rules, String strategy, String from, String to) {
        rules.add(new Entry(strategy, new Regex(phrase(from), to, false, "")));
    }

    private static final Entry[] RULES = buildRules();
    /** Candidate recipes: every rule still fires at most once per candidate. */
    private static final String[][] RECIPES = {
        { "结构", "词序", "连接词", "语气", "词汇" },
        { "结构", "词序" },
        { "连接词", "语气", "词汇" },
    };
    private static final String[] STRATEGY_ORDER = { "结构", "词序", "连接词", "语气", "词汇" };

    private static final class Plan {
        final String working;
        final ArrayList<String> parts;
        Plan(String working, ArrayList<String> parts) { this.working = working; this.parts = parts; }
        String restore(String rewritten) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < rewritten.length(); i++) {
                char c = rewritten.charAt(i);
                if (c >= PH_BASE && c < PH_BASE + PH_CAPACITY) {
                    int index = c - PH_BASE;
                    if (index >= parts.size()) throw new IllegalArgumentException("掩码标记越界");
                    out.append(parts.get(index));
                } else out.append(c);
            }
            return out.toString();
        }
    }

    private static void addMatches(Pattern pattern, String text, ArrayList<int[]> ranges) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) if (matcher.end() > matcher.start()) ranges.add(new int[]{matcher.start(), matcher.end()});
    }

    /** Mask islands, numbers, units, model codes and registered terms become one opaque char each. */
    private static Plan protect(String text, List<String> terms) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= PH_BASE && c < PH_BASE + PH_CAPACITY) return null;
        }
        ArrayList<int[]> ranges = new ArrayList<int[]>();
        addMatches(TOKENS, text, ranges);
        addMatches(ISLANDS, text, ranges);
        if (terms != null) for (String term : terms) {
            if (term == null || term.isEmpty()) continue;
            for (int at = text.indexOf(term); at >= 0; at = text.indexOf(term, at + term.length()))
                ranges.add(new int[]{at, at + term.length()});
        }
        Collections.sort(ranges, new Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return a[0] - b[0]; }
        });
        ArrayList<int[]> merged = new ArrayList<int[]>();
        for (int[] range : ranges) {
            if (range[1] <= range[0]) continue;
            if (!merged.isEmpty() && merged.get(merged.size() - 1)[1] >= range[0])
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], range[1]);
            else merged.add(range);
        }
        if (merged.size() > PH_CAPACITY) return null;
        ArrayList<String> parts = new ArrayList<String>();
        StringBuilder working = new StringBuilder();
        int cursor = 0;
        for (int i = 0; i < merged.size(); i++) {
            int[] range = merged.get(i);
            working.append(text, cursor, range[0]);
            working.append((char) (PH_BASE + i));
            parts.add(text.substring(range[0], range[1]));
            cursor = range[1];
        }
        working.append(text, cursor, text.length());
        return new Plan(working.toString(), parts);
    }

    /** Validation reuses the caller's own semantics: one occurrence per island, in order, no stray marker. */
    private static TextProtection.Mask maskOf(String text) {
        ArrayList<TextProtection.Island> islands = new ArrayList<TextProtection.Island>();
        Matcher matcher = TOKENS.matcher(text);
        while (matcher.find())
            islands.add(new TextProtection.Island(matcher.start(), matcher.end(), matcher.group(), matcher.group()));
        return new TextProtection.Mask(text, text, islands);
    }

    private static boolean islandsIntact(TextProtection.Mask mask, String candidate) {
        try {
            mask.segments(candidate);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int brackets(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == ']' || c == '{' || c == '}') count++;
        }
        return count;
    }

    private static boolean wanted(String[] set, String strategy) {
        for (String item : set) if (item.equals(strategy)) return true;
        return false;
    }

    private static String label(ArrayList<String> used) {
        StringBuilder out = new StringBuilder();
        for (String strategy : STRATEGY_ORDER) {
            if (!used.contains(strategy)) continue;
            if (out.length() > 0) out.append('、');
            out.append(strategy);
        }
        return out.toString();
    }

    private static boolean holds(ArrayList<Option> options, String text) {
        for (Option option : options) if (option.text.equals(text)) return true;
        return false;
    }

    private static void collect(String working, ArrayList<Entry> hits, String[] set,
                                ArrayList<String> texts, ArrayList<String> labels) {
        String current = working;
        ArrayList<String> used = new ArrayList<String>();
        for (Entry entry : hits) {
            if (!wanted(set, entry.strategy) || used.contains(entry.strategy)) continue;
            String next = entry.rule.apply(current);
            if (next == null || next.equals(current)) continue;
            current = next;
            used.add(entry.strategy);
        }
        if (!current.equals(working)) {
            texts.add(current);
            labels.add(label(used));
        }
    }

    public static ArrayList<Option> rewrite(String text, List<String> terms) {
        ArrayList<Option> options = new ArrayList<Option>();
        if (text == null || text.isEmpty()) return options;
        try {
            Plan plan = protect(text, terms);
            if (plan == null) return options;
            ArrayList<Entry> hits = new ArrayList<Entry>();
            for (Entry entry : RULES) if (entry.rule.apply(plan.working) != null) hits.add(entry);
            if (hits.isEmpty()) return options;
            ArrayList<String> texts = new ArrayList<String>();
            ArrayList<String> labels = new ArrayList<String>();
            for (String[] recipe : RECIPES) collect(plan.working, hits, recipe, texts, labels);
            for (Entry entry : hits) {
                String single = entry.rule.apply(plan.working);
                if (single != null && !single.equals(plan.working)) {
                    texts.add(single);
                    labels.add(entry.strategy);
                }
            }
            TextProtection.Mask mask = maskOf(text);
            for (int i = 0; i < texts.size() && options.size() < 3; i++) {
                String candidate = plan.restore(texts.get(i));
                if (candidate.equals(text) || holds(options, candidate)) continue;
                if (!islandsIntact(mask, candidate) || brackets(candidate) > brackets(text)) continue;
                Option option = new Option();
                option.text = candidate;
                option.strategy = labelOf(labels.get(i));
                options.add(option);
            }
        } catch (RuntimeException ignored) {
            return new ArrayList<Option>();
        }
        return options;
    }

    private static String labelOf(String label) {
        return label == null || label.isEmpty() ? "词汇" : label;
    }

    /** True when at least one rule can fire without any registered term, so terms only ever shrink the set. */
    public static boolean applicable(String text) {
        return !rewrite(text, null).isEmpty();
    }
}
